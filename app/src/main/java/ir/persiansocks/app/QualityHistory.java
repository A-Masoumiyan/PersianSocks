package ir.persiansocks.app;

import android.content.Context;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;

/** Network category contains no SSID, IMSI or device identifier. Never uploaded. */
public final class QualityHistory {
    private static final int MAX_HISTORY=5000;
    private static int writes;
    private static final Map<String,Map<String,QualityStats>> scopes=new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong revision=new java.util.concurrent.atomic.AtomicLong();
    public static long revision(){return revision.get();}
    private static volatile Context app;
    private static volatile String current="other";
    private QualityHistory(){}
    public static void initialize(Context context){app=context.getApplicationContext();select(context);}
    public static void select(Context context){try{current=PhysicalTestNetwork.capture(context).scope();}catch(Exception ignored){current="other";}load(current);}
    private static void load(String scope){if(app==null||scopes.containsKey(scope))return;ProxyStore.IO.execute(()->{if(scopes.containsKey(scope))return;Map<String,QualityStats> rows=new ConcurrentHashMap<>();for(ProxyDatabase.QualityRow r:ProxyDatabase.get(app).data().recentQuality(scope,MAX_HISTORY))try{QualityStats value=QualityStats.fromJson(new JSONObject(r.payload));if(value.useful())rows.put(r.key,value);}catch(Exception ignored){}scopes.put(scope,rows);revision.incrementAndGet();});}
    public static void probe(PhysicalTestNetwork network,ProxyEndpoint candidate,Socks5Probe.Result result){
        if(app==null||candidate==null||result==null||result.failure==Socks5Probe.Failure.CANCELLED||!network.valid())return;
        if(result.endpoint==null&&ProxyStore.index().find(candidate.key())==null&&scopes.getOrDefault(network.scope(),Collections.emptyMap()).get(candidate.key())==null)return;
        String scope=network.scope();ProxyStore.IO.execute(()->{ensure(scope);if(result.endpoint==null&&!scopes.get(scope).containsKey(candidate.key()))return;QualityStats value=scopes.get(scope).computeIfAbsent(candidate.key(),k->new QualityStats());value.probe(result.endpoint!=null,result.endpoint==null?-1:result.endpoint.latencyMs,System.currentTimeMillis());persist(scope,candidate.key(),value);});
    }
    private static void ensure(String scope){if(scopes.containsKey(scope))return;Map<String,QualityStats> rows=new ConcurrentHashMap<>();for(ProxyDatabase.QualityRow r:ProxyDatabase.get(app).data().recentQuality(scope,MAX_HISTORY))try{QualityStats value=QualityStats.fromJson(new JSONObject(r.payload));if(value.useful())rows.put(r.key,value);}catch(Exception ignored){}scopes.put(scope,rows);revision.incrementAndGet();}
    public static void session(String scope,String key,long ms,boolean failure){if(app==null||key==null||key.isEmpty())return;ProxyStore.IO.execute(()->{ensure(scope);QualityStats value=scopes.get(scope).computeIfAbsent(key,k->new QualityStats());value.session(ms,failure,System.currentTimeMillis());persist(scope,key,value);});}
    private static void persist(String scope,String key,QualityStats value){ProxyDatabase.Data dao=ProxyDatabase.get(app).data();dao.quality(new ProxyDatabase.QualityRow(scope,key,value));Map<String,QualityStats> cache=scopes.get(scope);if(cache.size()>MAX_HISTORY){String oldest=null;long time=Long.MAX_VALUE;for(Map.Entry<String,QualityStats> e:cache.entrySet())if(e.getValue().lastObservedAt()<time){oldest=e.getKey();time=e.getValue().lastObservedAt();}if(oldest!=null)cache.remove(oldest);}if(++writes%128==0)dao.trimQuality(scope,MAX_HISTORY);revision.incrementAndGet();}
    public static QualityStats stats(String key){return scopes.getOrDefault(current,Collections.emptyMap()).get(key);}
    public static double score(ProxyEndpoint p){QualityStats s=stats(p.key());return s==null?0:s.score(System.currentTimeMillis());}
    public static String scope(){return current;}
}
