package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.CitizenRegistry.*;
import static dev.aivillages.core.kernel.CitizenLifecycle.*;
import static org.junit.jupiter.api.Assertions.*;

final class CitizenLifecycleTest {
    static final class Owners implements CitizenLifecycle.Owners {
        int stops, jobs, leases; boolean stop = true, job = true, lease = true, throwing;
        public boolean stop(Citizen c, Marker m) { stops++; if (throwing) throw new IllegalStateException("owner unavailable"); return stop; }
        public boolean jobs(Citizen c, Marker m) { jobs++; return job; }
        public boolean leases(Citizen c, Marker m) { leases++; return lease; }
    }
    static final class Scene {
        final CitizenRegistryTest.Memory memory = new CitizenRegistryTest.Memory();
        final CitizenRegistryTest.TestClock clock = new CitizenRegistryTest.TestClock();
        final TrustedContext owner = CitizenRegistryTest.caller(memory.state.worldId());
        final ActorRef actor = new ActorRef(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld");
        final Owners owners = new Owners();
        final CitizenRegistry registry;
        final CitizenLifecycle lifecycle;
        Scene() { this(Settings.production()); }
        Scene(Settings settings) {
            memory.state = new Snapshot(owner.scope().worldId(), 1,
                    List.of(new Citizen(actor, owner, "Ada", Availability.LOADED)), null);
            registry = new CitizenRegistry(memory.state, memory, privateAddresses(), clock, false);
            lifecycle = new CitizenLifecycle(registry, owners, settings, 10);
        }
        Marker marker() { return lifecycle.marker(actor.citizenId()); }
        Decision fact(Event event, Source source) {
            return lifecycle.offer(new Fact(owner.scope().worldId(), actor, marker().generation(), event, source, 11));
        }
        void step() { registry.tick(); lifecycle.step(); }
        void settle() { for (int n = 0; n < 100; n++) step(); }
        void load() {
            settle(); assertEquals(Decision.ACCEPTED, fact(Event.LOAD, Source.ENTITY_LOAD)); settle();
            assertTrue(registry.available(actor));
        }
    }
    @Test void unloadReloadRetainsIdentityNameAndScopeAndFencesBeforePublication() {
        var s = new Scene(); s.load(); long generation = s.marker().generation();
        assertEquals(Decision.ACCEPTED, s.fact(Event.UNLOAD, Source.CHUNK_UNLOAD));
        assertFalse(s.registry.available(s.actor));
        assertEquals(Availability.UNLOADED, s.registry.query(s.actor.citizenId(), s.owner).availability());
        assertEquals(Decision.DEFERRED, s.fact(Event.LOAD, Source.ENTITY_LOAD));
        s.settle(); assertEquals(Event.UNLOAD, s.marker().event());
        assertEquals(Decision.ACCEPTED, s.fact(Event.LOAD, Source.ENTITY_LOAD)); s.settle();
        assertEquals(generation + 1, s.marker().generation());
        assertTrue(s.registry.available(s.actor));
        var c = s.registry.query(s.actor.citizenId(), s.owner);
        assertEquals(s.actor, c.actor()); assertEquals(s.owner, c.owner()); assertEquals("Ada", c.displayName());
        assertEquals(1, s.registry.snapshot().citizens().size());
    }
    @ParameterizedTest @EnumSource(value = Event.class, names = {"DEATH", "REMOVE", "REPLACEMENT", "DIMENSION"})
    void terminalAndUnsupportedBindingsCannotBeReactivated(Event event) {
        var s = new Scene(); s.load();
        Source source = switch (event) {
            case DEATH -> Source.AFTER_DEATH; case REMOVE -> Source.DISCARDED;
            case REPLACEMENT -> Source.UNSUPPORTED_REPLACEMENT; default -> Source.DIMENSION_CHANGE;
        };
        s.fact(event, source); s.settle();
        assertEquals(Decision.TERMINAL, s.fact(Event.LOAD, Source.ENTITY_LOAD));
        assertFalse(s.registry.observeAvailability(s.actor.citizenId(), Availability.LOADED).accepted());
        assertFalse(s.registry.available(s.actor)); assertEquals(event, s.marker().event());
        assertTrue(s.registry.controls(s.actor, s.owner)); // Identity/control history is retained, separately from work.
    }
    @Test void duplicateAndReorderedLossPreservesDeathAndDoesNotRepeatCleanup() {
        var s = new Scene(); s.load();
        s.fact(Event.UNLOAD, Source.CHUNK_UNLOAD); s.fact(Event.DEATH, Source.AFTER_DEATH); s.settle();
        int jobs = s.owners.jobs, leases = s.owners.leases; long revision = s.memory.state.revision();
        for (int n = 0; n < 100; n++) {
            s.fact(Event.UNLOAD, Source.CHUNK_UNLOAD); s.fact(Event.DEATH, Source.KILLED);
            s.fact(Event.LOAD, Source.ENTITY_LOAD); s.step();
        }
        assertEquals(Event.DEATH, s.marker().event()); assertEquals(jobs, s.owners.jobs);
        assertEquals(leases, s.owners.leases); assertEquals(revision, s.memory.state.revision());
    }
    @Test void staleLossCannotStopNewGenerationOrReleaseItsClaims() {
        var s = new Scene(); s.load(); long old = s.marker().generation();
        s.fact(Event.UNLOAD, Source.CHUNK_UNLOAD); s.settle(); s.fact(Event.LOAD, Source.ENTITY_LOAD); s.settle();
        int stops = s.owners.stops;
        assertEquals(Decision.STALE, s.lifecycle.offer(new Fact(s.owner.scope().worldId(), s.actor,
                old, Event.DEATH, Source.AFTER_DEATH, 12)));
        assertEquals(stops, s.owners.stops); assertTrue(s.registry.available(s.actor));
    }
    @Test void sameNameAndNearbySuccessorAndCrossWorldAssertionsCannotTransferIdentity() {
        var s = new Scene(); s.load(); int stops = s.owners.stops;
        for (var actor : List.of(new ActorRef(s.actor.citizenId(), UUID.randomUUID(), s.actor.dimension()),
                new ActorRef(UUID.randomUUID(), s.actor.entityId(), s.actor.dimension()),
                new ActorRef(s.actor.citizenId(), s.actor.entityId(), "minecraft:the_nether")))
            assertEquals(Decision.FOREIGN, s.lifecycle.offer(new Fact(s.owner.scope().worldId(), actor,
                    s.marker().generation(), Event.DEATH, Source.AFTER_DEATH, 12)));
        assertEquals(Decision.FOREIGN, s.lifecycle.offer(new Fact(UUID.randomUUID(), s.actor,
                s.marker().generation(), Event.DEATH, Source.AFTER_DEATH, 12)));
        assertEquals(stops, s.owners.stops); assertTrue(s.registry.available(s.actor));
        assertThrows(SecurityException.class, () -> s.registry.query(s.actor.citizenId(),
                CitizenRegistryTest.caller(s.owner.scope().worldId())));
    }
    @Test void missingObservationAndTimeoutNeverInferDeath() {
        var s = new Scene(); s.load(); s.fact(Event.MISSING, Source.MISSING_LOOKUP); s.settle();
        s.clock.now += 1_000_000; s.settle();
        assertEquals(Event.MISSING, s.marker().event()); assertFalse(s.marker().terminal());
        s.fact(Event.LOAD, Source.LOADED_LOOKUP); s.settle(); assertTrue(s.registry.available(s.actor));
    }
    @Test void deathDuringStalledRenameImmediatelyStopsWorkAndRetainsCommittedName() {
        var s = new Scene(); s.load(); s.memory.hold = true;
        assertTrue(s.registry.rename(s.actor.citizenId(), "Mira", s.owner).accepted());
        int stops = s.owners.stops;
        s.fact(Event.DEATH, Source.AFTER_DEATH); assertEquals(stops + 1, s.owners.stops);
        assertFalse(s.registry.available(s.actor)); s.step();
        s.memory.hold = false; s.memory.release(); s.settle();
        assertEquals("Mira", s.registry.query(s.actor.citizenId(), s.owner).displayName());
        assertEquals(Event.DEATH, s.marker().event()); assertEquals(7, s.marker().acknowledged());
    }
    @Test void deathCoalescedBeforeLoadPublicationStillRetainsTerminalFact() {
        var s = new Scene(); s.settle(); s.fact(Event.LOAD, Source.ENTITY_LOAD);
        s.fact(Event.DEATH, Source.AFTER_DEATH); s.settle();
        assertEquals(Event.DEATH, s.registry.snapshot().citizens().getFirst().lifecycle().event());
        assertFalse(s.registry.available(s.actor));
    }
    @Test void eachOwnerAcknowledgementSurvivesReopenWithoutReplayingAcknowledgedStages() {
        for (int checkpoint : List.of(0, 1, 3, 7)) {
            var s = new Scene(); s.load(); s.fact(Event.DEATH, Source.AFTER_DEATH);
            for (int n = 0; n < 100 && !(s.memory.state.citizens().getFirst().lifecycle().event() == Event.DEATH
                    && s.memory.state.citizens().getFirst().lifecycle().acknowledged() == checkpoint); n++) s.step();
            assertEquals(checkpoint, s.memory.state.citizens().getFirst().lifecycle().acknowledged());
            var owners = new Owners();
            var registry = new CitizenRegistry(s.memory.state, s.memory, privateAddresses(), s.clock, false);
            var recovery = new CitizenLifecycle(registry, owners, Settings.production(), 50);
            for (int n = 0; n < 80; n++) { registry.tick(); recovery.step(); }
            assertEquals(checkpoint == 0 ? 1 : 0, owners.stops);
            assertEquals(checkpoint <= 1 ? 1 : 0, owners.jobs);
            assertEquals(checkpoint <= 3 ? 1 : 0, owners.leases);
            assertFalse(registry.available(s.actor)); assertEquals(7, recovery.marker(s.actor.citizenId()).acknowledged());
        }
    }
    @Test void failedNotificationsAreBoundedAndRemainDurablyUnresolvedForRestart() {
        var s = new Scene(new Settings(1, 1, 2)); s.load(); s.owners.job = false;
        s.fact(Event.UNLOAD, Source.CHUNK_UNLOAD); s.settle();
        assertEquals(1, s.marker().acknowledged()); assertEquals(1, s.lifecycle.counters().exhausted());
        int calls = s.owners.jobs; s.settle(); assertEquals(calls, s.owners.jobs);
        assertEquals(Decision.DEFERRED, s.fact(Event.LOAD, Source.ENTITY_LOAD));
    }
    @Test void restartDoesNotTrustSavedLoadedObservation() {
        var s = new Scene(); s.load();
        var registry = new CitizenRegistry(s.memory.state, s.memory, privateAddresses(), s.clock, false);
        var recovery = new CitizenLifecycle(registry, new Owners(), Settings.production(), 100);
        assertFalse(registry.available(s.actor));
        assertEquals(Decision.DEFERRED, recovery.offer(new Fact(s.owner.scope().worldId(), s.actor,
                recovery.marker(s.actor.citizenId()).generation(), Event.LOAD, Source.ENTITY_LOAD, 101)));
        for (int n = 0; n < 100; n++) { registry.tick(); recovery.step(); }
        assertFalse(registry.available(s.actor)); assertEquals(Event.VERIFY, recovery.marker(s.actor.citizenId()).event());
    }
    @Test void populationQueueAndPerSliceWorkHaveFixedBoundsUnderDuplicateStorms() {
        var memory = new CitizenRegistryTest.Memory(); var rows = new ArrayList<Citizen>();
        var owner = CitizenRegistryTest.caller(memory.state.worldId());
        for (int n = 0; n < MAX_CITIZENS; n++) rows.add(new Citizen(
                new ActorRef(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld"), owner, "Ada", Availability.LOADED));
        memory.state = new Snapshot(owner.scope().worldId(), 1, rows, null);
        var registry = new CitizenRegistry(memory.state, memory, privateAddresses(), new CitizenRegistryTest.TestClock(), false);
        var lifecycle = new CitizenLifecycle(registry, new Owners(), new Settings(8, 8, 4096), 1);
        assertEquals(64, lifecycle.counters().queued());
        for (int round = 0; round < 16; round++) for (Citizen c : rows)
            lifecycle.offer(new Fact(owner.scope().worldId(), c.actor(), 0, Event.DEATH, Source.AFTER_DEATH, 2));
        for (int n = 0; n < 800; n++) {
            registry.tick(); lifecycle.step(); var counts = lifecycle.counters();
            assertTrue(counts.inspected() <= 8 && counts.notifications() <= 8 && counts.queued() <= 64);
        }
        assertEquals(64, registry.snapshot().citizens().size());
        assertTrue(registry.snapshot().citizens().stream().allMatch(c -> c.lifecycle().event() == Event.DEATH));
        rows.add(new Citizen(new ActorRef(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld"), owner, null, Availability.UNKNOWN));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(owner.scope().worldId(), 2, rows, null));
    }
    @ParameterizedTest @ValueSource(ints = {-1, 0, 9, Integer.MAX_VALUE})
    void invalidBatchAndLookupLimitsReject(int invalid) {
        assertThrows(IllegalArgumentException.class, () -> new Settings(invalid, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Settings(1, invalid, 1));
    }
    @ParameterizedTest @ValueSource(ints = {-1, 0, 4097, Integer.MAX_VALUE})
    void invalidRetryLimitsReject(int invalid) {
        assertThrows(IllegalArgumentException.class, () -> new Settings(1, 1, invalid));
    }
    @Test void fabricatedEvidenceAndInvalidGenerationsRejectBeforeCoordination() {
        var s = new Scene();
        assertThrows(IllegalArgumentException.class, () -> new Fact(s.owner.scope().worldId(), s.actor, 0, Event.DEATH, Source.ENTITY_LOAD, 0));
        assertThrows(IllegalArgumentException.class, () -> new Fact(s.owner.scope().worldId(), s.actor, -1, Event.DEATH, Source.AFTER_DEATH, 0));
        assertThrows(IllegalArgumentException.class, () -> new Marker(0, Event.UNLOAD, Source.CHUNK_UNLOAD, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Marker(0, Event.UNLOAD, Source.CHUNK_UNLOAD, 0, 2));
    }
    @Test void unavailableOwnerCannotCrashTheTickOrAcknowledgeUnfinishedCleanup() {
        var s=new Scene(new Settings(1,1,2)); s.load(); s.owners.throwing=true;
        assertDoesNotThrow(()->s.fact(Event.DEATH,Source.AFTER_DEATH));
        assertDoesNotThrow(s::settle); assertFalse(s.registry.available(s.actor));
        assertEquals(0,s.registry.snapshot().citizens().getFirst().lifecycle().acknowledged());
        assertEquals(1,s.lifecycle.counters().exhausted());
    }
}
