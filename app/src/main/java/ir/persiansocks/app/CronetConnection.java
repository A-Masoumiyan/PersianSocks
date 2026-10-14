package ir.persiansocks.app;

import android.content.Context;
import android.net.Network;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import org.chromium.net.*;

/** Experimental, network-bound HTTP/2/3 client. Bounded streaming and pre-header fallback only. */
final class CronetConnection extends HttpURLConnection {
    private static CronetEngine engine;
    private static final ExecutorService callbacks=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"app-cronet");t.setDaemon(true);return t;});
    private static synchronized CronetEngine engine(Context context){if(engine==null)engine=new CronetEngine.Builder(context).enableHttp2(true).enableQuic(true).enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISABLED,0).build();return engine;}
    private final Network network;private final CronetEngine cronet;
    private final Map<String,String> headers=new LinkedHashMap<>();private volatile UrlRequest request;
    private UrlResponseInfo info;private IOException error;private boolean closed,done,reading;private byte[] chunk;private int offset,redirects;
    private HttpURLConnection fallback;
    CronetConnection(URL url,Network network,Context context){super(url);this.network=network;cronet=engine(context);}
    private static boolean safeRedirect(String address){try{URL u=new URL(address);return u.getProtocol().equals("https")&&u.getUserInfo()==null;}catch(Exception e){return false;}}
    public boolean usingProxy(){return false;}
    public synchronized void disconnect(){closed=true;if(request!=null)request.cancel();if(fallback!=null)fallback.disconnect();notifyAll();}
    public void setRequestProperty(String k,String v){if(connected)throw new IllegalStateException("Connected");headers.put(k,v);}
    public String getRequestProperty(String k){return headers.get(k);}
    private synchronized void header(UrlResponseInfo i){info=i;responseCode=i.getHttpStatusCode();connected=true;notifyAll();}
    public void connect()throws IOException {
        synchronized(this){if(closed)throw new IOException("Cancelled");if(connected)return;
            try{
                UrlRequest.Builder b=cronet.newUrlRequestBuilder(url.toString(),new UrlRequest.Callback(){
                    public void onRedirectReceived(UrlRequest r,UrlResponseInfo i,String next){
                        synchronized(CronetConnection.this){if(closed){r.cancel();return;}if(!getInstanceFollowRedirects()){header(i);done=true;r.cancel();return;}if(++redirects>5||!safeRedirect(next)){error=new IOException("Unsafe redirect");done=true;r.cancel();CronetConnection.this.notifyAll();return;}}
                        r.followRedirect();
                    }
                    public void onResponseStarted(UrlRequest r,UrlResponseInfo i){header(i);}
                    public void onReadCompleted(UrlRequest r,UrlResponseInfo i,ByteBuffer buffer){synchronized(CronetConnection.this){buffer.flip();chunk=new byte[buffer.remaining()];buffer.get(chunk);offset=0;reading=false;CronetConnection.this.notifyAll();}}
                    public void onSucceeded(UrlRequest r,UrlResponseInfo i){synchronized(CronetConnection.this){done=true;reading=false;CronetConnection.this.notifyAll();}}
                    public void onFailed(UrlRequest r,UrlResponseInfo i,CronetException e){synchronized(CronetConnection.this){error=new IOException("Cronet request failed",e);done=true;reading=false;CronetConnection.this.notifyAll();}}
                    public void onCanceled(UrlRequest r,UrlResponseInfo i){synchronized(CronetConnection.this){done=true;reading=false;CronetConnection.this.notifyAll();}}
                },callbacks).bindToNetwork(network.getNetworkHandle()).disableCache();
                for(Map.Entry<String,String> h:headers.entrySet())b.addHeader(h.getKey(),h.getValue());request=b.build();request.start();
                long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.max(1000,getConnectTimeout())+Math.max(1000,getReadTimeout()));
                while(info==null&&error==null&&!closed&&!done)await(end);
                if(closed)throw new IOException("Cancelled");if(info!=null)return;if(error==null)error=new IOException("Cronet response unavailable");
            }catch(IOException|RuntimeException e){error=e instanceof IOException?(IOException)e:new IOException(e);if(request!=null)request.cancel();}
            if(closed)throw new IOException("Cancelled");
            fallback=AppHttp.okhttp(url,network);fallback.setConnectTimeout(getConnectTimeout());fallback.setReadTimeout(getReadTimeout());fallback.setInstanceFollowRedirects(getInstanceFollowRedirects());for(Map.Entry<String,String> h:headers.entrySet())fallback.setRequestProperty(h.getKey(),h.getValue());
        }
        fallback.connect();responseCode=fallback.getResponseCode();connected=true;
    }
    private void await(long deadline)throws IOException{long ms=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());if(ms<=0)throw new SocketTimeoutException("Cronet timeout");try{wait(Math.min(ms,250));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("Cancelled");}}
    public int getResponseCode()throws IOException{connect();return fallback==null?responseCode:fallback.getResponseCode();}
    public String getHeaderField(String name){try{connect();if(fallback!=null)return fallback.getHeaderField(name);List<String> list=info.getAllHeaders().entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse(null);return list==null||list.isEmpty()?null:list.get(0);}catch(IOException e){return null;}}
    public String getContentType(){return getHeaderField("Content-Type");}
    public String getContentEncoding(){return getHeaderField("Content-Encoding");}
    public InputStream getInputStream()throws IOException{
        connect();if(fallback!=null)return fallback.getInputStream();if(responseCode>=400)throw new FileNotFoundException("HTTP "+responseCode);
        return new InputStream(){
            public int read()throws IOException{byte[] b=new byte[1];int n=read(b,0,1);return n<0?-1:b[0]&255;}
            public int read(byte[] b,int start,int len)throws IOException{if(len==0)return 0;if(start<0||len<0||start>b.length-len)throw new IndexOutOfBoundsException();synchronized(CronetConnection.this){long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.max(1000,getReadTimeout()));for(;;){if(closed)throw new IOException("Cancelled");if(chunk!=null&&offset<chunk.length){int n=Math.min(len,chunk.length-offset);System.arraycopy(chunk,offset,b,start,n);offset+=n;if(offset==chunk.length)chunk=null;return n;}if(error!=null)throw error;if(done)return -1;if(!reading){reading=true;request.read(ByteBuffer.allocateDirect(16384));}await(end);}}}
            public void close(){disconnect();}
        };
    }
}
