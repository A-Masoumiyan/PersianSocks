import ir.persiansocks.app.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class ReconnectPriorityHarness {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static ProxyEndpoint endpoint(String ip,long ordinary,long instagram){return new ProxyEndpoint(ip,8080,ordinary,false,7,7,System.currentTimeMillis()).withServiceLatencies(Collections.singletonMap(ServiceTargets.ALL[2].url(),instagram));}
    public static void main(String[] args)throws Exception {
        ScanHarness.init(args[0]);ScanProbePolicy.configure(false,"");ServiceTargets.configure(Collections.emptyList());
        for(int cycle=0;cycle<100;cycle++){
            check(IdleStopPolicy.allowed(cycle,cycle,cycle,cycle,false),"idle command may stop itself");
            check(!IdleStopPolicy.allowed(cycle,cycle+1,cycle,cycle+1,true),"old disconnect cannot stop immediate reconnect");
            check(!IdleStopPolicy.allowed(cycle,cycle,cycle,cycle+1,false),"unseen replacement start ID prevents old stop");
        }
        for(ProxyEndpoint.Type type:ProxyEndpoint.Type.values())try(SingleProxyHarness.Proxy proxy=new SingleProxyHarness.Proxy(type,"valid",300000)){
            int refusedPort;try(ServerSocket reserve=new ServerSocket(0)){refusedPort=reserve.getLocalPort();}
            AtomicInteger captures=new AtomicInteger();List<Long> delays=new ArrayList<>();
            Socks5Probe.Result result=ConnectionPreflight.run(()->{
                int path=captures.incrementAndGet();
                return Socks5Probe.inspect(proxy.endpoint(),false,new AtomicBoolean(),null,()->new Socket(){@Override public void connect(SocketAddress address,int timeout)throws java.io.IOException{super.connect(path<3?new InetSocketAddress(InetAddress.getLoopbackAddress(),refusedPort):address,timeout);}},InetAddress::getByName);
            },delays::add,new AtomicBoolean(),n->{});
            check(result.endpoint!=null&&captures.get()==3&&delays.equals(Arrays.asList(900L,1800L)),"actual TCP refusal recovers on captured third path: "+type);
            check(result.endpoint.instagramLatency()>0&&result.endpoint.instagramLatency()!=Long.MAX_VALUE,"actual Instagram TLS timing retained: "+type);
            check(ProxyEndpoint.fromJson(result.endpoint.json()).instagramLatency()==result.endpoint.instagramLatency(),"per-service timing survives cache reload");
            int before=proxy.accepted.get();ServiceTargets.Target selected=ConnectionPreflight.target("FULL",result.endpoint);
            Socks5Probe.Result preliminary=ConnectionPreflight.inspect(proxy.endpoint(),selected,new AtomicBoolean(),null,Socket::new,InetAddress::getByName);
            check(preliminary.endpoint!=null&&preliminary.endpoint.serviceMask==selected.bit&&!preliminary.endpoint.full()&&proxy.accepted.get()==before+1,"one preliminary request cannot claim full VPN verification: "+type);
            for(int repeat=0;repeat<5;repeat++){
                result=ConnectionPreflight.run(()->Socks5Probe.inspect(proxy.endpoint(),false,new AtomicBoolean(),null,Socket::new,InetAddress::getByName),delays::add,new AtomicBoolean(),n->{});
                check(result.endpoint!=null&&result.endpoint.full(),"repeated fresh connect/disconnect sockets stay usable: "+type);
            }
        }
        for(Socks5Probe.Failure failure:new Socks5Probe.Failure[]{Socks5Probe.Failure.TLS_FAILED,Socks5Probe.Failure.SOCKS_AUTH,Socks5Probe.Failure.SOCKS_PROTOCOL}){
            AtomicInteger attempts=new AtomicInteger();List<Long> delays=new ArrayList<>();
            ConnectionPreflight.run(()->{attempts.incrementAndGet();return new Socks5Probe.Result(null,failure,true,false);},delays::add,new AtomicBoolean(),n->{});
            check(attempts.get()==1&&delays.isEmpty(),"certificate/auth/protocol failure never retried: "+failure);
        }
        AtomicInteger attempts=new AtomicInteger();AtomicBoolean stop=new AtomicBoolean();
        Socks5Probe.Result cancelled=ConnectionPreflight.run(()->{attempts.incrementAndGet();return new Socks5Probe.Result(null,Socks5Probe.Failure.TCP_REFUSED,false,false);},ms->stop.set(true),stop,n->{});
        check(cancelled.failure==Socks5Probe.Failure.CANCELLED&&attempts.get()==1,"cancellation prevents queued retry");
        attempts.set(0);Socks5Probe.Result failed=ConnectionPreflight.run(()->{attempts.incrementAndGet();return new Socks5Probe.Result(null,Socks5Probe.Failure.TCP_REFUSED,false,false);},ms->{},new AtomicBoolean(),n->{});
        check(failed.endpoint==null&&failed.failure==Socks5Probe.Failure.TCP_REFUSED&&attempts.get()==3,"permanent refusal remains a real error after bounded attempts");
        ProxyEndpoint quick=endpoint("8.8.8.8",10,900),best=endpoint("1.1.1.1",900,100);
        check(ProxyResults.visible(Arrays.asList(quick,best),"ALL",ProxyResults.Sort.FASTEST).get(0).key().equals(quick.key()),"Ordinary ping controls ranking without Instagram priority");
        List<ProxyEndpoint> original=Collections.singletonList(quick);List<ProxyEndpoint> pinned=ProxyResults.pinActive(original,best,true);
        check(pinned.get(0).key().equals(best.key())&&pinned.size()==2&&original.size()==1,"active proxy stays visible even outside home filter without changing input");
        check(ProxyResults.pinActive(original,best,false).size()==1,"unsaved active proxy is not silently added to saved list");
        check(ProxyResults.pinActive(Arrays.asList(quick,best),best,true).size()==2,"pinning active proxy does not duplicate it");
        System.out.println("PASS: "+checks+" reconnect lifecycle, real TCP/TLS and service-neutral ranking checks");
    }
}
