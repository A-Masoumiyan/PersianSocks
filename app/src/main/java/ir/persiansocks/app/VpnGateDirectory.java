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

/** Bounded hash-pinned catalog discovery. All connection scanning belongs to ProxyVpnService. */
public final class VpnGateDirectory implements AutoCloseable {
 public interface Listener {void changed(String status);}
 public final VpnGateStore store;
 private int connectTimeout=8000,readTimeout=12000;
 private final Context context;private final Listener listener;
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final Set<HttpURLConnection> connections=ConcurrentHashMap.newKeySet();
 private final AtomicInteger generation=new AtomicInteger();private volatile boolean closed,busy;
 public VpnGateDirectory(Context c,Listener l){context=c.getApplicationContext();store=new VpnGateStore(c.getFilesDir());listener=l;}
 public boolean busy(){return busy;}
 public void load(){worker.execute(()->{try{store.load();}catch(Exception ignored){}changed("");});}
 private void changed(String message){if(!closed)listener.changed(message);}
 private byte[] download(String address,int maximum,int token)throws Exception{
  if(closed||token!=generation.get())throw new IOException("Cancelled");
  HttpURLConnection connection=AppHttp.sourceConnection(new URL(address));connections.add(connection);connection.setConnectTimeout(connectTimeout);connection.setReadTimeout(readTimeout);
  try{if(connection.getResponseCode()!=200)throw new IOException("Directory HTTP "+connection.getResponseCode());if(connection.getContentLengthLong()>maximum)throw new IOException("Directory too large");
   try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(closed||token!=generation.get())throw new IOException("Cancelled");if(out.size()+n>maximum)throw new IOException("Directory too large");out.write(buffer,0,n);}return out.toByteArray();}
  }finally{connections.remove(connection);connection.disconnect();}
 }
 private static final String MIRROR="https://raw.githubusercontent.com/GeorgeXie2333/vpngate-list-mirror/";
 private byte[] pinned(String commit,String path,int size,String hash,int token)throws Exception{
  byte[] bytes;try{bytes=download(MIRROR+commit+"/"+path,size,token);}catch(Exception e){bytes=download("https://cdn.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@"+commit+"/"+path,size,token);}VpnGateProfile.checksum(bytes,size,hash);return bytes;
 }
 public void scanDownloads(boolean quick){if(quick){connectTimeout=3500;readTimeout=4500;}}
 public VpnGateProfile resolve(VpnGateProfile profile)throws Exception{return profile.lazy()?profile.resolve(pinned(profile.poolCommit,"pool/configs/"+profile.configHash+".json",profile.documentBytes,profile.documentHash,generation.get())):profile;}
 public static VpnGateProfile resolve(Context context,VpnGateProfile profile)throws Exception{
  if(!profile.lazy())return profile;try(VpnGateDirectory directory=new VpnGateDirectory(context,message->{})){return profile.resolve(directory.pinned(profile.poolCommit,"pool/configs/"+profile.configHash+".json",profile.documentBytes,profile.documentHash,directory.generation.get()));}
 }
 public void refresh(){if(busy||closed||ProxyVpnService.gateScanning())return;busy=true;int token=generation.incrementAndGet();changed(AppLanguage.t(R.string.ui_032_gate_loading));worker.execute(()->{
  List<VpnGateProfile> profiles=new ArrayList<>();String error="";
  try{profiles.addAll(VpnGateProfile.parse(new String(download("https://www.vpngate.net/api/iphone/",VpnGateProfile.MAX_DIRECTORY_BYTES,token),StandardCharsets.UTF_8)));}catch(Exception e){error=String.valueOf(e.getMessage());}
  try{
   byte[] indexBytes;try{indexBytes=download(MIRROR+"main/pool/latest.json",32768,token);}catch(Exception blocked){indexBytes=download("https://cdn.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@main/pool/latest.json",32768,token);}JSONObject index=new JSONObject(new String(indexBytes,StandardCharsets.UTF_8));String commit=index.getString("data_commit");if(index.getInt("schema_version")!=1||!index.getString("kind").equals("vpngate-pool")||!commit.matches("[a-f0-9]{40}"))throw new IOException("Invalid pool index");
   JSONObject file=index.getJSONObject("files").getJSONObject("pool/servers.json");int size=file.getInt("bytes");if(size<1||size>16*1024*1024)throw new IOException("Invalid pool size");
   JSONObject pool=new JSONObject(new String(pinned(commit,"pool/servers.json",size,file.getString("sha256"),token),StandardCharsets.UTF_8));if(pool.getInt("schema_version")!=1||!pool.getString("kind").equals("vpngate-pool"))throw new IOException("Invalid pool catalog");
   org.json.JSONArray rows=pool.getJSONArray("servers");for(int i=0;i<Math.min(rows.length(),VpnGateProfile.MAX_PROFILES);i++){
    if(closed||token!=generation.get())throw new IOException("Cancelled");
    try{VpnGateProfile p=VpnGateProfile.candidate(rows.getJSONObject(i),commit);profiles.add(p);}catch(Exception invalid){/* Invalid or stale rows do not discard the verified catalog. */}
   }
  }catch(Exception e){error=String.valueOf(e.getMessage());}
  try{if(!closed&&token==generation.get()){if(profiles.isEmpty())throw new IOException(error);store.replace(profiles);changed(AppLanguage.t(R.string.ui_032_gate_count)+store.snapshot().size());}}
  catch(Exception e){if(token==generation.get())changed(AppLanguage.t(R.string.ui_032_gate_error)+e.getMessage());}
  finally{if(token==generation.get()){busy=false;changed("");}}
 });}
 public void stop(){generation.incrementAndGet();busy=false;for(HttpURLConnection connection:connections)connection.disconnect();connections.clear();changed(AppLanguage.t(R.string.ui_032_gate_stopped));}
 @Override public void close(){closed=true;stop();worker.shutdownNow();}
}
