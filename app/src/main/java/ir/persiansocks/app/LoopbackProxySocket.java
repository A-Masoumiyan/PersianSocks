package ir.persiansocks.app;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;
/** Authenticated loopback SOCKS connection used to verify the same listener offered to apps. */
public final class LoopbackProxySocket extends Socket {
 private final String host,user,password;private final int localPort;
 public LoopbackProxySocket(String host,int localPort,String user,String password){this.host=host;this.localPort=localPort;this.user=user;this.password=password;}
 private static byte[] read(InputStream in,int n)throws IOException{byte[] b=new byte[n];for(int at=0;at<n;){int k=in.read(b,at,n-at);if(k<0)throw new EOFException();at+=k;}return b;}
 @Override public void connect(SocketAddress destination,int timeout)throws IOException{
  try{super.connect(new InetSocketAddress("127.0.0.1",localPort),timeout);setSoTimeout(timeout);InputStream in=getInputStream();OutputStream out=getOutputStream();out.write(new byte[]{5,1,2});out.flush();byte[] reply=read(in,2);if(reply[0]!=5||reply[1]!=2)throw new IOException("Local SOCKS authentication unavailable");
   byte[] u=user.getBytes(StandardCharsets.US_ASCII),p=password.getBytes(StandardCharsets.US_ASCII),h=host.getBytes(StandardCharsets.US_ASCII);if(u.length>255||p.length>255||h.length<1||h.length>255)throw new IOException("Invalid SOCKS parameters");
   out.write(1);out.write(u.length);out.write(u);out.write(p.length);out.write(p);out.flush();reply=read(in,2);if(reply[0]!=1||reply[1]!=0)throw new IOException("Local SOCKS authentication failed");
   int port=((InetSocketAddress)destination).getPort();out.write(new byte[]{5,1,0,3,(byte)h.length});out.write(h);out.write(port>>8);out.write(port&255);out.flush();reply=read(in,4);if(reply[0]!=5||reply[1]!=0||reply[2]!=0)throw new IOException("Local SOCKS connection failed");int n=reply[3]==1?4:reply[3]==4?16:reply[3]==3?in.read():-1;if(n<1)throw new IOException("Invalid local SOCKS reply");read(in,n+2);
  }catch(IOException|RuntimeException e){close();throw e;}
 }
}
