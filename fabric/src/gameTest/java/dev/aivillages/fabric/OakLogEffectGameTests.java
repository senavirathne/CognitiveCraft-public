package dev.aivillages.fabric;

import java.time.Clock;
import java.util.*;
import dev.aivillages.core.kernel.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;

/** Actual installed survival/loot, per-entity merge isolation and component-preserving physical slots. */
public final class OakLogEffectGameTests {
    @GameTest(maxTicks=1600,padding=16)
    public void actualBareHandTimingLootAndComponentPreservingCustody(GameTestHelper h) {
        for(int x=0;x<10;x++)for(int z=0;z<8;z++)h.setBlock(x,0,z,Blocks.STONE);
        h.setBlock(3,1,3,Blocks.OAK_LOG);h.setBlock(7,1,3,Blocks.CHEST);h.setBlock(4,1,5,Blocks.BIRCH_LOG);
        var v=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,1,2,3);v.setNoAi(false);
        h.setBlock(3,1,6,Blocks.OAK_LOG);h.setBlock(3,2,6,Blocks.STONE);
        var probe=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,2,2,6);
        var probeLog=h.absolutePos(new BlockPos(3,1,6));
        var p=h.absolutePos(new BlockPos(3,1,3));var c=h.absolutePos(new BlockPos(7,1,3));
        BootstrapGameTests.forceFixtureChunks(h,h.absolutePos(BlockPos.ZERO));
        String dim=h.getLevel().dimension().identifier().toString();var actor=new ActorRef(UUID.randomUUID(),v.getUUID(),dim);
        var probeActor=new ActorRef(UUID.randomUUID(),probe.getUUID(),dim);
        var source=new Cuboid(dim,p.getX(),p.getY(),p.getZ(),p.getX(),p.getY(),p.getZ());
        var box=new ContainerRef(dim,c.getX(),c.getY(),c.getZ());var id=UUID.randomUUID();
        var owner=new TrustedContext(new PrincipalRef(id),new ScopeRef(UUID.randomUUID(),id));
        var request=new ValidatedRequest(new CapabilityRequest(OakLogDelivery.ID,Map.of("actor",new ActorValue(actor),"source",new AreaValue(source),
                "destination",new ContainerValue(box),"amount",new IntValue(1))),owner,new ObservationRef(UUID.randomUUID(),0,dim));
        var world=new FabricGatewayWorld(h.getLevel());
        var gateway=new SurvivalGateway(world,(a,e,o)->o.equals(owner),new SurvivalGateway.Limits(1,64,64,16,256,1,200,32),Clock.systemUTC());
        var budget=new Budgets.Ledger(OakLogDelivery.executionLimits(System.currentTimeMillis()).total(),Clock.systemUTC());
        UUID run=UUID.randomUUID();var correlation=new RunCorrelation(run,new ArtifactRef(OakLogDelivery.ID,"d".repeat(64)),null);
        var custom=new ItemStack(Items.OAK_LOG,3);custom.set(DataComponents.CUSTOM_NAME,Component.literal("retain-components"));
        var container=(Container)h.getLevel().getBlockEntity(c);container.setItem(0,custom.copy());container.setItem(1,new ItemStack(Items.OAK_LOG,63));
        for(int slot=2;slot<container.getContainerSize();slot++)container.setItem(slot,new ItemStack(Items.STONE,64));
        v.getInventory().setItem(0,new ItemStack(Items.OAK_LOG,3));
        var unrelated=new ItemEntity(h.getLevel(),p.getX()+.5,p.getY()+.25,p.getZ()+.5,new ItemStack(Items.OAK_LOG,17));
        h.getLevel().addFreshEntity(unrelated);
        int[] phase={0};long[] first={-1},harvested={-1};ActionHandle[] handle={null};var receipts=new ArrayList<ResourceDelivery.Receipt>();
        boolean[] airborneDeferred={false},partialSurfaceDrop={false},endpointRefined={false};
        var operations=List.of(Operation.APPROACH_OAK_LOG,Operation.HARVEST_NEXT_OAK_LOG,Operation.APPROACH_OAK_LOG,Operation.PICKUP_TRACKED_OAK_LOGS,
                Operation.MOVE_TO_DESTINATION,Operation.TRANSFER_OAK_LOGS);
        h.succeedWhen(() -> {
            if(phase[0]>=2 && phase[0]<=3) {
                for(var receipt:receipts)if(receipt.stage()==ResourceDelivery.Stage.HARVEST)
                    for(var drop:receipt.dropIds()) {
                        var state=world.resourceDrop(drop,ResourceDelivery.OAK_LOG);
                        if(state.status()==ObservationStatus.PRESENT && state.cell().y()==p.getY()-1)
                            partialSurfaceDrop[0]=true;
                    }
            }
            if(phase[0]==0 && !v.onGround()) {
                var movement=world.approachResource(actor,new SurvivalGateway.Cell(dim,p.getX(),p.getY(),p.getZ()),1,ResourceDelivery.OAK_LOG);
                h.assertTrue(!movement.reached() && !movement.impossible() && movement.chargedWork()==0,
                        "Airborne oak approach consumed a ground path or returned a false terminal result");
                airborneDeferred[0]=true;
            }
            if(!v.onGround() || !probe.onGround() || !world.loadedForDiscovery(p)) {h.assertTrue(false,"Waiting for loaded grounded fixture");return;}
            if(phase[0]==0 && handle[0]==null) {
                var centerHit=h.getLevel().clip(new net.minecraft.world.level.ClipContext(probe.getEyePosition(),
                        net.minecraft.world.phys.Vec3.atCenterOf(probeLog),net.minecraft.world.level.ClipContext.Block.COLLIDER,
                        net.minecraft.world.level.ClipContext.Fluid.NONE,probe));
                h.assertTrue(centerHit.getBlockPos().equals(probeLog.above()),"Probe did not witness the actual overhang center occlusion");
                h.assertTrue(world.resourceBreakEligibility(probeActor,new SurvivalGateway.Cell(dim,probeLog.getX(),probeLog.getY(),probeLog.getZ()),
                        ResourceDelivery.OAK_LOG)==null,"Visible lower oak portion was rejected under unchanged native range");
                var cell=new SurvivalGateway.Cell(dim,p.getX(),p.getY(),p.getZ());
                h.assertTrue(world.resourceBlock(cell,ResourceDelivery.OAK_LOG).breakTicks()==60,"Pinned oak survival timing differs");
                h.assertTrue(world.resourceBreakEligibility(actor,cell,ResourceDelivery.OAK_LOG)==Outcomes.Reason.TARGET_UNAVAILABLE,
                        "Distant worker cell was accepted as face-adjacent oak arrival");
                var birch=h.absolutePos(new BlockPos(4,1,5));
                h.assertTrue(!world.resourceBlock(new SurvivalGateway.Cell(dim,birch.getX(),birch.getY(),birch.getZ()),ResourceDelivery.OAK_LOG).eligible(),"Another species entered the oak allowlist");
                v.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(Items.IRON_AXE));
                h.assertTrue(world.resourceBreakEligibility(actor,cell,ResourceDelivery.OAK_LOG)==Outcomes.Reason.TARGET_INVALID,"Equipped tool escaped bare-hand restriction");
                v.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                boolean drops=h.getLevel().getGameRules().get(GameRules.BLOCK_DROPS),grief=h.getLevel().getGameRules().get(GameRules.MOB_GRIEFING);
                try {
                    h.getLevel().getGameRules().set(GameRules.BLOCK_DROPS,false,h.getLevel().getServer());
                    h.assertTrue(world.resourceBreakEligibility(actor,cell,ResourceDelivery.OAK_LOG)==Outcomes.Reason.AUTHORITY_DENIED,"BLOCK_DROPS ignored");
                    h.getLevel().getGameRules().set(GameRules.BLOCK_DROPS,true,h.getLevel().getServer());
                    h.getLevel().getGameRules().set(GameRules.MOB_GRIEFING,false,h.getLevel().getServer());
                    h.assertTrue(world.resourceBreakEligibility(actor,cell,ResourceDelivery.OAK_LOG)==Outcomes.Reason.AUTHORITY_DENIED,"MOB_GRIEFING ignored");
                } finally {
                    h.getLevel().getGameRules().set(GameRules.BLOCK_DROPS,drops,h.getLevel().getServer());
                    h.getLevel().getGameRules().set(GameRules.MOB_GRIEFING,grief,h.getLevel().getServer());
                }
                v.getInventory().setItem(0,custom.copy());h.assertTrue(world.resourceInventory(actor,ResourceDelivery.OAK_LOG).reason()==Outcomes.Reason.TARGET_INVALID,"Actor components erased");
                v.getInventory().setItem(0,new ItemStack(Items.OAK_LOG,3));
            }
            if(phase[0]>=operations.size()) {
                h.assertTrue(airborneDeferred[0],"Falling-worker approach regression did not execute");
                h.assertTrue(partialSurfaceDrop[0],"Natural loot did not exercise the partial-surface observation cell");
                h.assertTrue(endpointRefined[0],"Real native path endpoint refinement did not execute");
                h.assertTrue(OakLogDelivery.completed(request,run,receipts),"Trusted native receipt chain incomplete");
                h.assertTrue(v.getInventory().countItem(Items.OAK_LOG)==3,"Preloaded actor stock spent as output");
                h.assertTrue(container.getItem(1).getCount()==64 && ItemStack.isSameItemSameComponents(container.getItem(0),custom),"Real slots/components changed incorrectly");
                h.assertTrue(unrelated.isAlive() && unrelated.getItem().getCount()==17,"Foreign drops merged or were consumed");
                gateway.releaseRun(run);
                System.out.println("RB001_EFFECTS breakTicks="+(harvested[0]-first[0])+" airborneDeferred="+airborneDeferred[0]+" partialSurfaceDrop="+partialSurfaceDrop[0]+" endpointRefined="+endpointRefined[0]+" overhangVisible=true receiptChain="+receipts+" foreignDrop="+unrelated.getUUID());
                return;
            }
            Operation op=operations.get(phase[0]);
            if(op==Operation.PICKUP_TRACKED_OAK_LOGS && h.getLevel().getGameTime()-harvested[0]<20) {h.assertTrue(false,"Waiting for actual pickup delay");return;}
            if(handle[0]==null) {
                var args=new HashMap<String,Value>();for(var parameter:op.signature().parameters())args.put(parameter.name(),parameter.name().equals("amount") ? new IntValue(1) : request.request().arguments().get(parameter.name()));
                handle[0]=gateway.start(request,correlation,op.requirement(),args,budget);
                if(op==Operation.HARVEST_NEXT_OAK_LOG)first[0]=h.getLevel().getGameTime();
            }
            var result=gateway.poll(handle[0]);
            if(op==Operation.HARVEST_NEXT_OAK_LOG && h.getLevel().getGameTime()-first[0]<60)
                h.assertTrue(h.getLevel().getBlockState(p).is(Blocks.OAK_LOG),"Oak was removed before survival break time");
            if(result.terminal()) {
                h.assertTrue(result.reason()==null,"Oak native effect failed "+op+" "+result.reason());
                if(phase[0]==0) {
                    endpointRefined[0]=world.lastNavigationSummary()!=null && world.lastNavigationSummary().endpointRefined();
                    var motion=v.getDeltaMovement();
                    h.assertTrue(v.onGround() && motion.x*motion.x+motion.z*motion.z<=0.01*0.01
                                    && Math.abs(v.getY()-p.getY())<=0.125,
                            "Standing oak arrival was reported before actual horizontal movement settled");
                }
                receipts.addAll(result.resourceReceipts());if(op==Operation.HARVEST_NEXT_OAK_LOG) {
                    harvested[0]=h.getLevel().getGameTime();
                    // Simulate ordinary hoeing after harvest. The actual produced
                    // entity lands naturally; its position/count is never rewritten.
                    for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)
                        h.getLevel().setBlock(p.offset(dx,-1,dz),Blocks.FARMLAND.defaultBlockState()
                                .setValue(net.minecraft.world.level.block.FarmlandBlock.MOISTURE,7),3);
                }
                handle[0]=null;phase[0]++;
            }
            h.assertTrue(false,"Waiting for complete native oak stages");
        });
    }
}
