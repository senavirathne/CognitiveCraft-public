package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Mandates.*;
import static dev.aivillages.core.kernel.MandateRegistryTest.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real citizen/Job/dispatcher/lease owners; stop confirmation and mechanics are explicit test ports. */
class MandateOwnerIntegrationTest {
    static final class Scene {
        final MandateRegistryTest.Scene authority=new MandateRegistryTest.Scene();
        final CitizenRegistry citizens;
        final JobLifecycleStoreTest.Memory jobDisk=new JobLifecycleStoreTest.Memory(WORLD);
        final JobLifecycleStore jobs;
        final MandateJobExecutions executionOwners;
        final MandateWorkers workers;
        final ResourceLeaseService leases;
        final WorkerDispatcher dispatcher;
        final Map<UUID,List<ResourceLeases.Ref>> claims=new HashMap<>();
        final List<Jobs.Cancellation> cancellations=new ArrayList<>();
        ResourceLeases.Snapshot leaseDisk=ResourceLeases.Snapshot.empty(WORLD);
        Handle handle;
        UUID parent,jobId;
        long tick;
        boolean stopConfirmed,coordinatorAvailable=true;
        Scene() {
            citizens=new CitizenRegistry(new CitizenRegistry.Snapshot(WORLD,0,List.of(new CitizenRegistry.Citizen(ACTOR,OWNER,"Private Captain",CitizenRegistry.Availability.LOADED),
                    new CitizenRegistry.Citizen(actor(101),FOREIGN,"Other Private",CitizenRegistry.Availability.LOADED)),null),
                    (a,b)->CompletableFuture.completedFuture(b),(a,b)->false,authority.clock,false);
            citizens.observeAvailability(ACTOR.citizenId(),CitizenRegistry.Availability.LOADED);citizens.tick();
            citizens.observeAvailability(actor(101).citizenId(),CitizenRegistry.Availability.LOADED);citizens.tick();
            jobs=new JobLifecycleStore(jobDisk.state,jobDisk,JobLifecycleStore.privateJobs((a,c)->citizens.controls(a,c)||authority.registry.controls(a,Effect.OBSERVE,c)),
                    cancellations::add,id->Optional.of(CropDelivery.SPEC),JobLifecycleStoreTest.environment(),authority.clock,Jobs.Settings.defaults(),false);
            executionOwners=new MandateJobExecutions(()->jobs);
            authority.registry=new MandateRegistry(authority.memory.state,authority.memory,new MandateRegistry.References(){
                public boolean authority(ActorRef actor,Effect effect,TrustedContext issuer){return citizens.controls(actor,issuer);}
                public boolean available(TrustedContext caller){return !caller.equals(COORDINATOR)||coordinatorAvailable;}
            },executionOwners,authority.clock,Limits.defaults(),false);
            parent=authority.root().id();workers=new MandateWorkers(citizens,authority.registry);
            leases=new ResourceLeaseService(leaseDisk,(a,b)->{assertEquals(leaseDisk,a);leaseDisk=b;return CompletableFuture.completedFuture(b);},new JobLeaseOwners(jobs,authority.clock),
                    (resource,owner)->new ResourceLeases.Observation(resource,ObservationStatus.PRESENT,1,"actual-target",tick,1,null),ResourceLeases.Settings.fixture(),()->tick,authority.clock,false);
            leases.tick();
            dispatcher=new WorkerDispatcher(jobs,workers,(request,actor)->new ObservationSnapshot(JobLifecycleStoreTest.observation(),ObservationStatus.PRESENT,"test-port",tick,1,Map.of("mature_wheat",2L)),
                    (bound,observed)->new CapabilityResolver.Decision(new Resolution(ResolutionStatus.RESOLVED,null,ARTIFACT,"known-test-method"),bound,Set.of(),observed,1,List.of(),1,1,true,false,null),
                    new WorkerDispatcher.ExecutionPort(){
                        public List<ArtifactRef> pinned(ArtifactRef ref){return List.of(ref);}
                        public WorkerDispatcher.Start start(ValidatedRequest bound,ArtifactRef ref,UUID run,Budgets.ExecutionLimits limit,Budgets.Ledger usage){
                            assertTrue(claims.containsKey(run));handle=new Handle(jobs.snapshot().jobs().getFirst(),usage);return new WorkerDispatcher.Start(handle,null);
                        }
                    },new WorkerDispatcher.Claims(){
                        public ResourceLeases.Result prepare(ValidatedRequest request,RunCorrelation run){
                            var permission=authority.registry.prepare(request,run);
                            if(permission.status()==Status.PENDING)return new ResourceLeases.Result(ResourceLeases.Code.PENDING,null,null,List.of(),0,0);
                            if(!permission.accepted())return new ResourceLeases.Result(ResourceLeases.Code.REJECTED,permission.reason(),null,List.of(),0,0);
                            if(claims.containsKey(run.runId()))return leases.validate(claims.get(run.runId()).getFirst(),request.context());
                            var result=leases.acquire(new JobLeaseOwners(jobs,authority.clock).byRun(request,run),List.of(new ResourceLeases.Demand(ResourceLeases.Resource.space(WORLD,SOURCE),1)),100,request.context());
                            if(result.group()!=null)claims.put(run.runId(),result.leases());return result;
                        }
                        public ResourceLeases.Result validate(ValidatedRequest request,RunCorrelation run){return prepare(request,run);}
                        public void release(UUID run){if(!leases.ready())return;var refs=claims.get(run);if(refs==null)return;var result=leases.release(refs,COORDINATOR);
                            if(result.code()!=ResourceLeases.Code.PENDING)claims.remove(run);}
                        public boolean released(UUID run){return !claims.containsKey(run);}
                    },job->true,authority.clock,()->tick,new WorkerDispatcher.Settings(4,3,2,2,8,16,200,10000));
            var args=new HashMap<>(request(COORDINATOR).request().arguments());args.put("amount",new IntValue(2));
            jobId=UUID.randomUUID();assertTrue(jobs.create(jobId,jobId,new CapabilityRequest(CropDelivery.ID,args),COORDINATOR,JobLifecycleStoreTest.observation(),budget(500,10000),List.of()).accepted());jobs.tick();
        }
        Jobs.Job job(){return jobs.query(jobId,COORDINATOR);}
        void step(){tick++;authority.clock.now++;citizens.tick();jobs.tick();leases.tick();authority.registry.maintain(tick);dispatcher.step(COORDINATOR);}
        void until(java.util.function.BooleanSupplier done){for(int n=0;n<300&&!done.getAsBoolean();n++)step();assertTrue(done.getAsBoolean(),()->"Job="+job()+" decisions="+dispatcher.diagnostics(jobId,COORDINATOR)+" grants="+authority.registry.snapshot()+" claims="+claims);}
        void revoke(){Grant g=authority.find(parent);assertEquals(Status.PENDING,authority.registry.revoke(parent,g.revision(),OWNER).status());}
        final class Handle implements WorkerDispatcher.Handle {
            final Jobs.Job assigned;final Budgets.Ledger usage;
            BoundedSkillExecutor.Progress progress;
            Handle(Jobs.Job assigned,Budgets.Ledger usage){this.assigned=assigned;this.usage=usage;set(null);}
            void set(ExecutionStatus status){
                var a=assigned.current();var receipts=JobLifecycleStoreTest.receipts(a,1);
                var terminal=status==null?null:new Execution(status,status==ExecutionStatus.CANCELLED?Reason.CANCELLED:Reason.INTERRUPTED,3,null);
                progress=new BoundedSkillExecutor.Progress(status==null?BoundedSkillExecutor.Phase.RUNNING:BoundedSkillExecutor.Phase.TERMINAL,
                        new BoundedSkillExecutor.Summary(a.executions().getLast().runId(),ARTIFACT,null,List.of(ARTIFACT),3,receipts,terminal,usage.snapshot()),List.of());
            }
            public BoundedSkillExecutor.Progress tick(TrustedContext owner){return progress;}
            public BoundedSkillExecutor.Progress progress(TrustedContext owner){return progress;}
            public BoundedSkillExecutor.Progress cancel(TrustedContext owner){set(ExecutionStatus.CANCELLED);return progress;}
            public BoundedSkillExecutor.Progress interrupt(TrustedContext owner){set(ExecutionStatus.INTERRUPTED);return progress;}
            public boolean stopped(TrustedContext owner){return stopConfirmed&&progress.summary().outcome()!=null;}
        }
    }
    @Test void revocationCancelsOriginalJobButKeepsLeaseUntilExecutionOwnerConfirmsStop() {
        var s=new Scene();s.until(()->s.handle!=null&&s.job().effects()==3);var binding=s.authority.registry.snapshot().bindings().getFirst();
        assertEquals(ACTOR,s.job().current().worker());assertEquals(COORDINATOR,s.job().origin());assertEquals(3,s.job().effects());
        s.revoke();for(int n=0;n<20;n++)s.step();assertFalse(s.claims.isEmpty());assertTrue(s.job().current().open());
        assertTrue(s.leases.snapshot().leases().stream().anyMatch(l->l.state()==ResourceLeases.State.ACTIVE));
        UUID other=UUID.randomUUID();var args=new HashMap<>(request(FOREIGN).request().arguments());args.put("actor",new ActorValue(actor(101)));
        assertTrue(s.jobs.create(other,other,new CapabilityRequest(CropDelivery.ID,args),FOREIGN,JobLifecycleStoreTest.observation(),budget(100,10000),List.of()).accepted());s.jobs.tick();
        var otherJob=s.jobs.query(other,FOREIGN);assertTrue(s.jobs.assign(other,otherJob.guard(),UUID.randomUUID(),actor(101),ARTIFACT,List.of(ARTIFACT),budget(100,10000),FOREIGN).accepted());s.jobs.tick();
        var competitor=s.leases.acquire(new ResourceLeases.Owner(other,1),List.of(new ResourceLeases.Demand(ResourceLeases.Resource.space(WORLD,SOURCE),1)),100,FOREIGN);
        assertEquals(ResourceLeases.Code.CONFLICT,competitor.code());assertFalse(s.authority.registry.allows(binding.request(),binding.run(),Effect.HARVEST,new EffectTarget(DIM,1,64,0)));
        s.stopConfirmed=true;s.until(()->!s.job().current().open()&&s.claims.isEmpty());assertEquals(Jobs.State.CANCELLED,s.job().state());assertEquals(3,s.job().effects());assertEquals(1,s.job().fulfilled());
        assertFalse(s.cancellations.isEmpty());assertEquals(binding.generation(),s.cancellations.getFirst().generation());
    }
    @Test void coordinatorLossUsesTheSameBoundedStopAndReconciliationOwners() {
        var s=new Scene();s.until(()->s.handle!=null);s.coordinatorAvailable=false;
        for(int n=0;n<20;n++)s.step();assertFalse(s.claims.isEmpty());s.stopConfirmed=true;s.until(()->!s.job().current().open()&&s.claims.isEmpty());
        assertEquals(3,s.job().effects());assertTrue(s.leases.snapshot().leases().stream().noneMatch(l->l.state()==ResourceLeases.State.ACTIVE));
    }
    @Test void grantedRecruitmentDoesNotDiscloseForeignPrivateDisplayNameOrTransferIdentity() {
        var s=new Scene();var original=s.citizens.snapshot();var page=s.workers.page(COORDINATOR,null,3);
        assertEquals(1,page.citizens().size());assertNull(page.citizens().getFirst().displayName());assertFalse(s.citizens.controls(ACTOR,COORDINATOR));assertTrue(s.workers.controls(ACTOR,COORDINATOR));
        assertTrue(s.workers.page(FOREIGN,null,3).citizens().stream().noneMatch(a->a.actor().equals(ACTOR)));assertEquals(original,s.citizens.snapshot());s.revoke();s.authority.registry.tick();
        assertTrue(s.workers.page(COORDINATOR,null,3).citizens().isEmpty());assertEquals(original,s.citizens.snapshot());
    }
    @Test void namesAndOrganizationMembershipCannotSupplyAnAuthorityGrant() {
        var s=new Scene();s.revoke();s.authority.registry.tick();assertFalse(s.workers.controls(ACTOR,COORDINATOR));
        assertTrue(s.citizens.rename(ACTOR.citizenId(),"Guild Leader",OWNER).accepted());s.citizens.tick();
        var groups=new OrganizationRegistry(Organizations.Snapshot.empty(WORLD),(a,b)->CompletableFuture.completedFuture(b),new OrganizationRegistry.References(){
            public OrganizationRegistry.MemberFact citizen(ActorRef actor){return new OrganizationRegistry.MemberFact(OWNER,Organizations.Participation.ACTIVE);}
            public boolean headquarters(ContainerRef ref,TrustedContext c){return false;}
        },s.jobs,s.authority.clock,Organizations.Limits.defaults(),false);
        UUID guild=UUID.randomUUID();assertTrue(groups.create(guild,"Leaders",Organizations.Kind.ORGANIZATION,0,
                new Organizations.PolicyRef(Organizations.PolicyId.NAMED_SHARING,1,Set.of(OWNER),Set.of()),null,COORDINATOR).accepted());groups.tick();
        var scope=groups.inspect(guild,COORDINATOR).scope();assertTrue(groups.join(guild,scope.revision(),ACTOR,"leader",OWNER).accepted());groups.tick();
        assertFalse(s.workers.controls(ACTOR,COORDINATOR));assertEquals(OWNER,s.citizens.find(ACTOR.citizenId()).owner());
    }
    @Test void repeatedOldCleanupCannotCancelAReplacementGeneration() {
        var s=new Scene();s.until(()->s.handle!=null);var binding=s.authority.registry.snapshot().bindings().getFirst();
        var job=s.job();var a=job.current();var stopped=new Execution(ExecutionStatus.INTERRUPTED,Reason.INTERRUPTED,a.effects(),null);
        assertTrue(s.jobs.recordExecutionResult(job.id(),job.guard(),new Jobs.Report(a.id(),a.generation(),a.effects(),a.usage(),a.receipts(),stopped),COORDINATOR).accepted());s.jobs.tick();
        job=s.job();assertTrue(s.jobs.reconcile(job.id(),job.guard(),new Jobs.Report(a.id(),a.generation(),a.effects(),a.usage(),a.receipts(),stopped),true,COORDINATOR).accepted());s.jobs.tick();
        job=s.job();assertEquals(Jobs.State.READY,job.state());
        assertTrue(s.jobs.assign(job.id(),job.guard(),UUID.randomUUID(),ACTOR,ARTIFACT,List.of(ARTIFACT),budget(100,10000),COORDINATOR).accepted());s.jobs.tick();
        var replacement=s.job();assertEquals(binding.generation()+1,replacement.generation());
        for(int n=0;n<5;n++)s.executionOwners.requestStop(binding);assertSame(replacement,s.job());assertEquals(Jobs.State.ACTIVE,s.job().state());
    }
}
