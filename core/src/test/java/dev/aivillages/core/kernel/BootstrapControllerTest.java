package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.Outcomes.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;

final class BootstrapControllerTest {
    private static final ArtifactRef REF = new ArtifactRef(CropDelivery.ID, "a".repeat(64));

    private static final class TestClock extends Clock {
        long now = 1_000;
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }

    private static final class Memory implements BootstrapController.Storage {
        BootstrapJournal.State state = new BootstrapJournal.State(UUID.randomUUID(), 0, null, List.of());
        int writes;
        boolean failNext, holdNext;
        BootstrapJournal.State heldState;
        CompletableFuture<BootstrapJournal.State> held;
        @Override public CompletionStage<BootstrapJournal.State> replace(BootstrapJournal.State expected,
                BootstrapJournal.Enrollment enrollment, List<BootstrapJournal.RunMarker> runs) {
            assertEquals(state, expected);
            writes++;
            if (failNext) {
                failNext = false;
                return CompletableFuture.failedFuture(new java.io.IOException("injected write failure"));
            }
            if (holdNext) {
                holdNext = false;
                heldState = new BootstrapJournal.State(state.worldId(), state.revision() + 1,
                        enrollment, runs, state.externalIdentities());
                held = new CompletableFuture<>();
                return held;
            }
            state = new BootstrapJournal.State(state.worldId(), state.revision() + 1,
                    enrollment, runs, state.externalIdentities());
            return CompletableFuture.completedFuture(state);
        }
        void release() { state = heldState; held.complete(state); }
    }
    private static final class Scene {
        final Memory memory = new Memory();
        final int[] modelStarts = {0}, executionStarts = {0}, resolutions = {0};
        final TestClock clock = new TestClock();
        final PrincipalRef principal = new PrincipalRef(UUID.randomUUID());
        final BootstrapController controller;
        final CitizenRegistry registry;
        final CitizenRegistryTest.Memory identityMemory;
        ActorRef actor;
        TrustedContext owner;
        boolean known;
        ResolutionStatus routeStatus;
        Reason routeReason;
        boolean delayed;
        boolean delayedExecution;
        Scene() { this(false); }
        Scene(boolean registryBacked) {
            if (registryBacked) {
                memory.state = new BootstrapJournal.State(memory.state.worldId(), 0, null, List.of(), true);
                actor = new ActorRef(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld");
                owner = new TrustedContext(principal, new ScopeRef(memory.state.worldId(), principal.id()));
                identityMemory = new CitizenRegistryTest.Memory();
                identityMemory.state = new CitizenRegistry.Snapshot(memory.state.worldId(), 1,
                        List.of(new CitizenRegistry.Citizen(actor, owner, "Ada", CitizenRegistry.Availability.LOADED)),
                        new CitizenRegistry.Migration(0, "0".repeat(64), actor.citizenId()));
                registry = new CitizenRegistry(identityMemory.state, identityMemory,
                        CitizenRegistry.privateAddresses(), clock, false);
            } else { registry = null; identityMemory = null; }
            controller = new BootstrapController(memory.state, memory,
                    (request, enrolled) -> new ObservationSnapshot(new ObservationRef(
                            UUID.randomUUID(), 1, enrolled.dimension()),
                            ObservationStatus.PRESENT, "source:" + enrolled.entityId(),
                            1, 1, Map.of("mature_wheat", 2L)),
                    (bound, snapshot) -> {
                        resolutions[0]++;
                        Resolution route = routeStatus != null
                                ? new Resolution(routeStatus, routeReason, null,
                                        snapshot.targetIdentity()) : known
                                ? new Resolution(ResolutionStatus.RESOLVED, null, REF,
                                        snapshot.targetIdentity())
                                : new Resolution(ResolutionStatus.MISSING_IMPLEMENTATION, null,
                                        null, snapshot.targetIdentity());
                        return new CapabilityResolver.Decision(route,
                                known ? bound : null, java.util.Set.of(), snapshot, 0,
                                List.of(), 1, 0, true, false, null);
                    },
                    (route, bound, limits) -> {
                        modelStarts[0]++;
                        return new BootstrapController.Research.Handle() {
                            ResearchAdmissionController.Status result() {
                                return new ResearchAdmissionController.Status(
                                        ResearchAdmissionController.Phase.TERMINAL,
                                        new Outcomes.Research(ResearchStatus.ADMITTED, null, REF,
                                                new EvidenceRef("trial", "test:1", "fake"), true, 1),
                                        REF, 3, List.of(), Map.of(), List.of());
                            }
                            @Override public ResearchAdmissionController.Status tick(TrustedContext owner) {
                                return delayed ? new ResearchAdmissionController.Status(
                                        ResearchAdmissionController.Phase.GENERATING, null, null,
                                        0, List.of(), Map.of(), List.of()) : result();
                            }
                            @Override public ResearchAdmissionController.Status status(TrustedContext owner) {
                                return result();
                            }
                            @Override public ResearchAdmissionController.Status cancel(TrustedContext owner) {
                                delayed = false;
                                return new ResearchAdmissionController.Status(
                                        ResearchAdmissionController.Phase.TERMINAL,
                                        new Outcomes.Research(ResearchStatus.CANCELLED, Reason.CANCELLED,
                                                null, null, false, 1), null, 0,
                                        List.of(), Map.of(), List.of());
                            }
                        };
                    },
                    (bound, ref, id, limits, usage) -> {
                        executionStarts[0]++;
                        BootstrapController.Execution.Handle handle = new BootstrapController.Execution.Handle() {
                            BoundedSkillExecutor.Progress terminal(ExecutionStatus status) {
                                long effects = status == ExecutionStatus.SUCCEEDED ? 3 : 2;
                                var outcome = new Outcomes.Execution(status,
                                        status == ExecutionStatus.SUCCEEDED ? null
                                                : status == ExecutionStatus.CANCELLED
                                                        ? Reason.CANCELLED : Reason.INTERRUPTED, effects,
                                        status == ExecutionStatus.SUCCEEDED
                                                ? new EvidenceRef("run", "test:1", "fake") : null);
                                return new BoundedSkillExecutor.Progress(BoundedSkillExecutor.Phase.TERMINAL,
                                        new BoundedSkillExecutor.Summary(id, ref, null,
                                                List.of(ref), effects, List.of(), outcome, Map.of()), List.of());
                            }
                            @Override public BoundedSkillExecutor.Progress tick(TrustedContext caller) {
                                return delayedExecution ? pending() : terminal(ExecutionStatus.SUCCEEDED);
                            }
                            @Override public BoundedSkillExecutor.Progress progress(TrustedContext caller) {
                                return delayedExecution ? pending() : terminal(ExecutionStatus.SUCCEEDED);
                            }
                            @Override public BoundedSkillExecutor.Progress cancel(TrustedContext caller) {
                                delayedExecution = false;
                                return terminal(ExecutionStatus.CANCELLED);
                            }
                            @Override public BoundedSkillExecutor.Progress interrupt(TrustedContext caller) {
                                return terminal(ExecutionStatus.INTERRUPTED);
                            }
                            BoundedSkillExecutor.Progress pending() {
                                return new BoundedSkillExecutor.Progress(BoundedSkillExecutor.Phase.RUNNING,
                                        new BoundedSkillExecutor.Summary(id, ref, null,
                                                List.of(ref), 2, List.of(), null, Map.of()), List.of());
                            }
                        };
                        return new BootstrapController.Execution.Start(handle, null);
                    },
                    new RequestEnvironment() {
                        @Override public boolean enrolled(ActorRef actor, TrustedContext context) {
                            return actor.equals(Scene.this.actor) && context.equals(owner);
                        }
                        @Override public boolean loaded(Cuboid source, ObservationRef observation) {
                            return true;
                        }
                        @Override public boolean available(ContainerRef destination,
                                                           ObservationRef observation) { return true; }
                    }, id -> id.equals(CropDelivery.ID) ? java.util.Optional.of(CropDelivery.SPEC)
                            : java.util.Optional.empty(),
                    now -> {
                        var calls = new Budgets.Limits(Map.of(Budgets.Kind.CALLS, 2L,
                                Budgets.Kind.REPAIRS, 1L, Budgets.Kind.INPUT_BYTES, 16_384L,
                                Budgets.Kind.OUTPUT_BYTES, 4_096L), now + 600_000);
                        var trial = new Budgets.ExecutionLimits(new Budgets.Limits(
                                Map.of(Budgets.Kind.INSTRUCTIONS, 10L), now + 600_000), 10_000);
                        return new Budgets.ResearchLimits(new Budgets.InferenceLimits(calls,
                                16_384, 4_096), trial, new Budgets.Limits(Map.of(
                                Budgets.Kind.CALLS, 2L, Budgets.Kind.REPAIRS, 1L,
                                Budgets.Kind.INPUT_BYTES, 16_384L, Budgets.Kind.OUTPUT_BYTES, 4_096L,
                                Budgets.Kind.INSTRUCTIONS, 10L), now + 600_000));
                    }, now -> new Budgets.ExecutionLimits(new Budgets.Limits(
                            Map.of(Budgets.Kind.INSTRUCTIONS, 10L), now + 600_000), 10_000), clock, registry);
        }
        void enroll() {
            if (registry != null) return;
            actor = controller.enroll(UUID.randomUUID(), "minecraft:overworld", principal);
            controller.tick();
            owner = controller.enrollment().owner();
        }
        CapabilityRequest request(long amount) {
            return new CapabilityRequest(CropDelivery.ID,
                    Map.of("actor", new ActorValue(actor), "amount", new IntValue(amount),
                            "source", new AreaValue(new Cuboid(actor.dimension(), 0, 64, 0,
                                    1, 64, 1)), "destination", new ContainerValue(
                                            new ContainerRef(actor.dimension(), 5, 64, 0))));
        }
        BootstrapController.View complete(UUID id) {
            for (int n = 0; n < 8; n++) controller.tick();
            return controller.status(id, owner);
        }
    }

