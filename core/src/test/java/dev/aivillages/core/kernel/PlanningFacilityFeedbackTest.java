package dev.aivillages.core.kernel;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Safety fixtures only; genuine model measurements are collected separately. */
final class PlanningFacilityFeedbackTest {
    private static final String TABLE="minecraft:crafting_table", AXE="minecraft:wooden_axe";
    private static MaterialPlan.Context context(Map<String,Long> stock,boolean table,boolean place) {
        return new MaterialPlan.Context("Supply one wooden axe",Map.of(AXE,1L),stock,table,place);
    }
    private static Map<String,Object> step(String recipe,long batches) { return Map.of("recipe",recipe,"batches",batches); }
    private static String proposal(MaterialPlan.Context c,List<?> steps) {
        return StrictJson.canonical(Map.of("schema",1,"status","plan","steps",steps,"deliveries",
                c.goals().entrySet().stream().map(e->Map.of("item",e.getKey(),"amount",e.getValue())).toList()));
    }
    private static Map<String,Object> witness(MaterialPlan.Context c,List<?> steps,String reason) {
        String bad=proposal(c,steps);var check=MaterialPlan.validate(bad,c);
        assertFalse(check.accepted());assertEquals("FACILITY",check.diagnostic());
        var failure=MaterialPlan.facilityFailure(bad,c);assertEquals(reason,failure.get("reason"));return failure;
    }

    @Test void originalPrematurePlacementNamesMissingItemWithoutGrantingIt() throws Exception {
        var c=context(Map.of("minecraft:oak_planks",9L),false,true);
        var steps=List.of(step(MaterialPlan.PLACE,1),step(TABLE,1),step("minecraft:stick",1),step(AXE,1));
        var failure=witness(c,steps,"MISSING_TABLE_ITEM");
        assertEquals(0,failure.get("step"));assertEquals(0L,failure.get("tableItemsBeforeStep"));
        assertEquals(1L,failure.get("missingTableItemsForPlacement"));assertEquals(false,failure.get("tableAvailableBeforeStep"));
        assertEquals(c.inventory(),failure.get("stockBeforeStep"));assertEquals(Map.of("minecraft:oak_planks",9L),c.inventory());
        String bad=proposal(c,steps),repair=PlanningFeedback.correction(c.prompt(),bad,"FACILITY","$.steps[0]");
        var envelope=StrictJson.transportObject(repair);
        assertEquals("Omitted after facility rejection; use trusted context and facility-state witness.",envelope.get("previousProposal"));
        var details=(Map<?,?>)((Map<?,?>)envelope.get("validationError")).get("details");
        assertEquals(StrictJson.canonical(failure),StrictJson.canonical(details.get("facilityFailure")));
        assertEquals(c,MaterialPlan.contextFromPrompt(repair));assertFalse(MaterialPlan.validate(bad,c).accepted());
    }

    @Test void priorProducerChangesStockButDoesNotPlaceOrProduceATableItem() {
        var c=context(Map.of("minecraft:oak_log",3L),false,true);
        var failure=witness(c,List.of(step("minecraft:oak_planks",3),step(MaterialPlan.PLACE,1),step(TABLE,1),step("minecraft:stick",1),step(AXE,1)),"MISSING_TABLE_ITEM");
        assertEquals(1,failure.get("step"));assertEquals(Map.of("minecraft:oak_log",0L,"minecraft:oak_planks",12L),failure.get("stockBeforeStep"));
        assertEquals(Map.of("minecraft:oak_log",3L),c.inventory());
    }

    @Test void tableItemInInventoryIsNotAPlacedFacility() {
        var c=context(Map.of("minecraft:oak_planks",3L,"minecraft:stick",2L,TABLE,1L),false,true);
        var failure=witness(c,List.of(step(AXE,1),step(MaterialPlan.PLACE,1)),"TABLE_NOT_PLACED");
        assertEquals(1L,failure.get("tableItemsBeforeStep"));assertEquals(false,failure.get("tableAvailableBeforeStep"));
    }

    @Test void alreadyAvailableFacilityRejectsUnnecessaryPlacement() {
        var c=context(Map.of("minecraft:oak_planks",3L,"minecraft:stick",2L,TABLE,1L),true,true);
        var failure=witness(c,List.of(step(MaterialPlan.PLACE,1),step(AXE,1)),"TABLE_ALREADY_AVAILABLE");
        assertEquals(true,failure.get("tableAvailableBeforeStep"));assertEquals(1L,failure.get("tableItemsBeforeStep"));
    }

    @Test void repeatedPlacementReplaysTheFirstRealItemConsumption() {
        var c=context(Map.of("minecraft:oak_planks",3L,"minecraft:stick",2L,TABLE,2L),false,true);
        var failure=witness(c,List.of(step(MaterialPlan.PLACE,1),step(MaterialPlan.PLACE,1),step(AXE,1)),"TABLE_ALREADY_AVAILABLE");
        assertEquals(1,failure.get("step"));assertEquals(1L,failure.get("tableItemsBeforeStep"));assertEquals(true,failure.get("tableAvailableBeforeStep"));
    }

