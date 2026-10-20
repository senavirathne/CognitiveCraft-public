package dev.aivillages.core.kernel;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PlanningConstraintTest {
    private static MaterialPlan.Context facts(){return new MaterialPlan.Context("one axe",Map.of("minecraft:wooden_axe",1L),Map.of("minecraft:oak_log",3L),false,true);}
    @Test void quantityGrammarChangesWithTheActualRequestRatherThanBenchmarkIdentity() {
        var f=new GoalPlannerTest.Fixture();
        assertNotEquals(StrictJson.canonical(Plan.outputShape(f.goal(2,3))),StrictJson.canonical(Plan.outputShape(f.goal(7,4))));
        assertTrue(StrictJson.canonical(Plan.outputShape(f.goal(7,4))).contains("\"enum\":[7]"));
    }
    @Test void invalidGoalsCannotDefineAQuantityGrammar() {
        var f=new GoalPlannerTest.Fixture();assertThrows(IllegalArgumentException.class,()->Plan.outputShape(f.goal(0,3)));
    }
    @Test void materialFactsRoundTripThroughBoundedRepairWithoutUsingCandidateFacts() throws Exception {
        var facts=facts();var text=PlanningFeedback.correction(facts.prompt(),"{\"inventory\":{\"minecraft:oak_log\":999}}","QUANTITY","$.deliveries");
        assertEquals(facts,MaterialPlan.contextFromPrompt(text));
    }
    @Test void unsupportedAndInsufficientRequestsHaveDifferentConstrainedRefusals() {
        var shortFacts=new MaterialPlan.Context("one axe",Map.of("minecraft:wooden_axe",1L),Map.of(),true,false);
        var unknown=new MaterialPlan.Context("torch",Map.of("minecraft:torch",1L),Map.of(),true,false);
        assertTrue(StrictJson.canonical(MaterialPlan.outputShape(shortFacts)).contains("insufficient_resources"));
        assertTrue(StrictJson.canonical(MaterialPlan.outputShape(unknown)).contains("unsupported"));
        assertNotEquals(MaterialPlan.outputShape(shortFacts),MaterialPlan.outputShape(unknown));
    }
    @Test void malformedFactsCannotBecomeARefinedGrammar() {
        assertThrows(Exception.class,()->MaterialPlan.contextFromPrompt("{}"));
        assertThrows(Exception.class,()->MaterialPlan.contextFromPrompt("{\"trustedContext\":\"{}\",\"availableInventory\":{\"minecraft:oak_log\":100}}"));
    }
    @Test void dependencyHintsDoNotGrantAPlanOrBypassOrderedStockValidation() {
        var facts=facts();assertTrue(facts.prompt().contains("\"before\":\"minecraft:crafting_table\""));
        String wrong="{\"schema\":1,\"status\":\"plan\",\"steps\":[{\"recipe\":\"minecraft:wooden_axe\",\"batches\":1}],\"deliveries\":[{\"item\":\"minecraft:wooden_axe\",\"amount\":1}]}";
        assertFalse(MaterialPlan.validate(wrong,facts).accepted());
    }
    @Test void feedbackExposesExpectedAndObservedSlotTotalsWithoutChangingThem() {
        var f=new GoalPlannerTest.Fixture();var root=f.create();var context=f.prepare(root);
        var wrong=Plan.encode(new Plan.Proposal(List.of(new Plan.Node(0,0,5,List.of()),new Plan.Node(1,1,4,List.of()))));
        String feedback=PlanningFeedback.correction(context.prompt(),wrong,"QUANTITY","$.nodes.amount");
        assertTrue(feedback.contains("observedSlotTotals"));assertTrue(feedback.contains("expectedSlotTotals"));
        assertEquals(2,((dev.aivillages.core.kernel.Contracts.IntValue)context.slots().getFirst().bound().request().arguments().get("amount")).value());
    }
}
