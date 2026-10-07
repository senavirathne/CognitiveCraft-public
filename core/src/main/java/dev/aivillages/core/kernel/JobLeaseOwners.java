package dev.aivillages.core.kernel;

import java.time.Clock;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.ResourceLeases.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;

/** Read-only binding to the actual job owner; no assignments, selection or lifecycle writes. */
public final class JobLeaseOwners implements ResourceLeaseService.Owners {
    private final JobLifecycleStore jobs;
    private final Clock clock;
    public JobLeaseOwners(JobLifecycleStore jobs,Clock clock) {
        this.jobs=Objects.requireNonNull(jobs);this.clock=Objects.requireNonNull(clock);
    }
    @Override public OwnerFacts inspect(Owner owner,TrustedContext caller) {
        if(!jobs.mayControl(owner.job(),owner.generation(),caller))return null;
        Jobs.Job job=jobs.query(owner.job(),caller);
        Jobs.Attempt attempt=job.current();ActorRef actor=attempt==null?job.responsible():attempt.worker();
        UUID run=attempt==null || attempt.executions().isEmpty()?null:attempt.executions().getLast().runId();
        Reason reason=switch(job.state()) {
            case READY,WAITING,ACTIVE -> null;
            case CANCELLING,CANCELLED -> Reason.CANCELLED;
            case INTERRUPTED -> Reason.INTERRUPTED;
            case SUCCEEDED,FAILED -> Reason.ACTION_FAILED;
        };
        if(clock.millis()>job.allowance().deadlineEpochMillis())reason=Reason.BUDGET_EXHAUSTED;
        return new OwnerFacts(job.origin(),actor,run,reason==null,reason);
    }
    public Owner byRun(ValidatedRequest request,RunCorrelation correlation) {
        for(Jobs.Job job:jobs.snapshot().jobs()) {
            Jobs.Attempt attempt=job.current();
            Jobs.ExecutionReference current=attempt==null || attempt.executions().isEmpty()
                    ? null:attempt.executions().getLast();
            if(current!=null && current.runId().equals(correlation.runId())
                    // Routing refreshes observation evidence after the job is durably created.
                    // Its typed arguments and authority must still match the current assignment.
                    && attempt.bound().request().equals(request.request())
                    && attempt.bound().context().equals(request.context())
                    && current.artifact().equals(correlation.artifact())
                    && jobs.mayControl(job.id(),job.generation(),request.context()))
                return new Owner(job.id(),job.generation());
        }
        throw new SecurityException("No current durable job execution");
    }
}
