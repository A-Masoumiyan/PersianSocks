package ir.persiansocks.app;
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import java.nio.file.*;
public final class ScanModesHarness {
    static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static List<ProxyEndpoint> rows(int count){List<ProxyEndpoint> list=new ArrayList<>();for(int i=0;i<count;i++)list.add(new ProxyEndpoint("8.8.8.8",1000+i,-1,false));return list;}
    static Socks5Probe.Result good(ProxyEndpoint p){return new Socks5Probe.Result(new ProxyEndpoint(p.host,p.port,100,false,7,7,System.currentTimeMillis()),Socks5Probe.Failure.NONE,true,true);}
    static PreparedScanner.Listener listener(CountDownLatch done){return new PreparedScanner.Listener(){public void onResult(int i,Socks5Probe.Result r){}public void onProgress(int d,int t,int g){}public void onFinished(ScanCheckpoint s,String m){done.countDown();}};}
    static void checkpoint(boolean deep)throws Exception{
        Path folder=Files.createTempDirectory("scan-mode-");ScanFiles files=new ScanFiles(folder.toFile());ScanCheckpoint original=new ScanCheckpoint(rows(5),new ProxyScanner.Options(100,deep,true),"CATALOG");original.record(0,good(original.candidate(0)));files.activate(original);files.save(original);
        ScanCheckpoint restored=files.checkpoint();check(restored.slow==deep&&restored.done()==1&&restored.pending().size()==4,"Stored mode and pending rows survive reload");
        PreparedScanner scanner=new PreparedScanner();CountDownLatch started=new CountDownLatch(1),newResult=new CountDownLatch(1);files.activate(restored);
        scanner.start(restored,files::save,(p,slow,stop,sockets)->{if(p.port==1001)return good(p);started.countDown();try{while(!stop.get())Thread.sleep(5);}catch(InterruptedException e){}return new Socks5Probe.Result(null,Socks5Probe.Failure.CANCELLED,false,false);},new PreparedScanner.Listener(){public void onResult(int i,Socks5Probe.Result r){newResult.countDown();}public void onProgress(int d,int t,int g){}public void onFinished(ScanCheckpoint s,String m){}});
        check(started.await(2,TimeUnit.SECONDS)&&newResult.await(2,TimeUnit.SECONDS),"A fresh result is processed before pause");scanner.requestStop();scanner.pause();ScanCheckpoint paused=files.checkpoint();check(paused.slow==deep&&paused.done()==2&&paused.pending().size()==3,"Pause durably saves the new result, mode and cancelled pending rows");
        files.activate(paused);CountDownLatch finished=new CountDownLatch(1);AtomicInteger tested=new AtomicInteger();AtomicBoolean wrong=new AtomicBoolean();
        scanner.start(paused,files::save,(p,slow,stop,sockets)->{if(slow!=deep||(p.port==1000||p.port==1001))wrong.set(true);tested.incrementAndGet();return good(p);},listener(finished));
        check(finished.await(5,TimeUnit.SECONDS),"Paused mode resumed to completion");check(!wrong.get()&&tested.get()==3&&paused.done()==5,"Resume uses the frozen type and never repeats completed rows");
    }
    static long benchmark(boolean deep)throws Exception{
        ScanCheckpoint state=new ScanCheckpoint(rows(1728),new ProxyScanner.Options(100,deep,true),"CATALOG");PreparedScanner scanner=new PreparedScanner();CountDownLatch finished=new CountDownLatch(1);long start=System.nanoTime();
        scanner.start(state,s->{},(p,slow,stop,sockets)->{try{Thread.sleep(250);}catch(InterruptedException e){return new Socks5Probe.Result(null,Socks5Probe.Failure.CANCELLED,false,false);}return new Socks5Probe.Result(null,Socks5Probe.Failure.TCP_REFUSED,false,false);},listener(finished));
        check(finished.await(30,TimeUnit.SECONDS)&&state.complete(),"Controlled-delay scan completes");return (System.nanoTime()-start)/1000000;
    }
    public static void main(String[] args)throws Exception{
        PreparedScanner.foreground(true);PreparedScanner.connectionPending(false);
        AdaptiveScanPolicy fast=new AdaptiveScanPolicy(false),deep=new AdaptiveScanPolicy(true);
        check(fast.limit(1000)==216&&deep.limit(1000)==24,"Ninefold v18 foreground capacity");
        for(int i=0;i<120;i++)fast.observe(new Socks5Probe.Result(null,Socks5Probe.Failure.HTTPS_TIMEOUT,true,true));
        check(fast.limit(1000)==216,"Individual dead proxies cannot collapse list throughput");
        check(AdaptiveScanPolicy.hardCap(false,false,false)==54&&AdaptiveScanPolicy.hardCap(false,true,true)==8,"Background and VPN setup limits stay bounded");
        check(deep.budget(true).totalMs==20000&&fast.budget(false).connectMs==2500&&fast.budget(false).readMs==4500&&fast.budget(false).totalMs==11000,"Original v18 deadlines are preserved");checkpoint(false);checkpoint(true);
        long fastMs=benchmark(false),deepMs=benchmark(true);System.out.println("BENCHMARK: 1728 identical 250 ms fixtures (24-worker v18 baseline vs 216-worker fast); fast="+fastMs+" ms; deep="+deepMs+" ms; ratio="+String.format(java.util.Locale.US,"%.2f",(double)deepMs/fastMs)+"x; controlled host scheduling, not phone/network throughput");
        System.out.println("PASS: "+checks+" scan mode/capacity/pause/resume/deadline checks");
    }
}
