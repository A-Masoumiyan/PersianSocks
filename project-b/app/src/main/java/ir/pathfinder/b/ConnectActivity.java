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

/**
 * Scan publicly listed Iranian-IP proxies, independently verifying
 * Telegram API and YouTube HTTPS over the proxy. Does not scan arbitrary IPs.
 */
public final class ConnectActivity extends Activity {
  private static final int REQ=71;
  private TextView progress, results;
  private Button scanButton, connectButton;
  private final Handler ui=new Handler(Looper.getMainLooper());
  private AtomicBoolean stop=new AtomicBoolean(false);
  private String report="";
  private RelayProbe.Entry best;
  private boolean scanning;
  private final Runnable poll=new Runnable(){
    @Override public void run(){
      if(!scanning){
        String state=getSharedPreferences("pathfinder",0).getString("status","");
        if(!state.isEmpty()&&state.contains("تونل"))progress.setText(state);
      }
      ui.postDelayed(this,1800);
    }
  };
  @Override public void onCreate(Bundle state){
    super.onCreate(state);
    getWindow().setStatusBarColor(0xff0b111f);
    getWindow().setNavigationBarColor(0xff0b111f);
    ScrollView scroll=new ScrollView(this);
    LinearLayout root=new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    root.setPadding(dp(17),dp(23),dp(17),dp(25));
    root.setBackgroundColor(0xff0b111f);
    scroll.addView(root);
    setContentView(scroll);

    root.addView(text("PROJECT B  /  0.4",13,0xff71b1ff));
    root.addView(text("شناسایی پراکسی‌های ایرانی",24,Color.WHITE));
    root.addView(text("مبدأ پراکسی حتماً IP ایران است. برنامه برای هر پراکسی عمومیِ فهرست‌شده، دسترسی مستقل به Telegram API و YouTube را از داخل خود پراکسی بررسی می‌کند.",14,0xffc1cede));

    scanButton=button("اسکن پراکسی‌های ایرانی و بررسی تلگرام / یوتیوب",0xff276eac);
    root.addView(scanButton);
    scanButton.setOnClickListener(v->new AlertDialog.Builder(this)
       .setTitle("آزمایش پراکسی‌های عمومی")
       .setMessage("این آزمون فقط به پراکسی‌های منتشرشده در فهرست‌های عمومی درخواست‌های محدود می‌فرستد، نه همه آدرس‌های ایران. پراکسی‌ها قابل‌اعتماد یا امن فرض نمی‌شوند. ادامه می‌دهی؟")
       .setNegativeButton("لغو",null)
       .setPositiveButton("شروع اسکن",(d,w)->scan()).show());

    connectButton=button("اتصال VPN به بهترین SOCKS5 تأییدشده",0xff287c6d);
    root.addView(connectButton);
    connectButton.setEnabled(false);
    connectButton.setOnClickListener(v->{
      if(best==null){progress.setText("هیچ SOCKS5 تأییدشده‌ای موجود نیست.");return;}
      new AlertDialog.Builder(this).setTitle("هشدار امنیت")
         .setMessage("پراکسی‌های عمومی ناشناس می‌توانند مقصدهای اتصال و داده‌های رمزگذاری‌نشده را مشاهده کنند. برای ورود به حساب‌های حساس استفاده نکن.")
         .setNegativeButton("انصراف",null)
         .setPositiveButton("اتصال آزمایشی",(d,w)->permission()).show();
    });

    Button cancel=button("توقف اسکن / قطع VPN",0xff4d5363);
    root.addView(cancel);
    cancel.setOnClickListener(v->{
      stop.set(true);
      Intent intent=new Intent(this,PathVpnService.class).setAction(PathVpnService.STOP);
      startService(intent);
      progress.setText("درخواست توقف ارسال شد");
    });

    progress=text("آماده بررسی فهرست آفلاین پراکسی‌های دارای IP ایران",14,0xffe4efff);
    progress.setPadding(dp(12),dp(13),dp(12),dp(13));
    GradientDrawable bg=new GradientDrawable();
    bg.setColor(0xff18263a);bg.setCornerRadius(dp(12));
    progress.setBackground(bg);root.addView(progress);

    root.addView(text("نتایج تأییدشده",18,Color.WHITE));
    results=text("هنوز نتیجه‌ای ثبت نشده است.",13,0xffc4d0e1);
    results.setTextDirection(View.TEXT_DIRECTION_LTR);
    results.setTextIsSelectable(true);
    root.addView(results);

    Button copy=button("کپی همه نتایج موفق",0xff3e5273);
    root.addView(copy);copy.setOnClickListener(v->{
      if(report.isEmpty()){progress.setText("نتیجه‌ای برای کپی وجود ندارد.");return;}
      android.content.ClipboardManager cm=
         (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
      cm.setPrimaryClip(ClipData.newPlainText("Iran proxies",report));
      progress.setText("گزارش در کلیپ‌بورد کپی شد.");
    });
    Button share=button("اشتراک‌گذاری گزارش",0xff3e5273);
    root.addView(share);share.setOnClickListener(v->{
      if(report.isEmpty()){progress.setText("هنوز گزارشی وجود ندارد.");return;}
      Intent intent=new Intent(Intent.ACTION_SEND).setType("text/plain")
         .putExtra(Intent.EXTRA_TEXT,report);
      startActivity(Intent.createChooser(intent,"ارسال گزارش"));
    });
    root.addView(text("محدودیت: تشخیص رسیدن به Telegram API و YouTube HTTPS با اجرای کامل خود اپ تلگرام یا پخش ویدیو برابر نیست. رله‌های HTTP صرفاً در گزارش نشان داده می‌شوند؛ اتصال VPN این نسخه از SOCKS5 پشتیبانی می‌کند.",12,0xff9aaac4));
    root.addView(text("فهرست قبل از قطع اینترنت داخل APK ذخیره شده است و برای اسکن به GitHub یا API جدید وصل نمی‌شود. پوشش آن شامل تمام IPها و پروکسی‌های ایران نیست.",12,0xff9aaac4));
  }
  private int dp(float size){return (int)(size*getResources().getDisplayMetrics().density+0.5f);}
  private TextView text(String value,int size,int color){
    TextView t=new TextView(this);
    t.setText(value);t.setTextSize(size);t.setTextColor(color);
    t.setPadding(0,dp(9),0,dp(10));return t;
  }
  private Button button(String title,int color){
    Button b=new Button(this);b.setAllCaps(false);b.setText(title);b.setTextColor(Color.WHITE);
    GradientDrawable shape=new GradientDrawable();
    shape.setColor(color);shape.setCornerRadius(dp(12));b.setBackground(shape);
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(52));
    p.bottomMargin=dp(9);b.setLayoutParams(p);return b;
  }
  private void scan(){
    if(scanning)return;
    scanning=true;stop=new AtomicBoolean(false);best=null;connectButton.setEnabled(false);
    scanButton.setEnabled(false);report="";
    results.setText("در حال شناسایی آدرس‌های منتشرشده با IP ایران…");
    progress.setText("تست Telegram API و YouTube از مسیر پراکسی…");
    AtomicBoolean stopToken=stop;
    new Thread(()->{
      IranProxyScanner.Result found=null;
      String fail="";
      try {
        found=IranProxyScanner.scan(this,(done,total,success)->ui.post(()->
           progress.setText("بررسی "+done+" از "+total+" پراکسی ایرانی | موفق: "+success)),stopToken);
      }catch(Exception ex){fail=ex.getClass().getSimpleName()+": "+ex.getMessage();}
      final IranProxyScanner.Result res=found;
      final String error=fail;
      ui.post(()->{
        scanning=false;scanButton.setEnabled(true);
        if(stopToken.get()){
          progress.setText("اسکن متوقف شد.");return;
        }
        if(res==null){
          progress.setText("خطای اسکن: "+error);
          return;
        }
        StringBuilder text=new StringBuilder();
        text.append("Iran IP public proxy audit\n");
        text.append("Date: ").append(new java.util.Date()).append('\n');
        text.append("IR endpoints in bundled sources: ").append(res.candidates).append('\n');
        text.append("Completed checks: ").append(res.checked).append('\n');
        text.append("Verified Telegram/YouTube endpoints: ").append(res.hits.size()).append("\n\n");
        int tg=0,yt=0;
        for(IranProxyScanner.Hit h:res.hits){
          text.append(h.format()).append('\n');
          if(h.telegram)tg++;
          if(h.youtube)yt++;
          if(best==null && h.proxy.type==IranProxyScanner.Type.SOCKS5){
            best=new RelayProbe.Entry(h.proxy.ip,h.proxy.port,"","");
          }
        }
        report=text.toString();
        results.setText(report);
        connectButton.setEnabled(best!=null);
        progress.setText("پایان بررسی؛ تلگرام: "+tg+" | یوتیوب: "+yt+
          " | پراکسی موفق: "+res.hits.size()+
          (best==null?" | SOCKS5 مناسب اتصال یافت نشد":" | SOCKS5 قابل‌آزمایش یافت شد"));
      });
    },"iran-ip-proxy-scan").start();
  }
  private void permission(){
    Intent intent=VpnService.prepare(this);
    if(intent==null)connect();else startActivityForResult(intent,REQ);
  }
  @Override protected void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);
    if(request==REQ){
      if(result==RESULT_OK)connect();
      else progress.setText("مجوز VPN رد شد.");
    }
  }
  private void connect(){
    if(best==null){progress.setText("هیچ SOCKS5 انتخاب نشده");return;}
    Intent intent=new Intent(this,PathVpnService.class).setAction(PathVpnService.CONNECT)
      .putExtra(PathVpnService.HOST,best.host).putExtra(PathVpnService.PORT,best.port)
      .putExtra(PathVpnService.USER,best.user).putExtra(PathVpnService.PASS,best.pass);
    if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);
    progress.setText("در حال اتصال آزمایشی از طریق "+best.label());
  }
  @Override protected void onResume(){super.onResume();ui.post(poll);}
  @Override protected void onPause(){ui.removeCallbacks(poll);super.onPause();}
}