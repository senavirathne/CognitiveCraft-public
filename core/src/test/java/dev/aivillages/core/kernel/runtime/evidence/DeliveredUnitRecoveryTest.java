package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.Jobs.*;
import dev.aivillages.core.kernel.Outcomes.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.OakLogTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Typed accounting and bounded reassignment contracts. These fixtures are not native world proof. */
final class DeliveredUnitRecoveryTest {
    static final class Scene {
        final JobLifecycleStoreTest.TestClock clock=new JobLifecycleStoreTest.TestClock();
        final JobLifecycleStoreTest.Memory memory=new JobLifecycleStoreTest.Memory(OWNER.scope().worldId());
        JobLifecycleStore store;Job original;boolean missingDeposit,partialKind;
        Scene(int amount) {
            store=open();UUID id=UUID.randomUUID();var bound=bound(amount);
            assertTrue(store.create(id,id,bound.request(),OWNER,bound.observation(),JobLifecycleStoreTest.limits(11_000),List.of()).accepted());store.tick();
            var job=store.query(id,OWNER);var ref=new ArtifactRef(OakLogDelivery.ID,"a".repeat(64));
            assertTrue(store.assign(id,job.guard(),UUID.randomUUID(),ACTOR,ref,List.of(ref),JobLifecycleStoreTest.limits(11_000),OWNER).accepted());store.tick();
            original=store.query(id,OWNER);store=open();store.tick();
        }
        JobLifecycleStore open(){return new JobLifecycleStore(memory.state,memory,JobLifecycleStore.privateJobs((a,c)->c.equals(OWNER)),c->{},
                id->id.equals(OakLogDelivery.ID)?Optional.of(OakLogDelivery.SPEC):Optional.empty(),JobLifecycleStoreTest.environment(),clock,Settings.defaults(),false);}
        StoppedObservation observed(StoppedContext context) {
            var receipts=chain(context.original().bound(),context.original().executions().getFirst().runId(),1);
            if(missingDeposit)receipts=receipts.subList(0,2);
            boolean expired=clock.millis()>context.original().allowance().deadlineEpochMillis();
            return new StoppedObservation(partialKind?StoppedKind.PARTIAL:StoppedKind.DELIVERED_UNIT,
                    new Report(context.attempt(),context.generation(),3,Map.of(Budgets.Kind.ATTEMPTED_EFFECTS,3L,Budgets.Kind.COMMITTED_EFFECTS,3L),List.of(),
                            new Execution(expired?ExecutionStatus.BLOCKED:ExecutionStatus.INTERRUPTED,expired?Reason.BUDGET_EXHAUSTED:Reason.INTERRUPTED,3,null),receipts),"fixture: synthetic delivered-unit authority contract only");
        }
        Change reconcile(){var authority=store.attachRecovery(this::observed);var job=store.query(original.id(),OWNER);return store.reconcileStopped(job.id(),job.guard(),authority.capture(StoppedContext.of(job)),OWNER);}
    }
    @Test void exactDeliveredUnitClosesTheOriginalAttemptAndOnlyTheRemainingUnitCanBeAssigned() {
        var s=new Scene(2);assertTrue(s.reconcile().accepted());s.store.tick();var ready=s.store.query(s.original.id(),OWNER);
        assertEquals(State.READY,ready.state());assertEquals(1,ready.fulfilled());assertFalse(ready.current().open());assertFalse(ready.current().uncertain());
        assertEquals(s.original.current().id(),ready.current().id());assertEquals(s.original.allowance(),ready.allowance());assertEquals(3,ready.current().effects());
        var remaining=s.store.remainingAllowance(ready.id(),OWNER);assertEquals(ready.allowance().deadlineEpochMillis(),remaining.deadlineEpochMillis());
        assertEquals(ready.allowance().maximum(Budgets.Kind.COMMITTED_EFFECTS)-3,remaining.maximum(Budgets.Kind.COMMITTED_EFFECTS));
        var ref=s.original.current().executions().getFirst().artifact();
        assertTrue(s.store.assign(ready.id(),ready.guard(),UUID.randomUUID(),ACTOR,ref,List.of(ref),remaining,OWNER).accepted());s.store.tick();
        var assigned=s.store.query(ready.id(),OWNER);assertEquals(2,assigned.generation());assertEquals(1,((IntValue)assigned.current().bound().request().arguments().get("amount")).value());
        assertEquals(2,((IntValue)assigned.request().request().arguments().get("amount")).value());assertEquals(1,assigned.fulfilled());assertEquals(0,assigned.current().effects());
    }
    @Test void expiredOriginalDeadlineStillAccountsTheSavedUnitButCannotAuthorizeNewWork() {
        var s=new Scene(2);s.clock.now=11_001;assertTrue(s.reconcile().accepted());s.store.tick();var job=s.store.query(s.original.id(),OWNER);
        assertEquals(State.WAITING,job.state());assertEquals(Reason.BUDGET_EXHAUSTED,job.reason());assertEquals(1,job.fulfilled());assertFalse(job.current().uncertain());
        var ref=s.original.current().executions().getFirst().artifact();assertFalse(s.store.assign(job.id(),job.guard(),UUID.randomUUID(),ACTOR,ref,List.of(ref),JobLifecycleStoreTest.limits(21_001),OWNER).accepted());
        assertEquals(s.original.allowance(),job.allowance());
    }
    @Test void missingDepositPartialCustodyAndChangedOriginalDemandCannotAuthorizeAReplacement() {
        var missing=new Scene(2);missing.missingDeposit=true;assertFalse(missing.reconcile().accepted());assertTrue(missing.store.query(missing.original.id(),OWNER).current().open());
        var partial=new Scene(2);partial.partialKind=true;assertFalse(partial.reconcile().accepted());
        var changed=new Scene(3);assertFalse(changed.reconcile().accepted());
    }
    @Test void originalCancellationWinsOverAResumeReport() {
        var s=new Scene(2);var job=s.store.query(s.original.id(),OWNER);assertTrue(s.store.cancel(job.id(),job.guard(),OWNER).accepted());s.store.tick();
        assertFalse(s.reconcile().accepted());assertEquals(State.CANCELLING,s.store.query(job.id(),OWNER).state());assertTrue(s.store.query(job.id(),OWNER).current().open());
    }
}
