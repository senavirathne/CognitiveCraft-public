package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.Outcomes.Reason;
import dev.aivillages.core.kernel.SurvivalGateway.*;
import java.util.*;

/** Trusted composition port around the original harvest; player/model data cannot supply its action. */
public interface HarvestProtocol {
    record Request(ValidatedRequest bound,RunCorrelation correlation,UUID action,UUID operation,
                   ActorRef actor,Cuboid area,ContainerRef destination,Cell source,ResourceDelivery.ItemIdentity item,
                   long deadline,long effectsRemaining,Map<Budgets.Kind,Long> usage) {
        public Request {
            Objects.requireNonNull(bound);Objects.requireNonNull(correlation);Objects.requireNonNull(action);Objects.requireNonNull(operation);
            Objects.requireNonNull(actor);Objects.requireNonNull(area);Objects.requireNonNull(destination);Objects.requireNonNull(source);Objects.requireNonNull(item);
            usage=Map.copyOf(usage);
            if(!source.inside(area) || !actor.dimension().equals(source.dimension()) || !destination.dimension().equals(source.dimension())
                    || deadline<0 || effectsRemaining<1)throw new IllegalArgumentException("Original harvest request envelope");
        }
    }
    record Observed(ItemHarvest harvest,ResourceDelivery.Receipt receipt,Map<Budgets.Kind,Long> usage) {
        public Observed {Objects.requireNonNull(harvest);usage=Map.copyOf(usage);}
    }
    interface Action {
        boolean current();
        Observed execute();
        Map<Budgets.Kind,Long> usage();
    }
    interface Ticket { }
    enum Code { PENDING,ACKNOWLEDGED,FAILED,UNRESOLVED }
    record Progress(Code code,Reason reason) {
        public Progress {Objects.requireNonNull(code);if(((code==Code.FAILED || code==Code.UNRESOLVED)!=(reason!=null) || code==Code.UNRESOLVED && reason!=Reason.INTERRUPTED))throw new IllegalArgumentException("Harvest protocol progress");}
    }
    Ticket begin(Request request,Action original);
    Progress poll(Ticket ticket);
    void forget(Ticket ticket);
}
