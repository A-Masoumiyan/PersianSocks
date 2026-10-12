package ir.persiansocks.app;

import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns only the sockets of one user-requested connection or latency test. */
public final class SingleProxyTest {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    public void cancel() {
        cancelled.set(true);
        for (Socket socket : sockets) try { socket.close(); } catch (Exception ignored) {}
    }
    public boolean cancelled() { return cancelled.get(); }
    public Socks5Probe.Result connection(ProxyEndpoint proxy, boolean slow,
            Socks5Probe.SocketMaker maker, Socks5Probe.Resolver resolver) {
        return Socks5Probe.inspect(proxy, slow, cancelled, sockets, maker, resolver);
    }
    public Socks5Probe.Result latency(ProxyEndpoint proxy, ServiceTargets.Target target, boolean slow,
            Socks5Probe.SocketMaker maker, Socks5Probe.Resolver resolver) {
        return Socks5Probe.inspectTarget(proxy, target, slow, cancelled, sockets, maker, resolver);
    }
    public static ServiceTargets.Target latencyTarget(ProxyEndpoint proxy, String filter) {
        for (ServiceTargets.Target target : ServiceTargets.current()) {
            boolean matches = filter.equals(target.id) ||
                (target.bit == 1 && filter.equals("TELEGRAM")) ||
                (target.bit == 2 && filter.equals("YOUTUBE")) ||
                (target.bit == 4 && filter.equals("INSTAGRAM"));
            if (matches && proxy.passed(target)) return target;
        }
        for (ServiceTargets.Target target : ServiceTargets.current()) if (proxy.passed(target)) return target;
        return ServiceTargets.ALL[0];
    }
}
