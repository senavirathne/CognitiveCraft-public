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
    public interface WorldAccess {
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
        /** Real block destruction and drop spawn; no direct inventory insertion. */
        Harvest harvest(ActorRef actor, Cell target);
        /** Only a tracked physical drop can be picked up; validate identity/count and reach. */
        Pickup pickup(ActorRef actor, UUID dropId, int expectedRemaining);
        /** One actual wheat item debited from actor and credited to the current container. */
        Transfer transferOne(ActorRef actor, ContainerRef destination);
    }

    private static final class Batch {
        final UUID id;
        final Map<UUID, Integer> drops = new LinkedHashMap<>();
        long picked;
        long deposited;
        Batch(UUID id) { this.id = id; }
    }
    private static final class Run {
        final ActorRef actor;
        final ValidatedRequest request;
        final UUID id;
        final Map<UUID, Batch> batches = new LinkedHashMap<>();
        final List<CropDelivery.CropReceipt> receipts = new ArrayList<>();
        Cell expectedNextCrop;
        long expectedWheat;
        long lastTick;
        Run(ActorRef actor, ValidatedRequest request, UUID id, long wheat, long tick) {
            this.actor = actor; this.request = request; this.id = id;
            expectedWheat = wheat; lastTick = tick;
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
        ActionReceipt current;
        Active(ActionHandle handle, Run run, Operation operation, Map<String, Value> arguments,
               Budgets.Ledger usage, long tick, long startedMillis, long deadlineMillis) {
            this.handle = handle; this.run = run; this.operation = operation;
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
    private final Map<UUID, Run> runs = new HashMap<>();
    private final Map<UUID, Active> handles = new LinkedHashMap<>();

    public SurvivalGateway(WorldAccess world, AuthorityPolicy authority, Limits limits, Clock clock) {
        this.world = Objects.requireNonNull(world);
        this.authority = Objects.requireNonNull(authority);
        this.limits = Objects.requireNonNull(limits);
        this.clock = Objects.requireNonNull(clock);
    }
    public List<PrimitiveSignature> registered() { return GatewayPrimitives.instance().all(); }
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
        if (!authority.currentlyAllows(run.actor, effect, run.request.context())) {
            finish(action, Reason.AUTHORITY_DENIED, null, null); return;
        }
        if (op == Operation.MOVE_TO_SOURCE) {
            moveToSource(action);
            run.lastTick = world.tick();
            return;
        }
        Cell target = target(action);
        Reason permission = world.permit(run.request, effect, target);
        if (permission != null) { finish(action, permission, null, null); return; }
        switch (op) {
            case OBSERVE_SOURCE -> observeSource(action);
            case OBSERVE_INVENTORY -> observeStock(action, world.inventory(run.actor), target);
            case OBSERVE_CONTAINER -> observeStock(action, world.container(destination(run.request)), target);
            case MOVE, MOVE_TO_DESTINATION -> move(action, target);
            case MOVE_TO_SOURCE -> throw new AssertionError("handled above");
            case HARVEST_WHEAT -> harvest(action, target);
            case HARVEST_NEXT_WHEAT -> harvestNext(action);
            case PICKUP_WHEAT -> pickup(action, target);
            case PICKUP_TRACKED_WHEAT -> pickupTracked(action);
            case TRANSFER_WHEAT -> transfer(action, target);
        }
        run.lastTick = world.tick();
    }

    private void moveToSource(Active action) {
        Cell selected = selectNextCrop(action, Effect.OBSERVE);
        if (selected == null) return;
        // Selection evidence is refreshed while navigation progresses.
        if (!authority.currentlyAllows(action.run.actor, Effect.OBSERVE, action.run.request.context())) {
            finish(action, Reason.AUTHORITY_DENIED, null, null); return;
        }
        Reason observed = world.permit(action.run.request, Effect.OBSERVE, selected);
        if (observed != null) { finish(action, observed, null, null); return; }
        action.usage.debit(Kind.OBSERVATIONS, 1);
        CropState current = world.crop(selected);
        if (current.status() != ObservationStatus.PRESENT || !current.matureWheat()) {
            finish(action, Reason.STALE_OBSERVATION, null, null); return;
        }
        Reason permission = world.permit(action.run.request, Effect.MOVE, selected);
        if (permission != null) { finish(action, permission, null, null); return; }
        move(action, selected);
        if (action.current.terminal() && action.current.reason() == null)
            action.run.expectedNextCrop = selected;
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
            if (!authority.currentlyAllows(action.run.actor, observationPermission, action.run.request.context())) {
                finish(action, Reason.AUTHORITY_DENIED, null, null); return null;
            }
            Reason denied = world.permit(action.run.request, observationPermission, cell);
            if (denied != null) { finish(action, denied, null, null); return null; }
            action.usage.debit(Kind.OBSERVATIONS, 1);
            CropState crop = world.crop(cell);
            if (crop.status() == ObservationStatus.UNKNOWN) action.unknown++;
            else if (crop.status() == ObservationStatus.PRESENT && crop.matureWheat())
                return action.selectedTarget = cell;
        }
        if (action.scanned >= volume)
            finish(action, action.unknown > 0 ? Reason.STALE_OBSERVATION : Reason.RESOURCE_MISSING, null, null);
        return null;
    }

    private void move(Active action, Cell target) {
        if (!action.usage.canDebit(Kind.TRAVEL_BLOCKS, 1)
                || !action.usage.canDebit(Kind.INSTRUCTIONS, limits.navigationCallsPerPoll())
                || action.travel >= limits.maxTravelBlocks()) {
            action.terminalBound = "travel-or-navigation-allowance";
            finish(action, Reason.BUDGET_EXHAUSTED, null, null); return;
        }
        action.movementStarted = true;
        Movement movement = action.operation == Operation.MOVE_TO_SOURCE
                ? world.moveToSource(action.run.actor,target,limits.navigationCallsPerPoll())
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
            CropState crop = world.crop(cell);
            if (crop.status() == ObservationStatus.UNKNOWN) action.unknown++;
            else if (crop.status() == ObservationStatus.PRESENT && crop.matureWheat()) action.mature++;
        }
        if (action.scanned >= volume) {
            ObservationStatus status = action.unknown > 0 ? ObservationStatus.UNKNOWN
                    : action.mature > 0 ? ObservationStatus.PRESENT : ObservationStatus.ABSENT;
            ObservationSnapshot snapshot = new ObservationSnapshot(
                    new ObservationRef(UUID.randomUUID(), world.tick(), area.dimension()), status,
                    "source:" + UUID.nameUUIDFromBytes(area.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    world.tick(), action.scanned,
                    Map.of("mature_wheat", (long)action.mature, "unknown_cells", (long)action.unknown));
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
    private void harvest(Active action, Cell cell) {
        Run run = action.run;
        if (!cell.inside(source(run.request))) { finish(action, Reason.AUTHORITY_DENIED, null, null); return; }
        CropState crop = world.crop(cell);
        action.usage.debit(Kind.OBSERVATIONS, 1);
        if (crop.status() == ObservationStatus.UNKNOWN) {
            finish(action, Reason.STALE_OBSERVATION, null, null); return;
        }
        if (crop.status() != ObservationStatus.PRESENT || !crop.matureWheat()) {
            finish(action, Reason.TARGET_INVALID, null, null); return;
        }
        if (!effectAllowance(action)) return;
        action.usage.debit(Kind.ATTEMPTED_EFFECTS, 1);
        Harvest result = world.harvest(run.actor, cell);
        if (result.cropRemoved()) {
            committed(action);
            Batch batch = new Batch(UUID.randomUUID());
            long wheat = 0;
            for (Drop drop : result.spawnedWheat()) {
                if (drop.wheat() > 64 || batch.drops.putIfAbsent(drop.entityId(), drop.wheat()) != null) {
                    finish(action, Reason.ACTION_FAILED, null, null); return;
                }
                wheat = Math.addExact(wheat, drop.wheat());
            }
            if (wheat > 0) {
                run.batches.put(batch.id, batch);
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
                DropState state = world.dropped(entry.getKey());
                action.usage.debit(Kind.OBSERVATIONS, 1);
                if (state.status() != ObservationStatus.PRESENT || state.wheat() != entry.getValue()) {
                    if (Boolean.getBoolean("cognitivecraft.gametest.trace"))
                        System.out.println("IMP-007 custody stale stage=tracked-drop run=" + run.id
                                + " drop=" + entry.getKey() + " expected=" + entry.getValue()
                                + " state=" + state + " inventoryWheat="
                                + world.actor(run.actor).inventoryWheat() + " tick=" + world.tick());
                    finish(action, Reason.STALE_OBSERVATION, null, null); return;
                }
                pickup(action, state.cell());
                return;
            }
        }
        finish(action, Reason.RESOURCE_MISSING, null, null);
    }
    private void pickup(Active action, Cell target) {
        Run run = action.run;
        if (world.actor(run.actor).inventoryWheat() != run.expectedWheat) {
            if (Boolean.getBoolean("cognitivecraft.gametest.trace"))
                System.out.println("IMP-007 custody stale stage=actor-stock run=" + run.id
                        + " expected=" + run.expectedWheat + " actual="
                        + world.actor(run.actor).inventoryWheat() + " tick=" + world.tick());
            finish(action, Reason.STALE_OBSERVATION, null, null); return;
        }
        Batch batch = null;
        Map.Entry<UUID, Integer> drop = null;
        boolean trackedElsewhere = false;
        for (Batch candidate : run.batches.values()) {
            for (var entry : candidate.drops.entrySet()) {
                DropState state = world.dropped(entry.getKey());
                action.usage.debit(Kind.OBSERVATIONS, 1);
                if (state.status() != ObservationStatus.PRESENT || state.wheat() != entry.getValue()) {
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
        Pickup result = world.pickup(run.actor, drop.getKey(), drop.getValue());
        if (result.collectedWheat() > 0) {
            committed(action);
            long expected = Math.addExact(run.expectedWheat, result.collectedWheat());
            if (result.collectedWheat() > drop.getValue()
                    || result.dropRemaining() != drop.getValue() - result.collectedWheat()
                    || world.actor(run.actor).inventoryWheat() != expected) {
                finish(action, Reason.STALE_OBSERVATION, null, null); return;
            }
            run.expectedWheat = expected;
            batch.picked = Math.addExact(batch.picked, result.collectedWheat());
            if (result.dropRemaining() == 0) batch.drops.remove(drop.getKey());
            else batch.drops.put(drop.getKey(), result.dropRemaining());
            receipt(action, batch.id, CropDelivery.Stage.PICKUP, result.collectedWheat());
            finish(action, result.reason(), new IntValue(result.collectedWheat()), null);
        } else finish(action, result.reason() == null ? Reason.TARGET_UNAVAILABLE : result.reason(), null, null);
    }
    private void transfer(Active action, Cell target) {
        Run run = action.run;
        action.requested = ((IntValue)action.arguments.get("amount")).value() > Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int)((IntValue)action.arguments.get("amount")).value();
        if (run.available() == 0) {
            finish(action, Reason.RESOURCE_MISSING, null, null); return;
        }
        if (world.actor(run.actor).inventoryWheat() != run.expectedWheat) {
            finish(action, Reason.STALE_OBSERVATION, null, null); return;
        }
        Batch batch = run.batches.values().stream()
                .filter(b -> b.picked > b.deposited).findFirst().orElse(null);
        if (batch == null || !effectAllowance(action)) return;
        action.usage.debit(Kind.ATTEMPTED_EFFECTS, 1);
        Transfer result = world.transferOne(run.actor, destination(run.request));
        if (result.depositedWheat() == 1) {
            committed(action);
            if (world.actor(run.actor).inventoryWheat() != run.expectedWheat - 1) {
                finish(action, Reason.STALE_OBSERVATION, null, null); return;
            }
            run.expectedWheat--;
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
        if (action.run.receipts.size() >= limits.maxReceiptsPerRun()) {
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
        if (action.run.receipts.size() >= limits.maxReceiptsPerRun())
            throw new Budgets.Exhausted();
        Run run = action.run;
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
        if ((action.operation == Operation.MOVE || action.operation == Operation.MOVE_TO_SOURCE
                || action.operation == Operation.MOVE_TO_DESTINATION) && action.movementStarted && action.run != null) {
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
                List.copyOf(action.receipts), reason, result, snapshot);
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
        if (!request.request().capability().equals(CropDelivery.ID)
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
        return ((AreaValue)request.request().arguments().get("source")).value();
    }
    private static ContainerRef destination(ValidatedRequest request) {
        return ((ContainerValue)request.request().arguments().get("destination")).value();
    }
    private Cell target(Active action) {
        if (action.operation == Operation.OBSERVE_SOURCE
                || action.operation == Operation.HARVEST_NEXT_WHEAT) {
            Cuboid area = source(action.run.request);
            return new Cell(area.dimension(), area.minX(), area.minY(), area.minZ());
        }
        if (action.operation == Operation.OBSERVE_CONTAINER
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
