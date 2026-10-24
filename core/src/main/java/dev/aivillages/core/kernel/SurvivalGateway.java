package dev.aivillages.core.kernel;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static dev.aivillages.core.kernel.Budgets.Kind;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;
import static dev.aivillages.core.kernel.Outcomes.Reason;

/**
 * One server-thread gateway for independently callable P0 operations. The WorldAccess
 * implementation must perform the final Minecraft checks immediately before each effect.
 * The gateway owns ephemeral handles/custody only; a crash never replays an unfinished action.
 */
public final class SurvivalGateway implements GatewayPort {
    public enum ObservationKind { SOURCE_CROPS, ACTOR_STOCK, CONTAINER_STOCK,
        SOURCE_OAK_LOGS, ACTOR_OAK_STOCK, CONTAINER_OAK_STOCK, SOURCE_BIRCH_LOGS, ACTOR_BIRCH_STOCK, CONTAINER_BIRCH_STOCK }
    /** Exact bounded context inspected by an observation primitive, never a whole-world assertion. */
    public record ObservationTarget(ObservationKind kind, Cuboid area, UUID entity) {
        public ObservationTarget {
            Objects.requireNonNull(kind); Objects.requireNonNull(area);
            if ((kind == ObservationKind.ACTOR_STOCK || kind == ObservationKind.ACTOR_OAK_STOCK || kind == ObservationKind.ACTOR_BIRCH_STOCK) != (entity != null))
                throw new IllegalArgumentException("Observation target identity");
            if (kind != ObservationKind.SOURCE_CROPS && kind != ObservationKind.SOURCE_OAK_LOGS && kind != ObservationKind.SOURCE_BIRCH_LOGS && (area.minX() != area.maxX()
                    || area.minY() != area.maxY() || area.minZ() != area.maxZ()))
                throw new IllegalArgumentException("Stock observation location");
        }
    }
    /** Only this gateway can issue physical evidence. Public snapshots alone are untrusted values. */
    public static final class ObservedEvidence {
        private final UUID source;
        private final TrustedContext owner;
        private final ActorRef actor;
        private final ObservationTarget target;
        private final ObservationSnapshot snapshot;
        private final long at;
        private final long sequence;
        private ObservedEvidence(UUID source, TrustedContext owner, ActorRef actor,
                                 ObservationTarget target, ObservationSnapshot snapshot, long at, long sequence) {
            this.source = source; this.owner = owner; this.actor = actor;
            this.target = target; this.snapshot = snapshot; this.at = at; this.sequence = sequence;
        }
        public UUID source() { return source; }
        public TrustedContext owner() { return owner; }
        public ActorRef actor() { return actor; }
        public ObservationTarget target() { return target; }
        public ObservationSnapshot snapshot() { return snapshot; }
        public long at() { return at; }
        /** Local issuance order; it is not a global Minecraft mutation revision. */
        public long sequence() { return sequence; }
    }
    public record Limits(int maxRuns, int maxHandles, int maxReceiptsPerRun,
                         int cellsPerPoll, int maxSourceCells, int navigationCallsPerPoll,
                         long maxActionTicks, long maxTravelBlocks) {
        public Limits {
            if (maxRuns < 1 || maxRuns > 64 || maxHandles < 1 || maxHandles > 512
                    || maxReceiptsPerRun < 1 || maxReceiptsPerRun > 512
                    || cellsPerPoll < 1 || cellsPerPoll > 256 || maxSourceCells < 1
                    || maxSourceCells > 4_096 || navigationCallsPerPoll < 1
                    || navigationCallsPerPoll > 4 || maxActionTicks < 1
                    || maxTravelBlocks < 1 || maxTravelBlocks > 4_096)
                throw new IllegalArgumentException("Finite gateway limits");
        }
    }
    public record Cell(String dimension, int x, int y, int z) {
        public Cell { Objects.requireNonNull(dimension); }
        public boolean inside(Cuboid area) {
            return dimension.equals(area.dimension()) && x >= area.minX() && x <= area.maxX()
                    && y >= area.minY() && y <= area.maxY() && z >= area.minZ() && z <= area.maxZ();
        }
        public String identity() { return dimension + "/" + x + "," + y + "," + z; }
    }
    public record ActorState(boolean loaded, boolean alive, String dimension,
                             int x, int y, int z, long inventoryWheat) {
        public ActorState {
            Objects.requireNonNull(dimension);
            if (inventoryWheat < 0) throw new IllegalArgumentException("Stock");
        }
    }
    /** UNKNOWN means unloaded/inaccessible; ABSENT means actually inspected and missing. */
    public record CropState(ObservationStatus status, boolean matureWheat, String identity) {
        public CropState { Objects.requireNonNull(status); Objects.requireNonNull(identity); }
    }
    public record StockState(ObservationStatus status, String identity, long wheat, long seeds) {
        public StockState {
            Objects.requireNonNull(status); Objects.requireNonNull(identity);
            if (wheat < 0 || seeds < 0) throw new IllegalArgumentException("Stock");
        }
    }
    public record Drop(UUID entityId, int wheat) {
        public Drop { Objects.requireNonNull(entityId); if (wheat < 1 || wheat > 64) throw new IllegalArgumentException("Drop"); }
    }
    public record DropState(ObservationStatus status, Cell cell, int wheat) {
        public DropState {
            Objects.requireNonNull(status); Objects.requireNonNull(cell);
            if (wheat < 0) throw new IllegalArgumentException("Drop stock");
        }
    }
    public record Harvest(boolean cropRemoved, List<Drop> spawnedWheat, Reason reason) {
        public Harvest { spawnedWheat = List.copyOf(spawnedWheat); }
    }
    public record Pickup(int collectedWheat, int dropRemaining, Reason reason) {
        public Pickup {
            if (collectedWheat < 0 || dropRemaining < 0) throw new IllegalArgumentException("Pickup");
        }
    }
    public record Transfer(int depositedWheat, Reason reason) {
        public Transfer {
            if (depositedWheat < 0 || depositedWheat > 1) throw new IllegalArgumentException("Transfer one");
        }
    }
    public record Movement(boolean reached, boolean impossible, int chargedWork, long travelBlocks) {
        public Movement {
            if (chargedWork < 0 || travelBlocks < 0) throw new IllegalArgumentException("Movement work");
        }
    }
    public record ItemBlock(ObservationStatus status, boolean eligible, String identity, int breakTicks) {
        public ItemBlock {
            Objects.requireNonNull(status); Objects.requireNonNull(identity);
            if (identity.length() > 128 || breakTicks < 0 || breakTicks > 200)
                throw new IllegalArgumentException("Block witness");
        }
    }
    public record ItemStock(ObservationStatus status, String identity, long quantity, long capacity,
                            String fingerprint, Reason reason) {
        public ItemStock {
            Objects.requireNonNull(status); Objects.requireNonNull(identity); Objects.requireNonNull(fingerprint);
            if (quantity < 0 || capacity < 0 || identity.length() > 128 || fingerprint.length() > 128)
                throw new IllegalArgumentException("Item stock");
        }
    }
    public record ItemDrop(UUID entityId, int quantity) {
        public ItemDrop {
            Objects.requireNonNull(entityId);
            if (quantity < 1 || quantity > 64) throw new IllegalArgumentException("Item drop");
        }
    }
    public record ItemDropState(ObservationStatus status, Cell cell, int quantity) {
        public ItemDropState {
            Objects.requireNonNull(status); Objects.requireNonNull(cell);
            if (quantity < 0) throw new IllegalArgumentException("Item drop state");
        }
    }
    public record ItemHarvest(boolean removed, List<ItemDrop> drops, Reason reason) {
        public ItemHarvest { drops = List.copyOf(drops); }
    }
    public record ItemPickup(int collected, int remaining, Reason reason) {
        public ItemPickup { if (collected < 0 || remaining < 0) throw new IllegalArgumentException("Item pickup"); }
    }
    public record ItemTransfer(int deposited, Reason reason) {
        public ItemTransfer { if (deposited < 0 || deposited > 1) throw new IllegalArgumentException("Item transfer"); }
    }
    public interface WorldAccess {
        default PlacementTransaction.Preflight placementPreflight(ValidatedRequest request) {return PlacementTransaction.Preflight.blocked(Reason.UNSUPPORTED_PRIMITIVE);}
        default PlacementTransaction.Mutation withdrawTableItem(ActorRef actor,PlacementTransaction.Preflight captured,String custody,String source) {return PlacementTransaction.Mutation.blocked(Reason.UNSUPPORTED_PRIMITIVE);}
        default PlacementTransaction.Mutation placeTable(ActorRef actor,PlacementTransaction.Preflight captured,String custody,String source) {return PlacementTransaction.Mutation.blocked(Reason.UNSUPPORTED_PRIMITIVE);}
        default Movement moveRecipe(ActorRef actor,Cell target,int maximum) {return move(actor,target,maximum);}
        /** Explicit placement MOVE keeps the approved block out of the worker's standing footprint. */
        default Movement movePlacement(ActorRef actor,Cell target,FacilityRef approved,int maximum) {return moveRecipe(actor,target,maximum);}
        default RecipeTransaction.Preflight recipePreflight(ValidatedRequest request) {return RecipeTransaction.Preflight.unsupported();}
        default RecipeTransaction.Mutation withdrawRecipeInput(ActorRef actor,ContainerRef source,
                RecipeTransaction.Preflight captured,String expectedCustody,String expectedSource) {
            return RecipeTransaction.Mutation.blocked(Reason.UNSUPPORTED_PRIMITIVE);
        }
        default RecipeTransaction.Mutation craftRecipe(ActorRef actor,RecipeTransaction.Preflight captured,String expectedCustody) {
            return RecipeTransaction.Mutation.blocked(Reason.UNSUPPORTED_PRIMITIVE);
        }
        default RecipeTransaction.Mutation depositRecipeOutput(ActorRef actor,ContainerRef destination,
                RecipeTransaction.Preflight captured,String expectedCustody,int quantity) {
            return RecipeTransaction.Mutation.blocked(Reason.UNSUPPORTED_PRIMITIVE);
        }
        /** Every method is called on the server thread; implementations must not force-load chunks. */
        boolean onGameThread();
        long tick();
        ActorState actor(ActorRef actor);
        boolean acquire(ActorRef actor, UUID runId);
        boolean holds(ActorRef actor, UUID runId);
        void release(ActorRef actor, UUID runId);
        void stopMovement(ActorRef actor);
        /** Null means permitted; includes current claim/game-rule/target-scope checks. */
        Reason permit(ValidatedRequest request, Effect effect, Cell target);
        CropState crop(Cell cell);
        DropState dropped(UUID entityId);
        StockState inventory(ActorRef actor);
        StockState container(ContainerRef destination);
        Movement move(ActorRef actor, Cell target, int maxNavigationCalls);
        /** Source approach may be closer than interaction reach; it still performs navigation only. */
        default Movement moveToSource(ActorRef actor, Cell target, int maxNavigationCalls) {
            return move(actor,target,maxNavigationCalls);
        }
        default Movement approachResource(ActorRef actor,Cell target,int maxNavigationCalls,ResourceDelivery.ItemIdentity item) {
            return item.equals(ResourceDelivery.WHEAT) ? moveToSource(actor,target,maxNavigationCalls) : move(actor,target,maxNavigationCalls);
        }
        /** Move toward this custody identity; the native adapter uses its actual interaction point. */
        default Movement approachResourceDrop(ActorRef actor,UUID dropId,int expected,int maximum,ResourceDelivery.ItemIdentity item) {
            ItemDropState state=resourceDrop(dropId,item);
            if(state.status()!=ObservationStatus.PRESENT || state.quantity()!=expected)
                return new Movement(false,true,0,0);
            return moveToSource(actor,state.cell(),maximum);
        }
        /** Real block destruction and drop spawn; no direct inventory insertion. */
        Harvest harvest(ActorRef actor, Cell target);
        /** Only a tracked physical drop can be picked up; validate identity/count and reach. */
        Pickup pickup(ActorRef actor, UUID dropId, int expectedRemaining);
        /** One actual wheat item debited from actor and credited to the current container. */
        Transfer transferOne(ActorRef actor, ContainerRef destination);
        /** Additive typed operations; old adapters retain exactly their wheat implementation. */
        default ItemBlock resourceBlock(Cell cell, ResourceDelivery.ItemIdentity item) {
            if (!item.equals(ResourceDelivery.WHEAT)) return new ItemBlock(ObservationStatus.UNKNOWN,false,cell.identity(),0);
            CropState c = crop(cell); return new ItemBlock(c.status(),c.matureWheat(),c.identity(),0);
        }
        default ItemStock resourceInventory(ActorRef actor, ResourceDelivery.ItemIdentity item) {
            StockState s = inventory(actor);
            return new ItemStock(item.equals(ResourceDelivery.WHEAT) ? s.status() : ObservationStatus.UNKNOWN,
                    s.identity(),item.equals(ResourceDelivery.WHEAT) ? s.wheat() : 0,0,"",null);
        }
        default ItemStock resourceContainer(ContainerRef target, ResourceDelivery.ItemIdentity item) {
            StockState s = container(target);
            return new ItemStock(item.equals(ResourceDelivery.WHEAT) ? s.status() : ObservationStatus.UNKNOWN,
                    s.identity(),item.equals(ResourceDelivery.WHEAT) ? s.wheat() : 0,0,"",null);
        }
        default Reason resourceBreakEligibility(ActorRef actor, Cell cell, ResourceDelivery.ItemIdentity item) {
            return item.equals(ResourceDelivery.WHEAT) ? null : Reason.UNSUPPORTED_PRIMITIVE;
        }
        default ItemHarvest resourceHarvest(ActorRef actor, Cell cell, ResourceDelivery.ItemIdentity item, long elapsedTicks) {
            if (!item.equals(ResourceDelivery.WHEAT)) return new ItemHarvest(false,List.of(),Reason.UNSUPPORTED_PRIMITIVE);
            Harvest h = harvest(actor,cell);
            return new ItemHarvest(h.cropRemoved(),h.spawnedWheat().stream().map(d -> new ItemDrop(d.entityId(),d.wheat())).toList(),h.reason());
        }
        default ItemDropState resourceDrop(UUID id,ResourceDelivery.ItemIdentity item) {
            DropState d = dropped(id);
            return new ItemDropState(item.equals(ResourceDelivery.WHEAT) ? d.status() : ObservationStatus.UNKNOWN,
                    d.cell(),item.equals(ResourceDelivery.WHEAT) ? d.wheat() : 0);
        }
        default ItemPickup resourcePickup(ActorRef actor,UUID drop,int expected,ResourceDelivery.ItemIdentity item) {
            if (!item.equals(ResourceDelivery.WHEAT)) return new ItemPickup(0,expected,Reason.UNSUPPORTED_PRIMITIVE);
            Pickup p = pickup(actor,drop,expected); return new ItemPickup(p.collectedWheat(),p.dropRemaining(),p.reason());
        }
        default ItemTransfer resourceTransferOne(ActorRef actor,ContainerRef target,ResourceDelivery.ItemIdentity item) {
            if (!item.equals(ResourceDelivery.WHEAT)) return new ItemTransfer(0,Reason.UNSUPPORTED_PRIMITIVE);
            Transfer t = transferOne(actor,target); return new ItemTransfer(t.depositedWheat(),t.reason());
        }
    }

