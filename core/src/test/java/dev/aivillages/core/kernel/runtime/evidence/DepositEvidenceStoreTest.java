package dev.aivillages.core.kernel.runtime.evidence;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;
import static dev.aivillages.core.kernel.runtime.evidence.NativeDepositTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** A controlled fake port checks ordering; its counter is not native evidence. */
final class DepositEvidenceStoreTest {
    static final class Time extends Clock {
        long millis;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
        public long millis(){return millis;}public Instant instant(){return Instant.ofEpochMilli(millis);}
    }
    static final class Port implements DepositEvidenceStore.NativePort {
        int calls;boolean current=true;Witness actor,chest;Result answer;
        public boolean current(Intent intent){return current;}
        public Result execute(Intent intent){calls++;return answer==null?completed(intent).result():answer;}
        public Witness restored(Participant participant){return participant.kind()==Kind.ACTOR?actor:chest;}
    }
    static final class Scene {
        final Time clock=new Time();final Port port=new Port();final DepositEvidenceStore store;
        CompletableFuture<DepositJournal.Snapshot> write;DepositJournal.Snapshot proposed;
        Scene(){this(new DepositJournal.Snapshot(WORLD,UUID.randomUUID(),0,List.of()));}
        Scene(DepositJournal.Snapshot initial){store=new DepositEvidenceStore(initial,(expected,next)->{assertNull(write);proposed=next;return write=new CompletableFuture<>();},port,clock,10,false);}
        void ack(){var future=write;write=null;future.complete(proposed);store.tick();}
        DepositEvidenceStore.Prepared prepare(Intent intent){assertEquals(DepositEvidenceStore.Code.PENDING,store.prepare(intent).code());ack();return store.prepare(intent).prepared();}
    }
    @Test void nativeCallWaitsForPreparedAcknowledgementAndResultRetriesCannotRepeatIt() {
        var scene=new Scene();Intent intent=intent();assertEquals(DepositEvidenceStore.Code.PENDING,scene.store.prepare(intent).code());
        assertEquals(0,scene.port.calls);assertEquals(DepositEvidenceStore.Code.STALE,scene.store.perform(null).code());
        scene.ack();var prepared=scene.store.prepare(intent).prepared();assertNotNull(prepared);
        assertEquals(DepositEvidenceStore.Code.PENDING,scene.store.perform(prepared).code());assertEquals(1,scene.port.calls);
        assertEquals(DepositEvidenceStore.Code.PENDING,scene.store.perform(prepared).code());assertEquals(1,scene.port.calls);
        scene.ack();assertEquals(DepositEvidenceStore.Code.RESULT_ACKNOWLEDGED,scene.store.perform(prepared).code());assertEquals(1,scene.port.calls);
        assertEquals(completed(intent),scene.store.snapshot().entries().getFirst());
    }
    @Test void changedPreconditionsCannotRunAfterAnOldPreparedIntentAcknowledges() {
        var scene=new Scene();var prepared=scene.prepare(intent());scene.port.current=false;
        assertEquals(DepositEvidenceStore.Code.STALE,scene.store.perform(prepared).code());assertEquals(0,scene.port.calls);
        assertTrue(scene.store.protectedJobs().contains(prepared.intent().key().root()));
    }
    @Test void deadlineAndLateAcknowledgementCannotAuthorizeAnEffectOrDropAnExclusion() {
        var scene=new Scene();Intent intent=intent();scene.store.prepare(intent);scene.clock.millis=11;scene.store.tick();
        assertTrue(scene.store.fenced());assertEquals(Set.of(intent.key().root(),intent.key().step()),scene.store.protectedJobs());
        scene.ack();assertTrue(scene.store.fenced());assertEquals(DepositEvidenceStore.Code.FENCED,scene.store.prepare(intent).code());assertEquals(0,scene.port.calls);
        assertTrue(scene.store.excludes(intent.actor(),UUID.randomUUID()));
    }
    @Test void aForeignProcessCapabilityAndAStructurallyEqualWrongAcknowledgementAreRejected() {
        var first=new Scene();Intent intent=intent();var prepared=first.prepare(intent);var second=new Scene();
        assertEquals(DepositEvidenceStore.Code.STALE,second.store.perform(prepared).code());assertEquals(0,second.port.calls);
        var third=new Scene();third.store.prepare(intent);var p=third.proposed;
        third.write.complete(new DepositJournal.Snapshot(p.world(),p.epoch(),p.revision(),p.entries()));third.store.tick();assertTrue(third.store.fenced());assertEquals(0,third.port.calls);
    }
    @Test void restoredPreparedEvidenceRequiresRecoveryAndRepeatedAdoptionIsReadOnly() {
        Entry entry=new Entry(intent(),null);var scene=new Scene(new DepositJournal.Snapshot(WORLD,UUID.randomUUID(),1,List.of(entry)));
        assertEquals(DepositEvidenceStore.Code.RECOVERY_REQUIRED,scene.store.prepare(entry.intent()).code());assertEquals(0,scene.port.calls);
        Marker marker=marker(entry,MarkerPhase.PREPARED);scene.port.actor=new Witness(entry.intent().actor(),marker,true);scene.port.chest=new Witness(entry.intent().chest(),marker,true);
        var a=scene.store.reconcile(entry.intent().key());var b=scene.store.reconcile(entry.intent().key());
        assertEquals(Reconciliation.PREPARED_NO_EFFECT,a.result());assertEquals(a.entry(),b.entry());assertSame(scene.store.snapshot().entries().getFirst(),a.entry());assertEquals(0,scene.port.calls);assertNull(scene.write);
    }
    @Test void existingRecordCollisionAndAnUnavailableWriterCannotBecomePrepared() {
        var scene=new Scene();Intent intent=intent();scene.prepare(intent);
        Intent conflict=new Intent(intent.key(),intent.origin(),intent.run(),intent.operation(),actor(2,7),intent.chest(),intent.item(),0,0,64,intent.conditions());
        assertEquals(DepositEvidenceStore.Code.STALE,scene.store.prepare(conflict).code());assertEquals(0,scene.port.calls);
        var failed=new Scene();failed.store.prepare(intent);failed.write.completeExceptionally(new IllegalStateException("fixture storage failure"));failed.store.tick();
        assertTrue(failed.store.fenced());assertEquals(0,failed.port.calls);assertTrue(failed.store.protectedJobs().contains(intent.key().root()));
    }
    @Test void savedReconciliationMustAcknowledgeBeforeASecondEffectCanUseTheParticipants() {
        Entry entry=completed(intent());var scene=new Scene(new DepositJournal.Snapshot(WORLD,UUID.randomUUID(),1,List.of(entry)));
        Marker marker=marker(entry,MarkerPhase.RESULT);scene.port.actor=new Witness(entry.result().actor(),marker,true);scene.port.chest=new Witness(entry.result().chest(),marker,true);
        Intent next=intent();assertEquals(DepositEvidenceStore.Code.RECOVERY_REQUIRED,scene.store.prepare(next).code());
        var proof=scene.store.reconcile(entry.intent().key());assertEquals(Reconciliation.SAVED_COMPLETE,proof.result());
        assertEquals(DepositEvidenceStore.Code.PENDING,scene.store.verify(proof));assertTrue(scene.store.excludes(entry.intent().actor(),UUID.randomUUID()));
        scene.ack();assertFalse(scene.store.excludes(entry.intent().actor(),UUID.randomUUID()));assertTrue(scene.store.protectedJobs().isEmpty());
        assertEquals(DepositEvidenceStore.Code.RESOLVED,scene.store.verify(proof));assertNull(scene.write);
        var restored=new Scene(scene.store.snapshot());assertEquals(DepositEvidenceStore.Code.RESOLVED,restored.store.prepare(entry.intent()).code());
        assertEquals(Reconciliation.SAVED_COMPLETE,restored.store.reconcile(entry.intent().key()).result());assertEquals(0,restored.port.calls);
        assertEquals(DepositEvidenceStore.Code.PENDING,restored.store.prepare(next).code());assertEquals(0,restored.port.calls);
    }
    @Test void changedParticipantBetweenCaptureAndVerificationCannotCloseAnExclusion() {
        Entry entry=completed(intent());var scene=new Scene(new DepositJournal.Snapshot(WORLD,UUID.randomUUID(),1,List.of(entry)));
        Marker marker=marker(entry,MarkerPhase.RESULT);scene.port.actor=new Witness(entry.result().actor(),marker,true);scene.port.chest=new Witness(entry.result().chest(),marker,true);
        var proof=scene.store.reconcile(entry.intent().key());scene.port.chest=new Witness(chest(6,13),marker,true);
        assertEquals(DepositEvidenceStore.Code.STALE,scene.store.verify(proof));assertNull(scene.write);assertFalse(scene.store.protectedJobs().isEmpty());assertEquals(0,scene.port.calls);
    }
    @Test void preparedAcknowledgementCannotExtendTheOriginalPhysicalDeadline() {
        var scene=new Scene();var prepared=scene.prepare(intent());scene.clock.millis=10_001;
        assertEquals(DepositEvidenceStore.Code.STALE,scene.store.perform(prepared).code());assertEquals(0,scene.port.calls);
        assertFalse(scene.store.protectedJobs().isEmpty());
    }
    @Test void unreceiptedCompleteDeltaAcknowledgesButCannotReleaseOrInvokeAgain() {
        var scene=new Scene();Intent intent=intent();var prepared=scene.prepare(intent);
        scene.port.answer=observed(intent,actor(0,8),chest(6,12),"native host exception after both inventory writes");
        assertEquals(DepositEvidenceStore.Code.PENDING,scene.store.perform(prepared).code());scene.ack();
        assertEquals(DepositEvidenceStore.Code.RESULT_ACKNOWLEDGED,scene.store.perform(prepared).code());assertEquals(1,scene.port.calls);
        Entry entry=scene.store.snapshot().entries().getFirst();Marker marker=marker(entry,MarkerPhase.RESULT);
        scene.port.actor=new Witness(entry.result().actor(),marker,true);scene.port.chest=new Witness(entry.result().chest(),marker,true);
        var proof=scene.store.reconcile(intent.key());assertEquals(Reconciliation.PARTIAL_UNRESOLVED,proof.result());
        assertEquals(DepositEvidenceStore.Code.STALE,scene.store.verify(proof));assertFalse(scene.store.protectedJobs().isEmpty());assertNull(scene.write);
    }
}
