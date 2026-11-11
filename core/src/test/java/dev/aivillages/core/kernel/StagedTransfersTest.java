package dev.aivillages.core.kernel;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;
import static dev.aivillages.core.kernel.runtime.claims.StagedTransfers.*;
import dev.aivillages.core.kernel.runtime.claims.StagedTransfers.Plan;

/** Real Jobs ownership and cumulative envelopes, with an adversarial physical host. */
class StagedTransfersTest {
    static final String LOG=ResourceDelivery.OAK_LOG.id();
    static final class Scene implements World {
        final UUID world=UUID.randomUUID();final TrustedContext caller=JobLifecycleStoreTest.owner(world);
        final ActorRef actor=JobLifecycleStoreTest.actor();final JobLifecycleStoreTest.TestClock clock=new JobLifecycleStoreTest.TestClock();
        final ContainerRef source=new ContainerRef(actor.dimension(),0,0,0),dest=new ContainerRef(actor.dimension(),2,0,0);
        final JobLifecycleStoreTest.Memory memory=new JobLifecycleStoreTest.Memory(world);
        final JobLifecycleStore jobs;final ResourceLeaseService leases;final dev.aivillages.core.kernel.runtime.claims.StagedTransfers staging;
        final Plan plan=new Plan(source,dest,source,List.of(new Units(ResourceDelivery.OAK_LOG,3)));
        final UUID id=UUID.randomUUID();long tick;int stock=5,held,output,moves,partial;boolean controls=true,fits=true,reached=true,swap,throwAfterMove;
        String sourceId="source",destId="destination";
        Scene(){this(Settings.defaults(),64);}
        Scene(Settings settings,long effects){this(settings,effects,true,false);}
        Scene(Settings settings,long effects,boolean preparation,boolean recipe){
            jobs=new JobLifecycleStore(memory.state,memory,JobLifecycleStore.privateJobs((a,c)->controls&&a.equals(actor)&&c.equals(caller)),
                    c->{},c->Optional.of(recipe?OakPlankProduction.SPEC:OakLogDelivery.SPEC),JobLifecycleStoreTest.environment(),clock,Jobs.Settings.defaults(),false);
            var limits=new Budgets.Limits(Map.of(Budgets.Kind.INSTRUCTIONS,1000L,Budgets.Kind.OBSERVATIONS,100_000L,
                    Budgets.Kind.ATTEMPTED_EFFECTS,64L,Budgets.Kind.COMMITTED_EFFECTS,effects,Budgets.Kind.ELAPSED_TICKS,1200L),100_000);
            var request=new CapabilityRequest(recipe?OakPlankProduction.ID:OakLogDelivery.ID,Map.of("actor",new ActorValue(actor),"amount",new IntValue(recipe?12:3),
                    "source",recipe?new ContainerValue(source):new AreaValue(ResourceLeases.point(source)),"destination",new ContainerValue(dest)));
            assertTrue(jobs.create(id,id,request,caller,JobLifecycleStoreTest.observation(),limits,List.of()).accepted());jobs.tick();
            Jobs.Job j=jobs.query(id,caller);var ref=new ArtifactRef(request.capability(),"a".repeat(64));
            assertTrue(jobs.assign(id,j.guard(),UUID.randomUUID(),actor,preparation?null:ref,preparation?List.of():List.of(ref),limits,caller).accepted());jobs.tick();
            leases=new ResourceLeaseService(ResourceLeases.Snapshot.empty(world),(a,b)->CompletableFuture.completedFuture(b),new JobLeaseOwners(jobs,clock),
                    (r,o)->new ResourceLeases.Observation(r,ObservationStatus.PRESENT,r.kind()==ResourceLeases.Kind.STOCK?stock:1,
                            r.area().minX()==source.x()?sourceId:destId,tick,1,null),ResourceLeases.Settings.fixture(),()->tick,clock,false);leases.tick();
            staging=new dev.aivillages.core.kernel.runtime.claims.StagedTransfers(jobs,leases,this,clock,settings);
        }
        public Snapshot observe(ValidatedRequest r,Plan p,Operation op){
            return new Snapshot(tick,sourceId,destId,"actor:"+held,"source:"+stock,"dest:"+output+(swap?":swap":""),
                    Map.of(LOG,stock),Map.of(LOG,held),held+3<=64,fits,reached,10,null);
        }
        public Move move(ValidatedRequest r,Plan p,Operation op,Snapshot captured){
            Snapshot fresh=observe(r,p,op);
            if(!fresh.sourceFingerprint().equals(captured.sourceFingerprint())||!fresh.destinationFingerprint().equals(captured.destinationFingerprint())
                    ||!fresh.actorFingerprint().equals(captured.actorFingerprint())||!fits||!controls)return Move.blocked(fresh,Reason.STALE_OBSERVATION);
            int n=partial>0?partial:3;
            if(op==Operation.WITHDRAW){stock-=n;held+=n;}else{held-=n;if(op==Operation.RETURN)stock+=n;else output+=n;}
            moves++;if(throwAfterMove)throw new IllegalStateException("Native mutation outcome unknown");
            return new Move(true,Map.of(LOG,n),observe(r,p,op),null);
        }
        Frame open(){return staging.open(id,plan,caller);}
        View step(Frame f,Operation op){View v=staging.step(f,op,caller);jobs.tick();leases.tick();tick++;return v;}
        View until(Frame f,Operation op,Phase phase){for(int i=0;i<12;i++){View v=step(f,op);if(v.phase()==phase)return v;}fail("Expected "+phase+" got "+staging.view(f,caller));return null;}
    }
    @Test void interpreterOwnedJobCannotOpenASecondPhysicalOwner(){
        var s=new Scene(Settings.defaults(),64,false,false);assertThrows(SecurityException.class,s::open);assertEquals(0,s.moves);
    }
    @Test void idleResidenceMaintenanceStopsSchedulingAndPreservesCustody(){
        var s=new Scene(new Settings(32,64,5,8),64);var f=s.open();s.until(f,Operation.WITHDRAW,Phase.HELD);
        assertEquals(1,s.jobs.query(s.id,s.caller).current().effects());s.tick=6;s.staging.tick();s.jobs.tick();
        assertEquals(Phase.BLOCKED,s.staging.view(f,s.caller).phase());assertEquals(3,s.held);
        assertEquals(Jobs.State.INTERRUPTED,s.jobs.query(s.id,s.caller).state());assertTrue(s.jobs.query(s.id,s.caller).current().uncertain());
    }
    @Test void fullDestinationWaitCanBeExplicitlyReplacedByLawfulReturnWithoutReset(){
        var s=new Scene();var f=s.open();s.until(f,Operation.WITHDRAW,Phase.HELD);s.fits=false;
        s.step(f,Operation.DEPOSIT);long first=s.staging.view(f,s.caller).firstTick();s.step(f,Operation.RETURN);s.fits=true;
        var v=s.until(f,Operation.RETURN,Phase.RETURNED);assertEquals(first,v.firstTick());assertEquals(2,s.moves);assertEquals(5,s.stock);assertEquals(0,s.output);
        assertEquals(2,s.jobs.query(s.id,s.caller).current().effects());assertEquals(0,s.jobs.query(s.id,s.caller).current().credited());
    }
    @Test void explicitCancellationAfterReturnClosesOnlyTheOriginalJobWithNoCredit(){
        var s=new Scene();var f=s.open();s.until(f,Operation.WITHDRAW,Phase.HELD);s.until(f,Operation.RETURN,Phase.RETURNED);
        var j=s.jobs.query(s.id,s.caller);assertTrue(s.jobs.cancel(j.id(),j.guard(),s.caller).accepted());s.jobs.tick();s.staging.tick();s.jobs.tick();
        assertEquals(Jobs.State.CANCELLED,s.jobs.query(s.id,s.caller).state());assertEquals(2,s.jobs.query(s.id,s.caller).current().effects());
        assertEquals(0,s.jobs.query(s.id,s.caller).current().credited());assertEquals(5,s.stock);
    }
    @Test void exactThreePhysicalUnitsThenDepositAreTwoEffectsWithNoGoalCredit(){
        var s=new Scene();var f=s.open();s.until(f,Operation.WITHDRAW,Phase.HELD);
        assertEquals(2,s.stock);assertEquals(3,s.held);assertEquals(Map.of(LOG,3),s.staging.view(f,s.caller).acquired());
        int n=s.moves;s.step(f,Operation.WITHDRAW);assertEquals(n,s.moves);
        View end=s.until(f,Operation.DEPOSIT,Phase.DEPOSITED);assertEquals(0,s.held);assertEquals(3,s.output);
        assertEquals(5,s.stock+s.held+s.output);assertEquals(2,end.moves().size());assertTrue(end.acquired().isEmpty());
        assertEquals(0,s.jobs.query(s.id,s.caller).current().credited());
    }
    @Test void trustedReservedPreparationNeedsNoFabricatedMethodAndNewPinStopsOldFrame(){
        var s=new Scene(Settings.defaults(),64,true,false);var f=s.open();assertTrue(s.jobs.query(s.id,s.caller).current().executions().isEmpty());
        var j=s.jobs.query(s.id,s.caller);var ref=new ArtifactRef(OakLogDelivery.ID,"b".repeat(64));
        assertTrue(s.jobs.pin(s.id,j.guard(),new Jobs.ExecutionReference(UUID.randomUUID(),ref,List.of(ref)),s.caller).accepted());s.jobs.tick();
        assertEquals(Phase.BLOCKED,s.step(f,Operation.WITHDRAW).phase());assertEquals(0,s.moves);
    }
    @Test void rawRecipeInputsCannotBeDepositedAsTransformationOutput(){
        var s=new Scene(Settings.defaults(),64,true,true);var f=s.open();s.until(f,Operation.WITHDRAW,Phase.HELD);
        var v=s.step(f,Operation.DEPOSIT);assertEquals(Reason.REQUEST_INVALID,v.blocker());assertEquals(3,s.held);assertEquals(0,s.output);
    }
    @Test void impossibleStockWaitsWithoutAnyClaimOrPhysicalEffect(){
        var s=new Scene();s.stock=2;var f=s.open();for(int i=0;i<6;i++)s.step(f,Operation.WITHDRAW);
        assertEquals(0,s.moves);assertTrue(s.leases.snapshot().leases().isEmpty());assertEquals(Phase.WAITING,s.staging.view(f,s.caller).phase());
        s.stock+=2;s.until(f,Operation.WITHDRAW,Phase.HELD);assertEquals(1,s.stock);
    }
    @Test void noChestClaimDuringNavigationAndFullInitialDestinationHasZeroEffects(){
        var s=new Scene();s.reached=false;var f=s.open();for(int i=0;i<5;i++)s.step(f,Operation.WITHDRAW);
        assertTrue(s.leases.snapshot().leases().isEmpty());s.reached=true;s.until(f,Operation.WITHDRAW,Phase.HELD);
        var full=new Scene();full.fits=false;var closed=full.open();assertEquals(Phase.BLOCKED,full.staging.view(closed,full.caller).phase());
        assertEquals(0,full.moves);assertEquals(5,full.stock);
    }
    @Test void dynamicFillAfterGrantCannotWithdrawAndCannotBeReportedAsRollback(){
        var s=new Scene();var f=s.open();s.step(f,Operation.WITHDRAW);s.fits=false;s.until(f,Operation.WITHDRAW,Phase.BLOCKED);
        assertEquals(0,s.moves);assertEquals(5,s.stock);assertEquals(0,s.held);
    }
    @Test void fullReturnPreservesActualCustodyAndDoesNotChooseFallback(){
        var s=new Scene();var f=s.open();s.until(f,Operation.WITHDRAW,Phase.HELD);s.fits=false;
        for(int i=0;i<6;i++)s.step(f,Operation.RETURN);
        assertEquals(3,s.held);assertEquals(2,s.stock);assertEquals(0,s.output);assertEquals(1,s.moves);
        assertEquals(Map.of(LOG,3),s.staging.view(f,s.caller).acquired());
    }
    @Test void revokedReturnAndBoundedResidencePreserveCustody(){
        var s=new Scene();var f=s.open();s.until(f,Operation.WITHDRAW,Phase.HELD);s.controls=false;
        assertEquals(Phase.BLOCKED,s.step(f,Operation.RETURN).phase());assertEquals(3,s.held);
        var timed=new Scene(new Settings(32,64,5,8),64);var t=timed.open();timed.until(t,Operation.WITHDRAW,Phase.HELD);
        timed.tick=6;assertEquals(Reason.ACTION_TIMEOUT,timed.step(t,Operation.DEPOSIT).blocker());assertEquals(3,timed.held);
    }
    @Test void lawfulReturnIsASecondPhysicalEffectAndConservesStock(){
        var s=new Scene();var f=s.open();s.until(f,Operation.WITHDRAW,Phase.HELD);var v=s.until(f,Operation.RETURN,Phase.RETURNED);
        assertEquals(5,s.stock);assertEquals(0,s.held);assertEquals(2,v.moves().size());assertEquals(Operation.RETURN,v.moves().getLast().kind());
    }
    @Test void replacementOrPrivateCustodyMutationDoesNotRebindExistingFrame(){
        var s=new Scene();var f=s.open();s.sourceId="replacement";s.until(f,Operation.WITHDRAW,Phase.BLOCKED);assertEquals(0,s.moves);
        var changed=new Scene();var c=changed.open();changed.held=1;changed.until(c,Operation.WITHDRAW,Phase.BLOCKED);assertEquals(5,changed.stock);
    }
    @Test void effectAllowanceIsCheckedBeforeNativeMutationAndCannotResetOnReopen(){
        var s=new Scene(Settings.defaults(),0);var f=s.open();s.until(f,Operation.WITHDRAW,Phase.BLOCKED);
        assertEquals(0,s.moves);assertThrows(SecurityException.class,s::open);assertEquals(Reason.BUDGET_EXHAUSTED,s.staging.view(f,s.caller).blocker());
    }
    @Test void unexpectedPartialMoveRetainsAttributableCustodyAndStops(){
        var s=new Scene();s.partial=1;var f=s.open();View v=s.until(f,Operation.WITHDRAW,Phase.BLOCKED);
        assertEquals(1,s.held);assertEquals(Map.of(LOG,1),v.acquired());assertEquals(5,s.stock+s.held);assertEquals(1,v.moves().size());
    }
    @Test void nativeExceptionAfterMutationIsUncertainAndNeverRetried(){
        var s=new Scene();s.throwAfterMove=true;var f=s.open();View v=s.until(f,Operation.WITHDRAW,Phase.BLOCKED);
        assertTrue(v.uncertain());assertEquals(Reason.INTERRUPTED,v.blocker());int n=s.moves;s.step(f,Operation.WITHDRAW);assertEquals(n,s.moves);
        assertEquals(5,s.stock+s.held);assertFalse(s.staging.owns(s.jobs.query(s.id,s.caller)));
    }
    @Test void planEnvelopeAndCallerAreImmutableAndAllBoundsValidated(){
        var s=new Scene();var f=s.open();assertThrows(SecurityException.class,()->s.staging.view(f,JobLifecycleStoreTest.owner(s.world)));
        var tooMuch=new Plan(s.source,s.dest,s.source,List.of(new Units(ResourceDelivery.OAK_LOG,4)));
        assertThrows(SecurityException.class,()->s.staging.open(s.id,tooMuch,s.caller));
        assertThrows(IllegalArgumentException.class,()->new Settings(65,64,600,8));
        assertThrows(IllegalArgumentException.class,()->new Plan(s.source,s.dest,s.dest,List.of(new Units(ResourceDelivery.OAK_LOG,3))));
    }
}
