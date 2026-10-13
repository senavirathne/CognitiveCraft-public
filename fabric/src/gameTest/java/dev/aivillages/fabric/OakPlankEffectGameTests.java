package dev.aivillages.fabric;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import dev.aivillages.core.kernel.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;

/** Actual installed 2x2 recipe, bounded native slots and reload/custody invalidation. */
public final class OakPlankEffectGameTests {
    @GameTest(maxTicks=10000,padding=16)
    public void actualRecipeSlotsComponentsCapacityReplacementAndReload(GameTestHelper h) {
        for(int x=0;x<7;x++)for(int z=0;z<7;z++)h.setBlock(x,0,z,Blocks.STONE);
        h.setBlock(2,1,2,Blocks.CHEST);h.setBlock(2,1,4,Blocks.CHEST);
        var v=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,2,1,3);v.setNoAi(false);
        BootstrapGameTests.forceFixtureChunks(h,h.absolutePos(BlockPos.ZERO));
        String dim=h.getLevel().dimension().identifier().toString();
        var a=h.absolutePos(new BlockPos(2,1,2));var b=h.absolutePos(new BlockPos(2,1,4));
        var source=new ContainerRef(dim,a.getX(),a.getY(),a.getZ());var destination=new ContainerRef(dim,b.getX(),b.getY(),b.getZ());
        var actor=new ActorRef(UUID.randomUUID(),v.getUUID(),dim);UUID principal=UUID.randomUUID(),run=UUID.randomUUID();
        var owner=new TrustedContext(new PrincipalRef(principal),new ScopeRef(UUID.randomUUID(),principal));
        var bound=new ValidatedRequest(new CapabilityRequest(OakPlankProduction.ID,Map.of("actor",new ActorValue(actor),"amount",new IntValue(3),
                "source",new ContainerValue(source),"destination",new ContainerValue(destination))),owner,new ObservationRef(UUID.randomUUID(),0,dim));
        var world=new FabricGatewayWorld(h.getLevel());Container from=(Container)h.getLevel().getBlockEntity(a);
        Container to=(Container)h.getLevel().getBlockEntity(b);int[] phase={0};RecipeTransaction.Preflight[] capture={null};
        String[] custody={null};CompletableFuture<?>[] reload={null};
        h.succeedWhen(()->{
            if(!v.onGround() || !world.loadedForDiscovery(a)) {h.assertTrue(false,"Waiting for real grounded recipe fixture");return;}
            if(phase[0]==0) {
                h.assertTrue(world.acquire(actor,run),"Native gateway control missing");
                from.setItem(0,new ItemStack(Items.OAK_LOG,2));v.getInventory().setItem(0,new ItemStack(Items.OAK_LOG,3));
                v.getInventory().setItem(1,new ItemStack(Items.OAK_PLANKS,7));to.setItem(0,new ItemStack(Items.OAK_PLANKS,11));
                var named=new ItemStack(Items.OAK_LOG);named.set(DataComponents.CUSTOM_NAME,Component.literal("exact-input-components"));
                from.setItem(1,named);h.assertTrue(world.recipePreflight(bound).reason()==Reason.TARGET_INVALID,"Component input was accepted");from.setItem(1,ItemStack.EMPTY);
                for(int i=0;i<v.getInventory().getContainerSize();i++)v.getInventory().setItem(i,new ItemStack(Items.STONE,64));
                v.getInventory().setItem(0,new ItemStack(Items.OAK_LOG,63));
                h.assertTrue(world.recipePreflight(bound).reason()==Reason.TARGET_UNAVAILABLE,"Known result capacity did not block before withdrawal");
                h.assertTrue(from.countItem(Items.OAK_LOG)==2,"Capacity preflight mutated source");
                v.getInventory().clearContent();v.getInventory().setItem(0,new ItemStack(Items.OAK_LOG,3));v.getInventory().setItem(1,new ItemStack(Items.OAK_PLANKS,7));
                capture[0]=world.recipePreflight(bound);h.assertTrue(capture[0].status()==ObservationStatus.PRESENT && capture[0].recipe().outputs()==4,"Installed recipe was not qualified");
                var withdrawn=world.withdrawRecipeInput(actor,source,capture[0],capture[0].custodyFingerprint(),capture[0].sourceFingerprint());
                h.assertTrue(withdrawn.reason()==null && withdrawn.moved()==1 && from.countItem(Items.OAK_LOG)==1,"Actual one-log withdrawal failed");
                var crafted=world.craftRecipe(actor,capture[0],withdrawn.custodyAfter());
                h.assertTrue(crafted.reason()==null && crafted.consumed()==1 && crafted.produced()==4 && crafted.remainders()==0,"Real 2x2 match/assemble/remainder failed");
                var deposited=world.depositRecipeOutput(actor,destination,capture[0],crafted.custodyAfter(),3);
                h.assertTrue(deposited.reason()==null && to.countItem(Items.OAK_PLANKS)==14 && v.getInventory().countItem(Items.OAK_PLANKS)==8,"Real surplus or old stock changed");
                h.assertTrue(v.getInventory().countItem(Items.OAK_LOG)==3,"Preexisting actor logs were credited as supplied input");
                capture[0]=world.recipePreflight(bound);withdrawn=world.withdrawRecipeInput(actor,source,capture[0],capture[0].custodyFingerprint(),capture[0].sourceFingerprint());
                crafted=world.craftRecipe(actor,capture[0],withdrawn.custodyAfter());h.assertTrue(crafted.reason()==null,"Second real craft failed");
                world.release(actor,run);h.assertTrue(v.getInventory().countItem(Items.OAK_PLANKS)==12 && to.countItem(Items.OAK_PLANKS)==14,"Cancellation erased or deposited real crafted custody");
                h.assertTrue(world.acquire(actor,run),"Control reacquisition failed");from.setItem(0,new ItemStack(Items.OAK_LOG,2));
                capture[0]=world.recipePreflight(bound);withdrawn=world.withdrawRecipeInput(actor,source,capture[0],capture[0].custodyFingerprint(),capture[0].sourceFingerprint());
                custody[0]=withdrawn.custodyAfter();
                v.getInventory().setItem(7,new ItemStack(Items.STONE));
                h.assertTrue(world.craftRecipe(actor,capture[0],custody[0]).reason()==Reason.STALE_OBSERVATION,"External native custody mutation was ignored");
                v.getInventory().setItem(7,ItemStack.EMPTY);
                reload[0]=h.getLevel().getServer().reloadResources(h.getLevel().getServer().getPackRepository().getSelectedIds());phase[0]=1;
            }
            if(phase[0]==1) {
                if(!reload[0].isDone()) {h.assertTrue(false,"Waiting for actual recipe reload");return;}reload[0].join();
                h.assertTrue(world.craftRecipe(actor,capture[0],custody[0]).reason()==Reason.ARTIFACT_INCOMPATIBLE,"Reloaded recipe reused an invocation snapshot");
                h.assertTrue(v.getInventory().countItem(Items.OAK_LOG)==4 && v.getInventory().countItem(Items.OAK_PLANKS)==12,"Reload block lost actual withdrawn input");
                var fresh=world.recipePreflight(bound);h.assertTrue(fresh.recipe().generation()>capture[0].recipe().generation(),"New invocation did not requalify the installed recipe");
                var withdrawn=world.withdrawRecipeInput(actor,source,fresh,fresh.custodyFingerprint(),fresh.sourceFingerprint());
                var crafted=world.craftRecipe(actor,fresh,withdrawn.custodyAfter());h.assertTrue(crafted.reason()==null,"Qualified new invocation failed");
                h.getLevel().setBlock(b,Blocks.AIR.defaultBlockState(),3);h.getLevel().setBlock(b,Blocks.CHEST.defaultBlockState(),3);
                h.assertTrue(world.depositRecipeOutput(actor,destination,fresh,crafted.custodyAfter(),3).reason()==Reason.STALE_OBSERVATION,"Replacement container accepted stale delivery");
                world.release(actor,run);h.assertTrue(world.craftRecipe(actor,fresh,crafted.custodyAfter()).reason()==Reason.ACTOR_UNAVAILABLE,"Revoked host control crafted");
                System.out.println("RB002_EFFECTS installed2x2=true componentInputRejected=true initialCapacityBlocked=true realSurplus=1 oldPlanksRetained=true cancellationCustodyRetained=true externalCustodyRejected=true actualRecipeReloadBlocked=true replacementRejected=true revokedControlRejected=true");phase[0]=2;
            }
        });
    }
}
