package ir.persiansocks.app;
import android.app.*;import android.content.*;import android.net.*;import android.os.*;import java.io.*;import java.net.*;import java.util.concurrent.*;
/** Deterministic local OpenVPN peer; debug-only and never part of the release. */
public final class GateFixtureService extends VpnService {
 static volatile String state="OFF",failure="";static volatile int protectedSockets;static volatile GateFixtureService instance;
 private OpenVpnSession session;private ParcelFileDescriptor tun;
 @Override public void onCreate(){super.onCreate();instance=this;NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("gate-fixture","Local OpenVPN fixture",NotificationManager.IMPORTANCE_LOW));startForeground(924,new Notification.Builder(this,"gate-fixture").setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("Local OpenVPN fixture").build());}
 @Override public int onStartCommand(Intent intent,int flags,int id){if("STOP".equals(intent.getAction())){if(session!=null)session.close();state="GUARD";return START_NOT_STICKY;}if("OFF".equals(intent.getAction())){if(session!=null)session.close();try{if(tun!=null)tun.close();}catch(Exception ignored){}tun=null;state="OFF";stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;}
  state="STARTING";failure="";new Thread(()->{try{
   PhysicalTestNetwork path=PhysicalTestNetwork.capture(this);String ca;
   try(InputStream in=path.network().openConnection(new URL("http://10.0.2.2:18080/ca.crt")).getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>32768)throw new IOException("CA too large");out.write(b,0,n);}ca=out.toString("UTF-8");}
   VpnGateProfile base=VpnGateProfile.sanitize("8.8.8.8","JP",1,"client\ndev tun\nproto tcp\nremote 8.8.8.8 11940\ncipher AES-128-GCM\nauth SHA256\n<ca>\n"+ca+"</ca>\n");
   java.lang.reflect.Constructor<VpnGateProfile> constructor=VpnGateProfile.class.getDeclaredConstructor(String.class,String.class,int.class,boolean.class,long.class,String.class);constructor.setAccessible(true);VpnGateProfile profile=constructor.newInstance("10.0.2.2","JP",11940,true,1L,base.config.replace("remote 8.8.8.8 11940","remote 10.0.2.2 11940"));
   session=new OpenVpnSession(this,profile,new OpenVpnSession.Listener(){
    public boolean protect(FileDescriptor fd)throws Exception{try(ParcelFileDescriptor copy=ParcelFileDescriptor.dup(fd)){if(!GateFixtureService.this.protect(copy.getFd()))return false;}path.network().bindSocket(fd);protectedSockets++;return true;}
    public ParcelFileDescriptor openTun(OpenVpnSession.TunConfig config)throws Exception{Builder b=new Builder().setSession("OpenVPN fixture").setBlocking(false).setMtu(config.mtu).addAddress(config.ipv4,32).addAddress("fd00:1::2",64).addRoute("0.0.0.0",0).addRoute("::",0).addDnsServer("10.77.0.1").setUnderlyingNetworks(new Network[]{path.network()});tun=b.establish();if(tun==null)throw new IOException("TUN unavailable");return ParcelFileDescriptor.dup(tun.getFileDescriptor());}
    public void connected(){state="CONNECTED";}public void reconnecting(){state="RECONNECTING";}public void failed(String error){failure=error;state="FAILED";}public void bytes(long in,long out){}
   });session.start();
  }catch(Exception e){failure=e.toString();state="FAILED";}},"gate-fixture").start();return START_NOT_STICKY;
 }
 @Override public void onDestroy(){if(session!=null)session.close();try{if(tun!=null)tun.close();}catch(Exception ignored){}instance=null;super.onDestroy();}
}
