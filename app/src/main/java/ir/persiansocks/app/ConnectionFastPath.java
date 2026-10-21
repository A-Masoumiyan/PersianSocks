package ir.persiansocks.app;
/** Skip duplicated physical preflight only for recent healthy evidence. VPN TLS verification remains mandatory. */
public final class ConnectionFastPath {
 private ConnectionFastPath(){}
 public static boolean recent(ProxyEndpoint p,long now){return p!=null&&p.hasAnyService()&&p.testedAt>0&&p.testedAt<=now&&now-p.testedAt<=120_000;}
}
