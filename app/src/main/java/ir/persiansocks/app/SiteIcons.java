package ir.persiansocks.app;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.net.*;
import android.util.LruCache;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** One persisted bitmap per exact hostname; removing its last test deletes its icon and provenance. */
public final class SiteIcons {
    private final Context context;private final Runnable changed;private final File directory;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"site-icons");t.setPriority(Thread.MIN_PRIORITY);return t;});
    private final LruCache<String,Bitmap> memory=new LruCache<String,Bitmap>(1024*1024){protected int sizeOf(String k,Bitmap b){return b.getByteCount();}};
    private final IconRetryPolicy retries=new IconRetryPolicy();private final java.util.concurrent.atomic.AtomicBoolean scheduled=new java.util.concurrent.atomic.AtomicBoolean();private volatile Set<String> hosts=Collections.emptySet();private volatile boolean closed;private volatile HttpURLConnection connection;
    public SiteIcons(Context c,Runnable r){context=c.getApplicationContext();changed=r;directory=new File(context.getFilesDir(),"site-icons");directory.mkdirs();}
    private String key(String host){try{byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(host.getBytes(StandardCharsets.US_ASCII));StringBuilder s=new StringBuilder();for(byte b:hash)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    public Bitmap get(String host){return memory.get(host);}
    public synchronized void sync(){
        if(closed)return;
        Set<String> next=new HashSet<>();for(ServiceTargets.Target t:ServiceTargets.current())next.add(t.host);
        hosts=Collections.unmodifiableSet(next);memory.snapshot().keySet().stream().filter(h->!next.contains(h)).forEach(memory::remove);retries.retain(next);
        if(!scheduled.compareAndSet(false,true))return;
        worker.execute(()->{try{
            Set<String> current=hosts;Set<String> names=new HashSet<>();for(String h:current){names.add(key(h)+".png");names.add(key(h)+".json");}
            File[] files=directory.listFiles();if(files!=null)for(File f:files)if(!names.contains(f.getName()))f.delete();
            // A warm icon cache needs no copy of the potentially large proxy index.
            List<ProxyEndpoint> candidates=null;
            for(String host:current){
                if(closed||!hosts.contains(host)||memory.get(host)!=null)continue;
                File target=new File(directory,key(host)+".png");Bitmap bmp=target.isFile()?BitmapFactory.decodeFile(target.getPath()):null;
                if(bmp!=null){synchronized(this){if(hosts.contains(host)){memory.put(host,bmp);notifyChanged();}else bmp.recycle();}continue;}
                if(candidates==null)candidates=ProxyStore.index().snapshot();
                ProxyEndpoint route=iconRoute(host,candidates);
                Network active=context.getSystemService(ConnectivityManager.class).getActiveNetwork();
                String fingerprint=String.valueOf(active)+":"+ProxyVpnService.phase()+":"+(route==null?"direct":route.key());
                if(!retries.begin(host,fingerprint,android.os.SystemClock.elapsedRealtime()))continue;
                try{
                    // A verified proxy fetches this site's real icon even when the physical ISP blocks it.
                    if(route!=null)try{PhysicalTestNetwork path=PhysicalTestNetwork.capture(context);if(fetch(host,target,route,path))continue;}catch(Exception ignored){}
                    fetch(host,target,null,null);
                }catch(Exception ignored){}
            }
        }finally{scheduled.set(false);}});
    }
    private ProxyEndpoint iconRoute(String host,List<ProxyEndpoint> candidates){
        ProxyEndpoint active=ProxyEndpoint.parse(ProxyVpnService.activeEndpoint());
        if(ProxyVpnService.phase()==ProxyVpnService.CONNECTED&&active!=null&&DistributionPolicy.allows(active))return active;
        for(ServiceTargets.Target t:ServiceTargets.current())if(t.host.equals(host)){
            ProxyEndpoint best=null;for(ProxyEndpoint p:candidates)if(p.passed(t)&&DistributionPolicy.allows(p)&&(best==null||p.latencyMs<best.latencyMs))best=p;
            if(best!=null)return best;
        }return null;
    }
    private void notifyChanged(){new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{if(!closed)changed.run();});}
    private static final class Body{final byte[] data;final String url;Body(byte[] b,String u){data=b;url=u;}}
    private Body read(String url,String original,boolean page,ProxyEndpoint proxy,PhysicalTestNetwork path)throws Exception {
        long deadline=System.nanoTime()+15_000_000_000L;int limit=page?128*1024:1024*1024;
        for(int redirect=0;redirect<5;redirect++){
            if(closed||!IconPolicy.https(url)||page&&!IconPolicy.sameSite(original,url))throw new IOException("Icon origin");HttpURLConnection c;if(proxy!=null)c=new ProxyHttpConnection(new URL(url),proxy,path::socket,path::resolve);else{Network network=context.getSystemService(ConnectivityManager.class).getActiveNetwork();if(network==null)throw new IOException("Offline");c=AppHttp.open(new URL(url),network);}connection=c;
            try{c.setConnectTimeout(4000);c.setReadTimeout(4000);c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent","Mozilla/5.0 PersianSocks site icon");c.setRequestProperty("Accept-Encoding","identity");int status=c.getResponseCode();if(status>=300&&status<400){String location=c.getHeaderField("Location");if(location==null)throw new IOException("Redirect");url=new URL(new URL(url),location).toString();continue;}if(status!=200)throw new IOException("HTTP status");String encoding=c.getContentEncoding();if(encoding!=null&&!encoding.equalsIgnoreCase("identity")&&!encoding.equalsIgnoreCase("gzip"))throw new IOException("Icon encoding");try(InputStream raw=c.getInputStream();InputStream in="gzip".equalsIgnoreCase(encoding)?new java.util.zip.GZIPInputStream(raw):raw;ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){if(closed||System.nanoTime()>deadline)throw new IOException("Icon timeout");if(bytes.size()+n>limit){if(!page)throw new IOException("Icon limit");bytes.write(buf,0,limit-bytes.size());break;}bytes.write(buf,0,n);}return new Body(bytes.toByteArray(),url);}}finally{c.disconnect();connection=null;}
        }throw new IOException("Icon redirect limit");
    }
    private boolean fetch(String host,File target,ProxyEndpoint proxy,PhysicalTestNetwork path)throws Exception {
        String origin="https://"+host+"/";List<String> links=new ArrayList<>();try{Body page=read(origin,origin,true,proxy,path);links=IconPolicy.icons(new String(page.data,StandardCharsets.UTF_8),page.url);}catch(Exception ignored){links.add(origin+"favicon.ico");}
        for(String link:links){if(closed||!hosts.contains(host))return false;try{Body body=read(link,origin,false,proxy,path);Bitmap bitmap=decode(body.data);if(bitmap==null)continue;if(bitmap.getWidth()>96||bitmap.getHeight()>96){float scale=96f/Math.max(bitmap.getWidth(),bitmap.getHeight());Bitmap old=bitmap;bitmap=Bitmap.createScaledBitmap(old,Math.max(1,Math.round(old.getWidth()*scale)),Math.max(1,Math.round(old.getHeight()*scale)),true);if(old!=bitmap)old.recycle();}
                synchronized(this){if(closed||!hosts.contains(host)){bitmap.recycle();return false;}File temp=new File(directory,key(host)+".tmp");try(OutputStream out=new FileOutputStream(temp)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}if(!temp.renameTo(target)){temp.delete();throw new IOException("Icon storage");}try(OutputStream out=new FileOutputStream(new File(directory,key(host)+".json"))){out.write(new org.json.JSONObject().put("host",host).put("source",body.url).toString().getBytes(StandardCharsets.UTF_8));}memory.put(host,bitmap);}notifyChanged();return true;
            }catch(Exception ignored){}}return false;
    }
    private static int le(byte[] b,int p,int n){int v=0;for(int i=0;i<n;i++)v|=(b[p+i]&255)<<(8*i);return v;}
    static Bitmap decode(byte[] data){
        try{
            if(data.length>22&&le(data,0,2)==0&&le(data,2,2)==1){int count=Math.min(32,le(data,4,2));for(int i=count-1;i>=0;i--){int at=6+i*16;if(at+16>data.length)continue;int size=le(data,at+8,4),offset=le(data,at+12,4);if(size<40||offset<0||size>data.length-offset)continue;byte[] icon=Arrays.copyOfRange(data,offset,offset+size);Bitmap png=decodeRaster(icon);if(png!=null)return png;int header=le(icon,0,4),w=le(icon,4,4),h=le(icon,8,4)/2,bits=le(icon,14,2);if(header<40||w<1||h<1||w>512||h>512||(bits!=32&&bits!=24)||le(icon,16,4)!=0)continue;int stride=((w*bits+31)/32)*4,maskStride=((w+31)/32)*4;if(header+(long)stride*h>icon.length)continue;int[] pixels=new int[w*h];boolean alpha=false;for(int y=0;y<h;y++)for(int x=0;x<w;x++){int p=header+(h-1-y)*stride+x*(bits/8),a=bits==32?icon[p+3]&255:255;if(bits==32&&a!=0)alpha=true;pixels[y*w+x]=Color.argb(a,icon[p+2]&255,icon[p+1]&255,icon[p]&255);}int mask=header+stride*h;for(int y=0;y<h;y++)for(int x=0;x<w;x++){int m=mask+(h-1-y)*maskStride+x/8;boolean transparent=m<icon.length&&(icon[m]&(128>>(x%8)))!=0;if(transparent)pixels[y*w+x]&=0xffffff;else if(bits==32&&!alpha)pixels[y*w+x]|=0xff000000;}return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);}return null;}
            return decodeRaster(data);
        }catch(Exception e){return null;}
    }
    private static Bitmap decodeRaster(byte[] data){BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,bounds);if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>4096||bounds.outHeight>4096)return null;BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=1;while(bounds.outWidth/opts.inSampleSize>256||bounds.outHeight/opts.inSampleSize>256)opts.inSampleSize*=2;return BitmapFactory.decodeByteArray(data,0,data.length,opts);}
    public void close(){closed=true;if(connection!=null)connection.disconnect();worker.shutdownNow();memory.evictAll();}
    public static Drawable placeholder(){return new Drawable(){private final Paint p=new Paint(3);public void draw(Canvas c){p.setColor(Color.rgb(151,169,189));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(1,getBounds().width()/15f));Rect r=getBounds();float x=r.exactCenterX(),y=r.exactCenterY(),s=r.width()*.4f;c.drawCircle(x,y,s,p);c.drawOval(x-s/2,y-s,x+s/2,y+s,p);c.drawLine(x-s,y,x+s,y,p);}public void setAlpha(int a){}public void setColorFilter(ColorFilter f){}public int getOpacity(){return PixelFormat.TRANSLUCENT;}};}
}
