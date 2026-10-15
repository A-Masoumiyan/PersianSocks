package ir.persiansocks.app;

import android.app.Application;
import android.os.Build;
import java.io.*;

/** Small local crash report; never sent or uploaded automatically. */
public final class PersianSocksApp extends Application implements androidx.work.Configuration.Provider {
    @androidx.annotation.NonNull @Override public androidx.work.Configuration getWorkManagerConfiguration(){return new androidx.work.Configuration.Builder().build();}
    @Override public void onCreate(){
        super.onCreate();PreparedScanner.resources(new DeviceScanResources(this));AppLanguage.initialize(this);AppHttp.initialize(this);QualityHistory.initialize(this);SourceQuality.initialize(this);ProxyResults.quality(QualityHistory::score);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->ProxyStore.IO.execute(()->{try{ProxyMaintenance.schedule(this);}catch(Exception ex){android.util.Log.e("PersianSocks","Maintenance scheduling deferred",ex);}}),1000);
        Thread.UncaughtExceptionHandler original=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            try(PrintWriter out=new PrintWriter(new File(getFilesDir(),"last-crash.txt"),"UTF-8")){
                out.println("PersianSocks " + AppLanguage.version(this) + " / Android "+Build.VERSION.RELEASE+" / SDK "+Build.VERSION.SDK_INT);
                out.println("Thread: "+thread.getName());Throwable current=error;
                for(int cause=0;current!=null&&cause<4;cause++,current=current.getCause()){
                    out.println(current.getClass().getName()+": "+current.getMessage());
                    StackTraceElement[] frames=current.getStackTrace();
                    for(int i=0;i<Math.min(frames.length,48);i++)out.println("  at "+frames[i]);
                }
            }catch(Throwable ignored){}
            if(original!=null)original.uncaughtException(thread,error);
        });
    }
}
