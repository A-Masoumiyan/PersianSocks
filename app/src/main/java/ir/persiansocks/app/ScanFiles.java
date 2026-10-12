package ir.persiansocks.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Separate atomic files keep a refreshed catalog from replacing a paused scan's inputs. */
public final class ScanFiles {
    private final Path directory;
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
        JSONObject meta=new JSONObject();meta.put("count",unique.size());write("catalog-meta.json",meta);
    }
    public synchronized List<ProxyEndpoint> catalog() throws Exception {
        Path file = directory.resolve("proxy-catalog.json"); if (!Files.exists(file)) return new ArrayList<>();
        JSONArray rows = new JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getJSONArray("items");
        Map<String, ProxyEndpoint> result = new LinkedHashMap<>();
        for (int i = 0; i < rows.length(); i++) { ProxyEndpoint p = ProxyEndpoint.parse(rows.getString(i)); if (p != null) result.putIfAbsent(p.key(), p); }
        return new ArrayList<>(result.values());
    }
    private static final Object CHECKPOINT_LOCK=new Object();
    private static final Map<Path,java.lang.ref.WeakReference<ScanCheckpoint>> ACTIVE=new HashMap<>();
    public void activate(ScanCheckpoint checkpoint){synchronized(CHECKPOINT_LOCK){ACTIVE.put(directory,new java.lang.ref.WeakReference<>(checkpoint));}}
    public void save(ScanCheckpoint checkpoint) throws Exception {
        synchronized(CHECKPOINT_LOCK){
            if(ACTIVE.containsKey(directory)&&ACTIVE.get(directory).get()!=checkpoint)return;
            Files.createDirectories(directory);Path target=directory.resolve("scan-checkpoint.json"),temp=directory.resolve("scan-checkpoint.json.tmp");
            try(FileOutputStream raw=new FileOutputStream(temp.toFile());BufferedWriter out=new BufferedWriter(new OutputStreamWriter(raw,StandardCharsets.UTF_8))){checkpoint.writeRows(out);out.flush();raw.getFD().sync();}
            try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException ex){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
        }
    }
    public synchronized ScanCheckpoint checkpoint() throws Exception {
        Path file = directory.resolve("scan-checkpoint.json"); if (!Files.exists(file)) return null;
        try(BufferedReader in=Files.newBufferedReader(file,StandardCharsets.UTF_8)){
            String first=in.readLine();if(first==null)return null;JSONObject header=new JSONObject(first);
            return header.optInt("format",0)==2?ScanCheckpoint.readRows(header,in):ScanCheckpoint.fromJson(header);
        }
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
            boolean complete;
            try(BufferedReader in=Files.newBufferedReader(file,StandardCharsets.UTF_8)){
                JSONObject head=new JSONObject(in.readLine());
                complete=head.optInt("format",0)==2?head.optInt("done",-1)==head.optInt("total",-2):ScanCheckpoint.fromJson(head).complete();
            }
            if(complete){Files.deleteIfExists(file);ACTIVE.remove(directory);}
        }
    }
}
