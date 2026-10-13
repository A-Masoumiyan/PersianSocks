package ir.persiansocks.app;
/** Public IPv4 evidence read from an HTTPS endpoint, never inferred from a selected card. */
public final class PublicIpEvidence {
    private PublicIpEvidence(){}
    public static String parse(String raw){
        if(raw==null||raw.length()>256)return "";String ip=raw.trim();
        ProxyEndpoint endpoint=ProxyEndpoint.parse(ip+":80");
        return ip.matches("[0-9.]+")&&endpoint!=null&&endpoint.isPublicIpv4()?ip:"";
    }
    public static boolean matches(String actual,String expected){return !parse(actual).isEmpty()&&actual.equals(expected);}
}
