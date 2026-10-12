package ir.persiansocks.app;

import android.app.Application;
import android.os.Build;
import java.io.*;

/** Small local crash report; never sent or uploaded automatically. */
public final class PersianSocksApp extends Application {
    @Override public void onCreate(){
        super.onCreate();Thread.UncaughtExceptionHandler original=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            try(PrintWriter out=new PrintWriter(new File(getFilesDir(),"last-crash.txt"),"UTF-8")){
                out.println("PersianSocks 0.18.0 / Android "+Build.VERSION.RELEASE+" / SDK "+Build.VERSION.SDK_INT);
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
