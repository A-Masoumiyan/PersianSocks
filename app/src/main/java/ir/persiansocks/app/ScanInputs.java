package ir.persiansocks.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class ScanInputs {
    private ScanInputs() {}
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("persiansocks",Context.MODE_PRIVATE);
    }
    public static void initializePolicy(Context context){
        if(!prefs(context).getBoolean("ui-028-migrated",false))prefs(context).edit().putInt("scan-types",7).putBoolean("ui-028-migrated",true).apply();
        SharedPreferences p=prefs(context);
        // Mask 4 was persisted by the old fixed-TLS build; ordinary UI choices are 1, 6 or 7.
        if(!DistributionPolicy.play()&&p.getInt("scan-types",7)==4)p.edit().putInt("scan-types",7).apply();
        DistributionPolicy.setUserTlsOnly(p.getBoolean("tls-only",false));
        configureTests(context);ProxyResults.instagramFirst(true);
    }
    public static void configureTests(Context context){SharedPreferences p=prefs(context);ScanProbePolicy.configure(p.getBoolean("scan-ping-only",false),p.getString("ping-target-url",""));}
    public static String speedUrl(Context context){return prefs(context).getString("speed-target-url","");}
    public static String filter(Context context) {
        String key=prefs(context).getString("filter","ALL");
        if(key.startsWith("https://")) {
            for(ServiceTargets.Target t:ServiceTargets.current())if(t.id.equals(key))return key;
            return "ALL";
        }
        try { return ProxyResults.Filter.valueOf(key).name(); }
        catch(Exception ignored){return "ALL";}
    }
    public static ProxyResults.Sort sort(Context context) {
        try{return ProxyResults.Sort.valueOf(prefs(context).getString("sort","FASTEST"));}
        catch(Exception ignored){return ProxyResults.Sort.FASTEST;}
    }
    public static ProxyResults.Protocol protocol(Context context){
        if(DistributionPolicy.encryptedOnly())return ProxyResults.Protocol.HTTP;
        try{return ProxyResults.Protocol.valueOf(prefs(context).getString("protocol-filter","ALL"));}
        catch(Exception ignored){return ProxyResults.Protocol.ALL;}
    }
    public static ProxyResults.PingLimit pingLimit(Context context) {
        return ProxyResults.PingLimit.fromMilliseconds(prefs(context).getInt("ping-limit-ms", 0));
    }
    public static ProxyScanner.Options options(Context context) {
        return new ProxyScanner.Options(prefs(context).getInt("max-tests",5000),
            prefs(context).getBoolean("slow-scan",false),prefs(context).getBoolean("scan-all",false),prefs(context).getInt("scan-types",7));
    }
    public static List<ProxyScanner.Source> sources(Context context) {
        List<ProxyScanner.Source> out=new ArrayList<>(Arrays.asList(SourceCatalog.DEFAULT));
        out.addAll(Arrays.asList(SourceCatalog.HTTP));
        int added=0;
        for(String line:prefs(context).getString("extra-sources","").split("\n")) {
            ProxyScanner.Source source=SourceCatalog.custom(line,added+1);
            if(source!=null&&added<20){out.add(source);added++;}
        }
        out.removeIf(s->!DistributionPolicy.allows(s.type));
        return out;
    }
    public static List<ProxyEndpoint> bootstrap(Context context) {
        List<ProxyEndpoint> out=new ArrayList<>();
        for(ProxyEndpoint.Type type:ProxyEndpoint.Type.values()){
            if(!DistributionPolicy.allows(type))continue;
            String name=type==ProxyEndpoint.Type.SOCKS5?"socks5":type.name().toLowerCase(java.util.Locale.US);
            try(InputStream in=context.getAssets().open("bootstrap-"+name+".txt");ByteArrayOutputStream buffer=new ByteArrayOutputStream()){
                byte[] block=new byte[4096];int n;while((n=in.read(block))!=-1)buffer.write(block,0,n);
                out.addAll(ProxyScanner.parseList(new String(buffer.toByteArray(),StandardCharsets.UTF_8),true,ProxyScanner.MAX_TESTS,type));
            }catch(Exception ignored){}
        }
        return out;
    }
}
