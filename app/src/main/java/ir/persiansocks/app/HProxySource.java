package ir.persiansocks.app;

import java.io.IOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Provider measurements select candidates; they never become local health or ping evidence. */
public final class HProxySource {
    private HProxySource() {}
    public static final int MAX_ROWS=300, MIN_CHECKS=10, MIN_CANDIDATES=20;
    public static final long CACHE_MS=10*60_000L;
    private static final long MAX_AGE_MS=120*60_000L, FUTURE_SKEW_MS=60_000L;
    public interface Downloader { String get() throws Exception; }
    public static final class RateLimited extends IOException {
        public final long delayMs;
        public RateLimited(long delayMs){super("HTTP 429");this.delayMs=delayMs;}
    }
    public static final class Result {
        public final List<ProxyEndpoint> endpoints;
        public final int windowMinutes, rejected;
        public final boolean cached;
        Result(List<ProxyEndpoint> endpoints,int window,int rejected,boolean cached){
            this.endpoints=Collections.unmodifiableList(endpoints);this.windowMinutes=window;
            this.rejected=rejected;this.cached=cached;
        }
    }
    private static final class Cache {
        final String body;final long savedAt,retryAt;
        Cache(String body,long savedAt,long retryAt){this.body=body;this.savedAt=savedAt;this.retryAt=retryAt;}
    }
    private static final Map<String,Cache> CACHE=new ConcurrentHashMap<>();
    private static void remember(String url,Cache cache){
        if(!CACHE.containsKey(url)&&CACHE.size()>=16)CACHE.clear();
        CACHE.put(url,cache);
    }
    public static Result fetch(String url,long now,AtomicBoolean cancelled,Downloader downloader)throws Exception {
        long started=System.nanoTime();
        Cache old=CACHE.get(url);
        if(cancelled.get())throw new IOException("Cancelled");
        if(old!=null&&old.body!=null&&now>=old.savedAt&&now-old.savedAt<CACHE_MS)
            return parse(old.body,now,cancelled,true);
        if(old!=null&&now<old.retryAt){
            if(old.body!=null)return parse(old.body,now,cancelled,true);
            throw new RateLimited(old.retryAt-now);
        }
        try{
            String body=downloader.get();
            // Read-time freshness matters when a response is slow; never cache malformed JSON.
            long receivedAt=now+java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
            Result result=parse(body,receivedAt,cancelled,false);
            if(!cancelled.get())remember(url,new Cache(body,receivedAt,0));
            return result;
        }catch(RateLimited ex){
            long failedAt=now+java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
            remember(url,new Cache(old==null?null:old.body,old==null?0:old.savedAt,
                failedAt+Math.max(1000,Math.min(86_400_000L,ex.delayMs))));
            if(old!=null&&old.body!=null)return parse(old.body,failedAt,cancelled,true);
            throw ex;
        }catch(Exception ex){
            if(old!=null&&old.body!=null&&!cancelled.get())return parse(old.body,now+java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),cancelled,true);
            throw ex;
        }
    }
    private static final class Candidate {
        final ProxyEndpoint endpoint;final long age;final double uptime,latency;final long checks;
        Candidate(ProxyEndpoint endpoint,long age,double uptime,double latency,long checks){
            this.endpoint=endpoint;this.age=age;this.uptime=uptime;this.latency=latency;this.checks=checks;
        }
    }
    public static Result parse(String body,long now,AtomicBoolean cancelled,boolean cached)throws Exception {
        if(cancelled.get())throw new IOException("Cancelled");
        JSONArray rows=new JSONArray(body);
        if(rows.length()>MAX_ROWS)throw new IOException("HProxy row limit exceeded");
        Map<String,Candidate> unique=new HashMap<>();int rejected=0;
        for(int i=0;i<rows.length();i++){
            if(cancelled.get())throw new IOException("Cancelled");
            Candidate candidate=read(rows.optJSONObject(i),now);
            if(candidate==null){rejected++;continue;}
            Candidate old=unique.get(candidate.endpoint.key());
            if(old==null||candidate.age<old.age)unique.put(candidate.endpoint.key(),candidate);
        }
        List<Candidate> eligible=new ArrayList<>(unique.values());
        eligible.sort(Comparator.comparingInt((Candidate c)->c.age<=1_800_000L?0:c.age<=3_600_000L?1:2)
            .thenComparing(Comparator.comparingDouble((Candidate c)->c.uptime).reversed())
            .thenComparing(Comparator.comparingLong((Candidate c)->c.checks).reversed())
            .thenComparingDouble(c->c.latency).thenComparingLong(c->c.age).thenComparing(c->c.endpoint.key()));
        int window=30;
        while(window<120&&countWithin(eligible,window)<MIN_CANDIDATES)window*=2;
        List<ProxyEndpoint> selected=new ArrayList<>();
        for(Candidate c:eligible)if(c.age<=window*60_000L)selected.add(c.endpoint);
        return new Result(selected,window,rejected,cached);
    }
    private static int countWithin(List<Candidate> rows,int minutes){
        int count=0;for(Candidate c:rows)if(c.age<=minutes*60_000L)count++;return count;
    }
    private static double number(JSONObject row,String key){
        Object value=row.opt(key);return value instanceof Number?((Number)value).doubleValue():Double.NaN;
    }
    private static Candidate read(JSONObject row,long now){
        if(row==null||!"alive".equals(row.opt("status")))return null;
        JSONArray protocols=row.optJSONArray("protocols");boolean socks5=false;
        if(protocols!=null)for(int i=0;i<protocols.length();i++)if("socks5".equals(protocols.opt(i)))socks5=true;
        if(!socks5||!(row.opt("ip") instanceof String)||!(row.opt("last_verified_at") instanceof String))return null;
        double port=number(row,"port"),latency=number(row,"latency_ms"),uptime=number(row,"uptime_pct"),daily=number(row,"uptime_24h"),checks=number(row,"verification_count");
        if(!Double.isFinite(port)||port!=Math.rint(port)||port<1||port>65535||
            !Double.isFinite(latency)||latency<0||latency>1500||
            !Double.isFinite(uptime)||uptime<90||uptime>100||
            !Double.isFinite(daily)||daily<90||daily>100||
            !Double.isFinite(checks)||checks<MIN_CHECKS||checks!=Math.rint(checks)||checks>Long.MAX_VALUE)return null;
        ProxyEndpoint endpoint=ProxyEndpoint.parse(row.optString("ip")+":"+(int)port);
        if(endpoint==null||!endpoint.isPublicIpv4()||endpoint.type!=ProxyEndpoint.Type.SOCKS5)return null;
        try{
            long verified=Instant.parse(row.optString("last_verified_at")).toEpochMilli();
            long age=now-verified;
            if(verified<=0||age< -FUTURE_SKEW_MS||age>MAX_AGE_MS)return null;
            return new Candidate(endpoint,Math.max(0,age),Math.min(uptime,daily),latency,(long)checks);
        }catch(Exception ignored){return null;}
    }
    public static long retryDelay(String value,long now){
        try{return Math.max(1000,Math.min(86_400_000L,Math.multiplyExact(Long.parseLong(value.trim()),1000)));}
        catch(Exception ignored){
            try{return Math.max(1000,Math.min(86_400_000L,ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-now));}
            catch(Exception invalid){return 60_000L;}
        }
    }
}
