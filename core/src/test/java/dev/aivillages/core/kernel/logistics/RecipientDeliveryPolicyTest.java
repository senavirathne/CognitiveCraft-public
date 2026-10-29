package dev.aivillages.core.kernel.logistics;

import java.util.*;
import org.junit.jupiter.api.Test;
import dev.aivillages.core.kernel.storage.StorageAccessPolicy.Scope;
import dev.aivillages.core.kernel.storage.StorageCapabilities.Item;
import dev.aivillages.core.kernel.storage.StorageRegistry.Permission;
import static dev.aivillages.core.kernel.logistics.RecipientDeliveryPolicy.*;
import static dev.aivillages.core.kernel.logistics.TransferObservation.*;
import static org.junit.jupiter.api.Assertions.*;

class RecipientDeliveryPolicyTest {
    static UUID id(int n){return new UUID(0,n);}
    final MobilityPolicy.Actor actor=new MobilityPolicy.Actor(id(1),id(2),id(3),id(4),1,1,"minecraft:overworld");
    final Scope scope=new Scope(id(1),id(5),id(2),id(3));
    final Item item=new Item("minecraft:oak_planks","0".repeat(64),64,true,false);
    final Target target=new Target(id(1),id(6),id(7),id(8),1,1,"minecraft:overworld");
    final DeliveryPolicy.Endpoint source=new DeliveryPolicy.Endpoint(id(1),id(9),"minecraft:overworld",Set.of(id(10)));
    Goal existing(int q){return new Goal(id(11),actor,target,Source.EXISTING_CARRIER,actor.citizen(),null,item,q);}
    Goal stored(int q,Item key){return new Goal(id(11),actor,target,Source.STORAGE,null,source,key,q);}
    RecipientResolver.Citizen citizen(Target t,String name,boolean visible,boolean loaded,Set<Permission> rights){return new RecipientResolver.Citizen(scope,t.epoch(),t.citizen(),t.entity(),t.generation(),t.policyRevision(),name,t.dimension(),"1".repeat(64),100,visible,false,loaded,rights);}
    TransferObservation deposit(int q,Counts before,Counts after){return new TransferObservation(Status.LIVE_DEPOSITED,Reason.NONE,q,before,after,"native recipient");}
    @Test void quantitiesOneFourAndSixtyFourRemainExact(){for(int q:new int[]{1,4,64})assertEquals(Reason.NONE,admit(existing(q)));}
    @Test void zeroAndSixtyFiveNeverBecomeImplicitAmounts(){for(int q:new int[]{0,65,-1})assertThrows(IllegalArgumentException.class,()->existing(q));}
    @Test void sameCitizenCannotBeItsOwnRecipient(){Target same=new Target(id(1),actor.citizen(),id(7),id(8),1,1,actor.dimension());assertEquals(Reason.SAME_ENDPOINT,admit(new Goal(id(11),actor,same,Source.EXISTING_CARRIER,id(3),null,item,4)));}
    @Test void sameNativeEntityCannotMasqueradeAsDistinctRecipient(){Target same=new Target(id(1),id(6),actor.entity(),id(8),1,1,actor.dimension());assertEquals(Reason.SAME_ENDPOINT,admit(new Goal(id(11),actor,same,Source.EXISTING_CARRIER,id(3),null,item,4)));}
    @Test void thirdVillagerCannotBeAnImplicitWithdrawalSource(){assertEquals(Reason.UNAUTHORIZED,admit(new Goal(id(11),actor,target,Source.EXISTING_CARRIER,id(99),null,item,4)));}
    @Test void foreignWorldOrDimensionCannotReceiveCargo(){for(Target other:List.of(new Target(id(99),id(6),id(7),id(8),1,1,actor.dimension()),new Target(id(1),id(6),id(7),id(8),1,1,"minecraft:the_nether")))assertEquals(Reason.STALE_ENDPOINT,admit(new Goal(id(11),actor,other,Source.EXISTING_CARRIER,id(3),null,item,4)));}
    @Test void renamedSameCitizenRemainsTheBoundRecipient(){assertEquals(Reason.NONE,target.current(citizen(target,"Renamed receiver",true,true,Set.of(Permission.DEPOSIT)),scope,100));}
    @Test void namesakeNeverReroutesTheOriginalCitizen(){Target namesake=new Target(id(1),id(66),id(77),id(8),1,1,actor.dimension());assertEquals(Reason.STALE_ENDPOINT,target.current(citizen(namesake,"Receiver",true,true,Set.of(Permission.DEPOSIT)),scope,100));}
    @Test void changedEntityGenerationOrEpochInvalidatesRecipient(){for(Target t:List.of(new Target(id(1),id(6),id(77),id(8),1,1,actor.dimension()),new Target(id(1),id(6),id(7),id(8),2,1,actor.dimension()),new Target(id(1),id(6),id(7),id(88),1,1,actor.dimension())))assertEquals(Reason.STALE_ENDPOINT,target.current(citizen(t,"Receiver",true,true,Set.of(Permission.DEPOSIT)),scope,100));}
    @Test void policyRevisionAndObservationTickMustBeCurrent(){Target t=new Target(id(1),id(6),id(7),id(8),1,2,actor.dimension());assertEquals(Reason.STALE_ENDPOINT,target.current(citizen(t,"Receiver",true,true,Set.of(Permission.DEPOSIT)),scope,100));assertEquals(Reason.STALE_ENDPOINT,target.current(citizen(target,"Receiver",true,true,Set.of(Permission.DEPOSIT)),scope,101));}
    @Test void hiddenUnloadedAndMissingRecipientsFailClosed(){assertEquals(Reason.STALE_ENDPOINT,target.current(null,scope,100));for(boolean visible:new boolean[]{false,true})assertEquals(Reason.UNAUTHORIZED,target.current(citizen(target,"Private",visible,false,Set.of(Permission.DEPOSIT)),scope,100));assertEquals(Reason.UNAUTHORIZED,target.current(citizen(target,"Private",false,true,Set.of(Permission.DEPOSIT)),scope,100));}
    @Test void inspectOrANameNeverImpliesRecipientDepositPermission(){assertEquals(Reason.UNAUTHORIZED,target.current(citizen(target,"Public receiver",true,true,Set.of(Permission.INSPECT)),scope,100));}
    @Test void nondefaultAndContainerBearingComponentsAreUnsupported(){for(Item key:List.of(new Item(item.registryId(),item.fingerprint(),64,false,false),new Item(item.registryId(),item.fingerprint(),64,true,true)))assertEquals(Reason.UNSUPPORTED_ITEM,admit(stored(4,key)));}
    @Test void carryingBatchesRemainFiniteWithoutBroadeningStackSupport(){assertEquals(Reason.NONE,admit(stored(64,new Item("minecraft:ender_pearl","0".repeat(64),16,true,false))));assertEquals(Reason.BATCH_LIMIT,admit(stored(9,new Item("minecraft:iron_pickaxe","0".repeat(64),1,true,false))));}
    @Test void nativeReachCanNarrowButNeverWidenTwoPointFiveBlocks(){assertTrue(withinReach(2.5,6));assertFalse(withinReach(2.5001,6));assertFalse(withinReach(2,1.5));assertTrue(withinReach(1.4,1.5));assertFalse(withinReach(Double.NaN,2.5));assertFalse(withinReach(1,Double.POSITIVE_INFINITY));}
    @Test void explicitExistingFourLeavesTheOtherFiveAtSender(){Session s=new Session(existing(4));s.acceptExisting(4);s.observe(deposit(4,new Counts(0,9,1),new Counts(0,5,5)));assertEquals(Phase.DELIVERED_LIVE,s.phase());assertEquals(4,s.delivered());assertEquals(0,s.inFlight());}
    @Test void oldStockAndUnadmittedDeltasCannotCreateDeliveryCredit(){Session s=new Session(existing(4));assertThrows(IllegalArgumentException.class,()->s.observe(deposit(4,new Counts(0,9,1),new Counts(0,5,5))));s.acceptExisting(4);assertThrows(IllegalArgumentException.class,()->s.observe(deposit(4,new Counts(0,9,1),new Counts(0,9,5))));assertEquals(0,s.delivered());}
    @Test void fourStorageBatchesRetainOneRequestAndOldCarrierStock(){Item pearls=new Item("minecraft:ender_pearl","0".repeat(64),16,true,false);Session s=new Session(stored(64,pearls));for(int i=0;i<4;i++){int old=16*i;Counts a=new Counts(64-old,3,1+old),b=new Counts(48-old,19,1+old),c=new Counts(48-old,3,17+old);s.observe(new TransferObservation(Status.LIVE_WITHDRAWN,Reason.NONE,16,a,b,"carrier"));s.observe(deposit(16,b,c));}assertEquals(4,s.batches());assertEquals(64,s.delivered());assertEquals(id(11),s.goal().request());assertEquals(Phase.DELIVERED_LIVE,s.phase());}
    @Test void existingAndStorageSourceProofsCannotBeInterchanged(){Session s=new Session(existing(4));assertThrows(IllegalArgumentException.class,()->s.observe(new TransferObservation(Status.LIVE_WITHDRAWN,Reason.NONE,4,new Counts(10,3,1),new Counts(6,7,1),"carrier")));assertThrows(IllegalStateException.class,()->new Session(stored(4,item)).acceptExisting(4));}
    @Test void refusalRetainsInFlightUnitsAndCannotAutomaticallyRetry(){Session s=new Session(existing(4));s.acceptExisting(4);s.observe(new TransferObservation(Status.REFUSED,Reason.CAPACITY,0,null,null,"unavailable"));assertEquals(4,s.inFlight());assertEquals(0,s.delivered());assertEquals(Phase.BLOCKED,s.phase());assertThrows(IllegalStateException.class,()->s.acceptExisting(4));assertThrows(IllegalStateException.class,()->s.observe(deposit(4,new Counts(0,9,1),new Counts(0,5,5))));}
    @Test void uncertainNativeOutcomeIsNeverCompletionOrReplayAuthority(){Session s=new Session(existing(4));s.acceptExisting(4);s.observe(new TransferObservation(Status.UNCERTAIN,Reason.UNCERTAIN_EFFECT,4,null,null,"owner reconciliation"));assertEquals(Phase.UNCERTAIN,s.phase());assertEquals(0,s.delivered());assertEquals(4,s.inFlight());assertThrows(IllegalStateException.class,()->s.acceptExisting(4));}
    @Test void recipientNavigationCapturesOneFinitePosition(){MobilityPolicy.Point p=new MobilityPolicy.Point(actor.dimension(),12,201,7);MobilityPolicy.Goal g=MobilityPolicy.recipient(actor,p,100,1000,MobilityPolicy.Limits.maximum());assertEquals(MobilityPolicy.Mode.GO_RECIPIENT,g.mode());assertEquals(List.of(p),g.standing());assertEquals(p,g.endpoint());assertEquals(700,g.deadline());}
}
