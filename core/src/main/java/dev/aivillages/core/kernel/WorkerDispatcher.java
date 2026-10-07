package dev.aivillages.core.kernel;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;

/**
 * Architecture extension 0.4. Server-thread, model-independent orchestration.
 * Jobs, citizens, claims, resolutions and execution truth remain with their owners.
 * Cursors/diagnostics are disposable; live entries only reference owner handles.
 */
public final class WorkerDispatcher {
    public static final String ARCHITECTURE_VERSION = "0.4";
    public record Settings(int jobs, int workers, int starts, int retries, int live,
                           int diagnostics, long assignmentTicks, long actionMillis) {
        public Settings {
            if (jobs < 1 || jobs > 4 || workers < 1 || workers > 3 || starts < 1 || starts > 2
                    || retries < 1 || retries > 2 || live < 1 || live > 8 || diagnostics < 1 || diagnostics > 16
                    || assignmentTicks < 1 || assignmentTicks > 1200 || actionMillis < 1 || actionMillis > 15000)
                throw new IllegalArgumentException("Finite dispatch limits");
        }
        public static Settings fixture() { return new Settings(4,3,2,2,8,16,20,10000); }
        public static Settings production() { return new Settings(4,3,2,2,8,16,1200,15000); }
    }
    /** Existing executor handles plus an explicit stopped-owner observation. */
    public interface Handle extends BootstrapController.Execution.Handle {
        boolean stopped(TrustedContext origin);
    }
    public record Start(Handle handle, Reason rejected) {
        public Start { if ((handle == null) == (rejected == null)) throw new IllegalArgumentException("Dispatch start"); }
    }
    public interface ExecutionPort {
        List<ArtifactRef> pinned(ArtifactRef artifact);
        Start start(ValidatedRequest bound, ArtifactRef artifact, UUID run,
                    Budgets.ExecutionLimits limits, Budgets.Ledger usage);
    }
    public interface Claims {
        default ResourceLeases.Result preflight(Jobs.Job job,ValidatedRequest proposed) {
            return new ResourceLeases.Result(ResourceLeases.Code.VALID,null,null,List.of(),0,0);
        }
        ResourceLeases.Result prepare(ValidatedRequest request, RunCorrelation run);
        ResourceLeases.Result validate(ValidatedRequest request, RunCorrelation run);
        void release(UUID run);
        boolean released(UUID run);
    }
    public static Claims leasedClaims(LeasedGateway gateway) {
        Objects.requireNonNull(gateway);
        return new Claims() {
            public ResourceLeases.Result preflight(Jobs.Job job,ValidatedRequest proposed){return gateway.preflight(job,proposed);}
            public ResourceLeases.Result prepare(ValidatedRequest request,RunCorrelation run){return gateway.prepare(request,run);}
            public ResourceLeases.Result validate(ValidatedRequest request,RunCorrelation run){return gateway.validate(request,run);}
            public void release(UUID run){gateway.releaseRun(run);}
            public boolean released(UUID run){return gateway.released(run);}
        };
    }
    public static ExecutionPort executorPort(BoundedSkillExecutor executor, ArtifactCatalog catalog) {
        Objects.requireNonNull(executor);Objects.requireNonNull(catalog);
        return new ExecutionPort() {
            public List<ArtifactRef> pinned(ArtifactRef ref){return JobArtifactPins.closure(catalog.find(ref).orElseThrow(),catalog);}
            public Start start(ValidatedRequest bound,ArtifactRef ref,UUID id,Budgets.ExecutionLimits limits,Budgets.Ledger usage) {
                var started=executor.startAdmitted(bound,ref,new RunCorrelation(id,ref,null),limits,usage);
                if(started instanceof BoundedSkillExecutor.Rejected rejected)return new Start(null,rejected.reason());
                var run=((BoundedSkillExecutor.Started)started).run();
                return new Start(new Handle() {
                    public BoundedSkillExecutor.Progress tick(TrustedContext owner){return run.tick(owner);}
                    public BoundedSkillExecutor.Progress progress(TrustedContext owner){return run.progress(owner);}
                    public BoundedSkillExecutor.Progress cancel(TrustedContext owner){return run.cancel(owner);}
                    public BoundedSkillExecutor.Progress interrupt(TrustedContext owner){return run.interrupt(owner);}
                    public boolean stopped(TrustedContext owner){return run.stopped(owner);}
                },null);
            }
        };
    }
    public enum Code { SELECTED, ASSIGNED, STARTED, BLOCKED, DEFERRED, STOPPING, OBSERVED }
    public record Decision(UUID job, ActorRef worker, Code code, Resolution routing, Reason reason,
                           List<CapabilityResolver.CandidateEvidence> alternatives, String tieBreak) {
        public Decision {
            Objects.requireNonNull(job);Objects.requireNonNull(code);alternatives=List.copyOf(alternatives);
            if(alternatives.size()>64 || tieBreak==null || tieBreak.length()>128)throw new IllegalArgumentException("Bounded dispatch evidence");
        }
    }
    public record Slice(List<Decision> decisions, int jobsInspected, int workersInspected,
                        int resolutions, int starts, int retries) {
        public Slice { decisions=List.copyOf(decisions); }
    }
    private enum Phase { CHARGED, READYING, ASSIGNING, CLAIMING, RUNNING, STOPPING, PUBLISHING, RECONCILING }
    private static final class Cursor { UUID job, worker, monitor; int scannedWorkers; }
    private static final class Live {
        final UUID job, run;
        final TrustedContext origin;
        final ActorRef worker;
        final long citizenRevision, expires;
        final ArtifactRef artifact;
        final List<ArtifactRef> pinned;
        final CapabilityResolver.Decision resolution;
        Jobs.Guard expected;
        Phase phase=Phase.CHARGED;
        long generation;
        ValidatedRequest bound;
        Budgets.Ledger usage;
        Handle handle;
        Jobs.Report report;
        Reason failure;
        boolean cancel, released;
        Live(Jobs.Job job, ActorRef worker, long citizenRevision, long expires,
             CapabilityResolver.Decision resolution, List<ArtifactRef> pinned, Jobs.Guard expected) {
            this.job=job.id();origin=job.origin();this.worker=worker;this.citizenRevision=citizenRevision;
            this.expires=expires;this.resolution=resolution;artifact=resolution.routing().artifact();
            this.pinned=List.copyOf(pinned);this.expected=expected;
            run=UUID.nameUUIDFromBytes((job.id()+":"+(job.generation()+1)+":"+worker.citizenId()).getBytes(StandardCharsets.UTF_8));
        }
        RunCorrelation correlation(){return new RunCorrelation(run,artifact,null);}
    }
    private final JobLifecycleStore jobs;
    private final CitizenRegistry citizens;
    private final BootstrapController.Observer observer;
    private final BootstrapController.Resolver resolver;
    private final ExecutionPort execution;
    private final Claims claims;
    private final Predicate<Jobs.Job> accepts;
    private final Clock clock;
    private final LongSupplier ticks;
    private final Settings settings;
    private final Thread thread=Thread.currentThread();
    private final Map<TrustedContext,Cursor> cursors=new LinkedHashMap<>();
    private final Map<UUID,Live> live=new LinkedHashMap<>();
    private final Map<UUID,List<Decision>> diagnostics=new LinkedHashMap<>();
    private long lastTick=-1;
    private boolean closing;
    private static final class Work { int jobs,workers,resolutions,starts,retries; final List<Decision> decisions=new ArrayList<>(); }
    public WorkerDispatcher(JobLifecycleStore jobs, CitizenRegistry citizens,
                            BootstrapController.Observer observer, BootstrapController.Resolver resolver,
                            ExecutionPort execution, Claims claims, Predicate<Jobs.Job> accepts,
                            Clock clock, LongSupplier ticks, Settings settings) {
        this.jobs=Objects.requireNonNull(jobs);this.citizens=Objects.requireNonNull(citizens);
        this.observer=Objects.requireNonNull(observer);this.resolver=Objects.requireNonNull(resolver);
        this.execution=Objects.requireNonNull(execution);this.claims=Objects.requireNonNull(claims);
        this.accepts=Objects.requireNonNull(accepts);this.clock=Objects.requireNonNull(clock);
        this.ticks=Objects.requireNonNull(ticks);this.settings=Objects.requireNonNull(settings);
    }
    public Settings settings(){return settings;}
    public List<Decision> diagnostics(UUID id,TrustedContext caller) {
        checkThread();Jobs.Job job=jobs.query(id,caller);
        if(!job.origin().equals(caller))throw new SecurityException("Private dispatch diagnostics");
        return diagnostics.getOrDefault(id,List.of());
    }
    public void clearPolicyCache(){checkThread();cursors.clear();diagnostics.clear();}
    /** Shutdown stops executor-owned work; durable unfinished records remain interrupted on reload. */
    public void close() {
        checkThread();
        closing=true;
        for(Live l:live.values()) {
            if(l.handle!=null)l.handle.interrupt(l.origin);
            if(l.handle==null||l.handle.stopped(l.origin))claims.release(l.run);
        }
    }

