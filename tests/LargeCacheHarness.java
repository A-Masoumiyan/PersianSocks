import ir.persiansocks.app.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class LargeCacheHarness {
    static int checks;
    static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;System.out.println("PASS: "+name);}
    static ProxyEndpoint endpoint(int i,long time){return new ProxyEndpoint("8.8."+(i/256)+"."+(i%256),1080,i%2500+1,false,7,7,time);}
    public static void main(String[] args)throws Exception {
        DistributionPolicy.setUserTlsOnly(false);ServiceTargets.configure(Collections.emptyList());
        long start=System.nanoTime();ProxyIndex index=new ProxyIndex();
        for(int i=1;i<=50000;i++)index.accept(endpoint(i,1000));
        check(index.size()==50000,"50,000 verified endpoints retained in the keyed index");
        List<ProxyEndpoint> initial=index.snapshot();
        check(ProxyResults.visible(initial,"FULL",ProxyResults.Sort.FASTEST,ProxyResults.Protocol.ALL,ProxyResults.PingLimit.UNDER_1000).size()==19980,"Large-list ranking composes with service/type/ping filters");
        ScanCheckpoint checkpoint=new ScanCheckpoint(initial,new ProxyScanner.Options(100,false,true),"CATALOG");
        for(int i=0;i<checkpoint.total();i++)checkpoint.record(i,new Socks5Probe.Result(initial.get(i),Socks5Probe.Failure.NONE,true,true));
        File directory=Files.createTempDirectory("large-proxy-cache-").toFile();ScanFiles files=new ScanFiles(directory);
        files.save(checkpoint);check(new ScanFiles(directory).checkpoint().goodCount()==50000,"Large completed checkpoint is streamed and reloads all valid rows");
        ProxyEndpoint first=initial.get(0);index.reject(first.key(),2000);
        check(index.find(first.key())==null&&index.rejected(first.key()),"Failed ping removes the active endpoint");
        check(!index.accept(first),"A stale success cannot restore an endpoint rejected later");
        index.apply(checkpoint);check(index.find(first.key())==null,"Old paused checkpoint cannot revive a failed individual test");
        ProxyCacheFile file=new ProxyCacheFile(directory);file.save(index);ProxyIndex restored=new ProxyIndex();file.load(restored);
        check(restored.size()==49999&&restored.rejected(first.key()),"Failure tombstone and healthy cache persist through process restart");
        check(!restored.accept(endpoint(1,2000)),"Same-timestamp success cannot erase a failure");
        check(restored.accept(endpoint(1,2001)),"A later explicit successful scan can restore the endpoint");
        ProxyEndpoint unknown=new ProxyEndpoint("1.1.1.1",1080,-1,false,7,7,2001);
        check(!restored.accept(unknown),"Unmeasured/invalid latency is never saved as healthy");
        ProxyEndpoint updated=restored.find(first.key()).withLatency(42);
        restored.accept(updated);restored.accept(endpoint(1,2001));
        check(restored.find(first.key()).latencyMs==42,"New ping value cannot be replaced by an older service result");
        checkpoint.forgetVerified(first.key(),System.currentTimeMillis());
        check(checkpoint.goodCount()==49999&&checkpoint.complete(),"Invalidation removes saved success without losing resume progress");
        for(int i=0;i<45000;i++)restored.reject(initial.get(i).key(),System.currentTimeMillis()+1000);
        check(restored.size()==5000,"45,000 failed endpoints release their proxy objects from the active cache");
        file.save(restored);restored=new ProxyIndex();file.load(restored);check(restored.size()==5000,"Only remaining healthy endpoints reappear after reopening");
        files.activate(checkpoint);files.save(checkpoint);files.discardComplete();check(files.checkpoint()==null,"Completed checkpoint is discarded after cache commit");
        ScanCheckpoint fresh=new ScanCheckpoint(initial.subList(0,3),new ProxyScanner.Options(100,false),"CATALOG");
        files.activate(fresh);files.save(fresh);files.save(checkpoint);
        check(files.checkpoint().total()==3,"An older coordinator cannot overwrite a newly activated scan");
        PreparedScanner runner=new PreparedScanner();CountDownLatch finished=new CountDownLatch(1);AtomicInteger running=new AtomicInteger(),peak=new AtomicInteger();
        ScanCheckpoint bounded=new ScanCheckpoint(initial.subList(0,135),new ProxyScanner.Options(100,false,true),"CATALOG");
        runner.start(bounded,state->{},(p,slow,cancel,sockets)->{
            int n=running.incrementAndGet();peak.accumulateAndGet(n,Math::max);
            try{Thread.sleep(3);return new Socks5Probe.Result(p,Socks5Probe.Failure.NONE,true,true);}
            catch(InterruptedException ex){return new Socks5Probe.Result(null,Socks5Probe.Failure.CANCELLED,false,false);}
            finally{running.decrementAndGet();}
        },new PreparedScanner.Listener(){public void onResult(int i,Socks5Probe.Result r){}public void onProgress(int d,int t,int g){}public void onFinished(ScanCheckpoint s,String m){finished.countDown();}});
        check(finished.await(10,TimeUnit.SECONDS)&&peak.get()<=12,"Probe concurrency remains bounded at 12 under a large queued batch");
        check(bounded.done()==135,"Reducing parallelism never drops queued results");
        PreparedScanner waiting=new PreparedScanner();CountDownLatch diskEntered=new CountDownLatch(1),diskRelease=new CountDownLatch(1);AtomicInteger writes=new AtomicInteger();
        ScanCheckpoint unstarted=new ScanCheckpoint(initial.subList(0,3),new ProxyScanner.Options(100,false),"CATALOG");
        waiting.start(unstarted,state->{if(writes.incrementAndGet()==1){diskEntered.countDown();while(diskRelease.getCount()>0)try{diskRelease.await();}catch(InterruptedException ignored){}}},
            (p,slow,cancel,sockets)->new Socks5Probe.Result(p,Socks5Probe.Failure.NONE,true,true),
            new PreparedScanner.Listener(){public void onResult(int i,Socks5Probe.Result r){}public void onProgress(int d,int t,int g){}public void onFinished(ScanCheckpoint s,String m){}});
        if(!diskEntered.await(3,TimeUnit.SECONDS))throw new AssertionError("Storage fixture never entered");
        FutureTask<Boolean> stop=new FutureTask<>(()->{waiting.requestStop();return true;});new Thread(stop).start();
        check(stop.get(1,TimeUnit.SECONDS),"UI stop request never waits for an in-progress disk save");
        diskRelease.countDown();ScanCheckpoint stopped=waiting.pause();
        check(stopped!=null&&stopped.done()==0,"Cancellation during initial persistence preserves every candidate for resume");
        System.out.println("STRESS: 50,000 endpoints; elapsed="+((System.nanoTime()-start)/1000000)+" ms; heap ceiling="+Runtime.getRuntime().maxMemory());
        System.out.println("RESULT: "+checks+" checks passed");
    }
}
