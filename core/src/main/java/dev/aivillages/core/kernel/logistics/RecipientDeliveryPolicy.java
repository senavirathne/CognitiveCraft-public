package dev.aivillages.core.kernel.logistics;

import java.util.*;
import dev.aivillages.core.kernel.storage.StorageAccessPolicy.Scope;
import dev.aivillages.core.kernel.storage.StorageCapabilities.Item;
import dev.aivillages.core.kernel.storage.StorageRegistry.Permission;
import static dev.aivillages.core.kernel.logistics.TransferObservation.*;

/** Pure recipient proposals and live deltas. No custody owner, effect ledger, scheduler or save receipt. */
public final class RecipientDeliveryPolicy {
    public static final int MAX_QUANTITY=64,MAX_BATCHES=8;
    public static final double MAX_SEPARATION=2.5;
    public enum Source { EXISTING_CARRIER, STORAGE }
    /** Labels and positions do not form recipient identity. */
    public record Target(UUID world,UUID citizen,UUID entity,UUID epoch,long generation,long policyRevision,String dimension) {
        public Target {Objects.requireNonNull(world);Objects.requireNonNull(citizen);Objects.requireNonNull(entity);Objects.requireNonNull(epoch);new MobilityPolicy.Point(dimension,0,0,0);if(generation<1 || policyRevision<0)throw new IllegalArgumentException("recipient generation/revision");}
        public static Target of(RecipientResolver.Citizen c){return new Target(c.scope().world(),c.id(),c.entity(),c.epoch(),c.generation(),c.policyRevision(),c.dimension());}
        public Reason current(RecipientResolver.Citizen c,Scope scope,long tick){
            if(c==null || !c.scope().equals(scope) || c.tick()!=tick || !world.equals(scope.world()) || !citizen.equals(c.id()) || !entity.equals(c.entity()) || !epoch.equals(c.epoch()) || generation!=c.generation() || policyRevision!=c.policyRevision() || !dimension.equals(c.dimension()))return Reason.STALE_ENDPOINT;
            return c.loaded() && c.visible() && c.permitted().contains(Permission.DEPOSIT)?Reason.NONE:Reason.UNAUTHORIZED;
        }
    }
    public record Goal(UUID request,MobilityPolicy.Actor carrier,Target recipient,Source source,UUID sourceCitizen,DeliveryPolicy.Endpoint storageSource,Item item,int quantity) {
        public Goal {Objects.requireNonNull(request);Objects.requireNonNull(carrier);Objects.requireNonNull(recipient);Objects.requireNonNull(source);Objects.requireNonNull(item);if(quantity<1 || quantity>MAX_QUANTITY)throw new IllegalArgumentException("quantity 1..64");if(source==Source.EXISTING_CARRIER?(sourceCitizen==null || storageSource!=null):(sourceCitizen!=null || storageSource==null))throw new IllegalArgumentException("one explicit source shape");}
    }
    public static Reason admit(Goal g){
        if(!g.carrier.world().equals(g.recipient.world) || !g.carrier.dimension().equals(g.recipient.dimension))return Reason.STALE_ENDPOINT;
        if(g.carrier.citizen().equals(g.recipient.citizen) || g.carrier.entity().equals(g.recipient.entity))return Reason.SAME_ENDPOINT;
        if(g.source==Source.EXISTING_CARRIER && !g.carrier.citizen().equals(g.sourceCitizen))return Reason.UNAUTHORIZED;
        if(g.source==Source.STORAGE && (!g.storageSource.world().equals(g.carrier.world()) || !g.storageSource.dimension().equals(g.carrier.dimension())))return Reason.STALE_ENDPOINT;
        if(!g.item.defaultComponents() || g.item.containerItem())return Reason.UNSUPPORTED_ITEM;
        return (g.quantity+g.item.maxStack()-1)/g.item.maxStack()>MAX_BATCHES?Reason.BATCH_LIMIT:Reason.NONE;
    }
    public static boolean withinReach(double distance,double nativeLimit){return Double.isFinite(distance) && Double.isFinite(nativeLimit) && nativeLimit>0 && distance>=0 && distance<=Math.min(MAX_SEPARATION,nativeLimit);}
    public enum Phase { READY, IN_FLIGHT, DELIVERED_LIVE, BLOCKED, UNCERTAIN }
    public static final class Session {
        private final Goal goal;private Phase phase;private Reason reason;private int delivered,inFlight,batches;
        public Session(Goal goal){this.goal=Objects.requireNonNull(goal);reason=admit(goal);phase=reason==Reason.NONE?Phase.READY:Phase.BLOCKED;}
        public Goal goal(){return goal;}public Phase phase(){return phase;}public Reason reason(){return reason;}public int delivered(){return delivered;}public int inFlight(){return inFlight;}public int batches(){return batches;}public int remaining(){return goal.quantity-delivered;}
        /** The native owner explicitly authorized these sender slots; this observation moves no units. */
        public void acceptExisting(int q){if(goal.source!=Source.EXISTING_CARRIER || phase!=Phase.READY)throw new IllegalStateException("existing cargo phase");accept(q);}
        private void accept(int q){if(q<1 || q>remaining() || batches>=MAX_BATCHES)throw new IllegalArgumentException("bounded original cargo");inFlight=q;batches++;phase=Phase.IN_FLIGHT;}
        public void observe(TransferObservation o){
            if(phase==Phase.BLOCKED || phase==Phase.UNCERTAIN || phase==Phase.DELIVERED_LIVE)throw new IllegalStateException("terminal recipient delivery");
            if(o.status()==Status.REFUSED){reason=o.reason();phase=Phase.BLOCKED;return;}
            if(o.status()==Status.UNCERTAIN){reason=Reason.UNCERTAIN_EFFECT;phase=Phase.UNCERTAIN;return;}
            if(o.status()==Status.LIVE_WITHDRAWN){if(goal.source!=Source.STORAGE || phase!=Phase.READY || !DeliveryPolicy.withdrawal(o.before(),o.after(),o.quantity()))throw new IllegalArgumentException("unproved storage cargo");accept(o.quantity());return;}
            if(phase!=Phase.IN_FLIGHT || o.quantity()!=inFlight || !DeliveryPolicy.deposit(o.before(),o.after(),inFlight))throw new IllegalArgumentException("unproved recipient delta or old stock credit");
            delivered+=inFlight;inFlight=0;phase=delivered==goal.quantity?Phase.DELIVERED_LIVE:Phase.READY;
        }
    }
    private RecipientDeliveryPolicy(){}
}
