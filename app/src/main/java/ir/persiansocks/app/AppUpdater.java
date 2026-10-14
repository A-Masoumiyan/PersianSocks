package ir.persiansocks.app;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.*;
import android.provider.Settings;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Checks in foreground; download/install only by explicit user action. No silent installs. */
public final class AppUpdater {
    private final Activity activity;
    private final Button button;
    private final TextView versionNote;
    private boolean started;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean closed,cancelled;
    private volatile HttpURLConnection connection;
    private boolean busy,returningFromSettings;
    private UpdateRelease release;
    private File ready;
    private AlertDialog progressDialog;
    public AppUpdater(Activity a,Button b,TextView note){versionNote=note;activity=a;button=b;try{release=UpdateRelease.newest(a.getSharedPreferences("persiansocks",0).getString("update-release","[]"),AppLanguage.version(a));}catch(Exception ignored){}if(DistributionPolicy.play())release=null;label();button.setOnClickListener(v->{if(release!=null)offer();else check(true);});}
    private String t(int id){return AppLanguage.t(id);}
    private void post(Runnable r){activity.runOnUiThread(()->{if(!closed&&!activity.isDestroyed())r.run();});}
    private void label(){versionNote.setText(release==null?"":t(R.string.ui_v28_update)+" "+AppLanguage.isolate("v"+release.version));if(button instanceof UiIconButton)((UiIconButton)button).active(release!=null);button.setContentDescription(t(release==null?R.string.ui_update_000:R.string.ui_update_001)+(release==null?"":" v"+release.version));button.setText(t(release==null?R.string.ui_update_000:R.string.ui_update_001));button.setTextColor(release==null?android.graphics.Color.WHITE:android.graphics.Color.rgb(70,226,184));button.setEnabled(!busy);}
    public void start(){
        if(returningFromSettings){returningFromSettings=false;if(activity.getPackageManager().canRequestPackageInstalls())install();}
        if(DistributionPolicy.play())return;
        check(false);
    }
    public void close(){closed=true;cancelled=true;if(connection!=null)connection.disconnect();worker.shutdownNow();if(progressDialog!=null)progressDialog.dismiss();}
    private HttpURLConnection open(String address,Network network,boolean redirects,long deadline)throws Exception {
        for(int count=0;count<6;count++){
            if(cancelled||Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new IOException("Cancelled or expired");
            if(!address.startsWith("https://")||redirects&&!UpdateRelease.trustedDownload(address))throw new IOException("Untrusted download");
            HttpURLConnection c=AppHttp.open(new URL(address),network);connection=c;
            c.setConnectTimeout(10000);c.setReadTimeout(10000);c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent","PersianSocks-Updater");c.setRequestProperty("Accept",redirects?"application/octet-stream":"application/vnd.github+json");
            int status=c.getResponseCode();
            if(status>=300&&status<=399&&redirects){String location=c.getHeaderField("Location");c.disconnect();if(location==null)throw new IOException("Missing redirect");address=new URL(new URL(address),location).toString();continue;}
            if(status!=200){c.disconnect();throw new IOException("HTTP "+status);}return c;
        }throw new IOException("Redirect limit");
    }
    private Network network()throws IOException {Network n=activity.getSystemService(ConnectivityManager.class).getActiveNetwork();if(n==null)throw new IOException("Offline");return n;}
    private void check(boolean manual){
        if(busy)return;
        if(DistributionPolicy.play()){activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://play.google.com/store/apps/details?id="+activity.getPackageName())));return;}
        busy=true;cancelled=false;button.setText(t(R.string.ui_update_002));button.setEnabled(false);
        worker.execute(()->{
            UpdateRelease found=null;boolean success=false;
            try{
                HttpURLConnection c=open("https://api.github.com/repos/A-Masoumiyan/PersianSocks/releases?per_page=100",network(),false,System.nanoTime()+30_000_000_000L);
                try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buf=new byte[8192];int n;long deadline=System.nanoTime()+30_000_000_000L;
                    while((n=in.read(buf))!=-1){if(cancelled||System.nanoTime()>deadline||out.size()+n>2*1024*1024)throw new IOException("Metadata limit");out.write(buf,0,n);}
                    found=UpdateRelease.newest(out.toString("UTF-8"),AppLanguage.version(activity));success=true;
                }finally{c.disconnect();}
            }catch(Exception ignored){}finally{connection=null;}
            final UpdateRelease result=found;final boolean ok=success;
            post(()->{busy=false;if(ok){release=result;try{activity.getSharedPreferences("persiansocks",0).edit().putLong("update-checked",System.currentTimeMillis()).putString("update-release",result==null?"[]":result.cache()).apply();}catch(Exception ignored){}}label();if(manual){if(ok&&release!=null)offer();else Toast.makeText(activity,t(ok?R.string.ui_update_003:R.string.ui_update_004),Toast.LENGTH_LONG).show();}});
        });
    }
    private void offer(){
        new AlertDialog.Builder(activity,R.style.AppDialogTheme).setTitle(t(R.string.ui_update_001)+" "+AppLanguage.isolate(release.version))
            .setMessage(t(R.string.ui_update_005)+"\n\n"+release.notes)
            .setNeutralButton(t(R.string.ui_update_017),(d,w)->check(true)).setPositiveButton(t(R.string.ui_update_006),(d,w)->download()).setNegativeButton(t(R.string.ui_update_007),null).show();
    }
    private void download(){
        if(busy||release==null||DistributionPolicy.play())return;busy=true;cancelled=false;label();final UpdateRelease selected=release;
        ProgressBar progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);
        AlertDialog dialog=new AlertDialog.Builder(activity,R.style.AppDialogTheme).setTitle(t(R.string.ui_update_008)).setView(progress).setNegativeButton(t(R.string.ui_update_009),(d,w)->cancelDownload()).setOnCancelListener(d->cancelDownload()).create();progressDialog=dialog;dialog.show();
        worker.execute(()->{
            File part=null,verified=null;
            try{
                File dir=new File(activity.getCacheDir(),"updates");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Storage");
                File[] old=dir.listFiles();if(old!=null)for(File f:old)if(System.currentTimeMillis()-f.lastModified()>86400000L)f.delete();
                verified=new File(dir,"update-"+UUID.randomUUID()+".apk");part=new File(dir,verified.getName()+".part");
                long deadline=System.nanoTime()+120_000_000_000L;HttpURLConnection c=open(selected.url,network(),true,deadline);
                MessageDigest hash=MessageDigest.getInstance("SHA-256");long total=0;int last=-1;
                try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(part)){
                    byte[] buf=new byte[32768];int n;
                    while((n=in.read(buf))!=-1){if(cancelled||System.nanoTime()>deadline||total+n>selected.bytes)throw new IOException("Download limit");out.write(buf,0,n);hash.update(buf,0,n);total+=n;int pct=(int)(total*100/selected.bytes);if(pct!=last){last=pct;post(()->progress.setProgress(pct));}}
                }finally{c.disconnect();}
                if(cancelled||total!=selected.bytes||!hex(hash.digest()).equals(selected.digest))throw new IOException("Integrity");
                PackageManager pm=activity.getPackageManager();PackageInfo candidate=pm.getPackageArchiveInfo(part.getPath(),PackageManager.GET_SIGNING_CERTIFICATES),current=pm.getPackageInfo(activity.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
                if(candidate==null||!current.packageName.equals(candidate.packageName)||!selected.version.equals(candidate.versionName)||candidate.getLongVersionCode()<=current.getLongVersionCode()||!signers(candidate).equals(signers(current)))throw new IOException("APK identity");
                if(!part.renameTo(verified))throw new IOException("Storage");part=null;final File complete=verified;
                post(()->{busy=false;label();dialog.dismiss();if(cancelled){complete.delete();return;}ready=complete;new AlertDialog.Builder(activity,R.style.AppDialogTheme).setTitle(t(R.string.ui_update_010)).setMessage(t(R.string.ui_update_011)).setPositiveButton(t(R.string.ui_update_012),(d,w)->install()).setNegativeButton(t(R.string.ui_update_007),null).show();});
            }catch(Exception ignored){if(verified!=null)verified.delete();post(()->{busy=false;label();dialog.dismiss();if(!cancelled)Toast.makeText(activity,t(R.string.ui_update_013),Toast.LENGTH_LONG).show();});}
            finally{if(part!=null)part.delete();connection=null;}
        });
    }
    private void cancelDownload(){cancelled=true;if(connection!=null)connection.disconnect();}
    private static String hex(byte[] value){StringBuilder s=new StringBuilder();for(byte b:value)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
    private static Set<String> signers(PackageInfo info)throws IOException {
        if(info.signingInfo==null)throw new IOException("No signer");Set<String> result=new HashSet<>();for(Signature s:info.signingInfo.getApkContentsSigners())result.add(hex(s.toByteArray()));if(result.isEmpty())throw new IOException("No signer");return result;
    }
    private void install(){
        if(ready==null||!ready.isFile()||DistributionPolicy.play())return;
        try{
            if(!activity.getPackageManager().canRequestPackageInstalls()){
                new AlertDialog.Builder(activity,R.style.AppDialogTheme).setTitle(t(R.string.ui_update_014)).setMessage(t(R.string.ui_update_015))
                    .setPositiveButton(t(R.string.ui_update_016),(d,w)->{try{returningFromSettings=true;activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())));}catch(Exception e){returningFromSettings=false;Toast.makeText(activity,t(R.string.ui_update_013),Toast.LENGTH_LONG).show();}}).setNegativeButton(t(R.string.ui_update_007),null).show();return;
            }
            Uri uri=Uri.parse("content://"+activity.getPackageName()+".updates/"+ready.getName());Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri("APK update",uri));activity.startActivity(intent);
        }catch(Exception e){Toast.makeText(activity,t(R.string.ui_update_013),Toast.LENGTH_LONG).show();}
    }
}
