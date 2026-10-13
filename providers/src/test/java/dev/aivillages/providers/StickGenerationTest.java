package dev.aivillages.providers;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.aivillages.core.kernel.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;
import static org.junit.jupiter.api.Assertions.*;

class StickGenerationTest {
    @Test void modelProposedCausalRecipeOperationsCompileWithLateBoundQuantityAndContainers() throws Exception {
        try(var f=new GenerationChecks.Fixture()) {
            var old=GenerationChecks.request(Generation.Role.INITIAL,"stick production");
            var args=new HashMap<String,Value>(old.bound().request().arguments());
            args.put("source",args.get("destination"));args.put("amount",new IntValue(8));
            var bound=new ValidatedRequest(new CapabilityRequest(StickProduction.ID,args),old.bound().context(),old.bound().observation());
            var signatures=List.of(Operation.OBSERVE_STICK_INPUTS.signature(),Operation.MOVE_TO_INPUT.signature(),Operation.WITHDRAW_OAK_PLANKS.signature(),
                    Operation.CRAFT_STICKS.signature(),Operation.MOVE_TO_DESTINATION.signature(),Operation.TRANSFER_STICKS.signature());
            var request=new Generation.Request(old.id(),bound,StickProduction.SPEC,signatures,List.of(),old.role(),old.context());
            var limits=new Budgets.InferenceLimits(new Budgets.Limits(Map.of(Budgets.Kind.CALLS,1L,Budgets.Kind.INPUT_BYTES,16384L,
                    Budgets.Kind.OUTPUT_BYTES,8192L),f.clock.millis()+10000),16384,8192);
            var h=f.adapter.generate(request,limits,new Budgets.Ledger(limits.total(),f.clock));
            String proposal="{\"schema\":1,\"capability\":\"cognitivecraft:produce_sticks\",\"capabilityVersion\":1,\"dependencies\":[],\"body\":{"
                    +"\"preflight\":\"cognitivecraft:observe_stick_inputs\",\"batchSize\":4,\"cycle\":[\"cognitivecraft:move_to_input\","
                    +"\"cognitivecraft:withdraw_oak_planks\",\"cognitivecraft:craft_sticks\",\"cognitivecraft:move_to_destination\","
                    +"\"cognitivecraft:transfer_sticks\"],\"result\":{\"param\":\"amount\"}}}";
            var wire=StrictJson.transportObject(f.backend.bodies.get(0));
            @SuppressWarnings("unchecked") var messages=(List<Map<String,Object>>)wire.get("messages");
            String instructions=(String)messages.getFirst().get("content");
            assertTrue(instructions.contains("two default oak planks") && instructions.contains("four new sticks"));
            assertTrue(instructions.contains("cognitivecraft:withdraw_oak_planks") && !instructions.contains("withdraw one log"));
            f.backend.respond(0,proposal,900L,180L);var result=h.result().toCompletableFuture().join();
            assertEquals(Generation.Outcome.CANDIDATE,result.outcome());
            var artifact=assertInstanceOf(SkillCompiler.Success.class,new SkillCompiler(PlanningGoals.catalog(),GatewayPrimitives.instance(),ref->Optional.empty())
                    .compile(result.candidateIr())).skill().artifact();
            assertEquals(StickProduction.ID,artifact.descriptor().ref().capability());assertTrue(artifact.descriptor().dependencies().isEmpty());
            assertEquals(6,artifact.descriptor().primitives().size());assertFalse(result.candidateIr().contains("harvest"));
            assertTrue(result.candidateIr().contains("DIV"));
        }
    }
}
