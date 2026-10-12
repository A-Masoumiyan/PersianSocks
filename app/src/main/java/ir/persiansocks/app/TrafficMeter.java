package ir.persiansocks.app;

import java.util.Locale;

/** Monotonic session totals survive engine replacement during proxy failover. */
public final class TrafficMeter {
    public static final class Snapshot {
        public final long downloaded, uploaded;
        public final double downPerSecond, upPerSecond;
        public Snapshot(long downloaded,long uploaded,double down,double up){
            this.downloaded=downloaded;this.uploaded=uploaded;downPerSecond=down;upPerSecond=up;
        }
        public String rates(){return "↓ "+bytes(downPerSecond)+"/s   ↑ "+bytes(upPerSecond)+"/s";}
    }
    private long downloaded,uploaded,previousDown,previousUp,time;
    public TrafficMeter(long downloaded,long uploaded){this.downloaded=Math.max(0,downloaded);this.uploaded=Math.max(0,uploaded);}
    public void newSession(){downloaded=uploaded=0;beginEngine(0);}
    public void beginEngine(long now){previousDown=previousUp=0;time=now;}
    public Snapshot sample(long down,long up,long now){
        down=Math.max(0,down);up=Math.max(0,up);
        long d=down>=previousDown?down-previousDown:down,u=up>=previousUp?up-previousUp:up;
        downloaded+=d;uploaded+=u;
        double seconds=(now-time)/1e9;
        previousDown=down;previousUp=up;time=now;
        return new Snapshot(downloaded,uploaded,seconds>0?d/seconds:0,seconds>0?u/seconds:0);
    }
    public Snapshot stopped(){return new Snapshot(downloaded,uploaded,0,0);}
    public static String bytes(double value){
        if(value<1000)return String.format(Locale.US,"%.0f B",Math.max(0,value));
        if(value<1_000_000)return String.format(Locale.US,"%.1f KB",value/1000);
        if(value<1_000_000_000)return String.format(Locale.US,"%.2f MB",value/1_000_000);
        return String.format(Locale.US,"%.2f GB",value/1_000_000_000);
    }
}
