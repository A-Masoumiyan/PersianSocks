package ir.pathfinder.b;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import hev.htproxy.TProxyService;

/** Only starts a TUN after a real external SOCKS5 relay is verified. */
public final class PathVpnService extends VpnService {
  static final String CONNECT="ir.pathfinder.b.CONNECT",STOP="ir.pathfinder.b.STOP";
  static final String STATUS="ir.pathfinder.b.STATUS";
  static final String HOST="host",PORT="port",USER="user",PASS="password";
  private static final int ID=502;
  private static final String CH="pathfinder-connection";
  private volatile ParcelFileDescriptor tun;
  private volatile boolean active;
  private Thread monitor;
  private volatile int generation;
  @Override public void onCreate(){
    super.onCreate();
    getSystemService(NotificationManager.class).createNotificationChannel(
      new NotificationChannel(CH,"PathFinder VPN",NotificationManager.IMPORTANCE_LOW));
  }
  private Notification notification(String message){
    PendingIntent pending=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),
      PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this,CH).setSmallIcon(android.R.drawable.stat_sys_download_done)
      .setContentTitle("Project B / PathFinder").setContentText(message)
      .setContentIntent(pending).setOngoing(true).build();
  }
  private void state(String text){
    getSharedPreferences("pathfinder",MODE_PRIVATE).edit().putString("status",text).apply();
    sendBroadcast(new Intent(STATUS).setPackage(getPackageName()).putExtra("message",text));
  }
  @Override public int onStartCommand(Intent intent,int flags,int startId){
    if(intent==null)return START_NOT_STICKY;
    if(STOP.equals(intent.getAction())){shutdown();state("VPN قطع شد");stopSelf();return START_NOT_STICKY;}
    if(!CONNECT.equals(intent.getAction()))return START_NOT_STICKY;
    if(VpnService.prepare(this)!=null){state("اجازه VPN صادر نشده");stopSelf();return START_NOT_STICKY;}
    String host=intent.getStringExtra(HOST);
    int port=intent.getIntExtra(PORT,0);
    String user=intent.getStringExtra(USER),pass=intent.getStringExtra(PASS);
    if(host==null||port<=0||port>65535){stopSelf();return START_NOT_STICKY;}
    try {
      if(Build.VERSION.SDK_INT>=34)startForeground(ID,notification("تأیید رله…"),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
      else startForeground(ID,notification("تأیید رله…"));
    }catch(Throwable e){state("اعلان VPN فعال نشد: "+e.getMessage());stopSelf();return START_NOT_STICKY;}
    int myGen=++generation;
    RelayProbe.Entry entry=new RelayProbe.Entry(host,port,user==null?"":user,pass==null?"":pass);
    new Thread(()->connect(entry,myGen),"pathfinder-vpn").start();
    return START_STICKY;
  }
  private void connect(RelayProbe.Entry entry,int token){
    try {
      shutdownEngine();
      ConnectivityManager mgr=getSystemService(ConnectivityManager.class);
      Network physical=null;
      if(mgr!=null)for(Network network:mgr.getAllNetworks()){
        NetworkCapabilities c=mgr.getNetworkCapabilities(network);
        if(c!=null&&!c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
             &&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)){
          physical=network;break;
        }
      }
      Inet4Address ip=RelayProbe.verify(physical,entry);
      if(token!=generation)return;
      Builder b=new Builder().setSession("PathFinder B / verified relay")
        .setMtu(1400).setBlocking(false)
        .addAddress("198.18.0.1",15).addDnsServer("198.18.0.2")
        .addAddress("fd00:1:fd00:1::2",64).addRoute("::",0);
      if(physical!=null)b.setUnderlyingNetworks(new Network[]{physical});
      // Native proxy sockets cannot call VpnService.protect().
      // Route all IPv4 addresses except this verified upstream IPv4 into TUN.
      addRoutesExcept(b,ip);
      tun=b.establish();
      if(tun==null)throw new IOException("TUN permission was denied");
      String config="tunnel:\n"+
        "  mtu: 1400\n"+
        "  ipv4: 198.18.0.1\n"+
        "  ipv6: 'fd00:1:fd00:1::2'\n"+
        "  multi-queue: false\n"+
        "socks5:\n"+
        "  address: '"+ip.getHostAddress()+"'\n"+
        "  port: "+entry.port+"\n"+
        "  username: '"+yaml(entry.user)+"'\n"+
        "  password: '"+yaml(entry.pass)+"'\n"+
        "  udp: 'udp'\n"+
        "mapdns:\n"+
        "  address: 198.18.0.2\n"+
        "  port: 53\n"+
        "  network: 198.19.0.0\n"+
        "  netmask: 255.255.0.0\n"+
        "  cache-size: 1024\n"+
        "misc:\n"+
        "  connect-timeout: 12000\n"+
        "  tcp-read-write-timeout: 45000\n"+
        "  log-level: warn\n";
      File cfg=new File(getFilesDir(),"pathfinder-vpn.yml");
      try(FileOutputStream out=new FileOutputStream(cfg)){out.write(config.getBytes(StandardCharsets.UTF_8));}
      if(!TProxyService.TProxyStartService(cfg.getAbsolutePath(),tun.getFd()))
        throw new IOException("Native TUN engine could not start");
      active=true;
      state("تونل راه‌اندازی شد: "+entry.label()+"؛ عملکرد مرورگر را هم بررسی کنید");
      getSystemService(NotificationManager.class).notify(ID,notification("تونل: "+entry.label()));
      monitor=new Thread(()->{
        while(active&&token==generation)try{
          Thread.sleep(1250);
          if(!TProxyService.TProxyIsRunning()){
            state("موتور VPN متوقف شد");shutdown();stopSelf();return;
          }
        }catch(InterruptedException e){return;}
         catch(Throwable e){state("خطا در موتور VPN");shutdown();stopSelf();return;}
      },"pathfinder-monitor");
      monitor.start();
    }catch(Throwable error){
      shutdown();
      state("اتصال ناموفق: "+error.getClass().getSimpleName()+": "+error.getMessage());
      stopSelf();
    }
  }
  private static String yaml(String s){
    return s==null?"":s.replace("'","''").replace("\r","").replace("\n","");
  }
  private static void addRoutesExcept(Builder b,Inet4Address address)throws Exception {
    byte[] raw=address.getAddress();
    long ip=((long)(raw[0]&255)<<24)|((long)(raw[1]&255)<<16)|((long)(raw[2]&255)<<8)|(raw[3]&255);
    for(int bit=31;bit>=0;bit--){
      long lower=(1L<<(bit+1))-1L;
      long network=(ip&~lower)|((ip^(1L<<bit))&(1L<<bit));
      b.addRoute(((network>>24)&255)+"."+((network>>16)&255)+"."+
        ((network>>8)&255)+"."+(network&255),32-bit);
    }
  }
  private synchronized void shutdownEngine(){
    active=false;
    if(monitor!=null){monitor.interrupt();monitor=null;}
    try{TProxyService.TProxyStopService();}catch(Throwable ignored){}
    if(tun!=null){try{tun.close();}catch(Exception ignored){}tun=null;}
    new File(getFilesDir(),"pathfinder-vpn.yml").delete();
  }
  private void shutdown(){generation++;shutdownEngine();stopForeground(STOP_FOREGROUND_REMOVE);}
  @Override public void onRevoke(){shutdown();state("VPN مجوز خود را از دست داد");stopSelf();super.onRevoke();}
  @Override public void onDestroy(){shutdown();super.onDestroy();}
}