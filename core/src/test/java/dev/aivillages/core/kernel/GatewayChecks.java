package dev.aivillages.core.kernel;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.aivillages.core.kernel.Budgets.Kind;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;
import static dev.aivillages.core.kernel.Outcomes.Reason;
import static dev.aivillages.core.kernel.SurvivalGateway.*;

/** Deterministic fake mechanics, boundaries and receipts; also runnable without JUnit. */
public final class GatewayChecks {
    private GatewayChecks() { }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static final class Ticks extends Clock {
        long millis = 1_000;
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }
    static final class FakeWorld implements WorldAccess {
        final Map<Cell, Boolean> crops = new HashMap<>();
        final Map<UUID, DropSim> drops = new HashMap<>();
        final Map<UUID, UUID> leases = new HashMap<>();
        final Cell source = new Cell("minecraft:overworld", 0, 64, 0);
        Cell actorPos = source;
        Cell deniedCell;
        long tick = 1;
        long actorWheat, containerWheat, seedsDropped, containerCapacity = 64, actorCapacity = 64;
        boolean actorLoaded = true, actorAlive = true, deny, targetInvalid,
                mobGriefing = true, unreachable, gameThread = true;
        int spawnedWheat = 1, scans, moves, harvests, pickups, transfers, stopCalls;
        record DropSim(Cell cell, int wheat) { }
        @Override public boolean onGameThread() { return gameThread; }
        @Override public long tick() { return tick; }
        @Override public ActorState actor(ActorRef actor) {
            return new ActorState(actorLoaded, actorAlive, actor.dimension(),
                    actorPos.x(), actorPos.y(), actorPos.z(), actorWheat);
        }
        @Override public boolean acquire(ActorRef actor, UUID run) {
            if (leases.containsKey(actor.entityId()) && !leases.get(actor.entityId()).equals(run)) return false;
            leases.put(actor.entityId(), run); return true;
        }
        @Override public boolean holds(ActorRef actor, UUID run) {
            return run.equals(leases.get(actor.entityId()));
        }
        @Override public void release(ActorRef actor, UUID run) {
            leases.remove(actor.entityId(), run);
        }
        @Override public void stopMovement(ActorRef actor) { stopCalls++; }
        @Override public Reason permit(ValidatedRequest request, Effect effect, Cell cell) {
            if (targetInvalid) return Reason.TARGET_INVALID;
            if (deny || cell.equals(deniedCell)
                    || !mobGriefing && (effect == Effect.HARVEST || effect == Effect.PICKUP
                    || effect == Effect.TRANSFER)) return Reason.AUTHORITY_DENIED;
            return null;
        }
        @Override public CropState crop(Cell cell) {
            scans++;
            if (cell.x() == 99) return new CropState(ObservationStatus.UNKNOWN, false, cell.identity());
            Boolean mature = crops.get(cell);
            return new CropState(mature == null ? ObservationStatus.ABSENT : ObservationStatus.PRESENT,
                    Boolean.TRUE.equals(mature), cell.identity());
        }
        @Override public StockState inventory(ActorRef actor) {
            return new StockState(ObservationStatus.PRESENT, "actor:" + actor.entityId(), actorWheat, 0);
        }
        @Override public StockState container(ContainerRef destination) {
            return new StockState(ObservationStatus.PRESENT, "container:destination", containerWheat, 0);
        }
        @Override public Movement move(ActorRef actor, Cell target, int maximum) {
            moves++;
            if (unreachable) return new Movement(false, true, 1, 0);
            if (actorPos.equals(target)) return new Movement(true, false, 0, 0);
            int x = actorPos.x() + Integer.signum(target.x() - actorPos.x());
            int y = actorPos.y() + Integer.signum(target.y() - actorPos.y());
            int z = actorPos.z() + Integer.signum(target.z() - actorPos.z());
            actorPos = new Cell(target.dimension(), x, y, z);
            return new Movement(actorPos.equals(target), false, 1, 1);
        }
        private boolean reach(Cell cell) {
            return Math.abs((long)actorPos.x()-cell.x()) <= 1
                    && Math.abs((long)actorPos.y()-cell.y()) <= 1
                    && Math.abs((long)actorPos.z()-cell.z()) <= 1;
        }
        @Override public Harvest harvest(ActorRef actor, Cell cell) {
            if (!reach(cell)) return new Harvest(false, List.of(), Reason.TARGET_UNAVAILABLE);
            if (!Boolean.TRUE.equals(crops.get(cell))) return new Harvest(false, List.of(), Reason.TARGET_INVALID);
            crops.remove(cell); harvests++; seedsDropped += 2;
            UUID id = UUID.randomUUID(); drops.put(id, new DropSim(cell, spawnedWheat));
            return new Harvest(true, List.of(new Drop(id, spawnedWheat)), null);
        }
        @Override public DropState dropped(UUID id) {
            DropSim drop = drops.get(id);
            return drop == null ? new DropState(ObservationStatus.ABSENT, source, 0)
                    : new DropState(ObservationStatus.PRESENT, drop.cell(), drop.wheat());
        }
        @Override public Pickup pickup(ActorRef actor, UUID id, int expected) {
            DropSim drop = drops.get(id);
            if (drop == null || drop.wheat() != expected) return new Pickup(0, 0, Reason.STALE_OBSERVATION);
            if (!reach(drop.cell())) return new Pickup(0, expected, Reason.TARGET_UNAVAILABLE);
            int moved = (int)Math.min(expected, Math.max(0, actorCapacity - actorWheat));
            if (moved == 0) return new Pickup(0, expected, Reason.TARGET_UNAVAILABLE);
            actorWheat += moved; pickups++;
            if (moved == expected) drops.remove(id);
            else drops.put(id, new DropSim(drop.cell(), expected-moved));
            return new Pickup(moved, expected-moved, null);
        }
        @Override public Transfer transferOne(ActorRef actor, ContainerRef destination) {
            Cell target = new Cell(destination.dimension(), destination.x(), destination.y(), destination.z());
            if (!reach(target)) return new Transfer(0, Reason.TARGET_UNAVAILABLE);
            if (containerWheat >= containerCapacity) return new Transfer(0, Reason.TARGET_UNAVAILABLE);
            if (actorWheat == 0) return new Transfer(0, Reason.RESOURCE_MISSING);
            actorWheat--; containerWheat++; transfers++;
            return new Transfer(1, null);
        }
    }
    private static final class Fixture {
        final FakeWorld world = new FakeWorld();
        final Ticks clock = new Ticks();
        final ActorRef actor = new ActorRef(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld");
        final PrincipalRef owner = new PrincipalRef(UUID.randomUUID());
        final PrincipalRef stranger = new PrincipalRef(UUID.randomUUID());
        final Cuboid area = new Cuboid(actor.dimension(), 0, 64, 0, 1, 64, 1);
        final ContainerRef chest = new ContainerRef(actor.dimension(), 2, 64, 0);
        final UUID runId = UUID.randomUUID();
        boolean grant;
        final SurvivalGateway gateway = new SurvivalGateway(world,
                (a, effect, context) -> context.principal().equals(owner) || grant,
                new Limits(2, 64, 48, 2, 32, 1, 100, 16), clock);
        final Budgets.Ledger budget = new Budgets.Ledger(new Budgets.Limits(Map.of(
                Kind.ELAPSED_TICKS, 100L, Kind.INSTRUCTIONS, 200L,
                Kind.OBSERVATIONS, 50L, Kind.TRAVEL_BLOCKS, 16L,
                Kind.ATTEMPTED_EFFECTS, 20L, Kind.COMMITTED_EFFECTS, 20L), 100_000), clock);
        ValidatedRequest bound(PrincipalRef p) {
            return new ValidatedRequest(new CapabilityRequest(CropDelivery.ID, Map.of(
                    "actor", new ActorValue(actor), "amount", new IntValue(2),
                    "source", new AreaValue(area), "destination", new ContainerValue(chest))),
                    new TrustedContext(p, new ScopeRef(UUID.randomUUID(), UUID.randomUUID())),
                    new ObservationRef(UUID.randomUUID(), 0, actor.dimension()));
        }
        final ValidatedRequest ownerRequest = bound(owner);
        ActionHandle start(Operation operation, Map<String, Value> args) {
            return gateway.start(ownerRequest, new RunCorrelation(runId,
                    new ArtifactRef(CropDelivery.ID, "a".repeat(64)), null),
                    operation.requirement(), args, budget);
        }
        Map<String, Value> at(Operation op, Cell cell) {
            return Map.of("actor", new ActorValue(actor), "x", new IntValue(cell.x()),
                    "y", new IntValue(cell.y()), "z", new IntValue(cell.z()));
        }
        Map<String, Value> transferArgs(int count) {
            return Map.of("actor", new ActorValue(actor), "destination",
                    new ContainerValue(chest), "amount", new IntValue(count));
        }
        ActionReceipt poll(ActionHandle handle) {
            ActionReceipt result = gateway.poll(handle);
            while (!result.terminal()) {
                world.tick++; clock.millis += 50;
                result = gateway.poll(handle);
            }
            return result;
        }
        ActionReceipt harvest(Cell cell) {
            return poll(start(Operation.HARVEST_WHEAT, at(Operation.HARVEST_WHEAT, cell)));
        }
        ActionReceipt pickup(Cell cell) {
            return poll(start(Operation.PICKUP_WHEAT, at(Operation.PICKUP_WHEAT, cell)));
        }
        ActionReceipt transfer(int count) {
            return poll(start(Operation.TRANSFER_WHEAT, transferArgs(count)));
        }
    }
    private static void separateActionsAndAttribution() {
        Fixture f = new Fixture();
        f.world.crops.put(f.world.source, true);
        f.world.actorWheat = 7;
        f.world.containerWheat = 8;
        ActionReceipt harvest = f.harvest(f.world.source);
        check(harvest.reason() == null && harvest.committedEffects() == 1, "Harvest effect");
        check(f.world.actorWheat == 7 && f.world.containerWheat == 8 && f.world.drops.size() == 1,
                "Harvest inserted directly or credited old stock");
        ActionReceipt pickup = f.pickup(f.world.source);
        check(pickup.reason() == null && f.world.actorWheat == 8 && f.world.containerWheat == 8,
                "Pickup did not collect actual drop");
        f.world.actorPos = new Cell(f.actor.dimension(), 2, 64, 0);
        ActionReceipt transfer = f.transfer(1);
        check(transfer.reason() == null && f.world.actorWheat == 7 && f.world.containerWheat == 9,
                "Transfer did not physically debit and credit");
        List<CropDelivery.CropReceipt> receipts = new ArrayList<>();
        receipts.addAll(harvest.cropReceipts()); receipts.addAll(pickup.cropReceipts());
        receipts.addAll(transfer.cropReceipts());
        check(!CropDelivery.completed(f.ownerRequest, f.runId, receipts), "One wheat cannot fulfill request of two");
        check(receipts.stream().map(CropDelivery.CropReceipt::batchId).distinct().count() == 1,
                "Custody batch changed");
        check(f.world.seedsDropped == 2, "Extra seed drops were deleted");
    }
    private static void boundsAndUnknown() {
        Fixture f = new Fixture();
        f.world.crops.put(f.world.source, false);
        check(f.harvest(f.world.source).reason() == Reason.TARGET_INVALID, "Immature crop harvested");
        Cell outside = new Cell(f.actor.dimension(), 6, 64, 0);
        f.world.crops.put(outside, true);
        check(f.harvest(outside).reason() == Reason.AUTHORITY_DENIED, "Outside crop harvested");
        check(Boolean.TRUE.equals(f.world.crops.get(outside)), "Outside crop changed");
        f.world.crops.put(f.world.source, true);
        int priorScans = f.world.scans;
        ActionHandle scan = f.start(Operation.OBSERVE_SOURCE,
                Map.of("actor", new ActorValue(f.actor), "source", new AreaValue(f.area)));
        ActionReceipt first = f.gateway.poll(scan);
        check(!first.terminal() && f.world.scans == priorScans + 2, "Scan did not yield at two cells");
        check(f.gateway.poll(scan).equals(first) && f.world.scans == priorScans + 2,
                "Second poll in a tick scanned again");
        f.world.tick++;
        ActionReceipt last = f.gateway.poll(scan);
        check(last.terminal() && last.observation().scannedCells() == 4
                && last.observation().status() == ObservationStatus.PRESENT
                && last.result().equals(new IntValue(1)), "Scan result");
        check(f.budget.snapshot().get(Kind.OBSERVATIONS) == 5, "Scan observations accounting");
        f.gateway.releaseRun(f.runId);
        Fixture missing = new Fixture();
        Cuboid unknownArea = new Cuboid(missing.actor.dimension(), 99, 64, 0, 99, 64, 0);
        ActionHandle scanUnknown = missing.start(Operation.OBSERVE_SOURCE, Map.of(
                "actor", new ActorValue(missing.actor), "source", new AreaValue(unknownArea)));
        check(missing.gateway.poll(scanUnknown).reason() == Reason.REQUEST_INVALID,
                "Source binding must be exact");
        ValidatedRequest unknownRequest = new ValidatedRequest(
                new CapabilityRequest(CropDelivery.ID, Map.of(
                        "actor", new ActorValue(missing.actor), "amount", new IntValue(1),
                        "source", new AreaValue(unknownArea), "destination", new ContainerValue(missing.chest))),
                missing.ownerRequest.context(), missing.ownerRequest.observation());
        ActionHandle permittedScan = missing.gateway.start(unknownRequest,
                new RunCorrelation(UUID.randomUUID(), new ArtifactRef(CropDelivery.ID, "a".repeat(64)), null),
                Operation.OBSERVE_SOURCE.requirement(),
                Map.of("actor", new ActorValue(missing.actor), "source", new AreaValue(unknownArea)),
                missing.budget);
        check(missing.gateway.poll(permittedScan).observation().status() == ObservationStatus.UNKNOWN,
                "Unloaded cell reported absent");
        check(missing.gateway.poll(permittedScan).result() == null,
                "Unknown cells cannot produce a proven zero count");
    }
    private static void typedBoundMovementUsesTheSameSourceAndDestinationBindings() {
        Fixture f = new Fixture();
        Cell selected = new Cell(f.actor.dimension(), 1, 64, 1);
        f.world.crops.put(selected, true);
        f.world.actorPos = new Cell(f.actor.dimension(), -3, 64, 0);
        Map<String, Value> sourceArgs = Map.of("actor", new ActorValue(f.actor),
                "source", new AreaValue(f.area));
        ActionReceipt moved = f.poll(f.start(Operation.MOVE_TO_SOURCE, sourceArgs));
        check(moved.reason() == null && moved.result().equals(new BoolValue(true))
                        && f.world.actorPos.equals(selected),
                "move_to_source did not select the harvest_next_wheat target");
        ActionReceipt harvested = f.poll(f.start(Operation.HARVEST_NEXT_WHEAT, sourceArgs));
        check(harvested.reason() == null && f.world.harvests == 1,
                "harvest_next_wheat disagreed with typed source movement");

        Fixture destination = new Fixture();
        destination.world.actorPos = new Cell(destination.actor.dimension(), -3, 64, 0);
        Map<String, Value> destinationArgs = Map.of("actor", new ActorValue(destination.actor),
                "destination", new ContainerValue(destination.chest));
        ActionReceipt deliveredMove = destination.poll(
                destination.start(Operation.MOVE_TO_DESTINATION, destinationArgs));
        check(deliveredMove.reason() == null
                        && destination.world.actorPos.equals(new Cell(destination.actor.dimension(),
                        destination.chest.x(), destination.chest.y(), destination.chest.z())),
                "move_to_destination did not use the bound container");

        Fixture changed = new Fixture();
        changed.world.crops.put(selected, true);
        changed.world.actorPos = new Cell(changed.actor.dimension(), -3,64,0);
        Map<String, Value> changedSource = Map.of("actor",new ActorValue(changed.actor),
                "source",new AreaValue(changed.area));
        check(changed.poll(changed.start(Operation.MOVE_TO_SOURCE,changedSource)).reason() == null,
                "Source movement fixture failed");
        changed.world.crops.put(changed.world.source,true);
        check(changed.poll(changed.start(Operation.HARVEST_NEXT_WHEAT,changedSource)).reason()
                        == Reason.STALE_OBSERVATION && changed.world.harvests == 0,
                "Changed deterministic crop selection caused a different physical harvest");
        check(changed.world.stopCalls > 0,"Typed navigation did not stop at terminal selection");

        Map<String, Value> wrongSource = Map.of("actor", new ActorValue(f.actor),
                "source", new AreaValue(new Cuboid(f.actor.dimension(), 8,64,8,9,64,9)));
        check(f.gateway.poll(f.start(Operation.MOVE_TO_SOURCE, wrongSource)).reason()
                        == Reason.REQUEST_INVALID,
                "typed movement accepted a source outside the canonical request");
    }

