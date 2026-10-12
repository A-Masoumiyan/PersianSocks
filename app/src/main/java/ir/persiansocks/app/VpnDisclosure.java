package ir.persiansocks.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.widget.ScrollView;
import android.widget.TextView;

/** An affirmative app disclosure precedes Android VPN consent on every connection entry point. */
public final class VpnDisclosure {
    private VpnDisclosure() {}
    public static boolean accepted(Context context){return !DistributionPolicy.play()||
        context.getSharedPreferences("persiansocks",Context.MODE_PRIVATE).getInt("vpn-disclosure",0)==DistributionPolicy.DISCLOSURE_VERSION;}
    public static void ensure(Activity activity,Runnable accept,Runnable decline){
        if(accepted(activity)){accept.run();return;}
        TextView text=new TextView(activity);text.setText(DistributionPolicy.DISCLOSURE);text.setTextSize(15);
        int pad=(int)(20*activity.getResources().getDisplayMetrics().density);text.setPadding(pad,pad,pad,pad);
        text.setTextDirection(android.view.View.TEXT_DIRECTION_RTL);
        ScrollView scroll=new ScrollView(activity);scroll.addView(text);
        new AlertDialog.Builder(activity).setTitle("اجازهٔ عبور ترافیک از VPN").setView(scroll)
            .setPositiveButton("می‌پذیرم",(d,w)->{
                activity.getSharedPreferences("persiansocks",Context.MODE_PRIVATE).edit().putInt("vpn-disclosure",DistributionPolicy.DISCLOSURE_VERSION).apply();
                if(!activity.isFinishing()&&!activity.isDestroyed())accept.run();
            }).setNegativeButton("نمی‌پذیرم",(d,w)->decline.run()).setOnCancelListener(d->decline.run()).show();
    }
    public static void showPrivacy(Activity activity){
        TextView text=new TextView(activity);text.setText(DistributionPolicy.DISCLOSURE);text.setTextSize(15);
        int pad=(int)(20*activity.getResources().getDisplayMetrics().density);text.setPadding(pad,pad,pad,pad);text.setTextIsSelectable(true);
        ScrollView scroll=new ScrollView(activity);scroll.addView(text);
        AlertDialog.Builder dialog=new AlertDialog.Builder(activity).setTitle("حریم خصوصی و شیوهٔ اتصال").setView(scroll).setPositiveButton("بستن",null);
        if(!BuildConfig.PRIVACY_POLICY_URL.isEmpty())dialog.setNeutralButton("سیاست آنلاین",(d,w)->{
            try{activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(BuildConfig.PRIVACY_POLICY_URL)));}
            catch(Exception ex){android.widget.Toast.makeText(activity,"امکان بازکردن نشانی نیست",android.widget.Toast.LENGTH_LONG).show();}
        });
        dialog.show();
    }
}
