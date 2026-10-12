package ir.persiansocks.app;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Toast;

/** A user-launched, transparent entry point handles foreground-start rules and VPN consent. */
public final class ControlActivity extends Activity {
    private static final int REQUEST_VPN=291;
    private String command;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        command=getIntent().getAction();
        if(!QuickControls.TOGGLE.equals(command)&&!QuickControls.AUTO.equals(command)){finishQuietly();return;}
        waiting=saved!=null&&saved.getBoolean("waiting",false);
        if(waiting)return;
        runCommand();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("waiting",waiting);super.onSaveInstanceState(out);
    }
    private boolean waiting;
    private void runCommand() {
        if(QuickControlState.busy(ProxyVpnService.phase(),ProxyVpnService.autoScanning())){execute();return;}
        VpnDisclosure.ensure(this,this::prepareVpn,this::finishQuietly);
    }
    private void prepareVpn(){
        Intent consent=VpnService.prepare(this);
        if(consent!=null){waiting=true;startActivityForResult(consent,REQUEST_VPN);}
        else execute();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=REQUEST_VPN)return;
        waiting=false;
        if(result==RESULT_OK)execute();
        else {Toast.makeText(this,"مجوز VPN صادر نشد",Toast.LENGTH_SHORT).show();finishQuietly();}
    }
    private void execute() {
        try{QuickControls.execute(this,command);}
        catch(Exception error){Toast.makeText(this,"امکان اجرای فرمان نیست: "+error.getMessage(),Toast.LENGTH_LONG).show();}
        finishQuietly();
    }
    private void finishQuietly(){finish();overridePendingTransition(0,0);}
}
