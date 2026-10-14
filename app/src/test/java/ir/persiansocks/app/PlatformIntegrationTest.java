package ir.persiansocks.app;

import android.app.Application;
import android.content.Context;
import androidx.room.Room;
import androidx.paging.*;
import androidx.work.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.*;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29,application=Application.class)
public class PlatformIntegrationTest {
    private ProxyDatabase db;
    @Before public void setup(){db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),ProxyDatabase.class).allowMainThreadQueries().build();}
    @After public void close(){db.close();}
    static ProxyEndpoint healthy(String host,int port,long when){return new ProxyEndpoint(host,port,100,false,7,7,when);}
    @Test public void olderSuccessCannotReviveNewerFailureAndFavoritesSurvive(){
        ProxyEndpoint p=healthy("1.1.1.1",1080,100);ProxyIndex ix=new ProxyIndex();ix.accept(p);ProxyDatabase.ProxyRow success=new ProxyDatabase.ProxyRow(ix.records().get(0));
        ProxyDatabase.ProxyRow failed=new ProxyDatabase.ProxyRow();failed.key=p.key();failed.changed=200;
        db.data().put(failed);db.data().put(success);assertNull(db.data().proxies().get(0).payload);
        success.changed=200;db.data().put(success);assertNull(db.data().proxies().get(0).payload);
        db.data().favorites(Collections.singletonList(new ProxyDatabase.FavoriteRow(p,0)));db.data().prune(Collections.singletonList(p.key()));
        assertEquals(p.key(),db.data().favorites().get(0).key);
        ProxyEndpoint newer=healthy("1.1.1.1",1080,300);ix.accept(newer);db.data().put(new ProxyDatabase.ProxyRow(ix.records().get(0)));assertNotNull(db.data().proxies().get(0).payload);
    }
    @Test public void actualRoomPagingReadsOnlyRequestedPage()throws Exception{
        List<ProxyDatabase.ResultRow> rows=new ArrayList<>();for(int i=0;i<2000;i++)rows.add(new ProxyDatabase.ResultRow(11,i,healthy("1.1.1.1",1000+i,100)));db.data().publishRows(11,rows);
        PagingSource<Integer,ProxyDatabase.ResultRow> source=db.data().pages(11);
        Object loaded=BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,(scope,continuation)->source.load(new PagingSource.LoadParams.Refresh<Integer>(0,80,false),(kotlin.coroutines.Continuation)continuation));
        assertTrue(loaded instanceof PagingSource.LoadResult.Page);List<ProxyDatabase.ResultRow> page=((PagingSource.LoadResult.Page<Integer,ProxyDatabase.ResultRow>)loaded).getData();assertEquals(80,page.size());assertEquals(0,page.get(0).position);assertEquals(79,page.get(79).position);
        List<ProxyDatabase.CatalogRow> catalog=new ArrayList<>();List<String> keys=new ArrayList<>();for(ProxyDatabase.ResultRow r:rows){catalog.add(new ProxyDatabase.CatalogRow(r.key,r.position));keys.add(r.key);}db.data().catalog(catalog);db.data().pruneAll(keys);assertEquals(0,db.data().catalogCount());
        db.data().publishRows(12,Collections.singletonList(new ProxyDatabase.ResultRow(12,0,healthy("8.8.8.8",1080,200))));PagingSource<Integer,ProxyDatabase.ResultRow> next=db.data().pages(12);
        Object changed=BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,(scope,continuation)->next.load(new PagingSource.LoadParams.Refresh<Integer>(0,80,false),(kotlin.coroutines.Continuation)continuation));
        List<ProxyDatabase.ResultRow> fresh=((PagingSource.LoadResult.Page<Integer,ProxyDatabase.ResultRow>)changed).getData();assertEquals(1,fresh.size());assertEquals("8.8.8.8:1080",fresh.get(0).key);
    }
    @Test public void migrationPreservesHealthyTombstonesFavoritesAndPausedScan()throws Exception{
        Context c=RuntimeEnvironment.getApplication();File dir=c.getFilesDir();ProxyEndpoint p=healthy("1.1.1.1",1080,100),bad=healthy("8.8.8.8",1080,100);ProxyIndex old=new ProxyIndex();old.accept(p);old.accept(bad);old.reject(bad.key(),200);new ProxyCacheFile(dir).save(old);
        c.getSharedPreferences("persiansocks-user-favorites",0).edit().putString("saved-proxies-v1",new org.json.JSONArray().put(bad.json()).toString()).commit();
        ScanFiles files=new ScanFiles(dir);files.saveCatalog(Arrays.asList(p,bad));ScanCheckpoint pause=new ScanCheckpoint(Arrays.asList(p,bad),new ProxyScanner.Options(100,false),"CATALOG");files.activate(pause);files.save(pause);
        ProxyStore.IO.submit(()->{ProxyStore.load(c);assertEquals(1,ProxyStore.index().size());assertTrue(ProxyStore.index().rejected(bad.key()));ProxyDatabase actual=ProxyDatabase.get(c);assertEquals(2,actual.data().proxies().size());assertEquals(bad.key(),actual.data().favorites().get(0).key);assertEquals("1",actual.data().meta("legacy-v1"));assertEquals(2,files.checkpoint().total());assertEquals(2,files.catalogCount());assertTrue(new ProxyCacheFile(dir).exists());return null;}).get(15,TimeUnit.SECONDS);
    }
    @Test public void okhttpStreamsAndRejectsDowngradeRedirect()throws Exception{
        try(ServerSocket server=new ServerSocket(0,2,InetAddress.getLoopbackAddress())){
            ExecutorService executor=Executors.newSingleThreadExecutor();executor.submit(()->{try(Socket s=server.accept()){BufferedReader r=new BufferedReader(new InputStreamReader(s.getInputStream()));while(!r.readLine().isEmpty()){}s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nContent-Type: text/plain\r\n\r\nhello".getBytes(java.nio.charset.StandardCharsets.US_ASCII));}catch(Exception e){throw new RuntimeException(e);}});
            AppHttp.Connection c=new AppHttp.Connection(new URL("http://127.0.0.1:"+server.getLocalPort()),new okhttp3.OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build());c.setConnectTimeout(1000);c.setReadTimeout(1000);assertEquals(200,c.getResponseCode());assertEquals("hello",new String(c.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.US_ASCII));c.disconnect();executor.shutdownNow();
        }
        try(ServerSocket server=new ServerSocket(0,2,InetAddress.getLoopbackAddress())){
            ExecutorService executor=Executors.newSingleThreadExecutor();executor.submit(()->{try(Socket s=server.accept()){BufferedReader r=new BufferedReader(new InputStreamReader(s.getInputStream()));while(!r.readLine().isEmpty()){}s.getOutputStream().write("HTTP/1.1 302 Found\r\nContent-Length: 0\r\nLocation: http://127.0.0.1:1/unsafe\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));}catch(Exception e){throw new RuntimeException(e);}});
            AppHttp.Connection c=new AppHttp.Connection(new URL("http://127.0.0.1:"+server.getLocalPort()),new okhttp3.OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build());c.setConnectTimeout(1000);c.setReadTimeout(1000);c.setInstanceFollowRedirects(true);try{c.getResponseCode();fail("Downgrade followed");}catch(IOException expected){assertTrue(expected.getMessage().contains("Unsafe"));}finally{c.disconnect();executor.shutdownNow();}
        }
    }
    @Test public void okhttpCancellationInterruptsWaitingHeaders()throws Exception{
        try(ServerSocket server=new ServerSocket(0,2,InetAddress.getLoopbackAddress())){
            CountDownLatch accepted=new CountDownLatch(1);ExecutorService executor=Executors.newFixedThreadPool(2);
            executor.submit(()->{try(Socket s=server.accept()){accepted.countDown();while(s.getInputStream().read()!=-1){}}catch(Exception ignored){}});
            AppHttp.Connection c=new AppHttp.Connection(new URL("http://127.0.0.1:"+server.getLocalPort()),new okhttp3.OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build());c.setConnectTimeout(1000);c.setReadTimeout(5000);
            Future<Boolean> stopped=executor.submit(()->{try{c.getResponseCode();return false;}catch(IOException expected){return true;}});assertTrue(accepted.await(2,TimeUnit.SECONDS));c.disconnect();assertTrue(stopped.get(1,TimeUnit.SECONDS));executor.shutdownNow();
        }
    }
    @Test public void recyclerHomeStartsInBothLanguages(){
        Context c=RuntimeEnvironment.getApplication();AppLanguage.initialize(c);
        for(String language:new String[]{"fa","en"}){AppLanguage.select(c,language);assertEquals(AppLanguage.t(R.string.ui_quality_sort),AppLanguage.display("کیفیت روی این شبکه"));org.robolectric.android.controller.ActivityController<MainActivity> activity=Robolectric.buildActivity(MainActivity.class).create();assertNotNull(activity.get().findViewById(android.R.id.content));activity.destroy();}
    }
    private static void addAddress(android.net.LinkProperties links,String ip,int prefix)throws Exception{
        android.net.LinkAddress address=org.robolectric.util.ReflectionHelpers.callConstructor(android.net.LinkAddress.class,org.robolectric.util.ReflectionHelpers.ClassParameter.from(InetAddress.class,InetAddress.getByName(ip)),org.robolectric.util.ReflectionHelpers.ClassParameter.from(int.class,prefix));
        org.robolectric.util.ReflectionHelpers.callInstanceMethod(links,"addLinkAddress",org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.net.LinkAddress.class,address));
    }
    @Test public void physicalRouteKeepsDnsAdditionsButRejectsLostIpv4()throws Exception{
        Context c=RuntimeEnvironment.getApplication();android.net.ConnectivityManager manager=c.getSystemService(android.net.ConnectivityManager.class);org.robolectric.shadows.ShadowConnectivityManager shadow=org.robolectric.Shadows.shadowOf(manager);shadow.clearAllNetworks();
        android.net.Network n=org.robolectric.util.ReflectionHelpers.callConstructor(android.net.Network.class,org.robolectric.util.ReflectionHelpers.ClassParameter.from(int.class,101));android.net.NetworkInfo info=org.robolectric.shadows.ShadowNetworkInfo.newInstance(android.net.NetworkInfo.DetailedState.CONNECTED,android.net.ConnectivityManager.TYPE_WIFI,0,true,true);shadow.addNetwork(n,info);shadow.setActiveNetworkInfo(info);
        android.net.NetworkCapabilities caps=new android.net.NetworkCapabilities();org.robolectric.util.ReflectionHelpers.callInstanceMethod(caps,"addTransportType",org.robolectric.util.ReflectionHelpers.ClassParameter.from(int.class,android.net.NetworkCapabilities.TRANSPORT_WIFI));org.robolectric.util.ReflectionHelpers.callInstanceMethod(caps,"addCapability",org.robolectric.util.ReflectionHelpers.ClassParameter.from(int.class,android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET));shadow.setNetworkCapabilities(n,caps);
        android.net.LinkProperties first=new android.net.LinkProperties();addAddress(first,"192.0.2.15",24);addAddress(first,"2001:db8::1",64);shadow.setLinkProperties(n,first);PhysicalTestNetwork path=PhysicalTestNetwork.capture(c);assertTrue(path.valid());assertEquals("wifi",path.scope());
        android.net.LinkProperties dns=org.robolectric.util.ReflectionHelpers.callConstructor(android.net.LinkProperties.class,org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.net.LinkProperties.class,first));org.robolectric.util.ReflectionHelpers.callInstanceMethod(dns,"addDnsServer",org.robolectric.util.ReflectionHelpers.ClassParameter.from(InetAddress.class,InetAddress.getByName("1.1.1.1")));addAddress(dns,"2001:db8::2",64);shadow.setLinkProperties(n,dns);assertTrue(path.valid());
        android.net.LinkProperties removed=new android.net.LinkProperties();addAddress(removed,"2001:db8::1",64);shadow.setLinkProperties(n,removed);assertFalse(path.valid());
    }
    @Test public void maintenanceIsUniqueConstrainedDelayedAndCancellable()throws Exception{
        Context c=RuntimeEnvironment.getApplication();try{WorkManager.initialize(c,new androidx.work.Configuration.Builder().build());}catch(IllegalStateException initialized){}
        c.getSharedPreferences("persiansocks",0).edit().putBoolean("background-maintenance",true).commit();ProxyMaintenance.schedule(c);ProxyMaintenance.schedule(c);WorkManager manager=WorkManager.getInstance(c);
        List<WorkInfo> jobs=manager.getWorkInfosForUniqueWork("proxy-short-maintenance-v1").get(10,TimeUnit.SECONDS);assertEquals(1,jobs.size());assertEquals(NetworkType.UNMETERED,jobs.get(0).getConstraints().getRequiredNetworkType());assertTrue(jobs.get(0).getConstraints().requiresBatteryNotLow());assertEquals(6*3600000L,jobs.get(0).getInitialDelayMillis());
        c.getSharedPreferences("persiansocks",0).edit().putBoolean("background-maintenance",false).commit();ProxyMaintenance.schedule(c);assertEquals(WorkInfo.State.CANCELLED,manager.getWorkInfosForUniqueWork("proxy-short-maintenance-v1").get(10,TimeUnit.SECONDS).get(0).getState());
    }
    @Test public void roomKeepsWifiAndMobileEvidenceSeparate(){
        QualityStats wifi=new QualityStats(),mobile=new QualityStats();long now=System.currentTimeMillis();for(int i=0;i<12;i++){wifi.probe(true,100,now);mobile.probe(false,-1,now);}db.data().quality(new ProxyDatabase.QualityRow("wifi","1.1.1.1:1080",wifi));db.data().quality(new ProxyDatabase.QualityRow("mobile","1.1.1.1:1080",mobile));
        assertEquals(1,db.data().quality("wifi").size());assertEquals(1,db.data().quality("mobile").size());assertNotEquals(db.data().quality("wifi").get(0).payload,db.data().quality("mobile").get(0).payload);
    }
    @Test public void catalogRefreshDoesNotReplacePausedInput()throws Exception{
        Context c=RuntimeEnvironment.getApplication();File dir=new File(c.getCacheDir(),"room-checkpoint-test");dir.mkdirs();ScanFiles files=new ScanFiles(dir);
        ProxyEndpoint a=healthy("1.1.1.1",1080,100),b=healthy("8.8.8.8",1080,100);files.saveCatalog(Collections.singletonList(a));ScanCheckpoint checkpoint=new ScanCheckpoint(Collections.singletonList(a),new ProxyScanner.Options(100,false),"CATALOG");files.activate(checkpoint);files.save(checkpoint);files.mergeCatalog(Collections.singletonList(b));
        ScanCheckpoint restored=files.checkpoint();assertEquals(1,restored.total());assertEquals(a.key(),restored.candidate(0).key());assertEquals(2,files.catalogCount());
    }
}