    @Test void registryRenamePreservesKnownExecutionAndCannotReviveCancellation() {
        var scene = new Scene(true); scene.known = true;
        var actor = scene.actor; var owner = scene.owner;
        var first = scene.controller.submit(scene.request(1), owner);
        var completed = scene.complete(first.id());
        assertEquals(REF.sha256(), completed.marker().artifactSha256());
        assertEquals(3, completed.marker().effects());
        assertTrue(scene.registry.rename(actor.citizenId(), "Mira", owner).accepted()); scene.registry.tick();
        assertEquals(actor, scene.registry.query(actor.citizenId(), owner).actor());
        assertEquals(owner, scene.registry.query(actor.citizenId(), owner).owner());
        assertEquals(completed, scene.controller.status(first.id(), owner));
        scene.delayedExecution = true;
        var second = scene.controller.submit(scene.request(2), owner); scene.controller.tick();
        scene.controller.cancel(second.id(), owner); var cancelled = scene.complete(second.id());
        assertEquals("CANCELLED", cancelled.marker().outcome());
        assertEquals(2, cancelled.marker().effects());
        assertTrue(scene.registry.rename(actor.citizenId(), "Ada", owner).accepted()); scene.registry.tick();
        for (int i = 0; i < 40; i++) { scene.registry.tick(); scene.controller.tick(); }
        assertEquals(cancelled, scene.controller.status(second.id(), owner));
        assertEquals(2, scene.executionStarts[0]); assertEquals(0, scene.modelStarts[0]);
        assertNull(scene.memory.state.enrollment()); assertTrue(scene.memory.state.externalIdentities());
    }

