package ir.persiansocks.app;

import java.net.URI;
import org.json.*;

/** Only stable Android assets from our repository, independently of GitHub's Latest label. */
public final class UpdateRelease {
    public static final String REPO="https://github.com/A-Masoumiyan/PersianSocks";
    public static final long MAX_BYTES=512L*1024*1024;
    /** Streaming downloads get a size-dependent budget, bounded at one hour. */
    public static long downloadBudgetMillis(long bytes){
        if(bytes<=0||bytes>MAX_BYTES)throw new IllegalArgumentException("Invalid APK size");
        return Math.min(3_600_000L,120_000L+((bytes+65535L)/65536L)*1000L);
    }
    public final String version,url,digest,notes;
    public final long bytes;
    private UpdateRelease(String v,String u,String d,String n,long b){version=v;url=u;digest=d;notes=n;bytes=b;}
    public static int compare(String a,String b){
        if(!a.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")||!b.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}"))throw new IllegalArgumentException("Invalid version");
        String[] x=a.split("\\."),y=b.split("\\.");
        for(int i=0;i<3;i++){int c=Integer.compare(Integer.parseInt(x[i]),Integer.parseInt(y[i]));if(c!=0)return c;}return 0;
    }
    public static UpdateRelease newest(String json,String installed)throws JSONException {
        return inspect(json,installed).release;
    }
    /** A newer stable release with an unusable asset must never mean "up to date". */
    public static final class Check {
        public final UpdateRelease release;
        public final String unavailableVersion;
        private Check(UpdateRelease selected,String latest){
            release=selected;
            unavailableVersion=latest!=null&&(selected==null||compare(latest,selected.version)>0)?latest:null;
        }
    }
    public static Check inspect(String json,String installed)throws JSONException {
        JSONArray releases=new JSONArray(json);UpdateRelease best=null;String latest=null;
        for(int i=0;i<releases.length();i++){
            JSONObject r=releases.getJSONObject(i);String tag=r.optString("tag_name");
            if(r.optBoolean("draft")||r.optBoolean("prerelease")||!tag.matches("v[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}"))continue;
            String v=tag.substring(1);if(compare(v,installed)<=0)continue;
            JSONArray releaseAssets=r.optJSONArray("assets");
            // Historical combined Android/Windows releases use a calendar tag, not an APK version.
            if(tag.matches("v(?:19|20)[0-9]{2}\\.(?:[1-9]|1[0-2])\\.(?:[1-9]|[12][0-9]|3[01])")){
                boolean matching=false;if(releaseAssets!=null)for(int j=0;j<releaseAssets.length();j++)if(("PersianSocks-"+v+".apk").equals(releaseAssets.getJSONObject(j).optString("name"))){matching=true;break;}
                if(!matching)continue;
            }
            if(latest==null||compare(v,latest)>0)latest=v;
            if(best!=null&&compare(v,best.version)<=0)continue;
            JSONArray assets=releaseAssets;if(assets==null)continue;
            for(int j=0;j<assets.length();j++){
                JSONObject a=assets.getJSONObject(j);String name="PersianSocks-"+v+".apk",u=a.optString("browser_download_url"),d=a.optString("digest");long size=a.optLong("size");
                if(!name.equals(a.optString("name"))||!"uploaded".equals(a.optString("state"))||size<=0||size>MAX_BYTES||!d.matches("sha256:[0-9a-fA-F]{64}"))continue;
                if(!u.equals(REPO+"/releases/download/"+tag+"/"+name))continue;
                String notes=r.optString("body");if(notes.length()>6000)notes=notes.substring(0,6000);
                best=new UpdateRelease(v,u,d.substring(7).toLowerCase(java.util.Locale.ROOT),notes,size);break;
            }
        }
        return new Check(best,latest);
    }
    public String cache()throws JSONException {
        JSONObject a=new JSONObject().put("name","PersianSocks-"+version+".apk").put("state","uploaded").put("size",bytes).put("digest","sha256:"+digest).put("browser_download_url",url);
        return new JSONArray().put(new JSONObject().put("tag_name","v"+version).put("body",notes).put("assets",new JSONArray().put(a))).toString();
    }
    public static boolean trustedDownload(String url){
        try{URI u=new URI(url);String h=u.getHost();return "https".equals(u.getScheme())&&u.getRawUserInfo()==null&&(u.getPort()==-1||u.getPort()==443)&&
            ("github.com".equals(h)||"release-assets.githubusercontent.com".equals(h)||"objects.githubusercontent.com".equals(h));}catch(Exception e){return false;}
    }
}
