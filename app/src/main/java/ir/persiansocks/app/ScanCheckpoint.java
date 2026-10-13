package ir.persiansocks.app;

import java.util.*;
import org.json.*;
import java.io.*;

/** Completed indices, rather than a cursor, preserve out-of-order parallel results. */
public final class ScanCheckpoint {
    public static final int BATCH_SIZE=500;
    public final String mode, fingerprint;
    public final boolean slow;
    public final int types;
    private final List<ProxyEndpoint> candidates;
    private final BitSet completed = new BitSet();
    private final Map<Integer, ProxyEndpoint> good = new LinkedHashMap<>();
    private final Map<Integer, Long> finishedAt = new HashMap<>();
    public ScanCheckpoint(List<ProxyEndpoint> input, ProxyScanner.Options options, String mode) {
        this.mode = mode; fingerprint = fingerprint(); slow = options.slow; types = options.types;
        Map<String, ProxyEndpoint> unique = new LinkedHashMap<>();
        for (ProxyEndpoint p : input) if (DistributionPolicy.allows(p) && (p.type.bit & types) != 0) {
            unique.putIfAbsent(p.key(), p); if (unique.size() >= options.maxTests) break;
        }
        candidates = new ArrayList<>(unique.values());
    }
    private ScanCheckpoint(List<ProxyEndpoint> input, String mode, String fingerprint, boolean slow, int types) {
        candidates = new ArrayList<>(input); this.mode = mode; this.fingerprint = fingerprint; this.slow = slow; this.types = types;
    }
    public static String fingerprint() {
        StringBuilder result = new StringBuilder(Boolean.toString(DistributionPolicy.encryptedOnly()));
        for (ServiceTargets.Target target : ServiceTargets.current()) result.append('|').append(target.url());
        return result.toString();
    }
    public boolean compatible() { return fingerprint.equals(fingerprint()); }
    public int total() { return candidates.size(); }
    public synchronized ProxyEndpoint candidate(int index) { return candidates.get(index); }
    public synchronized int done() { return completed.cardinality(); }
    public synchronized boolean complete() { return done() == total(); }
    public synchronized List<Integer> pending() {
        List<Integer> result = new ArrayList<>();
        for (int i = completed.nextClearBit(0); i < total(); i = completed.nextClearBit(i + 1)) result.add(i);
        return result;
    }
    public synchronized boolean record(int index, Socks5Probe.Result result) {
        if (index < 0 || index >= total() || completed.get(index) || result.failure == Socks5Probe.Failure.CANCELLED) return false;
        if (result.endpoint != null && !candidate(index).key().equals(result.endpoint.key())) throw new IllegalArgumentException("Mismatched scan result");
        completed.set(index); finishedAt.put(index, System.currentTimeMillis());
        if (result.endpoint != null) good.put(index, result.endpoint); return true;
    }
    public synchronized List<ProxyEndpoint> good() { return new ArrayList<>(good.values()); }
    public synchronized int goodCount() { return good.size(); }
    public synchronized long finishedAt(int index) { return finishedAt.getOrDefault(index, 0L); }
    public synchronized ProxyEndpoint verified(int index){return good.get(index);}
    public synchronized void forgetVerified(String key,long time){
        for(Map.Entry<Integer,ProxyEndpoint> entry:new ArrayList<>(good.entrySet()))
            if(entry.getValue().key().equals(key)&&Math.max(entry.getValue().testedAt,entry.getValue().latencyAt)<=time){good.remove(entry.getKey());finishedAt.put(entry.getKey(),time);break;}
    }
    public synchronized List<Integer> completedIndices(){List<Integer> out=new ArrayList<>();for(int i=completed.nextSetBit(0);i>=0;i=completed.nextSetBit(i+1))out.add(i);return out;}
    public synchronized ScanCheckpoint copy(){ScanCheckpoint out=new ScanCheckpoint(new ArrayList<>(candidates),mode,fingerprint,slow,types);out.completed.or(completed);out.good.putAll(good);out.finishedAt.putAll(finishedAt);return out;}
    public void writeRows(BufferedWriter writer)throws Exception {
        ScanCheckpoint copy=copy();JSONObject header=new JSONObject();
        header.put("format",3);header.put("mode",mode);header.put("fingerprint",fingerprint);header.put("slow",slow);header.put("types",types);header.put("total",total());
        header.put("done",copy.done());
        writer.write(header.toString());writer.newLine();
        for(int i=0;i<copy.total();i++) {
            JSONObject row=new JSONObject();if(copy.candidate(i)==null){row.put("pruned",true);writer.write(row.toString());writer.newLine();continue;}row.put("key",copy.candidate(i).key());
            if(copy.completed.get(i))row.put("finished",copy.finishedAt.get(i));
            ProxyEndpoint p=copy.good.get(i);if(p!=null)row.put("proxy",p.json());
            writer.write(row.toString());writer.newLine();
        }
    }
    public static ScanCheckpoint readRows(JSONObject header,BufferedReader reader)throws Exception {
        List<ProxyEndpoint> candidates=new ArrayList<>();Set<String> keys=new HashSet<>();
        ScanCheckpoint state=new ScanCheckpoint(candidates,header.getString("mode"),header.getString("fingerprint"),header.getBoolean("slow"),header.getInt("types"));
        String line;int index=0;
        while((line=reader.readLine())!=null){
            JSONObject row=new JSONObject(line);if(row.optBoolean("pruned",false)){state.candidates.add(null);state.completed.set(index++);continue;}ProxyEndpoint raw=ProxyEndpoint.parse(row.getString("key"));
            if(raw==null||!keys.add(raw.key()))throw new IOException("Invalid checkpoint candidate");state.candidates.add(raw);
            long time=row.optLong("finished",0);if(time>0){state.completed.set(index);state.finishedAt.put(index,time);}
            JSONObject payload=row.optJSONObject("proxy");
            if(payload!=null){ProxyEndpoint p=ProxyEndpoint.fromJson(payload);if(time<=0||!p.key().equals(raw.key()))throw new IOException("Invalid checkpoint result");state.good.put(index,p);}
            index++;
        }
        if(index!=header.getInt("total"))throw new IOException("Incomplete checkpoint");return state;
    }
    public synchronized List<ProxyEndpoint> merge(List<ProxyEndpoint> previous) {
        Map<String, ProxyEndpoint> results = new LinkedHashMap<>();
        for (ProxyEndpoint p : previous) if (DistributionPolicy.allows(p)) results.put(p.key(), p);
        for (int i = completed.nextSetBit(0); i >= 0; i = completed.nextSetBit(i + 1)) {
            if(candidate(i)==null)continue;String key = candidate(i).key(); ProxyEndpoint previousResult = results.get(key), fresh = good.get(i);
            if (fresh == null) {
                if (previousResult == null || previousResult.testedAt <= finishedAt.getOrDefault(i, 0L)) results.remove(key);
            } else if (fresh.hasAnyService() && DistributionPolicy.allows(fresh) &&
                (previousResult == null || fresh.testedAt > previousResult.testedAt)) results.put(key, fresh);
        }
        return new ArrayList<>(results.values());
    }

