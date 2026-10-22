package ir.persiansocks.app;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.RecyclerView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import static org.junit.Assert.*;

/** Real Android views with deterministic sample data; no network or device benchmark. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=29,application=Application.class,qualifiers="mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class InterfaceLayoutTest {
    private Context context(){Context c=RuntimeEnvironment.getApplication();AppLanguage.initialize(c);return c;}
    private static void layout(View view,int width,int height){
        forceTree(view);view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,height==0?View.MeasureSpec.UNSPECIFIED:View.MeasureSpec.EXACTLY));
        view.layout(0,0,width,view.getMeasuredHeight());
    }
    private static void forceTree(View view){view.forceLayout();view.invalidate();if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)forceTree(group.getChildAt(i));}}
    @Test public void badgesWrapWithinNarrowAndWideCardsInBothDirections(){
        Context c=context();
        for(String language:new String[]{"fa","en"})for(int width:new int[]{200,264,304,388}){
            AppLanguage.select(c,language);ServiceFlowLayout flow=new ServiceFlowLayout(c);
            for(int i=0;i<16;i++)flow.addView(new View(c),new ViewGroup.LayoutParams(44,36));
            layout(flow,width,0);int lastTop=-1;
            for(int i=0;i<flow.getChildCount();i++){
                View badge=flow.getChildAt(i);assertTrue(badge.getLeft()>=0);assertTrue(badge.getRight()<=width);
                assertTrue(badge.getBottom()<=flow.getMeasuredHeight());assertTrue(badge.getTop()>=lastTop);lastTop=badge.getTop();
                for(int j=0;j<i;j++){View other=flow.getChildAt(j);assertFalse(android.graphics.Rect.intersects(new android.graphics.Rect(badge.getLeft(),badge.getTop(),badge.getRight(),badge.getBottom()),new android.graphics.Rect(other.getLeft(),other.getTop(),other.getRight(),other.getBottom())));}
            }
            assertTrue(flow.getChildAt(15).getTop()>0);
            assertEquals(language.equals("fa")?width-44:0,flow.getChildAt(0).getLeft());
        }
    }
    @Test public void powerActionIsLocalizedAndReportsAButton(){
        Context c=context();AppLanguage.select(c,"en");PowerToggle button=new PowerToggle(c);
        button.showState(false,false);assertEquals("Connect VPN",button.getContentDescription());
        button.showState(true,false);assertEquals("Disconnect VPN",button.getContentDescription());assertTrue(button.isSelected());
        button.showState(false,true);assertEquals("Cancel VPN connection",button.getContentDescription());
        assertEquals("android.widget.Button",button.getAccessibilityClassName());
    }
    private static ProxyEndpoint sample(int offset){boolean gate=offset!=1;return new ProxyEndpoint("203.0.113."+(10+offset),gate?443:1080,offset==1?95:offset==0?180:210,false,gate?0:7,gate?0:7,System.currentTimeMillis(),Collections.emptySet(),Collections.emptySet(),offset==0?"JP":offset==1?"DE":"KR",gate?ProxyEndpoint.Type.OPENVPN:ProxyEndpoint.Type.SOCKS5);}
    private Object card(MainActivity activity,int offset)throws Exception{
        Object card=ReflectionHelpers.callConstructor(Class.forName("ir.persiansocks.app.MainActivity$CardHolder"),ReflectionHelpers.ClassParameter.from(MainActivity.class,activity));
        ReflectionHelpers.callInstanceMethod(card,"bind",ReflectionHelpers.ClassParameter.from(ProxyEndpoint.class,sample(offset)));return card;
    }
    @Test public void cardsKeepActionsAndServicesInsideSmallScreens()throws Exception{
        Context c=context();
        for(String language:new String[]{"fa","en"}){
            AppLanguage.select(c,language);var owner=Robolectric.buildActivity(MainActivity.class).create();
            try{
                Object card=card(owner.get(),0);View wrapper=ReflectionHelpers.getField(card,"wrapper");
                for(int width:new int[]{320,360,412}){
                    layout(wrapper,width,0);View row=ReflectionHelpers.getField(card,"row");ViewGroup services=ReflectionHelpers.getField(card,"services");
                    for(int i=0;i<services.getChildCount();i++){View item=services.getChildAt(i);assertTrue(item.getLeft()>=0);assertTrue(item.getRight()<=services.getWidth());}
                    UiIconButton favorite=ReflectionHelpers.getField(card,"favoriteButton");ViewGroup actions=(ViewGroup)favorite.getParent();
                    assertTrue(favorite.getWidth()>=32);assertTrue(favorite.getHeight()>=32);
                    assertTrue(favorite.getRight()<=actions.getWidth());
                    TextView country=ReflectionHelpers.getField(card,"designName");assertTrue(country.getWidth()>0);
                    TextView ping=ReflectionHelpers.getField(card,"ping"),down=ReflectionHelpers.getField(card,"down");
                    assertFalse(ping.getText().toString().isEmpty());assertTrue(ping.getHeight()>0);assertTrue(ping.getBottom()<=((View)ping.getParent()).getHeight());
                }
            }finally{owner.destroy();}
        }
    }
    @Test public void measuredDownloadIsVisibleWithoutChangingCompactCardHeight()throws Exception{
        Context c=context();AppLanguage.select(c,"fa");var owner=Robolectric.buildActivity(MainActivity.class).create();try{
            MainActivity a=owner.get();for(int type:new int[]{0,1}){Object card=card(a,type);View wrapper=ReflectionHelpers.getField(card,"wrapper");layout(wrapper,320,0);int height=wrapper.getMeasuredHeight();
                java.util.Map<String,ProxyTestResult> tests=ReflectionHelpers.getField(a,"testResults");ProxyTestResult measured=new ProxyTestResult();measured.downloadMBps=2.75;measured.speedTarget=ScanInputs.speedUrl(c);tests.put(sample(type).key(),measured);
                ReflectionHelpers.callInstanceMethod(card,"bind",ReflectionHelpers.ClassParameter.from(ProxyEndpoint.class,sample(type)));layout(wrapper,320,0);TextView down=ReflectionHelpers.getField(card,"down");assertEquals(View.VISIBLE,down.getVisibility());assertNotNull(down.getParent());assertTrue(down.getHeight()>0);assertTrue(down.getText().toString().contains("2.75"));assertTrue(down.getWidth()>0);assertEquals(height,wrapper.getMeasuredHeight());
            }
            TextView live=ReflectionHelpers.getField(a,"heroTraffic"),mode=ReflectionHelpers.getField(a,"designModeStatus");assertNotNull(live.getParent());assertNotNull(mode.getParent());assertNotNull(((View)ReflectionHelpers.getField(a,"healthyRetest")).getParent());
            c.getSharedPreferences("persiansocks",0).edit().putBoolean("proxy-only",true).putBoolean("kill-switch-off",false).commit();ReflectionHelpers.callInstanceMethod(a,"refreshDesignOne");assertFalse(AndroidRoutes.kill(c));assertTrue(mode.getText().toString().contains("پروکسی"));
            assertFalse(((View)ReflectionHelpers.getField(a,"sidebarKill")).isEnabled());
        }finally{c.getSharedPreferences("persiansocks",0).edit().remove("proxy-only").commit();owner.destroy();}
    }
    @Test public void unchangedCardsKeepTheirViewsWhenAnotherResultOrSelectionChanges()throws Exception{
        Context c=context();var owner=Robolectric.buildActivity(MainActivity.class).create();
        try{
            MainActivity activity=owner.get();Object first=card(activity,0),second=card(activity,1);
            ProxyEndpoint a=ReflectionHelpers.getField(first,"endpoint"),b=ReflectionHelpers.getField(second,"endpoint");
            View rowA=ReflectionHelpers.getField(first,"row"),rowB=ReflectionHelpers.getField(second,"row");
            Object originalA=rowA.getBackground(),originalB=rowB.getBackground();
            ReflectionHelpers.callInstanceMethod(first,"bind",ReflectionHelpers.ClassParameter.from(ProxyEndpoint.class,ProxyEndpoint.fromJson(a.json())));
            assertSame("Equivalent endpoint instances do not redraw",originalA,rowA.getBackground());
            ReflectionHelpers.setField(activity,"selected",b);
            ReflectionHelpers.callInstanceMethod(first,"bind",ReflectionHelpers.ClassParameter.from(ProxyEndpoint.class,a));
            ReflectionHelpers.callInstanceMethod(second,"bind",ReflectionHelpers.ClassParameter.from(ProxyEndpoint.class,b));
            assertSame("Unselected row remains untouched",originalA,rowA.getBackground());assertNotSame(originalB,rowB.getBackground());
            Object selectedBackground=rowB.getBackground();
            java.util.Map<String,ProxyTestResult> measurements=ReflectionHelpers.getField(activity,"testResults");ProxyTestResult measurement=new ProxyTestResult();measurement.downloadMBps=2;measurements.put(a.key(),measurement);
            ReflectionHelpers.callInstanceMethod(first,"bind",ReflectionHelpers.ClassParameter.from(ProxyEndpoint.class,a));
            ReflectionHelpers.callInstanceMethod(second,"bind",ReflectionHelpers.ClassParameter.from(ProxyEndpoint.class,b));
            assertNotSame(originalA,rowA.getBackground());assertSame("Unchanged card survives another result",selectedBackground,rowB.getBackground());
            Object updatedA=rowA.getBackground();measurement.downloadMBps=3;
            ReflectionHelpers.callInstanceMethod(first,"bind",ReflectionHelpers.ClassParameter.from(ProxyEndpoint.class,a));assertNotSame("Mutable measurement changes are detected",updatedA,rowA.getBackground());
        }finally{owner.destroy();}
    }
    @Test public void renderActualViewsForReview()throws Exception{
        String destination=System.getProperty("persiansocks.previewDir","");if(destination.isEmpty())return;
        Context c=context();
        for(String language:new String[]{"fa","en"})for(int width:new int[]{320,412}){
            AppLanguage.select(c,language);var owner=Robolectric.buildActivity(MainActivity.class).create();
            try{
                MainActivity activity=owner.get();ReflectionHelpers.setField(activity,"localDataLoaded",true);ReflectionHelpers.setField(activity,"selected",sample(0));
                ReflectionHelpers.setField(activity,"catalogCount",5000);ReflectionHelpers.callInstanceMethod(activity,"updateActions");
                ((TextView)ReflectionHelpers.getField(activity,"counter")).setText("24");
                ((TextView)ReflectionHelpers.getField(activity,"activeProfile")).setText("IP • 198.51.100.24");
                ((View)ReflectionHelpers.getField(activity,"emptyState")).setVisibility(View.GONE);
                RecyclerView list=ReflectionHelpers.getField(activity,"results");ConcatAdapter original=(ConcatAdapter)list.getAdapter();
                View header=ReflectionHelpers.getField(original.getAdapters().get(0),"header");
                java.util.Map<String,ProxyTestResult> preview=ReflectionHelpers.getField(activity,"testResults");for(int i=0;i<3;i++){ProxyTestResult measured=new ProxyTestResult();measured.downloadMBps=i==0?2.75:i==1?4.2:1.35;measured.speedTarget=ScanInputs.speedUrl(c);preview.put(sample(i).key(),measured);}
                ((TextView)ReflectionHelpers.getField(activity,"heroTraffic")).setText("↓ 1.25 MB/s   ↑ 86 KB/s");ReflectionHelpers.callInstanceMethod(activity,"refreshDesignOne");
                Object first=card(activity,0);View[] rows={header,ReflectionHelpers.getField(first,"wrapper"),ReflectionHelpers.getField(card(activity,1),"wrapper"),ReflectionHelpers.getField(card(activity,2),"wrapper")};
                list.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>(){
                    public int getItemCount(){return rows.length;}public int getItemViewType(int position){return position;}
                    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent,int type){View row=rows[type];if(row.getParent()!=null)((ViewGroup)row.getParent()).removeView(row);row.setLayoutParams(new RecyclerView.LayoutParams(-1,-2));return new RecyclerView.ViewHolder(row){};}
                    public void onBindViewHolder(RecyclerView.ViewHolder holder,int position){}
                });
                View page=ReflectionHelpers.getField(activity,"page");layout(page,width,900);
                export(page,destination,"home-"+language+"-"+width+".png");
                ReflectionHelpers.callInstanceMethod(activity,"setSidebarVisible",ReflectionHelpers.ClassParameter.from(boolean.class,true));layout(page,width,900);
                // The drawer width is recalculated by its parent layout callback.
                layout(page,width,900);assertTrue(((View)ReflectionHelpers.getField(activity,"sidebar")).getWidth()<=Math.round(width*.86f));export(page,destination,"settings-"+language+"-"+width+".png");
            }finally{owner.destroy();}
        }
    }
    private static void export(View view,String destination,String name)throws Exception{
        File directory=new File(destination);directory.mkdirs();Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
        view.getViewTreeObserver().dispatchOnPreDraw();view.draw(new Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(new File(directory,name))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}bitmap.recycle();
    }
}
