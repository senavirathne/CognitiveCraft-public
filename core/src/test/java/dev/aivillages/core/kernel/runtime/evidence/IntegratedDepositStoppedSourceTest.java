package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.*;
import java.time.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dev.aivillages.core.kernel.Jobs.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Jobs and evidence owners with synthetic saved participants; no native qualification credit. */
final class IntegratedDepositStoppedSourceTest {
    private static JoinedDepositStoppedSource source(ActivityCheckpointsTest.Scene scene) {
        var entry=scene.entry;var pickup=entry.intent().pickupPrefix().pickup();var harvest=pickup.intent().harvest();
        var clock=Clock.fixed(Instant.ofEpochMilli(100),ZoneOffset.UTC);
        var h=new HarvestEvidenceStore(new HarvestJournal.Snapshot(entry.intent().key().world(),UUID.randomUUID(),1,List.of(harvest)),
                (before,after)->CompletableFuture.completedFuture(after),new HarvestEvidenceStoreTest.Port(harvest.intent()),clock,30_000,false);
        var p=new PickupEvidenceStore(new PickupJournal.Snapshot(entry.intent().key().world(),UUID.randomUUID(),1,List.of(pickup)),h,
                (before,after)->CompletableFuture.completedFuture(after),new PickupEvidenceStoreTest.Port(),clock,30_000,false);
        return new JoinedDepositStoppedSource(scene.jobs,new JoinedDepositStoppedSource.Evidence() {
            public HarvestEvidenceStore harvest(){return h;}
            public PickupEvidenceStore pickup(){return p;}
            public DepositEvidenceStore deposit(){return scene.deposits;}
            public DepositEvidenceStore.Code verify(DepositEvidenceStore.Reconciled proof){return scene.deposits.verify(proof);}
        },key->false,clock);
    }
    @Test void savedIntegratedMaterialDeliverySettlesTheOriginalAttemptAndPreservesRootGoal(@TempDir Path world) throws Exception {
        var scene=new ActivityCheckpointsTest.Scene(false,true,true);var original=scene.job();
        var source=source(scene);var authority=scene.jobs.attachRecovery(source);
        assertEquals(StoppedKind.AMBIGUOUS,source.observe(StoppedContext.of(original)).kind());
        scene.deposits.tick();var evidence=authority.capture(StoppedContext.of(original));
        assertEquals(StoppedKind.EFFECT_COMPLETE,evidence.observation().kind());
        var report=evidence.observation().report();assertEquals(3,report.effects());
        assertEquals(Outcomes.ExecutionStatus.SUCCEEDED,report.terminal().status());
        assertEquals(scene.entry.token(),report.terminal().completion().id());
        assertEquals("native-hpd:1",report.terminal().completion().validatorVersion());
        assertEquals(original.allowance(),scene.job().allowance());
        assertTrue(scene.jobs.reconcileStopped(original.id(),original.guard(),evidence,original.origin()).accepted());scene.jobs.tick();
        var settled=scene.job();assertEquals(State.WAITING,settled.state());assertEquals(0,settled.fulfilled());
        assertEquals(original.request(),settled.request());assertEquals(original.current().id(),settled.current().id());
        assertEquals(original.generation(),settled.generation());assertFalse(settled.current().open());
        assertEquals(report.resourceReceipts(),settled.current().resourceReceipts());
        assertEquals(1,settled.activity().checkpoints().size());assertNull(settled.activity().current());
        var worldId=original.origin().scope().worldId();
        try(var journal=JobJournal.open(world,worldId)) {
            journal.replace(journal.snapshot(),new Snapshot(worldId,1,List.of(settled),List.of()));
        }
        try(var journal=JobJournal.open(world,worldId)) {
            assertFalse(journal.readOnly());assertEquals(settled,journal.snapshot().jobs().getFirst());
        }
        assertEquals(0,scene.mutations);
    }
    @Test void liveCountsCannotGrantSavedIntegratedCompletion() {
        var scene=new ActivityCheckpointsTest.Scene(true,false,true);var before=scene.jobs.snapshot();
        assertEquals(StoppedKind.AMBIGUOUS,source(scene).observe(StoppedContext.of(scene.job())).kind());
        assertEquals(before,scene.jobs.snapshot());assertFalse(scene.deposits.pending());
        assertTrue(scene.job().current().open());assertTrue(scene.job().activity().checkpoints().isEmpty());
        assertEquals(0,scene.mutations);
    }
}
