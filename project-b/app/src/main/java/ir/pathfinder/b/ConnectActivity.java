package ir.pathfinder.b;

import android.app.Activity;
import android.content.*;
import android.net.*;
import android.os.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;

public final class ConnectActivity extends Activity {
  private static final int VPN_PERMISSION=71;
  private EditText entries;
  private TextView status;
  private RelayProbe.Entry selected;
  private boolean testing;
  @Override public void onCreate(Bundle saved){
    super.onCreate(saved);
    ScrollView sc=new ScrollView(this);setContentView(sc);
    LinearLayout page=new LinearLayout(this);
    page.setPadding(22,28,22,20);page.setOrientation(LinearLayout.VERTICAL);
    page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);page.setBackgroundColor(0xff0b111f);
    sc.addView(page);
    page.addView(text("PROJECT B — اتصال واقعی",24));
    page.addView(text("ابتدا مسیرهای خروجی را در صفحه اصلی بررسی کن. سپس رله SOCKS5 مجاز را اینجا وارد کن؛ برنامه فقط اگر اتصال TLS واقعی از داخل آن موفق باشد، تونل VPN را فعال می‌کند.",14));
    page.addView(text("رله‌ها: هر خط یک آدرس",15));
    entries=new EditText(this);
    entries.setTextColor(Color.WHITE);entries.setHintTextColor(0xff8795ae);
    entries.setMinLines(4);entries.setMaxLines(7);entries.setSingleLine(false);
    entries.setHint("host:1080\nuser:pass@host:1080\nsocks5://host:1080");
    // Do not persist proxy credentials in plaintext.
    page.addView(entries);
    Button action=new Button(this);action.setAllCaps(false);action.setText("آزمایش و اتصال");
    page.addView(action);action.setOnClickListener(v->connect(action));
    Button stop=new Button(this);stop.setAllCaps(false);stop.setText("قطع اتصال");
    page.addView(stop);stop.setOnClickListener(v->{
      Intent i=new Intent(this,PathVpnService.class).setAction(PathVpnService.STOP);
      startService(i);status.setText("درخواست قطع اتصال ارسال شد");
    });
    status=text(getSharedPreferences("pathfinder",0).getString("status","آماده"),14);
    status.setTextIsSelectable(true);page.addView(status);
    page.addView(text("نکته: وب‌سایت باز یا DNS پاسخ‌دار به معنی وجود رله نیست. SOCKS5 ساده رمزگذاری مستقل ندارد؛ فقط از پراکسی مورداعتماد استفاده کن. DNS Tunnel هنوز در این نسخه نیست.",12));
  }
  private TextView text(String content,int size){
    TextView t=new TextView(this);t.setText(content);t.setTextSize(size);
    t.setTextColor(0xffeaf1fb);t.setPadding(0,12,0,12);return t;
  }
  private void connect(Button btn){
    if(testing)return;
    String list=entries.getText().toString();
    // Secrets remain in memory for this test session only.
    if(list.trim().isEmpty()){
      status.setText("آدرس رله وارد نشده؛ IP وب‌سایت عمومی قابل تبدیل به VPN نیست.");return;
    }
    testing=true;btn.setEnabled(false);status.setText("در حال بررسی اتصال واقعی…");
    new Thread(()->{
      RelayProbe.Entry working=null;
      StringBuilder log=new StringBuilder();
      ConnectivityManager cm=getSystemService(ConnectivityManager.class);
      Network net=cm==null?null:cm.getActiveNetwork();
      int count=0;
      for(String l:list.split("\\r?\\n")){
        if(count>=8)break;
        try{
          RelayProbe.Entry entry=RelayProbe.Entry.parse(l);
          if(entry==null)continue;count++;
          RelayProbe.verify(net,entry);
          working=entry;log.append("موفق: ").append(entry.label()).append(" (TLS + HTTP)\n");
          break;
        }catch(Exception ex){
          log.append("ناموفق: ").append(count).append(" / ").append(ex.getClass().getSimpleName()).append('\n');
        }
      }
      final RelayProbe.Entry found=working;
      runOnUiThread(()->{
        testing=false;btn.setEnabled(true);
        if(found==null){status.setText(log+"هیچ رله قابل استفاده‌ای پیدا نشد.");return;}
        selected=found;status.setText(log+"درخواست اجازه VPN…");
        Intent prepare=android.net.VpnService.prepare(this);
        if(prepare!=null)startActivityForResult(prepare,VPN_PERMISSION);else start();
      });
    },"pathfinder-connection-probe").start();
  }
  @Override protected void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);
    if(request==VPN_PERMISSION){if(result==RESULT_OK)start();else status.setText("مجوز VPN داده نشد");}
  }
  private void start(){
    if(selected==null)return;
    Intent intent=new Intent(this,PathVpnService.class).setAction(PathVpnService.CONNECT)
      .putExtra(PathVpnService.HOST,selected.host).putExtra(PathVpnService.PORT,selected.port)
      .putExtra(PathVpnService.USER,selected.user).putExtra(PathVpnService.PASS,selected.pass);
    if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);
    status.setText("اتصال در حال برقراری است؛ وضعیت را از اعلان برنامه بررسی کن.");
  }
  @Override protected void onResume(){
    super.onResume();
    if(status!=null)status.setText(getSharedPreferences("pathfinder",0)
      .getString("status",status.getText().toString()));
  }
}