    @Test void runIdReadsAreExactScopedAndSeparateRetainedFromCancellableRuns() {
        var scene = new Scene(true); scene.known = true;
        var ownerA = scene.owner; var actorA = scene.actor;
        var ownerB = CitizenRegistryTest.caller(scene.memory.state.worldId());
        var enrolled = scene.registry.enroll(UUID.randomUUID(), "minecraft:overworld", ownerB);
        assertTrue(enrolled.accepted()); scene.registry.tick();
        var first = scene.controller.submit(scene.request(1), ownerA);
        assertTrue(first.accepted()); scene.complete(first.id());
        scene.actor = enrolled.citizen().actor(); scene.owner = ownerB;
        var second = scene.controller.submit(scene.request(1), ownerB);
        assertTrue(second.accepted()); scene.complete(second.id());
        scene.actor = actorA; scene.owner = ownerA; scene.delayedExecution = true;
        var current = scene.controller.submit(scene.request(1), ownerA);
        assertTrue(current.accepted()); scene.controller.tick(); scene.controller.tick();
        List<UUID> retained = scene.controller.runIds(ownerA);
        List<UUID> cancellable = scene.controller.cancellableRunIds(ownerA);
        assertEquals(List.of(first.id(), current.id()), retained);
        assertEquals(List.of(second.id()), scene.controller.runIds(ownerB));
        assertEquals(List.of(current.id()), cancellable);
        assertTrue(scene.controller.cancellableRunIds(ownerB).isEmpty());
        var otherScope = new TrustedContext(ownerA.principal(),
                new ScopeRef(ownerA.scope().worldId(), UUID.randomUUID()));
        assertTrue(scene.controller.runIds(otherScope).isEmpty());
        assertTrue(scene.controller.cancellableRunIds(otherScope).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> retained.add(UUID.randomUUID()));
        assertThrows(UnsupportedOperationException.class, () -> cancellable.clear());
        scene.controller.cancel(current.id(), ownerA); scene.complete(current.id());
        assertTrue(scene.controller.cancellableRunIds(ownerA).isEmpty());
        assertEquals(List.of(first.id(), current.id()), scene.controller.runIds(ownerA));
        assertEquals(List.of(current.id()), cancellable);
        assertEquals(0, scene.modelStarts[0]);
    }

