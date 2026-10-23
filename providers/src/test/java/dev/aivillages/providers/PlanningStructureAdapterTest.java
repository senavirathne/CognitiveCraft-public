package dev.aivillages.providers;

import dev.aivillages.core.kernel.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.Contracts.*;

final class PlanningStructureAdapterTest {
    private static MaterialPlan.Context facts() {
        return new MaterialPlan.Context("Supply seven planks using one log",Map.of("minecraft:oak_planks",7L),Map.of("minecraft:oak_log",1L),false,false);
    }
    private static Generation.Request request(Generation.Role role,String context) {
        var seed=PlanningAdapterTest.request(context);
        var bound=new ValidatedRequest(new CapabilityRequest(MaterialPlan.ID,Map.of("amount",new IntValue(7))),seed.bound().context(),seed.bound().observation());
        return new Generation.Request(UUID.randomUUID(),bound,MaterialPlan.SPEC,List.of(),List.of(),role,context);
    }
    private static Budgets.InferenceLimits limits(GenerationChecks.Time clock) {
        return new Budgets.InferenceLimits(new Budgets.Limits(Map.of(Budgets.Kind.CALLS,2L,Budgets.Kind.REPAIRS,1L,
                Budgets.Kind.INPUT_BYTES,65536L,Budgets.Kind.OUTPUT_BYTES,16384L),clock.millis()+10000),32768,8192);
    }
    private static String refusal() { return "{\"schema\":1,\"status\":\"insufficient_resources\",\"steps\":[],\"deliveries\":[]}"; }

    @Test void initialAndRepairTransportsKeepTypedEmptyArrayBounds() throws Exception {
        var unseen=PlanningBenchmarkCases.load("structure-cases.json");assertEquals(6,unseen.size());
        assertEquals(6,unseen.stream().map(PlanningBenchmarkCases.Case::id).distinct().count());
        for(var c:unseen)assertEquals(c.expectedStatus(),MaterialPlan.requirements(c.material()).status(),c.id());
        for(var role:List.of(Generation.Role.PLANNING,Generation.Role.PLANNING_REPAIR)) try(var f=new GenerationChecks.Fixture()) {
            var l=limits(f.clock);String text=role.repair()?PlanningFeedback.correction(facts().prompt(),null,"MALFORMED","$.response"):facts().prompt();
            f.adapter.generate(request(role,text),l,new Budgets.Ledger(l.total(),f.clock));
            var wire=StrictJson.transportObject(f.backend.bodies.getFirst());
            assertEquals(StrictJson.canonical(MaterialPlan.outputShape(facts())),StrictJson.canonical(wire.get("format")));
            var props=(Map<?,?>)((Map<?,?>)wire.get("format")).get("properties");
            for(String name:List.of("steps","deliveries")) {
                var array=(Map<?,?>)props.get(name);assertEquals(0L,array.get("maxItems"));assertTrue(array.get("items") instanceof Map<?,?>);
            }
        }
    }

    @Test void nonObjectRefusalEntriesRemainRejectedAndCharged() {
        for(String bad:List.of(refusal().replace("\"steps\":[]","\"steps\":[\"explanation\"]"),
                refusal().replace("\"deliveries\":[]","\"deliveries\":[17]")))try(var f=new GenerationChecks.Fixture()) {
            var l=limits(f.clock);var ledger=new Budgets.Ledger(l.total(),f.clock);
            var h=f.adapter.generate(request(Generation.Role.PLANNING,facts().prompt()),l,ledger);f.backend.respond(0,bad,10L,12L);
            var result=h.result().toCompletableFuture().join();assertEquals(Generation.Outcome.MALFORMED,result.outcome());assertNull(result.candidateIr());
            assertEquals(bad.getBytes(StandardCharsets.UTF_8).length,result.usage().outputBytes());
            assertEquals(1L,ledger.snapshot().get(Budgets.Kind.CALLS));assertEquals((long)bad.getBytes(StandardCharsets.UTF_8).length,ledger.snapshot().get(Budgets.Kind.OUTPUT_BYTES));
        }
    }

    @Test void realRefusalBytesAreReturnedUnmodifiedAndValidatedIndependently() {
        try(var f=new GenerationChecks.Fixture()) {
            var l=limits(f.clock);var h=f.adapter.generate(request(Generation.Role.PLANNING,facts().prompt()),l,new Budgets.Ledger(l.total(),f.clock));
            f.backend.respond(0,refusal(),10L,12L);var result=h.result().toCompletableFuture().join();
            assertEquals(Generation.Outcome.CANDIDATE,result.outcome());assertEquals(refusal(),result.candidateIr());
            assertTrue(MaterialPlan.validate(result.candidateIr(),facts()).accepted());
            assertEquals(facts().inventory(),MaterialPlan.validate(result.candidateIr(),facts()).remainingInventory());
        }
    }

    @Test void syntaxAndAuthorityFailuresCannotBecomeCandidateIr() {
        for(String bad:List.of(refusal()+" trailing",refusal().substring(0,refusal().length()-1),
                refusal().replace("\"schema\":1","\"schema\":1,\"workers\":[\"foreign\"]"),
                refusal().replace("\"schema\":1","\"schema\":1,\"schema\":1")))try(var f=new GenerationChecks.Fixture()) {
            var l=limits(f.clock);var h=f.adapter.generate(request(Generation.Role.PLANNING,facts().prompt()),l,new Budgets.Ledger(l.total(),f.clock));
            f.backend.respond(0,bad,10L,12L);var result=h.result().toCompletableFuture().join();
            assertEquals(Generation.Outcome.MALFORMED,result.outcome());assertNull(result.candidateIr());
        }
    }
}
