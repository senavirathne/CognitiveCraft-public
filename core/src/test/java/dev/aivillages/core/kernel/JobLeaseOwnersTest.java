package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.JobLifecycleStoreTest.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static org.junit.jupiter.api.Assertions.*;

class JobLeaseOwnersTest {
    @Test void refreshedRoutingObservationKeepsTheExactDurableAssignment() {
        var s=new Scene();var job=s.assign(s.create(3));var a=job.current();
        var refreshed=new ValidatedRequest(a.bound().request(),a.bound().context(),observation());
        var owners=new JobLeaseOwners(s.store,s.clock);var run=a.executions().getLast();
        var found=owners.byRun(refreshed,new RunCorrelation(run.runId(),run.artifact(),null));
        assertEquals(new ResourceLeases.Owner(job.id(),job.generation()),found);
        var facts=owners.inspect(found,s.owner);
        assertTrue(facts.live());assertEquals(run.runId(),facts.run());assertEquals(s.actor,facts.worker());
        assertEquals(s.owner,facts.origin());assertEquals(job,s.current(job));
    }
    @Test void changedArgumentsContextArtifactOrRunCannotBorrowAnAssignment() {
        var s=new Scene();var job=s.assign(s.create(3));var a=job.current();
        var owners=new JobLeaseOwners(s.store,s.clock);var run=a.executions().getLast();
        var changed=new ValidatedRequest(request(s.actor,2),s.owner,observation());
        assertThrows(SecurityException.class,()->owners.byRun(changed,new RunCorrelation(run.runId(),run.artifact(),null)));
        assertThrows(SecurityException.class,()->owners.byRun(new ValidatedRequest(a.bound().request(),s.foreign,observation()),
                new RunCorrelation(run.runId(),run.artifact(),null)));
        assertThrows(SecurityException.class,()->owners.byRun(a.bound(),new RunCorrelation(UUID.randomUUID(),run.artifact(),null)));
        assertThrows(SecurityException.class,()->owners.byRun(a.bound(),new RunCorrelation(run.runId(),
                new ArtifactRef(CropDelivery.ID,"b".repeat(64)),null)));
    }
    @Test void oldJobGenerationAndLostWorkerControlHaveNoAuthority() {
        var s=new Scene();var job=s.assign(s.create(3));var owners=new JobLeaseOwners(s.store,s.clock);
        assertNull(owners.inspect(new ResourceLeases.Owner(job.id(),0),s.owner));
        assertNull(owners.inspect(new ResourceLeases.Owner(job.id(),job.generation()),s.foreign));
        s.controls=false;assertNull(owners.inspect(new ResourceLeases.Owner(job.id(),job.generation()),s.owner));
    }
    @Test void cancellationAndOriginalDeadlineStopLeasesWithoutLifecycleWrites() {
        var s=new Scene();var job=s.assign(s.create(3));var owner=new ResourceLeases.Owner(job.id(),job.generation());
        var owners=new JobLeaseOwners(s.store,s.clock);int writes=s.memory.writes;
        s.clock.now=job.allowance().deadlineEpochMillis()+1;
        var facts=owners.inspect(owner,s.owner);assertFalse(facts.live());assertEquals(Reason.BUDGET_EXHAUSTED,facts.reason());
        assertEquals(writes,s.memory.writes);
        s.clock.now=1000;commit(s,s.store.cancel(job.id(),job.guard(),s.owner));
        facts=owners.inspect(owner,s.owner);assertFalse(facts.live());assertEquals(Reason.CANCELLED,facts.reason());
    }
    @Test void readyJobsAreAuthoritativeBeforeAnExecutorExists() {
        var s=new Scene();var job=s.create(3);var owners=new JobLeaseOwners(s.store,s.clock);
        var facts=owners.inspect(new ResourceLeases.Owner(job.id(),0),s.owner);
        assertTrue(facts.live());assertEquals(s.actor,facts.worker());assertNull(facts.run());
        assertEquals(Jobs.State.READY,s.current(job).state());
    }
    @Test void completedAndInterruptedOwnersCannotGrantNewClaims() {
        var s=new Scene();var job=s.assign(s.create(1));job=s.report(job,1,ExecutionStatus.SUCCEEDED);
        var facts=new JobLeaseOwners(s.store,s.clock).inspect(new ResourceLeases.Owner(job.id(),job.generation()),s.owner);
        assertFalse(facts.live());assertEquals(Reason.ACTION_FAILED,facts.reason());
        var second=new Scene();var other=second.assign(second.create(1));other=second.report(other,0,ExecutionStatus.INTERRUPTED);
        facts=new JobLeaseOwners(second.store,second.clock).inspect(new ResourceLeases.Owner(other.id(),other.generation()),second.owner);
        assertFalse(facts.live());assertEquals(Reason.INTERRUPTED,facts.reason());
    }
    @Test void readSharingDoesNotAuthorizeResourceCoordinationAndControlSharingRetainsOrigin() {
        UUID world=UUID.randomUUID();var alice=owner(world);var bob=owner(world);var actor=actor();
        var clock=new TestClock();var disk=new Memory(world);boolean[] sharedControl={false};
        var policy=new JobLifecycleStore.Policy() {
            public boolean mayRead(TrustedContext caller,Jobs.Job job){return caller.equals(alice)||caller.equals(bob);}
            public boolean mayControl(TrustedContext caller,Jobs.Job job){return caller.equals(alice)||sharedControl[0]&&caller.equals(bob);}
            public boolean controls(ActorRef worker,TrustedContext origin){return worker.equals(actor)&&origin.equals(alice);}
        };
        var jobs=new JobLifecycleStore(disk.state,disk,policy,c->{},id->Optional.of(CropDelivery.SPEC),
                environment(),clock,Jobs.Settings.defaults(),false);
        UUID id=UUID.randomUUID();assertTrue(jobs.create(id,id,request(actor,3),alice,observation(),limits(11000),List.of()).accepted());jobs.tick();
        var owners=new JobLeaseOwners(jobs,clock);var owner=new ResourceLeases.Owner(id,0);
        assertEquals(alice,jobs.query(id,bob).origin());assertNull(owners.inspect(owner,bob));
        sharedControl[0]=true;assertEquals(alice,owners.inspect(owner,bob).origin());
        sharedControl[0]=false;assertNull(owners.inspect(owner,bob));assertEquals(1,disk.writes);
    }
    @Test void previousResearchExecutionCannotRenewAfterANewExactTrialPin() {
        var s=new Scene();var job=s.assign(s.create(3));var old=job.current().executions().getLast();
        UUID newRun=UUID.randomUUID();commit(s,s.store.pin(job.id(),job.guard(),
                new Jobs.ExecutionReference(newRun,REF,List.of(REF)),s.owner));
        var current=s.current(job);var owners=new JobLeaseOwners(s.store,s.clock);
        assertThrows(SecurityException.class,()->owners.byRun(current.current().bound(),new RunCorrelation(old.runId(),REF,null)));
        var owner=owners.byRun(current.current().bound(),new RunCorrelation(newRun,REF,null));
        assertEquals(newRun,owners.inspect(owner,s.owner).run());
    }
}
