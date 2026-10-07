package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Jobs.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual job and identity owners; asynchronous policy seams exercise the dispatcher boundary. */
class WorkerDispatcherTest {
    static final UUID WORLD = new UUID(0, 1);
    static final TrustedContext OWNER = context(10), OTHER = context(11);
    static final ArtifactRef REF = new ArtifactRef(CropDelivery.ID, "a".repeat(64));
    static TrustedContext context(int n) { return new TrustedContext(new PrincipalRef(new UUID(0,n)), new ScopeRef(WORLD,new UUID(0,n))); }
    static ActorRef actor(int n) { return new ActorRef(new UUID(0,n), new UUID(1,n), "minecraft:overworld"); }
    static class Scene {
        final JobLifecycleStoreTest.TestClock clock = new JobLifecycleStoreTest.TestClock();
        final JobLifecycleStoreTest.Memory disk = new JobLifecycleStoreTest.Memory(WORLD);
        final JobLifecycleStore jobs;
        final CitizenRegistry citizens;
        final List<ActorRef> workers = new ArrayList<>();
        final Map<UUID, Handle> handles = new LinkedHashMap<>();
        final Set<UUID> claims = new HashSet<>();
        final Map<UUID, Integer> starts = new HashMap<>();
        WorkerDispatcher dispatcher;
        long tick;
        boolean control=true, inspect=true, stopConfirmed=true, released=true, deferClaims, automatic=true, observationFails;
        int prepares;
        Reason leaseFailure, startFailure;
        ResolutionStatus resolution=ResolutionStatus.RESOLVED;
        Runnable afterResolve=()->{};
        Scene(int count) {this(count,false);}
        Scene(int count,boolean reverse) {this(count,reverse,false);}
        Scene(int count,boolean reverse,boolean shareAddresses) {
            var rows=new ArrayList<CitizenRegistry.Citizen>();
            for(int n=0;n<count;n++) { var a=actor(100+n);workers.add(a);rows.add(new CitizenRegistry.Citizen(a,OWNER,null,CitizenRegistry.Availability.UNKNOWN)); }
            rows.add(new CitizenRegistry.Citizen(actor(200),OTHER,"private",CitizenRegistry.Availability.UNKNOWN));
            if(reverse)Collections.reverse(rows);
            citizens=new CitizenRegistry(new CitizenRegistry.Snapshot(WORLD,0,rows,null),
                    (expected,next)->CompletableFuture.completedFuture(next),(caller,citizen)->shareAddresses,clock,false);
            for(var a:workers){citizens.observeAvailability(a.citizenId(),CitizenRegistry.Availability.LOADED);citizens.tick();}
            citizens.observeAvailability(actor(200).citizenId(),CitizenRegistry.Availability.LOADED);citizens.tick();
            jobs=new JobLifecycleStore(disk.state,disk,new JobLifecycleStore.Policy(){
                        public boolean mayRead(TrustedContext c,Job j){return inspect&&j.origin().equals(c);}
                        public boolean mayControl(TrustedContext c,Job j){return j.origin().equals(c);}
                        public boolean controls(ActorRef a,TrustedContext c){return control&&citizens.controls(a,c);}
                    },
                    c->{},id->Optional.of(CropDelivery.SPEC),JobLifecycleStoreTest.environment(),clock,Jobs.Settings.defaults(),false);
            dispatcher=createDispatcher();
        }
        WorkerDispatcher createDispatcher() {
            return new WorkerDispatcher(jobs,citizens,(request,worker)->observation(request),
                    (bound,observed)-> {
                        var status=resolution;
                        var reason=switch(status){case RESOLVED,MISSING_IMPLEMENTATION,NEEDS_PLANNING->null;
                            case UNAUTHORIZED->Reason.AUTHORITY_DENIED;case UNSUPPORTED_RUNTIME->Reason.UNSUPPORTED_PRIMITIVE;
                            case INCOMPATIBLE->Reason.ARTIFACT_INCOMPATIBLE;case BLOCKED->Reason.RESOURCE_MISSING;};
                        afterResolve.run();
                        return new CapabilityResolver.Decision(new Resolution(status,reason,status==ResolutionStatus.RESOLVED?REF:null,"fixture"),
                                status==ResolutionStatus.RESOLVED?bound:null,Set.of(),observed,1,List.of(),1,1,true,false,null);
                    },new WorkerDispatcher.ExecutionPort(){
                        public List<ArtifactRef> pinned(ArtifactRef ref){return List.of(ref);}
                        public WorkerDispatcher.Start start(ValidatedRequest bound,ArtifactRef ref,UUID run,Budgets.ExecutionLimits limit,Budgets.Ledger usage){
                            assertTrue(claims.contains(run));
                            var job=jobs.snapshot().jobs().stream().filter(j->j.current()!=null&&j.current().id().equals(run)).findFirst().orElseThrow();
                            assertEquals(job,disk.state.jobs().stream().filter(j->j.id().equals(job.id())).findFirst().orElseThrow());
                            if(startFailure!=null)return new WorkerDispatcher.Start(null,startFailure);
                            starts.merge(run,1,Integer::sum);var h=new Handle(job,bound,usage);handles.put(run,h);return new WorkerDispatcher.Start(h,null);
                        }
                    },new WorkerDispatcher.Claims(){
                        public ResourceLeases.Result prepare(ValidatedRequest request,RunCorrelation run){
                            prepares++;
                            if(leaseFailure!=null)return new ResourceLeases.Result(ResourceLeases.Code.CONFLICT,leaseFailure,null,List.of(),0,1);
                            if(deferClaims)return new ResourceLeases.Result(ResourceLeases.Code.PENDING,null,run.runId(),List.of(),0,1);
                            claims.add(run.runId());return new ResourceLeases.Result(ResourceLeases.Code.GRANTED,null,run.runId(),List.of(),0,1);
                        }
                        public ResourceLeases.Result validate(ValidatedRequest request,RunCorrelation run){return prepare(request,run);}
                        public void release(UUID run){if(released)claims.remove(run);}
                        public boolean released(UUID run){return released&&!claims.contains(run);}
                    },job->true,clock,()->tick,WorkerDispatcher.Settings.fixture());
        }
        ObservationSnapshot observation(CapabilityRequest request){if(observationFails)throw new IllegalStateException("Observation no longer available");return new ObservationSnapshot(JobLifecycleStoreTest.observation(),ObservationStatus.PRESENT,"fixture",tick,1,Map.of("mature_wheat",20L));}
        Job add(int id,int amount) { return add(id,amount,OWNER,workers.getFirst()); }
        Job add(int id,int amount,TrustedContext owner,ActorRef responsible) {
            var uuid=new UUID(2,id);var request=JobLifecycleStoreTest.request(responsible,amount);
            var limits=JobLifecycleStoreTest.limits(clock.now+10000);
            assertTrue(jobs.create(uuid,uuid,request,owner,JobLifecycleStoreTest.observation(),limits,List.of()).accepted());jobs.tick();
            return jobs.query(uuid,owner);
        }
        Job current(Job job){return jobs.query(job.id(),job.origin());}
        WorkerDispatcher.Slice step(){return step(OWNER);}
        WorkerDispatcher.Slice step(TrustedContext caller){tick++;clock.now++;citizens.tick();jobs.tick();return dispatcher.step(caller);}
        void until(java.util.function.BooleanSupplier done){for(int n=0;n<600&&!done.getAsBoolean();n++)step();assertTrue(done.getAsBoolean());}
        class Handle implements WorkerDispatcher.Handle {
            final Job job;final ValidatedRequest bound;final Budgets.Ledger usage;
            BoundedSkillExecutor.Progress progress;
            long delivered;
            Handle(Job job,ValidatedRequest bound,Budgets.Ledger usage){this.job=job;this.bound=bound;this.usage=usage;set(null);}
            void set(ExecutionStatus status){
                var a=job.current();var receipts=JobLifecycleStoreTest.receipts(a,delivered);
                var outcome=status==null?null:new Outcomes.Execution(status,JobLifecycleStoreTest.reason(status),delivered*3,
                        status==ExecutionStatus.SUCCEEDED?new EvidenceRef(a.id().toString(),"crop-delivery:1",OWNER.scope().domainId().toString()):null);
                var summary=new BoundedSkillExecutor.Summary(a.id(),REF,null,List.of(REF),delivered*3,receipts,outcome,usage.snapshot());
                progress=new BoundedSkillExecutor.Progress(status==null?BoundedSkillExecutor.Phase.RUNNING:BoundedSkillExecutor.Phase.TERMINAL,summary,List.of());
            }
            public BoundedSkillExecutor.Progress tick(TrustedContext owner){if(progress.summary().outcome()==null&&automatic){delivered=((IntValue)bound.request().arguments().get("amount")).value();set(ExecutionStatus.SUCCEEDED);}return progress;}
            public BoundedSkillExecutor.Progress progress(TrustedContext owner){return progress;}
            public BoundedSkillExecutor.Progress cancel(TrustedContext owner){if(progress.summary().outcome()==null)set(ExecutionStatus.CANCELLED);return progress;}
            public BoundedSkillExecutor.Progress interrupt(TrustedContext owner){if(progress.summary().outcome()==null)set(ExecutionStatus.INTERRUPTED);return progress;}
            public boolean stopped(TrustedContext owner){return stopConfirmed&&progress.summary().outcome()!=null;}
        }
    }
    @Test void oneDurableAssignmentValidClaimPinnedRunAndOrigin(){var s=new Scene(2);var j=s.add(1,3);s.until(()->s.current(j).state()==State.SUCCEEDED);var result=s.current(j);assertEquals(3,result.fulfilled());assertEquals(1,result.attempts().size());assertEquals(OWNER,result.current().bound().context());assertEquals(1,s.starts.values().stream().mapToInt(n->n).sum());}
    @Test void deterministicRoundRobinSelectsStableUuidWorker(){for(int repeat=0;repeat<3;repeat++){var s=new Scene(3,repeat%2==1);var j=s.add(1,1);s.until(()->!s.starts.isEmpty());assertEquals(actor(100),s.current(j).current().worker());}}
    @Test void observationFailureAfterAssignmentClosesSafelyWithoutAnyExecutorStart(){var s=new Scene(1);var j=s.add(1,1);s.step();s.step();assertNotNull(s.disk.state.jobs().getFirst().current());s.observationFails=true;for(int n=0;n<30;n++)assertDoesNotThrow(()->{s.step();});assertTrue(s.starts.isEmpty());assertTrue(s.claims.isEmpty());assertFalse(s.current(j).current().open());}
    @Test void delayedAssignmentPublicationRetainsItsProposalAndStartsOnlyAfterAcknowledgement(){var s=new Scene(1);var j=s.add(1,1);s.step();s.disk.hold=true;s.step();assertNotNull(s.disk.held);for(int n=0;n<6;n++)s.step();assertTrue(s.starts.isEmpty());assertTrue(s.claims.isEmpty());assertNull(s.current(j).current());s.disk.release();s.until(()->s.current(j).state()==State.SUCCEEDED);assertEquals(1,s.starts.size());assertEquals(1,s.current(j).attempts().size());}
    @Test void terminalHandleNeverReacquiresClaimsDuringReleaseAcknowledgement(){var s=new Scene(1);var j=s.add(1,1);s.until(()->!s.handles.isEmpty());s.released=false;s.step();int prepares=s.prepares;for(int n=0;n<15;n++)s.step();assertEquals(prepares,s.prepares);assertEquals(1,s.starts.size());assertTrue(s.current(j).current().open());s.released=true;s.until(()->s.current(j).state()==State.SUCCEEDED);}
    @Test void revokedReadAccessStillInterruptsTheActualExecutionOwner(){var s=new Scene(1);s.automatic=false;var j=s.add(1,1);s.until(()->!s.handles.isEmpty());s.inspect=false;s.step();var handle=s.handles.values().iterator().next();assertEquals(ExecutionStatus.INTERRUPTED,handle.progress.summary().outcome().status());assertTrue(s.claims.isEmpty());s.inspect=true;s.until(()->s.current(j).current()!=null&&!s.current(j).current().open());}
    @Test void twelveJobsSixCitizensStayBoundedAndEveryJobGetsAVisit()throws java.io.IOException {
        var s=new Scene(6);var rows=new ArrayList<Job>();
        for(int n=0;n<12;n++)rows.add(s.add(n,1));
        int steps=0,maxJobs=0,maxWorkers=0,maxStarts=0,maxRetries=0;
        while(rows.stream().anyMatch(j->s.current(j).state()!=State.SUCCEEDED)&&steps<200){
            var slice=s.step();maxJobs=Math.max(maxJobs,slice.jobsInspected());maxWorkers=Math.max(maxWorkers,slice.workersInspected());
            maxStarts=Math.max(maxStarts,slice.starts());maxRetries=Math.max(maxRetries,slice.retries());
            assertTrue(slice.jobsInspected()<=4);assertTrue(slice.workersInspected()<=3);assertTrue(slice.starts()<=2);assertTrue(slice.retries()<=2);steps++;
        }
        int bound=WorkerDispatcher.fairnessBound(12,6,WorkerDispatcher.Settings.fixture());
        assertTrue(steps<=bound);assertTrue(rows.stream().allMatch(j->s.current(j).state()==State.SUCCEEDED));
        var path=java.nio.file.Path.of("build/dispatch-evidence/scale.json");java.nio.file.Files.createDirectories(path.getParent());
        java.nio.file.Files.writeString(path,StrictJson.canonical(Map.of("schema",1L,"jobs",12L,"citizens",6L,
                "ticks",(long)steps,"fairnessBound",(long)bound,"maxJobs",(long)maxJobs,"maxWorkers",(long)maxWorkers,
                "maxStarts",(long)maxStarts,"maxRetries",(long)maxRetries,"assignmentTicks",20L))+"\n");
    }
    @ParameterizedTest @EnumSource(CitizenRegistry.Availability.class) void availabilityIsNeverAssumed(CitizenRegistry.Availability availability){var s=new Scene(1);var j=s.add(1,1);s.citizens.observeAvailability(s.workers.getFirst().citizenId(),availability);s.citizens.tick();for(int n=0;n<15;n++)s.step();assertEquals(availability==CitizenRegistry.Availability.LOADED?1:0,s.starts.size());}
    @ParameterizedTest @EnumSource(ResolutionStatus.class) void allSevenResolutionDecisionsSurvive(ResolutionStatus outcome){var s=new Scene(1);s.resolution=outcome;var j=s.add(1,1);for(int n=0;n<15;n++)s.step();assertEquals(outcome==ResolutionStatus.RESOLVED?1:0,s.starts.size());assertEquals(outcome,s.dispatcher.diagnostics(j.id(),OWNER).getFirst().routing().status());}
    @Test void explicitPublicAddressSharingNeverRecruitsAnotherPrincipalsCitizen(){
        var s=new Scene(1,false,true);var j=s.add(1,1);
        assertEquals(CitizenRegistry.AddressStatus.FOUND,s.citizens.address(actor(200).citizenId(),OWNER).status());
        assertFalse(s.citizens.controls(actor(200),OWNER));
        s.citizens.observeAvailability(s.workers.getFirst().citizenId(),CitizenRegistry.Availability.UNLOADED);s.citizens.tick();
        for(int n=0;n<12;n++)s.step();assertTrue(s.starts.isEmpty());assertEquals(OWNER,s.current(j).origin());
    }
    @Test void foreignCitizenAndDiagnosticsArePrivate(){var s=new Scene(1);var j=s.add(1,1);s.citizens.observeAvailability(s.workers.getFirst().citizenId(),CitizenRegistry.Availability.UNLOADED);s.citizens.tick();for(int n=0;n<12;n++)s.step();assertTrue(s.starts.isEmpty());assertThrows(SecurityException.class,()->s.dispatcher.diagnostics(j.id(),OTHER));assertTrue(s.step(OTHER).decisions().isEmpty());}
    @Test void revokedAuthorityBetweenRankingAndCommitCannotStart(){var s=new Scene(1);var j=s.add(1,1);s.afterResolve=()->s.control=false;for(int n=0;n<12;n++)s.step();assertTrue(s.starts.isEmpty());assertTrue(s.claims.isEmpty());}
    @Test void twoDispatchersCannotReserveOneWorkerTwice(){var s=new Scene(1);var a=s.add(1,1);var b=s.add(2,1);var second=s.createDispatcher();for(int n=0;n<100;n++){s.step();second.step(OWNER);}assertTrue(s.starts.values().stream().allMatch(n->n==1));assertEquals(1,s.current(a).attempts().size());assertEquals(1,s.current(b).attempts().size());}
    @Test void pendingLeaseAndCancellationNeverResurrectADeferredStart(){var s=new Scene(1);s.deferClaims=true;var j=s.add(1,1);s.until(()->s.current(j).state()==State.ACTIVE);var current=s.current(j);assertTrue(s.jobs.cancel(current.id(),current.guard(),OWNER).accepted());s.jobs.tick();s.deferClaims=false;for(int n=0;n<20;n++)s.step();assertTrue(s.starts.isEmpty());assertTrue(s.claims.isEmpty());assertEquals(State.CANCELLED,s.current(j).state());}
    @Test void leaseConflictClosesAttemptAndReconsiderationConsumesOriginalBudget(){var s=new Scene(1);s.leaseFailure=Reason.TARGET_UNAVAILABLE;var j=s.add(1,1);for(int n=0;n<120;n++)s.step();assertTrue(s.starts.isEmpty());assertTrue(s.claims.isEmpty());assertTrue(s.current(j).attempts().size()<=8);assertTrue(s.current(j).usage().getOrDefault(Budgets.Kind.INSTRUCTIONS,0L)>0);}
    @Test void rejectedExecutorStartLeavesNoOpenAttemptOrClaims(){var s=new Scene(1);s.startFailure=Reason.ARTIFACT_INCOMPATIBLE;var j=s.add(1,1);for(int n=0;n<50;n++)s.step();assertTrue(s.starts.isEmpty());assertTrue(s.claims.isEmpty());assertFalse(s.current(j).current().open());}
    @Test void disappearanceRetainsTwoOfFiveAndReassignsOnlyThreeAfterConfirmedStop(){var s=new Scene(2);s.automatic=false;var j=s.add(1,5);s.until(()->!s.handles.isEmpty());var old=s.handles.values().iterator().next();old.delivered=2;old.set(null);s.stopConfirmed=false;s.citizens.observeAvailability(old.job.current().worker().citizenId(),CitizenRegistry.Availability.UNLOADED);s.citizens.tick();for(int n=0;n<25;n++)s.step();assertEquals(1,s.starts.size());s.stopConfirmed=true;s.automatic=true;s.until(()->s.current(j).state()==State.SUCCEEDED);assertEquals(5,s.current(j).fulfilled());assertEquals(3,((IntValue)s.current(j).current().bound().request().arguments().get("amount")).value());var current=s.current(j);var report=new Report(old.job.current().id(),old.job.generation(),old.progress.summary().committedEffects(),old.usage.snapshot(),old.progress.summary().receipts(),old.progress.summary().outcome());assertEquals(Code.DUPLICATE,s.jobs.recordExecutionResult(current.id(),current.guard(),report,OWNER).code());assertEquals(current,s.current(j));}
    @Test void assignmentTimeoutDoesNotResetDeadlineOrOverlapUnconfirmedStop(){var s=new Scene(1);s.automatic=false;s.stopConfirmed=false;var j=s.add(1,1);s.until(()->!s.starts.isEmpty());for(int n=0;n<50;n++)s.step();assertEquals(1,s.starts.size());assertTrue(s.current(j).current().open());}
    @Test void disposablePolicyCacheDoesNotEraseJobsClaimsOrLiveAttempts(){var s=new Scene(2);s.automatic=false;var a=s.add(1,1);s.until(()->!s.starts.isEmpty());var before=s.jobs.snapshot();s.dispatcher.clearPolicyCache();assertEquals(before,s.jobs.snapshot());for(int n=0;n<3;n++)s.step();assertEquals(1,s.starts.size());}
    @Test void ownerPublicationIsAsyncAndNoStartPrecedesAssignmentAcknowledgement(){var s=new Scene(1);var j=s.add(1,1);s.disk.hold=true;for(int n=0;n<5;n++)s.step();assertTrue(s.starts.isEmpty());assertTrue(s.claims.isEmpty());assertNotNull(s.disk.held);s.disk.release();s.until(()->s.current(j).state()==State.SUCCEEDED);}

