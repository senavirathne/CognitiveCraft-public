package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.CitizenRegistry.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;
import static org.junit.jupiter.api.Assertions.*;

final class CitizenRegistryTest {
    static final class TestClock extends Clock {
        long now = 1_000;
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }
    static final class Memory implements Storage {
        Snapshot state = new Snapshot(UUID.randomUUID(), 0, List.of(), null), heldState;
        CompletableFuture<Snapshot> held;
        int writes;
        boolean hold, fail;
        @Override public CompletionStage<Snapshot> replace(Snapshot expected, Snapshot next) {
            assertEquals(state, expected); writes++;
            if (fail) return CompletableFuture.failedFuture(new java.io.IOException("injected"));
            if (hold) { heldState = next; held = new CompletableFuture<>(); return held; }
            state = next; return CompletableFuture.completedFuture(next);
        }
        void release() { state = heldState; held.complete(state); }
    }
    static final class Scene {
        final Memory memory = new Memory();
        final TestClock clock = new TestClock();
        final TrustedContext owner = caller(memory.state.worldId()), foreign = caller(memory.state.worldId());
        final CitizenRegistry registry;
        Scene() { this(privateAddresses()); }
        Scene(AddressPolicy policy) { registry = new CitizenRegistry(memory.state, memory, policy, clock, false); }
        Citizen enroll() {
            var result = registry.enroll(UUID.randomUUID(), "minecraft:overworld", owner);
            assertTrue(result.accepted()); registry.tick(); return result.citizen();
        }
        void name(Citizen c, String name) {
            assertTrue(registry.rename(c.actor().citizenId(), name, owner).accepted()); registry.tick();
        }
        void availability(Citizen c, Availability fact) {
            assertTrue(registry.observeAvailability(c.actor().citizenId(), fact).accepted()); registry.tick();
        }
    }
    static TrustedContext caller(UUID world) {
        var principal = new PrincipalRef(UUID.randomUUID());
        return new TrustedContext(principal, new ScopeRef(world, principal.id()));
    }

    @Test void renameAndReloadPreserveStableIdentityAndControl() {
        var s = new Scene(); var citizen = s.enroll();
        s.name(citizen, "Ada"); s.availability(citizen, Availability.LOADED);
        s.name(citizen, "Mira");
        var reopened = new CitizenRegistry(s.memory.state, s.memory, privateAddresses(), s.clock, false);
        var saved = reopened.query(citizen.actor().citizenId(), s.owner);
        assertEquals(citizen.actor(), saved.actor()); assertEquals(s.owner, saved.owner());
        assertEquals("Mira", saved.displayName());
        assertEquals(Availability.UNKNOWN, saved.availability());
        assertTrue(reopened.controls(citizen.actor(), s.owner));
        assertEquals(1, reopened.snapshot().citizens().size());
    }

    @Test void duplicateNamesReturnCandidatesAndExactIdDisambiguates() {
        var s = new Scene(); var a = s.enroll(); var b = s.enroll();
        s.name(a, "Ada"); s.name(b, "Ada");
        s.availability(a, Availability.LOADED); s.availability(b, Availability.LOADED);
        int before = s.memory.writes;
        var matches = s.registry.address("aDA", s.owner);
        assertEquals(AddressStatus.AMBIGUOUS, matches.status());
        assertEquals(2, matches.candidates().size()); assertFalse(matches.more());
        var exact = s.registry.address(b.actor().citizenId(), s.owner);
        assertEquals(AddressStatus.FOUND, exact.status());
        assertEquals(b.actor(), exact.candidates().getFirst().actor());
        assertEquals(before, s.memory.writes);
    }

    @Test void guessedForeignIdsAndNamesCannotQueryRenameOrControl() {
        var s = new Scene(); var citizen = s.enroll(); s.name(citizen, "Ada");
        int before = s.memory.writes;
        assertThrows(SecurityException.class, () -> s.registry.query(citizen.actor().citizenId(), s.foreign));
        assertEquals(Reason.AUTHORITY_DENIED, s.registry.rename(citizen.actor().citizenId(), "Mira", s.foreign).reason());
        assertEquals(AddressStatus.NOT_FOUND, s.registry.address("Ada", s.foreign).status());
        assertEquals(AddressStatus.NOT_FOUND, s.registry.address(citizen.actor().citizenId(), s.foreign).status());
        assertTrue(s.registry.citizens(s.foreign).citizens().isEmpty());
        assertFalse(s.registry.controls(citizen.actor(), s.foreign));
        assertEquals(before, s.memory.writes);
    }

