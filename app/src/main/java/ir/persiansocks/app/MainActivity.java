package ir.persiansocks.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.activity.ComponentActivity;
import androidx.recyclerview.widget.*;
import androidx.paging.*;
import androidx.lifecycle.LiveData;

import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.content.res.ColorStateList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

public final class MainActivity extends ComponentActivity {
    private static java.lang.ref.WeakReference<MainActivity> instance=new java.lang.ref.WeakReference<>(null);
    public static boolean backgroundBusy(){MainActivity a=instance.get();return a!=null&&!a.isDestroyed()&&(a.scanning||a.collecting||a.preparing||a.stopping||a.singleTest!=null||a.speedTest!=null||a.speedBatch!=null);}
    public static void pauseManualScanForQuickAction(){
        MainActivity current=instance.get();
        if(current!=null&&!current.isDestroyed()&&current.scanning)current.stopScan();
    }
    private static final int VPN_REQUEST = 170;
    private static final int BG = Color.rgb(14, 18, 22);
    private static final int SURFACE = Color.rgb(25, 31, 37);
    private static final int PRIMARY = Color.rgb(28, 205, 187);
    private static final int SECONDARY = Color.rgb(151, 169, 189);
    private static final int STROKE = Color.rgb(46, 54, 64);
    private static final int DANGER = Color.rgb(255, 142, 151);
    private static final int WARNING = Color.rgb(245, 199, 111);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ProxyScanner scanner = new ProxyScanner();
    private final PreparedScanner preparedScanner = new PreparedScanner();
    private ScanFiles scanFiles;
    private VpnGateDirectory gateDirectory;
    private boolean pendingGateScan,pendingGateDeep;private String pendingGateKey;private Button gateResultsButton;
    private ScanCheckpoint checkpoint;
    private int catalogCount;
    private boolean loading, preparing, stopping, localDataLoaded;
    private volatile boolean collecting;
    private volatile int collectGeneration;
    private final ProxyIndex healthy = ProxyStore.index();
    private final List<ProxyEndpoint> manual = new ArrayList<>();
    // Favorites are controlled only by explicit user actions, never by scanner cleanup.
    private final LinkedHashMap<String,ProxyEndpoint> favorites = new LinkedHashMap<>();
    private boolean showFavorites;
    private long favoritesRevision;
    private Button favoritesButton,savedPingButton,savedSpeedButton;
    private List<ProxyEndpoint> savedPingQueue;
    private int savedPingPosition;
    private boolean filteredPingMode;
    private Button filteredPingButton;
    private PublicIpCheck ipCheck;
    private String ipRoute="";
    private long ipCheckedAt;
    private final Runnable ipTicker=new Runnable(){public void run(){if(isDestroyed())return;String previousScope=QualityHistory.scope();QualityHistory.select(MainActivity.this);if(!previousScope.equals(QualityHistory.scope()))scheduleRender();refreshPublicIp(false);if(siteIcons!=null)ui.postDelayed(()->{if(!isDestroyed())siteIcons.sync();},600);ui.postDelayed(this,15000);}};
    private UiIconButton filterButton;
    private ProxyEndpoint selected;
    private RecyclerView results;
    private LiveData<PagingData<ProxyEndpoint>> pageStream;
    private static final java.util.concurrent.atomic.AtomicLong pageSequence=new java.util.concurrent.atomic.AtomicLong();
    private long pageGeneration;
    private int resultCount;
    private LinearLayout emptyState;
    private TextView selectionCountry;
    private final ResultAdapter resultAdapter=new ResultAdapter();

