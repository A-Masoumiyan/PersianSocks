package ir.persiansocks.app;
public final class AdaptiveScanHarness {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static Socks5Probe.Result success(long ms){return new Socks5Probe.Result(new ProxyEndpoint("8.8.8.8",1080,ms,false),Socks5Probe.Failure.NONE,true,true);}
    private static Socks5Probe.Result failed(Socks5Probe.Failure failure){return new Socks5Probe.Result(null,failure,false,false);}
    private static void samples(AdaptiveScanPolicy policy,Socks5Probe.Result result){for(int i=0;i<12;i++)policy.observe(result);}
    public static void main(String[] args){
        AdaptiveScanPolicy p=new AdaptiveScanPolicy();check(p.limit(24)==12,"Faster base starts at twelve");check(p.limit(10)==10&&p.limit(4)==4,"Background and VPN setup caps still apply");
        samples(p,failed(Socks5Probe.Failure.TCP_TIMEOUT));check(p.limit(24)==12,"Dead candidate TCP timeout is not evidence to ramp up or congest the path");
        samples(p,success(400));check(p.limit(24)==15,"Healthy cohorts increase capacity sooner");for(int i=0;i<20;i++)samples(p,success(200));check(p.limit(100)==24,"Repeated healthy cohorts remain bounded");
        samples(p,failed(Socks5Probe.Failure.HTTPS_TIMEOUT));check(p.limit(24)==12,"Congested route backs off from the upper bound");
        samples(p,failed(Socks5Probe.Failure.SOCKS_TIMEOUT));check(p.limit(24)==6,"Slow proxy transport backs off again");for(int i=0;i<10;i++)samples(p,failed(Socks5Probe.Failure.HTTPS_TIMEOUT));check(p.limit(24)==2,"Sustained congestion keeps the lower safety bound");
        samples(p,success(100));check(p.limit(24)==5,"Fresh good evidence recovers from the minimum");
        AdaptiveScanPolicy cancelled=new AdaptiveScanPolicy();for(int i=0;i<11;i++)cancelled.observe(success(100));for(int i=0;i<100;i++)cancelled.observe(failed(Socks5Probe.Failure.CANCELLED));check(cancelled.limit(24)==12,"Cancellation does not complete an adaptive cohort");cancelled.observe(success(100));check(cancelled.limit(24)==15,"A completed cohort still increases capacity after cancellation");
        AdaptiveScanPolicy.Budget slow=p.budget(true);check(slow.connectMs==6000&&slow.readMs==9000&&slow.totalMs==20000,"Slow mode keeps the original test budgets");
        AdaptiveScanPolicy.Budget fast=p.budget(false);check(fast.connectMs>=1800&&fast.readMs>=3500&&fast.totalMs<=20000,"Normal probe deadlines retain bounded floors");
        System.out.println("PASS: "+checks+" adaptive base/ramp/backoff/cancellation/deadline checks");
    }
}
