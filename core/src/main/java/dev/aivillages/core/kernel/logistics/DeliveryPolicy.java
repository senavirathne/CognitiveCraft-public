package dev.aivillages.core.kernel.logistics;
import java.util.*;
import dev.aivillages.core.kernel.storage.StorageCapabilities.Item;
import static dev.aivillages.core.kernel.logistics.TransferObservation.*;
/** Finite courier proposals and observed delta accounting; no inventory, effect or custody ledger. */
public final class DeliveryPolicy {
 public static final int MAX_QUANTITY=64,MAX_BATCHES=8;
 public record Endpoint(UUID world,UUID storage,String dimension,Set<UUID> participants){public Endpoint{Objects.requireNonNull(world);Objects.requireNonNull(storage);Objects.requireNonNull(dimension);new MobilityPolicy.Point(dimension,0,0,0);participants=Set.copyOf(participants);if(participants.isEmpty() || participants.size()>2)throw new IllegalArgumentException("bounded endpoint participants");}}
 public record Goal(UUID request,UUID carrier,Endpoint source,Endpoint destination,Item item,int quantity,boolean explicitItem,boolean selectedSourceSlot){
  public Goal{Objects.requireNonNull(request);Objects.requireNonNull(carrier);Objects.requireNonNull(source);Objects.requireNonNull(destination);Objects.requireNonNull(item);if(quantity<1 || quantity>MAX_QUANTITY)throw new IllegalArgumentException("quantity 1..64");}
 }
 public static Reason admit(Goal g){if(!g.source.world.equals(g.destination.world) || !g.source.dimension.equals(g.destination.dimension))return Reason.STALE_ENDPOINT;if(g.source.storage.equals(g.destination.storage) || !Collections.disjoint(g.source.participants,g.destination.participants))return Reason.SAME_ENDPOINT;if(!g.item.defaultComponents() || g.item.containerItem())return Reason.UNSUPPORTED_ITEM;if(!g.explicitItem && !g.selectedSourceSlot)return Reason.SELECTION_REQUIRED;if((g.quantity+g.item.maxStack()-1)/g.item.maxStack()>MAX_BATCHES)return Reason.BATCH_LIMIT;return Reason.NONE;}
 public static int batch(Goal g,int remaining,int carrierEmptySlotLimit,int capacity){if(admit(g)!=Reason.NONE || remaining<1 || remaining>g.quantity || carrierEmptySlotLimit<1)return 0;int q=Math.min(remaining,Math.min(g.item.maxStack(),carrierEmptySlotLimit));return capacity>=q?q:0;}
 public static boolean withdrawal(Counts a,Counts b,int q){return q>0 && a.source()-b.source()==q && b.carrier()-a.carrier()==q && a.destination()==b.destination() && a.total()==b.total();}
 public static boolean deposit(Counts a,Counts b,int q){return q>0 && a.carrier()-b.carrier()==q && b.destination()-a.destination()==q && a.source()==b.source() && a.total()==b.total();}
 public enum Phase { READY, IN_FLIGHT, DELIVERED_LIVE, BLOCKED, UNCERTAIN }
 public static final class Session {
  private final Goal goal;private Phase phase;private Reason reason;private int delivered,batches,inFlight;
  public Session(Goal goal){this.goal=goal;reason=admit(goal);phase=reason==Reason.NONE?Phase.READY:Phase.BLOCKED;}
  public Goal goal(){return goal;}public Phase phase(){return phase;}public Reason reason(){return reason;}public int remaining(){return goal.quantity-delivered;}public int batches(){return batches;}public int inFlight(){return inFlight;}public int delivered(){return delivered;}
  public void observe(TransferObservation o){if(phase==Phase.BLOCKED || phase==Phase.UNCERTAIN || phase==Phase.DELIVERED_LIVE)throw new IllegalStateException("terminal courier");if(o.status()==Status.UNCERTAIN){phase=Phase.UNCERTAIN;reason=Reason.UNCERTAIN_EFFECT;return;}if(o.status()==Status.REFUSED){phase=Phase.BLOCKED;reason=o.reason();return;}
   if(o.status()==Status.LIVE_WITHDRAWN){if(phase!=Phase.READY || batches>=MAX_BATCHES || o.quantity()>remaining() || !withdrawal(o.before(),o.after(),o.quantity()))throw new IllegalArgumentException("unproved withdrawal");inFlight=o.quantity();batches++;phase=Phase.IN_FLIGHT;}
   else {if(phase!=Phase.IN_FLIGHT || o.quantity()!=inFlight || !deposit(o.before(),o.after(),o.quantity()))throw new IllegalArgumentException("old/foreign carrier stock cannot discharge batch");delivered+=inFlight;inFlight=0;phase=delivered==goal.quantity?Phase.DELIVERED_LIVE:Phase.READY;}
  }
 }
 private DeliveryPolicy(){}
}