    private static final class Batch {
        final UUID id;
        final Map<UUID, Integer> drops = new LinkedHashMap<>();
        final Cell sourceBlock;
        List<UUID> originalDrops = List.of();
        long picked;
        long deposited;
        Batch(UUID id,Cell sourceBlock) { this.id = id; this.sourceBlock=sourceBlock; }
    }
    private static final class Run {
        final ActorRef actor;
        final ValidatedRequest request;
        final UUID id;
        final Map<UUID, Batch> batches = new LinkedHashMap<>();
        final List<CropDelivery.CropReceipt> receipts = new ArrayList<>();
        final List<ResourceDelivery.Receipt> resourceReceipts = new ArrayList<>();
        RecipeTransaction.Preflight recipe;
        PlacementTransaction.Preflight placement;
        String placementCustody="",placementSource="";
        boolean tableWithdrawn,tablePlaced;
        String recipeCustody="",recipeSource="";
        UUID craftBatch;
        int inputsWithdrawn,outputsProduced,batchOutputs;
        boolean inputPending;
        Cell expectedNextCrop;
        final ResourceDelivery.ItemIdentity item;
        long expectedItems;
        String inventoryFingerprint = "";
        long harvested;
        long deposited;
        long lastTick;
        Run(ActorRef actor, ValidatedRequest request, UUID id, long wheat, long tick) {
            this.actor = actor; this.request = request; this.id = id;
            item=RecipeProduction.is(request.request().capability()) ? RecipeProduction.of(request.request().capability()).output()
                    : CraftingTablePlacement.is(request.request().capability()) ? ResourceDelivery.CRAFTING_TABLE
                    : LogAcquisition.is(request.request().capability()) ? LogAcquisition.item(request.request().capability()) : ResourceDelivery.WHEAT;
            expectedItems = wheat; lastTick = tick;
        }
        long available() {
            long total = 0;
            for (Batch batch : batches.values()) total = Math.addExact(total, batch.picked - batch.deposited);
            return total;
        }
    }
    private static final class Active {
        final ActionHandle handle;
        final Run run;
        final Operation operation;
        final Map<String, Value> arguments;
        final Budgets.Ledger usage;
        final long startedTick;
        final long startedMillis;
        final long deadlineMillis;
        final List<CropDelivery.CropReceipt> receipts = new ArrayList<>();
        final List<ResourceDelivery.Receipt> resourceReceipts = new ArrayList<>();
        long breakStarted = -1;
        String breakIdentity;
        long lastPolled = Long.MIN_VALUE;
        long elapsedTick = 0;
        long effects;
        int scanned;
        int unknown;
        int mature;
        int requested;
        int transferred;
        long travel;
        boolean movementStarted;
        String terminalBound = "none";
        Cell selectedTarget;
        UUID selectedDrop;
        int selectedDropQuantity;
        ActionReceipt current;
        ObservedEvidence evidence;
        Active(ActionHandle handle, Run run, Operation operation, Map<String, Value> arguments,
               Budgets.Ledger usage, long tick, long startedMillis, long deadlineMillis) {
            this.handle = handle; this.run = run; this.operation = operation == null ? null : operation.shared();
            this.arguments = arguments; this.usage = usage;
            this.startedTick = tick; this.startedMillis = startedMillis;
            this.deadlineMillis = deadlineMillis;
            current = new ActionReceipt(handle, false, 0, List.of(), null);
        }
    }

    private final WorldAccess world;
    private final AuthorityPolicy authority;
    private final Limits limits;
    private final Clock clock;
    private final UUID observationSource = UUID.randomUUID();
    private long observationSequence;
    private java.util.function.Consumer<ObservedEvidence> observationSink = evidence -> { };
    private final Map<UUID, Run> runs = new HashMap<>();
    private final Map<UUID, Active> handles = new LinkedHashMap<>();

    public SurvivalGateway(WorldAccess world, AuthorityPolicy authority, Limits limits, Clock clock) {
        this.world = Objects.requireNonNull(world);
        this.authority = Objects.requireNonNull(authority);
        this.limits = Objects.requireNonNull(limits);
        this.clock = Objects.requireNonNull(clock);
    }
    public List<PrimitiveSignature> registered() { return GatewayPrimitives.instance().all(); }
    public UUID observationSource() { return observationSource; }
    /** Trusted runtime binding. The sink must be bounded, I/O-free and nonblocking. */
    public void observationSink(java.util.function.Consumer<ObservedEvidence> sink) {
        thread(); observationSink = Objects.requireNonNull(sink);
    }
    public ObservedEvidence observedEvidence(ActionHandle handle, TrustedContext caller) {
        thread(); Active action = get(handle);
        if (action.evidence == null || !action.evidence.owner().equals(caller)
                || !allows(action, Effect.OBSERVE, target(action))
                || world.permit(action.run.request, Effect.OBSERVE, target(action)) != null)
            throw new SecurityException("Observation unavailable");
        return action.evidence;
    }
    public int activeRuns() { thread(); return runs.size(); }
    public int trackedHandles() { thread(); return handles.size(); }

