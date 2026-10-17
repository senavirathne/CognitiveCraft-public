package dev.aivillages.fabric;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import dev.aivillages.core.kernel.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;

/** Controlled proposal exists only in the isolated test mod; genuine admission uses the configured backend. */
final class TableFixtures {
    static String program() {
        var count=Map.of("param","amount");
        return StrictJson.canonical(Map.of("schema",1,"capability",CraftingTableProduction.ID.name(),"capabilityVersion",1,"dependencies",List.of(),
                "body",List.of(call(Operation.OBSERVE_TABLE_INPUTS),Map.of("op","repeat","count",count,"body",List.of(
                        call(Operation.MOVE_TO_INPUT),call(Operation.WITHDRAW_TABLE_PLANKS),call(Operation.CRAFT_CRAFTING_TABLE),
                        call(Operation.MOVE_TO_DESTINATION),call(Operation.TRANSFER_CRAFTING_TABLES))),Map.of("op","result","value",Map.of("param","amount")))));
    }
    static Map<String,Object> call(Operation op) {
        var args=new LinkedHashMap<String,Object>();op.signature().parameters().forEach(p->args.put(p.name(),Map.of("param",p.name())));
        return Map.of("op","call","kind","primitive","id",op.signature().id(),"version",1,"fingerprint",op.signature().fingerprint(),
                "args",args,"into",op.name().toLowerCase(Locale.ROOT));
    }
    static final class ControlledGeneration implements GenerationPort {
        int calls;
        public Generation.Handle generate(Generation.Request request,Budgets.InferenceLimits limits,Budgets.Ledger usage) {
            calls++;String ir=program();int bytes=ir.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            usage.debit(Budgets.Kind.CALLS,1);usage.debit(Budgets.Kind.INPUT_BYTES,128);usage.debit(Budgets.Kind.OUTPUT_BYTES,bytes);
            var result=new Generation.Result(request.id(),request.bound().context(),request.role(),Generation.Outcome.CANDIDATE,null,ir,
                    descriptor(),new Generation.Usage(128,bytes,-1,-1,Generation.Precision.UNKNOWN),Generation.Compute.COMPLETED);
            return new Generation.Handle() {
                public UUID id() {return request.id();}public CompletableFuture<Generation.Result> result() {return CompletableFuture.completedFuture(result);}
                public boolean cancel() {return false;}public Generation.Compute compute() {return Generation.Compute.COMPLETED;}
            };
        }
        public Generation.Descriptor descriptor() {return new Generation.Descriptor("test-proposal-v1","rb006a-controlled-proposal",null);}
        public Generation.Status status() {return new Generation.Status(Generation.State.READY,0,null,Generation.Compute.COMPLETED,false,false);}
    }
    private TableFixtures() { }
}
