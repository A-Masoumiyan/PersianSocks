package ir.persiansocks.app;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
/** Independent directory cache; an advertised node is never inserted into the healthy proxy index. */
public final class VpnGateStore {
 private final File file;private final Map<String,VpnGateProfile> rows=new LinkedHashMap<>();private long revision;
 public VpnGateStore(File directory){file=new File(directory,"vpngate-directory.json");}
 public synchronized void load()throws IOException{if(!file.isFile())return;if(file.length()>16*1024*1024)throw new IOException("VPN Gate cache too large");String text;text=new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);try{JSONArray list=new JSONArray(text);for(int i=0;i<Math.min(VpnGateProfile.MAX_PROFILES,list.length());i++)try{VpnGateProfile p=VpnGateProfile.fromJson(list.getJSONObject(i));rows.put(p.key(),p);}catch(Exception ignored){}revision++;}catch(Exception e){throw new IOException("Invalid VPN Gate cache",e);}}
 public synchronized void replace(List<VpnGateProfile> profiles)throws IOException{for(VpnGateProfile p:profiles){VpnGateProfile old=rows.get(p.key());if(old!=null){p.portLatency=old.portLatency;p.portTestedAt=old.portTestedAt;}}rows.clear();for(VpnGateProfile p:profiles)rows.put(p.key(),p);revision++;save();}
 public synchronized void save()throws IOException{JSONArray list=new JSONArray();for(VpnGateProfile p:rows.values())list.put(p.json());File pending=new File(file+".tmp");try(FileOutputStream out=new FileOutputStream(pending)){out.write(list.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}if(!pending.renameTo(file))throw new IOException("Unable to save VPN Gate directory");}
 public synchronized List<VpnGateProfile> snapshot(){return new ArrayList<>(rows.values());}
 public synchronized VpnGateProfile get(String key){return rows.get(key);}
 public synchronized long revision(){return revision;}
 public synchronized void tested(String key,long latency,String error){VpnGateProfile p=rows.get(key);if(p!=null){p.portLatency=latency;p.lastError=error;p.portTestedAt=System.currentTimeMillis();revision++;}}
}
