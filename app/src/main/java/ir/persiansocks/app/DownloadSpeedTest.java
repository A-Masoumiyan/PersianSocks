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
    public void run(Transport transport,Listener listener){
        long bytes=0,start=0,last=0;double speed=0;String error="";
        AtomicBoolean timedOut=new AtomicBoolean(false);
        try {
            if(cancelled.get()||!transport.valid())throw new IOException("شبکهٔ تست در دسترس نیست");
            HttpURLConnection c=transport.open(new java.net.URL(URL+"&ts="+System.nanoTime()));connection=c;
            if(cancelled.get()){c.disconnect();throw new IOException("تست متوقف شد");}
            deadline.schedule(()->{timedOut.set(true);c.disconnect();},20,TimeUnit.SECONDS);
            c.setConnectTimeout(6000);c.setReadTimeout(5000);c.setUseCaches(false);c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept-Encoding","identity");c.setRequestProperty("Cache-Control","no-cache, no-store");
            c.setRequestProperty("User-Agent","PersianSocks/0.12.0");
            if(c.getResponseCode()!=200)throw new IOException("سرور تست پاسخ معتبر نداد");
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
                speed=mbps(bytes,System.nanoTime()-start);
            }
        }catch(Exception ex){
            if(timedOut.get()&&bytes>0&&transport.valid())speed=mbps(bytes,System.nanoTime()-start);
            else error=timedOut.get()?"مهلت تست تمام شد":String.valueOf(ex.getMessage());
        }finally{
            HttpURLConnection c=connection;if(c!=null)c.disconnect();connection=null;deadline.shutdownNow();
        }
        if(cancelled.get())error="تست متوقف شد";
        listener.finished(bytes,speed,error);
    }
    public static double mbps(long bytes,long nanos){return nanos<=0?0:bytes*8000.0/nanos;}
}
