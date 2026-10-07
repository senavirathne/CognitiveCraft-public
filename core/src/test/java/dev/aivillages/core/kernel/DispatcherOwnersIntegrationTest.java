package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static dev.aivillages.core.kernel.ResourceLeases.*;
import static dev.aivillages.core.kernel.VersionedSkillRepository.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;
import static dev.aivillages.core.kernel.ExecutorJUnitTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real resolver/retrieval, job and lease owners, interpreter and survival gateway; fake physical world. */
class DispatcherOwnersIntegrationTest {
    @TempDir Path directory;
    class Scene implements AutoCloseable {
        final Fixture f=new Fixture();
        final GatewayChecks.FakeWorld world=new GatewayChecks.FakeWorld();
        final ExecutorService io=Executors.newSingleThreadExecutor();
        final ActorRef second=new ActorRef(new UUID(9,2),new UUID(9,3),DIM);
        final TrustedContext foreign=new TrustedContext(new PrincipalRef(new UUID(9,4)),new ScopeRef(OWNER.scope().worldId(),new UUID(9,5)));
        final Cuboid area=new Cuboid(DIM,0,64,0,4,64,0);
        final CitizenRegistry citizens;
        final JobLifecycleStore jobs;
        final ResourceLeaseService leases;
        final SurvivalGateway raw;
        final LeasedGateway gateway;
        final WorkerDispatcher dispatcher;
        final CapabilityRetrievalIndex index;
        final List<SkillArtifact> artifacts=new ArrayList<>();
        final List<UUID> started=new ArrayList<>();
        final JobLifecycleStoreTest.Memory jobDisk=new JobLifecycleStoreTest.Memory(OWNER.scope().worldId());
        Snapshot leaseDisk=Snapshot.empty(OWNER.scope().worldId());
        int scopes, modelCalls;
        ArtifactRef blocked;
        boolean deny, failLeaseStorage;
        Scene(boolean alternatives) {
            for(int n=0;n<5;n++)world.crops.put(new SurvivalGateway.Cell(DIM,n,64,0),true);
            List<Object> cycle=List.of(call(Operation.MOVE_TO_SOURCE,Map.of("actor",Map.of("param","actor"),"source",Map.of("param","source")),"approached"),
                    call(Operation.HARVEST_NEXT_WHEAT,Map.of("actor",Map.of("param","actor"),"source",Map.of("param","source")),"harvested"),
                    call(Operation.PICKUP_TRACKED_WHEAT,Map.of("actor",Map.of("param","actor")),"picked"),
                    call(Operation.MOVE_TO_DESTINATION,Map.of("actor",Map.of("param","actor"),"destination",Map.of("param","destination")),"arrived"),
                    call(Operation.TRANSFER_WHEAT,Map.of("actor",Map.of("param","actor"),"destination",Map.of("param","destination"),"amount",Map.of("int",1L)),"delivered"));
            var body=List.<Object>of(Map.of("op","repeat","count",Map.of("param","amount"),"body",cycle),Map.of("op","result","value",Map.of("param","amount")));
            artifacts.add(f.compile(CropDelivery.ID,body,List.of(),AdmissionStatus.ADMITTED));
            if(alternatives) {
                var different=new ArrayList<Object>();different.add(call(Operation.OBSERVE_INVENTORY,Map.of("actor",Map.of("param","actor")),"observed"));different.addAll(body);
                artifacts.add(f.compile(CropDelivery.ID,different,List.of(),AdmissionStatus.ADMITTED));
                blocked=artifacts.stream().map(a->a.descriptor().ref()).min(Comparator.comparing(ArtifactRef::sha256)).orElseThrow();
            }
            citizens=new CitizenRegistry(new CitizenRegistry.Snapshot(OWNER.scope().worldId(),0,List.of(
                    new CitizenRegistry.Citizen(ACTOR,OWNER,null,CitizenRegistry.Availability.UNKNOWN),
                    new CitizenRegistry.Citizen(second,foreign,null,CitizenRegistry.Availability.UNKNOWN)),null),
                    (expected,next)->CompletableFuture.completedFuture(next),CitizenRegistry.privateAddresses(),f.clock,false);
            citizens.observeAvailability(ACTOR.citizenId(),CitizenRegistry.Availability.LOADED);citizens.tick();
            citizens.observeAvailability(second.citizenId(),CitizenRegistry.Availability.LOADED);citizens.tick();
            jobs=new JobLifecycleStore(jobDisk.state,jobDisk,JobLifecycleStore.privateJobs(citizens::controls),c->{},
                    f.specs,JobLifecycleStoreTest.environment(),f.clock,Jobs.Settings.defaults(),false);
            var owners=new JobLeaseOwners(jobs,f.clock);
            leases=new ResourceLeaseService(leaseDisk,(expected,next)->{
                if(failLeaseStorage)return CompletableFuture.failedFuture(new IllegalStateException("lease write failure"));
                assertEquals(leaseDisk,expected);leaseDisk=next;return CompletableFuture.completedFuture(next);
            },owners,(resource,owner)->new Observation(resource,ObservationStatus.PRESENT,
                    resource.kind()==ResourceLeases.Kind.STOCK?world.crops.size():1,"physical-source",world.tick,1,null),
                    Settings.production(),()->world.tick,f.clock,false);leases.tick();
            raw=new SurvivalGateway(world,(actor,effect,context)->!deny&&citizens.controls(actor,context),
                    new SurvivalGateway.Limits(4,128,64,16,256,1,200,32),f.clock);
            gateway=new LeasedGateway(raw,leases,(request,run)->LeasedGateway.cropWorkspaces(owners,request,run),()->world.tick,1200,100,4,128);
            var executor=new BoundedSkillExecutor(f.catalog,f.specs,GatewayPrimitives.instance(),(actor,artifact,context)->citizens.controls(actor,context),
                    (permit,request,limits)->{modelCalls++;throw new AssertionError("Models stopped");},new BoundedSkillExecutor.ControlPolicy(){
                        public boolean mayInspect(TrustedContext c,TrustedContext o,UUID id){return c.equals(o);}
                        public boolean mayCancel(TrustedContext c,TrustedContext o,UUID id){return c.equals(o);}
                    },gateway,gateway::releaseRun,f.clock,()->world.tick,new BoundedSkillExecutor.Settings(8,16,32,64));
            var exact=new CapabilityResolver.CandidateSource(){
                public Page page(CapabilityId id,String after,int maximum){var candidates=artifacts.stream().map(a->a.descriptor().ref())
                        .sorted(Comparator.comparing(ArtifactRef::sha256)).filter(ref->ref.capability().equals(id)&&ref.sha256().compareTo(after)>0)
                        .map(ref->new Candidate(ref,AdmissionStatus.ADMITTED,f.catalog.compatible.get(ref),Integrity.VERIFIED)).toList();
                    return new Page(candidates.size()>maximum?candidates.subList(0,maximum):candidates,
                            candidates.size()>maximum?candidates.get(maximum-1).ref().sha256():"",candidates.size()<=maximum,1);}
                public Optional<ArtifactDescriptor> descriptor(ArtifactRef ref){return Optional.ofNullable(f.catalog.bodies.get(ref)).map(SkillArtifact::descriptor);}
                public long revision(){return 1;}
            };
            var epoch=new CapabilityRetrievalIndex.Epoch(1,1,0,true);
            var source=new CapabilityRetrievalIndex.Source(){
                public CapabilityRetrievalIndex.Epoch epoch(){return epoch;}
                public CapabilityResolver.CandidateSource exact(){return exact;}
                public CapabilityRetrievalIndex.CatalogSnapshot snapshot(){return new CapabilityRetrievalIndex.CatalogSnapshot(){
                    boolean done;public CapabilityRetrievalIndex.Epoch epoch(){return epoch;}public int size(){return artifacts.size();}public boolean complete(){return done;}
                    public List<CapabilityRetrievalIndex.Metadata> next(int maximum){done=true;return exact.page(CropDelivery.ID,"",maximum).candidates().stream()
                            .map(candidate->new CapabilityRetrievalIndex.Metadata(candidate,Set.of("crop"))).toList();}
                };}
            };
            var registry=new CapabilityRetrievalIndex.LookupRegistry(Map.of(),Map.of(CropDelivery.ID,Set.of("crop")),f.specs);
            index=new CapabilityRetrievalIndex(directory.resolve(UUID.randomUUID().toString()),new CapabilityRetrievalIndex.Identity(OWNER.scope().worldId(),UUID.randomUUID()),source,registry,
                    f.specs,CapabilityRetrievalIndex.Limits.defaults(),f.clock::millis);
            var resolver=new CapabilityResolver.Engine(f.specs,GatewayPrimitives.instance(),index.candidates(),index.authoritativeFallback(),
                    CapabilityResolver.cropDeliverySupport(),citizens::controls,CapabilityResolver.enrolledWorldSkills(citizens::controls),
                    (actor,effect,context)->!deny&&citizens.controls(actor,context),(request,method,observed)->method.ref().equals(blocked)?Reason.RESOURCE_MISSING:null,
                    ()->world.tick,CapabilityResolver.Limits.defaults());
            var delegate=WorkerDispatcher.executorPort(executor,exact::descriptor);
            dispatcher=new WorkerDispatcher(jobs,citizens,(request,actor)->new ObservationSnapshot(new ObservationRef(UUID.randomUUID(),world.tick,DIM),
                    world.crops.isEmpty()?ObservationStatus.ABSENT:ObservationStatus.PRESENT,"source:"+UUID.nameUUIDFromBytes(area.toString().getBytes(StandardCharsets.UTF_8)),
                    world.tick,5,Map.of("mature_wheat",(long)world.crops.size(),"unknown_cells",0L)),resolver::resolve,
                    new WorkerDispatcher.ExecutionPort(){public List<ArtifactRef> pinned(ArtifactRef ref){return delegate.pinned(ref);}
                        public WorkerDispatcher.Start start(ValidatedRequest request,ArtifactRef ref,UUID run,Budgets.ExecutionLimits limits,Budgets.Ledger usage){
                            started.add(run);return delegate.start(request,ref,run,limits,usage);}},
                    WorkerDispatcher.leasedClaims(gateway),j->true,f.clock,()->world.tick,WorkerDispatcher.Settings.production());
        }
        UUID create(ActorRef actor,TrustedContext owner,int amount){var id=UUID.randomUUID();var request=new CapabilityRequest(CropDelivery.ID,Map.of("actor",new ActorValue(actor),"source",new AreaValue(area),"destination",new ContainerValue(DEST),"amount",new IntValue(amount)));
            assertTrue(jobs.create(id,id,request,owner,f.request.observation(),f.limits(4000,64,5000).total(),List.of()).accepted());jobs.tick();return id;}
        void step(){world.tick++;f.clock.advance(1);citizens.tick();jobs.tick();raw.tick();leases.tick();gateway.tick();index.maintain(io);dispatcher.step((scopes++&1)==0?OWNER:foreign);}
        Jobs.Job job(UUID id,TrustedContext owner){return jobs.query(id,owner);}
        void until(java.util.function.BooleanSupplier done){for(int n=0;n<1000&&!done.getAsBoolean();n++)step();assertTrue(done.getAsBoolean(),()->jobs.snapshot().jobs().stream().map(j->j.state()+" "+dispatcher.diagnostics(j.id(),j.origin())+" "+j.attempts().stream().map(a->a.terminal()).toList()).toList().toString());}
        public void close()throws Exception{dispatcher.close();index.close();io.shutdown();assertTrue(io.awaitTermination(5,TimeUnit.SECONDS));}
    }
    static Map<String,Object> call(Operation op,Map<String,Object> args,String into){return Map.of("op","call","kind","primitive","id",op.signature().id(),"version",1L,"fingerprint",op.signature().fingerprint(),"args",args,"into",into);}
    @Test void realKnownMethodDispatchesThroughEveryOwnerWithNoModelAndConservesFiveUnits()throws Exception{try(var s=new Scene(false)){var a=s.create(ACTOR,OWNER,3);var b=s.create(s.second,s.foreign,2);s.until(()->s.job(a,OWNER).state()==Jobs.State.SUCCEEDED&&s.job(b,s.foreign).state()==Jobs.State.SUCCEEDED);assertEquals(5,s.world.containerWheat);assertEquals(0,s.world.actorWheat);assertEquals(0,s.world.crops.size());assertEquals(0,s.modelCalls);assertEquals(2,s.started.size());assertEquals(5,s.job(a,OWNER).fulfilled()+s.job(b,s.foreign).fulfilled());assertTrue(s.index.counters().pages()>0||s.index.counters().fallbackPages()>0);assertThrows(SecurityException.class,()->s.dispatcher.diagnostics(a,s.foreign));}}
    @Test void realResolverKeepsBlockedFirstStrategyAndExecutesAdmittedAlternative()throws Exception{try(var s=new Scene(true)){var id=s.create(ACTOR,OWNER,2);s.until(()->s.job(id,OWNER).state()==Jobs.State.SUCCEEDED);var job=s.job(id,OWNER);assertNotEquals(s.blocked,job.current().executions().getFirst().artifact());assertEquals(2,s.world.containerWheat);assertEquals(0,s.modelCalls);assertTrue(s.dispatcher.diagnostics(id,OWNER).getFirst().alternatives().stream().anyMatch(c->c.ref().equals(s.blocked)&&c.state()==CapabilityResolver.CandidateState.BLOCKED));}}
    @Test void resourceConflictDoesNotConsumeAnAssignmentOrBecomeResearch()throws Exception{try(var s=new Scene(false)){var a=s.create(ACTOR,OWNER,3);var b=s.create(s.second,s.foreign,3);s.until(()->s.job(a,OWNER).state()==Jobs.State.ACTIVE||s.job(b,s.foreign).state()==Jobs.State.ACTIVE);for(int n=0;n<150;n++)s.step();assertTrue(s.world.containerWheat<=5);assertFalse(s.job(a,OWNER).fulfilled()==3&&s.job(b,s.foreign).fulfilled()==3);assertEquals(0,s.modelCalls);assertTrue(s.jobs.snapshot().jobs().stream().mapToInt(j->j.attempts().size()).sum()<=2);}}
    @Test void currentAuthorityFailureCannotCreateFirstPhysicalEffect()throws Exception{try(var s=new Scene(false)){var id=s.create(ACTOR,OWNER,3);s.until(()->s.job(id,OWNER).current()!=null);s.deny=true;for(int n=0;n<50;n++)s.step();assertEquals(0,s.world.harvests);assertEquals(0,s.world.containerWheat);assertEquals(0,s.modelCalls);}}
    @Test void leaseStorageFailurePreventsInterpreterEffectsAndNewRuns()throws Exception{try(var s=new Scene(false)){var id=s.create(ACTOR,OWNER,3);s.failLeaseStorage=true;for(int n=0;n<50;n++)s.step();assertEquals(Reason.STORAGE_UNAVAILABLE,s.leases.failure());assertEquals(0,s.world.harvests);assertEquals(0,s.started.size());}}
    @Test void externalRemovalAfterGrantIsReobservedBeforeMutation()throws Exception{try(var s=new Scene(false)){var id=s.create(ACTOR,OWNER,3);s.until(()->!s.leases.snapshot().leases().isEmpty());s.world.crops.clear();for(int n=0;n<80;n++)s.step();assertEquals(0,s.world.harvests);assertEquals(0,s.world.containerWheat);assertTrue(s.job(id,OWNER).state()!=Jobs.State.SUCCEEDED);}}
    @Test void durablePartialEffectsSurviveWorkerLossAndOnlyRemainingResponsibilityIsBound()throws Exception{try(var s=new Scene(false)){var id=s.create(ACTOR,OWNER,5);s.until(()->s.world.containerWheat==2);s.citizens.observeAvailability(ACTOR.citizenId(),CitizenRegistry.Availability.UNLOADED);s.citizens.tick();s.until(()->s.job(id,OWNER).state()==Jobs.State.READY||s.job(id,OWNER).state()==Jobs.State.WAITING);assertEquals(2,s.job(id,OWNER).fulfilled());assertEquals(2,s.world.containerWheat);assertEquals(0,s.raw.activeRuns());assertEquals(0,s.modelCalls);}}
}
