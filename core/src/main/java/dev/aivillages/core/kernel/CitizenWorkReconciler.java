package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.CitizenRegistry.*;
import static dev.aivillages.core.kernel.Outcomes.*;

/** Coordinates existing runtime/Job/lease APIs. Never reconstructs inventory or closes uncertainty. */
public final class CitizenWorkReconciler implements CitizenLifecycle.Owners {
    @FunctionalInterface public interface Runtime { boolean stop(ActorRef actor); }
    private final Runtime runtime;
    private final JobLifecycleStore jobs;
    private final ResourceLeaseService leases;
    public CitizenWorkReconciler(Runtime runtime, JobLifecycleStore jobs, ResourceLeaseService leases) {
        this.runtime = Objects.requireNonNull(runtime); this.jobs = Objects.requireNonNull(jobs);
        this.leases = Objects.requireNonNull(leases);
    }
    @Override public boolean stop(Citizen c, CitizenLifecycle.Marker loss) { return runtime.stop(c.actor()); }
    @Override public boolean jobs(Citizen c, CitizenLifecycle.Marker loss) {
        if (!jobs.ready()) return false;
        // Bounded by the existing Job snapshot quota. One owner publication per notification.
        for (var job : jobs.snapshot().jobs()) {
            var a = job.current();
            if (!job.origin().equals(c.owner()) || a == null || !a.worker().equals(c.actor())
                    || job.state() != Jobs.State.ACTIVE && job.state() != Jobs.State.CANCELLING) continue;
            // The runtime has already acknowledged cessation. Existing receipts are the only credit.
            var status = job.state() == Jobs.State.CANCELLING ? ExecutionStatus.CANCELLED : ExecutionStatus.INTERRUPTED;
            jobs.recordExecutionResult(job.id(), job.guard(), new Jobs.Report(a.id(), a.generation(), a.effects(),
                    a.usage(), a.receipts(), new Execution(status, status == ExecutionStatus.CANCELLED
                    ? Reason.CANCELLED : Reason.INTERRUPTED, a.effects(), null), a.resourceReceipts()), c.owner());
            return false;
        }
        return true;
    }
    @Override public boolean leases(Citizen c, CitizenLifecycle.Marker loss) {
        if (!leases.ready() || !jobs.ready()) return false;
        for (var lease : leases.snapshot().leases()) {
            if (lease.state() != ResourceLeases.State.ACTIVE || !lease.origin().equals(c.owner())) continue;
            var job = jobs.snapshot().jobs().stream().filter(j -> j.id().equals(lease.owner().job())
                    && j.origin().equals(c.owner())).findFirst().orElse(null);
            if (job == null || job.attempts().stream().noneMatch(a ->
                    a.generation() == lease.owner().generation() && a.worker().equals(c.actor()))) continue;
            // Exact group/epoch/lease generations. A replacement holder cannot match these refs.
            var refs = leases.snapshot().leases().stream().filter(l -> l.group().equals(lease.group()))
                    .map(ResourceLeases.Lease::ref).toList();
            leases.release(refs, c.owner()); return false;
        }
        return true;
    }
}
