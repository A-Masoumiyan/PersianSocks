package ir.persiansocks.app;

import java.util.*;
import org.json.*;

/** Phone observations only; a conservative score needs repeated, recent evidence. */
public final class QualityStats {
    private static final int WINDOW=32;
    private final ArrayDeque<Long> latencies=new ArrayDeque<>();
    private int successes,failures,disconnects,sessions;
    private long stableMs,lastAt;
    public synchronized void probe(boolean success,long latency,long now){
        if(lastAt>0&&now-lastAt>7L*86400000){successes=failures=disconnects=sessions=0;stableMs=0;latencies.clear();}
        if(successes+failures>=128){successes/=2;failures/=2;}
        if(success){successes++;if(latency>0){if(latencies.size()==WINDOW)latencies.removeFirst();latencies.addLast(latency);}}
        else failures++;
        lastAt=now;
    }
    public synchronized void session(long durationMs,boolean remoteFailure,long now){sessions=Math.min(128,sessions+1);stableMs=Math.min(7L*86400000,stableMs+Math.max(0,durationMs));if(remoteFailure)disconnects=Math.min(128,disconnects+1);lastAt=now;}
    public synchronized int samples(){return successes+failures;}
    public synchronized long median(){if(latencies.isEmpty())return -1;Long[] a=latencies.toArray(new Long[0]);Arrays.sort(a);return a[a.length/2];}
    public synchronized long jitter(){if(latencies.size()<2)return -1;long m=median();long[] a=new long[latencies.size()];int i=0;for(long n:latencies)a[i++]=Math.abs(n-m);Arrays.sort(a);return a[a.length/2];}
    public synchronized double score(long now){
        int n=successes+failures;if(n==0)return 0;
        double p=(double)successes/n,z=1.96,z2=z*z;
        double lower=(p+z2/(2*n)-z*Math.sqrt((p*(1-p)+z2/(4*n))/n))/(1+z2/n);
        double age=Math.exp(-Math.max(0,now-lastAt)/(3.0*86400000));
        double delay=median()<0?0:1.0/(1+median()/1500.0+Math.max(0,jitter())/750.0);
        double stability=sessions==0?0:(1.0/(1+(double)disconnects/sessions))*Math.min(1,stableMs/(sessions*300000.0));
        return age*(80*lower+12*delay*Math.min(1,n/5.0)+8*stability);
    }
    public synchronized JSONObject json(){JSONObject o=new JSONObject();try{o.put("success",successes).put("failure",failures).put("disconnects",disconnects).put("sessions",sessions).put("stableMs",stableMs).put("lastAt",lastAt).put("latencies",new JSONArray(latencies));}catch(Exception e){throw new IllegalStateException(e);}return o;}
    public static QualityStats fromJson(JSONObject o){QualityStats s=new QualityStats();s.successes=Math.max(0,o.optInt("success"));s.failures=Math.max(0,o.optInt("failure"));s.disconnects=Math.max(0,o.optInt("disconnects"));s.sessions=Math.max(0,o.optInt("sessions"));s.stableMs=Math.max(0,o.optLong("stableMs"));s.lastAt=o.optLong("lastAt");JSONArray a=o.optJSONArray("latencies");if(a!=null)for(int i=Math.max(0,a.length()-WINDOW);i<a.length();i++)if(a.optLong(i)>0)s.latencies.add(a.optLong(i));return s;}
}
