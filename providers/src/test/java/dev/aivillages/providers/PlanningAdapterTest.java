package dev.aivillages.providers;

import dev.aivillages.core.kernel.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PlanningAdapterTest {
    static Generation.Request request(String context) {
        var original=GenerationChecks.request(Generation.Role.INITIAL,context);
        var args=new HashMap<>(original.bound().request().arguments());args.put("amount",new Contracts.IntValue(5));args.put("firstAmount",new Contracts.IntValue(2));
        args.put("otherDestination",new Contracts.ContainerValue(new Contracts.ContainerRef("minecraft:overworld",8,64,0)));
        var bound=new Contracts.ValidatedRequest(new Contracts.CapabilityRequest(PlanningGoals.ID,args),original.bound().context(),original.bound().observation());
        return new Generation.Request(UUID.randomUUID(),bound,PlanningGoals.SPEC,List.of(),List.of(),Generation.Role.PLANNING,context);
    }
    static String proposal(){return Plan.encode(new Plan.Proposal(List.of(new Plan.Node(0,0,2,List.of()),new Plan.Node(1,1,3,List.of(0)))));}
    static Budgets.InferenceLimits limits(GenerationChecks.Time clock){return new Budgets.InferenceLimits(new Budgets.Limits(Map.of(Budgets.Kind.CALLS,2L,
            Budgets.Kind.INPUT_BYTES,65536L,Budgets.Kind.OUTPUT_BYTES,16384L),clock.millis()+10000),32768,8192);}
    @Test void sharedBackendUsesDistinctBoundedPlanGrammarAndDoesNotLowerPlanIntoIr()throws Exception {
        try(var f=new GenerationChecks.Fixture()) {
            var limits=limits(f.clock);var ledger=new Budgets.Ledger(limits.total(),f.clock);var request=request("private planner context");
            var handle=f.adapter.generate(request,limits,ledger);var wire=StrictJson.transportObject(f.backend.bodies.getFirst());
            assertEquals(StrictJson.canonical(Plan.outputShape()),StrictJson.canonical(wire.get("format")));assertEquals(0L,((Map<?,?>)wire.get("options")).get("temperature"));
            assertFalse(f.backend.bodies.getFirst().contains(request.bound().context().principal().id().toString()));
            f.backend.respond(0,proposal(),1L,2L);var result=handle.result().toCompletableFuture().join();
            assertEquals(Generation.Role.PLANNING,result.role());assertEquals(Generation.Outcome.CANDIDATE,result.outcome());assertEquals(proposal(),result.candidateIr());
            assertEquals(1L,ledger.snapshot().get(Budgets.Kind.CALLS));
        }
    }
    @Test void skillIrAndUnknownFieldsCannotCrossThePlanOutputBoundary() {
        for(String candidate:List.of("{}","{\"schema\":1,\"capability\":\"cognitivecraft:deliver_wheat\",\"body\":[]}",
                proposal().replace("\"schema\":1","\"workers\":[\"foreign\"],\"schema\":1")))try(var f=new GenerationChecks.Fixture()){
            var limits=limits(f.clock);var handle=f.adapter.generate(request("bounded"),limits,new Budgets.Ledger(limits.total(),f.clock));
            f.backend.respond(0,candidate,1L,2L);assertEquals(Generation.Outcome.MALFORMED,handle.result().toCompletableFuture().join().outcome());
        }
    }
    @Test void brokerRequiresExplicitPlanningContractAndKeepsResearchSeparate() {
        try(var f=new GenerationChecks.Fixture()) {
            var limits=limits(f.clock);var ledger=new Budgets.Ledger(limits.total(),f.clock);
            var broker=new AIWorkBroker(f.adapter,f.clock,AIWorkBroker.Settings.defaults(),AIWorkBroker.sessionLimits(f.clock.millis()),r->true,AIWorkBroker.Sharing.privateScopes());
            var request=request("bounded");assertThrows(IllegalArgumentException.class,()->broker.generate(request,limits,ledger));
            var handle=broker.submit(request,limits,ledger,Plan.OUTPUT,AIWorkBroker.Priority.NORMAL);broker.step();f.backend.respond(0,proposal(),1L,2L);broker.step();
            assertEquals(Generation.Outcome.CANDIDATE,handle.result().toCompletableFuture().join().outcome());broker.close();
        }
    }
    @Test void pendingPlanCancellationDiscardsLateBackendBytesWithoutNewCalls() {
        try(var f=new GenerationChecks.Fixture()) {
            var limits=limits(f.clock);var ledger=new Budgets.Ledger(limits.total(),f.clock);var handle=f.adapter.generate(request("bounded"),limits,ledger);
            assertTrue(handle.cancel());f.backend.respond(0,proposal(),1L,2L);
            assertNotEquals(Generation.Outcome.CANDIDATE,handle.result().toCompletableFuture().join().outcome());assertEquals(1L,ledger.snapshot().get(Budgets.Kind.CALLS));
        }
    }
}