    @Test void finishingWriteIsRetainedForStatusButNotSuggestedForCancellation() {
        var scene = new Scene(true); scene.known = true; scene.delayedExecution = true;
        var started = scene.controller.submit(scene.request(1), scene.owner);
        assertTrue(started.accepted()); scene.controller.tick(); scene.controller.tick();
        assertEquals(BootstrapController.Phase.EXECUTING, scene.controller.status(started.id(), scene.owner).phase());
        scene.memory.holdNext = true; scene.delayedExecution = false; scene.controller.tick();
        assertEquals(BootstrapController.Phase.PERSISTING, scene.controller.status(started.id(), scene.owner).phase());
        assertEquals(List.of(started.id()), scene.controller.runIds(scene.owner));
        assertTrue(scene.controller.cancellableRunIds(scene.owner).isEmpty());
        scene.memory.release();
        assertEquals("SUCCEEDED", scene.complete(started.id()).marker().outcome());
        assertTrue(scene.controller.cancellableRunIds(scene.owner).isEmpty());
    }

    @Test void runIdReadsPreserveRetentionLimitsAndExcludeAlreadyRequestedCancellation() {
        var scene = new Scene(true); scene.known = true;
        UUID first = null;
        for (int i = 0; i < BootstrapJournal.MAX_RUNS + 2; i++) {
            var submitted = scene.controller.submit(scene.request(1), scene.owner);
            assertTrue(submitted.accepted());
            if (first == null) first = submitted.id();
            scene.complete(submitted.id());
        }
        List<UUID> retained = scene.controller.runIds(scene.owner);
        assertEquals(BootstrapJournal.MAX_RUNS, retained.size());
        assertFalse(retained.contains(first));
        assertTrue(scene.controller.cancellableRunIds(scene.owner).isEmpty());
        var current = scene.controller.submit(scene.request(1), scene.owner);
        assertTrue(current.accepted());
        assertEquals(BootstrapJournal.MAX_RUNS + 1, scene.controller.runIds(scene.owner).size());
        assertEquals(List.of(current.id()), scene.controller.cancellableRunIds(scene.owner));
        scene.controller.cancel(current.id(), scene.owner);
        assertTrue(scene.controller.cancellableRunIds(scene.owner).isEmpty());
        assertTrue(scene.controller.runIds(scene.owner).contains(current.id()));
        assertFalse(retained.contains(current.id()));
        assertEquals("CANCELLED", scene.complete(current.id()).marker().outcome());
    }

    @Test void runIdReadsRejectWorkerThreadCalls() {
        var scene = new Scene(true);
        CompletableFuture.runAsync(() -> {
            assertThrows(IllegalStateException.class, () -> scene.controller.runIds(scene.owner));
            assertThrows(IllegalStateException.class, () -> scene.controller.cancellableRunIds(scene.owner));
        }).join();
    }

    @Test void registryScopesEachRunAcrossSerialCitizensAndKeepsOneActiveAttempt() {
        var scene = new Scene(true); scene.known = true;
        var ownerA = scene.owner; var actorA = scene.actor;
        var ownerB = CitizenRegistryTest.caller(scene.memory.state.worldId());
        var enrolled = scene.registry.enroll(UUID.randomUUID(), "minecraft:overworld", ownerB);
        assertTrue(enrolled.accepted()); scene.registry.tick();
        var first = scene.controller.submit(scene.request(1), ownerA);
        scene.actor = enrolled.citizen().actor(); scene.owner = ownerB;
        assertEquals(Reason.BUDGET_EXHAUSTED, scene.controller.submit(scene.request(1), ownerB).reason());
        scene.actor = actorA; scene.owner = ownerA; scene.complete(first.id());
        scene.actor = enrolled.citizen().actor(); scene.owner = ownerB;
        var second = scene.controller.submit(scene.request(1), ownerB); scene.complete(second.id());
        assertEquals(3, scene.controller.status(first.id(), ownerA).marker().effects());
        assertEquals(3, scene.controller.status(second.id(), ownerB).marker().effects());
        assertThrows(SecurityException.class, () -> scene.controller.status(first.id(), ownerB));
        assertThrows(SecurityException.class, () -> scene.controller.cancel(second.id(), ownerA));
        assertEquals(2, scene.memory.state.runs().size());
        assertEquals(0, scene.controller.modelCalls(ownerA)); assertEquals(0, scene.controller.modelCalls(ownerB));
        assertEquals(0, scene.modelStarts[0]);
    }

    @Test void registryModeRefusesEnrollmentAndMismatchedSchemaAcknowledgement() {
        var scene = new Scene(true); scene.known = true;
        assertThrows(IllegalStateException.class, () -> scene.controller.enroll(UUID.randomUUID(),
                "minecraft:overworld", scene.principal));
        assertEquals(0, scene.memory.writes);
        scene.memory.holdNext = true;
        var submitted = scene.controller.submit(scene.request(1), scene.owner);
        var forged = new BootstrapJournal.State(scene.memory.state.worldId(), 1,
                new BootstrapJournal.Enrollment(scene.actor, scene.owner), scene.memory.heldState.runs());
        scene.memory.held.complete(forged); scene.controller.tick();
        assertEquals(Reason.STORAGE_UNAVAILABLE, scene.controller.status(submitted.id(), scene.owner).storageError());
        assertFalse(scene.controller.ready()); assertEquals(0, scene.executionStarts[0]);
        assertEquals(0, scene.modelStarts[0]);
    }

