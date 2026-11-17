package ir.pathfinder.b;

import android.app.*;
import android.content.*;
import android.net.VpnService;
import android.os.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Authorized, resumable fixed-CIDR audit UI. */
public final class ConnectActivity extends Activity {
  private static final int VPN_PERMISSION=71;
  private static final String DEFAULT_PORTS="1080, 10808, 10809, 8080, 3128, 8000, 8888, 4145, 9050, 8118, 80, 443";
  private final Handler ui=new Handler(Looper.getMainLooper());
  private EditText portsInput;
  private TextView progress, results, summary;
  private Button scanButton,pauseButton,resetButton,connectButton;
  private AtomicBoolean pause=new AtomicBoolean(false);
  private volatile boolean scanning;
  private volatile boolean destroyed;
  private String report="";
  private RelayProbe.Entry chosen;
  private final Runnable statusPoll=new Runnable(){
    @Override public void run(){
      if(!destroyed){
        if(!scanning){
          String msg=getSharedPreferences("pathfinder",MODE_PRIVATE).getString("status","");
          if(msg.startsWith("تونل")||msg.startsWith("اتصال ناموفق"))
            summary.setText(msg);
        }
        ui.postDelayed(this,2000);
      }
    }
  };
  @Override public void onCreate(Bundle saved){
    super.onCreate(saved);
    getWindow().setStatusBarColor(0xff0b111f);
    getWindow().setNavigationBarColor(0xff0b111f);
    ScrollView scroll=new ScrollView(this);
    LinearLayout root=new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    root.setPadding(dp(18),dp(20),dp(18),dp(26));
    root.setBackgroundColor(0xff0b111f);
    scroll.addView(root);setContentView(scroll);

    root.addView(text("PROJECT B  /  0.5.1",13,0xff6cafff));
    root.addView(text("اسکن محدوده اختصاصی",26,Color.WHITE));
    root.addView(text("37.32.0.0/19  •  8,192 IP",16,0xff75c4fa));
    root.addView(text("فقط همین بازه بررسی می‌شود. مرحله اول پورت‌های TCP را پیدا می‌کند؛ مرحله دوم SOCKS4، SOCKS4a، SOCKS5 و HTTP CONNECT را با درخواست واقعی به تلگرام و یوتیوب می‌آزماید.",14,0xffc2cede));

    root.addView(text("پورت‌های مجاز برای بررسی (با ویرگول جدا کن، حداکثر ۲۴):",14,0xffc2cede));
    portsInput=new EditText(this);
    portsInput.setTextColor(Color.WHITE);portsInput.setHintTextColor(0xffa5b3c7);
    portsInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT|
        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    portsInput.setMinLines(2);portsInput.setMaxLines(3);
    portsInput.setText(getPreferences(MODE_PRIVATE).getString("ports",DEFAULT_PORTS));
    root.addView(portsInput);
    root.addView(text("نرخ کنترل‌شده: حداکثر حدود ۳۳ تلاش TCP در ثانیه، ۲۴ اتصال هم‌زمان. تعداد آزمون‌ها برابر تعداد IP ضربدر تعداد پورت است.",12,0xff9eafc4));

    scanButton=button("شروع / ادامه اسکن",0xff286fae);
    scanButton.setOnClickListener(v->new AlertDialog.Builder(this)
      .setTitle("تأیید محدوده دارای مجوز")
      .setMessage("فقط 37.32.0.0/19 روی پورت‌های انتخابی آزمایش می‌شود. تأیید می‌کنی که مجوز بررسی این شبکه را داری؟")
      .setNegativeButton("لغو",null)
      .setPositiveButton("شروع",(d,w)->startScan()).show());
    root.addView(scanButton);

    pauseButton=button("توقف موقت و ذخیره پیشرفت",0xff4d627d);
    pauseButton.setEnabled(false);
    pauseButton.setOnClickListener(v->{
      pause.set(true);
      progress.setText("توقف درخواست شد؛ پس از ذخیره دسته فعلی متوقف می‌شود.");
    });
    root.addView(pauseButton);

    resetButton=button("شروع اسکن از ابتدا",0xff394960);
    resetButton.setOnClickListener(v->{
      if(scanning){progress.setText("ابتدا اسکن را متوقف کن.");return;}
      new AlertDialog.Builder(this).setTitle("پاک‌کردن پیشرفت")
       .setMessage("نتایج و پیشرفت ذخیره‌شده این محدوده پاک شود؟")
       .setNegativeButton("انصراف",null)
       .setPositiveButton("پاک کن",(d,w)->{
         CidrRangeScanner.reset(this);
         chosen=null;report="";connectButton.setEnabled(false);
         results.setText("نتایجی ثبت نشده است.");
         progress.setText("آماده اسکن از ابتدا.");
       }).show();
    });
    root.addView(resetButton);

    progress=text("آماده.",15,0xffe9f3ff);
    progress.setPadding(dp(12),dp(14),dp(12),dp(14));
    GradientDrawable box=new GradientDrawable();
    box.setColor(0xff1a2b42);box.setCornerRadius(dp(12));
    progress.setBackground(box);root.addView(progress);

    summary=text("آزمون تلگرام و یوتیوب از طریق IP میزبان پراکسی انجام می‌شود.",13,0xffc5d6e7);
    root.addView(summary);

    connectButton=button("اتصال آزمایشی به SOCKS5 موفق",0xff286e65);
    connectButton.setEnabled(false);
    connectButton.setOnClickListener(v->{
      if(chosen==null)return;
      new AlertDialog.Builder(this).setTitle("اتصال آزمایشی")
       .setMessage("SOCKS5 عادی رمزگذاری مستقل ندارد. در استفاده از پراکسی ناشناس برای حساب‌های حساس احتیاط کن.")
       .setNegativeButton("لغو",null)
       .setPositiveButton("اتصال",(d,w)->permission()).show();
    });
    root.addView(connectButton);

    Button stopVpn=button("قطع VPN",0xff4b5365);
    stopVpn.setOnClickListener(v->{
      Intent intent=new Intent(this,PathVpnService.class).setAction(PathVpnService.STOP);
      startService(intent);
      summary.setText("درخواست قطع VPN ارسال شد.");
    });
    root.addView(stopVpn);

    root.addView(text("پراکسی‌های تأییدشده",19,Color.WHITE));
    results=text("هنوز اسکن نشده است.",13,0xffc3d0e5);
    results.setTextDirection(View.TEXT_DIRECTION_LTR);
    results.setTextIsSelectable(true);
    root.addView(results);

    Button copy=button("کپی نتایج موفق",0xff405271);
    copy.setOnClickListener(v->{
      if(report.isEmpty()){progress.setText("گزارشی برای کپی وجود ندارد.");return;}
      android.content.ClipboardManager manager=
          (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
      manager.setPrimaryClip(ClipData.newPlainText("CIDR scan",report));
      progress.setText("نتایج کپی شدند.");
    });root.addView(copy);

    Button share=button("اشتراک‌گذاری گزارش",0xff405271);
    share.setOnClickListener(v->{
      if(report.isEmpty()){progress.setText("گزارشی برای اشتراک وجود ندارد.");return;}
      Intent intent=new Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT,report);
      startActivity(Intent.createChooser(intent,"ارسال گزارش"));
    });root.addView(share);

    Button diagnostics=button("آزمون‌های عیب‌یابی DNS / HTTPS",0xff36445b);
    diagnostics.setOnClickListener(v->startActivity(new Intent(this,MainActivity.class)));
    root.addView(diagnostics);
    root.addView(text("توجه: HTTPS پاسخ‌دار با کارکرد کامل پیام‌رسان یا پخش ویدیو یکسان نیست. فقط اتصال به SOCKS5 در موتور VPN فعلی فعال است؛ SOCKS4/4a و HTTP CONNECT در اسکن و گزارش پشتیبانی می‌شوند.",12,0xff9fb0c6));
    refreshSaved();
  }
  private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+0.5f);}
  private TextView text(String s,int size,int color){
    TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
    t.setPadding(0,dp(9),0,dp(11));return t;
  }
  private Button button(String s,int background){
    Button b=new Button(this);b.setAllCaps(false);b.setText(s);b.setTextColor(Color.WHITE);
    GradientDrawable g=new GradientDrawable();g.setColor(background);g.setCornerRadius(dp(11));
    b.setBackground(g);
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(52));
    p.bottomMargin=dp(10);b.setLayoutParams(p);
    return b;
  }
  private int[] readPorts()throws Exception{
    return CidrRangeScanner.parsePorts(portsInput.getText().toString());
  }
  private void refreshSaved(){
    try {
      int[] ports=readPorts();
      CidrRangeScanner.State state=CidrRangeScanner.load(this,ports);
      show(state);
      progress.setText(state.completed()?"اسکن این تنظیمات کامل شده است.":
        state.cursor>0?"پیشرفت ذخیره شده؛ برای ادامه اسکن بزن.":"آماده شروع اسکن همه آدرس‌ها.");
    }catch(Exception e){progress.setText(e.getMessage());}
  }
  private void show(CidrRangeScanner.State state){
    StringBuilder b=new StringBuilder();
    b.append("Project B / 37.32.0.0/19\n");
    b.append("Proxy endpoints passing TLS checks:\n");
    int tg=0,yt=0;
    chosen=null;
    ArrayList<IranProxyScanner.Hit> hits=new ArrayList<>(state.hits);
    hits.sort((a,z)->{
      int qa=(a.telegram?1:0)+(a.youtube?1:0)+(a.thumbnail?1:0);
      int qb=(z.telegram?1:0)+(z.youtube?1:0)+(z.thumbnail?1:0);
      return qa!=qb?Integer.compare(qb,qa):Long.compare(a.duration,z.duration);
    });
    for(IranProxyScanner.Hit h:hits){
      b.append(h.format()).append('\n');
      if(h.telegram)tg++;
      if(h.youtube)yt++;
      if(chosen==null&&h.proxy.type==IranProxyScanner.Type.SOCKS5)
        chosen=new RelayProbe.Entry(h.proxy.ip,h.proxy.port,"","");
    }
    report=b.toString();
    results.setText(hits.isEmpty()?"هنوز پراکسی موفق تأیید نشده است.":report);
    connectButton.setEnabled(chosen!=null&&!scanning);
    summary.setText("پورت باز: "+state.opened.size()+" | موفق تلگرام: "+tg+
      " | موفق یوتیوب: "+yt+" | کل موفق: "+hits.size());
  }
  private void startScan(){
    if(scanning)return;
    final int[] ports;
    try{ports=readPorts();}catch(Exception e){progress.setText(e.getMessage());return;}
    getPreferences(MODE_PRIVATE).edit().putString("ports",portsInput.getText().toString()).apply();
    scanning=true;pause=new AtomicBoolean(false);
    scanButton.setEnabled(false);pauseButton.setEnabled(true);
    resetButton.setEnabled(false);portsInput.setEnabled(false);
    connectButton.setEnabled(false);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    AtomicBoolean token=pause;
    progress.setText("در حال اجرای اسکن مجاز روی "+CidrRangeScanner.CIDR+"…");
    new Thread(()->{
      CidrRangeScanner.State result=null;String error="";
      try {
        result=CidrRangeScanner.run(this,ports,token,(phase,completed,total,open,hits)->{
          ui.post(()->{
            progress.setText(phase+" • "+completed+" / "+total+
              " | پورت باز: "+open+" | پراکسی موفق: "+hits);
            if(phase.contains("تأیید")){
              CidrRangeScanner.State current=CidrRangeScanner.load(this,ports);
              show(current);
            }
          });
        });
      }catch(Exception ex){error=ex.getClass().getSimpleName()+": "+ex.getMessage();}
      final String fail=error;
      final CidrRangeScanner.State last=result;
      ui.post(()->{
        if(destroyed)return;
        scanning=false;
        scanButton.setEnabled(true);pauseButton.setEnabled(false);
        resetButton.setEnabled(true);portsInput.setEnabled(true);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(last!=null)show(last);
        else show(CidrRangeScanner.load(this,ports));
        if(!fail.isEmpty())progress.setText("خطای اسکن: "+fail+"؛ پیشرفت ذخیره‌شده باقی مانده است.");
        else if(token.get())progress.setText("اسکن متوقف شد؛ با دکمه شروع، از نقطه ذخیره‌شده ادامه بده.");
        else progress.setText("اسکن همه IPها و پورت‌های انتخابی کامل شد.");
      });
    },"cidr-network-audit").start();
  }
  private void permission(){
    Intent request=VpnService.prepare(this);
    if(request!=null)startActivityForResult(request,VPN_PERMISSION);
    else connect();
  }
  @Override protected void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);
    if(request==VPN_PERMISSION){
      if(result==RESULT_OK)connect();
      else progress.setText("مجوز VPN رد شد.");
    }
  }
  private void connect(){
    if(chosen==null){progress.setText("SOCKS5 تأییدشده انتخاب نشده است.");return;}
    Intent i=new Intent(this,PathVpnService.class).setAction(PathVpnService.CONNECT)
      .putExtra(PathVpnService.HOST,chosen.host).putExtra(PathVpnService.PORT,chosen.port)
      .putExtra(PathVpnService.USER,chosen.user).putExtra(PathVpnService.PASS,chosen.pass);
    if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
    summary.setText("در حال راه‌اندازی VPN: "+chosen.label());
  }
  @Override protected void onResume(){super.onResume();destroyed=false;ui.post(statusPoll);}
  @Override protected void onPause(){ui.removeCallbacks(statusPoll);super.onPause();}
  @Override protected void onDestroy(){
    destroyed=true;pause.set(true);
    ui.removeCallbacks(statusPoll);
    super.onDestroy();
  }
}