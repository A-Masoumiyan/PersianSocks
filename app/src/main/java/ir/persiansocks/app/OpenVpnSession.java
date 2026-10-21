package ir.persiansocks.app;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One isolated native process, controlled through a private Android management socket. */
public final class OpenVpnSession implements AutoCloseable {
 public interface Listener {
  boolean protect(FileDescriptor socket)throws Exception;
  ParcelFileDescriptor openTun(TunConfig config)throws Exception;
  void connected();void reconnecting();void failed(String message);void bytes(long received,long sent);
 }
 public static final class TunConfig {
  public String ipv4="",ipv6="";public int mtu=1400;public final List<String> dns=new ArrayList<>();
  TunConfig copy(){TunConfig c=new TunConfig();c.ipv4=ipv4;c.ipv6=ipv6;c.mtu=mtu;c.dns.addAll(dns);return c;}
 }
 private final AtomicBoolean closed=new AtomicBoolean(),failure=new AtomicBoolean();
 private final Listener listener;private final Context context;private final VpnGateProfile profile;
 private final Deque<FileDescriptor> receivedFds=new ConcurrentLinkedDeque<>();private final TunConfig tun=new TunConfig();
 private final StringBuilder logs=new StringBuilder();private final ScheduledExecutorService timers=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"openvpn-deadline");t.setDaemon(true);return t;});
 private volatile Process process;private volatile LocalServerSocket server;private volatile LocalSocket bound,management;
 private File configFile,socketFile;private ScheduledFuture<?> deadline;
 public OpenVpnSession(Context c,VpnGateProfile p,Listener listener){context=c.getApplicationContext();profile=p;this.listener=listener;}
 public void start(){Thread t=new Thread(this::run,"openvpn-management");t.setDaemon(true);t.start();}
 public synchronized String report(){return logs.toString();}
 private synchronized void log(String text){if(text.length()>1800)text=text.substring(0,1800);logs.append(text).append('\n');if(logs.length()>9000)logs.delete(0,logs.length()-9000);}
 private void fail(String why){if(!closed.get()&&failure.compareAndSet(false,true)){log(why);listener.failed(why);close();}}
 private void run(){
  try{
   File executable=new File(context.getApplicationInfo().nativeLibraryDir,"libopenvpnexec.so");if(!executable.isFile()||!executable.canExecute())throw new IOException("Bundled OpenVPN executable is unavailable");
   String nonce=UUID.randomUUID().toString().substring(0,8);configFile=new File(context.getFilesDir(),"gate-"+nonce+".ovpn");socketFile=new File(context.getFilesDir(),"gate-"+nonce+".sock");
   try(FileOutputStream out=new FileOutputStream(configFile)){out.write(profile.config.getBytes(StandardCharsets.UTF_8));}
   bound=new LocalSocket();bound.bind(new LocalSocketAddress(socketFile.getAbsolutePath(),LocalSocketAddress.Namespace.FILESYSTEM));server=new LocalServerSocket(bound.getFileDescriptor());
   if(closed.get())return;
   ProcessBuilder pb=new ProcessBuilder(executable.getAbsolutePath(),"--config",configFile.getAbsolutePath(),"--management",socketFile.getAbsolutePath(),"unix","--management-client","--management-query-passwords","--management-hold","--management-signal");pb.redirectErrorStream(true);process=pb.start();
   Process child=process;Thread watcher=new Thread(()->{try{int code=child.waitFor();if(!closed.get())fail("OpenVPN exited ("+code+"): "+tail());}catch(InterruptedException e){Thread.currentThread().interrupt();}},"openvpn-process");watcher.setDaemon(true);watcher.start();
   Thread output=new Thread(()->{try(BufferedReader in=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=in.readLine())!=null&&!closed.get())log(line);}catch(Exception ignored){}},"openvpn-output");output.setDaemon(true);output.start();
   deadline=timers.schedule(()->fail("OpenVPN connection timed out after 40 seconds"),40,TimeUnit.SECONDS);
   management=server.accept();if(management.getPeerCredentials().getUid()!=android.os.Process.myUid())throw new IOException("Unexpected management socket owner");management.setSoTimeout(45000);
   command("version 3\nstate on\nbytecount 2\n");byte[] bytes=new byte[4096];StringBuilder pending=new StringBuilder();
   while(!closed.get()){int n=management.getInputStream().read(bytes);if(n<0)break;FileDescriptor[] fds=management.getAncillaryFileDescriptors();if(fds!=null)for(FileDescriptor fd:fds){if(receivedFds.size()>=8){Os.close(fd);throw new IOException("Too many management descriptors");}receivedFds.add(fd);}pending.append(new String(bytes,0,n,StandardCharsets.UTF_8));if(pending.length()>32768)throw new IOException("Management line is too long");int end;while((end=pending.indexOf("\n"))>=0){String line=pending.substring(0,end).trim();pending.delete(0,end+1);handle(line);}}
   if(!closed.get())fail("OpenVPN process stopped: "+tail());
  }catch(Exception e){fail("OpenVPN: "+e.getMessage()+"\n"+tail());}finally{closed.set(true);cleanup();}
 }
 private synchronized String tail(){String value=logs.toString();return value.substring(Math.max(0,value.length()-1800));}
 private synchronized void command(String command)throws IOException{LocalSocket s=management;if(s==null||closed.get())throw new IOException("OpenVPN management is closed");s.getOutputStream().write(command.getBytes(StandardCharsets.UTF_8));s.getOutputStream().flush();}
 private void protectReceived()throws Exception{FileDescriptor fd=receivedFds.poll();if(fd==null)throw new IOException("Missing upstream socket descriptor");try{if(!listener.protect(fd))throw new IOException("OpenVPN physical socket protection failed");}finally{Os.close(fd);}}
 private void handle(String line)throws Exception{
  if(line.startsWith(">HOLD:")){command("hold release\n");return;}
  if(line.startsWith("PROTECTFD: ")){protectReceived();return;}
  if(line.startsWith(">PASSWORD:")){if(line.contains("Verification Failed"))throw new IOException("VPN Gate authentication failed");if(line.contains("'Auth'")){command("username 'Auth' 'vpn'\npassword 'Auth' 'vpn'\n");return;}throw new IOException("Unsupported OpenVPN credential request");}
  if(line.startsWith(">NEED-OK:")){String arg=line.substring(9);int q=arg.indexOf('\''),end=arg.indexOf('\'',q+1),colon=arg.indexOf(':');if(q<0||end<0||colon<0)throw new IOException("Invalid management request");String name=arg.substring(q+1,end),extra=arg.substring(colon+1).trim(),response="ok";String[] parts=extra.split("\\s+");
   switch(name){
    case "PROTECTFD":protectReceived();break;
    case "DNSSERVER":case "DNS6SERVER":if(tun.dns.size()<4)tun.dns.add(extra);break;
    case "IFCONFIG":if(parts.length<3)throw new IOException("Invalid IPv4 tunnel configuration");tun.ipv4=parts[0];tun.mtu=Math.max(1280,Math.min(1500,Integer.parseInt(parts[2])));break;
    case "IFCONFIG6":tun.ipv6=parts[0];break;
    case "PERSIST_TUN_ACTION":response="OPEN_BEFORE_CLOSE";break;
    case "OPENTUN":
     if(!extra.equals("tun"))throw new IOException("Only TUN devices are supported");
     try(ParcelFileDescriptor fd=listener.openTun(tun.copy())){management.setFileDescriptorsForSend(new FileDescriptor[]{fd.getFileDescriptor()});try{command("needok 'OPENTUN' ok\n");}finally{management.setFileDescriptorsForSend(null);}}return;
    case "ROUTE":case "ROUTE6":case "DNSDOMAIN":break; // Default routes and DNS are installed by our VpnService.
    default:throw new IOException("Unsupported management request: "+name);
   }command("needok '"+name+"' "+response+"\n");return;
  }
  if(line.startsWith(">STATE:")){String[] values=line.substring(7).split(",",4);if(values.length>1){if(values[1].equals("CONNECTED")){if(deadline!=null)deadline.cancel(false);listener.connected();}else if(values[1].equals("RECONNECTING"))listener.reconnecting();}log(line);return;}
  if(line.startsWith(">BYTECOUNT:")){String[] values=line.substring(11).split(",");if(values.length>=2)listener.bytes(Long.parseLong(values[0]),Long.parseLong(values[1]));return;}
  if(line.startsWith(">FATAL:")||line.startsWith(">PASSWORD:Verification Failed"))throw new IOException(line);
  if(line.startsWith("ERROR:"))throw new IOException(line);if(line.startsWith(">LOG:")||line.startsWith(">INFO:"))log(line);
 }
 @Override public void close(){closed.set(true);cleanup();}
 private synchronized void cleanup(){timers.shutdownNow();FileDescriptor fd;while((fd=receivedFds.poll())!=null)try{Os.close(fd);}catch(Exception ignored){}for(LocalSocket s:new LocalSocket[]{management,bound})if(s!=null)try{s.close();}catch(Exception ignored){}if(server!=null)try{server.close();}catch(Exception ignored){}Process p=process;if(p!=null){p.destroy();Thread reaper=new Thread(()->{try{if(!p.waitFor(2,TimeUnit.SECONDS))p.destroyForcibly();}catch(Exception ignored){p.destroyForcibly();}},"openvpn-reaper");reaper.setDaemon(true);reaper.start();}if(configFile!=null)configFile.delete();if(socketFile!=null)socketFile.delete();}
}
