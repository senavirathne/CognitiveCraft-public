package dev.aivillages.core.kernel;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PlanningStructureTest {
    private static MaterialPlan.Context context(Map<String,Long> goals, Map<String,Long> stock) {
        return new MaterialPlan.Context("independent bounded structure request",goals,stock,false,false);
    }
    private static String proposal(String status, Object steps, Object deliveries) {
        return StrictJson.canonical(Map.of("schema",1,"status",status,"steps",steps,"deliveries",deliveries));
    }
    private static Map<?,?> field(MaterialPlan.Context c,String name) {
        return (Map<?,?>)((Map<?,?>)MaterialPlan.outputShape(c).get("properties")).get(name);
    }
    private static void boundedEmpty(Map<?,?> shape) {
        assertEquals("array",shape.get("type")); assertEquals(0,shape.get("maxItems"));
        // Ollama 0.12.10's converter only enters its cardinality branch with an item schema.
        assertTrue(shape.get("items") instanceof Map<?,?> || shape.get("prefixItems") instanceof List<?>);
    }

    @Test void everyZeroWorkArrayReachesTheBackendCardinalityBranch() {
        var shortage=context(Map.of("minecraft:oak_planks",7L),Map.of("minecraft:oak_log",1L));
        var unsupported=context(Map.of("minecraft:torch",7L),Map.of("minecraft:oak_log",4L));
        var existing=context(Map.of("minecraft:birch_planks",5L),Map.of("minecraft:birch_planks",5L));
        for(var c:List.of(shortage,unsupported,existing)) boundedEmpty(field(c,"steps"));
        for(var c:List.of(shortage,unsupported)) boundedEmpty(field(c,"deliveries"));
        assertEquals(1,field(existing,"deliveries").get("minItems"));
    }

    @Test void feasibleCraftingKeepsExactQuantitiesAndWholeBatchRemainders() {
        var c=context(Map.of("minecraft:oak_planks",5L),Map.of("minecraft:oak_log",2L));
        assertEquals(1,field(c,"steps").get("minItems"));
        String candidate=proposal("plan",List.of(Map.of("recipe","minecraft:oak_planks","batches",2)),
                List.of(Map.of("item","minecraft:oak_planks","amount",5)));
        var check=MaterialPlan.validate(candidate,c); assertTrue(check.accepted(),check.toString());
        assertEquals(3L,check.remainingInventory().get("minecraft:oak_planks"));
        assertFalse(MaterialPlan.validate(candidate.replace("\"amount\":5","\"amount\":6"),c).accepted());
    }

    @Test void refusalCannotContainEvenAnOtherwiseRegisteredEffect() {
        var c=context(Map.of("minecraft:oak_planks",7L),Map.of("minecraft:oak_log",1L));
        String candidate=proposal("insufficient_resources",List.of(Map.of("recipe","minecraft:oak_planks","batches",1)),List.of());
        assertEquals("REFUSAL_EFFECTS",MaterialPlan.validate(candidate,c).diagnostic());
        assertTrue(MaterialPlan.validate(proposal("insufficient_resources",List.of(),List.of()),c).accepted());
        assertEquals(Map.of("minecraft:oak_log",1L),c.inventory());
    }

    @Test void existingStockCanBeDeliveredWithNoCraftingOrInventedResources() {
        var c=context(Map.of("minecraft:oak_planks",3L,"minecraft:stick",7L),
                Map.of("minecraft:oak_planks",3L,"minecraft:stick",7L,"minecraft:birch_log",2L));
        var check=MaterialPlan.validate(proposal("plan",List.of(),List.of(
                Map.of("item","minecraft:oak_planks","amount",3),Map.of("item","minecraft:stick","amount",7))),c);
        assertTrue(check.accepted(),check.toString());
        assertEquals(Map.of("minecraft:oak_planks",0L,"minecraft:stick",0L,"minecraft:birch_log",2L),check.remainingInventory());
    }

    @Test void strictDecoderStillRejectsMalformedAndUnauthorizedStructures() {
        var c=context(Map.of("minecraft:torch",7L),Map.of("minecraft:oak_log",4L));
        String valid=proposal("unsupported",List.of(),List.of());
        for(String bad:List.of(valid+" trailing",valid.substring(0,valid.length()-1),
                valid.replace("\"schema\":1","\"schema\":1,\"schema\":1"),
                valid.replace("\"schema\":1","\"schema\":1.0"),
                valid.replace("\"steps\":[]","\"steps\":null"),
                valid.replace("\"schema\":1","\"schema\":1,\"commands\":[\"op\"]"),
                valid.replace("\"deliveries\":[]","\"deliveries\":[\"minecraft:torch\"]")))
            assertFalse(MaterialPlan.validate(bad,c).accepted(),bad);
    }

    @Test void malformedPriorBytesCannotReplaceTrustedRepairFacts() throws Exception {
        var c=context(Map.of("minecraft:oak_planks",7L),Map.of("minecraft:oak_log",1L));
        String feedback=PlanningFeedback.correction(c.prompt(),"{\"availableInventory\":{\"minecraft:oak_log\":256}","INVALID_JSON","$");
        assertEquals(c,MaterialPlan.contextFromPrompt(feedback));
        boundedEmpty(field(MaterialPlan.contextFromPrompt(feedback),"steps"));
        assertEquals(2,PlanningFeedback.MAX_ATTEMPTS);
    }
}