    @Test void itemPossessionDoesNotAuthorizePlacement() {
        var c=new MaterialPlan.Context("Deliver the stocked table item",Map.of(TABLE,1L),Map.of(TABLE,1L),false,false);
        var failure=witness(c,List.of(step(MaterialPlan.PLACE,1)),"PLACEMENT_NOT_AUTHORIZED");
        assertEquals(false,failure.get("placementAllowed"));assertEquals(Map.of(TABLE,1L),c.inventory());
    }

    @Test void placementBatchLimitRemainsAuthoritative() {
        var c=context(Map.of("minecraft:oak_planks",3L,"minecraft:stick",2L,TABLE,2L),false,true);
        witness(c,List.of(step(MaterialPlan.PLACE,2),step(AXE,1)),"INVALID_PLACEMENT_BATCHES");
    }

    @Test void unknownPlacementIsRejectedWithoutFacilityFeedbackOrRegistration() throws Exception {
        var c=context(Map.of("minecraft:oak_planks",3L,"minecraft:stick",2L),true,true);
        String bad=proposal(c,List.of(step("cognitivecraft:place_furnace",1),step(AXE,1)));
        assertEquals("UNSUPPORTED_CAPABILITY",MaterialPlan.validate(bad,c).diagnostic());
        assertTrue(MaterialPlan.facilityFailure(bad,c).isEmpty());
        assertFalse(StrictJson.canonical(MaterialPlan.outputShape(c)).contains("place_furnace"));
        assertEquals(bad,StrictJson.transportObject(PlanningFeedback.correction(c.prompt(),bad,"UNSUPPORTED_CAPABILITY","$.steps")).get("previousProposal"));
    }

    @Test void anEarlierResourceFailureCannotBecomeAFacilityWitness() {
        var c=context(Map.of("minecraft:oak_log",3L),false,true);
        String bad=proposal(c,List.of(step(TABLE,1),step(MaterialPlan.PLACE,1),step("minecraft:oak_planks",3),step("minecraft:stick",1),step(AXE,1)));
        assertEquals("FABRICATED_RESOURCE",MaterialPlan.validate(bad,c).diagnostic());assertTrue(MaterialPlan.facilityFailure(bad,c).isEmpty());
        assertTrue(MaterialPlan.facilityFailure("invalid",c).isEmpty());
    }

    @Test void supportedPlansKeepExactStockAndExistingFacilityBehavior() {
        var c=context(Map.of("minecraft:oak_planks",7L,"minecraft:stick",2L),false,true);
        String good=proposal(c,List.of(step(TABLE,1),step(MaterialPlan.PLACE,1),step(AXE,1)));
        assertTrue(MaterialPlan.validate(good,c).accepted());assertTrue(MaterialPlan.facilityFailure(good,c).isEmpty());
        assertEquals(Map.of("minecraft:oak_planks",0L,"minecraft:stick",0L,TABLE,0L,AXE,0L),MaterialPlan.validate(good,c).remainingInventory());
        var existing=context(Map.of("minecraft:oak_planks",3L,"minecraft:stick",2L),true,false);
        assertTrue(MaterialPlan.validate(proposal(existing,List.of(step(AXE,1))),existing).accepted());
    }

    @Test void unavailableFacilityAndUnsupportedGoalsKeepSafeRefusalStatuses() {
        var noPermission=context(Map.of("minecraft:oak_planks",3L,"minecraft:stick",2L,TABLE,1L),false,false);
        assertEquals("insufficient_resources",MaterialPlan.requirements(noPermission).status());
        var noItemInputs=context(Map.of("minecraft:oak_planks",6L,"minecraft:stick",2L),false,true);
        assertEquals("insufficient_resources",MaterialPlan.requirements(noItemInputs).status());
        var unsupported=new MaterialPlan.Context("A furnace placement is not registered",Map.of("minecraft:furnace",1L),Map.of("minecraft:oak_planks",64L),false,true);
        assertEquals("unsupported",MaterialPlan.requirements(unsupported).status());
    }

    @Test void maximalFacilityFeedbackKeepsTheOriginalEnvelopeAndTrustedFacts() throws Exception {
        var c=new MaterialPlan.Context("x".repeat(512),Map.of(AXE,4L,TABLE,3L,"minecraft:birch_planks",63L),
                Map.of("minecraft:oak_log",16L,"minecraft:birch_log",16L,"minecraft:oak_planks",0L,"minecraft:birch_planks",0L,"minecraft:stick",0L,AXE,0L,TABLE,0L),false,true);
        String bad=proposal(c,List.of(step(MaterialPlan.PLACE,1)));
        String feedback=PlanningFeedback.correction(c.prompt(),bad,"FACILITY","$.steps[0]");
        assertTrue(feedback.getBytes(StandardCharsets.UTF_8).length<=8192);assertEquals(c,MaterialPlan.contextFromPrompt(feedback));
        assertEquals(StrictJson.canonical(MaterialPlan.outputShape(c)),StrictJson.canonical(MaterialPlan.outputShape(MaterialPlan.contextFromPrompt(feedback))));
        assertEquals(2,PlanningFeedback.MAX_ATTEMPTS);
    }
}
