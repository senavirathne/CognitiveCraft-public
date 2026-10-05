package dev.aivillages.core.kernel;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static dev.aivillages.core.kernel.Budgets.Kind;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;
import static dev.aivillages.core.kernel.Outcomes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic IR execution matrix, including races and admission/authority boundaries. */
class ExecutorJUnitTest {
    static final String DIM = "minecraft:overworld";
    static final ActorRef ACTOR = new ActorRef(UUID.randomUUID(), UUID.randomUUID(), DIM);
    static final Cuboid SOURCE = new Cuboid(DIM, 0, 64, 0, 0, 64, 0);
    static final ContainerRef DEST = new ContainerRef(DIM, 2, 64, 0);
    static final TrustedContext OWNER = new TrustedContext(new PrincipalRef(UUID.randomUUID()),
            new ScopeRef(UUID.randomUUID(), UUID.randomUUID()));
    static final TrustedContext STRANGER = new TrustedContext(new PrincipalRef(UUID.randomUUID()),
            new ScopeRef(UUID.randomUUID(), UUID.randomUUID()));
    static final TrustedContext SHARED = new TrustedContext(new PrincipalRef(UUID.randomUUID()),
            new ScopeRef(UUID.randomUUID(), UUID.randomUUID()));
    static final CapabilityId CHILD = new CapabilityId("cognitivecraft:harvest_step", 1);
    static final CapabilityId GRANDCHILD = new CapabilityId("cognitivecraft:harvest_stage", 1);
    static final EvidenceRef EVIDENCE = new EvidenceRef("admission", "fixture:1", "shared");
    static final UUID BATCH = UUID.randomUUID();