    @Test void explicitAddressGrantDoesNotGrantPrivateRecordOrControl() {
        var s = new Scene((caller, citizen) -> true); var citizen = s.enroll();
        s.name(citizen, "Ada"); s.availability(citizen, Availability.LOADED);
        var visible = s.registry.address("Ada", s.foreign);
        assertEquals(AddressStatus.FOUND, visible.status());
        assertEquals(new Address(citizen.actor(), "Ada", Availability.LOADED), visible.candidates().getFirst());
        assertThrows(SecurityException.class, () -> s.registry.query(citizen.actor().citizenId(), s.foreign));
        assertFalse(s.registry.controls(citizen.actor(), s.foreign));
        assertEquals(Reason.AUTHORITY_DENIED, s.registry.rename(citizen.actor().citizenId(), "Mira", s.foreign).reason());
    }

    @Test void grantCannotLeakAddressesIntoAnotherWorld() {
        var s = new Scene((caller, citizen) -> true); var citizen = s.enroll(); s.name(citizen, "Ada");
        var elsewhere = new TrustedContext(s.owner.principal(), new ScopeRef(UUID.randomUUID(), s.owner.scope().domainId()));
        assertEquals(AddressStatus.NOT_FOUND, s.registry.address("Ada", elsewhere).status());
        assertEquals(Reason.AUTHORITY_DENIED, s.registry.enroll(UUID.randomUUID(), "minecraft:overworld", elsewhere).reason());
    }

    @Test void invalidNamesRejectBeforeStorageAndExactLengthWorks() {
        var s = new Scene(); var citizen = s.enroll(); int before = s.memory.writes;
        for (String name : List.of("", "   ", "a".repeat(33), "Ada\nMira", "\tAda", "§Ada", "Ada\0", "\ud800"))
            assertEquals(Reason.REQUEST_INVALID, s.registry.rename(citizen.actor().citizenId(), name, s.owner).reason());
        assertEquals(before, s.memory.writes);
        s.name(citizen, "a".repeat(32));
        assertEquals("a".repeat(32), s.registry.query(citizen.actor().citizenId(), s.owner).displayName());
    }

