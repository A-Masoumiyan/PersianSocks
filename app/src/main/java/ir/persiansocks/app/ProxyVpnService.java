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
    public static final String CONNECT_CACHED="ir.persiansocks.app.CONNECT_CACHED";
    public static final String CONNECT = "ir.persiansocks.app.CONNECT";
    public static final String RECONFIGURE="ir.persiansocks.app.RECONFIGURE";
    public static final String RELEASE_GUARD="ir.persiansocks.app.RELEASE_GUARD";
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
    private ProtectedSocksGateway gateway;
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
            if(destroying||!userRequested||autoScanning)return;
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
        if(RECONFIGURE.equals(intent.getAction())){
            userRequested=false;closeTunnel();try{if(AndroidRoutes.kill(this)&&VpnService.prepare(this)==null){ensureChannel();Notification n=notification("کیل‌سوییچ فعال");if(Build.VERSION.SDK_INT>=34)startForeground(ID,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(ID,n);replaceTun(null);}}catch(Exception e){announceError(e.getMessage());}scheduleIdleStop();return START_NOT_STICKY;
        }
        if(RELEASE_GUARD.equals(intent.getAction())){if(phase==DISCONNECTED){closeTunnel();scheduleIdleStop();}return START_NOT_STICKY;}
        if (DISCONNECT.equals(intent.getAction())) {
            clearConnectionError();userRequested = false;
            closeTunnel();
            announce(AppLanguage.t(R.string.ui_extra_011));
            scheduleIdleStop();
            return START_NOT_STICKY;
        }
        if((CONNECT.equals(intent.getAction())||CONNECT_CACHED.equals(intent.getAction())||SCAN_CONNECT.equals(intent.getAction()))&&!VpnDisclosure.accepted(this)){
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
                    java.util.List<ProxyEndpoint> choices=ProxyResults.visible(ProxyStore.index().snapshot(),filter,sort,ScanInputs.protocol(this),ScanInputs.pingLimit(this));
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
            if (VpnService.prepare(this) != null) {
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
        if(!DistributionPolicy.allows(proxy)){finishConnection(AppLanguage.t(R.string.ui_extra_032));return;}
        clearConnectionError();closeTunnel();
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
        final ServiceTargets.Target preflightTarget=AndroidRoutes.verification(this,new ServiceTargets.Target[]{ConnectionPreflight.target(requiredFilter,ProxyStore.index().find(proxy.key()))})[0];
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
        Builder b=new Builder().setSession("PersianSocks • protected").setBlocking(false).setMtu(1400)
            .addAddress("198.18.0.1",15).addRoute("0.0.0.0",0)
            .addAddress("fd00:1:fd00:1::2",64).addRoute("::",0).addDnsServer("198.18.0.2");
        if(path!=null)b.setUnderlyingNetworks(new Network[]{path.network()});
        SplitTunnelSettings.apply(this,b);
        ParcelFileDescriptor next=b.establish();if(next==null)throw new java.io.IOException("VPN permission not available");
        ParcelFileDescriptor old=tun;tun=next;if(old!=null)old.close();
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
                public boolean tcp(Socket s) {try{if(!protect(s))return false;path.bind(s);return true;}catch(Exception ex){return false;}}
                public boolean udp(DatagramSocket s) {try{if(!protect(s))return false;path.bind(s);return true;}catch(Exception ex){return false;}}
                public InetAddress resolve(String host)throws Exception {return path.resolve(host);}
            },ProtectedSocksGateway.TCP_IDLE_MILLIS,AndroidRoutes.load(this));
            Socks5Probe.setResolver(path::resolve);Socks5Probe.setSocketMaker(path::socket);
            // Keep the old TUN until its replacement is established. An engine
            // stop cannot restore a physical route while the guard descriptor lives.
            replaceTun(path);
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
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            if (attempt < 24) handler.postDelayed(() -> verifyNetwork(token, attempt + 1), 250);
            else {
                saveVpnReport(AppLanguage.t(R.string.ui_extra_052));
                failConnection(AppLanguage.t(R.string.ui_extra_053));
            }
            return;
        }
        AtomicBoolean stop = cancelled;
        final String verificationFilter=AndroidRoutes.load(this).restricted?"ALL":requiredFilter;
        final ServiceTargets.Target[] verificationTargets=AndroidRoutes.verification(this,ConnectionPreflight.verificationTargets(verificationFilter,lastProfile));
        new Thread(() -> {
            TunnelVerifier.Result result = TunnelVerifier.check(new TunnelVerifier.Transport() {
                public Socket create() throws Exception { return network.getSocketFactory().createSocket(); }
                public InetAddress[] resolve(String host) throws Exception { return VpnMappedDns.resolve(network,host); }
            }, stop, probeSockets,verificationTargets,false,"ALL".equals(verificationFilter));
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
                scheduleHealthCheck(token);
            });
        }, "vpn-real-path-check").start();
    }
    private void resetTrafficSession(){
        if(newSessionPending){newSessionPending=false;trafficMeter.newSession();trafficSnapshot=trafficMeter.stopped();persistTraffic();publishTraffic();}
    }
    private void sampleTraffic(boolean notify){
        if(tun==null)return;
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
        userRequested=false;saveVpnReport(message);if(!error)clearConnectionError();closeTunnel();if(error)announceError(message);else announce(message);scheduleIdleStop();
    }
    private void failConnection(String reason){failConnection(reason,false);}
    private void failConnection(String reason,boolean invalidates) {
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
        cancelled.set(true);
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
            final String healthFilter=AndroidRoutes.load(this).restricted?"ALL":requiredFilter;
            ServiceTargets.Target[] targets = AndroidRoutes.verification(this,failover.healthTargets(healthFilter, lastProfile));
            new Thread(() -> {
                TunnelVerifier.Result result = TunnelVerifier.check(new TunnelVerifier.Transport() {
                    public Socket create() throws Exception { return network.getSocketFactory().createSocket(); }
                    public InetAddress[] resolve(String host) throws Exception { return VpnMappedDns.resolve(network,host); }
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
        cancelled.set(true);
        Socks5Probe.setSocketMaker(null);Socks5Probe.setResolver(null);
        verifiedServices = "";
        for (Socket s : probeSockets) { try { s.close(); } catch (Exception ignored) {} }probeSockets.clear();
        handler.removeCallbacksAndMessages(null);
        if (gateway != null) { gateway.close(); gateway = null; }
        try { TProxyService.TProxyStopService(); } catch (Throwable ignored) {}
        new File(getFilesDir(), "active-tunnel.yml").delete();
        boolean keep=AndroidRoutes.kill(this)&&!destroying&&VpnService.prepare(this)==null;
        if (!keep && tun != null) { try { tun.close(); } catch (Exception ignored) {} tun = null; }
        current = "";
        if(tun==null)try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
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
    @Override public void onRevoke() { userRequested=false; destroying=true;closeTunnel();destroying=false; announce(AppLanguage.t(R.string.ui_extra_081)); scheduleIdleStop(); super.onRevoke(); }
    @Override public void onDestroy() {
        destroying=true;userRequested=false;closeTunnel();
        if(networkMonitor!=null){try{networkMonitor.close();}catch(RuntimeException ignored){}networkMonitor=null;}
        if(instance.get()==this)instance.clear();
        super.onDestroy();
    }
}