    static final class TestClock extends Clock {
        long millis = 1000;
        long tick = 0;
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
        void advance(long elapsedMillis) { tick++; millis += elapsedMillis; }
    }
    static final class Catalog implements BoundedSkillExecutor.ArtifactSource {
        final Map<ArtifactRef, SkillArtifact> bodies = new HashMap<>();
        final Map<ArtifactRef, AdmissionRecord> admissions = new HashMap<>();
        final Map<ArtifactRef, Compatibility> compatible = new HashMap<>();
        @Override public Optional<SkillArtifact> body(ArtifactRef ref) { return Optional.ofNullable(bodies.get(ref)); }
        @Override public Optional<AdmissionRecord> admission(ArtifactRef ref) {
            return Optional.ofNullable(admissions.get(ref));
        }
        @Override public Optional<Compatibility> compatibility(ArtifactRef ref) {
            return Optional.ofNullable(compatible.get(ref));
        }
        void add(SkillArtifact artifact, AdmissionStatus status) {
            ArtifactRef ref = artifact.descriptor().ref();
            bodies.put(ref, artifact);
            admissions.put(ref, new AdmissionRecord(ref, status,
                    status == AdmissionStatus.ADMITTED ? EVIDENCE : null,
                    status == AdmissionStatus.QUARANTINED ? Reason.ARTIFACT_QUARANTINED : null, 1));
            compatible.put(ref, new Compatibility(ref, CompatibilityStatus.COMPATIBLE,
                    List.of(), "test-runtime"));
        }
    }
    static final class FakeGateway implements GatewayPort {
        final Map<ActionHandle, PrimitiveRequirement> actions = new HashMap<>();
        final List<PrimitiveRequirement> calls = new ArrayList<>();
        final List<ArtifactRef> callerArtifacts = new ArrayList<>();
        final Map<ActionHandle, ActionReceipt> last = new HashMap<>();
        boolean stall, receipts = true, duplicate, cancelPartial;
        boolean unknownObservation;
        Reason failure;
        int cancellation, polls;
        ValidatedRequest request;
        @Override public ActionHandle start(ValidatedRequest req, RunCorrelation run,
                                            PrimitiveRequirement primitive, Map<String, Value> args,
                                            Budgets.Ledger usage) {
            request = req;
            assertEquals(req.request().arguments().get("actor"), args.get("actor"));
            ActionHandle h = new ActionHandle(UUID.randomUUID(), run.runId());
            actions.put(h, primitive); calls.add(primitive); callerArtifacts.add(run.artifact());
            return h;
        }
        @Override public ActionReceipt poll(ActionHandle h) {
            polls++;
            if (stall) return new ActionReceipt(h, false, 0, List.of(), null);
            if (unknownObservation) return new ActionReceipt(h, true, 0, List.of(), null,
                    new IntValue(0), new ObservationSnapshot(request.observation(),
                            ObservationStatus.UNKNOWN, "unloaded-actor", 0, 1,
                            Map.of("unknown_cells", 1L)));
            int stage = calls.indexOf(actions.get(h));
            if (stage < 0) stage = calls.size() - 1;
            CropDelivery.Stage receiptStage = switch (stage % 3) {
                case 0 -> CropDelivery.Stage.HARVEST;
                case 1 -> CropDelivery.Stage.PICKUP;
                default -> CropDelivery.Stage.DEPOSIT;
            };
            long amount = ((IntValue) request.request().arguments().get("amount")).value();
            CropDelivery.CropReceipt receipt = new CropDelivery.CropReceipt(UUID.randomUUID(), h.runId(),
                    BATCH, ((ActorValue)request.request().arguments().get("actor")).value(),
                    ((AreaValue)request.request().arguments().get("source")).value(),
                    ((ContainerValue)request.request().arguments().get("destination")).value(),
                    receiptStage, amount);
            ActionReceipt answer = new ActionReceipt(h, true, 1,
                    receipts ? List.of(receipt) : List.of(), failure != null && stage == 1 ? failure : null,
                    new IntValue(amount), null);
            if (duplicate && last.containsKey(h)) return last.get(h);
            last.put(h, answer);
            return answer;
        }
        @Override public ActionReceipt cancel(ActionHandle h) {
            cancellation++;
            return new ActionReceipt(h, true, cancelPartial ? 1 : 0, List.of(), Reason.CANCELLED);
        }
    }
    static final class Fixture {
        final TestClock clock = new TestClock();
        final Catalog catalog = new Catalog();
        final FakeGateway gateway = new FakeGateway();
        final CapabilitySpec helperSpec = new CapabilitySpec(CHILD, CropDelivery.SPEC.parameters(),
                Type.INT, CropDelivery.SPEC.effects(), Completion.ATTRIBUTABLE_CROP_DELIVERY);
        final CapabilitySpec grandchildSpec = new CapabilitySpec(GRANDCHILD, CropDelivery.SPEC.parameters(),
                Type.INT, CropDelivery.SPEC.effects(), Completion.ATTRIBUTABLE_CROP_DELIVERY);
        final CapabilityCatalog specs = id -> id.equals(CropDelivery.ID) ? Optional.of(CropDelivery.SPEC)
                : id.equals(CHILD) ? Optional.of(helperSpec)
                : id.equals(GRANDCHILD) ? Optional.of(grandchildSpec) : Optional.empty();
        final SkillCompiler compiler = new SkillCompiler(specs, GatewayPrimitives.instance(),
                ref -> catalog.body(ref).map(SkillArtifact::descriptor));
        final ValidatedRequest request = new ValidatedRequest(new CapabilityRequest(CropDelivery.ID,
                Map.of("actor", new ActorValue(ACTOR), "source", new AreaValue(SOURCE),
                        "destination", new ContainerValue(DEST), "amount", new IntValue(1))), OWNER,
                new ObservationRef(UUID.randomUUID(), 1, DIM));
        final List<UUID> released = new ArrayList<>();
        boolean trialAuthorized = true;
        boolean ownerAuthorized = true;
        BoundedSkillExecutor executor;
        Fixture() {
            this((actor, ref, context) -> actor.equals(ACTOR) && context.equals(OWNER));
        }
        Fixture(EligibilityPolicy eligibility) {
            executor = new BoundedSkillExecutor(catalog, specs, GatewayPrimitives.instance(),
                    eligibility,
                    (permit, request, limits) -> trialAuthorized && permit.context().equals(OWNER)
                            && permit.expiresAtMillis() <= limits.total().deadlineEpochMillis(),
                    new BoundedSkillExecutor.ControlPolicy() {
                        public boolean mayInspect(TrustedContext caller, TrustedContext owner, UUID run) {
                            return owner.equals(OWNER) && (caller.equals(SHARED)
                                    || caller.equals(OWNER) && ownerAuthorized);
                        }
                        public boolean mayCancel(TrustedContext caller, TrustedContext owner, UUID run) {
                            return owner.equals(OWNER) && (caller.equals(SHARED)
                                    || caller.equals(OWNER) && ownerAuthorized);
                        }
                    }, gateway, released::add, clock, () -> clock.tick,
                    new BoundedSkillExecutor.Settings(3, 16, 12, 32));
        }
        SkillArtifact compile(CapabilityId id, List<Object> body, List<ArtifactRef> deps,
                              AdmissionStatus admission) {
            Map<String, Object> ir = Map.of("schema", 1L, "capability", id.name(),
                    "capabilityVersion", (long) id.version(),
                    "dependencies", deps.stream().map(ExecutorJUnitTest::ref).toList(), "body", body);
            SkillCompiler.CompileResult result = compiler.compile(StrictJson.canonical(ir));
            assertInstanceOf(SkillCompiler.Success.class, result, String.valueOf(result));
            SkillArtifact artifact = ((SkillCompiler.Success) result).skill().artifact();
            catalog.add(artifact, admission);
            return artifact;
        }
        SkillArtifact child() {
            return compile(CHILD, List.of(primitive(Operation.HARVEST_WHEAT, "h"),
                    primitive(Operation.PICKUP_WHEAT, "p"), primitive(Operation.TRANSFER_WHEAT, "d"),
                    result(local("d"))), List.of(), AdmissionStatus.ADMITTED);
        }
        SkillArtifact root(ArtifactRef dep, AdmissionStatus status) {
            return compile(CropDelivery.ID, List.of(Map.of("op", "bind", "name", "ok",
                            "type", "BOOL", "value", Map.of("bool", true)),
                    Map.of("op", "if", "test", local("ok"), "then", List.of(), "else", List.of()),
                    Map.of("op", "repeat", "count", Map.of("int", 1L), "body",
                            List.of(call(dep, "returned"))),
                    result(param("amount"))), List.of(dep), status);
        }
        SkillArtifact rootDirect(List<Object> body, AdmissionStatus status) {
            return compile(CropDelivery.ID, body, List.of(), status);
        }
        Budgets.ExecutionLimits limits(long instructions, long calls, long actionDeadline) {
            EnumMap<Kind, Long> caps = new EnumMap<>(Kind.class);
            caps.put(Kind.INSTRUCTIONS, instructions); caps.put(Kind.CALLS, calls);
            caps.put(Kind.OBSERVATIONS, 64L); caps.put(Kind.ELAPSED_TICKS, 100L);
            caps.put(Kind.ATTEMPTED_EFFECTS, 64L); caps.put(Kind.COMMITTED_EFFECTS, 64L);
            caps.put(Kind.TRAVEL_BLOCKS, 64L);
            return new Budgets.ExecutionLimits(new Budgets.Limits(caps, clock.millis + 10_000),
                    actionDeadline);
        }
        BoundedSkillExecutor.Start start(SkillArtifact artifact, Budgets.ExecutionLimits limits) {
            return executor.startAdmitted(request, artifact.descriptor().ref(),
                    new RunCorrelation(UUID.randomUUID(), artifact.descriptor().ref(), null),
                    limits, new Budgets.Ledger(limits.total(), clock));
        }
        BoundedSkillExecutor.Start trial(SkillArtifact artifact, Budgets.ExecutionLimits limits) {
            ArtifactRef ref = artifact.descriptor().ref();
            return executor.startTrial(request, ref,
                    new RunCorrelation(UUID.randomUUID(), ref, UUID.randomUUID()),
                    limits, new Budgets.Ledger(limits.total(), clock),
                    new BoundedSkillExecutor.TrialPermit(ref, OWNER, UUID.randomUUID(),
                            clock.millis + 9_000));
        }
        BoundedSkillExecutor.Run run(SkillArtifact artifact) {
            return assertInstanceOf(BoundedSkillExecutor.Started.class,
                    start(artifact, limits(200, 30, 500))).run();
        }
        BoundedSkillExecutor.Progress untilTerminal(BoundedSkillExecutor.Run run) {
            for (int i = 0; i < 80; i++) {
                BoundedSkillExecutor.Progress p = run.tick(OWNER);
                if (p.phase() == BoundedSkillExecutor.Phase.TERMINAL) return p;
                clock.advance(50);
            }
            return fail("bounded run did not terminate");
        }
    }
    static Map<String, Object> ref(ArtifactRef r) {
        return Map.of("capability", r.capability().name(), "version", (long)r.capability().version(),
                "sha256", r.sha256());
    }
    static Map<String, Object> param(String name) { return Map.of("param", name); }
    static Map<String, Object> local(String name) { return Map.of("local", name); }
    static Map<String, Object> result(Map<String, Object> value) {
        return Map.of("op", "result", "value", value);
    }
    static Map<String, Object> call(ArtifactRef ref, String into) {
        return Map.of("op", "call", "kind", "skill", "ref", ref(ref),
                "args", Map.of("actor", param("actor"), "source", param("source"),
                        "destination", param("destination"), "amount", param("amount")), "into", into);
    }
    static Map<String, Object> primitive(Operation op, String into) {
        Map<String, Object> args = new HashMap<>();
        for (Parameter parameter : op.signature().parameters()) {
            args.put(parameter.name(), switch (parameter.name()) {
                case "x" -> Map.of("int", 0L);
                case "y" -> Map.of("int", 64L);
                case "z" -> Map.of("int", 0L);
                default -> param(parameter.name());
            });
        }
        return Map.of("op", "call", "kind", "primitive", "id", op.signature().id(),
                "version", 1L, "fingerprint", op.signature().fingerprint(), "args", args, "into", into);
    }

