package dev.aivillages.fabric;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.production.RecipeChoice.ItemKey;
import dev.aivillages.core.kernel.production.planning.*;
import dev.aivillages.core.kernel.production.planning.PlanningModel.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricProductionTravelTest {
    private static ContainerRef at(int x,int y,int z){return new ContainerRef("minecraft:overworld",x,y,z);}
    private static ItemKey item(String name){return new ItemKey("minecraft:"+name,"{}");}
    private static RecipeProfile profile(CapabilityId legacy,List<ItemKey> inputs,ItemKey output,long travel) {
        var id=new CapabilityId(legacy.name(),2);var semantic=RecipeTransaction.fingerprint("fixture:cost:"+legacy);
        return new RecipeProfile(RecipeProduction.of(legacy).recipe(),id,inputs.stream().map(List::of).toList(),output,
                semantic,new MethodPin(id,RecipeTransaction.fingerprint("fixture:method:"+legacy),semantic,true),
                new Cost(travel,4,100+6*travel));
    }
    @Test void actualThreeLogProjectionFitsOriginalLimitsWithoutTreatingSearchBoundsAsWalking() {
        // Projection-only compatibility: no Job, method admission, native effect or saved proof is manufactured.
        var source=at(70,208,69);var start=at(70,208,68);var destination=at(77,208,71);
        var site=new FacilityRef("minecraft:overworld",71,208,69);
        var logs=List.of(at(68,208,65),at(69,208,65),at(70,208,65));
        var area=new Cuboid(source.dimension(),66,207,65,74,209,73);
        var actor=new ActorRef(UUID.randomUUID(),UUID.randomUUID(),source.dimension());
        UUID world=UUID.randomUUID(),principal=UUID.randomUUID(),root=UUID.randomUUID();
        var context=new TrustedContext(new PrincipalRef(principal),new ScopeRef(world,principal));
        var limits=OakPlankProduction.executionLimits(1000).total();
        var original=new java.util.EnumMap<Counter,Long>(PlanningModel.zeroCounts());
        original.put(Counter.TRAVEL,limits.maximum(Budgets.Kind.TRAVEL_BLOCKS));
        original.put(Counter.EFFECTS,limits.maximum(Budgets.Kind.ATTEMPTED_EFFECTS));
        original.put(Counter.ELAPSED_TICKS,limits.maximum(Budgets.Kind.ELAPSED_TICKS));
        var budget=new Budget(original,PlanningModel.zeroCounts(),limits.deadlineEpochMillis());
        var log=item("oak_log");var plank=item("oak_planks");var stick=item("stick");var table=item("crafting_table");var axe=item("wooden_axe");
        long ordinary=FabricProductionTravel.recipe(start,source,source,null,site);
        var recipes=List.of(profile(OakPlankProduction.ID,List.of(log),plank,ordinary),
                profile(StickProduction.ID,List.of(plank,plank),stick,ordinary),
                profile(CraftingTableProduction.ID,List.of(plank,plank,plank,plank),table,ordinary),
                profile(WoodenAxeProduction.ID,List.of(plank,plank,plank,stick,stick),axe,
                        FabricProductionTravel.recipe(start,source,destination,site,site)));
        var method=new MethodPin(OakLogDelivery.ID,RecipeTransaction.fingerprint("fixture:log-method"),RecipeTransaction.fingerprint("fixture:log"),true);
        long travel=FabricProductionTravel.acquisition(start,source,logs);
        var acquisition=new Acquisition("observed-logs",area,log,3,Availability.PRESENT,true,true,method,new Cost(travel,4,120+6*travel));
        var placement=new MethodPin(CraftingTablePlacement.ID,RecipeTransaction.fingerprint("fixture:place"),RecipeTransaction.fingerprint("fixture:site"),true);
        long placing=FabricProductionTravel.placement(start,source,site);
        var facility=new Facility(site,Availability.ABSENT,true,true,"",true,0,placement,new Cost(placing,3,100+6*placing));
        var policy=new Policy(actor,Set.of(actor),Set.of(source),Set.of(destination),Set.of(area),source,site,true,false,0,16);
        var goal=new Goal(Mode.MAKE_NEW,axe,1,destination,policy);
        var snapshot=new Snapshot(PlanningModel.SCHEMA,world,root,1,context,new Owner(principal,actor,0,true,false,false,false),
                goal,destination,0,budget,1000,0,List.of(),List.of(acquisition),recipes,facility,List.of(),
                Set.of(Kind.PRODUCE,Kind.ACQUIRE,Kind.PLACE_TABLE),List.of(),true,false,0);
        var analysis=new StaticProductionPlanner().analyse(snapshot,1,8);
        assertEquals(Feasibility.FEASIBLE,analysis.feasibility(),analysis.reason());
        assertEquals(8,PlanningCoordinator.remainingSegments(snapshot,analysis.plan(),1));
        assertEquals(128,limits.maximum(Budgets.Kind.TRAVEL_BLOCKS));assertEquals(1800,limits.maximum(Budgets.Kind.ELAPSED_TICKS));
        assertTrue(budget.fits(analysis.allowance()));assertEquals(3,analysis.residual().get(plank));assertEquals(2,analysis.residual().get(stick));
    }
    @Test void acquisitionQuotesActualTargetsAndTheReturnLegWithoutChargingEmptySearchCells() {
        var source=at(70,208,69);var start=at(70,208,68);
        var logs=List.of(at(68,208,65),at(69,208,65),at(70,208,65));
        assertEquals(17,FabricProductionTravel.acquisition(start,source,logs));
        assertEquals(17,FabricProductionTravel.acquisition(at(68,208,67),source,logs));
        assertTrue(FabricProductionTravel.acquisition(at(80,208,78),source,logs)>17);
        assertEquals(13,FabricProductionTravel.acquisition(start,source,List.of(at(70,208,65))));
    }
    @Test void recipeQuotesIncludeFutureBatchAndPlacementEndpointsAndTheRequiredFacilityLeg() {
        var source=at(70,208,69);var start=at(70,208,68);var destination=at(77,208,71);
        var site=new FacilityRef("minecraft:overworld",71,208,69);
        assertEquals(6,FabricProductionTravel.recipe(start,source,source,null,site));
        assertEquals(23,FabricProductionTravel.recipe(start,source,destination,site,site));
        assertEquals(23,FabricProductionTravel.recipe(at(77,208,73),source,destination,site,site));
        assertTrue(FabricProductionTravel.recipe(at(90,208,90),source,destination,site,site)>23);
        assertEquals(12,FabricProductionTravel.placement(start,source,site));
    }
    @Test void missingTargetsAndCrossDimensionQuotesDoNotAdvertiseAUsableRoute() {
        assertThrows(IllegalArgumentException.class,()->FabricProductionTravel.acquisition(at(0,0,0),at(1,0,0),List.of()));
        assertThrows(IllegalArgumentException.class,()->FabricProductionTravel.recipe(at(0,0,0),at(1,0,0),
                new ContainerRef("minecraft:the_nether",1,0,0),null,null));
    }
    @Test void singleBatchReservesActualApproachSidesWithinTheOriginalTravelLimit() {
        var start=at(328,208,67);var source=at(326,208,66);var destination=at(329,208,66);
        assertEquals(11,FabricProductionTravel.recipe(start,source,destination,null,null));
        long quoted=FabricProductionTravel.singleBatchInputAndDelivery(start,source,destination);
        assertEquals(21,quoted);
        assertTrue(quoted<=OakPlankProduction.executionLimits(1000).total().maximum(Budgets.Kind.TRAVEL_BLOCKS));
    }
    @Test void fourBatchesAtAnExistingTableCanFitTheUnchangedTravelAllowance() {
        var start=at(80,208,67);var source=at(68,208,72);var destination=at(76,208,70);
        var table=new FacilityRef("minecraft:overworld",79,208,74);
        assertTrue(4*FabricProductionTravel.recipe(start,source,destination,table,table)>128);
        assertEquals(31,FabricProductionTravel.existingTableOnClearFloor(start,source,destination,table));
        assertTrue(4*FabricProductionTravel.existingTableOnClearFloor(start,source,destination,table)<=128);
    }
    @Test void largeBirchGoalQuotesFourNativeGroupsWithoutChangingTheOriginalCounter() {
        var start=at(80,208,67);var source=at(68,208,72);var destination=at(76,208,70);
        var actor=new ActorRef(UUID.randomUUID(),UUID.randomUUID(),source.dimension());
        UUID world=UUID.randomUUID(),principal=UUID.randomUUID(),root=UUID.randomUUID();
        var context=new TrustedContext(new PrincipalRef(principal),new ScopeRef(world,principal));
        var original=new java.util.EnumMap<Counter,Long>(PlanningModel.zeroCounts());
        original.put(Counter.TRAVEL,128L);original.put(Counter.EFFECTS,64L);original.put(Counter.ELAPSED_TICKS,1800L);
        var budget=new Budget(original,PlanningModel.zeroCounts(),100_000);
        var log=item("birch_log");var planks=item("birch_planks");
        var policy=new Policy(actor,Set.of(actor),Set.of(source),Set.of(destination),Set.of(),source,null,false,false,0,16,false,true);
        var snapshot=new Snapshot(PlanningModel.SCHEMA,world,root,1,context,new Owner(principal,actor,0,true,false,false,false),
                new Goal(Mode.MAKE_NEW,planks,63,destination,policy),destination,0,budget,1000,0,
                List.of(new Stock("source:26",new Location(source,null,26),log,16,16,0,null,List.of(),Eligibility.NONE,
                        Availability.PRESENT,true,true,10,1)),List.of(),
                List.of(profile(BirchPlankProduction.ID,List.of(log),planks,FabricProductionTravel.recipe(start,source,destination,null,null))),
                null,List.of(),Set.of(Kind.PRODUCE),List.of(),true,false,0);
        var decision=new PlanningCoordinator().nextActivity(snapshot,null,Memory.empty(),1,8,true);
        assertEquals(Feasibility.FEASIBLE,decision.analysis().feasibility(),decision.blocker());
        assertEquals(4,PlanningCoordinator.remainingSegments(snapshot,decision.analysis().plan(),1));
        assertEquals(4,decision.neededChoice().batches());
        var choice=PlanningChoiceBuilder.build(snapshot,decision.neededChoice()).choice();
        assertTrue(choice.groupedPlanks());
        assertEquals(16,choice.phaseDelivery(0));
        assertEquals(4,choice.phaseBatches(0).size());
        assertEquals(128,budget.remaining(Counter.TRAVEL));
    }
}
