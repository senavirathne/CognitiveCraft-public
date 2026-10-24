package dev.aivillages.core.kernel;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import static org.junit.jupiter.api.Assertions.*;

class MandateEffectIntegrationTest {
    @Test void explicitEffectSetsFitTheRegisteredAlphabetWithoutWideningExistingRights() {
        var actor = new ActorRef(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld");
        var area = new Cuboid("minecraft:overworld", 0, 0, 0, 4, 4, 4);
        var destination = new ContainerRef("minecraft:overworld", 1, 1, 1);
        var beforePlacement = EnumSet.allOf(Effect.class);
        beforePlacement.remove(Effect.PLACE);
        var oldRights = new Mandates.Rights(Set.of(actor), beforePlacement,
                Set.of("minecraft:wheat"), area, Set.of(destination));
        var allExplicit = new Mandates.Rights(Set.of(actor), EnumSet.allOf(Effect.class),
                Set.of("minecraft:wheat"), area, Set.of(destination));
        assertEquals(Effect.values().length, allExplicit.actions().size());
        assertFalse(oldRights.actions().contains(Effect.PLACE));
        assertTrue(oldRights.subsetOf(allExplicit));
        assertFalse(allExplicit.subsetOf(oldRights));
        assertThrows(IllegalArgumentException.class, () -> new Mandates.Rights(Set.of(actor),
                Set.of(), Set.of("minecraft:wheat"), area, Set.of(destination)));
    }
}
