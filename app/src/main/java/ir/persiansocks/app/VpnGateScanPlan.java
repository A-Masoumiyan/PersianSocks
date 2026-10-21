package ir.persiansocks.app;

import java.util.*;
import java.util.function.Predicate;

/** Bounded preparation window; a slow download never blocks another ready server. */
public final class VpnGateScanPlan {
 public static final int WINDOW=8,DOWNLOADS=4;
 public static final class Item {
  public final VpnGateProfile candidate;public VpnGateProfile profile;public String error="";private int state;
  private Item(VpnGateProfile p){candidate=p;}
 }
 private final List<Item> items=new ArrayList<>();private int buffered,done;
 public VpnGateScanPlan(List<VpnGateProfile> profiles){for(VpnGateProfile p:profiles)items.add(new Item(p));}
 public synchronized List<Item> reserve(Predicate<VpnGateProfile> eligible){
  List<Item> out=new ArrayList<>();for(Item item:items){if(buffered>=WINDOW)break;if(item.state==0&&eligible.test(item.candidate)){item.state=1;buffered++;out.add(item);}}return out;
 }
 public synchronized void prepared(Item item,VpnGateProfile profile,String error){if(item.state!=1)return;item.profile=profile;item.error=error==null?"":error;item.state=2;}
 public synchronized Item takeReady(){for(Item item:items)if(item.state==2){item.state=3;buffered--;done++;return item;}return null;}
 public synchronized boolean finished(){return done==items.size();}
 public static int stageSeconds(String state){return state.equals("AUTH")?30:state.equals("GET_CONFIG")?15:12;}
 private VpnGateScanPlan(){throw new AssertionError();}
}
