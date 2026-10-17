package dev.aivillages.fabric;
import java.util.*;
import dev.aivillages.core.kernel.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;

/** Real BlockItem use, actual held stack, mutation guards and current facility observation. */
public final class PlacementEffectGameTests {
    @GameTest(maxTicks=10000,padding=16)
    public void exactApprovedSiteConsumesOneActualTableAndPreservesNativeCustody(GameTestHelper h){
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)h.setBlock(x,0,z,Blocks.STONE);
        h.setBlock(2,1,2,Blocks.CHEST);var v=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,3,1,3);v.setNoAi(false);
        var sourcePos=h.absolutePos(new BlockPos(2,1,2));var targetPos=h.absolutePos(new BlockPos(4,1,3));BootstrapGameTests.forceFixtureChunks(h,sourcePos);
        String dim=h.getLevel().dimension().identifier().toString();var actor=new ActorRef(UUID.randomUUID(),v.getUUID(),dim);
        UUID principal=UUID.randomUUID();var owner=new TrustedContext(new PrincipalRef(principal),new ScopeRef(UUID.randomUUID(),principal));var run=UUID.randomUUID();
        var source=new ContainerRef(dim,sourcePos.getX(),sourcePos.getY(),sourcePos.getZ());var target=new FacilityRef(dim,targetPos.getX(),targetPos.getY(),targetPos.getZ());
        var request=new CapabilityRequest(CraftingTablePlacement.ID,Map.of("actor",new ActorValue(actor),"source",new ContainerValue(source),"target",new FacilityValue(target),"amount",new IntValue(1)));
        var bound=new ValidatedRequest(request,owner,new ObservationRef(UUID.randomUUID(),0,dim));var world=new FabricGatewayWorld(h.getLevel());Container from=(Container)h.getLevel().getBlockEntity(sourcePos);int[] phase={0};
        h.succeedWhen(()->{
            if(!v.onGround()){h.assertTrue(false,"Waiting for native placement actor");return;}
            if(phase[0]>0)return;
            h.assertTrue(world.acquire(actor,run),"Native placement actor control");from.setItem(0,new ItemStack(Items.CRAFTING_TABLE,3));v.getInventory().setItem(0,new ItemStack(Items.CRAFTING_TABLE,7));
            var initial=world.placementPreflight(bound);h.assertTrue(initial.reason()==null && initial.availableItems()==3,"Known exact approved site");
            h.getLevel().setBlock(targetPos,Blocks.STONE.defaultBlockState(),3);h.assertTrue(world.placementPreflight(bound).reason()==Reason.TARGET_INVALID,"Occupied target accepted");h.getLevel().setBlock(targetPos,Blocks.AIR.defaultBlockState(),3);
            h.assertTrue(world.withdrawTableItem(actor,initial,initial.custodyFingerprint(),initial.sourceFingerprint()).reason()==Reason.STALE_OBSERVATION,"Target replace-and-restore accepted");
            h.getLevel().setBlock(targetPos.below(),Blocks.AIR.defaultBlockState(),3);h.assertTrue(world.placementPreflight(bound).reason()==Reason.TARGET_INVALID,"Unsupported site accepted");h.getLevel().setBlock(targetPos.below(),Blocks.STONE.defaultBlockState(),3);
            h.getLevel().setBlock(targetPos,Blocks.WATER.defaultBlockState(),3);h.assertTrue(world.placementPreflight(bound).reason()==Reason.TARGET_INVALID,"Fluid target accepted");h.getLevel().setBlock(targetPos,Blocks.AIR.defaultBlockState(),3);
            var rules=h.getLevel().getGameRules();boolean grief=rules.get(GameRules.MOB_GRIEFING);
            try{rules.set(GameRules.MOB_GRIEFING,false,h.getLevel().getServer());h.assertTrue(world.placementPreflight(bound).reason()==Reason.AUTHORITY_DENIED,"Placement ignored game rule");}finally{rules.set(GameRules.MOB_GRIEFING,grief,h.getLevel().getServer());}
            var fresh=world.placementPreflight(bound);var withdrawn=world.withdrawTableItem(actor,fresh,fresh.custodyFingerprint(),fresh.sourceFingerprint());
            h.assertTrue(withdrawn.reason()==null && withdrawn.withdrawn()==1 && from.countItem(Items.CRAFTING_TABLE)==2 && v.getInventory().countItem(Items.CRAFTING_TABLE)==8,"Actual one-item withdrawal");
            // Cancellation disposition retains the real item without producing a block or refund.
            world.release(actor,run);h.assertTrue(world.placeTable(actor,fresh,withdrawn.custodyAfter(),withdrawn.sourceAfter()).reason()==Reason.ACTOR_UNAVAILABLE,"Revoked actor placed");
            h.assertTrue(from.countItem(Items.CRAFTING_TABLE)==2 && v.getInventory().countItem(Items.CRAFTING_TABLE)==8 && h.getLevel().getBlockState(targetPos).isAir(),"Cancellation lost or refunded actual custody");
            h.assertTrue(world.acquire(actor,run),"Fresh actor control");fresh=world.placementPreflight(bound);withdrawn=world.withdrawTableItem(actor,fresh,fresh.custodyFingerprint(),fresh.sourceFingerprint());
            h.assertTrue(withdrawn.reason()==null,"Second explicit withdrawal");
            var effect=world.placeTable(actor,fresh,withdrawn.custodyAfter(),withdrawn.sourceAfter());
            h.assertTrue(effect.reason()==null && effect.consumed()==1 && effect.placed() && !effect.facilityIdentity().isBlank(),"Actual BlockItem placement failed: "+effect);
            h.assertTrue(from.countItem(Items.CRAFTING_TABLE)==1 && v.getInventory().countItem(Items.CRAFTING_TABLE)==8 && h.getLevel().getBlockState(targetPos).is(Blocks.CRAFTING_TABLE),"Actual table/item conservation");
            h.assertTrue(world.placeTable(actor,fresh,effect.custodyAfter(),withdrawn.sourceAfter()).reason()!=null && v.getInventory().countItem(Items.CRAFTING_TABLE)==8,"Duplicate consumed another item");
            var axe=new ValidatedRequest(new CapabilityRequest(WoodenAxeProduction.ID,Map.of("actor",new ActorValue(actor),"source",new ContainerValue(source),"destination",new ContainerValue(source),"table",new FacilityValue(target),"amount",new IntValue(1))),owner,bound.observation());
            var facility=world.recipePreflight(axe);h.assertTrue(facility.reason()==null && facility.facilityIdentity().equals(effect.facilityIdentity()),"Placed block was not reobserved as a current facility");
            world.release(actor,run);phase[0]=1;System.out.println("RB006B_EFFECTS blockItem=true consumed=1 actualBlock=true currentFacility=true nativeCustody=true replacement=true occupied=true unsupported=true fluid=true revocation=true cancellation=true noRefund=true");
        });
    }
}
