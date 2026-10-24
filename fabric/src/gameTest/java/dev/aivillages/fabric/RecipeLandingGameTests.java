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
/** Task 1.2: native gravity, retained admitted-recipe custody and exact one-batch production. */
public final class RecipeLandingGameTests {
    @GameTest(maxTicks=1000,padding=16)
    public void fallingAfterWithdrawalWaitsForNativeLandingAndCraftsOneBatch(GameTestHelper h){
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)h.setBlock(x,0,z,Blocks.STONE);
        h.setBlock(2,1,2,Blocks.CHEST);h.setBlock(3,1,3,Blocks.STONE);
        var v=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,3,2,3);v.setNoAi(false);
        var sourcePos=h.absolutePos(new BlockPos(2,1,2));BootstrapGameTests.forceFixtureChunks(h,sourcePos);
        String dim=h.getLevel().dimension().identifier().toString();var actor=new ActorRef(UUID.randomUUID(),v.getUUID(),dim);
        UUID principal=UUID.randomUUID(),run=UUID.randomUUID();var owner=new TrustedContext(new PrincipalRef(principal),new ScopeRef(UUID.randomUUID(),principal));
        var source=new ContainerRef(dim,sourcePos.getX(),sourcePos.getY(),sourcePos.getZ());
        var bound=new ValidatedRequest(new CapabilityRequest(OakPlankProduction.ID,Map.of("actor",new ActorValue(actor),"source",new ContainerValue(source),"destination",new ContainerValue(source),"amount",new IntValue(4))),owner,new ObservationRef(UUID.randomUUID(),0,dim));
        var world=new FabricGatewayWorld(h.getLevel());Container from=(Container)h.getLevel().getBlockEntity(sourcePos);
        int[] phase={0},waits={0};String[] failure={null};RecipeTransaction.Preflight[] captured={null};String[] custody={null};
        h.succeedWhen(()->{
            if(failure[0]!=null){h.assertTrue(false,failure[0]);return;}
            if(phase[0]==0){
                if(!v.onGround()){h.assertTrue(false,"Waiting for initial support");return;}
                h.assertTrue(world.acquire(actor,run),"Native recipe control");from.setItem(0,new ItemStack(Items.OAK_LOG,2));
                captured[0]=world.recipePreflight(bound);h.assertTrue(captured[0].reason()==null,"Known installed plank recipe");
                var withdrawn=world.withdrawRecipeInput(actor,source,captured[0],captured[0].custodyFingerprint(),captured[0].sourceFingerprint());
                h.assertTrue(withdrawn.moved()==1 && withdrawn.reason()==null,"Exactly one real log withdrawn");custody[0]=withdrawn.custodyAfter();
                // Remove only the fixture support. Gravity must produce the airborne state.
                h.setBlock(3,1,3,Blocks.AIR);phase[0]=1;h.assertTrue(false,"Waiting for ordinary fall");return;
            }
            if(phase[0]==1 && v.onGround()){h.assertTrue(false,"Waiting for native onGround=false");return;}
            if(!v.onGround()){
                var result=world.craftRecipe(actor,captured[0],custody[0]);
                if(result.reason()!=null || result.committed()){
                    failure[0]="Actual native airborne craft rejected after withdrawal: "+result;
                    System.out.println("RB006B_OFFLINE_REPRO onGround="+v.onGround()+" actualLog="+v.getInventory().countItem(Items.OAK_LOG)+" result="+result);
                    h.assertTrue(false,failure[0]);return;
                }
                h.assertTrue(v.getInventory().countItem(Items.OAK_LOG)==1 && v.getInventory().countItem(Items.OAK_PLANKS)==0 && from.countItem(Items.OAK_LOG)==1,"Landing wait changed real input custody");
                waits[0]++;phase[0]=2;h.assertTrue(false,"Waiting for ordinary landing");return;
            }
            h.assertTrue(waits[0]>0,"Native falling guard was exercised");
            var crafted=world.craftRecipe(actor,captured[0],custody[0]);
            h.assertTrue(crafted.reason()==null && crafted.consumed()==1 && crafted.produced()==4,"Landing did not complete the same batch");
            var deposited=world.depositRecipeOutput(actor,source,captured[0],crafted.custodyAfter(),4);
            h.assertTrue(deposited.reason()==null && deposited.moved()==4 && from.countItem(Items.OAK_LOG)==1 && from.countItem(Items.OAK_PLANKS)==4 && v.getInventory().isEmpty(),"Native recipe conservation after landing");
            h.assertTrue(!world.craftRecipe(actor,captured[0],custody[0]).committed(),"Old custody replay crafted twice");world.release(actor,run);
            System.out.println("RB006B_FALLING nativeGravity=true pending=true sameBatch=true withdrawn=1 consumed=1 produced=4 deposited=4 noReplay=true waits="+waits[0]);
        });
    }
}