    public Slice step(TrustedContext caller) {
        checkThread();var work=new Work();long tick=ticks.getAsLong();
        if(caller==null || !caller.scope().worldId().equals(jobs.snapshot().worldId()))throw new SecurityException("Dispatch scope");
        // A caller cannot reset the per-tick slice by repeatedly invoking this entry point.
        if(tick<=lastTick)return slice(work);
        lastTick=tick;
        if(!cursors.containsKey(caller)&&cursors.size()==64)return slice(work);
        Cursor cursor=cursors.computeIfAbsent(caller,ignored->new Cursor());
        List<Live> active=live.values().stream().filter(l->l.origin.equals(caller))
                .sorted(Comparator.comparing(l->l.job.toString())).toList();
        int monitorBudget=Math.min(active.size(),Math.max(1,settings.jobs()/2));
        int start=0;
        if(cursor.monitor!=null)while(start<active.size()&&active.get(start).job.toString().compareTo(cursor.monitor.toString())<=0)start++;
        for(int n=0;n<monitorBudget;n++) {
            Live l=active.get((start+n)%active.size());work.jobs++;cursor.monitor=l.job;
            advance(l,work,tick);
        }
        if(closing || !jobs.ready() || !citizens.ready() || live.size()>=settings.live() || work.jobs==settings.jobs())return slice(work);
        var page=jobs.dispatchPage(caller,cursor.job,settings.jobs()-work.jobs);
        var workers=citizens.controlPage(caller,cursor.worker,settings.workers());
        work.workers=workers.citizens().size();
        if(!workers.citizens().isEmpty())cursor.worker=workers.citizens().getLast().actor().citizenId();
        cursor.scannedWorkers+=workers.citizens().size();
        UUID examined=cursor.job;
        for(Jobs.Job job:page.jobs()) {
            examined=job.id();
            work.jobs++;
            if(!accepts.test(job) || live.containsKey(job.id()) || !dispatchable(job))continue;
            Decision last=new Decision(job.id(),null,Code.BLOCKED,null,Reason.ACTOR_UNAVAILABLE,List.of(),"UUID circular order");
            CapabilityResolver.Decision chosen=null;ActorRef selected=null;
            for(var address:workers.citizens()) {
                ActorRef actor=address.actor();
                if(address.availability()!=CitizenRegistry.Availability.LOADED || !citizens.controls(actor,job.origin())
                        || !jobs.workerAvailable(actor,job.origin())) {
                    last=new Decision(job.id(),actor,Code.BLOCKED,null,
                            address.availability()==CitizenRegistry.Availability.UNKNOWN?Reason.STALE_OBSERVATION:Reason.ACTOR_UNAVAILABLE,
                            List.of(),"UUID circular order");continue;
                }
                try {
                    ValidatedRequest proposed=jobs.bindWorker(job.id(),job.guard(),actor,job.origin());
                    var snapshot=observer.capture(proposed.request(),actor);
                    var bound=new ValidatedRequest(proposed.request(),job.origin(),snapshot.reference());
                    var decision=resolver.resolve(bound,snapshot);work.resolutions++;
                    last=decision(job,actor,Code.BLOCKED,decision,decision.routing().reason());
                    if(decision.routing().status()==ResolutionStatus.RESOLVED) {
                        var available=claims.preflight(job,bound);
                        if(available.usable()){chosen=decision;selected=actor;break;}
                        last=decision(job,actor,Code.BLOCKED,decision,available.reason()==null?Reason.TARGET_UNAVAILABLE:available.reason());
                    }
                }catch(SecurityException denied){last=new Decision(job.id(),actor,Code.BLOCKED,null,Reason.AUTHORITY_DENIED,List.of(),"UUID circular order");}
                catch(RuntimeException unavailable){last=new Decision(job.id(),actor,Code.BLOCKED,null,Reason.STORAGE_UNAVAILABLE,List.of(),"UUID circular order");}
            }
            remember(last,work);
            // Charge reconsideration through the actual job owner. No retry replenishes this allowance.
            var charged=jobs.charge(job.id(),job.guard(),Map.of(Budgets.Kind.INSTRUCTIONS,1L),job.origin());
            if(!charged.accepted()) { remember(new Decision(job.id(),selected,Code.DEFERRED,last.routing(),charged.reason(),last.alternatives(),last.tieBreak()),work);continue; }
            if(chosen!=null) {
                try {
                    var l=new Live(job,selected,workers.revision(),Math.addExact(tick,settings.assignmentTicks()),chosen,
                            execution.pinned(chosen.routing().artifact()),charged.job().guard());
                    live.put(job.id(),l);
                    remember(decision(job,selected,Code.SELECTED,chosen,null),work);
                    cursor.job=job.id();cursor.scannedWorkers=0;cursor.worker=selected.citizenId();
                }catch(RuntimeException invalid){remember(new Decision(job.id(),selected,Code.BLOCKED,last.routing(),Reason.ARTIFACT_INVALID,last.alternatives(),last.tieBreak()),work);}
            }
            // One job-owner publication is outstanding; never spin on a pending acknowledgement.
            break;
        }
        if(cursor.scannedWorkers>=workers.total() && !page.jobs().isEmpty()) {
            cursor.job=examined;cursor.scannedWorkers=0;
        }
        return slice(work);
    }
    private boolean dispatchable(Jobs.Job j) {
        return (j.state()==Jobs.State.READY || j.state()==Jobs.State.WAITING && j.dependencies().isEmpty())
                && (j.current()==null || !j.current().open()) && clock.millis()<=j.allowance().deadlineEpochMillis()
                && j.attempts().size()<jobs.settings().attempts();
    }
    private boolean workerCurrent(Live l,boolean generation) {
        if(!citizens.controls(l.worker,l.origin))return false;
        if(generation && citizens.snapshot().revision()!=l.citizenRevision)return false;
        try { return citizens.query(l.worker.citizenId(),l.origin).availability()==CitizenRegistry.Availability.LOADED; }
        catch(SecurityException denied){return false;}
    }
    private void advance(Live l,Work work,long tick) {
        Jobs.Job job;
        try { job=jobs.query(l.job,l.origin); }
        catch(SecurityException revoked){
            stop(l,Reason.AUTHORITY_DENIED,false);
            if(l.handle!=null)l.handle.interrupt(l.origin);
            if(l.handle==null||l.handle.stopped(l.origin))claims.release(l.run);
            return;
        }
        if(l.generation==0) {
            if(!jobs.ready())return;
            if(!job.guard().equals(l.expected) || !dispatchable(job) || tick>=l.expires || !workerCurrent(l,true)) {
                remember(decision(job,l.worker,Code.BLOCKED,l.resolution,Reason.STALE_OBSERVATION),work);live.remove(l.job);return;
            }
            if(job.state()==Jobs.State.WAITING) {
                var change=jobs.transition(job.id(),job.guard(),Jobs.State.READY,null,l.origin);
                if(change.accepted()){l.expected=change.job().guard();l.phase=Phase.READYING;}else live.remove(l.job);
                return;
            }
            try {
                var allowance=jobs.remainingAllowance(job.id(),l.origin);
                var change=jobs.assign(job.id(),job.guard(),l.run,l.worker,l.artifact,l.pinned,allowance,l.origin);
                if(!change.accepted()){remember(decision(job,l.worker,Code.BLOCKED,l.resolution,change.reason()),work);live.remove(l.job);return;}
                l.generation=change.job().generation();l.phase=Phase.ASSIGNING;
            }catch(RuntimeException invalid){remember(decision(job,l.worker,Code.BLOCKED,l.resolution,Reason.BUDGET_EXHAUSTED),work);live.remove(l.job);}
            return;
        }
        // The owner snapshot still contains the previous generation until the assignment write is acknowledged.
        // This is pending publication, not a stale completion or permission to abandon the live proposal.
        if(l.phase==Phase.ASSIGNING&&!jobs.ready())return;
        Jobs.Attempt attempt=job.current();
        if(attempt==null || attempt.generation()!=l.generation || !attempt.id().equals(l.run)) {
            // A stale handle cannot tick, credit, or release the replacement assignment's claims.
            stop(l,Reason.INTERRUPTED,false);if(l.handle==null||l.handle.stopped(l.origin)){claims.release(l.run);if(claims.released(l.run))live.remove(l.job);}return;
        }
        if(l.phase==Phase.PUBLISHING || l.phase==Phase.RECONCILING) {
            if(!jobs.ready())return;
            if(l.phase==Phase.RECONCILING){if(!attempt.open())live.remove(l.job);return;}
            if(attempt.terminal()==null)return;
            if(job.state()==Jobs.State.INTERRUPTED && attempt.uncertain()) {
                var change=jobs.reconcile(job.id(),job.guard(),l.report,true,l.origin);
                if(change.accepted())l.phase=Phase.RECONCILING;
            }else live.remove(l.job);
            return;
        }
        boolean cancelled=job.state()==Jobs.State.CANCELLING || job.state()==Jobs.State.CANCELLED;
        boolean lost=!workerCurrent(l,false) || !jobs.mayControl(job.id(),l.generation,l.origin);
        boolean storageLost=jobs.unavailableReason()==Reason.STORAGE_UNAVAILABLE;
        if(closing || storageLost || cancelled || lost || tick>=l.expires || clock.millis()>attempt.allowance().deadlineEpochMillis())
            stop(l,cancelled?Reason.CANCELLED:storageLost?Reason.STORAGE_UNAVAILABLE:lost?Reason.ACTOR_UNAVAILABLE:Reason.ACTION_TIMEOUT,cancelled);
        if(l.phase!=Phase.STOPPING) {
            if(!jobs.ready())return;
            if(l.bound==null) {
                try {
                    var observed=observer.capture(attempt.bound().request(),l.worker);
                    l.bound=new ValidatedRequest(attempt.bound().request(),l.origin,observed.reference());
                    var checked=resolver.resolve(l.bound,observed);work.resolutions++;
                    if(checked.routing().status()!=ResolutionStatus.RESOLVED || !l.artifact.equals(checked.routing().artifact())) {
                        remember(decision(job,l.worker,Code.BLOCKED,checked,checked.routing().reason()),work);
                        stop(l,checked.routing().reason()==null?Reason.STALE_OBSERVATION:checked.routing().reason(),false);
                    }else l.phase=Phase.CLAIMING;
                }catch(SecurityException denied){stop(l,Reason.AUTHORITY_DENIED,false);}
                catch(RuntimeException unavailable){stop(l,Reason.STALE_OBSERVATION,false);}
            }
            if(l.phase!=Phase.STOPPING) {
                ResourceLeases.Result resource;
                try {
                    resource=l.handle==null?claims.prepare(l.bound,l.correlation()):claims.validate(l.bound,l.correlation());
                    if(resource.usable()&&l.handle==null)resource=claims.validate(l.bound,l.correlation());
                }
                catch(RuntimeException unavailable){resource=new ResourceLeases.Result(ResourceLeases.Code.REJECTED,Reason.AUTHORITY_DENIED,null,List.of(),0,0);}
                if(!resource.usable()) {
                    if(resource.code()==ResourceLeases.Code.PENDING && resource.reason()==null)return;
                    stop(l,resource.reason()==null?Reason.TARGET_UNAVAILABLE:resource.reason(),false);
                    remember(decision(job,l.worker,Code.BLOCKED,l.resolution,l.failure),work);
                }else if(l.handle==null && work.starts<settings.starts()) {
                    // Current worker/control and lease state have just been rechecked after both acknowledgements.
                    if(!workerCurrent(l,true))stop(l,Reason.STALE_OBSERVATION,false);
                    else {
                        l.usage=new Budgets.Ledger(attempt.allowance(),clock);
                        Start started;
                        try { started=execution.start(l.bound,l.artifact,l.run,
                                new Budgets.ExecutionLimits(attempt.allowance(),settings.actionMillis()),l.usage); }
                        catch(RuntimeException failed){started=new Start(null,Reason.ACTION_FAILED);}
                        if(started.handle()==null)stop(l,started.rejected(),false);
                        else { l.handle=started.handle();l.phase=Phase.RUNNING;work.starts++;remember(decision(job,l.worker,Code.STARTED,l.resolution,null),work); }
                    }
                    return;
                }
            }
        }
        BoundedSkillExecutor.Progress progress=null;
        if(l.handle!=null) {
            progress=l.phase==Phase.STOPPING?(l.cancel?l.handle.cancel(l.origin):l.handle.interrupt(l.origin)):l.handle.tick(l.origin);
            if(!progress.summary().runId().equals(l.run) || !progress.summary().artifact().equals(l.artifact)
                    || !new HashSet<>(progress.summary().pinned()).equals(new HashSet<>(l.pinned))) {
                stop(l,Reason.STALE_OBSERVATION,false);return;
            }
            if(progress.summary().outcome()==null) {
                preserveProgress(l,job,attempt,progress.summary());
                return;
            }
            // Terminal execution must never reacquire claims while an asynchronous release is pending.
            l.phase=Phase.STOPPING;
            if(!l.handle.stopped(l.origin)){preserveProgress(l,job,attempt,progress.summary());return;}
        }else if(l.phase!=Phase.STOPPING)return;
        if(!l.released){claims.release(l.run);l.released=true;}
        // Release acknowledgements are part of confirmed disposition, never presumed from a notification.
        if(!claims.released(l.run)){claims.release(l.run);return;}
        if(!jobs.ready())return;
        if(progress!=null) {
            var summary=progress.summary();
            l.report=new Jobs.Report(l.run,l.generation,summary.committedEffects(),summary.usage(),summary.receipts(),summary.outcome());
        }else {
            ExecutionStatus status=l.cancel?ExecutionStatus.CANCELLED:
                    l.failure==Reason.ACTOR_UNAVAILABLE || l.failure==Reason.ACTION_TIMEOUT || l.failure==Reason.STALE_OBSERVATION
                            ?ExecutionStatus.INTERRUPTED:ExecutionStatus.BLOCKED;
            Reason reason=status==ExecutionStatus.INTERRUPTED?Reason.INTERRUPTED:status==ExecutionStatus.CANCELLED?Reason.CANCELLED:l.failure;
            l.report=new Jobs.Report(l.run,l.generation,0,Map.of(),List.of(),new Outcomes.Execution(status,reason,0,null));
        }
        var change=jobs.recordExecutionResult(job.id(),job.guard(),l.report,l.origin);
        if(change.accepted()){l.phase=Phase.PUBLISHING;remember(decision(job,l.worker,Code.OBSERVED,l.resolution,l.failure),work);}
    }
    private void preserveProgress(Live l,Jobs.Job job,Jobs.Attempt attempt,BoundedSkillExecutor.Summary summary) {
        if(jobs.ready()&&(summary.committedEffects()!=attempt.effects()||!summary.usage().equals(attempt.usage())
                ||!summary.receipts().equals(attempt.receipts())))
            jobs.recordExecutionResult(job.id(),job.guard(),new Jobs.Report(l.run,l.generation,summary.committedEffects(),
                    summary.usage(),summary.receipts(),null),l.origin);
    }
    private void stop(Live l,Reason reason,boolean cancel){l.failure=reason;l.cancel|=cancel;l.phase=Phase.STOPPING;}
    private Decision decision(Jobs.Job j,ActorRef a,Code code,CapabilityResolver.Decision d,Reason reason) {
        return new Decision(j.id(),a,code,d.routing(),reason,d.candidates(),"UUID circular order; artifact="+(d.routing().artifact()==null?"none":d.routing().artifact().sha256()));
    }
    private void remember(Decision d,Work work) {
        if(work.decisions.size()<settings.diagnostics())work.decisions.add(d);
        // The existing job quota bounds keys; replacement bounds histories and bytes by resolver evidence limits.
        if(!diagnostics.containsKey(d.job())&&diagnostics.size()==jobs.settings().total())return;
        diagnostics.put(d.job(),List.of(d));
    }
    private Slice slice(Work w){return new Slice(w.decisions,w.jobs,w.workers,w.resolutions,w.starts,w.retries);}
    /** Finite non-replenishing fixture: one-tick acknowledgements, one-tick known executions, no blockers. */
    public static int fairnessBound(int jobs,int workers,Settings settings) {
        if(jobs<1||jobs>64||workers<1||workers>64)throw new IllegalArgumentException("Fairness cohort");
        return Math.multiplyExact(jobs,Math.multiplyExact((workers+settings.workers()-1)/settings.workers()+1,
                8+2*((settings.live()+Math.max(1,settings.jobs()/2)-1)/Math.max(1,settings.jobs()/2))));
    }
    private void checkThread(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Dispatcher game thread");}
}
