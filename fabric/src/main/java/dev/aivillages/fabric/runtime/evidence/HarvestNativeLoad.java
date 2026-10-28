package dev.aivillages.fabric.runtime.evidence;

/** Only the exact ChunkMap storage parse call establishes saved source provenance. */
public final class HarvestNativeLoad {
    private static final ThreadLocal<Integer> DEPTH=ThreadLocal.withInitial(()->0);
    private HarvestNativeLoad() { }
    public static boolean saved(){return DEPTH.get()>0;}
    public static Scope enter(){return new Scope();}
    public static final class Scope implements AutoCloseable {
        private final int previous=DEPTH.get();private boolean closed;
        private Scope(){DEPTH.set(Math.addExact(previous,1));}
        @Override public void close(){if(!closed){DEPTH.set(previous);closed=true;}}
    }
}
