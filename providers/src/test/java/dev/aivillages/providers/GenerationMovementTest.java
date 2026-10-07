package dev.aivillages.providers;

import dev.aivillages.core.kernel.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;

class GenerationMovementTest {
    @ParameterizedTest
    @ValueSource(longs={20,30})
    void modelCanProposeSeparateTypedMovementThroughTheExistingIrLowering(long waitCount) throws Exception {
        try (var f = new GenerationChecks.Fixture()) {
            var original = GenerationChecks.request(Generation.Role.INITIAL,"movement-capable crop method");
            var signatures = List.of(Operation.MOVE_TO_SOURCE.signature(), Operation.MOVE_TO_DESTINATION.signature(),
                    Operation.HARVEST_NEXT_WHEAT.signature(), Operation.OBSERVE_INVENTORY.signature(),
                    Operation.PICKUP_TRACKED_WHEAT.signature(), Operation.TRANSFER_WHEAT.signature());
            var request = new Generation.Request(original.id(), original.bound(),original.capability(),
                    signatures,List.of(),original.role(),original.context());
            var limits = new Budgets.InferenceLimits(new Budgets.Limits(Map.of(Budgets.Kind.CALLS,1L,
                    Budgets.Kind.INPUT_BYTES,16384L,Budgets.Kind.OUTPUT_BYTES,8192L), f.clock.millis()+10000),16384,8192);
            var handle = f.adapter.generate(request,limits,new Budgets.Ledger(limits.total(),f.clock));
            String proposal = "{\"schema\":1,\"capability\":\"cognitivecraft:deliver_wheat\",\"capabilityVersion\":1,"
                    + "\"dependencies\":[],\"body\":{\"loop\":{\"count\":{\"param\":\"amount\"},\"body\":{"
                    + "\"prepare\":[\"cognitivecraft:move_to_source\",\"cognitivecraft:harvest_next_wheat\"],"
                    + "\"wait\":{\"primitive\":\"cognitivecraft:observe_inventory\",\"count\":"+waitCount+"},"
                    + "\"finish\":[\"cognitivecraft:pickup_tracked_wheat\",\"cognitivecraft:move_to_destination\","
                    + "\"cognitivecraft:transfer_wheat\"]}},\"result\":{\"param\":\"amount\"}}}";
            f.backend.respond(0,proposal,900L,200L);
            var result = handle.result().toCompletableFuture().join();
            assertEquals(Generation.Outcome.CANDIDATE,result.outcome());
            var ir = StrictJson.object(result.candidateIr());
            var root = (List<?>)ir.get("body");
            var loop = (Map<?,?>)root.get(0); var calls = (List<?>)loop.get("body");
            assertEquals(6,calls.size());
            assertEquals(Map.of("int",waitCount),((Map<?,?>)calls.get(2)).get("count"));
            assertEquals(List.of("cognitivecraft:move_to_source","cognitivecraft:harvest_next_wheat",
                    "cognitivecraft:pickup_tracked_wheat","cognitivecraft:move_to_destination","cognitivecraft:transfer_wheat"),
                    List.of(0,1,3,4,5).stream().map(i -> ((Map<?,?>)calls.get(i)).get("id")).toList());
            assertEquals(Map.of("actor",Map.of("param","actor"),"source",Map.of("param","source")),
                    ((Map<?,?>)calls.get(0)).get("args"));
            assertEquals(Map.of("actor",Map.of("param","actor"),"destination",Map.of("param","destination")),
                    ((Map<?,?>)calls.get(4)).get("args"));
        }
    }
}
