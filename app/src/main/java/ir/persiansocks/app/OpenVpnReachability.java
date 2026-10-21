package ir.persiansocks.app;

import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.Arrays;

/** OpenVPN control-channel reachability only. It never creates health/HTTPS evidence. */
public final class OpenVpnReachability {
 private static final SecureRandom RANDOM=new SecureRandom();
 private OpenVpnReachability(){}
 public static boolean needsKey(VpnGateProfile p){return p.config.contains("<tls-auth>")||p.config.contains("<tls-crypt>");}
 private static byte[] request(){byte[] p=new byte[14];p[0]=7<<3;byte[] sid=new byte[8];RANDOM.nextBytes(sid);System.arraycopy(sid,0,p,1,8);return p;}
 static boolean reply(byte[] p,int length,byte[] request){
  if(length<14||(p[0]&255)!=(8<<3))return false;int n=p[9]&255;if(n>8||length<14+n*4+(n>0?8:0))return false;
  if(n>0&&!Arrays.equals(Arrays.copyOfRange(request,1,9),Arrays.copyOfRange(p,10+4*n,18+4*n)))return false;
  return true;
 }
 private static int remaining(long end)throws SocketTimeoutException{long ms=(end-System.nanoTime())/1000000;if(ms<=0)throw new SocketTimeoutException("No OpenVPN control response");return (int)Math.max(1,ms);}
 private static void read(Socket s,InputStream in,byte[] buffer,long end)throws IOException{for(int at=0;at<buffer.length;){s.setSoTimeout(remaining(end));int n=in.read(buffer,at,buffer.length-at);if(n<0)throw new EOFException();at+=n;}}
 /** Socket must already be connected, bound to the physical network and protected. */
 public static boolean tcp(Socket socket,int timeoutMillis)throws IOException{
  long end=System.nanoTime()+timeoutMillis*1000000L;byte[] packet=request();OutputStream out=socket.getOutputStream();out.write(0);out.write(packet.length);out.write(packet);out.flush();
  InputStream in=socket.getInputStream();byte[] header=new byte[2];read(socket,in,header,end);int size=(header[0]&255)*256+(header[1]&255);if(size<14||size>512)return false;byte[] response=new byte[size];read(socket,in,response,end);return reply(response,size,packet);
 }
 /** Connected UDP socket pins the peer; repeat the same packet once for ordinary packet loss. */
 public static boolean udp(DatagramSocket socket,int timeoutMillis)throws IOException{
  byte[] packet=request();long end=System.nanoTime()+timeoutMillis*1000000L;
  for(int attempt=0;attempt<2;attempt++){
   socket.send(new DatagramPacket(packet,packet.length));socket.setSoTimeout(Math.min(remaining(end),Math.max(1,timeoutMillis/2)));byte[] bytes=new byte[512];DatagramPacket response=new DatagramPacket(bytes,bytes.length);
   try{socket.receive(response);if(reply(bytes,response.getLength(),packet))return true;}catch(SocketTimeoutException noReply){if(attempt==1)return false;}
  }return false;
 }
}