    @Test void modelGateAndKnownReuseDoNotGenerate() {
        Scene scene = new Scene(); scene.enroll();
        var blocked = scene.controller.submit(scene.request(1), scene.owner);
        assertTrue(blocked.accepted());
        var first = scene.complete(blocked.id());
        assertEquals("BLOCKED", first.marker().outcome());
        assertEquals(Reason.MODEL_UNAVAILABLE, first.marker().reason());
        assertEquals(0, scene.modelStarts[0]);
        scene.known = true;
        var reuse = scene.controller.submit(scene.request(2), scene.owner);
        assertEquals("SUCCEEDED", scene.complete(reuse.id()).marker().outcome());
        assertEquals(1, scene.executionStarts[0]);
        assertEquals(0, scene.modelStarts[0]);
        assertEquals(3, scene.controller.status(reuse.id(), scene.owner).marker().effects());
    }

    @Test void researchTrialFulfillsOnceAndRejectsForeignStatus() {
        Scene scene = new Scene(); scene.enroll(); scene.controller.inference(true);
        var started = scene.controller.submit(scene.request(1), scene.owner);
        assertFalse(scene.controller.submit(scene.request(1), scene.owner).accepted());
        var completed = scene.complete(started.id());
        assertEquals("ADMITTED", completed.marker().outcome());
        assertEquals(1, completed.marker().modelCalls());
        assertEquals(3, completed.marker().effects());
        assertEquals(1, scene.modelStarts[0]);
        assertEquals(0, scene.executionStarts[0]);
        assertThrows(SecurityException.class, () -> scene.controller.status(started.id(),
                new TrustedContext(new PrincipalRef(UUID.randomUUID()), scene.owner.scope())));
        assertFalse(scene.controller.submit(scene.request(0), scene.owner).accepted());
    }

    @Test void cancelBeforeDurableMarkerPreventsRoute() {
        Scene scene = new Scene(); scene.enroll(); scene.controller.inference(true);
        var started = scene.controller.submit(scene.request(1), scene.owner);
        scene.controller.cancel(started.id(), scene.owner);
        var done = scene.complete(started.id());
        assertEquals("CANCELLED", done.marker().outcome());
        assertEquals(0, scene.modelStarts[0]);
        assertEquals(0, scene.executionStarts[0]);
    }

    @Test void disableDuringDelayedResearchCancelsWithoutExecution() {
        Scene scene = new Scene(); scene.enroll(); scene.controller.inference(true);
        scene.delayed = true;
        var started = scene.controller.submit(scene.request(1), scene.owner);
        scene.controller.tick(); // durable active marker and research start
        scene.controller.tick();
        scene.controller.inference(false);
        var done = scene.complete(started.id());
        assertEquals("CANCELLED", done.marker().outcome());
        assertEquals(Reason.CANCELLED, done.marker().reason());
        assertEquals(1, scene.modelStarts[0]);
        assertEquals(0, scene.executionStarts[0]);
        assertFalse(scene.controller.inferenceEnabled());
    }

    @Test void everyOtherRoutingOutcomePreservesReasonWithoutResearch() {
        Map<ResolutionStatus, Reason> statuses = Map.of(
                ResolutionStatus.BLOCKED, Reason.RESOURCE_MISSING,
                ResolutionStatus.UNSUPPORTED_RUNTIME, Reason.UNSUPPORTED_PRIMITIVE,
                ResolutionStatus.INCOMPATIBLE, Reason.ARTIFACT_INCOMPATIBLE,
                ResolutionStatus.UNAUTHORIZED, Reason.AUTHORITY_DENIED);
        for (var entry : statuses.entrySet()) {
            Scene scene = new Scene(); scene.enroll(); scene.controller.inference(true);
            scene.routeStatus = entry.getKey(); scene.routeReason = entry.getValue();
            var submitted = scene.controller.submit(scene.request(1), scene.owner);
            var done = scene.complete(submitted.id());
            assertEquals(entry.getKey(), done.routing().status());
            assertEquals(entry.getValue(), done.marker().reason());
            assertEquals(entry.getKey().name(), done.marker().outcome());
            assertEquals(0, scene.modelStarts[0]);
            assertEquals(0, scene.executionStarts[0]);
        }
        Scene broad = new Scene(); broad.enroll(); broad.controller.inference(true);
        broad.routeStatus = ResolutionStatus.NEEDS_PLANNING;
        var submitted = broad.controller.submit(broad.request(1), broad.owner);
        assertEquals("NEEDS_PLANNING", broad.complete(submitted.id()).marker().outcome());
        assertEquals(0, broad.modelStarts[0]);
    }

