package ir.persiansocks.app;

import android.app.Application;
import androidx.room.Room;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29,application=Application.class)
public class SourceQualityTest {
    private ProxyDatabase db;
    private final ProxyScanner.Source a=new ProxyScanner.Source("A","https://example.org/a",null),b=new ProxyScanner.Source("B","https://example.org/b",null);
    private ProxyEndpoint endpoint(int port){return ProxyEndpoint.parse("8.8.8.8:"+port);}
    @Before public void setup(){db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),ProxyDatabase.class).allowMainThreadQueries().build();}
    @After public void close(){db.close();}
    @Test public void tracksOverlapAndContentFreshnessWithoutCountingReorderingAsNew(){
        SourceQuality.recordFetch(db,a,Arrays.asList(endpoint(80),endpoint(81),endpoint(80)),1000);
        SourceQuality.recordFetch(db,b,Arrays.asList(endpoint(81),endpoint(82)),1000);
        assertEquals(2,db.data().source(SourceQuality.id(a)).candidates);
        assertEquals(1,db.data().sourceOverlap(SourceQuality.id(a),0));assertEquals(1,db.data().sourceOverlap(SourceQuality.id(b),0));
        SourceQuality.recordFetch(db,a,Arrays.asList(endpoint(81),endpoint(80)),2000);
        assertEquals(1000,db.data().source(SourceQuality.id(a)).changedAt);assertEquals(2000,db.data().source(SourceQuality.id(a)).fetchedAt);
        SourceQuality.recordFetch(db,a,Collections.emptyList(),3000);
        assertTrue(db.data().source(SourceQuality.id(a)).failed);assertEquals(2000,db.data().source(SourceQuality.id(a)).fetchedAt);
        assertEquals(3,db.data().source(SourceQuality.id(a)).attempts);assertEquals(2,db.data().source(SourceQuality.id(a)).fetches);
        assertEquals(0,db.data().sourceOverlap(SourceQuality.id(a),1500));
    }
    @Test public void yieldUsesDistinctLatestTestsCurrentNetworkProfileAndFetch(){
        SourceQuality.recordFetch(db,a,Arrays.asList(endpoint(80),endpoint(81)),1000);
        ProxyDatabase.SourceProbeRow result=new ProxyDatabase.SourceProbeRow();result.key=endpoint(80).key();result.scope="wifi";result.profile="health:one";result.checkedAt=1100;result.success=true;
        db.data().sourceProbe(result);db.data().sourceProbe(result);
        String id=SourceQuality.id(a);
        assertEquals(1,db.data().sourceTested(id,"wifi","health:one",0));assertEquals(1,db.data().sourcePassed(id,"wifi","health:one",0));
        assertEquals(0,db.data().sourceTested(id,"cellular","health:one",0));assertEquals(0,db.data().sourceTested(id,"wifi","health:two",0));
        result.checkedAt=1200;result.success=false;db.data().sourceProbe(result);
        assertEquals(1,db.data().sourceTested(id,"wifi","health:one",0));assertEquals(0,db.data().sourcePassed(id,"wifi","health:one",0));
        SourceQuality.recordFetch(db,a,Arrays.asList(endpoint(80),endpoint(81)),2000);
        assertEquals(0,db.data().sourceTested(id,"wifi","health:one",0));
    }
    @Test public void streamingOverlapNeverCountsAnAddressTwice()throws Exception{
        StringBuilder body=new StringBuilder();for(int i=1;i<=1200;i++)body.append("8.8.8.8:").append(i).append('\n');body.append("8.8.8.8:80\n8.8.8.8:80\n127.0.0.1:90\n");
        for(int chunk:new int[]{1,17,32,63,8192}){
            java.io.StringReader source=new java.io.StringReader(body.toString());java.io.Reader reader=new java.io.FilterReader(source){public int read(char[] b,int off,int len)throws java.io.IOException{return super.read(b,off,Math.min(len,chunk));}};
            List<ProxyEndpoint> rows=ProxyScanner.parseStream(reader,true,100000,new java.util.concurrent.atomic.AtomicBoolean());
            assertEquals("Chunk boundary "+chunk,1200,rows.size());assertEquals("Only actual repeats count",2,ProxyScanner.duplicateCount(rows));
        }
        List<ProxyEndpoint> rows=ProxyScanner.parseList(body.toString(),true);
        assertEquals(2,ProxyScanner.duplicateCount(rows));SourceQuality.recordFetch(db,a,rows,1000);assertEquals(2,db.data().source(SourceQuality.id(a)).duplicates);
    }
    @Test public void expiredProvenanceIsRemovedButFavoriteDataSurvives(){
        db.data().favorites(Collections.singletonList(new ProxyDatabase.FavoriteRow(endpoint(80),0)));
        SourceQuality.recordFetch(db,a,Collections.singletonList(endpoint(80)),1000);
        SourceQuality.recordFetch(db,b,Collections.singletonList(endpoint(82)),8L*24*3600*1000);
        assertEquals(0,db.data().sourceOwners(endpoint(80).key(),0));assertEquals(1,db.data().favorites().size());
    }
    @Test public void versionOneMigratesWithoutLosingFavoritesOrProxyRecords()throws Exception{
        Application app=RuntimeEnvironment.getApplication();String name="source-migration-test.db";app.deleteDatabase(name);
        android.database.sqlite.SQLiteDatabase old=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(name),null);
        org.json.JSONObject schema=new org.json.JSONObject(new String(getClass().getResourceAsStream("/database-v1.json").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getJSONObject("database");
        org.json.JSONArray entities=schema.getJSONArray("entities");
        for(int i=0;i<entities.length();i++){
            org.json.JSONObject entity=entities.getJSONObject(i);String table=entity.getString("tableName");old.execSQL(entity.getString("createSql").replace("${TABLE_NAME}",table));
            org.json.JSONArray indices=entity.optJSONArray("indices");for(int j=0;indices!=null&&j<indices.length();j++)old.execSQL(indices.getJSONObject(j).getString("createSql").replace("${TABLE_NAME}",table));
        }
        old.execSQL("INSERT INTO favorites (`key`,payload,position) VALUES ('8.8.8.8:80','saved',0)");old.execSQL("INSERT INTO proxies (`key`,changed,payload) VALUES ('8.8.8.8:80',123,'kept')");old.setVersion(1);old.close();
        ProxyDatabase upgraded=Room.databaseBuilder(app,ProxyDatabase.class,name).allowMainThreadQueries().addMigrations(ProxyDatabase.SOURCE_MIGRATION).build();
        try{assertEquals("saved",upgraded.data().favorites().get(0).payload);assertEquals("kept",upgraded.data().proxies().get(0).payload);assertNull(upgraded.data().source("missing"));}finally{upgraded.close();app.deleteDatabase(name);}
    }
}
