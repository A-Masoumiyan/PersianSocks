package ir.persiansocks.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Separate atomic files keep a refreshed catalog from replacing a paused scan's inputs. */
public final class ScanFiles {
    private final Path directory;
    private String journalId="";
    private java.lang.ref.WeakReference<ScanCheckpoint> persisted=new java.lang.ref.WeakReference<>(null);
    public ScanFiles(File directory) { this.directory = directory.toPath(); }
    private synchronized void write(String name, JSONObject value) throws Exception {
        Files.createDirectories(directory); Path target = directory.resolve(name), temp = directory.resolve(name + ".tmp");
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(temp.toFile())) { out.write(bytes); out.getFD().sync(); }
        try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
    }
    public synchronized void saveCatalog(List<ProxyEndpoint> catalog) throws Exception {
        JSONObject value = new JSONObject(); JSONArray list = new JSONArray();
        Set<String> unique = new HashSet<>(); for (ProxyEndpoint p : catalog) if (unique.add(p.key())) list.put(p.key());
        value.put("items", list); value.put("receivedAt", System.currentTimeMillis()); write("proxy-catalog.json", value);
        JSONObject meta=new JSONObject();meta.put("count",unique.size());meta.put("baseline",unique.size());write("catalog-meta.json",meta);Files.deleteIfExists(directory.resolve("catalog-pruned.txt"));
    }
    public synchronized void mergeCatalog(List<ProxyEndpoint> incoming)throws Exception {
        Map<String,ProxyEndpoint> unique=new LinkedHashMap<>();for(ProxyEndpoint e:catalog())unique.put(e.key(),e);for(ProxyEndpoint e:incoming)unique.putIfAbsent(e.key(),e);saveCatalog(new ArrayList<>(unique.values()));
    }
    private Set<String> pruned()throws Exception {Path file=directory.resolve("catalog-pruned.txt");if(!Files.exists(file))return Collections.emptySet();return new HashSet<>(Files.readAllLines(file,StandardCharsets.UTF_8));}
    private synchronized void pruneCatalog(Set<String> keys)throws Exception {
        if(keys.isEmpty())return;int count=catalogCount();
        try(FileOutputStream raw=new FileOutputStream(directory.resolve("catalog-pruned.txt").toFile(),true);BufferedWriter out=new BufferedWriter(new OutputStreamWriter(raw,StandardCharsets.UTF_8))){for(String key:keys){out.write(key);out.newLine();}out.flush();raw.getFD().sync();}
        JSONObject meta=new JSONObject(new String(Files.readAllBytes(directory.resolve("catalog-meta.json")),StandardCharsets.UTF_8));meta.put("baseline",meta.optInt("baseline",count));meta.put("count",Math.max(0,count-keys.size()));write("catalog-meta.json",meta);
    }
    public synchronized List<ProxyEndpoint> catalog() throws Exception {
        Path file = directory.resolve("proxy-catalog.json"); if (!Files.exists(file)) return new ArrayList<>();
        JSONArray rows = new JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getJSONArray("items");
        Set<String> removed=pruned();Map<String, ProxyEndpoint> result = new LinkedHashMap<>();
        for (int i = 0; i < rows.length(); i++) { if(removed.contains(rows.getString(i)))continue;ProxyEndpoint p = ProxyEndpoint.parse(rows.getString(i)); if (p != null) result.putIfAbsent(p.key(), p); }
        return new ArrayList<>(result.values());
    }
    private static final Object CHECKPOINT_LOCK=new Object();
    private static final Map<Path,java.lang.ref.WeakReference<ScanCheckpoint>> ACTIVE=new HashMap<>();
    public void activate(ScanCheckpoint checkpoint){synchronized(CHECKPOINT_LOCK){ACTIVE.put(directory,new java.lang.ref.WeakReference<>(checkpoint));}}
    public void save(ScanCheckpoint checkpoint) throws Exception {
        synchronized(CHECKPOINT_LOCK){synchronized(checkpoint){
            if(ACTIVE.containsKey(directory)&&ACTIVE.get(directory).get()!=checkpoint)return;
            Set<String> failed=checkpoint.failedBatchKeys();pruneCatalog(failed);checkpoint.compactFailed(failed);
            Files.createDirectories(directory);Path target=directory.resolve("scan-checkpoint.json"),temp=directory.resolve("scan-checkpoint.json.tmp");
            Path events=directory.resolve("scan-events.jsonl");
            if(persisted.get()!=checkpoint||!Files.exists(target)){
                try(FileOutputStream raw=new FileOutputStream(temp.toFile());BufferedWriter out=new BufferedWriter(new OutputStreamWriter(raw,StandardCharsets.UTF_8))){journalId=UUID.randomUUID().toString();checkpoint.writeRows(out,journalId);out.flush();raw.getFD().sync();}
                try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException ex){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
                Files.deleteIfExists(events);persisted=new java.lang.ref.WeakReference<>(checkpoint);
            }else{
                JSONArray changes=checkpoint.changes();if(changes.length()>0)try(FileOutputStream raw=new FileOutputStream(events.toFile(),true);BufferedWriter out=new BufferedWriter(new OutputStreamWriter(raw,StandardCharsets.UTF_8))){out.write(new JSONObject().put("base",journalId).put("rows",changes).toString());out.newLine();out.flush();raw.getFD().sync();}
            }
            checkpoint.clearChanges();
        }}
    }
    public synchronized ScanCheckpoint checkpoint() throws Exception {
        Path file=directory.resolve("scan-checkpoint.json");if(!Files.exists(file))return null;ScanCheckpoint state;String base;
        try(BufferedReader in=Files.newBufferedReader(file,StandardCharsets.UTF_8)){String first=in.readLine();if(first==null)return null;JSONObject header=new JSONObject(first);base=header.optString("journalId");state=header.optInt("format",0)>=2?ScanCheckpoint.readRows(header,in):ScanCheckpoint.fromJson(header);}
        Path events=directory.resolve("scan-events.jsonl");if(Files.exists(events))try(BufferedReader in=Files.newBufferedReader(events,StandardCharsets.UTF_8)){String line;while((line=in.readLine())!=null){try{JSONObject delta=new JSONObject(line);if(base.equals(delta.optString("base")))state.replay(delta.getJSONArray("rows"));}catch(org.json.JSONException e){if(in.readLine()!=null)throw new IOException("Corrupt scan delta",e);break;}}}
        Set<String> removed=pruned();state.recoverPruned(removed);Path metaFile=directory.resolve("catalog-meta.json");if(Files.exists(metaFile)){JSONObject meta=new JSONObject(new String(Files.readAllBytes(metaFile),StandardCharsets.UTF_8));if(meta.has("baseline")){meta.put("count",Math.max(0,meta.getInt("baseline")-removed.size()));write("catalog-meta.json",meta);}}return state;
    }
    public synchronized int catalogCount()throws Exception {
        Path meta=directory.resolve("catalog-meta.json");
        if(Files.exists(meta))return new JSONObject(new String(Files.readAllBytes(meta),StandardCharsets.UTF_8)).optInt("count",0);
        Path file=directory.resolve("proxy-catalog.json");if(!Files.exists(file))return 0;
        return new JSONObject(new String(Files.readAllBytes(file),StandardCharsets.UTF_8)).getJSONArray("items").length();
    }
    public void discardComplete()throws Exception {
        synchronized(CHECKPOINT_LOCK){
            java.lang.ref.WeakReference<ScanCheckpoint> active=ACTIVE.get(directory);
            if(active!=null&&active.get()!=null&&!active.get().complete())return;
            Path file=directory.resolve("scan-checkpoint.json");if(!Files.exists(file))return;
            boolean complete=active!=null&&active.get()!=null?active.get().complete():checkpoint().complete();
            if(complete){if(Files.exists(directory.resolve("catalog-pruned.txt")))saveCatalog(catalog());Files.deleteIfExists(file);Files.deleteIfExists(directory.resolve("scan-events.jsonl"));ACTIVE.remove(directory);persisted.clear();}
        }
    }
}
