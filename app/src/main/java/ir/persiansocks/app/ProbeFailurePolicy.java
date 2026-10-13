package ir.persiansocks.app;
/** A transient failed attempt must not erase a previously verified proxy. Connection still revalidates. */
public final class ProbeFailurePolicy {
    private ProbeFailurePolicy() {}
    public static boolean invalidates(Socks5Probe.Failure failure) {
        return failure==null || failure==Socks5Probe.Failure.INSECURE_PROXY ||
            failure==Socks5Probe.Failure.SOCKS_AUTH || failure==Socks5Probe.Failure.SOCKS_PROTOCOL ||
            failure==Socks5Probe.Failure.TLS_FAILED;
    }
}
