package ir.persiansocks.app;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
/** Data adapters only: upstream collector code is never executed. */
public final class SourceDocuments {
 private SourceDocuments(){}
 public static List<ProxyEndpoint> readJson(InputStream input,ProxyScanner.Source source)throws Exception{
  ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] part=new byte[4096];int n;
  while((n=input.read(part))!=-1){if(b.size()+n>2_000_000)throw new IOException("JSON feed too large");b.write(part,0,n);}
  Object doc=new JSONTokener(b.toString(StandardCharsets.UTF_8.name())).nextValue();
  JSONArray rows=doc instanceof JSONArray?(JSONArray)doc:doc instanceof JSONObject?((JSONObject)doc).optJSONArray("data"):null;
  if(rows==null)throw new IOException("Proxy JSON array expected");
  Map<String,ProxyEndpoint> out=new LinkedHashMap<>();
  for(int i=0;i<rows.length();i++){
   JSONObject row=rows.optJSONObject(i);if(row==null)continue;
   String address=row.optString("proxy","");if(address.isEmpty())address=row.optString("host",row.optString("ip",""))+":"+row.optString("port","");
   JSONArray types=row.optJSONArray("protocols");if(types==null)types=new JSONArray().put(row.optString("protocol",source.type.name()));
   for(int t=0;t<types.length();t++)try{
    ProxyEndpoint.Type kind=ProxyEndpoint.Type.valueOf(types.getString(t).toUpperCase(Locale.ROOT));
    ProxyEndpoint e=ProxyEndpoint.parse(address);if(e==null||!e.isPublicIpv4())continue;e=e.withType(kind);
    if(source.country.isEmpty()||source.country.equals(CountryLookup.lookup(e.host)))out.putIfAbsent(e.key(),e);
   }catch(Exception ignored){}
  }return new ArrayList<>(out.values());
 }
 public static List<ProxyEndpoint> country(List<ProxyEndpoint> rows,String country){if(country.isEmpty())return rows;List<ProxyEndpoint> out=new ArrayList<>();for(ProxyEndpoint p:rows)if(country.equals(CountryLookup.lookup(p.host)))out.add(p);return out;}
 public static List<ProxyEndpoint> iranFirst(List<ProxyEndpoint> rows){List<ProxyEndpoint> out=new ArrayList<>(rows.size());for(ProxyEndpoint p:rows)if("IR".equals(CountryLookup.lookup(p.host)))out.add(p);for(ProxyEndpoint p:rows)if(!"IR".equals(CountryLookup.lookup(p.host)))out.add(p);return out;}
 public static ProxyEndpoint.Type scheme(String text,int start,ProxyEndpoint.Type fallback){
  if(start>=3&&text.substring(start-3,start).equals("://")){int i=start-4;while(i>=0&&Character.isLetterOrDigit(text.charAt(i)))i--;String name=text.substring(i+1,start-3).toUpperCase(Locale.ROOT);try{return ProxyEndpoint.Type.valueOf(name);}catch(Exception e){return null;}}
  return fallback;
 }
}
