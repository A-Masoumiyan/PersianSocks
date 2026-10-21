package ir.persiansocks.app;
import android.app.*;import android.content.*;import android.net.*;import android.os.*;import java.io.*;import java.net.*;import java.util.concurrent.*;
/** Deterministic local OpenVPN peer; debug-only and never part of the release. */
public final class GateFixtureService extends VpnService {
 static volatile String state="OFF",failure="";static volatile int protectedSockets;static volatile GateFixtureService instance;
 private OpenVpnSession session;private ParcelFileDescriptor tun,guard;
 @Override public void onCreate(){super.onCreate();instance=this;NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("gate-fixture","Local OpenVPN fixture",NotificationManager.IMPORTANCE_LOW));startForeground(924,new Notification.Builder(this,"gate-fixture").setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("Local OpenVPN fixture").build());}
 @Override public int onStartCommand(Intent intent,int flags,int id){if("STOP".equals(intent.getAction())){if(session!=null)session.close();state="GUARD";return START_NOT_STICKY;}if("OFF".equals(intent.getAction())){if(session!=null)session.close();try{if(tun!=null)tun.close();}catch(Exception ignored){}tun=null;try{if(guard!=null)guard.close();}catch(Exception ignored){}guard=null;state="OFF";stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;}
  state="STARTING";failure="";new Thread(()->{try{
   PhysicalTestNetwork path=PhysicalTestNetwork.capture(this);String[] pem=new String[3];String[] files={"ca.crt","client.crt","client.key"};
   for(int i=0;i<files.length;i++)pem[i]=readFixture(path,files[i]);
   guard=new Builder().setSession("OpenVPN pre-connect guard").setBlocking(false).addAddress("198.18.0.1",15).addAddress("fd00:1::2",64).addRoute("0.0.0.0",0).addRoute("::",0).addDnsServer("198.18.0.2").setUnderlyingNetworks(new Network[]{path.network()}).establish();
   VpnGateProfile base=VpnGateProfile.sanitize("8.8.8.8","JP",1,"client\ndev tun\nproto tcp\nremote 8.8.8.8 11940\ncipher AES-128-GCM\nauth SHA256\n<ca>\n"+pem[0]+"</ca>\n<cert>\n"+pem[1]+"</cert>\n<key>\n"+pem[2]+"</key>\n");
   java.lang.reflect.Constructor<VpnGateProfile> constructor=VpnGateProfile.class.getDeclaredConstructor(String.class,String.class,int.class,boolean.class,long.class,String.class);constructor.setAccessible(true);VpnGateProfile profile=constructor.newInstance("10.0.2.2","JP",11940,true,1L,base.config.replace("remote 8.8.8.8 11940","remote 10.0.2.2 11940"));
   session=new OpenVpnSession(this,profile,new OpenVpnSession.Listener(){
    public boolean protect(FileDescriptor fd)throws Exception{try(ParcelFileDescriptor copy=ParcelFileDescriptor.dup(fd)){if(!GateFixtureService.this.protect(copy.getFd()))return false;}path.network().bindSocket(fd);protectedSockets++;return true;}
    public ParcelFileDescriptor openTun(OpenVpnSession.TunConfig config)throws Exception{Builder b=new Builder().setSession("OpenVPN fixture").setBlocking(false).setMtu(config.mtu).addAddress(config.ipv4,32).addAddress("fd00:1::2",64).addRoute("0.0.0.0",0).addRoute("::",0).addDnsServer("10.77.0.1").setUnderlyingNetworks(new Network[]{path.network()});tun=b.establish();if(tun==null)throw new IOException("TUN unavailable");new Handler(Looper.getMainLooper()).post(()->retireGuard(0));return ParcelFileDescriptor.dup(tun.getFileDescriptor());}
    public void connected(){state="CONNECTED";}public void reconnecting(){state="RECONNECTING";}public void failed(String error){failure=error;state="FAILED";}public void bytes(long in,long out){}
   });session.start();
  }catch(Exception e){failure=e.toString();state="FAILED";}},"gate-fixture").start();return START_NOT_STICKY;
 }
 private String readFixture(PhysicalTestNetwork path,String name)throws Exception{
  try(Socket socket=new Socket()){if(!protect(socket))throw new IOException("Fixture socket protection failed");path.bind(socket);socket.connect(new InetSocketAddress("10.0.2.2",18080),3000);socket.setSoTimeout(3000);socket.getOutputStream().write(("GET /"+name+" HTTP/1.0\r\nHost: local.fixture\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
   InputStream in=new BufferedInputStream(socket.getInputStream());ByteArrayOutputStream headers=new ByteArrayOutputStream();int value;while((value=in.read())!=-1){headers.write(value);if(headers.size()>8192)throw new IOException("Fixture headers too large");String h=headers.toString("US-ASCII");if(h.endsWith("\r\n\r\n"))break;}if(!headers.toString("US-ASCII").startsWith("HTTP/1.0 200"))throw new IOException("Fixture HTTP failed");ByteArrayOutputStream body=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(body.size()+n>32768)throw new IOException("Fixture PEM too large");body.write(buffer,0,n);}return body.toString("UTF-8");
  }
 }
 private void retireGuard(int attempt){if(tun==null||guard==null)return;String name=TunLinkInfo.name(tun);ConnectivityManager cm=getSystemService(ConnectivityManager.class);for(Network network:cm.getAllNetworks()){LinkProperties lp=cm.getLinkProperties(network);if(!name.isEmpty()&&lp!=null&&name.equals(lp.getInterfaceName())){try{guard.close();}catch(Exception ignored){}guard=null;return;}}if(attempt<30)new Handler(Looper.getMainLooper()).postDelayed(()->retireGuard(attempt+1),200);}
 @Override public void onDestroy(){if(session!=null)session.close();try{if(tun!=null)tun.close();}catch(Exception ignored){}try{if(guard!=null)guard.close();}catch(Exception ignored){}instance=null;super.onDestroy();}
}
