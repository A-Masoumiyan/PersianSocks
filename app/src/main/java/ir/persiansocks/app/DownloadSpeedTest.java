package ir.persiansocks.app;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded, uncached download over the caller's explicitly selected transport. */
public final class DownloadSpeedTest {
    public static final int MAX_BYTES=20_000_000;
    public static final String URL="https://speed.cloudflare.com/__down?bytes="+MAX_BYTES;
    public interface Transport { HttpURLConnection open(java.net.URL url)throws Exception; boolean valid(); }
    public interface Listener { void progress(long bytes,double mbps); void finished(long bytes,double mbps,String error); }
    private final AtomicBoolean cancelled=new AtomicBoolean(false);
    private volatile HttpURLConnection connection;
    private final ScheduledExecutorService deadline=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"download-deadline");t.setDaemon(true);return t;});
    public void cancel(){cancelled.set(true);HttpURLConnection c=connection;if(c!=null)c.disconnect();deadline.shutdownNow();}
    public void run(Transport transport,Listener listener){run(transport,listener,"");}
    public void run(Transport transport,Listener listener,String configuredUrl){
        try(PreparedScanner.TrafficReservation reservation=PreparedScanner.downloadMeasurement()){
            runMeasured(transport,listener,configuredUrl);
        }
    }
    private void runMeasured(Transport transport,Listener listener,String configuredUrl){
        String selected=configuredUrl==null?"":configuredUrl;
        if(!selected.isEmpty())try{selected=TestDownloadPolicy.validate(selected);}catch(Exception ex){deadline.shutdownNow();listener.finished(0,0,String.valueOf(ex.getMessage()));return;}
        long bytes=0,start=0,last=0;double speed=0;String error="";
        AtomicBoolean timedOut=new AtomicBoolean(false);
        if(cancelled.get()){listener.finished(0,0,"تست متوقف شد");return;}
        deadline.schedule(()->{timedOut.set(true);HttpURLConnection c=connection;if(c!=null)c.disconnect();},20,TimeUnit.SECONDS);
        try {
            boolean custom=!selected.isEmpty();
            String[] endpoints=custom?new String[]{selected}:new String[]{URL,"https://proof.ovh.net/files/10Mb.dat"};
            for(int attempt=0;attempt<endpoints.length;attempt++){
                HttpURLConnection c=null;boolean retryable=false;
                try {
                    if(cancelled.get()||!transport.valid())throw new IOException("شبکهٔ تست در دسترس نیست");
                    c=transport.open(new java.net.URL(endpoints[attempt]+(custom?"":(attempt==0?"&":"?")+"ts="+System.nanoTime())));connection=c;
                    if(cancelled.get()||timedOut.get())throw new IOException("تست متوقف شد");
                    c.setConnectTimeout(6000);c.setReadTimeout(5000);c.setUseCaches(false);c.setInstanceFollowRedirects(false);
                    c.setRequestProperty("Accept-Encoding","identity");c.setRequestProperty("Cache-Control","no-cache, no-store");
                    c.setRequestProperty("User-Agent","PersianSocks");
                    int code=c.getResponseCode();retryable=code==403||code==429||code>=500; if(code!=200)throw new IOException("Download server returned HTTP "+code);
                    String contentType=c.getHeaderField("Content-Type");if(custom&&contentType!=null&&contentType.toLowerCase(java.util.Locale.ROOT).contains("text/html"))throw new IOException("لینک فایل به صفحهٔ HTML پاسخ داد");
                    String encoding=c.getContentEncoding();if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))throw new IOException("پاسخ فشرده برای تست سرعت پذیرفته نشد");
                    start=last=System.nanoTime();
                    try(InputStream in=c.getInputStream()) {
                        byte[] block=new byte[16384];
                        while(bytes<MAX_BYTES&&!cancelled.get()){
                            if(!transport.valid())throw new IOException("شبکهٔ تست تغییر کرد؛ تست را دوباره اجرا کن");
                            int n=in.read(block,0,(int)Math.min(block.length,MAX_BYTES-bytes));if(n<0)break;bytes+=n;
                            long now=System.nanoTime();speed=mbps(bytes,now-start);
                            if(now-last>=500_000_000){listener.progress(bytes,speed);last=now;}
                            if(now-start>=12_000_000_000L)break;
                        }
                        if(bytes==0&&!cancelled.get())throw new IOException("داده‌ای برای اندازه‌گیری نرسید");
                        if(custom&&bytes<TestDownloadPolicy.MIN_BYTES)throw new IOException("دادهٔ کافی برای تست سرعت دریافت نشد");
                        speed=mbps(bytes,System.nanoTime()-start);
                    }
                    error="";break;
                }catch(Exception ex){
                    long elapsed=start==0?0:System.nanoTime()-start;
                    if((timedOut.get()||ex instanceof SocketTimeoutException)&&bytes>=256_000&&elapsed>=3_000_000_000L&&transport.valid()){
                        speed=mbps(bytes,elapsed);error="";break;
                    }
                    error=timedOut.get()?"مهلت تست تمام شد":String.valueOf(ex.getMessage());
                    boolean secureFailure=false;for(Throwable cause=ex;cause!=null;cause=cause.getCause()){if(cause instanceof javax.net.ssl.SSLException)secureFailure=true;if(cause instanceof SocketTimeoutException||cause instanceof ConnectException||cause instanceof NoRouteToHostException||cause instanceof UnknownHostException)retryable=true;}
                    if(bytes>0||cancelled.get()||timedOut.get()||!transport.valid()||secureFailure||!retryable)break;
                }finally{if(c!=null)c.disconnect();connection=null;}
            }
        }finally{deadline.shutdownNow();}
        if(cancelled.get())error="تست متوقف شد";
        listener.finished(bytes,speed,error);
    }
    public static double mbps(long bytes,long nanos){return nanos<=0?0:bytes*8000.0/nanos;}
}
