package dev.aivillages.fabric;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import dev.aivillages.core.kernel.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;

/** Native effects prove recipe semantics, real slots and facility invalidation. */
public final class AxeEffectGameTests {
    @GameTest(maxTicks=10000,padding=16)
    public void realTableTwoIngredientsSlotsReplacementAuthorityAndReload(GameTestHelper h) {
        for(int x=0;x<7;x++)for(int z=0;z<7;z++)h.setBlock(x,0,z,Blocks.STONE);
        h.setBlock(2,1,2,Blocks.CHEST);h.setBlock(2,1,4,Blocks.CHEST);h.setBlock(3,1,3,Blocks.CRAFTING_TABLE);
        var v=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,2,1,3);v.setNoAi(false);
        BootstrapGameTests.forceFixtureChunks(h,h.absolutePos(BlockPos.ZERO));
        String dim=h.getLevel().dimension().identifier().toString();
        var a=h.absolutePos(new BlockPos(2,1,2));var b=h.absolutePos(new BlockPos(2,1,4));var t=h.absolutePos(new BlockPos(3,1,3));
        var source=new ContainerRef(dim,a.getX(),a.getY(),a.getZ());var destination=new ContainerRef(dim,b.getX(),b.getY(),b.getZ());
        var table=new FacilityRef(dim,t.getX(),t.getY(),t.getZ());
        var actor=new ActorRef(UUID.randomUUID(),v.getUUID(),dim);UUID principal=UUID.randomUUID(),run=UUID.randomUUID();
        var owner=new TrustedContext(new PrincipalRef(principal),new ScopeRef(UUID.randomUUID(),principal));
        var bound=new ValidatedRequest(new CapabilityRequest(WoodenAxeProduction.ID,Map.of("actor",new ActorValue(actor),"amount",new IntValue(4),
                "source",new ContainerValue(source),"destination",new ContainerValue(destination),"table",new FacilityValue(table))),owner,new ObservationRef(UUID.randomUUID(),0,dim));
        var world=new FabricGatewayWorld(h.getLevel());Container from=(Container)h.getLevel().getBlockEntity(a);Container to=(Container)h.getLevel().getBlockEntity(b);
        int[] phase={0};RecipeTransaction.Preflight[] captured={null};String[] custody={null};CompletableFuture<?>[] reload={null};
        Villager[] walker={null};ActorRef[] walkingActor={null};long[] travel={0};boolean[] airborne={false};
        h.succeedWhen(()->{
            if(phase[0]<2 && (!v.onGround() || !world.loadedForDiscovery(a))) {h.assertTrue(false,"Waiting for grounded table fixture");return;}
            if(phase[0]==0) {
                h.assertTrue(world.acquire(actor,run),"Native gateway control");
                from.setItem(0,new ItemStack(Items.OAK_PLANKS,1));from.setItem(1,new ItemStack(Items.OAK_PLANKS,11));
                from.setItem(2,new ItemStack(Items.STICK,1));from.setItem(3,new ItemStack(Items.STICK,7));
                var named=new ItemStack(Items.STICK);named.set(DataComponents.CUSTOM_NAME,Component.literal("component-input"));from.setItem(4,named);
                h.assertTrue(world.recipePreflight(bound).reason()==Reason.TARGET_INVALID,"Changed stick components accepted");from.setItem(4,ItemStack.EMPTY);
                var old=new ItemStack(Items.WOODEN_AXE);old.setDamageValue(9);to.setItem(0,old);
                for(int i=1;i<to.getContainerSize();i++)to.setItem(i,new ItemStack(Items.STONE,64));
                for(int i=1;i<=3;i++)to.setItem(i,ItemStack.EMPTY);
                var insufficient=world.recipePreflight(bound);h.assertTrue(insufficient.outputCapacity()==3,"Worn/old axes counted as empty output slots");
                to.setItem(4,ItemStack.EMPTY);
                var preflight=world.recipePreflight(bound);
                h.assertTrue(preflight.status()==ObservationStatus.PRESENT && preflight.recipe().inputs()==5 && preflight.recipe().outputs()==1
                        && preflight.availableInputs()==12 && preflight.availableSecondary()==8 && preflight.outputCapacity()==4,"Installed mirrored 3x3 qualification/two-input stock");
                var rules=h.getLevel().getGameRules();boolean grief=rules.get(GameRules.MOB_GRIEFING);
                try {rules.set(GameRules.MOB_GRIEFING,false,h.getLevel().getServer());h.assertTrue(world.recipePreflight(bound).reason()==Reason.AUTHORITY_DENIED,"Revoked table use permission accepted");}
                finally {rules.set(GameRules.MOB_GRIEFING,grief,h.getLevel().getServer());}
                var far=t.offset(12,0,0);h.getLevel().setBlock(far,Blocks.CRAFTING_TABLE.defaultBlockState(),3);
                var args=new HashMap<>(bound.request().arguments());args.put("table",new FacilityValue(new FacilityRef(dim,far.getX(),far.getY(),far.getZ())));
                var farBound=new ValidatedRequest(new CapabilityRequest(WoodenAxeProduction.ID,args),owner,bound.observation());
                var farCapture=world.recipePreflight(farBound);h.assertTrue(farCapture.reason()==null,"Far loaded facility fixture unavailable");
                h.assertTrue(world.craftRecipe(actor,farCapture,farCapture.custodyFingerprint()).reason()==Reason.TARGET_UNAVAILABLE,"Out-of-reach table crafted");
                h.getLevel().setBlock(far,Blocks.AIR.defaultBlockState(),3);
                captured[0]=preflight;
                h.getLevel().setBlock(t,Blocks.AIR.defaultBlockState(),3);
                h.assertTrue(world.recipePreflight(bound).reason()==Reason.FACILITY_MISSING,"No real table was accepted");
                h.getLevel().setBlock(t,Blocks.CRAFTING_TABLE.defaultBlockState(),3);
                v.getInventory().setItem(0,new ItemStack(Items.WOODEN_AXE));
                captured[0]=world.recipePreflight(bound);
                var withdrawn=world.withdrawRecipeInput(actor,source,captured[0],captured[0].custodyFingerprint(),captured[0].sourceFingerprint());
                h.assertTrue(withdrawn.reason()==null && withdrawn.moved()==5 && from.countItem(Items.OAK_PLANKS)==9 && from.countItem(Items.STICK)==6,"Atomic split-stack two-input withdrawal");
                h.getLevel().setBlock(t,Blocks.AIR.defaultBlockState(),3);h.getLevel().setBlock(t,Blocks.CRAFTING_TABLE.defaultBlockState(),3);
                h.assertTrue(world.craftRecipe(actor,captured[0],withdrawn.custodyAfter()).reason()==Reason.STALE_OBSERVATION,"Same-position replacement table accepted stale capture");
                h.assertTrue(v.getInventory().countItem(Items.OAK_PLANKS)==3 && v.getInventory().countItem(Items.STICK)==2,"Table loss erased actual withdrawn ingredients");
                // Fresh invocation may reconcile present physical ingredients; no old instruction is resumed.
                captured[0]=world.recipePreflight(bound);
                var crafted=world.craftRecipe(actor,captured[0],captured[0].custodyFingerprint());
                h.assertTrue(crafted.reason()==null && crafted.consumed()==5 && crafted.produced()==1 && crafted.remainders()==0,"Installed native assemble/remaining items");
                var axe=v.getInventory().getItem(1);h.assertTrue(axe.is(Items.WOODEN_AXE) && axe.getMaxStackSize()==1 && axe.getDamageValue()==0
                        && FabricRecipeTransactions.plain(axe,Items.WOODEN_AXE),"Actual assembled result components/stack maximum");
                var deposited=world.depositRecipeOutput(actor,destination,captured[0],crafted.custodyAfter(),1);
                h.assertTrue(deposited.reason()==null && to.countItem(Items.WOODEN_AXE)==2 && to.getItem(0).getDamageValue()==9,"New multi-slot deposit touched worn axe");
                h.assertTrue(v.getInventory().getItem(0).is(Items.WOODEN_AXE) && v.getInventory().getItem(1).isEmpty(),"Deposit physically removed old pristine axe instead of new stack");
                captured[0]=world.recipePreflight(bound);withdrawn=world.withdrawRecipeInput(actor,source,captured[0],captured[0].custodyFingerprint(),captured[0].sourceFingerprint());
                custody[0]=withdrawn.custodyAfter();v.getInventory().setItem(7,new ItemStack(Items.STONE));
                h.assertTrue(world.craftRecipe(actor,captured[0],custody[0]).reason()==Reason.STALE_OBSERVATION,"Custody contamination ignored");v.getInventory().setItem(7,ItemStack.EMPTY);
                reload[0]=h.getLevel().getServer().reloadResources(h.getLevel().getServer().getPackRepository().getSelectedIds());phase[0]=1;
            }
            if(phase[0]==1) {
                if(!reload[0].isDone()) {h.assertTrue(false,"Waiting for actual recipe reload");return;}reload[0].join();
                h.assertTrue(world.craftRecipe(actor,captured[0],custody[0]).reason()==Reason.ARTIFACT_INCOMPATIBLE,"Reload accepted stale recipe");
                h.assertTrue(v.getInventory().countItem(Items.OAK_PLANKS)==3 && v.getInventory().countItem(Items.STICK)==2,"Reload lost input custody");
                var fresh=world.recipePreflight(bound);h.assertTrue(fresh.recipe().generation()>captured[0].recipe().generation(),"Fresh recipe generation absent");
                var crafted=world.craftRecipe(actor,fresh,fresh.custodyFingerprint());h.assertTrue(crafted.reason()==null,"New qualified native craft");
                world.release(actor,run);h.assertTrue(v.getInventory().countItem(Items.WOODEN_AXE)==2,"Cancellation discarded real crafted axe");
                h.assertTrue(world.craftRecipe(actor,fresh,crafted.custodyAfter()).reason()==Reason.ACTOR_UNAVAILABLE,"Revoked host control crafted");
                for(int x=8;x<=16;x++)for(int z=8;z<=10;z++)h.setBlock(x,0,z,Blocks.STONE);
                for(int x=8;x<=16;x++)for(int y=1;y<=2;y++) {h.setBlock(x,y,8,Blocks.STONE);h.setBlock(x,y,10,Blocks.STONE);}
                h.setBlock(11,1,9,Blocks.CRAFTING_TABLE);h.setBlock(16,1,9,Blocks.CHEST);
                walker[0]=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,9,1,9);walker[0].setNoAi(false);
                walkingActor[0]=new ActorRef(UUID.randomUUID(),walker[0].getUUID(),dim);
                h.assertTrue(world.acquire(walkingActor[0],run),"Ordinary table-jump fixture control");phase[0]=2;
            }
            if(phase[0]==2) {
                // Gateway lifecycle samples held actors every tick, while bounded
                // dispatch may poll this execution only once in several ticks.
                world.actor(walkingActor[0]);
                if(h.getLevel().getGameTime()%8!=0) {h.assertTrue(false,"Waiting for bounded delayed movement poll");return;}
                var goal=h.absolutePos(new BlockPos(16,1,9));
                var movement=world.moveRecipe(walkingActor[0],new SurvivalGateway.Cell(dim,goal.getX(),goal.getY(),goal.getZ()),1);
                if(!walker[0].onGround())airborne[0]=true;
                h.assertTrue(movement.travelBlocks()<=1 && movement.chargedWork()<=1 && !movement.impossible(),"Ordinary airborne movement violated the unchanged poll envelope");
                travel[0]+=movement.travelBlocks();
                if(!movement.reached()) {h.assertTrue(false,"Waiting for actual constrained table jump");return;}
                h.assertTrue(airborne[0] && travel[0]>0 && travel[0]<=128 && walker[0].onGround(),"Airborne physical travel and final grounded settling were not witnessed");
                world.release(walkingActor[0],run);
                System.out.println("RB004_EFFECTS installed3x3=true mirrored=true components=true realSlots=true oldPristineSlotRetained=true splitTwoIngredients=true tableLoss=true replacementRejected=true recipeReloadBlocked=true cancellationCustody=true revokedControl=true ordinaryTableJumpTracked=true delayedPollTicks=8 travel="+travel[0]);phase[0]=3;
            }
        });
    }
}