    @Override public ActionHandle start(ValidatedRequest request, RunCorrelation correlation,
                                        PrimitiveRequirement primitive, Map<String, Value> arguments,
                                        Budgets.Ledger usage) {
        thread();
        Objects.requireNonNull(request); Objects.requireNonNull(correlation);
        Objects.requireNonNull(arguments); Objects.requireNonNull(usage);
        ActionHandle handle = new ActionHandle(UUID.randomUUID(), correlation.runId());
        retireTerminalHandlesForReleasedRuns();
        if (handles.size() >= limits.maxHandles()) throw new IllegalStateException("Active handle capacity");
        Operation op = GatewayPrimitives.instance().operation(primitive).orElse(null);
        Reason invalid = op == null ? Reason.UNSUPPORTED_PRIMITIVE : validate(request, op, arguments);
        ActorRef actor = actor(request);
        Run run = runs.get(correlation.runId());
        if (run != null && world.tick() - run.lastTick > limits.maxActionTicks()) {
            releaseRun(run.id);
            run = null;
            invalid = Reason.INTERRUPTED;
        }
        if (run == null && invalid == null) {
            ActorState state = world.actor(actor);
            if (state == null || !state.loaded() || !state.alive()) invalid = Reason.ACTOR_UNAVAILABLE;
            else if (!state.dimension().equals(actor.dimension())) invalid = Reason.TARGET_INVALID;
            else if (runs.size() >= limits.maxRuns()) invalid = Reason.BUDGET_EXHAUSTED;
            else if (!world.acquire(actor, correlation.runId())) invalid = Reason.ACTOR_UNAVAILABLE;
            else {
                run = new Run(actor, request, correlation.runId(), state.inventoryWheat(), world.tick());
                if (LogAcquisition.log(run.item)) {
                    ItemStock stock=world.resourceInventory(actor,run.item);
                    if (stock.status()!=ObservationStatus.PRESENT || stock.reason()!=null)
                        invalid=stock.reason()==null ? Reason.STALE_OBSERVATION : stock.reason();
                    run.expectedItems=stock.quantity(); run.inventoryFingerprint=stock.fingerprint();
                }
                runs.put(correlation.runId(), run);
            }
        } else if (run != null && (!run.request.equals(request) || !run.actor.equals(actor)))
            invalid = Reason.REQUEST_INVALID;
        if (invalid == null && !world.holds(actor, correlation.runId())) invalid = Reason.ACTOR_UNAVAILABLE;
        if (invalid == null && op.signature().effects().contains(Effect.MOVE)
                && handles.values().stream().anyMatch(existing ->
                existing.operation != null && existing.operation.signature().effects().contains(Effect.MOVE)
                        && existing.run != null && existing.run.actor.equals(actor)
                        && !existing.current.terminal()))
            invalid = Reason.ACTION_FAILED; // One physical route owner per actor.
        long startedMillis = clock.millis();
        long deadline;
        try { deadline = Math.addExact(startedMillis, limits.maxActionTicks() * 50L); }
        catch (ArithmeticException overflow) { deadline = Long.MAX_VALUE; }
        Active action = new Active(handle, run, op, Map.copyOf(arguments), usage,
                world.tick(), startedMillis, deadline);
        handles.put(handle.id(), action);
        if (invalid != null) finish(action, invalid, null, null);
        return handle;
    }

    @Override public ActionReceipt poll(ActionHandle handle) {
        thread();
        Active action = get(handle);
        if (action.current.terminal() || action.lastPolled == world.tick()) return action.current;
        action.lastPolled = world.tick();
        Run run = action.run;
        ActorState actorState = world.actor(run.actor);
        if (actorState == null || !actorState.loaded() || !actorState.alive()
                || !world.holds(run.actor, run.id)) {
            finish(action, Reason.ACTOR_UNAVAILABLE, null, null); releaseRun(run.id); return action.current;
        }
        if (!actorState.dimension().equals(run.actor.dimension())) {
            finish(action, Reason.TARGET_INVALID, null, null); releaseRun(run.id); return action.current;
        }
        if (world.tick() - action.startedTick > limits.maxActionTicks()) {
            action.terminalBound = "action-game-ticks";
            finish(action, Reason.ACTION_TIMEOUT, null, null); return action.current;
        }
        if (clock.millis() > action.deadlineMillis) {
            action.terminalBound = "action-wall-deadline";
            finish(action, Reason.ACTION_TIMEOUT, null, null); return action.current;
        }
        try {
            long delta = Math.max(0, world.tick() - action.startedTick - action.elapsedTick);
            if (!action.usage.canDebit(Kind.ELAPSED_TICKS, delta)) {
                action.terminalBound = "ledger-elapsed-ticks";
                finish(action, Reason.BUDGET_EXHAUSTED, null, null); return action.current;
            }
            action.usage.debit(Kind.ELAPSED_TICKS, delta);
            action.elapsedTick += delta;
            action.usage.debit(Kind.INSTRUCTIONS, 1);
            step(action, actorState);
        } catch (Budgets.Exhausted exhausted) {
            action.terminalBound = "ledger-work-or-effect";
            finish(action, Reason.BUDGET_EXHAUSTED, null, null);
        }
        return action.current;
    }

    @Override public ActionReceipt cancel(ActionHandle handle) {
        thread();
        Active action = get(handle);
        if (!action.current.terminal()) cancelRun(action.run.id);
        return action.current;
    }
    public void cancelRun(UUID runId) {
        thread();
        Run run = runs.get(runId);
        if (run == null) return;
        for (Active action : handles.values())
            if (action.run == run && !action.current.terminal())
                finish(action, Reason.CANCELLED, null, null);
        world.stopMovement(run.actor);
        world.release(run.actor, runId);
        runs.remove(runId);
    }
    /** Executor/controller must call this after terminal execution; no crash replay exists. */
    public void releaseRun(UUID runId) {
        thread();
        Run run = runs.remove(runId);
        if (run == null) return;
        for (Active action : handles.values())
            if (action.run == run && !action.current.terminal())
                finish(action, Reason.INTERRUPTED, null, null);
        world.stopMovement(run.actor);
        world.release(run.actor, runId);
    }
    /**
     * Retains terminal answers while capacity allows. Once a run is released, its oldest
     * terminal answers may be retired to make room; retired handles cannot mutate the world.
     */
    private void retireTerminalHandlesForReleasedRuns() {
        if (handles.size() < limits.maxHandles()) return;
        var iterator = handles.entrySet().iterator();
        while (iterator.hasNext() && handles.size() >= limits.maxHandles()) {
            Active action = iterator.next().getValue();
            if (action.current.terminal() && !runs.containsKey(action.handle.runId()))
                iterator.remove();
        }
    }
    /** Game-thread lifecycle sweep; no effect or replay when an actor disappears. */
    public void tick() {
        thread();
        for (Run run : List.copyOf(runs.values())) {
            ActorState state = world.actor(run.actor);
            if (state == null || !state.loaded() || !state.alive()
                    || !world.holds(run.actor, run.id)
                    || world.tick() - run.lastTick > limits.maxActionTicks())
                releaseRun(run.id);
        }
    }

    private void step(Active action, ActorState actorState) {
        Run run = action.run;
        Operation op = action.operation;
        Effect effect = op.signature().effects().iterator().next();
        if (!allows(action, effect, target(action))) {
            finish(action, Reason.AUTHORITY_DENIED, null, null); return;
        }
        if(CraftingTablePlacement.is(run.request.request().capability())) {
            stepPlacement(action,effect);run.lastTick=world.tick();return;
        }
        if(RecipeProduction.is(run.request.request().capability())) {
            stepRecipe(action,effect);run.lastTick=world.tick();return;
        }
        if (op == Operation.MOVE_TO_SOURCE || op == Operation.APPROACH_OAK_LOG) {
            moveToSource(action);
            run.lastTick = world.tick();
            return;
        }
        Cell target = target(action);
        Reason permission = world.permit(run.request, effect, target);
        if (permission != null) { finish(action, permission, null, null); return; }
        switch (op) {
            case OBSERVE_SOURCE, OBSERVE_OAK_SOURCE -> observeSource(action);
            case OBSERVE_INVENTORY -> observeStock(action, world.inventory(run.actor), target);
            case OBSERVE_CONTAINER -> observeStock(action, world.container(destination(run.request)), target);
            case OBSERVE_OAK_INVENTORY -> observeResourceStock(action,world.resourceInventory(run.actor,run.item),target);
            case OBSERVE_OAK_CONTAINER -> observeResourceStock(action,world.resourceContainer(destination(run.request),run.item),target);
            case MOVE, MOVE_TO_DESTINATION -> move(action, target);
            case MOVE_TO_SOURCE, APPROACH_OAK_LOG -> throw new AssertionError("handled above");
            case HARVEST_WHEAT -> harvest(action, target);
            case HARVEST_NEXT_WHEAT, HARVEST_NEXT_OAK_LOG -> harvestNext(action);
            case PICKUP_WHEAT -> pickup(action, target);
            case PICKUP_TRACKED_WHEAT, PICKUP_TRACKED_OAK_LOGS -> pickupTracked(action);
            case TRANSFER_WHEAT, TRANSFER_OAK_LOGS -> transfer(action, target);
            case OBSERVE_TABLE_SITE,WITHDRAW_TABLE_ITEM,MOVE_TO_PLACEMENT,PLACE_CRAFTING_TABLE,OBSERVE_TABLE_INPUTS,WITHDRAW_TABLE_PLANKS,CRAFT_CRAFTING_TABLE,TRANSFER_CRAFTING_TABLES,OBSERVE_PLANK_INPUTS,OBSERVE_STICK_INPUTS,OBSERVE_AXE_INPUTS,MOVE_TO_TABLE,WITHDRAW_AXE_INPUTS,CRAFT_WOODEN_AXE,TRANSFER_WOODEN_AXES,MOVE_TO_INPUT,WITHDRAW_OAK_LOG,WITHDRAW_OAK_PLANKS,CRAFT_OAK_PLANKS,CRAFT_STICKS,TRANSFER_OAK_PLANKS,TRANSFER_STICKS -> throw new AssertionError("Resource operation handled above");
        }
        run.lastTick = world.tick();
    }