    @Test void normalizationAndUtf8BoundAreDeterministic() {
        var s = new Scene(); var citizen = s.enroll();
        s.name(citizen, "  E\u0301va   Ada  ");
        assertEquals("Éva Ada", s.registry.query(citizen.actor().citizenId(), s.owner).displayName());
        String letter = new String(Character.toChars(0x10400));
        s.name(citizen, letter.repeat(32));
        assertEquals(128, s.registry.query(citizen.actor().citizenId(), s.owner).displayName()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals(Reason.REQUEST_INVALID, s.registry.rename(citizen.actor().citizenId(), letter.repeat(33), s.owner).reason());
    }

    @Test void oldNamesAreNotUnlimitedAliases() {
        var s = new Scene(); var citizen = s.enroll(); s.name(citizen, "Ada"); s.name(citizen, "Mira");
        assertEquals(AddressStatus.NOT_FOUND, s.registry.address("Ada", s.owner).status());
        assertEquals(1, s.registry.address("Mira", s.owner).candidates().size());
        assertEquals(1, s.registry.snapshot().citizens().size());
    }

    @Test void concurrentEnrollmentHasOnePublicationAndIdempotentBinding() {
        var s = new Scene(); s.memory.hold = true; UUID entity = UUID.randomUUID();
        var first = s.registry.enroll(entity, "minecraft:overworld", s.owner);
        assertTrue(first.accepted()); assertTrue(first.pending());
        var race = s.registry.enroll(entity, "minecraft:overworld", s.foreign);
        assertEquals(Reason.BUDGET_EXHAUSTED, race.reason()); assertNull(race.citizen());
        assertEquals(1, s.memory.writes); s.memory.release(); s.registry.tick();
        var repeated = s.registry.enroll(entity, "minecraft:overworld", s.owner);
        assertTrue(repeated.accepted()); assertFalse(repeated.pending());
        assertEquals(first.citizen().actor(), repeated.citizen().actor());
        assertEquals(Reason.AUTHORITY_DENIED, s.registry.enroll(entity, "minecraft:overworld", s.foreign).reason());
        assertEquals(1, s.memory.writes); assertEquals(1, s.registry.snapshot().citizens().size());
    }

    @Test void unloadUnavailableAndReplacementStayDistinctWithoutRebinding() {
        var s = new Scene(); var citizen = s.enroll(); s.name(citizen, "Ada");
        for (Availability fact : List.of(Availability.UNLOADED, Availability.UNKNOWN,
                Availability.UNAVAILABLE, Availability.REPLACEMENT_UNRESOLVED, Availability.LOADED)) {
            s.availability(citizen, fact);
            var actual = s.registry.query(citizen.actor().citizenId(), s.owner);
            assertEquals(fact, actual.availability()); assertEquals(citizen.actor(), actual.actor());
            assertEquals(s.owner, actual.owner()); assertEquals("Ada", actual.displayName());
        }
        var replacement = s.enroll(); s.name(replacement, "Ada");
        assertNotEquals(citizen.actor().citizenId(), replacement.actor().citizenId());
        assertNotEquals(citizen.actor().entityId(), replacement.actor().entityId());
        assertEquals(citizen.actor(), s.registry.query(citizen.actor().citizenId(), s.owner).actor());
    }

    @Test void countAndLookupCapsKeepAmbiguityHonest() {
        var s = new Scene();
        assertTrue(s.registry.citizens(s.owner).citizens().isEmpty());
        assertEquals(AddressStatus.NOT_FOUND, s.registry.address("Ada", s.owner).status());
        Citizen last = null;
        for (int i = 0; i < MAX_CITIZENS; i++) {
            last = s.enroll(); s.name(last, "Ada");
            if (i + 1 == MAX_RESULTS) assertFalse(s.registry.address("Ada", s.owner).more());
            if (i + 1 == MAX_RESULTS + 1) assertTrue(s.registry.address("Ada", s.owner).more());
        }
        assertEquals(MAX_CITIZENS, s.registry.snapshot().citizens().size());
        int before = s.memory.writes;
        assertEquals(Reason.STORAGE_LIMIT_REACHED, s.registry.enroll(UUID.randomUUID(), "minecraft:overworld", s.owner).reason());
        var names = s.registry.address("Ada", s.owner);
        assertEquals(MAX_RESULTS, names.candidates().size()); assertTrue(names.more());
        assertEquals(AddressStatus.AMBIGUOUS, names.status());
        assertEquals(MAX_RESULTS, s.registry.citizens(s.owner).citizens().size());
        assertTrue(s.registry.citizens(s.owner).more());
        assertEquals(1, s.registry.address(last.actor().citizenId(), s.owner).candidates().size());
        assertEquals(before, s.memory.writes);
    }

    @Test void controlledEnumerationCoversFullFinitePopulationWithoutAddressGrantLeakage() {
        var s = new Scene((caller, citizen) -> true);
        for (int i = 0; i < MAX_RESULTS + 5; i++) s.enroll();
        assertEquals(MAX_RESULTS, s.registry.citizens(s.owner).citizens().size());
        assertTrue(s.registry.citizens(s.owner).more());
        assertEquals(MAX_RESULTS + 5, s.registry.controlled(s.owner).size());
        assertTrue(s.registry.controlled(s.foreign).isEmpty(),
                "Address visibility must not become control enumeration");
    }

    @Test void snapshotRejectsDuplicateForeignAndExcessiveBindings() {
        var s = new Scene(); var citizen = s.enroll();
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(s.memory.state.worldId(), 1, List.of(citizen, citizen), null));
        var sameEntity = new Citizen(new ActorRef(UUID.randomUUID(), citizen.actor().entityId(), citizen.actor().dimension()), s.owner, null, Availability.UNKNOWN);
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(s.memory.state.worldId(), 1, List.of(citizen, sameEntity), null));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(UUID.randomUUID(), 1, List.of(citizen), null));
        var rows = new ArrayList<Citizen>();
        for (int i = 0; i <= MAX_CITIZENS; i++) rows.add(new Citizen(new ActorRef(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld"), s.owner, null, Availability.UNKNOWN));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(s.memory.state.worldId(), 1, rows, null));
    }

    @Test void expiryCannotGrantControlFromLateEnrollmentCommit() {
        var s = new Scene(); s.memory.hold = true;
        var accepted = s.registry.enroll(UUID.randomUUID(), "minecraft:overworld", s.owner);
        s.clock.now += MAX_PUBLICATION_WAIT_MILLIS; s.registry.tick();
        assertEquals(Reason.STORAGE_UNAVAILABLE, s.registry.unavailableReason());
        s.memory.release(); s.registry.tick();
        assertTrue(s.registry.snapshot().citizens().isEmpty());
        assertFalse(s.registry.controls(accepted.citizen().actor(), s.owner));
        assertEquals(1, s.memory.state.citizens().size());
        assertEquals(Reason.AUTHORITY_DENIED, s.registry.rename(accepted.citizen().actor().citizenId(), "Ada", s.owner).reason());
        assertEquals(Reason.STORAGE_UNAVAILABLE, s.registry.enroll(UUID.randomUUID(), "minecraft:overworld", s.owner).reason());
    }

    @Test void completionAtDeadlineCanBePolledLater() {
        var s = new Scene(); s.memory.hold = true;
        var accepted = s.registry.enroll(UUID.randomUUID(), "minecraft:overworld", s.owner);
        s.clock.now += MAX_PUBLICATION_WAIT_MILLIS; s.memory.release(); s.clock.now += 100;
        s.registry.tick(); assertTrue(s.registry.ready());
        assertEquals(accepted.citizen().actor(), s.registry.query(accepted.citizen().actor().citizenId(), s.owner).actor());
    }

    @Test void completionAfterDeadlineRejectsBeforeAnExpiryPoll() {
        var s = new Scene(); s.memory.hold = true;
        var accepted = s.registry.enroll(UUID.randomUUID(), "minecraft:overworld", s.owner);
        s.clock.now += MAX_PUBLICATION_WAIT_MILLIS + 1; s.memory.release(); s.registry.tick();
        assertFalse(s.registry.ready()); assertTrue(s.registry.snapshot().citizens().isEmpty());
        assertFalse(s.registry.controls(accepted.citizen().actor(), s.owner));
    }

    @Test void failedRenamePreservesPublishedNameAndFencesWork() {
        var s = new Scene(); var citizen = s.enroll(); s.name(citizen, "Ada");
        s.memory.fail = true;
        assertTrue(s.registry.rename(citizen.actor().citizenId(), "Mira", s.owner).accepted()); s.registry.tick();
        assertEquals("Ada", s.registry.query(citizen.actor().citizenId(), s.owner).displayName());
        assertEquals(Reason.STORAGE_UNAVAILABLE, s.registry.unavailableReason());
        assertFalse(s.registry.controls(citizen.actor(), s.owner));
    }

    @Test void overflowingDeadlineRejectsWithoutDispatch() {
        var s = new Scene(); s.clock.now = Long.MAX_VALUE - MAX_PUBLICATION_WAIT_MILLIS + 1;
        assertEquals(Reason.STORAGE_UNAVAILABLE, s.registry.enroll(UUID.randomUUID(), "minecraft:overworld", s.owner).reason());
        assertEquals(0, s.memory.writes); assertTrue(s.registry.snapshot().citizens().isEmpty());
    }

    @Test void mismatchedAcknowledgementCannotChangeOwner() {
        var s = new Scene(); s.memory.hold = true;
        var accepted = s.registry.enroll(UUID.randomUUID(), "minecraft:overworld", s.owner);
        var foreign = new Citizen(accepted.citizen().actor(), s.foreign, "Ada", Availability.LOADED);
        s.memory.held.complete(new Snapshot(s.memory.state.worldId(), 1, List.of(foreign), null)); s.registry.tick();
        assertTrue(s.registry.snapshot().citizens().isEmpty()); assertFalse(s.registry.ready());
        assertFalse(s.registry.controls(foreign.actor(), s.foreign));
    }

    @Test void recoveredReadOnlyRecordsRemainQueryOnly() {
        var s = new Scene(); var citizen = s.enroll(); s.name(citizen, "Ada");
        int before = s.memory.writes;
        var recovered = new CitizenRegistry(s.memory.state, s.memory, privateAddresses(), s.clock, true);
        assertEquals("Ada", recovered.query(citizen.actor().citizenId(), s.owner).displayName());
        assertEquals(Reason.STORAGE_UNAVAILABLE, recovered.rename(citizen.actor().citizenId(), "Mira", s.owner).reason());
        assertFalse(recovered.controls(citizen.actor(), s.owner)); assertEquals(before, s.memory.writes);
    }

    @Test void backgroundCallbacksCannotMutateGameThreadViews() {
        var s = new Scene(); s.memory.hold = true;
        var accepted = s.registry.enroll(UUID.randomUUID(), "minecraft:overworld", s.owner);
        CompletableFuture.runAsync(s.memory::release).join();
        assertTrue(s.registry.snapshot().citizens().isEmpty()); s.registry.tick();
        assertEquals(accepted.citizen().actor(), s.registry.query(accepted.citizen().actor().citizenId(), s.owner).actor());
        var wrongThread = CompletableFuture.runAsync(s.registry::tick);
        assertThrows(java.util.concurrent.CompletionException.class, wrongThread::join);
    }
}
