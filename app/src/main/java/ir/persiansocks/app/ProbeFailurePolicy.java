package ir.persiansocks.app;
/** A completed failed probe is unavailable now; cancelled or lost-route work is inconclusive. */
public final class ProbeFailurePolicy {
    private ProbeFailurePolicy() {}
    public static boolean transientFailure(Socks5Probe.Failure f){return f==Socks5Probe.Failure.TCP_TIMEOUT||f==Socks5Probe.Failure.TCP_REFUSED||f==Socks5Probe.Failure.SOCKS_TIMEOUT||f==Socks5Probe.Failure.HTTPS_TIMEOUT||f==Socks5Probe.Failure.IO_ERROR;}
    public static boolean invalidates(Socks5Probe.Failure failure) {
        return failure!=Socks5Probe.Failure.NONE&&failure!=Socks5Probe.Failure.CANCELLED;
    }
    public static Socks5Probe.Result observed(Socks5Probe.Result result,boolean routeAvailable){
        return routeAvailable?result:new Socks5Probe.Result(null,Socks5Probe.Failure.CANCELLED,false,false);
    }
}
