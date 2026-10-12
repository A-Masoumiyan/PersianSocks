import ir.persiansocks.app.SplitTunnelPolicy;
import java.util.*;

public final class SplitTunnelHarness {
    private static int checks;
    private static final String SELF="ir.persiansocks.app";
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static final class Rules implements SplitTunnelPolicy.Rules {
        Set<String> present=new HashSet<>(Arrays.asList(SELF,"user.a","user.b","system.a"));
        Set<String> allowed=new LinkedHashSet<>(),excluded=new LinkedHashSet<>();
        String removedDuringApply;
        public boolean installed(String name){return present.contains(name);}
        public boolean allow(String name){if(name.equals(removedDuringApply))return false;allowed.add(name);return true;}
        public boolean exclude(String name){if(name.equals(removedDuringApply))return false;excluded.add(name);return true;}
    }
    private static SplitTunnelPolicy policy(SplitTunnelPolicy.Mode mode,String...names){return new SplitTunnelPolicy(mode,Arrays.asList(names),SELF);}
    private static void rejects(SplitTunnelPolicy p,Rules r)throws Exception{
        boolean rejected=false;try{p.apply(SELF,r);}catch(IllegalArgumentException e){rejected=true;}
        check(rejected,"empty effective allow list must abort VPN establishment");
        check(r.allowed.isEmpty(),"self must not mask missing selected apps");
    }
    public static void main(String[]args)throws Exception{
        Rules r=new Rules();policy(SplitTunnelPolicy.Mode.ALL,"user.a").apply(SELF,r);
        check(r.allowed.isEmpty()&&r.excluded.isEmpty(),"ALL must leave builder unrestricted");
        r=new Rules();int n=policy(SplitTunnelPolicy.Mode.ONLY_SELECTED,"user.a","user.a","missing",SELF).apply(SELF,r);
        check(n==1,"only one installed user selection");
        check(r.allowed.equals(new LinkedHashSet<>(Arrays.asList("user.a",SELF))),"only selection and tunnel verifier allowed");
        check(r.excluded.isEmpty(),"never mix builder allow and exclude lists");
        r=new Rules();n=policy(SplitTunnelPolicy.Mode.EXCEPT_SELECTED,"user.a","system.a",SELF,"missing").apply(SELF,r);
        check(n==2&&r.excluded.equals(new HashSet<>(Arrays.asList("user.a","system.a"))),"exclude selected installed packages");
        check(r.allowed.isEmpty()&&!r.excluded.contains(SELF),"verification remains in tunnel in bypass mode");
        rejects(policy(SplitTunnelPolicy.Mode.ONLY_SELECTED),new Rules());
        rejects(policy(SplitTunnelPolicy.Mode.ONLY_SELECTED,"missing",SELF),new Rules());
        r=new Rules();r.removedDuringApply="user.a";rejects(policy(SplitTunnelPolicy.Mode.ONLY_SELECTED,"user.a"),r);
        r=new Rules();r.removedDuringApply="user.a";n=policy(SplitTunnelPolicy.Mode.ONLY_SELECTED,"user.a","user.b").apply(SELF,r);
        check(n==1&&r.allowed.contains("user.b"),"uninstall race with remaining app does not abort");
        r=new Rules();policy(SplitTunnelPolicy.Mode.EXCEPT_SELECTED,"missing").apply(SELF,r);
        check(r.allowed.isEmpty()&&r.excluded.isEmpty(),"stale bypass selection does not fail connection");
        check(SplitTunnelPolicy.visible(false,false,false),"user app visible by default");
        check(!SplitTunnelPolicy.visible(true,false,false),"system hidden by default");
        check(!SplitTunnelPolicy.visible(false,true,false),"updated system app hidden by default");
        check(SplitTunnelPolicy.visible(true,true,true),"show all includes system apps");
        List<String> source=new ArrayList<>(Arrays.asList("user.a","system.a"));
        SplitTunnelPolicy snapshot=new SplitTunnelPolicy(SplitTunnelPolicy.Mode.ONLY_SELECTED,source,SELF);source.clear();
        check(snapshot.packages.size()==2,"connection captures immutable preferences snapshot");
        boolean immutable=false;try{snapshot.packages.clear();}catch(UnsupportedOperationException e){immutable=true;}
        check(immutable,"snapshot is immutable");
        check(policy(SplitTunnelPolicy.Mode.ALL,"",null,SELF,"user.a").packages.size()==1,"invalid names removed");
        System.out.println("SplitTunnelHarness: "+checks+" checks passed");
    }
}