    @Test void successfulControlFlowAndPinnedTransitiveCalls() {
        Fixture f = new Fixture();
        SkillArtifact leaf = f.child();
        SkillArtifact middle = f.compile(GRANDCHILD,
                List.of(call(leaf.descriptor().ref(), "mid"), result(local("mid"))),
                List.of(leaf.descriptor().ref()), AdmissionStatus.ADMITTED);
        SkillArtifact root = f.root(middle.descriptor().ref(), AdmissionStatus.ADMITTED);
        BoundedSkillExecutor.Run run = f.run(root);
        f.catalog.bodies.clear(); f.catalog.admissions.clear(); f.catalog.compatible.clear();
        BoundedSkillExecutor.Progress p = f.untilTerminal(run);
        assertEquals(ExecutionStatus.SUCCEEDED, p.summary().outcome().status());
        assertEquals(List.of(leaf.descriptor().ref(), middle.descriptor().ref(),
                root.descriptor().ref()), p.summary().pinned());
        assertEquals(List.of(Operation.HARVEST_WHEAT.requirement(), Operation.PICKUP_WHEAT.requirement(),
                Operation.TRANSFER_WHEAT.requirement()), f.gateway.calls);
        assertEquals(3, p.summary().committedEffects());
        assertEquals(1, f.released.size());
        assertTrue(p.trace().size() <= 12);
    }
    @Test void trialRootSkipsEligibilityOnFixtureWorker() throws Exception {
        Thread serverThread = Thread.currentThread();
        AtomicInteger eligibilityCalls = new AtomicInteger();
        EligibilityPolicy eligibility = (actor, ref, context) -> {
            eligibilityCalls.incrementAndGet();
            if (Thread.currentThread() != serverThread)
                throw new IllegalStateException("Server thread required");
            return false;
        };
        try (var worker = Executors.newSingleThreadExecutor(
                task -> new Thread(task, "cognitivecraft-world-storage"))) {
            BoundedSkillExecutor.Start started = worker.submit(() -> {
                Fixture f = new Fixture(eligibility);
                SkillArtifact root = f.rootDirect(List.of(result(param("amount"))),
                        AdmissionStatus.CANDIDATE);
                return f.trial(root, f.limits(100, 10, 500));
            }).get(5, TimeUnit.SECONDS);
            assertInstanceOf(BoundedSkillExecutor.Started.class, started);
        }
        assertEquals(0, eligibilityCalls.get());
    }
    @Test void admittedRootStillRequiresEligibility() {
        for (boolean allowed : List.of(false, true)) {
            List<ArtifactRef> checked = new ArrayList<>();
            Fixture f = new Fixture((actor, ref, context) -> {
                assertEquals(ACTOR, actor);
                assertEquals(OWNER, context);
                checked.add(ref);
                return allowed;
            });
            SkillArtifact root = f.rootDirect(List.of(result(param("amount"))),
                    AdmissionStatus.ADMITTED);
            BoundedSkillExecutor.Start started = f.start(root, f.limits(100, 10, 500));
            assertEquals(List.of(root.descriptor().ref()), checked);
            if (allowed) assertInstanceOf(BoundedSkillExecutor.Started.class, started);
            else assertEquals(Reason.KNOWLEDGE_REQUIRED,
                    assertInstanceOf(BoundedSkillExecutor.Rejected.class, started).reason());
        }
    }
    @Test void trialDependenciesStillRequireEligibility() {
        for (boolean allowed : List.of(false, true)) {
            List<ArtifactRef> checked = new ArrayList<>();
            Fixture f = new Fixture((actor, ref, context) -> {
                assertEquals(ACTOR, actor);
                assertEquals(OWNER, context);
                checked.add(ref);
                return allowed;
            });
            SkillArtifact dependency = f.child();
            SkillArtifact root = f.root(dependency.descriptor().ref(), AdmissionStatus.CANDIDATE);
            BoundedSkillExecutor.Start started = f.trial(root, f.limits(100, 10, 500));
            assertEquals(List.of(dependency.descriptor().ref()), checked);
            if (allowed) assertInstanceOf(BoundedSkillExecutor.Started.class, started);
            else assertEquals(Reason.KNOWLEDGE_REQUIRED,
                    assertInstanceOf(BoundedSkillExecutor.Rejected.class, started).reason());
        }
    }
    @Test void candidateTrialNeedsValidatedAuthorizationAndAdmittedDependencies() {
        Fixture f = new Fixture();
        SkillArtifact dep = f.child();
        SkillArtifact root = f.root(dep.descriptor().ref(), AdmissionStatus.CANDIDATE);
        assertEquals(Reason.KNOWLEDGE_REQUIRED,
                assertInstanceOf(BoundedSkillExecutor.Rejected.class,
                        f.start(root, f.limits(100, 10, 500))).reason());
        Budgets.ExecutionLimits limits = f.limits(100, 10, 500);
        BoundedSkillExecutor.TrialPermit permit = new BoundedSkillExecutor.TrialPermit(
                root.descriptor().ref(), OWNER, UUID.randomUUID(), f.clock.millis + 9_000);
        f.trialAuthorized = false;
        assertEquals(Reason.AUTHORITY_DENIED, assertInstanceOf(BoundedSkillExecutor.Rejected.class,
                f.executor.startTrial(f.request, root.descriptor().ref(),
                        new RunCorrelation(UUID.randomUUID(), root.descriptor().ref(), UUID.randomUUID()),
                        limits, new Budgets.Ledger(limits.total(), f.clock), permit)).reason());
        f.trialAuthorized = true;
        BoundedSkillExecutor.Run run = assertInstanceOf(BoundedSkillExecutor.Started.class,
                f.executor.startTrial(f.request, root.descriptor().ref(),
                        new RunCorrelation(UUID.randomUUID(), root.descriptor().ref(), UUID.randomUUID()),
                        limits, new Budgets.Ledger(limits.total(), f.clock), permit)).run();
        assertEquals(ExecutionStatus.SUCCEEDED, f.untilTerminal(run).summary().outcome().status());
        f.catalog.admissions.put(dep.descriptor().ref(),
                new AdmissionRecord(dep.descriptor().ref(), AdmissionStatus.CANDIDATE, null, null, 2));
        assertEquals(Reason.KNOWLEDGE_REQUIRED, assertInstanceOf(BoundedSkillExecutor.Rejected.class,
                f.executor.startTrial(f.request, root.descriptor().ref(),
                        new RunCorrelation(UUID.randomUUID(), root.descriptor().ref(), null),
                        limits, new Budgets.Ledger(limits.total(), f.clock), permit)).reason());
    }
    @Test void falseSuccessWithoutTrustedReceipts() {
        Fixture f = new Fixture();
        SkillArtifact root = f.rootDirect(List.of(result(param("amount"))), AdmissionStatus.ADMITTED);
        assertEquals(ExecutionStatus.FAILED, f.untilTerminal(f.run(root)).summary().outcome().status());
        Fixture partial = new Fixture();
        partial.gateway.receipts = false;
        SkillArtifact dependency = partial.child();
        root = partial.root(dependency.descriptor().ref(), AdmissionStatus.ADMITTED);
        assertEquals(ExecutionStatus.FAILED, partial.untilTerminal(partial.run(root)).summary().outcome().status());
    }
    @Test void fixedTickWorkAndCumulativeNestedBudget() {
        Fixture f = new Fixture();
        SkillArtifact dep = f.child();
        SkillArtifact root = f.root(dep.descriptor().ref(), AdmissionStatus.ADMITTED);
        Budgets.ExecutionLimits cap = f.limits(200, 2, 500);
        BoundedSkillExecutor.Run run = assertInstanceOf(BoundedSkillExecutor.Started.class,
                f.start(root, cap)).run();
        long before = 0;
        for (int i = 0; i < 40; i++) {
            BoundedSkillExecutor.Progress p = run.tick(OWNER);
            long after = p.summary().usage().getOrDefault(Kind.INSTRUCTIONS, 0L);
            assertTrue(after - before <= 3, "interpreter slice grew past 3 instructions");
            before = after;
            if (p.phase() == BoundedSkillExecutor.Phase.TERMINAL) {
                assertEquals(Reason.BUDGET_EXHAUSTED, p.summary().outcome().reason());
                assertEquals(2, p.summary().usage().get(Kind.CALLS));
                return;
            }
            f.clock.advance(50);
        }
        fail("budget did not stop nested calls");
    }
    @Test void emptyNestedLoopsChargeCursorTransitionsAndYield() {
        Fixture f = new Fixture();
        SkillArtifact root = f.rootDirect(List.of(Map.of("op", "repeat",
                        "count", Map.of("int", 64L), "body", List.of(Map.of("op", "repeat",
                                "count", Map.of("int", 64L), "body", List.of()))),
                result(param("amount"))), AdmissionStatus.ADMITTED);
        Budgets.ExecutionLimits cap = f.limits(6, 0, 500);
        BoundedSkillExecutor.Run run = assertInstanceOf(BoundedSkillExecutor.Started.class,
                f.start(root, cap)).run();
        assertEquals(3L, run.tick(OWNER).summary().usage().get(Kind.INSTRUCTIONS));
        assertEquals(6L, run.tick(OWNER).summary().usage().get(Kind.INSTRUCTIONS));
        BoundedSkillExecutor.Progress blocked = run.tick(OWNER);
        assertEquals(ExecutionStatus.BLOCKED, blocked.summary().outcome().status());
        assertEquals(Reason.BUDGET_EXHAUSTED, blocked.summary().outcome().reason());
        assertTrue(f.gateway.calls.isEmpty());
    }
    @Test void terminalBlockageStopsAndRetainsPartialProgress() {
        Fixture f = new Fixture();
        f.gateway.failure = Reason.RESOURCE_MISSING;
        SkillArtifact root = f.root(f.child().descriptor().ref(), AdmissionStatus.ADMITTED);
        BoundedSkillExecutor.Run run = f.run(root);
        BoundedSkillExecutor.Progress p = f.untilTerminal(run);
        assertEquals(ExecutionStatus.BLOCKED, p.summary().outcome().status());
        assertEquals(Reason.RESOURCE_MISSING, p.summary().outcome().reason());
        assertTrue(p.summary().committedEffects() >= 1);
        int calls = f.gateway.calls.size();
        run.tick(OWNER);
        assertEquals(calls, f.gateway.calls.size());
    }
    @Test void childNeverBroadensPhysicalAuthorityOrTreatsStalenessAsAbsence() {
        Fixture denied = new Fixture();
        denied.gateway.failure = Reason.AUTHORITY_DENIED;
        SkillArtifact root = denied.root(denied.child().descriptor().ref(), AdmissionStatus.ADMITTED);
        BoundedSkillExecutor.Progress p = denied.untilTerminal(denied.run(root));
        assertEquals(ExecutionStatus.BLOCKED, p.summary().outcome().status());
        assertEquals(Reason.AUTHORITY_DENIED, p.summary().outcome().reason());
        assertEquals(2, denied.gateway.calls.size());
        assertTrue(denied.gateway.calls.stream().allMatch(requirement ->
                requirement.equals(Operation.HARVEST_WHEAT.requirement())
                        || requirement.equals(Operation.PICKUP_WHEAT.requirement())));
        Fixture stale = new Fixture();
        stale.gateway.failure = Reason.STALE_OBSERVATION;
        root = stale.root(stale.child().descriptor().ref(), AdmissionStatus.ADMITTED);
        assertEquals(Reason.STALE_OBSERVATION,
                stale.untilTerminal(stale.run(root)).summary().outcome().reason());
        Fixture unknown = new Fixture();
        unknown.gateway.unknownObservation = true;
        SkillArtifact observation = unknown.rootDirect(List.of(
                primitive(Operation.OBSERVE_INVENTORY, "observed"), result(param("amount"))),
                AdmissionStatus.ADMITTED);
        BoundedSkillExecutor.Progress stopped = unknown.untilTerminal(unknown.run(observation));
        assertEquals(ExecutionStatus.BLOCKED, stopped.summary().outcome().status());
        assertEquals(Reason.STALE_OBSERVATION, stopped.summary().outcome().reason());
    }
    @Test void waitingAndOverallDeadlineAreFinite() {
        Fixture f = new Fixture(); f.gateway.stall = true;
        SkillArtifact root = f.root(f.child().descriptor().ref(), AdmissionStatus.ADMITTED);
        Budgets.ExecutionLimits cap = f.limits(100, 10, 100);
        BoundedSkillExecutor.Run run = assertInstanceOf(BoundedSkillExecutor.Started.class,
                f.start(root, cap)).run();
        for (int i = 0; i < 12; i++) {
            BoundedSkillExecutor.Progress p = run.tick(OWNER);
            if (p.phase() == BoundedSkillExecutor.Phase.TERMINAL) {
                assertEquals(Reason.ACTION_TIMEOUT, p.summary().outcome().reason());
                assertEquals(1, f.gateway.cancellation);
                return;
            }
            f.clock.advance(50);
        }
        fail("stalled handle exceeded deadline");
    }
    @Test void waitingPollsYieldAndParentDeadlineWins() {
        Fixture f = new Fixture(); f.gateway.stall = true;
        SkillArtifact root = f.root(f.child().descriptor().ref(), AdmissionStatus.ADMITTED);
        Budgets.ExecutionLimits cap = f.limits(100, 10, 20_000);
        BoundedSkillExecutor.Run run = assertInstanceOf(BoundedSkillExecutor.Started.class,
                f.start(root, cap)).run();
        for (int i = 0; i < 5; i++) {
            run.tick(OWNER); f.clock.advance(50);
        }
        int calls = f.gateway.calls.size(), polls = f.gateway.polls;
        assertEquals(1, calls);
        assertTrue(polls > 0);
        f.clock.advance(10_000);
        BoundedSkillExecutor.Progress done = run.tick(OWNER);
        assertEquals(Reason.BUDGET_EXHAUSTED, done.summary().outcome().reason());
        assertEquals(calls, f.gateway.calls.size());
        assertEquals(polls, f.gateway.polls);
    }
    @Test void cancellationIsIdempotentAndLateResultsFenced() {
        Fixture f = new Fixture();
        SkillArtifact root = f.root(f.child().descriptor().ref(), AdmissionStatus.ADMITTED);
        BoundedSkillExecutor.Run run = f.run(root);
        for (int i = 0; i < 10 && f.gateway.calls.size() < 2; i++) {
            run.tick(OWNER); f.clock.advance(50);
        }
        f.gateway.cancelPartial = true;
        BoundedSkillExecutor.Progress cancelled = run.cancel(OWNER);
        assertEquals(ExecutionStatus.CANCELLED, cancelled.summary().outcome().status());
        assertTrue(cancelled.summary().committedEffects() >= 1);
        int calls = f.gateway.calls.size(), cancellations = f.gateway.cancellation;
        assertEquals(cancelled, run.cancel(OWNER));
        assertEquals(cancelled, run.tick(OWNER));
        assertEquals(calls, f.gateway.calls.size());
        assertEquals(cancellations, f.gateway.cancellation);
        assertEquals(1, f.released.size());
    }
    @Test void crossScopeInspectionAndControlRequireExplicitSharing() {
        Fixture f = new Fixture();
        SkillArtifact root = f.rootDirect(List.of(result(param("amount"))), AdmissionStatus.ADMITTED);
        BoundedSkillExecutor.Run run = f.run(root);
        assertThrows(SecurityException.class, () -> run.progress(STRANGER));
        assertThrows(SecurityException.class, () -> run.tick(STRANGER));
        assertThrows(SecurityException.class, () -> run.cancel(STRANGER));
        assertEquals(BoundedSkillExecutor.Phase.RUNNING, run.progress(SHARED).phase());
        assertEquals(ExecutionStatus.CANCELLED, run.cancel(SHARED).summary().outcome().status());
    }
    @Test void staleOwnerAuthorityCannotControlAnExistingRun() {
        Fixture f = new Fixture();
        SkillArtifact root = f.rootDirect(List.of(result(param("amount"))), AdmissionStatus.ADMITTED);
        BoundedSkillExecutor.Run run = f.run(root);
        f.ownerAuthorized = false;
        assertThrows(SecurityException.class, () -> run.progress(OWNER));
        assertThrows(SecurityException.class, () -> run.tick(OWNER));
        assertThrows(SecurityException.class, () -> run.cancel(OWNER));
        assertEquals(Reason.AUTHORITY_DENIED, assertInstanceOf(BoundedSkillExecutor.Rejected.class,
                f.start(root, f.limits(100, 10, 500))).reason());
        assertEquals(ExecutionStatus.CANCELLED, run.cancel(SHARED).summary().outcome().status());
    }
    @Test void interruptionRetainsPinsAndReceiptsAndNeverReplays() {
        Fixture f = new Fixture();
        SkillArtifact child = f.child(), root = f.root(child.descriptor().ref(), AdmissionStatus.ADMITTED);
        BoundedSkillExecutor.Run run = f.run(root);
        for (int i = 0; i < 10 && f.gateway.calls.size() < 2; i++) {
            run.tick(OWNER); f.clock.advance(50);
        }
        BoundedSkillExecutor.Progress interrupted = run.interrupt(OWNER);
        assertEquals(ExecutionStatus.INTERRUPTED, interrupted.summary().outcome().status());
        assertEquals(List.of(child.descriptor().ref(), root.descriptor().ref()), interrupted.summary().pinned());
        assertFalse(interrupted.summary().receipts().isEmpty());
        int calls = f.gateway.calls.size();
        f.clock.advance(50); run.tick(OWNER);
        assertEquals(calls, f.gateway.calls.size());
        assertEquals(1, f.released.size());
    }
    @Test void noModelPortIsRequiredForOfflineExecutionAndCancellation() {
        Fixture f = new Fixture();
        GenerationPort unavailable = (request, limits, usage) -> { throw new AssertionError("model used"); };
        assertNotNull(unavailable);
        SkillArtifact root = f.root(f.child().descriptor().ref(), AdmissionStatus.ADMITTED);
        assertEquals(ExecutionStatus.SUCCEEDED, f.untilTerminal(f.run(root)).summary().outcome().status());
        BoundedSkillExecutor.Run second = f.run(root);
        assertEquals(ExecutionStatus.CANCELLED, second.cancel(OWNER).summary().outcome().status());
    }
    @Test void admittedArtifactUsesInvocationBindingsWithoutNewVersion() {
        Fixture f = new Fixture();
        SkillArtifact root = f.root(f.child().descriptor().ref(), AdmissionStatus.ADMITTED);
        assertEquals(ExecutionStatus.SUCCEEDED, f.untilTerminal(f.run(root)).summary().outcome().status());
        Cuboid otherSource = new Cuboid(DIM, 3,64,0,3,64,0);
        ContainerRef otherDestination = new ContainerRef(DIM, 4,64,0);
        ValidatedRequest another = new ValidatedRequest(new CapabilityRequest(CropDelivery.ID,
                Map.of("actor", new ActorValue(ACTOR), "source", new AreaValue(otherSource),
                        "destination", new ContainerValue(otherDestination), "amount", new IntValue(2))),
                OWNER, new ObservationRef(UUID.randomUUID(),2,DIM));
        Budgets.ExecutionLimits limits = f.limits(200,30,500);
        BoundedSkillExecutor.Run second = assertInstanceOf(BoundedSkillExecutor.Started.class,
                f.executor.startAdmitted(another,root.descriptor().ref(),
                        new RunCorrelation(UUID.randomUUID(),root.descriptor().ref(),null),
                        limits,new Budgets.Ledger(limits.total(),f.clock))).run();
        assertEquals(ExecutionStatus.SUCCEEDED, f.untilTerminal(second).summary().outcome().status());
        assertEquals(root.descriptor().ref(), second.progress(OWNER).summary().artifact());
    }
    @Test void tamperedOrIncompatibleBodiesCannotStart() {
        Fixture f = new Fixture();
        SkillArtifact root = f.rootDirect(List.of(result(param("amount"))), AdmissionStatus.ADMITTED);
        ArtifactRef ref = root.descriptor().ref();
        f.catalog.bodies.put(ref, new SkillArtifact(root.descriptor(), "{}", root.metadata()));
        assertEquals(Reason.ARTIFACT_INVALID,
                assertInstanceOf(BoundedSkillExecutor.Rejected.class,
                        f.start(root, f.limits(100, 10, 500))).reason());
        f.catalog.bodies.put(ref, root);
        f.catalog.compatible.put(ref, new Compatibility(ref, CompatibilityStatus.INCOMPATIBLE,
                List.of(Reason.UNSUPPORTED_PRIMITIVE), "new-runtime"));
        f.catalog.bodies.remove(ref); // A read-only repository view may hide incompatible bytes.
        assertEquals(Reason.ARTIFACT_INCOMPATIBLE,
                assertInstanceOf(BoundedSkillExecutor.Rejected.class,
                        f.start(root, f.limits(100, 10, 500))).reason());
    }
}
