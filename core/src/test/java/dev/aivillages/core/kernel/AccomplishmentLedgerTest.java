package dev.aivillages.core.kernel;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static dev.aivillages.core.kernel.Accomplishments.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static org.junit.jupiter.api.Assertions.*;

class AccomplishmentLedgerTest {
    @TempDir Path temp;
    static final class Fixture {
        final JobLifecycleStoreTest.Scene scene=new JobLifecycleStoreTest.Scene();
        final AtomicReference<Jobs.Snapshot> override=new AtomicReference<>();
        final AtomicReference<CitizenRegistry.Snapshot> citizens=new AtomicReference<>();
        final RetentionRoots roots=new RetentionRoots();
        final JobOutcomeSource source=new JobOutcomeSource(()->override.get()==null?scene.memory.state:override.get(),citizens::get);
        Fixture(){registry(scene.actor);}
        void registry(ActorRef... actors){citizens.set(new CitizenRegistry.Snapshot(scene.world,1,Arrays.stream(actors)
                .map(a->new CitizenRegistry.Citizen(a,scene.owner,null,CitizenRegistry.Availability.LOADED)).toList(),null));}
        Jobs.Job successful(int amount){return scene.report(scene.assign(scene.create(amount)),amount,ExecutionStatus.SUCCEEDED);}
        void forged(Jobs.Job old,UnaryOperator<Jobs.Attempt> transform){
            var attempts=old.attempts().stream().map(transform).toList();
            var replacement=copy(old,attempts);
            override.set(new Jobs.Snapshot(scene.world,scene.memory.state.revision(),scene.memory.state.jobs().stream().map(j->j.id().equals(old.id())?replacement:j).toList(),List.of()));
        }
        AccomplishmentLedger open(Path path)throws IOException{return open(path,Limits.defaults(),(a,o,v)->false,p->{});}
        AccomplishmentLedger open(Path path,Limits limits,AccomplishmentLedger.AggregatePolicy policy,AccomplishmentJournal.Faults faults)throws IOException{
            return AccomplishmentLedger.open(path,scene.world,source,roots,limits,policy,faults);
        }
    }
    static Jobs.Job copy(Jobs.Job j,List<Jobs.Attempt> attempts){return new Jobs.Job(j.id(),j.submissionId(),j.revision(),j.request(),j.parent(),j.dependencies(),j.children(),j.state(),j.reason(),j.cancellationOutcome(),j.allowance(),j.usage(),j.generation(),attempts,j.events());}
    static Jobs.Attempt attempt(Jobs.Attempt a,List<CropDelivery.CropReceipt> receipts,long credited,Execution terminal){
        return new Jobs.Attempt(a.id(),a.generation(),a.worker(),a.bound(),a.allowance(),a.executions(),a.usage(),receipts,a.effects(),credited,terminal,a.uncertain(),a.cancellationDeliveries());
    }
    static long quantity(AccomplishmentLedger ledger){return ledger.snapshot().aggregates().stream().mapToLong(Aggregate::quantity).sum();}
    static Result record(AccomplishmentLedger ledger,Jobs.Job job){return ledger.recordOutcomes(List.of(OutcomeRef.of(job)),()->false);}
    static Limits limit(int index,int n){int[] v={8,128,8,4,64,1_048_576,8192};v[index]=n;return new Limits(v[0],v[1],v[2],v[3],v[4],v[5],v[6]);}

