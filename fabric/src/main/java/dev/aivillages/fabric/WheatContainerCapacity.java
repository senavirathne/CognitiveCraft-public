package dev.aivillages.fabric;

import java.util.function.IntFunction;
import static dev.aivillages.core.kernel.WorldReferenceDiscovery.MAX_CONTAINER_SLOTS;

/** Read-only capacity arithmetic shared by the loaded Minecraft observation adapter. */
final class WheatContainerCapacity {
    private WheatContainerCapacity() { }
    record Slot(boolean acceptsWheat, boolean empty, boolean sameWheat, int count, int limit) {
        Slot {
            if (count < 0 || limit < 0 || limit > 64) throw new IllegalArgumentException("Slot bounds");
        }
    }
    record Sample(boolean known, int capacity, int slotsInspected) { }
    static Sample inspect(int slots, IntFunction<Slot> reader) {
        if (slots < 0 || slots > MAX_CONTAINER_SLOTS) return new Sample(false,0,0);
        int capacity = 0;
        for (int i=0; i<slots; i++) {
            Slot slot = reader.apply(i);
            if (slot.acceptsWheat() && (slot.empty() || slot.sameWheat()))
                capacity = Math.addExact(capacity, slot.empty() ? slot.limit() : Math.max(0,slot.limit()-slot.count()));
        }
        return new Sample(true,capacity,slots);
    }
}
