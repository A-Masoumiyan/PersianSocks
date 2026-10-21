import ir.persiansocks.app.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
public final class AndroidParityHarness {
 static void check(boolean v,String label){if(!v)throw new AssertionError(label);}
 static byte[] request(int command,String host,int port)throws Exception{byte[] h=host.getBytes(StandardCharsets.US_ASCII);ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(new byte[]{5,(byte)command,0,3,(byte)h.length});b.write(h);b.write(new byte[]{(byte)(port>>8),(byte)port});return b.toByteArray();}
 static Socket client(ProtectedSocksGateway g)throws Exception{Socket s=new Socket("127.0.0.1",g.port());s.setSoTimeout(2000);GatewayTestSupport.authenticate(s,g);return s;}
 public static void main(String[] args)throws Exception{
  DomainRoutes route=new DomainRoutes(Collections.singleton("selected.test"));
  check(route.proxy("SELECTED.TEST.")&&route.proxy("sub.selected.test")&&!route.proxy("evilselected.test")&&!route.proxy("selected.test.evil"),"suffix boundary routing");
  check(route.unknownAddress("1.1.1.1")&&route.unknownAddress("2001:db8::1"),"unclassified IP must not fall through");
  AtomicInteger arrivals=new AtomicInteger();
  try(ServerSocket origin=new ServerSocket(0,16,InetAddress.getLoopbackAddress())){
   Thread serve=new Thread(()->{while(!origin.isClosed())try(Socket s=origin.accept()){arrivals.incrementAndGet();s.getOutputStream().write("DIRECT".getBytes(StandardCharsets.US_ASCII));}catch(Exception ignored){}});serve.setDaemon(true);serve.start();
   ProtectedSocksGateway.Protector protector=new ProtectedSocksGateway.Protector(){public boolean tcp(Socket s){return true;}public boolean udp(DatagramSocket s){return true;}public InetAddress resolve(String h)throws Exception{return InetAddress.getLoopbackAddress();}};
   try(ProtectedSocksGateway g=new ProtectedSocksGateway(ProxyEndpoint.parse("127.0.0.1:1"),protector,10000,route)){
    try(Socket s=client(g)){s.getOutputStream().write(request(1,"direct.test",origin.getLocalPort()));check(s.getInputStream().readNBytes(10)[1]==0,"unrelated route accepted");check(new String(s.getInputStream().readNBytes(6),StandardCharsets.US_ASCII).equals("DIRECT"),"unrelated traffic direct");}
    int before=arrivals.get();try(Socket s=client(g)){s.getOutputStream().write(request(1,"selected.test",origin.getLocalPort()));check(s.getInputStream().read()==-1,"failed proxy cannot return direct success");}Thread.sleep(100);check(arrivals.get()==before,"protected request did not reach direct origin");
    try(Socket s=client(g)){s.getOutputStream().write(new byte[]{5,1,0,1,127,0,0,1,(byte)(origin.getLocalPort()>>8),(byte)origin.getLocalPort()});check(s.getInputStream().read()==-1,"unknown IP does not bypass domain rule");}
   }
  }
  try(DatagramSocket origin=new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(),0))){
   origin.setSoTimeout(1500);AtomicInteger packets=new AtomicInteger();
   Thread echo=new Thread(()->{byte[] b=new byte[512];while(!origin.isClosed())try{DatagramPacket p=new DatagramPacket(b,b.length);origin.receive(p);packets.incrementAndGet();origin.send(p);}catch(SocketTimeoutException ignored){}catch(Exception e){break;}});echo.setDaemon(true);echo.start();
   ProtectedSocksGateway.Protector protector=new ProtectedSocksGateway.Protector(){public boolean tcp(Socket s){return true;}public boolean udp(DatagramSocket s){return true;}public InetAddress resolve(String h)throws Exception{return InetAddress.getLoopbackAddress();}};
   try(ProtectedSocksGateway g=new ProtectedSocksGateway(ProxyEndpoint.parse("127.0.0.1:1"),protector,10000,route);Socket control=client(g);DatagramSocket udp=new DatagramSocket()){
    control.getOutputStream().write(request(3,"direct.test",1));byte[] reply=control.getInputStream().readNBytes(10);check(reply.length==10&&reply[1]==0,"restricted UDP association");int port=((reply[8]&255)<<8)|(reply[9]&255);udp.setSoTimeout(1000);
    byte[] frame=request(1,"direct.test",origin.getLocalPort());frame[0]=frame[1]=0;byte[] send=Arrays.copyOf(frame,frame.length+4);System.arraycopy(new byte[]{1,2,3,4},0,send,frame.length,4);udp.send(new DatagramPacket(send,send.length,InetAddress.getLoopbackAddress(),port));DatagramPacket back=new DatagramPacket(new byte[512],512);udp.receive(back);check(back.getLength()==send.length&&back.getData()[back.getLength()-1]==4&&packets.get()==1,"named unrelated UDP direct round trip");
    frame=request(1,"selected.test",origin.getLocalPort());frame[0]=frame[1]=0;udp.send(new DatagramPacket(frame,frame.length,InetAddress.getLoopbackAddress(),port));try{udp.receive(back);throw new AssertionError("selected UDP escaped");}catch(SocketTimeoutException expected){}check(packets.get()==1,"selected UDP never reaches direct origin");
   }
  }
  CountryLookup.initialize(new FileInputStream(args[0]));
  ProxyScanner.Source json=new ProxyScanner.Source("IR","https://example.org/list.json","",ProxyEndpoint.Type.HTTP,ProxyScanner.Source.Format.JSON,"IR");
  List<ProxyEndpoint> rows=SourceDocuments.readJson(new ByteArrayInputStream("[{\"host\":\"212.33.201.143\",\"port\":80,\"protocol\":\"http\"},{\"proxy\":\"8.8.8.8:1080\",\"protocol\":\"socks5\",\"country\":\"IR\"}]".getBytes(StandardCharsets.UTF_8)),json);
  check(rows.size()==1&&rows.get(0).host.equals("212.33.201.143")&&!rows.get(0).hasAnyService(),"JSON geography and unverified health");
  List<ProxyEndpoint> mixed=ProxyScanner.parseList("socks4://8.8.8.8:1080\nhttp://8.8.8.8:80\nsocks5://9.9.9.9:1080",true,100,ProxyEndpoint.Type.SOCKS5);
  check(mixed.size()==2&&mixed.get(0).type==ProxyEndpoint.Type.HTTP&&mixed.get(1).type==ProxyEndpoint.Type.SOCKS5,"unsupported SOCKS4 not relabeled");
  ProxyEndpoint healthy=new ProxyEndpoint("8.8.8.8",1080,20,false,1,1,1000);check(ConnectionFastPath.recent(healthy,2000)&&!ConnectionFastPath.recent(healthy,200000)&&!ConnectionFastPath.recent(healthy,999),"connection freshness bound");
  System.out.println("PASS: Android selected-domain proxy failure blocked direct fallback; unrelated direct socket; hostname boundaries; unclassified IP block; Iran JSON; protocol identity; bounded cached fast connection");
 }
}
