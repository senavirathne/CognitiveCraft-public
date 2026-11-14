package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Jobs.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic authority contracts; native accounting and physical acquisition are separate gates. */
final class ReassignmentPolicyTest {
    private JobLifecycleStoreTest.Scene scene() {
        var s=new JobLifecycleStoreTest.Scene();s.store.requireExplicitReassignment();return s;
    }
    private Job stopped(JobLifecycleStoreTest.Scene s) {
        var j=s.assign(s.create(1));j=s.report(j,0,ExecutionStatus.INTERRUPTED);var a=j.current();
        var report=new Report(a.id(),a.generation(),a.effects(),a.usage(),a.receipts(),a.terminal());
        assertTrue(s.store.reconcile(j.id(),j.guard(),report,true,s.owner).accepted());s.settle();
        return s.current(j);
    }
    @Test void retryCannotChooseAnotherControlledWorkerWithoutOwnerPolicy() {
        var s=scene();var j=stopped(s);var other=JobLifecycleStoreTest.actor();
        assertThrows(SecurityException.class,()->s.store.bindWorker(j.id(),j.guard(),other,s.owner));
        assertEquals(Reason.AUTHORITY_DENIED,s.store.assign(j.id(),j.guard(),UUID.randomUUID(),other,
                JobLifecycleStoreTest.REF,List.of(JobLifecycleStoreTest.REF),JobLifecycleStoreTest.limits(11_000),s.owner).reason());
        assertEquals(j,s.current(j));
    }
    @Test void explicitPolicySelectsOnlyItsSuccessorAndJobsPublishesTheNextGeneration() {
        var s=scene();var j=stopped(s);var other=JobLifecycleStoreTest.actor();var before=s.store.snapshot();
        assertTrue(s.store.authorizeReassignment(j.id(),j.guard(),other,s.owner));assertSame(before,s.store.snapshot());
        assertThrows(SecurityException.class,()->s.store.bindWorker(j.id(),j.guard(),s.actor,s.owner));
        assertEquals(other,((ActorValue)s.store.bindWorker(j.id(),j.guard(),other,s.owner).request().arguments().get("actor")).value());
        var remaining=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);
        for(var kind:Budgets.Kind.values())remaining.put(kind,j.allowance().maximum(kind)-j.usage().getOrDefault(kind,0L));
        var next=s.assign(j,other,new Budgets.Limits(remaining,j.allowance().deadlineEpochMillis()));
        assertEquals(j.id(),next.id());assertEquals(j.generation()+1,next.generation());assertEquals(other,next.current().worker());
        assertEquals(j.responsible(),next.responsible());assertEquals(j.allowance(),next.allowance());
    }
    @Test void staleGuardsForeignCallersAndSameEntityAliasesCannotAuthorize() {
        var s=scene();var j=stopped(s);var other=JobLifecycleStoreTest.actor();
        assertFalse(s.store.authorizeReassignment(j.id(),new Guard(j.revision()-1,j.generation()),other,s.owner));
        assertFalse(s.store.authorizeReassignment(j.id(),new Guard(j.revision(),j.generation()+1),other,s.owner));
        assertFalse(s.store.authorizeReassignment(j.id(),j.guard(),other,s.foreign));
        assertFalse(s.store.authorizeReassignment(j.id(),j.guard(),s.actor,s.owner));
        assertFalse(s.store.authorizeReassignment(j.id(),j.guard(),new ActorRef(UUID.randomUUID(),s.actor.entityId(),s.actor.dimension()),s.owner));
    }
    @Test void policyCannotClearUncertaintyOrStartAnOpenAttempt() {
        var s=scene();var j=s.assign(s.create(1));j=s.report(j,0,ExecutionStatus.INTERRUPTED);var other=JobLifecycleStoreTest.actor();
        assertTrue(j.current().uncertain());assertTrue(s.store.authorizeReassignment(j.id(),j.guard(),other,s.owner));
        assertEquals(Reason.REQUEST_INVALID,s.store.assign(j.id(),j.guard(),UUID.randomUUID(),other,
                JobLifecycleStoreTest.REF,List.of(JobLifecycleStoreTest.REF),JobLifecycleStoreTest.limits(11_000),s.owner).reason());
        assertEquals(j,s.current(j));
    }
    @Test void revocationIsRepeatedAtAssignmentAndCannotMintNewAuthority() {
        var s=scene();var j=stopped(s);var other=JobLifecycleStoreTest.actor();
        assertTrue(s.store.authorizeReassignment(j.id(),j.guard(),other,s.owner));s.controls=false;
        assertThrows(SecurityException.class,()->s.store.bindWorker(j.id(),j.guard(),other,s.owner));
        assertEquals(Reason.AUTHORITY_DENIED,s.store.assign(j.id(),j.guard(),UUID.randomUUID(),other,
                JobLifecycleStoreTest.REF,List.of(JobLifecycleStoreTest.REF),JobLifecycleStoreTest.limits(11_000),s.owner).reason());
    }
    @Test void processRestartRetainsJobsButDoesNotReconstructTheAuthorization() {
        var s=scene();var j=stopped(s);var other=JobLifecycleStoreTest.actor();assertTrue(s.store.authorizeReassignment(j.id(),j.guard(),other,s.owner));
        var reopened=new JobLifecycleStore(s.store.snapshot(),s.memory,JobLifecycleStore.privateJobs((a,c)->c.equals(s.owner)),
                ignored->{},id->id.equals(CropDelivery.ID)?Optional.of(CropDelivery.SPEC):Optional.empty(),
                JobLifecycleStoreTest.environment(),s.clock,Settings.defaults(),false);reopened.requireExplicitReassignment();
        assertThrows(SecurityException.class,()->reopened.bindWorker(j.id(),j.guard(),other,s.owner));
        assertEquals(j,reopened.query(j.id(),s.owner));
    }
    @Test void cancelledOrExpiredOriginalCannotAuthorizeNewWork() {
        var s=scene();var j=stopped(s);var other=JobLifecycleStoreTest.actor();s.clock.now=11_001;
        assertFalse(s.store.authorizeReassignment(j.id(),j.guard(),other,s.owner));
        var second=scene();var active=stopped(second);assertTrue(second.store.cancel(active.id(),active.guard(),second.owner).accepted());second.settle();
        assertFalse(second.store.authorizeReassignment(active.id(),second.current(active).guard(),other,second.owner));
    }
    @Test void originalWorkerRetryAndLegacyStandaloneConstructionRemainCompatible() {
        var s=scene();var j=stopped(s);assertNotNull(s.store.bindWorker(j.id(),j.guard(),s.actor,s.owner));
        var legacy=new JobLifecycleStoreTest.Scene();var old=stopped(legacy);assertNotNull(legacy.store.bindWorker(old.id(),old.guard(),JobLifecycleStoreTest.actor(),legacy.owner));
    }
}
