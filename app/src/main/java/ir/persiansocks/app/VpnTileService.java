package ir.persiansocks.app;

import android.service.quicksettings.*;
import android.content.*;
import android.os.Build;
import android.graphics.drawable.Icon;

public final class VpnTileService extends TileService {
    private boolean listening;
    private final BroadcastReceiver changes=new BroadcastReceiver(){
        public void onReceive(Context context,Intent intent){updateTile();}
    };
    @Override public void onStartListening(){
        super.onStartListening();
        if(!listening){
            IntentFilter filter=new IntentFilter(ProxyVpnService.STATE);
            // Signature permission protects API 29-32; NOT_EXPORTED additionally applies on 33+.
            registerReceiver(changes,filter,ProxyVpnService.INTERNAL_PERMISSION,null,Context.RECEIVER_NOT_EXPORTED);
            listening=true;
        }
        updateTile();
    }
    @Override public void onStopListening(){
        if(listening){unregisterReceiver(changes);listening=false;}
        super.onStopListening();
    }
    @Override public void onDestroy(){
        if(listening){unregisterReceiver(changes);listening=false;}
        super.onDestroy();
    }
    @Override public void onTileAdded(){updateTile();}
    private void updateTile(){
        Tile tile=getQsTile();if(tile==null)return;
        tile.setLabel("PersianSocks");
        tile.setIcon(Icon.createWithResource(this,R.drawable.ic_qs_vpn));
        int phase=ProxyVpnService.phase();boolean scanning=ProxyVpnService.autoScanning();
        tile.setState(phase==ProxyVpnService.CONNECTED?Tile.STATE_ACTIVE:Tile.STATE_INACTIVE);
        tile.setSubtitle(QuickControlState.subtitle(phase,scanning));
        tile.setContentDescription("PersianSocks، "+QuickControlState.subtitle(phase,scanning)+
            "، "+QuickControlState.toggleLabel(phase,scanning));
        tile.updateTile();
    }
    @Override public void onClick(){
        super.onClick();
        if(isLocked())unlockAndRun(this::openCommand);else openCommand();
    }
    private void openCommand(){
        if(Build.VERSION.SDK_INT>=34)startActivityAndCollapse(QuickControls.command(this,QuickControls.TOGGLE,411));
        else openLegacyCommand();
    }
    // The PendingIntent overload only exists on API 34+. Older OS versions still need Intent.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private void openLegacyCommand(){
        startActivityAndCollapse(new Intent(this,ControlActivity.class).setAction(QuickControls.TOGGLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
    }
}
