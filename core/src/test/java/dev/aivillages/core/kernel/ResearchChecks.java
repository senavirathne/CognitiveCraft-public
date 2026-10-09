package dev.aivillages.core.kernel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static dev.aivillages.core.kernel.Budgets.Kind;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;
import static dev.aivillages.core.kernel.Outcomes.*;
import static dev.aivillages.core.kernel.VersionedSkillRepository.*;

/** Portable IMP-006 owner checks; JUnit invokes each group in CI. */
public final class ResearchChecks {
    private ResearchChecks() { }
    private static final String DIM = "minecraft:overworld";
    private static final ActorRef ACTOR = new ActorRef(UUID.randomUUID(), UUID.randomUUID(), DIM);
    private static final TrustedContext OWNER = new TrustedContext(new PrincipalRef(UUID.randomUUID()),
            new ScopeRef(UUID.randomUUID(), UUID.randomUUID()));
    private static final Cuboid AREA = new Cuboid(DIM, 2, 64, 2, 2, 64, 2);
    private static final ContainerRef CHEST = new ContainerRef(DIM, 4, 64, 2);
    private static final ObservationRef OBS = new ObservationRef(UUID.randomUUID(), 1, DIM);
    private static final CapabilityCatalog SPECS = id -> id.equals(CropDelivery.ID)
            ? Optional.of(CropDelivery.SPEC) : Optional.empty();
    private static final String CANDIDATE = candidate(0);
    private static final String WAIT_CANDIDATE = candidate(30);

