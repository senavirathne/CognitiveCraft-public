package dev.aivillages.core.kernel;

import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Jobs.*;
import static org.junit.jupiter.api.Assertions.*;

/** Diagnostic timing only. Requested publication cannot stand in for a durable Job or native proof. */
final class JobPublicationObservationTest {
    @Test void requestedSnapshotIsObservedBeforeStorageSubmissionAndBeforeDurableAcknowledgement() {
        var s=new JobLifecycleStoreTest.Scene();var before=s.store.snapshot();s.memory.hold=true;
        var observed=new ArrayList<JobLifecycleStore.PublicationObservation>();
        s.store.observePublications(p->{
            assertSame(before,p.before());assertSame(before,s.store.snapshot());assertEquals(0,s.memory.writes);
            assertEquals(1,p.requested().jobs().size());observed.add(p);
        });
        var id=UUID.randomUUID();var change=s.store.create(id,id,JobLifecycleStoreTest.request(s.actor,1),s.owner,
                JobLifecycleStoreTest.observation(),JobLifecycleStoreTest.limits(11_000),List.of());
        assertEquals(Code.PENDING,change.code());assertEquals(1,observed.size());assertSame(before,s.store.snapshot());
        assertSame(observed.getFirst().requested(),s.memory.heldState);assertFalse(s.store.ready());
        s.memory.release();s.store.tick();assertSame(observed.getFirst().requested(),s.store.snapshot());
        assertEquals(1,observed.size());assertTrue(s.store.ready());
    }
    @Test void rejectedStaleRequestDoesNotProduceAPublicationObservation() {
        var s=new JobLifecycleStoreTest.Scene();var j=s.create(1);var observed=new ArrayList<JobLifecycleStore.PublicationObservation>();
        s.store.observePublications(observed::add);
        var rejected=s.store.assign(j.id(),new Guard(j.revision()-1,j.generation()),UUID.randomUUID(),s.actor,
                JobLifecycleStoreTest.REF,List.of(JobLifecycleStoreTest.REF),JobLifecycleStoreTest.limits(11_000),s.owner);
        assertFalse(rejected.accepted());assertTrue(observed.isEmpty());assertEquals(j,s.current(j));
    }
}
