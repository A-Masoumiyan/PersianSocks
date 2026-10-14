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
        ScanCheckpoint state=new ScanCheckpoint(rows(144),new ProxyScanner.Options(100,deep,true),"CATALOG");PreparedScanner scanner=new PreparedScanner();CountDownLatch finished=new CountDownLatch(1);long start=System.nanoTime();
        scanner.start(state,s->{},(p,slow,stop,sockets)->{try{Thread.sleep(150);}catch(InterruptedException e){return new Socks5Probe.Result(null,Socks5Probe.Failure.CANCELLED,false,false);}return new Socks5Probe.Result(null,Socks5Probe.Failure.TCP_REFUSED,false,false);},listener(finished));
        check(finished.await(15,TimeUnit.SECONDS)&&state.complete(),"Controlled-delay scan completes");return (System.nanoTime()-start)/1000000;
    }
    public static void main(String[] args)throws Exception{
        PreparedScanner.foreground(true);PreparedScanner.connectionPending(false);
        AdaptiveScanPolicy fast=new AdaptiveScanPolicy(false),deep=new AdaptiveScanPolicy(true);check(fast.limit(100)==6*deep.limit(100),"Sixfold starting concurrency");
        for(int cohort=0;cohort<20;cohort++){for(int i=0;i<12;i++){fast.observe(good(rows(1).get(0)));deep.observe(good(rows(1).get(0)));}check(fast.limit(100)==6*deep.limit(100),"Sixfold healthy ramp remains bounded");}
        check(fast.limit(100)==36&&deep.limit(100)==6,"Fast/deep maxima are bounded");
        for(int cohort=0;cohort<8;cohort++){for(int i=0;i<12;i++){Socks5Probe.Result failure=new Socks5Probe.Result(null,Socks5Probe.Failure.HTTPS_TIMEOUT,true,true);fast.observe(failure);deep.observe(failure);}check(fast.limit(100)==6*deep.limit(100),"Sixfold capacity during backoff");}
        check(fast.limit(100)==6&&deep.limit(100)==1,"Congestion floors remain bounded");
        for(boolean foreground:new boolean[]{true,false})for(boolean connecting:new boolean[]{true,false})check(AdaptiveScanPolicy.hardCap(false,foreground,connecting)==6*AdaptiveScanPolicy.hardCap(true,foreground,connecting),"Capacity ratio preserves background and VPN setup limits");
        check(deep.budget(true).totalMs==20000&&deep.budget(true).readMs==9000&&fast.budget(false).totalMs<=20000,"Deep retains its longer probe budget");checkpoint(false);checkpoint(true);
        long fastMs=benchmark(false),deepMs=benchmark(true);System.out.println("BENCHMARK: 144 identical 150 ms fixtures; fast="+fastMs+" ms; deep="+deepMs+" ms; ratio="+String.format(java.util.Locale.US,"%.2f",(double)deepMs/fastMs)+"x; controlled host scheduling, not phone/network throughput");
        System.out.println("PASS: "+checks+" scan mode/capacity/pause/resume/deadline checks");
    }
}
