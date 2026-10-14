import ir.persiansocks.app.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class ScanConcurrencyHarness {
    static void run(boolean foreground,boolean connecting,int limit)throws Exception {
        PreparedScanner.foreground(foreground);PreparedScanner.connectionPending(connecting);PreparedScanner scanner=new PreparedScanner();List<ProxyEndpoint> rows=new ArrayList<>();for(int i=1;i<=48;i++)rows.add(ProxyEndpoint.parse("8.8.8.8:"+i));
        ScanCheckpoint state=new ScanCheckpoint(rows,new ProxyScanner.Options(100,false,true),"HEALTHY");AtomicInteger current=new AtomicInteger(),peak=new AtomicInteger();CountDownLatch finished=new CountDownLatch(1),cohort=new CountDownLatch(Math.min(12,limit));AtomicReference<String> result=new AtomicReference<>();
        scanner.start(state,s->{},(p,slow,stop,sockets)->{int n=current.incrementAndGet();peak.accumulateAndGet(n,Math::max);try{cohort.countDown();if(!cohort.await(2,TimeUnit.SECONDS))throw new AssertionError("Starting concurrency was not reached");Thread.sleep(90);}catch(InterruptedException e){return new Socks5Probe.Result(null,Socks5Probe.Failure.CANCELLED,false,false);}finally{current.decrementAndGet();}return new Socks5Probe.Result(null,Socks5Probe.Failure.TCP_TIMEOUT,false,false);},new PreparedScanner.Listener(){public void onResult(int i,Socks5Probe.Result r){}public void onProgress(int d,int t,int g){}public void onFinished(ScanCheckpoint s,String m){result.set(m);finished.countDown();}});
        if(!finished.await(10,TimeUnit.SECONDS)||!state.complete()||peak.get()>limit||peak.get()<Math.min(12,limit))throw new AssertionError("Concurrency cap: "+peak.get()+" / "+limit+" "+result.get());System.out.println("PASS: "+(connecting?"VPN setup":foreground?"foreground":"background")+" scan concurrency "+peak.get()+" <= "+limit);
    }
    public static void main(String[] args)throws Exception {try{run(true,false,24);run(false,false,10);run(true,true,4);}finally{PreparedScanner.foreground(true);PreparedScanner.connectionPending(false);}}
}
