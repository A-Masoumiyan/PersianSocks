package ir.persiansocks.app;

import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import java.util.ArrayList;
import java.util.Collections;

/** One passive physical-network callback, owned by the VPN service and delivered on its handler. */
public final class PhysicalNetworkMonitor implements AutoCloseable {
    public interface Listener { void changed(boolean routeBroken,boolean dnsChanged); }
    private final ConnectivityManager manager;
    private final PhysicalRouteTracker<Network> routes=new PhysicalRouteTracker<>();
    private final ConnectivityManager.NetworkCallback callback;
    public PhysicalNetworkMonitor(ConnectivityManager manager,Handler handler,Listener listener){
        this.manager=manager;
        // Initial synchronous snapshot is taken outside callbacks. Subsequent events use their data.
        for(Network n:manager.getAllNetworks()){
            LinkProperties p=manager.getLinkProperties(n);
            routes.update(n,physical(manager.getNetworkCapabilities(n)),p==null?null:addresses(p),p==null?null:dns(p));
        }
        callback=new ConnectivityManager.NetworkCallback(){
            @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){routes.update(n,physical(c),null,null);listener.changed(routes.activeBroken(),false);}
            @Override public void onLinkPropertiesChanged(Network n,LinkProperties p){boolean changed=routes.update(n,routes.usable(n),addresses(p),dns(p));listener.changed(routes.activeBroken(),changed);}
            @Override public void onLost(Network n){routes.lost(n);listener.changed(routes.activeBroken(),false);}
        };
        manager.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),callback,handler);
    }
    private static boolean physical(NetworkCapabilities c){return c!=null&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&!c.hasTransport(NetworkCapabilities.TRANSPORT_VPN);}
    private static String addresses(LinkProperties p){ArrayList<String>a=new ArrayList<>();p.getLinkAddresses().forEach(x->a.add(x.toString()));Collections.sort(a);return p.getInterfaceName()+"\n"+String.join("\n",a);}
    private static String dns(LinkProperties p){ArrayList<String>a=new ArrayList<>();p.getDnsServers().forEach(x->a.add(x.getHostAddress()));Collections.sort(a);return a.toString();}
    public void activate(Network n){routes.activate(n);}
    public void clearActive(){routes.clearActive();}
    public boolean available(){return routes.available();}
    public boolean activeBroken(){return routes.activeBroken();}
    @Override public void close(){manager.unregisterNetworkCallback(callback);}
}