    private final java.util.concurrent.ExecutorService rankWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean ranking;
    private long iconRevision;
    private String scanPerformance="";
    private long renderedRevision=-1;
    private String renderedCriteria="";
    private final java.util.concurrent.atomic.AtomicBoolean redrawQueued=new java.util.concurrent.atomic.AtomicBoolean();
    private TextView status, listStatus, catalogInfo, counter, selection;
    private TextView vpnStatus, vpnBadge, connectionAction, activeProfile, selectionProtocol, filterSummary, trafficDown, trafficUp, trafficDownTotal, trafficUpTotal;
    private Button filteredSpeedButton;
    private FilteredSpeedBatch speedBatch;
    private boolean preparingSpeedBatch;
    private int speedBatchGeneration;
    private DownloadSpeedTest speedTest;
    private String runningSpeedTarget="";
    private SingleProxyTest singleTest;
    private ProxyEndpoint testingProxy;
    private boolean testingLatency;
    private final Map<String, ProxyTestResult> testResults = new LinkedHashMap<>();
    private double liveDownloadMBps = -1;
    private int scanTypes=7;
    private ProxyResults.Protocol protocol=ProxyResults.Protocol.ALL;
    private ProxyResults.PingLimit pingLimit = ProxyResults.PingLimit.NONE;
    private Button scanToggle, collectButton, scanRestart;
    private TextView scanTotal,scanChecked;
    private int lastScanDone,lastScanTotal;
    private boolean smoothResults;
    private long measurementRevision;
    private PowerToggle vpnToggle;
    private String connectionError="";
    private CheckBox sidebarTls,sidebarAuto,sidebarKill;
    private CheckBox quickKill;
    private CheckBox chatOnly;
    private Button designAllApps,designCountry,designSort;
    private UiIconButton designServers,designSaved,designSettings;
    private TextView designKillStatus;
    private final Button[] designSources=new Button[3];
    private boolean initialFetchAttempted;
    private List<ProxyEndpoint> startupPreview=java.util.Collections.emptyList();
    private Button sidebarToggle, sidebarClose;
    private volatile boolean foregroundVisible=true;
    private AppUpdater appUpdater;
    private SiteIcons siteIcons;
    private FrameLayout page, sidebarOverlay;
    private View mainScroller;
    private LinearLayout sidebar;
    private boolean sidebarVisible;
    private android.window.OnBackInvokedCallback sidebarBack;
    private final List<String> filterKeys = new ArrayList<>();
    private final List<ServiceTargets.Target> customTargets = new ArrayList<>();
    private ProgressBar scanProgress;
    private String filter = "ALL";
    private ProxyResults.Sort sort = ProxyResults.Sort.FASTEST;
    private boolean vpnRequestPending;
    private int observedVpnPhase=-1;
    private volatile boolean scanning;
    private boolean observedAutoScan;
    private boolean userSelected;
    private volatile int scanGeneration;
    private String scanReport = AppLanguage.t(R.string.ui_000);
    private boolean slowScan;
    private boolean scanAll;
    private int maxTests = 5000;
    private List<ProxyEndpoint> bootstrap;
    private final BroadcastReceiver vpnState = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String profile = intent.getStringExtra("verified-proxy");
            if (profile != null) {
                try {
                    ProxyEndpoint fresh = ProxyEndpoint.fromJson(new org.json.JSONObject(profile));
                    if (fresh.hasAnyService()&&DistributionPolicy.allows(fresh)) {
                        healthy.accept(fresh);
                        if (ProxyVpnService.activeEndpoint().equals(fresh.key()) ||
                            (selected != null && selected.key().equals(fresh.key()))) selected = fresh;
                        render();
                    }
                } catch (Exception ignored) {}
            }
            if(intent.getBooleanExtra("gate-result",false)&&gateDirectory!=null){String key=intent.getStringExtra("gate-key");boolean ok=intent.getBooleanExtra("gate-ok",false);gateDirectory.store.evidence(key,ok,intent.getStringExtra("gate-error"),intent.getLongExtra("gate-at",0),intent.getLongExtra("gate-latency",-1));if(!ok)healthy.reject(key,System.currentTimeMillis());renderedRevision=-1;render();}
            if(intent.getBooleanExtra("traffic-only",false)){updateTraffic();return;}
            vpnRequestPending = false;
            boolean auto=ProxyVpnService.autoScanning();
            if(observedAutoScan&&!auto&&!scanning){lastScanDone=ProxyVpnService.autoScanDone();lastScanTotal=ProxyVpnService.autoScanTotal();saveScanCounts();reloadSaved();}
            observedAutoScan=auto;
            String state = intent.getStringExtra(ProxyVpnService.EXTRA_MESSAGE);
            if(intent.hasExtra("connection-error"))connectionError=intent.getStringExtra("connection-error");
            if(connectionError==null)connectionError="";
            if(state!=null&&(auto||ProxyVpnService.gateScanning()||intent.hasExtra("gate-result")))status.setText(AppLanguage.display(state));
            updateActions();updateTraffic();if(siteIcons!=null)siteIcons.sync();if(renderedRevision!=healthy.revision())render();
        }
    };

    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(AppLanguage.wrap(base)); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        instance=new java.lang.ref.WeakReference<>(this);
        ScanInputs.initializePolicy(this);
        scanFiles = new ScanFiles(getFilesDir());

        maxTests = Math.max(100, Math.min(ProxyScanner.MAX_TESTS,
            getSharedPreferences("persiansocks", MODE_PRIVATE).getInt("max-tests", 5000)));
        slowScan = getSharedPreferences("persiansocks", MODE_PRIVATE).getBoolean("slow-scan", false);
        lastScanDone=getSharedPreferences("persiansocks",MODE_PRIVATE).getInt("last-scan-done",0);lastScanTotal=getSharedPreferences("persiansocks",MODE_PRIVATE).getInt("last-scan-total",0);
        scanTypes=DistributionPolicy.scanTypes(getSharedPreferences("persiansocks",MODE_PRIVATE).getInt("scan-types",7));
        protocol=ScanInputs.protocol(this);
        pingLimit=ScanInputs.pingLimit(this);
        smoothResults=getSharedPreferences("persiansocks",MODE_PRIVATE).getBoolean("smooth-results",false);
        scanAll = getSharedPreferences("persiansocks", MODE_PRIVATE).getBoolean("scan-all", false);
        scanReport = getSharedPreferences("persiansocks", MODE_PRIVATE).getString("scan-report", scanReport);
        customTargets.addAll(ServiceTargets.decode(getSharedPreferences("persiansocks", MODE_PRIVATE).getString("test-domains", "[]")));
        ServiceTargets.configure(customTargets);
        siteIcons=new SiteIcons(this,()->{iconRevision++;refreshVisibleCards();});siteIcons.sync();
        filter = getSharedPreferences("persiansocks", MODE_PRIVATE).getString("filter", "ALL");
        if (filter.startsWith("https://") && customTargets.stream().noneMatch(t -> t.id.equals(filter))) filter = "ALL";
        try { sort = ProxyResults.Sort.valueOf(getSharedPreferences("persiansocks", MODE_PRIVATE).getString("sort", "FASTEST")); }
        catch (IllegalArgumentException ignored) {}
        startupPreview=QuickStartCache.load(this);if(!startupPreview.isEmpty())selected=startupPreview.get(0);
        buildUi();
        gateDirectory=new VpnGateDirectory(this,message->ui.post(()->{if(isDestroyed())return;if(!message.isEmpty())status.setText(message);renderedRevision=-1;render();}));gateDirectory.load();
        render();
        if (state != null && state.getBoolean("sidebar-open", false)) setSidebarVisible(true);
        connectionError=getSharedPreferences("persiansocks",MODE_PRIVATE).getString("connection-error","");updateActions();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{ Manifest.permission.POST_NOTIFICATIONS }, 18);
    }
    @Override protected void onStart() {
        super.onStart();foregroundVisible=true;PreparedScanner.foreground(true);ui.removeCallbacks(ipTicker);ui.post(ipTicker);
        ScanInputs.initializePolicy(this);QualityHistory.select(this);
        IntentFilter f = new IntentFilter(ProxyVpnService.STATE);
        registerReceiver(vpnState, f, ProxyVpnService.INTERNAL_PERMISSION, null, Context.RECEIVER_NOT_EXPORTED);
        vpnRequestPending = false;
        observedAutoScan=ProxyVpnService.autoScanning();
        if(!scanning&&!localDataLoaded)reloadSaved();
        connectionError=getSharedPreferences("persiansocks",MODE_PRIVATE).getString("connection-error","");
        updateActions();
        updateTraffic();
        QuickControls.refresh(this,true);
        if(appUpdater!=null)appUpdater.start();
        if(scanning&&checkpoint!=null){status.setText(AppLanguage.t(R.string.ui_125)+checkpoint.done()+AppLanguage.t(R.string.ui_017)+checkpoint.total()+AppLanguage.t(R.string.ui_126)+checkpoint.goodCount());scanProgress.setProgress((int)(100L*checkpoint.done()/Math.max(1,checkpoint.total())));}
        render();
    }
    @Override protected void onStop() { foregroundVisible=false;PreparedScanner.foreground(false);ui.removeCallbacks(ipTicker);if(ipCheck!=null){ipCheck.cancel();ipCheck=null;ipRoute="";}super.onStop(); unregisterReceiver(vpnState); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("sidebar-open", sidebarVisible);
        super.onSaveInstanceState(state);
    }
    @Override public void onBackPressed() {
        if (sidebarVisible) setSidebarVisible(false);
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && sidebarBack != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(sidebarBack);
            sidebarBack = null;
        }
        if(gateDirectory!=null)gateDirectory.close();
        if(appUpdater!=null)appUpdater.close();if(siteIcons!=null)siteIcons.close();
        if(ipCheck!=null)ipCheck.cancel();
        stopFilteredSpeed();
        cancelSingleTests();
        if(scanning)stopScan();
        collecting = false; collectGeneration++; scanner.stop();
        scanning = false; scanGeneration++;
        rankWorker.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        if(instance.get()==this)instance.clear();
        super.onDestroy();
    }

    private void buildUi() {buildLegacyUi();applyDesignOne();}
    private void buildLegacyUi() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        page = new FrameLayout(this);
        page.setBackgroundColor(BG);
        RecyclerView scroller = new RecyclerView(this); mainScroller = scroller; results=scroller;
        scroller.setLayoutManager(new LinearLayoutManager(this));scroller.setItemAnimator(null);
        scroller.setClipToPadding(false);

        scroller.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(AppLanguage.direction());

        page.addView(scroller, new FrameLayout.LayoutParams(-1, -1));
        page.setLayoutDirection(AppLanguage.direction());
        scroller.setLayoutDirection(AppLanguage.direction());
        setContentView(page);
        page.setOnApplyWindowInsetsListener((v, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                left=bars.left; top=bars.top; right=bars.right; bottom=bars.bottom;
            } else {
                left=insets.getSystemWindowInsetLeft(); top=insets.getSystemWindowInsetTop();
                right=insets.getSystemWindowInsetRight(); bottom=insets.getSystemWindowInsetBottom();
            }
            root.setPadding(dp(16), dp(12)+top, dp(16), dp(8));
            results.setPadding(left,0,right,dp(16)+bottom);
            if (sidebar != null) sidebar.setPadding(dp(14), dp(12)+top, dp(14)+right, dp(12)+bottom);
            return insets;
        });

        LinearLayout top = horizontal();
        sidebarToggle = new UiIconButton(this,UiIconButton.Kind.MENU,AppLanguage.t(R.string.ui_001));
        sidebarToggle.setContentDescription(AppLanguage.t(R.string.ui_001));
        sidebarToggle.setOnClickListener(v -> setSidebarVisible(!sidebarVisible));
        top.addView(sidebarToggle, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout title = new LinearLayout(this); title.setOrientation(LinearLayout.VERTICAL);
        title.setLayoutDirection(AppLanguage.direction());title.setGravity(AppLanguage.gravity());
        TextView brand = text("PersianSocks", 21, Color.WHITE, true);
        brand.setTextDirection(View.TEXT_DIRECTION_LTR);
        title.addView(brand,new LinearLayout.LayoutParams(-1,-2));
        title.addView(text("v" + AppLanguage.version(this) + (DistributionPolicy.play() ? " • TLS" : ""), 11, SECONDARY, false));
        LinearLayout.LayoutParams titleCell = new LinearLayout.LayoutParams(0, -2, 1);
        titleCell.setMarginStart(dp(10)); top.addView(title, titleCell);
        android.widget.ImageView brandIcon=new android.widget.ImageView(this);
        brandIcon.setImageResource(R.drawable.ic_launcher);
        brandIcon.setContentDescription(AppLanguage.t(R.string.ui_002));
        LinearLayout.LayoutParams iconCell=new LinearLayout.LayoutParams(dp(42),dp(42));
        iconCell.setMarginStart(dp(8));top.addView(brandIcon,iconCell);
        root.addView(top);

        LinearLayout hero = panel();
        hero.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TR_BL,
            new int[]{Color.rgb(20, 48, 62), Color.rgb(17, 33, 49)});
        gradient.setCornerRadius(dp(22)); gradient.setStroke(dp(1), Color.rgb(39, 77, 88));
        hero.setBackground(gradient);
        LinearLayout connectionHeading = horizontal();
        vpnBadge = text(AppLanguage.t(R.string.ui_003), 12, PRIMARY, true);
        connectionHeading.addView(vpnBadge, new LinearLayout.LayoutParams(0, -2, 1));
        selectionProtocol = chip("—", SECONDARY, Color.rgb(25, 47, 56));
        selectionProtocol.setTextDirection(View.TEXT_DIRECTION_LTR);
        connectionHeading.addView(selectionProtocol);
        hero.addView(connectionHeading);
        chatOnly=sidebarCheck(AppLanguage.t(R.string.ui_031_chatgpt_off),AndroidRoutes.mode(this).equals("chatgpt"));
        chatOnly.setOnClickListener(v->setChatgptOnly(chatOnly.isChecked()));
        hero.addView(chatOnly,new LinearLayout.LayoutParams(-1,dp(48)));
        selection = text(AppLanguage.t(R.string.ui_004), 20, Color.WHITE, true);
        selection.setTextDirection(View.TEXT_DIRECTION_LTR);
        selection.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        selection.setMaxLines(1); selection.setEllipsize(android.text.TextUtils.TruncateAt.END);
        selection.setAutoSizeTextTypeUniformWithConfiguration(14, 20, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        LinearLayout selectedIdentity=horizontal();
        selectedIdentity.addView(selection,new LinearLayout.LayoutParams(0,-2,1));
        selectionCountry=text("",12,PRIMARY,false);selectionCountry.setMaxWidth(dp(92));
        selectionCountry.setMaxLines(2);selectionCountry.setEllipsize(android.text.TextUtils.TruncateAt.END);selectionCountry.setVisibility(View.GONE);
        LinearLayout.LayoutParams countryCell=new LinearLayout.LayoutParams(-2,-2);countryCell.setMarginStart(dp(8));selectedIdentity.addView(selectionCountry,countryCell);
        hero.addView(selectedIdentity, margin(dp(5), 0));
        vpnStatus = text(AppLanguage.t(R.string.ui_005), 12, SECONDARY, false);
        vpnStatus.setMaxLines(4); vpnStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        vpnStatus.setOnClickListener(v -> connectionErrorDialog());
        vpnStatus.setTooltipText(AppLanguage.t(R.string.ui_008));
        hero.addView(vpnStatus, margin(dp(4), 0));
        activeProfile = text("", 12, SECONDARY, false); activeProfile.setVisibility(View.VISIBLE);activeProfile.setOnClickListener(v->refreshPublicIp(true));
        hero.addView(activeProfile, margin(dp(4), 0));

        LinearLayout actions = horizontal();
        vpnToggle = new PowerToggle(this);
        vpnToggle.setOnClickListener(v -> {
            if (vpnRequestPending || ProxyVpnService.phase() != ProxyVpnService.DISCONNECTED) disconnect();
            else connect();
        });
        LinearLayout powerColumn=new LinearLayout(this);powerColumn.setOrientation(LinearLayout.VERTICAL);powerColumn.setGravity(Gravity.CENTER);
        powerColumn.addView(vpnToggle,new LinearLayout.LayoutParams(dp(64),dp(64)));
        connectionAction=text(AppLanguage.t(R.string.ui_design_connect),11,Color.WHITE,true);connectionAction.setGravity(Gravity.CENTER);connectionAction.setMaxLines(2);
        powerColumn.addView(connectionAction,new LinearLayout.LayoutParams(-1,-2));
        quickKill=sidebarCheck(AppLanguage.t(R.string.ui_030_kill),AndroidRoutes.kill(this));quickKill.setTextSize(10);quickKill.setOnCheckedChangeListener((v,on)->setKill(on));powerColumn.addView(quickKill,new LinearLayout.LayoutParams(-2,-2));
        LinearLayout.LayoutParams powerCell=new LinearLayout.LayoutParams(dp(76),-2);powerCell.setMarginEnd(dp(8));actions.addView(powerColumn,powerCell);

        LinearLayout traffic = horizontal();
        LinearLayout down = metricColumn(AppLanguage.t(R.string.ui_011)), up = metricColumn(AppLanguage.t(R.string.ui_012));
        trafficDown = text("0 B/s", 17, PRIMARY, true);
        trafficUp = text("0 B/s", 17, Color.rgb(116,180,255), true);
        trafficDown.setTextDirection(View.TEXT_DIRECTION_LTR); trafficUp.setTextDirection(View.TEXT_DIRECTION_LTR);
        trafficDownTotal = text(AppLanguage.t(R.string.ui_013), 11, SECONDARY, false);
        trafficUpTotal = text(AppLanguage.t(R.string.ui_013), 11, SECONDARY, false);
        down.addView(trafficDown); down.addView(trafficDownTotal);
        up.addView(trafficUp); up.addView(trafficUpTotal);
        traffic.addView(down, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams upCell=new LinearLayout.LayoutParams(0,-2,1);upCell.setMarginStart(dp(8));traffic.addView(up,upCell);
        trafficDown.setMaxLines(1);trafficUp.setMaxLines(1);trafficDownTotal.setMaxLines(1);trafficUpTotal.setMaxLines(1);
        trafficDown.setAutoSizeTextTypeUniformWithConfiguration(12,17,1,android.util.TypedValue.COMPLEX_UNIT_SP);trafficUp.setAutoSizeTextTypeUniformWithConfiguration(12,17,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        trafficDownTotal.setEllipsize(android.text.TextUtils.TruncateAt.END);trafficUpTotal.setEllipsize(android.text.TextUtils.TruncateAt.END);
        actions.addView(traffic,new LinearLayout.LayoutParams(0,-2,1));hero.addView(actions,margin(dp(10),0));
        root.addView(hero, margin(dp(14), 0));

        LinearLayout scanPanel = panel();
        scanPanel.setPadding(dp(12), dp(12), dp(12), dp(10));
        TextView scanHeading=text(AppLanguage.t(R.string.ui_home_scan),14,Color.WHITE,true);
        scanPanel.addView(scanHeading,margin(0,dp(8)));
        LinearLayout scanTitle=horizontal();scanTitle.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LinearLayout scanCounts=new LinearLayout(this);scanCounts.setOrientation(LinearLayout.VERTICAL);scanCounts.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        scanTotal=scanCountBadge(scanCounts,R.drawable.ic_scan_total,AppLanguage.t(R.string.ui_scan_total));
        scanChecked=scanCountBadge(scanCounts,R.drawable.ic_scan_checked,AppLanguage.t(R.string.ui_scan_checked));
        scanTitle.addView(scanCounts,new LinearLayout.LayoutParams(0,-2,1));
        scanRestart=new UiIconButton(this,UiIconButton.Kind.RESTART,AppLanguage.t(R.string.ui_scan_restart));
        scanRestart.setOnClickListener(v->chooseScanMode(false));((UiIconButton)scanRestart).caption(AppLanguage.t(R.string.ui_design_restart));scanTitle.addView(scanRestart,scanActionCell(4));
        scanToggle=new UiIconButton(this,UiIconButton.Kind.PLAY,AppLanguage.t(R.string.ui_home_scan));
        scanToggle.setOnClickListener(v->{if(scanning||ProxyVpnService.autoScanning())stopScan();else continueOrStartScan();});scanTitle.addView(scanToggle,scanActionCell(4));
        collectButton=new UiIconButton(this,UiIconButton.Kind.COLLECT,AppLanguage.t(R.string.ui_014));
        collectButton.setOnClickListener(v->{if(collecting)stopCollection();else collectList();});((UiIconButton)collectButton).caption(AppLanguage.t(R.string.ui_design_sources));scanTitle.addView(collectButton,scanActionCell(0));
        scanPanel.addView(scanTitle);
        listStatus=text("",11,SECONDARY,false);listStatus.setVisibility(View.GONE);listStatus.setMaxLines(1);listStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        scanPanel.addView(listStatus,margin(dp(4),0));
        status=text(checkpoint!=null&&!checkpoint.complete()?AppLanguage.t(R.string.ui_016)+checkpoint.done()+AppLanguage.t(R.string.ui_017)+checkpoint.total():catalogCount==0?AppLanguage.t(R.string.ui_018):catalogCount+AppLanguage.t(R.string.ui_019),11,SECONDARY,false);
        status.setMaxLines(1);status.setEllipsize(android.text.TextUtils.TruncateAt.END);status.setOnClickListener(v->showReport());scanPanel.addView(status,margin(dp(4),0));
        scanProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        scanProgress.setMax(100);
        scanProgress.setProgressTintList(ColorStateList.valueOf(PRIMARY));
        scanProgress.setIndeterminateTintList(ColorStateList.valueOf(PRIMARY));
        scanProgress.setProgressBackgroundTintList(ColorStateList.valueOf(STROKE));
        scanProgress.setVisibility(View.GONE);
        scanPanel.addView(scanProgress, sized(dp(3), dp(8)));
        root.addView(scanPanel, margin(dp(8), 0));

        LinearLayout heading = horizontal();
        heading.addView(text(AppLanguage.t(R.string.ui_020), 18, Color.WHITE, true), new LinearLayout.LayoutParams(0, -2, 1));
        counter = chip(AppLanguage.t(R.string.ui_021), PRIMARY, Color.rgb(22,51,56)); heading.addView(counter);
        root.addView(heading, margin(dp(16), dp(4)));
        LinearLayout toolbar=horizontal();
        favoritesButton=new UiIconButton(this,UiIconButton.Kind.STAR,AppLanguage.t(R.string.ui_151));
        favoritesButton.setOnClickListener(v->{showFavorites=!showFavorites;renderedRevision=-1;render();});
        ((UiIconButton)favoritesButton).caption(AppLanguage.t(R.string.ui_design_saved));toolbar.addView(favoritesButton,weighted(64,1,6));
        filterButton=new UiIconButton(this,UiIconButton.Kind.FILTER,AppLanguage.t(R.string.ui_home_filters));
        filterButton.setOnClickListener(v->homeFiltersDialog());filterButton.caption(AppLanguage.t(R.string.ui_design_filters));toolbar.addView(filterButton,weighted(64,1,6));
        filteredSpeedButton=new UiIconButton(this,UiIconButton.Kind.DOWNLOAD,AppLanguage.t(R.string.ui_032));
        filteredSpeedButton.setOnClickListener(v->{if(speedBatch!=null||preparingSpeedBatch)stopFilteredSpeed();else prepareFilteredSpeed();});
        ((UiIconButton)filteredSpeedButton).caption(AppLanguage.t(R.string.ui_design_speed));toolbar.addView(filteredSpeedButton,weighted(64,1,6));
        savedPingButton=new UiIconButton(this,UiIconButton.Kind.SYNC,AppLanguage.t(R.string.ui_v25_008));
        savedPingButton.setOnClickListener(v->{if(savedPingQueue!=null)cancelSingleTests();else startSavedPings();render();});
        savedSpeedButton=new UiIconButton(this,UiIconButton.Kind.DOWNLOAD,AppLanguage.t(R.string.ui_v25_009));
        savedSpeedButton.setOnClickListener(v->{if(speedBatch!=null)stopFilteredSpeed();else confirmSpeedBatch(new FilteredSpeedBatch(favoriteSnapshots()));render();});
        ((UiIconButton)savedPingButton).caption(AppLanguage.t(R.string.ui_design_ping));((UiIconButton)savedSpeedButton).caption(AppLanguage.t(R.string.ui_design_speed));toolbar.addView(savedPingButton,weighted(64,1,6));toolbar.addView(savedSpeedButton,weighted(64,1,0));
        filteredPingButton=new UiIconButton(this,UiIconButton.Kind.SYNC,AppLanguage.t(R.string.ui_filtered_ping));filteredPingButton.setOnClickListener(v->{if(savedPingQueue!=null&&filteredPingMode)cancelSingleTests();else startFilteredPings();render();});((UiIconButton)filteredPingButton).caption(AppLanguage.t(R.string.ui_design_ping));toolbar.addView(filteredPingButton,weighted(64,1,0));
        savedPingButton.setVisibility(View.GONE);savedSpeedButton.setVisibility(View.GONE);root.addView(toolbar,margin(dp(4),0));
        rebuildFilters();
        filterSummary = text("", 12, SECONDARY, false);
        filterSummary.setMaxLines(2); filterSummary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        filterSummary.setOnClickListener(v -> homeFiltersDialog());
        root.addView(filterSummary, margin(dp(3), 0));
        emptyState=panel();root.addView(emptyState,margin(dp(8),0));
        results.setAdapter(new ConcatAdapter(new HeaderAdapter(root),resultAdapter));
        buildSidebar();
        page.requestApplyInsets();
    }

    private View detach(View v){if(v.getParent() instanceof ViewGroup)((ViewGroup)v.getParent()).removeView(v);return v;}
    private void applyDesignOne(){
        // Reuse the existing controller and its controls; the approved layout
        // changes presentation without replacing scanning, paging or settings.
        View oldTools=(View)favoritesButton.getParent();detach(oldTools);
        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.VERTICAL);header.setPadding(dp(16),dp(12),dp(16),dp(6));
        LinearLayout top=horizontal();top.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);top.addView(new View(this),new LinearLayout.LayoutParams(dp(42),dp(48)));
        LinearLayout title=new LinearLayout(this);title.setOrientation(LinearLayout.VERTICAL);title.setGravity(Gravity.CENTER);
        android.text.SpannableString wordmark=new android.text.SpannableString("PersianSocks");wordmark.setSpan(new android.text.style.ForegroundColorSpan(PRIMARY),7,12,0);
        TextView brand=text("PersianSocks",23,Color.WHITE,true);brand.setText(wordmark);brand.setGravity(Gravity.CENTER);brand.setTextDirection(View.TEXT_DIRECTION_LTR);title.addView(brand,new LinearLayout.LayoutParams(-1,-2));
        TextView subtitle=text(AppLanguage.t(R.string.ui_032_brand),11,SECONDARY,false);subtitle.setGravity(Gravity.CENTER);title.addView(subtitle);
        top.addView(title,new LinearLayout.LayoutParams(0,-2,1));detach(sidebarToggle);sidebarToggle.setBackgroundColor(Color.TRANSPARENT);top.addView(sidebarToggle,new LinearLayout.LayoutParams(dp(42),dp(48)));header.addView(top);
        LinearLayout hero=panel();hero.setPadding(dp(12),dp(14),dp(12),dp(12));hero.setBackground(background(SURFACE,dp(18),DesignOneWidgets.STROKE));
        LinearLayout identity=horizontal();identity.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);identity.addView(detach(vpnToggle),new LinearLayout.LayoutParams(dp(92),dp(92)));
        LinearLayout state=new LinearLayout(this);state.setOrientation(LinearLayout.VERTICAL);state.setGravity(AppLanguage.gravity());state.setPadding(dp(12),0,dp(4),0);
        vpnBadge.setTextSize(21);vpnBadge.setTextColor(Color.WHITE);state.addView(detach(vpnBadge),new LinearLayout.LayoutParams(-1,-2));connectionAction.setTextSize(12);connectionAction.setTextColor(SECONDARY);connectionAction.setGravity(AppLanguage.gravity());state.addView(detach(connectionAction),margin(dp(7),0));identity.addView(state,new LinearLayout.LayoutParams(0,-2,1));hero.addView(identity);
        LinearLayout controls=horizontal();controls.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LinearLayout kill=panel();kill.setPadding(dp(6),dp(10),dp(6),dp(10));kill.setBackground(background(Color.rgb(23,29,35),dp(11),DesignOneWidgets.STROKE));
        LinearLayout killRow=horizontal();killRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);UiIconButton shield=new UiIconButton(this,UiIconButton.Kind.SHIELD,AppLanguage.t(R.string.ui_030_guard_help));shield.setBackground(null);shield.setTextColor(PRIMARY);shield.setOnClickListener(v->guardHelp());killRow.addView(shield,new LinearLayout.LayoutParams(dp(20),dp(36)));
        LinearLayout killWords=new LinearLayout(this);killWords.setOrientation(LinearLayout.VERTICAL);TextView killTitle=text("Kill Switch",11,Color.WHITE,true);killTitle.setSingleLine(true);killTitle.setTextSize(9);killTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);killTitle.setTextDirection(View.TEXT_DIRECTION_LTR);killTitle.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);killWords.addView(killTitle);designKillStatus=text("",9,SECONDARY,false);designKillStatus.setMaxLines(2);killWords.addView(designKillStatus);killRow.addView(killWords,new LinearLayout.LayoutParams(0,-2,1));
        quickKill=new DesignOneWidgets.Switch(this);quickKill.setChecked(AndroidRoutes.kill(this));quickKill.setContentDescription(AppLanguage.t(R.string.ui_030_kill));quickKill.setOnCheckedChangeListener((v,on)->setKill(on));killRow.addView(quickKill,new LinearLayout.LayoutParams(dp(44),dp(36)));kill.addView(killRow);
        controls.addView(kill,new LinearLayout.LayoutParams(0,dp(76),1));
        LinearLayout scope=panel();scope.setPadding(dp(8),dp(6),dp(8),dp(6));scope.setBackground(background(Color.rgb(23,29,35),dp(11),DesignOneWidgets.STROKE));scope.addView(text(AppLanguage.t(R.string.ui_032_scope),10,SECONDARY,false));LinearLayout choices=horizontal();choices.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        designAllApps=button(AppLanguage.t(R.string.ui_032_all_apps),false);designAllApps.setTextSize(9);designAllApps.setPadding(dp(3),0,dp(3),0);designAllApps.setOnClickListener(v->{AndroidRoutes.chatgpt(this,false);getSharedPreferences("persiansocks",0).edit().putString("routing-mode","apps").apply();SplitTunnelSettings.save(this,SplitTunnelPolicy.Mode.ALL,java.util.Collections.emptySet(),false);applyRoutes();});choices.addView(designAllApps,new LinearLayout.LayoutParams(0,dp(36),1));
        chatOnly.setButtonDrawable(null);chatOnly.setTextSize(9);chatOnly.setPadding(dp(3),0,dp(3),0);chatOnly.setGravity(Gravity.CENTER);chatOnly.setMinHeight(0);choices.addView(detach(chatOnly),new LinearLayout.LayoutParams(0,dp(36),1));scope.addView(choices);LinearLayout.LayoutParams scopeCell=new LinearLayout.LayoutParams(0,dp(76),1);controls.addView(new View(this),new LinearLayout.LayoutParams(dp(8),1));controls.addView(scope,scopeCell);hero.addView(controls,margin(dp(12),0));
        hero.addView(detach(vpnStatus),margin(dp(7),0));header.addView(hero,margin(dp(14),0));
        LinearLayout sources=horizontal();sources.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);sources.setPadding(dp(4),dp(4),dp(4),dp(4));sources.setBackground(background(SURFACE,dp(14),DesignOneWidgets.STROKE));String[] captions={AppLanguage.t(R.string.ui_032_all),"SOCKS5","VPN Gate"};ProxyResults.Protocol[] types={ProxyResults.Protocol.ALL,ProxyResults.Protocol.SOCKS,ProxyResults.Protocol.VPN_GATE};
        for(int i=0;i<3;i++){final ProxyResults.Protocol kind=types[i];Button choice=button(captions[i],false);choice.setTextSize(12);choice.setPadding(0,0,0,0);choice.setMinHeight(0);designSources[i]=choice;choice.setOnClickListener(v->{protocol=kind;getSharedPreferences("persiansocks",0).edit().putString("protocol-filter",kind.name()).apply();renderedRevision=-1;render();if(kind==ProxyResults.Protocol.VPN_GATE&&gateDirectory!=null&&gateDirectory.store.snapshot().isEmpty())gateDirectory.refresh();});sources.addView(choice,new LinearLayout.LayoutParams(0,dp(38),1));}header.addView(sources,margin(dp(12),0));
        LinearLayout filters=horizontal();filters.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);designCountry=button(AppLanguage.t(R.string.ui_030_all_countries)+"  ◉",false);designCountry.setTextSize(11);designCountry.setOnClickListener(v->countryDialog());filters.addView(designCountry,new LinearLayout.LayoutParams(0,dp(42),1));
        designSort=button("≡  "+sortLabel(),false);designSort.setTextSize(11);designSort.setOnClickListener(v->designSortDialog());LinearLayout.LayoutParams sorting=new LinearLayout.LayoutParams(0,dp(42),1);sorting.setMarginStart(dp(8));filters.addView(designSort,sorting);header.addView(filters,margin(dp(10),0));
        gateResultsButton=button("",false);gateResultsButton.setTextSize(12);gateResultsButton.setOnClickListener(v->{boolean checked=getSharedPreferences("persiansocks",0).getBoolean("gate-verified-only",true);getSharedPreferences("persiansocks",0).edit().putBoolean("gate-verified-only",!checked).apply();renderedRevision=-1;render();});header.addView(gateResultsButton,margin(dp(8),0));
        filterSummary.setTextSize(10);header.addView(detach(filterSummary),margin(dp(2),0));header.addView(detach(emptyState),margin(dp(4),0));
        results.setAdapter(new ConcatAdapter(new HeaderAdapter(header),resultAdapter));
        LinearLayout footer=new LinearLayout(this);footer.setOrientation(LinearLayout.VERTICAL);footer.setPadding(dp(16),dp(10),dp(16),dp(2));footer.setBackgroundColor(BG);LinearLayout actions=horizontal();actions.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        scanToggle=new DesignOneWidgets.Scan(this);scanToggle.setOnClickListener(v->{
            if(ProxyVpnService.gateScanning()){stopGateScan();return;}if(gateDirectory.busy()){gateDirectory.stop();return;}
            if(protocol==ProxyResults.Protocol.VPN_GATE){requestGateScan(false,null);return;}
            if(scanning||ProxyVpnService.autoScanning())stopScan();else {if(catalogCount==0){Toast.makeText(this,AppLanguage.t(R.string.ui_119),Toast.LENGTH_SHORT).show();return;}if(resumableScan())runPrepared(checkpoint);else doScan(false,false);}
        });scanToggle.setOnLongClickListener(v->{if(protocol==ProxyResults.Protocol.VPN_GATE)chooseGateScan();else chooseScanMode(false);return true;});actions.addView(scanToggle,new LinearLayout.LayoutParams(0,dp(54),1));
        UiIconButton speedAction=new UiIconButton(this,UiIconButton.Kind.DOWNLOAD,AppLanguage.t(R.string.ui_176));speedAction.setBackground(background(SURFACE,dp(13),DesignOneWidgets.STROKE));speedAction.setOnClickListener(v->{if(selected!=null)startSpeedTest(selected);else Toast.makeText(this,AppLanguage.t(R.string.ui_202),Toast.LENGTH_LONG).show();});LinearLayout.LayoutParams speedSize=new LinearLayout.LayoutParams(dp(48),dp(54));speedSize.setMarginStart(dp(8));actions.addView(speedAction,speedSize);

        detach(collectButton);((UiIconButton)collectButton).kind(UiIconButton.Kind.SYNC);((UiIconButton)collectButton).caption("");collectButton.setBackground(background(SURFACE,dp(13),DesignOneWidgets.STROKE));LinearLayout.LayoutParams refresh=new LinearLayout.LayoutParams(dp(48),dp(54));refresh.setMarginStart(dp(8));actions.addView(collectButton,refresh);footer.addView(actions);
        LinearLayout reports=horizontal();reports.setGravity(Gravity.CENTER);status.setGravity(Gravity.CENTER);status.setTextSize(10);reports.addView(detach(status),new LinearLayout.LayoutParams(0,dp(22),1));listStatus.setTextSize(10);reports.addView(detach(listStatus),new LinearLayout.LayoutParams(0,dp(22),1));footer.addView(reports);footer.addView(detach(scanProgress),new LinearLayout.LayoutParams(-1,dp(2)));
        View rule=new View(this);rule.setBackgroundColor(DesignOneWidgets.STROKE);footer.addView(rule,sized(dp(1),dp(4)));LinearLayout navigation=horizontal();navigation.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        designSettings=designNavigation(navigation,UiIconButton.Kind.SETTINGS,AppLanguage.t(R.string.ui_032_settings),()->setSidebarVisible(true));designSaved=designNavigation(navigation,UiIconButton.Kind.STAR,AppLanguage.t(R.string.ui_design_saved),()->{showFavorites=true;renderedRevision=-1;render();});designServers=designNavigation(navigation,UiIconButton.Kind.SERVERS,AppLanguage.t(R.string.ui_032_servers),()->{showFavorites=false;renderedRevision=-1;render();});footer.addView(navigation);
        page.addView(footer,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));sidebarOverlay.bringToFront();
        for(int i=0;i<sidebar.getChildCount();i++)if(sidebar.getChildAt(i) instanceof ScrollView){LinearLayout content=(LinearLayout)((ScrollView)sidebar.getChildAt(i)).getChildAt(0);content.addView(text(AppLanguage.t(R.string.ui_032_list_tools),12,PRIMARY,true),margin(dp(12),0));content.addView(oldTools);content.addView(detach(activeProfile),margin(dp(10),0));LinearLayout metrics=(LinearLayout)trafficDown.getParent().getParent();content.addView(detach(metrics),margin(dp(8),0));break;}
        page.setOnApplyWindowInsetsListener((v,insets)->{int topInset,bottomInset;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());topInset=bars.top;bottomInset=bars.bottom;}else{topInset=insets.getSystemWindowInsetTop();bottomInset=insets.getSystemWindowInsetBottom();}header.setPadding(dp(16),dp(10)+topInset,dp(16),dp(6));footer.setPadding(dp(16),dp(10),dp(16),dp(2)+bottomInset);results.setPadding(0,0,0,dp(158)+bottomInset);if(sidebar!=null)sidebar.setPadding(dp(14),dp(12)+topInset,dp(14),dp(12)+bottomInset);return insets;});page.requestApplyInsets();
    }
    private UiIconButton designNavigation(LinearLayout row,UiIconButton.Kind kind,String caption,Runnable action){UiIconButton b=new UiIconButton(this,kind,caption);b.caption(caption);b.setBackground(null);b.setOnClickListener(v->action.run());row.addView(b,new LinearLayout.LayoutParams(0,dp(58),1));return b;}
    private void designSortDialog(){ProxyResults.Sort[] choices=ProxyResults.Sort.values();String[] labels=new String[choices.length];for(int i=0;i<labels.length;i++)labels[i]=AppLanguage.display(choices[i].label);new AlertDialog.Builder(this,R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_032_sort)).setItems(labels,(d,i)->{sort=choices[i];smoothResults=false;getSharedPreferences("persiansocks",0).edit().putString("sort",sort.name()).putBoolean("smooth-results",false).apply();renderedRevision=-1;render();}).setNeutralButton(AppLanguage.t(R.string.ui_home_filters),(d,w)->homeFiltersDialog()).show();}
    private void refreshDesignOne(){
        if(designAllApps==null)return;((UiIconButton)collectButton).kind(collecting?UiIconButton.Kind.STOP:UiIconButton.Kind.SYNC);((UiIconButton)collectButton).caption("");boolean chat=AndroidRoutes.mode(this).equals("chatgpt");boolean all=AndroidRoutes.mode(this).equals("apps")&&SplitTunnelSettings.load(this).mode==SplitTunnelPolicy.Mode.ALL;
        designAllApps.setBackground(background(all?PRIMARY:SURFACE,dp(9),DesignOneWidgets.STROKE));designAllApps.setTextColor(all?BG:SECONDARY);chatOnly.setBackground(background(chat?PRIMARY:SURFACE,dp(9),DesignOneWidgets.STROKE));chatOnly.setTextColor(chat?BG:SECONDARY);chatOnly.setText(AppLanguage.t(R.string.ui_032_chatgpt));chatOnly.setContentDescription(AppLanguage.t(chat?R.string.ui_031_chatgpt_on:R.string.ui_031_chatgpt_off));
        designKillStatus.setText(AppLanguage.t(!AndroidRoutes.kill(this)?R.string.ui_032_off:ProxyVpnService.guardArmed()?R.string.ui_032_guard:R.string.ui_032_ready));
        for(int i=0;i<3;i++){boolean chosen=i==0?protocol==ProxyResults.Protocol.ALL:i==1?protocol==ProxyResults.Protocol.SOCKS:protocol==ProxyResults.Protocol.VPN_GATE;designSources[i].setBackground(background(chosen?PRIMARY:SURFACE,dp(11),SURFACE));designSources[i].setTextColor(chosen?BG:Color.WHITE);designSources[i].setSelected(chosen);}
        String country=getSharedPreferences("persiansocks",0).getString("display-country","");designCountry.setText((country.isEmpty()?AppLanguage.t(R.string.ui_030_all_countries):CountryLookup.display(country,AppLanguage.locale()))+"  ◉");designSort.setText("≡  "+sortLabel());designServers.setTextColor(showFavorites?SECONDARY:PRIMARY);designSaved.setTextColor(showFavorites?PRIMARY:SECONDARY);designSettings.setTextColor(SECONDARY);
        int phase=vpnRequestPending?ProxyVpnService.CONNECTING:ProxyVpnService.phase();vpnBadge.setTextColor(phase==ProxyVpnService.CONNECTED?PRIMARY:Color.WHITE);if(phase==ProxyVpnService.DISCONNECTED)vpnBadge.setText(AppLanguage.t(R.string.ui_032_disconnected));connectionAction.setText(phase==ProxyVpnService.CONNECTED?AppLanguage.t(R.string.ui_032_connected_hint):phase==ProxyVpnService.CONNECTING?AppLanguage.t(R.string.ui_032_connecting_hint):AppLanguage.t(R.string.ui_032_select_hint));
        filterSummary.setVisibility(filter.equals("ALL")&&pingLimit==ProxyResults.PingLimit.NONE&&!smoothResults?View.GONE:View.VISIBLE);
    }

    private TextView scanCountBadge(LinearLayout parent,int icon,String label){
        LinearLayout row=horizontal();row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        ImageView image=new ImageView(this);image.setImageResource(icon);image.setImageTintList(ColorStateList.valueOf(SECONDARY));image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(image,new LinearLayout.LayoutParams(dp(16),dp(16)));
        TextView value=text(label+" —",11,SECONDARY,false);value.setGravity(Gravity.LEFT);value.setSingleLine(true);value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(0,-2,1);cell.setMarginStart(dp(5));row.addView(value,cell);parent.addView(row,margin(dp(2),dp(2)));return value;
    }
    private boolean resumableScan(){return checkpoint!=null&&!checkpoint.complete()&&checkpoint.compatible();}
    private void saveScanCounts(){getSharedPreferences("persiansocks",MODE_PRIVATE).edit().putInt("last-scan-done",lastScanDone).putInt("last-scan-total",lastScanTotal).apply();}
    private LinearLayout metricColumn(String label) {
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutDirection(AppLanguage.direction());column.setGravity(AppLanguage.gravity());
        column.setPadding(dp(10),dp(8),dp(10),dp(8));column.setBackground(background(Color.rgb(15,30,44),dp(12),Color.rgb(33,54,69)));
        column.addView(text(label, 11, SECONDARY, false)); return column;
    }
    private LinearLayout filterColumn(String label, Button choice) {
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutDirection(AppLanguage.direction());column.setGravity(AppLanguage.gravity());
        column.addView(text(label, 11, SECONDARY, false)); column.addView(choice, sized(dp(48), dp(4)));
        return column;
    }
    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(AppLanguage.direction());
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }
    private TextView chip(String label, int color, int fill) {
        TextView t = text(label, 11, color, true);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        t.setBackground(background(fill, dp(9), fill));
        return t;
    }
    private LinearLayout.LayoutParams sized(int height, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, height);
        p.topMargin = top; return p;
    }
    private void buildSidebar() {
        sidebarOverlay = new FrameLayout(this);
        sidebarOverlay.setVisibility(View.GONE);
        View scrim = new View(this); scrim.setBackgroundColor(Color.argb(170, 0, 0, 0));
        scrim.setContentDescription(AppLanguage.t(R.string.ui_033));
        scrim.setOnClickListener(v -> setSidebarVisible(false));
        sidebarOverlay.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        sidebar = new LinearLayout(this); sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setLayoutDirection(AppLanguage.direction());
        sidebar.setPadding(dp(14), dp(12), dp(14), dp(12));
        sidebar.setBackgroundColor(SURFACE); sidebar.setElevation(dp(16));
        sidebar.setClickable(true); // Consume taps inside the drawer, including empty space.
        // Physical RIGHT is intentional: END would place an RTL drawer on the left.
        FrameLayout.LayoutParams drawerCell = new FrameLayout.LayoutParams(dp(320), -1, AppLanguage.gravity());
        sidebarOverlay.addView(sidebar, drawerCell);
        sidebarOverlay.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            int width = Math.min(dp(320), Math.max(1, (int)((r-l)*.86f)));
            FrameLayout.LayoutParams cell = (FrameLayout.LayoutParams) sidebar.getLayoutParams();
            if (cell.width != width) { cell.width = width; sidebar.setLayoutParams(cell); }
        });
        LinearLayout heading = horizontal();
        heading.addView(text(AppLanguage.t(R.string.ui_034), 17, Color.WHITE, true), new LinearLayout.LayoutParams(0,-2,1));
        Button language=button("en".equals(AppLanguage.code(this))?"FA":"EN",false);language.setTextSize(13);language.setContentDescription(AppLanguage.t(R.string.ui_297));
        language.setOnClickListener(v->{pendingLanguage="en".equals(AppLanguage.code(this))?"fa":"en";applyPendingLanguage();});
        LinearLayout.LayoutParams languageCell=new LinearLayout.LayoutParams(dp(48),dp(48));languageCell.setMarginEnd(dp(12));heading.addView(language,languageCell);
        sidebarClose=new UiIconButton(this,UiIconButton.Kind.CLOSE,AppLanguage.t(R.string.ui_036));sidebarClose.setContentDescription(AppLanguage.t(R.string.ui_036));sidebarClose.setOnClickListener(v->setSidebarVisible(false));heading.addView(sidebarClose,new LinearLayout.LayoutParams(dp(48),dp(48)));sidebar.addView(heading);
        ScrollView scroll=new ScrollView(this);LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(0,dp(12),0,dp(8));scroll.addView(content);sidebar.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        sidebarKill=sidebarCheck(AppLanguage.t(R.string.ui_030_kill),AndroidRoutes.kill(this));sidebarKill.setOnCheckedChangeListener((v,on)->setKill(on));content.addView(sidebarKill,sized(dp(48),0));
        sidebarTool(content,UiIconButton.Kind.PRIVACY,AppLanguage.t(R.string.ui_030_guard_help),this::guardHelp);
        sidebarTool(content,UiIconButton.Kind.TARGET,AppLanguage.t(R.string.ui_030_routes),this::routesDialog);
        sidebarTool(content,UiIconButton.Kind.TARGET,AppLanguage.t(R.string.ui_030_country),this::countryDialog);
        content.addView(text(AppLanguage.t(R.string.ui_design_network),12,PRIMARY,true),margin(0,dp(8)));
        sidebarTls=sidebarCheck(AppLanguage.t(R.string.ui_038),DistributionPolicy.encryptedOnly());sidebarTls.setEnabled(!DistributionPolicy.play());sidebarTls.setOnCheckedChangeListener((b,on)->{if(on!=DistributionPolicy.encryptedOnly())changeTlsMode(on);});content.addView(sidebarTls,sized(dp(48),0));
        sidebarAuto=sidebarCheck(AppLanguage.t(R.string.ui_039),getSharedPreferences("persiansocks",MODE_PRIVATE).getBoolean("auto-switch",true));sidebarAuto.setOnCheckedChangeListener((b,on)->getSharedPreferences("persiansocks",MODE_PRIVATE).edit().putBoolean("auto-switch",on).apply());content.addView(sidebarAuto,sized(dp(48),0));
        CheckBox upkeep=sidebarCheck(AppLanguage.t(R.string.ui_maintenance),getSharedPreferences("persiansocks",0).getBoolean("background-maintenance",true));
        upkeep.setOnCheckedChangeListener((b,on)->{getSharedPreferences("persiansocks",0).edit().putBoolean("background-maintenance",on).apply();ProxyMaintenance.schedule(this);});content.addView(upkeep,sized(dp(48),0));
        content.addView(text(AppLanguage.t(R.string.ui_maintenance_help),11,SECONDARY,false),margin(dp(4),0));
        content.addView(text(AppLanguage.t(R.string.ui_design_tools),12,PRIMARY,true),margin(dp(18),dp(4)));
        sidebarTool(content,UiIconButton.Kind.SPLIT,AppLanguage.t(R.string.ui_254),()->startActivity(new Intent(this,SplitTunnelActivity.class)));
        sidebarTool(content,UiIconButton.Kind.SOURCE,AppLanguage.t(R.string.ui_227),this::sourceDialog);
        sidebarTool(content,UiIconButton.Kind.TARGET,AppLanguage.t(R.string.ui_047),this::targetsDialog);
        sidebarTool(content,UiIconButton.Kind.PRIVACY,AppLanguage.t(R.string.ui_276),()->VpnDisclosure.showPrivacy(this));
        content.addView(text(AppLanguage.t(R.string.ui_041),14,PRIMARY,true),margin(dp(18),0));
        final int[] values={5000,10000,20000,ProxyScanner.MAX_TESTS};
        if(!scanAll&&maxTests!=5000&&maxTests!=10000&&maxTests!=20000){maxTests=5000;saveScanLimit();}
        ScanLimitView limits=new ScanLimitView(this,scanAll?3:maxTests==5000?0:maxTests==10000?1:2,index->{scanAll=index==3;maxTests=values[index];saveScanLimit();});content.addView(limits,sized(dp(64),0));
        content.addView(text(AppLanguage.t(R.string.ui_design_tests),12,PRIMARY,true),margin(dp(12),dp(4)));
        sidebarTool(content,UiIconButton.Kind.PING,AppLanguage.t(R.string.ui_target_ping),this::pingTargetDialog);
        sidebarTool(content,UiIconButton.Kind.DOWNLOAD,AppLanguage.t(R.string.ui_target_speed),this::speedTargetDialog);
        sidebarTool(content,UiIconButton.Kind.SYNC,AppLanguage.t(R.string.ui_scan_healthy),()->chooseScanMode(true));
        LinearLayout footer=horizontal();sidebarIcon(footer,UiIconButton.Kind.INFO,AppLanguage.t(R.string.ui_054),this::aboutDialog);sidebarIcon(footer,UiIconButton.Kind.TELEGRAM,"Telegram @HTpink",()->{try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://t.me/HTpink")));}catch(Exception ignored){}});
        TextView version=text("v"+AppLanguage.version(this),10,SECONDARY,false);version.setTextDirection(View.TEXT_DIRECTION_LTR);footer.addView(version,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout updateBox=new LinearLayout(this);updateBox.setOrientation(LinearLayout.VERTICAL);updateBox.setGravity(Gravity.CENTER);UiIconButton update=new UiIconButton(this,UiIconButton.Kind.UPDATE,AppLanguage.t(R.string.ui_update_000));updateBox.addView(update,new LinearLayout.LayoutParams(dp(48),dp(48)));TextView available=text("",9,SECONDARY,false);available.setGravity(Gravity.CENTER);available.setTextDirection(AppLanguage.textDirection());available.setSingleLine(true);updateBox.addView(available);footer.addView(updateBox,new LinearLayout.LayoutParams(dp(96),-2));appUpdater=new AppUpdater(this,update,available);sidebar.addView(footer,margin(dp(12),0));
        page.addView(sidebarOverlay,new FrameLayout.LayoutParams(-1,-1));
    }
    private boolean testSettingsBusy(){
        if(scanning||ProxyVpnService.autoScanning()||collecting||preparing||loading||speedTest!=null||singleTest!=null||speedBatch!=null||preparingSpeedBatch){Toast.makeText(this,AppLanguage.t(R.string.ui_target_busy),Toast.LENGTH_SHORT).show();return true;}return false;
    }
    private void pingTargetDialog(){
        if(testSettingsBusy())return;
        LinearLayout panel=dialogContent();panel.addView(text(AppLanguage.t(R.string.ui_target_ping_help),13,SECONDARY,false));
        CheckBox only=sidebarCheck(AppLanguage.t(R.string.ui_target_only),getSharedPreferences("persiansocks",0).getBoolean("scan-ping-only",false));panel.addView(only,sized(dp(48),dp(8)));
        android.widget.RadioGroup choices=new android.widget.RadioGroup(this);choices.setLayoutDirection(AppLanguage.direction());List<String> urls=new ArrayList<>();List<String> labels=new ArrayList<>();urls.add("");labels.add(AppLanguage.t(R.string.ui_target_auto));
        for(ServiceTargets.Target t:ServiceTargets.current()){urls.add(t.url());labels.add(AppLanguage.display(t.name)+" • "+t.host);}
        String selected=getSharedPreferences("persiansocks",0).getString("ping-target-url","");
        for(int i=0;i<urls.size();i++){android.widget.RadioButton b=new android.widget.RadioButton(this);b.setId(i+1);b.setText(labels.get(i));b.setTextColor(Color.WHITE);b.setTextSize(13);b.setButtonTintList(ColorStateList.valueOf(PRIMARY));b.setMinHeight(dp(48));b.setLayoutDirection(AppLanguage.direction());choices.addView(b);if(urls.get(i).equals(selected))b.setChecked(true);}panel.addView(choices);
        LinearLayout actions=horizontal();Button save=button(AppLanguage.t(R.string.ui_065),true),cancel=button(AppLanguage.t(R.string.ui_067),false);actions.addView(save,weighted(48,1,6));actions.addView(cancel,weighted(48,1,0));panel.addView(actions,margin(dp(12),0));
        ScrollView scroll=new ScrollView(this);scroll.addView(panel);AlertDialog dialog=new AlertDialog.Builder(this,R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_target_ping)).setView(scroll).create();cancel.setOnClickListener(v->dialog.dismiss());
        save.setOnClickListener(v->{int index=choices.indexOfChild(choices.findViewById(choices.getCheckedRadioButtonId()));if(index<0||index>=urls.size())index=0;String url=urls.get(index);if(only.isChecked()&&url.isEmpty()){Toast.makeText(this,AppLanguage.t(R.string.ui_target_choose),Toast.LENGTH_SHORT).show();return;}getSharedPreferences("persiansocks",0).edit().putBoolean("scan-ping-only",only.isChecked()).putString("ping-target-url",url).apply();ScanInputs.configureTests(this);renderedRevision=-1;dialog.dismiss();render();});dialog.show();
    }
    private void speedTargetDialog(){
        if(testSettingsBusy())return;
        LinearLayout panel=dialogContent();panel.addView(text(AppLanguage.t(R.string.ui_target_speed_help),13,SECONDARY,false));
        String saved=ScanInputs.speedUrl(this);CheckBox custom=sidebarCheck(AppLanguage.t(R.string.ui_target_custom),!saved.isEmpty());panel.addView(custom,sized(dp(48),dp(8)));
        StringBuilder domains=new StringBuilder();for(ServiceTargets.Target t:ServiceTargets.current()){if(domains.length()>0)domains.append(" • ");domains.append(t.host);}panel.addView(text(domains.toString(),11,SECONDARY,false));
        EditText link=new EditText(this);link.setText(saved);link.setHint("https://example.com/test-file.bin");link.setTextColor(Color.WHITE);link.setTextSize(14);link.setSingleLine(true);link.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);link.setTextDirection(View.TEXT_DIRECTION_LTR);link.setEnabled(custom.isChecked());custom.setOnCheckedChangeListener((b,on)->link.setEnabled(on));panel.addView(link,sized(dp(56),dp(8)));
        Button domainsButton=button(AppLanguage.t(R.string.ui_047),false);panel.addView(domainsButton,sized(dp(48),dp(8)));
        LinearLayout actions=horizontal();Button save=button(AppLanguage.t(R.string.ui_065),true),cancel=button(AppLanguage.t(R.string.ui_067),false);actions.addView(save,weighted(48,1,6));actions.addView(cancel,weighted(48,1,0));panel.addView(actions,margin(dp(12),0));
        ScrollView scroll=new ScrollView(this);scroll.addView(panel);AlertDialog dialog=new AlertDialog.Builder(this,R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_target_speed)).setView(scroll).create();cancel.setOnClickListener(v->dialog.dismiss());domainsButton.setOnClickListener(v->{dialog.dismiss();targetsDialog();});
        save.setOnClickListener(v->{String url="";if(custom.isChecked())try{url=TestDownloadPolicy.validate(link.getText().toString().trim());}catch(Exception ex){link.setError(AppLanguage.t(R.string.ui_target_invalid));return;}getSharedPreferences("persiansocks",0).edit().putString("speed-target-url",url).apply();dialog.dismiss();renderedRevision=-1;render();});dialog.show();
    }
    private void saveScanLimit(){getSharedPreferences("persiansocks",MODE_PRIVATE).edit().putInt("max-tests",maxTests).putBoolean("scan-all",scanAll).apply();}
    private void sidebarIcon(LinearLayout row,UiIconButton.Kind kind,String label,Runnable action){UiIconButton icon=new UiIconButton(this,kind,label);icon.setOnClickListener(v->action.run());LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(dp(48),dp(48));cell.setMarginEnd(dp(6));row.addView(icon,cell);}
    private void sidebarTool(LinearLayout parent,UiIconButton.Kind kind,String label,Runnable action){
        LinearLayout item=horizontal();item.setPadding(dp(8),dp(4),dp(10),dp(4));item.setMinimumHeight(dp(56));
        item.setBackground(background(Color.rgb(18,28,43),dp(12),STROKE));
        item.setForeground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Color.argb(30,70,226,184)),null,background(Color.WHITE,dp(12),Color.WHITE)));
        UiIconButton icon=new UiIconButton(this,kind,label);icon.setBackground(null);icon.setClickable(false);icon.setFocusable(false);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(icon,new LinearLayout.LayoutParams(dp(32),dp(40)));
        TextView name=text(label,13,Color.WHITE,false);name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(0,-2,1);cell.setMarginStart(dp(10));item.addView(name,cell);
        item.setFocusable(true);item.setContentDescription(label);item.setOnClickListener(v->{setSidebarVisible(false);action.run();});
        parent.addView(item,margin(dp(6),0));
    }
    private Button sidebarAction(LinearLayout parent,String label,Runnable action) {
        Button choice = button(label,false); choice.setTextSize(13);
        choice.setGravity(AppLanguage.gravity()|Gravity.CENTER_VERTICAL); choice.setPadding(dp(12),0,dp(12),0);
        choice.setOnClickListener(v -> { setSidebarVisible(false); action.run(); });
        parent.addView(choice,sized(dp(48),dp(6)));return choice;
    }
    private CheckBox sidebarCheck(String label,boolean checked){CheckBox box=new CheckBox(this);box.setText(AppLanguage.display(label));box.setTextSize(13);box.setTextColor(Color.WHITE);box.setButtonTintList(ColorStateList.valueOf(PRIMARY));box.setChecked(checked);return box;}
    private void crashReport(){
        ProxyStore.IO.execute(()->{
            String report=AppLanguage.t(R.string.ui_055);
            try{java.io.File file=new java.io.File(getFilesDir(),"last-crash.txt");
                if(file.exists())report=new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8);}
            catch(Exception ex){report=AppLanguage.t(R.string.ui_056);}
            final String message=report;
            ui.post(()->{if(isDestroyed())return;new AlertDialog.Builder(this, R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_052)).setMessage(message)
                .setPositiveButton(AppLanguage.t(R.string.ui_057),(d,w)->{android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PersianSocks",message));})
                .setNegativeButton(AppLanguage.t(R.string.ui_007),null).show();});
        });
    }
    private void setSidebarVisible(boolean visible) {
        if (sidebarOverlay == null || sidebarVisible == visible) return;
        sidebarVisible = visible;
        if(visible&&page.getWidth()>0){
            FrameLayout.LayoutParams cell=(FrameLayout.LayoutParams)sidebar.getLayoutParams();
            cell.width=Math.min(dp(320),Math.max(1,(int)(page.getWidth()*.86f)));sidebar.setLayoutParams(cell);
        }
        sidebarOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        mainScroller.setImportantForAccessibility(visible ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        ((UiIconButton)sidebarToggle).kind(visible?UiIconButton.Kind.CLOSE:UiIconButton.Kind.MENU);
        sidebarToggle.setContentDescription(visible ? AppLanguage.t(R.string.ui_036) : AppLanguage.t(R.string.ui_001));
        if (visible) sidebarClose.requestFocus(); else sidebarToggle.requestFocus();
        if (Build.VERSION.SDK_INT >= 33) {
            if (visible && sidebarBack == null) {
                sidebarBack = () -> setSidebarVisible(false);
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,sidebarBack);
            } else if (!visible && sidebarBack != null) {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(sidebarBack); sidebarBack = null;
            }
        }
    }
    private LinearLayout.LayoutParams scanActionCell(int gap){LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(dp(52),dp(60));cell.setMarginEnd(dp(gap));return cell;}
    private LinearLayout.LayoutParams iconCell(int gap){LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(dp(48),dp(48));cell.setMarginEnd(dp(gap));return cell;}
    private void iconState(Button button,UiIconButton.Kind kind,String description,boolean active){
        UiIconButton icon=(UiIconButton)button;icon.kind(kind);icon.active(active);icon.setContentDescription(AppLanguage.display(description));icon.setTooltipText(icon.getContentDescription());
    }
    private void homeFiltersDialog(){
        final String[] draft={filter,protocol.name(),pingLimit.name(),smoothResults?"SMOOTH":sort.name()};
        LinearLayout panel=dialogContent(),tabs=horizontal();
        int[] titles={R.string.ui_025,R.string.ui_027,R.string.ui_029,R.string.ui_031};
        UiIconButton.Kind[] kinds={UiIconButton.Kind.TARGET,UiIconButton.Kind.PROTOCOL,UiIconButton.Kind.PING,UiIconButton.Kind.SORT};
        UiIconButton[] buttons=new UiIconButton[4];final int[] category={0};
        TextView heading=text("",14,PRIMARY,true);android.widget.RadioGroup options=new android.widget.RadioGroup(this);options.setOrientation(LinearLayout.VERTICAL);options.setLayoutDirection(AppLanguage.direction());
        Runnable showOptions=()->{
            int section=category[0];for(int i=0;i<4;i++)buttons[i].active(i==section);
            heading.setText(AppLanguage.t(titles[section]));options.setOnCheckedChangeListener(null);options.clearCheck();options.removeAllViews();
            List<String> values=new ArrayList<>(),labels=new ArrayList<>();
            if(section==0){for(String key:filterKeys){values.add(key);labels.add(AppLanguage.display(ProxyResults.label(key)));}}
            else if(section==1){for(ProxyResults.Protocol value:ProxyResults.Protocol.values()){
                if(DistributionPolicy.play()&&value!=ProxyResults.Protocol.HTTP||DistributionPolicy.encryptedOnly()&&value==ProxyResults.Protocol.SOCKS)continue;
                values.add(value.name());labels.add(value==ProxyResults.Protocol.HTTP&&DistributionPolicy.encryptedOnly()?"HTTPS / TLS":AppLanguage.display(value.label));}}
            else if(section==2){for(ProxyResults.PingLimit value:ProxyResults.PingLimit.values()){values.add(value.name());labels.add(AppLanguage.display(value.label));}}
            else{for(ProxyResults.Sort value:ProxyResults.Sort.values()){values.add(value.name());labels.add(AppLanguage.display(value.label));if(value==ProxyResults.Sort.DOWNLOAD){values.add("SMOOTH");labels.add(AppLanguage.t(R.string.ui_081));}}}
            for(int i=0;i<values.size();i++){android.widget.RadioButton choice=new android.widget.RadioButton(this);choice.setId(i+1);choice.setText(labels.get(i));choice.setTextSize(13);choice.setTextColor(Color.WHITE);choice.setLayoutDirection(AppLanguage.direction());choice.setTextDirection(AppLanguage.textDirection());choice.setButtonTintList(ColorStateList.valueOf(PRIMARY));choice.setPadding(dp(4),dp(4),dp(4),dp(4));choice.setMinHeight(dp(48));options.addView(choice,new android.widget.RadioGroup.LayoutParams(-1,-2));if(values.get(i).equals(draft[section]))choice.setChecked(true);}
            options.setOnCheckedChangeListener((group,id)->{if(id>0&&id<=values.size())draft[section]=values.get(id-1);});
        };
        for(int i=0;i<4;i++){final int index=i;buttons[i]=new UiIconButton(this,kinds[i],AppLanguage.t(titles[i]));buttons[i].setOnClickListener(v->{category[0]=index;showOptions.run();});tabs.addView(buttons[i],weighted(48,1,i==3?0:6));}
        panel.addView(tabs);panel.addView(heading,margin(dp(12),0));panel.addView(options);
        LinearLayout actions=horizontal();Button save=button(AppLanguage.t(R.string.ui_065),true),reset=button(AppLanguage.t(R.string.ui_home_reset),false),cancel=button(AppLanguage.t(R.string.ui_067),false);
        actions.addView(save,weighted(48,1,6));actions.addView(reset,weighted(48,1,6));actions.addView(cancel,weighted(48,1,0));panel.addView(actions,margin(dp(12),0));
        ScrollView scroll=new ScrollView(this);scroll.addView(panel);AlertDialog dialog=new AlertDialog.Builder(this,R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_home_filters)).setView(scroll).create();
        reset.setOnClickListener(v->{draft[0]="ALL";draft[1]=DistributionPolicy.encryptedOnly()?"HTTP":"ALL";draft[2]="NONE";draft[3]="FASTEST";showOptions.run();});cancel.setOnClickListener(v->dialog.dismiss());
        save.setOnClickListener(v->{boolean changed=!filter.equals(draft[0])||!protocol.name().equals(draft[1])||!pingLimit.name().equals(draft[2]);
            filter=draft[0];protocol=ProxyResults.Protocol.valueOf(draft[1]);pingLimit=ProxyResults.PingLimit.valueOf(draft[2]);smoothResults=draft[3].equals("SMOOTH");if(!smoothResults)sort=ProxyResults.Sort.valueOf(draft[3]);
            getSharedPreferences("persiansocks",MODE_PRIVATE).edit().putString("filter",filter).putString("protocol-filter",protocol.name()).putInt("ping-limit-ms",pingLimit.milliseconds).putString("sort",sort.name()).putBoolean("smooth-results",smoothResults).apply();
            if(ProxyVpnService.phase()==ProxyVpnService.DISCONNECTED&&!vpnRequestPending&&(changed||!userSelected))selectBest();dialog.dismiss();render();});
        showOptions.run();dialog.show();
    }
    private void rebuildFilters() {
        filterKeys.clear();
        for (ProxyResults.Filter option : ProxyResults.Filter.values()) filterKeys.add(option.name());
        for (ServiceTargets.Target target : customTargets) filterKeys.add(target.id);
    }
    private void connectionSettings() {
        LinearLayout inputs = dialogContent(); inputs.setLayoutDirection(AppLanguage.direction());
        CheckBox tls = new CheckBox(this); tls.setText(AppLanguage.t(R.string.ui_059));
        tls.setTextColor(Color.WHITE); tls.setTextSize(14); tls.setButtonTintList(ColorStateList.valueOf(PRIMARY));
        tls.setChecked(DistributionPolicy.encryptedOnly()); tls.setEnabled(!DistributionPolicy.play());
        inputs.addView(tls);
        inputs.addView(text(DistributionPolicy.play() ? AppLanguage.t(R.string.ui_060) :
            AppLanguage.t(R.string.ui_061),
            12, SECONDARY, false), margin(dp(4), 0));
        CheckBox auto = new CheckBox(this); auto.setText(AppLanguage.t(R.string.ui_062));
        auto.setTextColor(Color.WHITE); auto.setTextSize(14); auto.setButtonTintList(ColorStateList.valueOf(PRIMARY));
        auto.setChecked(getSharedPreferences("persiansocks", MODE_PRIVATE).getBoolean("auto-switch", true));
        inputs.addView(auto, margin(dp(12), 0));
        inputs.addView(text(AppLanguage.t(R.string.ui_063), 12, SECONDARY, false));
        ScrollView scroll = new ScrollView(this); scroll.addView(inputs);
        new AlertDialog.Builder(this, R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_064)).setView(scroll)
            .setPositiveButton(AppLanguage.t(R.string.ui_065), (d, which) -> {
                getSharedPreferences("persiansocks", MODE_PRIVATE).edit().putBoolean("auto-switch", auto.isChecked()).apply();
                if (!DistributionPolicy.play() && tls.isChecked() != DistributionPolicy.encryptedOnly()) changeTlsMode(tls.isChecked());
                else Toast.makeText(this, AppLanguage.t(R.string.ui_066), Toast.LENGTH_SHORT).show();
            }).setNegativeButton(AppLanguage.t(R.string.ui_067), null).show();
    }
    private void saveTargets() {
        cancelSingleTests();
        ServiceTargets.configure(customTargets);
        getSharedPreferences("persiansocks", MODE_PRIVATE).edit()
            .putString("test-domains", ServiceTargets.encode(customTargets)).putString("filter", filter).apply();
        if(siteIcons!=null)siteIcons.sync();rebuildFilters(); render();
    }
    private void targetsDialog() {
        List<String> items = new ArrayList<>(); items.add(AppLanguage.t(R.string.ui_068));
        for (ServiceTargets.Target t : customTargets) items.add(t.name + "  •  " + t.host);
        new AlertDialog.Builder(this, R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_047))
            .setItems(items.toArray(new String[0]), (d, index) -> {
                if (index == 0) { addTargetDialog(); return; }
                ServiceTargets.Target target = customTargets.get(index - 1);
                new AlertDialog.Builder(this, R.style.AppDialogTheme).setTitle(AppLanguage.display(target.name)).setMessage(target.url())
                    .setPositiveButton(AppLanguage.t(R.string.ui_069), (dialog, which) -> {
                        if (scanning||ProxyVpnService.autoScanning()) stopScan();
                        customTargets.remove(target);
                        if (filter.equals(target.id)) filter = "ALL";
                        saveTargets();
                        healthy.removeIf(p -> !p.hasAnyService());
                        ProxyStore.save(this, healthy); render();
                    }).setNegativeButton(AppLanguage.t(R.string.ui_007), null).show();
            }).setNegativeButton(AppLanguage.t(R.string.ui_007), null).show();
    }
    private void addTargetDialog() {
        if (customTargets.size() >= ServiceTargets.MAX_CUSTOM) {
            Toast.makeText(this, AppLanguage.t(R.string.ui_070), Toast.LENGTH_LONG).show(); return;
        }
        LinearLayout inputs = dialogContent();
        EditText name = new EditText(this); name.setHint(AppLanguage.t(R.string.ui_071)); name.setSingleLine(true);
        EditText url = new EditText(this); url.setHint(AppLanguage.t(R.string.ui_072));
        url.setSingleLine(true); url.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
            android.text.InputType.TYPE_TEXT_VARIATION_URI); url.setTextDirection(View.TEXT_DIRECTION_LTR);
        inputs.addView(name); inputs.addView(url);
        inputs.addView(text(AppLanguage.t(R.string.ui_073),
            12, SECONDARY, false), margin(dp(10), 0));
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_074))
            .setView(inputs).setPositiveButton(AppLanguage.t(R.string.ui_075), null).setNegativeButton(AppLanguage.t(R.string.ui_067), null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            ServiceTargets.Target target;
            try { target = ServiceTargets.custom(name.getText().toString(), url.getText().toString()); }
            catch (IllegalArgumentException ex) { url.setError(ex.getMessage()); return; }
            for (ServiceTargets.Target existing : ServiceTargets.current()) if (existing.url().equals(target.url())) {
                url.setError(AppLanguage.t(R.string.ui_076)); return;
            }
            if (scanning||ProxyVpnService.autoScanning()) stopScan();
            boolean haveSaved = !healthy.isEmpty();
            customTargets.add(target); filter = target.id; saveTargets(); dialog.dismiss(); if (haveSaved) chooseScanMode(true); else status.setText(AppLanguage.t(R.string.ui_077));
        }));
        dialog.show();
    }
    private String pendingLanguage;
    private void languageDialog() {
        new AlertDialog.Builder(this,R.style.AppDialogTheme)
            .setTitle(AppLanguage.t(R.string.ui_297))
            .setSingleChoiceItems(new String[]{"فارسی","English"},"en".equals(AppLanguage.code(this))?1:0,(dialog,which)->{
                dialog.dismiss();pendingLanguage=which==1?"en":"fa";
                if(languageBusy())Toast.makeText(this,AppLanguage.t(R.string.ui_298),Toast.LENGTH_LONG).show();
                applyPendingLanguage();
            }).setNegativeButton(AppLanguage.t(R.string.ui_007),null).show();
    }
    private boolean languageBusy() { return preparing||stopping||loading; }
    private void applyPendingLanguage() {
        if(isDestroyed()||pendingLanguage==null)return;
        if(languageBusy()){ui.postDelayed(this::applyPendingLanguage,500);return;}
        String next=pendingLanguage;pendingLanguage=null;if(next.equals(AppLanguage.code(this)))return;AppLanguage.select(this,next);boolean opened=sidebarVisible;setSidebarVisible(false);if(gateDirectory!=null)gateDirectory.close();
        if(appUpdater!=null)appUpdater.close();buildUi();renderedRevision=-1;render();setSidebarVisible(opened);if(appUpdater!=null)appUpdater.start();
    }
    private void aboutDialog() {
        TextView info = text("PersianSocks " + AppLanguage.version(this) + "\n\n" + AppLanguage.t(R.string.ui_078) +
            "IP Geolocation by DB-IP\nhttps://db-ip.com\n" +
            AppLanguage.t(R.string.ui_079) +
            AppLanguage.t(R.string.ui_080)+"\n\nOpenVPN 2.7.8 · GPL-2.0\nOpenSSL 3.5.9 · Apache-2.0\nLZO 2.10 · GPL-2.0+\nLZ4 1.10.0 · BSD-2-Clause\nhttps://github.com/A-Masoumiyan/PersianSocks/releases/tag/v0.31.0\n\n"+AppLanguage.t(R.string.ui_032_gate_privacy),
            13, Color.WHITE, false);
        info.setPadding(dp(20), dp(16), dp(20), dp(16)); info.setTextIsSelectable(true);
        android.text.util.Linkify.addLinks(info, android.text.util.Linkify.WEB_URLS);
        info.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        new AlertDialog.Builder(this, R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_054)).setView(info).setPositiveButton(AppLanguage.t(R.string.ui_007), null).show();
    }
    private String sortLabel() {
        if(smoothResults)return AppLanguage.t(R.string.ui_081);
        switch (sort) {
            case QUALITY: return AppLanguage.t(R.string.ui_quality_sort);
            case DOWNLOAD: return AppLanguage.t(R.string.ui_082);
            case MOST_SERVICES: return AppLanguage.t(R.string.ui_083);
            case RECENT: return AppLanguage.t(R.string.ui_084);
            case UDP_FIRST: return AppLanguage.t(R.string.ui_085);
            default: return AppLanguage.t(R.string.ui_086);
        }
    }
    private void selectBest() { userSelected=false; renderedRevision=-1; scheduleRender(); }
    private void scheduleRender() {
        if(!foregroundVisible)return;
        if(redrawQueued.compareAndSet(false,true))ui.postDelayed(()->{redrawQueued.set(false);if(!isDestroyed())render();},1000);
    }
    private void updateActions() {
        if(!foregroundVisible)return;
        if (scanToggle == null) return;
        if(chatOnly!=null){boolean enabled=AndroidRoutes.mode(this).equals("chatgpt");chatOnly.setChecked(enabled);chatOnly.setText(AppLanguage.t(enabled?R.string.ui_031_chatgpt_on:R.string.ui_031_chatgpt_off));}
        if(quickKill!=null&&AndroidRoutes.kill(this))quickKill.setText(AppLanguage.t(AndroidRoutes.kill(this)&&ProxyVpnService.guardArmed()?R.string.ui_030_guard_ready:R.string.ui_030_guard_wait));
        boolean auto=ProxyVpnService.autoScanning(), anyScan=scanning||auto||ProxyVpnService.gateScanning()||(gateDirectory!=null&&gateDirectory.busy());
        if(gateResultsButton!=null){gateResultsButton.setVisibility(protocol==ProxyResults.Protocol.VPN_GATE?View.VISIBLE:View.GONE);gateResultsButton.setText(AppLanguage.t(getSharedPreferences("persiansocks",0).getBoolean("gate-verified-only",true)?R.string.ui_gate_verified_only:R.string.ui_gate_candidates));}
        boolean resumable=protocol!=ProxyResults.Protocol.VPN_GATE&&resumableScan();
        iconState(scanToggle,anyScan?UiIconButton.Kind.STOP:UiIconButton.Kind.PLAY,AppLanguage.t(anyScan?R.string.ui_scan_pause:resumable?R.string.ui_scan_resume:R.string.ui_home_scan),anyScan||resumable);
        ((UiIconButton)scanToggle).caption(AppLanguage.t(anyScan?R.string.ui_design_pause:resumable?R.string.ui_design_resume:R.string.ui_design_scan));
        boolean dataBusy=!localDataLoaded||loading||preparing||stopping;
        scanToggle.setEnabled(protocol==ProxyResults.Protocol.VPN_GATE||(!collecting && !dataBusy));
        collectButton.setEnabled(!anyScan && !dataBusy);
        scanRestart.setEnabled(!anyScan&&!collecting&&!dataBusy&&catalogCount>0);
        int total=ProxyVpnService.gateScanning()?ProxyVpnService.gateTotal():auto?ProxyVpnService.autoScanTotal():checkpoint!=null?checkpoint.total():lastScanTotal>0?lastScanTotal:catalogCount;
        int done=ProxyVpnService.gateScanning()?ProxyVpnService.gateDone():auto?ProxyVpnService.autoScanDone():checkpoint!=null?checkpoint.done():lastScanDone;
        scanTotal.setText(AppLanguage.t(R.string.ui_scan_total)+" "+AppLanguage.isolate(localDataLoaded?Integer.toString(total):"—"));
        scanChecked.setText(AppLanguage.t(R.string.ui_scan_checked)+" "+AppLanguage.isolate(localDataLoaded?Integer.toString(done):"—"));
        scanTotal.setContentDescription(AppLanguage.t(R.string.ui_scan_total)+": "+total);scanChecked.setContentDescription(AppLanguage.t(R.string.ui_scan_checked)+": "+done);
        iconState(collectButton,collecting?UiIconButton.Kind.STOP:UiIconButton.Kind.COLLECT,collecting?AppLanguage.t(R.string.ui_088):AppLanguage.t(R.string.ui_014),collecting);
        if (!collecting) listStatus.setText(AppLanguage.t(R.string.ui_089) + catalogCount + AppLanguage.t(R.string.ui_090));
        listStatus.setVisibility(collecting ? View.VISIBLE : View.GONE);
        status.setVisibility(collecting ? View.GONE : View.VISIBLE);
        if (catalogInfo != null) catalogInfo.setText(AppLanguage.t(R.string.ui_089) + catalogCount + AppLanguage.t(R.string.ui_090));
        scanToggle.setTextColor(anyScan ? DANGER : Color.WHITE);
        scanProgress.setVisibility(anyScan||collecting||resumable?View.VISIBLE:View.GONE);
        if(!anyScan&&!collecting&&resumable){scanProgress.setIndeterminate(false);scanProgress.setProgress(total==0?0:(int)(100L*done/total));}
        if(ProxyVpnService.gateScanning()){scanProgress.setIndeterminate(total==0);scanProgress.setProgress(total==0?0:(int)(100L*done/total));}
        if(auto){
            int progress=ProxyVpnService.autoProgress();
            scanProgress.setIndeterminate(progress<0);scanProgress.setProgress(Math.max(0,progress));
        }
        int phase = vpnRequestPending ? ProxyVpnService.CONNECTING : ProxyVpnService.phase();
        if(observedVpnPhase!=phase){observedVpnPhase=phase;renderedRevision=-1;scheduleRender();}
        boolean active = phase != ProxyVpnService.DISCONNECTED;
        vpnToggle.showState(phase==ProxyVpnService.CONNECTED,phase==ProxyVpnService.CONNECTING);
        vpnBadge.setText(AppLanguage.t(phase==ProxyVpnService.CONNECTED?R.string.ui_091:phase==ProxyVpnService.CONNECTING?R.string.ui_design_connecting:R.string.ui_design_disconnected));
        connectionAction.setText(AppLanguage.t(phase==ProxyVpnService.CONNECTED?R.string.ui_design_disconnect:phase==ProxyVpnService.CONNECTING?R.string.ui_design_cancel:R.string.ui_design_connect));
        vpnBadge.setTextColor(phase==ProxyVpnService.CONNECTED?PRIMARY:phase==ProxyVpnService.CONNECTING?WARNING:SECONDARY);
        vpnStatus.setText(AppLanguage.display(connectionError));vpnStatus.setTextColor(DANGER);vpnStatus.setVisibility(connectionError.isEmpty()?View.GONE:View.VISIBLE);
        String current = active ? ProxyVpnService.activeEndpoint() : "";
        String address = !current.isEmpty()?current:selected!=null?selected.key():"";
        refreshPublicIp(false);
        ProxyEndpoint displayed = address.isEmpty() ? null : ProxyEndpoint.parse(address);
        selection.setText(displayed == null ? (address.isEmpty() ? AppLanguage.t(R.string.ui_095) : address) : displayed.address());
        selectionProtocol.setText(displayed == null ? "—" : displayed.type.name());
        ProxyEndpoint identity=displayed==null?null:healthy.find(address);
        if(identity==null&&displayed!=null)identity=favorites.get(address);
        if(identity==null&&selected!=null&&selected.key().equals(address))identity=selected;
        String country=displayed==null?"":identity!=null&&!identity.countryCode.isEmpty()?identity.countryCode:CountryLookup.lookup(displayed.host);
        selectionCountry.setText(displayed==null?"":AppLanguage.display(CountryLookup.display(country,AppLanguage.locale())));
        selectionCountry.setVisibility(displayed==null?View.GONE:View.VISIBLE);
        selection.setContentDescription(address.isEmpty()?AppLanguage.t(R.string.ui_004):address+" • "+selectionCountry.getText());
        boolean busy = speedTest != null || singleTest != null || speedBatch != null || preparingSpeedBatch;
        if(filteredSpeedButton!=null){
            iconState(filteredSpeedButton,speedBatch!=null||preparingSpeedBatch?UiIconButton.Kind.STOP:UiIconButton.Kind.DOWNLOAD,speedBatch!=null?AppLanguage.t(R.string.ui_098)+speedBatch.done()+" / "+speedBatch.total():preparingSpeedBatch?AppLanguage.t(R.string.ui_099):AppLanguage.t(R.string.ui_032),speedBatch!=null||preparingSpeedBatch);
            filteredSpeedButton.setEnabled(speedBatch!=null||preparingSpeedBatch||(!busy&&!healthy.isEmpty()));
            filteredSpeedButton.setAlpha(filteredSpeedButton.isEnabled()?1f:.45f);
        }
        if(!collecting){collectButton.setContentDescription(AppLanguage.t(R.string.ui_100)+catalogCount+AppLanguage.t(R.string.ui_101));collectButton.setTooltipText(collectButton.getContentDescription());}
        refreshDesignOne();
    }
    private void stopScan() {
        if(stopping)return;
        if(ProxyVpnService.autoScanning()){ProxyVpnService.cancelAutoScanFromUi();status.setText(AppLanguage.t(R.string.ui_102));return;}
        scanGeneration++;scanning=false;stopping=true;preparedScanner.requestStop();updateActions();
        ProxyStore.IO.execute(()->{
            String message;ScanCheckpoint paused=null;
            try{paused=preparedScanner.pause();if(paused!=null)healthy.apply(paused);ProxyStore.save(this,healthy);
                message=paused==null?AppLanguage.t(R.string.ui_103):AppLanguage.t(R.string.ui_104)+paused.done()+AppLanguage.t(R.string.ui_017)+paused.total()+AppLanguage.t(R.string.ui_105);}
            catch(Exception ex){message=AppLanguage.t(R.string.ui_106)+ex.getMessage();}
            final ScanCheckpoint saved=paused;final String note=message;
            ui.post(()->{if(isDestroyed())return;stopping=false;if(saved!=null)checkpoint=saved;
                saveTestResults();saveReport();status.setText(AppLanguage.display(note));render();});
        });
    }
    private void continueOrStartScan(){
        if(scanning||ProxyVpnService.autoScanning()||collecting||preparing||loading||stopping||!localDataLoaded)return;
        if(resumableScan())runPrepared(checkpoint);else chooseScanMode(false);
    }
    private void chooseScanMode(boolean savedOnly){
        if(protocol==ProxyResults.Protocol.VPN_GATE){chooseGateScan();return;}
        if(scanning||ProxyVpnService.autoScanning()||collecting||preparing||loading||stopping||!localDataLoaded)return;
        if(savedOnly?healthy.isEmpty():catalogCount==0){Toast.makeText(this,AppLanguage.t(savedOnly?R.string.ui_118:R.string.ui_119),Toast.LENGTH_SHORT).show();return;}
        new AlertDialog.Builder(this,R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_scan_choose))
            .setItems(new String[]{AppLanguage.t(R.string.ui_scan_fast),AppLanguage.t(R.string.ui_scan_deep)},(dialog,which)->doScan(savedOnly,which==1))
            .setNegativeButton(AppLanguage.t(R.string.ui_007),null).show();
    }
    private void stopCollection() {
        collectGeneration++; collecting = false; scanner.stop();
        updateActions(); status.setText(AppLanguage.t(R.string.ui_110));
    }
    private void collectList() {
        if(gateDirectory!=null&&!gateDirectory.busy())gateDirectory.refresh();
        if(protocol==ProxyResults.Protocol.VPN_GATE)return;
        if (scanning || ProxyVpnService.autoScanning()) { Toast.makeText(this, AppLanguage.t(R.string.ui_111), Toast.LENGTH_SHORT).show(); return; }
        collecting = true; final int token = ++collectGeneration; scanProgress.setIndeterminate(true); updateActions();
        listStatus.setText(AppLanguage.t(R.string.ui_112));
        List<ProxyEndpoint> previous = new ArrayList<>(manual); previous.addAll(healthy);
        ProxyStore.IO.execute(()->{if(!collecting||token!=collectGeneration)return;scanner.collect(previous, loadBootstrap(), configuredSources(), new ProxyScanner.CollectionListener() {
            public void onProgress(String message) { ui.post(() -> { if (collecting && token == collectGeneration) listStatus.setText(AppLanguage.display(message)); }); }
            public void onFailed(String message) { ui.post(() -> { if (token == collectGeneration) { collecting = false; updateActions(); status.setText(AppLanguage.display(message)); } }); }
            public void onFinished(List<ProxyEndpoint> received, String report) {
                if(!received.isEmpty())getSharedPreferences("persiansocks",0).edit().putBoolean("initial-discovery-030",true).apply();
                try{scanFiles.mergeCatalog(received);}catch(Exception ex){onFailed(AppLanguage.t(R.string.ui_113)+ex.getMessage());return;}
                final int count;try{count=scanFiles.catalogCount();}catch(Exception ex){onFailed(AppLanguage.t(R.string.ui_114));return;}
                ui.post(()->{if(!collecting||token!=collectGeneration||isDestroyed())return;
                    collecting=false;catalogCount=count;
                    if(checkpoint==null||checkpoint.complete()){lastScanDone=lastScanTotal=0;saveScanCounts();}
                    getSharedPreferences("persiansocks",MODE_PRIVATE).edit().putString("collection-report",report).apply();
                    updateActions();status.setText(AppLanguage.display(count+AppLanguage.t(R.string.ui_115)));});
            }
        });});
    }
    private void disconnect() {
        Intent i = new Intent(this, ProxyVpnService.class).setAction(ProxyVpnService.DISCONNECT);
        try { vpnRequestPending=true;connectionError="";startService(i); }
        catch (Exception e) { connectionError=AppLanguage.t(R.string.ui_116)+e.getMessage();vpnRequestPending=false; }
        updateActions();
    }

    private void doScan(boolean savedOnly,boolean deep) {
        if(scanning||collecting||preparing||stopping||loading)return;
        slowScan=deep;getSharedPreferences("persiansocks",MODE_PRIVATE).edit().putBoolean("slow-scan",deep).apply();
        preparing=true;updateActions();status.setText(AppLanguage.t(R.string.ui_117));
        final ProxyScanner.Options options=savedOnly?new ProxyScanner.Options(maxTests,deep,true,7):new ProxyScanner.Options(maxTests,deep,scanAll,scanTypes);
        ProxyStore.IO.execute(()->{
            ScanCheckpoint work=null;String error="";
            try{List<ProxyEndpoint> input=savedOnly?healthy.snapshot():SourceDocuments.iranFirst(scanFiles.catalog());
                input=SourceDocuments.country(input,getSharedPreferences("persiansocks",0).getString("scan-country",""));
                if(input.isEmpty())error=savedOnly?AppLanguage.t(R.string.ui_118):AppLanguage.t(R.string.ui_119);
                else work=new ScanCheckpoint(input,options,savedOnly?"HEALTHY":"CATALOG");}
            catch(Exception ex){error=ex.getMessage();}
            final ScanCheckpoint ready=work;final String note=error;
            ui.post(()->{if(isDestroyed())return;preparing=false;if(ready==null){status.setText(AppLanguage.display(note));updateActions();}else runPrepared(ready);});
        });
    }
    private void runPrepared(ScanCheckpoint work) {
        if(work==null||work.total()==0){status.setText(AppLanguage.t(R.string.ui_123));return;}
        if(scanning||collecting||preparing||stopping||loading||ProxyVpnService.autoScanning())return;
        if(!work.compatible()){status.setText(AppLanguage.t(R.string.ui_124));return;}
        PhysicalTestNetwork network;
        try{network=PhysicalTestNetwork.capture(this);}catch(Exception ex){status.setText(AppLanguage.display(ex.getMessage()));return;}
        checkpoint=work;scanFiles.activate(work);scanning=true;final int token=++scanGeneration;scanProgress.setIndeterminate(false);
        status.setText(AppLanguage.t(work.slow?R.string.ui_scan_deep:R.string.ui_scan_fast)+" • "+AppLanguage.t(R.string.ui_092)+work.done()+AppLanguage.t(R.string.ui_017)+work.total());render();
        final String latencyFilter=filter;
        scanPerformance="";
        PreparedScanner.Listener listener=new PreparedScanner.Listener(){
            public void onPerformance(int capacity,double rate){ui.post(()->{if(token==scanGeneration)scanPerformance=AppLanguage.t(R.string.ui_scan_capacity)+": "+capacity+" • "+String.format(java.util.Locale.US,"%.1f",rate)+" "+AppLanguage.t(R.string.ui_scan_rate);});}
            public void onResult(int index,Socks5Probe.Result result){
                if(!scanning||token!=scanGeneration)return;
                QualityHistory.probe(network,work.candidate(index),result);SourceQuality.probe(network,work,work.candidate(index),result);
                if(result.endpoint!=null)healthy.accept(result.endpoint);
                else if(ProbeFailurePolicy.invalidates(result.failure) && healthy.find(work.candidate(index).key())!=null)
                    healthy.reject(work.candidate(index).key(),work.finishedAt(index));
            }
            public void onProgress(int done,int total,int good){ui.post(()->{
                if(!scanning||token!=scanGeneration||isDestroyed()||!foregroundVisible)return;
                status.setText(AppLanguage.t(work.slow?R.string.ui_scan_deep:R.string.ui_scan_fast)+" • "+AppLanguage.t(R.string.ui_125)+done+AppLanguage.t(R.string.ui_017)+total+AppLanguage.t(R.string.ui_126)+good+"\n"+scanPerformance);
                scanProgress.setProgress(total==0?0:(int)(100L*done/total));
                scanReport=work.mode+"\n"+done+AppLanguage.t(R.string.ui_017)+total+AppLanguage.t(R.string.ui_127)+good+"\n"+scanPerformance;scheduleRender();});}
            public void onFinished(ScanCheckpoint state,String message){
                if(token!=scanGeneration)return;healthy.apply(state);ProxyStore.save(MainActivity.this,healthy);
                ProxyStore.IO.execute(()->{boolean committed=false;try{ProxyStore.flush(MainActivity.this);if(state.complete())scanFiles.discardComplete();committed=true;}catch(Exception ex){android.util.Log.e("PersianSocks","Final cache commit failed",ex);}
                    final boolean saved=committed;
                    ui.post(()->{if(!scanning||token!=scanGeneration||isDestroyed())return;scanning=false;
                        lastScanDone=state.done();lastScanTotal=state.total();saveScanCounts();
                        checkpoint=state.complete()&&saved?null:state;saveTestResults();saveReport();status.setText(AppLanguage.display(saved?message:AppLanguage.t(R.string.ui_128)));render();});});
            }
        };
        try {
            if(work.mode.equals("LATENCY"))preparedScanner.start(work,state->{if(!state.failedBatchKeys().isEmpty())ProxyStore.flush(MainActivity.this);scanFiles.save(state);final int count=scanFiles.catalogCount();ui.post(()->{if(token==scanGeneration&&!isDestroyed()){catalogCount=count;updateActions();}});},(p,slow,cancel,sockets)->{
                ProxyEndpoint current=healthy.find(p.key());
                if(current==null)return ProbeFailurePolicy.observed(ScanProbePolicy.profile().inspect(p,slow,cancel,sockets,network,network),network.valid());
                Socks5Probe.Result measured=ProbeFailurePolicy.observed(Socks5Probe.inspectTarget(current,SingleProxyTest.latencyTarget(current,latencyFilter),slow,cancel,sockets,network,network),network.valid());
                return measured.endpoint==null?measured:new Socks5Probe.Result(current.withLatency(measured.endpoint.latencyMs,SingleProxyTest.latencyTarget(current,latencyFilter).url()),Socks5Probe.Failure.NONE,true,true);
            },listener);
            else preparedScanner.start(work,state->{if(!state.failedBatchKeys().isEmpty())ProxyStore.flush(MainActivity.this);scanFiles.save(state);final int count=scanFiles.catalogCount();ui.post(()->{if(token==scanGeneration&&!isDestroyed()){catalogCount=count;updateActions();}});},network,network,listener);
        }catch(Exception ex){scanning=false;status.setText(AppLanguage.t(R.string.ui_129)+ex.getMessage());render();}
    }
    private void initialFetch(){
        if(gateDirectory!=null&&gateDirectory.store.snapshot().isEmpty()&&!gateDirectory.busy())gateDirectory.refresh();
        if(isDestroyed()||!foregroundVisible||initialFetchAttempted||!localDataLoaded||collecting||scanning||catalogCount>0||getSharedPreferences("persiansocks",0).getBoolean("initial-discovery-030",false))return;
        try{PhysicalTestNetwork.capture(this);}catch(Exception offline){ui.postDelayed(this::initialFetch,5000);return;}
        initialFetchAttempted=true;collectList();
    }
    private void setKill(boolean enabled){
        getSharedPreferences("persiansocks",0).edit().putBoolean("kill-switch-off",!enabled).apply();
        if(quickKill!=null&&quickKill.isChecked()!=enabled)quickKill.setChecked(enabled);
        if(sidebarKill!=null&&sidebarKill.isChecked()!=enabled)sidebarKill.setChecked(enabled);
        if(!enabled&&ProxyVpnService.guardActive())try{startService(new Intent(this,ProxyVpnService.class).setAction(ProxyVpnService.RELEASE_GUARD));}catch(Exception ignored){}
        if(!enabled&&quickKill!=null)quickKill.setText(AppLanguage.t(R.string.ui_030_kill));
        updateActions();
    }
    private void guardHelp(){
        new AlertDialog.Builder(this,R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_030_kill)).setMessage(AppLanguage.t(R.string.ui_030_guard_text)).setPositiveButton(AppLanguage.t(R.string.ui_007),null).setNeutralButton(AppLanguage.t(R.string.ui_030_vpn_settings),(d,w)->startActivity(new Intent(android.provider.Settings.ACTION_VPN_SETTINGS))).show();
    }
    private void applyRoutes(){
        if(ProxyVpnService.phase()!=ProxyVpnService.DISCONNECTED||ProxyVpnService.guardActive())try{startForegroundService(new Intent(this,ProxyVpnService.class).setAction(ProxyVpnService.RECONFIGURE));}catch(Exception error){connectionError=error.getMessage();}
        updateActions();
    }
    private void setChatgptOnly(boolean enabled){
        if(enabled&&!SplitTunnelSettings.installed(getPackageManager(),"com.openai.chatgpt")){
            connectionError=AppLanguage.t(R.string.ui_030_install_chatgpt);
            getSharedPreferences("persiansocks",0).edit().putString("connection-error",connectionError).apply();
            updateActions();connectionErrorDialog();return;
        }
        AndroidRoutes.chatgpt(this,enabled);applyRoutes();
    }
    private void connectionErrorDialog(){
        String report=connectionError+"\n\n"+getSharedPreferences("persiansocks",0).getString("vpn-report","");
        TextView detail=text(AppLanguage.display(report),13,Color.WHITE,false);detail.setTextIsSelectable(true);detail.setPadding(dp(20),dp(16),dp(20),dp(16));
        ScrollView scroll=new ScrollView(this);scroll.addView(detail);
        new AlertDialog.Builder(this,R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_006)).setView(scroll)
            .setPositiveButton(AppLanguage.t(R.string.ui_007),null)
            .setNeutralButton(AppLanguage.t(R.string.ui_031_copy_error),(d,w)->{android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(android.content.ClipData.newPlainText("VPN error",report));})
            .setNegativeButton(AppLanguage.t(R.string.ui_031_clear_error),(d,w)->{connectionError="";getSharedPreferences("persiansocks",0).edit().remove("connection-error").remove("connection-error-endpoint").apply();updateActions();}).show();
    }
    private void routesDialog(){
        LinearLayout box=dialogContent();box.addView(text(AppLanguage.t(R.string.ui_030_routes_help),12,SECONDARY,false));
        CheckBox[] presets=new CheckBox[3];String[] ids={"telegram","youtube","openai"};String[] names={"Telegram","YouTube","OpenAI / ChatGPT"};
        for(int i=0;i<3;i++){presets[i]=sidebarCheck(names[i],false);box.addView(presets[i]);}
        EditText input=new EditText(this);input.setText(getSharedPreferences("persiansocks",0).getString("route-domains",""));input.setTextColor(Color.WHITE);input.setMinLines(3);box.addView(input);
        AlertDialog dialog=new AlertDialog.Builder(this,R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_030_routes)).setView(box).setPositiveButton(AppLanguage.t(R.string.ui_065),null).setNegativeButton(AppLanguage.t(R.string.ui_007),null).setNeutralButton(AppLanguage.t(R.string.ui_030_apps),(d,w)->{getSharedPreferences("persiansocks",0).edit().putString("routing-mode","apps").apply();applyRoutes();}).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{try{List<String> domains=new ArrayList<>();domains.add(input.getText().toString());for(int i=0;i<3;i++)if(presets[i].isChecked())domains.addAll(DomainRoutes.preset(ids[i]));DomainRoutes clean=new DomainRoutes(domains);if(!clean.restricted)throw new IllegalArgumentException(AppLanguage.t(R.string.ui_030_choose_domain));getSharedPreferences("persiansocks",0).edit().putString("routing-mode","domains").putString("route-domains",String.join("\n",clean.domains)).apply();applyRoutes();dialog.dismiss();}catch(Exception e){input.setError(e.getMessage());}}));dialog.show();
    }
    private void countryDialog(){
        java.util.TreeSet<String> keys=new java.util.TreeSet<>();keys.add("IR");for(ProxyEndpoint p:allDirectoryEndpoints()){String key=p.countryCode.isEmpty()?CountryLookup.lookup(p.host):p.countryCode;if(!key.isEmpty())keys.add(key);}
        List<String> countries=new ArrayList<>();countries.add("");countries.addAll(keys);String[] labels=new String[countries.size()];for(int i=0;i<labels.length;i++)labels[i]=i==0?AppLanguage.t(R.string.ui_030_all_countries):CountryLookup.display(countries.get(i),AppLanguage.locale());
        new AlertDialog.Builder(this,R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_030_country)).setItems(labels,(d,i)->{getSharedPreferences("persiansocks",0).edit().putString("display-country",countries.get(i)).apply();renderedRevision=-1;render();new AlertDialog.Builder(this,R.style.AppDialogTheme).setMessage(AppLanguage.t(R.string.ui_030_scan_country)).setPositiveButton(AppLanguage.t(R.string.ui_065),(a,b)->getSharedPreferences("persiansocks",0).edit().putString("scan-country",countries.get(i)).apply()).setNegativeButton(AppLanguage.t(R.string.ui_030_all_countries),(a,b)->getSharedPreferences("persiansocks",0).edit().putString("scan-country","").apply()).show();}).show();
    }
    private void reloadSaved() {
        if(loading||scanning||preparing||stopping)return;loading=true;updateActions();
        final boolean initial=!localDataLoaded;
        ProxyStore.IO.execute(()->{
            Map<String,ProxyEndpoint> savedFavorites=initial?FavoriteProxies.load(this):null;
            Map<String,ProxyTestResult> savedTests=initial?readTestResults():null;
            ScanCheckpoint saved=null;int count=0;String error="";
            try{ProxyStore.load(this);if(initial)for(Map.Entry<String,ProxyTestResult> row:savedTests.entrySet()){ProxyEndpoint old=healthy.find(row.getKey());if(old!=null&&row.getValue().failedAfter(old))healthy.reject(old.key(),row.getValue().probeAt);}count=scanFiles.catalogCount();saved=scanFiles.checkpoint();
                if(saved!=null&&saved.compatible())healthy.apply(saved);
                if(saved!=null&&saved.complete()){ProxyStore.save(this,healthy);scanFiles.discardComplete();saved=null;}}
            catch(Exception ex){error=String.valueOf(ex.getMessage());}
            final ScanCheckpoint restored=saved;final int storedCount=count;final String failure=error;
            ui.post(()->{if(isDestroyed())return;if(initial){favorites.putAll(savedFavorites);testResults.putAll(savedTests);favoritesRevision++;measurementRevision++;localDataLoaded=true;}loading=false;checkpoint=restored;catalogCount=storedCount;
                scanReport=getSharedPreferences("persiansocks",MODE_PRIVATE).getString("scan-report",scanReport);
                if(!failure.isEmpty())status.setText(AppLanguage.t(R.string.ui_130)+failure);
                else status.setText(AppLanguage.display(restored!=null&&!restored.complete()?AppLanguage.t(R.string.ui_131)+restored.done()+AppLanguage.t(R.string.ui_017)+restored.total():storedCount+AppLanguage.t(R.string.ui_019)));
                ProxyEndpoint active=healthy.find(ProxyVpnService.activeEndpoint());if(active!=null){selected=active;userSelected=true;}render();ui.postDelayed(MainActivity.this::initialFetch,250);});
            if(!CountryLookup.initialized())try{CountryLookup.initialize(getAssets().open("ip-country.bin"));}catch(Exception ignored){}
        });
    }
    private List<ProxyEndpoint> allDirectoryEndpoints(){List<ProxyEndpoint> nodes=healthy.snapshot();if(gateDirectory!=null)for(VpnGateProfile p:gateDirectory.store.snapshot())if(p.port>0)nodes.add(p.endpoint());return nodes;}
    private boolean hasSelected(){return selected!=null&&(healthy.find(selected.key())!=null||favorites.containsKey(selected.key())||(selected.type==ProxyEndpoint.Type.OPENVPN&&gateDirectory!=null&&gateDirectory.store.get(selected.key())!=null));}
    private String criteria(){return getSharedPreferences("persiansocks",0).getBoolean("gate-verified-only",true)+"|"+(gateDirectory==null?0:gateDirectory.store.revision())+"|"+getSharedPreferences("persiansocks",0).getString("display-country","")+"|"+filter+"|"+protocol+"|"+sort+"|"+pingLimit+"|"+smoothResults+"|"+measurementRevision+"|"+QualityHistory.revision()+"|"+QualityHistory.scope()+"|"+showFavorites+"|"+favoritesRevision+"|"+(ProxyVpnService.phase()==ProxyVpnService.CONNECTED?ProxyVpnService.activeEndpoint():"");}
    private List<ProxyEndpoint> favoriteSnapshots(){
        List<ProxyEndpoint> items=new ArrayList<>();
        for(ProxyEndpoint saved:favorites.values()){
            ProxyEndpoint recent=healthy.find(saved.key());
            items.add(recent==null?saved:recent);
        }
        return items;
    }
    private void addFavorite(ProxyEndpoint proxy){
        if(proxy==null)return;
        if(favorites.containsKey(proxy.key())){
            Toast.makeText(this,AppLanguage.t(R.string.ui_132),Toast.LENGTH_SHORT).show();return;
        }
        favorites.put(proxy.key(),proxy);
        if(!FavoriteProxies.save(this,favorites.values())){
            favorites.remove(proxy.key());
            Toast.makeText(this,AppLanguage.t(R.string.ui_133),Toast.LENGTH_LONG).show();return;
        }
        favoritesRevision++;render();Toast.makeText(this,AppLanguage.t(R.string.ui_134),Toast.LENGTH_SHORT).show();
    }
    private void toggleFavorite(ProxyEndpoint proxy){if(!favorites.containsKey(proxy.key())){addFavorite(proxy);return;}ProxyEndpoint old=favorites.remove(proxy.key());if(!FavoriteProxies.save(this,favorites.values())){favorites.put(old.key(),old);Toast.makeText(this,AppLanguage.t(R.string.ui_138),Toast.LENGTH_LONG).show();return;}favoritesRevision++;render();}
    private void confirmRemoveFavorite(ProxyEndpoint proxy){
        if(proxy==null||!favorites.containsKey(proxy.key()))return;
        new AlertDialog.Builder(this,R.style.AppDialogTheme)
            .setTitle(AppLanguage.t(R.string.ui_135))
            .setMessage(AppLanguage.t(R.string.ui_136))
            .setPositiveButton(AppLanguage.t(R.string.ui_137), (dialog,which)->{
                ProxyEndpoint saved=favorites.remove(proxy.key());
                if(!FavoriteProxies.save(this,favorites.values())){
                    favorites.put(saved.key(),saved);
                    Toast.makeText(this,AppLanguage.t(R.string.ui_138),Toast.LENGTH_LONG).show();return;
                }
                favoritesRevision++;render();
            }).setNegativeButton(AppLanguage.t(R.string.ui_067),null).show();
    }
    private Map<String,Double> speedSnapshot(){
        Map<String,Double> speeds=new LinkedHashMap<>();
        for(Map.Entry<String,ProxyTestResult> entry:testResults.entrySet())
            speeds.put(entry.getKey(),entry.getValue().speedError.isEmpty()&&entry.getValue().speedTarget.equals(ScanInputs.speedUrl(this))?entry.getValue().downloadMBps:-1.0);
        return speeds;
    }
    private void render() {
        if(results==null||isDestroyed()||!foregroundVisible)return;
        refreshVisibleCards();
        testResults.keySet().removeIf(key->healthy.rejected(key)&&!favorites.containsKey(key));
        manual.removeIf(p->healthy.rejected(p.key()));
        if(selected!=null&&healthy.rejected(selected.key())&&!favorites.containsKey(selected.key())){selected=null;userSelected=false;}
        if(selected!=null){ProxyEndpoint latest=healthy.find(selected.key());if(latest!=null)selected=latest;}
        String kindLabel = protocol == ProxyResults.Protocol.HTTP && DistributionPolicy.encryptedOnly() ? "HTTPS / TLS" : AppLanguage.display(protocol.label);
        boolean filtered = !filter.equals("ALL") || protocol != ProxyResults.Protocol.ALL || pingLimit != ProxyResults.PingLimit.NONE;
        String summary=AppLanguage.display(ProxyResults.label(filter)+" • "+kindLabel+
            (pingLimit==ProxyResults.PingLimit.NONE?"":" • "+AppLanguage.display(pingLimit.label))+" • "+sortLabel());
        filterSummary.setText(summary);filterSummary.setVisibility(showFavorites?View.GONE:View.VISIBLE);
        filterButton.active(filtered||smoothResults||sort!=ProxyResults.Sort.FASTEST);
        filterButton.setContentDescription(AppLanguage.t(R.string.ui_home_filters)+": "+summary);filterButton.setTooltipText(filterButton.getContentDescription());
        ((UiIconButton)favoritesButton).caption(AppLanguage.t(showFavorites?R.string.ui_design_all:R.string.ui_design_saved));
        iconState(favoritesButton,showFavorites?UiIconButton.Kind.BACK:UiIconButton.Kind.STAR,
            showFavorites?AppLanguage.t(R.string.ui_150):AppLanguage.t(R.string.ui_151)+" ("+favorites.size()+")",showFavorites);
        savedPingButton.setVisibility(showFavorites?View.VISIBLE:View.GONE);savedSpeedButton.setVisibility(showFavorites?View.VISIBLE:View.GONE);
        iconState(savedPingButton,savedPingQueue!=null?UiIconButton.Kind.STOP:UiIconButton.Kind.SYNC,
            savedPingQueue!=null?AppLanguage.t(R.string.ui_v25_010)+" "+savedPingPosition+"/"+savedPingQueue.size():AppLanguage.t(R.string.ui_v25_008),savedPingQueue!=null);
        iconState(savedSpeedButton,speedBatch!=null?UiIconButton.Kind.STOP:UiIconButton.Kind.DOWNLOAD,
            speedBatch!=null?AppLanguage.t(R.string.ui_v25_010)+" "+speedBatch.done()+"/"+speedBatch.total():AppLanguage.t(R.string.ui_v25_009),speedBatch!=null);
        savedPingButton.setEnabled(savedPingQueue!=null||(singleTest==null&&speedTest==null&&speedBatch==null&&!preparingSpeedBatch));
        savedSpeedButton.setEnabled(speedBatch!=null||(singleTest==null&&speedTest==null&&savedPingQueue==null&&!preparingSpeedBatch));
        filterButton.setVisibility(showFavorites?View.GONE:View.VISIBLE);
        filteredSpeedButton.setVisibility(showFavorites?View.GONE:View.VISIBLE);
        filteredPingButton.setVisibility(showFavorites?View.GONE:View.VISIBLE);
        boolean pingBatch=savedPingQueue!=null&&filteredPingMode;
        iconState(filteredPingButton,pingBatch?UiIconButton.Kind.STOP:UiIconButton.Kind.SYNC,
            pingBatch?AppLanguage.t(R.string.ui_v25_010)+" "+savedPingPosition+"/"+savedPingQueue.size():AppLanguage.t(R.string.ui_filtered_ping),pingBatch);
        filteredPingButton.setEnabled(pingBatch||(singleTest==null&&speedTest==null&&speedBatch==null&&!preparingSpeedBatch&&savedPingQueue==null&&!healthy.isEmpty()));
        updateActions();
        // Startup data must finish before an old in-memory snapshot can be published.
        if(!localDataLoaded){emptyState.setVisibility(View.GONE);if(!startupPreview.isEmpty()&&pageGeneration==0)publishPages(++pageGeneration,new ArrayList<>(startupPreview));return;}
        if(renderedRevision==healthy.revision()&&renderedCriteria.equals(criteria())){refreshVisibleCards();return;}
        if(ranking)return;
        ranking=true;final String requested=criteria();final String service=filter;
        final ProxyResults.Sort order=sort;final ProxyResults.Protocol kind=protocol;final ProxyResults.PingLimit limit=pingLimit;
        final long revision=healthy.revision();
        final boolean favoritesMode=showFavorites;final boolean gateCandidates=!getSharedPreferences("persiansocks",0).getBoolean("gate-verified-only",true);
        final List<ProxyEndpoint> snapshot=favoritesMode?favoriteSnapshots():healthy.snapshot();
        final List<VpnGateProfile> gateProfiles=gateDirectory==null?java.util.Collections.emptyList():gateDirectory.store.snapshot();
        final Map<String,Double> speeds=speedSnapshot();final boolean smooth=smoothResults;
        rankWorker.execute(()->{
            try {
            List<ProxyEndpoint> visible=favoritesMode?snapshot:ProxyResults.visible(snapshot,service,order,kind,limit,speeds,smooth);
            if(gateCandidates&&!favoritesMode&&(kind==ProxyResults.Protocol.ALL||kind==ProxyResults.Protocol.VPN_GATE)&&service.equals("ALL")){
                java.util.Set<String> keys=new java.util.HashSet<>();for(ProxyEndpoint p:visible)keys.add(p.key());
                gateProfiles.sort(java.util.Comparator.comparingLong((VpnGateProfile p)->p.portLatency<0?Long.MAX_VALUE:p.portLatency).thenComparing(java.util.Comparator.comparingLong((VpnGateProfile p)->p.score).reversed()));
                for(VpnGateProfile p:gateProfiles)if(p.port>0&&!keys.contains(p.key())&&(limit==ProxyResults.PingLimit.NONE||limit.accepts(p.endpoint())))visible.add(p.endpoint());
            }
            ProxyEndpoint connected=ProxyVpnService.phase()==ProxyVpnService.CONNECTED?healthy.find(ProxyVpnService.activeEndpoint()):null;
            if(connected==null&&ProxyVpnService.phase()==ProxyVpnService.CONNECTED&&selected!=null&&selected.key().equals(ProxyVpnService.activeEndpoint()))connected=selected;
            String selectedCountry=getSharedPreferences("persiansocks",0).getString("display-country","");if(!selectedCountry.isEmpty())visible.removeIf(p->!selectedCountry.equals(p.countryCode.isEmpty()?CountryLookup.lookup(p.host):p.countryCode));
            List<ProxyEndpoint> pinned=ProxyResults.pinActive(visible,connected,!favoritesMode);
            final long generation=System.currentTimeMillis()*1000+pageSequence.incrementAndGet()%1000;final int count=pinned.size();final ProxyEndpoint best=visible.isEmpty()?null:visible.get(0);
            // Ranking is a view of references, not a durable write for each UI refresh.
            ui.post(()->{
                if(isDestroyed())return;ranking=false;
                if(!requested.equals(criteria())||revision!=healthy.revision()){render();return;}
                resultCount=count;QuickStartCache.save(this,pinned);publishPages(generation,pinned);renderedRevision=revision;renderedCriteria=requested;
                if(!userSelected&&!favoritesMode&&ProxyVpnService.phase()==ProxyVpnService.DISCONNECTED&&!vpnRequestPending)
                    selected=best;
                counter.setText(AppLanguage.display(resultCount+AppLanguage.t(R.string.ui_152)));
                emptyState.removeAllViews();emptyState.setVisibility(resultCount==0?View.VISIBLE:View.GONE);
                if(resultCount==0){
                    if(favoritesMode){
                        emptyState.addView(text(AppLanguage.t(R.string.ui_153),16,Color.WHITE,true));
                        emptyState.addView(text(AppLanguage.t(R.string.ui_154),12,SECONDARY,false),margin(dp(8),0));
                    }else{
                        emptyState.addView(text(!healthy.isEmpty()?AppLanguage.t(R.string.ui_155):scanning?AppLanguage.t(R.string.ui_157):AppLanguage.t(R.string.ui_158),16,Color.WHITE,true));
                        emptyState.addView(text(!healthy.isEmpty()?smoothResults?AppLanguage.t(R.string.ui_159):AppLanguage.t(R.string.ui_160):AppLanguage.t(R.string.ui_161),12,SECONDARY,false),margin(dp(8),0));
                    }
                }
                refreshVisibleCards();updateActions();
                if(revision!=healthy.revision())scheduleRender();
            });
            }catch(RuntimeException ex){android.util.Log.e("PersianSocks","Result paging failed",ex);ui.post(()->{ranking=false;if(!isDestroyed())status.setText(AppLanguage.t(R.string.ui_128));});}
        });
    }
    private void publishPages(long generation,List<ProxyEndpoint> rows){
        if(pageStream!=null)pageStream.removeObservers(this);pageGeneration=generation;
        Pager<Integer,ProxyEndpoint> pager=new Pager<>(new PagingConfig(80,40,false,160,400),()->new ProxyPages(rows));
        pageStream=PagingLiveData.getLiveData(pager);pageStream.observe(this,data->{if(pageGeneration==generation)resultAdapter.submitData(getLifecycle(),data);});
    }
    private final class HeaderAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final View header;HeaderAdapter(View h){header=h;}
        public int getItemCount(){return 1;}
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup p,int type){if(header.getParent()!=null)((ViewGroup)header.getParent()).removeView(header);header.setLayoutParams(new RecyclerView.LayoutParams(-1,-2));return new RecyclerView.ViewHolder(header){};}
        public void onBindViewHolder(RecyclerView.ViewHolder h,int p){}
    }
    private void refreshVisibleCards(){
        if(isDestroyed())return;
        if(results==null||results.isComputingLayout()){if(results!=null)results.post(this::refreshVisibleCards);return;}
        for(int i=0;i<results.getChildCount();i++){
            RecyclerView.ViewHolder holder=results.getChildViewHolder(results.getChildAt(i));
            if(holder instanceof ResultHolder){ResultHolder result=(ResultHolder)holder;if(result.card.endpoint!=null)bindCard(result,result.card.endpoint);}
        }
    }
    private void bindCard(ResultHolder holder,ProxyEndpoint p){
        ProxyEndpoint live=healthy.find(p.key());boolean hidden=!localDataLoaded||!showFavorites&&live==null&&healthy.rejected(p.key());
        int visibility=hidden?View.GONE:View.VISIBLE,height=hidden?0:ViewGroup.LayoutParams.WRAP_CONTENT;
        if(holder.card.wrapper.getVisibility()!=visibility)holder.card.wrapper.setVisibility(visibility);
        if(holder.card.wrapper.getLayoutParams().height!=height){holder.card.wrapper.getLayoutParams().height=height;holder.card.wrapper.requestLayout();}
        if(hidden)holder.card.endpoint=live==null?p:live;else holder.card.bind(live==null?p:live);
    }
    private final class ResultHolder extends RecyclerView.ViewHolder {
        final CardHolder card;ResultHolder(CardHolder c){super(c.wrapper);card=c;c.wrapper.setLayoutParams(new RecyclerView.LayoutParams(-1,-2));}
    }
    private final class ResultAdapter extends PagingDataAdapter<ProxyEndpoint,ResultHolder> {
        ResultAdapter(){super(new DiffUtil.ItemCallback<ProxyEndpoint>(){
            public boolean areItemsTheSame(ProxyEndpoint a,ProxyEndpoint b){return a.key().equals(b.key());}
            // Endpoint records are immutable; a replaced reference conservatively requires rebinding.
            @android.annotation.SuppressLint("DiffUtilEquals")
            public boolean areContentsTheSame(ProxyEndpoint a,ProxyEndpoint b){return a==b;}
        });}
        public ResultHolder onCreateViewHolder(ViewGroup parent,int type){return new ResultHolder(new CardHolder());}
        public void onBindViewHolder(ResultHolder holder,int position){ProxyEndpoint p=getItem(position);if(p!=null)bindCard(holder,p);}
        @Override public void onViewAttachedToWindow(ResultHolder holder){super.onViewAttachedToWindow(holder);if(holder.card.endpoint!=null)bindCard(holder,holder.card.endpoint);}
    }
    private final class ServiceIcon extends LinearLayout {final ImageView image=new ImageView(MainActivity.this);final TextView mark=text("",11,SECONDARY,true);ServiceIcon(){super(MainActivity.this);setGravity(Gravity.CENTER);setLayoutDirection(View.LAYOUT_DIRECTION_LTR);image.setScaleType(ImageView.ScaleType.FIT_CENTER);addView(image,new LinearLayout.LayoutParams(dp(22),dp(22)));addView(mark,new LinearLayout.LayoutParams(dp(12),-2));}}
    private final class CardHolder {
        final LinearLayout wrapper=new LinearLayout(MainActivity.this),row=new LinearLayout(MainActivity.this);
        final ServiceFlowLayout services=new ServiceFlowLayout(MainActivity.this);
        final TextView address=text("",15,Color.WHITE,true),badge=chip(AppLanguage.t(R.string.ui_162),PRIMARY,Color.rgb(22,75,68)),detail=text("",12,SECONDARY,false);
        final TextView down=metricValue("",SECONDARY),ping=metricValue("",Color.WHITE);
        final TextView designFlag=text("",23,Color.WHITE,false),designName=text("",14,Color.WHITE,true),designProtocol=chip("",PRIMARY,SURFACE),designRadio=new DesignOneWidgets.Choice(MainActivity.this);
        final DesignOneWidgets.Signal designSignal=new DesignOneWidgets.Signal(MainActivity.this);
        final List<ServiceIcon> serviceViews=new ArrayList<>();
        final View divider=new View(MainActivity.this);
        final FrameLayout swipeFrame=new FrameLayout(MainActivity.this);
        final TextView pingHint=text(AppLanguage.t(R.string.ui_v25_013),15,PRIMARY,true),downloadHint=text(AppLanguage.t(R.string.ui_v25_014),15,Color.rgb(99,172,255),true);
        ProxyEndpoint endpoint;String targetSet="";boolean swiping;
        private java.util.List<Object> boundState;
        UiIconButton favoriteButton;
        CardHolder(){
            wrapper.setOrientation(LinearLayout.VERTICAL);wrapper.setLayoutDirection(AppLanguage.direction());
            wrapper.setPadding(dp(16),dp(5),dp(16),dp(5));
            row.setOrientation(LinearLayout.VERTICAL);row.setPadding(dp(12),dp(12),dp(12),dp(10));
            swipeFrame.setClipChildren(true);wrapper.addView(swipeFrame,new LinearLayout.LayoutParams(-1,-2));
            pingHint.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);pingHint.setPadding(dp(18),0,dp(18),0);pingHint.setBackground(background(Color.rgb(16,56,45),dp(14),PRIMARY));
            downloadHint.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);downloadHint.setPadding(dp(18),0,dp(18),0);downloadHint.setBackground(background(Color.rgb(20,41,70),dp(14),Color.rgb(99,172,255)));
            swipeFrame.addView(pingHint,new FrameLayout.LayoutParams(-1,-1));swipeFrame.addView(downloadHint,new FrameLayout.LayoutParams(-1,-1));
            pingHint.setVisibility(View.GONE);downloadHint.setVisibility(View.GONE);swipeFrame.addView(row,new FrameLayout.LayoutParams(-1,-2));
            LinearLayout top=horizontal();address.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);address.setTextDirection(View.TEXT_DIRECTION_LTR);
            address.setMaxLines(1);address.setEllipsize(android.text.TextUtils.TruncateAt.END);
            address.setAutoSizeTextTypeUniformWithConfiguration(12,16,1,android.util.TypedValue.COMPLEX_UNIT_SP);
            top.addView(address,new LinearLayout.LayoutParams(0,-2,1));top.addView(badge);
            row.addView(top);
            LinearLayout actions=horizontal();actions.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            UiIconButton download=cardIcon(actions,UiIconButton.Kind.DOWNLOAD,AppLanguage.t(R.string.ui_176),()->{if(endpoint!=null)startSpeedTest(endpoint);});
            cardIcon(actions,UiIconButton.Kind.CONNECT,AppLanguage.t(R.string.ui_174),()->{if(endpoint!=null)startProxyProbe(endpoint,false);});
            cardIcon(actions,UiIconButton.Kind.SYNC,AppLanguage.t(R.string.ui_175),()->{if(endpoint!=null)startProxyProbe(endpoint,true);});
            cardIcon(actions,UiIconButton.Kind.INFO,AppLanguage.t(R.string.ui_177),()->{if(endpoint!=null)proxyDetails(endpoint);});
            favoriteButton=cardIcon(actions,UiIconButton.Kind.STAR,AppLanguage.t(R.string.ui_167),()->{if(endpoint!=null)toggleFavorite(endpoint);});
            detail.setMaxLines(2);detail.setEllipsize(android.text.TextUtils.TruncateAt.END);row.addView(detail,margin(dp(4),0));
            row.addView(services,margin(dp(10),0));
            TextView scope=text(AppLanguage.t(R.string.ui_health_card_scope),10,SECONDARY,false);row.addView(scope,margin(dp(4),0));
            row.addView(divider,sized(dp(1),dp(6)));
            LinearLayout metrics=horizontal();metrics.addView(resultColumn(AppLanguage.t(R.string.ui_164),down),new LinearLayout.LayoutParams(0,-2,1.5f));
            LinearLayout.LayoutParams pingCell=new LinearLayout.LayoutParams(0,-2,1);pingCell.setMarginStart(dp(8));metrics.addView(resultColumn(AppLanguage.t(R.string.ui_165),ping),pingCell);row.addView(metrics,margin(dp(8),0));
            row.addView(actions,margin(dp(10),0));
            row.setOnClickListener(v->{if(endpoint!=null){chooseProxy(endpoint);}});
            row.setLongClickable(false);
            // Horizontal gestures only; vertical movement stays with the scrolling ListView.
            row.setOnTouchListener(new View.OnTouchListener(){
                float startX,startY;boolean horizontal;ProxyEndpoint gestureProxy;
                @Override public boolean onTouch(View v,android.view.MotionEvent event){
                    int action=event.getActionMasked();
                    if(action==android.view.MotionEvent.ACTION_DOWN){
                        row.animate().cancel();row.setTranslationX(0);gestureProxy=endpoint;startX=event.getRawX();startY=event.getRawY();horizontal=false;swiping=false;return false;
                    }
                    float dx=event.getRawX()-startX,dy=event.getRawY()-startY;
                    if(action==android.view.MotionEvent.ACTION_MOVE &&
                        Math.abs(dx)>dp(24)&&Math.abs(dx)>Math.abs(dy)*1.3f){
                        horizontal=true;swiping=true;v.getParent().requestDisallowInterceptTouchEvent(true);
                        float distance=Math.max(-dp(132),Math.min(dp(132),dx));row.setTranslationX(distance);
                        pingHint.setVisibility(dx<0?View.VISIBLE:View.GONE);downloadHint.setVisibility(dx>0?View.VISIBLE:View.GONE);return true;
                    }
                    if(action==android.view.MotionEvent.ACTION_UP && horizontal){
                        horizontal=false;swiping=false;
                        if(Math.abs(dx)>dp(64)&&Math.abs(dx)>Math.abs(dy)*1.3f&&endpoint!=null&&gestureProxy!=null&&endpoint.key().equals(gestureProxy.key())){
                            if(dx<0)startProxyProbe(gestureProxy,true);else startSpeedTest(gestureProxy);
                        }
                        row.animate().translationX(0).setDuration(180).withEndAction(()->{pingHint.setVisibility(View.GONE);downloadHint.setVisibility(View.GONE);}).start();v.setPressed(false);return true;
                    }
                    if(action==android.view.MotionEvent.ACTION_CANCEL){horizontal=false;swiping=false;row.animate().translationX(0).setDuration(120).withEndAction(()->{pingHint.setVisibility(View.GONE);downloadHint.setVisibility(View.GONE);}).start();return false;}
                    return horizontal;
                }
            });
        }
        private void designCard(){
            row.removeAllViews();row.setPadding(dp(12),dp(8),dp(12),dp(8));row.setMinimumHeight(dp(78));wrapper.setPadding(dp(16),dp(6),dp(16),dp(6));LinearLayout body=horizontal();body.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            designFlag.setGravity(Gravity.CENTER);designFlag.setBackground(null);body.addView(designFlag,new LinearLayout.LayoutParams(dp(36),dp(30)));
            LinearLayout identity=new LinearLayout(MainActivity.this);identity.setOrientation(LinearLayout.VERTICAL);identity.setPadding(dp(10),0,dp(3),0);designName.setSingleLine(true);designName.setEllipsize(android.text.TextUtils.TruncateAt.END);designName.setGravity(Gravity.LEFT);identity.addView(designName,new LinearLayout.LayoutParams(-1,-2));address.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);address.setTextSize(12);address.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));address.setTextColor(SECONDARY);address.setGravity(Gravity.LEFT);body.addView(identity,new LinearLayout.LayoutParams(0,-2,1));
            LinearLayout info=new LinearLayout(MainActivity.this);info.setOrientation(LinearLayout.VERTICAL);info.setGravity(Gravity.RIGHT);LinearLayout metrics=horizontal();metrics.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);ping.setTextSize(14);ping.setGravity(Gravity.CENTER);metrics.addView(detach(ping),new LinearLayout.LayoutParams(dp(60),dp(30)));metrics.addView(designSignal,new LinearLayout.LayoutParams(dp(12),dp(20)));favoriteButton.setBackground(null);metrics.addView(detach(favoriteButton),new LinearLayout.LayoutParams(dp(34),dp(34)));designRadio.setGravity(Gravity.CENTER);metrics.addView(designRadio,new LinearLayout.LayoutParams(dp(26),dp(34)));info.addView(metrics,new LinearLayout.LayoutParams(dp(132),dp(34)));designProtocol.setPadding(dp(10),dp(3),dp(10),dp(3));designProtocol.setTextSize(10);body.addView(info,new LinearLayout.LayoutParams(dp(132),-2));row.addView(body);LinearLayout lower=horizontal();lower.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);lower.setPadding(dp(46),0,0,0);lower.addView(detach(address),new LinearLayout.LayoutParams(0,-2,1));lower.addView(designProtocol,new LinearLayout.LayoutParams(-2,-2));row.addView(lower,margin(dp(2),0));
            row.setLongClickable(true);row.setOnLongClickListener(v->{if(endpoint==null)return false;android.widget.PopupMenu menu=new android.widget.PopupMenu(MainActivity.this,row);int[] labels={R.string.ui_design_connect,R.string.ui_175,R.string.ui_176,R.string.ui_177,R.string.ui_167};for(int i=0;i<labels.length;i++)menu.getMenu().add(0,i,0,AppLanguage.t(labels[i]));menu.setOnMenuItemClickListener(item->{ProxyEndpoint p=endpoint;if(p==null)return false;switch(item.getItemId()){case 0:chooseProxy(p);connect();break;case 1:startProxyProbe(p,true);break;case 2:startSpeedTest(p);break;case 3:proxyDetails(p);break;case 4:toggleFavorite(p);break;}return true;});menu.show();return true;});
        }
        void bind(ProxyEndpoint p){
            if(designFlag.getParent()==null)designCard();
            ProxyTestResult measuredState=testResults.get(p.key());
            QualityStats qualityState=QualityHistory.stats(p.key());
            boolean testing=testingProxy!=null&&testingProxy.key().equals(p.key());
            java.util.List<Object> state=java.util.Arrays.asList(p.key(),p.latencyMs,p.latencyTarget,p.serviceMask,p.checkedMask,p.extraPassed,p.extraChecked,selected!=null&&selected.key().equals(p.key()),
                ProxyVpnService.phase()==ProxyVpnService.CONNECTED&&p.key().equals(ProxyVpnService.activeEndpoint()),
                favorites.containsKey(p.key()),healthy.rejected(p.key()),ScanCheckpoint.fingerprint(),AppLanguage.code(MainActivity.this),
                p.countryCode.isEmpty()?CountryLookup.lookup(p.host):p.countryCode,QualityHistory.scope(),
                qualityState==null?0:qualityState.samples(),qualityState==null?0L:Math.round(qualityState.score(System.currentTimeMillis())),
                iconRevision,testing&&speedTest!=null,testing&&singleTest!=null,testing?liveDownloadMBps:-1.0,ScanInputs.speedUrl(MainActivity.this),
                measuredState==null?null:measuredState.speedValue(),measuredState==null?null:measuredState.pingValue(p),measuredState==null?null:measuredState.speedTarget);
            if(state.equals(boundState)){endpoint=p;return;}
            boundState=state;
            if(endpoint==null||!endpoint.key().equals(p.key())||!swiping){row.animate().cancel();row.setTranslationX(0);pingHint.setVisibility(View.GONE);downloadHint.setVisibility(View.GONE);swiping=false;}
            endpoint=p;boolean unavailable=healthy.rejected(p.key());boolean active=selected!=null&&selected.key().equals(p.key());
            row.setBackground(background(active?Color.rgb(20,42,43):SURFACE,dp(14),active?Color.rgb(57,145,134):STROKE));
            row.setSelected(active);
            address.setText(p.address());badge.setVisibility(ProxyVpnService.phase()==ProxyVpnService.CONNECTED&&p.key().equals(ProxyVpnService.activeEndpoint())?View.VISIBLE:View.GONE);
            boolean saved=favorites.containsKey(p.key());
            favoriteButton.active(saved);favoriteButton.setContentDescription(saved?AppLanguage.t(R.string.ui_135):AppLanguage.t(R.string.ui_167));favoriteButton.setTooltipText(saved?AppLanguage.t(R.string.ui_135):AppLanguage.t(R.string.ui_167));
            row.setContentDescription(p.address()+AppLanguage.t(R.string.ui_168));
            String country=p.countryCode.isEmpty()?CountryLookup.lookup(p.host):p.countryCode;
            QualityStats quality=QualityHistory.stats(p.key());String history=quality==null?"": " • "+AppLanguage.t(R.string.ui_quality_samples)+" "+quality.samples()+" • "+Math.round(quality.score(System.currentTimeMillis()))+"/100";
            detail.setText(AppLanguage.display(CountryLookup.display(country,AppLanguage.locale())+"  •  "+AppLanguage.isolate(p.type.name()))+history+(unavailable?" • "+AppLanguage.t(R.string.ui_proxy_unavailable):""));detail.setTextColor(unavailable?WARNING:active?PRIMARY:SECONDARY);
            String shape=ScanCheckpoint.fingerprint();
            if(!shape.equals(targetSet)){
                targetSet=shape;services.removeAllViews();serviceViews.clear();
                for(ServiceTargets.Target target:ServiceTargets.current()){
                    ServiceIcon chip=new ServiceIcon();services.addView(chip,new ViewGroup.LayoutParams(dp(44),dp(36)));serviceViews.add(chip);
                }
            }
            int index=0;for(ServiceTargets.Target target:ServiceTargets.current()){
                boolean ok=!unavailable&&p.passed(target);ServiceIcon chip=serviceViews.get(index++);android.graphics.Bitmap icon=siteIcons==null?null:siteIcons.get(target.host);if(icon==null)chip.image.setImageDrawable(SiteIcons.placeholder());else chip.image.setImageBitmap(icon);
                chip.setBackground(background(ok?Color.rgb(19,58,54):Color.rgb(18,28,42),dp(8),ok?Color.rgb(30,83,73):Color.rgb(34,47,63)));
                chip.mark.setText(unavailable?"•":ok?"✓":p.checked(target)?"×":"•");chip.mark.setTextColor(ok?PRIMARY:SECONDARY);chip.setTooltipText(target.host+" • "+AppLanguage.t(R.string.ui_health_card_scope));chip.setContentDescription(target.name+" • "+target.host+(ok?AppLanguage.t(R.string.ui_169):p.checked(target)?AppLanguage.t(R.string.ui_170):AppLanguage.t(R.string.ui_171)));
            }
            ProxyTestResult measured=testResults.get(p.key());if(measured==null)measured=new ProxyTestResult();
            boolean running=testingProxy!=null&&testingProxy.key().equals(p.key());boolean downloadRunning=running&&speedTest!=null,probeRunning=running&&singleTest!=null;
            setMetricValue(down,unavailable?AppLanguage.t(R.string.ui_proxy_unavailable):downloadRunning?liveDownloadMBps>=0?ProxyTestResult.speed(liveDownloadMBps):AppLanguage.t(R.string.ui_172):measured.speedError.isEmpty()&&!measured.speedTarget.equals(ScanInputs.speedUrl(MainActivity.this))?AppLanguage.t(R.string.ui_181):measured.speedValue());
            down.setTextColor(unavailable?WARNING:downloadRunning||measured.speedError.isEmpty()&&measured.downloadMBps>=0?PRIMARY:measured.speedError.isEmpty()?SECONDARY:WARNING);
            setMetricValue(ping,unavailable?AppLanguage.t(R.string.ui_proxy_unavailable):probeRunning?AppLanguage.t(R.string.ui_172):ScanProbePolicy.latency(p)==Long.MAX_VALUE?AppLanguage.t(R.string.ui_181):measured.pingValue(p));divider.setBackgroundColor(active?Color.rgb(44,88,88):STROKE);
            address.setText(p.host);address.setContentDescription(p.key());
            boolean known=country.matches("[A-Z]{2}");designFlag.setText(known?new String(Character.toChars(0x1f1e6+country.charAt(0)-'A'))+new String(Character.toChars(0x1f1e6+country.charAt(1)-'A')):"◉");designName.setText(known?new java.util.Locale("",country).getDisplayCountry(AppLanguage.locale()):AppLanguage.t(R.string.ui_032_unknown));designRadio.setSelected(active);designRadio.setTextColor(active?PRIMARY:SECONDARY);designProtocol.setText(p.type==ProxyEndpoint.Type.OPENVPN?"VPN Gate":p.type.name());int tint=p.type==ProxyEndpoint.Type.OPENVPN?Color.rgb(130,167,255):PRIMARY;designProtocol.setTextColor(tint);designProtocol.setBackground(background(p.type==ProxyEndpoint.Type.OPENVPN?Color.rgb(28,43,70):Color.rgb(20,48,47),dp(12),tint));if(p.type==ProxyEndpoint.Type.OPENVPN){ping.setText(p.latencyMs<0?"—":p.latencyMs+" ms");ping.setContentDescription(AppLanguage.t(R.string.ui_032_gate_speed));row.setContentDescription(designName.getText()+", VPN Gate, "+AppLanguage.t(p.hasAnyService()?R.string.ui_091:R.string.ui_032_gate_unverified));}
            long ms=ScanProbePolicy.latency(p);designSignal.latency(ms);ping.setTextColor(unavailable?WARNING:ms==Long.MAX_VALUE?SECONDARY:ms<150?PRIMARY:ms<300?Color.rgb(255,191,55):Color.rgb(255,135,64));
        }
    }
    private UiIconButton cardIcon(LinearLayout row,UiIconButton.Kind kind,String label,Runnable action){UiIconButton icon=new UiIconButton(this,kind,label);icon.setOnClickListener(v->action.run());LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(0,dp(48),1);cell.setMarginStart(dp(2));cell.setMarginEnd(dp(2));row.addView(icon,cell);return icon;}
    private void proxyDetails(ProxyEndpoint p) {
        String country = p.countryCode.isEmpty() ? CountryLookup.lookup(p.host) : p.countryCode;
        StringBuilder details = new StringBuilder(p.key()).append(AppLanguage.t(R.string.ui_179)).append(CountryLookup.display(country,AppLanguage.locale()))
            .append(AppLanguage.t(R.string.ui_180)).append(p.latencyMs < 0 ? AppLanguage.t(R.string.ui_181) : isolated(p.latencyMs + " ms"))
            .append(AppLanguage.t(p.type==ProxyEndpoint.Type.OPENVPN&&p.latencyTarget.equals("tcp-port")?R.string.ui_gate_port_help:R.string.ui_182)).append("\n").append(AppLanguage.t(R.string.ui_health_scope))
            .append("\n").append(p.type==ProxyEndpoint.Type.OPENVPN?"OpenVPN":p.udpAdvertised ? AppLanguage.t(R.string.ui_183) : AppLanguage.t(R.string.ui_184))
            .append("\n\n");
        for (ServiceTargets.Target target : ServiceTargets.current())
            details.append(AppLanguage.display(target.name)).append(": ").append(p.passed(target) ? AppLanguage.t(R.string.ui_185) : p.checked(target) ? AppLanguage.t(R.string.ui_186) : AppLanguage.t(R.string.ui_187)).append('\n');
        ProxyTestResult measured = testResults.get(p.key());
        if (measured != null) {
            details.append(AppLanguage.t(R.string.ui_188)).append(isolated(ProxyTestResult.speed(measured.downloadMBps)));
            if (measured.downloadedBytes > 0) details.append(AppLanguage.t(R.string.ui_189)).append(isolated(TrafficMeter.bytes(measured.downloadedBytes)));
            if (!measured.speedError.isEmpty()) details.append(AppLanguage.t(R.string.ui_190)).append(ProxyTestResult.shortError(measured.speedError))
                .append("\n").append(measured.speedError);
            if (!measured.probeError.isEmpty()) details.append(AppLanguage.t(R.string.ui_191)).append(ProxyTestResult.shortError(measured.probeError))
                .append("\n").append(measured.probeError);
            if (!measured.probeInfo.isEmpty()) details.append("\n").append(measured.probeInfo);
        }
        if(p.instagramLatency()!=Long.MAX_VALUE)details.append("\n").append(AppLanguage.t(R.string.ui_287)).append(": ").append(p.instagramLatency()).append(" ms");
        if(!p.latencyTarget.isEmpty())details.append("\n").append(AppLanguage.t(R.string.ui_target_ping)).append(": ").append(p.latencyTarget);
        if(measured!=null){details.append("\n").append(AppLanguage.t(R.string.ui_target_speed)).append(": ").append(measured.speedTarget.isEmpty()?AppLanguage.t(R.string.ui_target_default):measured.speedTarget);if(!measured.speedError.isEmpty())details.append("\n").append(AppLanguage.t(R.string.ui_target_last)).append(measured.speedRequestTarget.isEmpty()?AppLanguage.t(R.string.ui_target_default):measured.speedRequestTarget);}
        if(measured!=null&&measured.speedAt>0)details.append("\n").append(AppLanguage.t(R.string.ui_v28_speed_time)).append(android.text.format.DateFormat.getDateFormat(AppLanguage.wrap(this)).format(new java.util.Date(measured.speedAt))).append(" ").append(android.text.format.DateFormat.getTimeFormat(AppLanguage.wrap(this)).format(new java.util.Date(measured.speedAt)));
        if(p.key().equals(getSharedPreferences("persiansocks",0).getString("connection-error-endpoint","")))details.append("\n\n").append(AppLanguage.t(R.string.ui_v28_vpn_error)).append(getSharedPreferences("persiansocks",0).getString("connection-error",""));
        details.append("\n\n").append(AppLanguage.t(R.string.ui_v28_evidence));
        if(p.type==ProxyEndpoint.Type.OPENVPN){VpnGateProfile node=gateDirectory.store.get(p.key());if(node!=null&&node.vpnTestedAt>0)details.append("\n\n").append(AppLanguage.t(node.vpnSuccess?R.string.ui_gate_scan_passed:R.string.ui_v28_vpn_error)).append("\n").append(node.vpnError);}
        if(p.type==ProxyEndpoint.Type.OPENVPN)details.append("\n\n").append(AppLanguage.t(R.string.ui_gate_scan_help)).append("\n\n").append(AppLanguage.t(R.string.ui_032_gate_privacy));
        TextView info = text(details.toString(), 14, Color.WHITE, false);
        info.setPadding(dp(18), dp(12), dp(18), dp(12)); info.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this); scroll.addView(info);
        new AlertDialog.Builder(this, R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_192)).setView(scroll)
            .setPositiveButton(AppLanguage.t(R.string.ui_007),null).show();
    }
    private void chooseProxy(ProxyEndpoint proxy) {
        selected=proxy;userSelected=true;ipRoute="";ipCheckedAt=0;
        int phase=ProxyVpnService.phase();
        if(phase!=ProxyVpnService.DISCONNECTED&&!proxy.key().equals(ProxyVpnService.activeEndpoint()))connect();
        render();
    }
    private String currentIpRoute() {
        android.net.ConnectivityManager cm=getSystemService(android.net.ConnectivityManager.class);
        return (vpnRequestPending?ProxyVpnService.CONNECTING:ProxyVpnService.phase())+"|"+(vpnRequestPending&&selected!=null?selected.key():ProxyVpnService.activeEndpoint())+"|"+cm.getActiveNetwork();
    }
    private void refreshPublicIp(boolean force) {
        if(activeProfile==null||isDestroyed())return;
        String route=currentIpRoute();long now=android.os.SystemClock.elapsedRealtime();
        if(!force&&route.equals(ipRoute)&&(ipCheck!=null||now-ipCheckedAt<60000))return;
        if(ipCheck!=null){ipCheck.cancel();ipCheck=null;}
        ipRoute=route;ipCheckedAt=now;activeProfile.setVisibility(View.VISIBLE);activeProfile.setTextColor(SECONDARY);
        if(ProxyVpnService.phase()==ProxyVpnService.CONNECTING||vpnRequestPending){activeProfile.setText(AppLanguage.t(R.string.ui_v25_006));return;}
        activeProfile.setText(AppLanguage.t(R.string.ui_v25_000)+AppLanguage.t(R.string.ui_v25_001));
        final boolean vpn=ProxyVpnService.phase()==ProxyVpnService.CONNECTED;
        final ProxyEndpoint proxy=ProxyEndpoint.parse(ProxyVpnService.activeEndpoint());final PublicIpCheck task=new PublicIpCheck();ipCheck=task;
        new Thread(()->{
            PublicIpCheck.Result result=null;try{result=task.inspect(getApplicationContext(),vpn,proxy);}catch(Exception ignored){}
            final PublicIpCheck.Result measured=result;
            ui.post(()->{
                if(isDestroyed()||ipCheck!=task||!route.equals(currentIpRoute()))return;
                ipCheck=null;ipCheckedAt=android.os.SystemClock.elapsedRealtime();
                if(measured==null){activeProfile.setText(AppLanguage.t(R.string.ui_v25_002));activeProfile.setTextColor(WARNING);return;}
                String message=AppLanguage.t(R.string.ui_v25_000)+AppLanguage.isolate(measured.actual);
                if(vpn){
                    if(selected!=null&&proxy!=null&&!selected.key().equals(proxy.key())){message+="\n"+AppLanguage.t(R.string.ui_v25_017);activeProfile.setTextColor(WARNING);}
                    else if(measured.expected.isEmpty()){message+="\n"+AppLanguage.t(R.string.ui_v25_016);activeProfile.setTextColor(WARNING);}
                    else if(measured.matches()){message+="\n"+AppLanguage.t(measured.actual.equals(proxy.host)?R.string.ui_v25_003:R.string.ui_v25_004);activeProfile.setTextColor(PRIMARY);}
                    else{message+="\n"+AppLanguage.t(R.string.ui_v25_005);activeProfile.setTextColor(DANGER);}
                }
                activeProfile.setText(message);
            });
        },"actual-public-ip-check").start();
    }
    private void startSavedPings() {
        if(singleTest!=null||speedTest!=null||speedBatch!=null||preparingSpeedBatch)return;
        filteredPingMode=false;savedPingQueue=favoriteSnapshots();savedPingPosition=0;nextSavedPing();
    }
    private void startFilteredPings() {
        if(singleTest!=null||speedTest!=null||speedBatch!=null||preparingSpeedBatch||savedPingQueue!=null)return;
        // Freeze matching healthy results before measurements can change the ranking/filter membership.
        savedPingQueue=ProxyResults.visible(healthy.snapshot(),filter,sort,protocol,pingLimit,speedSnapshot(),smoothResults);
        filteredPingMode=true;savedPingPosition=0;nextSavedPing();
    }
    private void nextSavedPing() {
        if(savedPingQueue==null)return;
        while(savedPingPosition<savedPingQueue.size()){
            ProxyEndpoint proxy=savedPingQueue.get(savedPingPosition++);
            if(proxy.type!=ProxyEndpoint.Type.OPENVPN&&(filteredPingMode||favorites.containsKey(proxy.key()))&&DistributionPolicy.allows(proxy)){startProxyProbe(proxy,true);return;}
        }
        savedPingQueue=null;updateActions();
    }
    private void confirmSpeedBatch(FilteredSpeedBatch batch) {
        if(speedTest!=null||singleTest!=null||speedBatch!=null||preparingSpeedBatch||savedPingQueue!=null||batch.total()==0)return;
        new AlertDialog.Builder(this,R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_v25_012))
            .setMessage(AppLanguage.t(R.string.ui_249)+batch.total()+AppLanguage.t(R.string.ui_250)+TrafficMeter.bytes(batch.maximumBytes())+AppLanguage.t(R.string.ui_251))
            .setNegativeButton(AppLanguage.t(R.string.ui_252),null).setPositiveButton(AppLanguage.t(R.string.ui_253),(d,w)->{
                if(isDestroyed()||speedTest!=null||singleTest!=null||speedBatch!=null||savedPingQueue!=null)return;
                speedBatch=batch;nextFilteredSpeed();render();
            }).show();
    }
    private void connect() {
        pendingGateScan=false;
        if (selected == null) { Toast.makeText(this, AppLanguage.t(R.string.ui_202), Toast.LENGTH_LONG).show(); return; }
        if(selected.type==ProxyEndpoint.Type.OPENVPN&&AndroidRoutes.mode(this).equals("domains")){connectionError=AppLanguage.t(R.string.ui_032_gate_scope);updateActions();return;}
        if (!selected.hasAnyService()&&selected.type!=ProxyEndpoint.Type.OPENVPN) { connectionError=AppLanguage.t(R.string.ui_203);updateActions(); return; }
        connectionError="";getSharedPreferences("persiansocks",MODE_PRIVATE).edit().remove("connection-error").apply();
        vpnRequestPending=true;updateActions();
        VpnDisclosure.ensure(this,this::prepareVpn,()->{vpnRequestPending=false;connectionError=AppLanguage.t(R.string.ui_204);updateActions();});
    }
    private void stopGateScan(){startService(new Intent(this,ProxyVpnService.class).setAction(ProxyVpnService.GATE_STOP));}
    private void chooseGateScan(){if(ProxyVpnService.gateScanning()){stopGateScan();return;}new AlertDialog.Builder(this,R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_gate_scan_title)).setItems(new String[]{AppLanguage.t(R.string.ui_scan_fast),AppLanguage.t(R.string.ui_scan_deep)},(dialog,which)->requestGateScan(which==1,null)).show();}
    private void requestGateScan(boolean deep,String key){
        if(ProxyVpnService.gateScanning()){stopGateScan();return;}if(gateDirectory.busy())return;
        if(AndroidRoutes.mode(this).equals("domains")){connectionError=AppLanguage.t(R.string.ui_032_gate_scope);updateActions();return;}
        if(gateDirectory.store.snapshot().isEmpty()){gateDirectory.refresh();Toast.makeText(this,AppLanguage.t(R.string.ui_gate_scan_empty),Toast.LENGTH_LONG).show();return;}
        new AlertDialog.Builder(this,R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_gate_scan_title)).setMessage(AppLanguage.t(R.string.ui_gate_scan_help)).setNegativeButton(AppLanguage.t(R.string.ui_067),null).setPositiveButton(AppLanguage.t(deep?R.string.ui_scan_deep:R.string.ui_scan_fast),(dialog,which)->{
            if(scanning||ProxyVpnService.autoScanning())stopScan();pendingGateScan=true;pendingGateDeep=deep;pendingGateKey=key;vpnRequestPending=true;VpnDisclosure.ensure(this,this::prepareVpn,()->{pendingGateScan=false;vpnRequestPending=false;updateActions();});
        }).show();
    }
    private void prepareVpn(){
        Intent prep = VpnService.prepare(this);
        if (prep != null) startActivityForResult(prep, VPN_REQUEST);
        else initiateVpn();
    }

    private List<ProxyEndpoint> loadBootstrap(){if(bootstrap==null)bootstrap=ScanInputs.bootstrap(this);return bootstrap;}
    private List<ProxyScanner.Source> configuredSources() {return ScanInputs.sources(this);}
    private void saveReport() {
        getSharedPreferences("persiansocks", MODE_PRIVATE).edit().putString("scan-report", scanReport).apply();
    }
    private void showReport() {
        TextView text = new TextView(this);
        String allReports = AppLanguage.t(R.string.ui_205) + getSharedPreferences("persiansocks", MODE_PRIVATE).getString("collection-report", AppLanguage.t(R.string.ui_206)) + "\n\n" + scanReport + AppLanguage.t(R.string.ui_207) +
            getSharedPreferences("persiansocks", MODE_PRIVATE).getString("vpn-report", AppLanguage.t(R.string.ui_208));
        text.setText(AppLanguage.display(allReports)); text.setTextSize(14);
        text.setTextIsSelectable(true); text.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView scroll = new ScrollView(this); scroll.addView(text);
        new AlertDialog.Builder(this, R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_209)).setView(scroll)
            .setPositiveButton(AppLanguage.t(R.string.ui_007), null).setNeutralButton(AppLanguage.t(R.string.ui_057), (d, w) -> {
                android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PersianSocks scan report", allReports));
                Toast.makeText(this, AppLanguage.t(R.string.ui_210), Toast.LENGTH_SHORT).show();
            }).show();
    }
    private void showSourceMetrics(){
        List<ProxyScanner.Source> sources=configuredSources();
        ProxyStore.IO.execute(()->{String report;try{report=SourceQuality.report(this,sources);}catch(Exception e){report=AppLanguage.t(R.string.ui_128);}final String content=report;
            ui.post(()->{if(isDestroyed())return;TextView body=text(content,13,SECONDARY,false);body.setTextIsSelectable(true);body.setPadding(dp(16),dp(12),dp(16),dp(12));ScrollView scroll=new ScrollView(this);scroll.addView(body);new AlertDialog.Builder(this,R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_source_metrics)).setView(scroll).setPositiveButton(AppLanguage.t(R.string.ui_007),null).show();});});
    }
    private void sourceDialog() {
        LinearLayout panel=dialogContent();panel.addView(text(AppLanguage.t(R.string.ui_v28_sources),13,SECONDARY,false));
        TextView metrics=text(AppLanguage.t(R.string.ui_source_metrics),14,PRIMARY,true);metrics.setPadding(0,dp(14),0,dp(14));metrics.setOnClickListener(v->showSourceMetrics());panel.addView(metrics);LinearLayout types=horizontal();CheckBox[] choices=new CheckBox[3];ProxyEndpoint.Type[] protocols={ProxyEndpoint.Type.SOCKS5,ProxyEndpoint.Type.HTTP,ProxyEndpoint.Type.HTTPS};int stored=getSharedPreferences("persiansocks",0).getInt("source-types",0);if(stored==0){for(String oldRow:getSharedPreferences("persiansocks",0).getString("extra-sources","").split("\n")){try{ProxyScanner.Source oldSource=SourceCatalog.custom(oldRow,1);if(oldSource!=null)stored|=oldSource.type.bit;}catch(Exception ignored){}}if(stored==0)stored=1;}
        for(int i=0;i<3;i++){choices[i]=new ProtocolChoice(this,protocols[i].name(),(stored&protocols[i].bit)!=0);if(DistributionPolicy.encryptedOnly()){choices[i].setChecked(i==2);choices[i].setEnabled(i==2);}LinearLayout.LayoutParams choiceSize=new LinearLayout.LayoutParams(0,dp(48),1);if(i>0)choiceSize.setMarginStart(dp(6));types.addView(choices[i],choiceSize);}panel.addView(types,margin(dp(12),0));
        EditText urls=new EditText(this);urls.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);urls.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);urls.setGravity(Gravity.LEFT|Gravity.TOP);urls.setTextSize(14);urls.setTextColor(Color.WHITE);urls.setHintTextColor(SECONDARY);urls.setMinLines(3);urls.setMaxLines(8);urls.setTextDirection(View.TEXT_DIRECTION_LTR);urls.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);urls.setHint("https://example.com/proxies.txt");StringBuilder old=new StringBuilder();java.util.Set<String> unique=new java.util.LinkedHashSet<>();for(String row:getSharedPreferences("persiansocks",0).getString("extra-sources","").split("\n")){try{ProxyScanner.Source source=SourceCatalog.custom(row,1);if(source!=null)unique.add(source.url);}catch(Exception ignored){}}for(String u:unique)old.append(u).append('\n');urls.setText(old.toString());panel.addView(urls);ScrollView scroll=new ScrollView(this);scroll.addView(panel);
        LinearLayout actions=horizontal();Button save=button(AppLanguage.t(R.string.ui_065),true),cancel=button(AppLanguage.t(R.string.ui_067),false);actions.addView(save,weighted(48,1,6));actions.addView(cancel,weighted(48,1,0));panel.addView(actions,margin(dp(12),0));
        AlertDialog dialog=new AlertDialog.Builder(this,R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_227)).setView(scroll).create();cancel.setOnClickListener(v->dialog.dismiss());save.setOnClickListener(v->{
            int selected=0;for(int i=0;i<3;i++)if(choices[i].isChecked())selected|=protocols[i].bit;if(selected==0){urls.setError(AppLanguage.t(R.string.ui_v28_type));return;}StringBuilder valid=new StringBuilder();int added=0;
            for(String row:urls.getText().toString().split("\n")){if(row.trim().isEmpty())continue;try{for(int i=0;i<3;i++)if(choices[i].isChecked()){ProxyScanner.Source source=SourceCatalog.custom(protocols[i].name()+" "+row.trim(),added+1);if(source==null||!DistributionPolicy.allows(source.type)||++added>20)throw new IllegalArgumentException();valid.append(source.type.name()).append(' ').append(source.url).append('\n');}}catch(Exception ex){urls.setError(AppLanguage.t(R.string.ui_v28_source_error));return;}}
            getSharedPreferences("persiansocks",0).edit().putString("extra-sources",valid.toString()).putInt("source-types",selected).apply();dialog.dismiss();Toast.makeText(this,AppLanguage.t(R.string.ui_228),Toast.LENGTH_SHORT).show();
        });dialog.show();
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK) initiateVpn();
            else { pendingGateScan=false;vpnRequestPending = false; connectionError=AppLanguage.t(R.string.ui_229);updateActions(); }
        }
    }
    private void initiateVpn() {
        if(pendingGateScan){pendingGateScan=false;try{startForegroundService(new Intent(this,ProxyVpnService.class).setAction(ProxyVpnService.GATE_SCAN).putExtra("deep",pendingGateDeep).putExtra("key",pendingGateKey));}catch(Exception e){connectionError=String.valueOf(e.getMessage());}vpnRequestPending=false;updateActions();return;}

        if(selected==null){vpnRequestPending=false;connectionError=AppLanguage.t(R.string.ui_230);updateActions();return;}
        if(!VpnDisclosure.accepted(this)){connect();return;}
        if(!DistributionPolicy.allows(selected)){vpnRequestPending=false;connectionError=AppLanguage.t(R.string.ui_231);updateActions();return;}
        Intent i = new Intent(this, ProxyVpnService.class).setAction(ProxyVpnService.CONNECT)
            .putExtra(ProxyVpnService.EXTRA_ENDPOINT, selected.key()).putExtra("filter", filter).putExtra("sort", sort.name()).putExtra("evidence",selected.json().toString());
        try {
            vpnRequestPending = true; startForegroundService(i);
            connectionError="";updateActions();
        } catch (Exception e) {
            vpnRequestPending = false; updateActions();
            connectionError=AppLanguage.t(R.string.ui_232)+e.getMessage();updateActions();
        }
    }

    private void changeTlsMode(boolean enabled){
        if(DistributionPolicy.play())return;
        cancelSingleTests();
        if(scanning||ProxyVpnService.autoScanning())stopScan();
        if (collecting) stopCollection();
        boolean wasConnected=ProxyVpnService.phase()!=ProxyVpnService.DISCONNECTED||vpnRequestPending;
        if(!ProxyVpnService.stopForTlsChange()&&wasConnected)disconnect();vpnRequestPending=false;
        getSharedPreferences("persiansocks",MODE_PRIVATE).edit().putBoolean("tls-only",enabled).apply();
        DistributionPolicy.setUserTlsOnly(enabled);
        scanTypes=DistributionPolicy.scanTypes(getSharedPreferences("persiansocks",MODE_PRIVATE).getInt("scan-types",7));
        protocol=ScanInputs.protocol(this);bootstrap=null;userSelected=false;selected=null;reloadSaved();selectBest();render();
        String message=enabled?AppLanguage.t(R.string.ui_233):AppLanguage.t(R.string.ui_234);
        if(wasConnected)message+=AppLanguage.t(R.string.ui_235);
        Toast.makeText(this,message,Toast.LENGTH_LONG).show();
    }
    private void updateTraffic(){
        if(trafficDown==null)return;
        TrafficMeter.Snapshot t=ProxyVpnService.traffic(this);
        trafficDown.setText(TrafficMeter.bytes(t.downPerSecond)+"/s");
        trafficUp.setText(TrafficMeter.bytes(t.upPerSecond)+"/s");
        trafficDownTotal.setText(AppLanguage.t(R.string.ui_236)+AppLanguage.isolate(TrafficMeter.bytes(t.downloaded)));
        trafficUpTotal.setText(AppLanguage.t(R.string.ui_236)+AppLanguage.isolate(TrafficMeter.bytes(t.uploaded)));
    }
    private boolean prepareSingleTest(ProxyEndpoint proxy) {
        if (proxy == null) { Toast.makeText(this, AppLanguage.t(R.string.ui_237), Toast.LENGTH_SHORT).show(); return false; }
        if (speedTest != null || singleTest != null || speedBatch != null || preparingSpeedBatch) {
            Toast.makeText(this, AppLanguage.t(R.string.ui_238), Toast.LENGTH_SHORT).show(); return false;
        }
        if (!DistributionPolicy.allows(proxy)) {
            Toast.makeText(this, AppLanguage.t(R.string.ui_239), Toast.LENGTH_SHORT).show(); return false;
        }
        return true;
    }
    private void cancelSingleTests() {
        savedPingQueue=null;
        speedBatchGeneration++;preparingSpeedBatch=false;
        if(speedBatch!=null){speedBatch.cancel();speedBatch=null;}
        DownloadSpeedTest download = speedTest; SingleProxyTest probe = singleTest;
        ProxyEndpoint previous = testingProxy;
        speedTest = null; singleTest = null; testingProxy = null;
        if (download != null) download.cancel(); if (probe != null) probe.cancel();
        if (previous != null) {
            ProxyTestResult record = resultFor(previous);
            if (download != null) record.speedError = AppLanguage.t(R.string.ui_240);
            if (probe != null) { record.probeError = AppLanguage.t(R.string.ui_240); record.probeInfo = ""; }
            saveTestResults();
        }
    }
    private void startProxyProbe(ProxyEndpoint proxy, boolean latencyOnly) {
        if(proxy!=null&&proxy.type==ProxyEndpoint.Type.OPENVPN){requestGateScan(false,proxy.key());return;}
        if (!prepareSingleTest(proxy)) return;
        final SingleProxyTest test = new SingleProxyTest(); singleTest = test; testingProxy = proxy; testingLatency = latencyOnly;
        final ServiceTargets.Target target = SingleProxyTest.latencyTarget(proxy, filter);
        final boolean slow = slowScan;
        render();
        new Thread(() -> {
            Socks5Probe.Result result = null; String error = "";
            try {
                PhysicalTestNetwork network = PhysicalTestNetwork.capture(this);
                result = latencyOnly ? test.latency(proxy, target, slow, network, network) :
                    test.connection(proxy, slow, network, network);
                result=ProbeFailurePolicy.observed(result,network.valid());
                if(!test.cancelled())QualityHistory.probe(network,proxy,result);
            } catch (Exception ex) { error = String.valueOf(ex.getMessage()); }
            final Socks5Probe.Result finished = result; final String failure = error;
            ui.post(() -> {
                if (isDestroyed() || singleTest != test) return;
                singleTest = null; testingProxy = null;
                String note;
                ProxyTestResult record = resultFor(proxy);record.probeAt=System.currentTimeMillis();
                if (test.cancelled() || finished!=null&&finished.failure==Socks5Probe.Failure.CANCELLED) { note = AppLanguage.t(R.string.ui_240); record.probeError = note; record.probeInfo = ""; }
                else if (finished != null && finished.endpoint != null) {
                    ProxyEndpoint fresh = finished.endpoint;
                    if (latencyOnly) {
                        ProxyEndpoint current = proxy;
                        ProxyEndpoint cached=healthy.find(proxy.key());if(cached!=null)current=cached;
                        Map<String,Long> timings=new java.util.HashMap<>(current.serviceLatencies);timings.put(target.url(),fresh.latencyMs);fresh = current.withLatency(fresh.latencyMs,target.url()).withServiceLatencies(timings);
                        record.probeInfo = AppLanguage.t(R.string.ui_241) + AppLanguage.display(target.name) + AppLanguage.t(R.string.ui_242);
                        note = AppLanguage.t(R.string.ui_144) + isolated(fresh.latencyMs + " ms");
                    } else { record.probeInfo = AppLanguage.t(R.string.ui_243); note = AppLanguage.t(R.string.ui_243); }
                    record.probeError = "";
                    final ProxyEndpoint verified = fresh;
                    healthy.accept(verified);
                    if (selected != null && selected.key().equals(proxy.key())) selected = verified;
                    ProxyStore.save(this, healthy);
                } else {
                    record.probeError = failure.isEmpty() && finished != null ? Socks5Probe.describe(finished.failure) : failure;
                    record.probeInfo = "";
                    note = (latencyOnly ? AppLanguage.t(R.string.ui_144) : AppLanguage.t(R.string.ui_244)) + ProxyTestResult.shortError(record.probeError);
                    if(finished!=null&&ProbeFailurePolicy.invalidates(finished.failure)){
                        long rejectedAt=System.currentTimeMillis();healthy.reject(proxy.key(),rejectedAt);manual.removeIf(p->p.key().equals(proxy.key()));
                        ScanCheckpoint current=checkpoint;
                        if(current!=null)ProxyStore.IO.execute(()->{try{current.forgetVerified(proxy.key(),rejectedAt);scanFiles.save(current);}catch(Exception ex){android.util.Log.e("PersianSocks","Invalidation save failed",ex);}});
                        if(!favorites.containsKey(proxy.key()))testResults.remove(proxy.key());
                        ProxyStore.save(this,healthy);
                        note=favorites.containsKey(proxy.key())?AppLanguage.t(R.string.ui_245):AppLanguage.t(R.string.ui_246);
                    }
                }
                saveTestResults();if(savedPingQueue!=null)nextSavedPing();render();
            });
        }, "single-proxy-connection-test").start();
    }
    private void startSpeedTest(ProxyEndpoint proxy) {
        if(proxy!=null&&proxy.type==ProxyEndpoint.Type.OPENVPN&&(!proxy.key().equals(ProxyVpnService.activeEndpoint())||ProxyVpnService.phase()!=ProxyVpnService.CONNECTED||ProxyVpnService.gateScanning())){new AlertDialog.Builder(this,R.style.AppDialogTheme).setMessage(AppLanguage.t(R.string.ui_gate_speed_connect)).setPositiveButton(AppLanguage.t(R.string.ui_design_connect),(d,w)->{chooseProxy(proxy);connect();}).setNegativeButton(AppLanguage.t(R.string.ui_067),null).show();return;}

        if (!prepareSingleTest(proxy)) return;
        runSpeedTest(proxy);
    }
    private void runSpeedTest(ProxyEndpoint proxy) {
        final DownloadSpeedTest test = new DownloadSpeedTest();final String targetUrl=ScanInputs.speedUrl(this);runningSpeedTarget=targetUrl;speedTest = test; testingProxy = proxy; liveDownloadMBps = -1;
        render();
        new Thread(() -> {
            PhysicalTestNetwork network;final android.net.ConnectivityManager manager=getSystemService(android.net.ConnectivityManager.class);final android.net.Network vpn=proxy.type==ProxyEndpoint.Type.OPENVPN?manager.getActiveNetwork():null;
            try { if(proxy.type==ProxyEndpoint.Type.OPENVPN){android.net.NetworkCapabilities caps=vpn==null?null:manager.getNetworkCapabilities(vpn);if(caps==null||!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN))throw new java.io.IOException("VPN unavailable");network=null;}else network = PhysicalTestNetwork.capture(this); }
            catch (Exception ex) {
                ui.post(() -> finishSpeedTest(test, proxy, 0, 0, String.valueOf(ex.getMessage()))); return;
            }
            test.run(new DownloadSpeedTest.Transport() {
                private long lastCheck; private boolean available = true;
                public java.net.HttpURLConnection open(java.net.URL url) throws Exception {
                    return vpn==null?new ProxyHttpConnection(url, proxy, network, network):AppHttp.open(url,vpn);
                }
                public boolean valid() {
                    long now = System.nanoTime();
                    if (now - lastCheck >= 200_000_000L) { available = vpn==null?network.valid() && DistributionPolicy.allows(proxy):ProxyVpnService.phase()==ProxyVpnService.CONNECTED&&!ProxyVpnService.gateScanning()&&proxy.key().equals(ProxyVpnService.activeEndpoint())&&vpn.equals(manager.getActiveNetwork())&&manager.getNetworkCapabilities(vpn)!=null&&manager.getNetworkCapabilities(vpn).hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN); lastCheck = now; }
                    return available;
                }
            }, new DownloadSpeedTest.Listener() {
                public void progress(long bytes, double mbps) { ui.post(() -> {
                    if (!isDestroyed() && speedTest == test) {
                        liveDownloadMBps = ProxyTestResult.fromMbps(mbps);
                        refreshVisibleCards();
                    }
                }); }
                public void finished(long bytes, double mbps, String error) {
                    ui.post(() -> finishSpeedTest(test, proxy, bytes, mbps, error));
                }
            }, targetUrl);
        }, "single-proxy-download-test").start();
    }
    private void finishSpeedTest(DownloadSpeedTest test, ProxyEndpoint proxy, long bytes, double mbps, String error) {
        if (isDestroyed() || speedTest != test) return;
        test.cancel(); speedTest = null; testingProxy = null;
        ProxyTestResult record = resultFor(proxy); record.speedError = error;record.speedRequestTarget=runningSpeedTarget;record.downloadedBytes = bytes;
        if (error.isEmpty()){record.speedTarget=runningSpeedTarget;record.downloadMBps = ProxyTestResult.fromMbps(mbps);record.speedAt=System.currentTimeMillis();}
        saveTestResults();
        if(speedBatch!=null){speedBatch.completed();nextFilteredSpeed();}
        render();
    }
    private void prepareFilteredSpeed() {
        if(speedTest!=null||singleTest!=null||speedBatch!=null||preparingSpeedBatch)return;
        preparingSpeedBatch=true;final int generation=++speedBatchGeneration;
        final String service=filter;final ProxyResults.Sort order=sort;
        final ProxyResults.Protocol kind=protocol;final ProxyResults.PingLimit limit=pingLimit;
        final List<ProxyEndpoint> snapshot=healthy.snapshot();updateActions();
        final List<VpnGateProfile> gateProfiles=gateDirectory==null?java.util.Collections.emptyList():gateDirectory.store.snapshot();
        final Map<String,Double> speeds=speedSnapshot();final boolean smooth=smoothResults;
        rankWorker.execute(()->{
            final FilteredSpeedBatch batch=new FilteredSpeedBatch(snapshot,service,order,kind,limit,speeds,smooth);
            ui.post(()->{
                if(isDestroyed()||generation!=speedBatchGeneration)return;
                preparingSpeedBatch=false;updateActions();
                if(batch.total()==0){Toast.makeText(this,AppLanguage.t(R.string.ui_247),Toast.LENGTH_SHORT).show();return;}
                new AlertDialog.Builder(this,R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_248))
                    .setMessage(AppLanguage.t(R.string.ui_249)+batch.total()+AppLanguage.t(R.string.ui_250)+TrafficMeter.bytes(batch.maximumBytes())+AppLanguage.t(R.string.ui_251))
                    .setNegativeButton(AppLanguage.t(R.string.ui_252),null).setPositiveButton(AppLanguage.t(R.string.ui_253),(d,w)->{
                        if(isDestroyed()||generation!=speedBatchGeneration||speedTest!=null||singleTest!=null||speedBatch!=null)return;
                        speedBatch=batch;nextFilteredSpeed();render();
                    }).show();
            });
        });
    }
    private void nextFilteredSpeed() {
        if(speedBatch==null)return;
        ProxyEndpoint proxy;
        while((proxy=speedBatch.next())!=null){
            ProxyEndpoint current=healthy.find(proxy.key());if(current==null)current=favorites.get(proxy.key());
            if(current!=null&&DistributionPolicy.allows(current)){runSpeedTest(current);return;}
            speedBatch.completed();
        }
        speedBatch=null;updateActions();
    }
    private void stopFilteredSpeed() {
        speedBatchGeneration++;preparingSpeedBatch=false;
        if(speedBatch!=null){
            speedBatch.cancel();speedBatch=null;cancelSingleTests();
        }
        updateActions();
    }
    private ProxyTestResult resultFor(ProxyEndpoint proxy) {
        ProxyTestResult result = testResults.get(proxy.key());
        if (result == null) { result = new ProxyTestResult(); testResults.put(proxy.key(), result); }
        return result;
    }
    private static String isolated(String value) { return "\u2066" + value + "\u2069"; }
    private LinearLayout resultColumn(String label,TextView value){
        LinearLayout pair=new LinearLayout(this);pair.setOrientation(LinearLayout.VERTICAL);pair.setLayoutDirection(AppLanguage.direction());
        pair.setPadding(dp(10),dp(8),dp(10),dp(8));pair.setBackground(background(Color.rgb(16,26,40),dp(10),Color.rgb(31,45,62)));
        pair.addView(text(label,11,SECONDARY,false));pair.addView(value,new LinearLayout.LayoutParams(-1,-2));return pair;
    }

    private TextView metricValue(String value, int color) {
        TextView text = text("", 16, color, true); text.setMaxLines(1);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END); setMetricValue(text, value); return text;
    }
    private void setMetricValue(TextView text, String value) {
        boolean number = value.contains("MB/s") || value.endsWith(" ms");
        text.setTextSize(number ? 16 : 12); text.setTextDirection(number ? View.TEXT_DIRECTION_LTR : AppLanguage.textDirection());
        text.setText(AppLanguage.display(value));
    }
    private Map<String,ProxyTestResult> readTestResults() {
        Map<String,ProxyTestResult> loaded=new LinkedHashMap<>();
        try {
            ProxyDatabase.Data dao=ProxyDatabase.get(this).data();String durable=dao.meta("test-results-v3");
            org.json.JSONObject stored = new org.json.JSONObject(durable==null?getSharedPreferences("persiansocks", MODE_PRIVATE).getString("test-results-v2", "{}"):durable);
            java.util.Iterator<String> keys = stored.keys();
            while (keys.hasNext() && loaded.size() < 1000) {
                String endpoint = keys.next(); org.json.JSONObject result = stored.optJSONObject(endpoint);
                if (endpoint.length() <= 300 && result != null) loaded.put(endpoint, ProxyTestResult.fromJson(result));
            }
            org.json.JSONObject speeds = new org.json.JSONObject(getSharedPreferences("persiansocks", MODE_PRIVATE).getString("speed-notes", "{}"));
            org.json.JSONObject probes = new org.json.JSONObject(getSharedPreferences("persiansocks", MODE_PRIVATE).getString("probe-notes", "{}"));
            java.util.Set<String> legacy = new java.util.LinkedHashSet<>();
            speeds.keys().forEachRemaining(legacy::add); probes.keys().forEachRemaining(legacy::add);
            boolean changed = false;
            for (String endpoint : legacy) if (loaded.size() < 1000 && endpoint.length() <= 300 && !loaded.containsKey(endpoint)) {
                loaded.put(endpoint, ProxyTestResult.legacy(speeds.optString(endpoint), probes.optString(endpoint))); changed = true;
            }
            if(durable==null||changed){org.json.JSONObject migrated=new org.json.JSONObject();for(Map.Entry<String,ProxyTestResult> entry:loaded.entrySet())migrated.put(entry.getKey(),entry.getValue().json());dao.meta(new ProxyDatabase.MetaRow("test-results-v3",migrated.toString()));}
            // Remove bulky legacy preferences only after the durable copy succeeds.
            getSharedPreferences("persiansocks",MODE_PRIVATE).edit().remove("test-results-v2").remove("speed-notes").remove("probe-notes").apply();
        } catch (Exception ignored) {}
        return loaded;
    }
    private void saveTestResults() {
        measurementRevision++;
        while (testResults.size() > 1000) testResults.remove(testResults.keySet().iterator().next());
        org.json.JSONObject stored = new org.json.JSONObject();
        for (Map.Entry<String, ProxyTestResult> entry : testResults.entrySet()) try { stored.put(entry.getKey(), entry.getValue().json()); } catch (Exception ignored) {}
        final String snapshot=stored.toString();final Context app=getApplicationContext();
        ProxyStore.IO.execute(()->{try{ProxyDatabase.get(app).data().meta(new ProxyDatabase.MetaRow("test-results-v3",snapshot));}catch(Exception ex){android.util.Log.e("PersianSocks","Test notes save failed",ex);}});
    }
    private int dp(float n) { return (int) (getResources().getDisplayMetrics().density * n + .5f); }
    private LinearLayout.LayoutParams margin(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = top; p.bottomMargin = bottom; return p;
    }
    private LinearLayout.LayoutParams weighted(int height, float weight, int gap) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(height), weight);
        p.setMarginEnd(dp(gap)); return p;
    }
    private GradientDrawable background(int fill, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(radius);
        d.setStroke(dp(1), stroke); return d;
    }
    private LinearLayout dialogContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutDirection(AppLanguage.direction());
        content.setPadding(dp(20), dp(8), dp(20), dp(8));
        return content;
    }
    private LinearLayout panel() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setLayoutDirection(AppLanguage.direction());
        c.setPadding(dp(18), dp(18), dp(18), dp(18));
        c.setBackground(background(SURFACE, dp(20), STROKE));
        return c;
    }
    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(AppLanguage.display(s)); t.setTextSize(size); t.setTextColor(color);
        t.setGravity(AppLanguage.gravity() | Gravity.CENTER_VERTICAL);
        t.setLayoutDirection(AppLanguage.direction());
        t.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        t.setTextDirection(AppLanguage.textDirection());
        t.setFontFeatureSettings("kern");
        t.setLineSpacing(dp(2), 1);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(AppLanguage.display(label)); b.setTextSize(12); b.setAllCaps(false);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
        b.setPadding(dp(8), dp(2), dp(8), dp(2));b.setBackgroundTintList(null);b.setStateListAnimator(null);
        b.setTextColor(primary ? Color.rgb(7, 21, 26) : Color.WHITE);
        b.setBackground(background(primary ? PRIMARY : SURFACE, dp(12), primary ? PRIMARY : STROKE));
        b.setForeground(new android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(Color.argb(35, 255, 255, 255)), null,
            background(Color.WHITE, dp(12), Color.WHITE)));
        return b;
    }
}
