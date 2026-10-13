package ir.persiansocks.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.widget.ScrollView;
import android.widget.TextView;

/** An affirmative app disclosure precedes Android VPN consent on every connection entry point. */
public final class VpnDisclosure {
    private VpnDisclosure() {}
    private static String notice(){return AppLanguage.t(DistributionPolicy.play()?R.string.ui_security_play:R.string.ui_security_personal);}
    public static boolean accepted(Context context){return !DistributionPolicy.play()||
        context.getSharedPreferences("persiansocks",Context.MODE_PRIVATE).getInt("vpn-disclosure",0)==DistributionPolicy.DISCLOSURE_VERSION;}
    public static void ensure(Activity activity,Runnable accept,Runnable decline){
        if(accepted(activity)){accept.run();return;}
        TextView text=new TextView(activity);text.setText(notice());text.setTextSize(15);
        int pad=(int)(20*activity.getResources().getDisplayMetrics().density);text.setPadding(pad,pad,pad,pad);
        text.setTextDirection(AppLanguage.textDirection());
        ScrollView scroll=new ScrollView(activity);scroll.addView(text);
        new AlertDialog.Builder(activity, R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_273)).setView(scroll)
            .setPositiveButton(AppLanguage.t(R.string.ui_274),(d,w)->{
                activity.getSharedPreferences("persiansocks",Context.MODE_PRIVATE).edit().putInt("vpn-disclosure",DistributionPolicy.DISCLOSURE_VERSION).apply();
                if(!activity.isFinishing()&&!activity.isDestroyed())accept.run();
            }).setNegativeButton(AppLanguage.t(R.string.ui_275),(d,w)->decline.run()).setOnCancelListener(d->decline.run()).show();
    }
    public static void showPrivacy(Activity activity){
        TextView text=new TextView(activity);text.setText(notice());text.setTextSize(15);
        int pad=(int)(20*activity.getResources().getDisplayMetrics().density);text.setPadding(pad,pad,pad,pad);text.setTextIsSelectable(true);
        ScrollView scroll=new ScrollView(activity);scroll.addView(text);
        AlertDialog.Builder dialog=new AlertDialog.Builder(activity, R.style.AppDialogTheme).setTitle(AppLanguage.t(R.string.ui_276)).setView(scroll).setPositiveButton(AppLanguage.t(R.string.ui_007),null);
        if(!BuildConfig.PRIVACY_POLICY_URL.isEmpty())dialog.setNeutralButton(AppLanguage.t(R.string.ui_277),(d,w)->{
            try{activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(BuildConfig.PRIVACY_POLICY_URL)));}
            catch(Exception ex){android.widget.Toast.makeText(activity,AppLanguage.t(R.string.ui_278),android.widget.Toast.LENGTH_LONG).show();}
        });
        dialog.show();
    }
}
