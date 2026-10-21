package ir.persiansocks.app;
import android.app.*;
import android.content.*;
import android.os.*;
import android.net.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import hev.htproxy.TProxyService;
/** Debug-only native fixture; never packaged into the signed release. */
public final class ParityInstrumentation extends Instrumentation {
 @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
 private String stage="initializing",lastSocketError="";
 private void check(boolean value,String label){if(!value)throw new AssertionError(label);}
 private void waitFor(java.util.function.BooleanSupplier condition)throws Exception{long until=SystemClock.elapsedRealtime()+15_000;while(!condition.getAsBoolean()&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);check(condition.getAsBoolean(),"Timed out at "+stage+"; "+lastSocketError);}
 // A tun2socks stack may acknowledge SYN before opening its upstream socket.
 // Prove an actual peer round trip, rather than mistaking local TCP for a leak.
 private boolean physical(){try(Socket s=new Socket()){s.connect(new InetSocketAddress("10.0.2.2",18080),1500);s.setSoTimeout(1500);s.getOutputStream().write("GET / HTTP/1.0\r\nHost: 10.0.2.2\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));s.getOutputStream().flush();String line=new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(),java.nio.charset.StandardCharsets.US_ASCII)).readLine();return line!=null&&line.startsWith("HTTP/")&&line.contains(" 200 ");}catch(Exception e){lastSocketError=e.toString();return false;}}
 private boolean gatePeer(){try(Socket s=new Socket()){s.connect(new InetSocketAddress("10.77.0.1",18080),1500);s.setSoTimeout(1500);s.getOutputStream().write("GET / HTTP/1.0\r\nHost: local.fixture\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));String line=new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream())).readLine();return line!=null&&line.contains(" 200 ");}catch(Exception e){lastSocketError=e.toString();return false;}}
 private Object descriptor()throws Exception {java.lang.reflect.Field owner=ProxyVpnService.class.getDeclaredField("instance");owner.setAccessible(true);Object service=((java.lang.ref.WeakReference<?>)owner.get(null)).get();java.lang.reflect.Field field=ProxyVpnService.class.getDeclaredField("tun");field.setAccessible(true);return field.get(service);}
 @Override public void onStart(){Bundle report=new Bundle();CountDownLatch release=new CountDownLatch(1);try{
  Context c=getTargetContext();c.getSharedPreferences("persiansocks",0).edit().putString("routing-mode","apps").putString("split-mode","ALL").putInt("vpn-disclosure",DistributionPolicy.DISCLOSURE_VERSION).putBoolean("kill-switch-off",false).putBoolean("auto-switch",false).putBoolean("initial-discovery-030",true).putBoolean("background-maintenance",false).commit();
  check(VpnService.prepare(c)==null,"Fixture requires Android VPN app-op consent");
  stage="physical baseline";waitFor(this::physical);
  stage="startup preview";ProxyEndpoint snapshot=new ProxyEndpoint("127.0.0.1",1,20,false,1,1,System.currentTimeMillis());QuickStartCache.save(c,Collections.singletonList(snapshot));
  ProxyStore.IO.execute(()->{try{release.await(25,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
  long start=SystemClock.elapsedRealtime();Activity screen=startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  java.lang.reflect.Field chosen=MainActivity.class.getDeclaredField("selected");chosen.setAccessible(true);check(chosen.get(screen)!=null,"Preview/selection waits for full storage");
  report.putLong("startup_preview_ms",SystemClock.elapsedRealtime()-start);
  stage="initial guard";runOnMainSync(()->c.startForegroundService(new Intent(c,ProxyVpnService.class).setAction(ProxyVpnService.RECONFIGURE)));waitFor(ProxyVpnService::guardActive);Object initialDescriptor=descriptor();
  stage="native engine";runOnMainSync(()->c.startForegroundService(new Intent(c,ProxyVpnService.class).setAction(ProxyVpnService.CONNECT).putExtra(ProxyVpnService.EXTRA_ENDPOINT,snapshot.key()).putExtra("filter","ALL").putExtra("sort","FASTEST").putExtra("evidence",snapshot.json().toString())));
  waitFor(()->{try{return TProxyService.TProxyIsRunning();}catch(Throwable e){return false;}});release.countDown();
  check(initialDescriptor==descriptor(),"Connecting replaced the existing guard interface");
  ConnectivityManager manager=c.getSystemService(ConnectivityManager.class);boolean v4=false,v6=false;
  for(Network n:manager.getAllNetworks()){NetworkCapabilities caps=manager.getNetworkCapabilities(n);if(caps==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))continue;LinkProperties lp=manager.getLinkProperties(n);if(lp!=null)for(RouteInfo route:lp.getRoutes())if(route.isDefaultRoute()){v4|=route.getDestination().getAddress() instanceof java.net.Inet4Address;v6|=route.getDestination().getAddress() instanceof java.net.Inet6Address;}}
  check(v4&&v6,"Guard must capture both default address families");
  // Native start means the worker was spawned, not that Android has installed
  // this UID's default route. Establish that precondition before testing stop.
  stage="VPN default route";waitFor(()->{Network active=manager.getActiveNetwork();NetworkCapabilities caps=active==null?null:manager.getNetworkCapabilities(active);lastSocketError="active="+active+" caps="+caps;return caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);});
  check(!physical(),"Direct IPv4 bypassed running engine");
  runOnMainSync(()->c.startService(new Intent(c,ProxyVpnService.class).setAction(ProxyVpnService.DISCONNECT)));
  stage="retained guard";waitFor(()->ProxyVpnService.guardActive()&&!TProxyService.TProxyIsRunning());for(int i=0;i<3;i++){Network active=manager.getActiveNetwork();check(!physical(),"Direct IPv4 escaped after engine disconnect; active="+active+" caps="+(active==null?null:manager.getNetworkCapabilities(active)));Thread.sleep(250);}
  c.getSharedPreferences("persiansocks",0).edit().putBoolean("kill-switch-off",true).commit();runOnMainSync(()->c.startService(new Intent(c,ProxyVpnService.class).setAction(ProxyVpnService.RELEASE_GUARD)));
  stage="release guard";waitFor(()->!ProxyVpnService.guardActive());waitFor(this::physical);
  stage="OpenVPN local TLS/management";runOnMainSync(()->c.startForegroundService(new Intent(c,GateFixtureService.class).setAction("START")));waitFor(()->{lastSocketError=GateFixtureService.failure;return GateFixtureService.state.equals("CONNECTED")||GateFixtureService.state.equals("FAILED");});check(GateFixtureService.state.equals("CONNECTED"),"OpenVPN handshake failed: "+GateFixtureService.failure);check(GateFixtureService.protectedSockets>0,"OpenVPN upstream FD was not protected");
  stage="OpenVPN default route";waitFor(()->{Network n=manager.getActiveNetwork();NetworkCapabilities caps=n==null?null:manager.getNetworkCapabilities(n);return caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);});
  stage="OpenVPN HTTP roundtrip";waitFor(()->gatePeer());
  runOnMainSync(()->c.startService(new Intent(c,GateFixtureService.class).setAction("STOP")));stage="OpenVPN retained guard";waitFor(()->GateFixtureService.state.equals("GUARD"));Thread.sleep(400);check(!gatePeer()&&!physical(),"OpenVPN stop leaked physical traffic");
  runOnMainSync(()->c.startService(new Intent(c,GateFixtureService.class).setAction("OFF")));stage="OpenVPN OFF";waitFor(this::physical);
  report.putString("stream","PARITY_NATIVE_PASS: OpenVPN TLS handshake, protected upstream FD, actual TUN HTTP roundtrip, retained guard and OFF; first-screen preview while storage blocked; native JNI engine; IPv4/IPv6 default routes; no direct IPv4 after engine disconnect; OFF restores traffic\n");finish(Activity.RESULT_OK,report);
 }catch(Throwable error){report.putString("stream","PARITY_NATIVE_FAIL: "+error+"\n");finish(Activity.RESULT_CANCELED,report);}finally{release.countDown();}}
}
