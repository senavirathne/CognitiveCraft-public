package dev.aivillages.fabric;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.aivillages.fabric.mixin.PathNavigationAccessor;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.Outcomes.Reason;
import dev.aivillages.core.kernel.SurvivalGateway;
import dev.aivillages.core.kernel.SurvivalGateway.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Minecraft 26.3 server-thread adapter. It never requests an unloaded chunk. */
public final class FabricGatewayWorld implements SurvivalGateway.WorldAccess {
    private final ServerLevel level;
    private final Map<UUID, BlockPos> previousPosition = new HashMap<>();
    private final Map<UUID, Route> routes = new HashMap<>();
    private static final int MAX_PATH_NODES = 128;
    private static final double SOURCE_APPROACH_DISTANCE_SQUARED = 0.64;
    private static final class Route {
        final BlockPos target;
        final UUID runId;
        final NavigationRoute policy;
        final boolean sourceApproach;
        Route(BlockPos target, UUID runId, boolean sourceApproach) {
            this.target = target;
            this.runId = runId;
            this.sourceApproach = sourceApproach;
            List<NavigationRoute.Approach> samples = new ArrayList<>(8);
            // A source goal is inside the selected crop cell. An adjacent end cell
            // cannot satisfy the closer arrival and would waste a pathfinding call.
            if (sourceApproach) samples.add(new NavigationRoute.Approach(
                    target.getX(), target.getY(), target.getZ()));
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (!sourceApproach && (dx != 0 || dz != 0)) samples.add(new NavigationRoute.Approach(
                        target.getX() + dx, target.getY(), target.getZ() + dz));
            }
            this.policy = new NavigationRoute(samples);
        }
    }
    record NavigationSummary(UUID actor, int attempts, int calls, String reason) { }
    private NavigationSummary lastNavigationSummary;
    NavigationSummary lastNavigationSummary() { return lastNavigationSummary; }
    public FabricGatewayWorld(ServerLevel level) { this.level = java.util.Objects.requireNonNull(level); }
    @Override public boolean onGameThread() { return level.getServer().isSameThread(); }
    @Override public long tick() { return level.getGameTime(); }
    private String dimension() { return level.dimension().identifier().toString(); }
    private Villager villager(ActorRef actor) {
        if (!actor.dimension().equals(dimension())) return null;
        Entity entity = level.getEntity(actor.entityId());
        return entity instanceof Villager v && !v.isRemoved() && v.isAlive() ? v : null;
    }
    private BlockPos position(Cell cell) { return new BlockPos(cell.x(), cell.y(), cell.z()); }
    private boolean loaded(BlockPos pos) {
        return level.hasChunkAt(pos) && level.isPositionEntityTicking(pos)
                && level.getWorldBorder().isWithinBounds(pos);
    }
    boolean loadedForDiscovery(BlockPos pos) { return loaded(pos); }
    boolean discoverable(ActorRef actor, BlockPos pos, Effect effect) {
        Villager v = villager(actor);
        if (v == null || !loaded(v.blockPosition()) || !loaded(pos) || !level.mayInteract(v, pos))
            return false;
        return effect == Effect.OBSERVE || level.getGameRules().get(GameRules.MOB_GRIEFING);
    }
    record ContainerInspection(ObservationStatus status, int wheatCapacity, int slotsInspected,
                               String identity) {
        ContainerInspection {
            if (wheatCapacity < 0 || slotsInspected < 0 || slotsInspected > 128)
                throw new IllegalArgumentException("Container inspection bounds");
        }
    }
    ContainerInspection inspectContainer(ContainerRef destination) {
        BlockPos p = new BlockPos(destination.x(), destination.y(), destination.z());
        if (!destination.dimension().equals(dimension()) || !loaded(p))
            return new ContainerInspection(ObservationStatus.UNKNOWN, 0, 0, "container:" + p.asLong());
        Container container = box(p);
        if (container == null)
            return new ContainerInspection(ObservationStatus.ABSENT, 0, 0, "container:" + p.asLong());
        ItemStack wheat = new ItemStack(Items.WHEAT);
        var measured = WheatContainerCapacity.inspect(container.getContainerSize(), i -> {
            if (!container.canPlaceItem(i,wheat))
                return new WheatContainerCapacity.Slot(false,false,false,0,0);
            ItemStack current = container.getItem(i);
            int limit = current.isEmpty() ? Math.min(wheat.getMaxStackSize(),container.getMaxStackSize(wheat))
                    : Math.min(wheat.getMaxStackSize(),Math.min(current.getMaxStackSize(),container.getMaxStackSize(current)));
            return new WheatContainerCapacity.Slot(true,current.isEmpty(),
                    ItemStack.isSameItemSameComponents(current,wheat),current.getCount(),limit);
        });
        return new ContainerInspection(measured.known() ? ObservationStatus.PRESENT : ObservationStatus.UNKNOWN,
                measured.capacity(),measured.slotsInspected(),container.getClass().getSimpleName() + ":" + p.asLong());
    }
    @Override public ActorState actor(ActorRef actor) {
        Villager v = villager(actor);
        if (v == null) return new ActorState(false, false, actor.dimension(), 0, 0, 0, 0);
        BlockPos p = v.blockPosition();
        return new ActorState(loaded(p), v.isAlive(), dimension(), p.getX(), p.getY(), p.getZ(),
                v.getInventory().countItem(Items.WHEAT));
    }
    @Override public boolean acquire(ActorRef actor, UUID runId) {
        Villager v = villager(actor);
        if (v == null || !loaded(v.blockPosition()) || !GatewayControl.acquire(v, runId)) return false;
        v.getBrain().stopAll(level, v);
        v.getNavigation().stop();
        ((PathNavigationAccessor)v.getNavigation()).cognitivecraft$setHasDelayedRecomputation(false);
        v.getMoveControl().setWantedPosition(v.getX(), v.getY(), v.getZ(), 0.0);
        previousPosition.put(v.getUUID(), v.blockPosition());
        return true;
    }
    @Override public boolean holds(ActorRef actor, UUID runId) {
        Villager v = villager(actor);
        return v != null && GatewayControl.holds(v, runId);
    }
    @Override public void release(ActorRef actor, UUID runId) {
        Villager v = villager(actor);
        if (v != null) {
            if (GatewayControl.holds(v, runId)) terminal(v, routes.get(v.getUUID()),
                    "run-release", 0, 0);
            GatewayControl.release(v, runId);
        } else GatewayControl.releaseByEntity(actor.entityId(), runId);
        previousPosition.remove(actor.entityId());
        routes.remove(actor.entityId());
    }
    @Override public void stopMovement(ActorRef actor) {
        Villager v = villager(actor);
        if (v != null) terminal(v, routes.get(actor.entityId()), "gateway-stop", 0, 0);
        routes.remove(actor.entityId());
    }
    private void stopOwnedNavigation(Villager v, Route route) {
        if (route == null || !GatewayControl.holds(v, route.runId)) return;
        v.getNavigation().stop();
        // In 26.3 stop() removes the current path but does not cancel a delayed
        // recompute. Clear this flag only while the gateway holds this villager.
        ((PathNavigationAccessor)v.getNavigation()).cognitivecraft$setHasDelayedRecomputation(false);
        v.getMoveControl().setWantedPosition(v.getX(), v.getY(), v.getZ(), 0.0);
    }
    @Override public Reason permit(ValidatedRequest request, Effect effect, Cell target) {
        ActorRef actor = ((ActorValue)request.request().arguments().get("actor")).value();
        Villager v = villager(actor);
        BlockPos pos = position(target);
        if (v == null || !loaded(v.blockPosition())) return Reason.ACTOR_UNAVAILABLE;
        if (!target.dimension().equals(dimension())) return Reason.TARGET_INVALID;
        if (!loaded(pos)) return effect == Effect.OBSERVE ? null : Reason.TARGET_UNAVAILABLE;
        if (!level.mayInteract(v, pos)) return Reason.AUTHORITY_DENIED;
        if ((effect == Effect.HARVEST || effect == Effect.PICKUP || effect == Effect.TRANSFER)
                && !level.getGameRules().get(GameRules.MOB_GRIEFING)) return Reason.AUTHORITY_DENIED;
        return null;
    }
    @Override public CropState crop(Cell cell) {
        BlockPos p = position(cell);
        if (!cell.dimension().equals(dimension()) || !loaded(p))
            return new CropState(ObservationStatus.UNKNOWN, false, cell.identity());
        var state = level.getBlockState(p);
        if (!state.is(Blocks.WHEAT))
            return new CropState(ObservationStatus.ABSENT, false, cell.identity());
        return new CropState(ObservationStatus.PRESENT,
                ((CropBlock)Blocks.WHEAT).isMaxAge(state), cell.identity());
    }
    @Override public StockState inventory(ActorRef actor) {
        Villager v = villager(actor);
        if (v == null || !loaded(v.blockPosition()))
            return new StockState(ObservationStatus.UNKNOWN, "actor:" + actor.entityId(), 0, 0);
        return new StockState(ObservationStatus.PRESENT, "actor:" + actor.entityId(),
                v.getInventory().countItem(Items.WHEAT), v.getInventory().countItem(Items.WHEAT_SEEDS));
    }
    @Override public StockState container(ContainerRef destination) {
        BlockPos p = new BlockPos(destination.x(), destination.y(), destination.z());
        if (!destination.dimension().equals(dimension()) || !loaded(p))
            return new StockState(ObservationStatus.UNKNOWN, "container:" + p.asLong(), 0, 0);
        Container box = box(p);
        if (box == null)
            return new StockState(ObservationStatus.ABSENT, "container:" + p.asLong(), 0, 0);
        return new StockState(ObservationStatus.PRESENT, "container:" + p.asLong(),
                box.countItem(Items.WHEAT), box.countItem(Items.WHEAT_SEEDS));
    }
    private Container box(BlockPos p) {
        var e = level.getBlockEntity(p);
        if (!(e instanceof ChestBlockEntity || e instanceof BarrelBlockEntity)) return null;
        if (e instanceof BaseContainerBlockEntity base && base.isLocked()) return null;
        if (e instanceof RandomizableContainerBlockEntity random && random.getLootTable() != null) return null;
        return (Container)e;
    }
    private boolean canReach(Villager v, BlockPos p) {
        return canReach(v, Vec3.atCenterOf(p), p);
    }
    private boolean canReach(Villager v, Vec3 target, BlockPos targetBlock) {
        if (v.distanceToSqr(target) > 6.25) return false;
        var hit = level.clip(new ClipContext(v.getEyePosition(), target,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, v));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(targetBlock);
    }
    @Override public Movement move(ActorRef actor, Cell target, int maxNavigationCalls) {
        return navigate(actor,target,maxNavigationCalls,false);
    }
    @Override public Movement moveToSource(ActorRef actor, Cell target, int maxNavigationCalls) {
        return navigate(actor,target,maxNavigationCalls,true);
    }
    private Movement navigate(ActorRef actor, Cell target, int maxNavigationCalls, boolean sourceApproach) {
        Villager v = villager(actor);
        if (v == null) {
            routes.remove(actor.entityId());
            return new Movement(false, true, 0, 0);
        }
        BlockPos targetPos = position(target), now = v.blockPosition();
        BlockPos previous = previousPosition.put(v.getUUID(), now);
        long travel = previous == null ? 0 : Math.max(Math.abs((long)now.getX() - previous.getX()),
                Math.max(Math.abs((long)now.getY() - previous.getY()),
                        Math.abs((long)now.getZ() - previous.getZ())));
        if (travel > 1) return terminal(v, routes.get(v.getUUID()), "invalid-travel", 0, travel);
        v.getLookControl().setLookAt(target.x() + 0.5, target.y() + 0.5, target.z() + 0.5);
        if (canReach(v, targetPos) && (!sourceApproach
                || v.distanceToSqr(Vec3.atCenterOf(targetPos)) <= SOURCE_APPROACH_DISTANCE_SQUARED)) {
            return terminal(v, routes.get(v.getUUID()), "physical-reach-and-los", 0, travel);
        }
        if (!loaded(targetPos) || !level.mayInteract(v, targetPos))
            return terminal(v, routes.get(v.getUUID()), "target-unavailable", 0, travel);
        Route route = routes.get(v.getUUID());
        if (route != null && (!route.target.equals(targetPos) || route.sourceApproach != sourceApproach)) {
            stopOwnedNavigation(v, route);
            routes.remove(v.getUUID());
            route = null;
        }
        if (route == null) {
            UUID runId = GatewayControl.owner(v);
            if (runId == null) return new Movement(false, true, 0, travel);
            route = new Route(targetPos, runId, sourceApproach);
            routes.put(v.getUUID(),route);
        }
        if (route.policy.stage() == NavigationRoute.Stage.FOLLOW_PATH) {
            Path active = v.getNavigation().getPath();
            NavigationRoute.PathSample sample = active == null ? null : sample(active, v);
            if (route.policy.follow(sample, tick()) == NavigationRoute.Follow.CONTINUE)
                return new Movement(false, false, 0, travel);
            stopOwnedNavigation(v, route);
            // A replaced path, exhausted cursor or stalled waypoint consumes the
            // approach. The next poll may submit at most one new path.
            return route.policy.hasRemaining() ? new Movement(false, false, 0, travel)
                    : terminal(v, route, "approaches-exhausted", 0, travel);
        }
        if (maxNavigationCalls < 1) return terminal(v, route, "navigation-work-exhausted", 0, travel);
        NavigationRoute.Approach approach;
        while ((approach = route.policy.select(new NavigationRoute.Position(
                v.getX(), v.getY(), v.getZ()))) != null) {
            BlockPos candidate = new BlockPos(approach.x(), approach.y(), approach.z());
            if (!walkable(v, candidate)) {
                route.policy.abandon("invalid-approach");
                continue;
            }
            // Planner tolerance zero is not a promise that the villager occupies
            // this cell or can interact. Inspect the resulting live path and reach.
            route.policy.chargedPathfinding();
            Path proposed = v.getNavigation().createPath(candidate, 0);
            boolean plannerReached = proposed != null && proposed.canReach();
            boolean validGeometry = plannerReached && permittedPath(v, proposed);
            boolean accepted = validGeometry && v.getNavigation().moveTo(proposed, 0.65);
            route.policy.note("createPath=" + (proposed != null) + " canReach=" + plannerReached
                    + " geometry=" + validGeometry + " moveTo=" + accepted
                    + " plannerTarget=" + (proposed == null ? "none" : proposed.getTarget())
                    + " end=" + (proposed == null ? "none" : proposed.getEndNode()));
            if (proposed == null) route.policy.abandon("null-path");
            else if (!plannerReached) route.policy.abandon("partial-path");
            else if (!validGeometry) route.policy.abandon("invalid-path-geometry");
            else if (!accepted) route.policy.abandon("moveTo-rejected");
            else {
                Path active = v.getNavigation().getPath();
                NavigationRoute.PathSample initial = active == null ? null : sample(active, v);
                if (initial == null) route.policy.abandon("no-active-path");
                else {
                    route.policy.accepted(initial, tick());
                    if (initial.node() >= initial.count()) {
                        route.policy.abandon("finished-outside-reach");
                        stopOwnedNavigation(v, route);
                    }
                }
            }
            if (route.policy.stage() == NavigationRoute.Stage.SELECT_APPROACH)
                stopOwnedNavigation(v, route);
            return route.policy.hasRemaining() || route.policy.stage() == NavigationRoute.Stage.FOLLOW_PATH
                    ? new Movement(false, false, 1, travel)
                    : terminal(v, route, "approaches-exhausted", 1, travel);
        }
        return terminal(v, route, "approaches-exhausted", 0, travel);
    }
    private boolean permittedPath(Villager v, Path path) {
        if (path.getNodeCount() < 1 || path.getNodeCount() > MAX_PATH_NODES
                || path.getEndNode() == null || path.getTarget() == null) return false;
        var end = path.getEndNode();
        BlockPos endPos = new BlockPos(end.x, end.y, end.z);
        return loaded(endPos) && loaded(endPos.above()) && level.mayInteract(v, endPos)
                && loaded(path.getTarget()) && level.mayInteract(v, path.getTarget());
    }
    private NavigationRoute.PathSample sample(Path path, Villager v) {
        if (!permittedPath(v, path)) return null;
        long geometry = 0xcbf29ce484222325L;
        geometry = (geometry ^ path.getTarget().asLong()) * 0x100000001b3L;
        for (int i = 0; i < path.getNodeCount(); i++) {
            var node = path.getNode(i);
            geometry = (geometry ^ new BlockPos(node.x, node.y, node.z).asLong()) * 0x100000001b3L;
        }
        int index = path.getNextNodeIndex();
        if (index < 0 || index > path.getNodeCount()) return null;
        Vec3 waypoint = index == path.getNodeCount() ? v.position()
                : path.getNextEntityPos(v);
        return new NavigationRoute.PathSample(path, geometry, index, path.getNodeCount(),
                new NavigationRoute.Position(waypoint.x, waypoint.y, waypoint.z),
                new NavigationRoute.Position(v.getX(), v.getY(), v.getZ()));
    }
    private Movement terminal(Villager v, Route route, String reason, int work, long travel) {
        if (route != null) {
            route.policy.terminal(reason);
            stopOwnedNavigation(v, route);
            lastNavigationSummary = new NavigationSummary(v.getUUID(), route.policy.attempts(),
                    route.policy.navigationCalls(), reason);
            if (Boolean.getBoolean("cognitivecraft.gametest.trace")
                    || Boolean.getBoolean("cognitivecraft.navigation.trace")) {
                var sample = route.policy.lastSample();
                System.out.println("CognitiveCraft MOVE route actor=" + v.getUUID()
                        + " position=" + v.position() + " target=" + route.target
                        + " targetDistance=" + v.distanceToSqr(Vec3.atCenterOf(route.target))
                        + " lineOfSight=" + canReach(v, route.target)
                        + " approach=" + route.policy.approach() + " generation="
                        + route.policy.generation() + " node=" + (sample == null ? "none"
                        : sample.node() + "/" + sample.count()) + " waypoint="
                        + (sample == null ? "none" : sample.waypoint()) + " waypointDistance="
                        + (sample == null ? "none" : sample.distance()) + " lastCredited="
                        + route.policy.bestDistance() + " lastProgressTick="
                        + route.policy.lastProgress() + " attemptStartTick="
                        + route.policy.attemptStart() + " attempts=" + route.policy.attempts()
                        + " navigationCalls=" + route.policy.navigationCalls()
                        + " reason=" + reason + " history=" + route.policy.history());
            }
            routes.remove(v.getUUID());
        }
        return new Movement(reason.equals("physical-reach-and-los"),
                !reason.equals("physical-reach-and-los"), work, travel);
    }
    private boolean walkable(Villager v, BlockPos foot) {
        if (!loaded(foot) || !loaded(foot.above()) || !loaded(foot.below())
                || !level.mayInteract(v, foot)) return false;
        return level.getBlockState(foot).getCollisionShape(level,foot).isEmpty()
                && level.getBlockState(foot.above()).getCollisionShape(level,foot.above()).isEmpty()
                && !level.getBlockState(foot.below()).getCollisionShape(level,foot.below()).isEmpty();
    }
    @Override public Harvest harvest(ActorRef actor, Cell target) {
        Villager v = villager(actor);
        BlockPos p = position(target);
        if (v == null || !loaded(p) || !level.mayInteract(v, p)
                || !level.getGameRules().get(GameRules.MOB_GRIEFING))
            return new Harvest(false, List.of(), Reason.AUTHORITY_DENIED);
        if (!canReach(v, p)) return new Harvest(false, List.of(), Reason.TARGET_UNAVAILABLE);
        var state = level.getBlockState(p);
        if (!state.is(Blocks.WHEAT) || !((CropBlock)Blocks.WHEAT).isMaxAge(state))
            return new Harvest(false, List.of(), Reason.TARGET_INVALID);
        List<ItemStack> drops = Block.getDrops(state, level, p, null, v, ItemStack.EMPTY);
        if (!level.setBlock(p, Blocks.AIR.defaultBlockState(), 3))
            return new Harvest(false, List.of(), Reason.ACTION_FAILED);
        List<Drop> wheat = new ArrayList<>();
        boolean failedSpawn = false;
        for (ItemStack stack : drops) {
            if (stack.isEmpty()) continue;
            ItemEntity entity = new ItemEntity(level, p.getX()+0.5, p.getY()+0.25, p.getZ()+0.5, stack.copy());
            entity.setDefaultPickUpDelay();
            if (!level.addFreshEntity(entity)) { failedSpawn = true; continue; }
            if (stack.is(Items.WHEAT)) wheat.add(new Drop(entity.getUUID(), stack.getCount()));
        }
        return new Harvest(true, wheat, failedSpawn ? Reason.ACTION_FAILED : null);
    }
    @Override public DropState dropped(UUID entityId) {
        Entity e = level.getEntity(entityId);
        if (!(e instanceof ItemEntity item) || item.isRemoved())
            return new DropState(ObservationStatus.ABSENT,
                    new Cell(dimension(), 0, 0, 0), 0);
        BlockPos p = item.blockPosition();
        if (!loaded(p)) return new DropState(ObservationStatus.UNKNOWN,
                new Cell(dimension(), p.getX(), p.getY(), p.getZ()), 0);
        return new DropState(ObservationStatus.PRESENT,
                new Cell(dimension(), p.getX(), p.getY(), p.getZ()),
                item.getItem().is(Items.WHEAT) ? item.getItem().getCount() : 0);
    }
    @Override public Pickup pickup(ActorRef actor, UUID dropId, int expectedRemaining) {
        Villager v = villager(actor);
        Entity e = level.getEntity(dropId);
        if (v == null || !(e instanceof ItemEntity item) || !item.isAlive()
                || !loaded(item.blockPosition())) return new Pickup(0, 0, Reason.STALE_OBSERVATION);
        BlockPos p = item.blockPosition();
        if (!level.mayInteract(v, p) || !level.getGameRules().get(GameRules.MOB_GRIEFING))
            return new Pickup(0, expectedRemaining, Reason.AUTHORITY_DENIED);
        ItemStack stack = item.getItem();
        if (!stack.is(Items.WHEAT) || stack.getCount() != expectedRemaining)
            return new Pickup(0, expectedRemaining, Reason.STALE_OBSERVATION);
        if (item.hasPickUpDelay() || !canReach(v, item.position(), p)) {
            if (Boolean.getBoolean("cognitivecraft.gametest.trace"))
                System.out.println("IMP-007 pickup trace delay=" + item.hasPickUpDelay()
                        + " actor=" + v.position() + " drop=" + item.position()
                        + " target=" + p + " reach=" + canReach(v, item.position(), p)
                        + " tick=" + level.getGameTime());
            return new Pickup(0, expectedRemaining, Reason.TARGET_UNAVAILABLE);
        }
        ItemStack remainder = v.getInventory().addItem(stack.copy());
        int moved = stack.getCount() - remainder.getCount();
        if (moved <= 0) return new Pickup(0, expectedRemaining, Reason.TARGET_UNAVAILABLE);
        if (remainder.isEmpty()) item.discard();
        else item.setItem(remainder);
        v.getInventory().setChanged();
        return new Pickup(moved, remainder.getCount(), null);
    }
    @Override public Transfer transferOne(ActorRef actor, ContainerRef destination) {
        Villager v = villager(actor);
        BlockPos p = new BlockPos(destination.x(), destination.y(), destination.z());
        if (v == null || !destination.dimension().equals(dimension()) || !loaded(p))
            return new Transfer(0, Reason.TARGET_UNAVAILABLE);
        if (!level.mayInteract(v, p) || !level.getGameRules().get(GameRules.MOB_GRIEFING))
            return new Transfer(0, Reason.AUTHORITY_DENIED);
        if (!canReach(v, p)) return new Transfer(0, Reason.TARGET_UNAVAILABLE);
        Container container = box(p);
        if (container == null) return new Transfer(0, Reason.TARGET_INVALID);
        var inventory = v.getInventory();
        int actorSlot = -1;
        for (int i = 0; i < inventory.getContainerSize(); i++)
            if (inventory.getItem(i).is(Items.WHEAT) && !inventory.getItem(i).isEmpty()) {
                actorSlot = i; break;
            }
        if (actorSlot < 0) return new Transfer(0, Reason.RESOURCE_MISSING);
        ItemStack one = new ItemStack(Items.WHEAT, 1);
        int targetSlot = -1;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack current = container.getItem(i);
            if (!container.canPlaceItem(i, one)) continue;
            if (current.isEmpty() || ItemStack.isSameItemSameComponents(current, one)
                    && current.getCount() < Math.min(current.getMaxStackSize(),
                    container.getMaxStackSize(current))) {
                targetSlot = i; break;
            }
        }
        if (targetSlot < 0) return new Transfer(0, Reason.TARGET_UNAVAILABLE);
        inventory.removeItem(actorSlot, 1);
        ItemStack current = container.getItem(targetSlot);
        if (current.isEmpty()) container.setItem(targetSlot, one);
        else { current.grow(1); container.setChanged(); }
        inventory.setChanged();
        container.setChanged();
        return new Transfer(1, null);
    }
}
