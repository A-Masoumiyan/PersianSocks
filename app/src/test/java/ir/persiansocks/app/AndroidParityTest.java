package ir.persiansocks.app;
import android.app.Application;
import android.content.Context;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=29,application=Application.class)
public class AndroidParityTest {
 @Test public void previewIsBoundedAndDoesNotPopulateAuthoritativeHealth(){
  Context c=RuntimeEnvironment.getApplication();c.getSharedPreferences("persiansocks",0).edit().clear().commit();
  org.robolectric.util.ReflectionHelpers.setStaticField(QuickStartCache.class,"savedAt",0L);
  List<ProxyEndpoint> rows=new ArrayList<>();for(int i=1;i<=20;i++)rows.add(new ProxyEndpoint("8.8.8.8",1000+i,20,false,1,1,System.currentTimeMillis()));
  QuickStartCache.save(c,rows);assertEquals(12,QuickStartCache.load(c).size());
  c.getSharedPreferences("persiansocks",0).edit().putString("startup-preview","x".repeat(48001)).commit();assertTrue(QuickStartCache.load(c).isEmpty());
 }
 @Test public void killSwitchIsOnByDefaultAndChatgptModeDoesNotRestrictToDomains(){
  Context c=RuntimeEnvironment.getApplication();c.getSharedPreferences("persiansocks",0).edit().clear().commit();assertTrue(AndroidRoutes.kill(c));
  c.getSharedPreferences("persiansocks",0).edit().putBoolean("kill-switch-off",true).putString("routing-mode","chatgpt").commit();assertFalse(AndroidRoutes.kill(c));assertFalse(AndroidRoutes.load(c).restricted);assertFalse(AndroidRoutes.scopedVerification(c));
  ServiceTargets.Target[] fallback={ServiceTargets.ALL[1]};assertSame(fallback,AndroidRoutes.verification(c,fallback));
 }
 @Test public void chatgptCanBeDisabledAfterTheAppIsRemovedAndRestoresPreviousScope(){
  Context c=RuntimeEnvironment.getApplication();c.getSharedPreferences("persiansocks",0).edit().clear().putString("routing-mode","domains").putString("route-domains","youtube.com").commit();
  AndroidRoutes.chatgpt(c,true);AndroidRoutes.chatgpt(c,true);assertEquals("chatgpt",AndroidRoutes.mode(c));
  AndroidRoutes.chatgpt(c,false);assertEquals("domains",AndroidRoutes.mode(c));assertTrue(AndroidRoutes.load(c).proxy("youtube.com"));
  c.getSharedPreferences("persiansocks",0).edit().putString("routing-mode","chatgpt").remove("routing-before-chatgpt").commit();
  AndroidRoutes.chatgpt(c,false);assertEquals("apps",AndroidRoutes.mode(c));
 }
 @Test public void domainProfileVerifiesAnIncludedDestinationAndRejectsEmptyRules(){
  Context c=RuntimeEnvironment.getApplication();c.getSharedPreferences("persiansocks",0).edit().clear().putString("routing-mode","domains").putString("route-domains","chatgpt.com").commit();
  ServiceTargets.Target[] targets=AndroidRoutes.verification(c,ServiceTargets.ALL);assertEquals(1,targets.length);assertEquals("chatgpt.com",targets[0].host);
  c.getSharedPreferences("persiansocks",0).edit().putString("route-domains","").commit();try{AndroidRoutes.load(c);fail("Empty domain scope silently became full tunnel");}catch(IllegalArgumentException expected){}
 }
}
