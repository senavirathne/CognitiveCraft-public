package dev.aivillages.fabric;

import java.util.function.IntFunction;
import static dev.aivillages.core.kernel.WorldReferenceDiscovery.MAX_CONTAINER_SLOTS;

/** Bounded component-aware slot arithmetic; all eligibility is supplied by the live adapter. */
final class ItemContainerCapacity {
    record Slot(boolean accepts,boolean empty,boolean sameComponents,int count,int limit) {
        Slot { if (count<0 || limit<0 || limit>64) throw new IllegalArgumentException("Slot bounds"); }
    }
    record Sample(boolean known,int capacity,int slotsInspected) { }
    static Sample inspect(int slots,IntFunction<Slot> reader) {
        if (slots<0 || slots>MAX_CONTAINER_SLOTS) return new Sample(false,0,0);
        int capacity=0;
        for(int i=0;i<slots;i++) {
            Slot s=reader.apply(i);
            if(s.accepts() && (s.empty() || s.sameComponents()))
                capacity=Math.addExact(capacity,Math.max(0,s.limit()-s.count()));
        }
        return new Sample(true,capacity,slots);
    }
    private ItemContainerCapacity() { }
}
