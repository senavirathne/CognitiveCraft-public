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
        ValidatedRequest changed = variant(bound,RecipeProduction.is(bound.request().capability()) ? 5 : 2,true);
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
                new EvidenceRef(sha256(fact), RecipeProduction.is(bound.request().capability()) ? "transformation-fixture:1"
                        : bound.request().capability().equals(OakLogDelivery.ID) ? "resource-fixture:1" : "crop-fixture:1",
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
        GatewayPort fake = RecipeProduction.is(test.request().capability()) ? new FakePlanks(test,usage) : new FakeCrops(test, usage);
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
            if(args.get("source") instanceof ContainerValue c) {
                var box=c.value();args.put("source",new ContainerValue(new ContainerRef(box.dimension(),box.x()+1,box.y(),box.z())));
            } else {
            Cuboid source = ((AreaValue)args.get("source")).value();
            int dx = source.maxX() == Integer.MAX_VALUE ? -1 : 1;
            args.put("source", new AreaValue(new Cuboid(source.dimension(), source.minX() + dx,
                    source.minY(), source.minZ(), source.maxX() + dx, source.maxY(), source.maxZ())));
            }
            ContainerRef target = ((ContainerValue)args.get("destination")).value();
            int tx = target.x() == Integer.MAX_VALUE ? -1 : 1;
            args.put("destination", new ContainerValue(new ContainerRef(target.dimension(),
                    target.x() + tx, target.y(), target.z())));
        }
        return new ValidatedRequest(new CapabilityRequest(original.request().capability(), args),
                original.context(), original.observation());
    }

    /** Explicit fake fixture only; passing this data simulation never proves Minecraft crafting. */
    private static final class FakePlanks implements GatewayPort {
        final ValidatedRequest bound;final Budgets.Ledger usage;
        final Map<ActionHandle,Map.Entry<RunCorrelation,Map.Entry<Operation,Map<String,Value>>>> pending=new HashMap<>();
        boolean prepared,inputHeld;int position,stock,outputs,delivered,sequence;UUID batch;
        final RecipeTransaction.Snapshot recipe;
        final RecipeProduction.Definition definition;
        String custody=RecipeTransaction.fingerprint("fake-custody:0");
        FakePlanks(ValidatedRequest bound,Budgets.Ledger usage) {this.bound=bound;this.usage=usage;
            definition=RecipeProduction.of(bound.request().capability());stock=definition.inputs(amount());
            recipe=new RecipeTransaction.Snapshot(definition.recipe(),RecipeTransaction.fingerprint("fake-static-recipe"),1,definition.inputPerBatch(),4,0);}
        int amount() {return Math.toIntExact(((IntValue)bound.request().arguments().get("amount")).value());}
        public ActionHandle start(ValidatedRequest request,RunCorrelation run,PrimitiveRequirement primitive,Map<String,Value> args,Budgets.Ledger ignored) {
            var handle=new ActionHandle(UUID.randomUUID(),run.runId());var op=GatewayPrimitives.instance().operation(primitive).orElse(null);
            if(op!=null)pending.put(handle,Map.entry(run,Map.entry(op,Map.copyOf(args))));return handle;
        }
        public ActionReceipt poll(ActionHandle handle) {
            var action=pending.remove(handle);
            if(action==null)return fail(handle,Reason.UNSUPPORTED_PRIMITIVE);
            var op=action.getValue().getKey();var args=action.getValue().getValue();
            if(!bound.request().arguments().get("actor").equals(args.get("actor"))
                    || args.containsKey("source") && !bound.request().arguments().get("source").equals(args.get("source"))
                    || args.containsKey("destination") && !bound.request().arguments().get("destination").equals(args.get("destination")))
                return fail(handle,Reason.REQUEST_INVALID);
            if(op==Operation.OBSERVE_PLANK_INPUTS || op==Operation.OBSERVE_STICK_INPUTS) {
                if(prepared || !new IntValue(amount()).equals(args.get("amount")))return fail(handle,Reason.REQUEST_INVALID);
                prepared=true;return new ActionReceipt(handle,true,0,List.of(),null,new BoolValue(true),null);
            }
            if(!prepared)return fail(handle,Reason.REQUEST_INVALID);
            if(op==Operation.MOVE_TO_INPUT || op==Operation.MOVE_TO_DESTINATION) {
                position=op==Operation.MOVE_TO_INPUT ? 1 : 2;
                return new ActionReceipt(handle,true,0,List.of(),null,new BoolValue(true),null);
            }
            ResourceDelivery.Stage stage;int q,consumed=0,produced=0,retained=0;
            if(op==Operation.WITHDRAW_OAK_LOG || op==Operation.WITHDRAW_OAK_PLANKS) {
                if(position!=1 || stock<definition.inputPerBatch() || inputHeld || outputs>0)return fail(handle,Reason.RESOURCE_MISSING);
                stock-=definition.inputPerBatch();inputHeld=true;batch=UUID.randomUUID();stage=ResourceDelivery.Stage.WITHDRAW;q=definition.inputPerBatch();
            } else if(op==Operation.CRAFT_OAK_PLANKS || op==Operation.CRAFT_STICKS) {
                if(!inputHeld || position!=1)return fail(handle,Reason.RESOURCE_MISSING);
                inputHeld=false;outputs=4;q=4;consumed=definition.inputPerBatch();produced=4;retained=4;stage=ResourceDelivery.Stage.CRAFT;
            } else if(op==Operation.TRANSFER_OAK_PLANKS || op==Operation.TRANSFER_STICKS) {
                if(position!=2 || outputs<1 || delivered>=amount() || !new IntValue(amount()).equals(args.get("amount")))return fail(handle,Reason.REQUEST_INVALID);
                q=Math.min(outputs,amount()-delivered);outputs-=q;delivered+=q;retained=outputs;stage=ResourceDelivery.Stage.DEPOSIT;
            } else return fail(handle,Reason.UNSUPPORTED_PRIMITIVE);
            usage.debit(Kind.ATTEMPTED_EFFECTS,1);usage.debit(Kind.COMMITTED_EFFECTS,1);
            String before=custody;custody=RecipeTransaction.fingerprint("fake-custody:"+(++sequence));
            var a=bound.request().arguments();var source=((ContainerValue)a.get("source")).value();
            var t=new RecipeTransaction.Transformation(recipe,consumed,produced,0,retained,before,custody,"fake-source","fake-target");
            var receipt=new ResourceDelivery.Receipt(UUID.randomUUID(),bound.context(),action.getKey().runId(),handle.id(),batch,
                    ((ActorValue)a.get("actor")).value(),ResourceLeases.point(source),((ContainerValue)a.get("destination")).value(),
                    stage==ResourceDelivery.Stage.WITHDRAW ? definition.input() : definition.output(),stage,q,
                    new ResourceDelivery.BlockPosition(source.dimension(),source.x(),source.y(),source.z()),List.of(),t);
            return new ActionReceipt(handle,true,1,List.of(),null,new IntValue(q),null,List.of(receipt));
        }
        ActionReceipt fail(ActionHandle h,Reason r) {return new ActionReceipt(h,true,0,List.of(),r);}
        public ActionReceipt cancel(ActionHandle h) {pending.remove(h);return fail(h,Reason.CANCELLED);}
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
            if(action.operation()!=Operation.MOVE_TO_DESTINATION && action.operation().oak()!=oak())
                return receipt(handle,Reason.REQUEST_INVALID);
            if (!bound.request().arguments().get("actor").equals(args.get("actor")))
                return receipt(handle, Reason.AUTHORITY_DENIED);
            try {
                return switch (action.operation()) {
                    case OBSERVE_SOURCE, OBSERVE_OAK_SOURCE -> {
                        usage.debit(Kind.OBSERVATIONS, 1);
                        if (!bound.request().arguments().get("source").equals(args.get("source")))
                            yield receipt(handle, Reason.TARGET_INVALID);
                        yield observed(handle, standing, "source");
                    }
                    case OBSERVE_INVENTORY, OBSERVE_OAK_INVENTORY -> {
                        usage.debit(Kind.OBSERVATIONS, 1);
                        yield observed(handle, carried.stream().mapToInt(Drop::count).sum(), "inventory");
                    }
                    case OBSERVE_CONTAINER, OBSERVE_OAK_CONTAINER -> {
                        usage.debit(Kind.OBSERVATIONS, 1);
                        if (!bound.request().arguments().get("destination").equals(args.get("destination")))
                            yield receipt(handle, Reason.TARGET_INVALID);
                        yield observed(handle, delivered, "container");
                    }
                    case HARVEST_NEXT_WHEAT, HARVEST_WHEAT, HARVEST_NEXT_OAK_LOG -> {
                        if ((action.operation() == Operation.HARVEST_NEXT_WHEAT || action.operation()==Operation.HARVEST_NEXT_OAK_LOG)
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
                    case PICKUP_TRACKED_WHEAT, PICKUP_WHEAT, PICKUP_TRACKED_OAK_LOGS -> {
                        if (action.operation() == Operation.PICKUP_WHEAT && !atSource(args))
                            yield receipt(handle, Reason.TARGET_INVALID);
                        if (ground.isEmpty()) yield receipt(handle, Reason.RESOURCE_MISSING);
                        usage.debit(Kind.ATTEMPTED_EFFECTS, 1);
                        usage.debit(Kind.COMMITTED_EFFECTS, 1);
                        Drop drop = ground.remove();
                        carried.add(drop);
                        yield effect(action, drop, CropDelivery.Stage.PICKUP, drop.count());
                    }
                    case TRANSFER_WHEAT, TRANSFER_OAK_LOGS -> {
                        if (!bound.request().arguments().get("destination").equals(args.get("destination")))
                            yield receipt(handle, Reason.TARGET_INVALID);
                        long requested = ((IntValue)args.get("amount")).value();
                        long available = carried.stream().mapToLong(Drop::count).sum();
                        if (requested > available) yield receipt(handle, Reason.RESOURCE_MISSING);
                        usage.debit(Kind.ATTEMPTED_EFFECTS, 1);
                        usage.debit(Kind.COMMITTED_EFFECTS, 1);
                        List<CropDelivery.CropReceipt> records = new ArrayList<>();
                        List<ResourceDelivery.Receipt> resources=new ArrayList<>();
                        long left = requested;
                        while (left > 0) {
                            Drop drop = carried.remove();
                            int count = (int)Math.min(left, drop.count());
                            if(oak()) resources.add(resource(action,drop.batch(),CropDelivery.Stage.DEPOSIT,count));
                            else records.add(crop(action, drop.batch(), CropDelivery.Stage.DEPOSIT, count));
                            if (count < drop.count())
                                carried.addFirst(new Drop(drop.batch(), drop.count() - count));
                            left -= count;
                        }
                        delivered += requested;
                        yield new ActionReceipt(handle, true, 1, records, null,
                                new IntValue(requested), null,resources);
                    }
                    case OBSERVE_PLANK_INPUTS,OBSERVE_STICK_INPUTS,MOVE_TO_INPUT,WITHDRAW_OAK_LOG,WITHDRAW_OAK_PLANKS,CRAFT_OAK_PLANKS,CRAFT_STICKS,TRANSFER_OAK_PLANKS,TRANSFER_STICKS -> receipt(handle,Reason.UNSUPPORTED_PRIMITIVE);
                    case MOVE, MOVE_TO_SOURCE, MOVE_TO_DESTINATION, APPROACH_OAK_LOG -> {
                        if ((action.operation() == Operation.MOVE_TO_SOURCE || action.operation()==Operation.APPROACH_OAK_LOG)
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
                            target, 0, 1, Map.of(oak() ? "oak_logs" : "wheat", count)));
        }
        private ActionReceipt effect(Action action, Drop drop, CropDelivery.Stage stage, int count) {
            if(oak()) return new ActionReceipt(action.handle(),true,1,List.of(),null,new IntValue(count),null,
                    List.of(resource(action,drop.batch(),stage,count)));
            return new ActionReceipt(action.handle(), true, 1,
                    List.of(crop(action, drop.batch(), stage, count)), null, new IntValue(count), null);
        }
        private boolean oak() { return bound.request().capability().equals(OakLogDelivery.ID); }
        private ResourceDelivery.Receipt resource(Action action,UUID batch,CropDelivery.Stage stage,int count) {
            var a=bound.request().arguments(); var source=((AreaValue)a.get("source")).value();
            return new ResourceDelivery.Receipt(UUID.randomUUID(),bound.context(),action.run().runId(),action.handle().id(),batch,
                    ((ActorValue)a.get("actor")).value(),source,((ContainerValue)a.get("destination")).value(),ResourceDelivery.OAK_LOG,
                    ResourceDelivery.Stage.valueOf(stage.name()),count,
                    new ResourceDelivery.BlockPosition(source.dimension(),source.minX(),source.minY(),source.minZ()),List.of(batch));
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
