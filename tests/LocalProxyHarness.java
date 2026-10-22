import ir.persiansocks.app.*;
import java.net.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
public final class LocalProxyHarness {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  ScanHarness.init(args[0]);AtomicInteger sockets=new AtomicInteger();
  try(ScanHarness.Mock upstream=new ScanHarness.Mock("valid");ProtectedSocksGateway local=new ProtectedSocksGateway(upstream.endpoint(),new ProtectedSocksGateway.Protector(){
   public boolean tcp(Socket socket){sockets.incrementAndGet();return true;}public boolean udp(DatagramSocket socket){return true;}public InetAddress resolve(String host){throw new AssertionError("SOCKS destination DNS must stay upstream");}
  })){
   ThreadLocal<String> host=new ThreadLocal<>();var active=ConcurrentHashMap.<Socket>newKeySet();
   TunnelVerifier.Result verified=TunnelVerifier.check(new TunnelVerifier.Transport(){
    public Socket create(){return new LoopbackProxySocket(host.get(),local.port(),local.username(),local.password());}
    public InetAddress[] resolve(String name)throws Exception{host.set(name);return new InetAddress[]{InetAddress.getByAddress(new byte[]{1,1,1,1})};}
   },new AtomicBoolean(),active,ServiceTargets.ALL,true,true);
   check(verified.accepts("ALL"),"Local proxy must deliver a real trusted HTTPS response");check(local.downloadedBytes()>0&&local.uploadedBytes()>0,"Both live traffic counters measure relayed bytes");check(sockets.get()>0&&active.isEmpty(),"Only gateway upstream sockets carry the requests and raced sockets close");
   try(LoopbackProxySocket bad=new LoopbackProxySocket("web.telegram.org",local.port(),local.username(),"wrong")){try{bad.connect(new InetSocketAddress("1.1.1.1",443),1000);throw new AssertionError("Wrong credential accepted");}catch(java.io.IOException expected){}}
  }
  System.out.println("PASS: local authenticated SOCKS mode, real HTTPS proof, remote DNS, traffic counters and wrong-password rejection");
 }
}
