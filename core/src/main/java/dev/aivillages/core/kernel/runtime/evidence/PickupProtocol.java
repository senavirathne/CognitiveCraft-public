package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.Outcomes.Reason;
import dev.aivillages.core.kernel.SurvivalGateway.*;
import java.util.*;

/** Composition-owned wrapper for the original gateway pickup, never a replay command. */
public interface PickupProtocol {
    record Request(ValidatedRequest bound, RunCorrelation correlation, UUID action, UUID operation,
                   ActorRef actor, Cell target, UUID drop, int expected, ResourceDelivery.ItemIdentity item,
                   long deadline, long effectsRemaining, Map<Budgets.Kind,Long> usage) {
        public Request {
            Objects.requireNonNull(bound); Objects.requireNonNull(correlation); Objects.requireNonNull(action);
            Objects.requireNonNull(operation); Objects.requireNonNull(actor); Objects.requireNonNull(target);
            Objects.requireNonNull(drop); Objects.requireNonNull(item); usage=Map.copyOf(usage);
            if(expected<1 || expected>64 || deadline<0 || effectsRemaining<1 || !actor.dimension().equals(target.dimension()))
                throw new IllegalArgumentException("Original pickup envelope");
        }
    }
    record Observed(ItemPickup pickup, ResourceDelivery.Receipt receipt, Map<Budgets.Kind,Long> usage) {
        public Observed { Objects.requireNonNull(pickup); usage=Map.copyOf(usage); }
    }
    interface Action { boolean current(); Observed execute(); Map<Budgets.Kind,Long> usage(); }
    interface Ticket { }
    enum Code { PENDING, ACKNOWLEDGED, FAILED, UNRESOLVED }
    record Progress(Code code, Reason reason) {
        public Progress {
            Objects.requireNonNull(code);
            if((code==Code.FAILED || code==Code.UNRESOLVED)!=(reason!=null)
                    || code==Code.UNRESOLVED && reason!=Reason.INTERRUPTED) throw new IllegalArgumentException("Pickup progress");
        }
    }
    Ticket begin(Request request, Action original);
    Progress poll(Ticket ticket);
    void forget(Ticket ticket);
}
