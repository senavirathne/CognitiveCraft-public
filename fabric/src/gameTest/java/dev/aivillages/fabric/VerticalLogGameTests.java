package dev.aivillages.fabric;

import java.time.Clock;
import java.util.*;
import dev.aivillages.core.kernel.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Native navigation and real loot/custody; no actor or produced-item pose edits. */
public final class VerticalLogGameTests {
    @GameTest(maxTicks=1600,padding=16)
    public void threeHighOakColumnUsesGroundedReach(GameTestHelper h) {
        acquisition(h,false,List.of(new BlockPos(5,1,3),new BlockPos(5,2,3),new BlockPos(5,3,3)),false,false);
    }
    @GameTest(maxTicks=1600,padding=16)
    public void suspendedOakPairUsesLowerStandingFloor(GameTestHelper h) {
        acquisition(h,false,List.of(new BlockPos(7,2,6),new BlockPos(7,3,6)),false,false);
    }
    @GameTest(maxTicks=1600,padding=16)
    public void fourHighOakColumnRequiresOrdinaryStep(GameTestHelper h) {
        acquisition(h,false,List.of(new BlockPos(5,1,3),new BlockPos(5,2,3),new BlockPos(5,3,3),new BlockPos(5,4,3)),true,false);
    }
    @GameTest(maxTicks=1600,padding=16)
    public void elevatedBirchPairRetainsExactSpecies(GameTestHelper h) {
        acquisition(h,true,List.of(new BlockPos(6,2,5),new BlockPos(6,3,5)),false,false);
    }
    @GameTest(maxTicks=1600,padding=16)
    public void tooHighLogCannotInventStandingSupport(GameTestHelper h) {
        acquisition(h,false,List.of(new BlockPos(7,4,5)),false,true);
    }
    @GameTest(maxTicks=1600,padding=16)
    public void blockedStandingColumnsCannotBypassCollision(GameTestHelper h) {
        acquisition(h,false,List.of(new BlockPos(7,2,5)),true,true);
    }

