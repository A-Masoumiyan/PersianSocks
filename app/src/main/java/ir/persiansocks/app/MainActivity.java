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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.BaseAdapter;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.CheckBox;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.content.res.ColorStateList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

public final class MainActivity extends Activity {
    private static java.lang.ref.WeakReference<MainActivity> instance=new java.lang.ref.WeakReference<>(null);
    public static void pauseManualScanForQuickAction(){
        MainActivity current=instance.get();
        if(current!=null&&!current.isDestroyed()&&current.scanning)current.stopScan();
    }
    private static final int VPN_REQUEST = 170;
    private static final int BG = Color.rgb(11, 17, 29);
    private static final int SURFACE = Color.rgb(22, 32, 48);
    private static final int PRIMARY = Color.rgb(70, 226, 184);
    private static final int SECONDARY = Color.rgb(151, 169, 189);
    private static final int STROKE = Color.rgb(43, 58, 76);
    private static final int DANGER = Color.rgb(255, 142, 151);
    private static final int WARNING = Color.rgb(245, 199, 111);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ProxyScanner scanner = new ProxyScanner();
    private final PreparedScanner preparedScanner = new PreparedScanner();
    private ScanFiles scanFiles;
    private ScanCheckpoint checkpoint;
    private int catalogCount;
    private boolean loading, preparing, stopping;
    private volatile boolean collecting;
    private volatile int collectGeneration;
    private final ProxyIndex healthy = ProxyStore.index();
    private final List<ProxyEndpoint> manual = new ArrayList<>();
    // Favorites are controlled only by explicit user actions, never by scanner cleanup.
    private final LinkedHashMap<String,ProxyEndpoint> favorites = new LinkedHashMap<>();
    private boolean showFavorites;
    private long favoritesRevision;
    private Button favoritesButton;
    private LinearLayout filterRow, limitRow;
    private ProxyEndpoint selected;
    private ListView results;
    private LinearLayout emptyState;
    private final ResultAdapter resultAdapter=new ResultAdapter();
    private List<ProxyEndpoint> ranked=new ArrayList<>();
    private final java.util.concurrent.ExecutorService rankWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean ranking;
    private long renderedRevision=-1;
    private String renderedCriteria="";
    private final java.util.concurrent.atomic.AtomicBoolean redrawQueued=new java.util.concurrent.atomic.AtomicBoolean();
    private TextView status, listStatus, catalogInfo, counter, selection;
    private TextView vpnStatus, vpnBadge, activeProfile, selectionProtocol, filterSummary, trafficDown, trafficUp, trafficDownTotal, trafficUpTotal;
    private Button speedButton, probeButton, filteredSpeedButton;
    private FilteredSpeedBatch speedBatch;
    private boolean preparingSpeedBatch;
    private int speedBatchGeneration;
    private DownloadSpeedTest speedTest;
    private SingleProxyTest singleTest;
    private ProxyEndpoint testingProxy;
    private boolean testingLatency;
    private final Map<String, ProxyTestResult> testResults = new LinkedHashMap<>();
    private double liveDownloadMBps = -1;
    private int scanTypes=7;
    private ProxyResults.Protocol protocol=ProxyResults.Protocol.ALL;
    private ProxyResults.PingLimit pingLimit = ProxyResults.PingLimit.NONE;
    private Button scanToggle, collectButton, sortButton, serviceButton, protocolButton, pingButton;
    private boolean smoothResults;
    private long measurementRevision;
    private PowerToggle vpnToggle;
    private String connectionError="";
    private CheckBox sidebarTls,sidebarAuto;
    private Button sidebarScan;
    private Button sidebarToggle, sidebarClose;
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
    private volatile boolean scanning;
    private boolean observedAutoScan;
    private boolean userSelected;
    private volatile int scanGeneration;
    private String scanReport = "هنوز اسکن جدیدی انجام نشده است.";
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
            if(intent.getBooleanExtra("traffic-only",false)){updateTraffic();return;}
            vpnRequestPending = false;
            boolean auto=ProxyVpnService.autoScanning();
            if(observedAutoScan&&!auto&&!scanning)reloadSaved();
            observedAutoScan=auto;
            String state = intent.getStringExtra(ProxyVpnService.EXTRA_MESSAGE);
            if(intent.hasExtra("connection-error"))connectionError=intent.getStringExtra("connection-error");
            if(connectionError==null)connectionError="";
            if(state!=null&&auto)status.setText(state);
            updateActions();updateTraffic();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        instance=new java.lang.ref.WeakReference<>(this);
        loadTestResults();
        ScanInputs.initializePolicy(this);
        favorites.putAll(FavoriteProxies.load(this));
        scanFiles = new ScanFiles(getFilesDir());
        
