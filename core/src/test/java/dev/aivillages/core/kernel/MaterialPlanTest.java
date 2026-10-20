package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class MaterialPlanTest {
    private static final String AXE="minecraft:wooden_axe", PLANK="minecraft:oak_planks", LOG="minecraft:oak_log", STICK="minecraft:stick", TABLE="minecraft:crafting_table";
    private static MaterialPlan.Context context(Map<String,Long> goals, Map<String,Long> stock, boolean table, boolean place) {
        return new MaterialPlan.Context("bounded request",goals,stock,table,place);
    }
    private static String proposal(String status, List<Map<String,Object>> steps, Map<String,Long> deliveries) {
        return StrictJson.canonical(Map.of("schema",1,"status",status,"steps",steps,"deliveries",deliveries.entrySet().stream()
                .map(e->Map.of("item",e.getKey(),"amount",e.getValue())).toList()));
    }
    private static Map<String,Object> craft(String recipe,int batches) {return Map.of("recipe","minecraft:"+recipe,"batches",batches);}
    @Test void multistepAxeUsesRegisteredRecipeRatiosAndPreservesRemainders() {
        var c=context(Map.of(AXE,1L),Map.of(LOG,2L),true,false);
        var p=proposal("plan",List.of(craft("oak_planks",2),craft("stick",1),craft("wooden_axe",1)),c.goals());
        var check=MaterialPlan.validate(p,c);assertTrue(check.accepted(),check.toString());
        assertEquals(3L,check.remainingInventory().get(PLANK));assertEquals(2L,check.remainingInventory().get(STICK));
    }
    @Test void optionalFacilityConsumesOneActualTableBeforeCrafting() {
        var c=context(Map.of(AXE,1L),Map.of(LOG,3L),false,true);
        var p=proposal("plan",List.of(craft("oak_planks",3),craft("crafting_table",1),Map.of("recipe",MaterialPlan.PLACE,"batches",1),craft("stick",1),craft("wooden_axe",1)),c.goals());
        var r=MaterialPlan.validate(p,c);assertTrue(r.accepted(),r.toString());assertEquals(0L,r.remainingInventory().get(TABLE));
    }
    @Test void shortageIsProvenFromTotalDemandIncludingSharedIngredients() {
        var c=context(Map.of(AXE,2L),Map.of(LOG,1L),true,false);
        assertEquals("insufficient_resources",MaterialPlan.requirements(c).status());
        assertTrue(MaterialPlan.validate(proposal("insufficient_resources",List.of(),Map.of()),c).accepted());
        assertFalse(MaterialPlan.validate(proposal("plan",List.of(craft("wooden_axe",2)),c.goals()),c).accepted());
    }
    @Test void feasibleRequestsCannotPassWithFalseShortageRefusals() {
        var c=context(Map.of(STICK,4L),Map.of(PLANK,2L),false,false);
        assertFalse(MaterialPlan.validate(proposal("insufficient_resources",List.of(),Map.of()),c).accepted());
    }
    @Test void futureCraftedInputsCannotBeBorrowedFromLaterSteps() {
        var c=context(Map.of(AXE,1L),Map.of(LOG,2L),true,false);
        var p=proposal("plan",List.of(craft("wooden_axe",1),craft("oak_planks",2),craft("stick",1)),c.goals());
        assertEquals("FABRICATED_RESOURCE",MaterialPlan.validate(p,c).diagnostic());
    }
    @Test void excessBatchesAndChangedDeliveryQuantitiesAreRejected() {
        var c=context(Map.of(PLANK,4L),Map.of(LOG,3L),false,false);
        assertFalse(MaterialPlan.validate(proposal("plan",List.of(craft("oak_planks",2)),c.goals()),c).accepted());
        assertFalse(MaterialPlan.validate(proposal("plan",List.of(craft("oak_planks",1)),Map.of(PLANK,8L)),c).accepted());
    }
    @Test void missingFacilityIsNotInventedAndPlacementRequiresItsExplicitPermission() {
        var c=context(Map.of(AXE,1L),Map.of(PLANK,20L,STICK,20L,TABLE,1L),false,false);
        assertEquals("insufficient_resources",MaterialPlan.requirements(c).status());
        assertFalse(MaterialPlan.validate(proposal("plan",List.of(craft("wooden_axe",1)),c.goals()),c).accepted());
    }
    @Test void multipleTargetsReserveTheirOwnStockBeforeExpandingPrerequisites() {
        var c=context(Map.of(AXE,1L,PLANK,4L),Map.of(LOG,3L),true,false);
        assertEquals(Map.of("minecraft:oak_planks",3L,"minecraft:stick",1L,"minecraft:wooden_axe",1L),MaterialPlan.requirements(c).batches());
        var p=proposal("plan",List.of(craft("oak_planks",3),craft("stick",1),craft("wooden_axe",1)),c.goals());
        assertTrue(MaterialPlan.validate(p,c).accepted());
    }
    @Test void unsupportedRecipesAndExtraAuthorityFieldsCannotCrossTheBoundary() {
        var c=context(Map.of("minecraft:torch",4L),Map.of(LOG,4L),false,true);
        assertTrue(MaterialPlan.validate(proposal("unsupported",List.of(),Map.of()),c).accepted());
        assertFalse(MaterialPlan.validate(proposal("plan",List.of(craft("torch",1)),c.goals()),c).accepted());
        assertFalse(MaterialPlan.validate(proposal("unsupported",List.of(),Map.of()).replace("\"schema\":1","\"schema\":1,\"commands\":[]"),c).accepted());
    }
    @Test void currentStockNeedsNoCraftingAndRoundingRetainsOnlyWholeBatchSurplus() {
        var existing=context(Map.of(AXE,1L),Map.of(AXE,1L),false,false);
        assertTrue(MaterialPlan.validate(proposal("plan",List.of(),existing.goals()),existing).accepted());
        var odd=context(Map.of(PLANK,5L),Map.of(LOG,2L),false,false);
        var r=MaterialPlan.validate(proposal("plan",List.of(craft("oak_planks",2)),odd.goals()),odd);
        assertTrue(r.accepted());assertEquals(3L,r.remainingInventory().get(PLANK));
    }
}