    private void acquisition(GameTestHelper h,boolean birch,List<BlockPos> relative,boolean step,boolean blocked) {
        for(int x=0;x<12;x++)for(int z=0;z<10;z++)h.setBlock(x,0,z,Blocks.STONE);
        var item=birch?ResourceDelivery.BIRCH_LOG:ResourceDelivery.OAK_LOG;
        var nativeItem=FabricResourceItems.item(item);var block=FabricResourceItems.log(item);
        for(var p:relative)h.setBlock(p,block);
        if(step && !blocked) {h.setBlock(4,1,3,Blocks.STONE);h.setBlock(4,1,4,Blocks.STONE);}
        if(step && blocked) {
            var p=relative.getFirst();
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(Math.abs(dx)+Math.abs(dz)==1)
                for(int y=1;y<=5;y++)h.setBlock(p.getX()+dx,y,p.getZ()+dz,Blocks.STONE);
        }
        h.setBlock(10,1,7,Blocks.CHEST);
        var foreignPos=h.absolutePos(new BlockPos(9,1,2));h.setBlock(9,1,2,birch?Blocks.OAK_LOG:Blocks.BIRCH_LOG);
        var v=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,1,2,3);v.setNoAi(false);
        var positions=relative.stream().map(h::absolutePos).toList();
        var c=h.absolutePos(new BlockPos(10,1,7));
        BootstrapGameTests.forceFixtureChunks(h,h.absolutePos(BlockPos.ZERO));
        String dim=h.getLevel().dimension().identifier().toString();
        var actor=new ActorRef(UUID.randomUUID(),v.getUUID(),dim);
        int minX=positions.stream().mapToInt(BlockPos::getX).min().orElseThrow(),maxX=positions.stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int minY=positions.stream().mapToInt(BlockPos::getY).min().orElseThrow(),maxY=positions.stream().mapToInt(BlockPos::getY).max().orElseThrow();
        int minZ=positions.stream().mapToInt(BlockPos::getZ).min().orElseThrow(),maxZ=positions.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
        var source=new Cuboid(dim,minX,minY,minZ,maxX,maxY,maxZ);var destination=new ContainerRef(dim,c.getX(),c.getY(),c.getZ());
        var principal=UUID.randomUUID();var context=new TrustedContext(new PrincipalRef(principal),new ScopeRef(UUID.randomUUID(),principal));
        var capability=birch?BirchLogDelivery.ID:OakLogDelivery.ID;
        var request=new ValidatedRequest(new CapabilityRequest(capability,Map.of("actor",new ActorValue(actor),"source",new AreaValue(source),
                "destination",new ContainerValue(destination),"amount",new IntValue(positions.size()))),context,new ObservationRef(UUID.randomUUID(),0,dim));
        var world=new FabricGatewayWorld(h.getLevel());
        var gateway=new SurvivalGateway(world,(a,e,o)->o.equals(context),new SurvivalGateway.Limits(1,64,64,16,256,1,200,32),Clock.systemUTC());
        var budget=new Budgets.Ledger(OakLogDelivery.executionLimits(System.currentTimeMillis()).total(),Clock.systemUTC());
        UUID run=UUID.randomUUID();var correlation=new RunCorrelation(run,new ArtifactRef(capability,"d".repeat(64)),null);
        Container chest=(Container)h.getLevel().getBlockEntity(c);
        chest.setItem(0,new ItemStack(Items.STONE,11));v.getInventory().setItem(0,new ItemStack(nativeItem,2));
        var operations=List.of(birch?Operation.APPROACH_BIRCH_LOG:Operation.APPROACH_OAK_LOG,
                birch?Operation.HARVEST_NEXT_BIRCH_LOG:Operation.HARVEST_NEXT_OAK_LOG,
                birch?Operation.APPROACH_BIRCH_LOG:Operation.APPROACH_OAK_LOG,
                birch?Operation.PICKUP_TRACKED_BIRCH_LOGS:Operation.PICKUP_TRACKED_OAK_LOGS,
                Operation.MOVE_TO_DESTINATION,birch?Operation.TRANSFER_BIRCH_LOGS:Operation.TRANSFER_OAK_LOGS);
        int[] phase={0},completed={0};long[] harvested={-1};ActionHandle[] handle={null};
        boolean[] failed={false},stepped={false};var receipts=new ArrayList<ResourceDelivery.Receipt>();
        h.succeedWhen(()->{
            if(!v.onGround() || !world.loadedForDiscovery(positions.getFirst())) {h.assertTrue(false,"Waiting for native loaded landing");return;}
            if(v.getY()>positions.getFirst().getY()+0.5)stepped[0]=true;
            if(completed[0]==positions.size()) {
                h.assertTrue(ResourceDelivery.completed(request,run,List.of(),receipts),"Incomplete real receipt chain");
                h.assertTrue(positions.stream().allMatch(p->h.getLevel().getBlockState(p).isAir()),"Selected native log remains");
                h.assertTrue(chest.countItem(nativeItem)==positions.size() && v.getInventory().countItem(nativeItem)==2,"Physical stock/retained custody mismatch");
                h.assertTrue(chest.countItem(Items.STONE)==11 && h.getLevel().getBlockState(foreignPos).is(birch?Blocks.OAK_LOG:Blocks.BIRCH_LOG),"Foreign stock or species changed");
                for(var stage:List.of(ResourceDelivery.Stage.HARVEST,ResourceDelivery.Stage.PICKUP,ResourceDelivery.Stage.DEPOSIT))
                    h.assertTrue(receipts.stream().filter(r->r.stage()==stage).mapToLong(ResourceDelivery.Receipt::quantity).sum()==positions.size(),"Incorrect stage attribution "+stage);
                for(var r:receipts)for(var id:r.dropIds())h.assertTrue(!(h.getLevel().getEntity(id) instanceof ItemEntity),"Attributable drop remains after delivery");
                h.assertTrue(!step || stepped[0],"Required ordinary elevation movement was not witnessed");
                gateway.releaseRun(run);
                System.out.println("A01_VERTICAL_NATIVE_PASS item="+item+" source="+source+" harvested="+positions.size()+" picked="+positions.size()+" deposited="+positions.size()+" retained=2 stepped="+stepped[0]+" receipts="+receipts);
                return;
            }
            Operation op=operations.get(phase[0]);
            if(phase[0]==2 && h.getLevel().getGameTime()-harvested[0]<20) {h.assertTrue(false,"Waiting for actual native pickup delay");return;}
            if(handle[0]==null) {
                var args=new HashMap<String,Value>();for(var parameter:op.signature().parameters())args.put(parameter.name(),parameter.name().equals("amount")?new IntValue(1):request.request().arguments().get(parameter.name()));
                handle[0]=gateway.start(request,correlation,op.requirement(),args,budget);
            }
            var result=gateway.poll(handle[0]);
            if(result.terminal()) {
                if(blocked) {
                    h.assertTrue(phase[0]==0 && result.reason()==Outcomes.Reason.TARGET_UNAVAILABLE,"Unreachable target did not fail at physical approach");
                    h.assertTrue(receipts.isEmpty() && result.resourceReceipts().isEmpty() && positions.stream().allMatch(p->h.getLevel().getBlockState(p).is(block)),"Blocked target mutated or fabricated receipts");
                    h.assertTrue(chest.countItem(nativeItem)==0 && v.getInventory().countItem(nativeItem)==2,"Blocked approach altered custody");
                    gateway.releaseRun(run);
                    System.out.println("A01_VERTICAL_BOUNDARY_PASS source="+source+" collision="+step+" harvested=0 picked=0 deposited=0 retained=2 navigation="+world.lastNavigationSummary());
                    return;
                }
                if(result.reason()!=null && !failed[0]) {
                    failed[0]=true;System.out.println("A01_VERTICAL_NATIVE_FAILURE op="+op+" reason="+result.reason()+" completed="+completed[0]+" actor="+v.position()+" nativeInventory="+v.getInventory().countItem(nativeItem)+" chest="+chest.countItem(nativeItem)+" navigation="+world.lastNavigationSummary());
                }
                h.assertTrue(result.reason()==null,"Native vertical operation failed "+op+" "+result.reason());
                receipts.addAll(result.resourceReceipts());
                if(phase[0]==0)h.assertTrue(v.onGround(),"Approach invented an airborne standing position");
                if(phase[0]==1)harvested[0]=h.getLevel().getGameTime();
                handle[0]=null;phase[0]++;
                if(phase[0]==operations.size()){phase[0]=0;completed[0]++;}
            }
            h.assertTrue(false,"Waiting for real native acquisition stages");
        });
    }
}
