package ir.persiansocks.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.net.Socket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import hev.htproxy.TProxyService;

/** Physical SOCKS sockets are protected individually, so this app can verify the VPN path too. */
public final class ProxyVpnService extends VpnService {
    public static final String GATE_SCAN="ir.persiansocks.app.GATE_SCAN",GATE_STOP="ir.persiansocks.app.GATE_STOP";
    private static volatile boolean gateScanning;private static volatile int gateDone,gateTotal,gatePassed;
    public static boolean gateScanning(){return gateScanning;}
    public static int gateDone(){return gateDone;}public static int gateTotal(){return gateTotal;}
    private volatile java.util.Map<String,Boolean> gateProtocolResults=new ConcurrentHashMap<>();private final Set<DatagramSocket> gateProbeDatagrams=ConcurrentHashMap.newKeySet();
    private java.util.concurrent.ExecutorService gatePreparePool;private VpnGateScanPlan gatePlan;private VpnGateDirectory gateResolver;private boolean gateAttemptRunning;
    private java.util.concurrent.ExecutorService gatePortPool;private final Set<Socket> gatePortSockets=ConcurrentHashMap.newKeySet();private long gateScanStarted;
    private java.util.List<VpnGateProfile> gateQueue=java.util.Collections.emptyList();private volatile int gateRun;private boolean gateDeep;private String gateRestore="",gateFirstSuccess="";private VpnGateStore scanGateStore;
    public static final String CONNECT_CACHED="ir.persiansocks.app.CONNECT_CACHED";
    public static final String CONNECT = "ir.persiansocks.app.CONNECT";
    public static final String RECONFIGURE="ir.persiansocks.app.RECONFIGURE";
    public static final String RELEASE_GUARD="ir.persiansocks.app.RELEASE_GUARD";
    public static String localProxyUri(){ProxyVpnService s=instance.get();ProtectedSocksGateway g=s==null?null:s.gateway;return s!=null&&s.localProxyRunning&&phase==CONNECTED&&g!=null?"socks5://"+g.username()+":"+g.password()+"@127.0.0.1:"+g.port():"";}
    public static boolean guardArmed(){ProxyVpnService s=instance.get();return s!=null&&s.tun!=null;}
    public static boolean guardActive(){ProxyVpnService s=instance.get();return s!=null&&s.tun!=null&&phase==DISCONNECTED;}
    public static final String DISCONNECT = "ir.persiansocks.app.DISCONNECT";
    public static final String SCAN_CONNECT = "ir.persiansocks.app.SCAN_CONNECT";
    public static final String STATE = "ir.persiansocks.app.STATE";
    public static final String INTERNAL_PERMISSION = "ir.persiansocks.app.permission.INTERNAL_STATE";
    public static final String EXTRA_ENDPOINT = "endpoint";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_PHASE = "phase";
    public static final int DISCONNECTED = 0, CONNECTING = 1, CONNECTED = 2;
    private static volatile TrafficMeter.Snapshot trafficSnapshot;
    public static TrafficMeter.Snapshot traffic(android.content.Context context){
        TrafficMeter.Snapshot value=trafficSnapshot;if(value!=null)return value;
        android.content.SharedPreferences prefs=context.getSharedPreferences("persiansocks",MODE_PRIVATE);
        return new TrafficMeter.Snapshot(prefs.getLong("traffic-down",0),prefs.getLong("traffic-up",0),0,0);
    }
    private TrafficMeter trafficMeter;
    private boolean newSessionPending;
    private long lastTrafficSave;
    private static volatile int phase = DISCONNECTED;
    private static volatile String activeEndpoint = "";
    private static volatile boolean autoScanning;
    private static volatile int autoDone, autoTotal;
    private static java.lang.ref.WeakReference<ProxyVpnService> instance=new java.lang.ref.WeakReference<>(null);
    public static boolean stopForTlsChange(){
        ProxyVpnService service=instance.get();
        if(service!=null){service.finishConnection(AppLanguage.t(R.string.ui_extra_009),false);return true;}
        return false;
    }
    public static int phase() { return phase; }
    public static boolean protectTestDatagram(DatagramSocket socket) { ProxyVpnService s=instance.get();return s==null||s.tun==null||s.protect(socket); }
    public static boolean protectTestSocket(Socket socket) {
        ProxyVpnService service = instance.get();
        return service == null || service.tun==null || service.protect(socket);
    }
    public static String activeEndpoint() { return activeEndpoint; }
    public static boolean autoScanning() { return autoScanning; }
    public static int autoScanDone(){return autoDone;}
    public static int autoScanTotal(){return autoTotal;}
    public static int autoProgress() { return autoTotal<=0?-1:(int)(100L*autoDone/autoTotal); }
    public static void cancelAutoScanFromUi(){
        ProxyVpnService service=instance.get();
        if(service!=null&&autoScanning)service.finishConnection(AppLanguage.t(R.string.ui_extra_010),false);
    }
    private static final String CH = "persiansocks_vpn";
    private static final int ID = 901;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile ParcelFileDescriptor tun;
    private volatile int generation = 0;
    private int commandStartId;private boolean destroying;
    private String current = "";
    private ProtectedSocksGateway gateway;private volatile boolean localProxyRunning;
    private OpenVpnSession gateSession;
    private OpenVpnSession.TunConfig gateTunConfig;
    private final java.util.List<ParcelFileDescriptor> retiredTuns=new java.util.ArrayList<>();
    private String tunKind="socks";private long gateReceived,gateSent;private boolean gateVerifying;
    private AtomicBoolean cancelled = new AtomicBoolean(true);
    private final Set<Socket> probeSockets = ConcurrentHashMap.newKeySet();
    private String verifiedServices = "";
    private boolean userRequested;
    private String requiredFilter = "ALL";
    private ProxyResults.Sort requiredSort = ProxyResults.Sort.QUALITY;
    private ProxyEndpoint lastProfile;
    private final FailoverPolicy failover = new FailoverPolicy();
    private final StringBuilder recoveryLog = new StringBuilder();
    private PreparedScanner automaticScanner;
    private boolean autoFinishing;
    private ScanCheckpoint autoCheckpoint;
    private AutoScanSelection autoSelection;
    private String autoReport = "";
    private boolean autoSelectionScheduled;
    private long lastScanAnnouncement;
    private PhysicalNetworkMonitor networkMonitor;
    private boolean waitingForPhysical;
    private final Runnable recoverPhysical=()->{
        if(!waitingForPhysical||!userRequested||destroying)return;
        if(!autoSwitch()){finishConnection(AppLanguage.t(R.string.ui_extra_070));return;}
        if(networkMonitor!=null&&!networkMonitor.available())return;
        if(!physicalNetworkAvailable())return;
        ProxyEndpoint resume=ProxyEndpoint.parse(current);
        if(resume==null){finishConnection(AppLanguage.t(R.string.ui_extra_071));return;}
        waitingForPhysical=false;failover.reset(current);beginConnect(resume);
    };

    @Override public void onCreate() {
        super.onCreate();
        instance=new java.lang.ref.WeakReference<>(this);
        ScanInputs.initializePolicy(this);
        android.content.SharedPreferences prefs=getSharedPreferences("persiansocks",MODE_PRIVATE);
        trafficMeter=new TrafficMeter(prefs.getLong("traffic-down",0),prefs.getLong("traffic-up",0));
        trafficSnapshot=trafficMeter.stopped();
        ServiceTargets.configure(ServiceTargets.decode(getSharedPreferences("persiansocks", MODE_PRIVATE).getString("test-domains", "[]")));
        ProxyStore.IO.execute(()->{try { if(!CountryLookup.initialized())CountryLookup.initialize(getAssets().open("ip-country.bin")); } catch (Exception ignored) {}});
        try{networkMonitor=new PhysicalNetworkMonitor(getSystemService(ConnectivityManager.class),new Handler(Looper.getMainLooper()),(broken,dnsChanged)->{
            if(destroying||!userRequested||autoScanning||gateScanning)return;
            if(dnsChanged&&tunnelPhysical!=null)tunnelPhysical.invalidateDns();
            if(broken&&!waitingForPhysical){
                recoveryLog.append("PHYSICAL_ROUTE_CHANGED: retry same proxy on a fresh route\n");
                waitForNetwork();
            }
            if(waitingForPhysical){handler.removeCallbacks(recoverPhysical);handler.postDelayed(recoverPhysical,250);}
        });}catch(RuntimeException ex){Log.w("PersianSocks","Physical callback unavailable; using recovery polling",ex);}
    }

