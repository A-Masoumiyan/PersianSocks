package ir.persiansocks.app;
import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;
/** Control-channel responsiveness is not certificate or tunnel evidence. */
public final class GateReachabilityHarness {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static byte[] response(byte[] request){byte[] p=new byte[26];p[0]=8<<3;p[1]=1;p[9]=1;System.arraycopy(request,1,p,14,8);return p;}
 public static void main(String[] args)throws Exception {
  byte[] request=new byte[14];request[0]=7<<3;for(int i=1;i<9;i++)request[i]=(byte)i;byte[] reply=response(request);
  check(OpenVpnReachability.reply(reply,reply.length,request),"Echoed session ID is accepted");reply[14]^=1;check(!OpenVpnReachability.reply(reply,reply.length,request),"Wrong session ID is rejected");reply=response(request);reply[0]=5<<3;check(!OpenVpnReachability.reply(reply,reply.length,request),"A bare ACK is not a server reset");check(!OpenVpnReachability.reply(new byte[13],13,request),"Truncation rejected");
  try(ServerSocket server=new ServerSocket(0,2,InetAddress.getLoopbackAddress())){
   Thread peer=new Thread(()->{try(Socket socket=server.accept()){DataInputStream in=new DataInputStream(socket.getInputStream());int n=in.readUnsignedShort();byte[] p=in.readNBytes(n);byte[] r=response(p);DataOutputStream out=new DataOutputStream(socket.getOutputStream());out.writeShort(r.length);out.write(r);out.flush();}catch(Exception e){throw new RuntimeException(e);}});peer.start();
   try(Socket socket=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort())){check(OpenVpnReachability.tcp(socket,1000),"TCP length framing and session roundtrip");}peer.join();
  }
  try(DatagramSocket peer=new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(),0));DatagramSocket client=new DatagramSocket()){
   Thread echo=new Thread(()->{try{byte[] b=new byte[512];DatagramPacket packet=new DatagramPacket(b,b.length);peer.receive(packet);peer.receive(packet);byte[] r=response(Arrays.copyOf(b,packet.getLength()));peer.send(new DatagramPacket(r,r.length,packet.getSocketAddress()));}catch(Exception e){throw new RuntimeException(e);}});echo.start();client.connect(InetAddress.getLoopbackAddress(),peer.getLocalPort());check(OpenVpnReachability.udp(client,800),"UDP retries the lost first control packet");echo.join();
  }
  try(DatagramSocket peer=new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(),0));DatagramSocket client=new DatagramSocket()){client.connect(InetAddress.getLoopbackAddress(),peer.getLocalPort());long begin=System.nanoTime();check(!OpenVpnReachability.udp(client,200)&&(System.nanoTime()-begin)/1000000<600,"Silent UDP endpoint has a bounded fast preflight");}
  System.out.println("PASS: OpenVPN framed TCP, UDP loss/retry, session validation and silent-peer deadline");
 }
}