    public synchronized Set<String> failedBatchKeys(){
        Set<String> keys=new HashSet<>();if(!mode.equals("CATALOG"))return keys;
        for(int start=0;start<total();start+=BATCH_SIZE){int end=Math.min(total(),start+BATCH_SIZE);if(completed.nextClearBit(start)<end)continue;
            for(int i=start;i<end;i++)if(candidates.get(i)!=null&&!good.containsKey(i))keys.add(candidates.get(i).key());}
        return keys;
    }
    public synchronized void compactFailed(Set<String> keys){
        for(int i=completed.nextSetBit(0);i>=0;i=completed.nextSetBit(i+1))if(candidates.get(i)!=null&&!good.containsKey(i)&&keys.contains(candidates.get(i).key())){candidates.set(i,null);finishedAt.remove(i);}
    }
    public JSONObject json() throws JSONException {return copy().snapshotJson();}
    private JSONObject snapshotJson() throws JSONException {
        JSONObject out = new JSONObject(); out.put("mode", mode); out.put("fingerprint", fingerprint); out.put("slow", slow); out.put("types", types);
        JSONArray list = new JSONArray(); for (ProxyEndpoint p : candidates) list.put(p==null?JSONObject.NULL:p.key()); out.put("candidates", list);
        out.put("completed", Base64.getEncoder().encodeToString(completed.toByteArray()));
        JSONArray finished = new JSONArray();
        for (Map.Entry<Integer, Long> entry : finishedAt.entrySet()) {
            JSONArray row = new JSONArray(); row.put(entry.getKey()); row.put(entry.getValue()); finished.put(row);
        }
        out.put("finished", finished);
        JSONArray verified = new JSONArray();
        for (Map.Entry<Integer, ProxyEndpoint> entry : good.entrySet()) {
            JSONObject row = new JSONObject(); row.put("index", entry.getKey()); row.put("proxy", entry.getValue().json()); verified.put(row);
        }
        out.put("good", verified); return out;
    }
    public static ScanCheckpoint fromJson(JSONObject input) throws Exception {
        JSONArray raw = input.getJSONArray("candidates"); List<ProxyEndpoint> candidates = new ArrayList<>(); Set<String> keys = new HashSet<>();
        for (int i = 0; i < raw.length(); i++) {
            if(raw.isNull(i)){candidates.add(null);continue;}ProxyEndpoint p = ProxyEndpoint.parse(raw.getString(i));
            if (p == null || !keys.add(p.key())) throw new IllegalArgumentException("Invalid stored candidate");
            candidates.add(p);
        }
        ScanCheckpoint result = new ScanCheckpoint(candidates, input.getString("mode"), input.getString("fingerprint"), input.getBoolean("slow"), input.getInt("types"));
        result.completed.or(BitSet.valueOf(Base64.getDecoder().decode(input.getString("completed"))));
        if (result.completed.length() > result.total()) throw new IllegalArgumentException("Invalid checkpoint indices");
        JSONArray finished = input.getJSONArray("finished");
        for (int i = 0; i < finished.length(); i++) {
            JSONArray row = finished.getJSONArray(i); int index = row.getInt(0); long time = row.getLong(1);
            if (index < 0 || index >= result.total() || !result.completed.get(index) || time <= 0) throw new IllegalArgumentException("Invalid result timestamp");
            result.finishedAt.put(index, time);
        }
        int pruned=0;for(int i=0;i<result.total();i++)if(result.candidate(i)==null){if(!result.completed.get(i))throw new IllegalArgumentException("Pending candidate pruned");pruned++;}
        if (result.finishedAt.size()+pruned != result.done()) throw new IllegalArgumentException("Incomplete result timestamps");
        JSONArray good = input.getJSONArray("good");
        for (int i = 0; i < good.length(); i++) {
            JSONObject row = good.getJSONObject(i); int index = row.getInt("index"); ProxyEndpoint p = ProxyEndpoint.fromJson(row.getJSONObject("proxy"));
            if (index < 0 || index >= result.total() || !result.completed.get(index) || !p.key().equals(result.candidate(index).key()))
                throw new IllegalArgumentException("Invalid verified result");
            result.good.put(index, p);
        }
        return result;
    }
}
