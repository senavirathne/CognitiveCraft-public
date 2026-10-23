package dev.aivillages.fabric;

import java.util.UUID;
import dev.aivillages.core.kernel.Contracts.ActorRef;
import dev.aivillages.core.kernel.Outcomes.Reason;
import dev.aivillages.core.kernel.ResourceDelivery;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Independent native pickup guards. Fixture items are born once and then obey normal physics. */
public final class DroppedLogGameTests {
    @GameTest(maxTicks=400,padding=16)
    public void settledFloorDropUsesActualEntityReach(GameTestHelper h) {probe(h,"floor");}
    @GameTest(maxTicks=400,padding=16)
    public void dropBesideChestRetainsClearPhysicalRay(GameTestHelper h) {probe(h,"chest");}
    @GameTest(maxTicks=400,padding=16)
    public void movingDropRetainsUuidAcrossCells(GameTestHelper h) {probe(h,"moving");}
    @GameTest(maxTicks=400,padding=16)
    public void enclosedDropCannotBeCollected(GameTestHelper h) {probe(h,"blocked");}
    @GameTest(maxTicks=400,padding=16)
    public void fullInventoryCannotConsumeDrop(GameTestHelper h) {probe(h,"capacity");}
    @GameTest(maxTicks=400,padding=16)
    public void removedDropCannotBeCollectedAfterNavigationStarts(GameTestHelper h) {probe(h,"removed");}
    @GameTest(maxTicks=400,padding=16)
    public void nativePickupDelayPreservesActualDrop(GameTestHelper h) {probe(h,"delay");}

