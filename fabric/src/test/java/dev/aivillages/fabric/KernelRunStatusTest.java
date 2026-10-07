package dev.aivillages.fabric;

import dev.aivillages.core.kernel.BootstrapController;
import dev.aivillages.core.kernel.BootstrapJournal;
import dev.aivillages.core.kernel.Contracts.ArtifactRef;
import dev.aivillages.core.kernel.CropDelivery;
import dev.aivillages.core.kernel.Outcomes.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** A failed metadata acknowledgement must not hide known committed work in player status. */
final class KernelRunStatusTest {
    private final UUID runId = UUID.randomUUID(), citizen = UUID.randomUUID();
    private final ArtifactRef artifact = new ArtifactRef(CropDelivery.ID, "a".repeat(64));

    @Test void failedTerminalWriteShowsLiveCancellationAndCheckpointSeparately() {
        var marker = new BootstrapJournal.RunMarker(runId, citizen, BootstrapJournal.Phase.ACTIVE,
                null, null, 0, 0, null);
        var view = new BootstrapController.View(runId, BootstrapController.Phase.TERMINAL,
                marker, null, new Execution(ExecutionStatus.CANCELLED, Reason.CANCELLED, 3, null),
                null, List.of(), Reason.STORAGE_UNAVAILABLE);
        String status = KernelRunStatus.describe(view);
        assertTrue(status.contains("outcome=CANCELLED reason=CANCELLED"));
        assertTrue(status.contains("effectsAtCheckpoint=0"));
        assertTrue(status.contains(" effects=3"));
        assertTrue(status.contains("storage=STORAGE_UNAVAILABLE"));
        assertTrue(status.contains("physical progress must be reconciled"));
        assertFalse(status.contains("outcome=null"));
        assertFalse(status.contains(" effects=0"));
    }

    @Test void failedResearchMarkerShowsActualStorageReasonAndModelCalls() {
        var marker = new BootstrapJournal.RunMarker(runId, citizen, BootstrapJournal.Phase.ACTIVE,
                null, null, 0, 0, null);
        var view = new BootstrapController.View(runId, BootstrapController.Phase.TERMINAL,
                marker, null, null,
                new Research(ResearchStatus.BLOCKED, Reason.STORAGE_LIMIT_REACHED,
                        artifact, null, false, 1), List.of(), Reason.STORAGE_UNAVAILABLE);
        String status = KernelRunStatus.describe(view);
        assertTrue(status.contains("outcome=BLOCKED reason=STORAGE_LIMIT_REACHED"));
        assertTrue(status.contains("effectsAtCheckpoint=0"));
        assertTrue(status.contains("modelCalls=1"));
        assertTrue(status.contains("artifact=" + artifact.sha256()));
        assertFalse(status.contains("ADMITTED"));
        assertFalse(status.contains(" effects=0"));
    }

    @Test void reloadedInterruptionDoesNotPresentCheckpointAsExactPhysicalProgress() {
        var marker = new BootstrapJournal.RunMarker(runId, citizen, BootstrapJournal.Phase.INTERRUPTED,
                "INTERRUPTED", Reason.INTERRUPTED, 3, 0, artifact.sha256());
        var view = new BootstrapController.View(runId, BootstrapController.Phase.TERMINAL,
                marker, null, null, null, List.of(), null);
        String status = KernelRunStatus.describe(view);
        assertTrue(status.contains("outcome=INTERRUPTED reason=INTERRUPTED"));
        assertTrue(status.contains("effectsAtCheckpoint=3"));
        assertTrue(status.contains("physical progress must be reconciled"));
        assertFalse(status.contains(" effects=3"));
    }

    @Test void reloadedCommittedSummaryRetainsItsTerminalCounts() {
        var marker = new BootstrapJournal.RunMarker(runId, citizen, BootstrapJournal.Phase.TERMINAL,
                "ADMITTED", null, 12, 1, artifact.sha256());
        var view = new BootstrapController.View(runId, BootstrapController.Phase.TERMINAL,
                marker, null, null, null, List.of(), null);
        String status = KernelRunStatus.describe(view);
        assertTrue(status.contains("outcome=ADMITTED"));
        assertTrue(status.contains(" effects=12"));
        assertTrue(status.contains("modelCalls=1"));
        assertTrue(status.contains("artifact=" + artifact.sha256()));
        assertFalse(status.contains("AtCheckpoint"));
        assertFalse(status.contains("physical progress must be reconciled"));
    }
}
