package dev.aivillages.core.kernel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static dev.aivillages.core.kernel.Budgets.Kind;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;
import static dev.aivillages.core.kernel.Outcomes.*;

/** Finite, isolated fake crop world. The real trial still uses the server gateway. */
public final class CropFixtureRunner implements ResearchAdmissionController.FixturePort {
    private final BoundedSkillExecutor.ArtifactSource dependencies;
    private final CapabilityCatalog capabilities;
    private final PrimitiveCatalog primitives;
    private final EligibilityPolicy eligibility;
    private final Executor worker;
    private final Clock clock;

    public CropFixtureRunner(BoundedSkillExecutor.ArtifactSource dependencies,
            CapabilityCatalog capabilities, PrimitiveCatalog primitives,
            EligibilityPolicy eligibility, Executor worker, Clock clock) {
        this.dependencies = Objects.requireNonNull(dependencies);
        this.capabilities = Objects.requireNonNull(capabilities);
        this.primitives = Objects.requireNonNull(primitives);
        this.eligibility = Objects.requireNonNull(eligibility);
        this.worker = Objects.requireNonNull(worker);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public CompletionStage<ResearchAdmissionController.FixtureEvidence> evaluate(
            SkillCompiler.CompiledSkill skill, ValidatedRequest bound,
            Budgets.ResearchLimits limits, Budgets.Ledger usage, BooleanSupplier cancelled) {
        return CompletableFuture.supplyAsync(() -> check(skill, bound, limits, usage, cancelled),
                worker);
    }

    private ResearchAdmissionController.FixtureEvidence check(SkillCompiler.CompiledSkill skill,
            ValidatedRequest bound, Budgets.ResearchLimits limits, Budgets.Ledger usage,
            BooleanSupplier cancelled) {
        long before = usage.snapshot().getOrDefault(Kind.INSTRUCTIONS, 0L);
        ValidatedRequest small = variant(bound, 1, false);
        ValidatedRequest changed = variant(bound, 2, true);
        int cases = 0;
        for (ValidatedRequest test : List.of(small, changed)) {
            if (cancelled.getAsBoolean())
                return new ResearchAdmissionController.FixtureEvidence(false, null,
                        Reason.CANCELLED, cases, 0);
            Reason failed = run(skill.artifact(), test, limits, usage, cancelled);
            cases++;
            if (failed != null) return new ResearchAdmissionController.FixtureEvidence(false, null,
                    failed, cases, Math.min(4_096, usage.snapshot().getOrDefault(
                            Kind.INSTRUCTIONS, 0L) - before));
        }
        String fact = skill.artifact().descriptor().ref().sha256() + ":" + small.request()
                + ":" + changed.request() + ":two-custody-cases";
        return new ResearchAdmissionController.FixtureEvidence(true,
                new EvidenceRef(sha256(fact), "crop-fixture:1",
                        bound.context().scope().worldId().toString()),
                null, cases, Math.min(4_096, usage.snapshot().getOrDefault(
                        Kind.INSTRUCTIONS, 0L) - before));
    }

    private Reason run(SkillArtifact artifact, ValidatedRequest test,
                       Budgets.ResearchLimits limits, Budgets.Ledger usage,
                       BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) return Reason.CANCELLED;
        ArtifactRef ref = artifact.descriptor().ref();
        BoundedSkillExecutor.ArtifactSource source = new BoundedSkillExecutor.ArtifactSource() {
            @Override public Optional<SkillArtifact> body(ArtifactRef queried) {
                return ref.equals(queried) ? Optional.of(artifact) : dependencies.body(queried);
            }
            @Override public Optional<AdmissionRecord> admission(ArtifactRef queried) {
                return ref.equals(queried) ? Optional.of(new AdmissionRecord(
                        ref, AdmissionStatus.CANDIDATE, null, null, 0))
                        : dependencies.admission(queried);
            }
            @Override public Optional<Compatibility> compatibility(ArtifactRef queried) {
                return ref.equals(queried) ? Optional.of(new Compatibility(ref,
                        CompatibilityStatus.COMPATIBLE, List.of(), "fixture:1"))
                        : dependencies.compatibility(queried);
            }
        };
        FakeCrops fake = new FakeCrops(test, usage);
        AtomicLong ticks = new AtomicLong();
        UUID authorization = UUID.randomUUID();
        BoundedSkillExecutor.TrialPermit permit = new BoundedSkillExecutor.TrialPermit(ref,
                test.context(), authorization, limits.total().deadlineEpochMillis());
        BoundedSkillExecutor executor = new BoundedSkillExecutor(source, capabilities, primitives,
                (actor, queried, owner) -> owner.equals(test.context())
                        && eligibility.mayUse(actor, queried, owner),
                (given, request, envelope) -> given.equals(permit) && request.equals(test),
                new BoundedSkillExecutor.ControlPolicy() {
                    @Override public boolean mayInspect(TrustedContext caller, TrustedContext owner,
                                                        UUID run) { return owner.equals(caller); }
                    @Override public boolean mayCancel(TrustedContext caller, TrustedContext owner,
                                                       UUID run) { return owner.equals(caller); }
                }, fake, ignored -> { }, clock, ticks::get,
                new BoundedSkillExecutor.Settings(16, 16, 8, 64));
        Budgets.ExecutionLimits envelope = new Budgets.ExecutionLimits(limits.total(),
                limits.trial().actionDeadlineMillis());
        BoundedSkillExecutor.Start started = executor.startTrial(test, ref,
                new RunCorrelation(UUID.randomUUID(), ref, UUID.randomUUID()),
                envelope, usage, permit);
        if (started instanceof BoundedSkillExecutor.Rejected rejected) return rejected.reason();
        BoundedSkillExecutor.Run run = ((BoundedSkillExecutor.Started)started).run();
        for (int step = 0; step < 512; step++) {
            if (cancelled.getAsBoolean()) {
                run.cancel(test.context());
                return Reason.CANCELLED;
            }
            BoundedSkillExecutor.Progress progress = run.tick(test.context());
            if (progress.phase() == BoundedSkillExecutor.Phase.TERMINAL)
                return progress.summary().outcome().status() == ExecutionStatus.SUCCEEDED
                        ? null : progress.summary().outcome().reason() == Reason.BUDGET_EXHAUSTED
                        ? Reason.BUDGET_EXHAUSTED : Reason.ARTIFACT_INVALID;
            ticks.incrementAndGet();
        }
        run.cancel(test.context());
        return Reason.BUDGET_EXHAUSTED;
    }

