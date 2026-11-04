package dev.aivillages.fabric.runtime.evidence;

import dev.aivillages.core.kernel.runtime.evidence.NativePickup;
import java.util.*;

/** Main-thread complete trail from the real H lifetime; capture is data, not pickup authority. */
public final class EntityMembershipTrail {
    private Thread owner=Thread.currentThread();
    private final UUID entity,incarnation;
    private final NavigableSet<NativePickup.Chunk> chunks=new TreeSet<>();
    private NativePickup.Chunk current;
    private boolean complete=true;
    private java.util.function.Consumer<NativePickup.Trail> observer=t->{};
    public void observe(java.util.function.Consumer<NativePickup.Trail> observer){thread();this.observer=Objects.requireNonNull(observer);}
    public EntityMembershipTrail(UUID entity,UUID incarnation,NativePickup.Chunk birth) {
        this.entity=Objects.requireNonNull(entity);this.incarnation=Objects.requireNonNull(incarnation);
        current=Objects.requireNonNull(birth);chunks.add(birth);
    }
    static EntityMembershipTrail restored(NativePickup.Trail saved,boolean complete) {
        var result=new EntityMembershipTrail(saved.entity(),saved.incarnation(),saved.chunks().getFirst());
        result.chunks.clear();result.chunks.addAll(saved.chunks());result.current=saved.current();result.complete=complete;result.owner=null;return result;
    }
    public NativePickup.Trail description(){thread();return new NativePickup.Trail(entity,incarnation,List.copyOf(chunks),current);}
    private void thread(){if(owner==null && DepositNativeLoad.saved(dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.Kind.ACTOR))return;if(owner==null)owner=Thread.currentThread();if(Thread.currentThread()!=owner)throw new IllegalStateException("Native entity membership thread");}
    /** Must be wired to every real chunk change, not sampled once per tick. */
    public void moved(NativePickup.Chunk next) {
        thread();Objects.requireNonNull(next);
        if(!complete)return;
        if(!next.dimension().equals(current.dimension())){complete=false;return;}
        if(!chunks.contains(next) && chunks.size()==NativePickup.MAX_TRAIL_CHUNKS){complete=false;return;}
        boolean changed=!current.equals(next);chunks.add(next);current=next;if(changed)observer.accept(capture());
    }
    /** Unknown/unobserved movement permanently refuses a complete first-pickup capture. */
    public void gap(){thread();complete=false;}
    public boolean complete(){thread();return complete;}
    public NativePickup.Trail capture() {
        thread();if(!complete)throw new IllegalStateException("Incomplete native entity membership");
        return new NativePickup.Trail(entity,incarnation,List.copyOf(chunks),current);
    }
}
