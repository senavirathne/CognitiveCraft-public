package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static org.junit.jupiter.api.Assertions.*;

final class GoalPlannerTest {
    static final class FakeModel implements GenerationPort {
        final List<Call> calls=new ArrayList<>();boolean available=true;
        final class Call implements Generation.Handle {
            final Generation.Request request;final CompletableFuture<Generation.Result> result=new CompletableFuture<>();
            final Budgets.ResponseAllowance output;boolean cancelled;
            Call(Generation.Request request,Budgets.ResponseAllowance output){this.request=request;this.output=output;}
            public UUID id(){return request.id();}public CompletionStage<Generation.Result> result(){return result;}
            public boolean cancel(){cancelled=true;return true;}public Generation.Compute compute(){return result.isDone()?Generation.Compute.COMPLETED:cancelled?Generation.Compute.STOP_UNCONFIRMED:Generation.Compute.RUNNING;}
            void respond(String json){byte[] bytes=json.getBytes(java.nio.charset.StandardCharsets.UTF_8);output.accept(bytes.length);output.close();
                result.complete(new Generation.Result(id(),request.bound().context(),request.role(),Generation.Outcome.CANDIDATE,null,json,
                        descriptor(),new Generation.Usage(128,bytes.length,-1,-1,Generation.Precision.UNKNOWN),Generation.Compute.COMPLETED));}
        }
        public Generation.Handle generate(Generation.Request request,Budgets.InferenceLimits limits,Budgets.Ledger allowance) {
            var call=new Call(request,limits.chargeCall(allowance,128,request.role().repair()));calls.add(call);return call;
        }
        public Generation.Descriptor descriptor(){return new Generation.Descriptor("ollama-chat-v1","deterministic-planning",null);}
        public Generation.Status status(){return new Generation.Status(available?Generation.State.READY:Generation.State.UNAVAILABLE,0,null,Generation.Compute.NOT_STARTED,false,false);}
    }
    static class Fixture implements GoalPlanner.ContextPort {
        final UUID world=UUID.randomUUID();final TrustedContext owner=JobLifecycleStoreTest.owner(world),foreign=JobLifecycleStoreTest.owner(world);
        final ActorRef actor=JobLifecycleStoreTest.actor();final JobLifecycleStoreTest.TestClock clock=new JobLifecycleStoreTest.TestClock();
        final JobLifecycleStoreTest.Memory disk=new JobLifecycleStoreTest.Memory(world);final FakeModel model=new FakeModel();
        boolean allowed=true,method=true,captureHeld,changed,captureFail;long tick=1,crops=9;int captures,routes;
        final ArtifactDescriptor descriptor=new ArtifactDescriptor(JobLifecycleStoreTest.REF,1,CropDelivery.SPEC.parameters(),Type.INT,
                CropDelivery.SPEC.effects(),List.of(),List.of());
        final JobLifecycleStore jobs;final AIWorkBroker broker;GoalPlanner planner;
        PlanJournal.Snapshot planDisk=PlanJournal.Snapshot.empty(world);boolean planFail,shared;
        CompletableFuture<Plan.Context> held;Plan.Context prepared;
        CapabilityResolver.Domain domain=CapabilityResolver.Domain.BROAD_GOAL;boolean handled,complete=true;
        PlanningBudget budget=PlanningBudget.defaults();
        Fixture(){this(null);}
        Fixture(JobLifecycleStore.Policy override) {
            jobs=new JobLifecycleStore(disk.state,disk,override==null?JobLifecycleStore.privateJobs((a,c)->allowed && a.equals(actor) && c.equals(owner)):override,
                    cancellation->{},PlanningGoals.catalog(),JobLifecycleStoreTest.environment(),clock,Jobs.Settings.defaults(),false);
            broker=new AIWorkBroker(model,clock,AIWorkBroker.Settings.defaults(),AIWorkBroker.sessionLimits(clock.millis()),
                    r->allowed,AIWorkBroker.Sharing.privateScopes());planner=makePlanner();
        }
        GoalPlanner makePlanner(){return new GoalPlanner(jobs,broker,this,budget,clock,planDisk,(expected,next)->{
            assertEquals(planDisk,expected);if(planFail)return CompletableFuture.failedFuture(new IllegalStateException("Plan storage fixture"));
            planDisk=next;return CompletableFuture.completedFuture(next);},(template,caller)->template.owner().equals(caller)||shared&&caller.equals(owner));}
        Budgets.Limits limits(){var max=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);for(var kind:Budgets.Kind.values())max.put(kind,20000L);
            max.put(Budgets.Kind.CANDIDATES,2L);max.put(Budgets.Kind.INPUT_BYTES,65536L);max.put(Budgets.Kind.OUTPUT_BYTES,16384L);return new Budgets.Limits(max,clock.now+10000);}
        CapabilityRequest goal(int first,int second) {
            var args=new HashMap<>(JobLifecycleStoreTest.request(actor,first+second).arguments());args.put("otherDestination",new ContainerValue(new ContainerRef(actor.dimension(),5,0,5)));
            args.put("firstAmount",new IntValue(first));return new CapabilityRequest(PlanningGoals.ID,args);
        }
        Jobs.Job create(){return create(goal(2,3));}
        Jobs.Job create(CapabilityRequest request){UUID id=UUID.randomUUID();assertTrue(jobs.create(id,id,request,owner,JobLifecycleStoreTest.observation(),limits(),List.of()).accepted());ack();return jobs.query(id,owner);}
        void ack(){jobs.tick();assertTrue(jobs.ready(),String.valueOf(jobs.lastFailure()));}
        void step(){jobs.tick();broker.step();planner.tick();}
        GoalPlanner.View advance(GoalPlanner.View view,int steps){for(int i=0;i<steps;i++)step();return planner.view(view.id(),owner);}
        GoalPlanner.View queued(Jobs.Job root){var p=planner.propose(root.id(),owner);p=advance(p,5);assertEquals(GoalPlanner.State.QUEUED,p.state());assertEquals(1,model.calls.size());return p;}
        String valid(){return Plan.encode(new Plan.Proposal(List.of(new Plan.Node(0,0,2,List.of()),new Plan.Node(1,1,3,List.of(0)))));}
        public boolean controls(ActorRef a,TrustedContext c){return allowed&&a.equals(actor)&&c.equals(owner);}
        public long tick(){return tick;}
        ObservationSnapshot observation(CapabilityRequest r){var area=((AreaValue)r.arguments().get("source")).value();
            long cells=(long)(area.maxX()-area.minX()+1)*(area.maxY()-area.minY()+1)*(area.maxZ()-area.minZ()+1);
            return new ObservationSnapshot(new ObservationRef(UUID.randomUUID(),tick,actor.dimension()),ObservationStatus.PRESENT,
                    "source:"+UUID.nameUUIDFromBytes(area.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),tick,Math.toIntExact(cells),Map.of("mature_wheat",changed?crops-1:crops,"unknown_cells",0L));}
        CapabilityResolver.Engine engine() {
            var source=new CapabilityResolver.CandidateSource(){
                public VersionedSkillRepository.Page page(CapabilityId id,String after,int size){return new VersionedSkillRepository.Page(
                        id.equals(CropDelivery.ID)&&method&&after.isEmpty()?List.of(new VersionedSkillRepository.Candidate(descriptor.ref(),AdmissionStatus.ADMITTED,
                                new Compatibility(descriptor.ref(),CompatibilityStatus.COMPATIBLE,List.of(),"fixture"),VersionedSkillRepository.Integrity.VERIFIED)):List.of(),"",complete,1);}
                public Optional<ArtifactDescriptor> descriptor(ArtifactRef id){return Optional.of(Fixture.this.descriptor);}
                public long revision(){return 1;}
            };
            return new CapabilityResolver.Engine(PlanningGoals.catalog(),GatewayPrimitives.instance(),source,null,
                    spec->spec.equals(PlanningGoals.SPEC)?new CapabilityResolver.Envelope(domain,Set.of()):CapabilityResolver.cropDeliverySupport().forCapability(spec),
                    this::controls,(a,ref,c)->controls(a,c),(a,e,c)->controls(a,c),CapabilityResolver.cropPrerequisites(),()->tick,CapabilityResolver.Limits.defaults());
        }
        public CapabilityResolver.Decision resolve(ValidatedRequest r){var snapshot=observation(r.request());return engine().resolve(new ValidatedRequest(r.request(),r.context(),snapshot.reference()),snapshot);}
        public Plan.Context route(Jobs.Job root,PlanningBudget b){routes++;return prepare(root);}
        Plan.Context prepare(Jobs.Job root) {
            var snapshot=observation(root.request().request());var bound=new ValidatedRequest(root.request().request(),owner,snapshot.reference());
            var direct=engine().resolve(bound,snapshot);var rows=new ArrayList<Plan.Slot>();
            if(PlanningGoals.valid(root.request().request()))for(var request:PlanningGoals.slots(root.request().request())){
                var o=observation(request);rows.add(new Plan.Slot(new ValidatedRequest(request,owner,o.reference()),o,descriptor));}
            var catalog=new Prerequisites.Catalog(1,List.of(),handled?List.of(new Prerequisites.Production("fixture:broad","fixture:out",root.request().request().capability(),"",1,List.of())):
                    List.of(Prerequisites.Production.cropDelivery()),Set.of(),complete);
            prepared=new Plan.Context(root.id(),root.guard(),root.request(),direct,DeterministicPrerequisiteResolver.outcomeCoverage(root.request().request().capability(),catalog),rows,"");
            return prepared;
        }
        public CompletionStage<Plan.Context> capture(Jobs.Job root,PlanningBudget b) {
            captures++;prepare(root);if(captureFail)return CompletableFuture.failedFuture(new IllegalStateException("History storage unavailable"));
            if(captureHeld){held=new CompletableFuture<>();return held;}return CompletableFuture.completedFuture(prepared);
        }
        Plan.Check check(String json){var root=create();var ctx=capture(root,PlanningBudget.defaults()).toCompletableFuture().join();
            return new PlanValidator(PlanningBudget.defaults()).validate(json,ctx,limits(),this,tick);}
        Jobs.Job complete(UUID id) {
            var child=jobs.query(id,owner);assertTrue(jobs.assign(id,child.guard(),UUID.randomUUID(),actor,descriptor.ref(),List.of(descriptor.ref()),child.allowance(),owner).accepted());ack();child=jobs.query(id,owner);
            var a=child.current();long q=((IntValue)child.request().request().arguments().get("amount")).value();
            var outcome=new Execution(ExecutionStatus.SUCCEEDED,null,q,new EvidenceRef(a.id().toString(),"crop-delivery:1",owner.scope().domainId().toString()));
            assertTrue(jobs.recordExecutionResult(id,child.guard(),new Jobs.Report(a.id(),a.generation(),q,Map.of(Budgets.Kind.CALLS,1L),JobLifecycleStoreTest.receipts(a,q),outcome),owner).accepted());ack();return jobs.query(id,owner);
        }
    }
    @Test void validDagPublishesOnlyScopedCanonicalChildrenAndParentAccounting() {
        var f=new Fixture();var root=f.create();var view=f.queued(root);f.model.calls.getFirst().respond(f.valid());view=f.advance(view,6);
        assertEquals(GoalPlanner.State.ADMITTED,view.state());root=f.jobs.query(root.id(),f.owner);
        assertEquals(2,root.children().size());assertEquals(Jobs.State.WAITING,root.state());assertEquals(1L,root.usage().get(Budgets.Kind.CANDIDATES));
        assertEquals(32768L,root.usage().get(Budgets.Kind.INPUT_BYTES));assertEquals(128L,view.actual().get(Budgets.Kind.INPUT_BYTES));
        for(UUID id:root.children()){var child=f.jobs.query(id,f.owner);assertEquals(f.owner,child.origin());assertEquals(root.id(),child.parent());
            assertEquals(root.allowance().deadlineEpochMillis(),child.allowance().deadlineEpochMillis());assertEquals(0,child.allowance().maximum(Budgets.Kind.INPUT_BYTES));}
        assertEquals(1,f.planDisk.links().size());assertEquals(1,f.broker.stats().calls());
    }
    @ParameterizedTest @ValueSource(strings={"direct","prerequisites","incomplete","unsupported"})
    void priorRoutingCannotCallPlannerWhenDirectOrDeterministicHandlingApplies(String caseName) {
        var f=new Fixture();if(caseName.equals("prerequisites"))f.handled=true;
        if(caseName.equals("incomplete"))f.complete=false;
        if(caseName.equals("unsupported"))f.domain=CapabilityResolver.Domain.UNSUPPORTED;
        var root=f.create(caseName.equals("direct")?JobLifecycleStoreTest.request(f.actor,2):f.goal(2,3));
        var view=f.advance(f.planner.propose(root.id(),f.owner),5);
        assertEquals(GoalPlanner.State.DETERMINISTIC,view.state());assertEquals(0,f.model.calls.size());assertTrue(f.jobs.query(root.id(),f.owner).usage().isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"unknown","primitive","unbound","foreign","budget","cycle","oversize","quantity","duplicate","fraction","zero","overflow","command"})
    void malformedUnsupportedOrFabricatedProposalsAdmitNoWork(String test) {
        var f=new Fixture();String candidate=f.valid();Plan.Diagnostic diagnostic=Plan.Diagnostic.MALFORMED;
        switch(test){
            case "unknown" -> {candidate=candidate.replace(CropDelivery.ID.name(),"unknown:outcome");diagnostic=Plan.Diagnostic.UNSUPPORTED_CAPABILITY;}
            case "primitive" -> candidate=candidate.replace("\"schema\":1","\"primitive\":\"teleport\",\"schema\":1");
            case "unbound" -> {candidate=candidate.replace("\"slot\":0","\"slot\":7");diagnostic=Plan.Diagnostic.UNBOUND;}
            case "foreign" -> candidate=candidate.replace("\"schema\":1","\"actor\":\"foreign\",\"schema\":1");
            case "budget" -> candidate=candidate.replace("\"schema\":1","\"budget\":999999,\"schema\":1");
            case "cycle" -> {candidate=Plan.encode(new Plan.Proposal(List.of(new Plan.Node(0,0,2,List.of(1)),new Plan.Node(1,1,3,List.of(0)))));diagnostic=Plan.Diagnostic.CYCLE;}
            case "oversize" -> {candidate=" ".repeat(8193)+candidate;diagnostic=Plan.Diagnostic.SIZE;}
            case "quantity" -> {candidate=candidate.replace("\"amount\":3","\"amount\":4");diagnostic=Plan.Diagnostic.QUANTITY;}
            case "duplicate" -> {candidate=candidate.replace("\"id\":1","\"id\":0");diagnostic=Plan.Diagnostic.UNBOUND;}
            case "fraction" -> candidate=candidate.replace("\"amount\":2","\"amount\":2.5");
            case "zero" -> {candidate=candidate.replace("\"amount\":2","\"amount\":0");diagnostic=Plan.Diagnostic.QUANTITY;}
            case "overflow" -> candidate=candidate.replace("\"amount\":2","\"amount\":9223372036854775808");
            case "command" -> candidate=candidate.replace("\"schema\":1","\"command\":\"give wheat\",\"schema\":1");
        }
        var check=f.check(candidate);assertFalse(check.accepted());assertEquals(diagnostic,check.diagnostic(),check.toString());assertEquals(1,f.jobs.snapshot().jobs().size());
    }
    @ParameterizedTest @ValueSource(strings={"scope","method","stock","age","future"})
    void responseRevalidatesAuthorityMethodAndPremiseWithBoundedRefusal(String change) {
        var f=new Fixture();var root=f.create();var view=f.queued(root);
        switch(change){case "scope"->f.allowed=false;case "method"->f.method=false;case "stock"->f.changed=true;
            case "age"->f.tick=100;case "future"->f.tick=0;}
        f.model.calls.getFirst().respond(f.valid());for(int i=0;i<6;i++)f.step();
        if(change.equals("scope")){f.allowed=true;view=f.planner.view(view.id(),f.owner);}else view=f.planner.view(view.id(),f.owner);
        assertEquals(change.equals("age")?GoalPlanner.State.ADMITTED:GoalPlanner.State.REJECTED,view.state());
        if(!change.equals("age"))assertTrue(f.jobs.query(root.id(),f.owner).children().isEmpty());assertEquals(1,f.model.calls.size());
    }
    @Test void pendingCancelAndDelayedReplayCannotCreateChildrenOrRefundBudget() {
        var f=new Fixture();var root=f.create();var view=f.queued(root);var before=f.jobs.query(root.id(),f.owner).usage();
        assertTrue(f.planner.cancelProposal(view.id(),f.owner));f.model.calls.getFirst().respond(f.valid());f.advance(view,6);
        assertTrue(f.jobs.query(root.id(),f.owner).children().isEmpty());assertEquals(before,f.jobs.query(root.id(),f.owner).usage());assertEquals(GoalPlanner.State.CANCELLED,f.planner.view(view.id(),f.owner).state());
    }
    @Test void cancellationBeforeContextOrStorageAckNeverStartsModel() {
        var f=new Fixture();var root=f.create();f.captureHeld=true;var view=f.advance(f.planner.propose(root.id(),f.owner),2);
        assertTrue(f.planner.cancelProposal(view.id(),f.owner));f.held.complete(f.prepared);f.advance(view,5);assertEquals(0,f.model.calls.size());
        f.captureHeld=false;f.disk.hold=true;view=f.planner.propose(root.id(),f.owner);f.planner.tick();assertEquals(GoalPlanner.State.RESERVING,f.planner.view(view.id(),f.owner).state());
        assertTrue(f.planner.cancelProposal(view.id(),f.owner));f.disk.release();f.advance(view,5);assertEquals(0,f.model.calls.size());
    }
    @Test void rejectedCandidatesConsumeSameDurableAllowanceAndStopAtTwo() {
        var f=new Fixture();var root=f.create();
        for(int i=0;i<2;i++){var view=f.planner.propose(root.id(),f.owner);view=f.advance(view,5);f.model.calls.get(i).respond("{}");assertEquals(GoalPlanner.State.REJECTED,f.advance(view,5).state());}
        var last=f.advance(f.planner.propose(root.id(),f.owner),5);assertEquals(Reason.BUDGET_EXHAUSTED,last.reason());assertEquals(2,f.model.calls.size());
        assertEquals(2L,f.jobs.query(root.id(),f.owner).usage().get(Budgets.Kind.CANDIDATES));assertTrue(f.jobs.query(root.id(),f.owner).children().isEmpty());
    }
    @Test void deadlineCountsContextAndQueueTimeAndNoNewAllowance() {
        var f=new Fixture();var root=f.create();f.captureHeld=true;var view=f.advance(f.planner.propose(root.id(),f.owner),2);
        f.clock.now=root.allowance().deadlineEpochMillis();f.step();assertEquals(Reason.BUDGET_EXHAUSTED,f.planner.view(view.id(),f.owner).reason());
        f.held.complete(f.prepared);f.step();assertEquals(0,f.model.calls.size());
    }
    @Test void replayReloadAndFailedLinkageKeepOneTrackedAtomicJobGraph() {
        var f=new Fixture();var root=f.create();f.planFail=true;var view=f.queued(root);f.model.calls.getFirst().respond(f.valid());view=f.advance(view,6);
        assertEquals(GoalPlanner.State.ADMITTED,view.state());var ids=view.jobs();f.planner=f.makePlanner();
        var replay=f.planner.propose(root.id(),f.owner);assertEquals(ids,replay.jobs());assertEquals(1,f.model.calls.size());assertEquals(3,f.jobs.snapshot().jobs().size());
        assertEquals(1L,f.jobs.query(root.id(),f.owner).usage().get(Budgets.Kind.CANDIDATES));
    }
    @Test void publicationFailureCannotExposeExecutableFragmentsAndReloadUsesWholeSnapshot() {
        var f=new Fixture();var root=f.create();var view=f.queued(root);f.disk.fail=true;f.model.calls.getFirst().respond(f.valid());f.advance(view,6);
        assertEquals(1,f.jobs.snapshot().jobs().size());assertTrue(f.jobs.query(root.id(),f.owner).children().isEmpty());assertEquals(3,f.disk.writes);
        JobLifecycleStore.validateSnapshot(f.disk.state,Jobs.Settings.defaults());
    }
    @Test void modelsDisabledKnownChildrenExecuteAndObjectiveCompletesThroughOwner() {
        var f=new Fixture();var root=f.create();var view=f.queued(root);f.model.calls.getFirst().respond(f.valid());view=f.advance(view,6);
        f.broker.enabled(false);f.model.available=false;f.complete(view.jobs().get(0));f.complete(view.jobs().get(1));
        assertEquals(Jobs.State.SUCCEEDED,f.jobs.query(root.id(),f.owner).state());assertEquals(1,f.model.calls.size());
        assertFalse(f.planner.cancelProposal(view.id(),f.owner));assertTrue(f.planner.demonstrate(root.id(),f.owner));f.planner.tick();
        var template=f.planner.templates(f.owner).getFirst();assertEquals(0,f.planner.templates(f.foreign).size());
        assertFalse(Plan.encode(template.topology()).contains(f.actor.entityId().toString()));assertFalse(Plan.encode(template.topology()).contains("evidence"));
        var next=f.create(f.goal(1,4));var reuse=f.advance(f.planner.reuse(next.id(),template.id(),f.owner),6);assertEquals(GoalPlanner.State.ADMITTED,reuse.state());assertEquals(1,f.model.calls.size());
        assertEquals(List.of(1L,4L),reuse.jobs().stream().map(id->((IntValue)f.jobs.query(id,f.owner).request().request().arguments().get("amount")).value()).toList());
    }
    @Test void objectiveCancellationPreservesOtherRootsAndActualPartialChildEffects() {
        var f=new Fixture();var unrelated=f.create(JobLifecycleStoreTest.request(f.actor,1));var root=f.create();var view=f.queued(root);f.model.calls.getFirst().respond(f.valid());view=f.advance(view,6);
        f.complete(view.jobs().get(0));var successful=f.jobs.query(view.jobs().get(0),f.owner);assertTrue(f.planner.cancelObjective(root.id(),f.owner).accepted());f.ack();
        assertEquals(Jobs.State.CANCELLED,f.jobs.query(root.id(),f.owner).state());assertEquals(successful,f.jobs.query(successful.id(),f.owner));
        assertEquals(Jobs.State.READY,f.jobs.query(unrelated.id(),f.owner).state());assertEquals(2,successful.fulfilled());
    }
    @Test void guessedIdsAndRevokedTemplateGrantsNeverDiscloseOrControl() {
        var f=new Fixture();var root=f.create();var view=f.planner.propose(root.id(),f.owner);
        assertThrows(SecurityException.class,()->f.planner.view(view.id(),f.foreign));assertThrows(SecurityException.class,()->f.planner.propose(root.id(),f.foreign));
        f.allowed=false;assertThrows(SecurityException.class,()->f.planner.cancelObjective(root.id(),f.owner));
    }
    @Test void explicitTemplateSharingRebindsFreshScopeAndRevocationFencesPendingReuse() {
        var f=new Fixture();var template=new PlanJournal.Template(UUID.randomUUID(),f.foreign,
                new Plan.Proposal(List.of(new Plan.Node(0,0,1,List.of()),new Plan.Node(1,1,1,List.of(0)))));
        f.planDisk=new PlanJournal.Snapshot(f.world,1,List.of(),List.of(template));f.planner=f.makePlanner();
        var root=f.create();assertThrows(SecurityException.class,()->f.planner.reuse(root.id(),template.id(),f.owner));
        f.shared=true;f.captureHeld=true;var pending=f.advance(f.planner.reuse(root.id(),template.id(),f.owner),2);
        f.shared=false;f.held.complete(f.prepared);pending=f.advance(pending,5);
        assertEquals(Reason.AUTHORITY_DENIED,pending.reason());assertTrue(f.jobs.query(root.id(),f.owner).children().isEmpty());
        f.shared=true;f.captureHeld=false;pending=f.advance(f.planner.reuse(root.id(),template.id(),f.owner),6);
        assertEquals(GoalPlanner.State.ADMITTED,pending.state());assertEquals(0,f.model.calls.size());
        for(UUID id:pending.jobs())assertEquals(f.owner,f.jobs.query(id,f.owner).origin());
    }
    @Test void unavailableBackendRefusesPlanningButDoesNotInventFallbackOrRefund() {
        var f=new Fixture();f.model.available=false;var root=f.create();var view=f.advance(f.planner.propose(root.id(),f.owner),6);
        assertEquals(Reason.MODEL_UNAVAILABLE,view.reason());assertTrue(f.jobs.query(root.id(),f.owner).children().isEmpty());
        assertEquals(0,f.model.calls.size());assertEquals(1L,f.jobs.query(root.id(),f.owner).usage().get(Budgets.Kind.CANDIDATES));
    }
    @ParameterizedTest @ValueSource(strings={"nodes","depth","edges","createdWork","output","work"})
    void everyGraphAndWorkCeilingAcceptsItsBoundaryAndRejectsNext(String axis) {
        var f=new Fixture();var root=f.create(f.goal(4,4));var ctx=f.capture(root,PlanningBudget.defaults()).toCompletableFuture().join();
        var nodes=new ArrayList<Plan.Node>();
        if(axis.equals("nodes")||axis.equals("createdWork"))for(int i=0;i<8;i++)nodes.add(new Plan.Node(i,i<4?0:1,1,List.of()));
        else {nodes.add(new Plan.Node(0,0,4,List.of()));nodes.add(new Plan.Node(1,1,4,List.of(0)));}
        String json=Plan.encode(new Plan.Proposal(nodes));var base=new PlanningBudget(8,3,16,8,8192,8192,1024,10);
        var accepted=new PlanValidator(base).validate(json,ctx,f.limits(),f,f.tick);assertTrue(accepted.accepted(),accepted.toString());
        int n=8,d=3,e=16,c=8,o=8192,w=1024;
        switch(axis){case "nodes"->n=7;case "depth"->d=1;case "edges"->{
            nodes.set(1,new Plan.Node(1,1,2,List.of(0)));nodes.add(new Plan.Node(2,1,2,List.of(0,1)));json=Plan.encode(new Plan.Proposal(nodes));e=2;}
            case "createdWork"->c=7;case "output"->o=json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length-1;
            case "work"->w=accepted.work()-1;}
        var denied=new PlanValidator(new PlanningBudget(n,d,e,c,8192,o,w,10)).validate(json,ctx,f.limits(),f,f.tick);
        assertFalse(denied.accepted(),axis);assertEquals(Reason.BUDGET_EXHAUSTED,denied.reason());
        if(axis.equals("depth"))assertTrue(new PlanValidator(new PlanningBudget(8,2,16,8,8192,8192,1024,10)).validate(json,ctx,f.limits(),f,f.tick).accepted());
        if(axis.equals("edges"))assertTrue(new PlanValidator(new PlanningBudget(8,3,3,8,8192,8192,1024,10)).validate(json,ctx,f.limits(),f,f.tick).accepted());
        if(axis.equals("output"))assertTrue(new PlanValidator(new PlanningBudget(8,3,16,8,8192,o+1,1024,10)).validate(json,ctx,f.limits(),f,f.tick).accepted());
        if(axis.equals("work"))assertTrue(new PlanValidator(new PlanningBudget(8,3,16,8,8192,8192,w+1,10)).validate(json,ctx,f.limits(),f,f.tick).accepted());
    }
    @Test void insufficientKnownResourcesCannotBeManufacturedByQuantitiesOrHistory() {
        var f=new Fixture();f.crops=4;var check=f.check(f.valid());assertEquals(Plan.Diagnostic.FABRICATED_RESOURCE,check.diagnostic());
        assertEquals(Reason.RESOURCE_MISSING,check.reason());assertEquals(1,f.jobs.snapshot().jobs().size());
    }
    @Test void contextByteBoundaryIsChargedBeforeRefusalAndCannotMintARetry() {
        for(int extra:new int[]{0,-1}) {
            var f=new Fixture();var root=f.create();var ctx=f.capture(root,f.budget).toCompletableFuture().join();
            int bytes=ctx.prompt().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            f.budget=new PlanningBudget(8,3,16,8,bytes+extra,8192,512,10);f.planner=f.makePlanner();
            var view=f.advance(f.planner.propose(root.id(),f.owner),5);
            assertEquals(extra==0?GoalPlanner.State.QUEUED:GoalPlanner.State.REJECTED,view.state());
            assertEquals(extra==0?1:0,f.model.calls.size());assertEquals(1L,f.jobs.query(root.id(),f.owner).usage().get(Budgets.Kind.CANDIDATES));
            if(extra<0)assertEquals(Reason.BUDGET_EXHAUSTED,view.reason());
        }
    }
    @Test void freshRuntimeOwnersCannotRestoreSpentCandidateOrByteAllowances() {
        var f=new Fixture();var root=f.create();
        for(int i=0;i<2;i++){var v=f.advance(f.planner.propose(root.id(),f.owner),5);f.model.calls.get(i).respond("{}");f.advance(v,5);}
        f.planner.close();f.broker.close();var model=new FakeModel();
        var recovered=new JobLifecycleStore(f.disk.state,f.disk,JobLifecycleStore.privateJobs(f::controls),c->{},PlanningGoals.catalog(),JobLifecycleStoreTest.environment(),f.clock,Jobs.Settings.defaults(),false);
        var broker=new AIWorkBroker(model,f.clock,AIWorkBroker.Settings.defaults(),AIWorkBroker.sessionLimits(f.clock.millis()),r->true,AIWorkBroker.Sharing.privateScopes());
        var planner=new GoalPlanner(recovered,broker,f,f.budget,f.clock,f.planDisk,(a,b)->CompletableFuture.completedFuture(b),(t,c)->t.owner().equals(c));
        var view=planner.propose(root.id(),f.owner);for(int i=0;i<5;i++){recovered.tick();broker.step();planner.tick();}
        assertEquals(Reason.BUDGET_EXHAUSTED,planner.view(view.id(),f.owner).reason());assertEquals(0,model.calls.size());
        assertEquals(65536L,recovered.query(root.id(),f.owner).usage().get(Budgets.Kind.INPUT_BYTES));assertTrue(recovered.query(root.id(),f.owner).children().isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"cancel","storage","size"})
    void cancelledFailedOrOversizedContextCannotQueueUnchargedRetries(String mode) {
        var f=new Fixture();var root=f.create();f.captureHeld=mode.equals("cancel");f.captureFail=mode.equals("storage");
        if(mode.equals("size")){f.budget=new PlanningBudget(8,3,16,8,8192,8192,200,10);f.planner=f.makePlanner();}
        for(int i=0;i<2;i++) {
            var view=f.advance(f.planner.propose(root.id(),f.owner),2);
            if(f.captureHeld){assertTrue(f.planner.cancelProposal(view.id(),f.owner));f.held.complete(f.prepared);}
            view=f.advance(view,5);assertEquals(f.captureHeld?GoalPlanner.State.CANCELLED:GoalPlanner.State.REJECTED,view.state());
            assertEquals(i+1L,f.jobs.query(root.id(),f.owner).usage().get(Budgets.Kind.CANDIDATES));
        }
        var exhausted=f.advance(f.planner.propose(root.id(),f.owner),5);
        assertEquals(Reason.BUDGET_EXHAUSTED,exhausted.reason());assertEquals(mode.equals("size")?0:2,f.captures);assertEquals(2,f.routes);assertEquals(0,f.model.calls.size());
        assertEquals(2L*f.budget.work(),f.jobs.query(root.id(),f.owner).usage().get(Budgets.Kind.INSTRUCTIONS));assertTrue(f.jobs.query(root.id(),f.owner).children().isEmpty());
    }
}
