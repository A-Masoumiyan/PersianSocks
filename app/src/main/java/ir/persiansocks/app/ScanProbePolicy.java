package ir.persiansocks.app;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A scan captures its profile; single-target success never implies untested service success. */
public final class ScanProbePolicy {
    private static volatile Profile current=new Profile(false,"");
    private ScanProbePolicy(){}
    public static void configure(boolean only,String url){current=new Profile(only,url==null?"":url);}
    public static Profile profile(){return current;}
    public static long latency(ProxyEndpoint p){ServiceTargets.Target t=current.target();return p.latencyMs<0||t!=null&&!t.url().equals(p.latencyTarget)?Long.MAX_VALUE:p.latencyMs;}
    public static final class Profile {
        public final boolean only;public final String url;
        Profile(boolean only,String url){this.only=only;this.url=url;}
        public ServiceTargets.Target target(){for(ServiceTargets.Target t:ServiceTargets.current())if(t.url().equals(url))return t;return only?ServiceTargets.ALL[0]:null;}
        public String signature(){ServiceTargets.Target t=target();return t==null?"":"|ping:"+only+":"+t.url();}
        public Socks5Probe.Result inspect(ProxyEndpoint p,boolean slow,AtomicBoolean stop,Set<Socket> sockets,Socks5Probe.SocketMaker maker,Socks5Probe.Resolver resolver){
            ServiceTargets.Target t=target();
            if(!only)return Socks5Probe.inspect(p,slow,stop,sockets,maker,resolver,t);
            Socks5Probe.Result result=Socks5Probe.inspectTarget(p,t,slow,stop,sockets,maker,resolver);
            if(result.endpoint==null)return result;
            Set<String> extra=t.bit==0?Collections.singleton(t.id):Collections.emptySet();
            ProxyEndpoint verified=new ProxyEndpoint(p.host,p.port,result.endpoint.latencyMs,false,t.bit,t.bit,System.currentTimeMillis(),extra,extra,CountryLookup.lookup(p.host),p.type).withLatency(result.endpoint.latencyMs,t.url());
            return new Socks5Probe.Result(verified,Socks5Probe.Failure.NONE,result.tcpReached,result.socksReached);
        }
    }
}
