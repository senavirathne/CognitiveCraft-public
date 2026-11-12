package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.runtime.activity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Jobs.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static dev.aivillages.core.kernel.JobLifecycleStoreTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic contract fixtures only. Native provenance is independently qualified in Docker. */
final class ActivityRuntimeTest {
    static final class Fixture {
        final Scene s=new Scene();final UUID root=UUID.randomUUID();
        long stock;int observed;
        final ActivityRuntime runtime=new ActivityRuntime(s.store,j->{observed++;return new ActivityRuntime.Availability(true,1,stock,0,"");});
        Fixture(){commit(s,runtime.create(root,request(s.actor,2),s.owner,observation(),limits(11_000),2));}
        Job job(){return s.store.query(root,s.owner);}
        ActivityRuntime.Proposal proposal(String token){
            return new ActivityRuntime.Proposal(1,UUID.randomUUID(),root,job().guard(),token,request(s.actor,1),REF,null,
                    ActivityPayload.Quantities.exact(1),s.store.remainingAllowance(root,s.owner),2);
        }
        void prepare(String token){commit(s,runtime.prepare(proposal(token),s.owner));}
        Job assign(){return s.assign(job(),s.actor,job().activity().current().allowance());}
        Job finish(){return s.report(assign(),1,ExecutionStatus.SUCCEEDED);}
    }
    @Test void twoPreparedSegmentsKeepOneRootAndOnlyActualReceiptsCompleteIt(){
        var f=new Fixture();var original=f.job().request();f.prepare("one");Job first=f.finish();
        assertEquals(State.WAITING,first.state());assertEquals(1,first.fulfilled());assertEquals(1,first.activity().checkpoints().size());
        assertEquals(first.current().id(),first.activity().checkpoints().getFirst().attempt());
        f.prepare("two");Job last=f.finish();assertEquals(State.SUCCEEDED,last.state());assertEquals(2,last.fulfilled());
        assertEquals(original,last.request());assertEquals(f.root,last.id());assertEquals(2,last.generation());assertEquals(2,last.activity().checkpoints().size());
        assertEquals(2,last.attempts().stream().map(Attempt::id).distinct().count());
        JobLifecycleStore.validateSnapshot(f.s.store.snapshot(),Settings.defaults());
    }
    @Test void preparationMustBeAcknowledgedBeforeAssignment(){
        var f=new Fixture();f.s.memory.hold=true;var before=f.job();var p=f.proposal("held");
        assertEquals(Code.PENDING,f.runtime.prepare(p,f.s.owner).code());assertNull(f.job().activity().current());
        assertFalse(f.s.store.assign(f.root,before.guard(),UUID.randomUUID(),f.s.actor,REF,List.of(REF),before.allowance(),f.s.owner).accepted());
        f.s.memory.release();f.s.settle();assertNotNull(f.job().activity().current());assertDoesNotThrow(f::assign);
    }
    @Test void stableTokenReplayMapsToOneAuthoritativeStepEvenWithRetriedEnvelopeId(){
        var f=new Fixture();var p=f.proposal("same");commit(f.s,f.runtime.prepare(p,f.s.owner));var step=f.job().activity().current().id();
        var retry=new ActivityRuntime.Proposal(1,UUID.randomUUID(),p.root(),p.expected(),p.token(),p.request(),p.artifact(),p.choice(),p.quantities(),p.allowance(),p.plannedSteps());
        assertEquals(Code.DUPLICATE,f.runtime.prepare(retry,f.s.owner).code());assertEquals(step,f.job().activity().current().id());assertEquals(1,f.job().activity().steps().size());
    }
    @Test void conflictingStableTokenCannotReplaceItsBindings(){
        var f=new Fixture();var p=f.proposal("same");commit(f.s,f.runtime.prepare(p,f.s.owner));
        var bad=new ActivityRuntime.Proposal(1,p.id(),p.root(),p.expected(),p.token(),request(f.s.actor,2),p.artifact(),null,ActivityPayload.Quantities.exact(2),p.allowance(),2);
        assertFalse(f.runtime.prepare(bad,f.s.owner).accepted());assertEquals(1,f.job().activity().current().quantity());
    }
    @Test void staleRevisionAndGenerationCannotPrepare(){
        var f=new Fixture();var p=f.proposal("stale");for(var guard:List.of(new Guard(0,0),new Guard(p.expected().revision(),1))){
            var bad=new ActivityRuntime.Proposal(1,p.id(),p.root(),guard,p.token(),p.request(),p.artifact(),null,p.quantities(),p.allowance(),2);
            assertFalse(f.runtime.prepare(bad,f.s.owner).accepted());
        }assertTrue(f.job().activity().steps().isEmpty());
    }
    @Test void secondWriterCannotPrepareWhileFirstPreparedOrExecuting(){
        var f=new Fixture();f.prepare("first");assertFalse(f.runtime.prepare(f.proposal("second"),f.s.owner).accepted());f.assign();
        assertFalse(f.runtime.prepare(f.proposal("third"),f.s.owner).accepted());assertEquals(1,f.job().activity().steps().size());
    }
    @Test void privateSnapshotNeverCallsNativeObservationForDeniedPrincipal(){
        var f=new Fixture();assertThrows(SecurityException.class,()->f.runtime.snapshot(f.root,f.s.foreign));assertEquals(0,f.observed);
        assertFalse(f.runtime.prepare(f.proposal("private"),f.s.foreign).accepted());assertEquals(0,f.observed);
    }
    @Test void originalActorPolicyRejectsReplacementAndChangedSource(){
        var f=new Fixture();f.prepare("first");var j=f.job();
        assertFalse(f.s.store.assign(f.root,j.guard(),UUID.randomUUID(),actor(),REF,List.of(REF),j.allowance(),f.s.owner).accepted());
        var g=new Fixture();var p=g.proposal("changed");var args=new HashMap<>(p.request().arguments());args.put("destination",new ContainerValue(new ContainerRef("minecraft:overworld",99,0,99)));
        assertFalse(g.runtime.prepare(new ActivityRuntime.Proposal(1,p.id(),p.root(),p.expected(),p.token(),new CapabilityRequest(CropDelivery.ID,args),REF,null,p.quantities(),p.allowance(),2),g.s.owner).accepted());
    }
    @Test void deadlineCannotBeExtendedOrRefilledBetweenSegments(){
        var f=new Fixture();f.prepare("first");f.finish();var p=f.proposal("second");
        assertTrue(p.allowance().maximum(Budgets.Kind.CALLS)<20);assertEquals(11_000,p.allowance().deadlineEpochMillis());
        var bad=new ActivityRuntime.Proposal(1,p.id(),p.root(),p.expected(),p.token(),p.request(),REF,null,p.quantities(),limits(12_000),2);
        assertFalse(f.runtime.prepare(bad,f.s.owner).accepted());f.s.clock.now=11_000;
        assertFalse(f.runtime.prepare(p,f.s.owner).accepted());assertEquals(ActivityRuntime.Next.EXPIRED,f.runtime.snapshot(f.root,f.s.owner).next());
        assertEquals(1,f.job().activity().checkpoints().size());
    }
    @Test void successfulSegmentCannotOvercreditOriginalGoal(){
        var f=new Fixture();f.prepare("first");f.finish();var p=f.proposal("overshoot");
        assertFalse(f.runtime.prepare(new ActivityRuntime.Proposal(1,p.id(),p.root(),p.expected(),p.token(),request(f.s.actor,2),REF,null,ActivityPayload.Quantities.exact(2),p.allowance(),2),f.s.owner).accepted());
        assertEquals(1,f.job().fulfilled());
    }
    @Test void historicalCreditDoesNotRecreateRemovedCurrentStock(){
        var f=new Fixture();f.prepare("one");f.finish();f.stock=1;var before=f.runtime.snapshot(f.root,f.s.owner);f.stock=0;var after=f.runtime.snapshot(f.root,f.s.owner);
        assertEquals(1,before.availability().destinationQuantity());assertEquals(0,after.availability().destinationQuantity());
        assertEquals(1,after.confirmedCredit());assertEquals(before.checkpoints(),after.checkpoints());assertEquals(1,after.remainingGoal());assertFalse(after.plannerEnabled());
    }
    @Test void unchangedReceiptReportIsIdempotentButChangedBodyFails(){
        var f=new Fixture();f.prepare("one");var j=f.finish();var a=j.current();var report=new Report(a.id(),a.generation(),a.effects(),a.usage(),a.receipts(),a.terminal());
        assertEquals(Code.DUPLICATE,f.s.store.recordExecutionResult(f.root,j.guard(),report,f.s.owner).code());
        var r=a.receipts().getFirst();var changed=new CropDelivery.CropReceipt(r.receiptId(),r.runId(),r.batchId(),r.actor(),r.source(),r.destination(),r.stage(),2);
        assertFalse(f.s.store.recordExecutionResult(f.root,j.guard(),new Report(a.id(),a.generation(),a.effects(),a.usage(),List.of(changed),a.terminal()),f.s.owner).accepted());
        assertEquals(1,f.job().activity().checkpoints().size());
    }
    @Test void forgedCheckpointAndImpossibleSuccessAreRejectedOnLoad(){
        var f=new Fixture();var j=f.job();var p=j.activity();
        assertThrows(IllegalArgumentException.class,()->new ActivityPayload(p.mode(),p.policy(),2,null,List.of(),List.of(new ActivityPayload.Checkpoint(UUID.randomUUID(),UUID.randomUUID(),1,1,List.of(UUID.randomUUID()),ActivityPayload.Evidence.LIVE_RECEIPTS)),p.blocker()));
        var impossible=new Job(j.id(),j.submissionId(),j.revision(),j.request(),null,List.of(),List.of(),State.SUCCEEDED,null,null,j.allowance(),j.usage(),0,List.of(),j.events(),null,p);
        assertThrows(IllegalArgumentException.class,()->JobLifecycleStore.validateSnapshot(new Snapshot(f.s.world,1,List.of(impossible),List.of()),Settings.defaults()));
    }
    @Test void unknownChoicePersistsAsDataAndCannotExecute(){
        var f=new Fixture();var p=f.proposal("choice");String b=Base64.getEncoder().encodeToString("fixture:unknown-choice".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var choice=new ActivityPayload.Choice(b,ActivityPayload.digest("fixture:unknown-choice"));
        commit(f.s,f.runtime.prepare(new ActivityRuntime.Proposal(1,p.id(),p.root(),p.expected(),p.token(),p.request(),REF,choice,p.quantities(),p.allowance(),2),f.s.owner));
        assertEquals(choice,f.job().activity().current().choice());assertEquals(ActivityRuntime.Next.INTEGRATION_PENDING,f.runtime.snapshot(f.root,f.s.owner).next());
        assertFalse(f.s.store.materialReady(f.job()));assertFalse(f.s.store.assign(f.root,f.job().guard(),UUID.randomUUID(),f.s.actor,REF,List.of(REF),p.allowance(),f.s.owner).accepted());
        assertEquals(ActivityRuntime.Validation.UNAVAILABLE,ActivityRuntime.SemanticAdapter.unavailable().validate(choice,p.quantities(),p.request()));
    }
    @Test void q3Yield4PreservesSurplusAndCheckedArithmetic(){
        var q=new ActivityPayload.Quantities(3,4,1,4,3,1,0);assertEquals(1,q.surplus());assertFalse(q.exact());
        assertThrows(IllegalArgumentException.class,()->new ActivityPayload.Quantities(3,4,1,4,3,0,0));
        assertThrows(IllegalArgumentException.class,()->new ActivityPayload.Quantities(3,4,1,4,4,0,0));
        assertThrows(ArithmeticException.class,()->new ActivityPayload.Quantities(Long.MAX_VALUE,2,Long.MAX_VALUE/2+1,Long.MAX_VALUE,3,0,0));
    }
    @Test void malformedChoiceBytesAndOversizedPayloadAreRejected(){
        assertThrows(IllegalArgumentException.class,()->new ActivityPayload.Choice("*","a".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->new ActivityPayload.Choice("YQ==","a".repeat(64)));
        byte[] bytes=new byte[769];assertThrows(IllegalArgumentException.class,()->new ActivityPayload.Choice(Base64.getEncoder().encodeToString(bytes),ActivityPayload.digest(bytes)));
        assertThrows(IllegalArgumentException.class,()->ActivityPayload.initial(CropDelivery.ID,9));
    }
    @Test void ambiguousEffectsCannotHideInTerminalFailureOrCancellation(){
        var f=new Fixture();f.prepare("one");var j=f.assign();var a=j.current();
        commit(f.s,f.s.store.recordExecutionResult(f.root,j.guard(),new Report(a.id(),a.generation(),1,Map.of(),List.of(),new Execution(ExecutionStatus.FAILED,Reason.ACTION_FAILED,0,null)),f.s.owner));
        assertEquals(State.INTERRUPTED,f.job().state());assertTrue(f.job().current().uncertain());
        assertEquals(ActivityRuntime.Next.RECOVER_OR_SETTLE,f.runtime.snapshot(f.root,f.s.owner).next());assertTrue(f.job().activity().checkpoints().isEmpty());
        assertFalse(f.runtime.prepare(f.proposal("retry"),f.s.owner).accepted());
    }
    @Test void cancellationPreservesHistoryAndRejectsFurtherProposals(){
        var f=new Fixture();f.prepare("one");f.finish();var j=f.job();commit(f.s,f.runtime.cancel(f.root,j.guard(),f.s.owner));
        assertEquals(State.CANCELLED,f.job().state());assertEquals(1,f.job().activity().checkpoints().size());
        assertFalse(f.runtime.prepare(f.proposal("two"),f.s.owner).accepted());
    }
    @Test void schema4AndLegacyRowsRoundTripWithoutInventedCheckpoints()throws Exception {
        var f=new Fixture();f.prepare("one");f.finish();var decoded=JobCodec.job(StrictJson.object(JobCodec.job(f.job())));assertEquals(f.job(),decoded);
        var s=new Scene();var legacy=s.create(1);assertNull(JobCodec.job(StrictJson.object(JobCodec.job(legacy))).activity());
        assertEquals(1L,StrictJson.object(JobCodec.job(legacy)).get("schema"));assertEquals(4L,StrictJson.object(JobCodec.job(f.job())).get("schema"));
    }
    @Test void newJournalReopensSameRootAndExcludesDuplicateWriter(@TempDir Path world)throws Exception {
        var f=new Fixture();f.prepare("one");f.finish();var snapshot=f.s.store.snapshot();
        try(var disk=JobJournal.open(world,f.s.world)) {
            disk.replace(disk.snapshot(),new Snapshot(f.s.world,1,snapshot.jobs(),snapshot.allocations()));
            assertThrows(java.io.IOException.class,()->JobJournal.open(world,f.s.world));
        }
        try(var disk=JobJournal.open(world,f.s.world)){assertFalse(disk.readOnly());assertEquals(f.job(),disk.snapshot().jobs().getFirst());}
    }
    @Test void archivePayloadRetainsPreparedArtifactAndHistoricalCheckpointReferences(){
        var f=new Fixture();f.prepare("one");assertTrue(f.s.store.protectedRoots().artifacts().contains(REF));f.finish();f.prepare("two");f.finish();
        var payload=JobHistoryCodec.encode(f.s.store.snapshot());assertDoesNotThrow(()->assertEquals(f.job(),JobHistoryCodec.decode(payload,f.s.world).jobs().getFirst()));
    }
    @Test void restartOfIdleCheckpointKeepsHistoryWithoutInventingAnotherAttempt(){
        var f=new Fixture();f.prepare("one");f.finish();var j=f.job();var restored=new JobLifecycleStore(f.s.store.snapshot(),f.s.memory,
                JobLifecycleStore.privateJobs((a,c)->true),c->{},id->Optional.of(CropDelivery.SPEC),environment(),f.s.clock,Settings.defaults(),false);
        assertTrue(restored.ready());assertEquals(j,restored.query(f.root,f.s.owner));assertEquals(1,restored.query(f.root,f.s.owner).attempts().size());
    }
    @Test void futurePlanCountCanChangeWithoutErasingVerifiedHistory(){
        var f=new Fixture();f.prepare("one");f.finish();var old=f.job().activity().checkpoints();var p=f.proposal("two");
        commit(f.s,f.runtime.prepare(new ActivityRuntime.Proposal(1,p.id(),p.root(),p.expected(),p.token(),p.request(),REF,null,p.quantities(),p.allowance(),3),f.s.owner));
        assertEquals(old,f.job().activity().checkpoints());assertEquals(3,f.runtime.snapshot(f.root,f.s.owner).plannedSteps());
    }
    @Test void canonicalSharedFixturesRetainExactBytesDigestAndNonExecutableMeaning()throws Exception {
        String path="/dev/aivillages/core/kernel/runtime/activity/contract-fixtures.json";
        try(var input=getClass().getResourceAsStream(path)) {
            assertNotNull(input);var fixtures=StrictJson.object(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(Boolean.TRUE,fixtures.get("fixtureOnly"));var cases=(List<?>)fixtures.get("cases");assertEquals(4,cases.size());
            for(Object raw:cases) {
                @SuppressWarnings("unchecked") var row=(Map<String,Object>)raw;String canonical=StrictJson.canonical(row.get("payload"));
                assertEquals(row.get("canonical_utf8"),canonical);assertEquals(row.get("sha256"),ActivityPayload.digest(canonical));
            }
            @SuppressWarnings("unchecked") var first=(Map<String,Object>)cases.getFirst();
            @SuppressWarnings("unchecked") var payload=(Map<String,Object>)first.get("payload");
            @SuppressWarnings("unchecked") var q=(Map<String,Object>)payload.get("quantity");
            var split=new ActivityPayload.Quantities((Long)q.get("deficit"),(Long)q.get("yield"),(Long)q.get("batches"),(Long)q.get("produced"),(Long)q.get("goalAllocation"),(Long)q.get("surplus"),0);
            assertFalse(split.exact());assertEquals(1,split.surplus());
            var f=new Fixture();assertFalse(f.runtime.create(UUID.randomUUID(),new CapabilityRequest(new CapabilityId("fixture:four-planks",1),Map.of()),f.s.owner,observation(),limits(11_000),2).accepted());
        }
    }
    @Test void removingOrChangingVerifiedHistoryIsRejectedBySnapshotImport(){
        var f=new Fixture();f.prepare("one");f.finish();var j=f.job();var p=j.activity();
        var erased=new ActivityPayload(p.mode(),p.policy(),p.plannedSteps(),null,p.steps(),List.of(),p.blocker());
        var changed=new Job(j.id(),j.submissionId(),j.revision(),j.request(),null,List.of(),List.of(),j.state(),j.reason(),j.cancellationOutcome(),j.allowance(),j.usage(),j.generation(),j.attempts(),j.events(),null,erased);
        assertThrows(IllegalArgumentException.class,()->JobLifecycleStore.validateSnapshot(new Snapshot(f.s.world,1,List.of(changed),List.of()),Settings.defaults()));
        var c=p.checkpoints().getFirst();var forged=new ActivityPayload(p.mode(),p.policy(),2,null,p.steps(),List.of(new ActivityPayload.Checkpoint(c.step(),c.attempt(),c.generation()+1,c.quantity(),c.receipts(),c.evidence())),p.blocker());
        assertThrows(IllegalArgumentException.class,()->forged.validate(j));
    }
    @Test void allocationsRemainDisjointAcrossActivityReceiptsAndLegacyDemands(){
        var f=new Fixture();f.prepare("one");f.finish();var producer=f.job();var attempt=producer.current();var deposit=attempt.receipts().stream().filter(r->r.stage()==CropDelivery.Stage.DEPOSIT).findFirst().orElseThrow();
        var first=f.s.create(1);var second=f.s.create(1);
        commit(f.s,f.s.store.allocate(new Allocation(UUID.randomUUID(),producer.id(),attempt.id(),deposit.receiptId(),first.id(),1),first.guard(),f.s.owner));
        assertFalse(f.s.store.allocate(new Allocation(UUID.randomUUID(),producer.id(),attempt.id(),deposit.receiptId(),second.id(),1),second.guard(),f.s.owner).accepted());
        assertEquals(1,f.s.store.snapshot().allocations().size());
    }
    @Test void unknownActivityFieldsAndDowngradedEnvelopeCannotBeDecoded()throws Exception {
        var f=new Fixture();var row=StrictJson.object(JobCodec.job(f.job()));
        @SuppressWarnings("unchecked") var activity=(Map<String,Object>)row.get("activity");activity.put("terminalProof",true);
        assertThrows(IllegalArgumentException.class,()->JobCodec.job(row));activity.remove("terminalProof");row.put("schema",3L);
        assertThrows(StrictJson.Invalid.class,()->JobCodec.job(row));
    }

    @Test void interruptedActivitySegmentStaysFencedUntilItsStopAdapterIsQualified(){
        var f=new Fixture();f.prepare("one");f.assign();
        var restored=new JobLifecycleStore(f.s.store.snapshot(),f.s.memory,JobLifecycleStore.privateJobs((a,c)->true),c->{},
                id->Optional.of(CropDelivery.SPEC),environment(),f.s.clock,Settings.defaults(),false);restored.tick();
        int[] captures={0};var runtime=new dev.aivillages.core.kernel.runtime.RecoveredJobOwners(restored,new dev.aivillages.core.kernel.runtime.RecoveredJobOwners.LiveOwners(){
            public boolean bootstrap(Job j){return false;}public boolean dispatcher(Job j){return false;}
            public dev.aivillages.core.kernel.runtime.RecoveredJobOwners.Presence presence(Job j){return dev.aivillages.core.kernel.runtime.RecoveredJobOwners.Presence.AVAILABLE;}
        },c->{captures[0]++;return StoppedObservation.ambiguous("fixture");},4);
        runtime.step();assertEquals(0,captures[0]);assertEquals(State.INTERRUPTED,restored.query(f.root,f.s.owner).state());assertTrue(restored.query(f.root,f.s.owner).current().uncertain());
    }

}
