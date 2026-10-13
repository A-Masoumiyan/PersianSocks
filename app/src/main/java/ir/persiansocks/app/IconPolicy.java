package ir.persiansocks.app;
import java.net.*;
import java.util.*;
import java.util.regex.*;
/** Uses icons declared by that site's HTML, never a name guess or third-party favicon directory. */
public final class IconPolicy {
    private IconPolicy(){}
    public static boolean https(String url){try{URI u=new URI(url);String h=u.getHost();return "https".equals(u.getScheme())&&u.getRawUserInfo()==null&&(u.getPort()<0||u.getPort()==443)&&h!=null&&h.contains(".")&&!h.matches("[0-9.]+")&&!h.endsWith(".local")&&!h.endsWith(".localhost");}catch(Exception e){return false;}}
    public static boolean sameSite(String a,String b){try{String x=new URI(a).getHost(),y=new URI(b).getHost();if(x.startsWith("www."))x=x.substring(4);if(y.startsWith("www."))y=y.substring(4);return x.equalsIgnoreCase(y);}catch(Exception e){return false;}}
    private static String attribute(String tag,String key){Matcher m=Pattern.compile("(?i)(?:^|\\s)"+key+"\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))").matcher(tag);if(!m.find())return "";return (m.group(1)!=null?m.group(1):m.group(2)!=null?m.group(2):m.group(3)).replace("&amp;","&").replace("&#38;","&");}
    public static List<String> icons(String html,String page){
        html=html.replaceAll("(?is)<!--.*?-->|<(script|style)\\b[^>]*>.*?</\\1\\s*>","");LinkedHashSet<String> urls=new LinkedHashSet<>();Matcher tags=Pattern.compile("(?is)<link\\b[^>]{1,4096}>").matcher(html);
        while(tags.find()&&urls.size()<12){String tag=tags.group(),rel=attribute(tag,"rel").toLowerCase(Locale.ROOT),type=attribute(tag,"type");List<String> tokens=Arrays.asList(rel.split("\\s+"));if(!tokens.contains("icon")&&!tokens.contains("apple-touch-icon"))continue;if(type.toLowerCase(Locale.ROOT).contains("svg"))continue;
            try{String href=attribute(tag,"href");if(href.isEmpty())continue;String url=new URL(new URL(page),href).toString();if(https(url)&&!url.toLowerCase(Locale.ROOT).split("\\?",2)[0].endsWith(".svg"))urls.add(url);}catch(Exception ignored){}
        }
        try{URI origin=new URI(page);urls.add("https://"+origin.getHost()+"/favicon.ico");}catch(Exception ignored){}return new ArrayList<>(urls);
    }
}
