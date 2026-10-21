package ir.persiansocks.app;

import android.app.Application;
import android.content.Context;
import android.widget.TextView;
import androidx.room.Room;
import androidx.paging.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29,application=Application.class)
public class StartupRegressionTest {
    private ProxyDatabase db,previous;
    @Before public void setup(){
        Context c=RuntimeEnvironment.getApplication();AppLanguage.initialize(c);AppLanguage.select(c,"fa");
        previous=ReflectionHelpers.getStaticField(ProxyDatabase.class,"instance");
        db=Room.inMemoryDatabaseBuilder(c,ProxyDatabase.class).allowMainThreadQueries().build();
        ReflectionHelpers.setStaticField(ProxyDatabase.class,"instance",db);
    }
    @After public void close()throws Exception{
        ProxyStore.IO.submit(()->{}).get(10,TimeUnit.SECONDS);
        ReflectionHelpers.setStaticField(ProxyDatabase.class,"instance",previous);db.close();
    }
    private static ProxyEndpoint good(String host,int port,long time){return new ProxyEndpoint(host,port,613,false,7,7,time);}
    @Test public void pagingUsesImmutableReferencesWithoutDerivedDatabaseRows()throws Exception{
        List<ProxyEndpoint> rows=new ArrayList<>();for(int i=0;i<501;i++)rows.add(good("18.157.123.132",1000+i,1));
        ProxyEndpoint first=rows.get(0);ProxyPages source=new ProxyPages(rows);rows.clear();
        PagingSource.LoadResult.Page<Integer,ProxyEndpoint> page=(PagingSource.LoadResult.Page<Integer,ProxyEndpoint>)source.loadFuture(new PagingSource.LoadParams.Refresh<>(null,160,false)).get();
        assertEquals(160,page.getData().size());assertSame(first,page.getData().get(0));assertNull(page.getPrevKey());assertEquals(Integer.valueOf(160),page.getNextKey());
        PagingSource.LoadResult.Page<Integer,ProxyEndpoint> tail=(PagingSource.LoadResult.Page<Integer,ProxyEndpoint>)source.loadFuture(new PagingSource.LoadParams.Append<>(480,80,false)).get();
        assertEquals(21,tail.getData().size());assertNull(tail.getNextKey());assertEquals(480,tail.getItemsBefore());
        try(android.database.Cursor cursor=db.getOpenHelper().getReadableDatabase().query("SELECT COUNT(*) FROM result_pages")){assertTrue(cursor.moveToFirst());assertEquals(0,cursor.getInt(0));}
    }
    @Test public void initialRankingDoesNotRewriteRoomOrWaitForLegacyLists()throws Exception{
        Context c=RuntimeEnvironment.getApplication();ProxyEndpoint p=good("18.157.123.132",3128,System.currentTimeMillis());ProxyStore.index().accept(p);
        c.getSharedPreferences("persiansocks-user-favorites",0).edit().putString("saved-proxies-v1",new org.json.JSONArray().put(p.json()).toString()).commit();
        ReflectionHelpers.setStaticField(CountryLookup.class,"ranges",null);
        org.robolectric.android.controller.ActivityController<MainActivity> owner=Robolectric.buildActivity(MainActivity.class).create();
        try{
            assertNotNull(owner.get().findViewById(android.R.id.content));assertFalse(CountryLookup.initialized());
            Map<String,ProxyEndpoint> favorites=ReflectionHelpers.getField(owner.get(),"favorites");assertTrue(favorites.isEmpty());
            ExecutorService rank=ReflectionHelpers.getField(owner.get(),"rankWorker");rank.submit(()->{}).get(10,TimeUnit.SECONDS);
            try(android.database.Cursor cursor=db.getOpenHelper().getReadableDatabase().query("SELECT COUNT(*) FROM result_pages")){assertTrue(cursor.moveToFirst());assertEquals(0,cursor.getInt(0));}
        }finally{owner.destroy();ProxyStore.index().reject(p.key(),System.currentTimeMillis()+1);}
    }
    @Test public void rejectedFavoriteKeepsItsStarAndLosesOldSuccessMetrics()throws Exception{
        ProxyEndpoint p=good("18.157.123.132",3128,1);ProxyStore.index().accept(p);ProxyStore.index().reject(p.key(),System.currentTimeMillis());
        db.data().favorites(Collections.singletonList(new ProxyDatabase.FavoriteRow(p,0)));
        org.robolectric.android.controller.ActivityController<MainActivity> owner=Robolectric.buildActivity(MainActivity.class).create();
        try{
            Map<String,ProxyEndpoint> favorites=ReflectionHelpers.getField(owner.get(),"favorites");favorites.put(p.key(),p);
            Object card=ReflectionHelpers.callConstructor(Class.forName("ir.persiansocks.app.MainActivity$CardHolder"),ReflectionHelpers.ClassParameter.from(MainActivity.class,owner.get()));
            ReflectionHelpers.callInstanceMethod(card,"bind",ReflectionHelpers.ClassParameter.from(ProxyEndpoint.class,p));
            TextView ping=ReflectionHelpers.getField(card,"ping"),down=ReflectionHelpers.getField(card,"down");
            assertEquals(AppLanguage.t(R.string.ui_proxy_unavailable),ping.getText().toString());assertEquals(ping.getText().toString(),down.getText().toString());
            List<?> icons=ReflectionHelpers.getField(card,"serviceViews");for(Object icon:icons){TextView mark=ReflectionHelpers.getField(icon,"mark");assertNotEquals("✓",mark.getText().toString());}
            assertTrue(favorites.containsKey(p.key()));assertEquals(1,db.data().favorites().size());
        }finally{owner.destroy();}
    }
    @Test public void legacyTestNotesMoveToRoomBeforePreferencesAreRemoved()throws Exception{
        Context c=RuntimeEnvironment.getApplication();String key="18.157.123.132:3128";
        ProxyTestResult note=new ProxyTestResult();note.probeAt=1234;note.probeError="Connection refused";note.downloadMBps=1.89;
        String json=new org.json.JSONObject().put(key,note.json()).toString();
        android.content.SharedPreferences prefs=c.getSharedPreferences("persiansocks",0);
        prefs.edit().putString("test-results-v2",json).commit();
        org.robolectric.android.controller.ActivityController<MainActivity> owner=Robolectric.buildActivity(MainActivity.class).create();
        try{
            Map<String,ProxyTestResult> migrated=ProxyStore.IO.submit(()->(Map<String,ProxyTestResult>)ReflectionHelpers.callInstanceMethod(owner.get(),"readTestResults")).get(10,TimeUnit.SECONDS);
            assertEquals("Connection refused",migrated.get(key).probeError);assertEquals(1.89,migrated.get(key).downloadMBps,0);
            assertNotNull(db.data().meta("test-results-v3"));assertFalse(prefs.contains("test-results-v2"));
            Map<String,ProxyTestResult> reloaded=ProxyStore.IO.submit(()->(Map<String,ProxyTestResult>)ReflectionHelpers.callInstanceMethod(owner.get(),"readTestResults")).get(10,TimeUnit.SECONDS);
            assertEquals(1234,reloaded.get(key).probeAt);assertEquals("Connection refused",reloaded.get(key).probeError);
        }finally{owner.destroy();}
    }
    @Test public void contentAppearsWithoutLoadingOverlayBeforeCachedResultsArePublished()throws Exception{
        ProxyEndpoint p=good("18.157.123.132",19394,System.currentTimeMillis());
        ProxyDatabase.ProxyRow row=new ProxyDatabase.ProxyRow();row.key=p.key();row.changed=p.testedAt;row.payload=p.json().toString();
        db.data().putAll(Collections.singletonList(row));db.data().meta(new ProxyDatabase.MetaRow("legacy-v1","1"));db.data().catalog(Collections.singletonList(new ProxyDatabase.CatalogRow(p.key(),0)));
        // Integration tests share this sandbox; force a cold store load against this test's Room DB.
        boolean loadedBefore=ProxyStore.loaded();List<ProxyIndex.Record> previousRecords=ProxyStore.index().records();
        java.util.function.Consumer<ProxyIndex.Record> previousListener=ReflectionHelpers.getField(ProxyStore.index(),"listener");
        Map<Object,Object> backends=ReflectionHelpers.getStaticField(ScanFiles.class,"BACKENDS");Map<Object,Object> previousBackends=new HashMap<>(backends);
        ReflectionHelpers.setStaticField(ProxyStore.class,"loaded",false);
        org.robolectric.android.controller.ActivityController<MainActivity> owner=Robolectric.buildActivity(MainActivity.class).create();
        try{
            MainActivity activity=owner.get();assertNotNull(activity.findViewById(android.R.id.content));
            assertEquals(1,descendants(((android.view.View)ReflectionHelpers.getField(activity,"scanProgress")).getRootView(),android.widget.ProgressBar.class));
            assertNull(ReflectionHelpers.getField(activity,"pageStream"));
            ReflectionHelpers.callInstanceMethod(activity,"reloadSaved");ProxyStore.IO.submit(()->{}).get(15,TimeUnit.SECONDS);Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            ((ExecutorService)ReflectionHelpers.getField(activity,"rankWorker")).submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue((Boolean)ReflectionHelpers.getField(activity,"localDataLoaded"));assertNotNull(ReflectionHelpers.getField(activity,"pageStream"));
            assertEquals("loaded catalog",1,(int)ReflectionHelpers.getField(activity,"catalogCount"));assertNotNull(ProxyStore.index().find(p.key()));assertTrue((int)ReflectionHelpers.getField(activity,"resultCount")>=1);
        }finally{
            owner.destroy();ProxyStore.IO.submit(()->{}).get(10,TimeUnit.SECONDS);
            ProxyIndex index=ProxyStore.index();index.listener(null);((Map<?,?>)ReflectionHelpers.getField(index,"good")).clear();((Map<?,?>)ReflectionHelpers.getField(index,"changed")).clear();
            for(ProxyIndex.Record record:previousRecords)index.restore(record.json());index.listener(previousListener);
            backends.clear();backends.putAll(previousBackends);ReflectionHelpers.setStaticField(ProxyStore.class,"loaded",loadedBefore);
        }
    }
    private static int descendants(android.view.View view,Class<?> type){int n=type.isInstance(view)?1:0;if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)n+=descendants(group.getChildAt(i),type);}return n;}
    @Test public void approvedHeroKeepsPowerScopeAndKillControlsInBothLanguages(){
        Context c=RuntimeEnvironment.getApplication();for(String language:new String[]{"fa","en"}){
            AppLanguage.select(c,language);var owner=Robolectric.buildActivity(MainActivity.class).create();try{
                MainActivity activity=owner.get();android.view.View power=ReflectionHelpers.getField(activity,"vpnToggle");
                android.widget.CheckBox kill=ReflectionHelpers.getField(activity,"quickKill"),chat=ReflectionHelpers.getField(activity,"chatOnly");android.widget.Button all=ReflectionHelpers.getField(activity,"designAllApps");
                assertNotNull(power.getParent());assertNotNull(kill.getParent());assertNotNull(chat.getParent());assertNotNull(all.getParent());
                assertEquals(AndroidRoutes.kill(c),kill.isChecked());assertEquals(AndroidRoutes.mode(c).equals("chatgpt"),chat.isChecked());
                assertFalse(power.getContentDescription().toString().isEmpty());assertFalse(chat.getContentDescription().toString().isEmpty());
            }finally{owner.destroy();}
        }
    }
    @Test public void upgradeRestoresAllServiceScanningAndSidebarOnlyKeepsLimitSlider(){
        Context c=RuntimeEnvironment.getApplication();android.content.SharedPreferences prefs=c.getSharedPreferences("persiansocks",0);
        prefs.edit().remove("scan-v18-restored").putBoolean("scan-ping-only",true).putString("ping-target-url",ServiceTargets.ALL[2].url()).putInt("max-tests",1234).commit();
        org.robolectric.android.controller.ActivityController<MainActivity> owner=Robolectric.buildActivity(MainActivity.class).create();
        try{
            MainActivity activity=owner.get();assertFalse(ScanProbePolicy.profile().only);assertNull(ScanProbePolicy.profile().target());
            android.view.View sidebar=ReflectionHelpers.getField(activity,"sidebar");assertEquals(0,descendants(sidebar,android.widget.EditText.class));assertEquals(1,descendants(sidebar,ScanLimitView.class));
            assertEquals(5000,prefs.getInt("max-tests",0));
            // Later target choices remain explicit and are not reset at each Activity start.
            prefs.edit().putBoolean("scan-ping-only",true).putString("ping-target-url",ServiceTargets.ALL[1].url()).commit();ScanInputs.initializePolicy(c);assertTrue(ScanProbePolicy.profile().only);
        }finally{owner.destroy();prefs.edit().putBoolean("scan-ping-only",false).putString("ping-target-url","").commit();ScanInputs.configureTests(c);}
    }
    @Test public void longPressAndRestartOfferTwoModesWhileResumeSkipsTheDialog()throws Exception{
        Context c=RuntimeEnvironment.getApplication();Shadows.shadowOf(c.getSystemService(android.net.ConnectivityManager.class)).clearAllNetworks();
        org.robolectric.android.controller.ActivityController<MainActivity> owner=Robolectric.buildActivity(MainActivity.class).create();
        try{
            MainActivity activity=owner.get();ReflectionHelpers.setField(activity,"localDataLoaded",true);ReflectionHelpers.setField(activity,"catalogCount",10);ReflectionHelpers.callInstanceMethod(activity,"updateActions");
            ReflectionHelpers.setField(activity,"protocol",ProxyResults.Protocol.SOCKS);android.widget.Button play=ReflectionHelpers.getField(activity,"scanToggle"),restart=ReflectionHelpers.getField(activity,"scanRestart");play.performLongClick();
            android.app.AlertDialog first=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertEquals(2,first.getListView().getAdapter().getCount());assertEquals(AppLanguage.t(R.string.ui_scan_fast),first.getListView().getAdapter().getItem(0));assertEquals(AppLanguage.t(R.string.ui_scan_deep),first.getListView().getAdapter().getItem(1));first.dismiss();
            ReflectionHelpers.setField(activity,"protocol",ProxyResults.Protocol.VPN_GATE);ReflectionHelpers.setField(activity,"catalogCount",0);restart.performClick();android.app.AlertDialog gate=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertNotSame(first,gate);assertEquals(2,gate.getListView().getAdapter().getCount());assertEquals(AppLanguage.t(R.string.ui_scan_deep),gate.getListView().getAdapter().getItem(1));gate.dismiss();ReflectionHelpers.setField(activity,"protocol",ProxyResults.Protocol.SOCKS);ReflectionHelpers.setField(activity,"catalogCount",10);
            ScanCheckpoint paused=new ScanCheckpoint(Arrays.asList(ProxyEndpoint.parse("8.8.8.8:1080"),ProxyEndpoint.parse("9.9.9.9:1080")),new ProxyScanner.Options(100,true),"CATALOG");paused.record(0,new Socks5Probe.Result(good("8.8.8.8",1080,System.currentTimeMillis()),Socks5Probe.Failure.NONE,true,true));
            ReflectionHelpers.setField(activity,"checkpoint",paused);ReflectionHelpers.callInstanceMethod(activity,"updateActions");assertEquals(UiIconButton.Kind.PLAY,ReflectionHelpers.getField(play,"kind"));assertEquals(AppLanguage.t(R.string.ui_scan_resume),play.getContentDescription().toString());
            play.performClick();assertSame(gate,org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());assertSame(paused,ReflectionHelpers.getField(activity,"checkpoint"));assertTrue(paused.slow);
            restart.performClick();android.app.AlertDialog again=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertNotSame(first,again);assertEquals(2,again.getListView().getAdapter().getCount());again.dismiss();
            ReflectionHelpers.setField(activity,"scanning",true);ReflectionHelpers.callInstanceMethod(activity,"updateActions");assertEquals(UiIconButton.Kind.STOP,ReflectionHelpers.getField(play,"kind"));assertFalse(restart.isEnabled());ReflectionHelpers.setField(activity,"scanning",false);
        }finally{owner.destroy();}
    }
    @Test public void pausedCountersStayLeftOfControlsAndKeepOriginalDenominator(){
        Context c=RuntimeEnvironment.getApplication();
        for(String language:new String[]{"fa","en"}){
            AppLanguage.select(c,language);org.robolectric.android.controller.ActivityController<MainActivity> owner=Robolectric.buildActivity(MainActivity.class).create();
            try{
                MainActivity activity=owner.get();ReflectionHelpers.setField(activity,"localDataLoaded",true);ReflectionHelpers.setField(activity,"catalogCount",500);
                ScanCheckpoint paused=new ScanCheckpoint(Arrays.asList(ProxyEndpoint.parse("8.8.8.8:1080"),ProxyEndpoint.parse("9.9.9.9:1080")),new ProxyScanner.Options(100,false),"CATALOG");paused.record(0,new Socks5Probe.Result(good("8.8.8.8",1080,System.currentTimeMillis()),Socks5Probe.Failure.NONE,true,true));ReflectionHelpers.setField(activity,"checkpoint",paused);ReflectionHelpers.callInstanceMethod(activity,"updateActions");
                TextView total=ReflectionHelpers.getField(activity,"scanTotal"),checked=ReflectionHelpers.getField(activity,"scanChecked");assertTrue(total.getText().toString().contains("2"));assertTrue(checked.getText().toString().contains("1"));
                android.view.View counts=(android.view.View)((android.view.View)total.getParent()).getParent();android.view.View restart=ReflectionHelpers.getField(activity,"scanRestart");android.view.ViewGroup row=(android.view.ViewGroup)counts.getParent();
                int width=(int)(320*c.getResources().getDisplayMetrics().density);row.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED));row.layout(0,0,width,row.getMeasuredHeight());assertTrue(counts.getRight()<=restart.getLeft());
                android.widget.ProgressBar bar=ReflectionHelpers.getField(activity,"scanProgress");assertEquals(android.view.View.VISIBLE,bar.getVisibility());assertFalse(bar.isIndeterminate());assertEquals(50,bar.getProgress());
            }finally{owner.destroy();}
        }
    }
    private PhysicalTestNetwork physical()throws Exception{
        Context c=RuntimeEnvironment.getApplication();android.net.ConnectivityManager manager=c.getSystemService(android.net.ConnectivityManager.class);org.robolectric.shadows.ShadowConnectivityManager shadow=Shadows.shadowOf(manager);shadow.clearAllNetworks();
        android.net.Network n=ReflectionHelpers.callConstructor(android.net.Network.class,ReflectionHelpers.ClassParameter.from(int.class,193));android.net.NetworkInfo info=org.robolectric.shadows.ShadowNetworkInfo.newInstance(android.net.NetworkInfo.DetailedState.CONNECTED,android.net.ConnectivityManager.TYPE_WIFI,0,true,true);shadow.addNetwork(n,info);shadow.setActiveNetworkInfo(info);
        android.net.NetworkCapabilities caps=new android.net.NetworkCapabilities();ReflectionHelpers.callInstanceMethod(caps,"addTransportType",ReflectionHelpers.ClassParameter.from(int.class,android.net.NetworkCapabilities.TRANSPORT_WIFI));ReflectionHelpers.callInstanceMethod(caps,"addCapability",ReflectionHelpers.ClassParameter.from(int.class,android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET));shadow.setNetworkCapabilities(n,caps);return PhysicalTestNetwork.capture(c);
    }
    @Test public void neverSuccessfulNoiseDoesNotFillQualityStorage()throws Exception{
        Map<?,?> scopes=ReflectionHelpers.getStaticField(QualityHistory.class,"scopes");scopes.clear();PhysicalTestNetwork network=physical();QualityHistory.initialize(RuntimeEnvironment.getApplication());ProxyStore.IO.submit(()->{}).get(10,TimeUnit.SECONDS);
        ProxyEndpoint p=good("9.9.9.9",19301,1);QualityHistory.probe(network,p,new Socks5Probe.Result(p,Socks5Probe.Failure.NONE,true,true));ProxyStore.IO.submit(()->{}).get(10,TimeUnit.SECONDS);
        QualityHistory.probe(network,p,new Socks5Probe.Result(null,Socks5Probe.Failure.HTTPS_TIMEOUT,true,true));
        for(int i=0;i<2048;i++)QualityHistory.probe(network,new ProxyEndpoint("11.0."+(i/254)+"."+(i%254+1),1080,-1,false),new Socks5Probe.Result(null,Socks5Probe.Failure.TCP_TIMEOUT,false,false));
        ProxyStore.IO.submit(()->{}).get(10,TimeUnit.SECONDS);assertEquals(1,db.data().quality("wifi").size());assertEquals(2,QualityHistory.stats(p.key()).samples());
    }
    @Test public void recentQualityAndPruningStayScoped(){
        for(int i=0;i<12;i++){QualityStats stats=new QualityStats();stats.probe(true,100,i+1);db.data().quality(new ProxyDatabase.QualityRow("wifi","11.1.1."+i,stats));}
        QualityStats stats=new QualityStats();stats.probe(true,100,1);db.data().quality(new ProxyDatabase.QualityRow("mobile","9.9.9.9",stats));
        assertEquals(5,db.data().recentQuality("wifi",5).size());assertEquals("11.1.1.11",db.data().recentQuality("wifi",5).get(0).key);
        db.data().trimQuality("wifi",5);assertEquals(5,db.data().quality("wifi").size());assertEquals(1,db.data().quality("mobile").size());
    }
}
