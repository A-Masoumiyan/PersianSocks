import ir.persiansocks.app.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class AvailabilityHarness {
    static int checks;
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;}
    static ProxyEndpoint good(long time){return new ProxyEndpoint("18.157.123.132",3128,613,false,7,7,time).withType(ProxyEndpoint.Type.HTTP);}
    public static void main(String[] args)throws Exception {
        for(Socks5Probe.Failure f:Socks5Probe.Failure.values()){
            if(f==Socks5Probe.Failure.NONE||f==Socks5Probe.Failure.CANCELLED)continue;
            ProxyIndex index=new ProxyIndex();ProxyEndpoint old=good(1);index.accept(old);
            ScanCheckpoint state=new ScanCheckpoint(Collections.singletonList(old),new ProxyScanner.Options(100,false),"HEALTHY");
            state.record(0,new Socks5Probe.Result(null,f,false,false));index.apply(state);
            check(index.find(old.key())==null,"Completed failure is not a healthy result: "+f);
            check(index.rejected(old.key()),"Failure leaves a tombstone: "+f);
            check(!index.accept(old),"Old success cannot revive a newer failure: "+f);
            ProxyIndex restarted=new ProxyIndex();for(ProxyIndex.Record row:index.records())restarted.restore(row.json());
            check(restarted.find(old.key())==null&&restarted.rejected(old.key()),"Failure survives restart: "+f);
        }
        ProxyIndex index=new ProxyIndex();ProxyEndpoint old=good(100);index.accept(old);
        Socks5Probe.Result timeout=new Socks5Probe.Result(null,Socks5Probe.Failure.HTTPS_TIMEOUT,true,true);
        Socks5Probe.Result interrupted=ProbeFailurePolicy.observed(timeout,false);
        ScanCheckpoint checkpoint=new ScanCheckpoint(Collections.singletonList(old),new ProxyScanner.Options(100,false),"HEALTHY");
        check(interrupted.failure==Socks5Probe.Failure.CANCELLED&&!checkpoint.record(0,interrupted),"Lost physical route is pending, not a failed proxy");
        index.apply(checkpoint);check(index.find(old.key())==old,"Lost route preserves the previously verified candidate");
        check(ProbeFailurePolicy.observed(timeout,true)==timeout,"Stable physical route preserves actual failure evidence");
        check(!ProbeFailurePolicy.invalidates(Socks5Probe.Failure.CANCELLED),"User cancellation does not reject a proxy");
        index.reject(old.key(),200);check(index.accept(good(201)),"A genuinely newer success restores availability");
        ProxyTestResult last=new ProxyTestResult();last.probeError="مهلت HTTPS تمام شد";last.probeAt=200;
        check(last.failedAfter(old),"Old-release cached success is rejected when a later explicit probe failed");
        check(!last.failedAfter(good(201))&&last.pingValue(good(201)).equals("613 ms"),"A newer successful scan supersedes an older failed ping label");
        for(String error:new String[]{"تست متوقف شد","Cancelled","شبکهٔ تست قطع شد","Offline","Network changed","DNS resolve failed"}){last.probeError=error;check(!last.failedAfter(old),"Old cancellation/network notes do not erase availability: "+error);}
        List<ProxyEndpoint> rows=new ArrayList<>();for(int i=1;i<=100;i++)rows.add(new ProxyEndpoint("11.1.1."+i,1080,i,false,7,7,1));
        AtomicInteger scores=new AtomicInteger();ProxyResults.quality(p->{scores.incrementAndGet();return p.latencyMs;});
        List<ProxyEndpoint> ranked=ProxyResults.visible(rows,"ALL",ProxyResults.Sort.QUALITY);
        check(scores.get()==rows.size(),"Quality score is computed once per row instead of once per sort comparison");
        check(ranked.get(0).latencyMs==100&&ranked.get(99).latencyMs==1,"Memoized quality scores keep descending ranking");
        ProxyResults.quality(p->0);
        System.out.println("PASS: "+checks+" availability, stale-label, route-loss and ranking checks");
    }
}
