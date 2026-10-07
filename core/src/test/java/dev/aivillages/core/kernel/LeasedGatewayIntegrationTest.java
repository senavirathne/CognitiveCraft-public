package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.ResourceLeases.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static dev.aivillages.core.kernel.ExecutorJUnitTest.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;
import static org.junit.jupiter.api.Assertions.*;

/** Real interpreter and effect gateway; only the mechanics and asynchronous storage are deterministic ports. */
class LeasedGatewayIntegrationTest {
    static final Cuboid AREA=new Cuboid(DIM,0,64,0,4,64,0);
    static final class Scene {
        final Fixture f=new Fixture();
        final GatewayChecks.FakeWorld world=new GatewayChecks.FakeWorld();
        final JobLifecycleStoreTest.Memory jobDisk=new JobLifecycleStoreTest.Memory(OWNER.scope().worldId());
        final JobLifecycleStore jobs;
        final JobLeaseOwners owners;
        final ResourceLeaseService leases;
        final SurvivalGateway raw;
        final LeasedGateway gateway;
        final BoundedSkillExecutor executor;
        final BoundedSkillExecutor.Run run;
        final SkillArtifact artifact;
        final Resource crop=Resource.crops(OWNER.scope().worldId(),AREA);
        final UUID jobId=UUID.randomUUID();
        Snapshot disk=Snapshot.empty(OWNER.scope().worldId()),heldNext;
        CompletableFuture<Snapshot> held;
        boolean hold,control=true;
        Scene(int amount,int claim) {
            for(int n=0;n<5;n++)world.crops.put(new SurvivalGateway.Cell(DIM,n,64,0),true);
            var actor=Map.<String,Object>of("actor",Map.of("param","actor"));
            List<Object> cycle=List.of(call(Operation.HARVEST_NEXT_WHEAT,Map.of("actor",Map.of("param","actor"),"source",Map.of("param","source")),"h"),
                    call(Operation.PICKUP_TRACKED_WHEAT,actor,"p"),
                    call(Operation.TRANSFER_WHEAT,Map.of("actor",Map.of("param","actor"),"destination",Map.of("param","destination"),"amount",Map.of("int",1L)),"d"));
            artifact=f.compile(CropDelivery.ID,List.of(Map.of("op","repeat","count",Map.of("param","amount"),"body",cycle),
                    Map.of("op","result","value",Map.of("param","amount"))),List.of(),AdmissionStatus.ADMITTED);
            jobs=new JobLifecycleStore(jobDisk.state,jobDisk,JobLifecycleStore.privateJobs((a,c)->control&&a.equals(ACTOR)&&c.equals(OWNER)),
                    c->{},f.specs,JobLifecycleStoreTest.environment(),f.clock,Jobs.Settings.defaults(),false);
            var request=new CapabilityRequest(CropDelivery.ID,Map.of("actor",new ActorValue(ACTOR),"source",new AreaValue(AREA),
                    "destination",new ContainerValue(DEST),"amount",new IntValue(amount)));
            var total=f.limits(100,32,4096).total();
            assertTrue(jobs.create(jobId,jobId,request,OWNER,f.request.observation(),total,List.of()).accepted());jobs.tick();
            var job=jobs.query(jobId,OWNER);var ref=artifact.descriptor().ref();
            assertTrue(jobs.assign(jobId,job.guard(),UUID.randomUUID(),ACTOR,ref,List.of(ref),total,OWNER).accepted());jobs.tick();
            owners=new JobLeaseOwners(jobs,f.clock);
            leases=new ResourceLeaseService(disk,(expected,next)-> {
                assertEquals(disk,expected);
                if(hold){heldNext=next;held=new CompletableFuture<>();return held;}
                disk=next;return CompletableFuture.completedFuture(next);
            },owners,(resource,owner)->new Observation(resource,ObservationStatus.PRESENT,
                    resource.kind()==ResourceLeases.Kind.STOCK?world.crops.values().stream().filter(Boolean.TRUE::equals).count():1,
                    "same-target",world.tick,1,null),Settings.fixture(),()->world.tick,f.clock,false);leases.tick();
            raw=new SurvivalGateway(world,(a,e,c)->control&&a.equals(ACTOR)&&c.equals(OWNER),
                    new SurvivalGateway.Limits(4,64,64,16,256,1,100,32),f.clock);
            gateway=new LeasedGateway(raw,leases,(bound,correlation)->new LeasedGateway.Binding(owners.byRun(bound,correlation),
                    List.of(new Demand(crop,claim),new Demand(Resource.facility(OWNER.scope().worldId(),DEST),1))),
                    ()->world.tick,100,20,4,64);
            executor=new BoundedSkillExecutor(f.catalog,f.specs,GatewayPrimitives.instance(),(a,key,c)->control&&a.equals(ACTOR)&&c.equals(OWNER),
                    (permit,bound,limits)->{throw new AssertionError("No model/trial port");},new BoundedSkillExecutor.ControlPolicy() {
                        public boolean mayInspect(TrustedContext c,TrustedContext o,UUID id){return c.equals(o);}
                        public boolean mayCancel(TrustedContext c,TrustedContext o,UUID id){return c.equals(o);}
                    },gateway,gateway::releaseRun,f.clock,()->world.tick,new BoundedSkillExecutor.Settings(8,16,32,64));
            var attempt=jobs.query(jobId,OWNER).current();var execution=attempt.executions().getLast();
            var start=executor.startAdmitted(attempt.bound(),ref,new RunCorrelation(execution.runId(),ref,null),
                    new Budgets.ExecutionLimits(total,10000),new Budgets.Ledger(total,f.clock));
            assertInstanceOf(BoundedSkillExecutor.Started.class,start);run=((BoundedSkillExecutor.Started)start).run();
        }
        BoundedSkillExecutor.Progress step(){world.tick++;f.clock.advance(1);raw.tick();leases.tick();gateway.tick();return run.tick(OWNER);}
        void until(BooleanSupplier finished){for(int n=0;n<160&&!finished.getAsBoolean();n++)step();assertTrue(finished.getAsBoolean());}
        boolean terminal(){return run.progress(OWNER).phase()==BoundedSkillExecutor.Phase.TERMINAL;}
        void release(){disk=heldNext;held.complete(disk);hold=false;}
    }
    static Map<String,Object> call(Operation op,Map<String,Object> args,String into){
        return Map.of("op","call","kind","primitive","id",op.signature().id(),"version",1L,
                "fingerprint",op.signature().fingerprint(),"args",args,"into",into);
    }
    @Test void wholeGroupAcknowledgementPrecedesEveryPhysicalEffectAndCompletionConservesStock() {
        var s=new Scene(3,3);s.hold=true;s.until(()->s.held!=null);
        for(int n=0;n<5;n++)s.step();assertEquals(0,s.world.harvests);assertEquals(0,s.world.pickups);assertEquals(0,s.world.transfers);
        assertEquals(2,s.leases.protectedRoots().jobs().size()+1);assertFalse(s.leases.ready());
        s.release();s.until(s::terminal);var progress=s.run.progress(OWNER);
        assertEquals(ExecutionStatus.SUCCEEDED,progress.summary().outcome().status());
        assertEquals(3,s.world.harvests);assertEquals(3,s.world.containerWheat);assertEquals(2,s.world.crops.size());
        assertEquals(9,progress.summary().committedEffects());assertEquals(9,progress.summary().receipts().size());
        s.until(()->s.leases.ready()&&s.leases.snapshot().leases().stream().noneMatch(l->l.state()==State.ACTIVE));
    }
    @Test void pendingConsumptionKeepsActualHarvestEvidenceButPreventsNextEffect() {
        var s=new Scene(3,3);s.until(()->s.leases.snapshot().leases().size()==2);s.hold=true;
        s.until(()->s.world.harvests==1);assertNotNull(s.held);
        for(int n=0;n<5;n++)s.step();assertEquals(0,s.world.pickups);assertEquals(0,s.world.transfers);
        var partial=s.run.progress(OWNER);assertEquals(1,partial.summary().committedEffects());assertEquals(1,partial.summary().receipts().size());
        s.release();s.until(s::terminal);assertEquals(3,s.world.containerWheat);
    }
    @Test void cancelledAcknowledgementCannotStartAnEffectOrMintDeliveryCredit() {
        var s=new Scene(3,3);s.hold=true;s.until(()->s.held!=null);
        var cancelled=s.run.cancel(OWNER);assertEquals(ExecutionStatus.CANCELLED,cancelled.summary().outcome().status());
        assertEquals(0,cancelled.summary().committedEffects());s.release();
        s.until(()->s.leases.ready()&&s.leases.snapshot().leases().stream().noneMatch(l->l.state()==State.ACTIVE));
        assertEquals(0,s.world.harvests);assertTrue(s.world.leases.isEmpty());
    }
    @Test void externalStockLossAndCurrentPermissionPreventTheFirstEffect() {
        var s=new Scene(3,3);s.until(()->s.leases.snapshot().leases().size()==2);
        s.world.crops.clear();s.until(s::terminal);assertEquals(0,s.world.harvests);
        assertEquals(Reason.RESOURCE_MISSING,s.run.progress(OWNER).summary().outcome().reason());
        var permission=new Scene(3,3);permission.until(()->permission.leases.snapshot().leases().size()==2);
        permission.world.deny=true;permission.until(permission::terminal);assertEquals(0,permission.world.harvests);
        assertEquals(Reason.AUTHORITY_DENIED,permission.run.progress(OWNER).summary().outcome().reason());
    }
    @Test void originalActionWaitingBudgetIsNotResetByAnAsynchronousGrant() {
        var s=new Scene(3,3);s.hold=true;s.until(()->s.held!=null);s.until(s::terminal);
        assertEquals(Reason.ACTION_TIMEOUT,s.run.progress(OWNER).summary().outcome().reason());
        assertEquals(0,s.world.harvests);s.release();s.step();assertEquals(0,s.world.harvests);
        assertTrue(s.run.progress(OWNER).summary().usage().get(Budgets.Kind.ELAPSED_TICKS)>0);
    }
    @Test void anIrWithMoreHarvestsThanClaimedStopsBeforeTheExtraPhysicalMutation() {
        var s=new Scene(2,1);s.until(s::terminal);
        assertEquals(Reason.RESOURCE_MISSING,s.run.progress(OWNER).summary().outcome().reason());
        assertEquals(1,s.world.harvests);assertEquals(1,s.world.containerWheat);assertEquals(4,s.world.crops.size());
        assertEquals(3,s.run.progress(OWNER).summary().committedEffects());
    }
    @Test void actualOwnerLossStopsAnAcquiredRunAndRetainsPartialEvidence() {
        var s=new Scene(3,3);s.until(()->s.world.transfers==1);s.control=false;s.until(s::terminal);
        assertEquals(1,s.world.containerWheat);assertEquals(1,s.world.harvests);
        assertEquals(3,s.run.progress(OWNER).summary().committedEffects());
    }
}
