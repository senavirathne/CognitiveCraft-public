package dev.aivillages.fabric;

import dev.aivillages.core.kernel.BootstrapController;
import dev.aivillages.core.kernel.BootstrapJournal;
import dev.aivillages.core.kernel.Outcomes.Reason;

/** Presents current outcomes separately from an older persisted progress checkpoint. */
final class KernelRunStatus {
    private KernelRunStatus() { }

    static String describe(BootstrapController.View view) {
        var marker = view.marker();
        boolean checkpoint = marker != null && marker.phase() != BootstrapJournal.Phase.TERMINAL;
        String outcome = marker == null ? null : marker.outcome();
        Reason reason = marker == null ? null : marker.reason();
        long calls = marker == null ? 0 : marker.modelCalls();
        String artifact = marker == null ? null : marker.artifactSha256();
        if (view.outcome() != null) {
            outcome = view.outcome().status().name();
            reason = view.outcome().reason();
        } else if (view.research() != null) {
            outcome = view.research().status().name();
            reason = view.research().reason();
            calls = view.research().modelCalls();
            if (view.research().artifact() != null) artifact = view.research().artifact().sha256();
        }
        return "run=" + view.id() + " phase=" + view.phase()
                + (view.routing() == null ? "" : " resolution=" + view.routing().status()
                        + " resolutionReason=" + view.routing().reason())
                + (outcome == null ? "" : " outcome=" + outcome + " reason=" + reason)
                + (marker == null ? "" : " effects" + (checkpoint ? "AtCheckpoint=" : "=")
                        + marker.effects())
                + (checkpoint && view.outcome() != null
                        ? " effects=" + view.outcome().committedEffects() : "")
                + " modelCalls=" + calls + " artifact=" + artifact
                + " receipts=" + view.receipts().size()
                + (view.storageError() == null ? "" : " storage=" + view.storageError())
                + (marker != null && (marker.phase() == BootstrapJournal.Phase.INTERRUPTED
                        || view.storageError() != null)
                        ? " (physical progress must be reconciled)" : "");
    }
}
