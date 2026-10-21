import ir.persiansocks.app.*;
public final class ScanGovernorHarness {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void complete(ScanCapacityGovernor g,int count){for(int i=0;i<count;i++)g.completed(false);}
    public static void main(String[] args){
        ScanCapacityGovernor g=new ScanCapacityGovernor(false,0);
        check(g.capacity(0,216,192,true)==192,"Fast starts at the eightfold capacity target when resources permit");
        complete(g,100);check(g.capacity(10000,216,192,true)==216,"Measured completed work increases saturated capacity");
        complete(g,100);check(g.capacity(20000,216,216,true)==216,"Dead proxies may complete without collapsing throughput");
        complete(g,40);check(g.capacity(30000,216,216,true)==192,"Throughput regression backs off the last increase");
        check(g.capacity(31000,8,96,true)==8,"Memory/thermal pressure applies immediately");
        check(g.capacity(32000,216,8,true)==8,"Recovery never jumps immediately to 216");
        complete(g,100);check(g.capacity(41000,216,8,true)==32,"Recovery is bounded and gradual");
        check(g.rate(41000)>0,"Measured tests/sec is available");
        ScanCapacityGovernor idle=new ScanCapacityGovernor(false,0);
        for(int i=0;i<100;i++)idle.completed(true);
        check(idle.capacity(20000,216,192,true)==192&&idle.rate(20000)==0,"Cancellation is not completed work");
        complete(idle,100);check(idle.capacity(30000,216,2,false)==192,"A draining or unsaturated queue does not ramp");
        ScanCapacityGovernor deep=new ScanCapacityGovernor(true,0);deep.capacity(0,216,12,true);complete(deep,100);
        check(deep.capacity(10000,216,12,true)==16,"Deep scans recover in smaller steps");
        check(deep.capacity(11000,1,16,true)==1,"VPN connection can reduce capacity to one");
        System.out.println("PASS: measured throughput, thermal/memory backoff, recovery, cancellation and draining checks");
    }
}
