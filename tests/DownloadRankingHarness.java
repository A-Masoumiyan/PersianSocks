import ir.persiansocks.app.*;
import java.util.*;

public final class DownloadRankingHarness {
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);System.out.println("PASS: "+label);}
    static ProxyEndpoint p(String host,long ping,int services){return new ProxyEndpoint(host,1080,ping,false,services,7,1);}
    public static void main(String[] args){
        DistributionPolicy.setUserTlsOnly(false);
        ProxyEndpoint fast=p("1.1.1.1",200,7),tie=p("2.2.2.2",30,7),slow=p("3.3.3.3",10,1),unknown=p("4.4.4.4",1,7),bad=p("5.5.5.5",5,7),zero=p("6.6.6.6",3,7),noPing=p("7.7.7.7",-1,7);
        List<ProxyEndpoint> all=Arrays.asList(unknown,slow,fast,tie,bad,zero,noPing);
        Map<String,Double> speeds=new HashMap<>();speeds.put(fast.key(),8.0);speeds.put(tie.key(),8.0);speeds.put(slow.key(),1.0);speeds.put(bad.key(),Double.NaN);speeds.put(zero.key(),0.0);speeds.put(noPing.key(),9.0);
        List<ProxyEndpoint> ranked=ProxyResults.visible(all,"ALL",ProxyResults.Sort.DOWNLOAD,ProxyResults.Protocol.ALL,ProxyResults.PingLimit.NONE,speeds,false);
        check(ranked.equals(Arrays.asList(noPing,tie,fast,slow,zero,unknown,bad)),"Measured download descending; equal speed uses ping; zero before unmeasured and invalid");
        List<ProxyEndpoint> smooth=ProxyResults.visible(all,"ALL",ProxyResults.Sort.RECENT,ProxyResults.Protocol.ALL,ProxyResults.PingLimit.NONE,speeds,true);
        check(smooth.equals(Arrays.asList(tie,fast,slow)),"Smooth excludes unmeasured, invalid, zero and unknown ping, and prioritizes speed then ping");
        check(ProxyResults.visible(all,"FULL",ProxyResults.Sort.DOWNLOAD,ProxyResults.Protocol.ALL,ProxyResults.PingLimit.UNDER_500,speeds,true).equals(Arrays.asList(tie,fast)),"Quality composes with service and ping filters");
        check(ProxyResults.visible(all,"ALL",ProxyResults.Sort.DOWNLOAD,ProxyResults.Protocol.HTTP,ProxyResults.PingLimit.NONE,speeds,true).isEmpty(),"Quality never bypasses protocol filter");
        FilteredSpeedBatch batch=new FilteredSpeedBatch(all,"ALL",ProxyResults.Sort.FASTEST,ProxyResults.Protocol.ALL,ProxyResults.PingLimit.NONE,speeds,true);
        speeds.clear();check(batch.total()==3&&batch.next()==tie,"Batch freezes the same quality selection and ranking as results");
        check(ProxyResults.visible(all,"ALL",ProxyResults.Sort.DOWNLOAD,ProxyResults.Protocol.ALL,ProxyResults.PingLimit.NONE,speeds,true).isEmpty(),"No speed measurements gives an empty quality filter");
        check(all.get(0)==unknown,"Ranking preserves source order");
    }
}
