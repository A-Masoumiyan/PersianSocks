import ir.persiansocks.app.UpdateRelease;
import org.json.*;

public final class UpdateReleaseHarness {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static JSONObject release(String version)throws Exception {
        return new JSONObject().put("tag_name","v"+version).put("body","Release notes").put("assets",new JSONArray().put(new JSONObject()
            .put("name","PersianSocks-"+version+".apk").put("state","uploaded").put("size",4000000)
            .put("digest","sha256:"+"a".repeat(64)).put("browser_download_url",UpdateRelease.REPO+"/releases/download/v"+version+"/PersianSocks-"+version+".apk")));
    }
    static UpdateRelease select(JSONObject r)throws Exception{return UpdateRelease.newest(new JSONArray().put(r).toString(),"0.27.0");}
    public static void main(String[] args)throws Exception {
        check(UpdateRelease.compare("0.10.0","0.9.9")>0,"numeric versions");
        check(UpdateRelease.compare("1.0.0","0.999.999")>0,"major version");
        check(select(release("0.27.0"))==null,"no equal version");check(select(release("0.26.0"))==null,"no downgrade");
        UpdateRelease update=select(release("0.28.0"));check(update!=null&&update.version.equals("0.28.0"),"new stable version");
        check(UpdateRelease.newest(update.cache(),"0.27.0").digest.equals(update.digest),"cached badge survives restart");
        check(UpdateRelease.newest(update.cache(),"0.28.0")==null,"cache cleared after upgrade");
        for(String flag:new String[]{"draft","prerelease"})check(select(release("0.28.0").put(flag,true))==null,flag);
        for(String tag:new String[]{"windows-v0.99.0","v0.99.0-debug-preview","v0.28.0-beta","v999999999.0.0"})check(select(release("0.28.0").put("tag_name",tag))==null,"exclude "+tag);
        JSONObject mixed=new JSONObject().put("tag_name","windows-v0.99.0").put("assets",new JSONArray());
        check(UpdateRelease.newest(new JSONArray().put(mixed).put(release("0.28.0")).put(release("0.30.0")).put(release("0.29.0")).toString(),"0.27.0").version.equals("0.30.0"),"choose Android independently of Latest/order");
        for(String key:new String[]{"name","state","digest","browser_download_url"}){
            JSONObject r=release("0.28.0");r.getJSONArray("assets").getJSONObject(0).put(key,"bad");check(select(r)==null,"reject invalid "+key);
        }
        for(long size:new long[]{0,-1,UpdateRelease.MAX_BYTES+1}){JSONObject r=release("0.28.0");r.getJSONArray("assets").getJSONObject(0).put("size",size);check(select(r)==null,"reject size");}
        for(String url:new String[]{"http://github.com/file","https://github.com.evil.test/file","https://user:pass@github.com/file","https://evil.test/file","https://github.com:8443/file"})check(!UpdateRelease.trustedDownload(url),"reject redirect "+url);
        check(UpdateRelease.trustedDownload("https://release-assets.githubusercontent.com/github-production-release-asset/file?token=example"),"official signed asset redirect");
        check(UpdateRelease.trustedDownload("https://objects.githubusercontent.com/file"),"official objects redirect");
        check(UpdateRelease.newest("[]","0.27.0")==null,"empty repository");
        try{UpdateRelease.compare("0.28.0-evil","0.27.0");throw new AssertionError("unsafe version accepted");}catch(IllegalArgumentException expected){checks++;}
        if(args.length>0){String feed=java.nio.file.Files.readString(java.nio.file.Path.of(args[0]));UpdateRelease actual=UpdateRelease.newest(feed,"0.25.0");check(actual!=null&&actual.version.equals(args[1]),"live repository release selection");}
        System.out.println("PASS: "+checks+" update selection/version/cache/download-origin checks");
    }
}