        maxTests = Math.max(100, Math.min(ProxyScanner.MAX_TESTS,
            getSharedPreferences("persiansocks", MODE_PRIVATE).getInt("max-tests", 5000)));
        slowScan = getSharedPreferences("persiansocks", MODE_PRIVATE).getBoolean("slow-scan", false);
        scanTypes=DistributionPolicy.scanTypes(getSharedPreferences("persiansocks",MODE_PRIVATE).getInt("scan-types",7));
        protocol=ScanInputs.protocol(this);
        pingLimit=ScanInputs.pingLimit(this);
        smoothResults=getSharedPreferences("persiansocks",MODE_PRIVATE).getBoolean("smooth-results",false);
        scanAll = getSharedPreferences("persiansocks", MODE_PRIVATE).getBoolean("scan-all", false);
        scanReport = getSharedPreferences("persiansocks", MODE_PRIVATE).getString("scan-report", scanReport);
        customTargets.addAll(ServiceTargets.decode(getSharedPreferences("persiansocks", MODE_PRIVATE).getString("test-domains", "[]")));
        ServiceTargets.configure(customTargets);
        filter = getSharedPreferences("persiansocks", MODE_PRIVATE).getString("filter", "ALL");
        if (filter.startsWith("https://") && customTargets.stream().noneMatch(t -> t.id.equals(filter))) filter = "ALL";
        try { CountryLookup.initialize(getAssets().open("ip-country.bin")); } catch (Exception ignored) {}
        try { sort = ProxyResults.Sort.valueOf(getSharedPreferences("persiansocks", MODE_PRIVATE).getString("sort", "FASTEST")); }
        catch (IllegalArgumentException ignored) {}
        buildUi();
        render();
        if (state != null && state.getBoolean("sidebar-open", false)) setSidebarVisible(true);
        connectionError=getSharedPreferences("persiansocks",MODE_PRIVATE).getString("connection-error","");updateActions();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{ Manifest.permission.POST_NOTIFICATIONS }, 18);
    }
    @Override protected void onStart() {
        super.onStart();
        ScanInputs.initializePolicy(this);
        IntentFilter f = new IntentFilter(ProxyVpnService.STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(vpnState, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(vpnState, f);
        vpnRequestPending = false;
        observedAutoScan=ProxyVpnService.autoScanning();
        if(!scanning)reloadSaved();
        connectionError=getSharedPreferences("persiansocks",MODE_PRIVATE).getString("connection-error","");
        updateActions();
        updateTraffic();
        QuickControls.refresh(this,true);
    }
    @Override protected void onStop() { super.onStop(); unregisterReceiver(vpnState); }
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

    private void buildUi() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        page = new FrameLayout(this);
        page.setBackgroundColor(BG);
        ListView scroller = new ListView(this); mainScroller = scroller; results=scroller;
        scroller.setDivider(null); scroller.setCacheColorHint(BG);
        scroller.setClipToPadding(false);

        scroller.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setPadding(dp(12), dp(8), dp(12), dp(16));
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroller.addHeaderView(root,null,false);
        page.addView(scroller, new FrameLayout.LayoutParams(-1, -1));
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
            root.setPadding(dp(12), dp(8)+top, dp(12), dp(8));
            results.setPadding(left,0,right,dp(16)+bottom);
            if (sidebar != null) sidebar.setPadding(dp(14), dp(12)+top, dp(14)+right, dp(12)+bottom);
            return insets;
        });

        LinearLayout top = horizontal();
        sidebarToggle = button("☰", false); sidebarToggle.setTextSize(22);
        sidebarToggle.setContentDescription("نمایش تنظیمات");
        sidebarToggle.setOnClickListener(v -> setSidebarVisible(!sidebarVisible));
        top.addView(sidebarToggle, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout title = new LinearLayout(this); title.setOrientation(LinearLayout.VERTICAL);
        TextView brand = text("PersianSocks", 22, Color.WHITE, true);
        brand.setTextDirection(View.TEXT_DIRECTION_LTR);
        title.addView(brand);
        title.addView(text(DistributionPolicy.play() ? "v0.22.0 • TLS" : "v0.22.0", 11, SECONDARY, false));
        LinearLayout.LayoutParams titleCell = new LinearLayout.LayoutParams(0, -2, 1);
        titleCell.setMarginStart(dp(10)); top.addView(title, titleCell);
        android.widget.ImageView brandIcon=new android.widget.ImageView(this);
        brandIcon.setImageResource(R.drawable.ic_launcher);
        brandIcon.setContentDescription("نشان PersianSocks");
        LinearLayout.LayoutParams iconCell=new LinearLayout.LayoutParams(dp(42),dp(42));
        iconCell.setMarginStart(dp(8));top.addView(brandIcon,iconCell);
        root.addView(top);

        LinearLayout hero = panel();
        hero.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TR_BL,
            new int[]{Color.rgb(21, 55, 62), Color.rgb(20, 32, 48)});
        gradient.setCornerRadius(dp(18)); gradient.setStroke(dp(1), Color.rgb(44, 88, 88));
        hero.setBackground(gradient);
        LinearLayout connectionHeading = horizontal();
        vpnBadge = text("●  آمادهٔ اتصال", 12, PRIMARY, true);
        connectionHeading.addView(vpnBadge, new LinearLayout.LayoutParams(0, -2, 1));
        selectionProtocol = chip("—", SECONDARY, Color.rgb(25, 47, 56));
        selectionProtocol.setTextDirection(View.TEXT_DIRECTION_LTR);
        connectionHeading.addView(selectionProtocol);
        hero.addView(connectionHeading);
        selection = text("پروکسی انتخاب نشده", 20, Color.WHITE, true);
        selection.setTextDirection(View.TEXT_DIRECTION_LTR);
        selection.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        selection.setMaxLines(1); selection.setEllipsize(android.text.TextUtils.TruncateAt.END);
        selection.setAutoSizeTextTypeUniformWithConfiguration(14, 20, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        hero.addView(selection, margin(dp(5), 0));
        vpnStatus = text("یک پروکسی انتخاب کن و متصل شو", 12, SECONDARY, false);
        vpnStatus.setMaxLines(2); vpnStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        vpnStatus.setOnClickListener(v -> new AlertDialog.Builder(this, R.style.AppDialogTheme).setTitle("وضعیت اتصال")
            .setMessage(vpnStatus.getText()).setPositiveButton("بستن", null).show());
        vpnStatus.setTooltipText("برای دیدن پیام کامل بزنید");
        hero.addView(vpnStatus, margin(dp(4), 0));
        activeProfile = text("", 11, SECONDARY, false); activeProfile.setVisibility(View.GONE);
        hero.addView(activeProfile, margin(dp(4), 0));

        LinearLayout actions = horizontal();
        vpnToggle = new PowerToggle(this);
        vpnToggle.setOnClickListener(v -> {
            if (vpnRequestPending || ProxyVpnService.phase() != ProxyVpnService.DISCONNECTED) disconnect();
            else connect();
        });
        LinearLayout.LayoutParams powerCell=new LinearLayout.LayoutParams(dp(56),dp(56));powerCell.setMarginEnd(dp(8));actions.addView(vpnToggle,powerCell);
        probeButton = button("تست اتصال", false); probeButton.setTextSize(12);
        probeButton.setOnClickListener(v -> { if (singleTest != null) singleTest.cancel(); else startProxyProbe(selected, false); });
        actions.addView(probeButton, weighted(48, 1, 6));
        speedButton = button("تست سرعت", false); speedButton.setTextSize(12);
        speedButton.setOnClickListener(v -> { if (speedTest != null) speedTest.cancel(); else startSpeedTest(selected); });
        actions.addView(speedButton, weighted(48, 1, 0));
        hero.addView(actions, margin(dp(10), 0));

        LinearLayout traffic = horizontal();
        LinearLayout down = metricColumn("↓ دانلود"), up = metricColumn("↑ آپلود");
        trafficDown = text("0 B/s", 17, PRIMARY, true);
        trafficUp = text("0 B/s", 17, PRIMARY, true);
        trafficDown.setTextDirection(View.TEXT_DIRECTION_LTR); trafficUp.setTextDirection(View.TEXT_DIRECTION_LTR);
        trafficDownTotal = text("مجموع: 0 B", 11, SECONDARY, false);
        trafficUpTotal = text("مجموع: 0 B", 11, SECONDARY, false);
        down.addView(trafficDown); down.addView(trafficDownTotal);
        up.addView(trafficUp); up.addView(trafficUpTotal);
        traffic.addView(down, new LinearLayout.LayoutParams(0, -2, 1));
        traffic.addView(up, new LinearLayout.LayoutParams(0, -2, 1));
        hero.addView(traffic, margin(dp(10), 0));
        root.addView(hero, margin(dp(8), 0));

        LinearLayout scanPanel = panel();
        scanPanel.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout scanTitle = horizontal();
        collectButton = button("دریافت لیست", false); collectButton.setTextSize(12);
        collectButton.setOnClickListener(v -> { if (collecting) stopCollection(); else collectList(); });
        scanTitle.addView(collectButton, weighted(48, 1, 8));
        scanToggle = button("اسکن  ▾", false); scanToggle.setTextSize(13);
        scanToggle.setPadding(dp(12), 0, dp(12), 0);
        scanToggle.setOnClickListener(v -> { if (scanning || ProxyVpnService.autoScanning()) stopScan(); else scanMenu(); });
        scanTitle.addView(scanToggle, weighted(48, 1, 0));
        scanPanel.addView(scanTitle);
        listStatus = text("", 11, SECONDARY, false); listStatus.setVisibility(View.GONE);
        listStatus.setMaxLines(1); listStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        scanPanel.addView(listStatus, margin(dp(4), 0));
        status = text(checkpoint != null && !checkpoint.complete() ? "اسکن متوقف‌شده: " + checkpoint.done() + " از " + checkpoint.total() :
            catalogCount == 0 ? "اول لیست پروکسی‌ها را دریافت کن" : catalogCount + " پروکسی آمادهٔ اسکن", 12, SECONDARY, false);
        status.setMaxLines(1); status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setOnClickListener(v -> showReport());
        scanPanel.addView(status, margin(dp(4), 0));
        scanProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        scanProgress.setMax(100);
        scanProgress.setProgressTintList(ColorStateList.valueOf(PRIMARY));
        scanProgress.setIndeterminateTintList(ColorStateList.valueOf(PRIMARY));
        scanProgress.setProgressBackgroundTintList(ColorStateList.valueOf(STROKE));
        scanProgress.setVisibility(View.GONE);
        scanPanel.addView(scanProgress, sized(dp(3), dp(8)));
        root.addView(scanPanel, margin(dp(8), 0));

        LinearLayout heading = horizontal();
        heading.addView(text("پروکسی‌ها", 18, Color.WHITE, true), new LinearLayout.LayoutParams(0, -2, 1));
        counter = text("۰ نتیجه", 12, PRIMARY, true); heading.addView(counter);
        Button reset=button("↺",false);reset.setContentDescription("بازنشانی فیلترها");reset.setOnClickListener(v->resetFilters());
        LinearLayout.LayoutParams resetCell=new LinearLayout.LayoutParams(dp(48),dp(48));resetCell.setMarginStart(dp(8));heading.addView(reset,resetCell);
        root.addView(heading, margin(dp(12), 0));
        favoritesButton=button("☆ علاقه‌مندی‌ها",false);
        favoritesButton.setTextSize(13);
        favoritesButton.setOnClickListener(v->{showFavorites=!showFavorites;renderedRevision=-1;render();});
        root.addView(favoritesButton,sized(dp(44),dp(4)));
        filterRow=horizontal();limitRow=horizontal();
        serviceButton=dropdownButton("سرویس: همه","انتخاب سرویس");serviceButton.setOnClickListener(v->serviceMenu());
        protocolButton=dropdownButton("نوع: همه","نوع پروکسی");protocolButton.setOnClickListener(v->protocolMenu());
        pingButton=dropdownButton("پینگ: همه","محدودیت پینگ");pingButton.setOnClickListener(v->pingMenu());
        sortButton=dropdownButton("کمترین تأخیر","مرتب‌سازی");sortButton.setOnClickListener(v->sortMenu());
        filterRow.addView(serviceButton,weighted(48,1,6));filterRow.addView(protocolButton,weighted(48,1,0));
        limitRow.addView(pingButton,weighted(48,1,6));limitRow.addView(sortButton,weighted(48,1,0));
        root.addView(filterRow,margin(dp(6),0));root.addView(limitRow,margin(dp(6),0));
        filteredSpeedButton=button("تست سرعت همهٔ نتایج فیلترشده",false);
        filteredSpeedButton.setTextSize(12);
        filteredSpeedButton.setOnClickListener(v->{if(speedBatch!=null||preparingSpeedBatch)stopFilteredSpeed();else prepareFilteredSpeed();});
        root.addView(filteredSpeedButton,sized(dp(42),dp(6)));
        rebuildFilters();
        filterSummary = text("", 11, SECONDARY, false);
        filterSummary.setSingleLine(true); filterSummary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        filterSummary.setOnClickListener(v -> resetFilters());
        root.addView(filterSummary, margin(dp(3), 0));
        emptyState=panel();root.addView(emptyState,margin(dp(8),0));
        results.setAdapter(resultAdapter);
        buildSidebar();
        page.requestApplyInsets();
    }

    private LinearLayout metricColumn(String label) {
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.addView(text(label, 11, SECONDARY, false)); return column;
    }
    private LinearLayout filterColumn(String label, Button choice) {
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.addView(text(label, 11, SECONDARY, false)); column.addView(choice, sized(dp(48), dp(4)));
        return column;
    }
    private Button dropdownButton(String label, String description) {
        Button choice = button(label + "  ▾", false); choice.setTextSize(13);
        choice.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); choice.setPadding(dp(12), 0, dp(12), 0);
        choice.setSingleLine(true); choice.setEllipsize(android.text.TextUtils.TruncateAt.END);
        choice.setContentDescription(description); return choice;
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
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
        scrim.setContentDescription("بستن سایدبار");
        scrim.setOnClickListener(v -> setSidebarVisible(false));
        sidebarOverlay.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        sidebar = new LinearLayout(this); sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        sidebar.setPadding(dp(14), dp(12), dp(14), dp(12));
        sidebar.setBackgroundColor(SURFACE); sidebar.setElevation(dp(16));
        sidebar.setClickable(true); // Consume taps inside the drawer, including empty space.
        // Physical RIGHT is intentional: END would place an RTL drawer on the left.
        FrameLayout.LayoutParams drawerCell = new FrameLayout.LayoutParams(dp(320), -1, Gravity.RIGHT);
        sidebarOverlay.addView(sidebar, drawerCell);
        sidebarOverlay.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            int width = Math.min(dp(320), Math.max(1, (int)((r-l)*.86f)));
            FrameLayout.LayoutParams cell = (FrameLayout.LayoutParams) sidebar.getLayoutParams();
            if (cell.width != width) { cell.width = width; sidebar.setLayoutParams(cell); }
        });
        LinearLayout heading = horizontal();
        heading.addView(text("تنظیمات", 17, Color.WHITE, true), new LinearLayout.LayoutParams(0,-2,1));
        sidebarClose = button("بستن  ×", false);
        sidebarClose.setContentDescription("پنهان کردن سایدبار");
        sidebarClose.setOnClickListener(v -> setSidebarVisible(false));
        LinearLayout.LayoutParams closeCell = new LinearLayout.LayoutParams(dp(82),dp(48));
        closeCell.setMarginStart(dp(8)); heading.addView(sidebarClose, closeCell);
        sidebar.addView(heading);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0,dp(12),0,dp(8)); scroll.addView(content);
        sidebar.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));

        content.addView(text("اتصال",14,PRIMARY,true));
        sidebarTls=sidebarCheck("فقط پروکسی TLS",DistributionPolicy.encryptedOnly());sidebarTls.setEnabled(!DistributionPolicy.play());
        sidebarTls.setOnCheckedChangeListener((b,on)->{if(on!=DistributionPolicy.encryptedOnly())changeTlsMode(on);});content.addView(sidebarTls,sized(dp(48),dp(4)));
        sidebarAuto=sidebarCheck("تعویض خودکار هنگام قطع",getSharedPreferences("persiansocks",MODE_PRIVATE).getBoolean("auto-switch",true));
        sidebarAuto.setOnCheckedChangeListener((b,on)->getSharedPreferences("persiansocks",MODE_PRIVATE).edit().putBoolean("auto-switch",on).apply());content.addView(sidebarAuto,sized(dp(48),0));
        sidebarAction(content,"اسپلیت تونل • انتخاب برنامه‌ها",() -> startActivity(new Intent(this,SplitTunnelActivity.class)));
        content.addView(text("اسکن",14,PRIMARY,true),margin(dp(12),0));
        sidebarScan=sidebarAction(content,"تعداد، نوع و مهلت اسکن",this::scanSettings);
        sidebarAction(content,"بازسنجی ۲۰ سریع‌ترین",this::fastPing);
        catalogInfo=text("",11,SECONDARY,false);content.addView(catalogInfo,margin(dp(4),0));
        content.addView(text("منابع و پروکسی",14,PRIMARY,true),margin(dp(12),0));
        sidebarAction(content,"مدیریت منابع",this::sourceDialog);
        sidebarAction(content,"افزودن پروکسی دستی",this::manualDialog);
        sidebarAction(content,"دامنه‌های تست",this::targetsDialog);
        content.addView(text("ابزارها و گزارش",14,PRIMARY,true),margin(dp(12),0));
        sidebarAction(content,"افزودن به پنل سریع",() -> QuickControls.requestTile(this));
        sidebarAction(content,"افزودن ویجت گوشی",() -> QuickControls.requestWidget(this));
        sidebarAction(content,"گزارش اسکن و اتصال",this::showReport);
        sidebarAction(content,"گزارش خطای برنامه",this::crashReport);
        if(DistributionPolicy.play())sidebarAction(content,"حریم خصوصی",()->VpnDisclosure.showPrivacy(this));
        sidebarAction(content,"دربارهٔ برنامه",this::aboutDialog);
        page.addView(sidebarOverlay,new FrameLayout.LayoutParams(-1,-1));
    }
    private Button sidebarAction(LinearLayout parent,String label,Runnable action) {
        Button choice = button(label,false); choice.setTextSize(13);
        choice.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL); choice.setPadding(dp(12),0,dp(12),0);
        choice.setOnClickListener(v -> { setSidebarVisible(false); action.run(); });
        parent.addView(choice,sized(dp(48),dp(6)));return choice;
    }
    private CheckBox sidebarCheck(String label,boolean checked){CheckBox box=new CheckBox(this);box.setText(label);box.setTextSize(13);box.setTextColor(Color.WHITE);box.setButtonTintList(ColorStateList.valueOf(PRIMARY));box.setChecked(checked);return box;}
    private void crashReport(){
        ProxyStore.IO.execute(()->{
            String report="خطای ثبت‌شده‌ای نیست.";
            try{java.io.File file=new java.io.File(getFilesDir(),"last-crash.txt");
                if(file.exists())report=new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8);}
            catch(Exception ex){report="خواندن گزارش ناموفق بود";}
            final String message=report;
            ui.post(()->{if(isDestroyed())return;new AlertDialog.Builder(this, R.style.AppDialogTheme).setTitle("گزارش خطای برنامه").setMessage(message)
                .setPositiveButton("کپی گزارش",(d,w)->{android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PersianSocks",message));})
                .setNegativeButton("بستن",null).show();});
        });
    }
    private void setSidebarVisible(boolean visible) {
        if (sidebarOverlay == null || sidebarVisible == visible) return;
        sidebarVisible = visible;
        sidebarOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        mainScroller.setImportantForAccessibility(visible ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        sidebarToggle.setText(visible ? "×" : "☰");
        sidebarToggle.setContentDescription(visible ? "پنهان کردن سایدبار" : "نمایش تنظیمات");
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
    private void pingMenu() {
        PopupMenu popup = new PopupMenu(this,pingButton);
        for (ProxyResults.PingLimit limit : ProxyResults.PingLimit.values())
            popup.getMenu().add(1,limit.ordinal()+1,limit.ordinal(),limit.label).setChecked(pingLimit == limit);
        popup.getMenu().setGroupCheckable(1,true,true);
        popup.setOnMenuItemClickListener(item -> {
            pingLimit=ProxyResults.PingLimit.values()[item.getItemId()-1]; 
            getSharedPreferences("persiansocks",MODE_PRIVATE).edit().putInt("ping-limit-ms",pingLimit.milliseconds).apply();
            if (ProxyVpnService.phase()==ProxyVpnService.DISCONNECTED && !vpnRequestPending) { userSelected=false; selectBest(); }
            render(); return true;
        });
        popup.show();
    }
    private void resetFilters() {
        filter="ALL"; protocol=DistributionPolicy.encryptedOnly() ? ProxyResults.Protocol.HTTP : ProxyResults.Protocol.ALL;
        pingLimit=ProxyResults.PingLimit.NONE; sort=ProxyResults.Sort.FASTEST; smoothResults=false;
        getSharedPreferences("persiansocks",MODE_PRIVATE).edit().putString("filter",filter)
            .putString("protocol-filter",protocol.name()).putInt("ping-limit-ms",0).putString("sort",sort.name()).putBoolean("smooth-results",false).apply();
        if (ProxyVpnService.phase()==ProxyVpnService.DISCONNECTED && !vpnRequestPending) { userSelected=false; selectBest(); }
        render();
    }
    private void rebuildFilters() {
        filterKeys.clear();
        for (ProxyResults.Filter option : ProxyResults.Filter.values()) filterKeys.add(option.name());
        for (ServiceTargets.Target target : customTargets) filterKeys.add(target.id);
    }
    private void serviceMenu() {
        PopupMenu popup = new PopupMenu(this, serviceButton);
        for (int i = 0; i < filterKeys.size(); i++) {
            String key = filterKeys.get(i);
            popup.getMenu().add(1, i + 1, i, ProxyResults.label(key)).setChecked(key.equals(filter));
        }
        popup.getMenu().setGroupCheckable(1, true, true);
        popup.getMenu().add(2, 100, filterKeys.size(), "+ دامنهٔ تست");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 100) { targetsDialog(); return true; }
            filter = filterKeys.get(item.getItemId() - 1);
            getSharedPreferences("persiansocks", MODE_PRIVATE).edit().putString("filter", filter).apply();
            if (ProxyVpnService.phase() == ProxyVpnService.DISCONNECTED && !vpnRequestPending &&
                (selected == null || !ProxyResults.accepts(selected, filter))) { userSelected = false; selectBest(); }
            render(); return true;
        });
        popup.show();
    }
    private void protocolMenu() {
        PopupMenu popup = new PopupMenu(this, protocolButton);
        for (ProxyResults.Protocol option : ProxyResults.Protocol.values()) {
            if (DistributionPolicy.play() && option != ProxyResults.Protocol.HTTP) continue;
            if (DistributionPolicy.encryptedOnly() && option == ProxyResults.Protocol.SOCKS) continue;
            String label = option == ProxyResults.Protocol.HTTP && DistributionPolicy.encryptedOnly() ? "HTTPS / TLS" : option.label;
            popup.getMenu().add(1, option.ordinal() + 1, option.ordinal(), label).setChecked(protocol == option);
        }
        popup.getMenu().setGroupCheckable(1, true, true);
        popup.setOnMenuItemClickListener(item -> {
            protocol = ProxyResults.Protocol.values()[item.getItemId() - 1];
            getSharedPreferences("persiansocks", MODE_PRIVATE).edit().putString("protocol-filter", protocol.name()).apply();
            if (ProxyVpnService.phase() == ProxyVpnService.DISCONNECTED && !vpnRequestPending) { userSelected = false; selectBest(); }
            render(); return true;
        });
        popup.show();
    }
    private void connectionSettings() {
        LinearLayout inputs = dialogContent(); inputs.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        CheckBox tls = new CheckBox(this); tls.setText("فقط پروکسی‌های TLS");
        tls.setTextColor(Color.WHITE); tls.setTextSize(14); tls.setButtonTintList(ColorStateList.valueOf(PRIMARY));
        tls.setChecked(DistributionPolicy.encryptedOnly()); tls.setEnabled(!DistributionPolicy.play());
        inputs.addView(tls);
        inputs.addView(text(DistributionPolicy.play() ? "این نسخه فقط اتصال TLS با گواهی معتبر را می‌پذیرد." :
            "پیش‌فرض خاموش است. روشن‌کردن آن، اسکن و اتصال را به TLS معتبر محدود می‌کند. تغییر این گزینه، اسکن و اتصال جاری را متوقف می‌کند.",
            12, SECONDARY, false), margin(dp(4), 0));
        CheckBox auto = new CheckBox(this); auto.setText("تعویض خودکار پروکسی");
        auto.setTextColor(Color.WHITE); auto.setTextSize(14); auto.setButtonTintList(ColorStateList.valueOf(PRIMARY));
        auto.setChecked(getSharedPreferences("persiansocks", MODE_PRIVATE).getBoolean("auto-switch", true));
        inputs.addView(auto, margin(dp(12), 0));
        inputs.addView(text("در صورت قطع اتصال، جایگزین طبق فیلتر فعلی انتخاب می‌شود.", 12, SECONDARY, false));
        ScrollView scroll = new ScrollView(this); scroll.addView(inputs);
        new AlertDialog.Builder(this, R.style.AppDialogTheme).setTitle("تنظیمات اتصال").setView(scroll)
            .setPositiveButton("ذخیره", (d, which) -> {
                getSharedPreferences("persiansocks", MODE_PRIVATE).edit().putBoolean("auto-switch", auto.isChecked()).apply();
                if (!DistributionPolicy.play() && tls.isChecked() != DistributionPolicy.encryptedOnly()) changeTlsMode(tls.isChecked());
                else Toast.makeText(this, "تنظیمات اتصال ذخیره شد", Toast.LENGTH_SHORT).show();
            }).setNegativeButton("لغو", null).show();
    }
    private void saveTargets() {
        cancelSingleTests();
        ServiceTargets.configure(customTargets);
        getSharedPreferences("persiansocks", MODE_PRIVATE).edit()
            .putString("test-domains", ServiceTargets.encode(customTargets)).putString("filter", filter).apply();
        rebuildFilters(); render();
    }
    private void targetsDialog() {
        List<String> items = new ArrayList<>(); items.add("+ افزودن دامنهٔ جدید");
        for (ServiceTargets.Target t : customTargets) items.add(t.name + "  •  " + t.host);
        new AlertDialog.Builder(this, R.style.AppDialogTheme).setTitle("دامنه‌های تست")
            .setItems(items.toArray(new String[0]), (d, index) -> {
                if (index == 0) { addTargetDialog(); return; }
                ServiceTargets.Target target = customTargets.get(index - 1);
                new AlertDialog.Builder(this, R.style.AppDialogTheme).setTitle(target.name).setMessage(target.url())
                    .setPositiveButton("حذف دامنه", (dialog, which) -> {
                        if (scanning||ProxyVpnService.autoScanning()) stopScan();
                        customTargets.remove(target);
                        if (filter.equals(target.id)) filter = "ALL";
                        saveTargets();
                        healthy.removeIf(p -> !p.hasAnyService());
                        ProxyStore.save(this, healthy); render();
                    }).setNegativeButton("بستن", null).show();
            }).setNegativeButton("بستن", null).show();
    }
    private void addTargetDialog() {
        if (customTargets.size() >= ServiceTargets.MAX_CUSTOM) {
            Toast.makeText(this, "حداکثر ۸ دامنهٔ دلخواه؛ برای افزودن، یکی را حذف کن", Toast.LENGTH_LONG).show(); return;
        }
        LinearLayout inputs = dialogContent();
        EditText name = new EditText(this); name.setHint("نام سرویس، مثلاً فیسبوک"); name.setSingleLine(true);
        EditText url = new EditText(this); url.setHint("facebook.com یا https://www.facebook.com/");
        url.setSingleLine(true); url.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
            android.text.InputType.TYPE_TEXT_VARIATION_URI); url.setTextDirection(View.TEXT_DIRECTION_LTR);
        inputs.addView(name); inputs.addView(url);
        inputs.addView(text("HTTPS با گواهی معتبر و پاسخ ۲۰۰ تا ۳۹۹ بررسی می‌شود. پس از ثبت، نتایج موجود دوباره تست می‌شوند.",
            12, SECONDARY, false), margin(dp(10), 0));
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppDialogTheme).setTitle("افزودن دامنهٔ تست")
            .setView(inputs).setPositiveButton("ثبت و تست", null).setNegativeButton("لغو", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            ServiceTargets.Target target;
            try { target = ServiceTargets.custom(name.getText().toString(), url.getText().toString()); }
            catch (IllegalArgumentException ex) { url.setError(ex.getMessage()); return; }
            for (ServiceTargets.Target existing : ServiceTargets.current()) if (existing.url().equals(target.url())) {
                url.setError("این نشانی قبلاً اضافه شده"); return;
            }
            if (scanning||ProxyVpnService.autoScanning()) stopScan();
            boolean haveSaved = !healthy.isEmpty();
            customTargets.add(target); filter = target.id; saveTargets(); dialog.dismiss(); if (haveSaved) doScan(true); else status.setText("دامنه اضافه شد؛ لیست را دریافت و اسکن کن");
        }));
        dialog.show();
    }
    private void aboutDialog() {
        TextView info = text("PersianSocks 0.22.0\n\nکشور، موقعیت تقریبی سرور پروکسی است.\n" +
            "IP Geolocation by DB-IP\nhttps://db-ip.com\n" +
            "DB-IP Country Lite، اکتبر ۲۰۲۶\nمجوز CC BY 4.0\nhttps://creativecommons.org/licenses/by/4.0/\n" +
            "دادهٔ CSV به جدول IPv4 فشرده تبدیل شده است.\n\nموتور تونل: hev-socks5-tunnel، مجوز MIT",
            13, Color.WHITE, false);
        info.setPadding(dp(20), dp(16), dp(20), dp(16)); info.setTextIsSelectable(true);
        android.text.util.Linkify.addLinks(info, android.text.util.Linkify.WEB_URLS);
        info.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        new AlertDialog.Builder(this, R.style.AppDialogTheme).setTitle("دربارهٔ برنامه").setView(info).setPositiveButton("بستن", null).show();
    }
    private void sortMenu() {
        PopupMenu popup = new PopupMenu(this, sortButton);
        for (ProxyResults.Sort option : ProxyResults.Sort.values())
            popup.getMenu().add(1, option.ordinal() + 1, option.ordinal(), option.label).setChecked(sort == option);
        popup.getMenu().setGroupCheckable(1, true, true);
        // Combined low-ping / fast-download is a sorting choice, not a service filter.
        popup.getMenu().add(2, 101, ProxyResults.Sort.values().length, "پینگ کم / دانلود سریع")
            .setCheckable(true).setChecked(smoothResults);
        popup.setOnMenuItemClickListener(item -> {
            if(item.getItemId()==101){
                smoothResults=!smoothResults;
                getSharedPreferences("persiansocks",MODE_PRIVATE).edit().putBoolean("smooth-results",smoothResults).apply();
            }else{
                sort = ProxyResults.Sort.values()[item.getItemId() - 1];
                smoothResults=false;
                getSharedPreferences("persiansocks", MODE_PRIVATE).edit()
                    .putString("sort",sort.name()).putBoolean("smooth-results",false).apply();
            }
            if (!userSelected && ProxyVpnService.phase() == ProxyVpnService.DISCONNECTED && !vpnRequestPending) selectBest();
            render(); return true;
        });
        popup.show();
    }
    private String sortLabel() {
        if(smoothResults)return "پینگ کم / دانلود سریع";
        switch (sort) {
            case DOWNLOAD: return "دانلود سریع";
            case MOST_SERVICES: return "بیشترین سرویس";
            case RECENT: return "جدیدترین تست";
            case UDP_FIRST: return "اولویت UDP";
            default: return "پینگ کم";
        }
    }
    private void selectBest() { userSelected=false; renderedRevision=-1; scheduleRender(); }
    private void scheduleRender() {
        if(redrawQueued.compareAndSet(false,true))ui.postDelayed(()->{redrawQueued.set(false);if(!isDestroyed())render();},1000);
    }
    private void updateActions() {
        if (scanToggle == null) return;
        boolean auto=ProxyVpnService.autoScanning(), anyScan=scanning||auto;
        scanToggle.setText(anyScan ? "توقف اسکن" : "اسکن  ▾");
        scanToggle.setEnabled(!collecting && !loading && !preparing && !stopping);
        collectButton.setEnabled(!anyScan && !loading && !preparing && !stopping);
        collectButton.setText(collecting ? "توقف دریافت" : "دریافت لیست");
        if (!collecting) listStatus.setText("لیست ذخیره‌شده: " + catalogCount + " پروکسی");
        listStatus.setVisibility(collecting ? View.VISIBLE : View.GONE);
        status.setVisibility(collecting ? View.GONE : View.VISIBLE);
        if (catalogInfo != null) catalogInfo.setText("لیست ذخیره‌شده: " + catalogCount + " پروکسی");
        scanToggle.setTextColor(anyScan ? DANGER : Color.WHITE);
        scanProgress.setVisibility(anyScan || collecting ? View.VISIBLE : View.GONE);
        if(auto){
            int progress=ProxyVpnService.autoProgress();
            scanProgress.setIndeterminate(progress<0);scanProgress.setProgress(Math.max(0,progress));
        }
        int phase = vpnRequestPending ? ProxyVpnService.CONNECTING : ProxyVpnService.phase();
        boolean active = phase != ProxyVpnService.DISCONNECTED;
        vpnToggle.showState(phase==ProxyVpnService.CONNECTED,phase==ProxyVpnService.CONNECTING);
        vpnBadge.setText(phase==ProxyVpnService.CONNECTED?"●  متصل":"●  VPN");
        vpnBadge.setTextColor(phase==ProxyVpnService.CONNECTED?PRIMARY:phase==ProxyVpnService.CONNECTING?WARNING:SECONDARY);
        vpnStatus.setText(connectionError);vpnStatus.setTextColor(DANGER);vpnStatus.setVisibility(connectionError.isEmpty()?View.GONE:View.VISIBLE);
        if(sidebarScan!=null)sidebarScan.setText("اسکن: "+(scanAll?"Max":maxTests)+" • تنظیمات");
        String current = active ? ProxyVpnService.activeEndpoint() : "";
        String address = selected != null ? selected.key() : current;
        boolean other = !current.isEmpty() && !current.equals(address);
        activeProfile.setVisibility(other ? View.VISIBLE : View.GONE);
        activeProfile.setText(other ? "اتصال VPN فعلی: " + current : "");
        ProxyEndpoint displayed = address.isEmpty() ? null : ProxyEndpoint.parse(address);
        selection.setText(displayed == null ? (address.isEmpty() ? "انتخاب نشده" : address) : displayed.address());
        selectionProtocol.setText(displayed == null ? "—" : displayed.type.name());
        selection.setContentDescription(address.isEmpty() ? "پروکسی انتخاب نشده" : address);
        boolean busy = speedTest != null || singleTest != null || speedBatch != null || preparingSpeedBatch;
        speedButton.setText(speedTest != null ? "توقف سرعت" : "تست سرعت");
        probeButton.setText(singleTest != null ? "توقف تست" : "تست اتصال");
        speedButton.setEnabled(speedBatch == null && !preparingSpeedBatch && (speedTest != null || (selected != null && !busy)));
        probeButton.setEnabled(singleTest != null || (selected != null && !busy));
        speedButton.setAlpha(speedButton.isEnabled() ? 1f : .45f);
        probeButton.setAlpha(probeButton.isEnabled() ? 1f : .45f);
        if(filteredSpeedButton!=null){
            filteredSpeedButton.setText(speedBatch!=null?"توقف تست سرعت • "+speedBatch.done()+" / "+speedBatch.total():preparingSpeedBatch?"توقف آماده‌سازی…":"تست سرعت همهٔ نتایج فیلترشده");
            filteredSpeedButton.setEnabled(speedBatch!=null||preparingSpeedBatch||(!busy&&!healthy.isEmpty()));
            filteredSpeedButton.setAlpha(filteredSpeedButton.isEnabled()?1f:.45f);
        }
        collectButton.setContentDescription("دریافت لیست؛ " + catalogCount + " پروکسی ذخیره‌شده");
    }
    private void stopScan() {
        if(stopping)return;
        if(ProxyVpnService.autoScanning()){ProxyVpnService.cancelAutoScanFromUi();status.setText("در حال ذخیرهٔ پیشرفت…");return;}
        scanGeneration++;scanning=false;stopping=true;preparedScanner.requestStop();updateActions();
        ProxyStore.IO.execute(()->{
            String message;ScanCheckpoint paused=null;
            try{paused=preparedScanner.pause();if(paused!=null)healthy.apply(paused);ProxyStore.save(this,healthy);
                message=paused==null?"اسکن متوقف شد":"متوقف: "+paused.done()+" از "+paused.total()+" • ادامه ذخیره شد";}
            catch(Exception ex){message="ذخیرهٔ پیشرفت ناموفق: "+ex.getMessage();}
            final ScanCheckpoint saved=paused;final String note=message;
            ui.post(()->{if(isDestroyed())return;stopping=false;if(saved!=null)checkpoint=saved;
                saveTestResults();saveReport();status.setText(note);render();});
        });
    }
    private void scanMenu() {
        PopupMenu popup = new PopupMenu(this, scanToggle);
        if (checkpoint != null && !checkpoint.complete() && checkpoint.compatible())
            popup.getMenu().add(0, 1, 0, "ادامهٔ اسکن (" + checkpoint.done() + " از " + checkpoint.total() + ")");
        popup.getMenu().add(0, 2, 1, "اسکن لیست از ابتدا");
        popup.getMenu().add(0, 3, 2, "اسکن مجدد همهٔ سالم‌ها (" + healthy.size() + ")");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) runPrepared(checkpoint);
            else if (item.getItemId() == 2) doScan(false);
            else if (item.getItemId() == 3) doScan(true);
            return true;
        }); popup.show();
    }
    private void stopCollection() {
        collectGeneration++; collecting = false; scanner.stop();
        updateActions(); status.setText("دریافت متوقف شد؛ لیست قبلی حفظ شد");
    }
    private void collectList() {
        if (scanning || ProxyVpnService.autoScanning()) { Toast.makeText(this, "ابتدا اسکن را متوقف کن", Toast.LENGTH_SHORT).show(); return; }
        collecting = true; final int token = ++collectGeneration; scanProgress.setIndeterminate(true); updateActions();
        listStatus.setText("در حال دریافت لیست…");
        List<ProxyEndpoint> previous = new ArrayList<>(manual); previous.addAll(healthy);
        ProxyStore.IO.execute(()->{if(!collecting||token!=collectGeneration)return;scanner.collect(previous, loadBootstrap(), configuredSources(), new ProxyScanner.CollectionListener() {
            public void onProgress(String message) { ui.post(() -> { if (collecting && token == collectGeneration) listStatus.setText(message); }); }
            public void onFailed(String message) { ui.post(() -> { if (token == collectGeneration) { collecting = false; updateActions(); status.setText(message); } }); }
            public void onFinished(List<ProxyEndpoint> received, String report) {
                try{scanFiles.saveCatalog(received);}catch(Exception ex){onFailed("ذخیرهٔ لیست ناموفق: "+ex.getMessage());return;}
                final int count=received.size();
                ui.post(()->{if(!collecting||token!=collectGeneration||isDestroyed())return;
                    collecting=false;catalogCount=count;
                    getSharedPreferences("persiansocks",MODE_PRIVATE).edit().putString("collection-report",report).apply();
                    updateActions();status.setText(count+" پروکسی دریافت شد؛ آمادهٔ اسکن");});
            }
        });});
    }
    private void disconnect() {
        Intent i = new Intent(this, ProxyVpnService.class).setAction(ProxyVpnService.DISCONNECT);
        try { vpnRequestPending=true;connectionError="";startService(i); }
        catch (Exception e) { connectionError="امکان قطع سرویس نیست: "+e.getMessage();vpnRequestPending=false; }
        updateActions();
    }

    private void doScan(boolean savedOnly) {
        if(scanning||collecting||preparing||stopping||loading)return;
        preparing=true;updateActions();status.setText("آماده‌سازی اسکن…");
        final ProxyScanner.Options options=savedOnly?new ProxyScanner.Options(maxTests,slowScan,true,7):new ProxyScanner.Options(maxTests,slowScan,scanAll,scanTypes);
        ProxyStore.IO.execute(()->{
            ScanCheckpoint work=null;String error="";
            try{List<ProxyEndpoint> input=savedOnly?healthy.snapshot():scanFiles.catalog();
                if(input.isEmpty())error=savedOnly?"پروکسی سالمی ذخیره نشده":"اول «دریافت لیست» را بزن";
                else work=new ScanCheckpoint(input,options,savedOnly?"HEALTHY":"CATALOG");}
            catch(Exception ex){error=ex.getMessage();}
            final ScanCheckpoint ready=work;final String note=error;
            ui.post(()->{if(isDestroyed())return;preparing=false;if(ready==null){status.setText(note);updateActions();}else runPrepared(ready);});
        });
    }
    private void fastPing() {
        if(scanning||collecting||loading||preparing||stopping){Toast.makeText(this,"ابتدا عملیات جاری را متوقف کن",Toast.LENGTH_SHORT).show();return;}
        if(ranked.isEmpty()){Toast.makeText(this,"پروکسی سالمی با فیلتر فعلی نیست",Toast.LENGTH_SHORT).show();return;}
        sort=ProxyResults.Sort.FASTEST;getSharedPreferences("persiansocks",MODE_PRIVATE).edit().putString("sort",sort.name()).apply();
        preparing=true;updateActions();status.setText("بازسنجی سریع‌ترین پروکسی‌ها…");
        final String service=filter;final ProxyResults.Protocol kind=protocol;final ProxyResults.PingLimit limit=pingLimit;final boolean slow=slowScan;
        final List<ProxyEndpoint> snapshot=healthy.snapshot();
        rankWorker.execute(()->{
            List<ProxyEndpoint> choices=ProxyResults.visible(snapshot,service,ProxyResults.Sort.FASTEST,kind,limit);
            ScanCheckpoint work=new ScanCheckpoint(new ArrayList<>(choices.subList(0,Math.min(20,choices.size()))),new ProxyScanner.Options(100,slow,true,7),"LATENCY");
            ui.post(()->{if(isDestroyed())return;preparing=false;runPrepared(work);});
        });
    }
    private void runPrepared(ScanCheckpoint work) {
        if(work==null||work.total()==0){status.setText("پروکسی سازگاری برای اسکن نیست");return;}
        if(scanning||collecting||preparing||stopping||loading||ProxyVpnService.autoScanning())return;
        if(!work.compatible()){status.setText("تنظیمات تست تغییر کرده؛ اسکن را از ابتدا شروع کن");return;}
        PhysicalTestNetwork network;
        try{network=PhysicalTestNetwork.capture(this);}catch(Exception ex){status.setText(ex.getMessage());return;}
        checkpoint=work;scanFiles.activate(work);scanning=true;final int token=++scanGeneration;scanProgress.setIndeterminate(false);
        status.setText("اسکن: "+work.done()+" از "+work.total());render();
        final String latencyFilter=filter;
        PreparedScanner.Listener listener=new PreparedScanner.Listener(){
            public void onResult(int index,Socks5Probe.Result result){
                if(!scanning||token!=scanGeneration)return;
                if(result.endpoint!=null)healthy.accept(result.endpoint);
                else if(result.failure!=Socks5Probe.Failure.CANCELLED && healthy.find(work.candidate(index).key())!=null)
                    healthy.reject(work.candidate(index).key(),work.finishedAt(index));
            }
            public void onProgress(int done,int total,int good){ui.post(()->{
                if(!scanning||token!=scanGeneration||isDestroyed())return;
                status.setText("بررسی "+done+" از "+total+" • سالم تازه: "+good);
                scanProgress.setProgress(total==0?0:(int)(100L*done/total));
                scanReport=work.mode+"\n"+done+" از "+total+"\nسالم تازه: "+good;scheduleRender();});}
            public void onFinished(ScanCheckpoint state,String message){
                if(token!=scanGeneration)return;healthy.apply(state);ProxyStore.save(MainActivity.this,healthy);
                ProxyStore.IO.execute(()->{boolean committed=false;try{ProxyStore.flush(MainActivity.this);if(state.complete())scanFiles.discardComplete();committed=true;}catch(Exception ex){android.util.Log.e("PersianSocks","Final cache commit failed",ex);}
                    final boolean saved=committed;
                    ui.post(()->{if(!scanning||token!=scanGeneration||isDestroyed())return;scanning=false;
                        checkpoint=state.complete()&&saved?null:state;saveTestResults();saveReport();status.setText(saved?message:"ذخیرهٔ نهایی ناموفق؛ پیشرفت اسکن حفظ شد");render();});});
            }
        };
        try {
            if(work.mode.equals("LATENCY"))preparedScanner.start(work,scanFiles::save,(p,slow,cancel,sockets)->{
                ProxyEndpoint current=healthy.find(p.key());
                if(current==null)return Socks5Probe.inspect(p,slow,cancel,sockets,network::socket,network::resolve);
                Socks5Probe.Result measured=Socks5Probe.inspectTarget(current,SingleProxyTest.latencyTarget(current,latencyFilter),slow,cancel,sockets,network::socket,network::resolve);
                return measured.endpoint==null?measured:new Socks5Probe.Result(current.withLatency(measured.endpoint.latencyMs),Socks5Probe.Failure.NONE,true,true);
            },listener);
            else preparedScanner.start(work,scanFiles::save,network::socket,network::resolve,listener);
        }catch(Exception ex){scanning=false;status.setText("شروع اسکن ناموفق: "+ex.getMessage());render();}
    }
    private void reloadSaved() {
        if(loading||scanning||preparing||stopping)return;loading=true;updateActions();
        ProxyStore.IO.execute(()->{
            ScanCheckpoint saved=null;int count=0;String error="";
            try{ProxyStore.load(this);count=scanFiles.catalogCount();saved=scanFiles.checkpoint();
                if(saved!=null&&saved.compatible())healthy.apply(saved);
                if(saved!=null&&saved.complete()){ProxyStore.save(this,healthy);scanFiles.discardComplete();saved=null;}}
            catch(Exception ex){error=String.valueOf(ex.getMessage());}
            final ScanCheckpoint restored=saved;final int storedCount=count;final String failure=error;
            ui.post(()->{if(isDestroyed())return;loading=false;checkpoint=restored;catalogCount=storedCount;
                scanReport=getSharedPreferences("persiansocks",MODE_PRIVATE).getString("scan-report",scanReport);
                if(!failure.isEmpty())status.setText("بازیابی ناموفق: "+failure);
                else status.setText(restored!=null&&!restored.complete()?"ادامهٔ اسکن: "+restored.done()+" از "+restored.total():storedCount+" پروکسی آمادهٔ اسکن");
                ProxyEndpoint active=healthy.find(ProxyVpnService.activeEndpoint());if(active!=null){selected=active;userSelected=true;}render();});
        });
    }
    private boolean hasSelected(){return selected!=null&&(healthy.find(selected.key())!=null||favorites.containsKey(selected.key()));}
    private String criteria(){return filter+"|"+protocol+"|"+sort+"|"+pingLimit+"|"+smoothResults+"|"+measurementRevision+"|"+showFavorites+"|"+favoritesRevision;}
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
            Toast.makeText(this,"این پروکسی قبلاً ذخیره شده",Toast.LENGTH_SHORT).show();return;
        }
        favorites.put(proxy.key(),proxy);
        if(!FavoriteProxies.save(this,favorites.values())){
            favorites.remove(proxy.key());
            Toast.makeText(this,"ذخیرهٔ علاقه‌مندی ناموفق بود",Toast.LENGTH_LONG).show();return;
        }
        favoritesRevision++;render();Toast.makeText(this,"به علاقه‌مندی‌ها اضافه شد",Toast.LENGTH_SHORT).show();
    }
    private void confirmRemoveFavorite(ProxyEndpoint proxy){
        if(proxy==null||!favorites.containsKey(proxy.key()))return;
        new AlertDialog.Builder(this,R.style.AppDialogTheme)
            .setTitle("حذف از علاقه‌مندی‌ها")
            .setMessage("فقط این پروکسی از فهرست ذخیره‌شده حذف شود؟")
            .setPositiveButton("حذف", (dialog,which)->{
                ProxyEndpoint saved=favorites.remove(proxy.key());
                if(!FavoriteProxies.save(this,favorites.values())){
                    favorites.put(saved.key(),saved);
                    Toast.makeText(this,"حذف ذخیره نشد",Toast.LENGTH_LONG).show();return;
                }
                favoritesRevision++;render();
            }).setNegativeButton("لغو",null).show();
    }
    private Map<String,Double> speedSnapshot(){
        Map<String,Double> speeds=new LinkedHashMap<>();
        for(Map.Entry<String,ProxyTestResult> entry:testResults.entrySet())
            speeds.put(entry.getKey(),entry.getValue().speedError.isEmpty()?entry.getValue().downloadMBps:-1.0);
        return speeds;
    }
    private void render() {
        if(results==null||isDestroyed())return;
        testResults.keySet().removeIf(key->healthy.rejected(key)&&!favorites.containsKey(key));
        manual.removeIf(p->healthy.rejected(p.key()));
        if(selected!=null&&healthy.rejected(selected.key())&&!favorites.containsKey(selected.key())){selected=null;userSelected=false;}
        if(selected!=null){ProxyEndpoint latest=healthy.find(selected.key());if(latest!=null)selected=latest;}
        String kindLabel = protocol == ProxyResults.Protocol.HTTP && DistributionPolicy.encryptedOnly() ? "HTTPS / TLS" : protocol.label;
        boolean filtered = !filter.equals("ALL") || protocol != ProxyResults.Protocol.ALL || pingLimit != ProxyResults.PingLimit.NONE;
        filterSummary.setText(ProxyResults.label(filter) + "  •  " + kindLabel +
            (pingLimit == ProxyResults.PingLimit.NONE ? "" : "  •  " + pingLimit.label));
        filterSummary.setVisibility(View.GONE);
        sortButton.setText(sortLabel() + "  ▾");
        sortButton.setContentDescription("مرتب‌سازی: " + (smoothResults?"پینگ کم / دانلود سریع":sort.label));
        sortButton.setEnabled(true);
        serviceButton.setText("سرویس: "+ProxyResults.label(filter) + "  ▾");
        serviceButton.setContentDescription("فیلتر سرویس: " + ProxyResults.label(filter));
        serviceButton.setTextColor(filter.equals("ALL") ? Color.WHITE : PRIMARY);
        serviceButton.setBackground(background(SURFACE, dp(12), filter.equals("ALL") ? STROKE : Color.rgb(44, 100, 91)));
        protocolButton.setText("نوع: "+kindLabel + "  ▾");
        protocolButton.setContentDescription("فیلتر نوع پروکسی: " + kindLabel);
        protocolButton.setTextColor(protocol == ProxyResults.Protocol.ALL ? Color.WHITE : PRIMARY);
        protocolButton.setBackground(background(SURFACE, dp(12), protocol == ProxyResults.Protocol.ALL ? STROKE : Color.rgb(44, 100, 91)));
        pingButton.setText("پینگ: "+(pingLimit==ProxyResults.PingLimit.NONE?"همه":pingLimit.label.replace("میلی‌ثانیه","ms"))+"  ▾");
        pingButton.setContentDescription("محدودیت پینگ: " + pingLimit.label);
        pingButton.setTextColor(pingLimit == ProxyResults.PingLimit.NONE ? Color.WHITE : PRIMARY);
        pingButton.setBackground(background(SURFACE,dp(12),pingLimit == ProxyResults.PingLimit.NONE ? STROKE : Color.rgb(44,100,91)));
        favoritesButton.setText(showFavorites?"← همهٔ پروکسی‌ها":"★ علاقه‌مندی‌ها ("+favorites.size()+")");
        favoritesButton.setContentDescription(showFavorites?"بازگشت به همهٔ پروکسی‌ها":"نمایش پروکسی‌های ذخیره‌شده");
        filterRow.setVisibility(showFavorites?View.GONE:View.VISIBLE);
        limitRow.setVisibility(showFavorites?View.GONE:View.VISIBLE);
        filteredSpeedButton.setVisibility(showFavorites?View.GONE:View.VISIBLE);
        updateActions();
        if(renderedRevision==healthy.revision()&&renderedCriteria.equals(criteria())){resultAdapter.notifyDataSetChanged();return;}
        if(ranking)return;
        ranking=true;final String requested=criteria();final String service=filter;
        final ProxyResults.Sort order=sort;final ProxyResults.Protocol kind=protocol;final ProxyResults.PingLimit limit=pingLimit;
        final long revision=healthy.revision();
        final boolean favoritesMode=showFavorites;
        final List<ProxyEndpoint> snapshot=favoritesMode?favoriteSnapshots():healthy.snapshot();
        final Map<String,Double> speeds=speedSnapshot();final boolean smooth=smoothResults;
        rankWorker.execute(()->{
            List<ProxyEndpoint> visible=favoritesMode?snapshot:ProxyResults.visible(snapshot,service,order,kind,limit,speeds,smooth);
            ui.post(()->{
                if(isDestroyed())return;ranking=false;
                if(!requested.equals(criteria())){render();return;}
                ranked=visible;renderedRevision=revision;renderedCriteria=requested;
                if(!userSelected&&!favoritesMode&&ProxyVpnService.phase()==ProxyVpnService.DISCONNECTED&&!vpnRequestPending)
                    selected=visible.isEmpty()?null:visible.get(0);
                counter.setText(visible.size()+" نتیجه");
                emptyState.removeAllViews();emptyState.setVisibility(visible.isEmpty()?View.VISIBLE:View.GONE);
                if(visible.isEmpty()){
                    if(favoritesMode){
                        emptyState.addView(text("هنوز پروکسی مورد علاقه‌ای ذخیره نکردی",16,Color.WHITE,true));
                        emptyState.addView(text("به فهرست اصلی برگرد و ستارهٔ کنار پروکسی را بزن.",12,SECONDARY,false),margin(dp(8),0));
                    }else{
                        emptyState.addView(text(!healthy.isEmpty()?"نتیجه‌ای با این فیلترها نیست":loading?"در حال بارگذاری…":scanning?"در حال جستجو…":"هنوز نتیجه‌ای نیست",16,Color.WHITE,true));
                        emptyState.addView(text(!healthy.isEmpty()?smoothResults?"در منوی مرتب‌سازی، «پینگ کم / دانلود سریع» را خاموش کن و تست سرعت بگیر؛ سپس دوباره روشنش کن.":"از سایدبار فیلترها را تغییر بده.":"لیست را دریافت و اسکن کن؛ پروکسی‌های نامعتبر حذف می‌شوند.",12,SECONDARY,false),margin(dp(8),0));
                    }
                }
                resultAdapter.notifyDataSetChanged();updateActions();
                if(revision!=healthy.revision())scheduleRender();
            });
        });
    }
    private final class ResultAdapter extends BaseAdapter {
        public int getCount(){return ranked.size();}
        public ProxyEndpoint getItem(int position){return ranked.get(position);}
        public long getItemId(int position){return position;}
        public View getView(int position,View recycled,ViewGroup parent){
            CardHolder holder;
            if(recycled==null){holder=new CardHolder();recycled=holder.wrapper;recycled.setTag(holder);}
            else holder=(CardHolder)recycled.getTag();
            holder.bind(getItem(position));return recycled;
        }
    }
    private final class CardHolder {
        final LinearLayout wrapper=new LinearLayout(MainActivity.this),row=new LinearLayout(MainActivity.this),services=new LinearLayout(MainActivity.this);
        final TextView address=text("",15,Color.WHITE,true),badge=chip("منتخب",PRIMARY,Color.rgb(22,75,68)),detail=text("",12,SECONDARY,false);
        final TextView down=metricValue("",SECONDARY),ping=metricValue("",Color.WHITE);
        final List<TextView> serviceViews=new ArrayList<>();
        final View divider=new View(MainActivity.this);
        ProxyEndpoint endpoint;String targetSet="";
        Button favoriteButton;
        CardHolder(){
            wrapper.setOrientation(LinearLayout.VERTICAL);wrapper.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            wrapper.setPadding(dp(12),dp(4),dp(12),dp(4));
            row.setOrientation(LinearLayout.VERTICAL);row.setPadding(dp(9),dp(6),dp(9),dp(6));wrapper.addView(row);
            LinearLayout top=horizontal();address.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);address.setTextDirection(View.TEXT_DIRECTION_LTR);
            address.setMaxLines(1);address.setEllipsize(android.text.TextUtils.TruncateAt.END);
            address.setAutoSizeTextTypeUniformWithConfiguration(12,16,1,android.util.TypedValue.COMPLEX_UNIT_SP);
            top.addView(address,new LinearLayout.LayoutParams(0,-2,1));top.addView(badge);
            Button star=button("☆",false);star.setTextSize(19);
            star.setOnClickListener(v->{
                if(endpoint==null)return;
                if(showFavorites)confirmRemoveFavorite(endpoint);else addFavorite(endpoint);
            });
            LinearLayout.LayoutParams starCell=new LinearLayout.LayoutParams(dp(42),dp(40));
            starCell.setMarginStart(dp(4));top.addView(star,starCell);
            favoriteButton=star;
            Button menu=button("⋮",false);menu.setTextSize(22);menu.setContentDescription("تست‌ها و جزئیات پروکسی");
            menu.setOnClickListener(v->{if(endpoint!=null)proxyMenu(endpoint,menu);});
            LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(dp(36),dp(40));cell.setMarginStart(dp(4));top.addView(menu,cell);row.addView(top);
            row.addView(detail,margin(dp(2),0));services.setOrientation(LinearLayout.VERTICAL);row.addView(services,margin(dp(5),0));
            row.addView(divider,sized(dp(1),dp(6)));
            LinearLayout metrics=horizontal();metrics.addView(resultColumn("سرعت دانلود:",down),new LinearLayout.LayoutParams(0,-2,1.5f));
            LinearLayout.LayoutParams pingCell=new LinearLayout.LayoutParams(0,-2,1);pingCell.setMarginStart(dp(8));metrics.addView(resultColumn("پینگ:",ping),pingCell);row.addView(metrics,margin(dp(5),0));
            row.setOnClickListener(v->{if(endpoint!=null){selected=endpoint;userSelected=true;render();}});
            row.setOnLongClickListener(v->{if(endpoint!=null)proxyDetails(endpoint);return true;});
            // Horizontal gestures only; vertical movement stays with the scrolling ListView.
            row.setOnTouchListener(new View.OnTouchListener(){
                float startX,startY;boolean horizontal;
                @Override public boolean onTouch(View v,android.view.MotionEvent event){
                    int action=event.getActionMasked();
                    if(action==android.view.MotionEvent.ACTION_DOWN){
                        startX=event.getRawX();startY=event.getRawY();horizontal=false;return false;
                    }
                    float dx=event.getRawX()-startX,dy=event.getRawY()-startY;
                    if(action==android.view.MotionEvent.ACTION_MOVE &&
                        Math.abs(dx)>dp(24)&&Math.abs(dx)>Math.abs(dy)*1.3f){
                        horizontal=true;v.getParent().requestDisallowInterceptTouchEvent(true);return true;
                    }
                    if(action==android.view.MotionEvent.ACTION_UP && horizontal){
                        horizontal=false;
                        if(Math.abs(dx)>dp(64)&&Math.abs(dx)>Math.abs(dy)*1.3f&&endpoint!=null){
                            if(dx<0)startSpeedTest(endpoint);else startProxyProbe(endpoint,true);
                        }
                        v.setPressed(false);return true;
                    }
                    if(action==android.view.MotionEvent.ACTION_CANCEL){horizontal=false;return false;}
                    return horizontal;
                }
            });
        }
        void bind(ProxyEndpoint p){
            endpoint=p;boolean active=selected!=null&&selected.key().equals(p.key());
            row.setBackground(background(active?Color.rgb(23,54,57):SURFACE,dp(14),active?PRIMARY:STROKE));
            address.setText(p.address());badge.setVisibility(active?View.VISIBLE:View.GONE);
            boolean saved=favorites.containsKey(p.key());
            favoriteButton.setText(showFavorites?"حذف":saved?"★":"☆");
            favoriteButton.setTextSize(showFavorites?11:19);
            favoriteButton.setTextColor(saved?PRIMARY:SECONDARY);
            favoriteButton.setContentDescription(showFavorites?"حذف از علاقه‌مندی‌ها":saved?"قبلاً به علاقه‌مندی‌ها اضافه شده":"افزودن به علاقه‌مندی‌ها");
            row.setContentDescription(p.address()+"؛ کشیدن به چپ: تست سرعت؛ کشیدن به راست: پینگ");
            String country=p.countryCode.isEmpty()?CountryLookup.lookup(p.host):p.countryCode;
            detail.setText(CountryLookup.display(country)+"  •  "+p.type.name());detail.setTextColor(active?PRIMARY:SECONDARY);
            String shape=ScanCheckpoint.fingerprint();
            if(!shape.equals(targetSet)){
                targetSet=shape;services.removeAllViews();serviceViews.clear();LinearLayout line=null;int count=0;
                for(ServiceTargets.Target target:ServiceTargets.current()){
                    if(count++%3==0){line=horizontal();services.addView(line,margin(serviceViews.isEmpty()?0:dp(6),0));}
                    TextView chip=chip("",SECONDARY,SURFACE);chip.setPadding(dp(4),dp(2),dp(4),dp(2));
                    LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(0,-2,1);cell.setMarginEnd(dp(5));line.addView(chip,cell);serviceViews.add(chip);
                }
            }
            int index=0;for(ServiceTargets.Target target:ServiceTargets.current()){
                boolean ok=p.passed(target);TextView chip=serviceViews.get(index++);
                chip.setText((ok?"✓ ":p.checked(target)?"× ":"• ")+target.name);chip.setTextColor(ok?PRIMARY:SECONDARY);
                int fill=ok?Color.rgb(22,68,62):Color.rgb(32,42,56);chip.setBackground(background(fill,dp(9),fill));
                chip.setContentDescription(target.name+(ok?" تأیید شد":p.checked(target)?" تأیید نشد":" تست نشده"));
            }
            ProxyTestResult measured=testResults.get(p.key());if(measured==null)measured=new ProxyTestResult();
            boolean running=testingProxy!=null&&testingProxy.key().equals(p.key());boolean downloadRunning=running&&speedTest!=null,probeRunning=running&&singleTest!=null;
            setMetricValue(down,downloadRunning?liveDownloadMBps>=0?ProxyTestResult.speed(liveDownloadMBps):"در حال تست…":measured.speedValue());
            down.setTextColor(downloadRunning||measured.downloadMBps>=0?PRIMARY:SECONDARY);
            setMetricValue(ping,probeRunning?"در حال تست…":measured.pingValue(p.latencyMs));divider.setBackgroundColor(active?Color.rgb(44,88,88):STROKE);
        }
    }
    private void proxyMenu(ProxyEndpoint p, View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "انتخاب پروکسی");
        popup.getMenu().add(0, 2, 1, "تست اتصال فقط این پروکسی");
        popup.getMenu().add(0, 3, 2, "تأخیر مجدد");
        popup.getMenu().add(0, 4, 3, "تست سرعت دانلود (تا ۲۰ MB)");
        popup.getMenu().add(0, 5, 4, "جزئیات");
        if(showFavorites&&favorites.containsKey(p.key()))popup.getMenu().add(0,7,5,"حذف از علاقه‌مندی‌ها");
        else if(!favorites.containsKey(p.key()))popup.getMenu().add(0,8,5,"افزودن به علاقه‌مندی‌ها");
        if (testingProxy != null && testingProxy.key().equals(p.key())) popup.getMenu().add(0, 6, 5, "توقف تست این پروکسی");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: userSelected = true; selected = p; render(); break;
                case 2: startProxyProbe(p, false); break;
                case 3: startProxyProbe(p, true); break;
                case 4: startSpeedTest(p); break;
                case 5: proxyDetails(p); break;
                case 6: cancelSingleTests(); render(); break;
                case 7: confirmRemoveFavorite(p); break;
                case 8: addFavorite(p); break;
            }
            return true;
        });
        popup.show();
    }
    private void proxyDetails(ProxyEndpoint p) {
        String country = p.countryCode.isEmpty() ? CountryLookup.lookup(p.host) : p.countryCode;
        StringBuilder details = new StringBuilder(p.key()).append("\n\nکشور: ").append(CountryLookup.display(country))
            .append("\nپینگ: ").append(p.latencyMs < 0 ? "تست‌نشده" : isolated(p.latencyMs + " ms"))
            .append("\nپینگ، زمان پاسخ HTTPS از مسیر این پروکسی است.")
            .append("\n").append(p.udpAdvertised ? "UDP اعلام‌شده توسط منبع؛ تضمین اتصال UDP نیست" : "فقط TCP تأییدشده")
            .append("\n\n");
        for (ServiceTargets.Target target : ServiceTargets.current())
            details.append(target.name).append(": ").append(p.passed(target) ? "تأیید شد" : p.checked(target) ? "تأیید نشد" : "تست نشده").append('\n');
        ProxyTestResult measured = testResults.get(p.key());
        if (measured != null) {
            details.append("\nسرعت دانلود ثبت‌شده: ").append(isolated(ProxyTestResult.speed(measured.downloadMBps)));
            if (measured.downloadedBytes > 0) details.append("\nحجم ثبت‌شدهٔ تست سرعت: ").append(isolated(TrafficMeter.bytes(measured.downloadedBytes)));
            if (!measured.speedError.isEmpty()) details.append("\n\nخطای آخرین تست سرعت: ").append(ProxyTestResult.shortError(measured.speedError))
                .append("\n").append(measured.speedError);
            if (!measured.probeError.isEmpty()) details.append("\n\nخطای آخرین تست اتصال/پینگ: ").append(ProxyTestResult.shortError(measured.probeError))
                .append("\n").append(measured.probeError);
            if (!measured.probeInfo.isEmpty()) details.append("\n").append(measured.probeInfo);
        }
        TextView info = text(details.toString(), 14, Color.WHITE, false);
        info.setPadding(dp(18), dp(12), dp(18), dp(12)); info.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this); scroll.addView(info);
        new AlertDialog.Builder(this, R.style.AppDialogTheme).setTitle("جزئیات پروکسی").setView(scroll)
            .setPositiveButton("انتخاب", (d, which) -> { userSelected = true; selected = p; render(); })
            .setNegativeButton("بستن", null).show();
    }
    private void manualDialog() {
        EditText field = new EditText(this);
        field.setHint(DistributionPolicy.play()?"https://proxy.example.com:443":"IP:PORT یا http://IP:PORT یا https://HOST:PORT");
        field.setTextDirection(View.TEXT_DIRECTION_LTR);
        field.setSingleLine(true);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        LinearLayout inputs=panel();inputs.addView(field);
        android.widget.Spinner type=new android.widget.Spinner(this);
        type.setAdapter(new android.widget.ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"SOCKS5","HTTP","HTTPS (TLS به پروکسی)"}));
        if(DistributionPolicy.play()){type.setSelection(2);}else inputs.addView(type);
        inputs.addView(text("برای HTTPS می‌توانی نام میزبان بدهی؛ گواهی باید معتبر باشد. پروکسی‌های نیازمند رمز فعلاً پشتیبانی نمی‌شوند.",12,SECONDARY,false),margin(dp(8),0));
        new AlertDialog.Builder(this, R.style.AppDialogTheme).setTitle("افزودن پروکسی دستی").setView(inputs)
            .setPositiveButton("ثبت و تست", (d, which) -> {
                String raw=field.getText().toString().trim();
                if(!raw.contains("://"))raw=(type.getSelectedItemPosition()==1?"http://":type.getSelectedItemPosition()==2?"https://":"")+raw;
                ProxyEndpoint e = ProxyEndpoint.parse(raw);
                if(e!=null&&!DistributionPolicy.allows(e)){Toast.makeText(this,"این نسخه فقط HTTPS با TLS معتبر می‌پذیرد",Toast.LENGTH_LONG).show();return;}
                if (e == null) { Toast.makeText(this, "IP:PORT، http://IP:PORT یا https://HOST:PORT معتبر وارد کن", Toast.LENGTH_LONG).show(); return; }
                manual.add(e); status.setText("در حال تست پروکسی دستی…");
                new Thread(() -> {
                    Socks5Probe.Result outcome;
                    try{PhysicalTestNetwork network=PhysicalTestNetwork.capture(this);
                        outcome=Socks5Probe.inspect(e,slowScan,new java.util.concurrent.atomic.AtomicBoolean(false),null,network::socket,network::resolve);}
                    catch(Exception ex){outcome=new Socks5Probe.Result(null,Socks5Probe.Failure.IO_ERROR,false,false);}
                    final Socks5Probe.Result tested=outcome;
                    ProxyEndpoint good = tested.endpoint;
                    ui.post(() -> {
                        if (isDestroyed()) return;
                        if (good != null) {
                            healthy.accept(good); selected = good; userSelected = true;
                            if (!ProxyResults.accepts(good, filter)) {
                                filter = "ALL";
                                getSharedPreferences("persiansocks", MODE_PRIVATE).edit().putString("filter", filter).apply();
                            }
                            if(!protocol.accepts(good)){
                                protocol=good.type==ProxyEndpoint.Type.SOCKS5?ProxyResults.Protocol.SOCKS:ProxyResults.Protocol.HTTP;
                                getSharedPreferences("persiansocks",MODE_PRIVATE).edit().putString("protocol-filter",protocol.name()).apply();
                            }
                            ProxyStore.save(this, healthy);
                            status.setText("پروکسی دستی سالم بود"); render();
                        } else {
                            manual.removeIf(p->p.key().equals(e.key()));
                            if(tested.tcpReached){healthy.reject(e.key(),System.currentTimeMillis());ProxyStore.save(this,healthy);}
                            status.setText("تست دستی ناموفق؛ پروکسی در نتایج ذخیره نشد");render();
                        }
                    });
                }, "manual-socks-test").start();
            }).setNegativeButton("لغو", null).show();
    }
    private void connect() {
        if (selected == null) { Toast.makeText(this, "اول یک پروکسی سالم انتخاب کن", Toast.LENGTH_LONG).show(); return; }
        if (!selected.hasAnyService()) { connectionError="اول تست سرویس‌ها را انجام بده";updateActions(); return; }
        connectionError="";getSharedPreferences("persiansocks",MODE_PRIVATE).edit().remove("connection-error").apply();
        vpnRequestPending=true;updateActions();
        VpnDisclosure.ensure(this,this::prepareVpn,()->{vpnRequestPending=false;connectionError="اتصال آغاز نشد؛ اجازهٔ عبور ترافیک داده نشد";updateActions();});
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
        String allReports = "گزارش دریافت لیست:\n" + getSharedPreferences("persiansocks", MODE_PRIVATE).getString("collection-report", "هنوز لیستی دریافت نشده") + "\n\n" + scanReport + "\n\nگزارش اتصال VPN:\n" +
            getSharedPreferences("persiansocks", MODE_PRIVATE).getString("vpn-report", "هنوز تست تونل انجام نشده است.");
        text.setText(allReports); text.setTextSize(14);
        text.setTextIsSelectable(true); text.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView scroll = new ScrollView(this); scroll.addView(text);
        new AlertDialog.Builder(this, R.style.AppDialogTheme).setTitle("گزارش آخرین اسکن").setView(scroll)
            .setPositiveButton("بستن", null).setNeutralButton("کپی گزارش", (d, w) -> {
                android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PersianSocks scan report", allReports));
                Toast.makeText(this, "گزارش کپی شد", Toast.LENGTH_SHORT).show();
            }).show();
    }
    private void scanSettings() {
        LinearLayout panel = dialogContent();
        panel.addView(text("تعداد تست: ۱۰۰ تا ۲۰٬۰۰۰، یا Max برای همهٔ پروکسی‌های دریافت‌شده پس از حذف تکراری‌ها.", 14, Color.WHITE, false));
        EditText count = new EditText(this); count.setText(String.valueOf(maxTests));
        count.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); panel.addView(count);
        CheckBox all=new CheckBox(this);all.setText("Max (همهٔ نتایج)");
        all.setTextColor(Color.WHITE);all.setButtonTintList(ColorStateList.valueOf(PRIMARY));all.setChecked(scanAll);
        count.setEnabled(!scanAll);count.setAlpha(scanAll?.4f:1f);
        all.setOnCheckedChangeListener((b,checked)->{count.setEnabled(!checked);count.setAlpha(checked?.4f:1f);});
        panel.addView(all);
        panel.addView(text("نوع پروکسی برای اسکن",14,Color.WHITE,true),margin(dp(10),0));
        CheckBox socks=new CheckBox(this);socks.setText("SOCKS5");socks.setTextColor(Color.WHITE);socks.setChecked((getSharedPreferences("persiansocks",MODE_PRIVATE).getInt("scan-types",7)&1)!=0);socks.setEnabled(!DistributionPolicy.encryptedOnly());if(!DistributionPolicy.play())panel.addView(socks);
        CheckBox http=new CheckBox(this);http.setText("HTTP و HTTPS");http.setTextColor(Color.WHITE);http.setChecked((getSharedPreferences("persiansocks",MODE_PRIVATE).getInt("scan-types",7)&6)!=0);http.setEnabled(!DistributionPolicy.encryptedOnly());if(!DistributionPolicy.play())panel.addView(http);
        panel.addView(text(DistributionPolicy.encryptedOnly()?"نوع اسکن: فقط HTTPS با TLS معتبر به خود پروکسی. سرور هم باید این اتصال رمزنگاری‌شده را پشتیبانی کند.":"یکی یا هر دو را انتخاب کن. HTTPS مقصد با CONNECT و HTTPS با TLS به خود پروکسی پشتیبانی می‌شود.",12,SECONDARY,false));
        CheckBox slow = new CheckBox(this); slow.setText("مهلت بیشتر برای شبکهٔ کند");
        slow.setTextColor(Color.WHITE); slow.setChecked(slowScan); panel.addView(slow);
        panel.addView(text("تغییر این تنظیمات برای «اسکن از ابتدا» است؛ ادامهٔ اسکن از تنظیمات همان نوبت استفاده می‌کند.", 12, SECONDARY, false));
        panel.addView(text("۲۴ تست همزمان. در حالت معمولی مهلت TCP برابر ۲.۵ ثانیه است؛ در حالت کند ۶ ثانیه. همهٔ سرویس‌های ثبت‌شده با HTTPS امتحان می‌شوند.", 12, SECONDARY, false));
        panel.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        new AlertDialog.Builder(this, R.style.AppDialogTheme).setTitle("تنظیمات اسکن").setView(scroll)
            .setPositiveButton("ذخیره", (d, w) -> {
                scanAll=all.isChecked();
                if(!scanAll){
                    try { maxTests = Math.max(100, Math.min(ProxyScanner.MAX_TESTS, Integer.parseInt(count.getText().toString()))); }
                    catch (NumberFormatException ignored) { maxTests = 5000; }
                }
                int requested=(socks.isChecked()?1:0)|(http.isChecked()?6:0);
                if(requested==0)requested=7;
                scanTypes=DistributionPolicy.scanTypes(requested);
                if(scanTypes==0){scanTypes=7;Toast.makeText(this,"نوعی انتخاب نشد؛ هر دو ذخیره شدند",Toast.LENGTH_LONG).show();}
                slowScan = slow.isChecked();
                getSharedPreferences("persiansocks", MODE_PRIVATE).edit().putInt("max-tests", maxTests)
                    .putBoolean("slow-scan", slowScan).putBoolean("scan-all",scanAll).putInt("scan-types",requested).apply();
                Toast.makeText(this, "برای اسکن بعدی ذخیره شد", Toast.LENGTH_SHORT).show();
            }).setNegativeButton("لغو", null).show();
    }
    private void sourceDialog() {
        LinearLayout panel = dialogContent();
        StringBuilder names = new StringBuilder("منابع داخلی:\n");
        if(!DistributionPolicy.play())for (ProxyScanner.Source source : SourceCatalog.DEFAULT) names.append(source.name).append(" • ");
        names.append(DistributionPolicy.play()?"\nHTTPS با TLS:\n":"\nHTTP / HTTPS:\n");
        for(ProxyScanner.Source source:SourceCatalog.HTTP)if(DistributionPolicy.allows(source.type))names.append(source.name).append(" • ");
        panel.addView(text(names.toString(), 12, SECONDARY, false));
        panel.addView(text(DistributionPolicy.play()?"در هر خط HTTPS و سپس لینک فهرست سرورهای TLS را وارد کن. منبع باید واقعاً TLS به خود پروکسی داشته باشد؛ گواهی هر سرور بررسی می‌شود. حداکثر ۲۰ منبع.":"در هر خط نوع و لینک منبع را وارد کن: SOCKS5 یا HTTP یا HTTPS، سپس فاصله و لینک https://؛ بدون نوع، SOCKS5 در نظر گرفته می‌شود. حداکثر ۲۰ منبع.", 14, Color.WHITE, false));
        EditText urls = new EditText(this); urls.setMinLines(3); urls.setMaxLines(6);
        urls.setHint(DistributionPolicy.play()?"HTTPS https://example.com/tls-proxies.txt":"HTTP https://example.com/http.txt\nHTTPS https://example.com/tls-proxies.txt");
        urls.setText(getSharedPreferences("persiansocks", MODE_PRIVATE).getString("extra-sources", ""));
        panel.addView(urls);
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        new AlertDialog.Builder(this, R.style.AppDialogTheme).setTitle("منابع پروکسی").setView(scroll)
            .setPositiveButton("ذخیره", (d, w) -> {
                StringBuilder valid = new StringBuilder(); int added = 0;
                for (String row : urls.getText().toString().split("\\n")) {
                    try {
                        ProxyScanner.Source source=SourceCatalog.custom(row,added+1);
                        if(source!=null&&DistributionPolicy.allows(source.type)&&added<20){added++;valid.append(source.type.name()).append(' ').append(source.url).append('\n');}
                    } catch (Exception ignored) {}
                }
                getSharedPreferences("persiansocks", MODE_PRIVATE).edit().putString("extra-sources", valid.toString()).apply();
                Toast.makeText(this, "منابع ذخیره شدند", Toast.LENGTH_SHORT).show();
            }).setNegativeButton("لغو", null).show();
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK) initiateVpn();
            else { vpnRequestPending = false; connectionError="مجوز VPN صادر نشد";updateActions(); }
        }
    }
    private void initiateVpn() {
        if(selected==null){vpnRequestPending=false;connectionError="پروکسی منتخب دیگر معتبر نیست";updateActions();return;}
        if(!VpnDisclosure.accepted(this)){connect();return;}
        if(!DistributionPolicy.allows(selected)){vpnRequestPending=false;connectionError="یک پروکسی با TLS معتبر انتخاب کن";updateActions();return;}
        Intent i = new Intent(this, ProxyVpnService.class).setAction(ProxyVpnService.CONNECT)
            .putExtra(ProxyVpnService.EXTRA_ENDPOINT, selected.key()).putExtra("filter", filter).putExtra("sort", sort.name());
        try {
            vpnRequestPending = true; startForegroundService(i);
            connectionError="";updateActions();
        } catch (Exception e) {
            vpnRequestPending = false; updateActions();
            connectionError="امکان آغاز سرویس VPN نیست: "+e.getMessage();updateActions();
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
        String message=enabled?"فقط پروکسی‌های TLS انتخاب می‌شوند":"الزام TLS خاموش شد؛ SOCKS5 و HTTP هم قابل استفاده‌اند";
        if(wasConnected)message+="؛ اتصال قبلی قطع شد";
        Toast.makeText(this,message,Toast.LENGTH_LONG).show();
    }
    private void updateTraffic(){
        if(trafficDown==null)return;
        TrafficMeter.Snapshot t=ProxyVpnService.traffic(this);
        trafficDown.setText(TrafficMeter.bytes(t.downPerSecond)+"/s");
        trafficUp.setText(TrafficMeter.bytes(t.upPerSecond)+"/s");
        trafficDownTotal.setText("مجموع: "+TrafficMeter.bytes(t.downloaded));
        trafficUpTotal.setText("مجموع: "+TrafficMeter.bytes(t.uploaded));
    }
    private boolean prepareSingleTest(ProxyEndpoint proxy) {
        if (proxy == null) { Toast.makeText(this, "اول یک پروکسی انتخاب کن", Toast.LENGTH_SHORT).show(); return false; }
        if (speedTest != null || singleTest != null || speedBatch != null || preparingSpeedBatch) {
            Toast.makeText(this, "یک تست در حال اجراست؛ اول آن را متوقف کن", Toast.LENGTH_SHORT).show(); return false;
        }
        if (!DistributionPolicy.allows(proxy)) {
            Toast.makeText(this, "این پروکسی با تنظیم TLS فعلی سازگار نیست", Toast.LENGTH_SHORT).show(); return false;
        }
        return true;
    }
    private void cancelSingleTests() {
        speedBatchGeneration++;preparingSpeedBatch=false;
        if(speedBatch!=null){speedBatch.cancel();speedBatch=null;}
        DownloadSpeedTest download = speedTest; SingleProxyTest probe = singleTest;
        ProxyEndpoint previous = testingProxy;
        speedTest = null; singleTest = null; testingProxy = null;
        if (download != null) download.cancel(); if (probe != null) probe.cancel();
        if (previous != null) {
            ProxyTestResult record = resultFor(previous);
            if (download != null) record.speedError = "تست متوقف شد";
            if (probe != null) { record.probeError = "تست متوقف شد"; record.probeInfo = ""; }
            saveTestResults();
        }
    }
    private void startProxyProbe(ProxyEndpoint proxy, boolean latencyOnly) {
        if (!prepareSingleTest(proxy)) return;
        final SingleProxyTest test = new SingleProxyTest(); singleTest = test; testingProxy = proxy; testingLatency = latencyOnly;
        final ServiceTargets.Target target = SingleProxyTest.latencyTarget(proxy, filter);
        final boolean slow = slowScan;
        render();
        new Thread(() -> {
            Socks5Probe.Result result = null; String error = "";
            try {
                PhysicalTestNetwork network = PhysicalTestNetwork.capture(this);
                result = latencyOnly ? test.latency(proxy, target, slow, network::socket, network::resolve) :
                    test.connection(proxy, slow, network::socket, network::resolve);
            } catch (Exception ex) { error = String.valueOf(ex.getMessage()); }
            final Socks5Probe.Result finished = result; final String failure = error;
            ui.post(() -> {
                if (isDestroyed() || singleTest != test) return;
                singleTest = null; testingProxy = null;
                String note;
                ProxyTestResult record = resultFor(proxy);
                if (test.cancelled()) { note = "تست متوقف شد"; record.probeError = note; record.probeInfo = ""; }
                else if (finished != null && finished.endpoint != null) {
                    ProxyEndpoint fresh = finished.endpoint;
                    if (latencyOnly) {
                        ProxyEndpoint current = proxy;
                        ProxyEndpoint cached=healthy.find(proxy.key());if(cached!=null)current=cached;
                        fresh = current.withLatency(fresh.latencyMs);
                        record.probeInfo = "پینگ به " + target.name + " با HTTPS اندازه‌گیری شد";
                        note = "پینگ: " + isolated(fresh.latencyMs + " ms");
                    } else { record.probeInfo = "تست اتصال: موفق"; note = "تست اتصال: موفق"; }
                    record.probeError = "";
                    final ProxyEndpoint verified = fresh;
                    healthy.accept(verified);
                    if (selected != null && selected.key().equals(proxy.key())) selected = verified;
                    ProxyStore.save(this, healthy);
                } else {
                    record.probeError = failure.isEmpty() && finished != null ? Socks5Probe.describe(finished.failure) : failure;
                    record.probeInfo = "";
                    note = (latencyOnly ? "پینگ: " : "تست اتصال: ") + ProxyTestResult.shortError(record.probeError);
                    if(finished!=null&&finished.failure!=Socks5Probe.Failure.CANCELLED){
                        long rejectedAt=System.currentTimeMillis();healthy.reject(proxy.key(),rejectedAt);manual.removeIf(p->p.key().equals(proxy.key()));
                        ScanCheckpoint current=checkpoint;
                        if(current!=null)ProxyStore.IO.execute(()->{try{current.forgetVerified(proxy.key(),rejectedAt);scanFiles.save(current);}catch(Exception ex){android.util.Log.e("PersianSocks","Invalidation save failed",ex);}});
                        if(!favorites.containsKey(proxy.key()))testResults.remove(proxy.key());
                        ProxyStore.save(this,healthy);
                        note=favorites.containsKey(proxy.key())?"پروکسی پاسخ نداد؛ از اسکن حذف شد ولی در علاقه‌مندی‌ها باقی ماند":"پروکسی پاسخ نداد و از نتایج حذف شد";
                    }
                }
                saveTestResults(); render();
            });
        }, "single-proxy-connection-test").start();
    }
    private void startSpeedTest(ProxyEndpoint proxy) {
        if (!prepareSingleTest(proxy)) return;
        runSpeedTest(proxy);
    }
    private void runSpeedTest(ProxyEndpoint proxy) {
        final DownloadSpeedTest test = new DownloadSpeedTest(); speedTest = test; testingProxy = proxy; liveDownloadMBps = -1;
        render();
        new Thread(() -> {
            PhysicalTestNetwork network;
            try { network = PhysicalTestNetwork.capture(this); }
            catch (Exception ex) {
                ui.post(() -> finishSpeedTest(test, proxy, 0, 0, String.valueOf(ex.getMessage()))); return;
            }
            test.run(new DownloadSpeedTest.Transport() {
                private long lastCheck; private boolean available = true;
                public java.net.HttpURLConnection open(java.net.URL url) {
                    return new ProxyHttpConnection(url, proxy, network::socket, network::resolve);
                }
                public boolean valid() {
                    long now = System.nanoTime();
                    if (now - lastCheck >= 200_000_000L) { available = network.valid() && DistributionPolicy.allows(proxy); lastCheck = now; }
                    return available;
                }
            }, new DownloadSpeedTest.Listener() {
                public void progress(long bytes, double mbps) { ui.post(() -> {
                    if (!isDestroyed() && speedTest == test) {
                        liveDownloadMBps = ProxyTestResult.fromMbps(mbps);
                        resultAdapter.notifyDataSetChanged();
                    }
                }); }
                public void finished(long bytes, double mbps, String error) {
                    ui.post(() -> finishSpeedTest(test, proxy, bytes, mbps, error));
                }
            });
        }, "single-proxy-download-test").start();
    }
    private void finishSpeedTest(DownloadSpeedTest test, ProxyEndpoint proxy, long bytes, double mbps, String error) {
        if (isDestroyed() || speedTest != test) return;
        test.cancel(); speedTest = null; testingProxy = null;
        ProxyTestResult record = resultFor(proxy); record.speedError = error; record.downloadedBytes = bytes;
        if (error.isEmpty()) record.downloadMBps = ProxyTestResult.fromMbps(mbps);
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
        final Map<String,Double> speeds=speedSnapshot();final boolean smooth=smoothResults;
        rankWorker.execute(()->{
            final FilteredSpeedBatch batch=new FilteredSpeedBatch(snapshot,service,order,kind,limit,speeds,smooth);
            ui.post(()->{
                if(isDestroyed()||generation!=speedBatchGeneration)return;
                preparingSpeedBatch=false;updateActions();
                if(batch.total()==0){Toast.makeText(this,"نتیجه‌ای مطابق فیلترها نیست",Toast.LENGTH_SHORT).show();return;}
                new AlertDialog.Builder(this,R.style.AppDialogTheme).setTitle("تست سرعت نتایج فیلترشده")
                    .setMessage("تمام "+batch.total()+" نتیجهٔ مطابق فیلترهای فعلی به‌ترتیب تست می‌شوند؛ حتی کارت‌های پایین فهرست.\nحداکثر مصرف: "+TrafficMeter.bytes(batch.maximumBytes())+" (۲۰ MB برای هر پروکسی).\nتغییر فیلتر پس از شروع، صف تست را تغییر نمی‌دهد.")
                    .setNegativeButton("انصراف",null).setPositiveButton("شروع تست",(d,w)->{
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
            ProxyEndpoint current=healthy.find(proxy.key());
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
        LinearLayout pair=horizontal();pair.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        pair.addView(text(label,10,SECONDARY,false));LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(-2,-2);cell.setMarginStart(dp(4));pair.addView(value,cell);return pair;
    }

    private TextView metricValue(String value, int color) {
        TextView text = text("", 16, color, true); text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END); setMetricValue(text, value); return text;
    }
    private void setMetricValue(TextView text, String value) {
        boolean number = value.contains("MB/s") || value.endsWith(" ms");
        text.setTextSize(number ? 13 : 11); text.setTextDirection(number ? View.TEXT_DIRECTION_LTR : View.TEXT_DIRECTION_RTL);
        text.setText(value);
    }
    private void loadTestResults() {
        try {
            org.json.JSONObject stored = new org.json.JSONObject(getSharedPreferences("persiansocks", MODE_PRIVATE).getString("test-results-v2", "{}"));
            java.util.Iterator<String> keys = stored.keys();
            while (keys.hasNext() && testResults.size() < 1000) {
                String endpoint = keys.next(); org.json.JSONObject result = stored.optJSONObject(endpoint);
                if (endpoint.length() <= 300 && result != null) testResults.put(endpoint, ProxyTestResult.fromJson(result));
            }
            org.json.JSONObject speeds = new org.json.JSONObject(getSharedPreferences("persiansocks", MODE_PRIVATE).getString("speed-notes", "{}"));
            org.json.JSONObject probes = new org.json.JSONObject(getSharedPreferences("persiansocks", MODE_PRIVATE).getString("probe-notes", "{}"));
            java.util.Set<String> legacy = new java.util.LinkedHashSet<>();
            speeds.keys().forEachRemaining(legacy::add); probes.keys().forEachRemaining(legacy::add);
            boolean changed = false;
            for (String endpoint : legacy) if (testResults.size() < 1000 && endpoint.length() <= 300 && !testResults.containsKey(endpoint)) {
                testResults.put(endpoint, ProxyTestResult.legacy(speeds.optString(endpoint), probes.optString(endpoint))); changed = true;
            }
            if (changed) saveTestResults();
        } catch (Exception ignored) {}
    }
    private void saveTestResults() {
        measurementRevision++;
        while (testResults.size() > 1000) testResults.remove(testResults.keySet().iterator().next());
        org.json.JSONObject stored = new org.json.JSONObject();
        for (Map.Entry<String, ProxyTestResult> entry : testResults.entrySet()) try { stored.put(entry.getKey(), entry.getValue().json()); } catch (Exception ignored) {}
        getSharedPreferences("persiansocks", MODE_PRIVATE).edit().putString("test-results-v2", stored.toString()).apply();
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
        content.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        content.setPadding(dp(20), dp(8), dp(20), dp(8));
        return content;
    }
    private LinearLayout panel() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(18), dp(18), dp(18));
        c.setBackground(background(SURFACE, dp(20), STROKE));
        return c;
    }
    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        t.setFontFeatureSettings("kern");
        t.setLineSpacing(dp(2), 1);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label); b.setTextSize(12); b.setAllCaps(false);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
        b.setPadding(dp(8), dp(2), dp(8), dp(2));
        b.setTextColor(primary ? Color.rgb(7, 21, 26) : Color.WHITE);
        b.setBackground(background(primary ? PRIMARY : SURFACE, dp(12), primary ? PRIMARY : STROKE));
        b.setForeground(new android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(Color.argb(35, 255, 255, 255)), null,
            background(Color.WHITE, dp(12), Color.WHITE)));
        return b;
    }
}
