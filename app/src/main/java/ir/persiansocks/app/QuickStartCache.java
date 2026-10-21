package ir.persiansocks.app;
import android.content.Context;
import java.util.*;
import org.json.*;
/** Small header/list preview; Room remains authoritative and tunnel verification stays mandatory. */
public final class QuickStartCache {
 private QuickStartCache(){}
 private static long savedAt;
 public static List<ProxyEndpoint> load(Context c){List<ProxyEndpoint> rows=new ArrayList<>();try{String raw=c.getSharedPreferences("persiansocks",0).getString("startup-preview","[]");if(raw.length()>48_000)return rows;JSONArray array=new JSONArray(raw);for(int i=0;i<Math.min(12,array.length());i++){ProxyEndpoint p=ProxyEndpoint.fromJson(array.getJSONObject(i));if(p.hasAnyService()&&DistributionPolicy.allows(p))rows.add(p);}}catch(Exception ignored){}return rows;}
 public static void save(Context c,List<ProxyEndpoint> rows){long now=android.os.SystemClock.elapsedRealtime();if(savedAt>0&&now-savedAt<30_000)return;savedAt=now;JSONArray a=new JSONArray();for(int i=0;i<Math.min(12,rows.size());i++)a.put(rows.get(i).json());c.getSharedPreferences("persiansocks",0).edit().putString("startup-preview",a.toString()).apply();}
}
