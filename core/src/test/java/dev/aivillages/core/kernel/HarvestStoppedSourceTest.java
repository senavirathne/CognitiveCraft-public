package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.runtime.evidence.*;
import dev.aivillages.core.kernel.runtime.evidence.NativeHarvest.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Jobs.*;
import static dev.aivillages.core.kernel.OakLogTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic port contracts, including actual Jobs publication; no native save qualification. */
final class HarvestStoppedSourceTest {
    static final class Scene implements HarvestStoppedSource.Evidence {
        final JobLifecycleStoreTest.Memory memory=new JobLifecycleStoreTest.Memory(OWNER.scope().worldId());
        JobLifecycleStore jobs;Job job;Entry entry;HarvestEvidenceStore evidence;HarvestStoppedSource source;
        boolean restored=true,available=true,hold;CompletableFuture<HarvestJournal.Snapshot> pending;HarvestJournal.Snapshot next;
        Scene(boolean cancel) {
            jobs=reopen();UUID id=UUID.randomUUID();var bound=bound(2);
            assertTrue(jobs.create(id,id,bound.request(),OWNER,bound.observation(),OakLogDelivery.executionLimits(CLOCK.millis()).total(),List.of()).accepted());jobs.tick();job=jobs.query(id,OWNER);
            var ref=new ArtifactRef(OakLogDelivery.ID,"a".repeat(64));
            assertTrue(jobs.assign(id,job.guard(),UUID.randomUUID(),ACTOR,ref,List.of(ref),job.allowance(),OWNER).accepted());jobs.tick();job=jobs.query(id,OWNER);
            var actor=new NativeDeposit.Participant(NativeDeposit.Kind.ACTOR,ACTOR.dimension(),ACTOR.entityId().toString(),UUID.randomUUID(),0,Collections.nCopies(8,new NativeDeposit.Slot("minecraft:air",0)));
            var conditions=new NativeDeposit.Conditions(ACTOR.citizenId(),job.revision(),job.allowance().deadlineEpochMillis(),job.allowance().deadlineEpochMillis(),16,0,2500,1000,
                    List.of(new NativeDeposit.Claim(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1)),Map.of());
            var intent=new Intent(new NativeDeposit.Key(OWNER.scope().worldId(),id,id,job.current().id(),job.generation(),1),OWNER,job.current().executions().getFirst().runId(),UUID.randomUUID(),ACTOR,SOURCE,BOX,ResourceDelivery.OAK_LOG.id(),
                    new Source(ACTOR.dimension(),4,0,0,UUID.randomUUID(),0,"minecraft:oak_log"),conditions,actor);
            var after=new Source(ACTOR.dimension(),4,0,0,intent.source().incarnation(),1,"minecraft:air");
            var drop=new Drop(ACTOR.dimension(),UUID.randomUUID(),UUID.randomUUID(),0,intent.item(),1);
            var receipt=new ResourceDelivery.Receipt(UUID.randomUUID(),OWNER,intent.run(),intent.operation(),UUID.randomUUID(),ACTOR,SOURCE,BOX,ResourceDelivery.OAK_LOG,ResourceDelivery.Stage.HARVEST,1,
                    new ResourceDelivery.BlockPosition(ACTOR.dimension(),4,0,0),List.of(drop.entity()));
            entry=new Entry(intent,NativeHarvest.observed(intent,after,List.of(drop),receipt,Map.of(Budgets.Kind.ATTEMPTED_EFFECTS,1L,Budgets.Kind.COMMITTED_EFFECTS,1L),"",actor));
            jobs=reopen();job=jobs.query(id,OWNER);if(cancel){assertTrue(jobs.cancel(id,job.guard(),OWNER).accepted());jobs.tick();job=jobs.query(id,OWNER);}
            evidence=new HarvestEvidenceStore(new HarvestJournal.Snapshot(OWNER.scope().worldId(),UUID.randomUUID(),1,List.of(entry)),(before,value)->{
                if(hold){next=value;pending=new CompletableFuture<>();return pending;}return CompletableFuture.completedFuture(value);
            },new HarvestEvidenceStore.NativePort() {
                public boolean current(Intent intent){throw new AssertionError("Recovery may not request a mutation");}
                public Result execute(Intent intent){throw new AssertionError("Recovery may not execute");}
                public NativeDeposit.Witness restoredActor(NativeDeposit.Participant expected){return !available?null:new NativeDeposit.Witness(entry.result().actor(),marker(),restored);}
                public SourceWitness restoredSource(Source expected){return !available?null:new SourceWitness(entry.result().source(),marker(),restored);}
                public List<DropWitness> restoredDrops(List<Drop> expected){return !available?null:entry.result().drops().stream().map(d->new DropWitness(d,marker(),restored)).toList();}
            },CLOCK,30_000,false);source=new HarvestStoppedSource(jobs,this);
        }
        NativeDeposit.Marker marker(){return new NativeDeposit.Marker(entry.token(),entry.intent().digest(),NativeDeposit.MarkerPhase.RESULT,entry.result().digest());}
        JobLifecycleStore reopen(){var value=new JobLifecycleStore(memory.state,memory,JobLifecycleStore.privateJobs((a,c)->c.equals(OWNER)),c->{},PlanningGoals.catalog(),JobLifecycleStoreTest.environment(),CLOCK,Settings.defaults(),false);value.tick();return value;}
        public HarvestEvidenceStore store(){return evidence;}
        public HarvestEvidenceStore.Code verify(HarvestEvidenceStore.Reconciled proof){return evidence.verify(proof);}
        StoppedObservation observe(){return source.observe(StoppedContext.of(jobs.query(job.id(),OWNER)));}
    }
    @Test void savedFirstHarvestAccountsOnceForTheOriginalCancelledOwnerWithoutDeliveryCredit() {
        var s=new Scene(true);var old=s.job;var authority=s.jobs.attachRecovery(s.source);
        assertEquals(StoppedKind.AMBIGUOUS,s.observe().kind());s.evidence.tick();var proof=authority.capture(StoppedContext.of(old));
        assertEquals(StoppedKind.PARTIAL,proof.observation().kind());assertEquals(1,proof.observation().report().resourceReceipts().size());
        assertTrue(s.jobs.reconcileStopped(old.id(),old.guard(),proof,OWNER).accepted());s.jobs.tick();var settled=s.jobs.query(old.id(),OWNER);
        assertEquals(State.CANCELLED,settled.state());assertFalse(settled.current().open());assertFalse(settled.current().uncertain());assertEquals(0,settled.fulfilled());assertEquals(1,settled.effects());
        assertEquals(old.current().id(),settled.current().id());assertEquals(old.generation(),settled.generation());assertEquals(old.allowance(),settled.allowance());
        assertEquals(Code.DUPLICATE,s.jobs.reconcileStopped(old.id(),old.guard(),proof,OWNER).code());assertEquals(settled,s.reopen().query(old.id(),OWNER));
    }
    @Test void liveOrUnavailableParticipantsCannotSettleOrPublishVerification() {
        var s=new Scene(true);s.restored=false;assertEquals(StoppedKind.AMBIGUOUS,s.observe().kind());assertFalse(s.evidence.pending());
        s.restored=true;s.available=false;assertEquals(StoppedKind.WAIT_WORLD,s.observe().kind());assertFalse(s.evidence.pending());assertTrue(s.jobs.query(s.job.id(),OWNER).current().open());
    }
    @Test void firstHarvestNeverAutomaticallyResumesAnUncancelledTwoUnitDemand() {
        var s=new Scene(false);assertEquals(StoppedKind.AMBIGUOUS,s.observe().kind());assertFalse(s.evidence.pending());assertEquals(0,s.jobs.query(s.job.id(),OWNER).fulfilled());
    }
    @Test void verificationMustBeDurablyAcknowledgedBeforeAnOwnerReportCanEscape() {
        var s=new Scene(true);s.hold=true;assertEquals(StoppedKind.AMBIGUOUS,s.observe().kind());assertEquals(StoppedKind.AMBIGUOUS,s.observe().kind());
        assertTrue(s.evidence.pending());s.pending.complete(s.next);s.evidence.tick();assertEquals(StoppedKind.PARTIAL,s.observe().kind());
    }
    @Test void cancellationIntentSurvivesAnotherRestartBeforeOriginalAccounting() {
        var s=new Scene(true);s.jobs=s.reopen();s.job=s.jobs.query(s.job.id(),OWNER);assertEquals(State.INTERRUPTED,s.job.state());assertEquals(State.CANCELLED,s.job.cancellationOutcome());
        s.source=new HarvestStoppedSource(s.jobs,s);assertEquals(StoppedKind.AMBIGUOUS,s.observe().kind());s.evidence.tick();assertEquals(StoppedKind.PARTIAL,s.observe().kind());
    }
}
