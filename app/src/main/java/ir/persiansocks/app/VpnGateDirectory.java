package ir.persiansocks.app;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;

/** Bounded directory download and TCP reachability only. A port check never means a working VPN. */
public final class VpnGateDirectory implements AutoCloseable {
 public interface Listener {void changed(String status);}
 public final VpnGateStore store;
 private final Context context;private final Listener listener;
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
 private final AtomicInteger generation=new AtomicInteger();private volatile boolean closed,busy;
 public VpnGateDirectory(Context c,Listener l){context=c.getApplicationContext();store=new VpnGateStore(c.getFilesDir());listener=l;}
 public boolean busy(){return busy;}
 public void load(){worker.execute(()->{try{store.load();}catch(Exception ignored){}changed("");});}
 private void changed(String message){if(!closed)listener.changed(message);}
 private byte[] download(String address,int maximum,int token)throws Exception{
  if(closed||token!=generation.get())throw new IOException("Cancelled");
  HttpURLConnection connection=AppHttp.sourceConnection(new URL(address));connection.setConnectTimeout(8000);connection.setReadTimeout(12000);
  try{if(connection.getResponseCode()!=200)throw new IOException("Directory HTTP "+connection.getResponseCode());if(connection.getContentLengthLong()>maximum)throw new IOException("Directory too large");
   try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(closed||token!=generation.get())throw new IOException("Cancelled");if(out.size()+n>maximum)throw new IOException("Directory too large");out.write(buffer,0,n);}return out.toByteArray();}
  }finally{connection.disconnect();}
 }
 public void refresh(){if(busy||closed)return;busy=true;int token=generation.incrementAndGet();changed(AppLanguage.t(R.string.ui_032_gate_loading));worker.execute(()->{
  try{List<VpnGateProfile> profiles;
   try{profiles=VpnGateProfile.parse(new String(download("https://www.vpngate.net/api/iphone/",VpnGateProfile.MAX_DIRECTORY_BYTES,token),StandardCharsets.UTF_8));}
   catch(Exception official){JSONObject index=new JSONObject(new String(download("https://raw.githubusercontent.com/GeorgeXie2333/vpngate-list-mirror/main/latest.json",32768,token),StandardCharsets.UTF_8));String commit=index.getString("data_commit");if(index.getInt("schema_version")!=1||!commit.matches("[a-f0-9]{40}"))throw new IOException("Invalid directory index");JSONObject file=index.getJSONObject("files").getJSONObject("data/vpngate.csv");long size=file.getLong("bytes");String expected=file.getString("sha256");if(size<1||size>VpnGateProfile.MAX_DIRECTORY_BYTES||!expected.matches("[a-f0-9]{64}"))throw new IOException("Invalid directory size/hash");byte[] csv;
    try{csv=download("https://raw.githubusercontent.com/GeorgeXie2333/vpngate-list-mirror/"+commit+"/data/vpngate.csv",(int)size,token);}catch(Exception raw){csv=download("https://cdn.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@"+commit+"/data/vpngate.csv",(int)size,token);}
    StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(csv))hash.append(String.format(Locale.US,"%02x",b&255));if(csv.length!=size||!expected.contentEquals(hash))throw new IOException("Directory checksum mismatch");profiles=VpnGateProfile.parse(new String(csv,StandardCharsets.UTF_8));
   }
   if(!closed&&token==generation.get()){store.replace(profiles);changed(AppLanguage.t(R.string.ui_032_gate_count)+profiles.size());}
  }catch(Exception e){if(token==generation.get())changed(AppLanguage.t(R.string.ui_032_gate_error)+e.getMessage());}finally{if(token==generation.get()){busy=false;changed("");}}
 });}
 public void scan(){if(busy||closed)return;busy=true;int token=generation.incrementAndGet();List<VpnGateProfile> profiles=store.snapshot();changed(AppLanguage.t(R.string.ui_032_gate_scanning));worker.execute(()->{
  ExecutorService pool=Executors.newFixedThreadPool(8);AtomicInteger checked=new AtomicInteger();
  try{PhysicalTestNetwork path=PhysicalTestNetwork.capture(context);List<Future<?>> jobs=new ArrayList<>();for(VpnGateProfile p:profiles){if(!p.tcp)continue;jobs.add(pool.submit(()->{if(closed||token!=generation.get())return;long started=System.nanoTime(),latency=-1;String error="";
    try(Socket socket=path.socket()){sockets.add(socket);try{socket.connect(new InetSocketAddress(p.ip,p.port),1500);if(!path.valid())throw new IOException("Network changed");latency=Math.max(1,(System.nanoTime()-started)/1_000_000);}finally{sockets.remove(socket);}}catch(Exception e){error=String.valueOf(e.getMessage());}if(!closed&&token==generation.get()){store.tested(p.key(),latency,error);int count=checked.incrementAndGet();if(count%8==0)changed(AppLanguage.t(R.string.ui_032_gate_ports)+count);}
   }));}for(Future<?> job:jobs){if(token!=generation.get()||closed)break;job.get();}if(!closed&&token==generation.get()){store.save();changed(AppLanguage.t(R.string.ui_032_gate_scan_done));}
  }catch(Exception e){if(token==generation.get())changed(AppLanguage.t(R.string.ui_032_gate_error)+e.getMessage());}finally{pool.shutdownNow();if(token==generation.get()){busy=false;changed("");}}
 });}
 public void stop(){generation.incrementAndGet();busy=false;for(Socket s:sockets)try{s.close();}catch(Exception ignored){}sockets.clear();changed(AppLanguage.t(R.string.ui_032_gate_stopped));}
 @Override public void close(){closed=true;stop();worker.shutdownNow();}
}
