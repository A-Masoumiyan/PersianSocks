package ir.persiansocks.app;

/** Scan profiles use v18 timeouts and fixed bounded capacity: dead proxies cannot throttle the entire list. */
public final class AdaptiveScanPolicy {
    public static final class Budget {
        public final int connectMs,readMs,totalMs;
        Budget(int c,int r,int t){connectMs=c;readMs=r;totalMs=t;}
    }
    private static final ThreadLocal<Budget> attemptBudget=new ThreadLocal<>();
    private int limit,window,slowSamples,successful;private double latency=1500;
    private final int minimum,maximum,increase,scale;
    private boolean scanProfile;
    public AdaptiveScanPolicy(){this(12,2,24,3,1);}
    public AdaptiveScanPolicy(boolean deep){this(deep?24:216,deep?24:216,deep?24:216,0,1);scanProfile=true;}
    private AdaptiveScanPolicy(int start,int floor,int ceiling,int step,int multiplier){limit=start;minimum=floor;maximum=ceiling;increase=step;scale=multiplier;}
    public static int hardCap(boolean deep,boolean foreground,boolean connecting){return connecting?(deep?1:8):foreground?(deep?24:216):(deep?6:54);}
    public synchronized int limit(int hardCap){return Math.max(1,Math.min(hardCap,limit*scale));}
    public synchronized Budget budget(boolean slow){if(scanProfile)return new Budget(slow?6000:2500,slow?9000:4500,slow?20000:11000);if(slow)return new Budget(6000,9000,20000);int c=(int)Math.max(1800,Math.min(6000,latency*1.5));int r=(int)Math.max(3500,Math.min(9000,latency*2.5));return new Budget(c,r,Math.min(20000,c+r+4000));}
    public synchronized void observe(Socks5Probe.Result r){
        if(scanProfile||r.failure==Socks5Probe.Failure.CANCELLED)return;
        window++;
        if(r.endpoint!=null){successful++;latency=0.8*latency+0.2*Math.min(12000,r.endpoint.latencyMs);if(r.endpoint.latencyMs>4500)slowSamples++;}
        else if(r.failure==Socks5Probe.Failure.HTTPS_TIMEOUT||r.failure==Socks5Probe.Failure.SOCKS_TIMEOUT)slowSamples++;
        if(window>=12){if(slowSamples>=5)limit=Math.max(minimum,limit/2);else if(successful>=3)limit=Math.min(maximum,limit+increase);window=slowSamples=successful=0;}
    }
    static void enter(Budget b){attemptBudget.set(b);}static void leave(){attemptBudget.remove();}
    static Budget current(boolean slow){Budget b=attemptBudget.get();return b==null?new Budget(slow?6000:2500,slow?9000:4500,slow?20000:11000):b;}
}
