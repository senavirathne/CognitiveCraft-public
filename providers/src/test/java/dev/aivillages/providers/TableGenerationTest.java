package dev.aivillages.providers;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.aivillages.core.kernel.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;
import static org.junit.jupiter.api.Assertions.*;

class TableGenerationTest {
    @Test void modelProposedCausalRecipeOperationsCompileWithLateBoundQuantityAndContainers() throws Exception {
        try(var f=new GenerationChecks.Fixture()) {
            var old=GenerationChecks.request(Generation.Role.INITIAL,"crafting table production");
            var args=new HashMap<String,Value>(old.bound().request().arguments());
            args.put("source",args.get("destination"));args.put("amount",new IntValue(4));
            var bound=new ValidatedRequest(new CapabilityRequest(CraftingTableProduction.ID,args),old.bound().context(),old.bound().observation());
            var signatures=List.of(Operation.OBSERVE_TABLE_INPUTS.signature(),Operation.MOVE_TO_INPUT.signature(),Operation.WITHDRAW_TABLE_PLANKS.signature(),
                    Operation.CRAFT_CRAFTING_TABLE.signature(),Operation.MOVE_TO_DESTINATION.signature(),Operation.TRANSFER_CRAFTING_TABLES.signature());
            var request=new Generation.Request(old.id(),bound,CraftingTableProduction.SPEC,signatures,List.of(),old.role(),old.context());
            var limits=new Budgets.InferenceLimits(new Budgets.Limits(Map.of(Budgets.Kind.CALLS,1L,Budgets.Kind.INPUT_BYTES,16384L,
                    Budgets.Kind.OUTPUT_BYTES,8192L),f.clock.millis()+10000),16384,8192);
            var h=f.adapter.generate(request,limits,new Budgets.Ledger(limits.total(),f.clock));
            String proposal="{\"schema\":1,\"capability\":\"cognitivecraft:produce_crafting_tables\",\"capabilityVersion\":1,\"dependencies\":[],\"body\":{"
                    +"\"preflight\":\"cognitivecraft:observe_table_inputs\",\"batchSize\":1,\"cycle\":[\"cognitivecraft:move_to_input\","
                    +"\"cognitivecraft:withdraw_table_planks\",\"cognitivecraft:craft_crafting_table\",\"cognitivecraft:move_to_destination\","
                    +"\"cognitivecraft:transfer_crafting_tables\"],\"result\":{\"param\":\"amount\"}}}";
            var wire=StrictJson.transportObject(f.backend.bodies.get(0));
            @SuppressWarnings("unchecked") var messages=(List<Map<String,Object>>)wire.get("messages");
            String instructions=(String)messages.getFirst().get("content");
            assertTrue(instructions.contains("Four default oak planks") && instructions.contains("one new crafting-table item"));
            assertTrue(instructions.contains("cognitivecraft:withdraw_table_planks") && !instructions.contains("withdraw one log"));
            @SuppressWarnings("unchecked") var format=(Map<String,Object>)wire.get("format");
            @SuppressWarnings("unchecked") var rootProperties=(Map<String,Object>)format.get("properties");
            @SuppressWarnings("unchecked") var bodyShape=(Map<String,Object>)rootProperties.get("body");
            @SuppressWarnings("unchecked") var properties=(Map<String,Object>)bodyShape.get("properties");
            @SuppressWarnings("unchecked") var preflightShape=(Map<String,Object>)properties.get("preflight");
            assertEquals(List.of(Operation.OBSERVE_TABLE_INPUTS.signature().id()),preflightShape.get("enum"));
            @SuppressWarnings("unchecked") var cycleShape=(Map<String,Object>)properties.get("cycle");
            @SuppressWarnings("unchecked") var cycleItems=(Map<String,Object>)cycleShape.get("items");
            @SuppressWarnings("unchecked") var choices=(List<String>)cycleItems.get("enum");
            assertEquals(5,choices.size());assertFalse(choices.contains(Operation.OBSERVE_TABLE_INPUTS.signature().id()));
            assertTrue(choices.contains(Operation.TRANSFER_CRAFTING_TABLES.signature().id()));
            f.backend.respond(0,proposal,900L,180L);var result=h.result().toCompletableFuture().join();
            assertEquals(Generation.Outcome.CANDIDATE,result.outcome());
            var artifact=assertInstanceOf(SkillCompiler.Success.class,new SkillCompiler(PlanningGoals.catalog(),GatewayPrimitives.instance(),ref->Optional.empty())
                    .compile(result.candidateIr())).skill().artifact();
            assertEquals(CraftingTableProduction.ID,artifact.descriptor().ref().capability());assertTrue(artifact.descriptor().dependencies().isEmpty());
            assertEquals(6,artifact.descriptor().primitives().size());assertFalse(result.candidateIr().contains("harvest"));
            assertTrue(result.candidateIr().contains("DIV"));
        }
    }

    @Test void actualRejectedModelCycleCannotSmugglePreflightOrOmitDelivery() throws Exception {
        try(var f=new GenerationChecks.Fixture()) {
            var old=GenerationChecks.request(Generation.Role.INITIAL,"actual captured invalid recipe proposal");
            var args=new HashMap<String,Value>(old.bound().request().arguments());args.put("source",args.get("destination"));args.put("amount",new IntValue(4));
            var bound=new ValidatedRequest(new CapabilityRequest(CraftingTableProduction.ID,args),old.bound().context(),old.bound().observation());
            var request=new Generation.Request(old.id(),bound,CraftingTableProduction.SPEC,RecipeProduction.operations(CraftingTableProduction.ID).stream().map(Operation::signature).toList(),List.of(),old.role(),old.context());
            var limits=new Budgets.InferenceLimits(new Budgets.Limits(Map.of(Budgets.Kind.CALLS,1L,Budgets.Kind.INPUT_BYTES,16384L,Budgets.Kind.OUTPUT_BYTES,8192L),f.clock.millis()+10000),16384,8192);
            var handle=f.adapter.generate(request,limits,new Budgets.Ledger(limits.total(),f.clock));
            String invalid=StrictJson.canonical(Map.of("schema",1,"capability",CraftingTableProduction.ID.name(),"capabilityVersion",1,"dependencies",List.of(),
                    "body",Map.of("batchSize",1,"preflight",Operation.OBSERVE_TABLE_INPUTS.signature().id(),"result",Map.of("param","amount"),
                            "cycle",List.of(Operation.OBSERVE_TABLE_INPUTS,Operation.MOVE_TO_INPUT,Operation.WITHDRAW_TABLE_PLANKS,Operation.CRAFT_CRAFTING_TABLE,Operation.MOVE_TO_DESTINATION).stream().map(op->op.signature().id()).toList())));
            f.backend.respond(0,invalid,1096L,141L);var result=handle.result().toCompletableFuture().join();
            assertEquals(Generation.Outcome.MALFORMED,result.outcome());assertNull(result.candidateIr());
        }
    }
}
