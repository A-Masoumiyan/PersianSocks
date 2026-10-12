import ir.persiansocks.app.*;
import java.util.*;

public final class ControlsHarness {
    static int passed;
    static void check(boolean ok,String name){
        if(!ok)throw new AssertionError(name);
        passed++;System.out.println("PASS: "+name);
    }
    static ProxyEndpoint p(String host,int latency,int mask){return new ProxyEndpoint(host,1080,latency,false,mask,7,100);}
    public static void main(String[]args){
        check(!QuickControlState.busy(0,false),"An inactive tile starts a connection");
        check(QuickControlState.busy(1,false)&&QuickControlState.busy(0,true),"Connecting and scanning taps cancel ongoing work");
        check(QuickControlState.toggleLabel(2,false).equals("قطع اتصال"),"Connected tile switches to disconnect action");
        check(QuickControlState.autoLabel(0,false).equals("اسکن و اتصال خودکار"),"Disconnected widget offers automatic scan");
        check(QuickControlState.autoLabel(1,true).equals("توقف اسکن"),"A scanning widget changes the same button into Stop");
        check(QuickControlState.autoLabel(1,false).equals("لغو اتصال"),"Connecting widget can cancel preflight");
        check(QuickControlState.autoLabel(2,false).equals("قطع اتصال"),"Connected widget can disconnect");
        check(QuickControlState.subtitle(2,false).equals("متصل")&&QuickControlState.subtitle(1,true).equals("در حال اسکن"),
            "Both phone controls expose current phase correctly");
        AutoScanSelection choice=new AutoScanSelection("FULL",ProxyResults.Sort.FASTEST);
        ProxyEndpoint cached=p("1.1.1.1",1,7),tg=p("2.2.2.2",3,1),one=p("3.3.3.3",300,7),two=p("4.4.4.4",80,7),three=p("5.5.5.5",200,7);
        check(choice.best()==null&&choice.mergeSaved(Arrays.asList(cached)).size()==1,
            "Cached healthy entries are retained but cannot bypass fresh auto-scan confirmation");
        choice.add(tg);
        check(choice.best()==null,"A fresh Telegram-only result cannot auto-connect from Full filter");
        choice.add(one);choice.add(two);
        check(choice.best()==two&&!choice.haveBackups(),"Auto-connect chooses the best fresh compatible result collected so far");
        choice.add(three);
        check(choice.haveBackups(),"Three compatible results permit early scan completion with backups");
        ProxyEndpoint refreshed=p(one.host,20,7);choice.add(refreshed);
        check(choice.results().size()==4&&choice.best()==refreshed,"New measurements replace duplicates and reorder the selected candidate");
        List<ProxyEndpoint> merged=choice.mergeSaved(Arrays.asList(one,cached));
        check(merged.size()==5&&merged.stream().filter(x->x.address().equals(one.address())).findFirst().get()==refreshed,
            "Fresh results replace cached measurements without discarding unrelated backups");
        AutoScanSelection crowded=new AutoScanSelection("FULL",ProxyResults.Sort.FASTEST);
        for(int i=0;i<1000;i++)crowded.add(new ProxyEndpoint("9.9.9.9",1000+i,10,false,1,7,100));
        crowded.add(one);
        check(crowded.mergeSaved(Collections.emptyList()).subList(0,1000).contains(one),
            "A Full-compatible candidate survives the 1000-result storage limit after many unrelated results");
        ServiceTargets.Target custom=ServiceTargets.custom("فیسبوک","www.facebook.com");
        ServiceTargets.configure(Arrays.asList(custom));
        AutoScanSelection facebook=new AutoScanSelection(custom.id,ProxyResults.Sort.FASTEST);
        facebook.add(one);
        check(facebook.best()==null,"Custom-domain widget scans do not connect to a built-in-only result");
        ProxyEndpoint domainOnly=new ProxyEndpoint("6.6.6.6",1080,40,false,0,7,100,
            Collections.singleton(custom.id),Collections.singleton(custom.id),"US");
        facebook.add(domainOnly);
        check(facebook.best()==domainOnly,"A valid custom-only result can initiate auto-connect for its own filter");
        ServiceTargets.configure(Collections.emptyList());
        System.out.println("RESULT: "+passed+" checks passed");
    }
}
