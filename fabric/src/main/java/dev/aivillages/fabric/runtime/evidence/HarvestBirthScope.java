package dev.aivillages.fabric.runtime.evidence;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.world.entity.item.ItemEntity;

/** Exact synchronous spawn observation inside the trusted original H callback; data, never effect authority. */
public final class HarvestBirthScope implements AutoCloseable {
    private static final ThreadLocal<HarvestBirthScope> CURRENT=new ThreadLocal<>();
    private final Thread owner=Thread.currentThread();private final Consumer<ItemEntity> observer;private boolean closed,dispatching;
    private HarvestBirthScope(Consumer<ItemEntity> observer){this.observer=Objects.requireNonNull(observer);}
    static HarvestBirthScope open(Consumer<ItemEntity> observer) {
        if(CURRENT.get()!=null)throw new IllegalStateException("Nested original harvest birth observation");
        var scope=new HarvestBirthScope(observer);CURRENT.set(scope);return scope;
    }
    static boolean active(){return CURRENT.get()!=null;}
    /** Called before the original native addFreshEntity; an unscoped vanilla harvest stays untracked. */
    public static void spawned(ItemEntity entity) {
        var scope=CURRENT.get();if(scope==null)return;
        if(scope.closed||scope.dispatching||Thread.currentThread()!=scope.owner)throw new IllegalStateException("Changed original harvest birth scope");
        Objects.requireNonNull(entity);scope.dispatching=true;
        try{scope.observer.accept(entity);}finally{scope.dispatching=false;}
    }
    public void close() {
        if(closed||Thread.currentThread()!=owner||CURRENT.get()!=this||dispatching)throw new IllegalStateException("Changed harvest birth scope closure");
        CURRENT.remove();closed=true;
    }
}
