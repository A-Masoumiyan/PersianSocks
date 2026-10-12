import ir.persiansocks.app.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real proxy/TLS traffic keeps using the captured route while global VPN/default factories change. */
public final class ScanRouteHarness {
    static int checks;
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS: "+label);}
    public static void main(String[] args)throws Exception{
        ScanHarness.init(args[0]);ServiceTargets.configure(Collections.emptyList());DistributionPolicy.setUserTlsOnly(false);
        try(ScanHarness.Mock one=new ScanHarness.Mock("valid");ScanHarness.Mock two=new ScanHarness.Mock("valid")){
            List<ProxyEndpoint> candidates=Arrays.asList(one.endpoint(),two.endpoint());
            ScanCheckpoint state=new ScanCheckpoint(candidates,new ProxyScanner.Options(100,false,true),"CATALOG");
            PreparedScanner scanner=new PreparedScanner();CountDownLatch factoryEntered=new CountDownLatch(1),routeChanged=new CountDownLatch(1),finished=new CountDownLatch(1);
            AtomicInteger physicalSockets=new AtomicInteger(),vpnSockets=new AtomicInteger(),results=new AtomicInteger(),resolved=new AtomicInteger();
            Socks5Probe.SocketMaker physical=()->{physicalSockets.incrementAndGet();factoryEntered.countDown();if(!routeChanged.await(5,TimeUnit.SECONDS))throw new SocketTimeoutException();return new Socket();};
            scanner.start(state,s->{},physical,host->{resolved.incrementAndGet();return InetAddress.getByName("127.0.0.1");},new PreparedScanner.Listener(){
                public void onResult(int index,Socks5Probe.Result result){if(result.endpoint!=null)results.incrementAndGet();}
                public void onProgress(int done,int total,int good){}
                public void onFinished(ScanCheckpoint checkpoint,String message){finished.countDown();}
            });
            check(factoryEntered.await(5,TimeUnit.SECONDS),"Manual prepared scan starts on its captured socket factory");
            Socks5Probe.setSocketMaker(()->{vpnSockets.incrementAndGet();throw new java.io.IOException("Default route now belongs to VPN");});
            Socks5Probe.setResolver(host->{throw new UnknownHostException("VPN DNS must not serve the scan");});routeChanged.countDown();
            check(finished.await(20,TimeUnit.SECONDS),"Changing the default VPN socket/DNS factories does not stop the scan");
            check(results.get()==2&&state.goodCount()==2&&state.complete(),"Both proxies finish all real service checks after the route change");
            check(vpnSockets.get()==0,"No scan connection uses the VPN/default socket factory");
            check(physicalSockets.get()>=6&&resolved.get()>=6,"Each service handshake uses the explicit physical factory and resolver");
            check(state.good().stream().allMatch(ProxyEndpoint::full),"Route independence preserves complete-service verification");
        }finally{Socks5Probe.setSocketMaker(null);Socks5Probe.setResolver(null);}
        System.out.println("RESULT: "+checks+" checks passed");
    }
}