    private void stepPlacement(Active action,Effect effect) {
        Run run=action.run;Operation op=action.operation;var args=run.request.request().arguments();
        var sourceBox=((ContainerValue)args.get("source")).value();var site=CraftingTablePlacement.target(run.request.request());
        Cell target=op==Operation.MOVE_TO_INPUT || op==Operation.WITHDRAW_TABLE_ITEM
                ? new Cell(sourceBox.dimension(),sourceBox.x(),sourceBox.y(),sourceBox.z())
                : new Cell(site.dimension(),site.x(),site.y(),site.z());
        Reason denied=world.permit(run.request,effect,target);if(denied!=null){finish(action,denied,null,null);return;}
        if(op==Operation.OBSERVE_TABLE_SITE){
            if(run.placement!=null || run.tableWithdrawn){finish(action,Reason.REQUEST_INVALID,null,null);return;}
            action.usage.debit(Kind.OBSERVATIONS,150);var captured=world.placementPreflight(run.request);
            if(captured.status()!=ObservationStatus.PRESENT || captured.reason()!=null){finish(action,captured.reason()==null?Reason.STALE_OBSERVATION:captured.reason(),null,null);return;}
            if(!site.equals(captured.target()) || !sourceBox.equals(captured.source())){finish(action,Reason.STALE_OBSERVATION,null,null);return;}
            if(captured.availableItems()<1){finish(action,Reason.RESOURCE_MISSING,null,null);return;}
            // This is structural observation, not placement permission. MOVE must
            // be able to leave an actor-occupied site; every mutation rechecks clearance.
            run.placement=captured;run.placementCustody=captured.custodyFingerprint();run.placementSource=captured.sourceFingerprint();
            finish(action,null,new BoolValue(true),null);return;
        }
        if(run.placement==null){finish(action,Reason.REQUEST_INVALID,null,null);return;}
        if(op==Operation.MOVE_TO_INPUT || op==Operation.MOVE_TO_PLACEMENT){move(action,target);return;}
        if(!effectAllowance(action))return;
        action.usage.debit(Kind.OBSERVATIONS,2*run.placement.sourceSlots()+30);
        PlacementTransaction.Mutation result;ResourceDelivery.Stage stage;
        if(op==Operation.WITHDRAW_TABLE_ITEM){
            if(run.tableWithdrawn || run.tablePlaced){finish(action,Reason.REQUEST_INVALID,null,null);return;}
            action.usage.debit(Kind.ATTEMPTED_EFFECTS,1);
            result=world.withdrawTableItem(run.actor,run.placement,run.placementCustody,run.placementSource);stage=ResourceDelivery.Stage.WITHDRAW;
        }else if(op==Operation.PLACE_CRAFTING_TABLE){
            if(!run.tableWithdrawn || run.tablePlaced){finish(action,Reason.RESOURCE_MISSING,null,null);return;}
            action.usage.debit(Kind.ATTEMPTED_EFFECTS,1);
            result=world.placeTable(run.actor,run.placement,run.placementCustody,run.placementSource);stage=ResourceDelivery.Stage.PLACE;
        }else{finish(action,Reason.UNSUPPORTED_PRIMITIVE,null,null);return;}
        if(result.pending())return;
        if(!result.committed()){finish(action,result.reason()==null?Reason.ACTION_FAILED:result.reason(),null,null);return;}
        committed(action);
        // An inconsistent native effect stays in the world and counts against the attempt; it cannot fulfill PLACE.
        if(result.reason()!=null || !run.placementCustody.equals(result.custodyBefore()) || !RecipeTransaction.hash(result.custodyAfter())
                || stage==ResourceDelivery.Stage.WITHDRAW && (result.withdrawn()!=1 || result.consumed()!=0 || result.placed() || !RecipeTransaction.hash(result.sourceAfter()))
                || stage==ResourceDelivery.Stage.PLACE && (result.withdrawn()!=0 || result.consumed()!=1 || !result.placed() || result.facilityIdentity().isBlank())){
            finish(action,result.reason()==null?Reason.STALE_OBSERVATION:result.reason(),null,null);return;
        }
        if(stage==ResourceDelivery.Stage.WITHDRAW){run.tableWithdrawn=true;run.craftBatch=UUID.randomUUID();run.placementSource=result.sourceAfter();}
        else run.tablePlaced=true;
        var evidence=new PlacementTransaction.Evidence(site,run.placement.sourceIdentity(),run.placement.siteFingerprint(),
                result.custodyBefore(),result.custodyAfter(),stage==ResourceDelivery.Stage.WITHDRAW?"minecraft:air":"minecraft:crafting_table",result.facilityIdentity());
        var receipt=new ResourceDelivery.Receipt(UUID.randomUUID(),run.request.context(),run.id,action.handle.id(),run.craftBatch,run.actor,
                ResourceLeases.point(sourceBox),CraftingTablePlacement.address(run.request.request()),ResourceDelivery.CRAFTING_TABLE,stage,1,
                new ResourceDelivery.BlockPosition(sourceBox.dimension(),sourceBox.x(),sourceBox.y(),sourceBox.z()),List.of(),null,evidence);
        run.resourceReceipts.add(receipt);action.resourceReceipts.add(receipt);run.placementCustody=result.custodyAfter();
        if(Boolean.getBoolean("cognitivecraft.gametest.trace"))System.out.println("RB006B_EFFECT run="+run.id+" stage="+stage+" consumed="+result.consumed()+" placed="+result.placed());
        finish(action,null,new IntValue(1),null);
    }