    private void probe(GameTestHelper h,String variant) {
        for(int x=0;x<12;x++)for(int z=0;z<10;z++) {
            h.setBlock(x,0,z,Blocks.STONE);
            for(int y=1;y<=5;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        boolean distant=variant.equals("moving") || variant.equals("removed") || variant.equals("blocked");
        var v=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,distant?1:3,2,distant?3:4);v.setNoAi(false);
        if(variant.equals("chest"))h.setBlock(4,1,3,Blocks.CHEST);
        if(variant.equals("blocked")) {
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(Math.abs(dx)+Math.abs(dz)==1)
                for(int y=1;y<=2;y++)h.setBlock(7+dx,y,5+dz,Blocks.STONE);
            h.setBlock(7,2,5,Blocks.STONE);
        }
        BootstrapGameTests.forceFixtureChunks(h,h.absolutePos(BlockPos.ZERO));
        String dim=h.getLevel().dimension().identifier().toString();
        var actor=new ActorRef(UUID.randomUUID(),v.getUUID(),dim);var run=UUID.randomUUID();
        var world=new FabricGatewayWorld(h.getLevel());
        if(variant.equals("capacity")) {
            for(int i=0;i<v.getInventory().getContainerSize();i++)v.getInventory().setItem(i,new ItemStack(Items.STONE,64));
        } else v.getInventory().setItem(0,new ItemStack(Items.OAK_LOG,2));
        ItemEntity[] drop={null};long[] born={-1};BlockPos[] initial={null};
        int[] navigationWork={0};boolean[] movedCell={false},removed={false},reportedFailure={false};
        h.succeedWhen(()->{
            if(!world.holds(actor,run))h.assertTrue(world.acquire(actor,run),"Native probe control unavailable");
            if(!v.onGround()) {h.assertTrue(false,"Waiting for ordinary actor landing");return;}
            if(drop[0]==null) {
                var base=h.absolutePos(new BlockPos(distant?7:3,1,distant?5:3));
                double x=distant?base.getX()+.5:base.getX()+.9375;
                double z=distant?base.getZ()+.5:base.getZ()+.1607688054424;
                double y=base.getY()+(variant.equals("moving")?1.25:.05);
                drop[0]=new ItemEntity(h.getLevel(),x,y,z,new ItemStack(Items.OAK_LOG,1));
                drop[0].setThrower(v);drop[0].setTarget(drop[0].getUUID());
                drop[0].setDeltaMovement(variant.equals("moving")?.18:0,0,variant.equals("moving")?.12:0);
                if(variant.equals("delay"))drop[0].setDefaultPickUpDelay();
                h.assertTrue(h.getLevel().addFreshEntity(drop[0]),"Native fixture drop not registered");
                born[0]=h.getLevel().getGameTime();initial[0]=drop[0].blockPosition();
            }
            if(variant.equals("delay")) {
                h.assertTrue(drop[0].hasPickUpDelay(),"Native pickup delay was not actually active");
                var result=world.resourcePickup(actor,drop[0].getUUID(),1,ResourceDelivery.OAK_LOG);
                h.assertTrue(result.collected()==0 && result.remaining()==1 && result.reason()==Reason.TARGET_UNAVAILABLE,
                        "Native pickup delay was bypassed");
                h.assertTrue(drop[0].isAlive() && v.getInventory().countItem(Items.OAK_LOG)==2,"Delayed item was consumed");
                world.release(actor,run);System.out.println("A02_NATIVE_BOUNDARY variant="+variant+" result="+result);return;
            }
            if(!drop[0].blockPosition().equals(initial[0]))movedCell[0]=true;
            if(h.getLevel().getGameTime()-born[0]<20) {h.assertTrue(false,"Waiting for native item physics");return;}
            var movement=world.approachResourceDrop(actor,drop[0].getUUID(),1,1,ResourceDelivery.OAK_LOG);
            navigationWork[0]+=movement.chargedWork();
            if(variant.equals("removed") && !removed[0] && navigationWork[0]>0) {
                drop[0].discard();removed[0]=true;h.assertTrue(false,"Destroyed fixture after actual path submission");return;
            }
            if(variant.equals("removed") && removed[0]) {
                var result=world.resourcePickup(actor,drop[0].getUUID(),1,ResourceDelivery.OAK_LOG);
                h.assertTrue(movement.impossible() && result.reason()==Reason.STALE_OBSERVATION && result.collected()==0,
                        "Removed entity was treated as a collectible item");
                h.assertTrue(v.getInventory().countItem(Items.OAK_LOG)==2,"Removed drop was credited");
                world.release(actor,run);System.out.println("A02_NATIVE_BOUNDARY variant="+variant+" result="+result);return;
            }
            if(variant.equals("blocked")) {
                if(!movement.impossible()) {h.assertTrue(!movement.reached(),"Enclosure allowed a false arrival");h.assertTrue(false,"Waiting for bounded blocked route");return;}
                var result=world.resourcePickup(actor,drop[0].getUUID(),1,ResourceDelivery.OAK_LOG);
                h.assertTrue(result.collected()==0 && result.reason()==Reason.TARGET_UNAVAILABLE
                        && drop[0].isAlive() && drop[0].getItem().getCount()==1
                        && v.getInventory().countItem(Items.OAK_LOG)==2,"Blocked item was consumed or credited");
                world.release(actor,run);System.out.println("A02_NATIVE_BOUNDARY variant="+variant+" result="+result+" navigation="+world.lastNavigationSummary());return;
            }
            if(movement.impossible() && !reportedFailure[0]) {
                reportedFailure[0]=true;
                System.out.println("A02_NATIVE_FAILURE variant="+variant+" stage=approach actor="+v.position()
                        +" drop="+drop[0].position()+" navigation="+world.lastNavigationSummary());
            }
            h.assertTrue(!movement.impossible(),"Reachable native drop approach failed "+variant);
            if(!movement.reached()) {h.assertTrue(false,"Waiting for ordinary pickup approach");return;}
            var result=world.resourcePickup(actor,drop[0].getUUID(),1,ResourceDelivery.OAK_LOG);
            if(variant.equals("capacity")) {
                h.assertTrue(result.collected()==0 && result.reason()==Reason.TARGET_UNAVAILABLE
                        && drop[0].isAlive() && drop[0].getItem().getCount()==1
                        && v.getInventory().countItem(Items.STONE)==64*v.getInventory().getContainerSize(),
                        "Full inventory consumed or invented pickup capacity");
            } else {
                if(result.reason()!=null && !reportedFailure[0]) {
                    reportedFailure[0]=true;System.out.println("A02_NATIVE_FAILURE variant="+variant+" stage=pickup result="+result
                            +" actor="+v.position()+" drop="+drop[0].position());
                }
                h.assertTrue(result.reason()==null && result.collected()==1 && result.remaining()==0
                        && !drop[0].isAlive() && v.getInventory().countItem(Items.OAK_LOG)==3,
                        "Actual reachable drop was not physically collected "+variant+" "+result);
                h.assertTrue(!variant.equals("moving") || movedCell[0] && navigationWork[0]>0,
                        "Moving drop did not cross a cell and use ordinary native movement");
            }
            world.release(actor,run);
            System.out.println("A02_NATIVE_PASS variant="+variant+" originalCell="+initial[0]+" movedCell="+movedCell[0]
                    +" nativeNavigationWork="+navigationWork[0]+" pickup="+result);
        });
    }
}
