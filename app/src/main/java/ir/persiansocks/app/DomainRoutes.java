package ir.persiansocks.app;
import java.util.*;
/** Exact suffix boundaries, no arbitrary regex or executable rules. */
public final class DomainRoutes {
 public final Set<String> domains;
 public final boolean restricted;
 public DomainRoutes(Collection<String> input){LinkedHashSet<String> clean=new LinkedHashSet<>();for(String row:input)for(String d:row.split("[\\s,]+")){d=d.toLowerCase(Locale.ROOT).replaceFirst("^\\*\\.","");if(d.isEmpty())continue;if(!d.matches("[a-z0-9](?:[a-z0-9.-]{0,250}[a-z0-9])?")||!d.contains(".")||d.contains("..")||d.matches("[0-9.]+"))throw new IllegalArgumentException("دامنهٔ معتبر بدون نشانی صفحه وارد کن");clean.add(d);if(clean.size()>200)throw new IllegalArgumentException("حداکثر ۲۰۰ دامنه");}domains=Collections.unmodifiableSet(clean);restricted=!clean.isEmpty();}
 public boolean proxy(String host){if(!restricted)return true;String h=host.toLowerCase(Locale.ROOT).replaceFirst("\\.$","");for(String d:domains)if(h.equals(d)||h.endsWith("."+d))return true;return false;}
 public boolean unknownAddress(String host){return restricted&&(host.indexOf(':')>=0||host.matches("[0-9.]+"));}
 public static List<String> preset(String name){
  switch(name){
   case "telegram":return Arrays.asList("telegram.org","t.me","telegram.me","telegra.ph","telesco.pe","tdesktop.com","cdn-telegram.org");
   case "youtube":return Arrays.asList("youtube.com","youtu.be","googlevideo.com","ytimg.com","youtube-nocookie.com","youtubei.googleapis.com","youtube.googleapis.com");
   case "openai":return Arrays.asList("chatgpt.com","openai.com","oaistatic.com","oaiusercontent.com","oaistatsig.com","cdn.openaimerge.com","cdn.workos.com","forwarder.workos.com","frontend-apps-multi-region.workos.com","images.workoscdn.com","setup.workos.com","workos.imgix.net","challenges.cloudflare.com","intercom.io","intercomcdn.com","js.stripe.com","o207216.ingest.sentry.io","o33249.ingest.sentry.io","rum.browser-intake-datadoghq.com");
   default:return Collections.emptyList();
  }
 }
}
