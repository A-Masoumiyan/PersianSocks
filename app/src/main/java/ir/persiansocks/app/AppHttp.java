package ir.persiansocks.app;

import android.content.Context;
import android.net.Network;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** App HTTP only. SOCKS/CONNECT probing and tunneled user traffic keep their existing transport. */
public final class AppHttp {
    private static volatile Context app;
    private static final Map<Long,OkHttpClient> clients=new LinkedHashMap<>();
    static void initialize(Context context){app=context.getApplicationContext();ProxyScanner.httpFactory(url->{Network network=app.getSystemService(android.net.ConnectivityManager.class).getActiveNetwork();if(network==null)throw new IOException("Offline");return open(url,network);});}
    public static HttpURLConnection open(URL url,Network network)throws IOException {
        if(network==null)throw new IOException("No captured network");
        if(app!=null&&app.getSharedPreferences("persiansocks",0).getBoolean("cronet-experimental",false)&&url.getProtocol().equals("https")){
            try{return new CronetConnection(url,network,app);}catch(Exception|LinkageError e){android.util.Log.i("PersianSocks","Cronet unavailable; using OkHttp");}
        }
        return okhttp(url,network);
    }
    static HttpURLConnection okhttp(URL url,Network network){return new Connection(url,network);}
    private static synchronized OkHttpClient client(Network network){
        long handle=network.getNetworkHandle();OkHttpClient c=clients.get(handle);if(c!=null)return c;
        c=new OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).socketFactory(network.getSocketFactory())
            .dns(host->{try{return Arrays.asList(network.getAllByName(host));}catch(UnknownHostException e){throw e;}})
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).connectionPool(new ConnectionPool(4,2,TimeUnit.MINUTES)).build();
        clients.put(handle,c);if(clients.size()>4){Iterator<OkHttpClient> i=clients.values().iterator();OkHttpClient old=i.next();i.remove();old.connectionPool().evictAll();}
        return c;
    }
    static final class Connection extends HttpURLConnection {
        private final Network network;private final OkHttpClient configured;private final Map<String,String> headers=new LinkedHashMap<>();
        private volatile Call call;private volatile Response response;private volatile boolean closed;
        Connection(URL url,Network network){super(url);this.network=network;configured=null;}
        Connection(URL url,OkHttpClient client){super(url);network=null;configured=client;}
        public boolean usingProxy(){return false;}
        public void disconnect(){closed=true;Call c=call;if(c!=null)c.cancel();Response r=response;if(r!=null)r.close();}
        @Override public void setRequestProperty(String k,String v){if(connected)throw new IllegalStateException("Connected");headers.put(k,v);}
        @Override public String getRequestProperty(String k){return headers.get(k);}
        public void connect()throws IOException {
            if(closed)throw new IOException("Cancelled");if(connected)return;
            OkHttpClient c=(configured==null?client(network):configured).newBuilder().connectTimeout(Math.max(1,getConnectTimeout()),TimeUnit.MILLISECONDS).readTimeout(Math.max(1,getReadTimeout()),TimeUnit.MILLISECONDS).callTimeout(130,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();
            String address=url.toString();
            for(int redirects=0;redirects<6;redirects++){
                Request.Builder b=new Request.Builder().url(address);for(Map.Entry<String,String> h:headers.entrySet())b.header(h.getKey(),h.getValue());call=c.newCall(b.build());if(closed){call.cancel();throw new IOException("Cancelled");}Response r=call.execute();response=r;
                if(closed){r.close();throw new IOException("Cancelled");}
                if(getInstanceFollowRedirects()&&r.isRedirect()){
                    String location=r.header("Location");if(location==null){connected=true;responseCode=r.code();return;}
                    URL next=new URL(new URL(address),location);if(!next.getProtocol().equals("https")||next.getUserInfo()!=null){r.close();throw new IOException("Unsafe HTTP redirect");}address=next.toString();r.close();continue;
                }
                responseCode=r.code();connected=true;return;
            }
            disconnect();throw new IOException("HTTP redirect limit");
        }
        public int getResponseCode()throws IOException{connect();return responseCode;}
        public InputStream getInputStream()throws IOException{connect();if(responseCode>=400)throw new FileNotFoundException("HTTP "+responseCode);if(response.body()==null)return new ByteArrayInputStream(new byte[0]);return response.body().byteStream();}
        public String getHeaderField(String name){try{connect();return response.header(name);}catch(IOException e){return null;}}
        public Map<String,List<String>> getHeaderFields(){try{connect();return response.headers().toMultimap();}catch(IOException e){return Collections.emptyMap();}}
        public String getContentType(){return getHeaderField("Content-Type");}
        public String getContentEncoding(){return getHeaderField("Content-Encoding");}
        public long getContentLengthLong(){try{return Long.parseLong(getHeaderField("Content-Length"));}catch(Exception e){return -1;}}
    }
}
