package ir.pathfinder.b;

import android.net.Network;
import java.io.*;
import java.net.*;
import javax.net.ssl.*;

/** Verifies actual end-to-end TLS to Telegram/YouTube VIA an explicit SOCKS5 proxy. */
final class ServiceProbe {
  static final class Outcome {
    final boolean telegram,youtube,youtubeImage;
    final int milliseconds;
    final String detail;
    Outcome(boolean tg,boolean yt,boolean image,int ms,String detail){
      telegram=tg;youtube=yt;youtubeImage=image;milliseconds=ms;this.detail=detail;
    }
    boolean useful(){return telegram||youtube;}
  }
  static Outcome test(Network physical,RelayProbe.Entry proxy){
    long begin=android.os.SystemClock.elapsedRealtime();
    boolean telegram=check(physical,proxy,"api.telegram.org","/");
    boolean youtube=check(physical,proxy,"www.youtube.com","/robots.txt");
    boolean image=youtube&&check(physical,proxy,"i.ytimg.com","/vi/aqz-KE-bpKQ/default.jpg");
    int millis=(int)Math.min(999999,android.os.SystemClock.elapsedRealtime()-begin);
    return new Outcome(telegram,youtube,image,millis,
      "Telegram: "+telegram+", YouTube: "+youtube+", YouTube thumbnail CDN: "+image);
  }
  private static boolean check(Network physical,RelayProbe.Entry proxy,String host,String path){
    Socket raw=null;SSLSocket ssl=null;
    try {
      Inet4Address proxyIp=RelayProbe.ipv4(physical,proxy.host);
      raw=physical==null?new Socket():physical.getSocketFactory().createSocket();
      raw.setSoTimeout(4000);
      raw.connect(new InetSocketAddress(proxyIp,proxy.port),2300);
      InputStream in=raw.getInputStream();
      OutputStream out=raw.getOutputStream();
      // SOCKS5: no-auth or username/password if configured.
      out.write(new byte[]{5,2,0,2});out.flush();
      int version=in.read(),method=in.read();
      if(version!=5 || (method!=0&&method!=2))return false;
      if(method==2){
        byte[] user=proxy.user.getBytes("UTF-8"),pass=proxy.pass.getBytes("UTF-8");
        if(user.length>255||pass.length>255)return false;
        out.write(1);out.write(user.length);out.write(user);
        out.write(pass.length);out.write(pass);out.flush();
        if(in.read()!=1 || in.read()!=0)return false;
      }
      byte[] domain=host.getBytes("US-ASCII");
      out.write(new byte[]{5,1,0,3,(byte)domain.length});
      out.write(domain);out.write(1);out.write(187);out.flush(); // 443
      byte[] reply=RelayProbe.take(in,4);
      if(reply[0]!=5 || reply[1]!=0)return false;
      int type=reply[3]&255;
      if(type==1)RelayProbe.take(in,6);
      else if(type==4)RelayProbe.take(in,18);
      else if(type==3) {
        int length=in.read();if(length<0||length>255)return false;
        RelayProbe.take(in,length+2);
      } else return false;
      ssl=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault())
        .createSocket(raw,host,443,false);
      ssl.setSoTimeout(4300);
      SSLParameters params=ssl.getSSLParameters();
      params.setEndpointIdentificationAlgorithm("HTTPS");
      ssl.setSSLParameters(params);
      ssl.startHandshake();
      byte[] request=("GET "+path+" HTTP/1.1\r\nHost: "+host+
          "\r\nAccept: */*\r\nConnection: close\r\n\r\n").getBytes("US-ASCII");
      ssl.getOutputStream().write(request);ssl.getOutputStream().flush();
      byte[] prefix=new byte[32];int n=ssl.getInputStream().read(prefix);
      return n>0 && new String(prefix,0,n,"US-ASCII").startsWith("HTTP/");
    }catch(Exception ignored){return false;}
    finally {
      if(ssl!=null)try{ssl.close();}catch(Exception ignored){}
      if(raw!=null)try{raw.close();}catch(Exception ignored){}
    }
  }
}