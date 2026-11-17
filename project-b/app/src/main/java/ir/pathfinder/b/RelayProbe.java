package ir.pathfinder.b;

import android.net.Network;
import java.io.*;
import java.net.*;
import javax.net.ssl.*;

final class RelayProbe {
  static final class Entry {
    final String host,user,pass; final int port;
    Entry(String h,int p,String u,String pw){host=h;port=p;user=u;pass=pw;}
    String label(){return host+":"+port;}
    static Entry parse(String raw)throws Exception {
      String x=raw.trim(); if(x.isEmpty()||x.startsWith("#"))return null;
      if(x.startsWith("socks5://"))x=x.substring(9);
      String user="",pw="";int at=x.lastIndexOf('@');
      if(at>=0){String a=x.substring(0,at);x=x.substring(at+1);
        int c=a.indexOf(':');user=URLDecoder.decode(c<0?a:a.substring(0,c),"UTF-8");
        pw=URLDecoder.decode(c<0?"":a.substring(c+1),"UTF-8");}
      int colon=x.lastIndexOf(':'); if(colon<1||x.contains("]"))throw new IOException("Invalid endpoint");
      int port=Integer.parseInt(x.substring(colon+1));if(port<1||port>65535)throw new IOException("Invalid port");
      if(user.getBytes("UTF-8").length>255||pw.getBytes("UTF-8").length>255)throw new IOException("Credential too long");
      return new Entry(x.substring(0,colon),port,user,pw);
    }
  }
  static Inet4Address ipv4(Network n,String host)throws Exception {
    InetAddress[] all=n==null?InetAddress.getAllByName(host):n.getAllByName(host);
    for(InetAddress a:all)if(a instanceof Inet4Address)return (Inet4Address)a;
    throw new IOException("IPv4 proxy required");
  }
  static Inet4Address verify(Network n,Entry e)throws Exception{
    Inet4Address ip=ipv4(n,e.host);
    try(Socket sock=new Socket()){
      sock.connect(new InetSocketAddress(ip,e.port),4500);
      sock.setSoTimeout(7000);
      InputStream in=sock.getInputStream();OutputStream out=sock.getOutputStream();
      out.write(new byte[]{5,2,0,2});out.flush();
      int ver=in.read(),method=in.read();
      if(ver!=5||(method!=0&&method!=2))throw new IOException("Not a supported SOCKS5 server");
      if(method==2){
        byte[] u=e.user.getBytes("UTF-8"),p=e.pass.getBytes("UTF-8");
        out.write(1);out.write(u.length);out.write(u);out.write(p.length);out.write(p);out.flush();
        if(in.read()!=1||in.read()!=0)throw new IOException("SOCKS credentials rejected");
      }
      byte[] target="www.example.com".getBytes("US-ASCII");
      out.write(new byte[]{5,1,0,3,(byte)target.length});out.write(target);out.write(1);out.write(187);out.flush();
      byte[] hdr=take(in,4);
      if(hdr[0]!=5||hdr[1]!=0)throw new IOException("SOCKS CONNECT failed: "+(hdr[1]&255));
      int k=hdr[3]&255;
      if(k==1)take(in,6);
      else if(k==4)take(in,18);
      else if(k==3){int len=in.read();if(len<0)throw new EOFException();take(in,len+2);}
      else throw new IOException("Invalid SOCKS response");
      SSLSocket ssl=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(sock,"www.example.com",443,false);
      SSLParameters params=ssl.getSSLParameters();params.setEndpointIdentificationAlgorithm("HTTPS");ssl.setSSLParameters(params);
      ssl.startHandshake();
      ssl.getOutputStream().write("GET / HTTP/1.1\r\nHost: www.example.com\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));
      byte[] reply=new byte[40];int got=ssl.getInputStream().read(reply);
      if(got<=0||!new String(reply,0,got,"US-ASCII").startsWith("HTTP/"))
        throw new IOException("No valid HTTPS response through relay");
      ssl.close();
      return ip;
    }
  }
  static byte[] take(InputStream in,int size)throws IOException{
    byte[] v=new byte[size];int p=0;
    while(p<size){int k=in.read(v,p,size-p);if(k<0)throw new EOFException();p+=k;}return v;
  }
}