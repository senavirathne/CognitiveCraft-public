package dev.aivillages.fabric;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import dev.aivillages.core.kernel.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;

/** Controlled proposals only in the GameTest mod. Genuine gates inject no proposals. */
final class BirchFixtures {
    static Operation operation(Operation op,boolean birch) {
        if(!birch || op==Operation.MOVE_TO_INPUT || op==Operation.MOVE_TO_DESTINATION)return op;
        return Arrays.stream(Operation.values()).filter(o->(o.birchLog()||o.birchPlank()) && o.shared()==op).findFirst().orElseThrow();
    }
    static String program(CapabilityId id) {
        boolean plank=id.equals(BirchPlankProduction.ID);
        String ir=(plank?OakPlankFixtures.program():OakLogFixtures.program()).replace(plank?OakPlankProduction.ID.name():OakLogDelivery.ID.name(),id.name());
        for(var op:Operation.values())if(op.birchLog()||op.birchPlank())ir=ir.replace(op.shared().signature().id(),op.signature().id()).replace(op.shared().signature().fingerprint(),op.signature().fingerprint());
        return ir;
    }
    static final class ControlledGeneration implements GenerationPort {
        int calls;
        public Generation.Handle generate(Generation.Request request,Budgets.InferenceLimits limits,Budgets.Ledger usage) {
            calls++;String ir=program(request.bound().request().capability());int bytes=ir.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            usage.debit(Budgets.Kind.CALLS,1);usage.debit(Budgets.Kind.INPUT_BYTES,128);usage.debit(Budgets.Kind.OUTPUT_BYTES,bytes);
            var result=new Generation.Result(request.id(),request.bound().context(),request.role(),Generation.Outcome.CANDIDATE,null,ir,descriptor(),new Generation.Usage(128,bytes,-1,-1,Generation.Precision.UNKNOWN),Generation.Compute.COMPLETED);
            return new Generation.Handle() {
                public UUID id(){return request.id();}public CompletableFuture<Generation.Result> result(){return CompletableFuture.completedFuture(result);}
                public boolean cancel(){return false;}public Generation.Compute compute(){return Generation.Compute.COMPLETED;}
            };
        }
        public Generation.Descriptor descriptor(){return new Generation.Descriptor("test-proposal-v1","rb007-controlled-proposal",null);}
        public Generation.Status status(){return new Generation.Status(Generation.State.READY,0,null,Generation.Compute.COMPLETED,false,false);}
    }
}
