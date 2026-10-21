package ir.persiansocks.app;
import android.content.Context;
import java.util.*;
public final class AndroidRoutes {
 private AndroidRoutes(){}
 public static String mode(Context c){return c.getSharedPreferences("persiansocks",0).getString("routing-mode","apps");}
 public static DomainRoutes load(Context c){if(!mode(c).equals("domains"))return new DomainRoutes(Collections.emptyList());DomainRoutes routes=new DomainRoutes(Arrays.asList(c.getSharedPreferences("persiansocks",0).getString("route-domains","").split("\n")));if(!routes.restricted)throw new IllegalArgumentException("حداقل یک دامنه انتخاب کن");return routes;}
 public static ServiceTargets.Target[] verification(Context c,ServiceTargets.Target[] fallback){DomainRoutes r=load(c);if(!r.restricted)return fallback;for(ServiceTargets.Target t:fallback)if(r.proxy(t.host))return new ServiceTargets.Target[]{t};return new ServiceTargets.Target[]{new ServiceTargets.Target(0,"مسیر انتخابی",r.domains.iterator().next(),"/")};}
 public static boolean kill(Context c){return !c.getSharedPreferences("persiansocks",0).getBoolean("kill-switch-off",false);}
}
