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
    private static final ExecutorService DNS = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "single-proxy-dns"); thread.setDaemon(true); return thread;
    });
    private final ConnectivityManager manager;
    private final Network network;
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
        Future<InetAddress[]> query = DNS.submit(() -> network.getAllByName(host));
        try {
            InetAddress[] answers = query.get(6, TimeUnit.SECONDS);
            for (InetAddress answer : answers) if (answer instanceof Inet4Address) return answer;
            if (answers.length > 0) return answers[0];
            throw new UnknownHostException(host);
        } finally { query.cancel(true); }
    }
}
