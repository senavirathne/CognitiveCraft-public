package dev.aivillages.fabric;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.google.gson.Gson;
import dev.aivillages.core.kernel.Budgets;
import dev.aivillages.core.kernel.BoundedSkillExecutor;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.CropDelivery;
import dev.aivillages.core.kernel.GatewayPrimitives;
import dev.aivillages.core.kernel.GatewayPrimitives.Operation;
import dev.aivillages.core.kernel.Outcomes.Reason;
import dev.aivillages.core.kernel.Outcomes.ExecutionStatus;
import dev.aivillages.core.kernel.SkillCompiler;
import dev.aivillages.core.kernel.SurvivalGateway;
import dev.aivillages.fabric.mixin.PathNavigationAccessor;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.gamerules.GameRules;

import static dev.aivillages.core.kernel.Budgets.Kind;

/** Isolated real mechanics tests; no generation/model integration and no production seed skill. */
public final class GatewayGameTests {
    private static final BlockPos CROP = new BlockPos(2,1,2);
    private static final BlockPos CHEST = new BlockPos(2,1,0);

    private static void floor(GameTestHelper h) {
        for (int x=0;x<8;x++) for (int z=0;z<8;z++) h.setBlock(x,0,z,Blocks.STONE);
    }
    private static final class Scene {
        final GameTestHelper helper;
        final Villager worker;
        final ActorRef actor;
        final Cuboid source;
        final ContainerRef destination;
        final ValidatedRequest request;
        final UUID runId = UUID.randomUUID();
        final SurvivalGateway gateway;
        final FabricGatewayWorld world;
        final Budgets.Ledger ledger;
        final List<CropDelivery.CropReceipt> receipts = new ArrayList<>();
        Scene(GameTestHelper helper, Villager worker, BlockPos crop, BlockPos chest) {
            this.helper = helper; this.worker = worker;
            String dim = helper.getLevel().dimension().identifier().toString();
            BlockPos cropWorld = helper.absolutePos(crop), chestWorld = helper.absolutePos(chest);
            actor = new ActorRef(UUID.randomUUID(), worker.getUUID(), dim);
            source = new Cuboid(dim, cropWorld.getX(), cropWorld.getY(), cropWorld.getZ(),
                    cropWorld.getX(), cropWorld.getY(), cropWorld.getZ());
            destination = new ContainerRef(dim, chestWorld.getX(), chestWorld.getY(), chestWorld.getZ());
            request = new ValidatedRequest(new CapabilityRequest(CropDelivery.ID, Map.of(
                    "actor", new ActorValue(actor), "source", new AreaValue(source),
                    "destination", new ContainerValue(destination), "amount", new IntValue(1))),
                    new TrustedContext(new PrincipalRef(UUID.randomUUID()),
                            new ScopeRef(UUID.randomUUID(), UUID.randomUUID())),
                    new ObservationRef(UUID.randomUUID(), 0, dim));
            world = new FabricGatewayWorld(helper.getLevel());
            gateway = new SurvivalGateway(world,
                    (candidate, effect, context) -> context.equals(request.context()),
                    new SurvivalGateway.Limits(1, 64, 64, 16, 16, 1, 200, 32), Clock.systemUTC());
            ledger = new Budgets.Ledger(new Budgets.Limits(Map.of(
                    Kind.INSTRUCTIONS, 500L, Kind.CALLS, 64L, Kind.OBSERVATIONS, 64L,
                    Kind.TRAVEL_BLOCKS, 32L, Kind.ATTEMPTED_EFFECTS, 16L,
                    Kind.COMMITTED_EFFECTS, 16L, Kind.ELAPSED_TICKS, 200L),
                    System.currentTimeMillis() + 60_000), Clock.systemUTC());
        }
        Map<String, Value> at(BlockPos relative) {
            return atWorld(helper.absolutePos(relative));
        }
        Map<String, Value> atWorld(BlockPos pos) {
            return Map.of("actor", new ActorValue(actor), "x", new IntValue(pos.getX()),
                    "y", new IntValue(pos.getY()), "z", new IntValue(pos.getZ()));
        }
        ActionHandle start(Operation op, Map<String, Value> args) {
            return gateway.start(request, new RunCorrelation(runId,
                    new ArtifactRef(CropDelivery.ID, "a".repeat(64)), null),
                    op.requirement(), args, ledger);
        }
        ActionReceipt poll(ActionHandle handle) {
            ActionReceipt outcome = gateway.poll(handle);
            if (outcome.terminal()) receipts.addAll(outcome.cropReceipts());
            return outcome;
        }
    }

