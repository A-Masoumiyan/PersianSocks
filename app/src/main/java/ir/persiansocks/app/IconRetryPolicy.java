package ir.persiansocks.app;

import java.util.*;

/** Failures are throttled, not remembered forever; a new route can retry immediately. */
public final class IconRetryPolicy {
    private static final long COOLDOWN=60_000;
    private final Map<String,String> routes=new HashMap<>();
    private final Map<String,Long> times=new HashMap<>();
    public synchronized boolean begin(String host,String route,long now){
        if(route.equals(routes.get(host))&&now-times.getOrDefault(host,0L)<COOLDOWN)return false;
        routes.put(host,route);times.put(host,now);return true;
    }
    public synchronized void retain(Set<String> hosts){routes.keySet().retainAll(hosts);times.keySet().retainAll(hosts);}
}
