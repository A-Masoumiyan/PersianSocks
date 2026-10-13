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
    public static final String DISCONNECT = "ir.persiansocks.app.DISCONNECT";
    public static final String SCAN_CONNECT = "ir.persiansocks.app.SCAN_CONNECT";
    public static final String STATE = "ir.persiansocks.app.STATE";
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
        if(service!=null){service.finishConnection("اتصال به‌دلیل تغییر تنظیم TLS قطع شد",false);return true;}
        return false;
    }
    public static int phase() { return phase; }
    public static boolean protectTestSocket(Socket socket) {
        ProxyVpnService service = instance.get();
        return service == null || service.protect(socket);
    }
    public static String activeEndpoint() { return activeEndpoint; }
    public static boolean autoScanning() { return autoScanning; }
    public static int autoProgress() { return autoTotal<=0?-1:(int)(100L*autoDone/autoTotal); }
    public static void cancelAutoScanFromUi(){
        ProxyVpnService service=instance.get();
        if(service!=null&&autoScanning)service.finishConnection("اسکن خودکار متوقف شد",false);
    }
    private static final String CH = "persiansocks_vpn";
    private static final int ID = 901;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ParcelFileDescriptor tun;
    private volatile int generation = 0;
    private String current = "";
    private ProtectedSocksGateway gateway;
    private AtomicBoolean cancelled = new AtomicBoolean(true);
    private final Set<Socket> probeSockets = ConcurrentHashMap.newKeySet();
    private String verifiedServices = "";
    private boolean userRequested;
    private String requiredFilter = "ALL";
    private ProxyResults.Sort requiredSort = ProxyResults.Sort.FASTEST;
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

    @Override public void onCreate() {
        super.onCreate();
        instance=new java.lang.ref.WeakReference<>(this);
        ScanInputs.initializePolicy(this);
        android.content.SharedPreferences prefs=getSharedPreferences("persiansocks",MODE_PRIVATE);
        trafficMeter=new TrafficMeter(prefs.getLong("traffic-down",0),prefs.getLong("traffic-up",0));
        trafficSnapshot=trafficMeter.stopped();
        ServiceTargets.configure(ServiceTargets.decode(getSharedPreferences("persiansocks", MODE_PRIVATE).getString("test-domains", "[]")));
        try { CountryLookup.initialize(getAssets().open("ip-country.bin")); } catch (Exception ignored) {}
    }

    @Override public IBinder onBind(Intent intent) { return super.onBind(intent); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        ScanInputs.initializePolicy(this);
        if (DISCONNECT.equals(intent.getAction())) {
            clearConnectionError();userRequested = false;
            closeTunnel();
            announce("VPN قطع شد");
            stopSelf();
            return START_NOT_STICKY;
        }
        if((CONNECT.equals(intent.getAction())||CONNECT_CACHED.equals(intent.getAction())||SCAN_CONNECT.equals(intent.getAction()))&&!VpnDisclosure.accepted(this)){
            finishConnection("ابتدا توضیح استفاده از VPN را در برنامه تأیید کن");return START_NOT_STICKY;
        }
        if(CONNECT_CACHED.equals(intent.getAction())){
            clearConnectionError();final int token=++generation;userRequested=true;updatePhase(CONNECTING);
            try{ensureChannel();Notification notification=notification("اتصال VPN");
                if(Build.VERSION.SDK_INT>=34)startForeground(ID,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                else startForeground(ID,notification);}
            catch(Exception ex){finishConnection("شروع اتصال ناموفق بود: "+ex.getMessage());return START_NOT_STICKY;}
            ProxyStore.IO.execute(()->{
                try{ProxyStore.load(this);String filter=ScanInputs.filter(this);ProxyResults.Sort sort=ScanInputs.sort(this);
                    java.util.List<ProxyEndpoint> choices=ProxyResults.visible(ProxyStore.index().snapshot(),filter,sort,ScanInputs.protocol(this),ScanInputs.pingLimit(this));
                    handler.post(()->{if(token!=generation||!userRequested)return;
                        Intent next=new Intent(this,ProxyVpnService.class).setAction(choices.isEmpty()?SCAN_CONNECT:CONNECT);
                        if(!choices.isEmpty())next.putExtra(EXTRA_ENDPOINT,choices.get(0).key()).putExtra("filter",filter).putExtra("sort",sort.name());
                        onStartCommand(next,0,0);});}
                catch(Exception ex){handler.post(()->{if(token==generation)finishConnection("خواندن پروکسی‌های ذخیره‌شده ناموفق بود");});}
            });return START_NOT_STICKY;
        }
        if (SCAN_CONNECT.equals(intent.getAction())) {
            clearConnectionError();
            if (autoScanning) return START_NOT_STICKY;
            if (VpnService.prepare(this) != null) {
                finishConnection("برای اتصال از ویجت، ابتدا مجوز VPN را تأیید کن"); return START_NOT_STICKY;
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
            if (e == null) { announce("آدرس پروکسی معتبر نیست"); stopSelf(); return START_NOT_STICKY; }
            newSessionPending=true;
            if(!DistributionPolicy.allows(e)){finishConnection("این نسخه فقط پروکسی با TLS معتبر می‌پذیرد");return START_NOT_STICKY;}
            userRequested = true;
            requiredFilter = intent.getStringExtra("filter");
            if (requiredFilter == null) requiredFilter = "ALL";
            try { requiredSort = ProxyResults.Sort.valueOf(intent.getStringExtra("sort")); }
            catch (Exception ignored) { requiredSort = ProxyResults.Sort.FASTEST; }
            failover.reset(e.key()); recoveryLog.setLength(0);
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
        autoReport="اسکن و اتصال خودکار از کنترل گوشی\nفیلتر: "+ProxyResults.label(requiredFilter);
        updatePhase(CONNECTING);
        try {
            ensureChannel();
            Notification n=notification("اسکن و اتصال خودکار…");
            if(Build.VERSION.SDK_INT>=34)startForeground(ID,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(ID,n);
        }catch(Throwable error){finishConnection("امکان شروع اسکن پس‌زمینه نیست: "+error.getMessage());return;}
        announce("اسکن خودکار برای «"+ProxyResults.label(requiredFilter)+"»");
        final ProxyScanner.Options options = ScanInputs.options(this);
        ProxyStore.IO.execute(()->{
        try {
            ProxyStore.load(this);
            ScanFiles files = new ScanFiles(getFilesDir());
            java.util.List<ProxyEndpoint> candidates = files.catalog();
            if (candidates.isEmpty()) { handler.post(()->{if(token==generation)finishConnection("اول لیست پروکسی‌ها را داخل برنامه دریافت کن");}); return; }
            if(!autoScanning||token!=generation)return;
            autoCheckpoint = new ScanCheckpoint(candidates, options, "CATALOG");files.activate(autoCheckpoint);
            final ScanCheckpoint scan=autoCheckpoint;
            PhysicalTestNetwork network = PhysicalTestNetwork.capture(this);
            automaticScanner = new PreparedScanner();
            automaticScanner.start(autoCheckpoint, state->{if(!state.failedBatchKeys().isEmpty())ProxyStore.flush(ProxyVpnService.this);files.save(state);}, network::socket, network::resolve, new PreparedScanner.Listener() {
                public void onProgress(int done, int total, int good) { handler.post(() -> {
                    if (!autoScanning || token != generation) return;
                    autoDone = done; autoTotal = total;
                    if(!options.scanAll&&!autoFinishing){
                        if(autoSelection.haveBackups()){finishAutoScan(token);return;}
                        if(autoSelection.best()!=null&&!autoSelectionScheduled){autoSelectionScheduled=true;handler.postDelayed(()->finishAutoScan(token),5000);}
                    }
                    autoReport = "اسکن خودکار لیست ذخیره‌شده\n" + done + " از " + total + "\nسالم تازه: " + good;
                    scanAnnouncement("اسکن خودکار: " + done + " از " + total + " • سالم تازه: " + good);
                }); }
                public void onResult(int index,Socks5Probe.Result result) {
                    if(!autoScanning||token!=generation)return;
                    if(result.endpoint!=null){ProxyStore.index().accept(result.endpoint);autoSelection.add(result.endpoint);}
                    else if(result.failure!=Socks5Probe.Failure.CANCELLED&&ProxyStore.index().find(scan.candidate(index).key())!=null)
                        ProxyStore.index().reject(scan.candidate(index).key(),scan.finishedAt(index));
                }
                public void onFinished(ScanCheckpoint state, String message) { handler.post(() -> {
                    if (!autoScanning || token != generation) return;
                    autoCheckpoint = state; autoReport += "\n" + message; finishAutoScan(token);
                }); }
            });
        } catch (Exception ex) { handler.post(()->{if(token==generation)finishConnection("شروع اسکن خودکار ناموفق: " + ex.getMessage());}); }
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
                getSharedPreferences("persiansocks",MODE_PRIVATE).edit().putString("scan-report",autoReport+"\n"+autoDone+" از "+autoTotal).apply();
                if(chosen==null){finishConnection("پروکسی تازه‌ای با فیلتر و پینگ انتخابی پیدا نشد");return;}
                failover.reset(chosen.key());beginConnect(chosen);announce("در حال تأیید اتصال: "+chosen.key());
            });
        });
    }
    private void beginConnect(ProxyEndpoint proxy) {
        if(!DistributionPolicy.allows(proxy)){finishConnection("پروکسی بدون رمزنگاری انتخاب نشد");return;}
        closeTunnel();
        resetTrafficSession();
        int token = ++generation;
        cancelled = new AtomicBoolean(false);
        AtomicBoolean stop = cancelled;
        current = proxy.key();
        updatePhase(CONNECTING);
        lastProfile = proxy;
        saveVpnReport("پروکسی: " + current + "\nمرحله: آزمون دوبارهٔ سرویس‌ها پیش از ساخت تونل");
        try {
            ensureChannel();
            Notification n = notification("آزمون دوبارهٔ سرویس‌ها پیش از اتصال…");
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(ID, n);
        } catch (Throwable t) { saveVpnReport("آغاز سرویس: " + t); announce("امکان آغاز VPN نیست: " + t.getMessage()); closeTunnel(); stopSelf(); return; }
        announce("آزمون سرویس‌های فیلتر «" + ProxyResults.label(requiredFilter) + "»: " + current);
        new Thread(() -> {
            Socks5Probe.Result checked = Socks5Probe.inspect(proxy, true, stop, probeSockets);
            handler.post(() -> {
                if (token != generation || stop.get()) return;
                if (checked.endpoint == null || !ProxyResults.accepts(checked.endpoint, requiredFilter)) {
                    String msg = "اتصال آغاز نشد؛ سرویس‌ها دوباره تأیید نشدند: " + Socks5Probe.describe(checked.failure);
                    if (checked.endpoint != null) msg = "پروکسی دیگر شرط فیلتر «" + ProxyResults.label(requiredFilter) + "» را ندارد";
                    failConnection(msg); return;
                }
                ProxyStore.index().accept(checked.endpoint);ProxyStore.save(this,ProxyStore.index());
                lastProfile = checked.endpoint;
                announce("پروکسی دوباره تأیید شد: " + checked.endpoint.passedServices(), checked.endpoint);
                openTunnel(checked.endpoint, token);
            });
        }, "vpn-service-preflight").start();
    }
    private void openTunnel(ProxyEndpoint proxy, int thisGeneration) {
        ensureChannel();
        Notification n = notification("در حال راه‌اندازی تونل…");
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(ID, n);
            gateway = new ProtectedSocksGateway(proxy, new ProtectedSocksGateway.Protector() {
                public boolean tcp(Socket s) { return protect(s); }
                public boolean udp(DatagramSocket s) { return protect(s); }
                public InetAddress resolve(String host) throws Exception {
                    ConnectivityManager manager = getSystemService(ConnectivityManager.class);
                    for (Network net : manager.getAllNetworks()) {
                        NetworkCapabilities caps = manager.getNetworkCapabilities(net);
                        if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                            InetAddress[] ips = net.getAllByName(host);
                            if (ips.length > 0) return ips[0];
                        }
                    }
                    throw new java.io.IOException("No physical network for UDP relay DNS");
                }
            });
            Socks5Probe.setResolver(this::resolvePhysical);
            Socks5Probe.setSocketMaker(() -> {
                Socket s = new Socket();
                s.bind(new java.net.InetSocketAddress(0));
                if (!protect(s)) { s.close(); throw new java.io.IOException("Protect scan socket failed"); }
                return s;
            });
            Builder b = new Builder()
                .setSession("PersianSocks • " + current)
                .setBlocking(false)
                .setMtu(1400)
                .addAddress("198.18.0.1", 15)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd00:1:fd00:1::2", 64)
                .addRoute("::", 0)
                .addDnsServer("198.18.0.2");
            SplitTunnelSettings.apply(this, b);
            tun = b.establish();
            if (tun == null) throw new IllegalStateException("ایجاد رابط VPN توسط اندروید رد شد");
            String yaml = "tunnel:\n" +
                "  mtu: 1400\n" +
                "  ipv4: 198.18.0.1\n" +
                "  ipv6: 'fd00:1:fd00:1::2'\n" +
                "  multi-queue: false\n" +
                "socks5:\n" +
                "  address: 127.0.0.1\n" +
                "  port: " + gateway.port() + "\n" +
                "  udp: 'udp'\n" +
                "mapdns:\n" +
                "  address: 198.18.0.2\n" +
                "  port: 53\n" +
                "  network: 198.19.0.0\n" +
                "  netmask: 255.255.0.0\n" +
                "  cache-size: 4096\n" +
                "misc:\n" +
                "  max-session-count: 128\n" +
                "  connect-timeout: 10000\n" +
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
                throw new IllegalStateException("موتور تونل آغاز نشد");
            announce("در حال برقراری اتصال: " + current);
            pollRunning(thisGeneration, 0);
        } catch (Throwable t) {
            Log.e("PersianSocks", "Tunnel startup error", t);
            saveVpnReport("راه‌اندازی تونل: " + t);
            failConnection("اتصال برقرار نشد: " + t.getMessage());
        }
    }
    private void pollRunning(int token, int attempt) {
        handler.postDelayed(() -> {
            if (token != generation || tun == null) return;
            boolean on = false;
            try { on = TProxyService.TProxyIsRunning(); }
            catch (Throwable t) { Log.e("PersianSocks", "Status failed", t); }
            if (on) {
                announce("موتور تونل فعال شد؛ در حال تأیید ارتباط از داخل VPN…");
                getSystemService(NotificationManager.class).notify(ID, notification("در حال تست واقعی تونل…"));
                verifyNetwork(token, 0);
                monitorRunning(token);
            } else if (attempt < 24) pollRunning(token, attempt + 1);
            else {
                failConnection("موتور تونل راه‌اندازی نشد");
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
                saveVpnReport("شبکهٔ فعال اندروید به VPN تغییر نکرد؛ تست مستقیم به جای تونل پذیرفته نشد.");
                failConnection("شبکهٔ VPN آماده نشد");
            }
            return;
        }
        AtomicBoolean stop = cancelled;
        new Thread(() -> {
            TunnelVerifier.Result result = TunnelVerifier.check(new TunnelVerifier.Transport() {
                public Socket create() throws Exception { return network.getSocketFactory().createSocket(); }
                public InetAddress[] resolve(String host) throws Exception { return network.getAllByName(host); }
            }, stop, probeSockets);
            handler.post(() -> {
                if (token != generation || stop.get()) return;
                String stats = "";
                try {
                    long[] s = TProxyService.TProxyGetStats();
                    if (s != null && s.length >= 4) stats = "\nتونل: ارسال=" + s[1] + " بایت؛ دریافت=" + s[3] + " بایت";
                } catch (Throwable ignored) {}
                saveVpnReport("پروکسی: " + current + "\n" + result.report + stats);
                if (!result.accepts(requiredFilter)) {
                    failConnection("اتصال از داخل VPN برای فیلتر انتخابی تأیید نشد"); return;
                }
                lastProfile = result.profile(lastProfile);
                ProxyStore.index().accept(lastProfile);ProxyStore.save(this,ProxyStore.index());
                verifiedServices = lastProfile.passedServices();
                failover.connected(current);
                updatePhase(CONNECTED);
                announce("VPN تأیید شد: " + verifiedServices + " • " + current, lastProfile);
                getSystemService(NotificationManager.class).notify(ID, notification("متصل • " + verifiedServices));
                scheduleHealthCheck(token);
            });
        }, "vpn-real-path-check").start();
    }
    private InetAddress resolvePhysical(String host)throws Exception {
        ConnectivityManager manager=getSystemService(ConnectivityManager.class);
        for(Network net:manager.getAllNetworks()){
            NetworkCapabilities c=manager.getNetworkCapabilities(net);
            if(c!=null&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&!c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)){
                InetAddress[] addresses=net.getAllByName(host);
                for(InetAddress address:addresses)if(address instanceof java.net.Inet4Address)return address;
                if(addresses.length>0)return addresses[0];
            }
        }
        throw new java.io.IOException("No physical network for proxy DNS");
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
        if(notify&&phase==CONNECTED)getSystemService(NotificationManager.class).notify(ID,notification("متصل • "+verifiedServices+" • "+current));
    }
    private void persistTraffic(){
        TrafficMeter.Snapshot t=trafficSnapshot;if(t==null)return;
        getSharedPreferences("persiansocks",MODE_PRIVATE).edit().putLong("traffic-down",t.downloaded).putLong("traffic-up",t.uploaded).apply();
    }
    private void publishTraffic(){sendBroadcast(new Intent(STATE).setPackage(getPackageName()).putExtra("traffic-only",true));}
    private void saveVpnReport(String report) {
        if (recoveryLog.length() > 0) report += "\n\nتعویض خودکار:\n" + recoveryLog;
        File log = new File(getFilesDir(), "native-tunnel.log");
        if (tun != null && log.exists()) {
            try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(log, "r")) {
                long start = Math.max(0, f.length() - 8192); f.seek(start);
                byte[] bytes = new byte[(int) (f.length() - start)]; f.readFully(bytes);
                report += "\n\nآخرین پیام‌های موتور تونل:\n" + new String(bytes, StandardCharsets.UTF_8);
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
            failConnection("موتور VPN متوقف شد");
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
    private void finishConnection(String message){finishConnection(message,true);}
    private void finishConnection(String message,boolean error){
        userRequested=false;saveVpnReport(message);if(!error)clearConnectionError();closeTunnel();if(error)announceError(message);else announce(message);stopSelf();
    }
    private void failConnection(String reason) {
        announceError(reason);
        if(userRequested&&physicalNetworkAvailable()&&!current.isEmpty()){ProxyStore.index().reject(current,System.currentTimeMillis());ProxyStore.save(this,ProxyStore.index());}
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
        final int token=generation;final String service=requiredFilter;final ProxyResults.Sort order=requiredSort;
        final ProxyResults.Protocol kind=ScanInputs.protocol(this);final ProxyResults.PingLimit limit=ScanInputs.pingLimit(this);
        announce("در حال انتخاب جایگزین با پینگ مناسب…");
        ProxyStore.IO.execute(()->{
            java.util.List<ProxyEndpoint> ranked=ProxyResults.visible(ProxyStore.index().snapshot(),service,order,kind,limit);
            handler.post(()->{
                if(token!=generation||!userRequested)return;
                ProxyEndpoint next=failover.nextRanked(ranked,failed,System.currentTimeMillis());
                if(next==null){finishConnection("جایگزین تأییدشده‌ای با فیلتر و پینگ انتخابی باقی نماند؛ دوباره اسکن کن");return;}
                recoveryLog.append("انتخاب جایگزین: ").append(next.key()).append('\n');beginConnect(next);
                announce("تعویض خودکار: "+failed+" ← "+next.key()+"؛ در حال تأیید اتصال");
            });
        });
    }
    private void waitForNetwork() {
        cancelled.set(true);
        for (Socket socket : probeSockets) try { socket.close(); } catch (Exception ignored) {}
        handler.removeCallbacksAndMessages(null);
        int token = ++generation;
        trafficSnapshot=trafficMeter.stopped();publishTraffic();
        updatePhase(CONNECTING);
        announceError("اینترنت در دسترس نیست");
        getSystemService(NotificationManager.class).notify(ID, notification("منتظر بازگشت اینترنت"));
        waitForNetwork(token);
    }
    private void waitForNetwork(int token) {
        handler.postDelayed(() -> {
            if (token != generation || !userRequested) return;
            if (!autoSwitch()) { finishConnection("تعویض خودکار غیرفعال شد؛ اتصال قطع شد"); return; }
            if (physicalNetworkAvailable()) {
                ProxyEndpoint resume = ProxyEndpoint.parse(current);
                if (resume != null) { failover.reset(current); beginConnect(resume); }
                else finishConnection("برای اتصال دوباره یک پروکسی انتخاب کن");
            } else waitForNetwork(token);
        }, 10000);
    }
    private void scheduleHealthCheck(int token) {
        handler.postDelayed(() -> {
            if (token != generation || !userRequested || phase != CONNECTED) return;
            if (requiredFilter.startsWith("https://")) {
                boolean exists = false;
                for (ServiceTargets.Target t : ServiceTargets.current()) if (t.id.equals(requiredFilter)) exists = true;
                if (!exists) requiredFilter = "ALL";
            }
            ConnectivityManager manager = getSystemService(ConnectivityManager.class);
            Network network = manager.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : manager.getNetworkCapabilities(network);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                if (failover.health(false)) failConnection("شبکهٔ VPN دیگر فعال نیست");
                else scheduleHealthCheck(token);
                return;
            }
            AtomicBoolean stop = cancelled;
            ServiceTargets.Target[] targets = failover.healthTargets(requiredFilter, lastProfile);
            new Thread(() -> {
                TunnelVerifier.Result result = TunnelVerifier.check(new TunnelVerifier.Transport() {
                    public Socket create() throws Exception { return network.getSocketFactory().createSocket(); }
                    public InetAddress[] resolve(String host) throws Exception { return network.getAllByName(host); }
                }, stop, probeSockets, targets, true, requiredFilter.equals("ALL"));
                handler.post(() -> {
                    if (token != generation || stop.get() || !userRequested) return;
                    if (failover.health(result.accepts(requiredFilter))) {
                        saveVpnReport(result.report);
                        failConnection("دو آزمون پی‌درپی از داخل VPN ناموفق بود"); return;
                    }
                    if (!result.accepts(requiredFilter)) announce("ارتباط پاسخ نداد؛ یک بار دیگر بررسی می‌شود");
                    else announce("VPN تأیید شد: " + verifiedServices + " • " + current);
                    scheduleHealthCheck(token);
                });
            }, "vpn-health-check").start();
        }, 30000);
    }
    private void closeTunnel() {
        if(trafficMeter!=null){sampleTraffic(false);trafficSnapshot=trafficMeter.stopped();persistTraffic();publishTraffic();}
        generation++;
        PreparedScanner stoppedScanner=automaticScanner;automaticScanner=null;
        if(stoppedScanner!=null){stoppedScanner.requestStop();ProxyStore.IO.execute(()->{try{ScanCheckpoint saved=stoppedScanner.pause();if(saved!=null)ProxyStore.index().apply(saved);ProxyStore.save(this,ProxyStore.index());}catch(Exception ex){android.util.Log.e("PersianSocks","Stop save failed",ex);}});}
        if(autoScanning&&autoSelection!=null){
            getSharedPreferences("persiansocks",MODE_PRIVATE).edit().putString("scan-report",autoReport+"\nاسکن خودکار متوقف شد").apply();
        }
        autoScanning=false;
        autoCheckpoint=null;
        cancelled.set(true);
        Socks5Probe.setSocketMaker(null);Socks5Probe.setResolver(null);
        verifiedServices = "";
        for (Socket s : probeSockets) { try { s.close(); } catch (Exception ignored) {} }
        handler.removeCallbacksAndMessages(null);
        if (gateway != null) { gateway.close(); gateway = null; }
        try { TProxyService.TProxyStopService(); } catch (Throwable ignored) {}
        if (tun != null) { try { tun.close(); } catch (Exception ignored) {} tun = null; }
        current = "";
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        updatePhase(DISCONNECTED);
    }
    private void updatePhase(int next) {
        if(next==CONNECTED)clearConnectionError();
        phase = next;
        activeEndpoint = next == DISCONNECTED ? "" : current;
        Intent i = new Intent(STATE).setPackage(getPackageName()).putExtra(EXTRA_PHASE, phase).putExtra("connection-error",getSharedPreferences("persiansocks",MODE_PRIVATE).getString("connection-error",""));
        sendBroadcast(i);
        QuickControls.refresh(this,true);
    }
    private void clearConnectionError(){getSharedPreferences("persiansocks",MODE_PRIVATE).edit().remove("connection-error").apply();}
    private void announceError(String message){getSharedPreferences("persiansocks",MODE_PRIVATE).edit().putString("connection-error",message).apply();announce(message);}
    private void announce(String message) {
        announce(message, null);
    }
    private void announce(String message, ProxyEndpoint profile) {
        Intent i = new Intent(STATE);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_MESSAGE, message);
        i.putExtra(EXTRA_PHASE, phase);i.putExtra("connection-error",getSharedPreferences("persiansocks",MODE_PRIVATE).getString("connection-error",""));
        if (profile != null) i.putExtra("verified-proxy", profile.json().toString());
        sendBroadcast(i);
        getSharedPreferences("persiansocks", MODE_PRIVATE).edit().putString("state", message).apply();
        QuickControls.refresh(this,!autoScanning);
    }
    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "PersianSocks VPN", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("اتصال پروکسی، سرعت دانلود و آپلود VPN");
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
                "\nمجموع دانلود: "+TrafficMeter.bytes(trafficSnapshot.downloaded)+" • آپلود: "+TrafficMeter.bytes(trafficSnapshot.uploaded):"")))
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_qs_vpn)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, autoScanning?"توقف اسکن":"قطع اتصال", stopPi).build())
            .build();
    }
    @Override public void onRevoke() { userRequested=false; closeTunnel(); announce("مجوز VPN لغو شد"); stopSelf(); super.onRevoke(); }
    @Override public void onDestroy() {
        userRequested=false;closeTunnel();
        if(instance.get()==this)instance.clear();
        super.onDestroy();
    }
}
