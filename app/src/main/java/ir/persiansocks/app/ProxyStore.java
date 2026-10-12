package ir.persiansocks.app;

import android.content.Context;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Cache serialization is off UI. Shared index prevents stale save snapshots reviving failures. */
public final class ProxyStore {
    public static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"proxy-storage");t.setDaemon(true);return t;});
    private static final ProxyIndex index=new ProxyIndex();
    private static final AtomicBoolean saving=new AtomicBoolean();
    private static volatile boolean loaded;
    private static volatile long writtenRevision=-1;
    private ProxyStore(){}
    public static ProxyIndex index(){return index;}
    public static boolean loaded(){return loaded;}
    public static synchronized List<ProxyEndpoint> load(Context context) {
        if(!loaded) {
            ProxyCacheFile file=new ProxyCacheFile(context.getFilesDir());
            try {
                if(file.exists())file.load(index);
                else {
                    JSONArray old=new JSONArray(context.getSharedPreferences("persiansocks",Context.MODE_PRIVATE).getString("good","[]"));
                    for(int i=0;i<old.length();i++){JSONObject row=old.optJSONObject(i);if(row!=null)index.accept(ProxyEndpoint.fromJson(row));}
                    file.save(index);
                    context.getSharedPreferences("persiansocks",Context.MODE_PRIVATE).edit().remove("good").apply();
                }
                loaded=true;writtenRevision=index.revision();
            }catch(Exception ex){android.util.Log.e("PersianSocks","Cache load failed",ex);throw new IllegalStateException("خواندن نتایج ذخیره‌شده ناموفق بود",ex);}
        }
        return index.snapshot();
    }
    public static void save(Context context,List<ProxyEndpoint> proxies) {
        if(proxies!=index)for(ProxyEndpoint p:proxies)index.accept(p);
        final Context app=context.getApplicationContext();
        if(!saving.compareAndSet(false,true))return;
        IO.execute(()->{
            long written=-1;
            try {
                if(!loaded)load(app);
                ProxyCacheFile file=new ProxyCacheFile(app.getFilesDir());
                do {written=index.revision();file.save(index);writtenRevision=written;} while(written!=index.revision());
            }catch(Exception ex){android.util.Log.e("PersianSocks","Cache save failed",ex);}
            finally{saving.set(false);}
            if(written>=0&&written!=index.revision())save(app,index);
        });
    }
    public static void flush(Context context)throws Exception {
        if(!loaded)load(context);
        long revision=index.revision();if(writtenRevision==revision)return;
        new ProxyCacheFile(context.getFilesDir()).save(index);writtenRevision=revision;
    }
}
