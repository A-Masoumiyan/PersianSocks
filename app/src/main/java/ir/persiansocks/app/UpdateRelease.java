package ir.persiansocks.app;

import java.net.URI;
import org.json.*;

/** Only stable Android assets from our repository, independently of GitHub's Latest label. */
public final class UpdateRelease {
    public static final String REPO="https://github.com/A-Masoumiyan/PersianSocks";
    public static final long MAX_BYTES=32L*1024*1024;
    public final String version,url,digest,notes;
    public final long bytes;
    private UpdateRelease(String v,String u,String d,String n,long b){version=v;url=u;digest=d;notes=n;bytes=b;}
    public static int compare(String a,String b){
        if(!a.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")||!b.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}"))throw new IllegalArgumentException("Invalid version");
        String[] x=a.split("\\."),y=b.split("\\.");
        for(int i=0;i<3;i++){int c=Integer.compare(Integer.parseInt(x[i]),Integer.parseInt(y[i]));if(c!=0)return c;}return 0;
    }
    public static UpdateRelease newest(String json,String installed)throws JSONException {
        JSONArray releases=new JSONArray(json);UpdateRelease best=null;
        for(int i=0;i<releases.length();i++){
            JSONObject r=releases.getJSONObject(i);String tag=r.optString("tag_name");
            if(r.optBoolean("draft")||r.optBoolean("prerelease")||!tag.matches("v[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}"))continue;
            String v=tag.substring(1);if(compare(v,installed)<=0||best!=null&&compare(v,best.version)<=0)continue;
            JSONArray assets=r.optJSONArray("assets");if(assets==null)continue;
            for(int j=0;j<assets.length();j++){
                JSONObject a=assets.getJSONObject(j);String name="PersianSocks-"+v+".apk",u=a.optString("browser_download_url"),d=a.optString("digest");long size=a.optLong("size");
                if(!name.equals(a.optString("name"))||!"uploaded".equals(a.optString("state"))||size<=0||size>MAX_BYTES||!d.matches("sha256:[0-9a-fA-F]{64}"))continue;
                if(!u.equals(REPO+"/releases/download/"+tag+"/"+name))continue;
                String notes=r.optString("body");if(notes.length()>6000)notes=notes.substring(0,6000);
                best=new UpdateRelease(v,u,d.substring(7).toLowerCase(java.util.Locale.ROOT),notes,size);break;
            }
        }
        return best;
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
