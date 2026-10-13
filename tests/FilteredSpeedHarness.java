import ir.persiansocks.app.*;
import java.util.*;

public final class FilteredSpeedHarness {
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);System.out.println("PASS: "+label);}
    public static void main(String[] args){
        DistributionPolicy.setUserTlsOnly(false);
        List<ProxyEndpoint> all=new ArrayList<>();
        for(int i=1;i<=350;i++)all.add(new ProxyEndpoint("8.8.4."+i,1080,i,false,7,7,1));
        all.add(new ProxyEndpoint("1.1.1.1",1080,500,false,7,7,1));
        FilteredSpeedBatch batch=new FilteredSpeedBatch(all,"FULL",ProxyResults.Sort.FASTEST,ProxyResults.Protocol.ALL,ProxyResults.PingLimit.UNDER_500);
        check(batch.total()==350,"All matching results included beyond visible cards and Windows page size");
        check(batch.maximumBytes()==7_000_000_000L,"Download budget uses 64-bit bytes for large queues");
        all.clear();
        int visited=0;
        while(batch.next()!=null){visited++;batch.completed();}
        check(visited==350&&batch.done()==350,"Frozen queue survives source changes and completes every entry");
        List<ProxyEndpoint> input=Arrays.asList(new ProxyEndpoint("8.8.8.8",1080,20,false,7,7,1),new ProxyEndpoint("1.1.1.1",1080,30,false,1,7,1).withType(ProxyEndpoint.Type.HTTP));
        batch=new FilteredSpeedBatch(input,"FULL",ProxyResults.Sort.FASTEST,ProxyResults.Protocol.SOCKS,ProxyResults.PingLimit.UNDER_500);
        check(batch.total()==1,"Service, protocol and ping filters compose for batch selection");
        batch.cancel();batch.completed();
        check(batch.next()==null&&batch.done()==0,"Stopping batch prevents remaining tests and late completion");
        batch=new FilteredSpeedBatch(input,"FULL",ProxyResults.Sort.FASTEST,ProxyResults.Protocol.HTTP,ProxyResults.PingLimit.NONE);
        check(batch.total()==0&&batch.next()==null,"Empty combined filter never falls back to unfiltered proxies");
    }
}
