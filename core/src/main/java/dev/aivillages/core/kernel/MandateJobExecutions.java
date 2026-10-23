package dev.aivillages.core.kernel;

import java.util.Objects;
import java.util.function.Supplier;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Mandates.*;

/** Exact read/cancellation seam into the original Job owner; never writes assignments or leases. */
public final class MandateJobExecutions implements MandateRegistry.Executions {
    private final Supplier<JobLifecycleStore> source;
    public MandateJobExecutions(Supplier<JobLifecycleStore> source) { this.source = Objects.requireNonNull(source); }
    @Override public MandateRegistry.Assignment assignment(ValidatedRequest request, RunCorrelation run) {
        JobLifecycleStore jobs = source.get(); if (jobs == null || !jobs.ready()) return null;
        for (Jobs.Job job : jobs.snapshot().jobs()) if (matches(job, request, run.runId(), run.artifact()))
            return new MandateRegistry.Assignment(job.id(), job.generation(), run.artifact(), job.current().allowance());
        return null;
    }
    @Override public boolean current(Binding binding) {
        JobLifecycleStore jobs = source.get(); if (jobs == null) return false;
        return jobs.snapshot().jobs().stream().anyMatch(j -> j.id().equals(binding.job())
                && j.generation() == binding.generation() && matches(j, binding.request(), binding.run(), binding.artifact())
                && j.current().allowance().equals(binding.allowance()));
    }
    @Override public void requestStop(Binding binding) {
        JobLifecycleStore jobs = source.get(); if (jobs == null || !jobs.ready()) return;
        for (Jobs.Job job : jobs.snapshot().jobs()) if (job.id().equals(binding.job())
                && job.generation() == binding.generation() && matches(job, binding.request(), binding.run(), binding.artifact())) {
            jobs.cancel(job.id(), job.guard(), job.origin()); return;
        }
    }
    private static boolean matches(Jobs.Job job, ValidatedRequest request, java.util.UUID run, ArtifactRef artifact) {
        Jobs.Attempt attempt = job.current();
        if (job.state() != Jobs.State.ACTIVE || attempt == null || !attempt.open() || attempt.uncertain()
                || !attempt.bound().request().equals(request.request()) || !attempt.bound().context().equals(request.context())
                || attempt.executions().isEmpty()) return false;
        Jobs.ExecutionReference last = attempt.executions().getLast();
        return last.runId().equals(run) && last.artifact().equals(artifact);
    }
}
