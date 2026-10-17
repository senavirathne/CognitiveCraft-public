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
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;

/** Real inventory 2x2 qualification and native slot/custody/reload effects without a table block. */
public final class TableEffectGameTests {
    @GameTest(maxTicks=10000,padding=16)
    public void actualTableRecipeNeedsNoFacilityAndPreservesRealCustody(GameTestHelper h) {
        for(int x=0;x<7;x++)for(int z=0;z<7;z++)h.setBlock(x,0,z,Blocks.STONE);
        h.setBlock(2,1,2,Blocks.CHEST);h.setBlock(2,1,4,Blocks.CHEST);
        var v=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,2,1,3);v.setNoAi(false);
        BootstrapGameTests.forceFixtureChunks(h,h.absolutePos(BlockPos.ZERO));
        String dim=h.getLevel().dimension().identifier().toString();var a=h.absolutePos(new BlockPos(2,1,2));var b=h.absolutePos(new BlockPos(2,1,4));
        var source=new ContainerRef(dim,a.getX(),a.getY(),a.getZ());var destination=new ContainerRef(dim,b.getX(),b.getY(),b.getZ());
        var actor=new ActorRef(UUID.randomUUID(),v.getUUID(),dim);UUID principal=UUID.randomUUID(),run=UUID.randomUUID();
        var owner=new TrustedContext(new PrincipalRef(principal),new ScopeRef(UUID.randomUUID(),principal));
        var bound=new ValidatedRequest(new CapabilityRequest(CraftingTableProduction.ID,Map.of("actor",new ActorValue(actor),"amount",new IntValue(1),
                "source",new ContainerValue(source),"destination",new ContainerValue(destination))),owner,new ObservationRef(UUID.randomUUID(),0,dim));
        var world=new FabricGatewayWorld(h.getLevel());Container from=(Container)h.getLevel().getBlockEntity(a),to=(Container)h.getLevel().getBlockEntity(b);
        int[] phase={0};RecipeTransaction.Preflight[] captured={null};String[] custody={null};CompletableFuture<?>[] reload={null};
        h.succeedWhen(()->{
            if(!v.onGround() || !world.loadedForDiscovery(a)) {h.assertTrue(false,"Waiting for grounded table item fixture");return;}
            if(phase[0]==0) {
                h.assertTrue(world.acquire(actor,run),"Native custody owner missing");from.setItem(0,new ItemStack(Items.OAK_PLANKS,1));from.setItem(1,new ItemStack(Items.OAK_PLANKS,7));
                var named=new ItemStack(Items.OAK_PLANKS);named.set(DataComponents.CUSTOM_NAME,Component.literal("unsupported-components"));from.setItem(2,named);
                h.assertTrue(world.recipePreflight(bound).reason()==Reason.TARGET_INVALID,"Component input accepted");from.setItem(2,ItemStack.EMPTY);
                for(int i=0;i<v.getInventory().getContainerSize();i++)v.getInventory().setItem(i,new ItemStack(Items.STONE,64));
                v.getInventory().setItem(0,new ItemStack(Items.OAK_PLANKS,63));
                h.assertTrue(world.recipePreflight(bound).reason()==Reason.TARGET_UNAVAILABLE && from.countItem(Items.OAK_PLANKS)==8,"Full result custody lost input");
                v.getInventory().clearContent();v.getInventory().setItem(0,new ItemStack(Items.OAK_PLANKS,3));v.getInventory().setItem(1,new ItemStack(Items.CRAFTING_TABLE,7));
                to.setItem(0,new ItemStack(Items.CRAFTING_TABLE,11));
                captured[0]=world.recipePreflight(bound);h.assertTrue(captured[0].reason()==null && captured[0].recipe().inputs()==4 && captured[0].recipe().outputs()==1 && captured[0].facility()==null,"Installed inventory-only recipe qualification");
                var withdrawn=world.withdrawRecipeInput(actor,source,captured[0],captured[0].custodyFingerprint(),captured[0].sourceFingerprint());
                h.assertTrue(withdrawn.reason()==null && withdrawn.moved()==4 && from.countItem(Items.OAK_PLANKS)==4,"Split native input withdrawal");
                var crafted=world.craftRecipe(actor,captured[0],withdrawn.custodyAfter());h.assertTrue(crafted.reason()==null && crafted.consumed()==4 && crafted.produced()==1 && crafted.remainders()==0,"Actual four-input recipe match/assemble");
                var deposited=world.depositRecipeOutput(actor,destination,captured[0],crafted.custodyAfter(),1);
                h.assertTrue(deposited.reason()==null && to.countItem(Items.CRAFTING_TABLE)==12 && v.getInventory().countItem(Items.CRAFTING_TABLE)==7 && v.getInventory().countItem(Items.OAK_PLANKS)==3,"Old table/input custody changed");
                captured[0]=world.recipePreflight(bound);withdrawn=world.withdrawRecipeInput(actor,source,captured[0],captured[0].custodyFingerprint(),captured[0].sourceFingerprint());
                crafted=world.craftRecipe(actor,captured[0],withdrawn.custodyAfter());h.assertTrue(crafted.reason()==null,"Second actual craft");
                world.release(actor,run);h.assertTrue(v.getInventory().countItem(Items.CRAFTING_TABLE)==8 && to.countItem(Items.CRAFTING_TABLE)==12,"Cancellation erased real output");
                h.assertTrue(world.acquire(actor,run),"Fresh custody ownership");from.setItem(0,new ItemStack(Items.OAK_PLANKS,4));captured[0]=world.recipePreflight(bound);
                withdrawn=world.withdrawRecipeInput(actor,source,captured[0],captured[0].custodyFingerprint(),captured[0].sourceFingerprint());custody[0]=withdrawn.custodyAfter();
                v.getInventory().setItem(7,new ItemStack(Items.STONE));h.assertTrue(world.craftRecipe(actor,captured[0],custody[0]).reason()==Reason.STALE_OBSERVATION,"Changed custody accepted");v.getInventory().setItem(7,ItemStack.EMPTY);
                reload[0]=h.getLevel().getServer().reloadResources(h.getLevel().getServer().getPackRepository().getSelectedIds());phase[0]=1;
            }
            if(phase[0]==1) {
                if(!reload[0].isDone()) {h.assertTrue(false,"Waiting for real recipe reload");return;}reload[0].join();
                h.assertTrue(world.craftRecipe(actor,captured[0],custody[0]).reason()==Reason.ARTIFACT_INCOMPATIBLE,"Reload ignored");
                h.assertTrue(v.getInventory().countItem(Items.OAK_PLANKS)==7 && v.getInventory().countItem(Items.CRAFTING_TABLE)==8,"Reload lost actual items");
                var fresh=world.recipePreflight(bound);h.assertTrue(fresh.recipe().generation()>captured[0].recipe().generation(),"Recipe was not requalified");
                var crafted=world.craftRecipe(actor,fresh,fresh.custodyFingerprint());h.assertTrue(crafted.reason()==null,"Fresh recipe failed");
                h.getLevel().setBlock(b,Blocks.AIR.defaultBlockState(),3);h.getLevel().setBlock(b,Blocks.CHEST.defaultBlockState(),3);
                h.assertTrue(world.depositRecipeOutput(actor,destination,fresh,crafted.custodyAfter(),1).reason()==Reason.STALE_OBSERVATION,"Container replacement accepted");
                for(int x=0;x<7;x++)for(int z=0;z<7;z++)h.assertTrue(!h.getLevel().getBlockState(h.absolutePos(new BlockPos(x,1,z))).is(Blocks.CRAFTING_TABLE),"Item production placed a facility");
                world.release(actor,run);h.assertTrue(world.craftRecipe(actor,fresh,crafted.custodyAfter()).reason()==Reason.ACTOR_UNAVAILABLE,"Revoked native owner crafted");
                System.out.println("RB006A_EFFECTS installed2x2=true inputs=4 output=1 realSlots=true components=true fullCustody=true cancellation=true reload=true replacement=true placement=false");phase[0]=2;
            }
        });
    }
}
