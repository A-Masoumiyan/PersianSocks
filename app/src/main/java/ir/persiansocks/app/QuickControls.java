package ir.persiansocks.app;

import android.app.*;
import android.appwidget.AppWidgetManager;
import android.content.*;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.SystemClock;
import android.service.quicksettings.TileService;
import android.widget.Toast;
import java.util.*;

public final class QuickControls {
    public static final String TOGGLE="ir.persiansocks.app.QUICK_TOGGLE";
    public static final String AUTO="ir.persiansocks.app.QUICK_SCAN";
    private static long lastRefresh;
    private QuickControls() {}
    public static void ensureCatalog(Context context) {
        ScanInputs.initializePolicy(context);
        ServiceTargets.configure(ServiceTargets.decode(context.getSharedPreferences("persiansocks",Context.MODE_PRIVATE)
            .getString("test-domains","[]")));
    }
    public static PendingIntent command(Context context,String action,int request) {
        Intent intent=new Intent(context,ControlActivity.class).setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        return activityIntent(context,intent,request);
    }
    public static PendingIntent openApp(Context context) {
        return activityIntent(context,new Intent(context,MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP),413);
    }
    private static PendingIntent activityIntent(Context context,Intent intent,int request) {
        int flags=PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE;
        if(Build.VERSION.SDK_INT>=35) {
            ActivityOptions options=ActivityOptions.makeBasic();
            options.setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            return PendingIntent.getActivity(context,request,intent,flags,options.toBundle());
        }
        return PendingIntent.getActivity(context,request,intent,flags);
    }
    public static void execute(Activity activity,String action) {
        boolean busy=QuickControlState.busy(ProxyVpnService.phase(),ProxyVpnService.autoScanning());
        if(busy) {
            activity.startService(new Intent(activity,ProxyVpnService.class).setAction(ProxyVpnService.DISCONNECT));
            return;
        }
        if(!VpnDisclosure.accepted(activity))throw new IllegalStateException(AppLanguage.t(R.string.ui_extra_000));
        ensureCatalog(activity);

        Intent intent=new Intent(activity,ProxyVpnService.class);
        String filter=ScanInputs.filter(activity);
        ProxyResults.Sort sort=ScanInputs.sort(activity);
        List<ProxyEndpoint> compatible=ProxyResults.visible(ProxyStore.index().snapshot(),filter,sort,ScanInputs.protocol(activity),ScanInputs.pingLimit(activity));
        if(!action.equals(AUTO)&&!ProxyStore.loaded())intent.setAction(ProxyVpnService.CONNECT_CACHED);
        else if(action.equals(AUTO)||compatible.isEmpty()){MainActivity.pauseManualScanForQuickAction();intent.setAction(ProxyVpnService.SCAN_CONNECT);}
        else intent.setAction(ProxyVpnService.CONNECT).putExtra(ProxyVpnService.EXTRA_ENDPOINT,compatible.get(0).key())
            .putExtra("filter",filter).putExtra("sort",sort.name());
        activity.startForegroundService(intent);
    }
    public static void refresh(Context context,boolean force) {
        long now=SystemClock.elapsedRealtime();
        if(!force&&now-lastRefresh<1200)return;
        lastRefresh=now;
        try{VpnWidgetProvider.updateAll(context);}catch(Exception error){android.util.Log.w("PersianSocks","Widget update",error);}
        try{TileService.requestListeningState(context,new ComponentName(context,VpnTileService.class));}
        catch(Exception error){android.util.Log.w("PersianSocks","Tile update",error);}
    }
    public static void requestTile(Activity activity) {
        if(Build.VERSION.SDK_INT>=33) {
            try {
                StatusBarManager manager=activity.getSystemService(StatusBarManager.class);
                manager.requestAddTileService(new ComponentName(activity,VpnTileService.class),"PersianSocks",
                    Icon.createWithResource(activity,R.drawable.ic_qs_vpn),activity.getMainExecutor(),result->{
                        String message=result==StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED?AppLanguage.t(R.string.ui_extra_001):
                            result==StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED?AppLanguage.t(R.string.ui_extra_002):
                            AppLanguage.t(R.string.ui_extra_003);
                        Toast.makeText(activity,message,Toast.LENGTH_LONG).show();
                    });
                return;
            }catch(Exception error){android.util.Log.w("PersianSocks","Add tile",error);}
        }
        new AlertDialog.Builder(activity, R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_extra_004))
            .setMessage(AppLanguage.t(R.string.ui_extra_005))
            .setPositiveButton(AppLanguage.t(R.string.ui_extra_006),null).show();
    }
    public static void requestWidget(Activity activity) {
        AppWidgetManager manager=activity.getSystemService(AppWidgetManager.class);
        try{
            if(manager.isRequestPinAppWidgetSupported()&&manager.requestPinAppWidget(
                new ComponentName(activity,VpnWidgetProvider.class),null,null))return;
        }catch(Exception error){android.util.Log.w("PersianSocks","Pin widget",error);}
        new AlertDialog.Builder(activity, R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_extra_007))
            .setMessage(AppLanguage.t(R.string.ui_extra_008))
            .setPositiveButton(AppLanguage.t(R.string.ui_extra_006),null).show();
    }
}
