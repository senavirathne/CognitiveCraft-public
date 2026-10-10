package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static dev.aivillages.core.kernel.Budgets.Kind;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static dev.aivillages.core.kernel.VersionedSkillRepository.*;
import static org.junit.jupiter.api.Assertions.*;

/** Controlled generation, actual resolver/controller/compiler/repository and optional real broker. */
class PrimitiveDiagnosticResearchTest {
    @TempDir Path temporary;
    private static final String DIM = "minecraft:overworld";
    private static final TrustedContext OWNER = new TrustedContext(new PrincipalRef(new UUID(1, 1)),
            new ScopeRef(new UUID(2, 2), new UUID(3, 3)));
    private static final Cuboid AREA = new Cuboid(DIM, 0, 64, 0, 0, 64, 0);
    private static final ObservationRef OBS = new ObservationRef(new UUID(4, 4), 1, DIM);
    private static final ValidatedRequest BOUND = new ValidatedRequest(new CapabilityRequest(CropDelivery.ID,
            Map.of("actor", new ActorValue(new ActorRef(new UUID(5, 5), new UUID(6, 6), DIM)),
                    "source", new AreaValue(AREA), "destination", new ContainerValue(new ContainerRef(DIM, 2, 64, 0)),
                    "amount", new IntValue(1))), OWNER, OBS);
    private static final CapabilityCatalog CAPABILITIES = id -> id.equals(CropDelivery.ID)
            ? Optional.of(CropDelivery.SPEC) : Optional.empty();
    private static final ObservationSnapshot OBSERVED = new ObservationSnapshot(OBS, ObservationStatus.PRESENT,
            "source:" + UUID.nameUUIDFromBytes(AREA.toString().getBytes(StandardCharsets.UTF_8)),
            100, 1, Map.of("mature_wheat", 1L, "unknown_cells", 0L));

