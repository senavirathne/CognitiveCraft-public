package dev.aivillages.core.kernel.logistics;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.logistics.TransferObservation.*;
import java.util.*;
import dev.aivillages.core.kernel.storage.StorageCapabilities.Item;
class DAdversarialBoundaryTest {
 static final UUID W=UUID.randomUUID(),C=UUID.randomUUID();
 static final Item ITEM=new Item("minecraft:oak_planks","0".repeat(64),64,true,false);
 static DeliveryPolicy.Endpoint endpoint(){return new DeliveryPolicy.Endpoint(W,UUID.randomUUID(),"minecraft:overworld",Set.of(UUID.randomUUID()));}
 static DeliveryPolicy.Goal courier(){return new DeliveryPolicy.Goal(UUID.randomUUID(),C,endpoint(),endpoint(),ITEM,4,true,false);}
 static RecipientDeliveryPolicy.Goal handoff(){var a=new MobilityPolicy.Actor(W,UUID.randomUUID(),C,UUID.randomUUID(),1,1,"minecraft:overworld");return new RecipientDeliveryPolicy.Goal(UUID.randomUUID(),a,new RecipientDeliveryPolicy.Target(W,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,"minecraft:overworld"),RecipientDeliveryPolicy.Source.EXISTING_CARRIER,C,null,ITEM,4);}
 static TransferObservation refusal(Reason r){return new TransferObservation(Status.REFUSED,r,0,null,null,"unavailable");}
 static TransferObservation pickup(){return new TransferObservation(Status.LIVE_WITHDRAWN,Reason.NONE,4,new Counts(10,3,2),new Counts(6,7,2),"native");}
 static TransferObservation deposited(){return new TransferObservation(Status.LIVE_DEPOSITED,Reason.NONE,4,new Counts(6,7,2),new Counts(6,3,6),"native");}
 @Test void everyCourierRefusalBeforePickupPreservesOriginalRequestAndCannotRestart(){for(var r:Reason.values())if(r!=Reason.NONE){var g=courier();var s=new DeliveryPolicy.Session(g);s.observe(refusal(r));assertSame(g,s.goal());assertEquals(0,s.delivered());assertEquals(0,s.inFlight());assertEquals(r,s.reason());assertThrows(IllegalStateException.class,()->s.observe(pickup()));}}
 @Test void everyCourierRefusalInFlightRetainsAttributedStockAndCannotRetry(){for(var r:Reason.values())if(r!=Reason.NONE){var g=courier();var s=new DeliveryPolicy.Session(g);s.observe(pickup());s.observe(refusal(r));assertSame(g,s.goal());assertEquals(4,s.inFlight());assertEquals(1,s.batches());assertEquals(0,s.delivered());assertThrows(IllegalStateException.class,()->s.observe(deposited()));}}
 @Test void everyRecipientRefusalBeforeAdmissionPreservesScopeAndRejectsCargoAuthority(){for(var r:Reason.values())if(r!=Reason.NONE){var g=handoff();var s=new RecipientDeliveryPolicy.Session(g);s.observe(refusal(r));assertSame(g,s.goal());assertEquals(0,s.inFlight());assertEquals(0,s.delivered());assertThrows(IllegalStateException.class,()->s.acceptExisting(4));}var g=handoff();var foreign=new RecipientDeliveryPolicy.Goal(g.request(),g.carrier(),g.recipient(),g.source(),UUID.randomUUID(),null,ITEM,4);assertEquals(Reason.UNAUTHORIZED,new RecipientDeliveryPolicy.Session(foreign).reason());}
 @Test void everyRecipientRefusalInFlightNeverSettlesOrReroutes(){for(var r:Reason.values())if(r!=Reason.NONE){var g=handoff();var s=new RecipientDeliveryPolicy.Session(g);s.acceptExisting(4);s.observe(refusal(r));assertSame(g,s.goal());assertEquals(4,s.inFlight());assertEquals(0,s.delivered());assertThrows(IllegalStateException.class,()->s.observe(deposited()));}}
 @Test void unknownNativePersistenceCannotBecomeCompletionOrFreshSourceCredit(){var s=new DeliveryPolicy.Session(courier());s.observe(pickup());s.observe(new TransferObservation(Status.UNCERTAIN,Reason.UNCERTAIN_EFFECT,0,null,null,"unknown native saves"));assertEquals(DeliveryPolicy.Phase.UNCERTAIN,s.phase());assertEquals(4,s.inFlight());assertEquals(0,s.delivered());assertThrows(IllegalStateException.class,()->s.observe(deposited()));var h=new RecipientDeliveryPolicy.Session(handoff());h.acceptExisting(4);h.observe(new TransferObservation(Status.UNCERTAIN,Reason.UNCERTAIN_EFFECT,0,null,null,"unknown native saves"));assertEquals(RecipientDeliveryPolicy.Phase.UNCERTAIN,h.phase());assertEquals(4,h.inFlight());assertThrows(IllegalStateException.class,()->h.acceptExisting(4));}
}
