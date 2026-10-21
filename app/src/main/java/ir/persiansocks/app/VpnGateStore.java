package ir.persiansocks.app;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
/** Independent directory cache; an advertised node is never inserted into the healthy proxy index. */
public final class VpnGateStore {
 private final File file,journal;private final Map<String,VpnGateProfile> rows=new LinkedHashMap<>();private long revision;
 public VpnGateStore(File directory){file=new File(directory,"vpngate-directory.json");journal=new File(directory,"vpngate-results.jsonl");}
 public synchronized void load()throws IOException{if(!file.isFile())return;if(file.length()>16*1024*1024)throw new IOException("VPN Gate cache too large");String text;text=new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);try{rows.clear();JSONArray list=new JSONArray(text);for(int i=0;i<Math.min(VpnGateProfile.MAX_PROFILES,list.length());i++)try{VpnGateProfile p=VpnGateProfile.fromJson(list.getJSONObject(i));rows.put(p.key(),p);}catch(Exception ignored){}if(journal.isFile()&&journal.length()<=8*1024*1024)try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(journal),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null)try{JSONObject o=new JSONObject(line);evidence(o.getString("key"),o.getBoolean("ok"),o.optString("error"),o.getLong("at"),o.optLong("latency",-1));}catch(Exception ignored){}}revision++;}catch(Exception e){throw new IOException("Invalid VPN Gate cache",e);}}
 public synchronized void replace(List<VpnGateProfile> profiles)throws IOException{
  long cutoff=System.currentTimeMillis()-7L*86400000;rows.values().removeIf(p->p.lastSeenAt<cutoff);
  for(VpnGateProfile p:profiles){VpnGateProfile old=rows.get(p.key());if(old!=null){p.copyEvidence(old);if(p.lazy()&&!old.lazy()&&p.configHash.equals(old.configHash))p=resolvedWithMetadata(old,p);}rows.put(p.key(),p);}
  while(rows.size()>VpnGateProfile.MAX_PROFILES){String oldest=Collections.min(rows.values(),Comparator.comparingLong(p->p.lastSeenAt)).key();rows.remove(oldest);}revision++;save();
 }
 private VpnGateProfile resolvedWithMetadata(VpnGateProfile old,VpnGateProfile fresh){try{JSONObject json=fresh.json();json.put("config",old.config);return VpnGateProfile.fromJson(json);}catch(Exception ignored){return fresh;}}
 public synchronized void resolved(VpnGateProfile p){rows.values().removeIf(row->row.port==0&&row.ip.equals(p.ip)&&row.configHash.equals(p.configHash));rows.put(p.key(),p);int full=0;for(VpnGateProfile row:new ArrayList<>(rows.values()))if(!row.lazy()&&++full>256&&!row.poolCommit.isEmpty()&&!row.key().equals(p.key()))try{JSONObject o=row.json();o.put("config","");rows.put(row.key(),VpnGateProfile.fromJson(o));}catch(Exception ignored){}revision++;}
 public synchronized void evidence(String key,boolean success,String error,long at,long latency){VpnGateProfile p=rows.get(key);if(p!=null&&at>=p.vpnTestedAt){p.vpnTestedAt=at;p.vpnSuccess=success;p.vpnError=error;p.portLatency=latency;revision++;}}
 public synchronized void vpnResult(String key,boolean success,String error)throws IOException{
  VpnGateProfile p=rows.get(key);if(p==null)return;if(error.length()>1200)error=error.substring(0,1200);long now=System.currentTimeMillis();evidence(key,success,error,now,p.portLatency);
  if(journal.length()>8*1024*1024){save();try(FileOutputStream clear=new FileOutputStream(journal)){} }
  try{JSONObject row=new JSONObject().put("key",key).put("ok",success).put("error",error).put("at",now).put("latency",p.portLatency);try(FileOutputStream out=new FileOutputStream(journal,true)){out.write((row.toString()+"\n").getBytes(StandardCharsets.UTF_8));out.getFD().sync();}}catch(JSONException e){throw new IOException(e);}
 }
 public synchronized void save()throws IOException{JSONArray list=new JSONArray();List<VpnGateProfile> ordered=new ArrayList<>(rows.values());ordered.sort(Comparator.comparingLong((VpnGateProfile p)->Math.max(p.vpnTestedAt,p.lastSeenAt)).reversed());int full=0;for(VpnGateProfile p:ordered){JSONObject json=p.json();if(!p.lazy()&&++full>256){if(p.poolCommit.isEmpty())continue;try{json.put("config","");}catch(Exception ignored){}}list.put(json);}File pending=new File(file+".tmp");try(FileOutputStream out=new FileOutputStream(pending)){out.write(list.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}if(!pending.renameTo(file))throw new IOException("Unable to save VPN Gate directory");}
 public synchronized List<VpnGateProfile> snapshot(){return new ArrayList<>(rows.values());}
 public synchronized VpnGateProfile get(String key){return rows.get(key);}
 public synchronized long revision(){return revision;}
 public synchronized void tested(String key,long latency,String error){VpnGateProfile p=rows.get(key);if(p!=null){p.portLatency=latency;p.lastError=error;p.portTestedAt=System.currentTimeMillis();revision++;}}
}