    @Test void failedActiveMarkerNeverRoutesOrClaimsDurableCompletion() {
        Scene scene = new Scene(); scene.enroll(); scene.controller.inference(true);
        scene.memory.failNext = true;
        var started = scene.controller.submit(scene.request(1), scene.owner);
        scene.controller.tick();
        var view = scene.controller.status(started.id(), scene.owner);
        assertEquals(Reason.STORAGE_UNAVAILABLE, view.storageError());
        assertEquals(0, scene.modelStarts[0]);
        assertEquals(0, scene.executionStarts[0]);
        assertFalse(scene.controller.ready());
        assertTrue(scene.memory.state.runs().isEmpty());
    }

    @Test void activeMarkerReloadIsInterruptedBeforeNewWorkAndNeverReplayed() {
        Scene scene = new Scene(); scene.enroll();
        var enrollment = scene.memory.state.enrollment();
        var runId = UUID.randomUUID();
        scene.memory.state = new BootstrapJournal.State(scene.memory.state.worldId(),
                scene.memory.state.revision(), enrollment, List.of(
                        new BootstrapJournal.RunMarker(runId, scene.actor.citizenId(),
                                BootstrapJournal.Phase.ACTIVE, null, null, 2, 1,
                                REF.sha256())));
        var reloaded = new BootstrapController(scene.memory.state, scene.memory,
                (request, actor) -> { throw new AssertionError("No replay observation"); },
                (bound, snapshot) -> { throw new AssertionError("No replay routing"); },
                (decision, bound, limits) -> { throw new AssertionError("No replay model"); },
                (bound, ref, id, limits, usage) -> { throw new AssertionError("No replay execution"); },
                new RequestEnvironment() {
                    public boolean enrolled(ActorRef actor, TrustedContext context) { return false; }
                    public boolean loaded(Cuboid area, ObservationRef reference) { return false; }
                    public boolean available(ContainerRef target, ObservationRef reference) { return false; }
                }, id -> java.util.Optional.empty(), now -> { throw new AssertionError(); },
                now -> { throw new AssertionError(); }, Clock.systemUTC());
        assertFalse(reloaded.ready());
        reloaded.tick();
        var view = reloaded.status(runId, enrollment.owner());
        assertEquals(BootstrapJournal.Phase.INTERRUPTED, view.marker().phase());
        assertEquals(Reason.INTERRUPTED, view.marker().reason());
        assertEquals(2, view.marker().effects());
        assertEquals(1, view.marker().modelCalls());
        assertTrue(reloaded.ready());
    }

    @Test void malformedBindingsRejectBeforeRoutingOrEffects() {
        Scene scene = new Scene(); scene.enroll(); scene.controller.inference(true);
        for (long amount : List.of(-1L, 0L, 65L, Long.MAX_VALUE)) {
            var rejected = scene.controller.submit(scene.request(amount), scene.owner);
            assertFalse(rejected.accepted());
            assertEquals(Reason.REQUEST_INVALID, rejected.reason());
        }
        var other = new ActorRef(UUID.randomUUID(), UUID.randomUUID(), scene.actor.dimension());
        var wrongActor = new CapabilityRequest(CropDelivery.ID, Map.of(
                "actor", new ActorValue(other), "amount", new IntValue(1),
                "source", scene.request(1).arguments().get("source"),
                "destination", scene.request(1).arguments().get("destination")));
        assertEquals(Reason.REQUEST_INVALID,
                scene.controller.submit(wrongActor, scene.owner).reason());
        var wrongDimension = new CapabilityRequest(CropDelivery.ID, Map.of(
                "actor", new ActorValue(scene.actor), "amount", new IntValue(1),
                "source", new AreaValue(new Cuboid("minecraft:the_nether", 0, 64, 0,
                        1, 64, 1)),
                "destination", scene.request(1).arguments().get("destination")));
        assertEquals(Reason.REQUEST_INVALID,
                scene.controller.submit(wrongDimension, scene.owner).reason());
        assertThrows(IllegalArgumentException.class, () -> new Cuboid(scene.actor.dimension(),
                Integer.MIN_VALUE, 64, 0, Integer.MAX_VALUE, 64, 0));
        assertEquals(0, scene.resolutions[0]);
        assertEquals(0, scene.modelStarts[0]);
        assertEquals(0, scene.executionStarts[0]);
        assertTrue(scene.memory.state.runs().isEmpty());
    }

