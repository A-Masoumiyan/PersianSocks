package ir.persiansocks.app;

import android.app.PendingIntent;
import android.appwidget.*;
import android.content.*;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

public final class VpnWidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context context,AppWidgetManager manager,int[] ids){update(context,manager,ids);}
    @Override public void onEnabled(Context context){updateAll(context);}
    @Override public void onAppWidgetOptionsChanged(Context context,AppWidgetManager manager,int id,Bundle options){
        update(context,manager,new int[]{id});
    }
    public static void updateAll(Context context){
        AppWidgetManager manager=AppWidgetManager.getInstance(context);
        int[] ids=manager.getAppWidgetIds(new ComponentName(context,VpnWidgetProvider.class));
        if(ids.length>0)update(context,manager,ids);
    }
    private static void update(Context context,AppWidgetManager manager,int[] ids){
        QuickControls.ensureCatalog(context);
        int phase=ProxyVpnService.phase();boolean scanning=ProxyVpnService.autoScanning();
        RemoteViews views=new RemoteViews(context.getPackageName(),R.layout.vpn_widget);
        views.setTextViewText(R.id.widget_state,QuickControlState.subtitle(phase,scanning));
        views.setTextColor(R.id.widget_state,phase==ProxyVpnService.CONNECTED?Color.rgb(70,226,184):
            phase==ProxyVpnService.CONNECTING?Color.rgb(245,199,111):Color.rgb(151,169,189));
        String filter=ScanInputs.filter(context);
        String message=phase==ProxyVpnService.DISCONNECTED?"فیلتر: "+ProxyResults.label(filter)+" • انتخاب خودکار":
            scanning&&ProxyVpnService.autoProgress()<0?"در حال دریافت منابع • فیلتر: "+ProxyResults.label(filter):
            context.getSharedPreferences("persiansocks",Context.MODE_PRIVATE).getString("state","در حال آماده‌سازی…");
        views.setTextViewText(R.id.widget_detail,message);
        views.setTextViewText(R.id.widget_auto,QuickControlState.autoLabel(phase,scanning));
        views.setContentDescription(R.id.widget_auto,QuickControlState.autoLabel(phase,scanning));
        views.setInt(R.id.widget_auto,"setBackgroundResource",QuickControlState.busy(phase,scanning)?
            R.drawable.widget_stop:R.drawable.widget_action);
        views.setTextColor(R.id.widget_auto,QuickControlState.busy(phase,scanning)?Color.WHITE:Color.rgb(11,17,29));
        views.setViewVisibility(R.id.widget_progress,scanning?View.VISIBLE:View.GONE);
        int progress=ProxyVpnService.autoProgress();
        views.setProgressBar(R.id.widget_progress,100,Math.max(0,progress),progress<0);
        views.setOnClickPendingIntent(R.id.widget_auto,QuickControls.command(context,QuickControls.AUTO,412));
        PendingIntent open=QuickControls.openApp(context);
        views.setOnClickPendingIntent(R.id.widget_open,open);
        views.setOnClickPendingIntent(R.id.widget_title,open);
        for(int id:ids)manager.updateAppWidget(id,views);
    }
}
