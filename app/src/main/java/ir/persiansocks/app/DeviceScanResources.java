package ir.persiansocks.app;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

/** Read off the UI thread, at most once per two seconds. No permissions or identifiers. */
public final class DeviceScanResources implements ScanCapacityGovernor.Resources {
    private final ActivityManager memory;
    private final PowerManager power;
    private final ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();
    private long sampled=-10000,headroomAt=-10000;
    private int cap=72;
    private float headroom=Float.NaN;
    public DeviceScanResources(Context context){memory=context.getSystemService(ActivityManager.class);power=context.getSystemService(PowerManager.class);}
    public synchronized int cap(boolean deep){
        long now=SystemClock.elapsedRealtime();
        if(now-sampled>=2000){
            sampled=now;int cores=Runtime.getRuntime().availableProcessors();cap=Math.min(216,Math.max(24,cores*24));
            if(memory!=null){
                memory.getMemoryInfo(info);
                if(memory.isLowRamDevice()||memory.getMemoryClass()<=128)cap=Math.min(cap,48);
                else if(memory.getMemoryClass()<=256)cap=Math.min(cap,120);
                if(info.lowMemory||info.availMem<Math.max(info.threshold,128L*1024*1024))cap=Math.min(cap,8);
            }
            Runtime runtime=Runtime.getRuntime();long available=runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory());
            if(available<24L*1024*1024)cap=Math.min(cap,8);
            else if(available<48L*1024*1024)cap=Math.min(cap,32);
            if(power!=null)try{
                int thermal=power.getCurrentThermalStatus();
                if(Build.VERSION.SDK_INT>=30&&now-headroomAt>=10000){headroomAt=now;headroom=power.getThermalHeadroom(0);}
                if(Float.isFinite(headroom)){if(headroom>=1)thermal=Math.max(thermal,PowerManager.THERMAL_STATUS_SEVERE);else if(headroom>=.95f)thermal=Math.max(thermal,PowerManager.THERMAL_STATUS_MODERATE);else if(headroom>=.85f)thermal=Math.max(thermal,PowerManager.THERMAL_STATUS_LIGHT);}
                if(thermal>=PowerManager.THERMAL_STATUS_CRITICAL)cap=Math.min(cap,4);
                else if(thermal>=PowerManager.THERMAL_STATUS_SEVERE)cap=Math.min(cap,12);
                else if(thermal>=PowerManager.THERMAL_STATUS_MODERATE)cap=Math.min(cap,48);
                else if(thermal>=PowerManager.THERMAL_STATUS_LIGHT)cap=Math.min(cap,96);
                if(power.isPowerSaveMode())cap=Math.min(cap,48);
            }catch(RuntimeException ignored){/* Some vendor thermal services are unavailable. */}
        }
        return deep?Math.max(1,Math.min(24,cap/4)):cap;
    }
}
