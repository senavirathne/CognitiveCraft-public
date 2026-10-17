package dev.aivillages.fabric;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import dev.aivillages.core.kernel.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;

/** Controlled proposal is isolated in the test mod, never production knowledge. */
final class PlacementFixtures {
    static String program(){var body=new ArrayList<Object>();CraftingTablePlacement.operations().forEach(op->body.add(TableFixtures.call(op)));body.add(Map.of("op","result","value",Map.of("param","amount")));
        return StrictJson.canonical(Map.of("schema",1,"capability",CraftingTablePlacement.ID.name(),"capabilityVersion",1,"dependencies",List.of(),"body",body));}
    static final class ControlledGeneration implements GenerationPort {
        int calls;
        public Generation.Handle generate(Generation.Request request,Budgets.InferenceLimits limits,Budgets.Ledger usage){
            calls++;String ir=program();int bytes=ir.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            usage.debit(Budgets.Kind.CALLS,1);usage.debit(Budgets.Kind.INPUT_BYTES,128);usage.debit(Budgets.Kind.OUTPUT_BYTES,bytes);
            var result=new Generation.Result(request.id(),request.bound().context(),request.role(),Generation.Outcome.CANDIDATE,null,ir,descriptor(),new Generation.Usage(128,bytes,-1,-1,Generation.Precision.UNKNOWN),Generation.Compute.COMPLETED);
            return new Generation.Handle(){public UUID id(){return request.id();}public CompletableFuture<Generation.Result> result(){return CompletableFuture.completedFuture(result);}public boolean cancel(){return false;}public Generation.Compute compute(){return Generation.Compute.COMPLETED;}};
        }
        public Generation.Descriptor descriptor(){return new Generation.Descriptor("test-proposal-v1","rb006b-controlled-proposal",null);}
        public Generation.Status status(){return new Generation.Status(Generation.State.READY,0,null,Generation.Compute.COMPLETED,false,false);}
    }
}
