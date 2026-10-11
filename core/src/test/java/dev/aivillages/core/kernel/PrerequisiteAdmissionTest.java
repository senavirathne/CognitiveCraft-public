package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static dev.aivillages.core.kernel.Prerequisites.*;
import static org.junit.jupiter.api.Assertions.*;

final class PrerequisiteAdmissionTest {
    @TempDir Path temp;
    static final class Harness {
        final DeterministicPrerequisiteResolverTest.Fixture f=new DeterministicPrerequisiteResolverTest.Fixture();
        final JobLifecycleStoreTest.Memory disk=new JobLifecycleStoreTest.Memory(f.world);
        JobLifecycleStore.Policy override;
        long instructions=8192;
        JobLifecycleStore jobs=make(disk.state,false);
        PrerequisiteAdmission admission=new PrerequisiteAdmission(jobs,f.bounds,()->f.tick);
        JobLifecycleStore make(Jobs.Snapshot initial,boolean readOnly) {
            return new JobLifecycleStore(initial,disk,override==null?JobLifecycleStore.privateJobs((actor,owner)->f.allowed&&owner.equals(f.owner)):override,c->{},
                    id->id.equals(CropDelivery.ID)?Optional.of(CropDelivery.SPEC):Optional.empty(),JobLifecycleStoreTest.environment(),f.clock,Jobs.Settings.defaults(),readOnly);
        }
        Jobs.Job create(int quantity) {
            UUID id=UUID.randomUUID();assertTrue(jobs.create(id,id,JobLifecycleStoreTest.request(f.actor,quantity),f.owner,
                    JobLifecycleStoreTest.observation(),allowance(),List.of()).accepted());ack();return jobs.query(id,f.owner);
        }
        Budgets.Limits allowance(){return new Budgets.Limits(Map.of(Budgets.Kind.INSTRUCTIONS,instructions,Budgets.Kind.CALLS,256L,
                Budgets.Kind.OBSERVATIONS,512L,Budgets.Kind.ATTEMPTED_EFFECTS,64L,Budgets.Kind.COMMITTED_EFFECTS,64L,Budgets.Kind.ELAPSED_TICKS,600L),11_000);}
        void ack(){jobs.tick();assertTrue(jobs.ready(),String.valueOf(jobs.lastFailure()));}
        Jobs.Job current(Jobs.Job job){return jobs.query(job.id(),f.owner);}
        Need need(Jobs.Job job) {job=current(job);return new Need(job.id(),job.guard(),job.request(),"minecraft:wheat",
                ((IntValue)job.request().request().arguments().get("amount")).value(),AllocationPolicy.ATTRIBUTABLE_WHEAT);}
        Proposal propose(Jobs.Job... roots){return f.resolve(Arrays.stream(roots).map(this::need).toArray(Need[]::new));}
        Admission submit(Proposal proposal){return admission.submit(proposal,f.owner,f.catalog,f.supplies,f.methods,
                n->jobs.mayControl(n.demand(),n.guard().generation(),n.origin()));}
        Jobs.Job produce(UUID id,long quantity,ExecutionStatus status) {
            return produce(id,quantity,status,false);
        }
        Jobs.Job produce(UUID id,long quantity,ExecutionStatus status,boolean unitReceipts) {
            Jobs.Job job=jobs.query(id,f.owner);
            assertTrue(jobs.assign(id,job.guard(),UUID.randomUUID(),job.responsible(),JobLifecycleStoreTest.REF,
                    List.of(JobLifecycleStoreTest.REF),job.allowance(),f.owner).accepted());ack();job=jobs.query(id,f.owner);var attempt=job.current();
            var outcome=new Execution(status,JobLifecycleStoreTest.reason(status),quantity,status==ExecutionStatus.SUCCEEDED?
                    new EvidenceRef(attempt.id().toString(),"crop-delivery:1",f.owner.scope().domainId().toString()):null);
            var receipts=unitReceipts?java.util.stream.LongStream.range(0,quantity).boxed()
                    .flatMap(i->JobLifecycleStoreTest.receipts(attempt,1).stream()).toList():JobLifecycleStoreTest.receipts(attempt,quantity);
            assertTrue(jobs.recordExecutionResult(id,job.guard(),new Jobs.Report(attempt.id(),attempt.generation(),quantity,
                    Map.of(Budgets.Kind.CALLS,1L),receipts,outcome),f.owner).accepted());ack();return jobs.query(id,f.owner);
        }
    }
    @Test void compoundAdmissionUsesOnlyOwnedJobsAndDurableUsageWithOriginalDeadline() {
        var h=new Harness();var root=h.create(3);var p=h.propose(root);var result=h.submit(p);
        assertEquals(ResolutionStatus.RESOLVED,result.status());assertEquals(Jobs.Code.PENDING,result.publication().code());h.ack();
        root=h.current(root);var child=h.jobs.query(result.producers().getFirst(),h.f.owner);
        assertEquals(List.of(child.id()),root.children());assertEquals(List.of(child.id()),root.dependencies());assertEquals(root.id(),child.parent());
        assertEquals(Jobs.State.WAITING,root.state());assertEquals(Jobs.State.READY,child.state());
        assertTrue(root.usage().get(Budgets.Kind.INSTRUCTIONS)>p.metrics().work());assertEquals(root.allowance().deadlineEpochMillis(),child.allowance().deadlineEpochMillis());
        assertEquals(0,child.allowance().maximum(Budgets.Kind.INPUT_BYTES));assertEquals(0,child.allowance().maximum(Budgets.Kind.OUTPUT_BYTES));
        assertEquals(0,root.fulfilled());assertTrue(h.jobs.snapshot().allocations().isEmpty());
    }
    @Test void existingStockDoesNotFalsifyAttributableJobCompletion() {
        var h=new Harness();var root=h.create(3);h.f.counts.put("minecraft:wheat",3L);var p=h.propose(root);assertTrue(p.nodes().isEmpty());
        var result=h.submit(p);assertEquals(ResolutionStatus.RESOLVED,result.status());h.ack();root=h.current(root);
        assertEquals(Jobs.State.READY,root.state());assertEquals(0,root.fulfilled());assertTrue(root.children().isEmpty());assertTrue(h.jobs.snapshot().allocations().isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"stockRemoved","stockArrived","identity","catalog","method","scope"})
    void proposalRevalidatesEveryAuthorityAndObservationBeforeAnyChildIsPublished(String change) {
        var h=new Harness();var root=h.create(3);h.f.counts.put("minecraft:wheat",change.equals("stockRemoved")?1L:0L);
        var p=h.propose(root);assertTrue(p.accepted());int jobsBefore=h.jobs.snapshot().jobs().size();
        Supplies supplies=h.f.supplies;Methods methods=h.f.methods;Catalog catalog=h.f.catalog;
        switch(change) {
            case "stockRemoved" -> h.f.counts.put("minecraft:wheat",0L);
            case "stockArrived" -> h.f.counts.put("minecraft:wheat",1L);
            case "identity" -> supplies=(n,item)->{var s=h.f.supplies.inspect(n,item);return new Supply(item,s.status(),s.available(),"replacement",s.observation(),s.tick(),s.inspected(),s.reason());};
            case "catalog" -> catalog=new Catalog(2,catalog.recipes(),catalog.productions(),catalog.unsupportedOutputs(),true);
            case "method" -> methods=(production,batches,output,owner)->{var d=h.f.methods.resolve(production,batches,output,owner).decision();
                return new MethodSelection(new CapabilityResolver.Decision(new Resolution(ResolutionStatus.RESOLVED,null,new ArtifactRef(CropDelivery.ID,"b".repeat(64)),"changed"),
                        d.selectedBindings(),d.constraints(),d.observation(),2,d.candidates(),0,0,true,false,null),0);};
            case "scope" -> h.f.allowed=false;
        }
        var result=h.admission.submit(p,h.f.owner,catalog,supplies,methods,n->h.f.allowed);
        assertNotEquals(ResolutionStatus.RESOLVED,result.status());assertEquals(jobsBefore,h.jobs.snapshot().jobs().size());assertTrue(h.current(root).children().isEmpty());
        if(result.publication().accepted()){h.ack();assertTrue(h.current(root).usage().get(Budgets.Kind.INSTRUCTIONS)>=p.metrics().work());}
        if(change.equals("scope"))assertEquals(Reason.AUTHORITY_DENIED,result.reason());else assertEquals(Reason.STALE_OBSERVATION,result.reason());
    }
    @Test void replayIsIdempotentAndDoesNotRenewOrRefundBudgets() {
        var h=new Harness();var root=h.create(3);var p=h.propose(root);assertEquals(ResolutionStatus.RESOLVED,h.submit(p).status());h.ack();
        var before=h.jobs.snapshot();var repeat=h.submit(p);assertEquals(Jobs.Code.DUPLICATE,repeat.publication().code());assertEquals(before,h.jobs.snapshot());
        var fresh=h.propose(root);assertEquals(p.nodes().getFirst().id(),fresh.nodes().getFirst().id());assertEquals(Jobs.Code.DUPLICATE,h.submit(fresh).publication().code());
        assertEquals(before,h.jobs.snapshot());
    }
    @Test void compatibleRootsShareOneProducerAndEachRealOutputUnitCanBeCreditedOnlyOnce() {
        var h=new Harness();var first=h.create(3);var second=h.create(2);var p=h.propose(first,second);assertEquals(1,p.nodes().size());
        var result=h.submit(p);assertEquals(ResolutionStatus.RESOLVED,result.status());h.ack();UUID producer=result.producers().getFirst();
        assertEquals(List.of(producer),h.current(first).dependencies());assertEquals(List.of(producer),h.current(second).dependencies());
        assertEquals(1,h.current(first).children().size()+h.current(second).children().size());
        var child=h.produce(producer,5,ExecutionStatus.SUCCEEDED);var attempt=child.current();
        UUID receipt=attempt.receipts().stream().filter(r->r.stage()==CropDelivery.Stage.DEPOSIT).findFirst().orElseThrow().receiptId();
        for(var intent:p.allocations()) {var demand=h.jobs.query(intent.demand(),h.f.owner);
            assertTrue(h.admission.allocate(intent,attempt.id(),receipt,demand.guard(),h.f.owner).accepted());h.ack();}
        assertEquals(5,h.jobs.snapshot().allocations().stream().mapToLong(Jobs.Allocation::quantity).sum());assertEquals(Jobs.State.SUCCEEDED,h.current(first).state());assertEquals(Jobs.State.SUCCEEDED,h.current(second).state());
        var before=h.jobs.snapshot();var repeated=p.allocations().getFirst();assertEquals(Jobs.Code.DUPLICATE,
                h.admission.allocate(repeated,attempt.id(),receipt,h.jobs.query(repeated.demand(),h.f.owner).guard(),h.f.owner).code());assertEquals(before,h.jobs.snapshot());
        var third=h.create(1);assertEquals(Reason.RESOURCE_MISSING,h.admission.allocate(new AllocationIntent(third.id(),producer,1,AllocationPolicy.ATTRIBUTABLE_WHEAT),
                attempt.id(),receipt,third.guard(),h.f.owner).reason());
    }
    @Test void runtimeCycleAfterAdmissionIsRejectedAtomicallyByTheExistingOwner() {
        var h=new Harness();var root=h.create(2);var p=h.propose(root);var result=h.submit(p);h.ack();var child=h.jobs.query(result.producers().getFirst(),h.f.owner);
        var before=h.jobs.snapshot();assertFalse(h.jobs.addDependency(child.id(),child.guard(),root.id(),h.f.owner).accepted());assertEquals(before,h.jobs.snapshot());
    }
    @Test void cancellationKeepsPartialDeliveryAndCannotReactivateAProducer() {
        var h=new Harness();var root=h.create(3);var p=h.propose(root);var result=h.submit(p);h.ack();
        var child=h.produce(result.producers().getFirst(),1,ExecutionStatus.BLOCKED);assertEquals(1,child.fulfilled());
        assertTrue(h.jobs.cancel(root.id(),h.current(root).guard(),h.f.owner).accepted());h.ack();var before=h.jobs.snapshot();
        var replay=h.submit(p);assertEquals(Reason.INTERRUPTED,replay.reason());assertEquals(before,h.jobs.snapshot());assertEquals(1,h.jobs.query(child.id(),h.f.owner).fulfilled());
    }
    @Test void currentLeaseClaimsAreSubtractedAndInspectionNeverAcquiresAClaim() {
        var h=new Harness();var one=h.create(3);var two=h.create(3);var disk=new ResourceLeases.Snapshot[]{ResourceLeases.Snapshot.empty(h.f.world)};
        var service=new ResourceLeaseService(disk[0],(expected,next)->{assertEquals(disk[0],expected);disk[0]=next;return CompletableFuture.completedFuture(next);},
                new JobLeaseOwners(h.jobs,h.f.clock),(r,owner)->new ResourceLeases.Observation(r,ObservationStatus.PRESENT,5,"real-target",100,1,null),
                ResourceLeases.Settings.fixture(),()->100,h.f.clock,false);service.tick();
        var resource=ResourceLeases.Resource.stock(h.f.world,((ContainerValue)one.request().request().arguments().get("destination")).value());
        var claim=service.acquire(new ResourceLeases.Owner(one.id(),0),List.of(new ResourceLeases.Demand(resource,3)),100,h.f.owner);
        assertEquals(ResourceLeases.Code.PENDING,claim.code());service.tick();var before=service.snapshot();
        var supplies=new LeasePrerequisiteSupplies(service,()->100);var seen=supplies.inspect(h.need(two),"minecraft:wheat");
        assertEquals(2,seen.available());assertEquals(ObservationStatus.PRESENT,seen.status());assertEquals(before,service.snapshot());
    }
    @Test void failedAndExpiredWorkCannotPublishChildrenOrHideAStorageFailure() {
        var h=new Harness();var root=h.create(1);var p=h.propose(root);h.f.clock.now=11_001;
        assertEquals(Reason.BUDGET_EXHAUSTED,h.submit(p).reason());assertTrue(h.current(root).children().isEmpty());
        h.f.clock.now=1000;h.disk.fail=true;var result=h.submit(p);assertEquals(Jobs.Code.PENDING,result.publication().code());h.jobs.tick();
        assertFalse(h.jobs.ready());assertEquals(Reason.STORAGE_UNAVAILABLE,h.jobs.lastFailure());assertTrue(h.current(root).children().isEmpty());
    }
    @Test void durableJobJournalReopenRetainsProducerIdentityAndNoExpansionLedger() throws Exception {
        var h=new Harness();var root=h.create(2);var created=h.jobs.snapshot();var p=h.propose(root);h.submit(p);h.ack();UUID child=p.nodes().getFirst().id();
        try(var journal=JobJournal.open(temp,h.f.world)){journal.replace(journal.snapshot(),created);journal.replace(created,h.jobs.snapshot());}
        try(var journal=JobJournal.open(temp,h.f.world)) {
            h.disk.state=journal.snapshot();h.jobs=h.make(h.disk.state,false);h.admission=new PrerequisiteAdmission(h.jobs,h.f.bounds,()->100);
            assertEquals(List.of(child),h.current(root).children());var fresh=h.propose(root);
            assertEquals(child,fresh.nodes().getFirst().id());assertEquals(Jobs.Code.DUPLICATE,h.submit(fresh).publication().code());assertEquals(2,h.jobs.snapshot().jobs().size());
        }
    }
    @Test void separateNativeSizedReceiptsRespectTheProducerDemandTotalAcrossPartialAllocations() {
        var h=new Harness();var root=h.create(3);var p=h.propose(root);h.submit(p);h.ack();
        var child=h.produce(p.nodes().getFirst().id(),3,ExecutionStatus.SUCCEEDED,true);var attempt=child.current();
        var receipts=attempt.receipts().stream().filter(r->r.stage()==CropDelivery.Stage.DEPOSIT).toList();assertEquals(3,receipts.size());
        var intent=new AllocationIntent(root.id(),child.id(),2,AllocationPolicy.ATTRIBUTABLE_WHEAT);
        for(int i=0;i<2;i++){assertTrue(h.admission.allocate(intent,1,attempt.id(),receipts.get(i).receiptId(),h.current(root).guard(),h.f.owner).accepted());h.ack();}
        assertEquals(Reason.RESOURCE_MISSING,h.admission.allocate(intent,1,attempt.id(),receipts.get(2).receiptId(),h.current(root).guard(),h.f.owner).reason());
        assertEquals(2,h.jobs.snapshot().allocations().stream().mapToLong(Jobs.Allocation::quantity).sum());assertEquals(Jobs.State.READY,h.current(root).state());
    }
    @Test void actualRetrievalIndexAndResolverSelectExistingMetadataWithoutAResearchPort() {
        var f=new DeterministicPrerequisiteResolverTest.Fixture();var need=f.need("minecraft:wheat",2);
        CapabilityCatalog capabilities=id->id.equals(CropDelivery.ID)?Optional.of(CropDelivery.SPEC):Optional.empty();
        var candidate=new VersionedSkillRepository.Candidate(JobLifecycleStoreTest.REF,AdmissionStatus.ADMITTED,
                new Compatibility(JobLifecycleStoreTest.REF,CompatibilityStatus.COMPATIBLE,List.of(),"fixture"),VersionedSkillRepository.Integrity.VERIFIED);
        var descriptor=new ArtifactDescriptor(JobLifecycleStoreTest.REF,1,CropDelivery.SPEC.parameters(),Type.INT,Set.of(Effect.HARVEST),List.of(),List.of());
        var exact=new CapabilityResolver.CandidateSource(){
            public VersionedSkillRepository.Page page(CapabilityId id,String after,int size){return new VersionedSkillRepository.Page(after.isEmpty()?List.of(candidate):List.of(),"",true,1);}
            public Optional<ArtifactDescriptor> descriptor(ArtifactRef key){return Optional.of(descriptor);}
            public long revision(){return 1;}
        };
        var epoch=new CapabilityRetrievalIndex.Epoch(1,1,0,true);
        var source=new CapabilityRetrievalIndex.Source(){
            public CapabilityRetrievalIndex.Epoch epoch(){return epoch;}
            public CapabilityResolver.CandidateSource exact(){return exact;}
            public CapabilityRetrievalIndex.CatalogSnapshot snapshot(){return new CapabilityRetrievalIndex.CatalogSnapshot(){
                boolean done;
                public CapabilityRetrievalIndex.Epoch epoch(){return epoch;}
                public int size(){return 1;}
                public boolean complete(){return done;}
                public List<CapabilityRetrievalIndex.Metadata> next(int maximum){done=true;return List.of(new CapabilityRetrievalIndex.Metadata(candidate,Set.of("crop")));}
            };}
        };
        var registry=new CapabilityRetrievalIndex.LookupRegistry(Map.of("deliver wheat",CropDelivery.ID),Map.of(CropDelivery.ID,Set.of("crop")),capabilities);
        var index=new CapabilityRetrievalIndex(temp,new CapabilityRetrievalIndex.Identity(f.world,UUID.randomUUID()),source,registry,capabilities,CapabilityRetrievalIndex.Limits.defaults(),()->100);
        var rebuild=index.beginRebuild();CapabilityRetrievalIndex.Progress progress;int slices=0;
        do{progress=rebuild.advance();assertTrue(++slices<10);}while(!progress.terminal());assertEquals(CapabilityRetrievalIndex.State.READY,progress.state());
        var resolver=new CapabilityResolver.Engine(capabilities,GatewayPrimitives.instance(),index.candidates(),index.authoritativeFallback(),CapabilityResolver.cropDeliverySupport(),
                (actor,owner)->owner.equals(f.owner),(actor,ref,owner)->true,(actor,effect,owner)->true,CapabilityResolver.cropPrerequisites(),()->100,CapabilityResolver.Limits.defaults());
        Methods methods=(production,batches,output,owner)->{
            var args=new HashMap<>(owner.bound().request().arguments());args.put("amount",new IntValue(Math.toIntExact(output)));
            var bound=new ValidatedRequest(new CapabilityRequest(CropDelivery.ID,args),owner.origin(),owner.bound().observation());var area=((AreaValue)args.get("source")).value();
            var observed=new ObservationSnapshot(bound.observation(),ObservationStatus.PRESENT,"source:"+UUID.nameUUIDFromBytes(area.toString().getBytes(StandardCharsets.UTF_8)),100,9,
                    Map.of("mature_wheat",64L,"unknown_cells",0L));return new MethodSelection(resolver.resolve(bound,observed),0);
        };
        var p=new DeterministicPrerequisiteResolver(f.bounds,()->100).resolve(List.of(need),f.catalog,f.supplies,methods,n->true,f.ledger(4096));
        assertTrue(p.accepted(),p.reason()+" "+p.metrics());assertEquals(JobLifecycleStoreTest.REF,p.nodes().getFirst().method());assertTrue(index.counters().pages()>0);assertEquals(0,index.counters().fallbackPages());index.close();
    }
    @Test void explicitExistingControlGrantPreservesOriginAndRevocationFencesReplay() {
        var h=new Harness();var foreign=JobLifecycleStoreTest.owner(h.f.world);boolean[] grant={false};
        h.override=new JobLifecycleStore.Policy(){
            public boolean mayRead(TrustedContext caller,Jobs.Job job){return caller.equals(h.f.owner)||grant[0]&&caller.equals(foreign);}
            public boolean mayControl(TrustedContext caller,Jobs.Job job){return mayRead(caller,job);}
            public boolean controls(ActorRef actor,TrustedContext origin){return h.f.allowed&&origin.equals(h.f.owner);}
        };
        h.jobs=h.make(h.disk.state,false);h.admission=new PrerequisiteAdmission(h.jobs,h.f.bounds,()->100);var root=h.create(2);
        assertThrows(SecurityException.class,()->h.jobs.query(root.id(),foreign));grant[0]=true;var p=h.propose(root);
        var result=h.admission.submit(p,foreign,h.f.catalog,h.f.supplies,h.f.methods,n->h.jobs.mayControl(n.demand(),n.guard().generation(),foreign));
        assertEquals(ResolutionStatus.RESOLVED,result.status());h.ack();assertEquals(h.f.owner,h.jobs.query(result.producers().getFirst(),foreign).origin());
        grant[0]=false;var before=h.jobs.snapshot();var denied=h.admission.submit(p,foreign,h.f.catalog,h.f.supplies,h.f.methods,n->h.jobs.mayControl(n.demand(),n.guard().generation(),foreign));
        assertEquals(ResolutionStatus.UNAUTHORIZED,denied.status());assertEquals(Reason.AUTHORITY_DENIED,denied.reason());assertEquals(before,h.jobs.snapshot());
    }
    @Test void freshDuplicateExpansionStillConsumesTheAuthoritativeParentBudget() {
        var h=new Harness();h.instructions=600;var root=h.create(2);var first=h.propose(root);h.submit(first);h.ack();
        UUID producer=first.nodes().getFirst().id();long before=h.current(root).usage().get(Budgets.Kind.INSTRUCTIONS);int expansions=0;
        for(;;) {
            assertTrue(++expansions<=10,"Finite repeated-work envelope");var proposal=h.propose(root);
            var result=h.admission.submitExpanded(proposal,h.f.owner,h.f.catalog,h.f.supplies,h.f.methods,n->h.jobs.mayControl(n.demand(),n.guard().generation(),n.origin()));
            if(result.status()!=ResolutionStatus.RESOLVED){assertEquals(Reason.BUDGET_EXHAUSTED,result.reason());break;}
            assertEquals(Jobs.Code.PENDING,result.publication().code());h.ack();assertTrue(h.current(root).usage().get(Budgets.Kind.INSTRUCTIONS)>before);
            before=h.current(root).usage().get(Budgets.Kind.INSTRUCTIONS);assertEquals(List.of(producer),h.current(root).children());
        }
        assertEquals(2,h.jobs.snapshot().jobs().size());assertTrue(before<=600);assertEquals(11_000,h.current(root).allowance().deadlineEpochMillis());
    }
}
