package ir.pathfinder.b;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ConnectActivity extends Activity {
  private static final int VPN_PERMISSION=71;
  private EditText entries;
  private TextView status;
  private RelayProbe.Entry selected;
  private boolean testing,directMode;
  private AtomicBoolean cancelled=new AtomicBoolean(false);
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final Runnable refresh=new Runnable(){
    @Override public void run(){
      if(!testing&&status!=null){
        String v=getSharedPreferences("pathfinder",0).getString("status","");
        if(!v.isEmpty()&&!v.equals(lastSaved)){lastSaved=v;status.setText(v);}
      }
      handler.postDelayed(this,1200);
    }
  };
  private String lastSaved="";
  @Override public void onCreate(Bundle saved){
    super.onCreate(saved);
    getWindow().setStatusBarColor(0xff0b111f);
    getWindow().setNavigationBarColor(0xff0b111f);
    ScrollView sc=new ScrollView(this);setContentView(sc);
    LinearLayout page=new LinearLayout(this);
    page.setPadding(dp(18),dp(24),dp(18),dp(28));
    page.setOrientation(LinearLayout.VERTICAL);
    page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    page.setBackgroundColor(0xff0b111f);
    sc.addView(page);
    page.addView(text("PROJECT B / 0.3",13,0xff62a6ff));
    page.addView(text("کشف خودکار و اتصال",25,Color.WHITE));
    page.addView(text("برای شروع هیچ IP یا پراکسی وارد نکن. برنامه گزینه‌های آفلاین داخل APK را بررسی می‌کند و تنها در صورت پیدا کردن رله واقعی، مجوز VPN می‌خواهد.",14,0xffb3c0d4));

    Button auto=button("کشف خودکار و تلاش برای اتصال",0xff287cc4);
    page.addView(auto);
    auto.setOnClickListener(v->new AlertDialog.Builder(this)
      .setTitle("هشدار امنیت پراکسی عمومی")
      .setMessage("فهرست آفلاین شامل پراکسی‌های عمومی و ناشناس است. این سرویس‌ها می‌توانند داده‌های غیررمزگذاری‌شده و مقصد اتصال را ببینند. برای اطلاعات حساس و ورود به حساب استفاده نکن. آیا آزمایش کنم؟")
      .setNegativeButton("انصراف",null)
      .setPositiveButton("آزمایش", (d,w)->autoScan(auto)).show());

    Button direct=button("تست تونل مستقیم (بدون عبور از فیلترینگ)",0xff2c786d);
    page.addView(direct);
    direct.setOnClickListener(v->{
      if(testing)return;
      directMode=true;selected=null;
      status.setText("آزمایش مسیر مستقیم: فقط برای اطمینان از کارکرد موتور VPN روی اینترنت وصل.");
      permission();
    });
    page.addView(text("تست مستقیم فقط اتصال اندروید و موتور تونل را کنترل می‌کند؛ در زمان فیلترینگ شدید راه خروج نیست.",12,0xff9dacbf));

    TextView toggle=text("ورود دستی رله مجاز (اختیاری)  ▾",15,0xffa4cafa);
    page.addView(toggle);
    LinearLayout manual=new LinearLayout(this);
    manual.setOrientation(LinearLayout.VERTICAL);
    manual.setVisibility(View.GONE);page.addView(manual);
    entries=new EditText(this);
    entries.setTextColor(Color.WHITE);entries.setHintTextColor(0xff8190a8);
    entries.setMinLines(3);entries.setMaxLines(6);entries.setSingleLine(false);
    entries.setHint("host:1080\nuser:pass@host:1080");
    manual.addView(entries);
    Button manualButton=button("آزمون رله‌های واردشده",0xff475d7e);
    manual.addView(manualButton);
    manualButton.setOnClickListener(v->manualScan(manualButton));
    toggle.setOnClickListener(v->{
      boolean open=manual.getVisibility()!=View.VISIBLE;
      manual.setVisibility(open?View.VISIBLE:View.GONE);
      toggle.setText(open?"ورود دستی رله مجاز (اختیاری)  ▴":"ورود دستی رله مجاز (اختیاری)  ▾");
    });

    Button stop=button("توقف اسکن / قطع VPN",0xff4d5363);
    page.addView(stop);
    stop.setOnClickListener(v->{
      cancelled.set(true);
      Intent i=new Intent(this,PathVpnService.class).setAction(PathVpnService.STOP);
      startService(i);
      status.setText("درخواست توقف ارسال شد");
    });
    status=text("آماده بررسی خودکار. برای شروع دکمه اول را بزن.",14,0xffe7efff);
    status.setTextIsSelectable(true);
    status.setPadding(dp(12),dp(16),dp(12),dp(16));
    GradientDrawable bg=new GradientDrawable();bg.setColor(0xff182237);bg.setCornerRadius(dp(14));
    status.setBackground(bg);page.addView(status);
    page.addView(text("نکته: این نسخه مسیرها را از لیست ازپیش‌ذخیره‌شده بررسی می‌کند، نه کل IPهای سفید. DNS Tunnel و SlipNet هنوز اضافه نشده‌اند. هیچ اتصال موفقی تضمین نیست.",12,0xff9dacbf));
  }
  private int dp(float x){return (int)(getResources().getDisplayMetrics().density*x+0.5f);}
  private TextView text(String content,int size,int color){
    TextView t=new TextView(this);t.setText(content);t.setTextSize(size);
    t.setTextColor(color);t.setPadding(0,dp(10),0,dp(14));return t;
  }
  private Button button(String title,int color){
    Button b=new Button(this);b.setText(title);b.setTextColor(Color.WHITE);b.setAllCaps(false);
    GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(12));b.setBackground(shape);
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(52));p.bottomMargin=dp(10);
    b.setLayoutParams(p);return b;
  }
  private void autoScan(Button button){
    if(testing)return;
    testing=true;button.setEnabled(false);cancelled=new AtomicBoolean(false);
    status.setText("در حال بررسی گزینه‌های آفلاین برای پیدا کردن رله خروجی…");
    AtomicBoolean flag=cancelled;
    new Thread(()->{
      RelayProbe.Entry candidate=null;String error="";
      try{
        AutoRelayScanner.Result result=AutoRelayScanner.search(this,(done,total)->{
          runOnUiThread(()->status.setText("در حال بررسی رله‌های آفلاین: "+done+" / "+total));
        },flag);
        if(result!=null)candidate=result.candidate;
      }catch(Exception e){error=e.getMessage();}
      final RelayProbe.Entry found=candidate;
      final String problem=error;
      runOnUiThread(()->{
        testing=false;button.setEnabled(true);
        if(flag.get()&&found==null){status.setText("بررسی متوقف شد.");return;}
        if(found==null){
          status.setText("در فهرست آفلاین، رله خروجی تأییدشده پیدا نشد.\n"+
            (problem.isEmpty()?"این نتیجه به معنای نبود همه مسیرهای اینترنت نیست.":"خطا: "+problem));
          return;
        }
        directMode=false;selected=found;
        status.setText("رله واقعی تأیید شد: "+found.label()+"؛ درخواست مجوز VPN…");
        permission();
      });
    },"offline-auto-relay-scan").start();
  }
  private void manualScan(Button button){
    if(testing)return;
    String data=entries.getText().toString();if(data.trim().isEmpty()){
      status.setText("آدرس رله دستی وارد نشده است.");return;
    }
    testing=true;button.setEnabled(false);
    status.setText("آزمایش رله‌های دستی با TLS واقعی…");
    new Thread(()->{
      RelayProbe.Entry found=null;int count=0;
      ConnectivityManager cm=getSystemService(ConnectivityManager.class);
      Network n=cm==null?null:cm.getActiveNetwork();
      for(String line:data.split("\\r?\\n")){
        if(count>=8)break;
        try{
          RelayProbe.Entry e=RelayProbe.Entry.parse(line);
          if(e==null)continue;
          count++;
          RelayProbe.verify(n,e);found=e;break;
        }catch(Exception ignored){}
      }
      final RelayProbe.Entry verified=found;
      runOnUiThread(()->{
        testing=false;button.setEnabled(true);
        if(verified==null){status.setText("هیچ رله دستی با خروجی TLS واقعی تأیید نشد.");return;}
        selected=verified;directMode=false;status.setText("رله تأیید شد: "+verified.label());
        permission();
      });
    },"manual-relay-scan").start();
  }
  private void permission(){
    Intent request=VpnService.prepare(this);
    if(request!=null)startActivityForResult(request,VPN_PERMISSION);else begin();
  }
  @Override protected void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);
    if(request==VPN_PERMISSION){if(result==RESULT_OK)begin();else status.setText("مجوز VPN رد شد");}
  }
  private void begin(){
    Intent i;
    if(directMode)i=new Intent(this,PathVpnService.class).setAction(PathVpnService.DIRECT);
    else {
      if(selected==null){status.setText("هیچ رله‌ای تأیید نشده");return;}
      i=new Intent(this,PathVpnService.class).setAction(PathVpnService.CONNECT)
        .putExtra(PathVpnService.HOST,selected.host).putExtra(PathVpnService.PORT,selected.port)
        .putExtra(PathVpnService.USER,selected.user).putExtra(PathVpnService.PASS,selected.pass);
    }
    if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
    status.setText("در حال برقرار کردن تونل؛ وضعیت از سرویس VPN دریافت می‌شود…");
  }
  @Override protected void onResume(){super.onResume();handler.post(refresh);}
  @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
}