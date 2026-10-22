package dev.aivillages.providers;

import dev.aivillages.core.kernel.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.Contracts.*;

/** Transport/safety fixtures only; genuine outcomes come from PlanningLiveBenchmark. */
final class PlanningResourceGroundingAdapterTest {
    private static Generation.Request request(MaterialPlan.Context c,Generation.Role role,String text) {
        var seed=PlanningAdapterTest.request(text);
        var bound=new ValidatedRequest(new CapabilityRequest(MaterialPlan.ID,Map.of("amount",new IntValue(c.goals().values().stream().mapToLong(Long::longValue).sum()))),seed.bound().context(),seed.bound().observation());
        return new Generation.Request(UUID.randomUUID(),bound,MaterialPlan.SPEC,List.of(),List.of(),role,text);
    }
    private static Budgets.InferenceLimits limits(GenerationChecks.Time clock) {
        return new Budgets.InferenceLimits(new Budgets.Limits(Map.of(Budgets.Kind.CALLS,2L,Budgets.Kind.REPAIRS,1L,
                Budgets.Kind.INPUT_BYTES,65536L,Budgets.Kind.OUTPUT_BYTES,16384L),clock.millis()+10000),32768,8192);
    }
    @Test void independentCorpusUsesSupportedResourceChainsAndSafeNegativeStatuses() throws Exception {
        var cases=PlanningBenchmarkCases.load("resource-cases.json");assertEquals(10,cases.size());
        assertEquals(10,cases.stream().map(PlanningBenchmarkCases.Case::id).distinct().count());
        assertEquals(6,cases.stream().filter(c->c.expectedStatus().equals("plan")).count());
        for(var c:cases)assertEquals(c.expectedStatus(),MaterialPlan.requirements(c.material()).status(),c.id());
    }

    @Test void syntacticCandidateRemainsUnmodifiedAndCannotSpendNonexistentStock() {
        var c=new MaterialPlan.Context("Two table items from two logs",Map.of("minecraft:crafting_table",2L),Map.of("minecraft:oak_log",2L),false,false);
        String bad="{\"schema\":1,\"status\":\"plan\",\"steps\":[{\"recipe\":\"minecraft:crafting_table\",\"batches\":2},{\"recipe\":\"minecraft:oak_planks\",\"batches\":2}],\"deliveries\":[{\"item\":\"minecraft:crafting_table\",\"amount\":2}]}";
        try(var f=new GenerationChecks.Fixture()) {
            var l=limits(f.clock);var ledger=new Budgets.Ledger(l.total(),f.clock);
            var h=f.adapter.generate(request(c,Generation.Role.PLANNING,c.prompt()),l,ledger);f.backend.respond(0,bad,10L,12L);
            var result=h.result().toCompletableFuture().join();assertEquals(Generation.Outcome.CANDIDATE,result.outcome());assertEquals(bad,result.candidateIr());
            var check=MaterialPlan.validate(result.candidateIr(),c);assertFalse(check.accepted());assertEquals("FABRICATED_RESOURCE",check.diagnostic());
            assertEquals(Map.of("minecraft:oak_log",2L),c.inventory());assertEquals(1L,ledger.snapshot().get(Budgets.Kind.CALLS));
        }
    }

    @Test void originalAndRepairRequestsFitWireCapsAndUseOnlyOriginalCanonicalFacts() throws Exception {
        var cases=new ArrayList<>(PlanningBenchmarkCases.load("acceptance-requests.json"));cases.addAll(PlanningBenchmarkCases.load("resource-cases.json"));
        var stock=Map.of("minecraft:oak_log",16L,"minecraft:birch_log",16L,"minecraft:oak_planks",0L,"minecraft:birch_planks",0L,"minecraft:stick",0L,"minecraft:wooden_axe",0L,"minecraft:crafting_table",0L);
        cases.add(new PlanningBenchmarkCases.Case("maximum-envelope","unit-only","", "plan",0,0,new MaterialPlan.Context("x".repeat(512),Map.of("minecraft:wooden_axe",4L,"minecraft:crafting_table",3L,"minecraft:birch_planks",63L),stock,false,true)));
        for(var row:cases)if(row.material()!=null)for(var role:List.of(Generation.Role.PLANNING,Generation.Role.PLANNING_REPAIR))try(var f=new GenerationChecks.Fixture()) {
            var c=row.material();String text=role.repair()?PlanningFeedback.correction(c.prompt(),"x".repeat(8192),"MALFORMED","$"):c.prompt();
            var l=limits(f.clock);f.adapter.generate(request(c,role,text),l,new Budgets.Ledger(l.total(),f.clock));
            assertEquals(1,f.backend.bodies.size(),row.id());String body=f.backend.bodies.getFirst();assertTrue(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=16384,row.id());
            var wire=StrictJson.transportObject(body);assertEquals(StrictJson.canonical(MaterialPlan.outputShape(c)),StrictJson.canonical(wire.get("format")));
            assertEquals(c,MaterialPlan.contextFromPrompt(text));
        }
    }
}
