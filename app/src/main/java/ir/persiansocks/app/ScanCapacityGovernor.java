package ir.persiansocks.app;

/** Monotonic-time controller. Failures count as completed work, never as congestion. */
public final class ScanCapacityGovernor {
    public interface Resources { int cap(boolean deep); }
    private final boolean deep;
    private int limit, completed, windowCompleted, previousLimit;
    private long started, windowAt, nextIncrease;
    private double rate, previousRate;
    private boolean saturated;
    public ScanCapacityGovernor(boolean deep,long now) {
        this.deep=deep;limit=deep?12:192;started=windowAt=now;
    }
    public synchronized int capacity(long now,int resourceCap,int inFlight,boolean queued) {
        int cap=Math.max(1,Math.min(deep?24:216,resourceCap));
        if(limit>cap){limit=cap;previousRate=0;previousLimit=0;nextIncrease=now+10000;windowAt=now;windowCompleted=0;saturated=false;}
        saturated|=queued&&inFlight>=limit;
        long elapsed=now-windowAt;
        if(elapsed>=10000){
            rate=windowCompleted*1000.0/elapsed;
            if(saturated&&windowCompleted>0&&now>=nextIncrease){
                if(previousLimit>0&&rate<previousRate*.90){
                    limit=Math.min(cap,previousLimit);previousLimit=0;previousRate=0;nextIncrease=now+30000;
                }else if(limit<cap){
                    previousLimit=limit;previousRate=rate;
                    limit=Math.min(cap,limit+(deep?4:24));nextIncrease=now+10000;
                }
            }
            windowAt=now;windowCompleted=0;saturated=false;
        }
        return limit;
    }
    public synchronized void completed(boolean cancelled){if(!cancelled){completed++;windowCompleted++;}}
    public synchronized double rate(long now){return now>started?completed*1000.0/(now-started):0;}
}
