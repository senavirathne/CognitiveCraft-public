package dev.aivillages.providers;

import java.util.*;
import dev.aivillages.core.kernel.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic transport proposal, independently compiled; the live workflow proves actual model learning. */
class OakLogGenerationTest {
    @Test void structuredOakProposalUsesRegisteredSignaturesAndRuntimeParameters() throws Exception {
        try(var f=new GenerationChecks.Fixture()) {
            var old=GenerationChecks.request(Generation.Role.INITIAL,"oak delivery");
            var bound=new ValidatedRequest(new CapabilityRequest(OakLogDelivery.ID,old.bound().request().arguments()),
                    old.bound().context(),old.bound().observation());
            var signatures=List.of(Operation.APPROACH_OAK_LOG.signature(),Operation.HARVEST_NEXT_OAK_LOG.signature(),
                    Operation.OBSERVE_OAK_INVENTORY.signature(),Operation.PICKUP_TRACKED_OAK_LOGS.signature(),
                    Operation.MOVE_TO_DESTINATION.signature(),Operation.TRANSFER_OAK_LOGS.signature());
            var request=new Generation.Request(old.id(),bound,OakLogDelivery.SPEC,signatures,List.of(),old.role(),old.context());
            var limits=new Budgets.InferenceLimits(new Budgets.Limits(Map.of(Budgets.Kind.CALLS,1L,
                    Budgets.Kind.INPUT_BYTES,16384L,Budgets.Kind.OUTPUT_BYTES,8192L),f.clock.millis()+10000),16384,8192);
            var handle=f.adapter.generate(request,limits,new Budgets.Ledger(limits.total(),f.clock));
            String proposal="{\"schema\":1,\"capability\":\"cognitivecraft:deliver_oak_logs\",\"capabilityVersion\":1,\"dependencies\":[],\"body\":{\"loop\":{\"count\":{\"param\":\"amount\"},\"body\":{"
                    +"\"prepare\":[\"cognitivecraft:approach_oak_log\",\"cognitivecraft:harvest_next_oak_log\"],"
                    +"\"wait\":{\"primitive\":\"cognitivecraft:observe_oak_inventory\",\"count\":20},"
                    +"\"finish\":[\"cognitivecraft:pickup_tracked_oak_logs\",\"cognitivecraft:move_to_destination\",\"cognitivecraft:transfer_oak_logs\"]}},\"result\":{\"param\":\"amount\"}}}";
            f.backend.respond(0,proposal,900L,200L);
            var result=handle.result().toCompletableFuture().join();assertEquals(Generation.Outcome.CANDIDATE,result.outcome());
            var compiler=new SkillCompiler(PlanningGoals.catalog(),GatewayPrimitives.instance(),ref->Optional.empty());
            var skill=assertInstanceOf(SkillCompiler.Success.class,compiler.compile(result.candidateIr())).skill();
            assertEquals(OakLogDelivery.ID,skill.artifact().descriptor().ref().capability());
            assertEquals(6,skill.artifact().descriptor().primitives().size());
            var ir=StrictJson.object(result.candidateIr());assertInstanceOf(List.class,ir.get("body"));
            assertFalse(result.candidateIr().contains("harvest_next_wheat"));
        }
    }
}