    @Test void cancellationRetainsProgressAndBlocksFurtherExecution() {
        Scene scene = new Scene(); scene.enroll(); scene.known = true;
        scene.delayedExecution = true;
        var started = scene.controller.submit(scene.request(1), scene.owner);
        scene.controller.tick(); // durable marker, routing
        scene.controller.tick(); // pending executor slice
        assertEquals(BootstrapController.Phase.EXECUTING,
                scene.controller.status(started.id(), scene.owner).phase());
        scene.controller.cancel(started.id(), scene.owner);
        var completed = scene.complete(started.id());
        assertEquals("CANCELLED", completed.marker().outcome());
        assertEquals(2, completed.marker().effects());
        assertEquals(1, scene.executionStarts[0]);
        for (int n = 0; n < 10; n++) scene.controller.tick();
        assertEquals(1, scene.executionStarts[0]);
    }

    @Test void activeWriteExpiresAtBoundAndLateAcknowledgementCannotRoute() {
        Scene scene = new Scene(); scene.enroll(); scene.controller.inference(true);
        scene.memory.holdNext = true;
        var submitted = scene.controller.submit(scene.request(1), scene.owner);
        scene.clock.now += BootstrapController.MAX_STORAGE_WAIT_MILLIS - 1;
        scene.controller.tick();
        assertEquals(BootstrapController.Phase.PERSISTING,
                scene.controller.status(submitted.id(), scene.owner).phase());
        scene.clock.now++;
        scene.controller.tick();
        var expired = scene.controller.status(submitted.id(), scene.owner);
        assertEquals(BootstrapController.Phase.TERMINAL, expired.phase());
        assertEquals(Reason.STORAGE_UNAVAILABLE, expired.storageError());
        assertTrue(scene.memory.state.runs().isEmpty());
        assertFalse(scene.controller.ready());
        scene.memory.release(); // The worker may commit after abandonment.
        for (int i = 0; i < 8; i++) scene.controller.tick();
        assertEquals(expired, scene.controller.status(submitted.id(), scene.owner));
        assertFalse(scene.controller.submit(scene.request(1), scene.owner).accepted());
        assertEquals(0, scene.resolutions[0]);
        assertEquals(0, scene.modelStarts[0]);
        assertEquals(0, scene.executionStarts[0]);
        assertEquals(2, scene.memory.writes);
    }

    @Test void acknowledgementAtDeadlineRemainsValidWhenPolledLater() {
        Scene scene = new Scene(); scene.enroll(); scene.known = true;
        scene.memory.holdNext = true;
        var submitted = scene.controller.submit(scene.request(1), scene.owner);
        scene.clock.now += BootstrapController.MAX_STORAGE_WAIT_MILLIS;
        scene.memory.release();
        scene.clock.now++; // Completion time, not the next tick, owns the boundary.
        scene.controller.tick();
        assertEquals(BootstrapController.Phase.EXECUTING,
                scene.controller.status(submitted.id(), scene.owner).phase());
        assertEquals("SUCCEEDED", scene.complete(submitted.id()).marker().outcome());
        assertTrue(scene.controller.ready());
        assertEquals(1, scene.executionStarts[0]);
    }

    @Test void acknowledgementAfterDeadlineCannotStartExecutionBeforeExpiryPoll() {
        Scene scene = new Scene(); scene.enroll(); scene.known = true;
        scene.memory.holdNext = true;
        var submitted = scene.controller.submit(scene.request(1), scene.owner);
        scene.clock.now += BootstrapController.MAX_STORAGE_WAIT_MILLIS + 1;
        scene.memory.release();
        scene.controller.tick();
        assertEquals(Reason.STORAGE_UNAVAILABLE,
                scene.controller.status(submitted.id(), scene.owner).storageError());
        assertEquals(0, scene.executionStarts[0]);
        assertFalse(scene.controller.ready());
    }

    @Test void terminalWriteExpiryRetainsFulfillmentAndFencesLateCommit() {
        Scene scene = new Scene(); scene.enroll(); scene.known = true;
        var submitted = scene.controller.submit(scene.request(1), scene.owner);
        scene.controller.tick(); // Active marker and admitted execution.
        scene.memory.holdNext = true;
        scene.controller.tick(); // Actual fulfillment, pending terminal marker.
        var pending = scene.controller.status(submitted.id(), scene.owner);
        assertEquals(BootstrapController.Phase.PERSISTING, pending.phase());
        assertEquals(ExecutionStatus.SUCCEEDED, pending.outcome().status());
        assertEquals(3, pending.outcome().committedEffects());
        scene.clock.now += BootstrapController.MAX_STORAGE_WAIT_MILLIS;
        scene.controller.tick();
        var expired = scene.controller.status(submitted.id(), scene.owner);
        assertEquals(pending.outcome(), expired.outcome());
        assertEquals(pending.marker(), expired.marker());
        assertEquals(0, expired.marker().effects());
        assertEquals(Reason.STORAGE_UNAVAILABLE, expired.storageError());
        assertEquals(3, expired.outcome().committedEffects());
        assertFalse(scene.controller.ready());
        scene.memory.release();
        for (int i = 0; i < 8; i++) scene.controller.tick();
        assertEquals(expired, scene.controller.cancel(submitted.id(), scene.owner));
        assertEquals("SUCCEEDED", scene.memory.state.runs().getFirst().outcome());
        assertEquals(3, scene.memory.state.runs().getFirst().effects());
        assertEquals(1, scene.executionStarts[0]);
        assertFalse(scene.controller.submit(scene.request(1), scene.owner).accepted());
    }