    public static void main(String[] args) throws Exception {
        admittedAndStored();
        routingAndInvalidInput();
        repairAccounting();
        suppliedCandidateAndFixture();
        cancellationAndStorageFailure();
        adverseTrialsAndCancellationRaces();
        explicitRepairQuarantines();
        modelOutageAndQuota();
        System.out.println("IMP-006 research checks passed: 8 matrix groups");
    }
    private static ValidatedRequest request(int amount) {
        return new ValidatedRequest(new CapabilityRequest(CropDelivery.ID, Map.of(
                "actor", new ActorValue(ACTOR), "source", new AreaValue(AREA),
                "destination", new ContainerValue(CHEST), "amount", new IntValue(amount))),
                OWNER, OBS);
    }
    private static Map<String, Object> call(GatewayPrimitives.Operation op, String into,
                                             Map<String, Object> args) {
        return Map.of("op", "call", "kind", "primitive", "id", op.signature().id(),
                "version", 1L, "fingerprint", op.signature().fingerprint(),
                "args", args, "into", into);
    }
    private static String candidate(int waitTicks) {
        var actor = Map.<String,Object>of("actor", Map.of("param", "actor"));
        var source = Map.<String,Object>of("actor", Map.of("param", "actor"),
                "source", Map.of("param", "source"));
        var deposit = Map.<String,Object>of("actor", Map.of("param", "actor"),
                "destination", Map.of("param", "destination"), "amount", Map.of("int", 1L));
        List<Object> steps = new ArrayList<>();
        steps.add(call(GatewayPrimitives.Operation.HARVEST_NEXT_WHEAT, "h", source));
        if (waitTicks > 0) steps.add(Map.of("op", "repeat", "count", Map.of("int", (long)waitTicks),
                "body", List.of(call(GatewayPrimitives.Operation.OBSERVE_INVENTORY, "stock", actor))));
        steps.add(call(GatewayPrimitives.Operation.PICKUP_TRACKED_WHEAT, "p", actor));
        steps.add(call(GatewayPrimitives.Operation.TRANSFER_WHEAT, "d", deposit));
        var body = List.of(Map.of("op", "repeat", "count", Map.of("param", "amount"),
                "body", steps),
                Map.of("op", "result", "value", Map.of("param", "amount")));
        return StrictJson.canonical(Map.of("schema", 1L, "capability", CropDelivery.ID.name(),
                "capabilityVersion", 1L, "dependencies", List.of(), "body", body));
    }
    private static Budgets.ResearchLimits budget(long calls, long repairs, long trials) {
        long deadline = System.currentTimeMillis() + 45_000;
        EnumMap<Kind,Long> total = new EnumMap<>(Kind.class);
        total.put(Kind.CALLS, calls + 300); total.put(Kind.REPAIRS, repairs);
        total.put(Kind.INPUT_BYTES, 20_000L); total.put(Kind.OUTPUT_BYTES, 20_000L);
        total.put(Kind.CANDIDATES, 3L); total.put(Kind.TRIALS, trials);
        total.put(Kind.INSTRUCTIONS, 2_000L); total.put(Kind.OBSERVATIONS, 200L);
        total.put(Kind.TRAVEL_BLOCKS, 100L); total.put(Kind.ATTEMPTED_EFFECTS, 200L);
        total.put(Kind.COMMITTED_EFFECTS, 200L); total.put(Kind.ELAPSED_TICKS, 1_000L);
        Budgets.InferenceLimits inference = new Budgets.InferenceLimits(
                new Budgets.Limits(Map.of(Kind.CALLS, calls, Kind.REPAIRS, repairs,
                        Kind.INPUT_BYTES, 20_000L, Kind.OUTPUT_BYTES, 20_000L), deadline),
                10_000, 8_000);
        Budgets.ExecutionLimits trial = new Budgets.ExecutionLimits(new Budgets.Limits(
                Map.of(Kind.CALLS, 128L, Kind.INSTRUCTIONS, 300L, Kind.OBSERVATIONS, 60L,
                        Kind.TRAVEL_BLOCKS, 32L, Kind.ATTEMPTED_EFFECTS, 32L,
                        Kind.COMMITTED_EFFECTS, 32L, Kind.ELAPSED_TICKS, 100L), deadline), 5_000);
        return new Budgets.ResearchLimits(inference, trial, new Budgets.Limits(total, deadline));
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static final class Model implements GenerationPort {
        final List<String> responses = new ArrayList<>();
        final List<Generation.Role> roles = new ArrayList<>();
        CompletableFuture<Generation.Result> pending;
        Generation.Request lastRequest;
        boolean ignoreCancellation;
        boolean unavailable;
        Model(String... bodies) { responses.addAll(List.of(bodies)); }
        @Override public Generation.Descriptor descriptor() { return new Generation.Descriptor("ollama-chat-v1", "fixture-model", null); }
        @Override public Generation.Status status() { return new Generation.Status(unavailable ? Generation.State.UNAVAILABLE
                : pending != null && !pending.isDone() ? Generation.State.IN_FLIGHT : Generation.State.READY,
                0, null, pending != null && !pending.isDone() ? Generation.Compute.RUNNING : Generation.Compute.NOT_STARTED, false, false); }
        @Override public Generation.Handle generate(Generation.Request request,
                Budgets.InferenceLimits limits, Budgets.Ledger usage) {
            roles.add(request.role());
            lastRequest = request;
            CompletableFuture<Generation.Result> answer = new CompletableFuture<>();
            pending = answer;
            if (unavailable) answer.complete(new Generation.Result(request.id(),
                    request.bound().context(), request.role(), Generation.Outcome.MODEL_UNAVAILABLE,
                    Reason.MODEL_UNAVAILABLE, null,
                    new Generation.Descriptor("ollama-chat-v1", "unavailable", null),
                    new Generation.Usage(0, 0, -1, -1, Generation.Precision.UNKNOWN),
                    Generation.Compute.NOT_STARTED));
            if (!responses.isEmpty()) {
                String body = responses.remove(0);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                Budgets.ResponseAllowance allowance = limits.chargeCall(usage, 128,
                        request.role() == Generation.Role.REPAIR);
                allowance.accept(bytes.length); allowance.close();
                answer.complete(new Generation.Result(request.id(), request.bound().context(),
                        request.role(), Generation.Outcome.CANDIDATE, null, body,
                        new Generation.Descriptor("ollama-chat-v1", "fixture-model", null),
                        new Generation.Usage(128, bytes.length, -1, -1,
                                Generation.Precision.UNKNOWN), Generation.Compute.COMPLETED));
            }
            return new Generation.Handle() {
                @Override public UUID id() { return request.id(); }
                @Override public java.util.concurrent.CompletionStage<Generation.Result> result() {
                    return answer;
                }
                @Override public boolean cancel() {
                    return ignoreCancellation ? false : answer.cancel(false);
                }
                @Override public Generation.Compute compute() {
                    return answer.isDone() ? Generation.Compute.COMPLETED
                            : Generation.Compute.RUNNING;
                }
            };
        }
    }
    private static final class LiveGateway implements GatewayPort {
        CropFixtureRunner.FakeCrops fake;
        int calls;
        int stallAfter = Integer.MAX_VALUE;
        boolean failTransfer, dropDeposit;
        @Override public ActionHandle start(ValidatedRequest request, RunCorrelation run,
                PrimitiveRequirement primitive, Map<String, Value> arguments, Budgets.Ledger usage) {
            if (fake == null) fake = new CropFixtureRunner.FakeCrops(request, usage);
            calls++;
            return fake.start(request, run, primitive, arguments, usage);
        }
        @Override public ActionReceipt poll(ActionHandle handle) {
            if (calls >= stallAfter) return new ActionReceipt(handle, false, 0, List.of(), null);
            if (failTransfer && calls == 3) {
                fake.cancel(handle);
                return new ActionReceipt(handle, true, 0, List.of(), Reason.TARGET_UNAVAILABLE);
            }
            ActionReceipt actual = fake.poll(handle);
            if (dropDeposit && calls == 3)
                return new ActionReceipt(handle, true, actual.committedEffects(),
                        List.of(), actual.reason(), actual.result(), actual.observation());
            return actual;
        }
        @Override public ActionReceipt cancel(ActionHandle handle) { return fake.cancel(handle); }
    }
    private static final class Harness implements AutoCloseable {
        final Path world;
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final ResearchAdmissionController.DecisionGuard guard = new ResearchAdmissionController.DecisionGuard();
        final VersionedSkillRepository repository;
        final Model model;
        final LiveGateway gateway = new LiveGateway();
        final ResearchAdmissionController controller;
        final CompletableFuture<Void> fixtureGate = new CompletableFuture<>();
        final CompletableFuture<Void> publicationGate = new CompletableFuture<>();
        CompletableFuture<Void> quarantineGate = CompletableFuture.completedFuture(null);
        final ValidatedRequest bound = request(1);
        final ObservationSnapshot observed = new ObservationSnapshot(OBS, ObservationStatus.PRESENT,
                "source:" + UUID.nameUUIDFromBytes(AREA.toString().getBytes(StandardCharsets.UTF_8)),
                100, 1, Map.of("mature_wheat", 1L, "unknown_cells", 0L));
        final CapabilityResolver.Engine resolver;
        Harness(FaultInjector faults, String... responses) throws Exception {
            this(faults, false, false, Limits.defaults(), responses);
        }
        Harness(FaultInjector faults, boolean holdFixture, boolean holdPublication,
                String... responses) throws Exception {
            this(faults, holdFixture, holdPublication, Limits.defaults(), responses);
        }
        Harness(FaultInjector faults, boolean holdFixture, boolean holdPublication,
                Limits repositoryLimits, String... responses) throws Exception {
            this(faults, holdFixture, holdPublication, repositoryLimits, null, responses);
        }
        Harness(FaultInjector faults, boolean holdFixture, boolean holdPublication,
                Limits repositoryLimits, GenerationPort injectedGeneration, String... responses) throws Exception {
            world = Files.createTempDirectory("cc-research");
            if (!holdFixture) fixtureGate.complete(null);
            if (!holdPublication) publicationGate.complete(null);
            model = new Model(responses);
            repository = VersionedSkillRepository.open(world, new RuntimeSnapshot("minecraft-26.3",
                    SPECS, GatewayPrimitives.instance()), repositoryLimits, guard,
                    TrustedContext::equals, faults);
            ResearchAdmissionController.TrialArtifacts staging =
                    new ResearchAdmissionController.TrialArtifacts(repository);
            ResearchAdmissionController.TrialGrants grants =
                    new ResearchAdmissionController.TrialGrants(Clock.systemUTC());
            BoundedSkillExecutor executor = new BoundedSkillExecutor(staging, SPECS,
                    GatewayPrimitives.instance(), (actor, ref, owner) -> owner.equals(OWNER),
                    grants, new BoundedSkillExecutor.ControlPolicy() {
                        @Override public boolean mayInspect(TrustedContext caller, TrustedContext owner,
                                                              UUID run) { return caller.equals(owner); }
                        @Override public boolean mayCancel(TrustedContext caller, TrustedContext owner,
                                                             UUID run) { return caller.equals(owner); }
                    }, gateway, ignored -> { }, Clock.systemUTC(), () -> 100L,
                    new BoundedSkillExecutor.Settings(16, 16, 16, 64));
            resolver = new CapabilityResolver.Engine(SPECS, GatewayPrimitives.instance(),
                    CapabilityResolver.exactCatalog(repository), null,
                    CapabilityResolver.cropDeliverySupport(),
                    (actor, owner) -> owner.equals(OWNER), (actor, ref, owner) -> true,
                    (actor, effect, owner) -> owner.equals(OWNER),
                    (request, method, observation) -> null, () -> 100,
                    CapabilityResolver.Limits.defaults());
            CropFixtureRunner fixture = new CropFixtureRunner(staging, SPECS,
                    GatewayPrimitives.instance(), (actor, ref, owner) -> owner.equals(OWNER),
                    worker, Clock.systemUTC());
            ResearchAdmissionController.PublicationPort delegate =
                    ResearchAdmissionController.repositoryPublication(repository, worker);
            ResearchAdmissionController.PublicationPort gated =
                    new ResearchAdmissionController.PublicationPort() {
                        @Override public long revision() { return delegate.revision(); }
                        @Override public Optional<AdmissionRecord> admission(ArtifactRef ref) {
                            return delegate.admission(ref);
                        }
                        @Override public java.util.concurrent.CompletionStage<PublishResult> publish(
                                SkillArtifact artifact, AdmissionDecision decision, Provenance provenance) {
                            return publicationGate.thenCompose(ignored ->
                                    delegate.publish(artifact, decision, provenance));
                        }
                        @Override public java.util.concurrent.CompletionStage<PublishResult> quarantine(
                                ArtifactRef ref, QuarantineDecision decision) {
                            return quarantineGate.thenCompose(ignored ->
                                    delegate.quarantine(ref, decision));
                        }
                    };
            controller = new ResearchAdmissionController(SPECS, GatewayPrimitives.instance(),
                    repository, injectedGeneration == null ? model : injectedGeneration, (skill, bound, limits, usage, cancelled) ->
                            fixtureGate.thenCompose(ignored -> fixture.evaluate(
                                    skill, bound, limits, usage, cancelled)),
                    new ResearchAdmissionController.ExecutorTrialPort(executor), staging, grants,
                    gated, guard, resolver,
                    (actor, owner) -> owner.equals(OWNER),
                    (actor, effect, owner) -> owner.equals(OWNER), Clock.systemUTC(), worker,
                    new ResearchAdmissionController.Settings(3, 8, 2_048));
        }
        ResearchAdmissionController.Attempt start() {
            return controller.startMissing(resolver.resolve(bound, observed), bound,
                    budget(2, 1, 1));
        }
        ResearchAdmissionController.Status end(ResearchAdmissionController.Attempt attempt) {
            for (int i = 0; i < 8_000; i++) {
                ResearchAdmissionController.Status result = attempt.tick(OWNER);
                if (result.phase() == ResearchAdmissionController.Phase.TERMINAL) return result;
                LockSupport.parkNanos(250_000);
            }
            throw new AssertionError("Attempt did not terminate: " + attempt.status(OWNER));
        }
        ResearchAdmissionController.Status until(ResearchAdmissionController.Attempt attempt,
                                                 ResearchAdmissionController.Phase phase) {
            for (int i = 0; i < 8_000; i++) {
                ResearchAdmissionController.Status result = attempt.tick(OWNER);
                if (result.phase() == phase) return result;
                if (result.phase() == ResearchAdmissionController.Phase.TERMINAL)
                    throw new AssertionError("Unexpected terminal before " + phase + ": " + result);
                LockSupport.parkNanos(250_000);
            }
            throw new AssertionError("Attempt did not reach " + phase + ": " + attempt.status(OWNER));
        }
        @Override public void close() throws Exception {
            worker.shutdownNow(); worker.awaitTermination(5, TimeUnit.SECONDS);
            repository.close();
            try (var paths = Files.walk(world)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.delete(path); } catch (Exception failure) { throw new AssertionError(failure); }
                });
            }
        }
    }
    public static void admittedAndStored() throws Exception {
        try (Harness h = new Harness(FaultInjector.none(), CANDIDATE)) {
            var result = h.end(h.start());
            check(result.outcome().status() == ResearchStatus.ADMITTED, "admitted: " + result);
            check(result.outcome().published() && result.committedEffects() == 3
                    && result.receipts().size() == 3, "actual custody and publication");
            check(h.repository.resolve(result.outcome().artifact()).usable(), "durable admitted body");
            check(h.model.roles.equals(List.of(Generation.Role.INITIAL)), "one generation call");
            check(h.model.lastRequest.primitives().stream().map(PrimitiveSignature::id)
                    .collect(java.util.stream.Collectors.toSet()).equals(Set.of(
                            Operation.MOVE_TO_SOURCE.signature().id(),
                            Operation.MOVE_TO_DESTINATION.signature().id(),
                            Operation.HARVEST_NEXT_WHEAT.signature().id(),
                            Operation.OBSERVE_INVENTORY.signature().id(),
                            Operation.PICKUP_TRACKED_WHEAT.signature().id(),
                            Operation.TRANSFER_WHEAT.signature().id())),
                    "generation receives only the crop-relevant signature set");
            check(h.gateway.calls == 3, "one live run, no duplicate actions");
            check(result.usage().get(Kind.TRIALS) == 1L, "one trial budget");
        }
    }
    public static void brokerSharesGenerationButKeepsAdmissionAndTrialsSeparate() throws Exception {
        var model = new Model(CANDIDATE); var clock = Clock.systemUTC();
        try (var broker = new AIWorkBroker(model, clock, AIWorkBroker.Settings.defaults(),
                AIWorkBroker.sessionLimits(clock.millis()), request -> true, AIWorkBroker.Sharing.privateScopes());
             var first = new Harness(FaultInjector.none(), false, false, Limits.defaults(), broker);
             var second = new Harness(FaultInjector.none(), false, false, Limits.defaults(), broker)) {
            var a = first.start(); var b = second.start();
            check(broker.stats().queuedWork() == 1 && first.repository.status().bodyCount() == 0
                    && second.repository.status().bodyCount() == 0, "Broker did not coalesce before separate admission");
            broker.step(); broker.step();
            var left = first.end(a); var right = second.end(b);
            check(left.outcome().status() == ResearchStatus.ADMITTED && right.outcome().status() == ResearchStatus.ADMITTED
                    && left.committedEffects() == 3 && right.committedEffects() == 3
                    && left.outcome().modelCalls() == 1 && right.outcome().modelCalls() == 1
                    && model.roles.size() == 1 && broker.stats().calls() == 1,
                    "Generation sharing must not merge physical trials or admission decisions");
        }
    }
    public static void brokerCancellationCannotPromoteLateCandidate() throws Exception {
        var model = new Model(CANDIDATE); var clock = Clock.systemUTC();
        try (var broker = new AIWorkBroker(model, clock, AIWorkBroker.Settings.defaults(),
                AIWorkBroker.sessionLimits(clock.millis()), request -> true, AIWorkBroker.Sharing.privateScopes());
             var first = new Harness(FaultInjector.none(), false, false, Limits.defaults(), broker);
             var second = new Harness(FaultInjector.none(), false, false, Limits.defaults(), broker)) {
            var a = first.start(); var b = second.start(); broker.step(); a.cancel(OWNER); broker.step();
            var cancelled = first.end(a); var accepted = second.end(b);
            check(cancelled.outcome().status() == ResearchStatus.CANCELLED && cancelled.outcome().modelCalls() == 1
                    && first.gateway.calls == 0 && first.repository.status().bodyCount() == 0
                    && accepted.outcome().status() == ResearchStatus.ADMITTED && model.roles.size() == 1,
                    "Cancelled subscriber was charged honestly but cannot trial or publish late work");
        }
    }
    public static void brokerRepairConsumesOriginalResearchParent() throws Exception {
        var model = new Model("{}", CANDIDATE); var clock = Clock.systemUTC();
        try (var broker = new AIWorkBroker(model, clock, AIWorkBroker.Settings.defaults(),
                AIWorkBroker.sessionLimits(clock.millis()), request -> true, AIWorkBroker.Sharing.privateScopes());
             var harness = new Harness(FaultInjector.none(), false, false, Limits.defaults(), broker)) {
            var attempt = harness.start(); ResearchAdmissionController.Status status = null;
            for (int i = 0; i < 8000; i++) {
                broker.step(); status = attempt.tick(OWNER);
                if (status.phase() == ResearchAdmissionController.Phase.TERMINAL) break;
                LockSupport.parkNanos(250_000);
            }
            check(status != null && status.outcome() != null && status.outcome().status() == ResearchStatus.ADMITTED
                    && status.outcome().modelCalls() == 2 && status.usage().get(Kind.REPAIRS) == 1
                    && model.roles.equals(List.of(Generation.Role.INITIAL, Generation.Role.REPAIR)),
                    "Broker must not refresh research repair/call allowances");
        }
    }
    public static void routingAndInvalidInput() throws Exception {
        try (Harness h = new Harness(FaultInjector.none(), CANDIDATE)) {
            var blocked = h.resolver.resolve(h.bound, new ObservationSnapshot(OBS,
                    ObservationStatus.ABSENT, h.observed.targetIdentity(), 100, 1,
                    Map.of("mature_wheat", 0L, "unknown_cells", 0L)));
            var result = h.end(h.controller.startMissing(blocked, h.bound, budget(2, 1, 1)));
            check(result.outcome().status() == ResearchStatus.BLOCKED
                    && result.outcome().reason() == Reason.RESOURCE_MISSING, "route shortage");
            check(h.model.roles.isEmpty() && h.gateway.calls == 0, "shortage never synthesizes");
        }
        try (Harness h = new Harness(FaultInjector.none(), CANDIDATE)) {
            var invalid = new ValidatedRequest(new CapabilityRequest(CropDelivery.ID,
                    Map.of("actor", new ActorValue(ACTOR))), OWNER, OBS);
            var result = h.end(h.controller.startMissing(h.resolver.resolve(h.bound, h.observed),
                    invalid, budget(2, 1, 1)));
            check(result.outcome().status() == ResearchStatus.BLOCKED && h.model.roles.isEmpty(),
                    "invalid bound request never reaches generator");
        }
        for (ResolutionStatus rejected : ResolutionStatus.values()) {
            if (rejected == ResolutionStatus.MISSING_IMPLEMENTATION) continue;
            try (Harness h = new Harness(FaultInjector.none(), CANDIDATE)) {
                Reason reason = rejected == ResolutionStatus.RESOLVED
                        || rejected == ResolutionStatus.NEEDS_PLANNING ? null
                        : rejected == ResolutionStatus.UNAUTHORIZED ? Reason.AUTHORITY_DENIED
                        : rejected == ResolutionStatus.UNSUPPORTED_RUNTIME
                        ? Reason.UNSUPPORTED_PRIMITIVE : Reason.ARTIFACT_INCOMPATIBLE;
                ArtifactRef ref = rejected == ResolutionStatus.RESOLVED
                        ? new ArtifactRef(CropDelivery.ID, "a".repeat(64)) : null;
                var route = new CapabilityResolver.Decision(
                        new Resolution(rejected, reason, ref, "snapshot"),
                        rejected == ResolutionStatus.RESOLVED ? h.bound : null,
                        Set.of(), h.observed, h.repository.status().revision(),
                        List.of(), 0, 0, true, false, null);
                var outcome = h.end(h.controller.startMissing(route, h.bound, budget(2, 1, 1)));
                check(outcome.outcome().status() == ResearchStatus.BLOCKED
                        && h.model.roles.isEmpty() && h.gateway.calls == 0,
                        "route " + rejected + " did not start research");
            }
        }
        try (Harness h = new Harness(FaultInjector.none(), CANDIDATE)) {
            boolean rejected = false;
            try { h.controller.startRepair(h.bound, null, null,
                    Reason.ARTIFACT_INVALID, budget(2, 1, 1)); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected && h.model.roles.isEmpty(), "repair requires exact artifact and defect");
        }
    }
    public static void repairAccounting() throws Exception {
        try (Harness h = new Harness(FaultInjector.none(), "{", CANDIDATE)) {
            var result = h.end(h.start());
            check(result.outcome().status() == ResearchStatus.ADMITTED, "bounded repair: " + result);
            check(h.model.roles.equals(List.of(Generation.Role.INITIAL, Generation.Role.REPAIR)),
                    "repair role and parent allowance");
            check(result.outcome().modelCalls() == 2L && result.usage().get(Kind.REPAIRS) == 1L
                    && result.usage().get(Kind.CANDIDATES) == 2L, "cumulative ledger");
        }
        try (Harness h = new Harness(FaultInjector.none(), "{", "{", CANDIDATE)) {
            var result = h.end(h.start());
            check(result.outcome().status() == ResearchStatus.NOT_ADMITTED, "finite invalid repair");
            check(h.model.roles.size() == 2 && h.gateway.calls == 0, "third call denied");
        }
    }
    public static void suppliedCandidateAndFixture() throws Exception {
        try (Harness h = new Harness(FaultInjector.none())) {
            var result = h.end(h.controller.evaluateCandidate(h.bound, CANDIDATE, budget(0, 0, 1)));
            check(result.outcome().status() == ResearchStatus.ADMITTED && h.model.roles.isEmpty(),
                    "supplied candidate needs no model");
        }
        try (Harness h = new Harness(FaultInjector.none())) {
            var result = h.end(h.controller.evaluateCandidate(h.bound, WAIT_CANDIDATE,
                    budget(0, 0, 1)));
            check(result.outcome().status() == ResearchStatus.ADMITTED && h.model.roles.isEmpty(),
                    "bounded wait loop passes both fake parameter cases and live trial: " + result);
        }
        try (Harness h = new Harness(FaultInjector.none())) {
            String falseSkill = StrictJson.canonical(Map.of("schema", 1L,
                    "capability", CropDelivery.ID.name(), "capabilityVersion", 1L,
                    "dependencies", List.of(), "body", List.of(Map.of("op", "result",
                            "value", Map.of("param", "amount")))));
            var result = h.end(h.controller.evaluateCandidate(h.bound, falseSkill, budget(0, 0, 1)));
            check(result.outcome().status() == ResearchStatus.NOT_ADMITTED
                    && h.gateway.calls == 0, "false completion fails before live trial");
        }
        try (Harness h = new Harness(FaultInjector.none(), CANDIDATE.replace(
                Operation.HARVEST_NEXT_WHEAT.signature().id(), "cognitivecraft:forbidden"))) {
            var result = h.end(h.controller.startMissing(h.resolver.resolve(h.bound, h.observed),
                    h.bound, budget(1, 0, 1)));
            check(result.outcome().status() == ResearchStatus.NOT_ADMITTED
                    && h.gateway.calls == 0 && !result.diagnostics().isEmpty(),
                    "unregistered call never touches the world");
        }
    }
    public static void cancellationAndStorageFailure() throws Exception {
        try (Harness h = new Harness(FaultInjector.none())) {
            h.model.ignoreCancellation = true;
            var attempt = h.start();
            attempt.cancel(OWNER);
            Generation.Request request = h.model.lastRequest;
            h.model.pending.complete(new Generation.Result(request.id(), request.bound().context(),
                    request.role(), Generation.Outcome.CANDIDATE, null, CANDIDATE,
                    new Generation.Descriptor("ollama-chat-v1", "late", null),
                    new Generation.Usage(0, 0, -1, -1, Generation.Precision.UNKNOWN),
                    Generation.Compute.STOP_UNCONFIRMED));
            check(h.end(attempt).outcome().status() == ResearchStatus.CANCELLED
                    && h.gateway.calls == 0, "cancel generation");
        }
        try (Harness h = new Harness(point -> {
            if (point == FaultPoint.AFTER_BODY_WRITE) throw new java.io.IOException("fixture write fault");
        }, CANDIDATE)) {
            var result = h.end(h.start());
            check(result.outcome().status() == ResearchStatus.BLOCKED
                    && result.outcome().reason() == Reason.STORAGE_UNAVAILABLE
                    && result.committedEffects() == 3 && h.gateway.calls == 3,
                    "failed save keeps physical progress and does not repeat");
        }
    }
    public static void adverseTrialsAndCancellationRaces() throws Exception {
        try (Harness h = new Harness(FaultInjector.none(), CANDIDATE)) {
            h.gateway.failTransfer = true;
            var result = h.end(h.start());
            check(result.outcome().status() == ResearchStatus.BLOCKED
                    && result.outcome().reason() == Reason.TARGET_UNAVAILABLE
                    && result.committedEffects() == 2 && h.model.roles.size() == 1,
                    "environment blockage preserves harvest/pickup without code repair");
        }
        try (Harness h = new Harness(FaultInjector.none(), CANDIDATE)) {
            h.gateway.dropDeposit = true;
            var result = h.end(h.start());
            check(result.outcome().status() == ResearchStatus.NOT_ADMITTED
                    && result.committedEffects() == 3 && h.repository.status().bodyCount() == 0,
                    "reported result and preexisting stock cannot replace custody receipts");
        }
        try (Harness h = new Harness(FaultInjector.none(), true, false, CANDIDATE)) {
            var attempt = h.start();
            h.until(attempt, ResearchAdmissionController.Phase.FIXTURING);
            attempt.cancel(OWNER);
            var before = attempt.status(OWNER).usage();
            h.fixtureGate.complete(null);
            check(h.end(attempt).outcome().status() == ResearchStatus.CANCELLED
                    && h.gateway.calls == 0, "late fixture cannot start live effects");
            h.worker.submit(() -> { }).get(5, TimeUnit.SECONDS);
            check(before.equals(attempt.status(OWNER).usage()),
                    "late fixture did not debit the cancelled allowance");
        }
        try (Harness h = new Harness(FaultInjector.none(), CANDIDATE)) {
            h.gateway.stallAfter = 2;
            var attempt = h.start();
            var live = h.until(attempt, ResearchAdmissionController.Phase.TRIALING);
            for (int i = 0; i < 12 && h.gateway.calls < 2; i++) live = attempt.tick(OWNER);
            check(h.gateway.calls == 2, "partial trial reached stalled pickup");
            attempt.cancel(OWNER);
            var result = h.end(attempt);
            check(result.outcome().status() == ResearchStatus.CANCELLED
                    && result.committedEffects() == 1 && h.gateway.calls == 2,
                    "cancel retains one real committed effect and stops future actions");
        }
        try (Harness h = new Harness(FaultInjector.none(), false, true, CANDIDATE)) {
            var attempt = h.start();
            h.until(attempt, ResearchAdmissionController.Phase.PUBLISHING);
            attempt.cancel(OWNER);
            h.publicationGate.complete(null);
            var result = h.end(attempt);
            check(result.outcome().status() == ResearchStatus.CANCELLED
                    && result.committedEffects() == 3 && h.repository.status().bodyCount() == 0,
                    "cancel before publication prevents admission but retains delivered wheat");
        }
        try (Harness h = new Harness(FaultInjector.none(), false, true, CANDIDATE)) {
            var attempt = h.start();
            var publishing = h.until(attempt, ResearchAdmissionController.Phase.PUBLISHING);
            h.publicationGate.complete(null);
            for (int i = 0; i < 8_000 && h.repository.status().bodyCount() == 0; i++)
                LockSupport.parkNanos(250_000);
            check(h.repository.status().bodyCount() == 1, "repository committed before callback tick");
            attempt.cancel(OWNER);
            var result = h.end(attempt);
            check(result.outcome().status() == ResearchStatus.ADMITTED && result.outcome().published(),
                    "already committed publication is reported honestly after cancellation");
            check(publishing.committedEffects() == 3, "live effects preceded publication");
        }
        try (Harness h = new Harness(FaultInjector.none(), CANDIDATE)) {
            var attempt = h.start();
            TrustedContext foreign = new TrustedContext(new PrincipalRef(UUID.randomUUID()),
                    OWNER.scope());
            boolean denied = false;
            try { attempt.status(foreign); }
            catch (SecurityException expected) { denied = true; }
            check(denied, "foreign principal cannot inspect research status");
            attempt.cancel(OWNER);
        }
    }
    public static void explicitRepairQuarantines() throws Exception {
        try (Harness h = new Harness(FaultInjector.none(), CANDIDATE)) {
            var admitted = h.end(h.start());
            ArtifactRef original = admitted.outcome().artifact();
            EvidenceRef defect = new EvidenceRef("observed-defect", "trusted-runtime:1",
                    OWNER.scope().worldId().toString());
            var untrusted = new QuarantineDecision(UUID.randomUUID(), OWNER, defect,
                    Reason.ARTIFACT_INVALID);
            check(h.repository.quarantine(original, untrusted).status() == PublishStatus.UNAUTHORIZED,
                    "ordinary caller cannot quarantine an artifact");
            var repair = h.controller.startRepair(h.bound, original, defect,
                    Reason.ARTIFACT_INVALID, budget(1, 1, 1));
            h.until(repair, ResearchAdmissionController.Phase.GENERATING);
            check(h.repository.resolve(original).admission().status() == AdmissionStatus.QUARANTINED
                    && h.model.roles.equals(List.of(Generation.Role.INITIAL,
                            Generation.Role.REPAIR)), "defective exact ref quarantined before repair model");
            repair.cancel(OWNER);
            check(h.end(repair).outcome().status() == ResearchStatus.CANCELLED
                    && h.repository.resolve(original).admission().status()
                            == AdmissionStatus.QUARANTINED, "cancel does not restore defective artifact");
        }
        try (Harness h = new Harness(FaultInjector.none(), CANDIDATE)) {
            ArtifactRef original = h.end(h.start()).outcome().artifact();
            h.quarantineGate = new CompletableFuture<>();
            var repair = h.controller.startRepair(h.bound, original,
                    new EvidenceRef("defect-before-save", "trusted-runtime:1",
                            OWNER.scope().worldId().toString()),
                    Reason.ARTIFACT_INVALID, budget(1, 1, 1));
            check(repair.status(OWNER).phase() == ResearchAdmissionController.Phase.QUARANTINING,
                    "repair waits for durable quarantine");
            repair.cancel(OWNER);
            h.quarantineGate.complete(null);
            check(h.end(repair).outcome().status() == ResearchStatus.CANCELLED
                    && h.repository.resolve(original).admission().status() == AdmissionStatus.ADMITTED
                    && h.model.roles.equals(List.of(Generation.Role.INITIAL)),
                    "cancel before quarantine commit prevents mutation and repair generation");
        }
    }
    public static void modelOutageAndQuota() throws Exception {
        try (Harness h = new Harness(FaultInjector.none())) {
            h.model.unavailable = true;
            var result = h.end(h.start());
            check(result.outcome().status() == ResearchStatus.BLOCKED
                    && result.outcome().reason() == Reason.MODEL_UNAVAILABLE
                    && result.outcome().modelCalls() == 0 && h.gateway.calls == 0,
                    "local model outage cannot cause trial/admission");
        }
        var limited = new Limits(48, 64, 8_192, 4_194_304, 8, 16, 256);
        try (Harness h = new Harness(FaultInjector.none(), false, false, limited, CANDIDATE)) {
            var result = h.end(h.start());
            check(result.outcome().status() == ResearchStatus.BLOCKED
                    && result.outcome().reason() == Reason.STORAGE_LIMIT_REACHED
                    && result.committedEffects() == 3 && h.gateway.calls == 3,
                    "quota refusal follows live progress without repeating effects");
        }
    }
}
