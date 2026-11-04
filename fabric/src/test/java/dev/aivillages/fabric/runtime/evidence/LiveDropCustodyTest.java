package dev.aivillages.fabric.runtime.evidence;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LiveDropCustodyTest {
    @Test void originalRunProtectsDropAndReleasePermanentlyEndsExclusion() {
        UUID original=UUID.randomUUID();var current=new AtomicReference<>(original);
        var custody=new LiveDropCustody();custody.bind(()->original.equals(current.get()));
        assertTrue(custody.active());current.set(null);assertFalse(custody.active());
        current.set(original);assertFalse(custody.active());
    }
    @Test void anotherRunOnSameActorCannotInheritOriginalDrop() {
        UUID original=UUID.randomUUID();var current=new AtomicReference<>(original);
        var custody=new LiveDropCustody();custody.bind(()->original.equals(current.get()));
        current.set(UUID.randomUUID());assertFalse(custody.active());
        assertThrows(IllegalStateException.class,()->custody.bind(()->true));
    }
    @Test void reloadHasNoLiveOwnerEvenWhenSavedDescriptionRemains() {
        var original=new LiveDropCustody();original.bind(()->true);assertTrue(original.active());
        var reloaded=new LiveDropCustody();assertFalse(reloaded.active());
    }
}
