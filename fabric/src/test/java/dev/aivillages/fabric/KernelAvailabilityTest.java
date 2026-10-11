package dev.aivillages.fabric;

import dev.aivillages.core.kernel.CitizenRegistry;
import dev.aivillages.core.kernel.Contracts.*;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

final class KernelAvailabilityTest {
    @Test void unloadObservationSurvivesLoadedRowWhilePublicationIsPending() {
        var principal = new PrincipalRef(UUID.randomUUID());
        var owner = new TrustedContext(principal, new ScopeRef(UUID.randomUUID(), principal.id()));
        var actor = new ActorRef(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld");
        var citizen = new CitizenRegistry.Citizen(actor, owner, "Ada", CitizenRegistry.Availability.LOADED);
        var initial = new CitizenRegistry.Snapshot(owner.scope().worldId(), 0, List.of(citizen), null);
        var pending = new CompletableFuture<CitizenRegistry.Snapshot>();
        var written = new AtomicReference<CitizenRegistry.Snapshot>();
        var registry = new CitizenRegistry(initial, (expected, next) -> {
            assertEquals(initial, expected); written.set(next); return pending;
        }, CitizenRegistry.privateAddresses(), Clock.systemUTC(), false);
        KernelSession.refreshLoadedAvailability(registry, citizen, true);
        assertTrue(registry.observeAvailability(actor.citizenId(), CitizenRegistry.Availability.UNLOADED).accepted());
        assertFalse(registry.ready());
        for (int tick = 0; tick < 5; tick++) {
            KernelSession.refreshLoadedAvailability(registry, citizen, false);
            registry.tick();
            assertEquals(CitizenRegistry.Availability.LOADED, registry.snapshot().citizens().getFirst().availability());
            assertEquals(CitizenRegistry.Availability.UNLOADED, registry.query(actor.citizenId(), owner).availability());
        }
        pending.complete(written.get()); registry.tick();
        assertTrue(registry.ready());
        KernelSession.refreshLoadedAvailability(registry, registry.snapshot().citizens().getFirst(), false);
        assertEquals(CitizenRegistry.Availability.UNLOADED, registry.query(actor.citizenId(), owner).availability());
    }
}
