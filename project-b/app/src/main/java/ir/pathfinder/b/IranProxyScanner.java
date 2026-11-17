package ir.pathfinder.b;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Country-filtered audit of publicly advertised proxy endpoints, not blind IP scanning. */
final class IranProxyScanner {
  interface Progress {
    void update(int done,int total,int successful);
  }
  enum Type { SOCKS5, HTTP }
  static final class Candidate {
    final String ip;final int port;final Type type;
    Candidate(String ip,int port,Type type){this.ip=ip;this.port=port;this.type=type;}
    String key(){return type.name()+":"+ip+":"+port;}
    String display(){return ip+":"+port+" ("+type.name()+")";}
  }
  static final class Hit {
    final Candidate proxy;final boolean telegram,youtube,thumbnail;final long duration;
    Hit(Candidate p,boolean t,boolean y,boolean image,long ms){
      proxy=p;telegram=t;youtube=y;thumbnail=image;duration=ms;
    }
    String format(){
      String types=(telegram?"Telegram API ":"")+(youtube?"YouTube ":"");
      if(thumbnail)types+="YouTube image ";
      return proxy.display()+" | "+types.trim()+" | "+duration+" ms";
    }
  }
  static final class Result {
    final List<Hit> hits; final int candidates,checked;
    Result(List<Hit> hits,int candidates,int checked){this.hits=hits;this.candidates=candidates;this.checked=checked;}
  }
  private static final int WORKERS=12, MAX_PUBLIC_ENDPOINTS=3000;
  private static String[] sources() {
    return new String[]{"offline-socks5.txt","offline-http.txt","iran-public-proxies.txt"};
  }
  static Result scan(Context ctx, Progress progress, AtomicBoolean stop) throws Exception {
    ArrayList<Candidate> candidates=new ArrayList<>();
    HashSet<String> seen=new HashSet<>();
    OfflineCountry country;
    try(InputStream source=ctx.getAssets().open("offline-ip-country.bin")){
      country=new OfflineCountry(source);
    }
    for(String name:sources()){
      Type protocol=name.equals("offline-socks5.txt")?Type.SOCKS5:Type.HTTP;
      try(BufferedReader reader=new BufferedReader(new InputStreamReader(ctx.getAssets().open(name),"UTF-8"))){
        for(String line;(line=reader.readLine())!=null;){
          Candidate parsed=parse(line,protocol);
          if(parsed==null||!country.code(parsed.ip).equals("IR"))continue;
          if(seen.add(parsed.key()))candidates.add(parsed);
          if(candidates.size()>=MAX_PUBLIC_ENDPOINTS)break;
        }
      }catch(FileNotFoundException ignored){}
      if(candidates.size()>=MAX_PUBLIC_ENDPOINTS)break;
    }
    Collections.shuffle(candidates,new Random(System.nanoTime()));
    int total=candidates.size();
    if(total==0)return new Result(Collections.emptyList(),0,0);
    ConnectivityManager manager=(ConnectivityManager)ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
    Network physical=null;
    if(manager!=null)for(Network n:manager.getAllNetworks()){
      NetworkCapabilities caps=manager.getNetworkCapabilities(n);
      if(caps!=null&&!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
         &&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)){
        physical=n;break;
      }
    }
    final Network network=physical;
    ExecutorService exec=Executors.newFixedThreadPool(WORKERS);
    CompletionService<Hit> completion=new ExecutorCompletionService<>(exec);
    ArrayList<Hit> hits=new ArrayList<>();
    int checked=0;
    try {
      for(Candidate c:candidates){
        completion.submit(()->test(network,c,stop));
      }
      for(int i=0;i<total&&!stop.get();i++){
        Future<Hit> future=completion.poll(20,TimeUnit.SECONDS);
        if(future==null)continue;
        Hit hit=null;
        try{hit=future.get();}catch(Exception ignored){}
        checked++;
        if(hit!=null)hits.add(hit);
        if(checked%4==0||hit!=null||checked==total)progress.update(checked,total,hits.size());
      }
    }finally{exec.shutdownNow();}
    hits.sort((a,b)->{
      int qa=(a.telegram&&a.youtube?2:1)+(a.thumbnail?1:0);
      int qb=(b.telegram&&b.youtube?2:1)+(b.thumbnail?1:0);
      int d=Integer.compare(qb,qa);
      return d!=0?d:Long.compare(a.duration,b.duration);
    });
    return new Result(hits,total,checked);
  }
  private static Candidate parse(String raw,Type suggested){
    if(raw==null)return null;
    String text=raw.trim().toLowerCase(Locale.ROOT);
    if(text.isEmpty()||text.startsWith("#")||text.length()>150)return null;
    Type kind=suggested;
    if(text.startsWith("socks5://")){text=text.substring(9);kind=Type.SOCKS5;}
    else if(text.startsWith("http://")){text=text.substring(7);kind=Type.HTTP;}
    else if(text.startsWith("https://"))return null; // TLS-wrapped proxy requires separate trust verification
    int index=text.lastIndexOf(':');if(index<1||index==text.length()-1||text.contains("@"))return null;
    String ip=text.substring(0,index);
    if(!publicIpv4(ip))return null;
    int port;try{port=Integer.parseInt(text.substring(index+1));}catch(Exception e){return null;}
    if(port<1||port>65535)return null;
    return new Candidate(ip,port,kind);
  }
  private static boolean publicIpv4(String addr){
    String[] a=addr.split("\\.",-1);
    if(a.length!=4)return false;
    int[] p=new int[4];
    try {
      for(int i=0;i<4;i++){
        if(a[i].isEmpty()||a[i].length()>3)return false;
        p[i]=Integer.parseInt(a[i]);if(p[i]<0||p[i]>255)return false;
      }
    }catch(Exception e){return false;}
    return !(p[0]==0||p[0]==10||p[0]==127||p[0]>=224
       ||(p[0]==169&&p[1]==254)||(p[0]==172&&p[1]>=16&&p[1]<=31)
       ||(p[0]==192&&p[1]==168)||(p[0]==100&&p[1]>=64&&p[1]<=127)
       ||(p[0]==198&&(p[1]==18||p[1]==19)));
  }
  private static Hit test(Network network,Candidate candidate,AtomicBoolean stop){
    if(stop.get())return null;
    long t=android.os.SystemClock.elapsedRealtime();
    boolean telegram=attempt(network,candidate,"api.telegram.org","/");
    if(stop.get())return null;
    boolean youtube=attempt(network,candidate,"www.youtube.com","/robots.txt");
    if(!telegram&&!youtube)return null;
    boolean thumbnail=youtube&&attempt(network,candidate,"i.ytimg.com","/vi/aqz-KE-bpKQ/default.jpg");
    return new Hit(candidate,telegram,youtube,thumbnail,android.os.SystemClock.elapsedRealtime()-t);
  }
  private static boolean attempt(Network n,Candidate c,String dest,String path){
    Socket base=null;SSLSocket tls=null;
    try {
      base=n!=null?n.getSocketFactory().createSocket():new Socket();
      base.setSoTimeout(4000);
      base.connect(new InetSocketAddress(InetAddress.getByName(c.ip),c.port),2500);
      InputStream in=base.getInputStream();OutputStream out=base.getOutputStream();
      if(c.type==Type.SOCKS5) {
        out.write(new byte[]{5,1,0});out.flush();
        if(in.read()!=5||in.read()!=0)return false;
        byte[] target=dest.getBytes("US-ASCII");
        out.write(new byte[]{5,1,0,3,(byte)target.length});out.write(target);
        out.write(1);out.write(187);out.flush();
        byte[] head=RelayProbe.take(in,4);
        if(head[0]!=5||head[1]!=0)return false;
        int addrType=head[3]&255;
        if(addrType==1)RelayProbe.take(in,6);
        else if(addrType==4)RelayProbe.take(in,18);
        else if(addrType==3){
          int nBytes=in.read();if(nBytes<0)return false;
          RelayProbe.take(in,nBytes+2);
        }else return false;
      } else {
        String connect="CONNECT "+dest+":443 HTTP/1.1\r\nHost: "+dest+
          ":443\r\nProxy-Connection: close\r\n\r\n";
        out.write(connect.getBytes("US-ASCII"));out.flush();
        String firstLine=readLine(in);
        if(!firstLine.matches("HTTP/1\\.[01] 200(?: .*)?"))return false;
        while(!readLine(in).isEmpty()){}
      }
      tls=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault())
        .createSocket(base,dest,443,false);
      tls.setSoTimeout(4500);
      SSLParameters param=tls.getSSLParameters();
      param.setEndpointIdentificationAlgorithm("HTTPS");
      tls.setSSLParameters(param);
      tls.startHandshake();
      byte[] req=("GET "+path+" HTTP/1.1\r\nHost: "+dest+
        "\r\nConnection: close\r\nUser-Agent: PathFinderB/0.4\r\n\r\n").getBytes("US-ASCII");
      tls.getOutputStream().write(req);tls.getOutputStream().flush();
      String first=readLine(tls.getInputStream());
      // A real HTTP response from a TLS-verified remote endpoint; distinguish
      // availability from full media/chat functionality in the UI.
      return first.matches("HTTP/1\\.[01] (?:200|204|301|302|400|401|403|404|405)(?: .*)?");
    }catch(Exception e){return false;}
    finally {
      if(tls!=null)try{tls.close();}catch(Exception ignored){}
      if(base!=null)try{base.close();}catch(Exception ignored){}
    }
  }
  private static String readLine(InputStream in)throws IOException{
    ByteArrayOutputStream buf=new ByteArrayOutputStream();
    while(buf.size()<1024){
      int c=in.read();if(c<0)throw new EOFException();
      if(c=='\n')break;
      if(c!='\r')buf.write(c);
    }
    if(buf.size()>=1024)throw new IOException("Overlong header");
    return buf.toString("US-ASCII");
  }
}