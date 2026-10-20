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
import dev.aivillages.core.kernel.ResourceDelivery;
import dev.aivillages.core.kernel.LogAcquisition;
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
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
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
    private final Map<UUID, MovementTravel> movementTravel = new HashMap<>();
    private final Map<UUID, Route> routes = new HashMap<>();
    private final Map<UUID,Long> recipeSettled=new HashMap<>();
    private final FabricRecipeTransactions recipeTransactions;
    private final FabricTablePlacement tablePlacement;
    private static final int MAX_PATH_NODES = 128;
    private static final double SOURCE_APPROACH_DISTANCE_SQUARED = 0.64;
    private static final double OAK_APPROACH_DISTANCE_SQUARED = 1.15 * 1.15;
    private static final double OAK_SETTLED_MOTION_SQUARED = 0.01 * 0.01;
    // Preparation recognizes the same actual-item range as native pickup; it
    // must not demand a standing cell under a low ceiling for reachable loot.
    private static final double OAK_DROP_APPROACH_DISTANCE_SQUARED = 2.5 * 2.5;
    private static final class Route {
        final BlockPos target;
        final UUID runId;
        final NavigationRoute policy;
        final boolean sourceApproach;
        final boolean solidApproach;
        final boolean dropApproach;
        BlockPos approachTarget;
        BlockPos settledCell;
        long settledSince = -1;
        boolean endpointRefined;
        Route(BlockPos target, UUID runId, boolean sourceApproach, boolean solidApproach, boolean dropApproach) {
            this.target = target;
            this.runId = runId;
            this.sourceApproach = sourceApproach;
            this.solidApproach = solidApproach;
            this.dropApproach = dropApproach;
            List<NavigationRoute.Approach> samples = new ArrayList<>(8);
            // Wheat source goals remain inside the selected crop cell. Owned oak
            // loot also permits four adjacent standing cells when its own cell
            // is too low or its actual item ray is occluded.
            if (sourceApproach) samples.add(new NavigationRoute.Approach(
                    target.getX(), target.getY(), target.getZ()));
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dropApproach ? Math.abs(dx) + Math.abs(dz) == 1
                        : !sourceApproach && (solidApproach ? Math.abs(dx) + Math.abs(dz) == 1 : dx != 0 || dz != 0)) samples.add(new NavigationRoute.Approach(
                        target.getX() + dx, target.getY(), target.getZ() + dz));
            }
            this.policy = new NavigationRoute(samples);
        }
    }
    record NavigationSummary(UUID actor, int attempts, int calls, String reason, boolean endpointRefined) { }
    private NavigationSummary lastNavigationSummary;
    NavigationSummary lastNavigationSummary() { return lastNavigationSummary; }
    public FabricGatewayWorld(ServerLevel level) {
        this.level = java.util.Objects.requireNonNull(level);recipeTransactions=new FabricRecipeTransactions(level,this);tablePlacement=new FabricTablePlacement(level,this);
    }
    @Override public dev.aivillages.core.kernel.PlacementTransaction.Preflight placementPreflight(ValidatedRequest request){return tablePlacement.preflight(request);}
    @Override public dev.aivillages.core.kernel.PlacementTransaction.Mutation withdrawTableItem(ActorRef actor,dev.aivillages.core.kernel.PlacementTransaction.Preflight captured,String custody,String source){return tablePlacement.withdraw(actor,captured,custody,source);}
    @Override public dev.aivillages.core.kernel.PlacementTransaction.Mutation placeTable(ActorRef actor,dev.aivillages.core.kernel.PlacementTransaction.Preflight captured,String custody,String source){return tablePlacement.place(actor,captured,custody,source);}
    Villager recipeActor(ActorRef actor) {
        Villager v=villager(actor);return v!=null && loaded(v.blockPosition()) ? v : null;
    }
    Container recipeContainer(ContainerRef ref) {
        return ref.dimension().equals(dimension()) && loaded(new BlockPos(ref.x(),ref.y(),ref.z()))
                ? oakBox(new BlockPos(ref.x(),ref.y(),ref.z())) : null;
    }
    boolean recipeReach(Villager v,BlockPos p) {return canReach(v,p) && level.mayInteract(v,p);}
    String recipeContainerFingerprint(ContainerRef ref) {
        Container c=recipeContainer(ref);return c==null ? "" : FabricRecipeTransactions.fingerprint(c);
    }
    @Override public dev.aivillages.core.kernel.RecipeTransaction.Preflight recipePreflight(ValidatedRequest request) {
        return recipeTransactions.preflight(request);
    }
    dev.aivillages.core.kernel.RecipeTransaction.Preflight futureFacilityRecipe(ValidatedRequest request){return recipeTransactions.futureFacilityPreflight(request);}
    @Override public dev.aivillages.core.kernel.RecipeTransaction.Mutation withdrawRecipeInput(ActorRef actor,ContainerRef source,
            dev.aivillages.core.kernel.RecipeTransaction.Preflight captured,String custody,String sourceFingerprint) {
        return recipeTransactions.withdraw(actor,source,captured,custody,sourceFingerprint);
    }
    @Override public dev.aivillages.core.kernel.RecipeTransaction.Mutation craftRecipe(ActorRef actor,
            dev.aivillages.core.kernel.RecipeTransaction.Preflight captured,String custody) {
        return recipeTransactions.craft(actor,captured,custody);
    }
    @Override public dev.aivillages.core.kernel.RecipeTransaction.Mutation depositRecipeOutput(ActorRef actor,ContainerRef destination,
            dev.aivillages.core.kernel.RecipeTransaction.Preflight captured,String custody,int amount) {
        return recipeTransactions.deposit(actor,destination,captured,custody,amount);
    }
    @Override public Movement moveRecipe(ActorRef actor,Cell target,int maximum) {
        Villager v=recipeActor(actor);
        if(v==null)return new Movement(false,true,0,0);
        if(!v.onGround()) {
            recipeSettled.remove(v.getUUID());long travel=sampleTravel(v);
            return travel>1 ? terminal(v,routes.get(v.getUUID()),"invalid-travel",0,travel) : new Movement(false,false,0,travel);
        }
        var result=move(actor,target,maximum);
        if(!result.reached()) {recipeSettled.remove(v.getUUID());return result;}
        var motion=v.getDeltaMovement();
        if(motion.x*motion.x+motion.z*motion.z>0.0001 || motion.y>0.0001) {
            recipeSettled.remove(v.getUUID());return new Movement(false,false,result.chargedWork(),result.travelBlocks());
        }
        long since=recipeSettled.computeIfAbsent(v.getUUID(),ignored->tick());
        return tick()-since>=2 ? result : new Movement(false,false,result.chargedWork(),result.travelBlocks());
    }
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
        observeTravel(v);
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
        BlockPos p=v.blockPosition();
        movementTravel.put(v.getUUID(),new MovementTravel(tick(),p.getX(),p.getY(),p.getZ()));
        return true;
    }
    @Override public boolean holds(ActorRef actor, UUID runId) {
        Villager v = villager(actor);
        return v != null && GatewayControl.holds(v, runId);
    }
    @Override public void release(ActorRef actor, UUID runId) {
        if(holds(actor,runId))recipeTransactions.release(actor);
        Villager v = villager(actor);
        if (v != null) {
            if (GatewayControl.holds(v, runId)) terminal(v, routes.get(v.getUUID()),
                    "run-release", 0, 0);
            GatewayControl.release(v, runId);
        } else GatewayControl.releaseByEntity(actor.entityId(), runId);
        movementTravel.remove(actor.entityId());
        routes.remove(actor.entityId());
        recipeSettled.remove(actor.entityId());
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
        if ((effect == Effect.HARVEST || effect == Effect.PICKUP || effect == Effect.TRANSFER
                || effect==Effect.WITHDRAW || effect==Effect.CRAFT || effect==Effect.PLACE)
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
    private Vec3 oakInteractionPoint(Villager v,BlockPos p) {
        if(!loaded(p) || !loaded(v.blockPosition()))return null;
        for(double height:new double[]{0.5,0.125,0.875}) {
            var point=new Vec3(p.getX()+.5,p.getY()+height,p.getZ()+.5);
            if(canReach(v,point,p))return point;
        }
        return null;
    }
    private boolean oakApproachGeometry(Villager v, BlockPos target) {
        BlockPos now=v.blockPosition();
        if (Math.abs((long)now.getX()-target.getX())+Math.abs((long)now.getZ()-target.getZ())!=1) return false;
        double dx=v.getX()-(target.getX()+.5),dz=v.getZ()-(target.getZ()+.5);
        return dx*dx+dz*dz<=OAK_APPROACH_DISTANCE_SQUARED;
    }
    @Override public Movement move(ActorRef actor, Cell target, int maxNavigationCalls) {
        return navigate(actor,target,maxNavigationCalls,false);
    }
    @Override public Movement moveToSource(ActorRef actor, Cell target, int maxNavigationCalls) {
        return navigate(actor,target,maxNavigationCalls,true);
    }
    @Override public Movement approachResource(ActorRef actor,Cell target,int maximum,ResourceDelivery.ItemIdentity item) {
        return LogAcquisition.log(item) ? navigate(actor,target,maximum,false,true)
                : SurvivalGateway.WorldAccess.super.approachResource(actor,target,maximum,item);
    }
    @Override public Movement approachResourceDrop(ActorRef actor,UUID id,int expected,int maximum,ResourceDelivery.ItemIdentity item) {
        if(!LogAcquisition.log(item))
            return SurvivalGateway.WorldAccess.super.approachResourceDrop(actor,id,expected,maximum,item);
        Villager v=villager(actor);Entity entity=level.getEntity(id);
        if(v==null || !(entity instanceof ItemEntity drop) || !drop.isAlive()
                || !defaultLog(drop.getItem(),item) || drop.getItem().getCount()!=expected)
            return new Movement(false,true,0,0);
        BlockPos actual=drop.blockPosition();
        if(!loaded(actual) || !level.mayInteract(v,actual))return new Movement(false,true,0,0);
        BlockPos standing=level.getBlockState(actual).getCollisionShape(level,actual).isEmpty() ? actual : actual.above();
        return navigate(actor,new Cell(dimension(),standing.getX(),standing.getY(),standing.getZ()),maximum,
                true,false,drop.position(),actual);
    }
    private void observeTravel(Villager v) {
        MovementTravel measured=movementTravel.get(v.getUUID());
        if(measured!=null) {
            BlockPos p=v.blockPosition();measured.observe(tick(),p.getX(),p.getY(),p.getZ());
        }
    }
    private long sampleTravel(Villager v) {
        observeTravel(v);
        MovementTravel measured=movementTravel.get(v.getUUID());
        return measured==null ? 0 : measured.charge();
    }
    private Movement navigate(ActorRef actor, Cell target, int maxNavigationCalls, boolean sourceApproach) {
        return navigate(actor,target,maxNavigationCalls,sourceApproach,false);
    }
    private Movement navigate(ActorRef actor,Cell target,int maxNavigationCalls,boolean sourceApproach,boolean solidApproach) {
        return navigate(actor,target,maxNavigationCalls,sourceApproach,solidApproach,null,null);
    }
    private Movement navigate(ActorRef actor,Cell target,int maxNavigationCalls,boolean sourceApproach,boolean solidApproach,
                              Vec3 interactionPoint,BlockPos interactionBlock) {
        Villager v = villager(actor);
        if (v == null) {
            routes.remove(actor.entityId());
            return new Movement(false, true, 0, 0);
        }
        BlockPos targetPos = position(target), now = v.blockPosition();
        boolean ownedDrop=interactionPoint!=null,oakApproach=solidApproach || ownedDrop;
        Vec3 point=ownedDrop ? interactionPoint : Vec3.atCenterOf(targetPos);
        if(solidApproach) {
            var visible=oakInteractionPoint(v,targetPos);
            if(visible!=null)point=visible;
        }
        BlockPos rayTarget=ownedDrop ? interactionBlock : targetPos;
        long travel=sampleTravel(v);
        if (travel > 1) return terminal(v, routes.get(v.getUUID()), "invalid-travel", 0, travel);
        v.getLookControl().setLookAt(point.x,point.y,point.z);
        boolean adjacent=oakApproachGeometry(v,targetPos);
        // A worker can still be falling or jumping when a request starts. Wait
        // for ordinary landing before spending finite ground-path approaches.
        Route route = routes.get(v.getUUID());
        if (!v.onGround()) {
            if(route!=null)route.settledSince=-1;
            return new Movement(false,false,0,travel);
        }
        if(oakApproach && route==null) {
            UUID owner=GatewayControl.owner(v);
            if(owner==null)return new Movement(false,true,0,travel);
            route=new Route(targetPos,owner,sourceApproach,solidApproach,ownedDrop);routes.put(v.getUUID(),route);
        }
        boolean selectedFace = route == null || route.approachTarget == null
                || now.getX() == route.approachTarget.getX() && now.getZ() == route.approachTarget.getZ()
                && Math.abs(v.getY()-route.approachTarget.getY())<=0.125;
        if ((!solidApproach || adjacent && v.onGround() && selectedFace) && canReach(v,point,rayTarget) && (!sourceApproach
                || v.distanceToSqr(point) <= (ownedDrop ? OAK_DROP_APPROACH_DISTANCE_SQUARED : SOURCE_APPROACH_DISTANCE_SQUARED))) {
            if (oakApproach) {
                stopOwnedNavigation(v, route);
                var motion = v.getDeltaMovement();
                if (motion.x * motion.x + motion.z * motion.z > OAK_SETTLED_MOTION_SQUARED || motion.y>0.0001) {
                    route.settledSince=-1;
                    return new Movement(false,false,0,travel);
                }
                if(route.settledSince<0 || !now.equals(route.settledCell)) {
                    route.settledSince=tick();route.settledCell=now;
                }
                if(tick()-route.settledSince<2)return new Movement(false,false,0,travel);
            }
            return terminal(v, routes.get(v.getUUID()), "physical-reach-and-los", 0, travel);
        }
        if(route!=null)route.settledSince=-1;
        if (!loaded(targetPos) || !level.mayInteract(v, targetPos))
            return terminal(v, routes.get(v.getUUID()), "target-unavailable", 0, travel);
        if (route != null && (!route.target.equals(targetPos) || route.sourceApproach != sourceApproach
                || route.solidApproach != solidApproach || route.dropApproach != ownedDrop)) {
            stopOwnedNavigation(v, route);
            routes.remove(v.getUUID());
            route = null;
        }
        if (route == null) {
            UUID runId = GatewayControl.owner(v);
            if (runId == null) return new Movement(false, true, 0, travel);
            route = new Route(targetPos, runId, sourceApproach, solidApproach,ownedDrop);
            routes.put(v.getUUID(),route);
        }
        if (route.policy.stage() == NavigationRoute.Stage.FOLLOW_PATH) {
            Path active = v.getNavigation().getPath();
            NavigationRoute.PathSample sample = active == null ? null : sample(active, v);
            var endpoint = oakEndpoint(v,route,active);
            if (route.policy.follow(sample, tick(),endpoint) == NavigationRoute.Follow.CONTINUE) {
                if(endpoint!=null)refineOakEndpoint(v,route,endpoint);
                return new Movement(false, false, 0, travel);
            }
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
                    route.approachTarget = candidate;
                    var endpoint=oakEndpoint(v,route,active);
                    route.policy.accepted(initial, tick(),endpoint);
                    if (initial.node() >= initial.count() && endpoint==null) {
                        route.policy.abandon("finished-outside-reach");
                        stopOwnedNavigation(v, route);
                    } else if(endpoint!=null)refineOakEndpoint(v,route,endpoint);
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
    private NavigationRoute.Position oakEndpoint(Villager v,Route route,Path path) {
        BlockPos goal=route.approachTarget;
        if(!(route.solidApproach || route.dropApproach) || goal==null || path==null || !path.isDone()
                || path.getEndNode()==null || !GatewayControl.holds(v,route.runId)
                || !v.onGround() || Math.abs(v.getY()-goal.getY())>0.125 || !walkable(v,goal))return null;
        var end=path.getEndNode();
        if(end.x!=goal.getX() || end.y!=goal.getY() || end.z!=goal.getZ())return null;
        var point=new Vec3(goal.getX()+.5,goal.getY(),goal.getZ()+.5);
        if(v.distanceToSqr(point)>1.0 || !canReach(v,point,goal))return null;
        return new NavigationRoute.Position(point.x,point.y,point.z);
    }
    private void refineOakEndpoint(Villager v,Route route,NavigationRoute.Position endpoint) {
        // The native path ended within its normal tolerance, outside the tighter
        // harvest face. Ordinary collision-aware movement closes this last step;
        // the same policy still checks real path identity, progress and stall time.
        route.endpointRefined=true;
        v.getMoveControl().setWantedPosition(endpoint.x(),endpoint.y(),endpoint.z(),0.65);
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
        MovementTravel measured=movementTravel.get(v.getUUID());
        if(reason.equals("physical-reach-and-los") && measured!=null && !measured.drained()) {
            stopOwnedNavigation(v,route);
            return new Movement(false,false,work,travel);
        }
        if (route != null) {
            route.policy.terminal(reason);
            stopOwnedNavigation(v, route);
            lastNavigationSummary = new NavigationSummary(v.getUUID(), route.policy.attempts(),
                    route.policy.navigationCalls(), reason, route.endpointRefined);
            if (Boolean.getBoolean("cognitivecraft.gametest.trace")
                    || Boolean.getBoolean("cognitivecraft.navigation.trace")) {
                var sample = route.policy.lastSample();
                System.out.println("CognitiveCraft MOVE route actor=" + v.getUUID()
                        + " position=" + v.position() + " target=" + route.target
                        + " targetDistance=" + v.distanceToSqr(Vec3.atCenterOf(route.target))
                        + " lineOfSight=" + canReach(v, route.target)
                        + " approach=" + route.policy.approach() + " selectedApproach=" + route.approachTarget + " generation="
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
    private static boolean defaultLog(ItemStack stack,ResourceDelivery.ItemIdentity item) {
        return !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack,new ItemStack(FabricResourceItems.item(item)));
    }
    private static boolean defaultOak(ItemStack stack) {
        return !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack,new ItemStack(Items.OAK_LOG));
    }
    private Container oakBox(BlockPos p) {
        var state=level.getBlockState(p);
        if (state.is(Blocks.CHEST) && state.getValue(ChestBlock.TYPE)!=ChestType.SINGLE) return null;
        Container container=box(p);
        return container!=null && container.getContainerSize()<=128 ? container : null;
    }
    record ItemContainerInspection(ObservationStatus status,int capacity,int slotsInspected,String identity) { }
    ItemContainerInspection inspectOakContainer(ContainerRef target) {return inspectLogContainer(target,ResourceDelivery.OAK_LOG);}
    ItemContainerInspection inspectLogContainer(ContainerRef target,ResourceDelivery.ItemIdentity item) {
        BlockPos p=new BlockPos(target.x(),target.y(),target.z());
        if (!target.dimension().equals(dimension()) || !loaded(p))
            return new ItemContainerInspection(ObservationStatus.UNKNOWN,0,0,"unavailable");
        Container c=oakBox(p);
        if(c==null) return new ItemContainerInspection(ObservationStatus.ABSENT,0,0,"unsuitable");
        var sample=itemCapacity(c,new ItemStack(FabricResourceItems.item(item)));
        return new ItemContainerInspection(sample.known() ? ObservationStatus.PRESENT : ObservationStatus.UNKNOWN,
                sample.capacity(),sample.slotsInspected(),"container:"+p.asLong());
    }
    ItemContainerInspection inspectPlankContainer(ContainerRef target) {return inspectRecipeContainer(target,ResourceDelivery.OAK_PLANKS);}
    ItemContainerInspection inspectRecipeContainer(ContainerRef target,ResourceDelivery.ItemIdentity item) {
        BlockPos p=new BlockPos(target.x(),target.y(),target.z());
        if(!target.dimension().equals(dimension()) || !loaded(p))return new ItemContainerInspection(ObservationStatus.UNKNOWN,0,0,"unavailable");
        Container c=oakBox(p);if(c==null)return new ItemContainerInspection(ObservationStatus.ABSENT,0,0,"unsuitable");
        var sample=itemCapacity(c,new ItemStack(FabricResourceItems.item(item)));
        return new ItemContainerInspection(sample.known() ? ObservationStatus.PRESENT : ObservationStatus.UNKNOWN,sample.capacity(),sample.slotsInspected(),
                "plank-container:"+p.asLong()+":"+System.identityHashCode(c));
    }
    private static ItemContainerCapacity.Sample itemCapacity(Container c,ItemStack item) {
        return ItemContainerCapacity.inspect(c.getContainerSize(),slot -> {
            ItemStack current=c.getItem(slot);
            int limit=Math.min(item.getMaxStackSize(),c.getMaxStackSize(item));
            if(!current.isEmpty()) limit=Math.min(limit,current.getMaxStackSize());
            return new ItemContainerCapacity.Slot(c.canPlaceItem(slot,item),current.isEmpty(),
                    ItemStack.isSameItemSameComponents(current,item),current.getCount(),limit);
        });
    }
    private static String logInventoryFingerprint(Container c,ResourceDelivery.ItemIdentity item) {
        // Default oak stacks are fungible, but their slot/count footprint may not change
        // outside a witnessed pickup/transfer. Other slots still affect live capacity.
        StringBuilder rows=new StringBuilder();
        for(int i=0;i<c.getContainerSize();i++) {
            ItemStack stack=c.getItem(i);
            rows.append(i).append(':').append(stack.isEmpty() ? "empty" :
                    defaultLog(stack,item) ? (item.equals(ResourceDelivery.OAK_LOG) ? "oak" : "birch") : stack.is(FabricResourceItems.item(item)) ? (item.equals(ResourceDelivery.OAK_LOG) ? "unsupported-oak" : "unsupported-birch") : "other")
                    .append(':').append(stack.getCount()).append(';');
        }
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(rows.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    @Override public ItemBlock resourceBlock(Cell target,ResourceDelivery.ItemIdentity item) {
        if(!LogAcquisition.log(item)) return SurvivalGateway.WorldAccess.super.resourceBlock(target,item);
        BlockPos p=position(target);
        if(!target.dimension().equals(dimension()) || !loaded(p))
            return new ItemBlock(ObservationStatus.UNKNOWN,false,target.identity(),0);
        var state=level.getBlockState(p);
        if(!state.is(FabricResourceItems.log(item))) return new ItemBlock(ObservationStatus.ABSENT,false,target.identity(),0);
        float hardness=state.getDestroySpeed(level,p);
        long ticks=(long)Math.ceil((double)hardness*30.0);
        boolean supported=Float.isFinite(hardness) && hardness>0 && !state.requiresCorrectToolForDrops()
                && ticks>=1 && ticks<=200;
        return new ItemBlock(ObservationStatus.PRESENT,supported,state.toString(),supported ? (int)ticks : 0);
    }
    @Override public ItemStock resourceInventory(ActorRef actor,ResourceDelivery.ItemIdentity item) {
        if(item.equals(ResourceDelivery.BIRCH_PLANKS) || item.equals(ResourceDelivery.OAK_PLANKS) || item.equals(ResourceDelivery.STICK) || item.equals(ResourceDelivery.WOODEN_AXE) || item.equals(ResourceDelivery.CRAFTING_TABLE)) {
            var v=recipeActor(actor);if(v==null)return new ItemStock(ObservationStatus.UNKNOWN,"unavailable",0,0,"",null);
            return recipeStock(v.getInventory(),"actor:"+actor.entityId(),item);
        }
        if(!LogAcquisition.log(item)) return SurvivalGateway.WorldAccess.super.resourceInventory(actor,item);
        Villager v=villager(actor);
        if(v==null || !loaded(v.blockPosition()))
            return new ItemStock(ObservationStatus.UNKNOWN,"actor:"+actor.entityId(),0,0,"",null);
        Container c=v.getInventory(); long quantity=0; Reason reason=null;
        if(c.getContainerSize()>128) return new ItemStock(ObservationStatus.UNKNOWN,"actor:"+actor.entityId(),0,0,"",Reason.BUDGET_EXHAUSTED);
        for(int slot=0;slot<c.getContainerSize();slot++) {
            ItemStack stack=c.getItem(slot);
            if(defaultLog(stack,item)) quantity=Math.addExact(quantity,stack.getCount());
            else if(stack.is(FabricResourceItems.item(item))) reason=Reason.TARGET_INVALID;
        }
        var capacity=itemCapacity(c,new ItemStack(FabricResourceItems.item(item)));
        return new ItemStock(ObservationStatus.PRESENT,"actor:"+actor.entityId(),quantity,capacity.capacity(),
                logInventoryFingerprint(c,item),reason);
    }
    @Override public ItemStock resourceContainer(ContainerRef target,ResourceDelivery.ItemIdentity item) {
        if(item.equals(ResourceDelivery.BIRCH_PLANKS) || item.equals(ResourceDelivery.OAK_PLANKS) || item.equals(ResourceDelivery.STICK) || item.equals(ResourceDelivery.WOODEN_AXE) || item.equals(ResourceDelivery.CRAFTING_TABLE)) {
            Container c=recipeContainer(target);if(c==null)return new ItemStock(ObservationStatus.UNKNOWN,"unavailable",0,0,"",null);
            return recipeStock(c,"container:"+new BlockPos(target.x(),target.y(),target.z()).asLong(),item);
        }
        if(!LogAcquisition.log(item)) return SurvivalGateway.WorldAccess.super.resourceContainer(target,item);
        var inspection=inspectLogContainer(target,item);
        long quantity=0;
        if(inspection.status()==ObservationStatus.PRESENT) {
            Container c=oakBox(new BlockPos(target.x(),target.y(),target.z()));
            for(int slot=0;slot<c.getContainerSize();slot++) if(defaultLog(c.getItem(slot),item))
                quantity=Math.addExact(quantity,c.getItem(slot).getCount());
        }
        return new ItemStock(inspection.status(),inspection.identity(),quantity,inspection.capacity(),"",null);
    }
    @Override public Reason resourceBreakEligibility(ActorRef actor,Cell target,ResourceDelivery.ItemIdentity item) {
        if(!LogAcquisition.log(item)) return SurvivalGateway.WorldAccess.super.resourceBreakEligibility(actor,target,item);
        Villager v=villager(actor); BlockPos p=position(target);
        if(v==null || !target.dimension().equals(dimension()) || !loaded(p)) return Reason.TARGET_UNAVAILABLE;
        if(!level.mayInteract(v,p) || !level.getGameRules().get(GameRules.MOB_GRIEFING)
                || !level.getGameRules().get(GameRules.BLOCK_DROPS)) return Reason.AUTHORITY_DENIED;
        if(!v.getMainHandItem().isEmpty() || !v.getOffhandItem().isEmpty() || !v.getActiveEffects().isEmpty())
            return Reason.TARGET_INVALID;
        if(!v.onGround() || v.isInWater() || v.isInLava() || !oakApproachGeometry(v,p) || oakInteractionPoint(v,p)==null) return Reason.TARGET_UNAVAILABLE;
        return resourceBlock(target,item).eligible() ? null : Reason.TARGET_INVALID;
    }
    @Override public ItemHarvest resourceHarvest(ActorRef actor,Cell target,ResourceDelivery.ItemIdentity item,long elapsedTicks) {
        if(!LogAcquisition.log(item)) return SurvivalGateway.WorldAccess.super.resourceHarvest(actor,target,item,elapsedTicks);
        Reason reason=resourceBreakEligibility(actor,target,item);
        if(reason!=null) return new ItemHarvest(false,List.of(),reason);
        ItemBlock witness=resourceBlock(target,item);
        if(elapsedTicks<witness.breakTicks()) return new ItemHarvest(false,List.of(),Reason.TARGET_UNAVAILABLE);
        Villager v=villager(actor); BlockPos p=position(target); var state=level.getBlockState(p);
        List<ItemStack> loot=Block.getDrops(state,level,p,null,v,ItemStack.EMPTY);
        long oakUnits=0;
        for(ItemStack stack:loot) if(stack.is(FabricResourceItems.item(item)) && !stack.isEmpty()) oakUnits=Math.addExact(oakUnits,stack.getCount());
        if(loot.size()>64 || oakUnits>64 || loot.stream().anyMatch(s -> s.is(FabricResourceItems.item(item))
                && (!defaultLog(s,item) || s.getCount()>s.getMaxStackSize())))
            return new ItemHarvest(false,List.of(),Reason.TARGET_INVALID);
        ItemStock capacity=resourceInventory(actor,item);
        if(capacity.status()!=ObservationStatus.PRESENT || capacity.reason()!=null || capacity.capacity()<oakUnits)
            return new ItemHarvest(false,List.of(),capacity.reason()==null ? Reason.TARGET_UNAVAILABLE : capacity.reason());
        if(!level.setBlock(p,Blocks.AIR.defaultBlockState(),3)) return new ItemHarvest(false,List.of(),Reason.ACTION_FAILED);
        List<ItemDrop> tracked=new ArrayList<>(); boolean failed=false;
        for(ItemStack stack:loot) {
            if(stack.isEmpty()) continue;
            ItemEntity entity=new ItemEntity(level,p.getX()+.5,p.getY()+.25,p.getZ()+.5,stack.copy());
            entity.setDefaultPickUpDelay(); entity.setThrower(v);
            // Vanilla merge equality includes target. A unique target prevents both foreign
            // stock and other batches merging into this entity; manual gateway pickup keeps
            // the exact stack. The tick mixin clears the target when actor control is lost.
            if(defaultLog(stack,item)) entity.setTarget(entity.getUUID());
            if(!level.addFreshEntity(entity)) { failed=true; continue; }
            if(defaultLog(stack,item)) tracked.add(new ItemDrop(entity.getUUID(),stack.getCount()));
        }
        return new ItemHarvest(true,tracked,failed ? Reason.ACTION_FAILED : null);
    }
    @Override public ItemDropState resourceDrop(UUID id,ResourceDelivery.ItemIdentity item) {
        if(!LogAcquisition.log(item)) return SurvivalGateway.WorldAccess.super.resourceDrop(id,item);
        Entity entity=level.getEntity(id);
        if(!(entity instanceof ItemEntity drop) || drop.isRemoved())
            return new ItemDropState(ObservationStatus.ABSENT,new Cell(dimension(),0,0,0),0);
        BlockPos p=drop.blockPosition();
        return new ItemDropState(loaded(p) ? ObservationStatus.PRESENT : ObservationStatus.UNKNOWN,
                new Cell(dimension(),p.getX(),p.getY(),p.getZ()),defaultLog(drop.getItem(),item) ? drop.getItem().getCount() : 0);
    }
    @Override public ItemPickup resourcePickup(ActorRef actor,UUID id,int expected,ResourceDelivery.ItemIdentity item) {
        if(!LogAcquisition.log(item)) return SurvivalGateway.WorldAccess.super.resourcePickup(actor,id,expected,item);
        Villager v=villager(actor); Entity entity=level.getEntity(id);
        if(v==null || !(entity instanceof ItemEntity drop) || !drop.isAlive() || !loaded(drop.blockPosition()))
            return new ItemPickup(0,expected,Reason.STALE_OBSERVATION);
        if(!level.mayInteract(v,drop.blockPosition()) || !level.getGameRules().get(GameRules.MOB_GRIEFING))
            return new ItemPickup(0,expected,Reason.AUTHORITY_DENIED);
        ItemStack stack=drop.getItem();
        if(!defaultLog(stack,item) || stack.getCount()!=expected) return new ItemPickup(0,expected,Reason.STALE_OBSERVATION);
        if(drop.hasPickUpDelay() || !canReach(v,drop.position(),drop.blockPosition()))
            return new ItemPickup(0,expected,Reason.TARGET_UNAVAILABLE);
        ItemStack remaining=v.getInventory().addItem(stack.copy()); int moved=stack.getCount()-remaining.getCount();
        if(moved<=0) return new ItemPickup(0,expected,Reason.TARGET_UNAVAILABLE);
        if(remaining.isEmpty()) drop.discard(); else drop.setItem(remaining);
        v.getInventory().setChanged(); return new ItemPickup(moved,remaining.getCount(),null);
    }
    @Override public ItemTransfer resourceTransferOne(ActorRef actor,ContainerRef target,ResourceDelivery.ItemIdentity item) {
        if(!LogAcquisition.log(item)) return SurvivalGateway.WorldAccess.super.resourceTransferOne(actor,target,item);
        Villager v=villager(actor); BlockPos p=new BlockPos(target.x(),target.y(),target.z());
        if(v==null || !target.dimension().equals(dimension()) || !loaded(p) || !canReach(v,p))
            return new ItemTransfer(0,Reason.TARGET_UNAVAILABLE);
        if(!level.mayInteract(v,p) || !level.getGameRules().get(GameRules.MOB_GRIEFING))
            return new ItemTransfer(0,Reason.AUTHORITY_DENIED);
        Container c=oakBox(p); if(c==null) return new ItemTransfer(0,Reason.TARGET_INVALID);
        var inventory=v.getInventory(); int from=-1,to=-1;
        for(int i=0;i<inventory.getContainerSize();i++) if(defaultLog(inventory.getItem(i),item)) { from=i; break; }
        if(from<0) return new ItemTransfer(0,Reason.RESOURCE_MISSING);
        ItemStack one=inventory.getItem(from).copyWithCount(1);
        for(int i=0;i<c.getContainerSize();i++) {
            ItemStack current=c.getItem(i);
            int limit=Math.min(one.getMaxStackSize(),c.getMaxStackSize(one));
            if(!current.isEmpty()) limit=Math.min(limit,current.getMaxStackSize());
            if(c.canPlaceItem(i,one) && (current.isEmpty() && limit>=1
                    || ItemStack.isSameItemSameComponents(current,one) && current.getCount()<limit)) { to=i; break; }
        }
        if(to<0) return new ItemTransfer(0,Reason.TARGET_UNAVAILABLE);
        inventory.removeItem(from,1);
        if(c.getItem(to).isEmpty()) c.setItem(to,one); else c.getItem(to).grow(1);
        inventory.setChanged(); c.setChanged(); return new ItemTransfer(1,null);
    }
    private ItemStock recipeStock(Container c,String identity,ResourceDelivery.ItemIdentity item) {
        var nativeItem=FabricResourceItems.item(item);
        long quantity=0;Reason invalid=null;
        for(int i=0;i<c.getContainerSize();i++) {
            ItemStack s=c.getItem(i);
            if(FabricRecipeTransactions.plain(s,nativeItem))quantity=Math.addExact(quantity,s.getCount());
            else if(s.is(nativeItem))invalid=Reason.TARGET_INVALID;
        }
        var capacity=itemCapacity(c,new ItemStack(nativeItem));
        return new ItemStock(ObservationStatus.PRESENT,identity,quantity,capacity.capacity(),FabricRecipeTransactions.fingerprint(c),invalid);
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