    /** The same gateway owns these transient batches; only explicit primitives perform effects. */
    private void stepRecipe(Active action,Effect effect) {
        Run run=action.run;Operation op=action.operation;
        var definition=RecipeProduction.of(run.request.request().capability());
        var sourceBox=((ContainerValue)run.request.request().arguments().get("source")).value();
        var destinationBox=destination(run.request);
        Cell target=op==Operation.OBSERVE_BIRCH_PLANK_INPUTS || op==Operation.OBSERVE_PLANK_INPUTS || op==Operation.OBSERVE_STICK_INPUTS || op==Operation.OBSERVE_AXE_INPUTS || op==Operation.OBSERVE_TABLE_INPUTS || op==Operation.MOVE_TO_INPUT || op==Operation.WITHDRAW_OAK_LOG || op==Operation.WITHDRAW_OAK_PLANKS || op==Operation.WITHDRAW_AXE_INPUTS || op==Operation.WITHDRAW_TABLE_PLANKS
                ? new Cell(sourceBox.dimension(),sourceBox.x(),sourceBox.y(),sourceBox.z()) : target(action);
        if(op==Operation.TRANSFER_WOODEN_AXES || op==Operation.TRANSFER_CRAFTING_TABLES)target=new Cell(destinationBox.dimension(),destinationBox.x(),destinationBox.y(),destinationBox.z());
        if(definition.axe() && (op==Operation.MOVE_TO_TABLE || op==Operation.CRAFT_WOODEN_AXE || op==Operation.CRAFT_CRAFTING_TABLE)) {
            var f=((FacilityValue)run.request.request().arguments().get("table")).value();target=new Cell(f.dimension(),f.x(),f.y(),f.z());
        }
        if (!allows(action,effect,target)) {finish(action,Reason.AUTHORITY_DENIED,null,null);return;}
        Reason denied=world.permit(run.request,effect,target);
        if(denied!=null) {finish(action,denied,null,null);return;}
        long requested=((IntValue)run.request.request().arguments().get("amount")).value();
        if(op==Operation.OBSERVE_BIRCH_PLANK_INPUTS || op==Operation.OBSERVE_PLANK_INPUTS || op==Operation.OBSERVE_STICK_INPUTS || op==Operation.OBSERVE_AXE_INPUTS || op==Operation.OBSERVE_TABLE_INPUTS) {
            if(run.recipe!=null || run.inputsWithdrawn!=0) {finish(action,Reason.REQUEST_INVALID,null,null);return;}
            if(!action.usage.canDebit(Kind.OBSERVATIONS,296))throw new Budgets.Exhausted();
            var observed=world.recipePreflight(run.request);
            action.usage.debit(Kind.OBSERVATIONS,observed.sourceSlots()+observed.destinationSlots()+40);
            if(observed.reason()!=null || observed.status()!=ObservationStatus.PRESENT) {
                finish(action,observed.reason()==null ? Reason.STALE_OBSERVATION : observed.reason(),null,null);return;
            }
            if(definition.axe() && (!Objects.equals(observed.facility(),((FacilityValue)run.request.request().arguments().get("table")).value()) || observed.facilityIdentity().isBlank())) {finish(action,Reason.STALE_OBSERVATION,null,null);return;}
            if(!observed.recipe().key().equals(definition.recipe())) {finish(action,Reason.ARTIFACT_INCOMPATIBLE,null,null);return;}
            if(observed.availableInputs()<definition.inputs(requested) || definition.axe() && observed.availableSecondary()<definition.secondaryInputs(requested)) {finish(action,Reason.RESOURCE_MISSING,null,null);return;}
            if(observed.outputCapacity()<requested) {finish(action,Reason.TARGET_UNAVAILABLE,null,null);return;}
            run.recipe=observed;run.recipeCustody=observed.custodyFingerprint();run.recipeSource=observed.sourceFingerprint();
            finish(action,null,new BoolValue(true),null);return;
        }
        if(run.recipe==null) {finish(action,Reason.REQUEST_INVALID,null,null);return;}
        if(op==Operation.MOVE_TO_INPUT || op==Operation.MOVE_TO_DESTINATION || op==Operation.MOVE_TO_TABLE) {move(action,target);return;}
        if(!effectAllowance(action))return;
        int work=op==Operation.CRAFT_OAK_PLANKS || op==Operation.CRAFT_STICKS || op==Operation.CRAFT_WOODEN_AXE || op==Operation.CRAFT_CRAFTING_TABLE ? 64
                : 2*(op==Operation.WITHDRAW_OAK_LOG || op==Operation.WITHDRAW_OAK_PLANKS || op==Operation.WITHDRAW_AXE_INPUTS || op==Operation.WITHDRAW_TABLE_PLANKS ? run.recipe.sourceSlots() : run.recipe.destinationSlots())+24;
        action.usage.debit(Kind.OBSERVATIONS,work);
        RecipeTransaction.Mutation result;
        ResourceDelivery.Stage stage;
        int quantity,retained;
        if(op==Operation.WITHDRAW_OAK_LOG || op==Operation.WITHDRAW_OAK_PLANKS || op==Operation.WITHDRAW_AXE_INPUTS || op==Operation.WITHDRAW_TABLE_PLANKS) {
            if(run.inputPending || run.batchOutputs!=0 || run.inputsWithdrawn>=Math.multiplyExact(definition.batches(requested),definition.totalInputs())) {
                finish(action,Reason.REQUEST_INVALID,null,null);return;
            }
            action.usage.debit(Kind.ATTEMPTED_EFFECTS,1);
            result=world.withdrawRecipeInput(run.actor,sourceBox,run.recipe,run.recipeCustody,run.recipeSource);
            stage=ResourceDelivery.Stage.WITHDRAW;quantity=result.moved();retained=0;
            if(result.committed()) {run.craftBatch=UUID.randomUUID();run.inputsWithdrawn+=result.moved();run.inputPending=true;}
        } else if(op==Operation.CRAFT_OAK_PLANKS || op==Operation.CRAFT_STICKS || op==Operation.CRAFT_WOODEN_AXE || op==Operation.CRAFT_CRAFTING_TABLE) {
            if(!run.inputPending || run.batchOutputs!=0) {finish(action,Reason.RESOURCE_MISSING,null,null);return;}
            action.usage.debit(Kind.ATTEMPTED_EFFECTS,1);
            result=world.craftRecipe(run.actor,run.recipe,run.recipeCustody);
            stage=ResourceDelivery.Stage.CRAFT;quantity=result.produced();retained=quantity;
            if(result.committed()) {run.inputPending=false;run.batchOutputs=result.produced();run.outputsProduced+=result.produced();}
        } else if(op==Operation.TRANSFER_OAK_PLANKS || op==Operation.TRANSFER_STICKS || op==Operation.TRANSFER_WOODEN_AXES || op==Operation.TRANSFER_CRAFTING_TABLES) {
            if(run.inputPending || run.batchOutputs<1 || run.deposited>=requested
                    || ((IntValue)action.arguments.get("amount")).value()!=requested) {
                finish(action,Reason.REQUEST_INVALID,null,null);return;
            }
            int moved=Math.toIntExact(Math.min(run.batchOutputs,requested-run.deposited));
            action.usage.debit(Kind.ATTEMPTED_EFFECTS,1);
            result=world.depositRecipeOutput(run.actor,destinationBox,run.recipe,run.recipeCustody,moved);
            stage=ResourceDelivery.Stage.DEPOSIT;quantity=result.moved();retained=run.batchOutputs-quantity;
            if(result.committed()) {run.batchOutputs-=quantity;run.deposited+=quantity;}
        } else {finish(action,Reason.UNSUPPORTED_PRIMITIVE,null,null);return;}
        if(result.pending())return;
        if(result.committed()) {
            committed(action);
            if(result.reason()!=null || !run.recipeCustody.equals(result.custodyBefore())
                    || !RecipeTransaction.hash(result.custodyAfter())
                    || stage==ResourceDelivery.Stage.WITHDRAW && (quantity!=definition.totalInputs() || result.consumed()!=0 || result.produced()!=0)
                    || stage==ResourceDelivery.Stage.CRAFT && (quantity!=definition.outputPerBatch() || result.consumed()!=definition.totalInputs() || result.remainders()!=0)
                    || stage==ResourceDelivery.Stage.DEPOSIT && (quantity<1 || quantity>definition.outputPerBatch() || result.consumed()!=0 || result.produced()!=0)) {
                finish(action,Reason.STALE_OBSERVATION,null,null);return;
            }
            var transformation=new RecipeTransaction.Transformation(run.recipe.recipe(),result.consumed(),result.produced(),
                    result.remainders(),retained,result.custodyBefore(),result.custodyAfter(),run.recipe.sourceIdentity(),run.recipe.destinationIdentity(),run.recipe.facility(),run.recipe.facilityIdentity());
            var receipt=new ResourceDelivery.Receipt(UUID.randomUUID(),run.request.context(),run.id,action.handle.id(),run.craftBatch,
                    run.actor,ResourceLeases.point(sourceBox),destinationBox,
                    stage==ResourceDelivery.Stage.WITHDRAW ? definition.input() : definition.output(),
                    stage,stage==ResourceDelivery.Stage.WITHDRAW && definition.axe() ? 3 : quantity,new ResourceDelivery.BlockPosition(sourceBox.dimension(),sourceBox.x(),sourceBox.y(),sourceBox.z()),List.of(),transformation);
            run.resourceReceipts.add(receipt);action.resourceReceipts.add(receipt);
            if(stage==ResourceDelivery.Stage.WITHDRAW && definition.axe()) {
                var secondary=new ResourceDelivery.Receipt(UUID.randomUUID(),run.request.context(),run.id,
                        UUID.nameUUIDFromBytes((action.handle.id()+":sticks").getBytes(java.nio.charset.StandardCharsets.UTF_8)),run.craftBatch,
                        run.actor,receipt.source(),destinationBox,ResourceDelivery.STICK,stage,2,receipt.sourceBlock(),List.of(),transformation);
                run.resourceReceipts.add(secondary);action.resourceReceipts.add(secondary);
            }
            run.recipeCustody=result.custodyAfter();
            if(!result.sourceAfter().isEmpty())run.recipeSource=result.sourceAfter();
            if(Boolean.getBoolean("cognitivecraft.gametest.trace"))System.out.println((definition.table() ? "RB006A_EFFECT run=" : definition.axe() ? "RB004_EFFECT run=" : run.request.request().capability().equals(StickProduction.ID) ? "RB003_EFFECT run=" : "RB002_EFFECT run=")+run.id+" stage="+stage+" quantity="+quantity+" retained="+retained);
            finish(action,null,new IntValue(quantity),null);
        } else finish(action,result.reason()==null ? Reason.ACTION_FAILED : result.reason(),null,null);
    }

    private void moveToSource(Active action) {
        if (action.operation==Operation.APPROACH_OAK_LOG) {
            if (action.selectedDrop==null) {
                for (Batch batch:action.run.batches.values()) if(!batch.drops.isEmpty()) {
                    var first=batch.drops.entrySet().iterator().next();
                    action.selectedDrop=first.getKey();action.selectedDropQuantity=first.getValue();break;
                }
            }
            if(action.selectedDrop!=null) { approachOwnedDrop(action);return; }
        }
        Cell selected = selectNextCrop(action, Effect.OBSERVE);
        if (selected == null) return;
        // Selection evidence is refreshed while navigation progresses.
        if (!allows(action, Effect.OBSERVE, selected)) {
            finish(action, Reason.AUTHORITY_DENIED, null, null); return;
        }
        Reason observed = world.permit(action.run.request, Effect.OBSERVE, selected);
        if (observed != null) { finish(action, observed, null, null); return; }
        action.usage.debit(Kind.OBSERVATIONS, 1);
        ItemBlock current = world.resourceBlock(selected,action.run.item);
        if (current.status() != ObservationStatus.PRESENT || !current.eligible()) {
            finish(action, Reason.STALE_OBSERVATION, null, null); return;
        }
        Reason permission = world.permit(action.run.request, Effect.MOVE, selected);
        if (permission != null) { finish(action, permission, null, null); return; }
        move(action, selected);
        if (action.current.terminal() && action.current.reason() == null)
            action.run.expectedNextCrop = selected;
    }
    private void approachOwnedDrop(Active action) {
        Run run=action.run;
        if(!allows(action,Effect.OBSERVE,target(action))) {
            finish(action,Reason.AUTHORITY_DENIED,null,null);return;
        }
        action.usage.debit(Kind.OBSERVATIONS,1);
        ItemDropState current=world.resourceDrop(action.selectedDrop,run.item);
        if(current.status()!=ObservationStatus.PRESENT || current.quantity()!=action.selectedDropQuantity) {
            finish(action,Reason.STALE_OBSERVATION,null,null);return;
        }
        if(!current.cell().dimension().equals(run.actor.dimension())) {
            finish(action,Reason.TARGET_INVALID,null,null);return;
        }
        action.selectedTarget=current.cell();
        if (!allows(action, Effect.OBSERVE, current.cell()) || !allows(action, Effect.MOVE, current.cell())) {
            finish(action, Reason.AUTHORITY_DENIED, null, null); return;
        }
        Reason denied=world.permit(run.request,Effect.OBSERVE,current.cell());
        if(denied==null)denied=world.permit(run.request,Effect.MOVE,current.cell());
        if(denied!=null) { finish(action,denied,null,null);return; }
        move(action,current.cell());
    }

    /** Shared finite y/z/x crop selection for bound navigation and harvesting. */
    private Cell selectNextCrop(Active action, Effect observationPermission) {
        if (action.selectedTarget != null) return action.selectedTarget;
        Cuboid area = source(action.run.request);
        long width = (long)area.maxX() - area.minX() + 1;
        long depth = (long)area.maxZ() - area.minZ() + 1;
        long volume = width * depth * ((long)area.maxY() - area.minY() + 1);
        if (volume > limits.maxSourceCells()) {
            finish(action, Reason.BUDGET_EXHAUSTED, null, null); return null;
        }
        int left = limits.cellsPerPoll();
        while (left-- > 0 && action.scanned < volume) {
            int cursor = action.scanned++;
            Cell cell = new Cell(area.dimension(), (int)(area.minX() + cursor % width),
                    (int)(area.minY() + cursor / (width * depth)),
                    (int)(area.minZ() + (cursor / width) % depth));
            if (!allows(action, observationPermission, cell)) {
                finish(action, Reason.AUTHORITY_DENIED, null, null); return null;
            }
            Reason denied = world.permit(action.run.request, observationPermission, cell);
            if (denied != null) { finish(action, denied, null, null); return null; }
            action.usage.debit(Kind.OBSERVATIONS, 1);
            ItemBlock crop = world.resourceBlock(cell,action.run.item);
            if (crop.status() == ObservationStatus.UNKNOWN) action.unknown++;
            else if (crop.status() == ObservationStatus.PRESENT && crop.eligible())
                return action.selectedTarget = cell;
        }
        if (action.scanned >= volume)
            finish(action, action.unknown > 0 ? Reason.STALE_OBSERVATION : Reason.RESOURCE_MISSING, null, null);
        return null;
    }

