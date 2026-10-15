package ir.persiansocks.app;

public final class SourceCatalog {
    private SourceCatalog() {}
    public static final ProxyScanner.Source[] DEFAULT = {
        new ProxyScanner.Source("Roosterkid", "https://raw.githubusercontent.com/roosterkid/openproxylist/main/SOCKS5_RAW.txt", "https://cdn.jsdelivr.net/gh/roosterkid/openproxylist@main/SOCKS5_RAW.txt"),
        new ProxyScanner.Source("prxchk", "https://raw.githubusercontent.com/prxchk/proxy-list/main/socks5.txt", "https://cdn.jsdelivr.net/gh/prxchk/proxy-list@main/socks5.txt"),
        new ProxyScanner.Source("Zaeem20", "https://raw.githubusercontent.com/Zaeem20/FREE_PROXIES_LIST/master/socks5.txt", "https://cdn.jsdelivr.net/gh/Zaeem20/FREE_PROXIES_LIST@master/socks5.txt"),
        new ProxyScanner.Source("ShiftyTR", "https://raw.githubusercontent.com/ShiftyTR/Proxy-List/master/socks5.txt", "https://cdn.jsdelivr.net/gh/ShiftyTR/Proxy-List@master/socks5.txt"),
        new ProxyScanner.Source("jetkai", "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-socks5.txt", "https://cdn.jsdelivr.net/gh/jetkai/proxy-list@main/online-proxies/txt/proxies-socks5.txt"),
        new ProxyScanner.Source("Databay", "https://raw.githubusercontent.com/databay-labs/free-proxy-list/master/socks5.txt", "https://cdn.jsdelivr.net/gh/databay-labs/free-proxy-list@master/socks5.txt"),
        new ProxyScanner.Source("monosans", "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt", "https://cdn.jsdelivr.net/gh/monosans/proxy-list@main/proxies/socks5.txt"),
        new ProxyScanner.Source("Proxio", "https://raw.githubusercontent.com/proxio-io/proxy-list/main/socks5.txt", "https://cdn.jsdelivr.net/gh/proxio-io/proxy-list@main/socks5.txt"),
        new ProxyScanner.Source("litportnet", "https://raw.githubusercontent.com/litportnet/free-proxy-list/live/proxies/socks5.txt", "https://cdn.jsdelivr.net/gh/litportnet/free-proxy-list@live/proxies/socks5.txt"),
        new ProxyScanner.Source("hproxy", "https://raw.githubusercontent.com/hproxy-com/free-proxy-list/main/socks5.txt", "https://cdn.jsdelivr.net/gh/hproxy-com/free-proxy-list@main/socks5.txt"),
        new ProxyScanner.Source("ALIILAPRO", "https://raw.githubusercontent.com/ALIILAPRO/Proxy/main/socks5.txt", "https://cdn.jsdelivr.net/gh/ALIILAPRO/Proxy@main/socks5.txt"),
        new ProxyScanner.Source("TheSpeedX", "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt", "https://cdn.jsdelivr.net/gh/TheSpeedX/PROXY-List@master/socks5.txt"),
        new ProxyScanner.Source("ProxyScrape", "https://raw.githubusercontent.com/ProxyScrape/free-proxy-list/main/proxies/protocols/socks5/data.txt", "https://cdn.jsdelivr.net/gh/ProxyScrape/free-proxy-list@main/proxies/protocols/socks5/data.txt"),
        new ProxyScanner.Source("mzyui", "https://raw.githubusercontent.com/mzyui/proxy-list/main/socks5.txt", "https://cdn.jsdelivr.net/gh/mzyui/proxy-list@main/socks5.txt"),
        new ProxyScanner.Source("hookzof", "https://raw.githubusercontent.com/hookzof/socks5_list/master/proxy.txt", "https://cdn.jsdelivr.net/gh/hookzof/socks5_list@master/proxy.txt"),
        new ProxyScanner.Source("Proxifly", "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/socks5/data.txt", "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/socks5/data.txt"),
        new ProxyScanner.Source("MuRongPIG", "https://raw.githubusercontent.com/MuRongPIG/Proxy-Master/main/socks5.txt", "https://cdn.jsdelivr.net/gh/MuRongPIG/Proxy-Master@main/socks5.txt"),
    };
    public static final ProxyScanner.Source[] HTTP = {
        new ProxyScanner.Source("prxchk HTTP","https://raw.githubusercontent.com/prxchk/proxy-list/main/http.txt","https://cdn.jsdelivr.net/gh/prxchk/proxy-list@main/http.txt",ProxyEndpoint.Type.HTTP),
        new ProxyScanner.Source("monosans HTTP","https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt","https://cdn.jsdelivr.net/gh/monosans/proxy-list@main/proxies/http.txt",ProxyEndpoint.Type.HTTP),
        new ProxyScanner.Source("TheSpeedX HTTP","https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt","https://cdn.jsdelivr.net/gh/TheSpeedX/PROXY-List@master/http.txt",ProxyEndpoint.Type.HTTP),
        new ProxyScanner.Source("Proxifly HTTP","https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/http/data.txt","https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/http/data.txt",ProxyEndpoint.Type.HTTP),
        new ProxyScanner.Source("Roosterkid HTTPS destinations","https://raw.githubusercontent.com/roosterkid/openproxylist/main/HTTPS_RAW.txt","https://cdn.jsdelivr.net/gh/roosterkid/openproxylist@main/HTTPS_RAW.txt",ProxyEndpoint.Type.HTTP),
        new ProxyScanner.Source("Proxifly TLS proxy","https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/https/data.txt","https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/https/data.txt",ProxyEndpoint.Type.HTTPS),
    };
    public static ProxyScanner.Source custom(String line,int index){
        String[] pair=line.trim().split("\\s+",2);
        ProxyEndpoint.Type type=ProxyEndpoint.Type.SOCKS5;String url=pair[0];
        if(pair.length==2){try{type=ProxyEndpoint.Type.valueOf(pair[0].toUpperCase(java.util.Locale.US));}catch(Exception ex){return null;}url=pair[1].trim();}
        try{java.net.URL parsed=new java.net.URL(url);
            if(!parsed.getProtocol().equals("https")||parsed.getHost().isEmpty()||parsed.getUserInfo()!=null)return null;
            return new ProxyScanner.Source("منبع دلخواه "+index,url,"",type);
        }catch(Exception ex){return null;}
    }
}
