package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static dev.aivillages.core.kernel.Jobs.*;
import static dev.aivillages.core.kernel.ExecutorJUnitTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real executor, exact immutable dependency closure, production orchestration and disk job owner. */
class JobExecutorIntegrationTest {
    @TempDir Path world;
    final class Scene implements AutoCloseable {
        final Fixture f = new Fixture();
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final JobJournal journal;
        final JobLifecycleStore jobs;
        final SkillArtifact artifact;
        final BootstrapController controller;
        BootstrapJournal.State markers;
        int starts, resolutions, models;
        boolean hold;
        Snapshot heldExpected, heldNext;
        CompletableFuture<Snapshot> held;
        Scene() throws Exception {
            var child = f.child(); artifact = f.root(child.descriptor().ref(), AdmissionStatus.ADMITTED);
            journal = JobJournal.open(world, OWNER.scope().worldId());
            jobs = new JobLifecycleStore(journal.snapshot(), (expected,next) -> {
                if (hold) { heldExpected=expected; heldNext=next; held=new CompletableFuture<>(); return held; }
                return write(expected,next);
            }, JobLifecycleStore.privateJobs((a,c)->a.equals(ACTOR)&&c.equals(OWNER)),
                    cancellation -> controllerCancel(cancellation), f.specs, JobLifecycleStoreTest.environment(),
                    f.clock, Settings.defaults(), false);
            markers = new BootstrapJournal.State(OWNER.scope().worldId(),0,
                    new BootstrapJournal.Enrollment(ACTOR,OWNER),List.of());
            var execution = new BootstrapController.Execution() {
                public List<ArtifactRef> pinned(ArtifactRef ref) { return JobArtifactPins.closure(artifact.descriptor(), dependency -> f.catalog.body(dependency).map(SkillArtifact::descriptor)); }
                public Start start(ValidatedRequest bound, ArtifactRef ref, UUID id, Budgets.ExecutionLimits limits, Budgets.Ledger usage) {
                    starts++; var job=jobs.bySubmission(id,OWNER).orElseThrow();
                    assertEquals(State.ACTIVE,job.state());
                    assertEquals(job.current().executions(),journal.snapshot().jobs().getLast().current().executions());
                    var start=f.executor.startAdmitted(bound,ref,new RunCorrelation(id,ref,null),limits,usage);
                    if(start instanceof BoundedSkillExecutor.Rejected rejected)return new Start(null,rejected.reason());
                    var run=((BoundedSkillExecutor.Started)start).run();
                    return new Start(new Handle(){
                        public BoundedSkillExecutor.Progress tick(TrustedContext c){return run.tick(c);}
                        public BoundedSkillExecutor.Progress progress(TrustedContext c){return run.progress(c);}
                        public BoundedSkillExecutor.Progress cancel(TrustedContext c){return run.cancel(c);}
                        public BoundedSkillExecutor.Progress interrupt(TrustedContext c){return run.interrupt(c);}
                    },null);
                }
            };
            controller=new BootstrapController(markers,(expected,enrollment,runs)->{
                assertEquals(markers,expected);
                markers=new BootstrapJournal.State(expected.worldId(),expected.revision()+1,enrollment,runs);
                return CompletableFuture.completedFuture(markers);
            },(request,actor)->new ObservationSnapshot(f.request.observation(),ObservationStatus.PRESENT,"source",1,1,Map.of("mature_wheat",5L)),
                    (bound,snapshot)->{
                        resolutions++;return new CapabilityResolver.Decision(new Resolution(ResolutionStatus.RESOLVED,null,
                                artifact.descriptor().ref(),snapshot.targetIdentity()),bound,Set.of(),snapshot,0,List.of(),1,0,true,false,null);
                    },(decision,bound,limits)->{models++;throw new AssertionError("Provider must remain unavailable");},
                    execution,JobLifecycleStoreTest.environment(),f.specs,
                    now->{var limits=f.limits(100,20,1000);var inference=new Budgets.Limits(Map.of(Budgets.Kind.CALLS,0L,
                            Budgets.Kind.INPUT_BYTES,1L,Budgets.Kind.OUTPUT_BYTES,1L),limits.total().deadlineEpochMillis());
                        var maximum=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);maximum.putAll(limits.total().maxima());
                        maximum.put(Budgets.Kind.INPUT_BYTES,1L);maximum.put(Budgets.Kind.OUTPUT_BYTES,1L);
                        return new Budgets.ResearchLimits(new Budgets.InferenceLimits(inference,1,1),limits,
                                new Budgets.Limits(maximum,limits.total().deadlineEpochMillis()));},
                    now->f.limits(100,20,1000),f.clock,null,jobs);
        }
        CompletionStage<Snapshot> write(Snapshot expected,Snapshot next){
            return CompletableFuture.supplyAsync(()->{try{return journal.replace(expected,next);}catch(Exception e){throw new CompletionException(e);}},worker);
        }
        void controllerCancel(Cancellation c){ if(controller!=null)controller.observeJobCancellation(c); }
        void release() throws Exception { hold=false;held.complete(write(heldExpected,heldNext).toCompletableFuture().get(5,TimeUnit.SECONDS)); }
        UUID submit(){var result=controller.submit(f.request.request(),OWNER);assertTrue(result.accepted(),String.valueOf(result.reason()));return result.id();}
        void tick() throws Exception {controller.tick();Thread.sleep(1);}
        void until(java.util.function.BooleanSupplier done) throws Exception {
            // Real fsync acknowledgements are asynchronous; a 1000 x 1ms polling window
            // raced the disk worker under CI load. Bound both wall time and tick work.
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            for(int n=0;n<5000&&!done.getAsBoolean()&&System.nanoTime()<deadline;n++)tick();
            assertTrue(done.getAsBoolean(),()->"Durable owner did not acknowledge: ready="+jobs.ready()
                    +" states="+jobs.snapshot().jobs().stream().map(Job::state).toList());
        }
        Job job(UUID id){return jobs.bySubmission(id,OWNER).orElseThrow();}
        boolean terminal(UUID id){return controller.status(id,OWNER).phase()==BootstrapController.Phase.TERMINAL;}
        public void close() throws Exception {worker.shutdown();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));journal.close();}
    }
    @Test void realExecutorCompletionIsDurableAndDuplicateResultsDoNotMultiplyCredit() throws Exception {
        try(var s=new Scene()){
            UUID id=s.submit();s.until(()->s.terminal(id));Job job=s.job(id);
            assertEquals(State.SUCCEEDED,job.state());assertEquals(1,job.fulfilled());assertEquals(3,job.effects());
            assertEquals(2,s.jobs.protectedRoots().artifacts().size());assertEquals(0,s.models);
            var a=job.current();var report=new Report(a.id(),a.generation(),a.effects(),a.usage(),a.receipts(),a.terminal());
            assertEquals(Code.DUPLICATE,s.jobs.recordExecutionResult(job.id(),job.guard(),report,OWNER).code());
            assertEquals(job,s.journal.snapshot().jobs().getFirst());
        }
    }
    @Test void withheldCreationAckCannotResolveOrExecuteAndQueuedCancelStopsBeforeStart() throws Exception {
        try(var s=new Scene()){
            s.hold=true;UUID id=s.submit();for(int n=0;n<20;n++)s.tick();
            assertEquals(0,s.resolutions);assertEquals(0,s.starts);assertTrue(s.f.gateway.calls.isEmpty());
            s.controller.cancel(id,OWNER);s.release();s.until(()->s.terminal(id));
            assertEquals(State.CANCELLED,s.job(id).state());assertEquals(0,s.starts);assertEquals(0,s.models);
        }
    }
    @Test void delayedAssignmentAckPinsClosureBeforeAnyEffect() throws Exception {
        try(var s=new Scene()){
            UUID id=s.submit();s.until(()->!s.jobs.snapshot().jobs().isEmpty());
            s.hold=true;s.until(()->s.held!=null);
            assertEquals(0,s.starts);assertTrue(s.f.gateway.calls.isEmpty());
            assertEquals(2,s.jobs.protectedRoots().artifacts().size());s.release();s.until(()->s.terminal(id));
            assertEquals(State.SUCCEEDED,s.job(id).state());
        }
    }
    @Test void actualExecutorCancellationRequiresDurableIntentAndPreservesOwnerEffects() throws Exception {
        try(var s=new Scene()){
            s.f.gateway.stall=true;s.f.gateway.cancelPartial=true;
            UUID id=s.submit();s.until(()->!s.f.gateway.calls.isEmpty());
            s.controller.cancel(id,OWNER);assertEquals(State.ACTIVE,s.job(id).state());
            s.until(()->s.terminal(id));assertEquals(State.CANCELLED,s.job(id).state());
            assertEquals(1,s.job(id).effects());assertEquals(0,s.job(id).fulfilled());assertEquals(1,s.f.gateway.cancellation);
            assertTrue(s.job(id).current().cancellationDeliveries()>0);
        }
    }
    @Test void reloadOfLiveRealExecutorRetainsClosureAndNeverRestartsTheOldRun() throws Exception {
        Snapshot saved;ArtifactRef ref;UUID id;
        try(var s=new Scene()){
            s.f.gateway.stall=true;id=s.submit();s.until(()->!s.f.gateway.calls.isEmpty());
            saved=s.journal.snapshot();ref=s.artifact.descriptor().ref();
        }
        try(var journal=JobJournal.open(world,OWNER.scope().worldId())){
            assertEquals(saved,journal.snapshot());
            var store=new JobLifecycleStore(saved,(expected,next)->{
                try{return CompletableFuture.completedFuture(journal.replace(expected,next));}
                catch(Exception e){return CompletableFuture.failedFuture(e);}
            },JobLifecycleStore.privateJobs((a,c)->true),c->{throw new AssertionError("No replay or implicit cancel");},
                    capability->Optional.of(CropDelivery.SPEC),JobLifecycleStoreTest.environment(),new TestClock(),Settings.defaults(),false);
            store.tick();var job=store.bySubmission(id,OWNER).orElseThrow();
            assertEquals(State.INTERRUPTED,job.state());assertTrue(job.current().uncertain());assertEquals(0,job.fulfilled());
            assertTrue(store.protectedRoots().artifacts().contains(ref));assertTrue(store.readyJobs(OWNER,0).jobs().isEmpty());
        }
    }
}
