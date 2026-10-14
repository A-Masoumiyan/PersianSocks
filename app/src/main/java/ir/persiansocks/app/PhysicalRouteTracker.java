package ir.persiansocks.app;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Callback snapshots only; validation changes and unrelated networks do not break a route. */
public final class PhysicalRouteTracker<K> {
    private static final class State { boolean usable; String addresses, dns; }
    private final Map<K,State> routes=new HashMap<>();
    private K active;
    private String activeAddresses;
    public void activate(K key){active=key;State s=routes.get(key);activeAddresses=s==null?null:s.addresses;}
    public void clearActive(){active=null;activeAddresses=null;}
    public boolean update(K key,boolean usable,String addresses,String dns){
        State s=routes.computeIfAbsent(key,k->new State());
        boolean dnsChanged=Objects.equals(active,key)&&s.dns!=null&&dns!=null&&!s.dns.equals(dns);
        s.usable=usable;if(addresses!=null)s.addresses=addresses;if(dns!=null)s.dns=dns;
        if(Objects.equals(active,key)&&activeAddresses==null)activeAddresses=s.addresses;
        return dnsChanged;
    }
    public void lost(K key){routes.remove(key);}
    public boolean usable(K key){State s=routes.get(key);return s!=null&&s.usable;}
    public boolean available(){for(State s:routes.values())if(s.usable&&s.addresses!=null)return true;return false;}
    public boolean activeBroken(){
        if(active==null)return false;State s=routes.get(active);
        return s==null||!s.usable||(activeAddresses!=null&&s.addresses!=null&&!retainsAddresses(activeAddresses,s.addresses));
    }
    private static boolean retainsAddresses(String before,String after){
        String[] old=before.split("\n"),next=after.split("\n");
        if(!old[0].equals(next[0]))return false;
        // Added IPv6/privacy addresses need not interrupt sockets on retained source IPs.
        return new java.util.HashSet<>(java.util.Arrays.asList(next)).containsAll(java.util.Arrays.asList(old));
    }
}
