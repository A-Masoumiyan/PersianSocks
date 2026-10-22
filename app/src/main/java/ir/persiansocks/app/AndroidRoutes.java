package ir.persiansocks.app;
import android.content.Context;
import java.util.*;
public final class AndroidRoutes {
 private AndroidRoutes(){}
 public static String mode(Context c){return c.getSharedPreferences("persiansocks",0).getString("routing-mode","apps");}
 public static DomainRoutes load(Context c){if(!mode(c).equals("domains"))return new DomainRoutes(Collections.emptyList());DomainRoutes routes=new DomainRoutes(Arrays.asList(c.getSharedPreferences("persiansocks",0).getString("route-domains","").split("\n")));if(!routes.restricted)throw new IllegalArgumentException("حداقل یک دامنه انتخاب کن");return routes;}
 // App selection controls Android UID routing, not the service used by our internal
 // tunnel probe. An HTTP rejection from chatgpt.com must not gate every connection.
 public static boolean scopedVerification(Context c){return load(c).restricted;}
 public static ServiceTargets.Target[] verification(Context c,ServiceTargets.Target[] fallback){DomainRoutes r=load(c);if(!r.restricted)return fallback;for(ServiceTargets.Target t:fallback)if(r.proxy(t.host))return new ServiceTargets.Target[]{t};return new ServiceTargets.Target[]{new ServiceTargets.Target(0,"مسیر انتخابی",r.domains.iterator().next(),"/")};}
 public static void chatgpt(Context c,boolean enabled){
  android.content.SharedPreferences prefs=c.getSharedPreferences("persiansocks",0);
  String current=mode(c);android.content.SharedPreferences.Editor edit=prefs.edit();
  if(enabled){if(!current.equals("chatgpt"))edit.putString("routing-before-chatgpt",current);edit.putString("routing-mode","chatgpt");}
  else if(current.equals("chatgpt")){String previous=prefs.getString("routing-before-chatgpt","apps");edit.putString("routing-mode",previous.equals("domains")?"domains":"apps");}
  edit.apply();
 }
 public static String localCredential(Context c,String name){
  android.content.SharedPreferences p=c.getSharedPreferences("persiansocks-local-proxy",0);String value=p.getString(name,"");if(!value.matches("[a-f0-9]{64}")){byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.US,"%02x",b&255));value=s.toString();p.edit().putString(name,value).apply();}return value;
 }
 public static boolean proxyOnly(Context c){return c.getSharedPreferences("persiansocks",0).getBoolean("proxy-only",false);}
 public static boolean kill(Context c){return !proxyOnly(c)&&!c.getSharedPreferences("persiansocks",0).getBoolean("kill-switch-off",false);}
}