    @Override public IBinder onBind(Intent intent) { return super.onBind(intent); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if(startId>0)commandStartId=startId;
        if (intent == null) return START_NOT_STICKY;
        ScanInputs.initializePolicy(this);
        if(GATE_STOP.equals(intent.getAction())){cancelGateScan();finishConnection(AppLanguage.t(R.string.ui_032_gate_stopped),false);return START_NOT_STICKY;}
        if(GATE_SCAN.equals(intent.getAction())){
            if(AndroidRoutes.proxyOnly(this)){finishConnection(AppLanguage.t(R.string.ui_033_gate_vpn));return START_NOT_STICKY;}
            if(gateScanning)return START_NOT_STICKY;
            if(!VpnDisclosure.accepted(this)||VpnService.prepare(this)!=null){finishConnection(AppLanguage.t(R.string.ui_extra_016));return START_NOT_STICKY;}
            if(AndroidRoutes.mode(this).equals("domains")){finishConnection(AppLanguage.t(R.string.ui_032_gate_scope));return START_NOT_STICKY;}
            startGateScan(intent.getBooleanExtra("deep",false),intent.getStringExtra("key"),intent.getStringArrayListExtra("keys"));return START_NOT_STICKY;
        }
        if(RECONFIGURE.equals(intent.getAction())||DISCONNECT.equals(intent.getAction())||CONNECT.equals(intent.getAction())||CONNECT_CACHED.equals(intent.getAction())||SCAN_CONNECT.equals(intent.getAction()))cancelGateScan();
        if(RECONFIGURE.equals(intent.getAction())){
            ProxyEndpoint resume=userRequested&&!autoScanning?lastProfile:null;
            userRequested=false;closeTunnel();
            try{
                if(AndroidRoutes.kill(this)&&VpnService.prepare(this)==null){ensureChannel();Notification n=notification("کیل‌سوییچ فعال");if(Build.VERSION.SDK_INT>=34)startForeground(ID,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(ID,n);replaceTun(null);}
                if(resume!=null){userRequested=true;beginConnect(resume);}
            }catch(Exception e){finishConnection("مسیر معتبر نیست: "+e.getMessage());}
            scheduleIdleStop();return START_NOT_STICKY;
        }
        if(RELEASE_GUARD.equals(intent.getAction())){if(phase==DISCONNECTED){closeTunnel();scheduleIdleStop();}return START_NOT_STICKY;}
        if (DISCONNECT.equals(intent.getAction())) {
            clearConnectionError();userRequested = false;
            closeTunnel();
            announce(AppLanguage.t(R.string.ui_extra_011));
            scheduleIdleStop();
            return START_NOT_STICKY;
        }
        if((CONNECT.equals(intent.getAction())||CONNECT_CACHED.equals(intent.getAction())||SCAN_CONNECT.equals(intent.getAction()))&&!AndroidRoutes.proxyOnly(this)&&!VpnDisclosure.accepted(this)){
            finishConnection(AppLanguage.t(R.string.ui_extra_012));return START_NOT_STICKY;
        }
        if(CONNECT_CACHED.equals(intent.getAction())){
            clearConnectionError();final int token=++generation;userRequested=true;updatePhase(CONNECTING);
            try{ensureChannel();Notification notification=notification(AppLanguage.t(R.string.ui_extra_013));
                if(Build.VERSION.SDK_INT>=34)startForeground(ID,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                else startForeground(ID,notification);}
            catch(Exception ex){finishConnection(AppLanguage.t(R.string.ui_extra_014)+ex.getMessage());return START_NOT_STICKY;}
            ProxyStore.IO.execute(()->{
                try{ProxyStore.load(this);String filter=ScanInputs.filter(this);ProxyResults.Sort sort=ScanInputs.sort(this);
                    java.util.List<ProxyEndpoint> choices=ProxyResults.visible(ProxyStore.index().snapshot(),filter,sort,ScanInputs.protocol(this),ScanInputs.pingLimit(this));if(AndroidRoutes.proxyOnly(this))choices.removeIf(p->p.type==ProxyEndpoint.Type.OPENVPN);
                    handler.post(()->{if(token!=generation||!userRequested)return;
                        Intent next=new Intent(this,ProxyVpnService.class).setAction(choices.isEmpty()?SCAN_CONNECT:CONNECT);
                        if(!choices.isEmpty())next.putExtra(EXTRA_ENDPOINT,choices.get(0).key()).putExtra("filter",filter).putExtra("sort",sort.name());
                        onStartCommand(next,0,0);});}
                catch(Exception ex){handler.post(()->{if(token==generation)finishConnection(AppLanguage.t(R.string.ui_extra_015));});}
            });return START_NOT_STICKY;
        }
        if (SCAN_CONNECT.equals(intent.getAction())) {
            clearConnectionError();
            if (autoScanning) return START_NOT_STICKY;
            if (!AndroidRoutes.proxyOnly(this)&&VpnService.prepare(this) != null) {
                finishConnection(AppLanguage.t(R.string.ui_extra_016)); return START_NOT_STICKY;
            }
            newSessionPending=true;
            userRequested=true;
            requiredFilter=ScanInputs.filter(this);requiredSort=ScanInputs.sort(this);
            recoveryLog.setLength(0);beginAutoScan();return START_NOT_STICKY;
        }
        if (CONNECT.equals(intent.getAction())) {
            clearConnectionError();
            String input = intent.getStringExtra(EXTRA_ENDPOINT);
            ProxyEndpoint e = ProxyEndpoint.parse(input);
            if (e == null) { announce(AppLanguage.t(R.string.ui_extra_017)); scheduleIdleStop(); return START_NOT_STICKY; }
            newSessionPending=true;
            if(!DistributionPolicy.allows(e)){finishConnection(AppLanguage.t(R.string.ui_extra_018));return START_NOT_STICKY;}
            userRequested = true;
            requiredFilter = intent.getStringExtra("filter");
            if (requiredFilter == null) requiredFilter = "ALL";
            try { requiredSort = ProxyResults.Sort.valueOf(intent.getStringExtra("sort")); }
            catch (Exception ignored) { requiredSort = ProxyResults.Sort.QUALITY; }
            failover.reset(e.key()); recoveryLog.setLength(0);
            try{String evidence=intent.getStringExtra("evidence");if(evidence!=null){ProxyEndpoint snapshot=ProxyEndpoint.fromJson(new org.json.JSONObject(evidence));if(snapshot.key().equals(e.key())&&ConnectionFastPath.recent(snapshot,System.currentTimeMillis()))e=snapshot;}}catch(Exception ignored){}
            beginConnect(e);
        }
        return START_NOT_STICKY;
    }

    private void beginAutoScan() {
        closeTunnel();
        resetTrafficSession();
        final int token=++generation;
        cancelled=new AtomicBoolean(false);
        autoScanning=true;autoDone=autoTotal=0;autoSelectionScheduled=false;lastScanAnnouncement=0;
        autoCheckpoint=null;autoFinishing=false;
        autoSelection=new AutoScanSelection(requiredFilter,requiredSort,ScanInputs.protocol(this),ScanInputs.pingLimit(this));
        autoReport=AppLanguage.t(R.string.ui_extra_019)+ProxyResults.label(requiredFilter);
        updatePhase(CONNECTING);
        try {
            ensureChannel();
            Notification n=notification(AppLanguage.t(R.string.ui_extra_020));
            if(Build.VERSION.SDK_INT>=34)startForeground(ID,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(ID,n);
        }catch(Throwable error){finishConnection(AppLanguage.t(R.string.ui_extra_021)+error.getMessage());return;}
        announce(AppLanguage.t(R.string.ui_extra_022)+ProxyResults.label(requiredFilter)+"»");
        final ProxyScanner.Options options = ScanInputs.options(this);
        ProxyStore.IO.execute(()->{
        try {
            ProxyStore.load(this);
            ScanFiles files = new ScanFiles(getFilesDir());
            java.util.List<ProxyEndpoint> candidates = SourceDocuments.country(SourceDocuments.iranFirst(files.catalog()),getSharedPreferences("persiansocks",0).getString("scan-country",""));
            if (candidates.isEmpty()) { handler.post(()->{if(token==generation)finishConnection(AppLanguage.t(R.string.ui_extra_023));}); return; }
            if(!autoScanning||token!=generation)return;
            autoCheckpoint = new ScanCheckpoint(candidates, options, "CATALOG");files.activate(autoCheckpoint);
            final ScanCheckpoint scan=autoCheckpoint;
            PhysicalTestNetwork network = PhysicalTestNetwork.capture(this);
            automaticScanner = new PreparedScanner();
            automaticScanner.start(autoCheckpoint, state->{if(!state.failedBatchKeys().isEmpty())ProxyStore.flush(ProxyVpnService.this);files.save(state);}, network, network, new PreparedScanner.Listener() {
                public void onProgress(int done, int total, int good) { handler.post(() -> {
                    if (!autoScanning || token != generation) return;
                    autoDone = done; autoTotal = total;
                    if(!options.scanAll&&!autoFinishing){
                        if(autoSelection.haveBackups()){finishAutoScan(token);return;}
                        if(autoSelection.best()!=null&&!autoSelectionScheduled){autoSelectionScheduled=true;handler.postDelayed(()->finishAutoScan(token),5000);}
                    }
                    autoReport = AppLanguage.t(R.string.ui_extra_024) + done + AppLanguage.t(R.string.ui_extra_025) + total + AppLanguage.t(R.string.ui_extra_026) + good;
                    scanAnnouncement(AppLanguage.t(R.string.ui_extra_027) + done + AppLanguage.t(R.string.ui_extra_025) + total + AppLanguage.t(R.string.ui_extra_028) + good);
                }); }
                public void onResult(int index,Socks5Probe.Result result) {
                    if(!autoScanning||token!=generation)return;
                    QualityHistory.probe(network,scan.candidate(index),result);SourceQuality.probe(network,scan,scan.candidate(index),result);
                    if(result.endpoint!=null){ProxyStore.index().accept(result.endpoint);autoSelection.add(result.endpoint);}
                    else if(ProbeFailurePolicy.invalidates(result.failure)&&ProxyStore.index().find(scan.candidate(index).key())!=null)
                        ProxyStore.index().reject(scan.candidate(index).key(),scan.finishedAt(index));
                }
                public void onFinished(ScanCheckpoint state, String message) { handler.post(() -> {
                    if (!autoScanning || token != generation) return;
                    autoCheckpoint = state; autoReport += "\n" + message; finishAutoScan(token);
                }); }
            });
        } catch (Exception ex) { handler.post(()->{if(token==generation)finishConnection(AppLanguage.t(R.string.ui_extra_029) + ex.getMessage());}); }
        });
    }
    private void scanAnnouncement(String message){
        long now=android.os.SystemClock.elapsedRealtime();
        if(now-lastScanAnnouncement<1500)return;
        lastScanAnnouncement=now;
        announce(message);
        getSystemService(NotificationManager.class).notify(ID,notification(message));
    }
    private void finishAutoScan(int token){
        if(!autoScanning||token!=generation||autoFinishing)return;autoFinishing=true;
        PreparedScanner runner=automaticScanner;if(runner!=null)runner.requestStop();
        final AutoScanSelection selection=autoSelection;final ScanCheckpoint checkpoint=autoCheckpoint;
        ProxyStore.IO.execute(()->{
            ScanCheckpoint saved=checkpoint;
            try{if(runner!=null){ScanCheckpoint paused=runner.pause();if(paused!=null)saved=paused;}}
            catch(Exception ex){android.util.Log.e("PersianSocks","Auto scan save failed",ex);}
            if(saved!=null){ProxyStore.index().apply(saved);for(ProxyEndpoint p:saved.good())selection.add(p);}
            ProxyStore.save(this,ProxyStore.index());
            final ProxyEndpoint chosen=selection.best();final ScanCheckpoint progress=saved;
            handler.post(()->{
                if(token!=generation||!autoScanning)return;
                automaticScanner=null;autoScanning=false;autoFinishing=false;autoCheckpoint=null;
                if(progress!=null){autoDone=progress.done();autoTotal=progress.total();}
                getSharedPreferences("persiansocks",MODE_PRIVATE).edit().putString("scan-report",autoReport+"\n"+autoDone+AppLanguage.t(R.string.ui_extra_025)+autoTotal).apply();
                if(chosen==null){finishConnection(AppLanguage.t(R.string.ui_extra_030));return;}
                failover.reset(chosen.key());beginConnect(chosen);announce(AppLanguage.t(R.string.ui_extra_031)+chosen.key());
            });
        });
    }
    private PhysicalTestNetwork tunnelPhysical;
    private long qualityStarted;private String qualityScope="other",qualityKey="";
    private void finishQuality(boolean failure){if(qualityStarted!=0){QualityHistory.session(qualityScope,qualityKey,Math.max(0,android.os.SystemClock.elapsedRealtime()-qualityStarted),failure);qualityStarted=0;qualityKey="";}}
    private void beginConnect(ProxyEndpoint proxy) {
        if(proxy.type==ProxyEndpoint.Type.OPENVPN){if(AndroidRoutes.proxyOnly(this)){finishConnection(AppLanguage.t(R.string.ui_033_gate_vpn));return;}beginGateConnection(proxy);return;}
        if(!DistributionPolicy.allows(proxy)){finishConnection(AppLanguage.t(R.string.ui_extra_032));return;}
        // Automatic retries keep the last error visible until success or a new user request.
        closeTunnel();
        resetTrafficSession();
        int token = ++generation;
        cancelled = new AtomicBoolean(false);
        AtomicBoolean stop = cancelled;
        current = proxy.key();
        updatePhase(CONNECTING);
        lastProfile = proxy;
        saveVpnReport(AppLanguage.t(R.string.ui_extra_033) + current + AppLanguage.t(R.string.ui_extra_034));
        try {
            ensureChannel();
            Notification n = notification(AppLanguage.t(R.string.ui_extra_035));
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(ID, n);
        } catch (Throwable t) { saveVpnReport(AppLanguage.t(R.string.ui_extra_036) + t); announce(AppLanguage.t(R.string.ui_extra_037) + t.getMessage()); userRequested=false;closeTunnel(); scheduleIdleStop(); return; }
        announce(AppLanguage.t(R.string.ui_extra_038) + ProxyResults.label(requiredFilter) + "»: " + current);
        final ServiceTargets.Target preflightTarget;
        try{preflightTarget=AndroidRoutes.proxyOnly(this)?ConnectionPreflight.target(requiredFilter,ProxyStore.index().find(proxy.key())):AndroidRoutes.verification(this,new ServiceTargets.Target[]{ConnectionPreflight.target(requiredFilter,ProxyStore.index().find(proxy.key()))})[0];}catch(Exception error){finishConnection("مسیر معتبر نیست: "+error.getMessage());return;}
        try{armGuard();}catch(Exception error){finishConnection("حفاظت آماده نشد: "+error.getMessage());return;}
        new Thread(() -> {
            ProxyEndpoint cached=ConnectionFastPath.recent(proxy,System.currentTimeMillis())?proxy:ProxyStore.index().find(proxy.key());
            if(ConnectionFastPath.recent(cached,System.currentTimeMillis())){
                try{PhysicalTestNetwork fresh=PhysicalTestNetwork.capture(this);handler.post(()->{if(token!=generation||stop.get())return;tunnelPhysical=fresh;lastProfile=cached;if(networkMonitor!=null)networkMonitor.activate(fresh.network());openTunnel(cached,token);});return;}catch(Exception error){handler.post(()->{if(token==generation)waitForNetwork();});return;}
            }
            final PhysicalTestNetwork[] physical={null};
            Socks5Probe.Result checked=ConnectionPreflight.run(()->{
                PhysicalTestNetwork path=PhysicalTestNetwork.capture(this);physical[0]=path;
                return ConnectionPreflight.inspect(proxy,preflightTarget,stop,probeSockets,path::socket,path::resolve);
            },Thread::sleep,stop,attempt->handler.post(()->{if(token==generation&&!stop.get()&&attempt>1)announce(AppLanguage.t(R.string.ui_connect_retry)+attempt+"/3");}));
            final Socks5Probe.Result verified=checked;final PhysicalTestNetwork chosenPhysical=physical[0];
            if(chosenPhysical!=null&&!stop.get())QualityHistory.probe(chosenPhysical,proxy,verified);
            handler.post(() -> {
                if (token != generation || stop.get()) return;
                if(chosenPhysical!=null&&!chosenPhysical.valid()){waitForNetwork();return;}
                if (verified.endpoint == null) {
                    String msg = AppLanguage.t(R.string.ui_extra_039) + Socks5Probe.describe(verified.failure);
                    if (verified.endpoint != null) msg = AppLanguage.t(R.string.ui_extra_040) + ProxyResults.label(requiredFilter) + AppLanguage.t(R.string.ui_extra_041);
                    failConnection(msg,verified.endpoint==null&&ProbeFailurePolicy.invalidates(verified.failure)); return;
                }
                lastProfile = verified.endpoint;tunnelPhysical=chosenPhysical;
                if(networkMonitor!=null)networkMonitor.activate(chosenPhysical.network());
                if(!chosenPhysical.valid()||(networkMonitor!=null&&networkMonitor.activeBroken())){waitForNetwork();return;}
                announce(AppLanguage.t(R.string.ui_extra_042) + verified.endpoint.passedServices());
                openTunnel(verified.endpoint,token);
            });
        }, "vpn-service-preflight").start();
    }
    private void replaceTun(PhysicalTestNetwork path)throws Exception {
        if(retiredTuns.size()>=8)throw new java.io.IOException("VPN routing is still settling");
        Builder b=new Builder().setSession("PersianSocks • protected").setBlocking(false).setMtu(1400)
            .addAddress("198.18.0.1",15).addRoute("0.0.0.0",0)
            .addAddress("fd00:1:fd00:1::2",64).addRoute("::",0).addDnsServer("198.18.0.2");
        if(path!=null)b.setUnderlyingNetworks(new Network[]{path.network()});
        SplitTunnelSettings.apply(this,b);
        ParcelFileDescriptor next=b.establish();if(next==null)throw new java.io.IOException("VPN permission not available");
        ParcelFileDescriptor old=tun;tun=next;tunKind="socks";if(old!=null)retiredTuns.add(old);retireOldTuns(0);
    }
    private void retireOldTuns(int attempt){
        if(tun==null||retiredTuns.isEmpty())return;final ParcelFileDescriptor currentTun=tun;
        String name=TunLinkInfo.name(currentTun);boolean ready=false;ConnectivityManager manager=getSystemService(ConnectivityManager.class);
        Network active=manager.getActiveNetwork();NetworkCapabilities caps=active==null?null:manager.getNetworkCapabilities(active);android.net.LinkProperties links=active==null?null:manager.getLinkProperties(active);if(!name.isEmpty()&&caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&links!=null&&name.equals(links.getInterfaceName()))ready=true;
        if(ready){for(ParcelFileDescriptor old:retiredTuns)try{old.close();}catch(Exception ignored){}retiredTuns.clear();}
        else if(attempt<30)handler.postDelayed(()->{if(tun==currentTun)retireOldTuns(attempt+1);},200);
    }
    private void stopGateSession(){if(gateSession!=null){OpenVpnSession session=gateSession;gateSession=null;session.close();}gateVerifying=false;}
    private String gateProgress(){return AppLanguage.t(R.string.ui_gate_scan_progress)+" "+gateDone+"/"+gateTotal+" • "+AppLanguage.t(R.string.ui_gate_scan_passed)+" "+gatePassed;}
    private void cancelGateScan(){gateRun++;gateScanning=false;gateAttemptRunning=false;for(DatagramSocket s:gateProbeDatagrams)s.close();gateProbeDatagrams.clear();gateProtocolResults=new ConcurrentHashMap<>();if(gatePreparePool!=null){gatePreparePool.shutdownNow();gatePreparePool=null;}if(gateResolver!=null){gateResolver.close();gateResolver=null;}gatePlan=null;if(gatePortPool!=null){gatePortPool.shutdownNow();gatePortPool=null;}for(Socket socket:gatePortSockets)try{socket.close();}catch(Exception ignored){}gatePortSockets.clear();gateQueue=java.util.Collections.emptyList();scanGateStore=null;}
    private void startGateScan(boolean deep,String onlyKey,java.util.ArrayList<String> keys){
        gateRestore=phase==CONNECTED?current:"";closeTunnel();gateDeep=deep;gateFirstSuccess="";gateDone=gatePassed=gateTotal=0;gateScanning=true;gateScanStarted=System.currentTimeMillis();userRequested=true;requiredFilter="ALL";final int run=++gateRun;
        try{ensureChannel();Notification n=notification(AppLanguage.t(R.string.ui_gate_scan_progress));if(Build.VERSION.SDK_INT>=34)startForeground(ID,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(ID,n);armGuard();}
        catch(Exception e){cancelGateScan();finishConnection(e.getMessage());return;}
        String country=getSharedPreferences("persiansocks",0).getString("display-country","");
        new Thread(()->{try{VpnGateStore store=new VpnGateStore(getFilesDir());store.load();java.util.List<VpnGateProfile> profiles=store.snapshot();if(keys!=null)profiles.removeIf(p->!keys.contains(p.key()));profiles.removeIf(p->onlyKey!=null?!onlyKey.equals(p.key()):!country.isEmpty()&&!country.equals(p.country));
            // Round-robin countries within untested/oldest-first order. An interrupted scan resumes with the remaining candidates first.
            profiles.sort(java.util.Comparator.comparingInt((VpnGateProfile p)->p.vpnSuccess?0:1).thenComparingLong(p->p.vpnTestedAt).thenComparingLong(p->p.portLatency<0?Long.MAX_VALUE:p.portLatency));
            java.util.Map<String,java.util.ArrayDeque<VpnGateProfile>> countries=new java.util.LinkedHashMap<>();for(VpnGateProfile p:profiles)countries.computeIfAbsent(p.country,k->new java.util.ArrayDeque<>()).add(p);
            java.util.List<VpnGateProfile> balanced=new java.util.ArrayList<>();while(balanced.size()<profiles.size())for(java.util.ArrayDeque<VpnGateProfile> bucket:countries.values())if(!bucket.isEmpty())balanced.add(bucket.remove());
            handler.post(()->{if(!gateScanning||run!=gateRun)return;scanGateStore=store;gateQueue=balanced;gateTotal=balanced.size();gatePlan=new VpnGateScanPlan(balanced);gateResolver=new VpnGateDirectory(this,message->{});gateResolver.scanDownloads(!deep);gatePreparePool=java.util.concurrent.Executors.newFixedThreadPool(VpnGateScanPlan.DOWNLOADS);startGatePortChecks(run);nextGateScan(run);});
        }catch(Exception e){handler.post(()->{if(run==gateRun){cancelGateScan();finishConnection(e.getMessage());}});}},"gate-scan-plan").start();
    }
    private void startGatePortChecks(int run){
        gatePortPool=java.util.concurrent.Executors.newFixedThreadPool(16);final VpnGateStore store=scanGateStore;final long started=gateScanStarted;final boolean deep=gateDeep;final java.util.Map<String,Boolean> protocol=gateProtocolResults;
        for(VpnGateProfile candidate:gateQueue)if(candidate.tcp)gatePortPool.execute(()->{
            if(!gateScanning||run!=gateRun)return;long latency=-1;String error="";
            try{PhysicalTestNetwork path=PhysicalTestNetwork.capture(this);long begin=System.nanoTime();try(Socket socket=path.socket()){gatePortSockets.add(socket);try{
                if(!gateScanning||run!=gateRun)return;socket.connect(new java.net.InetSocketAddress(candidate.ip,candidate.port),deep?4000:1500);if(!path.valid())throw new java.io.IOException("Network changed");latency=Math.max(1,(System.nanoTime()-begin)/1000000);
                if(!deep){boolean response=false;try{response=OpenVpnReachability.tcp(socket,2000);}catch(Exception ignored){}if(gateScanning&&run==gateRun)protocol.put(candidate.key(),response);}
            }finally{gatePortSockets.remove(socket);}}}
            catch(Exception e){error=String.valueOf(e.getMessage());}
            if(gateScanning&&run==gateRun){store.tested(candidate.key(),latency,error);candidate.portTestedAt=Math.max(started,candidate.portTestedAt);handler.post(()->nextGateScan(run));}
        });
    }
    private void prepareGateProfiles(int run){
        final VpnGateScanPlan plan=gatePlan;final VpnGateDirectory resolver=gateResolver;final VpnGateStore store=scanGateStore;final boolean deep=gateDeep;final java.util.Map<String,Boolean> protocol=gateProtocolResults;
        for(VpnGateScanPlan.Item item:plan.reserve(p->{VpnGateProfile measured=store.get(p.key());return !p.tcp||(measured!=null&&measured.portTestedAt>=gateScanStarted);})){gatePreparePool.execute(()->{
            VpnGateProfile profile=null;String error="";
            try{if(run!=gateRun||!gateScanning)return;VpnGateProfile measured=store.get(item.candidate.key());if(!deep&&item.candidate.tcp&&measured.portLatency<0)throw new java.io.IOException("TCP: "+measured.lastError);
                profile=resolver.resolve(item.candidate);if(run!=gateRun||!gateScanning)return;if(item.candidate.lazy())store.resolved(profile);
                if(!deep&&!OpenVpnReachability.needsKey(profile)){
                    boolean response;if(profile.tcp)response=Boolean.TRUE.equals(protocol.get(item.candidate.key()));else{
                        PhysicalTestNetwork path=PhysicalTestNetwork.capture(this);try(DatagramSocket socket=path.datagram()){gateProbeDatagrams.add(socket);try{if(!gateScanning||run!=gateRun)return;socket.connect(new java.net.InetSocketAddress(profile.ip,profile.port));response=OpenVpnReachability.udp(socket,2500);if(!path.valid())throw new java.io.IOException("Network changed");}finally{gateProbeDatagrams.remove(socket);}}
                    }
                    if(!response)throw new java.io.IOException("Fast scan: no OpenVPN control response; deep scan can retry");
                }
            }catch(Exception e){error=String.valueOf(e.getMessage());}
            plan.prepared(item,profile,error);handler.post(()->nextGateScan(run));
        });}
    }
    private void nextGateScan(int run){
        if(!gateScanning||run!=gateRun)return;
        prepareGateProfiles(run);if(gateAttemptRunning)return;
        VpnGateScanPlan.Item next=gatePlan.takeReady();
        if(next==null&&!gatePlan.finished())return; // Preparation/port callbacks wake the scheduler; no polling or head-of-line wait.
        if(next==null){
            String restore=gateRestore.isEmpty()?gateFirstSuccess:gateRestore;String summary=gateProgress();cancelGateScan();closeTunnel();announce(summary);ProxyEndpoint endpoint=ProxyEndpoint.parse(restore);if(endpoint!=null){userRequested=true;beginConnect(endpoint);}else{userRequested=false;scheduleIdleStop();}return;
        }
        if(!physicalNetworkAvailable()){cancelGateScan();finishConnection(AppLanguage.t(R.string.ui_extra_070));return;}
        gateAttemptRunning=true;VpnGateProfile candidate=next.profile==null?next.candidate:next.profile;current=candidate.key();lastProfile=candidate.endpoint();announce(gateProgress()+" • "+candidate.country+" • "+candidate.ip);
        prepareGateProfiles(run);
        if(next.profile==null||!next.error.isEmpty()){gateScanResult(false,next.error);return;}
        userRequested=true;beginGateConnection(candidate.endpoint(),candidate);
    }
    private void gateScanResult(boolean success,String error){
        if(!gateScanning)return;final int run=gateRun;final String key=current;gateDone++;if(success){gatePassed++;if(gateFirstSuccess.isEmpty())gateFirstSuccess=key;}
        if(!success&&!key.isEmpty()){ProxyStore.index().reject(key,System.currentTimeMillis());ProxyStore.save(this,ProxyStore.index());}
        closeTunnel();final String summary=gateProgress();
        final VpnGateStore results=scanGateStore;
        new Thread(()->{try{results.vpnResult(key,success,error);}catch(Exception e){Log.w("PersianSocks","Gate scan result save failed",e);}
            handler.post(()->{if(!gateScanning||run!=gateRun)return;announce(summary);VpnGateProfile p=results.get(key);Intent result=new Intent(STATE).setPackage(getPackageName()).putExtra("gate-result",true).putExtra("gate-key",key).putExtra("gate-ok",success).putExtra("gate-error",p==null?error:p.vpnError).putExtra("gate-at",p==null?System.currentTimeMillis():p.vpnTestedAt).putExtra("gate-latency",p==null?-1:p.portLatency);sendBroadcast(result,INTERNAL_PERMISSION);gateAttemptRunning=false;nextGateScan(run);});
        },"gate-scan-result").start();
    }
    private void beginGateConnection(ProxyEndpoint proxy){beginGateConnection(proxy,null);}
    private void beginGateConnection(ProxyEndpoint proxy,VpnGateProfile prepared){
        if(AndroidRoutes.mode(this).equals("domains")){finishConnection(AppLanguage.t(R.string.ui_032_gate_scope));return;}
        closeTunnel();resetTrafficSession();final int token=++generation;cancelled=new AtomicBoolean(false);current=proxy.key();lastProfile=proxy;gateVerifying=false;gateReceived=gateSent=0;updatePhase(CONNECTING);
        try{ensureChannel();Notification n=notification("VPN Gate • OpenVPN");if(Build.VERSION.SDK_INT>=34)startForeground(ID,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(ID,n);armGuard();}
        catch(Exception error){finishConnection(error.getMessage());return;}
        announce(AppLanguage.t(R.string.ui_032_gate_connecting));
        new Thread(()->{
            try{if(token!=generation)return;VpnGateStore store=gateScanning?scanGateStore:new VpnGateStore(getFilesDir());if(!gateScanning)store.load();VpnGateProfile candidate=prepared==null?store.get(proxy.key()):prepared;if(candidate==null)throw new java.io.IOException(AppLanguage.t(R.string.ui_032_gate_refresh));VpnGateProfile profile=VpnGateDirectory.resolve(this,candidate);if(candidate.lazy()&&token==generation){store.resolved(profile);if(!gateScanning)store.save();}PhysicalTestNetwork path=PhysicalTestNetwork.capture(this);
                handler.post(()->{if(token!=generation||cancelled.get())return;current=profile.key();lastProfile=profile.endpoint();tunnelPhysical=path;if(networkMonitor!=null)networkMonitor.activate(path.network());trafficMeter.beginEngine(System.nanoTime());
                    NativeSocketProtector upstream=new NativeSocketProtector(this,path);
                    gateSession=new OpenVpnSession(this,profile,new OpenVpnSession.Listener(){
                        public boolean protect(java.io.FileDescriptor fd)throws Exception{if(token!=generation||cancelled.get()||!path.valid())return false;return upstream.protect(fd);}
                        public ParcelFileDescriptor openTun(OpenVpnSession.TunConfig config)throws Exception{java.util.concurrent.FutureTask<ParcelFileDescriptor> task=new java.util.concurrent.FutureTask<>(()->{if(token!=generation||cancelled.get())throw new java.io.IOException("Cancelled");return openGateTun(path,config);});handler.post(task);return task.get(8,java.util.concurrent.TimeUnit.SECONDS);}
                        public void connected(){handler.post(()->{if(token!=generation||cancelled.get()||gateVerifying)return;gateVerifying=true;verifyNetwork(token,0);});}
                        public void reconnecting(){handler.post(()->{if(token!=generation||cancelled.get())return;gateVerifying=false;updatePhase(CONNECTING);announce(AppLanguage.t(R.string.ui_032_gate_reconnecting));});}
                        public void failed(String message){handler.post(()->{if(token!=generation||cancelled.get())return;saveVpnReport(message);failConnection(message);});}
                        public void bytes(long received,long sent){handler.post(()->{if(token!=generation||cancelled.get())return;gateReceived=received;gateSent=sent;sampleTraffic(true);});}
                    },gateScanning&&!gateDeep?40:150,gateScanning&&!gateDeep);gateSession.start();
                });
            }catch(Exception error){handler.post(()->{if(token==generation)finishConnection(error.getMessage());});}
        },"vpngate-prepare").start();
    }
    private ParcelFileDescriptor openGateTun(PhysicalTestNetwork path,OpenVpnSession.TunConfig config)throws Exception{
        if(config.ipv4.isEmpty())throw new java.io.IOException("VPN Gate did not assign an IPv4 address");
        String signature=config.ipv4+"|"+config.ipv6+"|"+config.mtu+"|"+config.dns;
        String previous=gateTunConfig==null?"":gateTunConfig.ipv4+"|"+gateTunConfig.ipv6+"|"+gateTunConfig.mtu+"|"+gateTunConfig.dns;
        if(tun!=null&&tunKind.equals("openvpn")&&signature.equals(previous)){setUnderlyingNetworks(new Network[]{path.network()});return ParcelFileDescriptor.dup(tun.getFileDescriptor());}
        if(retiredTuns.size()>=8)throw new java.io.IOException("VPN routing is still settling; try again shortly");
        Builder builder=new Builder().setSession("PersianSocks • VPN Gate").setBlocking(false).setMtu(config.mtu).addAddress(config.ipv4,32).addRoute("0.0.0.0",0).addRoute("::",0).setUnderlyingNetworks(new Network[]{path.network()});
        if(config.ipv6.isEmpty())builder.addAddress("fd00:1:fd00:1::2",64);else{String[] address=config.ipv6.split("/");builder.addAddress(address[0],address.length>1?Integer.parseInt(address[1]):128);}
        if(config.dns.isEmpty()){builder.addDnsServer("9.9.9.9");builder.addDnsServer("1.1.1.1");}else for(String dns:config.dns)builder.addDnsServer(dns);
        SplitTunnelSettings.apply(this,builder);ParcelFileDescriptor next=builder.establish();if(next==null)throw new java.io.IOException("VPN permission unavailable");ParcelFileDescriptor previousTun=tun;tun=next;tunKind="openvpn";gateTunConfig=config;if(previousTun!=null)retiredTuns.add(previousTun);retireOldTuns(0);return ParcelFileDescriptor.dup(tun.getFileDescriptor());
    }
    private void armGuard()throws Exception {if(AndroidRoutes.kill(this)&&tun==null)replaceTun(null);}
    private void openTunnel(ProxyEndpoint proxy, int thisGeneration) {
        ensureChannel();
        Notification n = notification(AppLanguage.t(R.string.ui_extra_043));
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(ID, n);
            final PhysicalTestNetwork path=tunnelPhysical;if(path==null||!path.valid())throw new java.io.IOException("Physical network changed before tunnel start");
            gateway = new ProtectedSocksGateway(proxy, new ProtectedSocksGateway.Protector() {
                public boolean tcp(Socket s) {try{if(!AndroidRoutes.proxyOnly(ProxyVpnService.this)&&!protect(s))return false;path.bind(s);return true;}catch(Exception ex){return false;}}
                public boolean udp(DatagramSocket s) {try{if(!AndroidRoutes.proxyOnly(ProxyVpnService.this)&&!protect(s))return false;path.bind(s);return true;}catch(Exception ex){return false;}}
                public InetAddress resolve(String host)throws Exception {return path.resolve(host);}
            },ProtectedSocksGateway.TCP_IDLE_MILLIS,AndroidRoutes.proxyOnly(this)?new DomainRoutes(java.util.Collections.emptyList()):AndroidRoutes.load(this),AndroidRoutes.proxyOnly(this)?10818:0,AndroidRoutes.proxyOnly(this)?AndroidRoutes.localCredential(this,"username"):null,AndroidRoutes.proxyOnly(this)?AndroidRoutes.localCredential(this,"password"):null);
            if(AndroidRoutes.proxyOnly(this)){localProxyRunning=true;trafficMeter.beginEngine(System.nanoTime());verifyLocalProxy(thisGeneration,true);return;}
            Socks5Probe.setResolver(path::resolve);Socks5Probe.setSocketMaker(path::socket);
            // The guard already owns the routes for this scope. Replacing its
            // interface here creates an unnecessary Android network handover.
            // Scope changes explicitly rebuild it in RECONFIGURE instead.
            if(tun==null||!tunKind.equals("socks"))replaceTun(path);
            else if(!setUnderlyingNetworks(new Network[]{path.network()}))
                throw new java.io.IOException("Unable to update VPN underlying network");
            String yaml = "tunnel:\n" +
                "  mtu: 1400\n" +
                "  ipv4: 198.18.0.1\n" +
                "  ipv6: 'fd00:1:fd00:1::2'\n" +
                "  multi-queue: false\n" +
                "socks5:\n" +
                "  address: 127.0.0.1\n" +
                "  port: " + gateway.port() + "\n" +
                "  username: '" + gateway.username() + "'\n" +
                "  password: '" + gateway.password() + "'\n" +
                "  udp: 'udp'\n" +
                "mapdns:\n" +
                "  address: 198.18.0.2\n" +
                "  port: 53\n" +
                "  network: 198.19.0.0\n" +
                "  netmask: 255.255.0.0\n" +
                "  cache-size: 4096\n" +
                "misc:\n" +
                "  max-session-count: " + ProtectedSocksGateway.MAX_CONNECTIONS + "\n" +
                "  connect-timeout: 20000\n" +
                "  tcp-read-write-timeout: " + ProtectedSocksGateway.TCP_IDLE_MILLIS + "\n" +
                "  log-file: '" + new File(getFilesDir(), "native-tunnel.log").getAbsolutePath() + "'\n" +
                "  log-level: warn\n";
            try (FileOutputStream clear = new FileOutputStream(new File(getFilesDir(), "native-tunnel.log"), false)) {}
            File conf = new File(getFilesDir(), "active-tunnel.yml");
            try (FileOutputStream f = new FileOutputStream(conf)) {
                f.write(yaml.getBytes(StandardCharsets.UTF_8));
            }
            final int fd = tun.getFd();
            // JNI creates its own worker thread and returns immediately.
            trafficMeter.beginEngine(System.nanoTime());
            if (!TProxyService.TProxyStartService(conf.getAbsolutePath(), fd))
                throw new IllegalStateException(AppLanguage.t(R.string.ui_extra_045));
            announce(AppLanguage.t(R.string.ui_extra_046) + current);
            pollRunning(thisGeneration, 0);
        } catch (Throwable t) {
            Log.e("PersianSocks", "Tunnel startup error", t);
            saveVpnReport(AppLanguage.t(R.string.ui_extra_047) + t);
            failConnection(AppLanguage.t(R.string.ui_extra_048) + t.getMessage());
        }
    }
    private void verifyLocalProxy(int token,boolean initial){
        final ProtectedSocksGateway local=gateway;final AtomicBoolean stop=cancelled;final ThreadLocal<String> host=new ThreadLocal<>();
        final ServiceTargets.Target[] targets=ConnectionPreflight.verificationTargets(requiredFilter,lastProfile);
        new Thread(()->{
            TunnelVerifier.Result result=TunnelVerifier.check(new TunnelVerifier.Transport(){
                public Socket create(){return new LoopbackProxySocket(host.get(),local.port(),local.username(),local.password());}
                public InetAddress[] resolve(String name)throws Exception{host.set(name);return new InetAddress[]{InetAddress.getByAddress(new byte[]{1,1,1,1})};}
            },stop,probeSockets,targets,true,"ALL".equals(requiredFilter));
            handler.post(()->{if(token!=generation||stop.get()||gateway!=local)return;if(!result.accepts(requiredFilter)){failConnection(result.report);return;}
                lastProfile=result.profile(lastProfile);ProxyStore.index().accept(lastProfile);ProxyStore.save(this,ProxyStore.index());verifiedServices=lastProfile.passedServices();failover.connected(current);updatePhase(CONNECTED);announce(AppLanguage.t(R.string.ui_033_local_ready),lastProfile);if(initial)monitorLocalProxy(token,0);
            });
        },"local-proxy-verification").start();
    }
    private void monitorLocalProxy(int token,int tick){handler.postDelayed(()->{if(token!=generation||gateway==null||!AndroidRoutes.proxyOnly(this))return;if(tunnelPhysical==null||!tunnelPhysical.valid()){waitForNetwork();return;}sampleTraffic(true);if(tick>0&&tick%30==0)verifyLocalProxy(token,false);monitorLocalProxy(token,tick+1);},1000);}
    private void pollRunning(int token, int attempt) {
        handler.postDelayed(() -> {
            if (token != generation || tun == null) return;
            boolean on = false;
            try { on = TProxyService.TProxyIsRunning(); }
            catch (Throwable t) { Log.e("PersianSocks", "Status failed", t); }
            if (on) {
                announce(AppLanguage.t(R.string.ui_extra_049));
                getSystemService(NotificationManager.class).notify(ID, notification(AppLanguage.t(R.string.ui_extra_050)));
                verifyNetwork(token, 0);
                monitorRunning(token);
            } else if (attempt < 24) pollRunning(token, attempt + 1);
            else {
                failConnection(AppLanguage.t(R.string.ui_extra_051));
            }
        }, 250);
    }
    private void verifyNetwork(int token, int attempt) {
        if (token != generation || cancelled.get()) return;
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        Network network = manager.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : manager.getNetworkCapabilities(network);
        String expectedInterface=tun==null?"":TunLinkInfo.name(tun);android.net.LinkProperties links=network==null?null:manager.getLinkProperties(network);
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)||(gateSession!=null&&!expectedInterface.isEmpty()&&(links==null||!expectedInterface.equals(links.getInterfaceName())))) {
            if (attempt < 24) handler.postDelayed(() -> verifyNetwork(token, attempt + 1), 250);
            else {
                saveVpnReport(AppLanguage.t(R.string.ui_extra_052));
                failConnection(AppLanguage.t(R.string.ui_extra_053));
            }
            return;
        }
        AtomicBoolean stop = cancelled;
        final String verificationFilter=AndroidRoutes.scopedVerification(this)?"ALL":requiredFilter;
        final ServiceTargets.Target[] verificationTargets=AndroidRoutes.verification(this,ConnectionPreflight.verificationTargets(verificationFilter,lastProfile));
        new Thread(() -> {
            TunnelVerifier.Result result = TunnelVerifier.check(new TunnelVerifier.Transport() {
                public Socket create() throws Exception { return network.getSocketFactory().createSocket(); }
                public InetAddress[] resolve(String host) throws Exception { return gateSession!=null?network.getAllByName(host):VpnMappedDns.resolve(network,host); }
            }, stop, probeSockets,verificationTargets,gateScanning&&!gateDeep,"ALL".equals(verificationFilter)&&!(gateScanning&&gateDeep));
            handler.post(() -> {
                if (token != generation || stop.get()) return;
                String stats = "";
                try {
                    long[] s = TProxyService.TProxyGetStats();
                    if (s != null && s.length >= 4) stats = AppLanguage.t(R.string.ui_extra_054) + s[1] + AppLanguage.t(R.string.ui_extra_055) + s[3] + AppLanguage.t(R.string.ui_extra_056);
                } catch (Throwable ignored) {}
                saveVpnReport(AppLanguage.t(R.string.ui_extra_033) + current + "\n" + result.report + stats);
                if (!result.accepts(verificationFilter)) {
                    String detail=gateway==null?"":gateway.lastError();
                    failConnection(AppLanguage.t(R.string.ui_extra_057)+" • "+(detail.isEmpty()?result.report.replace("\n"," • "):detail)); return;
                }
                lastProfile = result.profile(lastProfile);
                ProxyStore.index().accept(lastProfile);ProxyStore.save(this,ProxyStore.index());
                verifiedServices = lastProfile.passedServices();
                failover.connected(current);
                qualityStarted=android.os.SystemClock.elapsedRealtime();qualityKey=current;qualityScope=tunnelPhysical==null?"other":tunnelPhysical.scope();
                updatePhase(CONNECTED);
                announce(AppLanguage.t(R.string.ui_extra_058) + verifiedServices + " • " + current, lastProfile);
                getSystemService(NotificationManager.class).notify(ID, notification(AppLanguage.t(R.string.ui_extra_059) + verifiedServices));
                if(gateScanning){gateScanResult(true,"");return;}
                scheduleHealthCheck(token);
            });
        }, "vpn-real-path-check").start();
    }
    private void resetTrafficSession(){
        if(newSessionPending){newSessionPending=false;trafficMeter.newSession();trafficSnapshot=trafficMeter.stopped();persistTraffic();publishTraffic();}
    }
    private void sampleTraffic(boolean notify){
        if(localProxyRunning&&gateway!=null){trafficSnapshot=trafficMeter.sample(gateway.downloadedBytes(),gateway.uploadedBytes(),System.nanoTime());publishTraffic();long now=android.os.SystemClock.elapsedRealtime();if(now-lastTrafficSave>=10000){persistTraffic();lastTrafficSave=now;}return;}
        if(tun==null)return;
        if(gateSession!=null){trafficSnapshot=trafficMeter.sample(gateReceived,gateSent,System.nanoTime());publishTraffic();long now=android.os.SystemClock.elapsedRealtime();if(now-lastTrafficSave>=10000){persistTraffic();lastTrafficSave=now;}if(notify&&phase==CONNECTED)getSystemService(NotificationManager.class).notify(ID,notification("VPN Gate • "+current));return;}
        try{
            long[] s=TProxyService.TProxyGetStats();
            // JNI indexes: tx bytes at 1 (read from TUN), rx bytes at 3 (written to TUN).
            if(s!=null&&s.length>=4)trafficSnapshot=trafficMeter.sample(s[3],s[1],System.nanoTime());
            else trafficSnapshot=trafficMeter.stopped();
        }catch(Throwable ex){trafficSnapshot=trafficMeter.stopped();}
        publishTraffic();
        long now=android.os.SystemClock.elapsedRealtime();if(now-lastTrafficSave>=10000){persistTraffic();lastTrafficSave=now;}
        if(notify&&phase==CONNECTED)getSystemService(NotificationManager.class).notify(ID,notification(AppLanguage.t(R.string.ui_extra_059)+verifiedServices+" • "+current));
    }
    private void persistTraffic(){
        TrafficMeter.Snapshot t=trafficSnapshot;if(t==null)return;
        getSharedPreferences("persiansocks",MODE_PRIVATE).edit().putLong("traffic-down",t.downloaded).putLong("traffic-up",t.uploaded).apply();
    }
    private void publishTraffic(){sendBroadcast(new Intent(STATE).setPackage(getPackageName()).putExtra("traffic-only",true),INTERNAL_PERMISSION);}
    private void saveVpnReport(String report) {
        if(gateSession!=null)report+="\n"+gateSession.report();
        if(gateway!=null)report+="\nRelay sessions: "+gateway.activeConnections()+"/"+ProtectedSocksGateway.MAX_CONNECTIONS+
            "; local capacity rejections: "+gateway.capacityRejections()+"; last relay error: "+gateway.lastError()+"\n";
        if (recoveryLog.length() > 0) report += AppLanguage.t(R.string.ui_extra_060) + recoveryLog;
        File log = new File(getFilesDir(), "native-tunnel.log");
        if (tun != null && log.exists()) {
            try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(log, "r")) {
                long start = Math.max(0, f.length() - 8192); f.seek(start);
                byte[] bytes = new byte[(int) (f.length() - start)]; f.readFully(bytes);
                report += AppLanguage.t(R.string.ui_extra_061) + new String(bytes, StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }
        getSharedPreferences("persiansocks", MODE_PRIVATE).edit().putString("vpn-report", report).apply();
    }
    private void monitorRunning(int token) {
        handler.postDelayed(() -> {
            if (token != generation || tun == null) return;
            try {
                if (TProxyService.TProxyIsRunning()) { sampleTraffic(true); monitorRunning(token); return; }
            } catch (Throwable t) { Log.e("PersianSocks", "Tunnel monitor failed", t); }
            failConnection(AppLanguage.t(R.string.ui_extra_062));
        }, 1000);
    }
    private boolean autoSwitch() {
        return getSharedPreferences("persiansocks", MODE_PRIVATE).getBoolean("auto-switch", true);
    }
    private boolean physicalNetworkAvailable() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        for (Network net : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(net);
            if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }
    private void scheduleIdleStop(){
        final int stoppedGeneration=generation,stoppedStart=commandStartId;
        handler.postDelayed(()->{if(!destroying&&IdleStopPolicy.allowed(stoppedGeneration,generation,stoppedStart,commandStartId,userRequested)&&tun==null&&!autoScanning){if(stoppedStart>0)stopSelfResult(stoppedStart);else stopSelf();}},1000);
    }
    private void finishConnection(String message){finishConnection(message,true);}
    private void finishConnection(String message,boolean error){
        if(gateScanning){gateScanResult(false,message);return;}
        userRequested=false;saveVpnReport(message);if(!error)clearConnectionError();closeTunnel();if(error)announceError(message);else announce(message);scheduleIdleStop();
    }
    private void failConnection(String reason){failConnection(reason,false);}
    private void failConnection(String reason,boolean invalidates) {
        if(gateScanning){if(tunnelPhysical!=null&&!tunnelPhysical.valid()){cancelGateScan();finishConnection(AppLanguage.t(R.string.ui_extra_070));}else gateScanResult(false,reason);return;}
        // A dead captured route is a phone-network event, not evidence against the proxy.
        if(userRequested&&tunnelPhysical!=null&&(!tunnelPhysical.valid()||(networkMonitor!=null&&networkMonitor.activeBroken()))){waitForNetwork();return;}
        announceError(reason);
        if(invalidates&&userRequested&&physicalNetworkAvailable()&&!current.isEmpty()){ProxyStore.index().reject(current,System.currentTimeMillis());ProxyStore.save(this,ProxyStore.index());}
        if (!userRequested || !autoSwitch()) { finishConnection(reason); return; }
        if (!physicalNetworkAvailable()) { waitForNetwork(); return; }
        String failed = current;
        failover.failed(failed, System.currentTimeMillis());
        recoveryLog.append(failed).append(": ").append(reason).append('\n');
        if (recoveryLog.length() > 6000) recoveryLog.delete(0, recoveryLog.length() - 6000);
        requiredFilter = getSharedPreferences("persiansocks", MODE_PRIVATE).getString("filter", requiredFilter);
        if (requiredFilter.startsWith("https://")) {
            boolean exists = false;
            for (ServiceTargets.Target t : ServiceTargets.current()) if (t.id.equals(requiredFilter)) exists = true;
            if (!exists) requiredFilter = "ALL";
        }
        try { requiredSort = ProxyResults.Sort.valueOf(getSharedPreferences("persiansocks", MODE_PRIVATE).getString("sort", requiredSort.name())); }
        catch (Exception ignored) {}
        QualityHistory.select(this);
        final int token=generation;final String service=requiredFilter;final ProxyResults.Sort order=requiredSort;
        final ProxyResults.Protocol kind=ScanInputs.protocol(this);final ProxyResults.PingLimit limit=ScanInputs.pingLimit(this);
        announce(AppLanguage.t(R.string.ui_extra_063));
        ProxyStore.IO.execute(()->{
            java.util.List<ProxyEndpoint> ranked=ProxyResults.visible(ProxyStore.index().snapshot(),service,order,kind,limit);
            if(failed.startsWith("openvpn://")&&service.equals("ALL")&&(kind==ProxyResults.Protocol.ALL||kind==ProxyResults.Protocol.VPN_GATE))try{
                VpnGateStore directory=new VpnGateStore(getFilesDir());directory.load();java.util.List<VpnGateProfile> candidates=directory.snapshot();String country=getSharedPreferences("persiansocks",0).getString("display-country","");
                candidates.removeIf(p->!country.isEmpty()&&!country.equals(p.country));candidates.sort(java.util.Comparator.comparingLong((VpnGateProfile p)->p.portLatency<0?Long.MAX_VALUE:p.portLatency).thenComparing(java.util.Comparator.comparingLong((VpnGateProfile p)->p.score).reversed()));
                int added=0;for(VpnGateProfile p:candidates){if(added++>=3)break;ranked.add(p.endpoint());}
            }catch(Exception ignored){}
            handler.post(()->{
                if(token!=generation||!userRequested)return;
                ProxyEndpoint next=failover.nextRanked(ranked,failed,System.currentTimeMillis());
                if(next==null){finishConnection(AppLanguage.t(R.string.ui_extra_064));return;}
                recoveryLog.append(AppLanguage.t(R.string.ui_extra_065)).append(next.key()).append('\n');beginConnect(next);
                announce(AppLanguage.t(R.string.ui_extra_066)+failed+" ← "+next.key()+AppLanguage.t(R.string.ui_extra_067));
            });
        });
    }
    private void waitForNetwork() {
        finishQuality(false);
        if(!userRequested)return;
        if(!autoSwitch()){finishConnection(AppLanguage.t(R.string.ui_extra_070));return;}
        waitingForPhysical=true;
        cancelled.set(true);stopGateSession();localProxyRunning=false;
        for (Socket socket : probeSockets) try { socket.close(); } catch (Exception ignored) {}
        handler.removeCallbacksAndMessages(null);
        if(gateway!=null){gateway.close();gateway=null;}
        tunnelPhysical=null;if(networkMonitor!=null)networkMonitor.clearActive();
        Socks5Probe.setSocketMaker(null);Socks5Probe.setResolver(null);
        int token = ++generation;
        trafficSnapshot=trafficMeter.stopped();publishTraffic();
        updatePhase(CONNECTING);
        announceError(AppLanguage.t(R.string.ui_extra_068));
        getSystemService(NotificationManager.class).notify(ID, notification(AppLanguage.t(R.string.ui_extra_069)));
        handler.postDelayed(recoverPhysical,250);
        waitForNetwork(token);
    }
    private void waitForNetwork(int token) {
        handler.postDelayed(() -> {
            if (token != generation || !userRequested) return;
            if (!autoSwitch()) { finishConnection(AppLanguage.t(R.string.ui_extra_070)); return; }
            recoverPhysical.run();
            if(waitingForPhysical)waitForNetwork(token);
        }, 10000);
    }
    private void scheduleHealthCheck(int token) {
        handler.postDelayed(() -> {
            if (token != generation || !userRequested || phase != CONNECTED) return;
            if(tunnelPhysical!=null&&(!tunnelPhysical.valid()||(networkMonitor!=null&&networkMonitor.activeBroken()))){waitForNetwork();return;}
            // Saturation is a local resource condition; defer the extra probe without a failure vote.
            if(gateway!=null&&gateway.nearCapacity()){saveVpnReport("LOCAL_CAPACITY: health probe deferred while relay is busy");scheduleHealthCheck(token);return;}
            if (requiredFilter.startsWith("https://")) {
                boolean exists = false;
                for (ServiceTargets.Target t : ServiceTargets.current()) if (t.id.equals(requiredFilter)) exists = true;
                if (!exists) requiredFilter = "ALL";
            }
            ConnectivityManager manager = getSystemService(ConnectivityManager.class);
            Network network = manager.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : manager.getNetworkCapabilities(network);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                if (failover.health(false)) failConnection(AppLanguage.t(R.string.ui_extra_072));
                else scheduleHealthCheck(token);
                return;
            }
            AtomicBoolean stop = cancelled;
            final ProtectedSocksGateway checkedGateway=gateway;
            final long previousCapacity=checkedGateway==null?0:checkedGateway.capacityRejections();
            final String healthFilter=AndroidRoutes.scopedVerification(this)?"ALL":requiredFilter;
            ServiceTargets.Target[] targets = AndroidRoutes.verification(this,failover.healthTargets(healthFilter, lastProfile));
            new Thread(() -> {
                TunnelVerifier.Result result = TunnelVerifier.check(new TunnelVerifier.Transport() {
                    public Socket create() throws Exception { return network.getSocketFactory().createSocket(); }
                    public InetAddress[] resolve(String host) throws Exception { return gateSession!=null?network.getAllByName(host):VpnMappedDns.resolve(network,host); }
                }, stop, probeSockets, targets, true, healthFilter.equals("ALL"));
                handler.post(() -> {
                    if (token != generation || stop.get() || !userRequested) return;
                    if(checkedGateway!=null&&checkedGateway.capacityRejections()>previousCapacity){saveVpnReport("LOCAL_CAPACITY: health sample inconclusive\n"+result.report);scheduleHealthCheck(token);return;}
                    if (failover.health(result.accepts(healthFilter))) {
                        saveVpnReport(result.report);
                        finishQuality(true);failConnection(AppLanguage.t(R.string.ui_extra_073),true); return;
                    }
                    if (!result.accepts(healthFilter)) announce(AppLanguage.t(R.string.ui_extra_074));
                    else announce(AppLanguage.t(R.string.ui_extra_058) + verifiedServices + " • " + current);
                    if(gateScanning){gateScanResult(true,"");return;}
                scheduleHealthCheck(token);
                });
            }, "vpn-health-check").start();
        }, 30000);
    }
    private void closeTunnel() {
        finishQuality(false);
        if(trafficMeter!=null){sampleTraffic(false);trafficSnapshot=trafficMeter.stopped();persistTraffic();publishTraffic();}
        generation++;tunnelPhysical=null;waitingForPhysical=false;if(networkMonitor!=null)networkMonitor.clearActive();
        PreparedScanner stoppedScanner=automaticScanner;automaticScanner=null;
        if(stoppedScanner!=null){stoppedScanner.requestStop();ProxyStore.IO.execute(()->{try{ScanCheckpoint saved=stoppedScanner.pause();if(saved!=null)ProxyStore.index().apply(saved);ProxyStore.save(this,ProxyStore.index());}catch(Exception ex){android.util.Log.e("PersianSocks","Stop save failed",ex);}});}
        if(autoScanning&&autoSelection!=null){
            getSharedPreferences("persiansocks",MODE_PRIVATE).edit().putString("scan-report",autoReport+AppLanguage.t(R.string.ui_extra_075)).apply();
        }
        autoScanning=false;
        autoCheckpoint=null;
        cancelled.set(true);stopGateSession();localProxyRunning=false;
        Socks5Probe.setSocketMaker(null);Socks5Probe.setResolver(null);
        verifiedServices = "";
        for (Socket s : probeSockets) { try { s.close(); } catch (Exception ignored) {} }probeSockets.clear();
        handler.removeCallbacksAndMessages(null);
        if (gateway != null) { gateway.close(); gateway = null; }
        try { TProxyService.TProxyStopService(); } catch (Throwable ignored) {}
        new File(getFilesDir(), "active-tunnel.yml").delete();
        boolean keep=AndroidRoutes.kill(this)&&!destroying&&VpnService.prepare(this)==null;
        if (!keep && tun != null) { try { tun.close(); } catch (Exception ignored) {} tun = null; }
        if(!keep){for(ParcelFileDescriptor old:retiredTuns)try{old.close();}catch(Exception ignored){}retiredTuns.clear();gateTunConfig=null;tunKind="socks";}else retireOldTuns(0);
        current = "";
        if(tun==null&&!gateScanning&&!userRequested)try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        else try{getSystemService(NotificationManager.class).notify(ID,notification("کیل‌سوییچ فعال؛ مسیرهای VPN تا اتصال مسدودند"));}catch(Exception ignored){}
        updatePhase(DISCONNECTED);
    }
    private void updatePhase(int next) {
        if(next==CONNECTED)clearConnectionError();
        phase = next;PreparedScanner.connectionActive(next==CONNECTED);PreparedScanner.connectionPending(next==CONNECTING);
        activeEndpoint = next == DISCONNECTED ? "" : current;
        Intent i = new Intent(STATE).setPackage(getPackageName()).putExtra(EXTRA_PHASE, phase).putExtra("connection-error",getSharedPreferences("persiansocks",MODE_PRIVATE).getString("connection-error",""));
        sendBroadcast(i,INTERNAL_PERMISSION);
        QuickControls.refresh(this,true);
    }
    private void clearConnectionError(){getSharedPreferences("persiansocks",MODE_PRIVATE).edit().remove("connection-error").remove("connection-error-endpoint").apply();}
    private void announceError(String message){android.content.SharedPreferences.Editor edit=getSharedPreferences("persiansocks",MODE_PRIVATE).edit().putString("connection-error",message);if(!current.isEmpty())edit.putString("connection-error-endpoint",current);edit.apply();announce(message);}
    private void announce(String message) {
        announce(message, null);
    }
    private void announce(String message, ProxyEndpoint profile) {
        if(gateScanning&&!message.startsWith(AppLanguage.t(R.string.ui_gate_scan_progress)))message=gateProgress()+" • "+message;
        Intent i = new Intent(STATE);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_MESSAGE, message);
        i.putExtra(EXTRA_PHASE, phase);i.putExtra("connection-error",getSharedPreferences("persiansocks",MODE_PRIVATE).getString("connection-error",""));
        if (profile != null) i.putExtra("verified-proxy", profile.json().toString());
        sendBroadcast(i,INTERNAL_PERMISSION);
        getSharedPreferences("persiansocks", MODE_PRIVATE).edit().putString("state", message).apply();
        QuickControls.refresh(this,!autoScanning);
    }
    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "PersianSocks VPN", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(AppLanguage.t(R.string.ui_extra_076));
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }
    private Notification notification(String txt) {
        Intent stop = new Intent(this, ProxyVpnService.class).setAction(DISCONNECT);
        PendingIntent stopPi = PendingIntent.getService(this, 5, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent home = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, home, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH)
            .setContentTitle("PersianSocks")
            .setContentText(phase==CONNECTED?trafficSnapshot.rates():txt)
            .setStyle(new Notification.BigTextStyle().bigText(txt+(phase==CONNECTED?"\n"+trafficSnapshot.rates()+
                AppLanguage.t(R.string.ui_extra_077)+TrafficMeter.bytes(trafficSnapshot.downloaded)+AppLanguage.t(R.string.ui_extra_078)+TrafficMeter.bytes(trafficSnapshot.uploaded):"")))
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_qs_vpn)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, autoScanning?AppLanguage.t(R.string.ui_extra_079):AppLanguage.t(R.string.ui_extra_080), stopPi).build())
            .build();
    }
    @Override public void onRevoke() { cancelGateScan();userRequested=false; destroying=true;closeTunnel();destroying=false; announce(AppLanguage.t(R.string.ui_extra_081)); scheduleIdleStop(); super.onRevoke(); }
    @Override public void onDestroy() {
        cancelGateScan();
        destroying=true;userRequested=false;closeTunnel();
        if(networkMonitor!=null){try{networkMonitor.close();}catch(RuntimeException ignored){}networkMonitor=null;}
        if(instance.get()==this)instance.clear();
        super.onDestroy();
    }
}
