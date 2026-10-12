package ir.persiansocks.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.json.JSONObject;

/** Reads one row at a time instead of holding JSON tree, full string and UTF-8 byte array together. */
public final class ProxyCacheFile {
    private final Path file;
    public ProxyCacheFile(File directory) { file=directory.toPath().resolve("proxy-cache.jsonl"); }
    public boolean exists(){return Files.exists(file);}
    public void load(ProxyIndex index)throws Exception {
        if(!exists())return;
        try(BufferedReader in=Files.newBufferedReader(file,StandardCharsets.UTF_8)) {
            String line;while((line=in.readLine())!=null) {
                if(line.isEmpty())continue;
                try{index.restore(new JSONObject(line));}catch(Exception ignored){}
            }
        }
    }
    public void save(ProxyIndex index)throws Exception {
        Files.createDirectories(file.getParent());Path temp=file.resolveSibling(file.getFileName()+".tmp");
        java.util.List<ProxyIndex.Record> rows=index.records();
        try(FileOutputStream raw=new FileOutputStream(temp.toFile());
            BufferedWriter out=new BufferedWriter(new OutputStreamWriter(raw,StandardCharsets.UTF_8))) {
            for(ProxyIndex.Record row:rows){out.write(row.json().toString());out.newLine();}
            out.flush();raw.getFD().sync();
        }
        try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException ex){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
    }
}
