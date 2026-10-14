package ir.persiansocks.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.*;

/** A test captures one physical network and never falls back to a VPN or direct destination. */
public final class PhysicalTestNetwork {
    private static final ExecutorService DNS = Executors.newFixedThreadPool(8, r -> {
        Thread thread = new Thread(r, "single-proxy-dns"); thread.setDaemon(true); return thread;
    });
    private final ConnectivityManager manager;
    private final Network network;
    // Cache belongs to this captured physical network, never to another network or VPN.
    private final ConcurrentHashMap<String,DnsAnswer> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,CompletableFuture<InetAddress>> pending = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong dnsGeneration=new java.util.concurrent.atomic.AtomicLong();
    private static final class DnsAnswer {
        final InetAddress address; final long expires,generation;
        DnsAnswer(InetAddress address,long generation) { this.address=address;this.generation=generation;expires=System.nanoTime()+30_000_000_000L; }
    }
    private PhysicalTestNetwork(ConnectivityManager manager, Network network) { this.manager = manager; this.network = network; }
    public static PhysicalTestNetwork capture(Context context) throws IOException {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        Network defaultNetwork = manager.getActiveNetwork();
        if (defaultNetwork != null && physical(manager.getNetworkCapabilities(defaultNetwork))) return new PhysicalTestNetwork(manager, defaultNetwork);
        Network best = null; int bestRank = -1;
        for (Network candidate : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(candidate);
            if (!physical(caps)) continue;
            int rank = (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 4 : 0) +
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? 2 : 0);
            if (rank > bestRank) { best = candidate; bestRank = rank; }
        }
        if (best == null) throw new IOException("اینترنت وای‌فای یا سیم‌کارت در دسترس نیست");
        return new PhysicalTestNetwork(manager, best);
    }
    private static boolean physical(NetworkCapabilities caps) {
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }
    public Network network(){return network;}
    public void invalidateDns(){dnsGeneration.incrementAndGet();cache.clear();pending.clear();}
    public void bind(Socket socket)throws IOException {if(!valid())throw new IOException("Physical network changed");network.bindSocket(socket);}
    public void bind(DatagramSocket socket)throws IOException {if(!valid())throw new IOException("Physical network changed");network.bindSocket(socket);}
    public boolean valid() { return physical(manager.getNetworkCapabilities(network)); }
    public Socket socket() throws IOException {
        if (!valid()) throw new IOException("شبکهٔ تست قطع شد");
        Socket socket = new Socket();
        try {
            socket.bind(new InetSocketAddress(0));
            if (!ProxyVpnService.protectTestSocket(socket)) throw new IOException("مسیر مستقل تست آماده نیست");
            network.bindSocket(socket);
            return socket;
        } catch (IOException | RuntimeException ex) { socket.close(); throw ex; }
    }
    public InetAddress resolve(String host) throws Exception {
        if (!valid()) throw new IOException("شبکهٔ تست قطع شد");
        if (host.matches("[0-9.]+")) return InetAddress.getByName(host);
        final long requestGeneration=dnsGeneration.get();
        DnsAnswer cached=cache.get(host);
        if(cached!=null&&cached.generation==requestGeneration&&System.nanoTime()<cached.expires)return cached.address;
        CompletableFuture<InetAddress> created=new CompletableFuture<>();
        CompletableFuture<InetAddress> query=pending.putIfAbsent(host,created);
        if(query==null){
            query=created;
            final long generation=requestGeneration;
            DNS.execute(()->{
                try {
                    if(!valid())throw new IOException("شبکهٔ تست قطع شد");
                    InetAddress[] answers=network.getAllByName(host);InetAddress best=null;
                    for(InetAddress answer:answers)if(answer instanceof Inet4Address){best=answer;break;}
                    if(best==null&&answers.length>0)best=answers[0];
                    if(best==null)throw new UnknownHostException(host);
                    if(!valid()||generation!=dnsGeneration.get())throw new IOException("Physical DNS route changed");
                    if(cache.size()>=256)cache.clear();
                    cache.put(host,new DnsAnswer(best,generation));created.complete(best);
                }catch(Exception ex){created.completeExceptionally(ex);}
                finally{pending.remove(host,created);}
            });
        }
        InetAddress answer=query.get(6,TimeUnit.SECONDS);
        if(!valid()||requestGeneration!=dnsGeneration.get())throw new IOException("Physical DNS route changed");
        return answer;
    }
}