    private void move(Active action, Cell target) {
        if (!allows(action, Effect.MOVE, target)) { finish(action, Reason.AUTHORITY_DENIED, null, null); return; }
        if (!action.usage.canDebit(Kind.TRAVEL_BLOCKS, 1)
                || !action.usage.canDebit(Kind.INSTRUCTIONS, limits.navigationCallsPerPoll())
                || action.travel >= limits.maxTravelBlocks()) {
            action.terminalBound = "travel-or-navigation-allowance";
            finish(action, Reason.BUDGET_EXHAUSTED, null, null); return;
        }
        action.movementStarted = true;
        Movement movement = action.selectedDrop!=null
                ? world.approachResourceDrop(action.run.actor,action.selectedDrop,action.selectedDropQuantity,limits.navigationCallsPerPoll(),action.run.item)
                : action.operation==Operation.APPROACH_OAK_LOG
                ? world.approachResource(action.run.actor,target,limits.navigationCallsPerPoll(),action.run.item)
                : action.operation == Operation.MOVE_TO_SOURCE
                ? world.moveToSource(action.run.actor,target,limits.navigationCallsPerPoll())
                : CraftingTablePlacement.is(action.run.request.request().capability())
                ? world.movePlacement(action.run.actor,target,action.run.placement.target(),limits.navigationCallsPerPoll())
                : RecipeProduction.is(action.run.request.request().capability())
                        || LogAcquisition.is(action.run.request.request().capability())
                ? world.moveRecipe(action.run.actor,target,limits.navigationCallsPerPoll())
                : world.move(action.run.actor, target, limits.navigationCallsPerPoll());
        if (movement.chargedWork() > limits.navigationCallsPerPoll()
                || movement.travelBlocks() > 1
                || movement.travelBlocks() > limits.maxTravelBlocks() - action.travel) {
            action.terminalBound = "reported-navigation-or-travel";
            finish(action, Reason.BUDGET_EXHAUSTED, null, null); return;
        }
        action.usage.debit(Kind.INSTRUCTIONS, movement.chargedWork());
        action.usage.debit(Kind.TRAVEL_BLOCKS, movement.travelBlocks());
        action.travel += movement.travelBlocks();
        if (movement.impossible()) finish(action, Reason.TARGET_UNAVAILABLE, null, null);
        else if (movement.reached()) finish(action, null, new BoolValue(true), null);
    }

