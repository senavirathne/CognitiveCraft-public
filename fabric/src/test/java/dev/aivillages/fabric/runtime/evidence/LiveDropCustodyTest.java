package dev.aivillages.fabric.runtime.evidence;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LiveDropCustodyTest {
    @Test void originalRunProtectsDropAndReleasePermanentlyEndsExclusion() {
        UUID original=UUID.randomUUID();var current=new AtomicReference<>(original);
        var custody=new LiveDropCustody();custody.bind(UUID.randomUUID(),()->original.equals(current.get()));
        assertTrue(custody.active());current.set(null);assertFalse(custody.active());
        current.set(original);assertFalse(custody.active());
    }
    @Test void anotherRunOnSameActorCannotInheritOriginalDrop() {
        UUID original=UUID.randomUUID();var current=new AtomicReference<>(original);
        var actor=UUID.randomUUID();var custody=new LiveDropCustody();custody.bind(actor,()->original.equals(current.get()));
        current.set(UUID.randomUUID());assertFalse(custody.active());
        assertThrows(IllegalStateException.class,()->custody.bind(actor,()->true));
    }
    @Test void reloadHasNoLiveOwnerEvenWhenSavedDescriptionRemains() {
        var actor=UUID.randomUUID();var original=new LiveDropCustody();original.bind(actor,()->true);assertTrue(original.active());
        original.restoreOrigin(actor);assertFalse(original.active());
        assertThrows(IllegalStateException.class,()->original.bind(actor,()->true));
        var reloaded=new LiveDropCustody();reloaded.restoreOrigin(actor);assertFalse(reloaded.active());
    }
    @Test void stoppedOrReloadedOutputRemainsAvailableToItsOriginAndBlocksForeignMobs() {
        var actor=UUID.randomUUID();var foreign=UUID.randomUUID();
        var owned=new AtomicReference<>(true);var custody=new LiveDropCustody();custody.bind(actor,owned::get);
        assertTrue(custody.blocksNativePickup(actor));
        owned.set(false);
        assertFalse(custody.blocksNativePickup(actor));assertTrue(custody.blocksNativePickup(foreign));
        var restored=new LiveDropCustody();restored.restoreOrigin(custody.origin());
        assertFalse(restored.active());assertFalse(restored.blocksNativePickup(actor));assertTrue(restored.blocksNativePickup(foreign));
        restored.restoreOrigin(null);assertFalse(restored.blocksNativePickup(foreign));
    }
}
