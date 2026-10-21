package ir.persiansocks.app;

import android.app.Application;
import android.content.Context;
import android.widget.CheckBox;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29,application=Application.class)
public class ChatgptControlTest {
 @Before public void initializeLanguage(){AppLanguage.initialize(RuntimeEnvironment.getApplication());}
 @Test public void persistedChatgptModeHasVisibleStateAndCanBeTurnedOffWithoutInstalledApp(){
  Context c=RuntimeEnvironment.getApplication();
  c.getSharedPreferences("persiansocks",0).edit().clear().putString("routing-mode","chatgpt").commit();
  var owner=Robolectric.buildActivity(MainActivity.class).create();
  try{
   MainActivity activity=owner.get();CheckBox toggle=ReflectionHelpers.getField(activity,"chatOnly");
   assertTrue(toggle.isChecked());assertEquals(AppLanguage.t(R.string.ui_031_chatgpt_on),toggle.getContentDescription().toString());
   toggle.performClick();assertEquals("apps",AndroidRoutes.mode(activity));assertFalse(toggle.isChecked());
   assertEquals(AppLanguage.t(R.string.ui_031_chatgpt_off),toggle.getContentDescription().toString());
  }finally{owner.destroy();}
 }
 @Test public void missingChatgptAppLeavesToggleOffAndShowsReadablePersistentError(){
  Context c=RuntimeEnvironment.getApplication();c.getSharedPreferences("persiansocks",0).edit().clear().commit();
  var owner=Robolectric.buildActivity(MainActivity.class).create();
  try{
   MainActivity activity=owner.get();CheckBox toggle=ReflectionHelpers.getField(activity,"chatOnly");toggle.performClick();
   assertFalse(toggle.isChecked());assertEquals("apps",AndroidRoutes.mode(activity));
   assertEquals(AppLanguage.t(R.string.ui_030_install_chatgpt),c.getSharedPreferences("persiansocks",0).getString("connection-error",""));
   android.app.AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);assertTrue(dialog.isShowing());
   assertNotNull(dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL));
  }finally{owner.destroy();}
 }
}
