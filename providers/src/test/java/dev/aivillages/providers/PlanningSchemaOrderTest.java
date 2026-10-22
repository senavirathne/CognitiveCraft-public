package dev.aivillages.providers;

import dev.aivillages.core.kernel.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.Contracts.*;

/** Deterministic wire/safety tests. No fixture is reported as actual-model evidence. */
final class PlanningSchemaOrderTest {
    private static MaterialPlan.Context sticks() {
        return new MaterialPlan.Context("Supply five sticks from one oak log",Map.of("minecraft:stick",5L),Map.of("minecraft:oak_log",1L),false,false);
    }
    private static Generation.Request request(MaterialPlan.Context c,Generation.Role role,String text) {
        var seed=PlanningAdapterTest.request(text);
        var bound=new ValidatedRequest(new CapabilityRequest(MaterialPlan.ID,Map.of("amount",new IntValue(c.goals().values().stream().mapToLong(Long::longValue).sum()))),seed.bound().context(),seed.bound().observation());
        return new Generation.Request(UUID.randomUUID(),bound,MaterialPlan.SPEC,List.of(),List.of(),role,text);
    }
    private static Budgets.InferenceLimits limits(GenerationChecks.Time clock) {
        return new Budgets.InferenceLimits(new Budgets.Limits(Map.of(Budgets.Kind.CALLS,2L,Budgets.Kind.REPAIRS,1L,
                Budgets.Kind.INPUT_BYTES,65536L,Budgets.Kind.OUTPUT_BYTES,16384L),clock.millis()+10000),32768,8192);
    }
    private static Map<?,?> properties(Object shape) { return (Map<?,?>)((Map<?,?>)shape).get("properties"); }
    private static void assertContractOrder(Object format) {
        var root=properties(format);assertEquals(List.of("schema","status","steps","deliveries"),new ArrayList<>(root.keySet()));
        var steps=(Map<?,?>)root.get("steps");var items=(Map<?,?>)steps.get("items");
        if(items.containsKey("anyOf"))for(Object choice:(List<?>)items.get("anyOf"))
            assertEquals(List.of("recipe","batches"),new ArrayList<>(properties(choice).keySet()));
        var deliveries=(Map<?,?>)root.get("deliveries");var deliveryItems=(Map<?,?>)deliveries.get("items");
        if(deliveryItems.containsKey("anyOf"))for(Object choice:(List<?>)deliveryItems.get("anyOf"))
            assertEquals(List.of("item","amount"),new ArrayList<>(properties(choice).keySet()));
    }

    @Test void wireSchemaUsesContractOrderWithoutChangingAnyAllowedValueOrArray() throws Exception {
        var shape=MaterialPlan.outputShape(sticks());var payload=Map.of("format",shape,"context","unchanged");
        String wire=PlanningSchemaWire.request(payload);var parsed=StrictJson.transportObject(wire);
        assertEquals(StrictJson.canonical(payload),StrictJson.canonical(parsed));
        assertContractOrder(parsed.get("format"));
        assertEquals(StrictJson.canonical(payload).getBytes(StandardCharsets.UTF_8).length,wire.getBytes(StandardCharsets.UTF_8).length);
        assertNotEquals(StrictJson.canonical(payload),wire);
    }

    @Test void schemaOrderingDoesNotReinterpretEnumLiteralsOrInventProperties() throws Exception {
        var literal=Map.of("properties",Map.of("a",1,"z",2),"required",List.of("z","a"));
        var shape=Map.of("type","object","required",List.of("z"),"properties",
                Map.of("z",Map.of("enum",List.of(literal)),"a",Map.of("type","string")));
        var payload=Map.of("format",shape,"messages",List.of(Map.of("content","quote \" slash \\ newline\n日本語")));
        String wire=PlanningSchemaWire.request(payload);var parsed=StrictJson.transportObject(wire);
        assertEquals(StrictJson.canonical(payload),StrictJson.canonical(parsed));
        assertEquals(List.of("z","a"),new ArrayList<>(properties(parsed.get("format")).keySet()));
        assertTrue(wire.contains(StrictJson.canonical(literal)));
    }

    @Test void planningAndRepairUseTheSameTrustedShapeAndContext() throws Exception {
        var c=sticks();
        for(var role:List.of(Generation.Role.PLANNING,Generation.Role.PLANNING_REPAIR))try(var f=new GenerationChecks.Fixture()) {
            String text=role.repair()?PlanningFeedback.correction(c.prompt(),"invalid","MALFORMED","$"):c.prompt();
            var l=limits(f.clock);f.adapter.generate(request(c,role,text),l,new Budgets.Ledger(l.total(),f.clock));
            var wire=StrictJson.transportObject(f.backend.bodies.getFirst());assertContractOrder(wire.get("format"));
            assertEquals(StrictJson.canonical(MaterialPlan.outputShape(c)),StrictJson.canonical(wire.get("format")));
            assertEquals(c,MaterialPlan.contextFromPrompt(text));
            assertEquals(text,((Map<?,?>)((List<?>)wire.get("messages")).getLast()).get("content"));
        }
    }

