package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.runtime.RecoveredJobOwners;
import org.junit.jupiter.api.Test;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Jobs.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static org.junit.jupiter.api.Assertions.*;

final class RecoveredJobOwnersTest {
    static final class Live implements RecoveredJobOwners.LiveOwners {
        boolean bootstrap, dispatch;
        RecoveredJobOwners.Presence presence = RecoveredJobOwners.Presence.AVAILABLE;
        public boolean bootstrap(Job job) { return bootstrap; }
        public boolean dispatcher(Job job) { return dispatch; }
        public RecoveredJobOwners.Presence presence(Job job) { return presence; }
    }
    static JobLifecycleStore reopen(JobLifecycleStoreTest.Scene scene) {
        var store = new JobLifecycleStore(scene.memory.state, scene.memory,
                JobLifecycleStore.privateJobs((a,c) -> c.equals(scene.owner)), scene.notices::add,
                id -> id.equals(CropDelivery.ID) ? Optional.of(CropDelivery.SPEC) : Optional.empty(),
                JobLifecycleStoreTest.environment(), scene.clock, Settings.defaults(), false);
        store.tick(); assertTrue(store.ready()); return store;
    }
    static StoppedObservation noEffect(StoppedContext c) {
        return new StoppedObservation(StoppedKind.NO_EFFECT, new Report(c.attempt(),c.generation(),0,
                c.original().usage(),List.of(),new Execution(ExecutionStatus.INTERRUPTED,Reason.INTERRUPTED,0,null)),
                "fixture: trusted stopped no-effect host witness; not gameplay");
    }
    static RecoveredJobOwners owners(JobLifecycleStore store, Live live) {
        return new RecoveredJobOwners(store,live,c -> StoppedObservation.ambiguous("fixture: no durable native proof"),4);
    }
    @Test void savedAttemptGetsOneStableRecoveryOwnerAndNoInventedProgress() {
        var s = new JobLifecycleStoreTest.Scene(); Job old = s.assign(s.create(2));
        var store = reopen(s); var recovery = owners(store,new Live());
        recovery.step(); var first = recovery.view(old.id(),s.owner);
        assertEquals(RecoveredJobOwners.Route.RECOVERY,first.route()); assertNotNull(first.recoveryOwner());
        assertEquals(RecoveredJobOwners.Blocker.NEEDS_ATTENTION,first.blocker()); assertNull(first.verifiedLegacyQuantity());
        assertEquals(old.current().id(),first.attempt()); assertEquals(old.generation(),first.executionGeneration());
        for(int n=0;n<20;n++) recovery.step();
        assertEquals(first,recovery.view(old.id(),s.owner)); assertTrue(store.query(old.id(),s.owner).current().open());
        assertThrows(IllegalStateException.class,() -> owners(store,new Live()));
    }
    @Test void recoveredOwnerIdentitySurvivesAnotherProcessButProcessAuthorityChanges() {
        var s = new JobLifecycleStoreTest.Scene(); Job old = s.assign(s.create(2));
        var first = owners(reopen(s),new Live()).view(old.id(),s.owner);
        var second = owners(reopen(s),new Live()).view(old.id(),s.owner);
        assertEquals(first.recoveryOwner(),second.recoveryOwner()); assertNotEquals(first.process(),second.process());
    }
    @Test void liveOwnershipIsFreshAndDualWritersAreExplicitlyUnresolved() {
        var s = new JobLifecycleStoreTest.Scene(); Job old = s.assign(s.create(2)); var live = new Live();
        var recovery = owners(reopen(s),live); live.bootstrap=true;
        assertEquals(RecoveredJobOwners.Route.LIVE_BOOTSTRAP,recovery.view(old.id(),s.owner).route());
        live.dispatch=true;
        assertEquals(RecoveredJobOwners.Blocker.OWNERSHIP_CONFLICT,recovery.view(old.id(),s.owner).blocker());
        live.bootstrap=false;
        assertEquals(RecoveredJobOwners.Route.LIVE_DISPATCHER,recovery.view(old.id(),s.owner).route());
        live.dispatch=false;
        assertEquals(RecoveredJobOwners.Route.RECOVERY,recovery.view(old.id(),s.owner).route());
    }
    @Test void unavailableWorldWaitsWithoutBecomingAbsentOrTerminal() {
        var s = new JobLifecycleStoreTest.Scene(); Job old = s.assign(s.create(2)); var live = new Live();
        var store = reopen(s); var recovery = owners(store,live); live.presence=RecoveredJobOwners.Presence.WAIT_WORLD;
        recovery.step(); assertEquals(RecoveredJobOwners.Blocker.WAIT_WORLD,recovery.view(old.id(),s.owner).blocker());
        assertTrue(store.query(old.id(),s.owner).current().open());
        live.presence=RecoveredJobOwners.Presence.AVAILABLE;
        assertEquals(RecoveredJobOwners.Blocker.NEEDS_ATTENTION,recovery.view(old.id(),s.owner).blocker());
    }
    @Test void cancellationSurvivesAdoptionAndAnotherRestartWithoutFalseSettlement() {
        var s = new JobLifecycleStoreTest.Scene(); Job old = s.assign(s.create(2));
        assertTrue(s.store.cancel(old.id(),old.guard(),s.owner).accepted()); s.store.tick();
        var store = reopen(s); var recovery = owners(store,new Live()); recovery.step();
        var view = recovery.view(old.id(),s.owner); assertTrue(view.cancellationIntent());
        assertTrue(store.query(old.id(),s.owner).current().open());
        assertTrue(owners(reopen(s),new Live()).view(old.id(),s.owner).cancellationIntent());
    }
    @Test void typedStoppedApiSettlesCancellingOnlyWithItsOwnTrustedCompleteEvidence() {
        var s = new JobLifecycleStoreTest.Scene(); Job old = s.assign(s.create(2)); var store = reopen(s);
        var authority = store.attachRecovery(RecoveredJobOwnersTest::noEffect);
        Job interrupted = store.query(old.id(),s.owner); assertTrue(store.cancel(old.id(),interrupted.guard(),s.owner).accepted()); store.tick();
        Job cancelling = store.query(old.id(),s.owner); assertEquals(State.CANCELLING,cancelling.state());
        var proof = authority.capture(StoppedContext.of(cancelling));
        assertTrue(store.reconcileStopped(old.id(),cancelling.guard(),proof,s.owner).accepted()); store.tick();
        Job settled = store.query(old.id(),s.owner); assertEquals(State.CANCELLED,settled.state()); assertFalse(settled.current().open());
        assertEquals(Code.DUPLICATE,store.reconcileStopped(old.id(),cancelling.guard(),proof,s.owner).code());
        assertEquals(old.allowance(),settled.allowance()); assertEquals(old.current().id(),settled.current().id());
    }
    @Test void booleanOrStoppedAloneCannotManufactureTypedNoEffectEvidence() {
        var s = new JobLifecycleStoreTest.Scene(); Job old = s.assign(s.create(2)); var store = reopen(s);
        var authority=store.attachRecovery(c -> new StoppedObservation(StoppedKind.STOPPED,null,"fixture: JVM gone is insufficient"));
        Job job=store.query(old.id(),s.owner); var proof=authority.capture(StoppedContext.of(job));
        assertFalse(store.reconcileStopped(job.id(),job.guard(),proof,s.owner).accepted()); assertTrue(store.query(job.id(),s.owner).current().open());
        assertFalse(store.reconcileStopped(job.id(),job.guard(),null,s.owner).accepted());
    }
    @Test void foreignStaleAndOtherProcessProofsRefuse() {
        var s = new JobLifecycleStoreTest.Scene(); Job old = s.assign(s.create(2)); var first=reopen(s);
        var a=first.attachRecovery(RecoveredJobOwnersTest::noEffect); Job original=first.query(old.id(),s.owner);
        var proof=a.capture(StoppedContext.of(original));
        assertFalse(first.reconcileStopped(old.id(),original.guard(),proof,s.foreign).accepted());
        var next=reopen(s); next.attachRecovery(RecoveredJobOwnersTest::noEffect); Job current=next.query(old.id(),s.owner);
        assertFalse(next.reconcileStopped(current.id(),current.guard(),proof,s.owner).accepted());
        assertTrue(first.cancel(old.id(),original.guard(),s.owner).accepted()); first.tick();
        assertFalse(first.reconcileStopped(old.id(),original.guard(),proof,s.owner).accepted());
    }
    @Test void partialCustodyStaysVisibleAndFencedUntilNativeProtocolExists() {
        var s=new JobLifecycleStoreTest.Scene();Job old=s.assign(s.create(2));var store=reopen(s);
        var recovery=new RecoveredJobOwners(store,new Live(),c -> new StoppedObservation(StoppedKind.PARTIAL,
                noEffect(c).report(),"fixture: incomplete physical custody protocol"),4);
        recovery.step(); assertEquals(RecoveredJobOwners.Blocker.PARTIAL_CUSTODY,recovery.view(old.id(),s.owner).blocker());
        assertTrue(store.query(old.id(),s.owner).current().open());
    }
    @Test void privateActivityPaginationDoesNotExposeForeignJobs() {
        var s=new JobLifecycleStoreTest.Scene();Job old=s.assign(s.create(2));var recovery=owners(reopen(s),new Live());
        assertThrows(SecurityException.class,() -> recovery.view(old.id(),s.foreign));
        assertTrue(recovery.page(s.foreign,0,4).activities().isEmpty());
        assertThrows(IllegalArgumentException.class,() -> recovery.page(s.owner,0,5));
        assertEquals(old.id(),recovery.page(s.owner,0,1).activities().getFirst().root());
    }
    @Test void roundRobinWorkIsBoundedAndVisitsAllOriginalOwners() {
        var s=new JobLifecycleStoreTest.Scene();
        for(int n=0;n<8;n++)s.assign(s.create(1),JobLifecycleStoreTest.actor(),JobLifecycleStoreTest.limits(s.clock.now+10000));
        var recovery=owners(reopen(s),new Live());recovery.step();assertEquals(4,recovery.counters().inspected());
        recovery.step();assertEquals(4,recovery.counters().inspected());assertEquals(8,recovery.counters().cached());
        assertEquals(8,recovery.counters().visits());
    }
    @Test void scopedEffectFenceSurvivesWithoutLeasesAndAllowsUnrelatedResources() {
        var s=new JobLifecycleStoreTest.Scene();Job old=s.assign(s.create(2));var recovery=owners(reopen(s),new Live());
        var request=JobLifecycleStoreTest.request(JobLifecycleStoreTest.actor(),1);
        var bound=new ValidatedRequest(request,s.owner,JobLifecycleStoreTest.observation());
        assertTrue(recovery.fenced(bound,Effect.HARVEST,new SurvivalGateway.Cell("minecraft:overworld",1,0,1)));
        assertTrue(recovery.fenced(bound,Effect.TRANSFER,new SurvivalGateway.Cell("minecraft:overworld",3,0,3)));
        assertFalse(recovery.fenced(bound,Effect.HARVEST,new SurvivalGateway.Cell("minecraft:overworld",20,0,20)));
        assertFalse(recovery.fenced(bound,Effect.OBSERVE,new SurvivalGateway.Cell("minecraft:overworld",1,0,1)));
        assertTrue(recovery.view(old.id(),s.owner).physicalEvidence().contains("ambiguous"));
    }
}
