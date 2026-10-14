package dev.aivillages.core.kernel;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.KnowledgeAccess.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeAccessStoreTest {
    @TempDir Path temp;
    static final ArtifactRef METHOD=JobLifecycleStoreTest.REF;
    static final ArtifactRef NEXT=new ArtifactRef(CropDelivery.ID,"b".repeat(64));
    static final class Methods implements KnowledgeAccessStore.Catalog {
        final Map<ArtifactRef,Reason> available=new LinkedHashMap<>();
        Methods(){available.put(METHOD,null);}
        public Decision usable(ArtifactRef method){return available.containsKey(method)?available.get(method)==null?Decision.allow():Decision.deny(available.get(method)):Decision.deny(Reason.ARTIFACT_INVALID);}
        public List<ArtifactRef> closure(ArtifactRef method){return List.of(method);}
        public List<ArtifactRef> admittedMethods(){return List.copyOf(available.keySet());}
    }
    static final class Fixture implements AutoCloseable {
        final AccomplishmentLedgerTest.Fixture f=new AccomplishmentLedgerTest.Fixture();
        final ActorRef teacher=f.scene.actor,learner=JobLifecycleStoreTest.actor();
        final Methods methods=new Methods();
        final AccomplishmentLedger ledger;
        final KnowledgeAccessSource source;
        final RetentionRoots roots=new RetentionRoots();
        final Path path;
        KnowledgeAccessStore access;
        Fixture(Path path)throws Exception{this.path=path;ledger=f.open(path);
            var teaching=f.successful(2);assertEquals(Accomplishments.Code.APPLIED,AccomplishmentLedgerTest.record(ledger,teaching).code());
            source=new KnowledgeAccessSource(()->f.override.get()==null?f.scene.memory.state:f.override.get(),f.citizens::get,ledger::snapshot);
            access=open(Limits.defaults(),(t,l,m)->false,List.of(),p->{});enroll(learner,f.scene.owner);observe(true);}
        KnowledgeAccessStore open(Limits limits,KnowledgeAccessStore.TeachingSharing sharing,List<VersionTransfer> versions,KnowledgeAccessJournal.Faults faults)throws IOException{
            return KnowledgeAccessStore.open(path,f.scene.world,source,methods,f.scene.clock,roots,limits,sharing,versions,faults);}
        void enroll(ActorRef actor,TrustedContext owner){var old=f.citizens.get();var records=new ArrayList<>(old.citizens());
            records.add(new CitizenRegistry.Citizen(actor,owner,null,CitizenRegistry.Availability.LOADED));
            f.citizens.set(new CitizenRegistry.Snapshot(f.scene.world,old.revision()+1,records,null));}
        void observe(boolean presence){source.observe(f.citizens.get(),f.scene.memory.state,presence?Set.of(new Pair(teacher,learner)):Set.of());}
        Jobs.Job create(){UUID id=UUID.randomUUID();var result=f.scene.store.create(id,id,JobLifecycleStoreTest.request(learner,2),f.scene.owner,
                    JobLifecycleStoreTest.observation(),JobLifecycleStoreTest.limits(f.scene.clock.now+10000),List.of());
            assertTrue(result.accepted());f.scene.settle();observe(true);return f.scene.store.query(id,f.scene.owner);}
        Jobs.Job start(){var job=create();assertEquals(Code.APPLIED,access.startApprenticeship(job.id(),teacher,METHOD,f.scene.owner,()->false).code());return job;}
        Jobs.Job assign(Jobs.Job job){job=f.scene.assign(job,learner,job.allowance());observe(true);return job;}
        Jobs.Job complete(Jobs.Job job){job=f.scene.report(job,2,ExecutionStatus.SUCCEEDED);observe(true);
            assertEquals(Accomplishments.Code.APPLIED,AccomplishmentLedgerTest.record(ledger,job).code());return job;}
        Result finish(Jobs.Job job){return access.recordLearningOutcome(job.id(),f.scene.owner,()->false);}
        @Override public void close()throws Exception{access.close();ledger.close();}
    }
    @Test void migrationPreservesLegacyAbsentRowsAndRenamesWithoutGrantingFutureRevisions()throws Exception{
        try(var f=new Fixture(temp)){
            assertTrue(f.access.mayUse(f.teacher,METHOD,f.f.scene.owner));assertTrue(f.access.snapshot().grants().isEmpty());
            f.methods.available.put(NEXT,null);assertEquals(Reason.KNOWLEDGE_REQUIRED,f.access.evaluateUse(f.teacher,NEXT,f.f.scene.owner).reason());
            var saved=f.access.snapshot();f.access.close();f.access=f.open(Limits.defaults(),(t,l,m)->false,List.of(),p->{});
            assertEquals(saved,f.access.snapshot());assertTrue(f.access.mayUse(f.teacher,METHOD,f.f.scene.owner));
            assertFalse(f.access.mayUse(f.learner,METHOD,f.f.scene.owner));assertEquals(Code.DUPLICATE,f.access.migrateSharedAccess(()->false).code());
        }
    }
    @Test void knownButUnlearnedAndIncompatibleAreDifferentFromSynthesisGaps()throws Exception{
        try(var f=new Fixture(temp)){
            assertEquals(Reason.KNOWLEDGE_REQUIRED,f.access.evaluateUse(f.learner,METHOD,f.f.scene.owner).reason());
            f.methods.available.put(METHOD,Reason.ARTIFACT_INCOMPATIBLE);
            assertEquals(Reason.ARTIFACT_INCOMPATIBLE,f.access.evaluateUse(f.learner,METHOD,f.f.scene.owner).reason());
            assertEquals(Reason.AUTHORITY_DENIED,f.access.evaluateUse(f.learner,METHOD,f.f.scene.foreign).reason());
        }
    }
    @Test void specificJobEligibilityCannotRecruitTeacherOrEscapeIntoOrdinaryExecution()throws Exception{
        try(var f=new Fixture(temp)){
            var job=f.start();var policy=f.access.policyForJob(job.id());
            assertTrue(policy.mayUse(f.learner,METHOD,f.f.scene.owner));assertFalse(policy.mayUse(f.teacher,METHOD,f.f.scene.owner));
            assertFalse(f.access.mayUse(f.learner,METHOD,f.f.scene.owner));
            assertFalse(f.access.policyForJob(UUID.randomUUID()).mayUse(f.learner,METHOD,f.f.scene.owner));
            job=f.assign(job);var e=job.current().executions().getFirst();var run=new RunCorrelation(e.runId(),METHOD,null);
            assertTrue(f.access.mayUseInRun(job.current().bound(),run,METHOD));
            assertFalse(f.access.mayUseInRun(job.current().bound(),new RunCorrelation(UUID.randomUUID(),METHOD,null),METHOD));
            assertFalse(f.access.mayUseInRun(job.current().bound(),run,NEXT));
            var altered=new ValidatedRequest(JobLifecycleStoreTest.request(f.learner,1),f.f.scene.owner,job.current().bound().observation());
            assertFalse(f.access.mayUseInRun(altered,run,METHOD));
        }
    }
    @Test void realJobReceiptsAndLedgerCompletionGrantOneReferenceAndPreserveAuthority()throws Exception{
        try(var f=new Fixture(temp)){
            var job=f.complete(f.assign(f.start()));var owners=f.f.scene.memory.state;
            assertEquals(Code.APPLIED,f.finish(job).code());assertEquals(Code.DUPLICATE,f.finish(job).code());
            assertTrue(f.access.mayUse(f.learner,METHOD,f.f.scene.owner));assertEquals(1,f.access.snapshot().grants().size());
            assertEquals(Stage.LEARNED,f.access.snapshot().practices().getFirst().stage());assertEquals(owners,f.f.scene.memory.state);
            assertFalse(f.access.mayUse(f.learner,METHOD,f.f.scene.foreign));
            var grant=f.access.snapshot().grants().getFirst();assertEquals(job.id(),grant.job());assertEquals(job.current().executions().getFirst().runId(),grant.run());
            assertEquals(new Enrollment(f.teacher,f.f.scene.owner),grant.teacher());
        }
    }
    @Test void pendingOwnerEvidenceCannotBeNarratedIntoACompletion()throws Exception{
        try(var f=new Fixture(temp)){
            var job=f.assign(f.start());assertEquals(Code.PENDING,f.finish(job).code());
            job=f.f.scene.report(job,2,ExecutionStatus.SUCCEEDED);f.observe(true);
            assertEquals(Code.PENDING,f.finish(job).code());assertTrue(f.access.snapshot().grants().isEmpty());
            AccomplishmentLedgerTest.record(f.ledger,job);assertEquals(Code.APPLIED,f.finish(job).code());
        }
    }
    @Test void forgedSuccessfulStatusWithoutPhysicalReceiptsCannotGrantKnowledge()throws Exception{
        try(var f=new Fixture(temp)){
            var job=f.complete(f.assign(f.start()));f.f.forged(job,a->AccomplishmentLedgerTest.attempt(a,List.of(),2,a.terminal()));
            assertEquals(Code.INVALID,f.finish(job).code());assertTrue(f.access.snapshot().grants().isEmpty());
        }
    }
    @Test void lossIsLatchedEvenIfTeacherReturnsBeforeJobCompletion()throws Exception{
        try(var f=new Fixture(temp)){
            var job=f.assign(f.start());f.observe(false);f.access.checkSupervision();
            f.observe(true);job=f.complete(job);assertEquals(Code.APPLIED,f.finish(job).code());
            assertEquals(Stage.STOPPED,f.access.snapshot().practices().getFirst().stage());assertFalse(f.access.mayUse(f.learner,METHOD,f.f.scene.owner));
        }
    }
    @ParameterizedTest @EnumSource(value=ExecutionStatus.class,names={"FAILED","CANCELLED","BLOCKED","INTERRUPTED"})
    void unsuccessfulPracticePreservesSpentResourcesWithoutPermanentLearning(ExecutionStatus outcome)throws Exception{
        try(var f=new Fixture(temp)){
            var job=f.assign(f.start());job=f.f.scene.report(job,1,outcome);f.observe(true);
            var before=f.f.scene.memory.state;assertEquals(Code.APPLIED,f.finish(job).code());
            assertEquals(Stage.STOPPED,f.access.snapshot().practices().getFirst().stage());assertTrue(f.access.snapshot().grants().isEmpty());assertEquals(before,f.f.scene.memory.state);
            assertEquals(1,job.current().effects());
        }
    }
    @Test void qualificationIsExactMethodEvidenceAndTeacherKnowledgeNeverConfersLearnerPermissions()throws Exception{
        try(var f=new Fixture(temp)){
            assertTrue(f.access.evaluateTeaching(f.teacher,f.learner,METHOD,f.f.scene.owner).allowed());
            assertFalse(f.access.evaluateTeaching(f.learner,f.teacher,METHOD,f.f.scene.owner).allowed());
            f.methods.available.put(NEXT,null);assertFalse(f.access.evaluateTeaching(f.teacher,f.learner,NEXT,f.f.scene.owner).allowed());
            f.observe(false);assertEquals(Reason.ACTOR_UNAVAILABLE,f.access.evaluateTeaching(f.teacher,f.learner,METHOD,f.f.scene.owner).reason());
        }
    }
    @Test void teacherControlRevocationAtPendingPrimitiveBoundaryFencesThatAttempt()throws Exception{
        try(var f=new Fixture(temp)){
            var job=f.assign(f.start());var state=f.f.citizens.get();var foreign=f.f.scene.foreign;
            f.f.citizens.set(new CitizenRegistry.Snapshot(state.worldId(),state.revision()+1,state.citizens().stream()
                    .map(c->c.actor().equals(f.teacher)?new CitizenRegistry.Citizen(c.actor(),foreign,c.displayName(),c.availability()):c).toList(),null));
            f.observe(true);var e=job.current().executions().getFirst();
            assertFalse(f.access.mayContinue(job.current().bound(),new RunCorrelation(e.runId(),METHOD,null),Set.of(METHOD)));
            assertEquals(Code.APPLIED,f.finish(job).code());assertTrue(f.access.snapshot().grants().isEmpty());
        }
    }
    @Test void crossPrincipalStartAndPrivateQueriesAreDenied()throws Exception{
        try(var f=new Fixture(temp)){
            var job=f.create();assertEquals(Code.DENIED,f.access.startApprenticeship(job.id(),f.teacher,METHOD,f.f.scene.foreign,()->false).code());
            assertThrows(SecurityException.class,()->f.access.grants(f.learner,f.f.scene.foreign,1));
        }
    }
    @Test void explicitCrossPrincipalTeachingRetainsTeacherOriginAndLearnerScope()throws Exception{
        try(var f=new Fixture(temp)){
            var old=f.f.citizens.get();f.f.citizens.set(new CitizenRegistry.Snapshot(old.worldId(),old.revision()+1,
                    old.citizens().stream().map(c->c.actor().equals(f.learner)?new CitizenRegistry.Citizen(c.actor(),f.f.scene.foreign,null,c.availability()):c).toList(),null));f.observe(true);
            assertEquals(Reason.AUTHORITY_DENIED,f.access.evaluateTeaching(f.teacher,f.learner,METHOD,f.f.scene.foreign).reason());
            f.access.close();f.access=f.open(Limits.defaults(),(teacher,learner,method)->teacher.owner().equals(f.f.scene.owner)&&learner.owner().equals(f.f.scene.foreign),List.of(),p->{});
            assertTrue(f.access.evaluateTeaching(f.teacher,f.learner,METHOD,f.f.scene.foreign).allowed());
            assertFalse(f.access.mayUse(f.learner,METHOD,f.f.scene.owner));
        }
    }
    @Test void committedGrantReloadAndTenThousandCompletionsDoNotGrowHistoryOrBodies()throws Exception{
        try(var f=new Fixture(temp)){
            var job=f.complete(f.assign(f.start()));assertEquals(Code.APPLIED,f.finish(job).code());
            var before=f.access.snapshot();var state=temp.resolve(KnowledgeAccessStore.WORLD_RELATIVE_PATH).resolve("state.jsonl");var bytes=Files.readAllBytes(state);
            f.access.close();f.access=f.open(Limits.defaults(),(t,l,m)->false,List.of(),p->{});
            for(int n=0;n<10000;n++)assertEquals(Code.DUPLICATE,f.finish(job).code());
            assertEquals(before,f.access.snapshot());assertArrayEquals(bytes,Files.readAllBytes(state));assertEquals(1,f.methods.available.size());
        }
    }
    @Test void restartOfPendingPracticeIsInterruptedWithoutReplayOrRetrospectiveGrant()throws Exception{
        try(var f=new Fixture(temp)){
            var job=f.complete(f.assign(f.start()));f.access.close();f.access=f.open(Limits.defaults(),(t,l,m)->false,List.of(),p->{});
            assertEquals(Code.APPLIED,f.finish(job).code());assertEquals(Stage.STOPPED,f.access.snapshot().practices().getFirst().stage());
            assertEquals(Reason.INTERRUPTED.name(),f.access.snapshot().practices().getFirst().stoppedReason());assertTrue(f.access.snapshot().grants().isEmpty());
        }
    }
    @Test void manyLearnersShareOneImmutableMethodWithBoundedReferenceStorageAndNoModelPort()throws Exception{
        try(var f=new Fixture(temp)){
            var first=f.complete(f.assign(f.start()));assertEquals(Code.APPLIED,f.finish(first).code());
            for(int n=0;n<15;n++){
                var another=JobLifecycleStoreTest.actor();f.enroll(another,f.f.scene.owner);
                UUID id=UUID.randomUUID();assertTrue(f.f.scene.store.create(id,id,JobLifecycleStoreTest.request(another,2),f.f.scene.owner,
                        JobLifecycleStoreTest.observation(),JobLifecycleStoreTest.limits(11000),List.of()).accepted());f.f.scene.settle();
                f.source.observe(f.f.citizens.get(),f.f.scene.memory.state,Set.of(new Pair(f.teacher,another)));
                assertEquals(Code.APPLIED,f.access.startApprenticeship(id,f.teacher,METHOD,f.f.scene.owner,()->false).code());
                var j=f.f.scene.store.query(id,f.f.scene.owner);j=f.f.scene.assign(j,another,j.allowance());
                j=f.f.scene.report(j,2,ExecutionStatus.SUCCEEDED);f.source.observe(f.f.citizens.get(),f.f.scene.memory.state,Set.of(new Pair(f.teacher,another)));
                AccomplishmentLedgerTest.record(f.ledger,j);assertEquals(Code.APPLIED,f.access.recordLearningOutcome(id,f.f.scene.owner,()->false).code());
            }
            assertEquals(16,f.access.snapshot().grants().size());assertEquals(1,f.methods.available.size());
            assertTrue(Files.size(temp.resolve(KnowledgeAccessStore.WORLD_RELATIVE_PATH).resolve("state.jsonl"))<65536);
        }
    }
    @Test void acquisitionRequiresRealSuccessfulOwnerEvidenceAndDoesNotDuplicateSharedAccess()throws Exception{
        try(var f=new Fixture(temp)){
            var job=f.complete(f.assign(f.create()));assertEquals(Code.APPLIED,f.access.recordAcquisition(job.id(),()->false).code());
            assertEquals(Origin.ACQUISITION,f.access.snapshot().grants().getFirst().origin());assertEquals(Code.DUPLICATE,f.access.recordAcquisition(job.id(),()->false).code());
        }
    }
    @Test void incompatibleRevisionNeverInheritsLearningAndExplicitCompatiblePolicyIsRequired()throws Exception{
        try(var f=new Fixture(temp)){
            var job=f.complete(f.assign(f.start()));f.finish(job);f.methods.available.put(NEXT,Reason.ARTIFACT_INCOMPATIBLE);
            assertEquals(Reason.ARTIFACT_INCOMPATIBLE,f.access.evaluateUse(f.learner,NEXT,f.f.scene.owner).reason());
            f.methods.available.put(NEXT,null);assertEquals(Reason.KNOWLEDGE_REQUIRED,f.access.evaluateUse(f.learner,NEXT,f.f.scene.owner).reason());
            assertEquals(Code.DENIED,f.access.migrateMethod(f.learner,METHOD,NEXT,f.f.scene.owner,()->false).code());
            f.access.close();f.access=f.open(Limits.defaults(),(t,l,m)->false,List.of(new VersionTransfer(METHOD,NEXT,"verified-compatible-v2")),p->{});
            assertEquals(Code.APPLIED,f.access.migrateMethod(f.learner,METHOD,NEXT,f.f.scene.owner,()->false).code());assertTrue(f.access.mayUse(f.learner,NEXT,f.f.scene.owner));
            assertEquals(Origin.COMPATIBLE_MIGRATION,f.access.snapshot().grants().getLast().origin());
        }
    }
    @Test void accessReferencesPinSharedBodiesJobsAndEvidenceBeforeUse()throws Exception{
        try(var f=new Fixture(temp)){
            var job=f.start();var pins=f.roots.snapshot().roots();assertTrue(pins.contains(WorldRetentionManager.artifact(METHOD)));
            assertTrue(pins.contains(new WorldRetentionManager.Key("jobs","records")));assertTrue(pins.contains(new WorldRetentionManager.Key("accomplishments","records")));
            assertEquals(Code.DUPLICATE,f.access.startApprenticeship(job.id(),f.teacher,METHOD,f.f.scene.owner,()->false).code());
        }
    }
    @Test void quotaRejectionAndCancelledPublicationLeaveExistingKnowledgeAndJobsUntouched()throws Exception{
        try(var f=new Fixture(temp)){
            var job=f.create();var before=f.access.snapshot();f.access.capacity(a->false);
            assertEquals(Code.LIMIT,f.access.startApprenticeship(job.id(),f.teacher,METHOD,f.f.scene.owner,()->false).code());assertEquals(before,f.access.snapshot());
            f.access.capacity(a->true);assertEquals(Code.CANCELLED,f.access.startApprenticeship(job.id(),f.teacher,METHOD,f.f.scene.owner,()->true).code());assertEquals(before,f.access.snapshot());
        }
    }
    @ParameterizedTest @ValueSource(ints={0,1})
    void activeAndHistoryLimitsHaveExactNAndOneOverNBoundaries(int maximum)throws Exception{
        try(var f=new Fixture(temp)){
            f.access.close();f.access=f.open(new Limits(512,maximum,maximum,1048576,8192),(t,l,m)->false,List.of(),p->{});
            for(int n=0;n<=maximum;n++){
                var job=f.create();assertEquals(n<maximum?Code.APPLIED:Code.LIMIT,f.access.startApprenticeship(job.id(),f.teacher,METHOD,f.f.scene.owner,()->false).code());
            }
            assertEquals(maximum,f.access.snapshot().practices().size());
        }
    }
    @ParameterizedTest @ValueSource(ints={0,1})
    void permanentGrantLimitDoesNotErasePracticeProof(int maximum)throws Exception{
        try(var f=new Fixture(temp)){
            f.access.close();f.access=f.open(new Limits(maximum,64,8,1048576,8192),(t,l,m)->false,List.of(),p->{});
            var job=f.complete(f.assign(f.start()));assertEquals(maximum==0?Code.LIMIT:Code.APPLIED,f.finish(job).code());
            assertEquals(maximum,f.access.snapshot().grants().size());
        }
    }
    @ParameterizedTest @EnumSource(KnowledgeAccessJournal.Point.class)
    void interruptedPublicationPreservesCommittedGrantOrInactiveOriginals(KnowledgeAccessJournal.Point point)throws Exception{
        try(var f=new Fixture(temp)){
            var fault=new AtomicBoolean();f.access.close();f.access=f.open(Limits.defaults(),(t,l,m)->false,List.of(),p->{if(fault.get()&&p==point)throw new IOException("fault");});
            var job=f.complete(f.assign(f.start()));fault.set(true);assertEquals(Code.UNAVAILABLE,f.finish(job).code());assertTrue(f.access.readOnly());
            f.access.close();f.access=f.open(Limits.defaults(),(t,l,m)->false,List.of(),p->{});
            if(point==KnowledgeAccessJournal.Point.AFTER_REPLACE){assertTrue(f.access.mayUse(f.learner,METHOD,f.f.scene.owner));assertEquals(Code.DUPLICATE,f.finish(job).code());}
            else assertTrue(f.access.snapshot().grants().isEmpty());
        }
    }
    @Test void unknownOrCorruptEnvelopeIsPreservedInactiveAndNeverOverwritten()throws Exception{
        try(var f=new Fixture(temp)){
            var state=temp.resolve(KnowledgeAccessStore.WORLD_RELATIVE_PATH).resolve("state.jsonl");f.access.close();
            var original=Files.readAllBytes(state);var future=new String(original,java.nio.charset.StandardCharsets.UTF_8).replace("\"schema\":1","\"schema\":9").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(state,future);f.access=f.open(Limits.defaults(),(t,l,m)->false,List.of(),p->{});assertTrue(f.access.readOnly());assertArrayEquals(future,Files.readAllBytes(state));
        }
    }
    @Test void writerOwnershipExcludesConcurrentWriterAndExternalReplacement()throws Exception{
        try(var f=new Fixture(temp)){
            assertThrows(IOException.class,()->f.open(Limits.defaults(),(t,l,m)->false,List.of(),p->{}));
            var state=temp.resolve(KnowledgeAccessStore.WORLD_RELATIVE_PATH).resolve("state.jsonl");Files.writeString(state,"changed");var job=f.create();
            assertEquals(Code.UNAVAILABLE,f.access.startApprenticeship(job.id(),f.teacher,METHOD,f.f.scene.owner,()->false).code());assertEquals("changed",Files.readString(state));
        }
    }
    @ParameterizedTest @ValueSource(ints={-1,513,Integer.MAX_VALUE})
    void invalidAndOverflowLimitsAreRejected(int maximum){assertThrows(IllegalArgumentException.class,()->new Limits(maximum,64,8,1048576,8192));}
    @Test void strictFormatRejectsStringCountersUnknownFieldsAndDuplicateRows()throws Exception{
        try(var f=new Fixture(temp)){
            byte[] valid=KnowledgeAccessCodec.encode(f.access.snapshot());assertEquals(f.access.snapshot(),KnowledgeAccessCodec.decode(valid,f.f.scene.world));
            var text=new String(valid,java.nio.charset.StandardCharsets.UTF_8);
            assertThrows(IOException.class,()->KnowledgeAccessCodec.decode(text.replace("\"revision\":1","\"revision\":\"1\"").getBytes(java.nio.charset.StandardCharsets.UTF_8),f.f.scene.world));
            assertThrows(IOException.class,()->KnowledgeAccessCodec.decode(text.replace("\"revision\":1","\"other\":1,\"revision\":1").getBytes(java.nio.charset.StandardCharsets.UTF_8),f.f.scene.world));
        }
    }
}