    private static final class Time extends Clock {
        long now = 1_000_000;
        @Override public long millis() { return now; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
    private static Budgets.ResearchLimits limits(Time clock, long calls, long repairs) {
        long deadline = clock.now + 10_000;
        var inference = new Budgets.InferenceLimits(new Budgets.Limits(Map.of(Kind.CALLS, calls,
                Kind.REPAIRS, repairs, Kind.INPUT_BYTES, 32_768L, Kind.OUTPUT_BYTES, 32_768L), deadline), 8192, 8192);
        var trial = new Budgets.ExecutionLimits(new Budgets.Limits(Map.of(Kind.CALLS, 128L,
                Kind.INSTRUCTIONS, 300L, Kind.OBSERVATIONS, 60L, Kind.TRAVEL_BLOCKS, 32L,
                Kind.ATTEMPTED_EFFECTS, 32L, Kind.COMMITTED_EFFECTS, 32L, Kind.ELAPSED_TICKS, 100L), deadline), 5000);
        var total = new HashMap<>(trial.total().maxima());
        inference.total().maxima().forEach((kind, maximum) -> total.merge(kind, maximum, Math::max));
        total.put(Kind.CALLS, Math.addExact(128, calls));
        total.put(Kind.CANDIDATES, 3L); total.put(Kind.TRIALS, 1L);
        return new Budgets.ResearchLimits(inference, trial, new Budgets.Limits(total, deadline));
    }

    private static final class Model implements GenerationPort {
        final List<Generation.Role> roles = new ArrayList<>();
        final ArrayDeque<String> responses = new ArrayDeque<>();
        final Generation.Descriptor descriptor;
        Generation.Request request;
        CompletableFuture<Generation.Result> pending;
        Budgets.ResponseAllowance allowance;
        Model(String protocol, String... replies) {
            descriptor = new Generation.Descriptor(protocol, "controlled-fixture", null);
            responses.addAll(List.of(replies));
        }
        @Override public Generation.Descriptor descriptor() { return descriptor; }
        @Override public Generation.Status status() {
            return new Generation.Status(Generation.State.READY, 0, null, Generation.Compute.NOT_STARTED, false, false);
        }
        @Override public Generation.Handle generate(Generation.Request request, Budgets.InferenceLimits limits,
                                                     Budgets.Ledger usage) {
            roles.add(request.role()); this.request = request;
            allowance = limits.chargeCall(usage, 128, request.role() == Generation.Role.REPAIR);
            CompletableFuture<Generation.Result> answer = new CompletableFuture<>(); pending = answer;
            if (!responses.isEmpty()) respond(responses.remove(), request.id(), request.bound().context());
            return new Generation.Handle() {
                @Override public UUID id() { return request.id(); }
                @Override public java.util.concurrent.CompletionStage<Generation.Result> result() { return answer; }
                @Override public boolean cancel() { return false; } // Deliberately permits late delivery.
                @Override public Generation.Compute compute() { return answer.isDone()
                        ? Generation.Compute.COMPLETED : Generation.Compute.RUNNING; }
            };
        }
        void respond(String source, UUID id, TrustedContext owner) {
            long bytes = source.getBytes(StandardCharsets.UTF_8).length;
            allowance.accept(bytes); allowance.close();
            pending.complete(new Generation.Result(id, owner, request.role(), Generation.Outcome.CANDIDATE,
                    null, source, descriptor, new Generation.Usage(128, bytes, -1, -1,
                    Generation.Precision.UNKNOWN), Generation.Compute.COMPLETED));
        }
    }

    private static final class Harness implements AutoCloseable {
        final Path root;
        final Time clock;
        final ArrayDeque<Runnable> compilation = new ArrayDeque<>();
        final VersionedSkillRepository repository;
        final ResearchAdmissionController controller;
        final CapabilityResolver.Engine resolver;
        final Model model;
        boolean authorized = true;
        int fixtureCalls, trialCalls;
        Harness(Path root, PrimitiveDiagnostics.Sink sink, boolean catalogMetadata,
                String protocol, String... responses) throws Exception {
            this(root, sink, catalogMetadata, new Time(), null, new Model(protocol, responses));
        }
        Harness(Path root, PrimitiveDiagnostics.Sink sink, boolean catalogMetadata, Time clock,
                GenerationPort generation, Model model) throws Exception {
            this.root = root; this.clock = clock; this.model = model;
            PrimitiveCatalog primitives = catalogMetadata
                    ? PrimitiveDiagnostics.completeCatalog(GatewayPrimitives.instance().all()) : GatewayPrimitives.instance();
            var guard = new ResearchAdmissionController.DecisionGuard();
            repository = VersionedSkillRepository.open(root, new RuntimeSnapshot("minecraft-26.3",
                    CAPABILITIES, primitives), Limits.defaults(), guard, TrustedContext::equals, FaultInjector.none());
            resolver = new CapabilityResolver.Engine(CAPABILITIES, primitives, CapabilityResolver.exactCatalog(repository),
                    null, CapabilityResolver.cropDeliverySupport(), (actor, owner) -> authorized && OWNER.equals(owner),
                    (actor, ref, owner) -> true, (actor, effect, owner) -> authorized && OWNER.equals(owner),
                    (request, method, observation) -> null, () -> 100, CapabilityResolver.Limits.defaults());
            var staging = new ResearchAdmissionController.TrialArtifacts(repository);
            var grants = new ResearchAdmissionController.TrialGrants(clock);
            var trial = new ResearchAdmissionController.TrialPort() {
                @Override public BoundedSkillExecutor.Start start(ValidatedRequest request, ArtifactRef ref,
                        RunCorrelation correlation, Budgets.ExecutionLimits limits, Budgets.Ledger usage,
                        BoundedSkillExecutor.TrialPermit permit) {
                    trialCalls++; throw new AssertionError("Rejected candidate reached physical trial");
                }
                @Override public BoundedSkillExecutor.Progress tick(TrustedContext owner) { throw new AssertionError("No trial"); }
                @Override public BoundedSkillExecutor.Progress cancel(TrustedContext owner) { throw new AssertionError("No trial"); }
            };
            controller = new ResearchAdmissionController(CAPABILITIES, primitives, repository,
                    generation == null ? model : generation, (skill, bound, limits, usage, cancelled) -> {
                        fixtureCalls++; throw new AssertionError("Rejected candidate reached fixtures");
                    }, trial, staging, grants, ResearchAdmissionController.repositoryPublication(repository, Runnable::run),
                    guard, resolver, (actor, owner) -> authorized && OWNER.equals(owner),
                    (actor, effect, owner) -> authorized && OWNER.equals(owner), clock, compilation::add,
                    new ResearchAdmissionController.Settings(3, 8, 2048), sink);
        }
        ResearchAdmissionController.Attempt start() {
            return controller.startMissing(resolver.resolve(BOUND, OBSERVED), BOUND, limits(clock, 3, 2));
        }
        ResearchAdmissionController.Status end(ResearchAdmissionController.Attempt attempt) {
            for (int i = 0; i < 12; i++) {
                if (!compilation.isEmpty()) compilation.remove().run();
                var status = attempt.tick(OWNER);
                if (status.phase() == ResearchAdmissionController.Phase.TERMINAL) return status;
            }
            throw new AssertionError("Deterministic attempt did not finish");
        }
        Map<String, String> diskHashes() throws Exception {
            Map<String, String> hashes = new HashMap<>();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.filter(Files::isRegularFile).toList())
                    hashes.put(root.relativize(path).toString(), java.util.HexFormat.of().formatHex(
                            java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            }
            return hashes;
        }
        @Override public void close() throws Exception { repository.close(); }
    }

    @Test void generatedMissingPrimitiveOffersOnceWithoutRepairTrialOrStoreMutation() throws Exception {
        List<PrimitiveDiagnostics.Observation> observed = new ArrayList<>();
        try (var h = new Harness(temporary.resolve("one"), event -> { observed.add(event); return PrimitiveDiagnostics.Offer.ACCEPTED; },
                true, "fixture-v2", PrimitiveDiagnosticsTest.missingIr())) {
            var before = h.diskHashes();
            var attempt = h.start(); var status = h.end(attempt);
            assertEquals(ResearchStatus.NOT_ADMITTED, status.outcome().status());
            assertEquals(Reason.UNSUPPORTED_PRIMITIVE, status.outcome().reason());
            assertEquals(1, status.outcome().modelCalls());
            assertEquals(List.of(Generation.Role.INITIAL), h.model.roles);
            assertEquals(1, observed.size());
            var event = observed.getFirst();
            assertEquals(attempt.id(), event.attemptId()); assertEquals(h.model.request.id(), event.generationId());
            assertEquals(OWNER, event.owner()); assertEquals(Generation.Role.INITIAL, event.role());
            assertEquals("fixture-v2", event.provider().protocol());
            assertEquals(1, status.usage().get(Kind.CANDIDATES));
            assertEquals(0, status.usage().getOrDefault(Kind.REPAIRS, 0L));
            assertEquals(0, status.usage().getOrDefault(Kind.TRIALS, 0L));
            assertEquals(0, h.fixtureCalls); assertEquals(0, h.trialCalls);
            assertEquals(before, h.diskHashes());
            for (int i = 0; i < 20; i++) { attempt.status(OWNER); attempt.tick(OWNER); }
            assertEquals(1, observed.size()); assertEquals(1, h.model.roles.size());
            assertFalse(status.toString().contains(event.candidateSha256()));
            assertFalse(status.toString().contains(event.generationId().toString()));
            var foreign = new TrustedContext(new PrincipalRef(UUID.randomUUID()), OWNER.scope());
            assertThrows(SecurityException.class, () -> attempt.status(foreign));
            assertThrows(SecurityException.class, () -> attempt.cancel(foreign));
        }
    }

    @Test void actualPrivateStoreAndOfflineReportKeepSyntheticRejectedDemandOutsideAuthoritativeRepository() throws Exception {
        var clock=new Time();clock.now=System.currentTimeMillis();
        var nanos=new java.util.concurrent.atomic.AtomicLong();
        Path diagnosticWorld=temporary.resolve("diagnostic-world");
        var service=new PrimitiveDiagnosticService(PrimitiveDiagnostics.Policy.metadata(),clock,nanos::get,
                ()->PrimitiveDiagnosticStore.open(diagnosticWorld,PrimitiveDiagnostics.Policy.metadata()),
                ignored->{},new UUID(70,71),false);
        service.cycle();
        try(var h=new Harness(temporary.resolve("authoritative-world"),service,true,clock,null,
                new Model("controlled-v1",PrimitiveDiagnosticsTest.missingIr()))) {
            var before=h.diskHashes();var result=h.end(h.start());
            assertEquals(Reason.UNSUPPORTED_PRIMITIVE,result.outcome().reason());
            assertEquals(1,result.outcome().modelCalls());assertEquals(0,result.usage().getOrDefault(Kind.REPAIRS,0L));
            assertEquals(0,h.fixtureCalls);assertEquals(0,h.trialCalls);
            nanos.set(1_000_000_000L);service.cycle();
            assertEquals(1,service.status().persistedSnapshots());assertEquals(1,service.snapshot().size());
            assertEquals(before,h.diskHashes());
            var row=service.snapshot().getFirst();
            String candidate=PrimitiveDiagnosticsTest.missingIr();
            assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(candidate.getBytes(StandardCharsets.UTF_8))),row.representative().candidateSha256());
            Path root=diagnosticWorld.resolve(PrimitiveDiagnosticStore.RELATIVE_ROOT);
            byte[] actual=Files.readAllBytes(root.resolve("segment-0.jsonl"));
            assertEquals(row,PrimitiveDiagnosticCodec.decode(actual));
            String encoded=new String(actual,StandardCharsets.UTF_8);
            for(String secret:List.of(OWNER.principal().id().toString(),OWNER.scope().worldId().toString(),
                    OWNER.scope().domainId().toString(),candidate))assertFalse(encoded.contains(secret));
            service.closeAsync();service.cycle();
            var report=PrimitiveDiagnosticReport.render(List.of(row),PrimitiveDiagnostics.Policy.metadata(),
                    Map.of(CropDelivery.ID,"Farming"),Math.max(clock.now,row.lastSeen()));
            try(var store=PrimitiveDiagnosticStore.existing(diagnosticWorld,PrimitiveDiagnostics.Policy.metadata())) {
                store.report(report.json(),report.expiry());
                assertArrayEquals(report.json(),Files.readAllBytes(root.resolve("report.json")));
            }
            assertEquals(before,h.diskHashes());
            Path evidence=Path.of("build/primitive-diagnostic-evidence");Files.createDirectories(evidence);
            Files.write(evidence.resolve("example.jsonl"),actual);
            Files.write(evidence.resolve("example-report.json"),report.json());
            Files.write(evidence.resolve("example-measurements.json"),PrimitiveDiagnosticCodec.bytes(Map.of(
                    "schema",1L,"synthetic",true,"recordBytes",(long)actual.length,"reportBytes",(long)report.json().length,
                    "modelCalls",1L,"repairs",0L,"trials",0L,"authoritativeHashesUnchanged",true,
                    "usage",result.usage().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                            e->e.getKey().name(),Map.Entry::getValue)))));
        } finally {service.closeAsync();service.cycle();}
    }

    @Test void offFullAndBrokenObserversKeepExactlyTheSameOutcomeAndDebits() throws Exception {
        Map<Kind, Long> baseline = null;
        List<PrimitiveDiagnostics.Sink> sinks = List.of(PrimitiveDiagnostics.noop(),
                event -> PrimitiveDiagnostics.Offer.ACCEPTED, event -> PrimitiveDiagnostics.Offer.FULL,
                event -> { throw new IllegalStateException("private payload must not be logged"); },
                event -> { throw new AssertionError("observer failure"); });
        int i = 0;
        for (var sink : sinks) try (var h = new Harness(temporary.resolve("mode" + i++), sink, true,
                "ollama-chat-v1", PrimitiveDiagnosticsTest.missingIr())) {
            var status = h.end(h.start());
            assertEquals(ResearchStatus.NOT_ADMITTED, status.outcome().status());
            assertEquals(Reason.UNSUPPORTED_PRIMITIVE, status.outcome().reason());
            assertEquals(1, h.model.roles.size()); assertEquals(0, h.fixtureCalls); assertEquals(0, h.trialCalls);
            if (baseline == null) baseline = status.usage(); else assertEquals(baseline, status.usage());
        }
    }

    @Test void suppliedCandidateRejectsWithoutAiDemandOrInference() throws Exception {
        List<PrimitiveDiagnostics.Observation> observed = new ArrayList<>();
        try (var h = new Harness(temporary.resolve("supplied"), event -> { observed.add(event); return PrimitiveDiagnostics.Offer.ACCEPTED; },
                true, "fixture-v2")) {
            var status = h.end(h.controller.evaluateCandidate(BOUND, PrimitiveDiagnosticsTest.missingIr(), limits(h.clock, 3, 2)));
            assertEquals(Reason.UNSUPPORTED_PRIMITIVE, status.outcome().reason());
            assertEquals(ResearchStatus.NOT_ADMITTED, status.outcome().status());
            assertEquals(0, status.outcome().modelCalls()); assertTrue(observed.isEmpty()); assertTrue(h.model.roles.isEmpty());
        }
    }

    @Test void absentDiagnosticCatalogOmitsObservationButCannotEnableRepair() throws Exception {
        int[] counters = {0, 0};
        var sink = new PrimitiveDiagnostics.Sink() {
            @Override public PrimitiveDiagnostics.Offer offer(PrimitiveDiagnostics.Observation event) { counters[0]++; return PrimitiveDiagnostics.Offer.ACCEPTED; }
            @Override public void catalogUnavailable() { counters[1]++; }
        };
        try (var h = new Harness(temporary.resolve("no-metadata"), sink, false, "fixture-v2", PrimitiveDiagnosticsTest.missingIr())) {
            var status = h.end(h.start());
            assertEquals(Reason.UNSUPPORTED_PRIMITIVE, status.outcome().reason());
            assertEquals(1, h.model.roles.size()); assertArrayEquals(new int[]{0, 1}, counters);
        }
    }

    @Test void authorityRevocationWinsDuringGenerationAndAfterCompilation() throws Exception {
        for (boolean afterCompilation : List.of(false, true)) {
            List<PrimitiveDiagnostics.Observation> events = new ArrayList<>();
            try (var h = new Harness(temporary.resolve("revocation" + afterCompilation), e -> { events.add(e); return PrimitiveDiagnostics.Offer.ACCEPTED; },
                    true, "fixture-v2", PrimitiveDiagnosticsTest.missingIr())) {
                var attempt = h.start();
                if (afterCompilation) {
                    assertEquals(ResearchAdmissionController.Phase.VALIDATING, attempt.tick(OWNER).phase());
                    assertEquals(1, h.compilation.size()); h.compilation.remove().run();
                }
                h.authorized = false;
                var status = attempt.tick(OWNER);
                assertEquals(ResearchStatus.BLOCKED, status.outcome().status());
                assertEquals(Reason.AUTHORITY_DENIED, status.outcome().reason());
                assertTrue(events.isEmpty()); assertEquals(1, h.model.roles.size()); assertEquals(0, h.fixtureCalls);
            }
        }
    }

    @Test void cancellationAndExpiryFenceLateCompilerResultsAndPreserveTerminalOutcome() throws Exception {
        for (boolean expires : List.of(false, true)) {
            List<PrimitiveDiagnostics.Observation> events = new ArrayList<>();
            try (var h = new Harness(temporary.resolve("fence" + expires), e -> { events.add(e); return PrimitiveDiagnostics.Offer.ACCEPTED; },
                    true, "fixture-v2", PrimitiveDiagnosticsTest.missingIr())) {
                var attempt = h.start(); attempt.tick(OWNER);
                assertEquals(1, h.compilation.size());
                if (expires) h.clock.now += 10_001; else attempt.cancel(OWNER);
                h.compilation.remove().run();
                var status = attempt.tick(OWNER);
                assertEquals(expires ? Reason.BUDGET_EXHAUSTED : Reason.CANCELLED, status.outcome().reason());
                assertEquals(expires ? ResearchStatus.BLOCKED : ResearchStatus.CANCELLED, status.outcome().status());
                assertTrue(events.isEmpty()); assertEquals(1, h.model.roles.size()); assertEquals(0, h.trialCalls);
            }
        }
    }

    @Test void foreignAndOldEpochProviderDeliveryCannotProduceObservation() throws Exception {
        for (boolean cancelFirst : List.of(false, true)) {
            List<PrimitiveDiagnostics.Observation> events = new ArrayList<>();
            try (var h = new Harness(temporary.resolve("foreign" + cancelFirst), e -> { events.add(e); return PrimitiveDiagnostics.Offer.ACCEPTED; },
                    true, "fixture-v2")) {
                var attempt = h.start();
                if (cancelFirst) attempt.cancel(OWNER);
                var owner = cancelFirst ? OWNER : new TrustedContext(new PrincipalRef(UUID.randomUUID()), OWNER.scope());
                h.model.respond(PrimitiveDiagnosticsTest.missingIr(), h.model.request.id(), owner);
                var status = attempt.tick(OWNER);
                assertEquals(cancelFirst ? Reason.CANCELLED : Reason.REQUEST_INVALID, status.outcome().reason());
                assertTrue(events.isEmpty()); assertTrue(h.compilation.isEmpty());
            }
        }
    }

    @Test void malformedCallsRetainRepairPolicyAndOnlyGeneratedRepairGapIsObserved() throws Exception {
        var invalid = PrimitiveDiagnosticsTest.missingIr().replace("\"fingerprint\":\"" + "0".repeat(64) + "\"", "\"fingerprint\":\"invalid\"");
        List<PrimitiveDiagnostics.Observation> observed = new ArrayList<>();
        try (var h = new Harness(temporary.resolve("repair"), e -> { observed.add(e); return PrimitiveDiagnostics.Offer.ACCEPTED; },
                true, "fixture-v2", invalid, PrimitiveDiagnosticsTest.missingIr())) {
            var status = h.end(h.start());
            assertEquals(List.of(Generation.Role.INITIAL, Generation.Role.REPAIR), h.model.roles);
            assertEquals(Reason.UNSUPPORTED_PRIMITIVE, status.outcome().reason());
            assertEquals(2, status.outcome().modelCalls()); assertEquals(1, status.usage().get(Kind.REPAIRS));
            assertEquals(1, observed.size()); assertEquals(Generation.Role.REPAIR, observed.getFirst().role());
            assertEquals(List.of("INVALID_STRUCTURE", "UNKNOWN_PRIMITIVE"), status.diagnostics().stream().map(SkillCompiler.Diagnostic::code).toList());
        }
    }

    @Test void inferenceExhaustionCannotCreateDemandOrExtendBudget() throws Exception {
        List<PrimitiveDiagnostics.Observation> observed = new ArrayList<>();
        try (var h = new Harness(temporary.resolve("exhausted"), e -> { observed.add(e); return PrimitiveDiagnostics.Offer.ACCEPTED; }, true, "fixture-v2")) {
            var attempt = h.controller.startMissing(h.resolver.resolve(BOUND, OBSERVED), BOUND, limits(h.clock, 0, 0));
            assertEquals(Reason.BUDGET_EXHAUSTED, attempt.status(OWNER).outcome().reason());
            assertTrue(h.model.roles.isEmpty()); assertTrue(observed.isEmpty());
        }
    }

    @Test void brokerCoalescingStillCountsIndependentRejectedAttempts() throws Exception {
        var clock = new Time(); var model = new Model("fixture-v2", PrimitiveDiagnosticsTest.missingIr());
        List<PrimitiveDiagnostics.Observation> observed = new ArrayList<>();
        PrimitiveDiagnostics.Sink sink = e -> { observed.add(e); return PrimitiveDiagnostics.Offer.ACCEPTED; };
        try (var broker = new AIWorkBroker(model, clock, AIWorkBroker.Settings.defaults(), AIWorkBroker.sessionLimits(clock.now),
                request -> true, AIWorkBroker.Sharing.privateScopes());
             var a = new Harness(temporary.resolve("left"), sink, true, clock, broker, model);
             var b = new Harness(temporary.resolve("right"), sink, true, clock, broker, model)) {
            var left = a.start(); var right = b.start(); broker.step(); broker.step();
            assertEquals(Reason.UNSUPPORTED_PRIMITIVE, a.end(left).outcome().reason());
            assertEquals(Reason.UNSUPPORTED_PRIMITIVE, b.end(right).outcome().reason());
            assertEquals(1, model.roles.size()); assertEquals(1, broker.stats().calls());
            assertEquals(2, observed.size());
            assertNotEquals(observed.get(0).attemptId(), observed.get(1).attemptId());
            assertNotEquals(observed.get(0).generationId(), observed.get(1).generationId());
            assertEquals(observed.get(0).candidateSha256(), observed.get(1).candidateSha256());
        }
    }
}
