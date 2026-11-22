package dev.aivillages.fabric;

import dev.aivillages.core.kernel.SurvivalGateway;
import dev.aivillages.core.kernel.WoodenAxeProduction;
import dev.aivillages.core.kernel.production.RecipeSources;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.RecipeTransaction.*;

/** Original-input native controls in a host with its own recipe and facility state. */
public final class OriginalInputCustodyGameTests {
    @GameTest(maxTicks=1000,padding=16)
    public void explicitHeldTablePlacesExactlyOneOriginalUnitAndPreservesContainerStock(GameTestHelper h) {
        PlacementEffectGameTests.gatewayPlacement(h,false,true);
    }

    @GameTest(maxTicks=2400,padding=16)
    public void separateRecipeInputsRequireRealReachAtEachOriginalSource(GameTestHelper h) {
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)h.setBlock(x,0,z,Blocks.STONE);
        h.setBlock(1,1,2,Blocks.CHEST);h.setBlock(6,1,2,Blocks.BARREL);
        h.setBlock(6,1,3,Blocks.CHEST);h.setBlock(5,1,4,Blocks.CRAFTING_TABLE);
        var firstPos=h.absolutePos(new BlockPos(1,1,2));
        var secondPos=h.absolutePos(new BlockPos(6,1,2));
        var targetPos=h.absolutePos(new BlockPos(6,1,3));
        var tablePos=h.absolutePos(new BlockPos(5,1,4));
        BootstrapGameTests.forceFixtureChunks(h,firstPos);
        var actor=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,1,1,3);actor.setNoAi(false);
        var world=new FabricGatewayWorld(h.getLevel());
        String dim=h.getLevel().dimension().identifier().toString();
        var worker=new ActorRef(UUID.randomUUID(),actor.getUUID(),dim);var run=UUID.randomUUID();
        var first=new ContainerRef(dim,firstPos.getX(),firstPos.getY(),firstPos.getZ());
        var second=new ContainerRef(dim,secondPos.getX(),secondPos.getY(),secondPos.getZ());
        var target=new ContainerRef(dim,targetPos.getX(),targetPos.getY(),targetPos.getZ());
        var table=new FacilityRef(dim,tablePos.getX(),tablePos.getY(),tablePos.getZ());
        var principal=UUID.randomUUID();var owner=new TrustedContext(new PrincipalRef(principal),new ScopeRef(UUID.randomUUID(),principal));
        var capability=new CapabilityId(WoodenAxeProduction.ID.name(),2);
        var request=new ValidatedRequest(new CapabilityRequest(capability,Map.of("actor",new ActorValue(worker),
                "source",new ContainerValue(first),"destination",new ContainerValue(target),"table",new FacilityValue(table),
                "amount",new IntValue(1))),owner,new ObservationRef(UUID.randomUUID(),0,dim));
        ChoiceAuthority authority=new ChoiceAuthority() {
            public boolean permits(TrustedContext c,ActorRef a,ContainerRef p,ChoicePhase phase) {
                return owner.equals(c) && worker.equals(a) && (first.equals(p) || second.equals(p) || target.equals(p));
            }
            public boolean permitsFacility(TrustedContext c,ActorRef a,FacilityRef p,ChoicePhase phase) {
                return owner.equals(c) && worker.equals(a) && table.equals(p);
            }
        };
        var sources=new RecipeSources(List.of(first,second));
        var planks=(Container)h.getLevel().getBlockEntity(firstPos);
        var sticks=(Container)h.getLevel().getBlockEntity(secondPos);
        FabricConcreteRecipeChoices.Context[] pending={null};long[] travel={0};
        String[] failure={null};
        h.failIfEver(()->{if(failure[0]!=null)h.fail(failure[0]);});
        h.succeedWhen(()->{
            if(pending[0]==null) {
                h.assertTrue(actor.onGround() && world.recipeReach(actor,firstPos) && !world.recipeReach(actor,secondPos),
                        "Waiting for real reach of only the first source");
                h.assertTrue(world.acquire(worker,run),"Original worker control");
                planks.setItem(0,new ItemStack(Items.OAK_PLANKS,3));sticks.setItem(26,new ItemStack(Items.STICK,2));
                var choice=world.selectActivityChoice(request,authority,sources);
                var before=world.prepareSeparatedChoice(request,choice,0,run,authority,sources,first);
                var withdrawn=world.withdrawChoiceSource(before,first,false);
                h.assertTrue(withdrawn.observation().kind()==ChoiceObservationKind.APPLIED && planks.isEmpty()
                        && actor.getInventory().countItem(Items.OAK_PLANKS)==3 && sticks.countItem(Items.STICK)==2,
                        "First visit moves only its scoped native input");
                pending[0]=withdrawn.continuation();
                var remote=world.withdrawChoiceSource(pending[0],second,true);
                h.assertTrue(remote.observation().kind()==ChoiceObservationKind.PRECHECK_NO_EFFECT && sticks.countItem(Items.STICK)==2,
                        "Second source refuses mutation outside actual current reach");
            }
            var movement=world.moveRecipe(worker,new SurvivalGateway.Cell(dim,second.x(),second.y(),second.z()),1);
            travel[0]+=movement.travelBlocks();
            h.assertTrue(!movement.impossible() && travel[0]<=32,"Ordinary motion within the bounded native allowance at "+actor.position());
            h.assertTrue(movement.reached(),"Waiting for the same worker to physically reach the second input");
            var withdrawn=world.withdrawChoiceSource(pending[0],second,true);
            if(withdrawn.observation().kind()!=ChoiceObservationKind.APPLIED || travel[0]<=0 || !sticks.isEmpty()
                    || actor.getInventory().countItem(Items.STICK)!=2 || actor.getInventory().countItem(Items.OAK_PLANKS)!=3) {
                failure[0]="Second visit failed native custody: "+withdrawn.observation().kind()+" "+withdrawn.observation().reason()
                        +" travel="+travel[0]+" stock="+sticks.countItem(Items.STICK)+" heldSticks="+actor.getInventory().countItem(Items.STICK)
                        +" heldPlanks="+actor.getInventory().countItem(Items.OAK_PLANKS);
                h.fail(failure[0]);
            }
            world.release(worker,run);
        });
    }
}
