package dev.aivillages.fabric;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import dev.aivillages.core.kernel.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;

/** Explicit test proposal. Production contains no built-in oak task program. */
final class OakLogFixtures {
    static String program() {
        return program(20);
    }
    static String program(int waits) {
        if(waits<1 || waits>40)throw new IllegalArgumentException("Bounded test proposal wait");
        var actor=Map.<String,Object>of("actor",Map.of("param","actor"));
        var source=Map.<String,Object>of("actor",Map.of("param","actor"),"source",Map.of("param","source"));
        var dest=Map.<String,Object>of("actor",Map.of("param","actor"),"destination",Map.of("param","destination"));
        var transfer=new HashMap<String,Object>(dest); transfer.put("amount",Map.of("int",1L));
        var body=List.of(Map.of("op","repeat","count",Map.of("param","amount"),"body",List.of(
                call(Operation.APPROACH_OAK_LOG,"approach",source),call(Operation.HARVEST_NEXT_OAK_LOG,"harvest",source),
                Map.of("op","repeat","count",Map.of("int",(long)waits),"body",List.of(call(Operation.OBSERVE_OAK_INVENTORY,"wait",actor))),
                call(Operation.APPROACH_OAK_LOG,"atDrop",source),
                call(Operation.PICKUP_TRACKED_OAK_LOGS,"pickup",actor),call(Operation.MOVE_TO_DESTINATION,"atDestination",dest),
                call(Operation.TRANSFER_OAK_LOGS,"deposit",transfer))),Map.of("op","result","value",Map.of("param","amount")));
        return StrictJson.canonical(Map.of("schema",1L,"capability",OakLogDelivery.ID.name(),"capabilityVersion",1L,"dependencies",List.of(),"body",body));
    }
    private static Map<String,Object> call(Operation op,String into,Map<String,Object> args) {
        return Map.of("op","call","kind","primitive","id",op.signature().id(),"version",1L,
                "fingerprint",op.signature().fingerprint(),"args",args,"into",into);
    }
    static final class ControlledGeneration implements GenerationPort {
        final int waits;
        ControlledGeneration(){this(20);}
        ControlledGeneration(int waits){this.waits=waits;}
        int calls;
        public Generation.Handle generate(Generation.Request request,Budgets.InferenceLimits limits,Budgets.Ledger usage) {
            calls++;String candidate=program(waits);
            usage.debit(Budgets.Kind.CALLS,1);usage.debit(Budgets.Kind.INPUT_BYTES,128);
            usage.debit(Budgets.Kind.OUTPUT_BYTES,candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            var result=new Generation.Result(request.id(),request.bound().context(),request.role(),Generation.Outcome.CANDIDATE,
                    null,candidate,descriptor(),new Generation.Usage(128,candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,-1,-1,
                    Generation.Precision.UNKNOWN),Generation.Compute.COMPLETED);
            return new Generation.Handle() {
                public UUID id() { return request.id(); }
                public CompletableFuture<Generation.Result> result() { return CompletableFuture.completedFuture(result); }
                public boolean cancel() { return false; }
                public Generation.Compute compute() { return Generation.Compute.COMPLETED; }
            };
        }
        public Generation.Descriptor descriptor() { return new Generation.Descriptor("test-proposal-v1","rb001-controlled-proposal",null); }
        public Generation.Status status() { return new Generation.Status(Generation.State.READY,0,null,Generation.Compute.COMPLETED,false,false); }
    }
    private OakLogFixtures() { }
}
