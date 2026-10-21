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
    @GameTest(maxTicks=1000,padding=16)
    public void actorOnApprovedSiteUsesExplicitMovementBeforeWithdrawalAndPlacement(GameTestHelper h){
        gatewayPlacement(h,false);
    }
    @GameTest(maxTicks=1000,padding=16)
    public void placementMoveRequiresTheSameCenterRayAsTheMutation(GameTestHelper h){
        gatewayPlacement(h,true);
    }
    private void gatewayPlacement(GameTestHelper h,boolean overhead){
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)h.setBlock(x,0,z,Blocks.STONE);
        // Adjacent source is the harder case: its closest approach is the reserved site.
        h.setBlock(2,1,2,Blocks.CHEST);if(overhead)h.setBlock(2,2,3,Blocks.STONE);
        var v=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,overhead?1:2,1,3);v.setNoAi(false);
        var sourcePos=h.absolutePos(new BlockPos(2,1,2));var targetPos=h.absolutePos(new BlockPos(2,1,3));BootstrapGameTests.forceFixtureChunks(h,sourcePos);
        String dim=h.getLevel().dimension().identifier().toString();var actor=new ActorRef(UUID.randomUUID(),v.getUUID(),dim);
        UUID principal=UUID.randomUUID(),run=UUID.randomUUID();var owner=new TrustedContext(new PrincipalRef(principal),new ScopeRef(UUID.randomUUID(),principal));
        var source=new ContainerRef(dim,sourcePos.getX(),sourcePos.getY(),sourcePos.getZ());var target=new FacilityRef(dim,targetPos.getX(),targetPos.getY(),targetPos.getZ());
        var bound=new ValidatedRequest(new CapabilityRequest(CraftingTablePlacement.ID,Map.of("actor",new ActorValue(actor),"source",new ContainerValue(source),"target",new FacilityValue(target),"amount",new IntValue(1))),owner,new ObservationRef(UUID.randomUUID(),0,dim));
        var world=new FabricGatewayWorld(h.getLevel());var clock=java.time.Clock.systemUTC();
        var gateway=new SurvivalGateway(world,(a,e,c)->true,new SurvivalGateway.Limits(8,512,64,16,256,1,200,32),clock);
        var usage=new Budgets.Ledger(OakPlankProduction.executionLimits(clock.millis()).total(),clock);
        Container from=(Container)h.getLevel().getBlockEntity(sourcePos);var operations=CraftingTablePlacement.operations();
        int[] phase={-1};ActionHandle[] active={null};var receipts=new ArrayList<ResourceDelivery.Receipt>();
        h.succeedWhen(()->{
            if(phase[0]<0){
                if(!v.onGround()){h.assertTrue(false,"Waiting for native actor landing");return;}
                from.setItem(0,new ItemStack(Items.CRAFTING_TABLE));
                h.assertTrue(v.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(targetPos))==!overhead,"Expected initial approved-cell occupancy");
                if(overhead)h.assertTrue(!world.recipeReach(v,targetPos),"Fixture must block the actual placement center ray");phase[0]=0;
            }
            var op=operations.get(phase[0]);
            if(active[0]==null){
                var args=new LinkedHashMap<String,Value>();op.signature().parameters().forEach(p->args.put(p.name(),bound.request().arguments().get(p.name())));
                active[0]=gateway.start(bound,new RunCorrelation(run,new ArtifactRef(CraftingTablePlacement.ID,"b".repeat(64)),null),op.requirement(),args,usage);
            }
            var result=gateway.poll(active[0]);
            if(!result.terminal()){h.assertTrue(false,"Waiting for explicit native "+op);return;}
            h.assertTrue(result.reason()==null,"Native self-occupied placement stopped at "+op+": "+result.reason());
            receipts.addAll(result.resourceReceipts());
            if(phase[0]==0)h.assertTrue(result.committedEffects()==0 && result.resourceReceipts().isEmpty() && from.countItem(Items.CRAFTING_TABLE)==1 && v.getInventory().isEmpty()
                    && v.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(targetPos))==!overhead,"Structural observation moved the actor or mutated custody");
            if(op==GatewayPrimitives.Operation.MOVE_TO_INPUT || op==GatewayPrimitives.Operation.MOVE_TO_PLACEMENT)
                h.assertTrue(!v.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(targetPos)),"MOVE ended inside approved block");
            if(op==GatewayPrimitives.Operation.MOVE_TO_PLACEMENT)h.assertTrue(world.recipeReach(v,targetPos),"MOVE accepted a ray the placement guard rejects");
            if(phase[0]+1<operations.size()){phase[0]++;active[0]=null;h.assertTrue(false,"Next explicit placement operation");return;}
            h.assertTrue(ResourceDelivery.quantity(bound,run,receipts)==1 && from.isEmpty() && v.getInventory().isEmpty() && h.getLevel().getBlockState(targetPos).is(Blocks.CRAFTING_TABLE),"Self-occupied placement failed native conservation");
            gateway.releaseRun(run);
            System.out.println((overhead?"RB006B_CENTER_RAY":"RB006B_SELF_OCCUPANCY")+" nativeNavigation=true structuralObservation=true noImplicitMove=true safeInput=true safeFace=true consumed=1 placed=1 depletedSource=true");
        });
    }
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
        int[] phase={0},waits={0};RecipeTransaction.Preflight[] captured={null};String[] custody={null};
        h.succeedWhen(()->{
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
                h.assertTrue(result.pending() && !result.committed(),"Airborne recipe must remain pending");
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
    @GameTest(maxTicks=1000,padding=16)
    public void liveEntityCrossingAfterWithdrawalWaitsWithoutConsumingOrLosingAuthority(GameTestHelper h){
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)h.setBlock(x,0,z,Blocks.STONE);
        h.setBlock(2,1,2,Blocks.CHEST);var v=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,3,1,3);v.setNoAi(false);
        var sourcePos=h.absolutePos(new BlockPos(2,1,2));var targetPos=h.absolutePos(new BlockPos(4,1,3));BootstrapGameTests.forceFixtureChunks(h,sourcePos);
        String dim=h.getLevel().dimension().identifier().toString();var actor=new ActorRef(UUID.randomUUID(),v.getUUID(),dim);
        UUID principal=UUID.randomUUID(),run=UUID.randomUUID();var owner=new TrustedContext(new PrincipalRef(principal),new ScopeRef(UUID.randomUUID(),principal));
        var source=new ContainerRef(dim,sourcePos.getX(),sourcePos.getY(),sourcePos.getZ());var target=new FacilityRef(dim,targetPos.getX(),targetPos.getY(),targetPos.getZ());
        var bound=new ValidatedRequest(new CapabilityRequest(CraftingTablePlacement.ID,Map.of("actor",new ActorValue(actor),"source",new ContainerValue(source),"target",new FacilityValue(target),"amount",new IntValue(1))),owner,new ObservationRef(UUID.randomUUID(),0,dim));
        var world=new FabricGatewayWorld(h.getLevel());Container from=(Container)h.getLevel().getBlockEntity(sourcePos);
        int[] phase={0},waits={0};PlacementTransaction.Preflight[] captured={null};PlacementTransaction.Mutation[] withdrawn={null};
        net.minecraft.world.entity.npc.villager.Villager[] blocker={null};ActorRef[] blockerActor={null};UUID blockerRun=UUID.randomUUID();
        h.succeedWhen(()->{
            if(phase[0]==0){
                if(!v.onGround()){h.assertTrue(false,"Waiting for initial actor landing");return;}
                h.assertTrue(world.acquire(actor,run),"Native placement control");from.setItem(0,new ItemStack(Items.CRAFTING_TABLE,2));
                captured[0]=world.placementPreflight(bound);withdrawn[0]=world.withdrawTableItem(actor,captured[0],captured[0].custodyFingerprint(),captured[0].sourceFingerprint());
                h.assertTrue(withdrawn[0].withdrawn()==1,"Exactly one real table withdrawn");
                blocker[0]=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,4,1,3);blocker[0].setNoAi(false);
                blockerActor[0]=new ActorRef(UUID.randomUUID(),blocker[0].getUUID(),dim);h.assertTrue(world.acquire(blockerActor[0],blockerRun),"Native crossing fixture control");
                var observed=world.placementPreflight(bound);h.assertTrue(observed.pending() && observed.siteFingerprint().equals(captured[0].siteFingerprint()),"Moving entity must not erase the structural site witness");
                var pending=world.placeTable(actor,captured[0],withdrawn[0].custodyAfter(),withdrawn[0].sourceAfter());
                h.assertTrue(pending.pending() && !pending.committed(),"Live entity collision must wait");waits[0]++;
                world.release(actor,run);h.assertTrue(world.placeTable(actor,captured[0],withdrawn[0].custodyAfter(),withdrawn[0].sourceAfter()).reason()==Reason.ACTOR_UNAVAILABLE,"Pending collision bypassed revoked control");
                h.assertTrue(world.acquire(actor,run),"Explicit test control reacquisition");
                phase[0]=1;h.assertTrue(false,"Waiting for crossing fixture landing");return;
            }
            if(phase[0]==1){
                // A freshly spawned native mob has not yet had its first physics tick.
                // Finish setup once, then start the path only from a grounded actor.
                if(!blocker[0].onGround()){h.assertTrue(false,"Waiting for crossing fixture landing");return;}
                var destination=h.absolutePos(new BlockPos(4,1,6));
                h.assertTrue(blocker[0].getNavigation().moveTo(destination.getX()+.5,destination.getY(),destination.getZ()+.5,.65),"Native crossing path");
                phase[0]=2;
            }
            var result=world.placeTable(actor,captured[0],withdrawn[0].custodyAfter(),withdrawn[0].sourceAfter());
            if(result.pending()){
                waits[0]++;h.assertTrue(from.countItem(Items.CRAFTING_TABLE)==1 && v.getInventory().countItem(Items.CRAFTING_TABLE)==1 && h.getLevel().getBlockState(targetPos).isAir(),"Collision wait changed native custody");
                h.assertTrue(false,"Waiting for ordinary crossing movement");return;
            }
            h.assertTrue(result.reason()==null && result.placed() && result.consumed()==1 && waits[0]>0,"Clear site did not place exactly once: "+result);
            h.assertTrue(from.countItem(Items.CRAFTING_TABLE)==1 && v.getInventory().isEmpty() && h.getLevel().getBlockState(targetPos).is(Blocks.CRAFTING_TABLE),"Native table conservation");
            h.assertTrue(!world.placeTable(actor,captured[0],result.custodyAfter(),withdrawn[0].sourceAfter()).committed(),"Cleared site replay consumed twice");
            world.release(actor,run);world.release(blockerActor[0],blockerRun);
            System.out.println("RB006B_COLLISION nativeEntity=true pending=true nativeWalking=true authority=true consumed=1 placed=1 noReplay=true waits="+waits[0]);
        });
    }
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