    private static ValidatedRequest variant(ValidatedRequest original, int amount, boolean change) {
        Map<String, Value> args = new HashMap<>(original.request().arguments());
        args.put("amount", new IntValue(amount));
        if (change) {
            Cuboid source = ((AreaValue)args.get("source")).value();
            int dx = source.maxX() == Integer.MAX_VALUE ? -1 : 1;
            args.put("source", new AreaValue(new Cuboid(source.dimension(), source.minX() + dx,
                    source.minY(), source.minZ(), source.maxX() + dx, source.maxY(), source.maxZ())));
            ContainerRef target = ((ContainerValue)args.get("destination")).value();
            int tx = target.x() == Integer.MAX_VALUE ? -1 : 1;
            args.put("destination", new ContainerValue(new ContainerRef(target.dimension(),
                    target.x() + tx, target.y(), target.z())));
        }
        return new ValidatedRequest(new CapabilityRequest(original.request().capability(), args),
                original.context(), original.observation());
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static final class FakeCrops implements GatewayPort {
        private record Drop(UUID batch, int count) { }
        private record Action(ActionHandle handle, RunCorrelation run, Operation operation,
                              Map<String, Value> arguments) { }
        private final ValidatedRequest bound;
        private final Budgets.Ledger usage;
        private final Map<ActionHandle, Action> pending = new HashMap<>();
        private final ArrayDeque<Drop> ground = new ArrayDeque<>();
        private final ArrayDeque<Drop> carried = new ArrayDeque<>();
        private int standing;
        private long delivered;
        FakeCrops(ValidatedRequest bound, Budgets.Ledger usage) {
            this.bound = bound; this.usage = usage;
            standing = Math.toIntExact(((IntValue)bound.request().arguments().get("amount")).value());
        }
        @Override public ActionHandle start(ValidatedRequest request, RunCorrelation run,
                PrimitiveRequirement primitive, Map<String, Value> arguments, Budgets.Ledger ignored) {
            ActionHandle handle = new ActionHandle(UUID.randomUUID(), run.runId());
            Operation operation = GatewayPrimitives.instance().operation(primitive).orElse(null);
            pending.put(handle, new Action(handle, run, operation, Map.copyOf(arguments)));
            return handle;
        }
        @Override public ActionReceipt poll(ActionHandle handle) {
            Action action = pending.remove(handle);
            if (action == null || action.operation() == null)
                return receipt(handle, Reason.UNSUPPORTED_PRIMITIVE);
            Map<String, Value> args = action.arguments();
            if (!bound.request().arguments().get("actor").equals(args.get("actor")))
                return receipt(handle, Reason.AUTHORITY_DENIED);
            try {
                return switch (action.operation()) {
                    case OBSERVE_SOURCE -> {
                        usage.debit(Kind.OBSERVATIONS, 1);
                        if (!bound.request().arguments().get("source").equals(args.get("source")))
                            yield receipt(handle, Reason.TARGET_INVALID);
                        yield observed(handle, standing, "source");
                    }
                    case OBSERVE_INVENTORY -> {
                        usage.debit(Kind.OBSERVATIONS, 1);
                        yield observed(handle, carried.stream().mapToInt(Drop::count).sum(), "inventory");
                    }
                    case OBSERVE_CONTAINER -> {
                        usage.debit(Kind.OBSERVATIONS, 1);
                        if (!bound.request().arguments().get("destination").equals(args.get("destination")))
                            yield receipt(handle, Reason.TARGET_INVALID);
                        yield observed(handle, delivered, "container");
                    }
                    case HARVEST_NEXT_WHEAT, HARVEST_WHEAT -> {
                        if (action.operation() == Operation.HARVEST_NEXT_WHEAT
                                ? !bound.request().arguments().get("source").equals(args.get("source"))
                                : !atSource(args)) yield receipt(handle, Reason.TARGET_INVALID);
                        if (standing == 0) yield receipt(handle, Reason.RESOURCE_MISSING);
                        usage.debit(Kind.ATTEMPTED_EFFECTS, 1);
                        usage.debit(Kind.COMMITTED_EFFECTS, 1);
                        standing--;
                        Drop drop = new Drop(UUID.randomUUID(), 1);
                        ground.add(drop);
                        yield effect(action, drop, CropDelivery.Stage.HARVEST, 1);
                    }
                    case PICKUP_TRACKED_WHEAT, PICKUP_WHEAT -> {
                        if (action.operation() == Operation.PICKUP_WHEAT && !atSource(args))
                            yield receipt(handle, Reason.TARGET_INVALID);
                        if (ground.isEmpty()) yield receipt(handle, Reason.RESOURCE_MISSING);
                        usage.debit(Kind.ATTEMPTED_EFFECTS, 1);
                        usage.debit(Kind.COMMITTED_EFFECTS, 1);
                        Drop drop = ground.remove();
                        carried.add(drop);
                        yield effect(action, drop, CropDelivery.Stage.PICKUP, drop.count());
                    }
                    case TRANSFER_WHEAT -> {
                        if (!bound.request().arguments().get("destination").equals(args.get("destination")))
                            yield receipt(handle, Reason.TARGET_INVALID);
                        long requested = ((IntValue)args.get("amount")).value();
                        long available = carried.stream().mapToLong(Drop::count).sum();
                        if (requested > available) yield receipt(handle, Reason.RESOURCE_MISSING);
                        usage.debit(Kind.ATTEMPTED_EFFECTS, 1);
                        usage.debit(Kind.COMMITTED_EFFECTS, 1);
                        List<CropDelivery.CropReceipt> records = new ArrayList<>();
                        long left = requested;
                        while (left > 0) {
                            Drop drop = carried.remove();
                            int count = (int)Math.min(left, drop.count());
                            records.add(crop(action, drop.batch(), CropDelivery.Stage.DEPOSIT, count));
                            if (count < drop.count())
                                carried.addFirst(new Drop(drop.batch(), drop.count() - count));
                            left -= count;
                        }
                        delivered += requested;
                        yield new ActionReceipt(handle, true, 1, records, null,
                                new IntValue(requested), null);
                    }
                    case MOVE, MOVE_TO_SOURCE, MOVE_TO_DESTINATION -> {
                        if (action.operation() == Operation.MOVE_TO_SOURCE
                                && !bound.request().arguments().get("source").equals(args.get("source")))
                            yield receipt(handle, Reason.TARGET_INVALID);
                        if (action.operation() == Operation.MOVE_TO_DESTINATION
                                && !bound.request().arguments().get("destination").equals(args.get("destination")))
                            yield receipt(handle, Reason.TARGET_INVALID);
                        usage.debit(Kind.TRAVEL_BLOCKS, 1);
                        yield new ActionReceipt(handle, true, 0, List.of(), null,
                                new BoolValue(true), null);
                    }
                };
            } catch (Budgets.Exhausted exhausted) { return receipt(handle, Reason.BUDGET_EXHAUSTED); }
        }
        private boolean atSource(Map<String, Value> args) {
            Cuboid area = ((AreaValue)bound.request().arguments().get("source")).value();
            long x = ((IntValue)args.get("x")).value(), y = ((IntValue)args.get("y")).value();
            long z = ((IntValue)args.get("z")).value();
            return x >= area.minX() && x <= area.maxX() && y >= area.minY()
                    && y <= area.maxY() && z >= area.minZ() && z <= area.maxZ();
        }
        private ActionReceipt observed(ActionHandle handle, long count, String target) {
            return new ActionReceipt(handle, true, 0, List.of(), null, new IntValue(count),
                    new ObservationSnapshot(bound.observation(), count > 0
                            ? ObservationStatus.PRESENT : ObservationStatus.ABSENT,
                            target, 0, 1, Map.of("wheat", count)));
        }
        private ActionReceipt effect(Action action, Drop drop, CropDelivery.Stage stage, int count) {
            return new ActionReceipt(action.handle(), true, 1,
                    List.of(crop(action, drop.batch(), stage, count)), null, new IntValue(count), null);
        }
        private CropDelivery.CropReceipt crop(Action action, UUID batch, CropDelivery.Stage stage,
                                              int count) {
            return new CropDelivery.CropReceipt(UUID.randomUUID(), action.run().runId(), batch,
                    ((ActorValue)bound.request().arguments().get("actor")).value(),
                    ((AreaValue)bound.request().arguments().get("source")).value(),
                    ((ContainerValue)bound.request().arguments().get("destination")).value(),
                    stage, count);
        }
        private ActionReceipt receipt(ActionHandle handle, Reason reason) {
            return new ActionReceipt(handle, true, 0, List.of(), reason);
        }
        @Override public ActionReceipt cancel(ActionHandle handle) {
            pending.remove(handle);
            return receipt(handle, Reason.CANCELLED);
        }
    }
}
