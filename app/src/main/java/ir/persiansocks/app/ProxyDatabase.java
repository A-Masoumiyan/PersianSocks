package ir.persiansocks.app;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.*;
import androidx.paging.PagingSource;
import java.util.*;

/** Durable, versioned data. A rejected key remains a timestamped tombstone. */
@Database(entities={ProxyDatabase.ProxyRow.class,ProxyDatabase.FavoriteRow.class,ProxyDatabase.QualityRow.class,ProxyDatabase.CatalogRow.class,ProxyDatabase.ResultRow.class,ProxyDatabase.MetaRow.class},version=1,exportSchema=true)
public abstract class ProxyDatabase extends RoomDatabase {
    private static volatile ProxyDatabase instance;
    public static ProxyDatabase get(Context context){ProxyDatabase d=instance;if(d==null)synchronized(ProxyDatabase.class){if(instance==null)instance=Room.databaseBuilder(context.getApplicationContext(),ProxyDatabase.class,"proxy-data.db").setQueryExecutor(ProxyStore.IO).build();d=instance;}return d;}
    public abstract Data data();
    @Entity(tableName="proxies") public static class ProxyRow {
        @PrimaryKey @NonNull public String key="";public long changed;public String payload;
        public ProxyRow(){} public ProxyRow(ProxyIndex.Record r){key=r.key;changed=r.time;payload=r.proxy==null?null:r.proxy.json().toString();}
    }
    @Entity(tableName="favorites") public static class FavoriteRow {
        @PrimaryKey @NonNull public String key="";@NonNull public String payload="";public int position;
        public FavoriteRow(){} public FavoriteRow(ProxyEndpoint p,int i){key=p.key();payload=p.json().toString();position=i;}
    }
    @Entity(tableName="quality",primaryKeys={"scope","key"}) public static class QualityRow {
        @NonNull public String scope="",key="",payload="";
        public QualityRow(){}public QualityRow(String scope,String key,QualityStats value){this.scope=scope;this.key=key;payload=value.json().toString();}
    }
    @Entity(tableName="catalog",indices={@Index("position")}) public static class CatalogRow {
        @PrimaryKey @NonNull public String key="";public int position;
        public CatalogRow(){}public CatalogRow(String key,int p){this.key=key;position=p;}
    }
    @Entity(tableName="result_pages",primaryKeys={"generation","position"}) public static class ResultRow {
        public long generation;public int position;@NonNull public String key="",payload="";
        public ResultRow(){}public ResultRow(long gen,int i,ProxyEndpoint p){generation=gen;position=i;key=p.key();payload=p.json().toString();}
        public ProxyEndpoint endpoint(){try{return ProxyEndpoint.fromJson(new org.json.JSONObject(payload));}catch(Exception e){throw new IllegalStateException("Invalid stored proxy",e);}}
    }
    @Entity(tableName="meta") public static class MetaRow {
        @PrimaryKey @NonNull public String key="";@NonNull public String value="";
        public MetaRow(){}public MetaRow(String k,String v){key=k;value=v;}
    }
    @Dao public abstract static class Data {
        @Query("SELECT * FROM proxies") public abstract List<ProxyRow> proxies();
        @Query("SELECT * FROM proxies ORDER BY `key` LIMIT :limit OFFSET :offset") public abstract List<ProxyRow> proxyPage(int limit,int offset);
        @Query("DELETE FROM proxies WHERE `key`=:key AND (changed<:time OR (changed=:time AND (:failed OR payload IS NOT NULL)))") abstract void removeOlder(String key,long time,boolean failed);
        @Insert(onConflict=OnConflictStrategy.IGNORE) abstract void insertProxy(ProxyRow row);
        @Transaction public void put(ProxyRow row){removeOlder(row.key,row.changed,row.payload==null);insertProxy(row);}
        @Transaction public void putAll(List<ProxyRow> rows){for(ProxyRow row:rows)put(row);}
        @Query("SELECT value FROM meta WHERE `key`=:key") public abstract String meta(String key);
        @Insert(onConflict=OnConflictStrategy.REPLACE) public abstract void meta(MetaRow row);
        @Query("SELECT * FROM favorites ORDER BY position") public abstract List<FavoriteRow> favorites();
        @Query("DELETE FROM favorites") abstract void clearFavorites();
        @Insert(onConflict=OnConflictStrategy.REPLACE) abstract void insertFavorites(List<FavoriteRow> rows);
        @Transaction public void favorites(List<FavoriteRow> rows){clearFavorites();insertFavorites(rows);}
        @Query("SELECT * FROM quality WHERE scope=:scope") public abstract List<QualityRow> quality(String scope);
        @Query("SELECT * FROM quality WHERE scope=:scope ORDER BY rowid DESC LIMIT :limit") public abstract List<QualityRow> recentQuality(String scope,int limit);
        @Query("DELETE FROM quality WHERE scope=:scope AND rowid NOT IN (SELECT rowid FROM quality WHERE scope=:scope ORDER BY rowid DESC LIMIT :limit)") public abstract void trimQuality(String scope,int limit);
        @Insert(onConflict=OnConflictStrategy.REPLACE) public abstract void quality(QualityRow row);
        @Query("SELECT `key` FROM catalog ORDER BY position") public abstract List<String> catalog();
        @Query("SELECT COUNT(*) FROM catalog") public abstract int catalogCount();
        @Query("DELETE FROM catalog") abstract void clearCatalog();
        @Insert(onConflict=OnConflictStrategy.IGNORE) abstract void insertCatalog(List<CatalogRow> rows);
        @Transaction public void catalog(List<CatalogRow> rows){clearCatalog();insertCatalog(rows);}
        @Transaction public void mergeCatalog(List<String> incoming){Map<String,CatalogRow> unique=new LinkedHashMap<>();for(String key:incoming)unique.putIfAbsent(key,new CatalogRow());for(String key:catalog())unique.putIfAbsent(key,new CatalogRow());List<CatalogRow> rows=new ArrayList<>();int i=0;for(String key:unique.keySet())rows.add(new CatalogRow(key,i++));catalog(rows);}
        @Query("DELETE FROM catalog WHERE `key` IN (:keys)") public abstract void prune(List<String> keys);
        @Transaction public void pruneAll(List<String> keys){for(int i=0;i<keys.size();i+=500)prune(keys.subList(i,Math.min(keys.size(),i+500)));}
        @Insert(onConflict=OnConflictStrategy.REPLACE) public abstract void results(List<ResultRow> rows);
        @Query("DELETE FROM result_pages WHERE generation<:generation AND generation NOT IN (SELECT DISTINCT generation FROM result_pages ORDER BY generation DESC LIMIT 4)") public abstract void pruneResults(long generation);
        @Query("SELECT * FROM result_pages WHERE generation=:generation ORDER BY position") public abstract PagingSource<Integer,ResultRow> pages(long generation);
        @Transaction public void publish(long generation,List<ProxyEndpoint> endpoints){List<ResultRow> page=new ArrayList<>(128);for(int i=0;i<endpoints.size();i++){page.add(new ResultRow(generation,i,endpoints.get(i)));if(page.size()==128){results(page);page.clear();}}if(!page.isEmpty())results(page);pruneResults(generation);}
        @Transaction public void publishRows(long generation,List<ResultRow> rows){results(rows);pruneResults(generation);}
    }
}
