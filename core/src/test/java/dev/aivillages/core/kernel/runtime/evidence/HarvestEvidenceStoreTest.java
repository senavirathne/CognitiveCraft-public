package dev.aivillages.core.kernel.runtime.evidence;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.runtime.evidence.NativeHarvest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic registered port and controllable acknowledgement futures; no native save claims. */
final class HarvestEvidenceStoreTest {
    static final class Time extends Clock {
        long now=100;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}
    }
    static final class Port implements HarvestEvidenceStore.NativePort {
        Entry completed;int calls;boolean current=true,restored,changed;
        Port(Intent i){completed=NativeHarvestTest.completed(i);}
        public boolean current(Intent i){return current;}
        public Result execute(Intent i){calls++;return completed.result();}
        public NativeDeposit.Witness restoredActor(NativeDeposit.Participant expected){return NativeHarvestTest.actor(completed,restored);}
        public SourceWitness restoredSource(Source expected){
            if(changed){var s=completed.result().source();return new SourceWitness(new Source(s.dimension(),s.x(),s.y(),s.z(),s.incarnation(),s.revision()+1,s.state()),NativeHarvestTest.marker(completed),restored);}
            return NativeHarvestTest.source(completed,restored);
        }
        public List<DropWitness> restoredDrops(List<Drop> expected){return NativeHarvestTest.drops(completed,restored);}
    }
    static final class Scene {
        final Time clock=new Time();final Intent intent=NativeHarvestTest.intent();final Port port=new Port(intent);
        final List<CompletableFuture<HarvestJournal.Snapshot>> writes=new ArrayList<>();final List<HarvestJournal.Snapshot> next=new ArrayList<>();
        final HarvestEvidenceStore store=new HarvestEvidenceStore(new HarvestJournal.Snapshot(intent.key().world(),UUID.randomUUID(),0,List.of()),(before,after)->{var f=new CompletableFuture<HarvestJournal.Snapshot>();writes.add(f);next.add(after);return f;},port,clock,100,false);
        void ack(){int n=writes.size()-1;writes.get(n).complete(next.get(n));store.tick();}
        HarvestEvidenceStore.Prepared prepare(){assertEquals(HarvestEvidenceStore.Code.PENDING,store.prepare(intent).code());ack();return store.prepare(intent).prepared();}
        void complete(){var prepared=prepare();assertEquals(HarvestEvidenceStore.Code.PENDING,store.perform(prepared).code());ack();assertEquals(HarvestEvidenceStore.Code.RESULT_ACKNOWLEDGED,store.perform(prepared).code());}
    }
    @Test void preparationAndResultMustAcknowledgeBeforeTheirCapabilitiesAdvance() {
        var s=new Scene();assertEquals(HarvestEvidenceStore.Code.PENDING,s.store.prepare(s.intent).code());assertEquals(0,s.port.calls);
        assertTrue(s.store.protectedJobs().contains(s.intent.key().step()));s.ack();var prepared=s.store.prepare(s.intent).prepared();
        assertEquals(HarvestEvidenceStore.Code.PENDING,s.store.perform(prepared).code());assertEquals(1,s.port.calls);
        assertEquals(HarvestEvidenceStore.Code.PENDING,s.store.perform(prepared).code());s.ack();
        assertEquals(HarvestEvidenceStore.Code.RESULT_ACKNOWLEDGED,s.store.perform(prepared).code());assertEquals(1,s.port.calls);
    }
    @Test void anotherProcessCannotExecuteRestoredPreparedEvidenceOrReuseTheOldCapability() {
        var s=new Scene();var old=s.prepare();var reopened=new HarvestEvidenceStore(s.store.snapshot(),(a,b)->CompletableFuture.completedFuture(b),s.port,s.clock,100,false);
        assertEquals(HarvestEvidenceStore.Code.RECOVERY_REQUIRED,reopened.prepare(s.intent).code());assertEquals(HarvestEvidenceStore.Code.STALE,reopened.perform(old).code());assertEquals(0,s.port.calls);
    }
    @Test void everyActualSavedParticipantIsReobservedAndReleaseWaitsForDurableVerification() {
        var s=new Scene();s.complete();assertEquals(Reconciliation.UNPROVEN,s.store.reconcile(s.intent.key()).result());
        s.port.restored=true;var proof=s.store.reconcile(s.intent.key());assertEquals(Reconciliation.SAVED_COMPLETE,proof.result());
        s.port.changed=true;assertEquals(HarvestEvidenceStore.Code.STALE,s.store.verify(proof));s.port.changed=false;
        proof=s.store.reconcile(s.intent.key());assertEquals(HarvestEvidenceStore.Code.PENDING,s.store.verify(proof));assertFalse(s.store.protectedJobs().isEmpty());
        s.ack();assertTrue(s.store.protectedJobs().isEmpty());assertEquals(HarvestEvidenceStore.Code.RESOLVED,s.store.verify(proof));assertEquals(1,s.port.calls);
    }
    @Test void expiredAcknowledgementPreservesTheExclusionAndNeverRepeatsTheNativeCall() {
        var s=new Scene();var prepared=s.prepare();s.store.perform(prepared);assertEquals(1,s.port.calls);
        s.clock.now+=101;s.store.tick();assertTrue(s.store.fenced());s.ack();
        assertEquals(HarvestEvidenceStore.Code.FENCED,s.store.perform(prepared).code());assertEquals(1,s.port.calls);assertFalse(s.store.protectedJobs().isEmpty());
    }
    @Test void wrongPublicationBodyCannotMintAnExecutionCapability() {
        var s=new Scene();var original=s.store.snapshot();s.store.prepare(s.intent);
        var wrong=new HarvestJournal.Snapshot(original.world(),UUID.randomUUID(),1,List.of(new Entry(s.intent,null)));
        s.writes.getFirst().complete(wrong);s.store.tick();
        assertTrue(s.store.fenced());assertSame(original,s.store.snapshot());assertEquals(0,s.port.calls);
        assertEquals(HarvestEvidenceStore.Code.FENCED,s.store.prepare(s.intent).code());assertFalse(s.store.protectedJobs().isEmpty());
    }
}
