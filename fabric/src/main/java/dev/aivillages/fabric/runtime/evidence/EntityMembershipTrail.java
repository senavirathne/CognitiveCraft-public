package dev.aivillages.fabric.runtime.evidence;

import dev.aivillages.core.kernel.runtime.evidence.NativePickup;
import java.util.*;

/** Main-thread complete trail from the real H lifetime; capture is data, not pickup authority. */
public final class EntityMembershipTrail {
    private final Thread owner=Thread.currentThread();
    private final UUID entity,incarnation;
    private final NavigableSet<NativePickup.Chunk> chunks=new TreeSet<>();
    private NativePickup.Chunk current;
    private boolean complete=true;
    public EntityMembershipTrail(UUID entity,UUID incarnation,NativePickup.Chunk birth) {
        this.entity=Objects.requireNonNull(entity);this.incarnation=Objects.requireNonNull(incarnation);
        current=Objects.requireNonNull(birth);chunks.add(birth);
    }
    private void thread(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Native entity membership thread");}
    /** Must be wired to every real chunk change, not sampled once per tick. */
    public void moved(NativePickup.Chunk next) {
        thread();Objects.requireNonNull(next);
        if(!complete)return;
        if(!next.dimension().equals(current.dimension())){complete=false;return;}
        if(!chunks.contains(next) && chunks.size()==NativePickup.MAX_TRAIL_CHUNKS){complete=false;return;}
        chunks.add(next);current=next;
    }
    /** Unknown/unobserved movement permanently refuses a complete first-pickup capture. */
    public void gap(){thread();complete=false;}
    public boolean complete(){thread();return complete;}
    public NativePickup.Trail capture() {
        thread();if(!complete)throw new IllegalStateException("Incomplete native entity membership");
        return new NativePickup.Trail(entity,incarnation,List.copyOf(chunks),current);
    }
}