    private static void movementReachAndTimeout() {
        Fixture f = new Fixture();
        f.world.actorPos = new Cell(f.actor.dimension(), -3, 64, 0);
        f.world.crops.put(f.world.source, true);
        check(f.harvest(f.world.source).reason() == Reason.TARGET_UNAVAILABLE, "Remote harvest mutated");
        check(f.world.harvests == 0, "Remote harvest effect");
        ActionReceipt move = f.poll(f.start(Operation.MOVE,
                f.at(Operation.MOVE, f.world.source)));
        check(move.reason() == null && move.result().equals(new BoolValue(true))
                && f.world.moves == 3 && f.budget.snapshot().get(Kind.TRAVEL_BLOCKS) == 3,
                "Physical movement/work bound");
        f.world.unreachable = true;
        f.world.actorPos = new Cell(f.actor.dimension(), -3, 64, 0);
        check(f.poll(f.start(Operation.MOVE, f.at(Operation.MOVE, f.world.source))).reason()
                == Reason.TARGET_UNAVAILABLE, "Unreachable route");
    }
    private static void movementTerminalsAndFakeClocks() {
        Fixture denied = new Fixture();
        denied.world.actorPos = new Cell(denied.actor.dimension(), -10, 64, 0);
        ActionHandle handle = denied.start(Operation.MOVE, denied.at(Operation.MOVE, denied.world.source));
        ActionReceipt first = denied.gateway.poll(handle);
        int moved = denied.world.moves;
        long charged = denied.budget.snapshot().getOrDefault(Kind.TRAVEL_BLOCKS, 0L);
        ActionHandle competing = denied.start(Operation.MOVE,
                denied.at(Operation.MOVE, denied.world.source));
        check(denied.gateway.poll(competing).reason() == Reason.ACTION_FAILED
                        && denied.world.stopCalls == 0 && denied.world.moves == moved,
                "Second MOVE handle displaced the first route or minted a new candidate pool");
        check(!first.terminal() && denied.gateway.poll(handle).equals(first)
                && denied.world.moves == moved && charged == 1,
                "Repeated same-tick poll moved or charged travel twice");
        denied.world.tick++;
        denied.world.deny = true;
        ActionReceipt blocked = denied.gateway.poll(handle);
        int stopped = denied.world.stopCalls;
        check(blocked.reason() == Reason.AUTHORITY_DENIED && stopped > 0,
                "Effect-time movement denial left navigation running");
        denied.world.tick++;
        check(denied.gateway.poll(handle).equals(blocked) && denied.world.stopCalls == stopped
                && denied.world.moves == moved, "Terminal poll restarted owned navigation");

        Fixture invalid = new Fixture();
        invalid.world.actorPos = new Cell(invalid.actor.dimension(), -10, 64, 0);
        ActionHandle stale = invalid.start(Operation.MOVE, invalid.at(Operation.MOVE, invalid.world.source));
        invalid.gateway.poll(stale);
        invalid.world.tick++;
        invalid.world.targetInvalid = true;
        check(invalid.gateway.poll(stale).reason() == Reason.TARGET_INVALID
                && invalid.world.stopCalls > 0, "Invalid target retained a live movement route");

        Fixture tick = new Fixture();
        tick.world.actorPos = new Cell(tick.actor.dimension(), -10, 64, 0);
        ActionHandle tickHandle = tick.start(Operation.MOVE, tick.at(Operation.MOVE, tick.world.source));
        tick.gateway.poll(tickHandle);
        tick.world.tick += 101; // maxActionTicks=100; this is the action deadline, not the ledger
        check(tick.gateway.poll(tickHandle).reason() == Reason.ACTION_TIMEOUT
                && tick.world.stopCalls > 0, "Game-tick deadline did not stop movement");

        Fixture wall = new Fixture();
        wall.world.actorPos = new Cell(wall.actor.dimension(), -10, 64, 0);
        ActionHandle wallHandle = wall.start(Operation.MOVE, wall.at(Operation.MOVE, wall.world.source));
        wall.gateway.poll(wallHandle);
        wall.world.tick++;
        wall.clock.millis += 5_001; // 100 action ticks * 50 ms, independently of game time
        check(wall.gateway.poll(wallHandle).reason() == Reason.ACTION_TIMEOUT
                && wall.world.stopCalls > 0, "Wall deadline did not stop movement");

        Fixture ledger = new Fixture();
        ledger.world.actorPos = new Cell(ledger.actor.dimension(), -10, 64, 0);
        Budgets.Ledger shortLedger = new Budgets.Ledger(new Budgets.Limits(Map.of(
                Kind.ELAPSED_TICKS, 1L, Kind.INSTRUCTIONS, 100L,
                Kind.TRAVEL_BLOCKS, 16L), 100_000), ledger.clock);
        ActionHandle ledgerHandle = ledger.gateway.start(ledger.ownerRequest,
                new RunCorrelation(ledger.runId, new ArtifactRef(CropDelivery.ID, "a".repeat(64)), null),
                Operation.MOVE.requirement(), ledger.at(Operation.MOVE, ledger.world.source), shortLedger);
        ledger.gateway.poll(ledgerHandle);
        ledger.world.tick++;
        ledger.gateway.poll(ledgerHandle);
        ledger.world.tick++;
        check(ledger.gateway.poll(ledgerHandle).reason() == Reason.BUDGET_EXHAUSTED
                && ledger.world.stopCalls > 0
                && shortLedger.snapshot().get(Kind.ELAPSED_TICKS) == 1L,
                "Ledger tick cap did not terminate without refund");

        Fixture cancelled = new Fixture();
        cancelled.world.actorPos = new Cell(cancelled.actor.dimension(), -10, 64, 0);
        ActionHandle pending = cancelled.start(Operation.MOVE,
                cancelled.at(Operation.MOVE, cancelled.world.source));
        cancelled.gateway.poll(pending);
        int beforeCancel = cancelled.world.moves;
        ActionReceipt terminal = cancelled.gateway.cancel(pending);
        cancelled.world.tick += 5;
        check(terminal.reason() == Reason.CANCELLED && cancelled.gateway.poll(pending).equals(terminal)
                && cancelled.world.moves == beforeCancel && cancelled.world.stopCalls > 0
                && cancelled.world.leases.isEmpty(), "Cancellation restarted route or kept custody");

        Fixture lost = new Fixture();
        lost.world.actorPos = new Cell(lost.actor.dimension(), -10, 64, 0);
        ActionHandle inFlight = lost.start(Operation.MOVE, lost.at(Operation.MOVE, lost.world.source));
        lost.gateway.poll(inFlight);
        lost.world.actorAlive = false;
        lost.world.tick++;
        check(lost.gateway.poll(inFlight).reason() == Reason.ACTOR_UNAVAILABLE
                && lost.world.stopCalls > 0 && lost.world.leases.isEmpty(),
                "Actor loss did not clean up active movement");
    }
    private static void trackedDropDriftAndForeignStock() {
        Fixture f = new Fixture();
        f.world.crops.put(f.world.source,true);
        ActionReceipt harvest = f.harvest(f.world.source);
        UUID trackedId = f.world.drops.keySet().iterator().next();
        Cell drifted = new Cell(f.actor.dimension(), 2,64,0);
        f.world.drops.put(trackedId,new FakeWorld.DropSim(drifted,1));
        UUID foreignId = UUID.randomUUID();
        f.world.drops.put(foreignId,new FakeWorld.DropSim(drifted,1));
        f.world.actorPos = new Cell(f.actor.dimension(),1,64,0);
        check(f.pickup(f.world.source).reason()==Reason.STALE_OBSERVATION,
                "Stale crop coordinate should not select a moved drop");
        f.world.deniedCell = drifted;
        check(f.pickup(drifted).reason()==Reason.AUTHORITY_DENIED
                && f.world.drops.size()==2 && f.world.actorWheat==0,
                "Moved drop ignored current-cell permission");
        Map<String, Value> tracked = Map.of("actor", new ActorValue(f.actor));
        check(f.poll(f.start(Operation.PICKUP_TRACKED_WHEAT, tracked)).reason()==Reason.AUTHORITY_DENIED,
                "Tracked pickup bypassed current-cell permission");
        f.world.deniedCell = null;
        ActionReceipt collected = f.poll(f.start(Operation.PICKUP_TRACKED_WHEAT, tracked));
        check(collected.reason()==null && collected.cropReceipts().size()==1
                && f.world.drops.size()==1 && f.world.drops.containsKey(foreignId)
                && f.world.actorWheat==1, "Foreign wheat was credited or tracked drift was lost");
        check(collected.cropReceipts().get(0).batchId()
                .equals(harvest.cropReceipts().get(0).batchId()),
                "Drift changed the proven source batch");
    }
    private static void capacitiesAndCancellation() {
        Fixture f = new Fixture();
        f.world.crops.put(f.world.source, true);
        f.world.actorCapacity = 0;
        f.harvest(f.world.source);
        check(f.pickup(f.world.source).reason() == Reason.TARGET_UNAVAILABLE
                && f.world.drops.size() == 1, "Full actor inventory lost drop");
        f.world.actorCapacity = 64;
        f.pickup(f.world.source);
        f.world.actorPos = new Cell(f.actor.dimension(), 2, 64, 0);
        f.world.containerCapacity = 0;
        check(f.transfer(1).reason() == Reason.TARGET_UNAVAILABLE && f.world.actorWheat == 1,
                "Full container lost item");
        f.world.containerCapacity = 1;
        ActionHandle transfer = f.start(Operation.TRANSFER_WHEAT, f.transferArgs(2));
        ActionReceipt once = f.gateway.poll(transfer);
        check(!once.terminal() && once.committedEffects() == 1 && once.cropReceipts().size() == 1,
                "First partial transfer");
        ActionReceipt cancelled = f.gateway.cancel(transfer);
        check(cancelled.terminal() && cancelled.reason() == Reason.CANCELLED
                && cancelled.cropReceipts().equals(once.cropReceipts()), "Partial cancel receipt");
        f.world.tick++;
        check(f.gateway.poll(transfer).equals(cancelled) && f.gateway.cancel(transfer).equals(cancelled)
                && f.world.transfers == 1 && f.world.leases.isEmpty() && f.world.stopCalls > 0,
                "Late poll repeated effect or failed to release control");
    }
    private static void revocationRaceAndLifecycle() {
        Fixture f = new Fixture();
        ActionHandle denied = f.gateway.start(f.bound(f.stranger), new RunCorrelation(UUID.randomUUID(),
                        new ArtifactRef(CropDelivery.ID, "a".repeat(64)), null),
                Operation.HARVEST_WHEAT.requirement(), f.at(Operation.HARVEST_WHEAT, f.world.source), f.budget);
        check(f.gateway.poll(denied).reason() == Reason.AUTHORITY_DENIED, "Unshared control allowed");
        Fixture explicitlyShared = new Fixture();
        explicitlyShared.grant = true;
        explicitlyShared.world.crops.put(explicitlyShared.world.source, true);
        ValidatedRequest sharedRequest = explicitlyShared.bound(explicitlyShared.stranger);
        ActionHandle accepted = explicitlyShared.gateway.start(sharedRequest,
                new RunCorrelation(explicitlyShared.runId, new ArtifactRef(CropDelivery.ID,"a".repeat(64)), null),
                Operation.HARVEST_WHEAT.requirement(),
                explicitlyShared.at(Operation.HARVEST_WHEAT, explicitlyShared.world.source),
                explicitlyShared.budget);
        check(explicitlyShared.gateway.poll(accepted).reason()==null
                && explicitlyShared.world.harvests==1, "Explicit grant failed");
        Fixture revoked = new Fixture();
        revoked.grant = true;
        revoked.world.crops.put(revoked.world.source, true);
        ActionHandle afterGrant = revoked.gateway.start(revoked.bound(revoked.stranger),
                new RunCorrelation(revoked.runId, new ArtifactRef(CropDelivery.ID,"a".repeat(64)), null),
                Operation.HARVEST_WHEAT.requirement(),
                revoked.at(Operation.HARVEST_WHEAT, revoked.world.source), revoked.budget);
        revoked.grant = false;
        check(revoked.gateway.poll(afterGrant).reason() == Reason.AUTHORITY_DENIED
                && revoked.world.harvests == 0, "Revoked grant still affected world");
        Fixture granted = new Fixture();
        granted.world.crops.put(granted.world.source, true);
        ActionHandle handle = granted.start(Operation.HARVEST_WHEAT,
                granted.at(Operation.HARVEST_WHEAT, granted.world.source));
        granted.world.deny = true;
        check(granted.gateway.poll(handle).reason() == Reason.AUTHORITY_DENIED
                && granted.world.harvests == 0, "Effect-time denial ignored");
        Fixture rules = new Fixture();
        rules.world.crops.put(rules.world.source, true);
        ActionHandle pending = rules.start(Operation.HARVEST_WHEAT,
                rules.at(Operation.HARVEST_WHEAT, rules.world.source));
        rules.world.mobGriefing = false;
        check(rules.gateway.poll(pending).reason() == Reason.AUTHORITY_DENIED
                && rules.world.harvests == 0, "Game rule ignored");
        Fixture stale = new Fixture();
        stale.world.crops.put(stale.world.source, true);
        ActionHandle race = stale.start(Operation.HARVEST_WHEAT,
                stale.at(Operation.HARVEST_WHEAT, stale.world.source));
        stale.world.crops.remove(stale.world.source);
        check(stale.gateway.poll(race).reason() == Reason.TARGET_INVALID
                && stale.world.harvests == 0, "Stale target mutated");
        Fixture lost = new Fixture();
        ActionHandle work = lost.start(Operation.MOVE, lost.at(Operation.MOVE, lost.world.source));
        lost.world.actorLoaded = false;
        check(lost.gateway.poll(work).reason() == Reason.ACTOR_UNAVAILABLE
                && lost.world.leases.isEmpty(), "Unload did not interrupt/release");
    }
    private static void budgetsAndModelFree() {
        Fixture f = new Fixture();
        f.world.crops.put(f.world.source, true);
        Budgets.Ledger zero = new Budgets.Ledger(new Budgets.Limits(Map.of(
                Kind.ELAPSED_TICKS, 10L, Kind.INSTRUCTIONS, 10L,
                Kind.OBSERVATIONS, 10L, Kind.ATTEMPTED_EFFECTS, 1L,
                Kind.COMMITTED_EFFECTS, 0L), 100_000), f.clock);
        ActionHandle attempt = f.gateway.start(f.ownerRequest,
                new RunCorrelation(f.runId, new ArtifactRef(CropDelivery.ID, "a".repeat(64)), null),
                Operation.HARVEST_WHEAT.requirement(),
                f.at(Operation.HARVEST_WHEAT, f.world.source), zero);
        check(f.gateway.poll(attempt).reason() == Reason.BUDGET_EXHAUSTED
                && f.world.harvests == 0 && zero.snapshot().getOrDefault(Kind.ATTEMPTED_EFFECTS, 0L) == 0,
                "Zero effect limit mutated world");
        Fixture exactlyOne = new Fixture();
        exactlyOne.world.crops.put(exactlyOne.world.source, true);
        Cell second = new Cell(exactlyOne.actor.dimension(), 1,64,0);
        exactlyOne.world.crops.put(second,true);
        Budgets.Ledger one = new Budgets.Ledger(new Budgets.Limits(Map.of(
                Kind.ELAPSED_TICKS, 10L, Kind.INSTRUCTIONS, 10L,
                Kind.OBSERVATIONS, 10L, Kind.ATTEMPTED_EFFECTS, 1L,
                Kind.COMMITTED_EFFECTS, 1L), 100_000), exactlyOne.clock);
        RunCorrelation correlation = new RunCorrelation(exactlyOne.runId,
                new ArtifactRef(CropDelivery.ID, "a".repeat(64)), null);
        ActionHandle first = exactlyOne.gateway.start(exactlyOne.ownerRequest, correlation,
                Operation.HARVEST_WHEAT.requirement(),
                exactlyOne.at(Operation.HARVEST_WHEAT, exactlyOne.world.source), one);
        check(exactlyOne.gateway.poll(first).reason()==null && exactlyOne.world.harvests==1,
                "Exact effect limit rejected");
        ActionHandle excess = exactlyOne.gateway.start(exactlyOne.ownerRequest,correlation,
                Operation.HARVEST_WHEAT.requirement(),exactlyOne.at(Operation.HARVEST_WHEAT,second),one);
        check(exactlyOne.gateway.poll(excess).reason()==Reason.BUDGET_EXHAUSTED
                && exactlyOne.world.harvests==1 && Boolean.TRUE.equals(exactlyOne.world.crops.get(second)),
                "N+1 effect limit mutated crop");
        boolean invalidLimit = false;
        try { new Limits(1,1,1,0,1,1,1,1); }
        catch (IllegalArgumentException expected) { invalidLimit = true; }
        check(invalidLimit, "Zero scan bound accepted");
        Fixture wrongThread = new Fixture();
        wrongThread.world.gameThread = false;
        boolean rejected = false;
        try { wrongThread.gateway.activeRuns(); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "Off-thread gateway access");
    }
    private static void boundedHandleRetirement() {
        Fixture f = new Fixture();
        SurvivalGateway small = new SurvivalGateway(f.world,
                (a, effect, context) -> true,
                new Limits(2, 2, 8, 2, 4, 1, 10, 8), f.clock);
        Map<String, Value> args = Map.of("actor", new ActorValue(f.actor));
        RunCorrelation firstRun = new RunCorrelation(f.runId,
                new ArtifactRef(CropDelivery.ID, "a".repeat(64)), null);
        ActionHandle first = small.start(f.ownerRequest, firstRun,
                Operation.OBSERVE_INVENTORY.requirement(), args, f.budget);
        ActionReceipt firstResult = small.poll(first);
        ActionHandle second = small.start(f.ownerRequest, firstRun,
                Operation.OBSERVE_INVENTORY.requirement(), args, f.budget);
        check(small.poll(second).terminal() && small.trackedHandles() == 2,
                "Two-handle exact cap");
        check(small.poll(first).equals(firstResult), "Terminal repeat changed before retirement");
        small.releaseRun(f.runId);
        UUID nextRun = UUID.randomUUID();
        ActionHandle next = small.start(f.ownerRequest,
                new RunCorrelation(nextRun, firstRun.artifact(), null),
                Operation.OBSERVE_INVENTORY.requirement(), args, f.budget);
        check(small.poll(next).terminal() && small.trackedHandles() <= 2
                && small.activeRuns() == 1, "Released handle tombstones blocked new run");
        boolean retired = false;
        try { small.poll(first); } catch (IllegalArgumentException expected) { retired = true; }
        check(retired && f.world.harvests == 0 && f.world.transfers == 0,
                "Retired handle unexpectedly executed");
        small.releaseRun(nextRun);
    }
    public static void main(String[] args) {
        parameterizedSourceHarvest();
        separateActionsAndAttribution();
        boundsAndUnknown();
        typedBoundMovementUsesTheSameSourceAndDestinationBindings();
        movementReachAndTimeout();
        movementTerminalsAndFakeClocks();
        trackedDropDriftAndForeignStock();
        capacitiesAndCancellation();
        revocationRaceAndLifecycle();
        budgetsAndModelFree();
        boundedHandleRetirement();
        System.out.println("GatewayChecks: eleven matrix groups passed");
    }
    private static void parameterizedSourceHarvest() {
        Fixture first = new Fixture();
        Cell last = new Cell(first.actor.dimension(), 1, 64, 1);
        first.world.crops.put(last, true);
        Map<String, Value> bound = Map.of("actor", new ActorValue(first.actor),
                "source", new AreaValue(first.area));
        ActionReceipt harvested = first.poll(first.start(Operation.HARVEST_NEXT_WHEAT, bound));
        check(harvested.reason() == null && harvested.committedEffects() == 1
                && first.world.harvests == 1 && first.world.scans <= 5,
                "bounded area harvest must use actual mature crop and world effect");
        check(first.poll(first.start(Operation.HARVEST_NEXT_WHEAT, bound)).reason()
                == Reason.RESOURCE_MISSING, "exhausted inspected area is a resource blocker");
        Fixture changed = new Fixture();
        Cuboid other = new Cuboid(changed.actor.dimension(), 11, 64, 12, 11, 64, 12);
        Cell second = new Cell(other.dimension(), 11, 64, 12);
        changed.world.actorPos = second;
        changed.world.crops.put(second, true);
        ValidatedRequest request = new ValidatedRequest(new CapabilityRequest(CropDelivery.ID,
                Map.of("actor", new ActorValue(changed.actor), "amount", new IntValue(1),
                        "source", new AreaValue(other), "destination", new ContainerValue(changed.chest))),
                changed.ownerRequest.context(), changed.ownerRequest.observation());
        ActionHandle handle = changed.gateway.start(request,
                new RunCorrelation(changed.runId, new ArtifactRef(CropDelivery.ID, "a".repeat(64)), null),
                Operation.HARVEST_NEXT_WHEAT.requirement(), Map.of("actor", new ActorValue(changed.actor),
                        "source", new AreaValue(other)), changed.budget);
        check(changed.poll(handle).reason() == null && changed.world.harvests == 1,
                "same primitive must use changed source binding");
        Fixture unknown = new Fixture();
        Cuboid unloaded = new Cuboid(unknown.actor.dimension(), 99, 64, 0, 99, 64, 0);
        ValidatedRequest unknownRequest = new ValidatedRequest(new CapabilityRequest(CropDelivery.ID,
                Map.of("actor", new ActorValue(unknown.actor), "amount", new IntValue(1),
                        "source", new AreaValue(unloaded), "destination", new ContainerValue(unknown.chest))),
                unknown.ownerRequest.context(), unknown.ownerRequest.observation());
        ActionHandle pending = unknown.gateway.start(unknownRequest,
                new RunCorrelation(unknown.runId, new ArtifactRef(CropDelivery.ID, "a".repeat(64)), null),
                Operation.HARVEST_NEXT_WHEAT.requirement(), Map.of("actor", new ActorValue(unknown.actor),
                        "source", new AreaValue(unloaded)), unknown.budget);
        check(unknown.poll(pending).reason() == Reason.STALE_OBSERVATION
                && unknown.world.harvests == 0, "unknown area is not absence or an effect");
    }
}
