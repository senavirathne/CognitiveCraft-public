package dev.aivillages.core.kernel.runtime.evidence;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.runtime.evidence.NativePickup.*;
import static org.junit.jupiter.api.Assertions.*;

/** Controlled real owner acknowledgements and registered synthetic ports; no Minecraft P qualification. */
final class PickupEvidenceStoreTest {
    static final class Port implements PickupEvidenceStore.NativePort {
        int calls;boolean current=true,restored,changed,throwsNative;Entry outcome;
        public boolean current(Intent intent){return current;}
        public Result execute(Intent intent){calls++;if(throwsNative)throw new IllegalStateException("after possible native effect");return outcome==null?NativePickupTest.completed(intent,0).result():outcome.result();}
        public Observation restored(Entry entry) {
            if(!restored)return null;var e=entry.result()==null?NativePickupTest.completed(entry.intent(),0):entry;var o=NativePickupTest.saved(e);
            if(!changed)return o;var rows=new ArrayList<>(o.memberships());var w=rows.getFirst();
            rows.set(0,NativePickupTest.witness(w,w.savedPresent(),w.currentPresent(),"f".repeat(64)));return NativePickupTest.members(o,rows);
        }
    }
    static final class Scene {
        final HarvestEvidenceStoreTest.Time clock=new HarvestEvidenceStoreTest.Time();
        final Intent intent;final PickupFixtures.HarvestPort hport;final HarvestEvidenceStore harvest;
        final HarvestEvidenceStore.Acknowledged ancestor;final Port port=new Port();
        final List<CompletableFuture<PickupJournal.Snapshot>> writes=new ArrayList<>();final List<PickupJournal.Snapshot> next=new ArrayList<>();
        final PickupEvidenceStore store;
        Scene(){this(PickupFixtures.intent(UUID.randomUUID()));}
        Scene(Intent intent) {
            this.intent=intent;hport=new PickupFixtures.HarvestPort(intent.harvest());harvest=PickupFixtures.liveHarvest(intent.harvest(),hport,clock);
            ancestor=harvest.acknowledged(intent.harvest().intent().key());assertNotNull(ancestor);
            store=new PickupEvidenceStore(new PickupJournal.Snapshot(intent.key().world(),UUID.randomUUID(),0,List.of()),harvest,
                    (a,b)->{var future=new CompletableFuture<PickupJournal.Snapshot>();writes.add(future);next.add(b);return future;},port,clock,100,false);
        }
        void ack(){int n=writes.size()-1;writes.get(n).complete(next.get(n));store.tick();}
        PickupEvidenceStore.Prepared prepare(){assertEquals(PickupEvidenceStore.Code.PENDING,store.prepare(intent,ancestor).code());ack();return store.prepare(intent,ancestor).prepared();}
        void complete(){var prepared=prepare();assertEquals(PickupEvidenceStore.Code.PENDING,store.perform(prepared).code());ack();assertEquals(PickupEvidenceStore.Code.RESULT_ACKNOWLEDGED,store.perform(prepared).code());}
    }
    @Test void preparedAndResultAcksPrecedeExecutionAndFutureLinkage() {
        var s=new Scene();assertEquals(PickupEvidenceStore.Code.PENDING,s.store.prepare(s.intent,s.ancestor).code());assertEquals(0,s.port.calls);
        assertNull(s.store.acknowledged(s.intent.key()));assertTrue(s.store.protectedJobs().contains(s.intent.key().step()));s.ack();
        var prepared=s.store.prepare(s.intent,s.ancestor).prepared();assertEquals(PickupEvidenceStore.Code.PENDING,s.store.perform(prepared).code());assertEquals(1,s.port.calls);
        assertEquals(PickupEvidenceStore.Code.PENDING,s.store.perform(prepared).code());assertNull(s.store.acknowledged(s.intent.key()));s.ack();
        assertEquals(PickupEvidenceStore.Code.RESULT_ACKNOWLEDGED,s.store.perform(prepared).code());assertEquals(1,s.port.calls);assertTrue(s.store.owns(s.store.acknowledged(s.intent.key())));
    }
    @Test void restoredHarvestBytesCannotMintAPickupCapability() {
        var s=new Scene();var restored=new HarvestEvidenceStore(s.harvest.snapshot(),(a,b)->CompletableFuture.completedFuture(b),s.hport,s.clock,100,false);
        var store=new PickupEvidenceStore(s.store.snapshot(),restored,(a,b)->CompletableFuture.completedFuture(b),s.port,s.clock,100,false);
        assertNull(restored.acknowledged(s.intent.harvest().intent().key()));assertEquals(PickupEvidenceStore.Code.STALE,store.prepare(s.intent,s.ancestor).code());assertEquals(0,s.port.calls);
    }
    @Test void foreignAndNullHarvestLinksRefuseBeforePublication() {
        var s=new Scene();var other=new Scene();assertEquals(PickupEvidenceStore.Code.STALE,s.store.prepare(s.intent,other.ancestor).code());
        assertEquals(PickupEvidenceStore.Code.STALE,s.store.prepare(s.intent,null).code());assertTrue(s.writes.isEmpty());assertEquals(0,s.port.calls);
    }
    @Test void restoredPickupPreparedAndAnOldPrivateCapabilityCannotReplay() {
        var s=new Scene();var old=s.prepare();var restored=new PickupEvidenceStore(s.store.snapshot(),s.harvest,(a,b)->CompletableFuture.completedFuture(b),s.port,s.clock,100,false);
        assertEquals(PickupEvidenceStore.Code.RECOVERY_REQUIRED,restored.prepare(s.intent,s.ancestor).code());assertEquals(PickupEvidenceStore.Code.STALE,restored.perform(old).code());assertEquals(0,s.port.calls);
    }
    @Test void currentPortRevalidationCannotBeReplacedByTheAncestralLink() {
        var s=new Scene();var prepared=s.prepare();s.port.current=false;
        assertEquals(PickupEvidenceStore.Code.STALE,s.store.perform(prepared).code());assertEquals(0,s.port.calls);assertFalse(s.store.protectedJobs().isEmpty());
    }
    @Test void elapsedDeadlineDoesNotResetOrAuthorizeAPreparedEffect() {
        var s=new Scene();var prepared=s.prepare();s.clock.now=s.intent.conditions().actionDeadlineEpochMillis()+1;
        assertEquals(PickupEvidenceStore.Code.STALE,s.store.perform(prepared).code());assertEquals(0,s.port.calls);
        assertEquals(1L,s.intent.conditions().usage().get(dev.aivillages.core.kernel.Budgets.Kind.COMMITTED_EFFECTS));
    }
    @Test void parentPublicationInvalidatesTheLinkBeforeAnyPickupCall() {
        var s=new Scene();var prepared=s.prepare();s.hport.restored=true;
        assertEquals(HarvestEvidenceStore.Code.PENDING,s.harvest.verify(s.harvest.reconcile(s.intent.harvest().intent().key())));
        assertEquals(PickupEvidenceStore.Code.RECOVERY_REQUIRED,s.store.perform(prepared).code());assertEquals(0,s.port.calls);assertFalse(s.store.protectedJobs().isEmpty());
    }
    @Test void lateResultAckKeepsProtectionAndNeverRepeatsThePhysicalPort() {
        var s=new Scene();var prepared=s.prepare();s.store.perform(prepared);s.clock.now+=101;s.store.tick();s.ack();
        assertTrue(s.store.fenced());assertEquals(PickupEvidenceStore.Code.FENCED,s.store.perform(prepared).code());assertEquals(1,s.port.calls);
        assertNull(s.store.acknowledged(s.intent.key()));assertFalse(s.store.protectedJobs().isEmpty());
    }
    @Test void exceptionAfterAStartedCallFencesInsteadOfRetrying() {
        var s=new Scene();var prepared=s.prepare();s.port.throwsNative=true;assertThrows(IllegalStateException.class,()->s.store.perform(prepared));
        assertTrue(s.store.fenced());assertEquals(PickupEvidenceStore.Code.FENCED,s.store.perform(prepared).code());assertEquals(1,s.port.calls);assertFalse(s.store.protectedJobs().isEmpty());
    }
    @Test void wrongOrFailedPublicationCannotAcknowledgeOrAdvanceState() {
        for(boolean error:List.of(false,true)) {
            var s=new Scene();var before=s.store.snapshot();s.store.prepare(s.intent,s.ancestor);
            if(error)s.writes.getFirst().completeExceptionally(new java.io.IOException("storage unavailable"));
            else s.writes.getFirst().complete(new PickupJournal.Snapshot(before.world(),UUID.randomUUID(),1,List.of(new Entry(s.intent,null))));
            s.store.tick();assertTrue(s.store.fenced());assertSame(before,s.store.snapshot());assertEquals(0,s.port.calls);assertFalse(s.store.protectedJobs().isEmpty());
        }
    }
    @Test void completeSavedCohortIsReobservedAndReleaseWaitsForVerificationAck() {
        var s=new Scene();s.complete();assertEquals(Reconciliation.WAIT_WORLD,s.store.reconcile(s.intent.key()).result());
        var linkage=s.store.acknowledged(s.intent.key());s.port.restored=true;var proof=s.store.reconcile(s.intent.key());assertEquals(Reconciliation.SAVED_COMPLETE,proof.result());
        s.port.changed=true;assertEquals(PickupEvidenceStore.Code.STALE,s.store.verify(proof));s.port.changed=false;
        proof=s.store.reconcile(s.intent.key());assertEquals(PickupEvidenceStore.Code.PENDING,s.store.verify(proof));assertFalse(s.store.owns(linkage));assertFalse(s.store.protectedJobs().isEmpty());
        s.ack();assertEquals(PickupEvidenceStore.Code.RESOLVED,s.store.verify(proof));assertTrue(s.store.protectedJobs().isEmpty());assertFalse(s.store.owns(linkage));assertEquals(1,s.port.calls);
    }
    @Test void preparedOnlyAndUnreceiptedEffectsNeverGetPositiveVerificationOrLiveResultLinks() {
        var s=new Scene();s.prepare();s.port.restored=true;var proof=s.store.reconcile(s.intent.key());
        assertEquals(Reconciliation.PARTIAL_UNRESOLVED,proof.result());assertEquals(PickupEvidenceStore.Code.STALE,s.store.verify(proof));assertNull(s.store.acknowledged(s.intent.key()));
        var u=new Scene();var actual=NativePickupTest.completed(u.intent,0).result();
        u.port.outcome=new Entry(u.intent,new Result(actual.intent(),Live.UNRECEIPTED_EFFECT,actual.source(),actual.actor(),actual.remaining(),null,actual.usage(),"lost original receipt"));
        u.complete();u.port.restored=true;assertEquals(Reconciliation.PARTIAL_UNRESOLVED,u.store.reconcile(u.intent.key()).result());assertNull(u.store.acknowledged(u.intent.key()));assertFalse(u.store.protectedJobs().isEmpty());
    }
    @Test void verificationAndLiveResultLinksBelongToExactlyOneOwnerAndProcess() {
        var s=new Scene();s.complete();s.port.restored=true;var proof=s.store.reconcile(s.intent.key());var linkage=s.store.acknowledged(s.intent.key());
        var other=new PickupEvidenceStore(s.store.snapshot(),s.harvest,(a,b)->CompletableFuture.completedFuture(b),s.port,s.clock,100,false);
        assertEquals(PickupEvidenceStore.Code.STALE,other.verify(proof));assertFalse(other.owns(linkage));assertNull(other.acknowledged(s.intent.key()));assertEquals(1,s.port.calls);
    }
    @Test void oversizedAncestorRefusesBeforeAnyPublicationOrPhysicalCall() {
        var s=new Scene(PickupFixtures.intent(UUID.randomUUID(),"test:"+"a".repeat(123),4));
        assertTrue(NativePickup.encode(new Entry(s.intent,null)).length>PickupEvidenceStore.MAX_INTENT_BYTES);
        assertEquals(PickupEvidenceStore.Code.FULL,s.store.prepare(s.intent,s.ancestor).code());assertTrue(s.writes.isEmpty());assertEquals(0,s.port.calls);
    }
    @Test void anotherThreadCannotAdvanceTheGameThreadOwner() throws Exception {
        var s=new Scene();var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var other=new Thread(()->{try{s.store.prepare(s.intent,s.ancestor);}catch(Throwable t){failure.set(t);}});other.start();other.join();
        assertInstanceOf(IllegalStateException.class,failure.get());assertTrue(s.writes.isEmpty());assertEquals(0,s.port.calls);
    }
    @Test void allOriginalOakAndBirchQuantitiesRemainWithinTheAdmittedFirstPickupEnvelope() {
        for(String item:List.of("minecraft:oak_log","minecraft:birch_log"))for(int quantity:List.of(1,2,3,4)) {
            var i=NativePickupTest.intent(NativePickupTest.harvest(item,1),quantity);var s=new Scene(i);
            assertEquals(PickupEvidenceStore.Code.PENDING,s.store.prepare(i,s.ancestor).code());assertEquals(0,s.port.calls);
            var e=NativePickupTest.completed(i,0);var verified=new Entry(i,e.result(),new Verification(Reconciliation.SAVED_COMPLETE,NativePickupTest.saved(e),UUID.randomUUID(),100));
            assertTrue(NativePickup.encode(verified).length<=MAX_RECORD_BYTES);
        }
    }
}
