package ir.persiansocks.app;

import java.util.*;
import org.json.JSONObject;

/** One keyed cache shared by scanner and UI; failures retain only a small timestamp, not a proxy/card. */
public final class ProxyIndex extends AbstractList<ProxyEndpoint> {
    private final LinkedHashMap<String,ProxyEndpoint> good = new LinkedHashMap<>();
    private final Map<String,Long> changed = new HashMap<>();
    private long revision;
    private java.util.function.Consumer<Record> listener;
    public synchronized void listener(java.util.function.Consumer<Record> listener){this.listener=listener;}
    private void changed(String key,long time,ProxyEndpoint p){if(listener!=null)listener.accept(new Record(key,time,p));}
    public synchronized long revision() { return revision; }
    public synchronized int size() { return good.size(); }
    public synchronized ProxyEndpoint get(int index) { return snapshot().get(index); }
    public synchronized ProxyEndpoint find(String key) { return good.get(key); }
    public synchronized List<ProxyEndpoint> snapshot() { return new ArrayList<>(good.values()); }
    @Override public Iterator<ProxyEndpoint> iterator() { return snapshot().iterator(); }
    @Override public synchronized boolean add(ProxyEndpoint p) { return accept(p); }
    @Override public synchronized void add(int index,ProxyEndpoint p) { accept(p); }
    @Override public synchronized void clear() {for(String key:new ArrayList<>(changed.keySet()))reject(key,System.currentTimeMillis());revision++;}
    @Override public synchronized boolean removeIf(java.util.function.Predicate<? super ProxyEndpoint> predicate) {
        boolean removed=false;
        for (ProxyEndpoint p:snapshot()) if(predicate.test(p)) { reject(p.key(),System.currentTimeMillis()); removed=true; }
        return removed;
    }
    public synchronized boolean accept(ProxyEndpoint p) {
        if (p == null || !p.hasAnyService() || p.latencyMs < 0) return false;
        long time=Math.max(p.testedAt,p.latencyAt);
        if (time < changed.getOrDefault(p.key(),0L)) return false;
        // A success must be newer than a remembered failure, even if the clock has millisecond ties.
        if (!good.containsKey(p.key()) && changed.containsKey(p.key()) && time == changed.get(p.key())) return false;
        good.put(p.key(),p); changed.put(p.key(),time); revision++; changed(p.key(),time,p); return true;
    }
    public synchronized boolean reject(String key,long time) {
        if(time < changed.getOrDefault(key,0L)) return false;
        boolean existed=good.remove(key)!=null;
        changed.put(key,time); revision++; changed(key,time,null); return existed;
    }
    public synchronized boolean rejected(String key) { return changed.containsKey(key) && !good.containsKey(key); }
    public void apply(ScanCheckpoint checkpoint) {
        ScanCheckpoint copy=checkpoint.copy();
        for(int index:copy.completedIndices()) {
            if(copy.candidate(index)==null)continue;
            ProxyEndpoint fresh=copy.verified(index);
            if(fresh!=null)accept(fresh);
            else if(ProbeFailurePolicy.invalidates(copy.failure(index))) { String key=copy.candidate(index).key(); if(find(key)!=null)reject(key,copy.finishedAt(index)); }
        }
    }
    public static final class Record {
        final String key;final long time;final ProxyEndpoint proxy;
        Record(String key,long time,ProxyEndpoint proxy){this.key=key;this.time=time;this.proxy=proxy;}
        public JSONObject json()throws Exception {JSONObject row=new JSONObject();row.put("key",key);row.put("changed",time);if(proxy!=null)row.put("proxy",proxy.json());return row;}
    }
    public synchronized List<Record> records() {
        List<Record> rows=new ArrayList<>();
        for(Map.Entry<String,Long> item:changed.entrySet()) {
            rows.add(new Record(item.getKey(),item.getValue(),good.get(item.getKey())));
        }
        return rows;
    }
    public synchronized void restore(JSONObject row) {
        String key=row.optString("key"); if(ProxyEndpoint.parse(key)==null)return;
        long time=row.optLong("changed",0); if(time < changed.getOrDefault(key,0L))return;
        JSONObject payload=row.optJSONObject("proxy");
        if(payload==null){reject(key,time);return;}
        if(changed.containsKey(key)&&!good.containsKey(key)&&time<=changed.get(key))return;
        ProxyEndpoint p=ProxyEndpoint.fromJson(payload);
        if(!p.key().equals(key)||!p.hasAnyService()||p.latencyMs<0)return;
        good.put(key,p);changed.put(key,Math.max(time,Math.max(p.testedAt,p.latencyAt)));revision++;
    }
}
