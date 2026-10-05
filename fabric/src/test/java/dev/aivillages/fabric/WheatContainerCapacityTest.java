package dev.aivillages.fabric;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

final class WheatContainerCapacityTest {
    @Test void mixedInventoryCountsOnlyCompatiblePermittedCapacity() {
        var slots = List.of(new WheatContainerCapacity.Slot(true,true,false,0,64),
                new WheatContainerCapacity.Slot(true,false,true,62,64),
                new WheatContainerCapacity.Slot(true,false,false,10,64),
                new WheatContainerCapacity.Slot(false,true,false,0,64));
        var sample = WheatContainerCapacity.inspect(slots.size(),slots::get);
        assertTrue(sample.known()); assertEquals(66,sample.capacity()); assertEquals(4,sample.slotsInspected());
    }
    @Test void perSlotLimitsAndOverfilledStacksCannotCreateCapacity() {
        var slots = List.of(new WheatContainerCapacity.Slot(true,true,false,0,16),
                new WheatContainerCapacity.Slot(true,false,true,12,16),
                new WheatContainerCapacity.Slot(true,false,true,65,64));
        assertEquals(20,WheatContainerCapacity.inspect(3,slots::get).capacity());
    }
    @Test void fullInventoryHasZeroCapacityAndEverySlotIsInspected() {
        AtomicInteger reads = new AtomicInteger();
        var sample = WheatContainerCapacity.inspect(27,i -> { reads.incrementAndGet();
            return new WheatContainerCapacity.Slot(true,false,true,64,64); });
        assertTrue(sample.known()); assertEquals(0,sample.capacity()); assertEquals(27,reads.get());
    }
    @Test void exactSlotBoundIsFiniteAndHasConservativeWheatCapacity() {
        AtomicInteger reads = new AtomicInteger();
        var sample = WheatContainerCapacity.inspect(128,i -> { reads.incrementAndGet();
            return new WheatContainerCapacity.Slot(true,true,false,0,64); });
        assertEquals(8192,sample.capacity()); assertEquals(128,reads.get()); assertEquals(128,sample.slotsInspected());
    }
    @Test void oversizedOrInvalidInventoryRemainsUnknownWithoutReadingSlots() {
        for (int size : new int[] {-1,129,Integer.MAX_VALUE}) {
            var sample = WheatContainerCapacity.inspect(size,i -> { fail("Out-of-budget slot read"); return null; });
            assertFalse(sample.known()); assertEquals(0,sample.capacity()); assertEquals(0,sample.slotsInspected());
        }
    }
    @Test void emptySupportedInventoryIsKnownButCannotFulfilQuantity() {
        var sample = WheatContainerCapacity.inspect(0,i -> { fail("Unexpected read"); return null; });
        assertTrue(sample.known()); assertEquals(0,sample.capacity());
    }
}
