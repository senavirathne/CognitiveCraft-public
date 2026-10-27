package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.runtime.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static dev.aivillages.core.kernel.Jobs.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic owner/storage contracts only; these are not native Minecraft recovery results. */
final class JobArchiveTest {
    @TempDir Path world;
    final JobLifecycleStoreTest.Scene s=new JobLifecycleStoreTest.Scene();
    final LeaseArchiveTest.Background background=new LeaseArchiveTest.Background();
    long references=1,leaseRevision=1;
    Set<UUID> pins=Set.of();
    Job completed(){return s.report(s.assign(s.create(1)),1,ExecutionStatus.SUCCEEDED);}
    void completed(int count){for(int n=0;n<count;n++)completed();}
    ClosureBundle attach(ArchiveWriter writer)throws Exception {
        JobHistory history=new JobHistory(()->s.memory.state,writer);history.refresh(s.memory.state);
        return new ClosureBundle(history,new JobArchive(s.store,history,writer,new ArchiveService(writer,background),PersistencePolicy.defaults()));
    }
    record ClosureBundle(JobHistory history,JobArchive archive) { }
    JobArchive.Closure closure(){return new JobArchive.Closure(s.store.snapshot().revision(),leaseRevision,references,pins,true);}
    void compact(JobArchive archive)throws Exception {
        archive.step(closure(),true);background.run();archive.step(closure(),true);
        s.store.tick();archive.step(closure(),true);assertTrue(s.store.ready(),archive.status().toString());
    }
    @Test void realOldCapacityCheckPrecedesArchivalAndHotLimitsRemainUnchanged()throws Exception {
        completed(64);Snapshot full=s.store.snapshot();
        var refused=s.store.create(UUID.randomUUID(),UUID.randomUUID(),JobLifecycleStoreTest.request(s.actor,1),s.owner,
                JobLifecycleStoreTest.observation(),JobLifecycleStoreTest.limits(11_000),List.of());
        assertEquals(Reason.STORAGE_LIMIT_REACHED,refused.reason());assertEquals(full,s.store.snapshot());
        try(var writer=ArchiveWriter.open(world,s.world,PersistencePolicy.defaults())) {
            var owners=attach(writer);compact(owners.archive());
            for(int n=0;n<40;n++){completed();if(s.store.snapshot().jobs().size()>=48)compact(owners.archive());}
            assertTrue(owners.archive().status().evicted()>=56);assertTrue(s.store.snapshot().jobs().size()<64);
            assertEquals(Settings.defaults(),s.store.settings());assertEquals(32,s.store.settings().active());
            assertEquals(104,owners.history().read().jobs().size());
        }
    }
    @Test void archiveAcknowledgementPrecedesHotPublicationAndExactPrivateReadSurvives()throws Exception {
        completed(48);Job old=s.store.snapshot().jobs().getFirst();
        try(var writer=ArchiveWriter.open(world,s.world,PersistencePolicy.defaults())) {
            var owners=attach(writer);var events=new ArrayList<JobArchive.Point>();owners.archive().observe(events::add);
            owners.archive().step(closure(),true);background.run();
            assertEquals(48,s.store.snapshot().jobs().size());assertEquals(old,owners.history().archived(old.id()));
            owners.archive().step(closure(),true);assertFalse(s.store.ready());assertEquals(48,s.store.snapshot().jobs().size());
            s.store.tick();owners.archive().step(closure(),true);assertEquals(32,s.store.snapshot().jobs().size());
            assertEquals(old,s.store.query(old.id(),s.owner));assertEquals(old,s.store.bySubmission(old.submissionId(),s.owner).orElseThrow());
            assertThrows(SecurityException.class,()->s.store.query(old.id(),s.foreign));assertTrue(s.store.bySubmission(old.submissionId(),s.foreign).isEmpty());
            assertEquals(List.of(JobArchive.Point.ARCHIVE_ACKNOWLEDGED,JobArchive.Point.BEFORE_HOT_PUBLICATION,JobArchive.Point.AFTER_HOT_ACKNOWLEDGEMENT),events);
            assertTrue(s.store.settledSubmission(old.submissionId()));
        }
    }
    @Test void unresolvedAttemptAndWholeLiveDependencyClosureRemainPinned()throws Exception {
        Job uncertain=s.report(s.assign(s.create(2)),1,null);
        Job producer=completed();Job child=s.create(1,List.of(producer.id()));completed(45);
        try(var writer=ArchiveWriter.open(world,s.world,PersistencePolicy.defaults())) {
            var owners=attach(writer);compact(owners.archive());
            assertTrue(s.store.snapshot().jobs().containsAll(List.of(uncertain,producer,child)));
            assertNull(owners.history().archived(uncertain.id()));assertNull(owners.history().archived(producer.id()));
            assertTrue(owners.archive().status().evicted()>0);
        }
    }
    @Test void pendingPublicationAndExplicitReaderPinsCannotBeCollected()throws Exception {
        completed(48);Job pinned=s.store.snapshot().jobs().getFirst();pins=Set.of(pinned.id());
        try(var writer=ArchiveWriter.open(world,s.world,PersistencePolicy.defaults())) {
            var owners=attach(writer);compact(owners.archive());assertNull(owners.history().archived(pinned.id()));
            completed(16);s.memory.hold=true;Job current=s.store.snapshot().jobs().stream().filter(j->j.state()==State.SUCCEEDED).findFirst().orElseThrow();
            // A fresh admission is deliberately left awaiting the original storage acknowledgement.
            s.store.create(UUID.randomUUID(),UUID.randomUUID(),JobLifecycleStoreTest.request(s.actor,1),s.owner,
                    JobLifecycleStoreTest.observation(),JobLifecycleStoreTest.limits(11_000),List.of());
            Snapshot before=s.store.snapshot();owners.archive().step(closure(),true);assertTrue(background.work.isEmpty());assertEquals(before,s.store.snapshot());
            assertEquals(State.SUCCEEDED,current.state());s.memory.release();s.settle();
        }
    }
    @Test void changedClosureAfterAcknowledgementPreservesHotRowsAndExactExtraCopy()throws Exception {
        completed(48);Snapshot before=s.store.snapshot();
        try(var writer=ArchiveWriter.open(world,s.world,PersistencePolicy.defaults())) {
            var owners=attach(writer);owners.archive().step(closure(),true);background.run();references++;
            owners.archive().step(closure(),true);assertEquals(before,s.store.snapshot());assertEquals(0,owners.archive().status().evicted());
            compact(owners.archive());assertEquals(16,owners.archive().status().evicted());
        }
    }
    @Test void archivedDependencyIsImportedWithItsFullImmutableClosureBeforeNewUse()throws Exception {
        completed(48);Job old=s.store.snapshot().jobs().getFirst();
        try(var writer=ArchiveWriter.open(world,s.world,PersistencePolicy.defaults())) {
            var owners=attach(writer);compact(owners.archive());
            Job next=s.create(1,List.of(old.id()));assertEquals(State.READY,next.state());assertTrue(s.store.snapshot().jobs().contains(old));
            assertEquals(old,s.store.query(old.id(),s.owner));
            assertEquals(Reason.STALE_OBSERVATION,s.store.charge(old.id(),old.guard(),Map.of(Budgets.Kind.CALLS,1L),s.owner).reason());
            s.report(s.assign(next),1,ExecutionStatus.SUCCEEDED);completed(14);compact(owners.archive());
            assertEquals(old,s.store.query(old.id(),s.owner));assertEquals(State.SUCCEEDED,s.store.query(next.id(),s.owner).state());
            assertFalse(owners.archive().readOnly());
        }
    }
    @Test void originalReceiptsAreIdempotentAndConflictingReplayCannotRecreateOrSpendHistory()throws Exception {
        completed(48);Job old=s.store.snapshot().jobs().getFirst();var a=old.current();
        try(var writer=ArchiveWriter.open(world,s.world,PersistencePolicy.defaults())) {
            var owners=attach(writer);compact(owners.archive());Snapshot before=s.store.snapshot();
            assertEquals(Code.DUPLICATE,s.store.recordExecutionResult(old.id(),old.guard(),new Report(a.id(),a.generation(),a.effects(),a.usage(),a.receipts(),a.terminal()),s.owner).code());
            assertEquals(Reason.STALE_OBSERVATION,s.store.recordExecutionResult(old.id(),old.guard(),new Report(a.id(),a.generation(),a.effects()+1,a.usage(),a.receipts(),a.terminal()),s.owner).reason());
            assertEquals(Reason.REQUEST_INVALID,s.store.create(UUID.randomUUID(),old.submissionId(),old.request().request(),s.owner,
                    old.request().observation(),old.allowance(),List.of()).reason());assertEquals(before,s.store.snapshot());
            Job fresh=s.assign(s.create(1));var b=fresh.current();var receipts=new ArrayList<>(JobLifecycleStoreTest.receipts(b,1));
            var r=receipts.getFirst();receipts.set(0,new CropDelivery.CropReceipt(a.receipts().getFirst().receiptId(),r.runId(),r.batchId(),r.actor(),r.source(),r.destination(),r.stage(),r.wheat()));
            assertEquals(Reason.REQUEST_INVALID,s.store.recordExecutionResult(fresh.id(),fresh.guard(),new Report(b.id(),b.generation(),1,Map.of(),receipts,null),s.owner).reason());
        }
    }
    @Test void archivedOutcomeAccomplishmentAndKnowledgeSourcesKeepTheOriginalProof()throws Exception {
        completed(48);Job old=s.store.snapshot().jobs().getFirst();
        var citizens=new CitizenRegistry.Snapshot(s.world,1,List.of(new CitizenRegistry.Citizen(s.actor,s.owner,null,CitizenRegistry.Availability.LOADED)),null);
        try(var writer=ArchiveWriter.open(world,s.world,PersistencePolicy.defaults())) {
            var owners=attach(writer);var source=JobOutcomeSource.withHistory(owners.history()::read,()->citizens);
            try(var ledger=AccomplishmentLedger.open(world,s.world,source,new RetentionRoots())) {
                assertEquals(Accomplishments.Code.APPLIED,ledger.recordOutcomes(List.of(Accomplishments.OutcomeRef.of(old)),()->false).code());
                var access=KnowledgeAccessSource.withHistory(()->s.memory.state,()->citizens,ledger::snapshot,owners.history()::read);
                var prior=ledger.query(s.actor,s.owner,16);compact(owners.archive());
                assertEquals(old,source.readDelivery(Accomplishments.OutcomeRef.of(old),new JobOutcomeSource.Work(8192)).job());
                assertEquals(old,access.verified(old.id(),new JobOutcomeSource.Work(8192)));
                assertEquals(Accomplishments.Code.DUPLICATE,ledger.recordOutcomes(List.of(Accomplishments.OutcomeRef.of(old)),()->false).code());
                assertEquals(prior,ledger.query(s.actor,s.owner,16));assertTrue(ledger.query(s.actor,s.foreign,16).evidence().isEmpty());
            }
        }
    }
    @Test void repeatedColdReadersRetainOriginalDeadlineBudgetIdentityAndDenyForeignLookup()throws Exception {
        completed(48);Job old=s.store.snapshot().jobs().getFirst();
        try(var writer=ArchiveWriter.open(world,s.world,PersistencePolicy.defaults())) {compact(attach(writer).archive());}
        for(int n=0;n<3;n++)try(var writer=ArchiveWriter.open(world,s.world,PersistencePolicy.defaults())) {
            var history=new JobHistory(()->s.memory.state,writer);history.refresh(s.memory.state);
            var cold=new JobLifecycleStore(s.memory.state,s.memory,JobLifecycleStore.privateJobs((a,c)->c.equals(s.owner)),c->{},
                    id->Optional.of(CropDelivery.SPEC),JobLifecycleStoreTest.environment(),s.clock,Settings.defaults(),false);
            new JobArchive(cold,history,writer,new ArchiveService(writer,background),PersistencePolicy.defaults());
            assertEquals(old,cold.query(old.id(),s.owner));assertEquals(old.allowance(),cold.query(old.id(),s.owner).allowance());
            assertEquals(old.usage(),cold.query(old.id(),s.owner).usage());assertThrows(SecurityException.class,()->cold.query(old.id(),s.foreign));
        }
    }
    @Test void missingRequiredSegmentFencesHistoryWithoutProvingAbsenceOrRemovingHotRows()throws Exception {
        completed(48);Job old=s.store.snapshot().jobs().getFirst();Path segment;
        try(var writer=ArchiveWriter.open(world,s.world,PersistencePolicy.defaults())) {
            compact(attach(writer).archive());segment=world.resolve(ArchiveHistory.RELATIVE_PATH).resolve(writer.snapshot().segments().getFirst().digest()+".seg");
        }
        Files.move(segment,world.resolve("preserved-missing.seg"));Snapshot before=s.memory.state;
        try(var writer=ArchiveWriter.open(world,s.world,PersistencePolicy.defaults())) {
            var history=new JobHistory(()->s.memory.state,writer);assertThrows(IOException.class,()->history.refresh(s.memory.state));
            assertTrue(history.failed());assertThrows(IllegalStateException.class,()->history.archived(old.id()));assertEquals(before,s.memory.state);
        }
    }
}
