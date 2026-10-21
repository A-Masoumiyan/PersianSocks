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
 private void check(boolean value,String label){if(!value)throw new AssertionError(label);}
 private void waitFor(java.util.function.BooleanSupplier condition)throws Exception{long until=SystemClock.elapsedRealtime()+15_000;while(!condition.getAsBoolean()&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);check(condition.getAsBoolean(),"Timed out waiting for native state");}
 private boolean physical(){try(Socket s=new Socket()){s.connect(new InetSocketAddress("10.0.2.2",18080),1500);return true;}catch(Exception e){return false;}}
 @Override public void onStart(){Bundle report=new Bundle();CountDownLatch release=new CountDownLatch(1);try{
  Context c=getTargetContext();c.getSharedPreferences("persiansocks",0).edit().putInt("vpn-disclosure",DistributionPolicy.DISCLOSURE_VERSION).putBoolean("kill-switch-off",false).putBoolean("auto-switch",false).putBoolean("initial-discovery-030",true).putBoolean("background-maintenance",false).commit();
  check(VpnService.prepare(c)==null,"Fixture requires Android VPN app-op consent");
  waitFor(this::physical);
  ProxyEndpoint snapshot=new ProxyEndpoint("127.0.0.1",1,20,false,1,1,System.currentTimeMillis());QuickStartCache.save(c,Collections.singletonList(snapshot));
  ProxyStore.IO.execute(()->{try{release.await(25,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
  long start=SystemClock.elapsedRealtime();Activity screen=startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  java.lang.reflect.Field chosen=MainActivity.class.getDeclaredField("selected");chosen.setAccessible(true);check(chosen.get(screen)!=null,"Preview/selection waits for full storage");
  report.putLong("startup_preview_ms",SystemClock.elapsedRealtime()-start);
  runOnMainSync(()->c.startForegroundService(new Intent(c,ProxyVpnService.class).setAction(ProxyVpnService.CONNECT).putExtra(ProxyVpnService.EXTRA_ENDPOINT,snapshot.key()).putExtra("filter","ALL").putExtra("sort","FASTEST").putExtra("evidence",snapshot.json().toString())));
  waitFor(()->{try{return TProxyService.TProxyIsRunning();}catch(Throwable e){return false;}});release.countDown();
  ConnectivityManager manager=c.getSystemService(ConnectivityManager.class);boolean v4=false,v6=false;
  for(Network n:manager.getAllNetworks()){NetworkCapabilities caps=manager.getNetworkCapabilities(n);if(caps==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))continue;LinkProperties lp=manager.getLinkProperties(n);if(lp!=null)for(RouteInfo route:lp.getRoutes())if(route.isDefaultRoute()){v4|=route.getDestination().getAddress() instanceof java.net.Inet4Address;v6|=route.getDestination().getAddress() instanceof java.net.Inet6Address;}}
  check(v4&&v6,"Guard must capture both default address families");
  runOnMainSync(()->c.startService(new Intent(c,ProxyVpnService.class).setAction(ProxyVpnService.DISCONNECT)));
  waitFor(ProxyVpnService::guardActive);check(!physical(),"Direct IPv4 escaped after engine disconnect");
  c.getSharedPreferences("persiansocks",0).edit().putBoolean("kill-switch-off",true).commit();runOnMainSync(()->c.startService(new Intent(c,ProxyVpnService.class).setAction(ProxyVpnService.RELEASE_GUARD)));
  waitFor(()->!ProxyVpnService.guardActive());waitFor(this::physical);
  report.putString("stream","PARITY_NATIVE_PASS: first-screen preview while storage blocked; native JNI engine; IPv4/IPv6 default routes; no direct IPv4 after engine disconnect; OFF restores traffic\n");finish(Activity.RESULT_OK,report);
 }catch(Throwable error){report.putString("stream","PARITY_NATIVE_FAIL: "+error+"\n");finish(Activity.RESULT_CANCELED,report);}finally{release.countDown();}}
}
