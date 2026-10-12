import ir.persiansocks.app.*;
import java.util.*;

/** User-facing limits composed with real result ranking, widget selection and failover. */
public final class PingFilterHarness {
    static int passed;
    static void check(boolean ok,String name) {
        if (!ok) throw new AssertionError(name);
        passed++; System.out.println("PASS: " + name);
    }
    static ProxyEndpoint endpoint(int index,long latency,int services) {
        return new ProxyEndpoint("8.8.4."+index,1080,latency,false,services,7,100+index);
    }
    static List<ProxyEndpoint> visible(List<ProxyEndpoint> list,ProxyResults.PingLimit ping) {
        return ProxyResults.visible(list,"ALL",ProxyResults.Sort.FASTEST,ProxyResults.Protocol.ALL,ping);
    }
    public static void main(String[] args) {
        DistributionPolicy.setUserTlsOnly(false);
        ProxyEndpoint p499=endpoint(1,499,7),p500=endpoint(2,500,1),p999=endpoint(3,999,7).withType(ProxyEndpoint.Type.HTTP);
        ProxyEndpoint p1000=endpoint(4,1000,7),p1499=endpoint(5,1499,7),p1500=endpoint(6,1500,7);
        ProxyEndpoint p1999=endpoint(7,1999,7),p2000=endpoint(8,2000,7),unknown=endpoint(9,-1,7);
        List<ProxyEndpoint> saved=new ArrayList<>(Arrays.asList(p2000,p999,p499,p1500,p500,p1499,p1000,p1999,unknown));
        List<ProxyEndpoint> original=new ArrayList<>(saved);
        check(visible(saved,ProxyResults.PingLimit.UNDER_500).equals(Arrays.asList(p499)),"Under 500 excludes the exact boundary and unknown ping");
        check(visible(saved,ProxyResults.PingLimit.UNDER_1000).equals(Arrays.asList(p499,p500,p999)),"Under 1000 includes 999, excludes 1000, and preserves ranking");
        check(visible(saved,ProxyResults.PingLimit.UNDER_1500).size()==5,"Under 1500 excludes the exact boundary");
        check(visible(saved,ProxyResults.PingLimit.UNDER_2000).size()==7,"Under 2000 excludes 2000 and unknown");
        check(visible(saved,ProxyResults.PingLimit.NONE).size()==9,"Removing limit restores all verified results including unknown ping");
        check(saved.equals(original),"Changing ping filters never modifies cached results or their ordering");
        check(ProxyResults.visible(saved,"FULL",ProxyResults.Sort.FASTEST,ProxyResults.Protocol.ALL,ProxyResults.PingLimit.UNDER_1000)
            .equals(Arrays.asList(p499,p999)),"Complete-service filter composes with ping limit");
        check(ProxyResults.visible(saved,"FULL",ProxyResults.Sort.RECENT,ProxyResults.Protocol.HTTP,ProxyResults.PingLimit.UNDER_1000)
            .equals(Arrays.asList(p999)),"Type, service, ping and sort compose together");
        check(ProxyResults.visible(saved,"ALL",ProxyResults.Sort.FASTEST,ProxyResults.Protocol.HTTP,ProxyResults.PingLimit.UNDER_500)
            .isEmpty(),"Empty limited result has no fallback to a wrong type or slower endpoint");
        check(visible(Arrays.asList(endpoint(10,0,7)),ProxyResults.PingLimit.UNDER_500).size()==1,"Measured zero is valid, distinct from unknown ping");
        check(ProxyResults.PingLimit.fromMilliseconds(0)==ProxyResults.PingLimit.NONE &&
            ProxyResults.PingLimit.fromMilliseconds(1500)==ProxyResults.PingLimit.UNDER_1500,"Preference value restores the user's ping choice");
        check(ProxyResults.PingLimit.fromMilliseconds(-1)==ProxyResults.PingLimit.NONE &&
            ProxyResults.PingLimit.fromMilliseconds(1234)==ProxyResults.PingLimit.NONE,"Unsupported old preference cannot impose a hidden arbitrary limit");
        ProxyEndpoint updated=p499.withLatency(1500);
        check(visible(Arrays.asList(updated),ProxyResults.PingLimit.UNDER_1500).isEmpty() &&
            visible(Arrays.asList(updated),ProxyResults.PingLimit.UNDER_2000).size()==1,"New single-profile ping measurement immediately changes limit membership");
        AutoScanSelection auto=new AutoScanSelection("FULL",ProxyResults.Sort.FASTEST,ProxyResults.Protocol.ALL,ProxyResults.PingLimit.UNDER_1000);
        for (ProxyEndpoint p:saved) auto.add(p);
        check(auto.best()==p499 && !auto.haveBackups(),"Widget selects only within ping limit and counts only matching backups");
        check(auto.mergeSaved(Collections.emptyList()).size()==9,"Widget still saves all fresh healthy endpoints outside the display limit");
        AutoScanSelection none=new AutoScanSelection("FULL",ProxyResults.Sort.FASTEST,ProxyResults.Protocol.ALL,ProxyResults.PingLimit.UNDER_500);
        none.add(p2000);none.add(unknown);
        check(none.best()==null && none.results().size()==2,"Widget never connects outside the limit when no matching result exists");
        AutoScanSelection legacy=new AutoScanSelection("FULL",ProxyResults.Sort.FASTEST);
        legacy.add(p2000);
        check(legacy.best()==p2000,"Existing unlimited widget behavior remains compatible");
        FailoverPolicy failover=new FailoverPolicy();failover.reset(p499.key());
        check(failover.next(ProxyResults.byPing(saved,ProxyResults.PingLimit.UNDER_1000),p499.key(),"FULL",ProxyResults.Sort.FASTEST,0)==p999,
            "Failover selects a backup matching both service and ping limit");
        failover.reset(p499.key());
        check(failover.next(ProxyResults.byPing(saved,ProxyResults.PingLimit.UNDER_500),p499.key(),"FULL",ProxyResults.Sort.FASTEST,0)==null,
            "Failover stops when the only endpoint within the limit is the failed current endpoint");
        System.out.println("RESULT: "+passed+" checks passed");
    }
}
