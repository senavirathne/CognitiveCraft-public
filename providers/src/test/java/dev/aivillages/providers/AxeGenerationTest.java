package dev.aivillages.providers;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.aivillages.core.kernel.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;
import static org.junit.jupiter.api.Assertions.*;
class AxeGenerationTest {
    Generation.Request request() {
        var old=GenerationChecks.request(Generation.Role.INITIAL,"wooden-axe production");var args=new HashMap<String,Value>(old.bound().request().arguments());
        args.put("source",args.get("destination"));args.put("amount",new IntValue(4));args.put("table",new FacilityValue(new FacilityRef(old.bound().observation().dimension(),1,0,1)));
        var bound=new ValidatedRequest(new CapabilityRequest(WoodenAxeProduction.ID,args),old.bound().context(),old.bound().observation());
        return new Generation.Request(old.id(),bound,WoodenAxeProduction.SPEC,RecipeProduction.operations(WoodenAxeProduction.ID).stream().map(Operation::signature).toList(),List.of(),old.role(),old.context());
    }
    String proposal(boolean invalid) {
        var ops=RecipeProduction.operations(WoodenAxeProduction.ID).subList(1,7);var cycle=new ArrayList<>(ops);
        if(invalid)cycle.set(0,Operation.OBSERVE_AXE_INPUTS);
        return StrictJson.canonical(Map.of("schema",1,"capability",WoodenAxeProduction.ID.name(),"capabilityVersion",1,"dependencies",List.of(),
                "body",Map.of("preflight",Operation.OBSERVE_AXE_INPUTS.signature().id(),"batchSize",1,"cycle",cycle.stream().map(op->op.signature().id()).toList(),"result",Map.of("param","amount"))));
    }
    @Test void modelSelectedTableRecipeCompilesToParameterizedIndependentIr() throws Exception {
        try(var f=new GenerationChecks.Fixture()) {
            var limits=new Budgets.InferenceLimits(new Budgets.Limits(Map.of(Budgets.Kind.CALLS,1L,Budgets.Kind.INPUT_BYTES,16384L,Budgets.Kind.OUTPUT_BYTES,8192L),f.clock.millis()+10000),16384,8192);
            var handle=f.adapter.generate(request(),limits,new Budgets.Ledger(limits.total(),f.clock));f.backend.respond(0,proposal(false),900L,180L);
            var result=handle.result().toCompletableFuture().join();assertEquals(Generation.Outcome.CANDIDATE,result.outcome());
            var skill=assertInstanceOf(SkillCompiler.Success.class,new SkillCompiler(PlanningGoals.catalog(),GatewayPrimitives.instance(),ref->Optional.empty()).compile(result.candidateIr())).skill();
            assertEquals(WoodenAxeProduction.ID,skill.artifact().descriptor().ref().capability());assertTrue(skill.artifact().descriptor().dependencies().isEmpty());assertEquals(7,skill.artifact().descriptor().primitives().size());
            assertTrue(result.candidateIr().contains("table") && !result.candidateIr().contains("harvest"));
        }
    }
    @Test void repeatedPreflightIsRejectedBeforeCompilation() throws Exception {
        try(var f=new GenerationChecks.Fixture()) {
            var limits=new Budgets.InferenceLimits(new Budgets.Limits(Map.of(Budgets.Kind.CALLS,1L,Budgets.Kind.INPUT_BYTES,16384L,Budgets.Kind.OUTPUT_BYTES,8192L),f.clock.millis()+10000),16384,8192);
            var handle=f.adapter.generate(request(),limits,new Budgets.Ledger(limits.total(),f.clock));f.backend.respond(0,proposal(true),900L,180L);
            assertEquals(Generation.Outcome.MALFORMED,handle.result().toCompletableFuture().join().outcome());
        }
    }
}