    private void observeSource(Active action) {
        Cuboid area = source(action.run.request);
        long width = (long) area.maxX() - area.minX() + 1;
        long depth = (long) area.maxZ() - area.minZ() + 1;
        long height = (long) area.maxY() - area.minY() + 1;
        long volume = width * height * depth;
        if (volume > limits.maxSourceCells()) {
            finish(action, Reason.BUDGET_EXHAUSTED, null, null); return;
        }
        int left = limits.cellsPerPoll();
        while (left-- > 0 && action.scanned < volume) {
            int cursor = action.scanned++;
            long x = area.minX() + cursor % width;
            long z = area.minZ() + (cursor / width) % depth;
            long y = area.minY() + cursor / (width * depth);
            Cell cell = new Cell(area.dimension(), (int)x, (int)y, (int)z);
            Reason denied = world.permit(action.run.request, Effect.OBSERVE, cell);
            if (denied != null) { finish(action, denied, null, null); return; }
            action.usage.debit(Kind.OBSERVATIONS, 1);
            ItemBlock crop = world.resourceBlock(cell,action.run.item);
            if (crop.status() == ObservationStatus.UNKNOWN) action.unknown++;
            else if (crop.status() == ObservationStatus.PRESENT && crop.eligible()) action.mature++;
        }
        if (action.scanned >= volume) {
            ObservationStatus status = action.unknown > 0 ? ObservationStatus.UNKNOWN
                    : action.mature > 0 ? ObservationStatus.PRESENT : ObservationStatus.ABSENT;
            ObservationSnapshot snapshot = new ObservationSnapshot(
                    new ObservationRef(UUID.randomUUID(), world.tick(), area.dimension()), status,
                    "source:" + UUID.nameUUIDFromBytes(area.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    world.tick(), action.scanned,
                    Map.of(LogAcquisition.log(action.run.item) ? LogAcquisition.count(action.run.request.request().capability()) : "mature_wheat", (long)action.mature, "unknown_cells", (long)action.unknown));
            finish(action, null, status == ObservationStatus.UNKNOWN
                    ? null : new IntValue(action.mature), snapshot);
        }
    }
    /** Select one mature crop from the bound area using finite per-poll observation work. */
    private void harvestNext(Active action) {
        Cell selected = selectNextCrop(action, Effect.HARVEST);
        if (selected == null) return;
        if (action.run.expectedNextCrop != null && !action.run.expectedNextCrop.equals(selected)) {
            finish(action, Reason.STALE_OBSERVATION, null, null); return;
        }
        action.run.expectedNextCrop = null;
        harvest(action, selected);
    }
    private void observeStock(Active action, StockState stock, Cell target) {
        action.usage.debit(Kind.OBSERVATIONS, 1);
        ObservationSnapshot snapshot = new ObservationSnapshot(
                new ObservationRef(UUID.randomUUID(), world.tick(), target.dimension()), stock.status(),
                stock.identity(), world.tick(), 1, Map.of("wheat", stock.wheat(), "seeds", stock.seeds()));
        finish(action, null, stock.status() == ObservationStatus.UNKNOWN
                ? null : new IntValue(stock.wheat()), snapshot);
    }
    private void observeResourceStock(Active action,ItemStock stock,Cell target) {
        action.usage.debit(Kind.OBSERVATIONS,1);
        if (stock.reason()!=null) { finish(action,stock.reason(),null,null); return; }
        var snapshot=new ObservationSnapshot(new ObservationRef(UUID.randomUUID(),world.tick(),target.dimension()),
                stock.status(),stock.identity(),world.tick(),1,Map.of(LogAcquisition.count(action.run.request.request().capability()),stock.quantity()));
        finish(action,null,stock.status()==ObservationStatus.UNKNOWN ? null : new IntValue(stock.quantity()),snapshot);
    }
    private void harvest(Active action, Cell cell) {
        if (!allows(action, Effect.HARVEST, cell)) { finish(action, Reason.AUTHORITY_DENIED, null, null); return; }
        Run run = action.run;
        if (!cell.inside(source(run.request))) { finish(action, Reason.AUTHORITY_DENIED, null, null); return; }
        ItemBlock crop = world.resourceBlock(cell,action.run.item);
        action.usage.debit(Kind.OBSERVATIONS, 1);
        if (crop.status() == ObservationStatus.UNKNOWN) {
            finish(action, Reason.STALE_OBSERVATION, null, null); return;
        }
        if (crop.status() != ObservationStatus.PRESENT || !crop.eligible()) {
            finish(action, Reason.TARGET_INVALID, null, null); return;
        }
        if (LogAcquisition.log(run.item)) {
            long requested=((IntValue)run.request.request().arguments().get("amount")).value();
            if (run.harvested>=requested) { finish(action,Reason.RESOURCE_MISSING,null,null); return; }
            Reason denied=world.permit(run.request,Effect.HARVEST,cell);
            ContainerRef target=destination(run.request);
            if(denied==null) denied=world.permit(run.request,Effect.TRANSFER,
                    new Cell(target.dimension(),target.x(),target.y(),target.z()));
            if (denied==null) denied=world.resourceBreakEligibility(run.actor,cell,run.item);
            if (denied!=null) { finish(action,denied,null,null); return; }
            ItemStock actorStock=world.resourceInventory(run.actor,run.item);
            ItemStock boxStock=world.resourceContainer(destination(run.request),run.item);
            action.usage.debit(Kind.OBSERVATIONS,2);
            long remainder=requested-run.deposited;
            if (!inventoryCurrent(run,actorStock) || boxStock.status()!=ObservationStatus.PRESENT
                    || boxStock.reason()!=null || actorStock.capacity()<requested-run.harvested
                    || boxStock.capacity()<remainder) {
                finish(action,boxStock.reason()==null ? Reason.TARGET_UNAVAILABLE : boxStock.reason(),null,null); return;
            }
            if (crop.breakTicks()<1) { finish(action,Reason.UNSUPPORTED_PRIMITIVE,null,null); return; }
            if (action.breakStarted<0) { action.breakStarted=world.tick(); action.breakIdentity=crop.identity(); }
            if (!action.breakIdentity.equals(crop.identity())) { finish(action,Reason.STALE_OBSERVATION,null,null); return; }
            if (world.tick()-action.breakStarted<crop.breakTicks()) return;
        }
        if (!effectAllowance(action)) return;
        action.usage.debit(Kind.ATTEMPTED_EFFECTS, 1);
        ItemHarvest result = world.resourceHarvest(run.actor, cell,run.item,
                action.breakStarted<0 ? 0 : world.tick()-action.breakStarted);
        if (result.removed()) {
            committed(action);
            Batch batch = new Batch(UUID.randomUUID(),cell);
            long wheat = 0;
            for (ItemDrop drop : result.drops()) {
                if (drop.quantity() > 64 || batch.drops.putIfAbsent(drop.entityId(), drop.quantity()) != null) {
                    finish(action, Reason.ACTION_FAILED, null, null); return;
                }
                wheat = Math.addExact(wheat, drop.quantity());
            }
            if (wheat > 0) {
                batch.originalDrops=List.copyOf(batch.drops.keySet());
                run.batches.put(batch.id, batch);
                run.harvested=Math.addExact(run.harvested,wheat);
                receipt(action, batch.id, CropDelivery.Stage.HARVEST, wheat);
            }
            finish(action, result.reason(), new IntValue(wheat), null);
        } else finish(action, result.reason() == null ? Reason.ACTION_FAILED : result.reason(), null, null);
    }
    private void pickupTracked(Active action) {
        Run run = action.run;
        if (run.batches.size() > limits.maxReceiptsPerRun()) {
            finish(action, Reason.BUDGET_EXHAUSTED, null, null); return;
        }
        for (Batch batch : run.batches.values()) {
            for (var entry : batch.drops.entrySet()) {
                ItemDropState state = world.resourceDrop(entry.getKey(),run.item);
                action.usage.debit(Kind.OBSERVATIONS, 1);
                if (state.status() != ObservationStatus.PRESENT || state.quantity() != entry.getValue()) {
                    if (Boolean.getBoolean("cognitivecraft.gametest.trace"))
                        System.out.println("IMP-007 custody stale stage=tracked-drop run=" + run.id
                                + " drop=" + entry.getKey() + " expected=" + entry.getValue()
                                + " state=" + state + " inventoryWheat="
                                + inventoryQuantity(run) + " tick=" + world.tick());
                    finish(action, Reason.STALE_OBSERVATION, null, null); return;
                }
                pickup(action, state.cell());
                return;
            }
        }
        finish(action, Reason.RESOURCE_MISSING, null, null);
    }
    private void pickup(Active action, Cell target) {
        if (!allows(action, Effect.PICKUP, target)) { finish(action, Reason.AUTHORITY_DENIED, null, null); return; }
        Run run = action.run;
        if (!inventoryCurrent(run,world.resourceInventory(run.actor,run.item))) {
            if (Boolean.getBoolean("cognitivecraft.gametest.trace"))
                System.out.println("IMP-007 custody stale stage=actor-stock run=" + run.id
                        + " expected=" + run.expectedItems + " actual="
                        + inventoryQuantity(run) + " tick=" + world.tick());
            finish(action, Reason.STALE_OBSERVATION, null, null); return;
        }
        Batch batch = null;
        Map.Entry<UUID, Integer> drop = null;
        boolean trackedElsewhere = false;
        for (Batch candidate : run.batches.values()) {
            for (var entry : candidate.drops.entrySet()) {
                ItemDropState state = world.resourceDrop(entry.getKey(),run.item);
                action.usage.debit(Kind.OBSERVATIONS, 1);
                if (state.status() != ObservationStatus.PRESENT || state.quantity() != entry.getValue()) {
                    if (Boolean.getBoolean("cognitivecraft.gametest.trace"))
                        System.out.println("IMP-007 custody stale stage=pickup-drop run=" + run.id
                                + " drop=" + entry.getKey() + " expected=" + entry.getValue()
                                + " state=" + state + " tick=" + world.tick());
                    finish(action, Reason.STALE_OBSERVATION, null, null); return;
                }
                if (!state.cell().dimension().equals(source(run.request).dimension())) {
                    finish(action, Reason.TARGET_INVALID, null, null); return;
                }
                if (state.cell().equals(target)) { batch = candidate; drop = entry; break; }
                trackedElsewhere = true;
            }
            if (drop != null) break;
        }
        if (batch == null) {
            finish(action, trackedElsewhere ? Reason.STALE_OBSERVATION : Reason.RESOURCE_MISSING,
                    null, null);
            return;
        }
        // The crop's source was verified at harvest. A dropped entity may drift afterward:
        // its current loaded cell, not its old crop cell, must pass the effect-time hook.
        Reason pickupPermission = world.permit(run.request, Effect.PICKUP, target);
        if (pickupPermission != null) { finish(action, pickupPermission, null, null); return; }
        if (!effectAllowance(action)) return;
        action.usage.debit(Kind.ATTEMPTED_EFFECTS, 1);
        ItemPickup result = world.resourcePickup(run.actor, drop.getKey(), drop.getValue(),run.item);
        if (result.collected() > 0) {
            committed(action);
            long expected = Math.addExact(run.expectedItems, result.collected());
            if (result.collected() > drop.getValue()
                    || result.remaining() != drop.getValue() - result.collected()
                    || inventoryQuantity(run) != expected) {
                finish(action, Reason.STALE_OBSERVATION, null, null); return;
            }
            run.expectedItems = expected;
            if (LogAcquisition.log(run.item)) run.inventoryFingerprint=world.resourceInventory(run.actor,run.item).fingerprint();
            batch.picked = Math.addExact(batch.picked, result.collected());
            if (result.remaining() == 0) batch.drops.remove(drop.getKey());
            else batch.drops.put(drop.getKey(), result.remaining());
            receipt(action, batch.id, CropDelivery.Stage.PICKUP, result.collected());
            finish(action, result.reason(), new IntValue(result.collected()), null);
        } else finish(action, result.reason() == null ? Reason.TARGET_UNAVAILABLE : result.reason(), null, null);
    }
    private void transfer(Active action, Cell target) {
        if (!allows(action, Effect.TRANSFER, target)) { finish(action, Reason.AUTHORITY_DENIED, null, null); return; }
        Run run = action.run;
        action.requested = ((IntValue)action.arguments.get("amount")).value() > Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int)((IntValue)action.arguments.get("amount")).value();
        if (LogAcquisition.log(run.item)
                && action.requested>((IntValue)run.request.request().arguments().get("amount")).value()-run.deposited) {
            finish(action,Reason.REQUEST_INVALID,null,null); return;
        }
        if (run.available() == 0) {
            finish(action, Reason.RESOURCE_MISSING, null, null); return;
        }
        if (!inventoryCurrent(run,world.resourceInventory(run.actor,run.item))) {
            finish(action, Reason.STALE_OBSERVATION, null, null); return;
        }
        Batch batch = run.batches.values().stream()
                .filter(b -> b.picked > b.deposited).findFirst().orElse(null);
        if (batch == null || !effectAllowance(action)) return;
        action.usage.debit(Kind.ATTEMPTED_EFFECTS, 1);
        ItemTransfer result = world.resourceTransferOne(run.actor, destination(run.request),run.item);
        if (result.deposited() == 1) {
            committed(action);
            if (inventoryQuantity(run) != run.expectedItems - 1) {
                finish(action, Reason.STALE_OBSERVATION, null, null); return;
            }
            run.expectedItems--;
            if (LogAcquisition.log(run.item)) run.inventoryFingerprint=world.resourceInventory(run.actor,run.item).fingerprint();
            run.deposited++;
            batch.deposited++;
            action.transferred++;
            receipt(action, batch.id, CropDelivery.Stage.DEPOSIT, 1);
            if (result.reason() != null || action.transferred == action.requested)
                finish(action, result.reason(), new IntValue(action.transferred), null);
            else action.current = current(action, false, null, null, null);
        } else finish(action, result.reason() == null ? Reason.TARGET_UNAVAILABLE : result.reason(),
                new IntValue(action.transferred), null);
    }

