package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;
import static org.junit.jupiter.api.Assertions.*;

/** Causal data/store contracts; these synthetic records contribute no native case passes. */
final class NativeJoinedDepositTest {
    static Entry entry() {
        var p=NativePickupTest.completed(NativePickupTest.intent(NativePickupTest.harvest(1),4),0);var h=p.intent().harvest().intent();var key=p.intent().key();
        var actor=p.result().actor();var empty=Collections.nCopies(27,new Slot("minecraft:air",0));
        var chest=new Participant(Kind.CHEST,actor.dimension(),"10,64,10",UUID.randomUUID(),5,empty);
        var old=p.intent().conditions();var conditions=new Conditions(old.citizen(),old.jobRevision()+1,old.deadlineEpochMillis(),15_000,old.effectsRemaining()-1,40,2500,1000,old.claims(),p.result().usage());
        var trail=p.intent().actorTrail();var arrival=new NativePickup.Trail(trail.entity(),trail.incarnation(),trail.chunks(),trail.chunks().getFirst());
        var prefix=new NativeJoinedDeposit.Prefix(p,arrival);
        var intent=new Intent(new Key(key.world(),key.root(),key.step(),key.attempt(),key.generation(),3),h.origin(),h.run(),UUID.randomUUID(),
                actor,chest,h.item(),0,0,64,conditions,new ResourceReceiptPrefix(List.of(p.intent().harvest().result().receipt(),p.result().receipt())),prefix);
        var actorSlots=new ArrayList<>(actor.slots());actorSlots.set(0,new Slot("minecraft:air",0));
        var chestSlots=new ArrayList<>(chest.slots());chestSlots.set(0,new Slot(h.item(),1));
        var afterActor=new Participant(actor.kind(),actor.dimension(),actor.address(),actor.incarnation(),actor.revision()+1,actorSlots);
        var afterChest=new Participant(chest.kind(),chest.dimension(),chest.address(),chest.incarnation(),chest.revision()+1,chestSlots);
        var r=p.result().receipt();var receipt=new ResourceDelivery.Receipt(UUID.randomUUID(),r.origin(),r.runId(),intent.operation(),r.batchId(),r.actor(),r.source(),r.destination(),r.item(),
                ResourceDelivery.Stage.DEPOSIT,1,r.sourceBlock(),r.dropIds());
        var usage=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);usage.putAll(p.result().usage());usage.put(Budgets.Kind.ATTEMPTED_EFFECTS,3L);usage.put(Budgets.Kind.COMMITTED_EFFECTS,3L);
        return new Entry(intent,NativeDeposit.observed(intent,afterActor,afterChest,"",receipt,usage));
    }
    static Marker marker(Entry e){return new Marker(e.token(),e.intent().digest(),MarkerPhase.RESULT,e.result().digest());}
    static NativePickup.Observation saved(Entry e) {
        var prefix=e.intent().pickupPrefix();var p=prefix.pickup();var marker=marker(e);var rows=new ArrayList<NativePickup.MembershipWitness>();
        for(var chunk:prefix.actorTrail().chunks())rows.add(new NativePickup.MembershipWitness(new NativePickup.Member(NativePickup.Role.ACTOR,prefix.actorTrail().entity(),chunk),
                e.result().actor().incarnation(),e.result().actor().revision(),marker,true,chunk.equals(prefix.actorTrail().current()),chunk.equals(prefix.actorTrail().current()),NativeDeposit.sha(chunk.toString())));
        for(var chunk:p.intent().dropTrail().chunks())rows.add(new NativePickup.MembershipWitness(new NativePickup.Member(NativePickup.Role.DROP,p.intent().dropTrail().entity(),chunk),
                p.intent().dropTrail().incarnation(),p.intent().harvest().result().drops().getFirst().revision(),marker,true,false,false,NativeDeposit.sha(chunk.toString())));
        return new NativePickup.Observation(new Witness(e.result().actor(),marker,true),new NativeHarvest.SourceWitness(p.result().source(),marker,true),null,
                new NativePickup.TrailWitness(prefix.actorTrail(),marker,true),new NativePickup.TrailWitness(p.intent().dropTrail(),marker,true),rows);
    }
    static Witness chest(Entry e){return new Witness(e.result().chest(),marker(e),true);}
    @Test void completeOriginalChainRoundTripsWithItsOwnAdditiveSchemaAndThreeCumulativeEffects() {
        var e=entry();var o=saved(e);assertEquals(Reconciliation.SAVED_COMPLETE,classify(e,o.actor(),chest(e),o));
        var verified=new Entry(e.intent(),e.result(),new Verification(Reconciliation.SAVED_COMPLETE,e.result().actor(),e.result().chest(),marker(e),marker(e),UUID.randomUUID(),100,o));
        assertEquals(verified,decode(encode(verified)));assertTrue(new String(encode(verified),java.nio.charset.StandardCharsets.UTF_8).contains("\"schema\":3"));
        assertEquals(3L,e.result().usage().get(Budgets.Kind.COMMITTED_EFFECTS));assertEquals(2,e.intent().receiptPrefix().receipts().size());
    }
    @Test void actorAndChestCountsAloneCannotUpgradeAnOldDepositIntoJoinedProof() {
        var e=entry();assertEquals(Reconciliation.UNPROVEN,classify(e,saved(e).actor(),chest(e)));
        assertEquals(Reconciliation.WAIT_WORLD,classify(e,saved(e).actor(),chest(e),null));
        assertThrows(IllegalArgumentException.class,()->new Entry(e.intent(),e.result(),new Verification(Reconciliation.SAVED_COMPLETE,e.result().actor(),e.result().chest(),marker(e),marker(e),UUID.randomUUID(),100)));
    }
    @Test void missingDepartureFrameAndUnexpectedLiveDropDoNotProveTheOriginalDeposit() {
        var e=entry();var o=saved(e);var missing=NativePickupTest.members(o,o.memberships().subList(1,o.memberships().size()));
        assertEquals(Reconciliation.WAIT_WORLD,classify(e,o.actor(),chest(e),missing));
        var original=e.intent().pickupPrefix().pickup().intent().harvest().result().drops().getFirst();
        var duplicate=new NativePickup.Observation(o.actor(),o.source(),new NativeHarvest.DropWitness(original,marker(e),true),o.actorTrail(),o.dropTrail(),o.memberships());
        assertEquals(Reconciliation.UNPROVEN,classify(e,o.actor(),chest(e),duplicate));
    }
    @Test void returnedAirAtTheOriginalCoordinatesCannotBorrowTheSavedSourceIncarnation() {
        var e=entry();var o=saved(e);var s=o.source().source();
        var replacement=new NativeHarvest.Source(s.dimension(),s.x(),s.y(),s.z(),UUID.randomUUID(),s.revision(),s.state());
        var changed=new NativePickup.Observation(o.actor(),new NativeHarvest.SourceWitness(replacement,marker(e),true),null,o.actorTrail(),o.dropTrail(),o.memberships());
        assertEquals(Reconciliation.UNPROVEN,classify(e,o.actor(),chest(e),changed));
    }
    @Test void oldHarvestOrPickupMarkerCannotStandInForTheJoinedNativeCohort() {
        var e=entry();var o=saved(e);var p=e.intent().pickupPrefix().pickup();var prior=NativePickupTest.marker(p);
        var old=new NativePickup.Observation(o.actor(),new NativeHarvest.SourceWitness(o.source().source(),prior,true),null,o.actorTrail(),o.dropTrail(),o.memberships());
        assertEquals(Reconciliation.UNPROVEN,classify(e,o.actor(),chest(e),old));
        assertEquals(Reconciliation.UNPROVEN,classify(e,new Witness(e.result().actor(),prior,true),chest(e),o));
    }
    @Test void repeatedOrForeignNativeFramesAreRejectedEvenWithEqualCounts() {
        var e=entry();var o=saved(e);var rows=new ArrayList<>(o.memberships());rows.set(1,rows.getFirst());
        assertNotEquals(Reconciliation.SAVED_COMPLETE,classify(e,o.actor(),chest(e),NativePickupTest.members(o,rows)));
        rows=new ArrayList<>(o.memberships());var r=rows.getLast();rows.set(rows.size()-1,NativePickupTest.witness(r,false,false,"f".repeat(64)));
        assertNotEquals(Reconciliation.SAVED_COMPLETE,classify(e,o.actor(),chest(e),NativePickupTest.members(o,rows)));
    }
    @Test void verificationPublicationWaitsForItsOwnDurableAcknowledgementAndNeverExecutes() {
        var e=entry();var o=saved(e);var pending=new CompletableFuture<DepositJournal.Snapshot>();var next=new DepositJournal.Snapshot[1];
        var port=new DepositEvidenceStore.NativePort() {
            public boolean current(Intent intent){throw new AssertionError("No recovery mutation");}public Result execute(Intent intent){throw new AssertionError("No replay");}
            public Witness restored(Participant expected){return expected.kind()==Kind.ACTOR?o.actor():chest(e);}
            public NativePickup.Observation restoredLineage(Entry entry){return o;}
        };
        var store=new DepositEvidenceStore(new DepositJournal.Snapshot(e.intent().key().world(),UUID.randomUUID(),1,List.of(e)),(a,b)->{next[0]=b;return pending;},port,Clock.fixed(Instant.ofEpochMilli(100),ZoneOffset.UTC),30_000,false);
        var proof=store.reconcile(e.intent().key());assertEquals(Reconciliation.SAVED_COMPLETE,proof.result());assertEquals(DepositEvidenceStore.Code.PENDING,store.verify(proof));
        assertTrue(store.protectedJobs().contains(e.intent().key().root()));pending.complete(next[0]);store.tick();
        assertEquals(DepositEvidenceStore.Code.RESOLVED,store.verify(proof));assertTrue(store.protectedJobs().isEmpty());
    }
}
