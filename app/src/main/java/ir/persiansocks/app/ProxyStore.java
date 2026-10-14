package ir.persiansocks.app;

import android.content.Context;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Room owns durable results; only changed records are written, off the UI thread. */
public final class ProxyStore {
    public static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"proxy-storage");t.setDaemon(true);return t;});
    private static final ProxyIndex index=new ProxyIndex();
    private static final ConcurrentLinkedQueue<ProxyIndex.Record> pending=new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean saving=new AtomicBoolean();
    private static volatile boolean loaded;
    private static final ScheduledExecutorService commits=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"proxy-room-commits");t.setDaemon(true);return t;});
    private ProxyStore(){}
    public static ProxyIndex index(){return index;}
    public static boolean loaded(){return loaded;}
    public static synchronized List<ProxyEndpoint> load(Context context) {
        if(!loaded)try{
            ProxyDatabase db=ProxyDatabase.get(context);ProxyDatabase.Data dao=db.data();
            if(dao.meta("legacy-v1")==null){
                ProxyIndex old=new ProxyIndex();ProxyCacheFile file=new ProxyCacheFile(context.getFilesDir());
                if(file.exists())file.load(old);else{JSONArray rows=new JSONArray(context.getSharedPreferences("persiansocks",0).getString("good","[]"));for(int i=0;i<rows.length();i++)if(rows.optJSONObject(i)!=null)old.accept(ProxyEndpoint.fromJson(rows.getJSONObject(i)));}
                List<ProxyIndex.Record> rows=old.records();
                List<ProxyDatabase.CatalogRow> catalog=new ArrayList<>();int i=0;for(ProxyEndpoint p:new ScanFiles(context.getFilesDir()).catalog())catalog.add(new ProxyDatabase.CatalogRow(p.key(),i++));
                List<ProxyDatabase.FavoriteRow> favorites=new ArrayList<>();i=0;for(ProxyEndpoint p:FavoriteProxies.load(context).values())favorites.add(new ProxyDatabase.FavoriteRow(p,i++));
                db.runInTransaction(()->{persist(dao,rows);dao.catalog(catalog);dao.favorites(favorites);dao.meta(new ProxyDatabase.MetaRow("legacy-v1","1"));});
                // Old files are retained as recovery evidence. Paused checkpoint and journal are untouched.
            }
            for(int offset=0;;offset+=500){List<ProxyDatabase.ProxyRow> page=dao.proxyPage(500,offset);for(ProxyDatabase.ProxyRow r:page){JSONObject row=new JSONObject().put("key",r.key).put("changed",r.changed);if(r.payload!=null)row.put("proxy",new JSONObject(r.payload));index.restore(row);}if(page.size()<500)break;}
            Context app=context.getApplicationContext();
            index.listener(record->{pending.add(record);});loaded=true;
            commits.scheduleWithFixedDelay(()->{if(!pending.isEmpty())save(app,index);},5,5,TimeUnit.SECONDS);
            ScanFiles.catalogBackend(context.getFilesDir(),new RoomCatalog(dao));
        }catch(Exception ex){android.util.Log.e("PersianSocks","Room load/migration failed",ex);throw new IllegalStateException("خواندن نتایج ذخیره‌شده ناموفق بود",ex);}
        return index.snapshot();
    }
    private static void persist(ProxyDatabase.Data dao,List<ProxyIndex.Record> rows){for(int i=0;i<rows.size();i+=128){List<ProxyDatabase.ProxyRow> batch=new ArrayList<>(128);for(ProxyIndex.Record r:rows.subList(i,Math.min(rows.size(),i+128)))batch.add(new ProxyDatabase.ProxyRow(r));dao.putAll(batch);}}
    public static void save(Context context,List<ProxyEndpoint> proxies) {
        if(proxies!=index)for(ProxyEndpoint p:proxies)index.accept(p);
        Context app=context.getApplicationContext();if(!saving.compareAndSet(false,true))return;
        IO.execute(()->{try{flush(app);}catch(Exception ex){android.util.Log.e("PersianSocks","Room save failed",ex);}finally{saving.set(false);}if(!pending.isEmpty())save(app,index);});
    }
    public static synchronized void flush(Context context)throws Exception {
        if(!loaded)load(context);List<ProxyIndex.Record> changes=new ArrayList<>();ProxyIndex.Record row;
        while((row=pending.poll())!=null)changes.add(row);if(changes.isEmpty())return;
        try{persist(ProxyDatabase.get(context).data(),changes);}catch(RuntimeException e){pending.addAll(changes);throw e;}
    }
    private static final class RoomCatalog implements ScanFiles.CatalogBackend {
        private final ProxyDatabase.Data dao;RoomCatalog(ProxyDatabase.Data d){dao=d;}
        public void save(List<ProxyEndpoint> list){List<ProxyDatabase.CatalogRow> rows=new ArrayList<>();int i=0;for(ProxyEndpoint p:list)rows.add(new ProxyDatabase.CatalogRow(p.key(),i++));dao.catalog(rows);}
        public List<ProxyEndpoint> load(){List<ProxyEndpoint> rows=new ArrayList<>();for(String key:dao.catalog()){ProxyEndpoint p=ProxyEndpoint.parse(key);if(p!=null)rows.add(p);}return rows;}
        public void merge(List<ProxyEndpoint> incoming){List<String> keys=new ArrayList<>();for(ProxyEndpoint p:incoming)keys.add(p.key());dao.mergeCatalog(keys);}
        public int count(){return dao.catalogCount();}public void prune(Set<String> keys){dao.pruneAll(new ArrayList<>(keys));}
    }
}
