package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static dev.aivillages.core.kernel.CitizenLifecycle.*;
import static org.junit.jupiter.api.Assertions.*;

final class CitizenWorkReconcilerTest {
    @Test void partialReceiptsAndUncertainResponsibilitySurviveWithoutSuccessOrReplay() {
        var s = new JobLifecycleStoreTest.Scene(); var job = s.assign(s.create(5));
        job = s.report(job, 2, null);
        var leaseDisk = ResourceLeases.Snapshot.empty(s.world);
        var leases = new ResourceLeaseService(leaseDisk, (a,b) -> CompletableFuture.completedFuture(b),
                new JobLeaseOwners(s.store, s.clock), (r,o) -> { throw new AssertionError("No world mutation/scan"); },
                ResourceLeases.Settings.production(), () -> 0, s.clock, false); leases.tick();
        var c = new CitizenRegistry.Citizen(s.actor, s.owner, "Ada", CitizenRegistry.Availability.UNAVAILABLE);
        var dead = new Marker(1, Event.DEATH, Source.AFTER_DEATH, 1, 0);
        var reconcile = new CitizenWorkReconciler(a -> true, s.store, leases);
        assertTrue(reconcile.stop(c, dead)); assertFalse(reconcile.jobs(c, dead)); s.settle();
        var stopped = s.current(job);
        assertEquals(Jobs.State.INTERRUPTED, stopped.state()); assertEquals(2, stopped.fulfilled());
        assertEquals(5, ((IntValue)stopped.request().request().arguments().get("amount")).value()); assertTrue(stopped.current().uncertain());
        assertEquals(job.current().receipts(), stopped.current().receipts());
        assertFalse(s.store.workerAvailable(s.actor, s.owner));
        int writes = s.memory.writes;
        assertTrue(reconcile.jobs(c, dead)); assertTrue(reconcile.leases(c, dead));
        assertEquals(writes, s.memory.writes);
    }
    @Test void explicitCancellationRetainsItsMeaningDuringCitizenLoss() {
        var s = new JobLifecycleStoreTest.Scene(); var job = s.assign(s.create(5));
        JobLifecycleStoreTest.commit(s, s.store.cancel(job.id(), job.guard(), s.owner));
        var leases = new ResourceLeaseService(ResourceLeases.Snapshot.empty(s.world),
                (a,b) -> CompletableFuture.completedFuture(b), new JobLeaseOwners(s.store, s.clock),
                (r,o) -> { throw new AssertionError(); }, ResourceLeases.Settings.production(), () -> 0, s.clock, false);
        var reconcile = new CitizenWorkReconciler(a -> true, s.store, leases);
        var c = new CitizenRegistry.Citizen(s.actor, s.owner, null, CitizenRegistry.Availability.UNAVAILABLE);
        reconcile.jobs(c, new Marker(1, Event.DEATH, Source.KILLED, 1, 1)); s.settle();
        assertEquals(Jobs.State.CANCELLED, s.current(job).state());
    }
    @Test void lossCannotMutateForeignJobsOrAnotherWorkersClaim() {
        var s = new JobLifecycleStoreTest.Scene(); var first = s.assign(s.create(5));
        var other = JobLifecycleStoreTest.actor(); var second = s.assign(s.create(5), other, JobLifecycleStoreTest.limits(11000));
        var disk = new java.util.concurrent.atomic.AtomicReference<>(ResourceLeases.Snapshot.empty(s.world));
        var leases = new ResourceLeaseService(disk.get(), (a,b) -> {
            assertEquals(disk.get(), a); disk.set(b); return CompletableFuture.completedFuture(b);
        }, new JobLeaseOwners(s.store, s.clock), (r,o) -> new ResourceLeases.Observation(r, ObservationStatus.PRESENT,
                10, "physical-source", 0, 1, null), ResourceLeases.Settings.production(), () -> 0, s.clock, false);
        leases.tick();
        var aResource = ResourceLeases.Resource.stock(s.world, new ContainerRef(s.actor.dimension(), 1, 0, 1));
        var bResource = ResourceLeases.Resource.stock(s.world, new ContainerRef(other.dimension(), 2, 0, 2));
        assertTrue(leases.acquire(new ResourceLeases.Owner(first.id(), first.generation()),
                List.of(new ResourceLeases.Demand(aResource, 2)), 100, s.owner).code() == ResourceLeases.Code.PENDING); leases.tick();
        assertTrue(leases.acquire(new ResourceLeases.Owner(second.id(), second.generation()),
                List.of(new ResourceLeases.Demand(bResource, 2)), 100, s.owner).code() == ResourceLeases.Code.PENDING); leases.tick();
        var originalOther = s.current(second);
        var c = new CitizenRegistry.Citizen(s.actor, s.owner, "Ada", CitizenRegistry.Availability.UNAVAILABLE);
        var dead = new Marker(1, Event.DEATH, Source.AFTER_DEATH, 1, 0);
        var reconcile = new CitizenWorkReconciler(a -> true, s.store, leases);
        reconcile.jobs(c, dead); s.settle(); reconcile.leases(c, dead); leases.tick();
        assertEquals(originalOther, s.current(second));
        assertTrue(leases.snapshot().leases().stream().anyMatch(l -> l.owner().job().equals(second.id())
                && l.state() == ResourceLeases.State.ACTIVE));
        assertFalse(leases.snapshot().leases().stream().anyMatch(l -> l.owner().job().equals(first.id())
                && l.state() == ResourceLeases.State.ACTIVE));
        var foreign = new CitizenRegistry.Citizen(other, s.foreign, null, CitizenRegistry.Availability.UNAVAILABLE);
        assertTrue(reconcile.jobs(foreign, dead)); assertTrue(reconcile.leases(foreign, dead));
        assertEquals(originalOther, s.current(second));
    }
}