    @ParameterizedTest @ValueSource(ints={0,5,Integer.MAX_VALUE}) void jobSliceRejectsZeroOversizeAndOverflow(int value){assertThrows(IllegalArgumentException.class,()->new WorkerDispatcher.Settings(value,3,2,2,8,16,20,10000));}
    @ParameterizedTest @ValueSource(ints={0,4,Integer.MAX_VALUE}) void workerSliceRejectsZeroOversizeAndOverflow(int value){assertThrows(IllegalArgumentException.class,()->new WorkerDispatcher.Settings(4,value,2,2,8,16,20,10000));}
    @ParameterizedTest @ValueSource(ints={0,3,Integer.MAX_VALUE}) void startSliceRejectsZeroOversizeAndOverflow(int value){assertThrows(IllegalArgumentException.class,()->new WorkerDispatcher.Settings(4,3,value,2,8,16,20,10000));}
    @ParameterizedTest @ValueSource(ints={0,3,Integer.MAX_VALUE}) void conflictRetryRejectsZeroOversizeAndOverflow(int value){assertThrows(IllegalArgumentException.class,()->new WorkerDispatcher.Settings(4,3,2,value,8,16,20,10000));}
    @ParameterizedTest @ValueSource(ints={0,9,Integer.MAX_VALUE}) void liveHandlesRejectZeroOversizeAndOverflow(int value){assertThrows(IllegalArgumentException.class,()->new WorkerDispatcher.Settings(4,3,2,2,value,16,20,10000));}
    @ParameterizedTest @ValueSource(ints={0,17,Integer.MAX_VALUE}) void evidenceRejectsZeroOversizeAndOverflow(int value){assertThrows(IllegalArgumentException.class,()->new WorkerDispatcher.Settings(4,3,2,2,8,value,20,10000));}
    @ParameterizedTest @ValueSource(longs={0,1201,Long.MAX_VALUE}) void timeoutRejectsZeroOversizeAndOverflow(long value){assertThrows(IllegalArgumentException.class,()->new WorkerDispatcher.Settings(4,3,2,2,8,16,value,10000));}
    @ParameterizedTest @ValueSource(longs={0,15001,Long.MAX_VALUE}) void actionDeadlineRejectsZeroOversizeAndOverflow(long value){assertThrows(IllegalArgumentException.class,()->new WorkerDispatcher.Settings(4,3,2,2,8,16,20,value));}
    @Test void documentedMaximumSettingsAndFairnessCohortAreFinite(){assertEquals(1200,WorkerDispatcher.Settings.production().assignmentTicks());assertEquals(4,WorkerDispatcher.Settings.fixture().jobs());assertTrue(WorkerDispatcher.fairnessBound(64,64,WorkerDispatcher.Settings.production())<100000);assertThrows(IllegalArgumentException.class,()->WorkerDispatcher.fairnessBound(65,1,WorkerDispatcher.Settings.fixture()));}
    @Test void lastWorkerPageCannotHideAnEligibleWorkerFromLaterJobs(){var s=new Scene(6);for(int n=0;n<5;n++){s.citizens.observeAvailability(s.workers.get(n).citizenId(),CitizenRegistry.Availability.UNLOADED);s.citizens.tick();}var jobs=new ArrayList<Job>();for(int n=0;n<12;n++)jobs.add(s.add(n,1));s.until(()->jobs.stream().allMatch(j->s.current(j).state()==State.SUCCEEDED));assertTrue(jobs.stream().allMatch(j->s.current(j).current().worker().equals(actor(105))));}
    @Test void busyWorkerDoesNotReceiveASecondConcurrentJob(){var s=new Scene(1);s.automatic=false;var a=s.add(1,1);var b=s.add(2,1);for(int n=0;n<18;n++)s.step();assertEquals(1,s.starts.size());assertEquals(1,s.jobs.snapshot().jobs().stream().flatMap(j->j.attempts().stream()).filter(Attempt::open).count());}
    @Test void aCitizenPublicationAfterSelectionFencesTheOldGeneration(){var s=new Scene(1);var j=s.add(1,1);boolean[] changed={false};s.afterResolve=()->{if(!changed[0]){changed[0]=true;s.citizens.rename(actor(100).citizenId(),"updated",OWNER);}};s.step();s.step();assertTrue(s.starts.isEmpty());assertTrue(s.claims.isEmpty());assertEquals(0,s.current(j).generation());}
    @Test void cancellationAfterChargeBeforeAssignmentCommitsNoRun(){var s=new Scene(1);var j=s.add(1,1);s.step();s.jobs.tick();var c=s.current(j);assertTrue(s.jobs.cancel(c.id(),c.guard(),OWNER).accepted());for(int n=0;n<8;n++)s.step();assertTrue(s.starts.isEmpty());assertTrue(s.claims.isEmpty());assertEquals(State.CANCELLED,s.current(j).state());}
    @Test void failedPublicationCannotBeTreatedAsAnAssignmentAck(){var s=new Scene(1);var j=s.add(1,1);s.disk.fail=true;for(int n=0;n<8;n++)s.step();assertEquals(Reason.STORAGE_UNAVAILABLE,s.jobs.unavailableReason());assertTrue(s.starts.isEmpty());assertTrue(s.claims.isEmpty());assertEquals(0,s.current(j).generation());}
    @Test void pendingResourceReleaseKeepsTheWorkerReserved(){var s=new Scene(1);s.released=false;var a=s.add(1,1);var b=s.add(2,1);for(int n=0;n<15;n++)s.step();assertEquals(1,s.starts.size());assertTrue(s.current(a).current().open());s.released=true;s.until(()->s.current(a).state()==State.SUCCEEDED&&s.current(b).state()==State.SUCCEEDED);assertEquals(2,s.starts.size());}
    @Test void repeatedCallsCannotResetTheSameTickSlice(){var s=new Scene(1);s.add(1,1);s.step();var snapshot=s.jobs.snapshot();var second=s.dispatcher.step(OWNER);assertEquals(0,second.jobsInspected());assertEquals(0,second.starts());assertEquals(snapshot,s.jobs.snapshot());}
    @Test void shutdownStopsExistingWorkAndDoesNotAdmitQueuedJobs(){var s=new Scene(2);s.automatic=false;var a=s.add(1,1);s.until(()->!s.starts.isEmpty());s.dispatcher.close();var b=s.add(2,1);for(int n=0;n<30;n++)s.step();assertEquals(1,s.starts.size());assertEquals(State.READY,s.current(b).state());}
    @Test void privateCircularOwnerPagesNeverContainTheOtherPrincipal(){var s=new Scene(6);var foreign=s.add(20,1,OTHER,actor(200));for(int n=0;n<12;n++)s.add(n,1);UUID after=null;var visited=new HashSet<UUID>();for(int n=0;n<5;n++){var page=s.jobs.dispatchPage(OWNER,after,4);assertEquals(12,page.total());assertTrue(page.jobs().stream().allMatch(j->j.origin().equals(OWNER)));page.jobs().forEach(j->visited.add(j.id()));after=page.jobs().getLast().id();}assertEquals(12,visited.size());assertFalse(visited.contains(foreign.id()));assertEquals(1,s.jobs.dispatchPage(OTHER,null,4).total());assertThrows(IllegalArgumentException.class,()->s.jobs.dispatchPage(OWNER,null,5));assertThrows(IllegalArgumentException.class,()->s.citizens.controlPage(OWNER,null,4));}
    @Test void selectionCanBeRebuiltButARecoveredOpenAttemptCannotBeStarted(){var s=new Scene(1);s.automatic=false;var j=s.add(1,5);s.until(()->!s.starts.isEmpty());var initial=s.jobs.snapshot();var restored=new JobLifecycleStore(initial,s.disk,JobLifecycleStore.privateJobs((a,c)->s.citizens.controls(a,c)),c->{},id->Optional.of(CropDelivery.SPEC),JobLifecycleStoreTest.environment(),s.clock,Jobs.Settings.defaults(),false);restored.tick();var recovered=restored.query(j.id(),OWNER);assertEquals(State.INTERRUPTED,recovered.state());assertTrue(recovered.current().uncertain());assertEquals(initial.jobs().getFirst().current().executions(),recovered.current().executions());assertTrue(restored.workerAvailable(actor(100),OWNER)==false);}
}