    @Test void wheatPlanningAndSkillGenerationKeepTheirCanonicalWireEncoding() throws Exception {
        for(var request:List.of(PlanningAdapterTest.request("legacy wheat planning"),GenerationChecks.request(Generation.Role.INITIAL,"legacy skill generation")))
            try(var f=new GenerationChecks.Fixture()) {
                var l=limits(f.clock);f.adapter.generate(request,l,new Budgets.Ledger(l.total(),f.clock));
                String wire=f.backend.bodies.getFirst();assertEquals(wire,StrictJson.canonical(StrictJson.transportObject(wire)));
            }
    }

    @Test void changedFieldOrderDoesNotRepairOrAdmitAnInvalidStepSequence() {
        var c=sticks();String bad="{\"schema\":1,\"status\":\"plan\",\"steps\":[{\"recipe\":\"minecraft:stick\",\"batches\":2},{\"recipe\":\"minecraft:oak_planks\",\"batches\":1}],\"deliveries\":[{\"item\":\"minecraft:stick\",\"amount\":5}]}";
        try(var f=new GenerationChecks.Fixture()) {
            var l=limits(f.clock);var ledger=new Budgets.Ledger(l.total(),f.clock);
            var handle=f.adapter.generate(request(c,Generation.Role.PLANNING,c.prompt()),l,ledger);f.backend.respond(0,bad,10L,12L);
            var result=handle.result().toCompletableFuture().join();assertEquals(Generation.Outcome.CANDIDATE,result.outcome());assertEquals(bad,result.candidateIr());
            assertEquals("FABRICATED_RESOURCE",MaterialPlan.validate(result.candidateIr(),c).diagnostic());
            assertEquals(Map.of("minecraft:oak_log",1L),c.inventory());assertEquals(1L,ledger.snapshot().get(Budgets.Kind.CALLS));
        }
    }

    @Test void independentHeldOutCasesKeepTheirDeclaredFeasibility() throws Exception {
        var cases=PlanningBenchmarkCases.load("order-cases.json");assertEquals(8,cases.size());
        assertEquals(8,cases.stream().map(PlanningBenchmarkCases.Case::id).distinct().count());
        assertEquals(5,cases.stream().filter(c->c.expectedStatus().equals("plan")).count());
        for(var c:cases)assertEquals(c.expectedStatus(),MaterialPlan.requirements(c.material()).status(),c.id());
    }

    @Test void allOriginalAndSupplementalContextsKeepTheSameWireByteCaps() throws Exception {
        var cases=new ArrayList<PlanningBenchmarkCases.Case>();
        for(String name:List.of("acceptance-requests.json","structure-cases.json","resource-cases.json","facility-cases.json","order-cases.json"))cases.addAll(PlanningBenchmarkCases.load(name));
        cases.add(new PlanningBenchmarkCases.Case("maximum-envelope","unit-only","","plan",0,0,new MaterialPlan.Context("x".repeat(512),
                Map.of("minecraft:wooden_axe",4L,"minecraft:crafting_table",3L,"minecraft:birch_planks",63L),
                Map.of("minecraft:oak_log",16L,"minecraft:birch_log",16L,"minecraft:oak_planks",0L,"minecraft:birch_planks",0L,"minecraft:stick",0L,"minecraft:wooden_axe",0L,"minecraft:crafting_table",0L),false,true)));
        for(var row:cases)if(row.material()!=null)for(var role:List.of(Generation.Role.PLANNING,Generation.Role.PLANNING_REPAIR))
            try(var f=new GenerationChecks.Fixture()) {
                var c=row.material();String text=role.repair()?PlanningFeedback.correction(c.prompt(),"x".repeat(8192),"MALFORMED","$"):c.prompt();
                var l=limits(f.clock);f.adapter.generate(request(c,role,text),l,new Budgets.Ledger(l.total(),f.clock));
                assertEquals(1,f.backend.bodies.size(),row.id());String body=f.backend.bodies.getFirst();var parsed=StrictJson.transportObject(body);
                assertTrue(body.getBytes(StandardCharsets.UTF_8).length<=16384,row.id());assertContractOrder(parsed.get("format"));
                assertEquals(StrictJson.canonical(MaterialPlan.outputShape(c)),StrictJson.canonical(parsed.get("format")));
                assertEquals(body.getBytes(StandardCharsets.UTF_8).length,StrictJson.canonical(parsed).getBytes(StandardCharsets.UTF_8).length);
            }
    }
}
