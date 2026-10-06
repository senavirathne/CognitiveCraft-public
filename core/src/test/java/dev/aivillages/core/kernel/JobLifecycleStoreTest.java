package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Jobs.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static org.junit.jupiter.api.Assertions.*;

final class JobLifecycleStoreTest {
    static final ArtifactRef REF = new ArtifactRef(CropDelivery.ID,"a".repeat(64));
    static final class TestClock extends Clock {
        long now = 1_000;
        public ZoneId getZone() { return ZoneId.of("UTC"); }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
        public long millis() { return now; }
    }
    static final class Memory implements JobLifecycleStore.Storage {
        Snapshot state;
        boolean hold, fail;
        int writes;
        Snapshot heldState;
        CompletableFuture<Snapshot> held;
        Memory(UUID world) { state=Snapshot.empty(world); }
        public CompletionStage<Snapshot> replace(Snapshot expected,Snapshot next) {
            assertEquals(state,expected); writes++;
            if (fail) return CompletableFuture.failedFuture(new IllegalStateException("fixture storage"));
            if (hold) {
                heldState=next; held=new CompletableFuture<>(); return held;
            }
            state=next; return CompletableFuture.completedFuture(next);
        }
        void release() { state=heldState; held.complete(state); hold=false; }
    }
    static final class Scene {
        final UUID world=UUID.randomUUID();
        final TrustedContext owner=owner(world), foreign=owner(world);
        final ActorRef actor=actor();
        final TestClock clock=new TestClock();
        final Memory memory=new Memory(world);
        final List<Cancellation> notices=new ArrayList<>();
        final JobLifecycleStore store;
        boolean controls=true, deliveryFails;
        Scene() { this(Settings.defaults(),null); }
        Scene(Settings settings,JobLifecycleStore.Policy override) {
            JobLifecycleStore.Policy policy=override==null
                    ? JobLifecycleStore.privateJobs((a,c)->controls && c.equals(owner)) : override;
            store=new JobLifecycleStore(memory.state,memory,policy,c->{
                notices.add(c);
                if(deliveryFails) throw new IllegalStateException("fixture delivery");
            },id->id.equals(CropDelivery.ID)?Optional.of(CropDelivery.SPEC):Optional.empty(),
                    environment(),clock,settings,false);
        }
        Job create(int amount) { return create(amount,List.of()); }
        Job create(int amount,List<UUID> deps) {
            UUID id=UUID.randomUUID();
            var result=store.create(id,id,request(actor,amount),owner,observation(),limits(clock.now+10_000),deps);
            assertTrue(result.accepted(),result.toString()); settle();
            return store.query(id,owner);
        }
        void settle() { store.tick(); assertTrue(store.ready(),String.valueOf(store.lastFailure())); }
        Job current(Job job) { return store.query(job.id(),owner); }
        Job assign(Job job) { return assign(job,actor,limits(clock.now+10_000)); }
        Job assign(Job job,ActorRef worker,Budgets.Limits allowance) {
            job=current(job);
            var change=store.assign(job.id(),job.guard(),UUID.randomUUID(),worker,REF,List.of(REF),allowance,owner);
            assertTrue(change.accepted(),change.toString()); settle(); return current(job);
        }
        Job report(Job job,long wheat,ExecutionStatus status) {
            job=current(job);
            Attempt a=job.current();
            var receipts=receipts(a,wheat);
            var outcome=status==null?null:new Execution(status,reason(status),wheat,
                    status==ExecutionStatus.SUCCEEDED?new EvidenceRef(a.id().toString(),"crop-delivery:1",owner.scope().domainId().toString()):null);
            var result=store.recordExecutionResult(job.id(),job.guard(),new Report(a.id(),a.generation(),wheat,
                    Map.of(Budgets.Kind.CALLS,1L),receipts,outcome),owner);
            assertTrue(result.accepted(),result.toString()); if(result.code()!=Code.DUPLICATE)settle();
            return current(job);
        }
    }
    static ActorRef actor() { return new ActorRef(UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld"); }
    static TrustedContext owner(UUID world) {
        var id=UUID.randomUUID(); return new TrustedContext(new PrincipalRef(id),new ScopeRef(world,id));
    }
    static ObservationRef observation() { return new ObservationRef(UUID.randomUUID(),0,"minecraft:overworld"); }
    static CapabilityRequest request(ActorRef actor,int quantity) {
        return new CapabilityRequest(CropDelivery.ID,Map.of("actor",new ActorValue(actor),"amount",new IntValue(quantity),
                "source",new AreaValue(new Cuboid(actor.dimension(),0,0,0,2,0,2)),
                "destination",new ContainerValue(new ContainerRef(actor.dimension(),3,0,3))));
    }
    static Budgets.Limits limits(long deadline) {
        return new Budgets.Limits(Map.of(Budgets.Kind.CALLS,20L,Budgets.Kind.INSTRUCTIONS,100L,
                Budgets.Kind.OBSERVATIONS,100L,Budgets.Kind.ATTEMPTED_EFFECTS,64L,
                Budgets.Kind.COMMITTED_EFFECTS,64L,Budgets.Kind.ELAPSED_TICKS,100L),deadline);
    }
    static RequestEnvironment environment() {
        return new RequestEnvironment() {
            public boolean enrolled(ActorRef actor,TrustedContext context) { return true; }
            public boolean loaded(Cuboid source,ObservationRef observation) { return true; }
            public boolean available(ContainerRef target,ObservationRef observation) { return true; }
        };
    }
    static Reason reason(ExecutionStatus status) {
        return switch(status) {
            case SUCCEEDED->null; case BLOCKED->Reason.RESOURCE_MISSING; case FAILED->Reason.ACTION_FAILED;
            case CANCELLED->Reason.CANCELLED; case INTERRUPTED->Reason.INTERRUPTED;
        };
    }
    static List<CropDelivery.CropReceipt> receipts(Attempt attempt,long wheat) {
        if(wheat==0)return List.of();
        UUID batch=UUID.randomUUID();
        return Stream.of(CropDelivery.Stage.values()).map(stage->new CropDelivery.CropReceipt(UUID.randomUUID(),
                attempt.executions().getFirst().runId(),batch,attempt.worker(),
                ((AreaValue)attempt.bound().request().arguments().get("source")).value(),
                ((ContainerValue)attempt.bound().request().arguments().get("destination")).value(),stage,wheat)).toList();
    }
    static void commit(Scene scene,Change result) {
        assertTrue(result.accepted(),result.toString());
        if(result.code()!=Code.DUPLICATE)scene.settle();
    }
    @Test void creationPreservesTypedBindingsScopeAndResponsibilityAfterDurableAck() {
        var scene=new Scene(); scene.memory.hold=true;
        UUID id=UUID.randomUUID(); var request=request(scene.actor,5); var observation=observation();
        var change=scene.store.create(id,id,request,scene.owner,observation,limits(11_000),List.of());
        assertEquals(Code.PENDING,change.code());
        assertThrows(SecurityException.class,()->scene.store.query(id,scene.owner));
        assertFalse(scene.store.ready()); assertEquals(List.of(),scene.store.readyJobs(scene.owner,0).jobs());
        scene.memory.release(); scene.settle(); Job job=scene.store.query(id,scene.owner);
        assertEquals(request,job.request().request()); assertEquals(observation,job.request().observation());
        assertEquals(scene.owner,job.origin()); assertEquals(scene.actor,job.responsible());
        assertEquals(State.READY,job.state()); assertEquals(1,job.revision()); assertEquals(0,job.generation());
    }
    @ParameterizedTest @ValueSource(ints={-1,0,65,Integer.MAX_VALUE})
    void invalidBoundRequestNeverCreatesRecordsOrNotifications(int amount) {
        var s=new Scene(); var before=s.store.snapshot();
        var result=s.store.create(UUID.randomUUID(),UUID.randomUUID(),request(s.actor,amount),s.owner,observation(),limits(11_000),List.of());
        assertEquals(Reason.REQUEST_INVALID,result.reason()); assertEquals(before,s.store.snapshot());
        assertEquals(0,s.memory.writes); assertTrue(s.notices.isEmpty());
    }
    @Test void unboundAndForeignWorldRequestsAreRejectedBeforeAdmission() {
        var s=new Scene(); UUID id=UUID.randomUUID();
        var incomplete=new CapabilityRequest(CropDelivery.ID,Map.of("amount",new IntValue(3)));
        assertEquals(Reason.REQUEST_INVALID,s.store.create(id,id,incomplete,s.owner,observation(),limits(11_000),List.of()).reason());
        assertEquals(Reason.AUTHORITY_DENIED,s.store.create(id,id,request(s.actor,1),owner(UUID.randomUUID()),observation(),limits(11_000),List.of()).reason());
        assertEquals(0,s.memory.writes);
    }
    @Test void revisionConflictChoosesOneAssignmentAndPreventsConflictingWorkerClaims() {
        var s=new Scene(); Job one=s.create(1),two=s.create(1); Guard guard=one.guard();
        var first=s.store.assign(one.id(),guard,UUID.randomUUID(),s.actor,REF,List.of(REF),limits(11_000),s.owner);
        assertTrue(first.accepted()); s.settle();
        var stale=s.store.assign(one.id(),guard,UUID.randomUUID(),s.actor,REF,List.of(REF),limits(11_000),s.owner);
        assertEquals(Code.CONFLICT,stale.code()); assertEquals(Reason.STALE_OBSERVATION,stale.reason());
        assertEquals(Reason.ACTOR_UNAVAILABLE,s.store.assign(two.id(),two.guard(),UUID.randomUUID(),s.actor,REF,List.of(REF),limits(11_000),s.owner).reason());
        assertEquals(1,s.current(one).generation()); assertEquals(0,s.current(two).generation());
    }
    @Test void immutableQueriesAndCurrentAuthorityDoNotTurnIdsIntoGrants() {
        var s=new Scene(); Job job=s.create(1); var before=s.store.snapshot();
        assertThrows(SecurityException.class,()->s.store.query(job.id(),s.foreign));
        assertThrows(SecurityException.class,()->s.store.query(UUID.randomUUID(),s.foreign));
        assertEquals(List.of(),s.store.readyJobs(s.foreign,0).jobs());
        assertEquals(Reason.AUTHORITY_DENIED,s.store.cancel(job.id(),job.guard(),s.foreign).reason());
        assertEquals(Reason.AUTHORITY_DENIED,s.store.transition(job.id(),job.guard(),State.FAILED,Reason.ACTION_FAILED,s.foreign).reason());
        assertThrows(UnsupportedOperationException.class,()->job.dependencies().add(UUID.randomUUID()));
        s.controls=false;
        assertEquals(Reason.AUTHORITY_DENIED,s.store.assign(job.id(),job.guard(),UUID.randomUUID(),s.actor,REF,List.of(REF),limits(11_000),s.owner).reason());
        assertEquals(before,s.store.snapshot());
    }
    @Test void explicitReadAndControlSharingPreservesOriginalPrincipalAndWorkerAuthority() {
        UUID world=UUID.randomUUID(); TrustedContext a=owner(world),b=owner(world); ActorRef worker=actor();
        var clock=new TestClock(); var memory=new Memory(world);
        var policy=new JobLifecycleStore.Policy(){
            public boolean mayRead(TrustedContext caller,Job job){return caller.equals(a)||caller.equals(b);}
            public boolean mayControl(TrustedContext caller,Job job){return caller.equals(a)||caller.equals(b);}
            public boolean controls(ActorRef actor,TrustedContext origin){return actor.equals(worker)&&origin.equals(a);}
        };
        var store=new JobLifecycleStore(memory.state,memory,policy,c->{},id->Optional.of(CropDelivery.SPEC),environment(),clock,Settings.defaults(),false);
        UUID id=UUID.randomUUID(); assertTrue(store.create(id,id,request(worker,1),a,observation(),limits(11_000),List.of()).accepted()); store.tick();
        Job job=store.query(id,b); assertEquals(a,job.origin()); assertEquals(1,store.readyJobs(b,0).jobs().size());
        assertEquals(Reason.AUTHORITY_DENIED,store.assign(id,job.guard(),UUID.randomUUID(),actor(),REF,List.of(REF),limits(11_000),b).reason());
        assertTrue(store.cancel(id,job.guard(),b).accepted()); store.tick(); assertEquals(a,store.query(id,b).origin());
    }
    @Test void dependencyChainOnlyReleasesCorrectParentOnce() {
        var s=new Scene(); Job c=s.create(1),b=s.create(1,List.of(c.id())),a=s.create(1,List.of(b.id()));
        assertEquals(List.of(c.id()),s.store.readyJobs(s.owner,0).jobs().stream().map(Job::id).toList());
        c=s.assign(c); c=s.report(c,1,ExecutionStatus.SUCCEEDED);
        assertEquals(State.READY,s.current(b).state()); assertEquals(State.WAITING,s.current(a).state());
        long revision=s.current(b).revision();
        Attempt attempt=c.current();
        var repeat=new Report(attempt.id(),attempt.generation(),attempt.effects(),attempt.usage(),attempt.receipts(),attempt.terminal());
        assertEquals(Code.DUPLICATE,s.store.recordExecutionResult(c.id(),c.guard(),repeat,s.owner).code());
        assertEquals(revision,s.current(b).revision());
        b=s.assign(b); s.report(b,1,ExecutionStatus.SUCCEEDED); assertEquals(State.READY,s.current(a).state());
    }
    @Test void initialAndRuntimeCyclesRejectAtomicallyIncludingNewChildren() {
        var s=new Scene(); Job c=s.create(1),b=s.create(1,List.of(c.id())),a=s.create(1,List.of(b.id()));
        Snapshot before=s.store.snapshot(); int writes=s.memory.writes;
        assertFalse(s.store.addDependency(c.id(),c.guard(),a.id(),s.owner).accepted());
        assertFalse(s.store.addDependency(a.id(),a.guard(),a.id(),s.owner).accepted());
        UUID child=UUID.randomUUID();
        var row=new Child(child,request(s.actor,1),observation(),limits(11_000),List.of(a.id()));
        assertFalse(s.store.addChildren(a.id(),a.guard(),List.of(row),s.owner).accepted());
        assertEquals(before,s.store.snapshot()); assertEquals(writes,s.memory.writes);
        assertThrows(SecurityException.class,()->s.store.query(child,s.owner));
    }
    @Test void atomicChildrenPreserveOriginAndFailedBatchLeavesNoOrphans() {
        var s=new Scene(); Job parent=s.create(1);
        var good=new Child(UUID.randomUUID(),request(s.actor,1),observation(),limits(11_000),List.of());
        var bad=new Child(UUID.randomUUID(),request(s.actor,0),observation(),limits(11_000),List.of());
        var before=s.store.snapshot();
        assertFalse(s.store.addChildren(parent.id(),parent.guard(),List.of(good,bad),s.owner).accepted());
        assertEquals(before,s.store.snapshot());
        commit(s,s.store.addChildren(parent.id(),parent.guard(),List.of(good),s.owner));
        assertEquals(parent.id(),s.store.query(good.id(),s.owner).parent());
        assertEquals(s.owner,s.store.query(good.id(),s.owner).origin());
        assertEquals(State.WAITING,s.current(parent).state());
    }
    @Test void activeLimitAllows32Rejects33WithoutEviction() {
        var s=new Scene(); var ids=new HashSet<UUID>();
        for(int i=0;i<32;i++)ids.add(s.create(1).id());
        Snapshot before=s.store.snapshot(); UUID id=UUID.randomUUID();
        assertEquals(Reason.STORAGE_LIMIT_REACHED,s.store.create(id,id,request(s.actor,1),s.owner,observation(),limits(11_000),List.of()).reason());
        assertEquals(before,s.store.snapshot()); assertEquals(ids,s.store.protectedRoots().jobs());
    }
    @Test void totalRecordQuotaKeepsReferencedTerminalKnowledge() {
        var s=new Scene();
        for(int i=0;i<64;i++){Job job=s.create(1);commit(s,s.store.cancel(job.id(),job.guard(),s.owner));}
        UUID id=UUID.randomUUID(); Snapshot before=s.store.snapshot();
        assertEquals(Reason.STORAGE_LIMIT_REACHED,s.store.create(id,id,request(s.actor,1),s.owner,observation(),limits(11_000),List.of()).reason());
        assertEquals(64,s.store.protectedRoots().jobs().size()); assertEquals(before,s.store.snapshot());
    }
    @ParameterizedTest @ValueSource(ints={8,9})
    void childCountBoundaryIsAtomic(int count) {
        var s=new Scene(); Job parent=s.create(1); var rows=new ArrayList<Child>();
        for(int i=0;i<count;i++)rows.add(new Child(UUID.randomUUID(),request(s.actor,1),observation(),limits(11_000),List.of()));
        var before=s.store.snapshot(); var change=s.store.addChildren(parent.id(),parent.guard(),rows,s.owner);
        if(count==8){commit(s,change);assertEquals(9,s.store.snapshot().jobs().size());}
        else{assertFalse(change.accepted());assertEquals(before,s.store.snapshot());}
    }
    @ParameterizedTest @ValueSource(ints={4,5})
    void dependencyDepthHasAnExplicitFourEdgeBoundary(int depth) {
        var s=new Scene(); Job last=s.create(1);
        for(int i=1;i<=depth;i++){
            UUID id=UUID.randomUUID(); Snapshot before=s.store.snapshot();
            var result=s.store.create(id,id,request(s.actor,1),s.owner,observation(),limits(11_000),List.of(last.id()));
            if(i<=4){commit(s,result);last=s.store.query(id,s.owner);}
            else{assertEquals(Reason.BUDGET_EXHAUSTED,result.reason());assertEquals(before,s.store.snapshot());}
        }
    }
    @Test void dependencyExpansionLimitUsesActualInspectedEdges() {
        var s=new Scene(); var leaves=new ArrayList<Job>(); var branches=new ArrayList<Job>();
        for(int i=0;i<8;i++)leaves.add(s.create(1));
        for(int i=0;i<7;i++)branches.add(s.create(1,leaves.stream().map(Job::id).toList()));
        var exact=new ArrayList<UUID>(branches.stream().map(Job::id).toList());exact.add(leaves.getFirst().id());
        UUID id=UUID.randomUUID();
        var allowed=s.store.create(id,id,request(s.actor,1),s.owner,observation(),limits(11_000),exact);
        assertTrue(allowed.accepted(),allowed.toString());assertEquals(64,allowed.inspectedEdges());s.settle();
        Job extra=s.create(1,List.of(leaves.getFirst().id()));
        var excess=new ArrayList<UUID>(branches.stream().map(Job::id).toList());excess.add(extra.id());
        Snapshot before=s.store.snapshot();UUID rejectedId=UUID.randomUUID();
        var rejected=s.store.create(rejectedId,rejectedId,request(s.actor,1),s.owner,observation(),limits(11_000),excess);
        assertEquals(Reason.BUDGET_EXHAUSTED,rejected.reason());assertEquals(64,rejected.inspectedEdges());
        assertEquals(before,s.store.snapshot());
    }
    @Test void parentChargesSurviveChildCancellationSiblingCreationAndRetry() {
        var s=new Scene(); Job parent=s.create(1);
        UUID first=UUID.randomUUID();
        commit(s,s.store.addChildren(parent.id(),parent.guard(),List.of(new Child(first,request(s.actor,1),observation(),limits(11_000),List.of())),s.owner));
        Job child=s.store.query(first,s.owner);
        commit(s,s.store.charge(child.id(),child.guard(),Map.of(Budgets.Kind.CALLS,7L),s.owner));
        child=s.store.query(first,s.owner);
        commit(s,s.store.cancel(first,child.guard(),s.owner));
        assertEquals(7,s.current(parent).usage().get(Budgets.Kind.CALLS)); assertEquals(7,s.current(child).usage().get(Budgets.Kind.CALLS));
        // Independent family demonstrates a cancelled attempt doesn't refund its parent's spend.
        var second=new Scene(); Job p=second.create(1); UUID kid=UUID.randomUUID();
        commit(second,second.store.addChildren(p.id(),p.guard(),List.of(new Child(kid,request(second.actor,2),observation(),limits(11_000),List.of())),second.owner));
        Job k=second.store.query(kid,second.owner); k=second.assign(k); k=second.report(k,1,ExecutionStatus.BLOCKED);
        assertEquals(1,second.current(p).usage().get(Budgets.Kind.CALLS));
        commit(second,second.store.transition(k.id(),k.guard(),State.READY,null,second.owner));
        var remaining=new Budgets.Limits(Map.of(Budgets.Kind.CALLS,19L),11_000);
        k=second.assign(k,second.actor,remaining);
        assertEquals(11_000,k.current().allowance().deadlineEpochMillis());
        assertEquals(1,((IntValue)k.current().bound().request().arguments().get("amount")).value());
        assertEquals(1,second.current(p).usage().get(Budgets.Kind.CALLS));
        assertFalse(second.store.charge(p.id(),second.current(p).guard(),Map.of(Budgets.Kind.CALLS,1L),second.owner).accepted());
    }
    @Test void childrenAndRetryCannotWidenOrRenewOriginalDeadline() {
        var s=new Scene(); Job parent=s.create(1); s.clock.now=5_000;
        Snapshot before=s.store.snapshot();
        assertEquals(Reason.BUDGET_EXHAUSTED,s.store.addChildren(parent.id(),parent.guard(),
                List.of(new Child(UUID.randomUUID(),request(s.actor,1),observation(),limits(15_000),List.of())),s.owner).reason());
        assertEquals(before,s.store.snapshot());
        assertEquals(Reason.BUDGET_EXHAUSTED,s.store.assign(parent.id(),parent.guard(),UUID.randomUUID(),s.actor,
                REF,List.of(REF),limits(15_000),s.owner).reason());
        s.clock.now=11_001; assertTrue(s.store.readyJobs(s.owner,0).jobs().isEmpty());
        assertEquals(Reason.BUDGET_EXHAUSTED,s.store.assign(parent.id(),parent.guard(),UUID.randomUUID(),s.actor,
                REF,List.of(REF),limits(11_000),s.owner).reason());
    }
    @Test void finiteCountersRejectOverflowWithoutCommitting() {
        var s=new Scene(); Job job=s.create(1); Snapshot before=s.store.snapshot();
        assertEquals(Reason.BUDGET_EXHAUSTED,s.store.charge(job.id(),job.guard(),Map.of(Budgets.Kind.CALLS,Long.MAX_VALUE),s.owner).reason());
        assertEquals(before,s.store.snapshot());
        assertThrows(IllegalArgumentException.class,()->new Guard(-1,0));
        assertThrows(IllegalArgumentException.class,()->new Budgets.Limits(Map.of(Budgets.Kind.CALLS,-1L),11_000));
        assertThrows(IllegalArgumentException.class,()->new Allocation(UUID.randomUUID(),job.id(),UUID.randomUUID(),UUID.randomUUID(),job.id(),0));
    }
    @ParameterizedTest @CsvSource({"0,64,8,4,64,8,16","33,64,8,4,64,8,16","32,31,8,4,64,8,16",
            "32,64,9,4,64,8,16","32,64,8,5,64,8,16","32,64,8,4,65,8,16","32,64,8,4,64,9,16","32,64,8,4,64,8,17"})
    void configurationRejectsZeroAndExcess(int active,int total,int children,int depth,int edges,int ready,int events) {
        assertThrows(IllegalArgumentException.class,()->new Settings(active,total,children,depth,edges,ready,events,8,64,128,2,30_000));
    }
    @Test void readyQueriesAreBoundedPagedAndNeverExposeForeignJobs() {
        var s=new Scene(); var expected=new HashSet<UUID>();
        for(int i=0;i<17;i++)expected.add(s.create(1).id());
        var found=new HashSet<UUID>();int offset=0, pages=0;
        do{var page=s.store.readyJobs(s.owner,offset); assertTrue(page.jobs().size()<=8);
            for(Job job:page.jobs())assertTrue(found.add(job.id()));offset=page.nextOffset();pages++;if(!page.more())break;
        }while(pages<10);
        assertEquals(expected,found);assertEquals(3,pages);assertTrue(s.store.readyJobs(s.foreign,0).jobs().isEmpty());
        assertThrows(SecurityException.class,()->s.store.readyJobs(s.owner,-1));
    }
    @Test void duplicateAndReorderedReportsCreditOnePhysicalBatchExactlyOnce() {
        var s=new Scene();Job job=s.assign(s.create(5));Attempt attempt=job.current();
        var receipts=receipts(attempt,5);var done=new Execution(ExecutionStatus.SUCCEEDED,null,5,
                new EvidenceRef(attempt.id().toString(),"crop-delivery:1",s.owner.scope().domainId().toString()));
        var report=new Report(attempt.id(),attempt.generation(),5,Map.of(Budgets.Kind.CALLS,1L),receipts,done);
        commit(s,s.store.recordExecutionResult(job.id(),job.guard(),report,s.owner));job=s.current(job);
        Snapshot snapshot=s.store.snapshot();
        assertEquals(State.SUCCEEDED,job.state());assertEquals(5,job.fulfilled());assertEquals(5,job.effects());
        for(int i=0;i<3;i++)assertEquals(Code.DUPLICATE,s.store.recordExecutionResult(job.id(),job.guard(),report,s.owner).code());
        var reversed=new ArrayList<>(receipts);Collections.reverse(reversed);
        assertEquals(Code.DUPLICATE,s.store.recordExecutionResult(job.id(),job.guard(),
                new Report(attempt.id(),attempt.generation(),3,Map.of(Budgets.Kind.CALLS,0L),reversed,null),s.owner).code());
        assertEquals(snapshot,s.store.snapshot());assertEquals(1,job.usage().get(Budgets.Kind.CALLS));
    }
    @Test void receiptCollisionForeignRunAndNarratedSuccessDoNotChangeCredit() {
        var s=new Scene();Job job=s.assign(s.create(5));Attempt attempt=job.current();Snapshot before=s.store.snapshot();
        var incomplete=receipts(attempt,5).stream().filter(r->r.stage()!=CropDelivery.Stage.PICKUP).toList();
        var done=new Execution(ExecutionStatus.SUCCEEDED,null,5,new EvidenceRef(attempt.id().toString(),"crop-delivery:1","scope"));
        assertEquals(Reason.ACTION_FAILED,s.store.recordExecutionResult(job.id(),job.guard(),
                new Report(attempt.id(),attempt.generation(),5,Map.of(),incomplete,done),s.owner).reason());
        var foreign=new CropDelivery.CropReceipt(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),s.actor,
                ((AreaValue)attempt.bound().request().arguments().get("source")).value(),
                ((ContainerValue)attempt.bound().request().arguments().get("destination")).value(),CropDelivery.Stage.DEPOSIT,5);
        assertEquals(Reason.AUTHORITY_DENIED,s.store.recordExecutionResult(job.id(),job.guard(),
                new Report(attempt.id(),attempt.generation(),5,Map.of(),List.of(foreign),null),s.owner).reason());
        assertEquals(before,s.store.snapshot());assertEquals(0,s.current(job).fulfilled());
    }
    @Test void cancellationIntentWaitsForTerminationAndRetainsTwoOfFive() {
        var s=new Scene();Job job=s.assign(s.create(5));job=s.report(job,2,null);
        commit(s,s.store.cancel(job.id(),job.guard(),s.owner));job=s.current(job);
        assertEquals(State.CANCELLING,job.state());assertEquals(2,job.fulfilled());assertTrue(s.notices.isEmpty());
        s.store.tick();s.settle();assertEquals(1,s.notices.size());assertEquals(State.CANCELLING,s.current(job).state());
        Attempt a=s.current(job).current();
        var result=new Report(a.id(),a.generation(),2,a.usage(),a.receipts(),new Execution(ExecutionStatus.CANCELLED,Reason.CANCELLED,2,null));
        commit(s,s.store.recordExecutionResult(job.id(),s.current(job).guard(),result,s.owner));
        assertEquals(State.CANCELLED,s.current(job).state());assertEquals(2,s.current(job).fulfilled());assertEquals(2,s.current(job).effects());
    }
    @Test void failedCancellationDeliveryRetriesWithinCapWithoutFalseTermination() {
        var s=new Scene();Job job=s.assign(s.create(5));s.deliveryFails=true;
        commit(s,s.store.cancel(job.id(),job.guard(),s.owner));
        for(int i=0;i<10;i++)s.store.tick();
        job=s.current(job);assertEquals(State.CANCELLING,job.state());
        assertEquals(2,s.notices.size());assertEquals(2,job.current().cancellationDeliveries());
        assertEquals(Reason.ACTION_FAILED,s.store.lastFailure());
        UUID attemptId=job.current().id();
        assertTrue(s.notices.stream().allMatch(n->n.attemptId().equals(attemptId)));
    }
    @Test void cancellingOneChildPropagatesAndStopsOwnedActiveSiblingBeforeParentCloses() {
        var s=new Scene();Job parent=s.create(1);UUID one=UUID.randomUUID(),two=UUID.randomUUID();
        commit(s,s.store.addChildren(parent.id(),parent.guard(),List.of(
                new Child(one,request(s.actor,1),observation(),limits(11_000),List.of()),
                new Child(two,request(s.actor,5),observation(),limits(11_000),List.of())),s.owner));
        Job second=s.store.query(two,s.owner); second=s.assign(second,actor(),new Budgets.Limits(Map.of(Budgets.Kind.CALLS,10L),11_000));
        second=s.report(second,2,null);
        Job first=s.store.query(one,s.owner);commit(s,s.store.cancel(first.id(),first.guard(),s.owner));
        assertEquals(State.CANCELLING,s.current(parent).state());assertEquals(State.CANCELLING,s.current(second).state());
        Attempt a=s.current(second).current();
        commit(s,s.store.recordExecutionResult(two,s.current(second).guard(),new Report(a.id(),a.generation(),2,a.usage(),a.receipts(),
                new Execution(ExecutionStatus.CANCELLED,Reason.CANCELLED,2,null)),s.owner));
        assertEquals(State.CANCELLED,s.current(parent).state());assertEquals(State.CANCELLED,s.current(second).state());
        assertEquals(2,s.current(second).fulfilled());assertEquals(1,s.current(parent).usage().get(Budgets.Kind.CALLS));
    }
    @Test void failedDependencyUsesFailedOutcomeAndNeverCancelsAnUnownedSharedDependency() {
        var s=new Scene();Job child=s.create(1),parent=s.create(1,List.of(child.id())),unrelated=s.create(1);
        commit(s,s.store.transition(child.id(),child.guard(),State.FAILED,Reason.ACTION_FAILED,s.owner));
        assertEquals(State.FAILED,s.current(parent).state());assertEquals(Reason.ACTION_FAILED,s.current(parent).reason());
        assertEquals(State.READY,s.current(unrelated).state());
    }
    @Test void obsoleteCompletionCannotFinishReassignedWorkButLateEffectsStayAttributable() {
        var s=new Scene();Job job=s.assign(s.create(5));job=s.report(job,2,ExecutionStatus.INTERRUPTED);
        Attempt old=job.current();assertEquals(State.INTERRUPTED,job.state());Snapshot before=s.store.snapshot();
        var stopped=new Report(old.id(),old.generation(),2,old.usage(),old.receipts(),
                new Execution(ExecutionStatus.INTERRUPTED,Reason.INTERRUPTED,2,null));
        assertFalse(s.store.reconcile(job.id(),job.guard(),stopped,false,s.owner).accepted());assertEquals(before,s.store.snapshot());
        commit(s,s.store.reconcile(job.id(),job.guard(),stopped,true,s.owner));job=s.current(job);
        job=s.assign(job,s.actor,new Budgets.Limits(Map.of(Budgets.Kind.CALLS,10L),11_000));
        long generation=job.generation();var extra=receipts(old,1);var all=new ArrayList<>(old.receipts());all.addAll(extra);
        var late=new Report(old.id(),old.generation(),3,old.usage(),all,
                new Execution(ExecutionStatus.SUCCEEDED,null,3,new EvidenceRef(old.id().toString(),"crop-delivery:1","scope")));
        commit(s,s.store.recordExecutionResult(job.id(),job.guard(),late,s.owner));
        job=s.current(job);assertEquals(State.ACTIVE,job.state());assertEquals(generation,job.generation());
        assertEquals(2,job.fulfilled());assertEquals(3,job.effects());
        assertEquals(6,job.attempts().getFirst().receipts().size());
        assertEquals(EventKind.LATE_EFFECTS,job.events().getLast().kind());
    }
    @Test void fiveOutputUnitsCannotSatisfyTwoThreeUnitDemands() {
        var s=new Scene();Job producer=s.assign(s.create(5));producer=s.report(producer,5,ExecutionStatus.SUCCEEDED);
        Job a=s.create(3),b=s.create(3);Attempt attempt=producer.current();
        UUID receipt=attempt.receipts().stream().filter(r->r.stage()==CropDelivery.Stage.DEPOSIT).findFirst().orElseThrow().receiptId();
        var first=new Allocation(UUID.randomUUID(),producer.id(),attempt.id(),receipt,a.id(),3);
        commit(s,s.store.allocate(first,a.guard(),s.owner));
        assertEquals(State.SUCCEEDED,s.current(a).state());Snapshot before=s.store.snapshot();
        assertEquals(Code.DUPLICATE,s.store.allocate(first,a.guard(),s.owner).code());
        var second=new Allocation(UUID.randomUUID(),producer.id(),attempt.id(),receipt,b.id(),3);
        assertEquals(Reason.RESOURCE_MISSING,s.store.allocate(second,b.guard(),s.owner).reason());
        assertEquals(before,s.store.snapshot());assertEquals(State.READY,s.current(b).state());
        assertEquals(3,s.store.snapshot().allocations().stream().mapToLong(Allocation::quantity).sum());
    }
    @Test void stalledStorageNeverBlocksCallerAndLateAcknowledgementCannotReviveJobs() {
        var s=new Scene();Job job=s.create(1);s.memory.hold=true;
        var change=s.store.assign(job.id(),job.guard(),UUID.randomUUID(),s.actor,REF,List.of(REF),limits(11_000),s.owner);
        assertEquals(Code.PENDING,change.code());assertFalse(s.store.ready());assertEquals(State.READY,s.current(job).state());
        s.clock.now+=30_001;s.store.tick();assertFalse(s.store.ready());assertEquals(Reason.STORAGE_UNAVAILABLE,s.store.lastFailure());
        s.memory.release();s.store.tick();assertEquals(State.READY,s.current(job).state());
        assertFalse(s.store.protectedRoots().complete());
    }
    @Test void protectedReferencesIncludePendingAssignmentAndAllExactVersions() {
        var s=new Scene();Job job=s.create(1);s.memory.hold=true;
        var dep=new ArtifactRef(CropDelivery.ID,"b".repeat(64));UUID run=UUID.randomUUID();
        assertTrue(s.store.assign(job.id(),job.guard(),run,s.actor,REF,List.of(REF,dep),limits(11_000),s.owner).accepted());
        assertEquals(Set.of(REF,dep),s.store.protectedRoots().artifacts());
        assertTrue(s.store.protectedRoots().runs().contains(run));assertTrue(s.store.protectedRoots().jobs().contains(job.id()));
        s.memory.release();s.settle();
        assertEquals(Set.of(REF,dep),s.store.protectedRoots().artifacts());
    }
    @Test void exactPreparationPinMustCommitAndCannotBeChangedInPlace() {
        var s=new Scene();Job job=s.create(1);UUID orchestration=UUID.randomUUID();
        commit(s,s.store.assign(job.id(),job.guard(),orchestration,s.actor,null,List.of(),limits(11_000),s.owner));
        job=s.current(job);UUID trial=UUID.randomUUID();var pinned=new ExecutionReference(trial,REF,List.of(REF));
        commit(s,s.store.pin(job.id(),job.guard(),pinned,s.owner));job=s.current(job);
        assertEquals(Code.DUPLICATE,s.store.pin(job.id(),job.guard(),pinned,s.owner).code());
        assertFalse(s.store.pin(job.id(),job.guard(),new ExecutionReference(trial,new ArtifactRef(CropDelivery.ID,"b".repeat(64)),
                List.of(new ArtifactRef(CropDelivery.ID,"b".repeat(64)))),s.owner).accepted());
        assertTrue(s.store.protectedRoots().runs().contains(trial));assertEquals(REF,job.current().executions().getFirst().artifact());
    }
    @Test void reloadRequiresCertainOwnerObservationAndDoesNotReplayOrInferInventory() {
        var s=new Scene();Job job=s.assign(s.create(5));Snapshot saved=s.memory.state;
        var reloadMemory=new Memory(s.world);reloadMemory.state=saved;
        var reload=new JobLifecycleStore(saved,reloadMemory,JobLifecycleStore.privateJobs((a,c)->true),c->{throw new AssertionError("No automatic dispatch/cancel");},
                id->Optional.of(CropDelivery.SPEC),environment(),s.clock,Settings.defaults(),false);
        assertFalse(reload.ready());reload.tick();assertTrue(reload.ready());
        Job interrupted=reload.query(job.id(),s.owner);assertEquals(State.INTERRUPTED,interrupted.state());
        assertTrue(interrupted.current().uncertain());assertEquals(0,interrupted.fulfilled());
        assertTrue(reload.readyJobs(s.owner,0).jobs().isEmpty());
        assertEquals(Reason.REQUEST_INVALID,reload.assign(job.id(),interrupted.guard(),UUID.randomUUID(),s.actor,REF,List.of(REF),limits(11_000),s.owner).reason());
        assertTrue(reload.protectedRoots().artifacts().contains(REF));assertEquals(saved.jobs().getFirst().allowance(),interrupted.allowance());
    }
    @Test void boundedEventsAggregateRepeatedUpdatesWithoutHistoryGrowth() {
        var s=new Scene();Job job=s.create(1);
        for(int i=0;i<24;i++){
            job=s.current(job);State target=job.state()==State.READY?State.WAITING:State.READY;
            commit(s,s.store.transition(job.id(),job.guard(),target,target==State.WAITING?Reason.RESOURCE_MISSING:null,s.owner));
        }
        job=s.current(job);assertEquals(16,job.events().size());assertEquals(25,job.revision());
    }
    @Test void allLifecycleOperationsRemainModelFreeAndOnTheirOwnerThread() throws Exception {
        var s=new Scene();Job job=s.create(1); // No generation port exists in this component.
        commit(s,s.store.cancel(job.id(),job.guard(),s.owner));assertEquals(State.CANCELLED,s.current(job).state());
        try(var pool=Executors.newSingleThreadExecutor()){
            var failure=pool.submit(()->assertThrows(IllegalStateException.class,()->s.store.query(job.id(),s.owner))).get();
            assertEquals("Server thread required",failure.getMessage());
        }
    }
    static Stream<Arguments> forbiddenPairs() {
        return Stream.of(State.values()).flatMap(from->Stream.of(State.values())
                .filter(to->!Jobs.legal(from,to)).map(to->Arguments.of(from,to)));
    }
    static Stream<Arguments> legalPairs() {
        return Stream.of(State.values()).flatMap(from->Stream.of(State.values())
                .filter(to->Jobs.legal(from,to)).map(to->Arguments.of(from,to)));
    }
    @ParameterizedTest @MethodSource("legalPairs")
    void allowedStatePairsAdvanceOnceOnlyWithRequiredOwnerEvidence(State from,State to) {
        var s=new Scene();Job job=s.create(1);Job sibling=null;
        if(from==State.WAITING && to==State.CANCELLING || from==State.CANCELLING && to==State.FAILED){
            UUID child=UUID.randomUUID();
            commit(s,s.store.addChildren(job.id(),job.guard(),List.of(new Child(child,request(s.actor,2),observation(),limits(11_000),List.of())),s.owner));
            sibling=s.assign(s.store.query(child,s.owner));
            if(from==State.CANCELLING){
                // A failing prerequisite cancels the running owned child; the parent
                // remains CANCELLING until that child actually stops.
                job=s.current(job);
                var failed=s.create(1);
                commit(s,s.store.addDependency(job.id(),job.guard(),failed.id(),s.owner));
                commit(s,s.store.transition(failed.id(),failed.guard(),State.FAILED,Reason.ACTION_FAILED,s.owner));
            }
        }else if(from==State.WAITING){
            commit(s,s.store.transition(job.id(),job.guard(),State.WAITING,Reason.RESOURCE_MISSING,s.owner));
        }else if(from==State.ACTIVE || from==State.CANCELLING || from==State.INTERRUPTED){
            job=s.assign(job);
            if(from==State.CANCELLING)commit(s,s.store.cancel(job.id(),job.guard(),s.owner));
            if(from==State.INTERRUPTED)job=s.report(job,0,ExecutionStatus.INTERRUPTED);
        }
        job=s.current(job);assertEquals(from,job.state());long revision=job.revision();
        switch(to){
            case READY,WAITING,FAILED -> {
                if(from==State.INTERRUPTED){
                    var a=job.current();var status=to==State.READY?ExecutionStatus.INTERRUPTED:
                            to==State.WAITING?ExecutionStatus.BLOCKED:ExecutionStatus.FAILED;
                    commit(s,s.store.reconcile(job.id(),job.guard(),new Report(a.id(),a.generation(),0,a.usage(),a.receipts(),
                            new Execution(status,reason(status),0,null)),true,s.owner));
                }else if(from==State.ACTIVE){
                    job=s.report(job,0,to==State.WAITING?ExecutionStatus.BLOCKED:ExecutionStatus.FAILED);
                }else if(from==State.CANCELLING){
                    assertNotNull(sibling);
                    sibling=s.current(sibling);var a=sibling.current();
                    commit(s,s.store.recordExecutionResult(sibling.id(),sibling.guard(),new Report(a.id(),a.generation(),0,a.usage(),a.receipts(),
                            new Execution(ExecutionStatus.CANCELLED,Reason.CANCELLED,0,null)),s.owner));
                }else commit(s,s.store.transition(job.id(),job.guard(),to,to==State.READY?null:Reason.RESOURCE_MISSING,s.owner));
            }
            case ACTIVE -> {
                commit(s,s.store.assign(job.id(),job.guard(),UUID.randomUUID(),s.actor,
                        from==State.WAITING?null:REF,from==State.WAITING?List.of():List.of(REF),limits(11_000),s.owner));
            }
            case SUCCEEDED -> job=s.report(job,1,ExecutionStatus.SUCCEEDED);
            case CANCELLING -> commit(s,s.store.cancel(job.id(),job.guard(),s.owner));
            case CANCELLED -> {
                if(from==State.ACTIVE || from==State.CANCELLING)job=s.report(job,0,ExecutionStatus.CANCELLED);
                else if(from==State.INTERRUPTED){
                    var a=job.current();
                    commit(s,s.store.reconcile(job.id(),job.guard(),new Report(a.id(),a.generation(),0,a.usage(),a.receipts(),
                            new Execution(ExecutionStatus.CANCELLED,Reason.CANCELLED,0,null)),true,s.owner));
                }else commit(s,s.store.cancel(job.id(),job.guard(),s.owner));
            }
            case INTERRUPTED -> {
                if(from==State.WAITING)commit(s,s.store.transition(job.id(),job.guard(),to,Reason.INTERRUPTED,s.owner));
                else job=s.report(job,0,ExecutionStatus.INTERRUPTED);
            }
        }
        job=s.current(job);assertEquals(to,job.state());assertEquals(revision+1,job.revision());
    }
    @Test void attemptAndReceiptLimitsHaveExactNAndNPlusOneBoundaries() {
        var s=new Scene();Job job=s.create(5);
        for(int i=0;i<8;i++){
            job=s.assign(job,s.actor,new Budgets.Limits(Map.of(Budgets.Kind.CALLS,20L-i),11_000));
            job=s.report(job,0,ExecutionStatus.BLOCKED);
            commit(s,s.store.transition(job.id(),job.guard(),State.READY,null,s.owner));job=s.current(job);
        }
        var before=s.store.snapshot();
        assertEquals(Reason.STORAGE_LIMIT_REACHED,s.store.assign(job.id(),job.guard(),UUID.randomUUID(),s.actor,REF,List.of(REF),
                new Budgets.Limits(Map.of(Budgets.Kind.CALLS,12L),11_000),s.owner).reason());
        assertEquals(before,s.store.snapshot());assertEquals(8,job.attempts().size());
        var t=new Scene();Job task=t.assign(t.create(5));Attempt a=task.current();var all=new ArrayList<CropDelivery.CropReceipt>();
        for(int i=0;i<64;i++)all.add(new CropDelivery.CropReceipt(UUID.randomUUID(),a.id(),UUID.randomUUID(),a.worker(),
                ((AreaValue)a.bound().request().arguments().get("source")).value(),
                ((ContainerValue)a.bound().request().arguments().get("destination")).value(),CropDelivery.Stage.DEPOSIT,1));
        commit(t,t.store.recordExecutionResult(task.id(),task.guard(),new Report(a.id(),a.generation(),64,Map.of(),all,null),t.owner));
        task=t.current(task);before=t.store.snapshot();
        var extra=new CropDelivery.CropReceipt(UUID.randomUUID(),a.id(),UUID.randomUUID(),a.worker(),
                ((AreaValue)a.bound().request().arguments().get("source")).value(),
                ((ContainerValue)a.bound().request().arguments().get("destination")).value(),CropDelivery.Stage.DEPOSIT,1);
        assertEquals(Reason.STORAGE_LIMIT_REACHED,t.store.recordExecutionResult(task.id(),task.guard(),
                new Report(a.id(),a.generation(),65,Map.of(),List.of(extra),null),t.owner).reason());
        assertEquals(before,t.store.snapshot());
    }
    @ParameterizedTest @MethodSource("forbiddenPairs")
    void forbiddenStatePairsLeaveStateAndAccountingUnchanged(State from,State to) {
        var s=new Scene();Job job=s.create(1);
        switch(from){
            case READY -> {}
            case WAITING -> commit(s,s.store.transition(job.id(),job.guard(),State.WAITING,Reason.RESOURCE_MISSING,s.owner));
            case ACTIVE -> job=s.assign(job);
            case CANCELLING -> {job=s.assign(job);commit(s,s.store.cancel(job.id(),job.guard(),s.owner));}
            case INTERRUPTED -> {job=s.assign(job);job=s.report(job,0,ExecutionStatus.INTERRUPTED);}
            case SUCCEEDED -> {job=s.assign(job);job=s.report(job,1,ExecutionStatus.SUCCEEDED);}
            case FAILED -> commit(s,s.store.transition(job.id(),job.guard(),State.FAILED,Reason.ACTION_FAILED,s.owner));
            case CANCELLED -> commit(s,s.store.cancel(job.id(),job.guard(),s.owner));
        }
        job=s.current(job);Snapshot before=s.store.snapshot();
        assertFalse(s.store.transition(job.id(),job.guard(),to,to==State.INTERRUPTED?Reason.INTERRUPTED:Reason.ACTION_FAILED,s.owner).accepted());
        assertEquals(before,s.store.snapshot());assertEquals(from,job.state());
    }
    @Test void assignmentsCannotChangeDimensionOrCapabilityAndFailureIsAtomic() {
        var s=new Scene();Job job=s.create(1);Snapshot before=s.store.snapshot();
        var otherDimension=new ActorRef(UUID.randomUUID(),UUID.randomUUID(),"minecraft:the_nether");
        assertFalse(s.store.assign(job.id(),job.guard(),UUID.randomUUID(),otherDimension,REF,List.of(REF),limits(11_000),s.owner).accepted());
        var otherCapability=new ArtifactRef(new CapabilityId("cognitivecraft:unregistered",1),"a".repeat(64));
        assertEquals(Reason.REQUEST_INVALID,s.store.assign(job.id(),job.guard(),UUID.randomUUID(),s.actor,otherCapability,
                List.of(otherCapability),limits(11_000),s.owner).reason());
        assertEquals(before,s.store.snapshot());assertTrue(s.store.protectedRoots().artifacts().isEmpty());
    }

    @Test void parentAccountingAndObservedChildTerminationPublishOneGuardedRevision() {
        var s=new Scene();Job parent=s.create(5);UUID child=UUID.randomUUID();
        commit(s,s.store.addChildren(parent.id(),parent.guard(),List.of(new Child(child,request(s.actor,5),
                observation(),limits(11_000),List.of())),s.owner));
        Job assigned=s.assign(s.store.query(child,s.owner));parent=s.current(parent);
        commit(s,s.store.cancel(parent.id(),parent.guard(),s.owner));parent=s.current(parent);
        long before=parent.revision();
        s.report(s.current(assigned),2,ExecutionStatus.CANCELLED);
        Job observed=s.current(parent);
        assertEquals(State.CANCELLED,observed.state());assertEquals(before+1,observed.revision());
        assertEquals(1,observed.usage().get(Budgets.Kind.CALLS));assertEquals(2,s.current(assigned).fulfilled());
        assertTrue(observed.events().stream().allMatch(e->e.revision()<=observed.revision()));
    }

}
