package dev.aivillages.providers;

import dev.aivillages.core.kernel.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.Contracts.*;

final class MaterialPlanningAdapterTest {
    private static MaterialPlan.Context facts(){return new MaterialPlan.Context("four planks",Map.of("minecraft:oak_planks",4L),Map.of("minecraft:oak_log",1L),false,false);}
    private static Generation.Request request(Generation.Role role,String context) {
        var seed=PlanningAdapterTest.request(context);
        var bound=new ValidatedRequest(new CapabilityRequest(MaterialPlan.ID,Map.of("amount",new IntValue(4))),seed.bound().context(),seed.bound().observation());
        return new Generation.Request(UUID.randomUUID(),bound,MaterialPlan.SPEC,List.of(),List.of(),role,context);
    }
    private static String candidate(){return "{\"schema\":1,\"status\":\"plan\",\"steps\":[{\"recipe\":\"minecraft:oak_planks\",\"batches\":1}],\"deliveries\":[{\"item\":\"minecraft:oak_planks\",\"amount\":4}]}";}
    private static Budgets.InferenceLimits limits(GenerationChecks.Time clock){return new Budgets.InferenceLimits(new Budgets.Limits(Map.of(
            Budgets.Kind.CALLS,2L,Budgets.Kind.REPAIRS,1L,Budgets.Kind.INPUT_BYTES,65536L,Budgets.Kind.OUTPUT_BYTES,16384L),clock.millis()+10000),32768,8192);}
    @Test void craftingPreviewHasItsOwnConstrainedShapeAndNeverBecomesSkillIr() throws Exception {
        try(var f=new GenerationChecks.Fixture()) {
            var limits=limits(f.clock);var h=f.adapter.generate(request(Generation.Role.PLANNING,facts().prompt()),limits,new Budgets.Ledger(limits.total(),f.clock));
            var wire=StrictJson.transportObject(f.backend.bodies.getFirst());
            assertEquals(StrictJson.canonical(MaterialPlan.outputShape(facts())),StrictJson.canonical(wire.get("format")));
            f.backend.respond(0,candidate(),1L,2L);assertEquals(candidate(),h.result().toCompletableFuture().join().candidateIr());
        }
    }
    @Test void planningRepairRetainsItsGrammarAndChargesTheOriginalRepairAllowance() {
        try(var f=new GenerationChecks.Fixture()) {
            var l=limits(f.clock);var ledger=new Budgets.Ledger(l.total(),f.clock);
            var feedback=PlanningFeedback.correction(facts().prompt(),candidate(),"QUANTITY","$.deliveries");
            var h=f.adapter.generate(request(Generation.Role.PLANNING_REPAIR,feedback),l,ledger);f.backend.respond(0,candidate(),1L,2L);
            assertEquals(Generation.Outcome.CANDIDATE,h.result().toCompletableFuture().join().outcome());
            assertEquals(1L,ledger.snapshot().get(Budgets.Kind.REPAIRS));assertEquals(1L,ledger.snapshot().get(Budgets.Kind.CALLS));
        }
    }
    @Test void brokerRejectsCrossedWheatAndMaterialContractsBeforeInference() {
        try(var f=new GenerationChecks.Fixture();var broker=new AIWorkBroker(f.adapter,f.clock,AIWorkBroker.Settings.defaults(),AIWorkBroker.sessionLimits(f.clock.millis()),r->true,AIWorkBroker.Sharing.privateScopes())) {
            var l=limits(f.clock);var ledger=new Budgets.Ledger(l.total(),f.clock);
            assertThrows(IllegalArgumentException.class,()->broker.submit(request(Generation.Role.PLANNING,"bound"),l,ledger,Plan.OUTPUT,AIWorkBroker.Priority.NORMAL));
            assertTrue(f.backend.bodies.isEmpty());
        }
    }
    @Test void grammaticalOutputStillRequiresDeterministicStockValidation() {
        var c=new MaterialPlan.Context("four planks",Map.of("minecraft:oak_planks",4L),Map.of(),false,false);
        assertFalse(MaterialPlan.validate(candidate(),c).accepted());
    }
    @Test void repairedGrammarUsesTheOriginalFactsAndNotThePreviousProposal() throws Exception {
        try(var f=new GenerationChecks.Fixture()) {
            var l=limits(f.clock);String feedback=PlanningFeedback.correction(facts().prompt(),"{\"status\":\"unsupported\"}","RESOURCE_STATUS","$.status");
            f.adapter.generate(request(Generation.Role.PLANNING_REPAIR,feedback),l,new Budgets.Ledger(l.total(),f.clock));
            var wire=StrictJson.transportObject(f.backend.bodies.getFirst());
            assertEquals(StrictJson.canonical(MaterialPlan.outputShape(facts())),StrictJson.canonical(wire.get("format")));
        }
    }
    @Test void realStockChangesTheGrammarWithoutAnyBenchmarkCaseIdentifier() throws Exception {
        try(var f=new GenerationChecks.Fixture()) {
            var l=limits(f.clock);var shortage=new MaterialPlan.Context("four planks",Map.of("minecraft:oak_planks",4L),Map.of(),false,false);
            f.adapter.generate(request(Generation.Role.PLANNING,shortage.prompt()),l,new Budgets.Ledger(l.total(),f.clock));
            var wire=StrictJson.transportObject(f.backend.bodies.getFirst());
            assertEquals(StrictJson.canonical(MaterialPlan.outputShape(shortage)),StrictJson.canonical(wire.get("format")));
            assertNotEquals(StrictJson.canonical(MaterialPlan.outputShape(facts())),StrictJson.canonical(wire.get("format")));
        }
    }
    @Test void wrongModelQuantityIsReturnedUnchangedAndRejectedByTheValidator() {
        try(var f=new GenerationChecks.Fixture()) {
            var l=limits(f.clock);var h=f.adapter.generate(request(Generation.Role.PLANNING,facts().prompt()),l,new Budgets.Ledger(l.total(),f.clock));
            String wrong=candidate().replace("\"amount\":4","\"amount\":3");f.backend.respond(0,wrong,1L,2L);
            var result=h.result().toCompletableFuture().join();assertEquals(wrong,result.candidateIr());
            assertEquals("QUANTITY",MaterialPlan.validate(result.candidateIr(),facts()).diagnostic());
        }
    }
    @Test void invalidMaterialContextCannotStartBackendInference() {
        try(var f=new GenerationChecks.Fixture()) {
            var l=limits(f.clock);var h=f.adapter.generate(request(Generation.Role.PLANNING,"{}"),l,new Budgets.Ledger(l.total(),f.clock));
            assertTrue(f.backend.bodies.isEmpty());assertNotEquals(Generation.Outcome.CANDIDATE,h.result().toCompletableFuture().join().outcome());
        }
    }
}
