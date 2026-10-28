package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.Budgets;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.Outcomes.Reason;
import dev.aivillages.core.kernel.ResourceDelivery;
import dev.aivillages.core.kernel.SurvivalGateway.ItemTransfer;
import java.util.*;

/** Composition-owned native deposit port. Its action is the original gateway call, never player data. */
public interface DepositProtocol {
    record Request(ValidatedRequest bound,RunCorrelation correlation,UUID action,UUID operation,
                   ActorRef actor,ContainerRef destination,ResourceDelivery.ItemIdentity item,long deadline,
                   long effectsRemaining,Map<Budgets.Kind,Long> usage,List<ResourceDelivery.Receipt> receiptPrefix) {
        public Request(ValidatedRequest bound,RunCorrelation correlation,UUID action,UUID operation,
                       ActorRef actor,ContainerRef destination,ResourceDelivery.ItemIdentity item,long deadline,
                       long effectsRemaining,Map<Budgets.Kind,Long> usage) {
            this(bound,correlation,action,operation,actor,destination,item,deadline,effectsRemaining,usage,List.of());
        }
        public Request {
            Objects.requireNonNull(bound);Objects.requireNonNull(correlation);Objects.requireNonNull(action);Objects.requireNonNull(operation);
            Objects.requireNonNull(actor);Objects.requireNonNull(destination);Objects.requireNonNull(item);usage=Map.copyOf(usage);receiptPrefix=List.copyOf(receiptPrefix);
            if(receiptPrefix.size()>63)throw new IllegalArgumentException("Original native deposit receipt prefix quota");
            if(deadline<0 || effectsRemaining<1)throw new IllegalArgumentException("Original native deposit allowance");
        }
    }
    record Observed(ItemTransfer transfer,ResourceDelivery.Receipt receipt,Map<Budgets.Kind,Long> usage) {
        public Observed {Objects.requireNonNull(transfer);usage=Map.copyOf(usage);}
    }
    interface Action {
        boolean current();
        Observed execute();
        Map<Budgets.Kind,Long> usage();
    }
    interface Ticket { }
    enum Code { PENDING, ACKNOWLEDGED, FAILED }
    record Progress(Code code,Reason reason) {
        public Progress {Objects.requireNonNull(code);if((code==Code.FAILED)!=(reason!=null))throw new IllegalArgumentException("Deposit protocol progress");}
    }
    Ticket begin(Request request,Action original);
    Progress poll(Ticket ticket);
    void forget(Ticket ticket);
}