    @Test void verifiedOwnerReceiptsAttributeExactQuantityAndOneCompletion()throws Exception{
        var f=new Fixture();var job=f.successful(3);var jobs=f.scene.memory.state;
        try(var ledger=f.open(temp)){assertEquals(Code.APPLIED,record(ledger,job).code());var q=ledger.query(f.scene.actor,f.scene.owner,16);
            assertEquals(3,quantity(ledger));assertEquals(1,q.evidence().getFirst().completedJobs());assertEquals(1,q.evidence().getFirst().contributionSamples());
            assertTrue(q.recognition().getFirst().active());assertEquals(jobs,f.scene.memory.state);
            assertTrue(q.evidence().getFirst().samples().stream().anyMatch(s->s.receipts().size()==3));}
    }
    @Test void unknownJobAndForeignWorldProduceNoCredit()throws Exception{
        var f=new Fixture();try(var ledger=f.open(temp)){
            assertEquals(Code.UNVERIFIABLE,ledger.recordOutcomes(List.of(new OutcomeRef(f.scene.world,UUID.randomUUID(),1)),()->false).code());
            assertEquals(Code.UNVERIFIABLE,ledger.recordOutcomes(List.of(new OutcomeRef(UUID.randomUUID(),UUID.randomUUID(),1)),()->false).code());assertEquals(0,quantity(ledger));}
    }
    @Test void narratedCountersCannotReplaceReceipts()throws Exception{
        var f=new Fixture();var job=f.successful(3);f.forged(job,a->attempt(a,List.of(),3,a.terminal()));
        try(var ledger=f.open(temp)){assertEquals(Code.UNVERIFIABLE,record(ledger,job).code());assertEquals(0,quantity(ledger));}
    }
    @ParameterizedTest @EnumSource(CropDelivery.Stage.class)
    void eachMissingPhysicalStageRejectsWholeJobCredit(CropDelivery.Stage missing)throws Exception{
        var f=new Fixture();var job=f.successful(3);f.forged(job,a->attempt(a,a.receipts().stream().filter(r->r.stage()!=missing).toList(),3,a.terminal()));
        try(var ledger=f.open(temp)){assertEquals(Code.UNVERIFIABLE,record(ledger,job).code());assertTrue(ledger.snapshot().progress().isEmpty());}
    }
    @Test void foreignRegistryOwnerCannotAttributeTheSameCitizen()throws Exception{
        var f=new Fixture();var job=f.successful(2);f.citizens.set(new CitizenRegistry.Snapshot(f.scene.world,2,List.of(new CitizenRegistry.Citizen(f.scene.actor,f.scene.foreign,null,CitizenRegistry.Availability.LOADED)),null));
        try(var ledger=f.open(temp)){assertEquals(Code.UNVERIFIABLE,record(ledger,job).code());}
    }
    @Test void matchingDisplayNameCannotReplaceStableEntityBinding()throws Exception{
        var f=new Fixture();var job=f.successful(2);var replacement=new ActorRef(f.scene.actor.citizenId(),UUID.randomUUID(),f.scene.actor.dimension());f.registry(replacement);
        try(var ledger=f.open(temp)){assertEquals(Code.UNVERIFIABLE,record(ledger,job).code());}
    }
    @Test void receiptFromUnpinnedRunIsRejected()throws Exception{
        var f=new Fixture();var job=f.successful(2);f.forged(job,a->attempt(a,a.receipts().stream().map(r->new CropDelivery.CropReceipt(r.receiptId(),UUID.randomUUID(),r.batchId(),r.actor(),r.source(),r.destination(),r.stage(),r.wheat())).toList(),2,a.terminal()));
        try(var ledger=f.open(temp)){assertEquals(Code.UNVERIFIABLE,record(ledger,job).code());}
    }
    @Test void replayAcrossReloadDoesNotPublishOrCreditAgain()throws Exception{
        var f=new Fixture();var job=f.successful(3);Snapshot before;byte[] bytes;
        try(var ledger=f.open(temp)){record(ledger,job);before=ledger.snapshot();bytes=Files.readAllBytes(temp.resolve(AccomplishmentLedger.WORLD_RELATIVE_PATH).resolve("state.jsonl"));}
        try(var ledger=f.open(temp)){assertEquals(before,ledger.snapshot());assertEquals(Code.DUPLICATE,record(ledger,job).code());assertEquals(3,quantity(ledger));
            assertArrayEquals(bytes,Files.readAllBytes(temp.resolve(AccomplishmentLedger.WORLD_RELATIVE_PATH).resolve("state.jsonl")));}
    }
    @Test void newerCumulativeEvidenceThenOlderReferenceCannotDoubleCredit()throws Exception{
        var f=new Fixture();var partial=f.scene.report(f.scene.assign(f.scene.create(5)),2,null);var done=f.scene.report(partial,3,ExecutionStatus.SUCCEEDED);
        try(var ledger=f.open(temp)){assertEquals(Code.APPLIED,record(ledger,done).code());assertEquals(Code.DUPLICATE,record(ledger,partial).code());assertEquals(5,quantity(ledger));}
    }
    @Test void staleUnseenReferenceWaitsForCurrentOwnerRevision()throws Exception{
        var f=new Fixture();var partial=f.scene.report(f.scene.assign(f.scene.create(5)),2,null);var done=f.scene.report(partial,3,ExecutionStatus.SUCCEEDED);
        try(var ledger=f.open(temp)){assertEquals(Code.STALE,record(ledger,partial).code());assertEquals(0,quantity(ledger));record(ledger,done);assertEquals(5,quantity(ledger));}
    }
    @Test void incrementalPartialContributionKeepsOneRunSampleAndOneWholeJob()throws Exception{
        var f=new Fixture();var partial=f.scene.report(f.scene.assign(f.scene.create(5)),2,null);
        try(var ledger=f.open(temp)){record(ledger,partial);assertEquals(2,quantity(ledger));assertEquals(0,ledger.snapshot().aggregates().getFirst().completedJobs());
            var done=f.scene.report(partial,3,ExecutionStatus.SUCCEEDED);record(ledger,done);var a=ledger.snapshot().aggregates().getFirst();
            assertEquals(5,a.quantity());assertEquals(1,a.contributionSamples());assertEquals(1,a.completedJobs());assertEquals(Code.DUPLICATE,record(ledger,done).code());}
    }
    @Test void twoWorkersOwnDistinctPartialContributionsWithoutWholeJobDuplication()throws Exception{
        var f=new Fixture();var second=JobLifecycleStoreTest.actor();f.registry(f.scene.actor,second);
        var partial=f.scene.report(f.scene.assign(f.scene.create(5)),2,ExecutionStatus.BLOCKED);
        try(var ledger=f.open(temp)){record(ledger,partial);
            assertTrue(f.scene.store.transition(partial.id(),partial.guard(),Jobs.State.READY,null,f.scene.owner).accepted());f.scene.settle();
            var next=f.scene.assign(partial,second,new Budgets.Limits(Map.of(Budgets.Kind.CALLS,19L),11000));var done=f.scene.report(next,3,ExecutionStatus.SUCCEEDED);record(ledger,done);
            var one=ledger.query(f.scene.actor,f.scene.owner,16).evidence().getFirst();var two=ledger.query(second,f.scene.owner,16).evidence().getFirst();
            assertEquals(2,one.quantity());assertEquals(0,one.completedJobs());assertEquals(3,two.quantity());assertEquals(1,two.completedJobs());assertEquals(done.fulfilled(),quantity(ledger));}
    }
    @ParameterizedTest @EnumSource(value=Reason.class,names={"RESOURCE_MISSING","AUTHORITY_DENIED","MODEL_UNAVAILABLE","ACTION_FAILED","CANCELLED","INTERRUPTED"})
    void reasonsRemainDistinctAndDoNotGrantNegativeCompetence(Reason reason)throws Exception{
        var f=new Fixture();var job=f.scene.assign(f.scene.create(3));var status=reason==Reason.CANCELLED?ExecutionStatus.CANCELLED:reason==Reason.INTERRUPTED?ExecutionStatus.INTERRUPTED:ExecutionStatus.BLOCKED;
        var report=new Execution(status,reason,0,null);f.forged(job,a->attempt(a,List.of(),0,report));
        try(var ledger=f.open(temp)){record(ledger,job);var a=ledger.snapshot().aggregates().getFirst();assertEquals(1L,a.outcomes().get(classify(report)));assertEquals(0,a.completedJobs());assertEquals(0,a.quantity());
            assertFalse(ledger.snapshot().recognition().getFirst().active());assertEquals(Jobs.State.ACTIVE,f.scene.memory.state.jobs().getFirst().state());}
    }
    @Test void cancelledWorkPreservesCertainPartialCreditWithoutCompletion()throws Exception{
        var f=new Fixture();var job=f.scene.report(f.scene.assign(f.scene.create(5)),2,ExecutionStatus.CANCELLED);
        try(var ledger=f.open(temp)){record(ledger,job);var a=ledger.snapshot().aggregates().getFirst();assertEquals(2,a.quantity());assertEquals(0,a.completedJobs());assertEquals(1L,a.outcomes().get(OutcomeClass.CANCELLATION));}
    }
    @Test void interruptedEffectsAreNotCreditedUntilJobsReconcilesThem()throws Exception{
        var f=new Fixture();var job=f.scene.report(f.scene.assign(f.scene.create(3)),2,ExecutionStatus.INTERRUPTED);
        try(var ledger=f.open(temp)){record(ledger,job);assertEquals(0,quantity(ledger));assertEquals(Jobs.State.INTERRUPTED,f.scene.current(job).state());}
    }
    @Test void differentGeometryRetainsSeparateConditionsAndSampleCounts()throws Exception{
        var f=new Fixture();var one=f.successful(2);
        var actor=f.scene.actor;var request=new CapabilityRequest(CropDelivery.ID,Map.of("actor",new ActorValue(actor),"amount",new IntValue(2),
                "source",new AreaValue(new Cuboid(actor.dimension(),0,0,0,15,0,15)),"destination",new ContainerValue(new ContainerRef(actor.dimension(),100,0,100))));
        UUID id=UUID.randomUUID();assertTrue(f.scene.store.create(id,id,request,f.scene.owner,JobLifecycleStoreTest.observation(),JobLifecycleStoreTest.limits(11000),List.of()).accepted());f.scene.settle();
        var two=f.scene.report(f.scene.assign(f.scene.store.query(id,f.scene.owner)),2,ExecutionStatus.SUCCEEDED);
        try(var ledger=f.open(temp)){record(ledger,one);record(ledger,two);var values=ledger.query(actor,f.scene.owner,16).evidence();assertEquals(2,values.size());
            assertEquals(Set.of(Size.SMALL,Size.LARGE),values.stream().map(a->a.key().environment().sourceSize()).collect(java.util.stream.Collectors.toSet()));
            assertTrue(values.stream().allMatch(a->a.completedJobs()==1&&a.contributionSamples()==1));
            assertEquals(Code.APPLIED,ledger.rules(List.of(new Rule("crop-contributor",2,"Two measured outcomes",CropDelivery.ID,2,4,2)),()->false).code());
            assertFalse(ledger.snapshot().recognition().getFirst().active(),"Different contexts must not be pooled silently");}
    }
    @Test void recognitionDoesNotChangeControlOrSourceLifecycle()throws Exception{
        var f=new Fixture();var job=f.successful(2);var before=f.scene.memory.state;
        try(var ledger=f.open(temp)){record(ledger,job);assertTrue(ledger.snapshot().recognition().getFirst().active());
            assertEquals(Code.DENIED,ledger.query(f.scene.actor,f.scene.foreign,16).code());assertEquals(before,f.scene.memory.state);assertTrue(f.source.controls(f.scene.actor,f.scene.owner));assertFalse(f.source.controls(f.scene.actor,f.scene.foreign));}
    }
    @Test void renamePreservesEvidenceAndRecognitionIdentity()throws Exception{
        var f=new Fixture();var job=f.successful(2);try(var ledger=f.open(temp)){record(ledger,job);var before=ledger.snapshot();
            f.citizens.set(new CitizenRegistry.Snapshot(f.scene.world,2,List.of(new CitizenRegistry.Citizen(f.scene.actor,f.scene.owner,"Renamed",CitizenRegistry.Availability.LOADED)),null));
            assertEquals(before,ledger.snapshot());assertEquals(f.scene.actor,ledger.query(f.scene.actor,f.scene.owner,16).recognition().getFirst().actor());}
    }
    @Test void explicitlySharedAggregateExcludesPrivateSupportingRefsAndRevocationIsImmediate()throws Exception{
        var f=new Fixture();var allow=new AtomicBoolean();var job=f.successful(3);
        try(var ledger=f.open(temp,Limits.defaults(),(a,o,v)->allow.get()&&v.equals(f.scene.foreign),p->{})){
            record(ledger,job);assertTrue(ledger.summaries(f.scene.actor,f.scene.foreign,16).isEmpty());allow.set(true);
            assertEquals(3,ledger.summaries(f.scene.actor,f.scene.foreign,16).getFirst().quantity());assertEquals(Code.DENIED,ledger.query(f.scene.actor,f.scene.foreign,16).code());
            allow.set(false);assertTrue(ledger.summaries(f.scene.actor,f.scene.foreign,16).isEmpty());}
    }
    @Test void removingCurrentCitizenBindingRevokesPrivateQueries()throws Exception{
        var f=new Fixture();var job=f.successful(3);try(var ledger=f.open(temp)){record(ledger,job);f.registry();
            assertEquals(Code.DENIED,ledger.query(f.scene.actor,f.scene.owner,16).code());assertTrue(ledger.summaries(f.scene.actor,f.scene.owner,16).isEmpty());assertEquals(3,quantity(ledger));}
    }
    @Test void ruleUpgradeRecomputesAndRetiresWithoutErasingEvidence()throws Exception{
        var f=new Fixture();var job=f.successful(2);try(var ledger=f.open(temp)){record(ledger,job);var evidence=ledger.snapshot().aggregates();
            var rule=new Rule("crop-contributor",2,"Experienced crop contributor",CropDelivery.ID,2,4,2);assertEquals(Code.APPLIED,ledger.rules(List.of(rule),()->false).code());
            var title=ledger.snapshot().recognition().getFirst();assertFalse(title.active());assertEquals(2,title.version());assertEquals(1,title.priorVersion());assertEquals(evidence,ledger.snapshot().aggregates());
            assertEquals(Code.DUPLICATE,ledger.rules(List.of(rule),()->false).code());var two=f.successful(2);record(ledger,two);assertTrue(ledger.snapshot().recognition().getFirst().active());}
        try(var ledger=f.open(temp)){assertEquals(4,quantity(ledger));assertEquals(2,ledger.snapshot().recognition().getFirst().version());}
    }
    @Test void sameRuleVersionCannotChangeMeaningAndDowngradeCannotReactivateIt()throws Exception{
        var f=new Fixture();var job=f.successful(2);try(var ledger=f.open(temp)){record(ledger,job);
            assertEquals(Code.UNVERIFIABLE,ledger.rules(List.of(new Rule("crop-contributor",1,"Changed meaning",CropDelivery.ID,2,4,2)),()->false).code());
            ledger.rules(List.of(new Rule("crop-contributor",2,"Changed meaning",CropDelivery.ID,2,4,2)),()->false);
            assertEquals(Code.UNVERIFIABLE,ledger.rules(Rule.defaults(),()->false).code());}
    }
    @Test void withdrawnRulesStayWithdrawnAfterNewOutcomesAndReload()throws Exception{
        var f=new Fixture();var job=f.successful(2);try(var ledger=f.open(temp)){record(ledger,job);ledger.rules(List.of(),()->false);record(ledger,f.successful(2));assertTrue(ledger.snapshot().rules().stream().noneMatch(Rule::active));assertFalse(ledger.snapshot().recognition().getFirst().active());}
        try(var ledger=f.open(temp)){assertTrue(ledger.snapshot().rules().stream().noneMatch(Rule::active));assertEquals(4,quantity(ledger));}
    }
    @Test void tenThousandReplaysHaveConstantStorageAndProgress()throws Exception{
        var f=new Fixture();var job=f.successful(2);try(var ledger=f.open(temp)){record(ledger,job);var before=ledger.snapshot();int bytes=AccomplishmentCodec.encode(before).length;
            for(int n=0;n<10000;n++)assertEquals(Code.DUPLICATE,record(ledger,job).code());assertEquals(before,ledger.snapshot());assertEquals(bytes,AccomplishmentCodec.encode(ledger.snapshot()).length);}
    }
    @Test void allSixtyFourSupportedJobsCompactToOneAggregateAndFourSamples()throws Exception{
        var f=new Fixture();try(var ledger=f.open(temp)){for(int n=0;n<64;n++)assertEquals(Code.APPLIED,record(ledger,f.successful(2)).code());
            var a=ledger.snapshot().aggregates().getFirst();assertEquals(128,a.quantity());assertEquals(64,a.completedJobs());assertEquals(64,a.contributionSamples());assertEquals(4,a.samples().size());
            assertEquals(64,ledger.snapshot().progress().size());assertTrue(AccomplishmentCodec.encode(ledger.snapshot()).length<100000);}
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3,4,5,6})
    void everyZeroBudgetRejectsNewCreditWithoutPublication(int index)throws Exception{
        var f=new Fixture();var job=f.successful(2);try(var ledger=f.open(temp,limit(index,0),(a,o,v)->false,p->{})){
            assertEquals(Code.LIMIT,record(ledger,job).code());assertEquals(0,quantity(ledger));assertTrue(ledger.snapshot().progress().isEmpty());}
    }
    @ParameterizedTest @CsvSource({"0,9","1,129","2,9","3,5","4,65","5,1048577","6,8193","0,-1","5,-1"})
    void invalidAndOneOverHardBoundsReject(int index,int amount){assertThrows(IllegalArgumentException.class,()->limit(index,amount));}
    @Test void exactBytesAndWorkPassButOneLessFailsAtomically()throws Exception{
        var f=new Fixture();var job=f.successful(2);Result measured;try(var ledger=f.open(temp.resolve("measure"))){measured=record(ledger,job);}
        for(int i:new int[]{5,6}){int n=i==5?measured.bytes():measured.work();
            try(var ledger=f.open(temp.resolve("n"+i),limit(i,n),(a,o,v)->false,p->{})){assertEquals(Code.APPLIED,record(ledger,job).code());}
            try(var ledger=f.open(temp.resolve("less"+i),limit(i,n-1),(a,o,v)->false,p->{})){assertEquals(Code.LIMIT,record(ledger,job).code());assertEquals(0,quantity(ledger));}}
    }
    @Test void aggregateAndJobCapacityDoNotEvictCreditedProgress()throws Exception{
        var f=new Fixture();var second=JobLifecycleStoreTest.actor();f.registry(f.scene.actor,second);var one=f.successful(2);var job=f.scene.report(f.scene.assign(f.scene.create(2),second,JobLifecycleStoreTest.limits(11000)),2,ExecutionStatus.SUCCEEDED);
        for(int index:new int[]{1,4})try(var ledger=f.open(temp.resolve("cap"+index),limit(index,1),(a,o,v)->false,p->{})){
            assertEquals(Code.APPLIED,record(ledger,one).code());var before=ledger.snapshot();assertEquals(Code.LIMIT,record(ledger,job).code());assertEquals(before,ledger.snapshot());assertEquals(Code.DUPLICATE,record(ledger,one).code());}
    }
    @Test void batchNAndNPlusOneAreCheckedBeforeIngestion()throws Exception{
        var f=new Fixture();var job=f.successful(2);try(var ledger=f.open(temp,limit(0,1),(a,o,v)->false,p->{})){
            assertEquals(Code.LIMIT,ledger.recordOutcomes(List.of(OutcomeRef.of(job),OutcomeRef.of(job)),()->false).code());assertEquals(Code.APPLIED,record(ledger,job).code());}
    }
    @Test void queryLimitsDoNotLeakPrivateRows()throws Exception{
        var f=new Fixture();try(var ledger=f.open(temp)){record(ledger,f.successful(2));assertEquals(Code.LIMIT,ledger.query(f.scene.actor,f.scene.owner,0).code());assertEquals(Code.LIMIT,ledger.query(f.scene.actor,f.scene.owner,17).code());assertEquals(1,ledger.query(f.scene.actor,f.scene.owner,1).evidence().size());}
    }
    @Test void categoryQuotaAndBusyRootPublicationPreserveJobAndLedgerState()throws Exception{
        var f=new Fixture();var job=f.successful(2);var before=f.scene.memory.state;
        try(var ledger=f.open(temp)){ledger.capacity(a->false);assertEquals(Code.LIMIT,record(ledger,job).code());assertTrue(ledger.snapshot().progress().isEmpty());
            ledger.capacity(a->true);Object lease=f.roots.beginDelete(f.roots.snapshot().generation());assertNotNull(lease);
            try{assertEquals(Code.LIMIT,record(ledger,job).code());assertEquals(before,f.scene.memory.state);assertEquals(0,quantity(ledger));}
            finally{f.roots.endDelete(lease);}}
    }
    @Test void supportingJobAndArtifactRootsAndAllRecoveryBytesAreProtected()throws Exception{
        var f=new Fixture();try(var ledger=f.open(temp)){record(ledger,f.successful(2));record(ledger,f.successful(2));
            assertTrue(f.roots.snapshot().roots().contains(new WorldRetentionManager.Key("jobs","records")));
            assertTrue(f.roots.snapshot().roots().contains(WorldRetentionManager.artifact(JobLifecycleStoreTest.REF)));
            var owner=ledger.retentionOwner();long accounted=0;try(var cursor=owner.open()){boolean done=false;while(!done){var page=cursor.next(2,65536);assertTrue(page.entries().stream().allMatch(WorldRetentionManager.Entry::protectedRecord));accounted+=page.entries().stream().mapToLong(e->e.usage().bytes()).sum();done=page.complete();}}
            try(var files=Files.list(temp.resolve(AccomplishmentLedger.WORLD_RELATIVE_PATH))){assertEquals(files.mapToLong(p->{try{return Files.size(p);}catch(IOException e){throw new RuntimeException(e);}}).sum(),accounted);}
        }
    }
    @ParameterizedTest @EnumSource(AccomplishmentJournal.Point.class)
    void interruptedWritesPreserveExactPriorOrNewGenerationAndNeverChangeJobs(AccomplishmentJournal.Point point)throws Exception{
        var f=new Fixture();var first=f.successful(2);try(var ledger=f.open(temp)){record(ledger,first);}byte[] original=Files.readAllBytes(temp.resolve(AccomplishmentLedger.WORLD_RELATIVE_PATH).resolve("state.jsonl"));
        var second=f.successful(2);var jobs=f.scene.memory.state;
        try(var ledger=f.open(temp,Limits.defaults(),(a,o,v)->false,p->{if(p==point)throw new IOException("fault");})){
            var result=record(ledger,second);assertEquals(Code.UNAVAILABLE,result.code());assertEquals(point==AccomplishmentJournal.Point.AFTER_REPLACE,result.publicationMayHaveSucceeded());assertEquals(jobs,f.scene.memory.state);}
        Path root=temp.resolve(AccomplishmentLedger.WORLD_RELATIVE_PATH);var persisted=AccomplishmentCodec.decode(Files.readAllBytes(root.resolve("state.jsonl")),f.scene.world);
        assertEquals(point==AccomplishmentJournal.Point.AFTER_REPLACE?4:2,persisted.aggregates().getFirst().quantity());
        if(point!=AccomplishmentJournal.Point.AFTER_REPLACE)assertArrayEquals(original,Files.readAllBytes(root.resolve("state.jsonl")));
        try(var ledger=f.open(temp)){if(ledger.readOnly())assertEquals(Code.UNAVAILABLE,record(ledger,second).code());else{assertEquals(Code.DUPLICATE,record(ledger,first).code());record(ledger,second);assertEquals(4,quantity(ledger));}}
    }
    @ParameterizedTest @EnumSource(AccomplishmentJournal.Point.class)
    void cancellationAtEachCommitBoundaryPreservesUncertainty(AccomplishmentJournal.Point point)throws Exception{
        var f=new Fixture();var cancelled=new AtomicBoolean();var job=f.successful(2);
        try(var ledger=f.open(temp,Limits.defaults(),(a,o,v)->false,p->{if(p==point)cancelled.set(true);})){
            var result=ledger.recordOutcomes(List.of(OutcomeRef.of(job)),cancelled::get);assertEquals(Code.CANCELLED,result.code());assertEquals(point==AccomplishmentJournal.Point.AFTER_REPLACE,result.publicationMayHaveSucceeded());}
        assertEquals(Jobs.State.SUCCEEDED,f.scene.current(job).state());
    }
    @ParameterizedTest @ValueSource(strings={"current","previous","stage","foreign","utf8"})
    void opaqueCorruptFutureAndForeignRecordsRemainPreservedInactive(String kind)throws Exception{
        var f=new Fixture();try(var ledger=f.open(temp)){record(ledger,f.successful(2));record(ledger,f.successful(2));}
        var root=temp.resolve(AccomplishmentLedger.WORLD_RELATIVE_PATH);Path target=root.resolve(kind.equals("previous")?"state.prev.jsonl":kind.equals("stage")?"state.next.jsonl":"state.jsonl");
        byte[] bytes=kind.equals("utf8")?new byte[]{(byte)0xff}:kind.equals("foreign")?AccomplishmentCodec.encode(Snapshot.empty(UUID.randomUUID())):kind.equals("current")?Files.readString(target).replace("\"schema\":1","\"schema\":99").getBytes():"corrupt\n".getBytes();Files.write(target,bytes);
        try(var ledger=f.open(temp)){assertTrue(ledger.readOnly());assertEquals(Code.UNAVAILABLE,record(ledger,f.scene.memory.state.jobs().getFirst()).code());assertEquals(Code.UNAVAILABLE,ledger.query(f.scene.actor,f.scene.owner,16).code());assertArrayEquals(bytes,Files.readAllBytes(target));}
    }
    @Test void externalReplacementIsNotOverwritten()throws Exception{
        var f=new Fixture();try(var ledger=f.open(temp)){record(ledger,f.successful(2));Path current=temp.resolve(AccomplishmentLedger.WORLD_RELATIVE_PATH).resolve("state.jsonl");Files.writeString(current,"external\n");
            assertEquals(Code.UNAVAILABLE,record(ledger,f.successful(2)).code());assertEquals("external\n",Files.readString(current));}
    }
    @Test void singleWriterAndSymlinkPathsAreRejected()throws Exception{
        var f=new Fixture();try(var ledger=f.open(temp)){assertThrows(IOException.class,()->f.open(temp));}
        Path second=temp.resolve("link-world");Files.createDirectories(second);Files.createSymbolicLink(second.resolve("data"),temp);assertThrows(IOException.class,()->f.open(second));
    }
    @Test void strictRecordCodecRejectsUnknownFieldsAndStringCounters()throws Exception{
        var f=new Fixture();try(var ledger=f.open(temp)){record(ledger,f.successful(2));byte[] data=AccomplishmentCodec.encode(ledger.snapshot());var decoded=AccomplishmentCodec.decode(data,f.scene.world);assertEquals(ledger.snapshot(),decoded);
            for(String change:new String[]{"unknown","string"}){String s=new String(data);int split=s.indexOf('\n');String payload=s.substring(split+1);
                payload=change.equals("unknown")?payload.replaceFirst("\"quantity\":2","\"quantity\":2,\"execute\":true"):payload.replaceFirst("\"quantity\":2","\"quantity\":\"2\"");
                var header=new LinkedHashMap<>(StrictJson.object(s.substring(0,split)));header.put("sha256",AccomplishmentCodec.sha(payload.getBytes()));byte[] changed=(StrictJson.canonical(header)+"\n"+payload).getBytes();assertThrows(IOException.class,()->AccomplishmentCodec.decode(changed,f.scene.world));}}
    }
    @Test void readerRegistrationIsAdditiveAndFutureSchemaIsNotReadable(){
        assertTrue(SaveCompatibility.registered().readable(new SaveCompatibility.Format(DOMAIN,1)));assertFalse(SaveCompatibility.registered().readable(new SaveCompatibility.Format(DOMAIN,2)));assertTrue(SaveCompatibility.registered().readable(new SaveCompatibility.Format("jobs",1)));
    }
    @Test void withdrawnRuleCannotBeReintroducedWithChangedUnversionedMeaning()throws Exception{
        var f=new Fixture();try(var ledger=f.open(temp)){ledger.rules(List.of(),()->false);
            assertEquals(Code.UNVERIFIABLE,ledger.rules(List.of(new Rule("crop-contributor",1,"Different policy",CropDelivery.ID,3,6,3)),()->false).code());
            assertEquals(Code.APPLIED,ledger.rules(List.of(new Rule("crop-contributor",2,"Different policy",CropDelivery.ID,3,6,3)),()->false).code());}
    }
    @Test void ruleCatalogIncludesRetiredDefinitionsWithinFourSlots()throws Exception{
        var f=new Fixture();try(var ledger=f.open(temp)){var rules=new ArrayList<>(Rule.defaults());for(int n=1;n<4;n++)rules.add(new Rule("rule"+n,1,"Measured contributor",CropDelivery.ID,1,2,1));
            assertEquals(Code.APPLIED,ledger.rules(rules,()->false).code());ledger.rules(List.of(),()->false);var before=ledger.snapshot();
            assertEquals(Code.LIMIT,ledger.rules(List.of(new Rule("fifth",1,"Measured contributor",CropDelivery.ID,1,2,1)),()->false).code());assertEquals(before,ledger.snapshot());}
    }
    @Test void forgedReceiptReuseAcrossJobsCannotCreditOneActionTwice()throws Exception{
        var f=new Fixture();var first=f.successful(2);var second=f.successful(2);var copied=first.current().receipts();
        f.forged(second,a->new Jobs.Attempt(a.id(),a.generation(),a.worker(),a.bound(),a.allowance(),first.current().executions(),a.usage(),copied,a.effects(),2,a.terminal(),false,0));
        try(var ledger=f.open(temp)){assertEquals(Code.UNVERIFIABLE,record(ledger,second).code());assertEquals(0,quantity(ledger));}
    }
    @Test void arithmeticOverflowInForgedReceiptsIsRejectedAtomically()throws Exception{
        var f=new Fixture();var job=f.successful(2);f.forged(job,a->{var receipts=new ArrayList<>(a.receipts());var r=receipts.getFirst();
            receipts.add(new CropDelivery.CropReceipt(UUID.randomUUID(),r.runId(),r.batchId(),r.actor(),r.source(),r.destination(),r.stage(),Long.MAX_VALUE));return attempt(a,receipts,2,a.terminal());});
        try(var ledger=f.open(temp)){assertEquals(Code.UNVERIFIABLE,record(ledger,job).code());assertEquals(0,quantity(ledger));}
    }
    @Test void contextCapNAndNPlusOneDoNotPoolDifferentMethods()throws Exception{
        var f=new Fixture();var first=f.successful(2);var job=f.scene.create(2);var ref=new ArtifactRef(CropDelivery.ID,"b".repeat(64));
        assertTrue(f.scene.store.assign(job.id(),job.guard(),UUID.randomUUID(),f.scene.actor,ref,List.of(ref),JobLifecycleStoreTest.limits(11000),f.scene.owner).accepted());f.scene.settle();job=f.scene.report(job,2,ExecutionStatus.SUCCEEDED);
        try(var ledger=f.open(temp,limit(2,1),(a,o,v)->false,p->{})){assertEquals(Code.APPLIED,record(ledger,first).code());var before=ledger.snapshot();assertEquals(Code.LIMIT,record(ledger,job).code());assertEquals(before,ledger.snapshot());}
    }
    @Test void currentAndRollbackEvidenceArePinnedImmediatelyOnReopen()throws Exception{
        var f=new Fixture();var job=f.successful(2);try(var ledger=f.open(temp)){record(ledger,job);}var roots=new RetentionRoots();
        try(var ledger=AccomplishmentLedger.open(temp,f.scene.world,f.source,roots)){assertTrue(roots.snapshot().roots().contains(WorldRetentionManager.artifact(JobLifecycleStoreTest.REF)));assertTrue(roots.snapshot().roots().contains(new WorldRetentionManager.Key("jobs","records")));}
    }
}
