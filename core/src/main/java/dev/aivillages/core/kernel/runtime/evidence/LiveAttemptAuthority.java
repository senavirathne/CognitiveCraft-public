package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.Budgets;
import dev.aivillages.core.kernel.Jobs;
import java.util.Map;
import java.util.Objects;

/** The original live binding can survive acknowledged usage observations. */
public final class LiveAttemptAuthority {
    private LiveAttemptAuthority() { }

    public static boolean unchanged(Jobs.Job before, Jobs.Job current) {
        if (!live(before) || !live(current)) return false;
        if (before == current) return true;
        if (current.revision() < before.revision() || !before.id().equals(current.id())
                || !before.submissionId().equals(current.submissionId()) || !before.request().equals(current.request())
                || !Objects.equals(before.parent(), current.parent()) || !before.dependencies().equals(current.dependencies())
                || !before.children().equals(current.children()) || before.reason() != current.reason()
                || !before.allowance().equals(current.allowance()) || before.generation() != current.generation()
                || !Objects.equals(before.preparation(), current.preparation())
                || before.attempts().size() != current.attempts().size()) return false;

        int last = before.attempts().size() - 1;
        if (!before.attempts().subList(0, last).equals(current.attempts().subList(0, last))) return false;
        var oldAttempt = before.current();
        var attempt = current.current();
        return oldAttempt.id().equals(attempt.id()) && oldAttempt.generation() == attempt.generation()
                && oldAttempt.worker().equals(attempt.worker()) && oldAttempt.bound().equals(attempt.bound())
                && oldAttempt.allowance().equals(attempt.allowance()) && oldAttempt.executions().equals(attempt.executions())
                && oldAttempt.receipts().equals(attempt.receipts()) && oldAttempt.resourceReceipts().equals(attempt.resourceReceipts())
                && oldAttempt.effects() == attempt.effects() && oldAttempt.credited() == attempt.credited()
                && oldAttempt.cancellationDeliveries() == attempt.cancellationDeliveries()
                && usageProgress(before.usage(), current.usage(), current.allowance())
                && usageProgress(oldAttempt.usage(), attempt.usage(), attempt.allowance());
    }

    private static boolean live(Jobs.Job job) {
        return job != null && job.state() == Jobs.State.ACTIVE && job.cancellationOutcome() == null
                && job.current() != null && job.current().terminal() == null && !job.current().uncertain();
    }

    private static boolean usageProgress(Map<Budgets.Kind, Long> before, Map<Budgets.Kind, Long> after, Budgets.Limits limits) {
        for (var kind : Budgets.Kind.values()) {
            long value = after.getOrDefault(kind, 0L);
            if (value < before.getOrDefault(kind, 0L) || value > limits.maximum(kind)) return false;
        }
        return true;
    }
}
