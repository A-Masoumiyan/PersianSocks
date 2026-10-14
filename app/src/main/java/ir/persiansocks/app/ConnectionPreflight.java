package ir.persiansocks.app;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
/** Fresh routing per bounded attempt; protocol/auth/certificate failures are terminal. */
public final class ConnectionPreflight {
    public interface Probe {Socks5Probe.Result inspect()throws Exception;}
    public interface Pause {void waitMillis(long ms)throws InterruptedException;}
    private ConnectionPreflight(){}
    public static ServiceTargets.Target target(String filter,ProxyEndpoint known){
        for(ServiceTargets.Target t:ServiceTargets.current())if(filter.equals(t.id)||filter.equals(t.bit==1?"TELEGRAM":t.bit==2?"YOUTUBE":t.bit==4?"INSTAGRAM":""))return t;
        if("ALL".equals(filter)&&known!=null&&!known.passed(ProxyEndpoint.INSTAGRAM))for(ServiceTargets.Target t:ServiceTargets.current())if(known.passed(t))return t;
        return ServiceTargets.ALL[2];
    }
    public static Socks5Probe.Result inspect(ProxyEndpoint p,ServiceTargets.Target target,AtomicBoolean stop,java.util.Set<java.net.Socket> sockets,Socks5Probe.SocketMaker maker,Socks5Probe.Resolver resolver){
        Socks5Probe.Result result=Socks5Probe.inspectTarget(p,target,true,stop,sockets,maker,resolver);if(result.endpoint==null)return result;
        java.util.Set<String> extra=target.bit==0?java.util.Collections.singleton(target.id):java.util.Collections.emptySet();
        ProxyEndpoint fresh=new ProxyEndpoint(p.host,p.port,result.endpoint.latencyMs,false,target.bit,target.bit,System.currentTimeMillis(),extra,extra,CountryLookup.lookup(p.host),p.type).withLatency(result.endpoint.latencyMs,target.url()).withServiceLatencies(java.util.Collections.singletonMap(target.url(),result.endpoint.latencyMs));
        return new Socks5Probe.Result(fresh,Socks5Probe.Failure.NONE,result.tcpReached,result.socksReached);
    }
    public static Socks5Probe.Result run(Probe probe,Pause pause,AtomicBoolean stop,IntConsumer progress){
        Socks5Probe.Result result=new Socks5Probe.Result(null,Socks5Probe.Failure.IO_ERROR,false,false);
        for(int attempt=1;attempt<=3;attempt++){
            if(stop.get())return cancelled();progress.accept(attempt);
            try{result=probe.inspect();}catch(Exception ex){result=new Socks5Probe.Result(null,ex instanceof javax.net.ssl.SSLException?Socks5Probe.Failure.TLS_FAILED:ex instanceof java.net.ConnectException?Socks5Probe.Failure.TCP_REFUSED:ex instanceof java.net.SocketTimeoutException?Socks5Probe.Failure.TCP_TIMEOUT:Socks5Probe.Failure.IO_ERROR,false,false);}
            if(stop.get())return cancelled();
            if(result.endpoint!=null||!ProbeFailurePolicy.transientFailure(result.failure)||attempt==3)return result;
            try{pause.waitMillis(attempt*900L);}catch(InterruptedException ex){Thread.currentThread().interrupt();return cancelled();}
        }return result;
    }
    private static Socks5Probe.Result cancelled(){return new Socks5Probe.Result(null,Socks5Probe.Failure.CANCELLED,false,false);}
}
