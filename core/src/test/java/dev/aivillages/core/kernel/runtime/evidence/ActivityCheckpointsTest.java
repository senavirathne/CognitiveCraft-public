package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.runtime.activity.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Jobs.*;
import static dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic owner contracts only. Never counted as native gameplay or save evidence. */
final class ActivityCheckpointsTest {
    static final class Scene {
        final Entry entry=NativeJoinedDepositTest.entry();
        final JobLifecycleStore jobs;final DepositEvidenceStore deposits;final ActivityCheckpoints checkpoints;
        final CompletableFuture<DepositJournal.Snapshot> held=new CompletableFuture<>();
        DepositJournal.Snapshot next;boolean liveAcknowledged,savedAvailable,hold;int writes,mutations;
        Scene(){this(false,false);}
        Scene(boolean live,boolean saved) {
            liveAcknowledged=live;savedAvailable=saved;var intent=entry.intent();var key=intent.key();var receipt=entry.result().receipt();
            var args=new HashMap<String,Value>();args.put("actor",new ActorValue(receipt.actor()));args.put("amount",new IntValue(2));
            args.put("source",new AreaValue(receipt.source()));args.put("destination",new ContainerValue(receipt.destination()));
            var observation=new ObservationRef(UUID.randomUUID(),0,receipt.actor().dimension());
            var request=new ValidatedRequest(new CapabilityRequest(OakLogDelivery.ID,args),intent.origin(),observation);
            args=new HashMap<>(args);args.put("amount",new IntValue(1));var bound=new ValidatedRequest(new CapabilityRequest(OakLogDelivery.ID,args),intent.origin(),observation);
            var maxima=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);for(var k:Budgets.Kind.values())maxima.put(k,1000L);
            var limits=new Budgets.Limits(maxima,100_000);var artifact=new ArtifactRef(OakLogDelivery.ID,"a".repeat(64));
            var p=intent.pickupPrefix().pickup();var h=p.intent().harvest();var receipts=List.of(h.result().receipt(),p.result().receipt(),receipt);
            var terminal=new Outcomes.Execution(Outcomes.ExecutionStatus.SUCCEEDED,null,3,new EvidenceRef(intent.run().toString(),"resource-delivery:1",intent.origin().scope().domainId().toString()));
            var attempt=new Attempt(key.attempt(),key.generation(),receipt.actor(),bound,limits,List.of(new ExecutionReference(intent.run(),artifact,List.of(artifact))),entry.result().usage(),List.of(),3,1,terminal,false,0,receipts);
            var step=new ActivityPayload.Step(UUID.randomUUID(),UUID.randomUUID(),"one","b".repeat(64),"c".repeat(64),0,1,artifact,null,ActivityPayload.Quantities.exact(1),limits,2,null);
            var activity=ActivityPayload.initial(OakLogDelivery.ID,2).prepared(step,2,true).assigned(attempt.id()).completed(attempt);
            var job=new Job(key.root(),key.root(),10,request,null,List.of(),List.of(),State.WAITING,null,null,limits,attempt.usage(),attempt.generation(),List.of(attempt),List.of(),null,activity);
            var snapshot=new Snapshot(key.world(),1,List.of(job),List.of());var clock=Clock.fixed(Instant.ofEpochMilli(100),ZoneOffset.UTC);
            jobs=new JobLifecycleStore(snapshot,(a,b)->CompletableFuture.completedFuture(b),JobLifecycleStore.privateJobs((a,c)->true),c->{},PlanningGoals.catalog(),
                    new RequestEnvironment(){public boolean enrolled(ActorRef a,TrustedContext c){return true;}public boolean loaded(Cuboid a,ObservationRef o){return true;}public boolean available(ContainerRef c,ObservationRef o){return true;}},clock,Settings.defaults(),false);
            var port=new DepositEvidenceStore.NativePort(){
                public boolean current(Intent i){mutations++;throw new AssertionError("No native operation in settlement");}
                public Result execute(Intent i){mutations++;throw new AssertionError("No replay");}
                public Witness restored(Participant expected){var o=NativeJoinedDepositTest.saved(entry);var w=expected.kind()==Kind.ACTOR?o.actor():NativeJoinedDepositTest.chest(entry);return savedAvailable?w:new Witness(w.participant(),w.marker(),false);}
                public NativePickup.Observation restoredLineage(Entry e){return NativeJoinedDepositTest.saved(entry);}
            };
            deposits=new DepositEvidenceStore(new DepositJournal.Snapshot(key.world(),UUID.randomUUID(),1,List.of(entry)),(a,b)->{writes++;next=b;return hold?held:CompletableFuture.completedFuture(b);},port,clock,30_000,false);
            checkpoints=new ActivityCheckpoints(jobs,deposits,e->liveAcknowledged&&e.equals(entry));
        }
        Job job(){return jobs.query(entry.intent().key().root(),entry.intent().origin());}
    }
    @Test void liveAcknowledgementClosesOnlyTheProcessExclusionAndNeverWritesSavedProof(){
        var s=new Scene(true,false);assertFalse(s.checkpoints.ready(s.job()));assertTrue(s.deposits.conflicts(Kind.ACTOR,s.entry.intent().actor().dimension(),s.entry.intent().actor().address(),""));
        s.checkpoints.step();assertTrue(s.checkpoints.ready(s.job()));assertNull(s.deposits.snapshot().entries().getFirst().verification());
        assertEquals(0,s.writes);assertEquals(0,s.mutations);assertFalse(s.deposits.conflicts(Kind.ACTOR,s.entry.intent().actor().dimension(),s.entry.intent().actor().address(),""));
        assertTrue(s.deposits.protectedJobs().contains(s.job().id()),"Unsaved replay obligations remain retained");
    }
    @Test void equalLiveCountsWithoutOriginalAcknowledgementCannotSettle(){
        var s=new Scene();s.checkpoints.step();assertFalse(s.checkpoints.ready(s.job()));assertEquals(0,s.writes);assertEquals(0,s.mutations);
        assertNull(s.deposits.snapshot().entries().getFirst().verification());
    }
    @Test void restartDiscardsLiveGrantsAndRequiresNativeSavedVerification(){
        var first=new Scene(true,false);first.checkpoints.step();assertTrue(first.checkpoints.ready(first.job()));
        var restored=new Scene(false,false);restored.checkpoints.step();assertFalse(restored.checkpoints.ready(restored.job()));assertEquals(0,restored.writes);
    }
    @Test void savedSettlementWaitsForTheOriginalJournalAcknowledgement(){
        var s=new Scene(false,true);s.hold=true;s.checkpoints.step();assertEquals(1,s.writes);assertTrue(s.deposits.pending());assertFalse(s.checkpoints.ready(s.job()));
        s.held.complete(s.next);s.deposits.tick();assertTrue(s.checkpoints.ready(s.job()));assertEquals(Reconciliation.SAVED_COMPLETE,s.deposits.snapshot().entries().getFirst().verification().outcome());
        assertEquals(0,s.mutations);
    }
    @Test void completedCheckpointMatchingDoesNotAuthorizeAForeignEntry(){
        var s=new Scene(true,false);var foreign=NativeJoinedDepositTest.entry();assertFalse(s.deposits.settled(foreign));s.checkpoints.step();assertFalse(s.deposits.settled(foreign));
    }
    @Test void settlementDoesNotAlterOriginalJobCreditBudgetAttemptOrHistory(){
        var s=new Scene(true,false);var original=s.job();s.checkpoints.step();assertEquals(original,s.job());assertEquals(1,s.job().fulfilled());
    }
    @Test void duplicateSettlementAuthorityIsRejected(){
        var s=new Scene();assertThrows(IllegalStateException.class,()->s.deposits.attachLiveSettlements(e->true));
    }
    @Test void repeatedLiveSettlementIsIdempotentAndRetainsTheUnsavedObligation(){
        var s=new Scene(true,false);for(int n=0;n<80;n++)s.checkpoints.step();assertTrue(s.checkpoints.ready(s.job()));assertEquals(0,s.writes);assertEquals(1,s.deposits.snapshot().entries().size());
        assertNull(s.deposits.snapshot().entries().getFirst().verification());assertFalse(s.deposits.protectedJobs().isEmpty());
    }
}
