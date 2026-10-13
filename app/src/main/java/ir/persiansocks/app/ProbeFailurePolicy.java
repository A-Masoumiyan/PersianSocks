package ir.persiansocks.app;
/** A transient failed attempt must not erase a previously verified proxy. Connection still revalidates. */
public final class ProbeFailurePolicy {
    private ProbeFailurePolicy() {}
    public static boolean transientFailure(Socks5Probe.Failure f){return f==Socks5Probe.Failure.TCP_TIMEOUT||f==Socks5Probe.Failure.TCP_REFUSED||f==Socks5Probe.Failure.SOCKS_TIMEOUT||f==Socks5Probe.Failure.HTTPS_TIMEOUT||f==Socks5Probe.Failure.IO_ERROR;}
    public static boolean invalidates(Socks5Probe.Failure failure) {
        return failure==null || failure==Socks5Probe.Failure.INSECURE_PROXY ||
            failure==Socks5Probe.Failure.SOCKS_AUTH || failure==Socks5Probe.Failure.SOCKS_PROTOCOL ||
            failure==Socks5Probe.Failure.TLS_FAILED;
    }
}
