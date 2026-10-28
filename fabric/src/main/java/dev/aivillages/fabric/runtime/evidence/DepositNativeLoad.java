package dev.aivillages.fabric.runtime.evidence;

import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.Kind;

/** Exact native storage-load call sites establish provenance; command/NBT imports do not. */
public final class DepositNativeLoad {
    private static final ThreadLocal<Integer> ACTIVE=ThreadLocal.withInitial(()->0);
    private DepositNativeLoad() { }
    public static boolean saved(Kind kind){return (ACTIVE.get()&(1<<kind.ordinal()))!=0;}
    public static Scope enter(Kind kind){return new Scope(kind);}
    public static final class Scope implements AutoCloseable {
        private final int previous;private boolean closed;
        private Scope(Kind kind){previous=ACTIVE.get();ACTIVE.set(previous|(1<<kind.ordinal()));}
        @Override public void close(){if(!closed){ACTIVE.set(previous);closed=true;}}
    }
}
