package ir.persiansocks.app;
/** A delayed stop belongs to one command and generation, never its replacement. */
public final class IdleStopPolicy {
    private IdleStopPolicy(){}
    public static boolean allowed(int oldGeneration,int generation,int oldStart,int start,boolean requested){return !requested&&oldGeneration==generation&&oldStart==start;}
}