    /** A test-supplied, parameter-bound IR body drives actual gateway effects on a server tick. */
    @GameTest(maxTicks=160,padding=16)
    public void executorComposesPhysicalCropDelivery(GameTestHelper h) {
        floor(h);
        h.setBlock(CROP.below(), Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));
        h.setBlock(CROP, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));
        h.setBlock(CHEST, Blocks.CHEST);
        Villager v = h.spawnWithNoFreeWill(EntityTypes.VILLAGER,3,1,2);
        Scene scene = new Scene(h,v,CROP,CHEST);
        Map<String,Object> boundSource = Map.of("actor", Map.of("param","actor"),
                "source", Map.of("param","source"));
        Map<String,Object> inventory = Map.of("actor", Map.of("param","actor"));
        Map<String,Object> transfer = Map.of("actor", Map.of("param","actor"),
                "destination", Map.of("param","destination"), "amount", Map.of("param","amount"));
        List<Object> body = List.of(irCall(Operation.HARVEST_NEXT_WHEAT,boundSource,"harvested"),
                Map.of("op","repeat","count",Map.of("int",30),"body",
                        List.of(irCall(Operation.OBSERVE_INVENTORY,inventory,"stock"))),
                irCall(Operation.PICKUP_TRACKED_WHEAT,inventory,"picked"),
                irCall(Operation.TRANSFER_WHEAT,transfer,"delivered"),
                Map.of("op","result","value",Map.of("param","amount")));
        String source = new Gson().toJson(Map.of("schema",1,"capability",CropDelivery.ID.name(),
                "capabilityVersion",1,"dependencies",List.of(),"body",body));
        SkillCompiler compiler = new SkillCompiler(id -> id.equals(CropDelivery.ID)
                ? Optional.of(CropDelivery.SPEC) : Optional.empty(), GatewayPrimitives.instance(),
                ref -> Optional.empty());
        SkillCompiler.CompileResult compiled = compiler.compile(source);
        h.assertTrue(compiled instanceof SkillCompiler.Success, "Test IR compilation: "+compiled);
        SkillArtifact artifact = ((SkillCompiler.Success)compiled).skill().artifact();
        ArtifactRef ref = artifact.descriptor().ref();
        BoundedSkillExecutor.ArtifactSource catalog = new BoundedSkillExecutor.ArtifactSource() {
            public Optional<SkillArtifact> body(ArtifactRef key) {
                return key.equals(ref) ? Optional.of(artifact) : Optional.empty();
            }
            public Optional<AdmissionRecord> admission(ArtifactRef key) {
                return key.equals(ref) ? Optional.of(new AdmissionRecord(ref, AdmissionStatus.ADMITTED,
                        new EvidenceRef("fixture", "gametest:1", "isolated"), null, 1)) : Optional.empty();
            }
            public Optional<Compatibility> compatibility(ArtifactRef key) {
                return key.equals(ref) ? Optional.of(new Compatibility(ref,
                        CompatibilityStatus.COMPATIBLE,List.of(),"gametest")) : Optional.empty();
            }
        };
        BoundedSkillExecutor executor = new BoundedSkillExecutor(catalog,
                id -> id.equals(CropDelivery.ID) ? Optional.of(CropDelivery.SPEC) : Optional.empty(),
                GatewayPrimitives.instance(), (actor, key, context) -> actor.equals(scene.actor)
                        && context.equals(scene.request.context()),
                (permit, request, limits) -> false, new BoundedSkillExecutor.ControlPolicy() {
                    public boolean mayInspect(TrustedContext caller, TrustedContext owner, UUID run) {
                        return caller.equals(scene.request.context()) && owner.equals(caller);
                    }
                    public boolean mayCancel(TrustedContext caller, TrustedContext owner, UUID run) {
                        return caller.equals(scene.request.context()) && owner.equals(caller);
                    }
                }, scene.gateway, scene.gateway::releaseRun, Clock.systemUTC(),
                () -> h.getLevel().getGameTime(), new BoundedSkillExecutor.Settings(4,16,32,64));
        var total = new Budgets.Limits(Map.of(Kind.INSTRUCTIONS,500L, Kind.CALLS,64L,
                Kind.OBSERVATIONS,64L, Kind.TRAVEL_BLOCKS,32L,
                Kind.ATTEMPTED_EFFECTS,16L, Kind.COMMITTED_EFFECTS,16L,
                Kind.ELAPSED_TICKS,200L), System.currentTimeMillis()+60_000);
        var limits = new Budgets.ExecutionLimits(total,10_000);
        var started = executor.startAdmitted(scene.request,ref,new RunCorrelation(scene.runId,ref,null),
                limits,new Budgets.Ledger(total,Clock.systemUTC()));
        h.assertTrue(started instanceof BoundedSkillExecutor.Started,"Executor refused fixture: "+started);
        BoundedSkillExecutor.Run run = ((BoundedSkillExecutor.Started)started).run();
        h.succeedWhen(()->{
            var progress = run.tick(scene.request.context());
            h.assertTrue(progress.phase()==BoundedSkillExecutor.Phase.TERMINAL,"Execution still waiting");
            h.assertTrue(progress.summary().outcome().status()==ExecutionStatus.SUCCEEDED,
                    "Executor failed: "+progress.summary().outcome());
            Container box = (Container)h.getLevel().getBlockEntity(h.absolutePos(CHEST));
            h.assertTrue(box.countItem(Items.WHEAT)==1 && h.getBlockState(CROP).isAir(),
                    "Delivered wheat did not follow Minecraft block/drop/inventory mechanics");
            h.assertTrue(!AiVillages.controls(v),"Execution retained worker control after completion");
        });
    }

    private static Map<String,Object> irCall(Operation op, Map<String,Object> args, String into) {
        return Map.of("op","call","kind","primitive","id",op.signature().id(),
                "version",1,"fingerprint",op.signature().fingerprint(),"args",args,"into",into);
    }
    @GameTest(maxTicks=160,padding=16)
    public void harvestedDropsRequirePhysicalPickupAndTransfer(GameTestHelper h) {
        floor(h);
        h.setBlock(CROP.below(), Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));
        h.setBlock(CROP, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));
        h.setBlock(CHEST, Blocks.CHEST);
        Villager v = h.spawnWithNoFreeWill(EntityTypes.VILLAGER,2,1,2);
        v.getInventory().addItem(new ItemStack(Items.WHEAT,3));
        Container box = (Container)h.getLevel().getBlockEntity(h.absolutePos(CHEST));
        box.setItem(0,new ItemStack(Items.WHEAT,5));
        Scene scene = new Scene(h,v,CROP,CHEST);
        ActionReceipt harvest = scene.poll(scene.start(Operation.HARVEST_WHEAT,scene.at(CROP)));
        h.assertTrue(harvest.terminal() && harvest.reason()==null && harvest.committedEffects()==1,
                "Actual mature crop was not harvested");
        h.assertTrue(h.getBlockState(CROP).isAir() && v.getInventory().countItem(Items.WHEAT)==3
                && box.countItem(Items.WHEAT)==5, "Harvest inserted yield into inventory/container");
        h.assertTrue(h.getLevel().getEntitiesOfClass(ItemEntity.class,
                        v.getBoundingBox().inflate(4), e->e.getItem().is(Items.WHEAT)).size()>=1,
                "Harvest did not spawn wheat item entity");
        h.assertTrue(!CropDelivery.completed(scene.request,scene.runId,scene.receipts),
                "Harvest alone satisfied delivery");
        h.assertTrue(AiVillages.gatewayControls(v), "Gateway did not hold worker during custody");
        h.runAtTickTime(30,()->{
            int beforePickup = v.getInventory().countItem(Items.WHEAT);
            var worldWheat = h.getLevel().getEntitiesOfClass(ItemEntity.class,
                    v.getBoundingBox().inflate(4),e->e.getItem().is(Items.WHEAT));
            h.assertTrue(!worldWheat.isEmpty(),"Tracked wheat vanished beside leased worker: inventoryWheat="+beforePickup+" controls="+AiVillages.gatewayControls(v));
            BlockPos dropCell = worldWheat.getFirst().blockPosition();
            ActionReceipt pickup = scene.poll(scene.start(Operation.PICKUP_WHEAT,scene.atWorld(dropCell)));
            h.assertTrue(pickup.reason()==null && pickup.committedEffects()==1
                    && v.getInventory().countItem(Items.WHEAT)==4 && box.countItem(Items.WHEAT)==5,
                    "Physical pickup failed: reason="+pickup.reason()+" before="+beforePickup
                            +" after="+v.getInventory().countItem(Items.WHEAT)
                            +" container="+box.countItem(Items.WHEAT)
                            +" worldDrops="+worldWheat.stream().map(e->e.getItem().getCount()+"@"+e.blockPosition()
                            +" delay="+e.hasPickUpDelay()).toList());
            ActionReceipt deposit = scene.poll(scene.start(Operation.TRANSFER_WHEAT,
                    Map.of("actor",new ActorValue(scene.actor),
                            "destination",new ContainerValue(scene.destination),
                            "amount",new IntValue(1))));
            h.assertTrue(deposit.reason()==null && deposit.committedEffects()==1
                    && v.getInventory().countItem(Items.WHEAT)==3 && box.countItem(Items.WHEAT)==6,
                    "Physical transfer did not conserve mixed wheat");
            h.assertTrue(CropDelivery.completed(scene.request,scene.runId,scene.receipts),
                    "Three distinct attributable stages did not satisfy outcome");
            scene.gateway.releaseRun(scene.runId);
            h.assertTrue(!AiVillages.controls(v), "Vanilla control not released");
            h.succeed();
        });
    }

    @GameTest(maxTicks=100,padding=16)
    public void fullOrReplacedContainerNeverCreditsTrackedWheat(GameTestHelper h) {
        floor(h);
        h.setBlock(CROP.below(), Blocks.FARMLAND.defaultBlockState()
                .setValue(FarmlandBlock.MOISTURE,7));
        h.setBlock(CROP, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));
        h.setBlock(CHEST, Blocks.CHEST);
        Container box = (Container)h.getLevel().getBlockEntity(h.absolutePos(CHEST));
        for (int slot=0; slot<box.getContainerSize(); slot++)
            box.setItem(slot,new ItemStack(Items.COBBLESTONE,64));
        Villager worker = h.spawnWithNoFreeWill(EntityTypes.VILLAGER,3,1,2);
        worker.getInventory().addItem(new ItemStack(Items.WHEAT,3));
        worker.getInventory().addItem(new ItemStack(Items.WHEAT_SEEDS,2));
        Scene scene = new Scene(h,worker,CROP,CHEST);
        ActionReceipt harvest = scene.poll(scene.start(Operation.HARVEST_WHEAT,scene.at(CROP)));
        h.assertTrue(harvest.reason()==null && harvest.committedEffects()==1
                && worker.getInventory().countItem(Items.WHEAT)==3,
                "Fixture harvest inserted wheat into preloaded inventory");
        h.runAtTickTime(30,()->{
            var drops = h.getLevel().getEntitiesOfClass(ItemEntity.class,
                    worker.getBoundingBox().inflate(4),item->item.getItem().is(Items.WHEAT));
            h.assertTrue(!drops.isEmpty(),"Tracked wheat drop missing before pickup");
            ActionReceipt pickup = scene.poll(scene.start(Operation.PICKUP_WHEAT,
                    scene.atWorld(drops.getFirst().blockPosition())));
            h.assertTrue(pickup.reason()==null && pickup.committedEffects()==1
                    && worker.getInventory().countItem(Items.WHEAT)==4
                    && worker.getInventory().countItem(Items.WHEAT_SEEDS)==2,
                    "Pickup failed or changed unrelated inventory: "+pickup);
            Map<String,Value> transfer = Map.of("actor",new ActorValue(scene.actor),
                    "destination",new ContainerValue(scene.destination),"amount",new IntValue(1));
            ActionReceipt full = scene.poll(scene.start(Operation.TRANSFER_WHEAT,transfer));
            h.assertTrue(full.terminal() && full.reason()==Reason.TARGET_UNAVAILABLE
                    && full.committedEffects()==0 && full.cropReceipts().isEmpty()
                    && worker.getInventory().countItem(Items.WHEAT)==4
                    && box.countItem(Items.WHEAT)==0
                    && !CropDelivery.completed(scene.request,scene.runId,scene.receipts),
                    "Full Minecraft chest credited wheat or lost inventory: "+full);
            ActionHandle pending = scene.start(Operation.TRANSFER_WHEAT,transfer);
            h.setBlock(CHEST,Blocks.STONE);
            ActionReceipt replaced = scene.poll(pending);
            h.assertTrue(replaced.terminal() && replaced.reason()==Reason.TARGET_INVALID
                    && replaced.committedEffects()==0 && replaced.cropReceipts().isEmpty()
                    && worker.getInventory().countItem(Items.WHEAT)==4
                    && !CropDelivery.completed(scene.request,scene.runId,scene.receipts),
                    "Replaced destination credited wheat or lost inventory: "+replaced);
            h.setBlock(CHEST,Blocks.CHEST);
            h.assertTrue(scene.gateway.poll(pending).equals(replaced)
                    && ((Container)h.getLevel().getBlockEntity(h.absolutePos(CHEST)))
                            .countItem(Items.WHEAT)==0,
                    "Restoring destination replayed terminal transfer");
            scene.gateway.releaseRun(scene.runId);
            h.assertTrue(!AiVillages.controls(worker),"Gateway retained worker after blockers");
            h.succeed();
        });
    }

    @GameTest(maxTicks=100,padding=16)
    public void maturityAndRevokedRuleStopPendingEffect(GameTestHelper h) {
        floor(h);
        h.setBlock(CROP.below(), Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));
        h.setBlock(CROP, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,4));
        h.setBlock(CHEST, Blocks.CHEST);
        Villager v = h.spawnWithNoFreeWill(EntityTypes.VILLAGER,3,1,2);
        Scene scene = new Scene(h,v,CROP,CHEST);
        ActionReceipt immature = scene.poll(scene.start(Operation.HARVEST_WHEAT,scene.at(CROP)));
        h.assertTrue(immature.reason()==Reason.TARGET_INVALID && h.getBlockState(CROP).getValue(CropBlock.AGE)==4,
                "Immature wheat mutated");
        h.setBlock(CROP, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));
        ActionHandle pending = scene.start(Operation.HARVEST_WHEAT,scene.at(CROP));
        var rules = h.getLevel().getGameRules();
        boolean previous = rules.get(GameRules.MOB_GRIEFING);
        try {
            rules.set(GameRules.MOB_GRIEFING,false,h.getLevel().getServer());
            ActionReceipt denied = scene.poll(pending);
            h.assertTrue(denied.reason()==Reason.AUTHORITY_DENIED
                    && h.getBlockState(CROP).getValue(CropBlock.AGE)==7,
                    "Existing handle bypassed current rule");
        } finally {
            rules.set(GameRules.MOB_GRIEFING,previous,h.getLevel().getServer());
            scene.gateway.releaseRun(scene.runId);
        }
        h.assertTrue(!AiVillages.controls(v),"Control held after terminal run release");
        h.succeed();
    }

    @GameTest(maxTicks=300,padding=16)
    public void workerWalksIntoReachThenReturnsToVanilla(GameTestHelper h) {
        floor(h);
        h.setBlock(CROP.below(), Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));
        h.setBlock(CROP, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));
        h.setBlock(CHEST, Blocks.CHEST);
        Villager v = h.spawn(EntityTypes.VILLAGER,6,1,6);
        Scene scene = new Scene(h,v,CROP,CHEST);
        ActionHandle move = scene.start(Operation.MOVE,scene.at(CROP));
        BlockPos target = h.absolutePos(CROP);
        h.succeedWhen(()->{
            ActionReceipt status = scene.gateway.poll(move);
            h.assertTrue(status.reason()==null, "Movement failed: "+status.reason()
                    +" worker="+v.blockPosition()+" target="+target
                    +" travel="+scene.ledger.snapshot().getOrDefault(Kind.TRAVEL_BLOCKS,0L));
            h.assertTrue(status.terminal(), "Waiting for physical route");
            h.assertTrue(v.distanceToSqr(target.getX()+0.5,target.getY()+0.5,target.getZ()+0.5)<=6.25,
                    "Movement result without reach");
            h.assertTrue(scene.ledger.snapshot().getOrDefault(Kind.TRAVEL_BLOCKS,0L)>0,
                    "No physical travel recorded");
            scene.gateway.releaseRun(scene.runId);
            h.assertTrue(!AiVillages.controls(v),"Vanilla brain was not released");
        });
    }

    @GameTest(maxTicks=300,padding=16)
    public void workerFollowsObstacleDetourAwayFromTarget(GameTestHelper h) {
        floor(h);
        h.setBlock(CROP.below(), Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));
        h.setBlock(CROP, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));
        h.setBlock(CHEST, Blocks.CHEST);
        // The solid wall has its only supported opening at z=7. The villager
        // starts at z=2 and must first travel away from the crop to get around it.
        for (int z=0; z<=6; z++) for (int y=1; y<=2; y++)
            h.setBlock(4,y,z,Blocks.STONE);
        Villager v = h.spawn(EntityTypes.VILLAGER,6,1,2);
        Scene scene = new Scene(h,v,CROP,CHEST);
        ActionHandle action = scene.start(Operation.MOVE,scene.at(CROP));
        BlockPos target = h.absolutePos(CROP);
        boolean[] detoured = {false};
        h.succeedWhen(()->{
            if (v.blockPosition().getZ() >= h.absolutePos(new BlockPos(0,0,6)).getZ())
                detoured[0] = true;
            ActionReceipt state = scene.gateway.poll(action);
            h.assertTrue(state.reason()==null,"Detour failed: "+state.reason()+" actor="
                    +v.position()+" target="+target+" calls="+scene.world.lastNavigationSummary());
            h.assertTrue(state.terminal(),"Following physical detour");
            h.assertTrue(detoured[0] && v.distanceToSqr(target.getX()+0.5,
                            target.getY()+0.5,target.getZ()+0.5)<=6.25,
                    "Actor did not travel around the wall into interaction reach");
            scene.gateway.releaseRun(scene.runId);
            h.assertTrue(!AiVillages.controls(v),"Detour retained run control");
        });
    }

    @GameTest(maxTicks=300,padding=16)
    public void translatedQuarterTurnObstacleDetour(GameTestHelper h) {
        // Fixed quarter-turn (x,z) -> (9-z,x+1) of the preceding obstacle,
        // translated two cells right and one cell forward in a 10x10 arena.
        for (int x=0; x<10; x++) for (int z=0; z<10; z++)
            h.setBlock(x,0,z,Blocks.STONE);
        BlockPos crop = new BlockPos(7,1,3); // rotation of (2,1,2)
        BlockPos chest = new BlockPos(9,1,3); // rotation of (2,1,0)
        h.setBlock(crop.below(),Blocks.FARMLAND.defaultBlockState()
                .setValue(FarmlandBlock.MOISTURE,7));
        h.setBlock(crop,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));
        h.setBlock(chest,Blocks.CHEST);
        for (int x=3; x<=9; x++) for (int y=1; y<=2; y++)
            h.setBlock(x,y,5,Blocks.STONE); // rotated solid wall, gap at x=2
        Villager v = h.spawn(EntityTypes.VILLAGER,7,1,7);
        Scene scene = new Scene(h,v,crop,chest);
        ActionHandle action = scene.start(Operation.MOVE,scene.at(crop));
        BlockPos target = h.absolutePos(crop);
        boolean[] detoured = {false};
        h.succeedWhen(()->{
            if (v.blockPosition().getX() <= h.absolutePos(new BlockPos(2,0,0)).getX())
                detoured[0] = true;
            ActionReceipt state = scene.gateway.poll(action);
            h.assertTrue(state.reason()==null,"Quarter-turn route failed: "+state.reason()
                    +" actor="+v.position()+" calls="+scene.world.lastNavigationSummary());
            h.assertTrue(state.terminal(),"Following quarter-turn obstacle detour");
            h.assertTrue(detoured[0] && v.distanceToSqr(target.getX()+0.5,
                            target.getY()+0.5,target.getZ()+0.5)<=6.25,
                    "Quarter-turn fixture did not reach through the gap");
            scene.gateway.releaseRun(scene.runId);
            h.assertTrue(!AiVillages.controls(v),"Quarter-turn run retained control");
        });
    }

    @GameTest(maxTicks=60,padding=16)
    public void distanceAloneAndLineOfSightAloneDoNotSucceed(GameTestHelper h) {
        floor(h);
        h.setBlock(CROP.below(), Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));
        h.setBlock(CROP, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));
        h.setBlock(CHEST, Blocks.CHEST);
        for (int y=1; y<=2; y++) h.setBlock(3,y,2,Blocks.STONE);
        Villager near = h.spawn(EntityTypes.VILLAGER,4,1,2);
        Scene blockedSight = new Scene(h,near,CROP,CHEST);
        ActionHandle nearAction = blockedSight.start(Operation.MOVE,blockedSight.at(CROP));
        BlockPos target = h.absolutePos(CROP);
        h.assertTrue(near.distanceToSqr(target.getX()+0.5,target.getY()+0.5,
                target.getZ()+0.5)<=6.25,"Fixture actor is outside the reach radius");
        h.assertTrue(!blockedSight.gateway.poll(nearAction).terminal(),
                "Distance without line of sight falsely completed MOVE");
        blockedSight.gateway.cancel(nearAction);

        Villager far = h.spawn(EntityTypes.VILLAGER,2,1,6);
        Scene beyondReach = new Scene(h,far,CROP,CHEST);
        ActionHandle farAction = beyondReach.start(Operation.MOVE,beyondReach.at(CROP));
        h.assertTrue(far.distanceToSqr(target.getX()+0.5,target.getY()+0.5,
                target.getZ()+0.5)>6.25,"Fixture actor starts inside the reach radius");
        h.assertTrue(!beyondReach.gateway.poll(farAction).terminal(),
                "Line of sight/accepted path without distance falsely completed MOVE");
        beyondReach.gateway.cancel(farAction);
        h.assertTrue(!AiVillages.controls(near) && !AiVillages.controls(far),
                "Cancellation retained physical control");
        h.succeed();
    }

    @GameTest(maxTicks=300,padding=16)
    public void blockedApproachesTerminateAfterFinitePathWork(GameTestHelper h) {
        floor(h);
        h.setBlock(CROP.below(), Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));
        h.setBlock(CROP, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));
        h.setBlock(CHEST, Blocks.CHEST);
        for (int x=5; x<=7; x++) for (int z=5; z<=7; z++) {
            if (x==6 && z==6) continue;
            for (int y=1; y<=2; y++) h.setBlock(x,y,z,Blocks.STONE);
        }
        Villager v = h.spawn(EntityTypes.VILLAGER,6,1,6);
        Scene scene = new Scene(h,v,CROP,CHEST);
        ActionHandle action = scene.start(Operation.MOVE,scene.at(CROP));
        h.succeedWhen(()->{
            ActionReceipt state = scene.gateway.poll(action);
            h.assertTrue(!state.terminal() || state.reason()==Reason.TARGET_UNAVAILABLE,
                    "Blocked route returned unexpected terminal reason: "+state.reason());
            h.assertTrue(state.terminal(),"Exhausting the finite approach samples");
            FabricGatewayWorld.NavigationSummary summary = scene.world.lastNavigationSummary();
            h.assertTrue(summary!=null && summary.actor().equals(v.getUUID())
                            && summary.attempts()<=8 && summary.calls()<=8
                            && summary.reason().equals("approaches-exhausted")
                            && v.getNavigation().isDone(),
                    "Obstruction did not terminate with bounded path work: "+summary);
            scene.gateway.releaseRun(scene.runId);
            h.assertTrue(!AiVillages.controls(v),"Obstruction retained run control");
        });
    }

    @GameTest(maxTicks=100,padding=16)
    public void cancelledNavigationCannotRecomputeIntoTheNextRun(GameTestHelper h) {
        floor(h);
        h.setBlock(CROP.below(), Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));
        h.setBlock(CROP, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));
        h.setBlock(CHEST, Blocks.CHEST);
        Villager v = h.spawn(EntityTypes.VILLAGER,6,1,6);
        Scene first = new Scene(h,v,CROP,CHEST);
        ActionHandle action = first.start(Operation.MOVE,first.at(CROP));
        Scene[] next = {null};
        ActionReceipt[] cancelled = {null};
        long[] cancelledTick = {0};
        h.succeedWhen(()->{
            var accessor = (PathNavigationAccessor)v.getNavigation();
            if (cancelled[0] == null) {
                ActionReceipt started = first.gateway.poll(action);
                h.assertTrue(!started.terminal(),"Route terminated before a native path started: "
                        +started.reason());
                h.assertTrue(v.getNavigation().getPath()!=null,
                        "Waiting for Minecraft to accept a route");
                var ownedPath = v.getNavigation().getPath();
                v.getNavigation().recomputePath();
                v.getNavigation().recomputePath();
                h.assertTrue(v.getNavigation().getPath()==ownedPath
                                && !accessor.cognitivecraft$getHasDelayedRecomputation(),
                        "Vanilla recomputation replaced the gateway's budgeted path");
                // Simulate delayed work queued before gateway custody or by an
                // older runtime: cancellation must still clear that native flag.
                accessor.cognitivecraft$setHasDelayedRecomputation(true);
                cancelled[0] = first.gateway.cancel(action);
                cancelledTick[0] = h.getLevel().getGameTime();
                h.assertTrue(cancelled[0].reason()==Reason.CANCELLED
                                && !AiVillages.controls(v) && v.getNavigation().getPath()==null
                                && !accessor.cognitivecraft$getHasDelayedRecomputation(),
                        "Cancel did not stop the path, pending replan and worker custody");
                // The next run suppresses vanilla brain tasks while native
                // navigation ticks any old delayed work for over 20 ticks.
                next[0] = new Scene(h,v,CROP,CHEST);
                ActionReceipt inventory = next[0].poll(next[0].start(Operation.OBSERVE_INVENTORY,
                        Map.of("actor",new ActorValue(next[0].actor))));
                h.assertTrue(inventory.reason()==null && AiVillages.gatewayControls(v),
                        "Second run did not acquire exclusive control");
            }
            h.assertTrue(h.getLevel().getGameTime()-cancelledTick[0]>=25,
                    "Waiting for native delayed-recompute interval");
            h.assertTrue(v.getNavigation().getPath()==null
                            && !accessor.cognitivecraft$getHasDelayedRecomputation(),
                    "Old delayed recomputation revived cancelled navigation");
            h.assertTrue(first.gateway.poll(action).equals(cancelled[0]),
                    "Cancelled handle resumed after late native work");
            next[0].gateway.releaseRun(next[0].runId);
            h.assertTrue(!AiVillages.controls(v),"Vanilla control was not restored");
            var vanillaPath = v.getNavigation().createPath(h.absolutePos(CROP),0);
            h.assertTrue(vanillaPath!=null && v.getNavigation().moveTo(vanillaPath,0.65),
                    "Released worker could not resume native navigation");
            v.getNavigation().recomputePath();
            v.getNavigation().recomputePath();
            h.assertTrue(accessor.cognitivecraft$getHasDelayedRecomputation(),
                    "Unowned vanilla recomputation was suppressed");
            v.getNavigation().stop();
            accessor.cognitivecraft$setHasDelayedRecomputation(false);
        });
    }
}
