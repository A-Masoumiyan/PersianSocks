import ir.persiansocks.app.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.*;
public final class DownloadFallbackHarness {
 static int checks;static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;System.out.println("PASS: "+message);}
 static final class Connection extends HttpURLConnection {
  final int code;final boolean tlsFailure;boolean closed;
  Connection(URL u,int code,boolean tls){super(u);this.code=code;tlsFailure=tls;}
  public int getResponseCode()throws IOException{if(tlsFailure)throw new javax.net.ssl.SSLHandshakeException("certificate rejected");return code;}
  public InputStream getInputStream(){return new ByteArrayInputStream(new byte[400_000]);}
  public void connect(){}public void disconnect(){closed=true;}public boolean usingProxy(){return true;}
 }
 static String run(int first,boolean tls,boolean valid,DownloadSpeedTest test,List<URL> destinations,AtomicInteger callbacks,AtomicLong bytes){
  AtomicReference<String> error=new AtomicReference<>();
  test.run(new DownloadSpeedTest.Transport(){public boolean valid(){return valid;}public HttpURLConnection open(URL url){check(PreparedScanner.trafficCap(false)<=4,"Scan yields capacity throughout speed measurement");destinations.add(url);return new Connection(url,destinations.size()==1?first:200,tls);}},
   new DownloadSpeedTest.Listener(){public void progress(long b,double rate){}public void finished(long b,double rate,String e){callbacks.incrementAndGet();bytes.set(b);error.set(e);}});
  check(PreparedScanner.trafficCap(false)==216,"Measurement releases scan capacity on success, failure and cancellation");return error.get();
 }
 public static void main(String[]args){
  PreparedScanner.foreground(true);
  PreparedScanner.TrafficReservation first=PreparedScanner.downloadMeasurement(),second=PreparedScanner.downloadMeasurement();
  first.close();first.close();check(PreparedScanner.trafficCap(false)==4,"Closing one reservation twice preserves another active measurement");second.close();
  try{new DownloadSpeedTest().run(new DownloadSpeedTest.Transport(){public boolean valid(){return false;}public HttpURLConnection open(URL u){throw new AssertionError();}},new DownloadSpeedTest.Listener(){public void progress(long b,double r){}public void finished(long b,double r,String e){throw new IllegalStateException("callback");}});}catch(IllegalStateException expected){}
  check(PreparedScanner.trafficCap(false)==216,"Throwing completion callback also releases measurement capacity");
  for(int status:new int[]{403,429,503}){
   List<URL> urls=new ArrayList<>();AtomicInteger callbacks=new AtomicInteger();AtomicLong bytes=new AtomicLong();
   String error=run(status,false,true,new DownloadSpeedTest(),urls,callbacks,bytes);
   check(error.isEmpty()&&bytes.get()==400_000&&callbacks.get()==1,"One validated alternate sample replaces a denied/throttled primary server: "+status);
   check(urls.size()==2&&urls.get(0).getHost().equals("speed.cloudflare.com")&&urls.get(1).getHost().equals("proof.ovh.net"),"Alternate destination stays inside the explicitly supplied proxy transport");
  }
  for(int status:new int[]{302,401,407}){
   List<URL> urls=new ArrayList<>();String error=run(status,false,true,new DownloadSpeedTest(),urls,new AtomicInteger(),new AtomicLong());
   check(!error.isEmpty()&&urls.size()==1,"Redirects and authentication failures do not trigger alternate requests: "+status);
  }
  List<URL> urls=new ArrayList<>();check(!run(200,true,true,new DownloadSpeedTest(),urls,new AtomicInteger(),new AtomicLong()).isEmpty()&&urls.size()==1,"Certificate failure is never bypassed by trying another server");
  urls=new ArrayList<>();check(!run(200,false,false,new DownloadSpeedTest(),urls,new AtomicInteger(),new AtomicLong()).isEmpty()&&urls.isEmpty(),"No request leaves an invalid captured network");
  DownloadSpeedTest stopped=new DownloadSpeedTest();stopped.cancel();AtomicInteger callbacks=new AtomicInteger();urls=new ArrayList<>();
  check(run(200,false,true,stopped,urls,callbacks,new AtomicLong()).equals("تست متوقف شد")&&callbacks.get()==1&&urls.isEmpty(),"Cancellation before starting returns exactly once with no network request");
  System.out.println("RESULT: "+checks+" checks passed");
 }
}
