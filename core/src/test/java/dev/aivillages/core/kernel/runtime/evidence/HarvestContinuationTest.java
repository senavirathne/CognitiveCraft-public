package dev.aivillages.core.kernel.runtime.evidence;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Original in-process linkage contracts; synthetic ports cannot qualify native pickup. */
final class HarvestContinuationTest {
    static final class Scene {
        final NativeHarvest.Entry harvest=NativePickupTest.harvest(1);
        final HarvestEvidenceStoreTest.Time clock=new HarvestEvidenceStoreTest.Time();
        final HarvestEvidenceStoreTest.Port port=new HarvestEvidenceStoreTest.Port(harvest.intent());
        final List<CompletableFuture<HarvestJournal.Snapshot>> writes=new ArrayList<>();
        final List<HarvestJournal.Snapshot> next=new ArrayList<>();
        final HarvestEvidenceStore store;
        Scene() {
            port.completed=harvest;
            store=new HarvestEvidenceStore(new HarvestJournal.Snapshot(harvest.intent().key().world(),UUID.randomUUID(),0,List.of()),
                    (before,after)->{var f=new CompletableFuture<HarvestJournal.Snapshot>();writes.add(f);next.add(after);return f;},port,clock,100,false);
        }
        void ack(){int n=writes.size()-1;writes.get(n).complete(next.get(n));store.tick();}
        HarvestEvidenceStore.Prepared prepare(){store.prepare(harvest.intent());ack();return store.prepare(harvest.intent()).prepared();}
        HarvestEvidenceStore.Acknowledged complete(){var prepared=prepare();store.perform(prepared);ack();return store.acknowledged(harvest.intent().key());}
    }
    @Test void originalResultMustAcknowledgeBeforeAnyLiveLinkIsIssued() {
        var s=new Scene();var key=s.harvest.intent().key();assertNull(s.store.acknowledged(key));var prepared=s.prepare();
        assertNull(s.store.acknowledged(key));s.store.perform(prepared);assertNull(s.store.acknowledged(key));
        s.ack();var link=s.store.acknowledged(key);assertNotNull(link);assertTrue(s.store.owns(link));
        assertSame(link,s.store.acknowledged(key));assertEquals(s.harvest,link.entry());assertEquals(1,s.port.calls);
    }
    @Test void restoredResultDataAndAnotherStoreCannotRecreateOrBorrowTheOriginalLink() {
        var s=new Scene();var link=s.complete();
        var reopened=new HarvestEvidenceStore(s.store.snapshot(),(before,after)->CompletableFuture.completedFuture(after),s.port,s.clock,100,false);
        assertNull(reopened.acknowledged(s.harvest.intent().key()));assertFalse(reopened.owns(link));
        assertFalse(reopened.links(link,NativePickupTest.intent(s.harvest)));assertEquals(1,s.port.calls);
    }
    @Test void onlyItsExactAcknowledgedAncestorCanLinkTheNextOriginalPickup() {
        var s=new Scene();var link=s.complete();
        assertTrue(s.store.links(link,NativePickupTest.intent(s.harvest)));
        assertFalse(s.store.links(link,NativePickupTest.intent(NativePickupTest.harvest(1))));
        assertFalse(s.store.owns(null));assertFalse(s.store.links(link,null));
        assertEquals(1L,link.entry().result().usage().get(dev.aivillages.core.kernel.Budgets.Kind.COMMITTED_EFFECTS));
        assertEquals(s.harvest.intent().conditions().deadlineEpochMillis(),link.entry().intent().conditions().deadlineEpochMillis());
        assertEquals(1,s.port.calls);
    }
    @Test void savedVerificationInvalidatesTheLiveLinkBeforeItsReleaseAcknowledges() {
        var s=new Scene();var link=s.complete();s.port.restored=true;
        var proof=s.store.reconcile(s.harvest.intent().key());assertEquals(NativeHarvest.Reconciliation.SAVED_COMPLETE,proof.result());
        assertEquals(HarvestEvidenceStore.Code.PENDING,s.store.verify(proof));assertFalse(s.store.owns(link));assertNull(s.store.acknowledged(s.harvest.intent().key()));
        s.ack();assertFalse(s.store.owns(link));assertNull(s.store.acknowledged(s.harvest.intent().key()));assertTrue(s.store.protectedJobs().isEmpty());
    }
    @Test void readOnlyFencedOrWrongThreadAccessNeverGrantsAReplacementLink() throws Exception {
        var s=new Scene();var link=s.complete();
        var readOnly=new HarvestEvidenceStore(s.store.snapshot(),(before,after)->CompletableFuture.completedFuture(after),s.port,s.clock,100,true);
        assertFalse(readOnly.owns(link));assertNull(readOnly.acknowledged(s.harvest.intent().key()));
        var failure=new AtomicReference<Throwable>();
        Thread other=new Thread(()->{try{s.store.acknowledged(s.harvest.intent().key());}catch(Throwable rejected){failure.set(rejected);}});
        other.start();other.join();assertInstanceOf(IllegalStateException.class,failure.get());
        s.port.restored=true;s.store.verify(s.store.reconcile(s.harvest.intent().key()));s.clock.now+=101;s.store.tick();
        assertTrue(s.store.fenced());assertFalse(s.store.owns(link));assertNull(s.store.acknowledged(s.harvest.intent().key()));assertEquals(1,s.port.calls);
    }
}