    private boolean effectAllowance(Active action) {
        if (!action.usage.canDebit(Kind.ATTEMPTED_EFFECTS, 1)
                || !action.usage.canDebit(Kind.COMMITTED_EFFECTS, 1)) {
            finish(action, Reason.BUDGET_EXHAUSTED, null, null); return false;
        }
        if (action.run.receipts.size() + action.run.resourceReceipts.size() >= limits.maxReceiptsPerRun()) {
            finish(action, Reason.BUDGET_EXHAUSTED, null, null); return false;
        }
        return true;
    }
    private void committed(Active action) {
        // World effects and their debit are serialized by the server thread.
        action.effects++;
        action.usage.debit(Kind.COMMITTED_EFFECTS, 1);
    }
    private void receipt(Active action, UUID batchId, CropDelivery.Stage stage, long wheat) {
        if (action.run.receipts.size() + action.run.resourceReceipts.size() >= limits.maxReceiptsPerRun())
            throw new Budgets.Exhausted();
        Run run = action.run;
        if (LogAcquisition.log(run.item)) {
            Batch batch=run.batches.get(batchId); Cell cell=batch.sourceBlock;
            UUID operation=UUID.nameUUIDFromBytes((action.handle.id()+"/"+action.effects)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var resource=new ResourceDelivery.Receipt(UUID.randomUUID(),run.request.context(),run.id,operation,batchId,
                    run.actor,source(run.request),destination(run.request),run.item,ResourceDelivery.Stage.valueOf(stage.name()),wheat,
                    new ResourceDelivery.BlockPosition(cell.dimension(),cell.x(),cell.y(),cell.z()),batch.originalDrops);
            run.resourceReceipts.add(resource); action.resourceReceipts.add(resource); return;
        }
        CropDelivery.CropReceipt receipt = new CropDelivery.CropReceipt(
                UUID.randomUUID(), run.id, batchId, run.actor, source(run.request),
                destination(run.request), stage, wheat);
        run.receipts.add(receipt);
        action.receipts.add(receipt);
    }
    private void finish(Active action, Reason reason, Value result, ObservationSnapshot snapshot) {
        if (action.current.terminal()) return;
        if (result != null && action.operation != null
                && result.type() != action.operation.signature().resultType())
            throw new IllegalStateException("Primitive result type mismatch");
        if (snapshot != null && (action.operation == null
                || action.operation.signature().effects().iterator().next() != Effect.OBSERVE))
            throw new IllegalStateException("Non-observation snapshot");
        action.current = current(action, true, reason, result, snapshot);
        long observedAt = clock.millis();
        if (snapshot != null && reason == null && observedAt >= 0 && observationSequence < Long.MAX_VALUE) {
            Cuboid area;
            ObservationKind kind;
            UUID entity = null;
            if (action.operation == Operation.OBSERVE_SOURCE || action.operation == Operation.OBSERVE_OAK_SOURCE) {
                kind = action.run.item.equals(ResourceDelivery.BIRCH_LOG) ? ObservationKind.SOURCE_BIRCH_LOGS : action.operation.oak() ? ObservationKind.SOURCE_OAK_LOGS : ObservationKind.SOURCE_CROPS; area = source(action.run.request);
            } else {
                kind = action.operation == Operation.OBSERVE_INVENTORY
                        ? ObservationKind.ACTOR_STOCK : ObservationKind.CONTAINER_STOCK;
                if (action.operation.oak()) kind=action.run.item.equals(ResourceDelivery.BIRCH_LOG)
                        ? action.operation==Operation.OBSERVE_OAK_INVENTORY ? ObservationKind.ACTOR_BIRCH_STOCK : ObservationKind.CONTAINER_BIRCH_STOCK
                        : action.operation==Operation.OBSERVE_OAK_INVENTORY ? ObservationKind.ACTOR_OAK_STOCK : ObservationKind.CONTAINER_OAK_STOCK;
                Cell cell = target(action);
                area = new Cuboid(cell.dimension(), cell.x(), cell.y(), cell.z(), cell.x(), cell.y(), cell.z());
                if (kind == ObservationKind.ACTOR_STOCK || kind == ObservationKind.ACTOR_OAK_STOCK || kind == ObservationKind.ACTOR_BIRCH_STOCK) entity = action.run.actor.entityId();
            }
            action.evidence = new ObservedEvidence(observationSource, action.run.request.context(),
                    action.run.actor, new ObservationTarget(kind, area, entity), snapshot, observedAt, ++observationSequence);
            // Retained knowledge is advisory; unavailable intake cannot change primitive semantics.
            try { observationSink.accept(action.evidence); } catch (RuntimeException unavailable) { }
        }
        if(action.operation==Operation.PICKUP_TRACKED_OAK_LOGS
                && (Boolean.getBoolean("cognitivecraft.gametest.trace") || Boolean.getBoolean("cognitivecraft.navigation.trace")))
            System.out.println("CognitiveCraft LOG_PICKUP terminal run="+action.run.id
                    +" actor="+action.run.actor.entityId()+" reason="+reason+" effects="+action.effects
                    +" expectedActorStock="+action.run.expectedItems+" tick="+world.tick()
                    +" ledger="+action.usage.snapshot());
        if ((action.operation == Operation.MOVE || action.operation == Operation.MOVE_TO_SOURCE
                || action.operation == Operation.APPROACH_OAK_LOG
                || action.operation == Operation.MOVE_TO_DESTINATION || action.operation==Operation.MOVE_TO_INPUT || action.operation==Operation.MOVE_TO_TABLE || action.operation==Operation.MOVE_TO_PLACEMENT)
                && action.movementStarted && action.run != null) {
            world.stopMovement(action.run.actor);
            if (Boolean.getBoolean("cognitivecraft.gametest.trace")
                    || Boolean.getBoolean("cognitivecraft.navigation.trace"))
                System.out.println("CognitiveCraft MOVE terminal run=" + action.run.id
                        + " actor=" + action.run.actor.entityId()
                        + " reason=" + reason + " bound=" + action.terminalBound
                        + " actionTicks=" + (world.tick() - action.startedTick)
                        + " wallMillis=" + (clock.millis() - action.startedMillis)
                        + " chargedTravel=" + action.travel + " ledger="
                        + action.usage.snapshot());
        }
    }
    private ActionReceipt current(Active action, boolean terminal, Reason reason,
                                  Value result, ObservationSnapshot snapshot) {
        return new ActionReceipt(action.handle, terminal, action.effects,
                List.copyOf(action.receipts), reason, result, snapshot,List.copyOf(action.resourceReceipts));
    }
    private Active get(ActionHandle handle) {
        Objects.requireNonNull(handle);
        Active action = handles.get(handle.id());
        if (action == null || !action.handle.equals(handle)) throw new IllegalArgumentException("Unknown handle");
        return action;
    }
    private void thread() {
        if (!world.onGameThread()) throw new IllegalStateException("Gateway requires the server thread");
    }
    private static Reason validate(ValidatedRequest request, Operation op, Map<String, Value> args) {
        boolean placement=CraftingTablePlacement.is(request.request().capability());
        if(placement && (!CraftingTablePlacement.valid(request.request()) || !CraftingTablePlacement.permits(op)
                || args.containsKey("target") && !Objects.equals(args.get("target"),request.request().arguments().get("target"))))return Reason.REQUEST_INVALID;
        boolean plank=RecipeProduction.is(request.request().capability());
        if(plank) {
            var values=request.request().arguments();
            if(values.size()!=RecipeProduction.of(request.request().capability()).spec().parameters().size() || !(values.get("amount") instanceof IntValue amount) || amount.value()<1 || amount.value()>RecipeProduction.of(request.request().capability()).maximumAmount()
                    || !(values.get("source") instanceof ContainerValue) || !(values.get("destination") instanceof ContainerValue))
                return Reason.REQUEST_INVALID;
            if(RecipeProduction.of(request.request().capability()).axe() && (!(values.get("table") instanceof FacilityValue f)
                    || !f.value().dimension().equals(((ActorValue)values.get("actor")).value().dimension())
                    || args.containsKey("table") && !values.get("table").equals(args.get("table"))))return Reason.REQUEST_INVALID;
            if((op==Operation.OBSERVE_BIRCH_PLANK_INPUTS || op==Operation.OBSERVE_PLANK_INPUTS || op==Operation.OBSERVE_STICK_INPUTS || op==Operation.OBSERVE_AXE_INPUTS || op==Operation.OBSERVE_TABLE_INPUTS) && !Objects.equals(values.get("amount"),args.get("amount")))return Reason.REQUEST_INVALID;
        }
        if(LogAcquisition.is(request.request().capability())) {
            try {
                var values=request.request().arguments();
                if(!(values.get("amount") instanceof IntValue amount) || amount.value()<1 || amount.value()>OakLogDelivery.MAX_AMOUNT
                        || !(values.get("source") instanceof AreaValue area) || ResourceLeases.cells(area.value())>256)
                    return Reason.REQUEST_INVALID;
            } catch(ArithmeticException overflow) { return Reason.REQUEST_INVALID; }
        }
        if (!ResourceDelivery.isDelivery(request.request().capability())
                || !placement && op!=Operation.MOVE_TO_DESTINATION && (plank ? !RecipeProduction.permits(request.request().capability(),op)
                : LogAcquisition.is(request.request().capability()) ? !LogAcquisition.permits(request.request().capability(),op)
                : op.placement() || op.plank() || op.birchPlank() || op.birchLog() || op.stick() || op.axe() || op.table() || op.oak())
                || !args.keySet().equals(op.signature().parameters().stream()
                .map(Parameter::name).collect(java.util.stream.Collectors.toSet())))
            return Reason.REQUEST_INVALID;
        for (Parameter p : op.signature().parameters()) {
            Value value = args.get(p.name());
            if (value == null || value.type() != p.type()) return Reason.REQUEST_INVALID;
            if (value instanceof IntValue n && (n.value() < p.minimum() || n.value() > p.maximum()))
                return Reason.REQUEST_INVALID;
        }
        if (!Objects.equals(request.request().arguments().get("actor"), args.get("actor"))
                || args.containsKey("source") && !Objects.equals(
                request.request().arguments().get("source"), args.get("source"))
                || args.containsKey("destination") && !Objects.equals(
                request.request().arguments().get("destination"), args.get("destination")))
            return Reason.REQUEST_INVALID;
        return null;
    }
    private static ActorRef actor(ValidatedRequest request) {
        return ((ActorValue)request.request().arguments().get("actor")).value();
    }
    private static Cuboid source(ValidatedRequest request) {
        return ResourceDelivery.sourceArea(request.request());
    }
    private static ContainerRef destination(ValidatedRequest request) {
        return ResourceDelivery.destinationAddress(request.request());
    }
    private long inventoryQuantity(Run run) {
        return run.item.equals(ResourceDelivery.WHEAT) ? world.actor(run.actor).inventoryWheat()
                : world.resourceInventory(run.actor,run.item).quantity();
    }
    private boolean inventoryCurrent(Run run,ItemStock stock) {
        if (run.item.equals(ResourceDelivery.WHEAT)) return world.actor(run.actor).inventoryWheat()==run.expectedItems;
        return stock.status()==ObservationStatus.PRESENT && stock.reason()==null
                && stock.quantity()==run.expectedItems && stock.fingerprint().equals(run.inventoryFingerprint);
    }
    private boolean allows(Active action, Effect effect, Cell target) {
        return authority.currentlyAllows(action.run.request, action.run.id, effect,
                new EffectTarget(target.dimension(), target.x(), target.y(), target.z()));
    }
    private Cell target(Active action) {
        if (action.operation == Operation.OBSERVE_SOURCE || action.operation==Operation.OBSERVE_OAK_SOURCE
                || action.operation==Operation.APPROACH_OAK_LOG || action.operation==Operation.HARVEST_NEXT_OAK_LOG
                || action.operation == Operation.HARVEST_NEXT_WHEAT) {
            if (action.selectedTarget!=null) return action.selectedTarget;
            Cuboid area = source(action.run.request);
            return new Cell(area.dimension(), area.minX(), area.minY(), area.minZ());
        }
        if (action.operation == Operation.OBSERVE_CONTAINER
                || action.operation == Operation.OBSERVE_OAK_CONTAINER
                || action.operation == Operation.TRANSFER_OAK_LOGS
                || action.operation == Operation.TRANSFER_WHEAT
                || action.operation == Operation.MOVE_TO_DESTINATION) {
            ContainerRef box = destination(action.run.request);
            return new Cell(box.dimension(), box.x(), box.y(), box.z());
        }
        ActorRef actor = action.run.actor;
        if (!action.arguments.containsKey("x")) {
            ActorState state = world.actor(actor);
            return new Cell(actor.dimension(), state.x(), state.y(), state.z());
        }
        return new Cell(actor.dimension(), Math.toIntExact(((IntValue)action.arguments.get("x")).value()),
                Math.toIntExact(((IntValue)action.arguments.get("y")).value()),
                Math.toIntExact(((IntValue)action.arguments.get("z")).value()));
    }
}