    @Test void enrollmentExpiryCannotPublishLiveControlFromLateCommit() {
        Scene scene = new Scene(); scene.memory.holdNext = true;
        scene.controller.enroll(UUID.randomUUID(), "minecraft:overworld", scene.principal);
        scene.clock.now += BootstrapController.MAX_STORAGE_WAIT_MILLIS;
        scene.controller.tick();
        assertNull(scene.controller.enrollment());
        assertFalse(scene.controller.ready());
        scene.memory.release(); scene.controller.tick();
        assertNull(scene.controller.enrollment());
        assertFalse(scene.controller.ready());
        assertThrows(IllegalStateException.class, () -> scene.controller.enroll(
                UUID.randomUUID(), "minecraft:overworld", scene.principal));
        assertEquals(1, scene.memory.writes);
    }

    @Test void overflowingStorageDeadlineRejectsBeforeDispatch() {
        Scene scene = new Scene(); scene.clock.now = Long.MAX_VALUE - 1;
        scene.controller.enroll(UUID.randomUUID(), "minecraft:overworld", scene.principal);
        scene.controller.tick();
        assertFalse(scene.controller.ready());
        assertNull(scene.controller.enrollment());
        assertEquals(0, scene.memory.writes);
    }

    @Test void reconciliationExpiryPreservesCheckpointStatusWithoutReplayOrLateUnlock() {
        Scene scene = new Scene(); scene.enroll();
        UUID id = UUID.randomUUID();
        var marker = new BootstrapJournal.RunMarker(id, scene.actor.citizenId(),
                BootstrapJournal.Phase.ACTIVE, null, null, 2, 1, REF.sha256());
        scene.memory.state = new BootstrapJournal.State(scene.memory.state.worldId(),
                scene.memory.state.revision(), scene.memory.state.enrollment(), List.of(marker));
        scene.memory.holdNext = true;
        var reloaded = new BootstrapController(scene.memory.state, scene.memory,
                (request, actor) -> { throw new AssertionError("No recovery world effects"); },
                (bound, snapshot) -> { throw new AssertionError("No recovery routing"); },
                (decision, bound, limits) -> { throw new AssertionError("No recovery model"); },
                (bound, ref, runId, limits, usage) -> { throw new AssertionError("No recovery execution"); },
                new RequestEnvironment() {
                    public boolean enrolled(ActorRef actor, TrustedContext context) { return false; }
                    public boolean loaded(Cuboid area, ObservationRef reference) { return false; }
                    public boolean available(ContainerRef target, ObservationRef reference) { return false; }
                }, capability -> java.util.Optional.empty(), now -> { throw new AssertionError(); },
                now -> { throw new AssertionError(); }, scene.clock);
        var pending = reloaded.status(id, scene.owner);
        assertTrue(reloaded.runIds(scene.owner).contains(id));
        assertTrue(reloaded.cancellableRunIds(scene.owner).isEmpty());
        assertEquals(BootstrapController.Phase.PERSISTING, pending.phase());
        assertEquals(marker, pending.marker());
        assertNull(pending.storageError());
        scene.clock.now += BootstrapController.MAX_STORAGE_WAIT_MILLIS;
        reloaded.tick();
        var expired = reloaded.status(id, scene.owner);
        assertEquals(BootstrapController.Phase.TERMINAL, expired.phase());
        assertEquals(marker, expired.marker());
        assertEquals(Reason.STORAGE_UNAVAILABLE, expired.storageError());
        assertTrue(expired.receipts().isEmpty());
        assertFalse(reloaded.ready());
        scene.memory.release();
        for (int i = 0; i < 8; i++) reloaded.tick();
        assertEquals(expired, reloaded.cancel(id, scene.owner));
        assertFalse(reloaded.submit(scene.request(1), scene.owner).accepted());
        assertEquals(BootstrapJournal.Phase.INTERRUPTED, scene.memory.state.runs().getFirst().phase());
        assertEquals(2, scene.memory.writes);
    }
}
