package dev.aivillages.fabric;

import dev.aivillages.core.kernel.JobLifecycleStore;
import dev.aivillages.core.kernel.Jobs;
import dev.aivillages.core.kernel.LogAcquisition;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit;
import dev.aivillages.fabric.runtime.evidence.FabricDepositProtocol;
import dev.aivillages.fabric.runtime.evidence.FabricPickupProtocol;
import java.util.Comparator;
import java.util.List;

/** Rebuilds a completed log prefix when a later native phase owns its actor and recipient. */
final class SavedActivityAcquisitions {
    private final JobLifecycleStore jobs;
    private final FabricDepositProtocol deposits;
    private final FabricPickupProtocol pickups;
    private final FabricActivityRecipeEffects recipes;

    SavedActivityAcquisitions(JobLifecycleStore jobs, FabricDepositProtocol deposits,
                              FabricPickupProtocol pickups, FabricActivityRecipeEffects recipes) {
        this.jobs = jobs;
        this.deposits = deposits;
        this.pickups = pickups;
        this.recipes = recipes;
    }

    boolean recognizes(NativeDeposit.Entry original) {
        try {
            var key = original.intent().key();
            var job = jobs.query(key.root(), original.intent().origin());
            if (job.activity() == null || job.activity().production() == null || !completed(job, original)) return false;
            var cohort = deposits.evidence().snapshot().entries().stream()
                    .filter(entry -> entry.intent().key().root().equals(key.root()) && completed(job, entry)
                            && entry.intent().key().generation() >= key.generation())
                    .sorted(Comparator.comparingLong(entry -> entry.intent().key().generation())).toList();
            if (cohort.isEmpty() || !cohort.getFirst().equals(original)) return false;
            var previous = original;
            for (var next : cohort.subList(1, cohort.size())) {
                var nextHarvest = next.intent().pickupPrefix().pickup().intent().harvest().intent();
                if (previous.intent().key().generation() + 1 != next.intent().key().generation()
                        || !previous.result().actor().equals(nextHarvest.actorBefore())
                        || !previous.result().chest().equals(next.intent().chest())
                        || !pickups.restoredDeliveryRemainders(previous)) return false;
                previous = next;
            }
            var direct = deposits.reconcile(previous.intent().key());
            if (direct != null && direct.result() == NativeDeposit.Reconciliation.SAVED_COMPLETE) return true;
            if (!pickups.restoredDeliveryRemainders(previous)) return false;
            if (pickups.savedPickupFollows(job, previous) && deposits.restoredRecipient(previous)) return true;
            return recipes.savedRecipeFollows(job, previous);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static boolean completed(Jobs.Job job, NativeDeposit.Entry entry) {
        var intent = entry.intent();
        var k = intent.key();
        if (!job.origin().equals(intent.origin()) || !k.root().equals(k.step()) || intent.pickupPrefix() == null
                || entry.result() == null || entry.result().live() != NativeDeposit.Live.COMPLETE) return false;
        var step = job.activity().forAttempt(k.attempt());
        if (step == null || !step.integrated() || !LogAcquisition.is(step.invocation().capability())
                || job.activity().checkpoints().stream().noneMatch(c -> c.attempt().equals(k.attempt())
                        && c.generation() == k.generation() && c.quantity() == 1)) return false;
        var pick = intent.pickupPrefix().pickup();
        var expected = List.of(pick.intent().harvest().result().receipt(), pick.result().receipt(), entry.result().receipt());
        return job.attempts().stream().anyMatch(a -> a.id().equals(k.attempt()) && a.generation() == k.generation()
                && !a.open() && !a.uncertain() && a.credited() == 1 && a.resourceReceipts().equals(expected));
    }
}
