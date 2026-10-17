package dev.aivillages.core.kernel;

import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Jobs.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static dev.aivillages.core.kernel.TableTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class TableJobLifecycleTest {
    @TempDir Path world;
    static final class Scene {
        final JobLifecycleStoreTest.Memory memory=new JobLifecycleStoreTest.Memory(OWNER.scope().worldId());
        final JobLifecycleStore jobs=new JobLifecycleStore(memory.state,memory,
                JobLifecycleStore.privateJobs((a,c)->a.equals(ACTOR) && c.equals(OWNER)),c->{},PlanningGoals.catalog(),
                JobLifecycleStoreTest.environment(),CLOCK,Settings.defaults(),false);
        Job create() {return create(1);}
        Job create(int amount) {
            UUID id=UUID.randomUUID();var b=bound(amount);
            assertTrue(jobs.create(id,id,b.request(),OWNER,b.observation(),CraftingTableProduction.executionLimits(CLOCK.millis()).total(),List.of()).accepted());
            jobs.tick();return jobs.query(id,OWNER);
        }
        Job assign(Job j) {
            var ref=new ArtifactRef(CraftingTableProduction.ID,"c".repeat(64));
            assertTrue(jobs.assign(j.id(),j.guard(),UUID.randomUUID(),ACTOR,ref,List.of(ref),j.allowance(),OWNER).accepted());
            jobs.tick();return jobs.query(j.id(),OWNER);
        }
        List<ResourceDelivery.Receipt> receipts(Job j) { var d=new Driver(Math.toIntExact(((IntValue)j.request().request().arguments().get("amount")).value()));d.produce();
            var b=j.current().bound();var run=j.current().executions().getFirst().runId();
            return d.receipts.stream().map(r->new ResourceDelivery.Receipt(r.receiptId(),b.context(),run,r.operationId(),r.batchId(),ACTOR,
                r.source(),r.destination(),r.item(),r.stage(),r.quantity(),r.sourceBlock(),r.dropIds(),r.transformation())).toList(); }
        Job finish(Job j,List<ResourceDelivery.Receipt> receipts) {
            var a=j.current();long effects=receipts.size();var outcome=new Execution(ExecutionStatus.SUCCEEDED,null,effects,new EvidenceRef(a.id().toString(),"transformation-delivery:1",OWNER.scope().domainId().toString()));
            var change=jobs.recordExecutionResult(j.id(),j.guard(),new Report(a.id(),a.generation(),effects,
                    Map.of(Budgets.Kind.COMMITTED_EFFECTS,effects),List.of(),outcome,receipts),OWNER);
            assertTrue(change.accepted(),change.toString());jobs.tick();return jobs.query(j.id(),OWNER);
        }
    }
    @Test void resourceReportsCompleteAndDuplicateReportDoesNotIncreaseCredit() {
        var s=new Scene();var j=s.assign(s.create());var receipts=s.receipts(j);j=s.finish(j,receipts);
        assertEquals(State.SUCCEEDED,j.state());assertEquals(1,j.fulfilled());assertTrue(j.current().receipts().isEmpty());
        var previous=s.memory.state;var a=j.current();
        assertEquals(Code.DUPLICATE,s.jobs.recordExecutionResult(j.id(),j.guard(),new Report(a.id(),a.generation(),3,
                a.usage(),List.of(),a.terminal(),receipts),OWNER).code());assertEquals(previous,s.memory.state);
    }
    @Test void historicalDepositCannotCompleteAnotherProduction() {
        var s=new Scene();var producer=s.assign(s.create());producer=s.finish(producer,s.receipts(producer));var demand=s.create();
        var a=producer.current();var receipt=a.resourceReceipts().getLast();
        var allocation=new Allocation(UUID.randomUUID(),producer.id(),a.id(),receipt.receiptId(),demand.id(),1);
        var previous=s.memory.state;
        assertEquals(Reason.REQUEST_INVALID,s.jobs.allocate(allocation,demand.guard(),OWNER).reason());
        assertEquals(previous,s.memory.state);assertEquals(State.READY,s.jobs.query(demand.id(),OWNER).state());
        assertEquals(0,s.jobs.query(demand.id(),OWNER).fulfilled());
        var forged=new Snapshot(previous.worldId(),previous.revision(),previous.jobs(),List.of(allocation));
        assertThrows(IllegalArgumentException.class,()->JobLifecycleStore.validateSnapshot(forged,Settings.defaults()));
        demand=s.assign(demand);demand=s.finish(demand,s.receipts(demand));
        assertEquals(State.SUCCEEDED,demand.state());assertEquals(1,demand.fulfilled());
        assertTrue(s.memory.state.allocations().isEmpty());
    }
    @Test void resourceJournalRoundTripRetainsEvidenceAndLegacyWheatEncoding() throws Exception {
        var s=new Scene();var j=s.assign(s.create());j=s.finish(j,s.receipts(j));
        Snapshot persisted;
        try(var journal=JobJournal.open(world,OWNER.scope().worldId())) {
            persisted=new Snapshot(s.memory.state.worldId(),Math.addExact(journal.snapshot().revision(),1),
                    s.memory.state.jobs(),s.memory.state.allocations());
            journal.replace(journal.snapshot(),persisted);
        }
        Path file=world.resolve(JobJournal.WORLD_RELATIVE_PATH).resolve("state.jsonl");
        assertEquals(2L,StrictJson.object(Files.readString(file).lines().findFirst().orElseThrow()).get("schema"));
        assertTrue(Files.readString(file).contains("transformation"));
        try(var journal=JobJournal.open(world,OWNER.scope().worldId())) { assertEquals(persisted,journal.snapshot());assertFalse(journal.readOnly()); }
        var old=new JobLifecycleStoreTest.Scene();var wheat=old.assign(old.create(1));wheat=old.report(wheat,1,ExecutionStatus.SUCCEEDED);
        String wheatRow=JobCodec.job(wheat);assertEquals(1L,StrictJson.object(wheatRow).get("schema"));
        assertFalse(wheatRow.contains("resourceReceipts"));assertEquals(wheat,JobCodec.job(StrictJson.object(wheatRow)));
    }
    @Test void crashRecoveryInterruptsWithoutReplayAndKeepsResourceAccounting() {
        var s=new Scene();var j=s.assign(s.create());var r=s.receipts(j).subList(0,1);var a=j.current();
        assertTrue(s.jobs.recordExecutionResult(j.id(),j.guard(),new Report(a.id(),a.generation(),1,
                Map.of(Budgets.Kind.COMMITTED_EFFECTS,1L),List.of(),null,r),OWNER).accepted());s.jobs.tick();
        var reopened=new JobLifecycleStore(s.memory.state,s.memory,JobLifecycleStore.privateJobs((x,c)->c.equals(OWNER)),
                c->{},PlanningGoals.catalog(),JobLifecycleStoreTest.environment(),CLOCK,Settings.defaults(),false);
        reopened.tick();var restored=reopened.query(j.id(),OWNER);
        assertEquals(State.INTERRUPTED,restored.state());assertTrue(restored.current().uncertain());
        assertEquals(r,restored.current().resourceReceipts());assertEquals(1,restored.effects());assertEquals(0,restored.fulfilled());
        assertEquals(1,restored.usage().get(Budgets.Kind.COMMITTED_EFFECTS));
    }
    @Test void futureCurrentJournalIsPreservedReadOnly() throws Exception {
        var s=new Scene();s.create();
        try(var journal=JobJournal.open(world,OWNER.scope().worldId())) { journal.replace(journal.snapshot(),s.memory.state); }
        Path file=world.resolve(JobJournal.WORLD_RELATIVE_PATH).resolve("state.jsonl");
        String future=Files.readString(file).replaceFirst("\\\"schema\\\":2","\"schema\":99");Files.writeString(file,future);
        try(var journal=JobJournal.open(world,OWNER.scope().worldId())) { assertTrue(journal.readOnly()); }
        assertEquals(future,Files.readString(file));
        assertTrue(SaveCompatibility.registered().readable(new SaveCompatibility.Format("jobs",2)));
        assertFalse(SaveCompatibility.registered().readable(new SaveCompatibility.Format("jobs",99)));
    }
    @Test void largestAmountFitsUnchangedStrictRecordAndRealJournalRoundTrip() throws Exception {
        var s=new Scene();var j=s.assign(s.create(4));j=s.finish(j,s.receipts(j));
        assertEquals(State.SUCCEEDED,j.state());assertEquals(4,j.fulfilled());assertEquals(12,j.current().resourceReceipts().size());
        String encoded=JobCodec.job(j);assertTrue(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<StrictJson.MAX_BYTES);
        assertEquals(j,JobCodec.job(StrictJson.object(encoded)));
        Snapshot persisted;
        try(var journal=JobJournal.open(world,OWNER.scope().worldId())) {
            persisted=new Snapshot(s.memory.state.worldId(),journal.snapshot().revision()+1,s.memory.state.jobs(),List.of());
            journal.replace(journal.snapshot(),persisted);
        }
        try(var journal=JobJournal.open(world,OWNER.scope().worldId())) {assertFalse(journal.readOnly());assertEquals(persisted,journal.snapshot());}
    }
    @Test void executedFullSchemaTwoTransformationRecordRemainsReadable() throws Exception {
        String old;
        try(var input=getClass().getResourceAsStream("/rb002/full-schema2-85c5-job.json")) {
            assertNotNull(input);old=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
        var job=JobCodec.job(StrictJson.object(old));assertEquals(State.SUCCEEDED,job.state());assertEquals(1,job.fulfilled());
        assertTrue(OakPlankProduction.completed(job.current().bound(),job.current().executions().getFirst().runId(),job.current().resourceReceipts()));
        assertEquals(job,JobCodec.job(StrictJson.object(JobCodec.job(job))));
    }
}
