package ir.pathfinder.b;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.content.Intent;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(11,17,31);
    private static final int CARD = Color.rgb(24,34,55);
    private static final int INK = Color.rgb(234,241,252);
    private static final int MUTED = Color.rgb(153,171,196);
    private static final int ACCENT = Color.rgb(83,143,244);
    private TextView output, state;
    private Button start, share;
    private final StringBuilder report = new StringBuilder();
    private volatile boolean running = false;
    private final AtomicInteger successHttps = new AtomicInteger();
    private final AtomicInteger successDns = new AtomicInteger();
    private final AtomicInteger successTcp4 = new AtomicInteger();
    private final AtomicInteger successTcp6 = new AtomicInteger();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        page.setPadding(dp(18), dp(30), dp(18), dp(18));
        page.setBackgroundColor(BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page);
        setContentView(scroll);

        TextView tag = text("PROJECT B  /  0.1", 13, ACCENT, true);
        page.addView(tag);
        TextView title = text("کشف مسیر ارتباط", 26, INK, true);
        space(page, 6);
        page.addView(title);
        TextView sub = text("آزمایش مستقل مسیرهای DNS، TCP و HTTPS — بدون دانلود لیست از اینترنت", 14, MUTED, false);
        space(page, 8);
        page.addView(sub);
        space(page, 18);

        LinearLayout card = box();
        page.addView(card);
        state = text("آماده بررسی شبکه", 17, INK, true);
        card.addView(state);
        TextView note = text("نتیجه مثبت یعنی مسیر آزمون‌شده باز است؛ به معنای وجود VPN یا رله قابل‌استفاده نیست.", 13, MUTED, false);
        space(card, 7);
        card.addView(note);
        space(card, 14);
        start = button("شروع بررسی", ACCENT);
        card.addView(start);
        start.setOnClickListener(v -> beginScan());
        space(card, 8);
        share = button("اشتراک‌گذاری گزارش", Color.rgb(49,65,92));
        card.addView(share);
        share.setOnClickListener(v -> shareReport());
        space(page, 15);
        TextView resultsTitle = text("گزارش بررسی", 18, INK, true);
        page.addView(resultsTitle);
        space(page, 8);
        LinearLayout resultCard = box();
        page.addView(resultCard);
        output = text("برای شروع، دکمه بررسی را بزنید.", 13, INK, false);
        output.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        output.setLineSpacing(dp(3),1.05f);
        resultCard.addView(output);
        space(page, 14);
        TextView foot = text("فقط درخواست‌های محدود به مقصدهای عمومی شناخته‌شده ارسال می‌شود. گزارش در گوشی ذخیره می‌شود. بدون دسترسی روت و بدون VPN.", 12, MUTED, false);
        page.addView(foot);
    }

    private int dp(float n) { return (int)(getResources().getDisplayMetrics().density*n+0.5f); }
    private void space(LinearLayout p,int h) {
        View v = new View(this); p.addView(v, new LinearLayout.LayoutParams(1,dp(h)));
    }
    private TextView text(String s,int size,int color,boolean bold) {
        TextView v=new TextView(this);
        v.setText(s);v.setTextColor(color);v.setTextSize(size);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        v.setGravity(Gravity.START);
        return v;
    }
    private LinearLayout box() {
        LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(15),dp(15),dp(15),dp(15));
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(CARD);shape.setCornerRadius(dp(16));
        b.setBackground(shape);return b;
    }
    private Button button(String label,int color) {
        Button b=new Button(this);b.setText(label);b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(12));
        b.setBackground(shape);
        b.setMinHeight(dp(46));
        return b;
    }
    private void line(String s) {
        synchronized(report) {
            report.append(s).append('\n');
            String all=report.toString();
            runOnUiThread(() -> output.setText(all));
        }
    }
    private void beginScan() {
        if(running)return;
        running=true;
        synchronized(report){report.setLength(0);}
        successHttps.set(0);successDns.set(0);successTcp4.set(0);successTcp6.set(0);
        state.setText("در حال آزمایش واقعی اتصال…");
        start.setEnabled(false);
        new Thread(() -> {
            try { perform(); }
            catch(Exception e){line("خطای بررسی: "+safe(e));}
            finally {
                running=false;
                runOnUiThread(() -> {
                    start.setEnabled(true);
                    start.setText("بررسی مجدد");
                    state.setText(successHttps.get()>0 ? "اتصال HTTPS خارجی تأیید شد" :
                        "مسیر HTTPS تأیید نشد؛ گزارش را ببینید");
                });
                saveHistory();
            }
        }, "pathfinder-scan").start();
    }
    private void perform() throws Exception {
        line("Project B PathFinder 0.1  |  "+new java.util.Date());
        ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        Network net=cm.getActiveNetwork();
        if(net==null){line("هیچ شبکه فعالی در اندروید دیده نشد.");return;}
        NetworkCapabilities cap=cm.getNetworkCapabilities(net);
        if(cap!=null){
            String type=cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi":
               cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"Mobile":
               cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)?"Ethernet":
               cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)?"VPN":"Other";
            line("شبکه: "+type+" | اینترنت سیستم: "+cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
        }
        LinkProperties props=cm.getLinkProperties(net);
        line("\n[۱] DNS اختصاص‌داده‌شده توسط شبکه");
        if(props!=null && props.getDnsServers()!=null && !props.getDnsServers().isEmpty()){
            List<InetAddress> resolvers=props.getDnsServers();
            int count=0;
            for(InetAddress dns:resolvers){
                if(count++>=3)break;
                line("Resolver: "+dns.getHostAddress());
                dnsTest(dns,"example.org",1,"A");
                dnsTest(dns,"google.com",16,"TXT");
                String nonce="b"+Long.toHexString(System.nanoTime())+".example.org";
                dnsTest(dns,nonce,1,"A (nonce)");
            }
        } else line("آدرس DNS از شبکه دریافت نشد.");
        line("\n[۲] TCP مستقیم روی IP عمومی");
        ExecutorService pool=Executors.newFixedThreadPool(4);
        pool.execute(() -> tcpTest("1.1.1.1",false));
        pool.execute(() -> tcpTest("8.8.8.8",false));
        pool.execute(() -> tcpTest("2606:4700:4700::1111",true));
        pool.execute(() -> tcpTest("2001:4860:4860::8888",true));
        pool.shutdown();
        pool.awaitTermination(12,TimeUnit.SECONDS);

        line("\n[۳] HTTPS خارجی با اعتبارسنجی TLS");
        ExecutorService httpsPool=Executors.newFixedThreadPool(3);
        httpsPool.execute(() -> httpsTest("Google","https://www.google.com/generate_204"));
        httpsPool.execute(() -> httpsTest("Cloudflare","https://www.cloudflare.com/cdn-cgi/trace"));
        httpsPool.execute(() -> httpsTest("Wikipedia","https://www.wikipedia.org/"));
        httpsPool.execute(() -> httpsTest("GitHub API","https://api.github.com/"));
        httpsPool.execute(() -> httpsTest("Microsoft","https://www.microsoft.com/"));
        httpsPool.execute(() -> httpsTest("Apple","https://www.apple.com/"));
        httpsPool.shutdown();
        httpsPool.awaitTermination(30,TimeUnit.SECONDS);

        line("\n[۴] خلاصه");
        line("DNS پاسخ‌دار: "+successDns.get());
        line("TCP IPv4 موفق: "+successTcp4.get()+" / 2");
        line("TCP IPv6 موفق: "+successTcp6.get()+" / 2");
        line("HTTPS معتبر و پاسخ 2xx: "+successHttps.get()+" / 6");
        if(successHttps.get()>0) line("نتیجه: دست‌کم یک مقصد خارجی با HTTPS واقعی پاسخ داده است.");
        else if(successDns.get()>0) line("نتیجه: پاسخ DNS داریم، ولی خروج HTTPS اثبات نشده است.");
        else line("نتیجه: در این آزمون مسیر خارجی تأیید نشد.");
        line("هشدار: DNS و TCP باز الزاماً امکان رله یا VPN ایجاد نمی‌کنند.");
    }
    private String safe(Throwable t){
        String msg=t.getMessage();
        return t.getClass().getSimpleName()+(msg==null?"":(": "+msg.replace('\n',' ').substring(0,Math.min(msg.length(),90))));
    }
    private void tcpTest(String host,boolean ipv6){
        try(Socket s=new Socket()){
            InetAddress addr=InetAddress.getByName(host);
            s.connect(new InetSocketAddress(addr,443),3000);
            if(ipv6)successTcp6.incrementAndGet();else successTcp4.incrementAndGet();
            line("OK TCP "+host+":443");
        }catch(Exception e){line("NO TCP "+host+":443 ("+safe(e)+")");}
    }
    private void httpsTest(String label,String address){
        HttpsURLConnection h=null;
        try {
            URL url=new URL(address);
            h=(HttpsURLConnection)url.openConnection();
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(4500);h.setReadTimeout(4500);
            h.setRequestProperty("User-Agent","PathFinderB/0.1");
            int code=h.getResponseCode();
            if(code>=200 && code<300) {
                try(InputStream in=h.getInputStream()){
                    byte[] small=new byte[128]; int n=in.read(small);
                }
                successHttps.incrementAndGet();
                line("OK HTTPS "+label+" — "+code+" TLS valid");
            } else {
                line("HTTP "+label+" — "+code+" (server responded; 2xx not confirmed)");
            }
        }catch(Exception e){line("NO HTTPS "+label+" ("+safe(e)+")");}
        finally{if(h!=null)h.disconnect();}
    }
    private void dnsTest(InetAddress server,String domain,int queryType,String kind){
        DatagramSocket sock=null;
        try {
            sock=new DatagramSocket();
            sock.setSoTimeout(2000);
            byte[] packet=makeDns(domain,queryType);
            DatagramPacket outbound=new DatagramPacket(packet,packet.length,server,53);
            sock.send(outbound);
            byte[] buf=new byte[1300];
            DatagramPacket incoming=new DatagramPacket(buf,buf.length);
            sock.receive(incoming);
            if(incoming.getLength()<12 || buf[0]!=packet[0] || buf[1]!=packet[1])throw new Exception("invalid DNS id");
            int flags=((buf[2]&255)<<8)|(buf[3]&255);
            if((flags&0x8000)==0)throw new Exception("not a DNS response");
            int rcode=flags&15;
            int answers=((buf[6]&255)<<8)|(buf[7]&255);
            successDns.incrementAndGet();
            line("DNS "+kind+" "+domain+" => RCODE "+rcode+", answers "+answers+" ("+server.getHostAddress()+")");
        }catch(Exception e){
            line("DNS "+kind+" "+domain+" => NO ("+safe(e)+")");
        }finally{if(sock!=null)sock.close();}
    }
    private byte[] makeDns(String domain,int type)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        int id=(int)(System.nanoTime()&65535);
        out.write((id>>8)&255);out.write(id&255);
        out.write(1);out.write(0); // standard recursive query
        out.write(0);out.write(1); // qdcount
        for(int i=0;i<6;i++)out.write(0);
        for(String part:domain.split("\\.")){
            byte[] b=part.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            if(b.length==0||b.length>63)throw new Exception("bad DNS label");
            out.write(b.length);out.write(b);
        }
        out.write(0);out.write(0);out.write(type);out.write(0);out.write(1);
        return out.toByteArray();
    }
    private void saveHistory(){
        try{
            String all;
            synchronized(report){all=report.toString();}
            try(OutputStream out=openFileOutput("scan-history.txt",MODE_APPEND)){
                out.write((all+"\n----------------\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }catch(Exception ignored){}
    }
    private void shareReport(){
        String s;
        synchronized(report){s=report.toString();}
        if(s.isEmpty())s="هنوز هیچ بررسی انجام نشده است.";
        Intent intent=new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT,"Project B PathFinder report");
        intent.putExtra(Intent.EXTRA_TEXT,s);
        startActivity(Intent.createChooser(intent,"اشتراک‌گذاری گزارش"));
    }
}
