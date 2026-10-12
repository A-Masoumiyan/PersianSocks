package ir.persiansocks.app;

/** Shared labels and action selection for the tile, widget and transparent command activity. */
public final class QuickControlState {
    public static final int OFF=0,CONNECTING=1,CONNECTED=2;
    private QuickControlState() {}
    public static boolean busy(int phase,boolean scanning) { return phase!=OFF||scanning; }
    public static String toggleLabel(int phase,boolean scanning) {
        return phase==CONNECTED?"قطع اتصال":busy(phase,scanning)?"لغو":"اتصال";
    }
    public static String subtitle(int phase,boolean scanning) {
        return scanning?"در حال اسکن":phase==CONNECTED?"متصل":phase==CONNECTING?"در حال اتصال":"قطع";
    }
    public static String autoLabel(int phase,boolean scanning) {
        return scanning?"توقف اسکن":phase==CONNECTED?"قطع اتصال":phase==CONNECTING?"لغو اتصال":"اسکن و اتصال خودکار";
    }
}
