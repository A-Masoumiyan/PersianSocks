package ir.persiansocks.app;

import java.util.*;

/** Bounded attempts and two failed health samples prevent flapping and endless loops. */
public final class FailoverPolicy {
    public static final int MAX_ATTEMPTS = 8;
    private final Set<String> attempted = new HashSet<>();
    private final Map<String,Long> cooldown = new HashMap<>();
    private int healthFailures;
    public void reset(String first) { attempted.clear(); cooldown.clear(); healthFailures=0; attempted.add(first); }
    public void connected(String address) { attempted.clear(); attempted.add(address); healthFailures=0; cooldown.remove(address); }
    public boolean health(boolean ok) {
        healthFailures=ok?0:healthFailures+1;
        return healthFailures>=2;
    }
    public void failed(String address,long now) { attempted.add(address); cooldown.put(address,now+300_000); }
    public ProxyEndpoint next(List<ProxyEndpoint> candidates,String current,String filter,
            ProxyResults.Sort sort,long now) {
        if(attempted.size()>=MAX_ATTEMPTS)return null;
        List<ProxyEndpoint> ranked=ProxyResults.visible(candidates,filter,sort);
        return nextRanked(ranked,current,now);
    }
    public ProxyEndpoint nextRanked(List<ProxyEndpoint> ranked,String current,long now){
        if(attempted.size()>=MAX_ATTEMPTS)return null;
        int start=0;
        for(int i=0;i<ranked.size();i++)if(ranked.get(i).key().equals(current)){start=i+1;break;}
        for(int step=0;step<ranked.size();step++) {
            ProxyEndpoint p=ranked.get((start+step)%ranked.size());
            if(p.key().equals(current)||attempted.contains(p.key())||cooldown.getOrDefault(p.key(),0L)>now)continue;
            attempted.add(p.key());return p;
        }
        return null;
    }
    public ServiceTargets.Target[] healthTargets(String filter,ProxyEndpoint active) {
        List<ServiceTargets.Target> targets=new ArrayList<>();
        for(ServiceTargets.Target t:ServiceTargets.current()) {
            boolean needed;
            if(filter.equals("FULL"))needed=t.bit!=0;
            else if(filter.equals("ALL"))needed=active!=null&&active.passed(t);
            else if(filter.startsWith("https://"))needed=t.id.equals(filter);
            else needed=t.bit!=0&&filter.equals(t.bit==1?"TELEGRAM":t.bit==2?"YOUTUBE":"INSTAGRAM");
            if(needed)targets.add(t);
        }
        if(targets.isEmpty())targets.addAll(Arrays.asList(ServiceTargets.ALL));
        return targets.toArray(new ServiceTargets.Target[0]);
    }
}
