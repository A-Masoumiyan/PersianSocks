package ir.persiansocks.app;

import java.io.IOException;
import java.util.*;
import javax.net.ssl.*;

/** Optional TLS-only mode defaults off; the separate Play profile remains strict. */
public final class DistributionPolicy {
    private DistributionPolicy() {}
    private static volatile boolean userTlsOnly;
    public static void setUserTlsOnly(boolean enabled){userTlsOnly=enabled;}
    public static boolean encryptedOnly(){return play()||userTlsOnly;}
    public static boolean play(){return BuildConfig.PLAY_DISTRIBUTION;}
    public static boolean allows(ProxyEndpoint proxy){return proxy!=null&&allows(proxy.type);}
    public static boolean allows(ProxyEndpoint.Type type){return !encryptedOnly()||type==ProxyEndpoint.Type.HTTPS;}
    public static int scanTypes(int requested){return encryptedOnly()?ProxyEndpoint.Type.HTTPS.bit:(requested&7)==0?7:requested&7;}
    public static void require(ProxyEndpoint proxy)throws IOException {
        if(!allows(proxy))throw new IOException("این نسخه فقط اتصال TLS به خود پروکسی را می‌پذیرد");
    }
    public static void configureTls(SSLSocket tls)throws SSLException {
        List<String> protocols=new ArrayList<>();
        for(String protocol:tls.getSupportedProtocols())if(protocol.equals("TLSv1.3")||protocol.equals("TLSv1.2"))protocols.add(protocol);
        if(protocols.isEmpty())throw new SSLException("TLS 1.2 or later is required");
        tls.setEnabledProtocols(protocols.toArray(new String[0]));
        SSLParameters params=tls.getSSLParameters();params.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(params);
    }
    public static final int DISCLOSURE_VERSION=2;
    public static final String DISCLOSURE="PersianSocks برای عبور ترافیک برنامه‌های گوشی از سرور پروکسی، از مجوز VPN استفاده می‌کند. هنگام اتصال، ترافیک شبکه از تونل این برنامه عبور می‌کند؛ می‌توانی هر زمان آن را قطع کنی.\n\n"+
        (play()?"در نسخهٔ Play مسیر گوشی تا پروکسی با TLS 1.2 یا 1.3 رمزنگاری می‌شود. ":"SOCKS5 و HTTP مسیر گوشی تا پروکسی را رمزنگاری نمی‌کنند؛ HTTPS یا حالت فقط TLS این مسیر را رمزنگاری می‌کند. ")+"اطلاعات اتصال، از جمله نشانی مقصد و IP عمومی گوشی، به سرور منتخب می‌رسد. اپراتور پروکسی می‌تواند مقصدها و دادهٔ سرویس‌های فاقد HTTPS را ببیند. منابع عمومی و سرورهایشان متعلق به اشخاص ثالث‌اند.\n\n"+
        "برنامه برای خود، فهرست نتیجه‌ها، تنظیمات، شمارندهٔ مصرف و گزارش‌های خطای اتصال را روی گوشی نگه می‌دارد؛ سامانهٔ تبلیغات، تحلیل رفتار یا ارسال خودکار این گزارش‌ها ندارد. اسکن، با درخواست HTTPS به منابع و دامنه‌های تست ارتباط برقرار می‌کند. تست سرعت به درخواست تو از Cloudflare یا OVH دانلود می‌کند و تا ۲۰ MB مصرف دارد. بررسی آی‌پی از ipify یا AWS انجام می‌شود. این سرویس‌ها IP خروجی درخواست را دریافت می‌کنند.\n\n"+
        "رمزنگاری تا پروکسی، مسیر بعد از پروکسی را به‌تنهایی رمزنگاری نمی‌کند؛ برای محرمانگی تا مقصد، خود برنامه یا سایت مقصد نیز باید از HTTPS یا رمزنگاری مناسب استفاده کند.\n\n"+
        "با زدن «می‌پذیرم»، عبور ترافیک از سرور منتخب و موارد بالا را می‌پذیری.";
}
