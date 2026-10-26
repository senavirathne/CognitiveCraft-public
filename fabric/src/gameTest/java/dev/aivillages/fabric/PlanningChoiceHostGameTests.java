package dev.aivillages.fabric;

import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;
import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.production.*;
import dev.aivillages.core.kernel.production.planning.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.RecipeTransaction.*;
import static dev.aivillages.core.kernel.production.planning.PlanningModel.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;

/** One actual native material checkpoint from a pure proposal, with explicit test authority and no Jobs/admission. */
public final class PlanningChoiceHostGameTests {
    private static final Gson JSON=new Gson();
    private static void evidence(String name,Object value){try{var root=Path.of(System.getProperty("cognitivecraft.parallelB.evidence"));Files.createDirectories(root);Files.writeString(root.resolve(name+".json"),JSON.toJson(value));}catch(java.io.IOException failure){throw new IllegalStateException(failure);}}
    private static String refusal(Runnable call,String expected){try{call.run();}catch(RuntimeException failure){if(failure.getMessage()!=null && failure.getMessage().contains(expected))return failure.getMessage();throw failure;}throw new IllegalStateException("Expected native no-effect refusal "+expected);}
    @GameTest(maxTicks=1200,padding=16)
    public void actualNativeCatalogueAndWholeGoalCompatibleConcreteCheckpoint(GameTestHelper h){
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)h.setBlock(x,0,z,Blocks.STONE);h.setBlock(2,1,2,Blocks.CHEST);h.setBlock(2,1,4,Blocks.CHEST);
        var v=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,2,1,3);v.setNoAi(false);BootstrapGameTests.forceFixtureChunks(h,h.absolutePos(BlockPos.ZERO));
        String dim=h.getLevel().dimension().identifier().toString();var a=h.absolutePos(new BlockPos(2,1,2));var b=h.absolutePos(new BlockPos(2,1,4));var site=h.absolutePos(new BlockPos(4,1,3));
        var source=new ContainerRef(dim,a.getX(),a.getY(),a.getZ());var destination=new ContainerRef(dim,b.getX(),b.getY(),b.getZ());var tableSite=new FacilityRef(dim,site.getX(),site.getY(),site.getZ());
        var actor=new ActorRef(UUID.randomUUID(),v.getUUID(),dim);UUID worldId=UUID.randomUUID(),principal=UUID.randomUUID(),rootId=UUID.randomUUID(),ownerId=UUID.randomUUID(),run=UUID.randomUUID();
        var context=new TrustedContext(new PrincipalRef(principal),new ScopeRef(worldId,principal));var world=new FabricGatewayWorld(h.getLevel());var host=new FabricRecipeTransactions(h.getLevel(),world);int[] phase={0};
        ChoiceAuthority authority=(caller,subject,target,operation)->caller.equals(context) && subject.equals(actor) && (target.equals(source)||target.equals(destination));
        h.succeedWhen(()->{
            if(!v.onGround() || !world.loadedForDiscovery(a)){h.assertTrue(false,"Waiting for actual grounded planning host fixture");return;}
            if(phase[0]!=0){h.assertTrue(false,"Native fixture cannot retry a failed effect");return;}phase[0]=1;
            try{
                h.assertTrue(world.acquire(actor,run),"Actual native control unavailable");var box=(Container)h.getLevel().getBlockEntity(a);box.clearContent();box.setItem(0,new ItemStack(Items.OAK_PLANKS,4));box.setItem(1,new ItemStack(Items.BIRCH_PLANKS,5));v.getInventory().clearContent();
                var profiles=new ArrayList<RecipeProfile>();for(var legacy:List.of(OakPlankProduction.ID,StickProduction.ID,CraftingTableProduction.ID,WoodenAxeProduction.ID)){
                    var data=host.describeChoice(new CapabilityId(legacy.name(),2),new Cost(2,3,10));var pin=new MethodPin(data.capability(),RecipeTransaction.fingerprint("fixture:unadmitted-host-method:"+data.capability()),data.semanticFingerprint(),true);
                    var cells=data.cells();if(legacy.equals(StickProduction.ID)){var oak=data.cells().getFirst().stream().filter(k->k.id().equals("minecraft:oak_planks")).findFirst().orElseThrow();cells=List.of(List.of(oak),List.of(oak));}
                    profiles.add(new RecipeProfile(data.recipe(),data.capability(),cells,data.result(),data.semanticFingerprint(),pin,data.allowance(),data.reloadEpoch()));}
                var goalItem=profiles.stream().filter(p->p.capability().name().equals(WoodenAxeProduction.ID.name())).findFirst().orElseThrow().result();
                var policy=new Policy(actor,Set.of(actor),Set.of(source),Set.of(source,destination),Set.of(),source,tableSite,true,false,0,64);var goal=new Goal(Mode.MAKE_NEW,goalItem,1,destination,policy);
                var limits=new EnumMap<Counter,Long>(Counter.class);for(var c:Counter.values())limits.put(c,1000L);var budget=new Budget(limits,zeroCounts(),100000);var owner=new Owner(ownerId,actor,0,true,false,false,false);
                var placement=new MethodPin(CraftingTablePlacement.ID,RecipeTransaction.fingerprint("fixture:unadmitted-placement-method"),RecipeTransaction.fingerprint("fixture:placement-contract"),true);var facility=new Facility(tableSite,Availability.ABSENT,true,true,"",true,0,placement,new Cost(2,2,10));
                class Projection {
                    List<Stock> stock(){var rows=new ArrayList<Stock>();for(int i=0;i<box.getContainerSize();i++){var stack=box.getItem(i);if(!stack.isEmpty())rows.add(new Stock("fixture:native-slot:"+i,new Location(source,null,i),FabricConcreteRecipeChoices.itemKey(h.getLevel(),stack),stack.getCount(),stack.getCount(),0,null,List.of(),Eligibility.NONE,Availability.PRESENT,true,true,2,h.getLevel().getGameTime()));}return List.copyOf(rows);}
                    PlanningModel.Snapshot view(long revision,List<ChoiceOffer> choices){return new PlanningModel.Snapshot(SCHEMA,worldId,rootId,revision,context,owner,goal,destination,0,budget,1000,revision,stock(),List.of(),profiles,facility,choices,EnumSet.allOf(Kind.class),List.of(),true,false,0);}
                }
                var projection=new Projection();var before=projection.stock();var coordinator=new PlanningCoordinator();var wanted=coordinator.next(projection.view(1,List.of()),null,Memory.empty());
                h.assertTrue(wanted.analysis().feasibility()==Feasibility.FEASIBLE && wanted.neededChoice()!=null,"Pure whole-goal planner failed actual recipe profile: "+wanted.blocker());
                var proposed=PlanningChoiceBuilder.build(projection.view(1,List.of()),wanted.neededChoice());var bound=new ValidatedRequest(proposed.invocation(),context,new ObservationRef(UUID.randomUUID(),h.getLevel().getGameTime(),dim));
                host.validateChoice(bound,proposed.choice(),run,authority,proposed.sources());h.assertTrue(before.equals(projection.stock()),"Native validation had a physical effect");
                var firstBinding=proposed.choice().batch(0).bindings().getFirst();var original=box.getItem(firstBinding.sourceSlot()).copy();box.setItem(firstBinding.sourceSlot(),original.copyWithCount(original.getCount()-1));var externallyChanged=projection.stock();
                String stale=refusal(()->host.validateChoice(bound,proposed.choice(),run,authority,proposed.sources()),"STALE_OBSERVATION");h.assertTrue(externallyChanged.equals(projection.stock()) && v.getInventory().isEmpty(),"Changed-slot refusal had an effect");box.setItem(firstBinding.sourceSlot(),original);
                String denied=refusal(()->host.validateChoice(bound,proposed.choice(),run,(c,x,t,p)->false,proposed.sources()),"AUTHORITY_DENIED");h.assertTrue(before.equals(projection.stock()),"Denied native validation consumed input");
                var full=(Container)h.getLevel().getBlockEntity(b);for(int i=0;i<full.getContainerSize();i++)full.setItem(i,new ItemStack(Items.STONE,64));var args=new HashMap<>(bound.request().arguments());args.put("destination",new ContainerValue(destination));var capacityRequest=new ValidatedRequest(new CapabilityRequest(bound.request().capability(),args),context,bound.observation());
                String capacity=refusal(()->host.validateChoice(capacityRequest,proposed.choice(),run,authority,proposed.sources()),"TARGET_UNAVAILABLE");h.assertTrue(before.equals(projection.stock()) && v.getInventory().isEmpty(),"Full-destination validation consumed input");full.clearContent();evidence("planning-guard-negatives",Map.of("nativeHostOnly",true,"sourceBefore",before,"sourceAfter",projection.stock(),"actorItems",v.getInventory().isEmpty()?0:1,"changedSlot",stale,"revokedAuthority",denied,"capacity",capacity));
                var offered=new ChoiceOffer(bound,proposed.choice(),proposed.sources());var decision=coordinator.next(projection.view(1,List.of(offered)),wanted.analysis().plan(),Memory.empty());
                h.assertTrue(decision.kind()==DecisionKind.PROPOSE && decision.proposal().quantities().goalAllocationUpperBound()==0 && decision.proposal().nativeChoice().choice().digest().equals(proposed.choice().digest()),"Choice/quantities changed across proposal boundary");
                var observations=new ArrayList<ChoiceObservation>();for(int batch=0;batch<proposed.choice().batches().size();batch++){var prepared=host.prepareChoice(bound,proposed.choice(),batch,run,authority,proposed.sources());for(var operation:List.of(ChoicePhase.WITHDRAW,ChoicePhase.CRAFT,ChoicePhase.DEPOSIT)){var result=host.executeChoice(prepared,operation);h.assertTrue(result.observation().kind()==ChoiceObservationKind.APPLIED && result.observation().choiceDigest().equals(proposed.choice().digest()) && !result.observation().saveConfirmed(),"Actual native planning host effect failed");observations.add(result.observation());prepared=result.continuation();}}
                int consumed=proposed.choice().exactInputs().values().stream().mapToInt(Math::toIntExact).sum();h.assertTrue(box.countItem(Items.OAK_PLANKS)+box.countItem(Items.BIRCH_PLANKS)==9-consumed && box.countItem(Items.WOODEN_AXE)==0 && v.getInventory().isEmpty(),"Material checkpoint lost inputs or invented a completed axe");
                var after=projection.stock();var remaining=new StaticProductionPlanner().analyse(projection.view(2,List.of()));h.assertTrue(remaining.feasibility()==Feasibility.FEASIBLE && remaining.plan().actions().size()<wanted.analysis().plan().actions().size(),"Selected material destroyed whole-goal feasibility");
                var row=new TreeMap<String,Object>();row.put("nativeHostOnly",true);row.put("authoritativeJobs",false);row.put("autonomousGameplay",false);row.put("methodPins","fixture-only; no actual admission/saved-method hash claim");row.put("rootIdentityKind","test-scope planning identity, not a production Job");row.put("modelInterfaceUsed",false);row.put("profiles",profiles);row.put("before",before);row.put("after",after);row.put("proposal",decision.proposal());row.put("choiceCanonical",proposed.choice().canonical());row.put("choiceDigest",proposed.choice().digest());row.put("observations",observations);row.put("remainingFeasibility",remaining.feasibility());row.put("initialActions",wanted.analysis().plan().actions().size());row.put("remainingActions",remaining.plan().actions().size());row.put("rootCredit",0);evidence("planning-material-checkpoint",row);
                world.release(actor,run);phase[0]=2;System.out.println("B05_NATIVE_HOST materialCheckpoint=true originalGoalUnchanged=true remainingFeasible=true Jobs=false autonomous=false");
            }catch(RuntimeException failure){evidence("first-failure",Map.of("nativeHostOnly",true,"failure",failure.toString()));throw new IllegalStateException("B05 first native host execution failed; no fixture retry",failure);}
        });
    }
}
