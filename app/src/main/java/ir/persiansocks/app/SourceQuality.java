package ir.persiansocks.app;

import android.content.Context;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Local measurements, scoped to network category and test profile. Never uploads telemetry. */
public final class SourceQuality {
    private static final long WEEK=7L*24*60*60*1000;
    private static volatile Context app;
    private static final java.util.concurrent.ConcurrentLinkedQueue<ProxyDatabase.SourceProbeRow> pending=new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.atomic.AtomicBoolean scheduled=new java.util.concurrent.atomic.AtomicBoolean();
    private SourceQuality(){}
    public static void initialize(Context context){
        app=context.getApplicationContext();
        ProxyScanner.sourceObserver((source,rows,duplicates)->{long at=System.currentTimeMillis();ProxyStore.IO.execute(()->{try{recordFetch(ProxyDatabase.get(app),source,rows,at,duplicates);}catch(RuntimeException e){android.util.Log.e("PersianSocks","Source measurements unavailable",e);}});});
    }
    public static void probe(PhysicalTestNetwork network,ScanCheckpoint state,ProxyEndpoint candidate,Socks5Probe.Result result){
        if(app==null||candidate==null||result.failure==Socks5Probe.Failure.CANCELLED||!network.valid())return;
        // A latency retest may carry old service flags. Only fresh full-profile evidence is credited.
        if(state.mode.equals("LATENCY")||state.fingerprint.contains("|ping:true:"))return;
        ProxyDatabase.SourceProbeRow row=new ProxyDatabase.SourceProbeRow();row.key=candidate.key();row.scope=network.scope();row.profile="health:"+state.fingerprint;row.checkedAt=System.currentTimeMillis();row.success=result.endpoint!=null&&result.endpoint.hasAnyService();pending.add(row);scheduleFlush();
    }
    private static void scheduleFlush(){if(scheduled.compareAndSet(false,true))ProxyStore.IO.execute(()->{
        try{ProxyDatabase db=ProxyDatabase.get(app);db.runInTransaction(()->{for(int i=0;i<256;i++){ProxyDatabase.SourceProbeRow row=pending.poll();if(row==null)break;if(db.data().sourceOwners(row.key,row.checkedAt-WEEK)>0)db.data().sourceProbe(row);}});}
        catch(RuntimeException e){pending.clear();android.util.Log.e("PersianSocks","Source measurements unavailable",e);}
        finally{scheduled.set(false);if(!pending.isEmpty())scheduleFlush();}
    });}
    static String id(ProxyScanner.Source source){return digest(source.type.name()+"|"+source.url);}
    private static String digest(String value){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    static void recordFetch(ProxyDatabase db,ProxyScanner.Source source,List<ProxyEndpoint> rows,long at){recordFetch(db,source,rows,at,ProxyScanner.duplicateCount(rows));}
    static void recordFetch(ProxyDatabase db,ProxyScanner.Source source,List<ProxyEndpoint> rows,long at,int duplicates){
        // Order-independent fingerprint: reordering a feed is not new content.
        TreeSet<String> keys=new TreeSet<>();for(ProxyEndpoint p:rows)keys.add(p.key());
        String id=id(source),fingerprint=digest(String.join("\n",keys));
        db.runInTransaction(()->{
            ProxyDatabase.Data dao=db.data();ProxyDatabase.SourceRow row=dao.source(id);if(row==null){row=new ProxyDatabase.SourceRow();row.id=id;}
            row.name=source.name;row.attemptAt=at;row.attempts++;row.failed=keys.isEmpty();
            if(!keys.isEmpty()){
                if(!row.fingerprint.equals(fingerprint))row.changedAt=at;
                row.fingerprint=fingerprint;row.fetchedAt=at;row.fetches++;row.candidates=keys.size();row.duplicates=duplicates;dao.clearOrigins(id);
                List<ProxyDatabase.OriginRow> batch=new ArrayList<>(256);
                for(String key:keys){ProxyDatabase.OriginRow origin=new ProxyDatabase.OriginRow();origin.source=id;origin.key=key;origin.fetchedAt=at;batch.add(origin);if(batch.size()==256){dao.origins(batch);batch.clear();}}
                if(!batch.isEmpty())dao.origins(batch);
            }
            dao.source(row);dao.trimOrigins(at-WEEK);dao.trimSourceProbes(at-WEEK);dao.trimSources(at-30L*24*60*60*1000);
        });
    }
    public static String profile(){return (ScanProbePolicy.profile().only?"ping:":"health:")+ScanCheckpoint.fingerprint();}
    public static String report(Context context,List<ProxyScanner.Source> sources){
        ProxyDatabase.Data dao=ProxyDatabase.get(context).data();long now=System.currentTimeMillis();
        StringBuilder out=new StringBuilder(AppLanguage.t(R.string.ui_source_metrics_scope)).append("\n\n");
        String scope=QualityHistory.scope(),profile=profile();
        for(ProxyScanner.Source source:sources){
            ProxyDatabase.SourceRow row=dao.source(id(source));out.append(source.name).append('\n');
            if(row==null){out.append(AppLanguage.t(R.string.ui_181)).append("\n\n");continue;}
            out.append(AppLanguage.t(R.string.ui_source_fetch_age)).append(": ").append(age(now,row.fetchedAt)).append('\n');
            out.append(AppLanguage.t(R.string.ui_source_changed_age)).append(": ").append(age(now,row.changedAt)).append('\n');
            out.append(AppLanguage.t(R.string.ui_source_candidates)).append(": ").append(row.candidates).append(" • ").append(AppLanguage.t(R.string.ui_source_duplicates)).append(": ").append(dao.sourceOverlap(row.id,now-WEEK)).append('\n');
            out.append(AppLanguage.t(R.string.ui_source_internal_duplicates)).append(": ").append(row.duplicates<0?AppLanguage.t(R.string.ui_181):Integer.toString(row.duplicates)).append('\n');
            int tested=dao.sourceTested(row.id,scope,profile,now-WEEK),passed=dao.sourcePassed(row.id,scope,profile,now-WEEK);
            out.append(AppLanguage.t(R.string.ui_source_yield)).append(": ").append(tested==0?AppLanguage.t(R.string.ui_181):passed+" / "+tested+" ("+Math.round(100.0*passed/tested)+"%)").append('\n');
            out.append(AppLanguage.t(R.string.ui_source_fetches)).append(": ").append(row.fetches).append(" / ").append(row.attempts);
            if(row.failed)out.append(" • ").append(AppLanguage.t(R.string.ui_source_empty));out.append("\n\n");
        }
        return out.toString();
    }
    private static String age(long now,long time){return time<=0?AppLanguage.t(R.string.ui_181):Math.max(0,(now-time)/60000)+" "+AppLanguage.t(R.string.ui_minutes_ago);}
}
