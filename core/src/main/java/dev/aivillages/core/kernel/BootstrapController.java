package dev.aivillages.core.kernel;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;

/** One enrolled actor and one request. World writes and model callbacks never drive game effects. */
public final class BootstrapController {
    /** Maximum acknowledgement wait; expiry never assumes the storage worker stopped. */
    public static final long MAX_STORAGE_WAIT_MILLIS = 30_000;
    public interface Storage {
        CompletionStage<BootstrapJournal.State> replace(BootstrapJournal.State expected,
                BootstrapJournal.Enrollment enrollment, List<BootstrapJournal.RunMarker> runs);
    }
    /** Read-only identity client; the registry exclusively publishes enrollment and naming. */
    public interface Identity {
        BootstrapJournal.Enrollment find(UUID citizenId);
        BootstrapJournal.Enrollment primary();
        boolean ready();
    }
    @FunctionalInterface public interface Observer {
        ObservationSnapshot capture(CapabilityRequest request, ActorRef actor);
    }
    @FunctionalInterface public interface Resolver {
        CapabilityResolver.Decision resolve(ValidatedRequest bound, ObservationSnapshot snapshot);
    }
    public interface Research {
        Handle start(CapabilityResolver.Decision decision, ValidatedRequest bound,
                     Budgets.ResearchLimits limits);
        interface Handle {
            ResearchAdmissionController.Status tick(TrustedContext owner);
            ResearchAdmissionController.Status status(TrustedContext owner);
            ResearchAdmissionController.Status cancel(TrustedContext owner);
        }
    }
    public interface Execution {
        default List<ArtifactRef> pinned(ArtifactRef artifact) { return List.of(artifact); }
        Start start(ValidatedRequest bound, ArtifactRef artifact, UUID runId,
                    Budgets.ExecutionLimits limits, Budgets.Ledger usage);
        record Start(Handle handle, Reason rejected) {
            public Start {
                if ((handle == null) == (rejected == null)) throw new IllegalArgumentException();
            }
        }
        interface Handle {
            BoundedSkillExecutor.Progress tick(TrustedContext owner);
            BoundedSkillExecutor.Progress progress(TrustedContext owner);
            BoundedSkillExecutor.Progress cancel(TrustedContext owner);
            BoundedSkillExecutor.Progress interrupt(TrustedContext owner);
        }
    }
    @FunctionalInterface public interface ResearchBudget {
        Budgets.ResearchLimits issue(long nowMillis);
    }
    @FunctionalInterface public interface ExecutionBudget {
        Budgets.ExecutionLimits issue(long nowMillis);
    }
    public enum Phase { PERSISTING, ROUTING, RESEARCHING, EXECUTING, TERMINAL }
    public record Submission(UUID id, boolean accepted, Reason reason) { }
    public record View(UUID id, Phase phase, BootstrapJournal.RunMarker marker,
                       Resolution routing, Outcomes.Execution outcome, Outcomes.Research research,
                       List<CropDelivery.CropReceipt> receipts, Reason storageError) {
        public View { receipts = List.copyOf(receipts); }
    }
    private record Written(BootstrapJournal.State state, Throwable error, Runnable after,
                           long completedMillis, BootstrapJournal.State expectedNext) { }
    private final Storage storage;
    private final JobLifecycleStore jobs;
    private Runnable afterJob;
    private final Identity identities;
    private final Observer observer;
    private final Resolver resolver;
    private final Research research;
    private final Execution execution;
    private final RequestEnvironment environment;
    private final CapabilityCatalog capabilities;
    private final ResearchBudget researchBudget;
    private final ExecutionBudget executionBudget;
    private final Clock clock;
    private final Thread gameThread;
    private final ConcurrentLinkedQueue<Written> writes = new ConcurrentLinkedQueue<>();
    private BootstrapJournal.State persisted;
    private Run active;
    private boolean writing, ready, storageFailed, inferenceEnabled;
    private long writeDeadlineMillis;
    private BootstrapJournal.Enrollment enrolling;
    private final ArrayDeque<View> recent = new ArrayDeque<>();

    /** Trusted composition ownership check; dispatch must not steal this controller's submission. */
    public boolean ownsSubmission(UUID submission) {
        thread();
        return active!=null && active.id.equals(submission) || persisted.runs().stream().anyMatch(r->r.id().equals(submission));
    }

    public BootstrapController(BootstrapJournal.State initial, Storage storage, Observer observer,
            Resolver resolver, Research research, Execution execution,
            RequestEnvironment environment, CapabilityCatalog capabilities,
            ResearchBudget researchBudget, ExecutionBudget executionBudget, Clock clock) {
        this(initial, storage, observer, resolver, research, execution, environment, capabilities,
                researchBudget, executionBudget, clock, null);
    }

    /** Registry-backed mode keeps one active run while identities and prior runs remain independently scoped. */
    public BootstrapController(BootstrapJournal.State initial, Storage storage, Observer observer,
            Resolver resolver, Research research, Execution execution,
            RequestEnvironment environment, CapabilityCatalog capabilities,
            ResearchBudget researchBudget, ExecutionBudget executionBudget, Clock clock, Identity identities) {
        this(initial, storage, observer, resolver, research, execution, environment, capabilities,
                researchBudget, executionBudget, clock, identities, null);
    }

    /** Production job owner; fixture callers without this seam retain their prior attempt interface. */
    public BootstrapController(BootstrapJournal.State initial, Storage storage, Observer observer,
            Resolver resolver, Research research, Execution execution, RequestEnvironment environment,
            CapabilityCatalog capabilities, ResearchBudget researchBudget, ExecutionBudget executionBudget,
            Clock clock, Identity identities, JobLifecycleStore jobs) {
        this.jobs = jobs;
        persisted = Objects.requireNonNull(initial);
        if (initial.externalIdentities() != (identities != null))
            throw new IllegalArgumentException("Bootstrap identity owner mismatch");
        this.identities = identities;
        this.storage = Objects.requireNonNull(storage); this.observer = Objects.requireNonNull(observer);
        this.resolver = Objects.requireNonNull(resolver); this.research = Objects.requireNonNull(research);
        this.execution = Objects.requireNonNull(execution);
        this.environment = Objects.requireNonNull(environment);
        this.capabilities = Objects.requireNonNull(capabilities);
        this.researchBudget = Objects.requireNonNull(researchBudget);
        this.executionBudget = Objects.requireNonNull(executionBudget);
        this.clock = Objects.requireNonNull(clock);
        gameThread = Thread.currentThread();
        if (identities != null && initial.runs().stream().anyMatch(r -> identities.find(r.citizenId()) == null))
            throw new IllegalArgumentException("Unknown citizen run reference");
        for (var marker : initial.runs())
            recent.add(new View(marker.id(), marker.phase() == BootstrapJournal.Phase.ACTIVE
                    ? Phase.PERSISTING : Phase.TERMINAL, marker, null, null, null, List.of(), null));
        if (initial.runs().stream().anyMatch(r -> r.phase() == BootstrapJournal.Phase.ACTIVE)) {
            List<BootstrapJournal.RunMarker> interrupted = initial.runs().stream().map(r ->
                    r.phase() != BootstrapJournal.Phase.ACTIVE ? r
                            : new BootstrapJournal.RunMarker(r.id(), r.citizenId(),
                                    BootstrapJournal.Phase.INTERRUPTED, "INTERRUPTED",
                                    Reason.INTERRUPTED, r.effects(), r.modelCalls(),
                                    r.artifactSha256())).toList();
            persist(initial.enrollment(), interrupted, () -> {
                recent.clear();
                for (var marker : persisted.runs()) recent.add(new View(marker.id(),
                        Phase.TERMINAL, marker, null, null, null, List.of(), null));
                ready = true;
            });
        } else ready = true;
    }

    public boolean ready() {
        thread(); return ready && !writing && !storageFailed && afterJob == null
                && (jobs == null || jobs.ready()) && (identities == null || identities.ready());
    }
    public BootstrapJournal.Enrollment enrollment() {
        thread(); return identities == null ? persisted.enrollment() : identities.primary();
    }
    public long modelCalls() {
        thread();
        return persisted.runs().stream().mapToLong(BootstrapJournal.RunMarker::modelCalls).sum();
    }
    public long modelCalls(TrustedContext caller) {
        thread();
        return persisted.runs().stream().filter(r -> {
            var owner = enrollment(r.citizenId());
            return owner != null && owner.owner().equals(caller);
        }).mapToLong(BootstrapJournal.RunMarker::modelCalls).sum();
    }
    public boolean inferenceEnabled() { thread(); return inferenceEnabled; }

    /** Only a server-trusted operator should call this; disabling fences pending synthesis. */
    public void inference(boolean enabled) {
        thread(); inferenceEnabled = enabled;
        if (!enabled && active != null && active.research != null
                && active.phase == Phase.RESEARCHING) {
            captureResearch(active, active.research.cancel(active.owner));
        }
    }

    /** One UUID enrollment per world. A new citizen cannot replace the previous owner. */
    public ActorRef enroll(UUID entityId, String dimension, PrincipalRef principal) {
        thread(); Objects.requireNonNull(principal);
        if (identities != null) throw new IllegalStateException("Enrollment belongs to the citizen registry");
        if (!ready() || persisted.enrollment() != null || enrolling != null || active != null)
            throw new IllegalStateException("Bootstrap enrollment unavailable");
        ActorRef actor = new ActorRef(UUID.randomUUID(), entityId, dimension);
        enrolling = new BootstrapJournal.Enrollment(actor, new TrustedContext(principal,
                new ScopeRef(persisted.worldId(), principal.id())));
        ready = false;
        persist(enrolling, persisted.runs(), () -> { enrolling = null; ready = true; });
        return actor;
    }

    public Submission submit(CapabilityRequest request, TrustedContext caller) {
        thread(); UUID id = UUID.randomUUID();
        if (!ready() || active != null) return new Submission(id, false, Reason.BUDGET_EXHAUSTED);
        var enrolled = identities == null ? persisted.enrollment()
                : request != null && request.arguments().get("actor") instanceof ActorValue value
                        ? identities.find(value.value().citizenId()) : null;
        if (enrolled == null || !enrolled.owner().equals(caller))
            return new Submission(id, false, Reason.AUTHORITY_DENIED);
        if (request == null || !(request.arguments().get("actor") instanceof ActorValue actor)
                || !actor.value().equals(enrolled.actor()))
            return new Submission(id, false, Reason.REQUEST_INVALID);
        ObservationSnapshot snapshot;
        try { snapshot = observer.capture(request, actor.value()); }
        catch (RuntimeException failed) { return new Submission(id, false, Reason.STALE_OBSERVATION); }
        var validation = RequestValidator.validate(request, caller, snapshot.reference(),
                capabilities, environment);
        if (validation instanceof RequestValidator.Rejected rejected)
            return new Submission(id, false, rejected.reason());
        Run run = new Run(id, caller, request, ((RequestValidator.Accepted)validation).value());
        if (jobs != null) {
            try {
                long now = clock.millis();
                run.researchLimits = researchBudget.issue(now);
                run.executionLimits = executionBudget.issue(now);
                var maxima = new java.util.EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);
                maxima.putAll(run.researchLimits.total().maxima());
                run.executionLimits.total().maxima().forEach((kind, maximum) -> maxima.merge(kind, maximum, Math::max));
                var allowance = new Budgets.Limits(maxima, Math.min(run.researchLimits.total().deadlineEpochMillis(),
                        run.executionLimits.total().deadlineEpochMillis()));
                var created = jobs.create(run.jobId, id, request, caller, snapshot.reference(), allowance, List.of());
                if (!created.accepted()) return new Submission(id, false, created.reason());
            } catch (RuntimeException failed) { return new Submission(id, false, Reason.BUDGET_EXHAUSTED); }
        }
        active = run; run.phase = Phase.PERSISTING;
        Runnable begin = () -> persist(enrolled, withMarker(run.marker), () -> {
            if (active == null || !active.id.equals(id)) return;
            if (active.cancelPending) terminate(active, "CANCELLED", Reason.CANCELLED);
            else route(active);
        });
        if (jobs == null) begin.run(); else afterJob = begin;
        return new Submission(id, true, null);
    }

    private void route(Run run) {
        run.phase = Phase.ROUTING;
        ObservationSnapshot snapshot;
        try { snapshot = observer.capture(run.request, ((ActorValue)run.request.arguments().get("actor")).value()); }
        catch (RuntimeException failed) { terminate(run, "BLOCKED", Reason.STALE_OBSERVATION); return; }
        var validated = RequestValidator.validate(run.request, run.owner, snapshot.reference(),
                capabilities, environment);
        if (validated instanceof RequestValidator.Rejected rejected) {
            terminate(run, "BLOCKED", rejected.reason()); return;
        }
        run.bound = ((RequestValidator.Accepted)validated).value();
        try {
            CapabilityResolver.Decision decision = resolver.resolve(run.bound, snapshot);
            run.routing = decision.routing();
            switch (decision.routing().status()) {
                case RESOLVED -> startExecution(run, decision.routing().artifact());
                case MISSING_IMPLEMENTATION -> {
                    if (!inferenceEnabled) terminate(run, "BLOCKED", Reason.MODEL_UNAVAILABLE);
                    else {
                        Runnable begin = () -> {
                            if (run.cancelPending) { terminate(run, "CANCELLED", Reason.CANCELLED); return; }
                            run.research = research.start(decision, run.bound, jobs == null
                                    ? researchBudget.issue(clock.millis()) : run.researchLimits);
                            run.phase = Phase.RESEARCHING;
                        };
                        if (jobs == null) begin.run();
                        else assignJob(run, null, List.of(), run.researchLimits.total(), begin);
                    }
                }
                default -> terminate(run, decision.routing().status().name(),
                        decision.routing().reason());
            }
        } catch (RuntimeException failure) { terminate(run, "BLOCKED", Reason.ACTION_FAILED); }
    }

    private void assignJob(Run run, ArtifactRef artifact, List<ArtifactRef> pinned,
                           Budgets.Limits allowance, Runnable begin) {
        var job = jobs.query(run.jobId, run.owner);
        var change = jobs.assign(job.id(), job.guard(), run.id,
                ((ActorValue)run.bound.request().arguments().get("actor")).value(), artifact, pinned, allowance, run.owner);
        if (!change.accepted()) { terminate(run, "BLOCKED", change.reason()); return; }
        run.phase = Phase.PERSISTING; afterJob = begin;
    }
    /** Durable exact trial pin. Returning false defers trial start without issuing a grant. */
    public boolean prepareTrial(ValidatedRequest bound, RunCorrelation correlation, List<ArtifactRef> pinned) {
        thread();
        if (jobs == null) return true;
        if (storageFailed || active == null || !active.bound.equals(bound) || active.cancelPending)
            throw new IllegalStateException("Trial job unavailable");
        if (!jobs.ready()) return false;
        var job = jobs.query(active.jobId, active.owner);
        var reference = new Jobs.ExecutionReference(correlation.runId(), correlation.artifact(), pinned);
        var change = jobs.pin(job.id(), job.guard(), reference, active.owner);
        if (!change.accepted()) throw new IllegalStateException("Trial pin: " + change.reason());
        return change.code() == Jobs.Code.DUPLICATE;
    }

    private void startExecution(Run run, ArtifactRef ref) {
        run.artifact = ref;
        if (jobs != null && jobs.query(run.jobId, run.owner).current() == null) {
            assignJob(run, ref, execution.pinned(ref), run.executionLimits.total(), () -> startExecution(run, ref));
            return;
        }
        if (run.cancelPending) { terminate(run, "CANCELLED", Reason.CANCELLED); return; }
        try {
            Budgets.ExecutionLimits limits = jobs == null ? executionBudget.issue(clock.millis()) : run.executionLimits;
            Execution.Start result = execution.start(run.bound, ref, run.id, limits,
                    new Budgets.Ledger(limits.total(), clock));
            if (result.rejected() != null) terminate(run, "BLOCKED", result.rejected());
            else { run.execution = result.handle(); run.phase = Phase.EXECUTING; }
        } catch (RuntimeException failure) { terminate(run, "BLOCKED", Reason.ACTION_FAILED); }
    }

    /** At most one persistence event and one engine slice per server tick. */
    public void tick() {
        thread();
        if (jobs != null) {
            jobs.tick();
            if (jobs.unavailableReason() == Reason.STORAGE_UNAVAILABLE && !storageFailed) failStorage();
            if (!jobs.ready()) return;
            if (active != null && active.cancelRequested && !active.cancelPending && !active.finishing) {
                var job = jobs.query(active.jobId, active.owner);
                var cancelled = jobs.cancel(job.id(), job.guard(), active.owner);
                if (cancelled.accepted()) active.cancelPending = true;
                return;
            }
            if (afterJob != null && !storageFailed) {
                Runnable next = afterJob; afterJob = null; next.run(); return;
            }
        }
        if (storageFailed) {
            writes.clear(); // A late acknowledgement cannot revive a fenced controller.
            return;
        }
        Written written = writes.poll();
        if (written == null && writing && clock.millis() >= writeDeadlineMillis) {
            failStorage();
            return;
        }
        if (written != null) {
            writing = false;
            if (written.error() != null || written.state() == null
                    || written.completedMillis() > writeDeadlineMillis
                    || !written.state().equals(written.expectedNext())) {
                failStorage();
            } else {
                persisted = written.state();
                written.after().run();
            }
        }
        if (written != null) return; // A completion and an engine slice are separate ticks.
        if (active != null && !writing && active.phase == Phase.RESEARCHING)
            advanceResearch(active);
        else if (active != null && !writing && active.phase == Phase.EXECUTING)
            advanceExecution(active);
    }

    private void failStorage() {
        storageFailed = true; ready = false; writing = false;
        if (active == null) {
            List<View> snapshots = recent.stream().map(view -> view.phase() != Phase.PERSISTING
                    ? view : new View(view.id(), Phase.TERMINAL, view.marker(), view.routing(),
                            view.outcome(), view.research(), view.receipts(), Reason.STORAGE_UNAVAILABLE))
                    .toList();
            recent.clear(); recent.addAll(snapshots);
            return;
        }
        active.storageError = Reason.STORAGE_UNAVAILABLE;
        if (active.phase == Phase.EXECUTING) {
            var progress = active.execution.interrupt(active.owner);
            active.effects = progress.summary().committedEffects();
            active.receipts = progress.summary().receipts();
            active.executionOutcome = progress.summary().outcome();
        }
        if (active.phase == Phase.RESEARCHING) {
            var status = active.research.cancel(active.owner);
            active.effects = status.committedEffects();
            active.receipts = status.receipts();
            active.researchOutcome = status.outcome();
        }
        active.phase = Phase.TERMINAL;
    }

    private void advanceResearch(Run run) {
        captureResearch(run, run.research.tick(run.owner));
    }
    private void captureResearch(Run run, ResearchAdmissionController.Status status) {
        run.effects = status.committedEffects(); run.receipts = status.receipts(); run.usage = status.usage();
        if (status.phase() != ResearchAdmissionController.Phase.TERMINAL) return;
        run.researchOutcome = status.outcome();
        run.calls = status.outcome().modelCalls();
        run.artifact = status.outcome().artifact();
        terminate(run, status.outcome().status().name(), status.outcome().reason());
    }
    private void advanceExecution(Run run) {
        BoundedSkillExecutor.Progress progress = run.execution.tick(run.owner);
        run.effects = progress.summary().committedEffects();
        run.receipts = progress.summary().receipts();
        run.usage = progress.summary().usage();
        if (progress.phase() != BoundedSkillExecutor.Phase.TERMINAL) return;
        run.executionOutcome = progress.summary().outcome();
        terminate(run, run.executionOutcome.status().name(), run.executionOutcome.reason());
    }
    private void terminate(Run run, String result, Reason reason) {
        if (run.phase == Phase.TERMINAL) return;
        run.finishing = true;
        if (jobs != null && !run.jobFinished) {
            var job = jobs.query(run.jobId, run.owner);
            Jobs.Change changed;
            if (job.state().terminal()) { run.jobFinished = true; }
            else if (job.current() == null) {
                changed = reason == Reason.CANCELLED ? jobs.cancel(job.id(), job.guard(), run.owner)
                        : jobs.transition(job.id(), job.guard(), Jobs.State.FAILED,
                                reason == null ? Reason.ACTION_FAILED : reason, run.owner);
                if (!changed.accepted()) { failStorage(); return; }
                run.phase = Phase.PERSISTING;
                afterJob = () -> { run.jobFinished = true; terminate(run, result, reason); };
                return;
            } else {
                Outcomes.Execution outcome = run.executionOutcome;
                if (outcome == null) {
                    ExecutionStatus status = reason == Reason.CANCELLED ? ExecutionStatus.CANCELLED
                            : run.researchOutcome != null && run.researchOutcome.status() == ResearchStatus.ADMITTED
                            ? ExecutionStatus.SUCCEEDED : ExecutionStatus.BLOCKED;
                    outcome = new Outcomes.Execution(status, status == ExecutionStatus.SUCCEEDED ? null
                            : reason == null ? Reason.ACTION_FAILED : reason, run.effects,
                            status == ExecutionStatus.SUCCEEDED ? new EvidenceRef(run.artifact.sha256(),
                                    "jobs:1", "attributable-crop-delivery") : null);
                }
                changed = jobs.recordExecutionResult(job.id(), job.guard(), new Jobs.Report(job.current().id(),
                        job.generation(), run.effects, run.usage, run.receipts, outcome), run.owner);
                if (!changed.accepted()) { failStorage(); return; }
                run.phase = Phase.PERSISTING;
                afterJob = () -> { run.jobFinished = true; terminate(run, result, reason); };
                return;
            }
        }
        run.phase = Phase.PERSISTING;
        BootstrapJournal.RunMarker marker = new BootstrapJournal.RunMarker(run.id,
                run.marker.citizenId(), BootstrapJournal.Phase.TERMINAL,
                result, reason, run.effects, run.calls,
                run.artifact == null ? null : run.artifact.sha256());
        persist(persisted.enrollment(), withMarker(marker), () -> {
            run.marker = marker; run.phase = Phase.TERMINAL;
            remember(view(run)); active = null;
        });
    }
    private void remember(View view) {
        if (recent.size() == BootstrapJournal.MAX_RUNS) recent.removeFirst();
        recent.addLast(view);
    }
    private List<BootstrapJournal.RunMarker> withMarker(BootstrapJournal.RunMarker marker) {
        List<BootstrapJournal.RunMarker> entries = new ArrayList<>(persisted.runs());
        entries.removeIf(value -> value.id().equals(marker.id()));
        if (entries.size() == BootstrapJournal.MAX_RUNS) entries.removeFirst();
        entries.add(marker);
        return List.copyOf(entries);
    }
    private void persist(BootstrapJournal.Enrollment enrollment,
                         List<BootstrapJournal.RunMarker> entries, Runnable after) {
        if (writing || storageFailed) throw new IllegalStateException("Bootstrap write occupied");
        writing = true;
        try {
            writeDeadlineMillis = Math.addExact(clock.millis(), MAX_STORAGE_WAIT_MILLIS);
            var target = new BootstrapJournal.State(persisted.worldId(),
                    Math.addExact(persisted.revision(), 1), persisted.externalIdentities() ? null : enrollment,
                    entries, persisted.externalIdentities());
            storage.replace(persisted, persisted.externalIdentities() ? null : enrollment, entries).whenComplete((state, failure) ->
                    writes.add(new Written(state, failure, after, clock.millis(), target)));
        } catch (RuntimeException failed) {
            writes.add(new Written(null, failed, after, clock.millis(), null));
        }
    }

    /** Retained/current identifiers using the same exact owner rule as status(). */
    public List<UUID> runIds(TrustedContext caller) {
        thread(); Objects.requireNonNull(caller);
        var ids = new ArrayList<UUID>();
        for (View retained : recent)
            if (owned(retained.marker().citizenId(), caller)) ids.add(retained.id());
        if (active != null && owned(active.marker.citizenId(), caller)
                && !ids.contains(active.id)) ids.add(active.id);
        return List.copyOf(ids);
    }

    public List<UUID> cancellableRunIds(TrustedContext caller) {
        thread(); Objects.requireNonNull(caller);
        return active != null && active.phase != Phase.TERMINAL && !active.cancelPending && !active.cancelRequested && !active.finishing
                && owned(active.marker.citizenId(), caller) ? List.of(active.id) : List.of();
    }

    private boolean owned(UUID citizenId, TrustedContext caller) {
        var enrolled = enrollment(citizenId);
        return enrolled != null && enrolled.owner().equals(caller);
    }

    public View status(UUID id, TrustedContext caller) {
        thread();
        if (identities == null) owner(caller);
        View result = active != null && active.id.equals(id) ? view(active)
                : recent.stream().filter(v -> v.id().equals(id)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Unknown run"));
        var enrolled = enrollment(result.marker().citizenId());
        if (enrolled == null || !enrolled.owner().equals(caller))
            throw new SecurityException("Bootstrap owner required");
        return result;
    }
    public View cancel(UUID id, TrustedContext caller) {
        thread(); View existing = status(id, caller);
        if (active == null || !active.id.equals(id)) return existing;
        Run run = active;
        if (jobs != null) {
            if (!run.finishing) run.cancelRequested = true;
            return view(run);
        }
        return observeCancellation(run, caller);
    }
    /** Called only after the job owner's cancellation intent/delivery commits. */
    public void observeJobCancellation(Jobs.Cancellation cancellation) {
        thread();
        if (active == null || active.finishing || !active.jobId.equals(cancellation.jobId())) return;
        var job = jobs.query(cancellation.jobId(), cancellation.origin());
        if (job.current() == null || !job.current().id().equals(cancellation.attemptId())
                || job.generation() != cancellation.generation()) return;
        active.cancelPending = true;
        observeCancellation(active, active.owner);
    }
    private View observeCancellation(Run run, TrustedContext caller) {
        if (run.phase == Phase.PERSISTING || run.phase == Phase.ROUTING) run.cancelPending = true;
        else if (run.phase == Phase.RESEARCHING) {
            captureResearch(run, run.research.cancel(caller));
        } else if (run.phase == Phase.EXECUTING) {
            var progress = run.execution.cancel(caller);
            run.effects = progress.summary().committedEffects();
            run.receipts = progress.summary().receipts();
            run.usage = progress.summary().usage();
            run.executionOutcome = progress.summary().outcome();
            terminate(run, "CANCELLED", Reason.CANCELLED);
        }
        return view(run);
    }
    private void owner(TrustedContext caller) {
        if (persisted.enrollment() == null || !persisted.enrollment().owner().equals(caller))
            throw new SecurityException("Bootstrap owner required");
    }
    private BootstrapJournal.Enrollment enrollment(UUID citizenId) {
        if (identities != null) return identities.find(citizenId);
        return persisted.enrollment() != null
                && persisted.enrollment().actor().citizenId().equals(citizenId)
                ? persisted.enrollment() : null;
    }
    private View view(Run run) {
        return new View(run.id, run.phase, run.marker, run.routing,
                run.executionOutcome, run.researchOutcome, run.receipts, run.storageError);
    }
    private void thread() {
        if (Thread.currentThread() != gameThread) throw new IllegalStateException("Server thread required");
    }
    private final class Run {
        final UUID jobId = UUID.randomUUID();
        final UUID id; final TrustedContext owner; final CapabilityRequest request;
        ValidatedRequest bound;
        BootstrapJournal.RunMarker marker;
        Phase phase;
        Resolution routing;
        Outcomes.Execution executionOutcome;
        Outcomes.Research researchOutcome;
        Research.Handle research;
        Execution.Handle execution;
        ArtifactRef artifact;
        Budgets.ResearchLimits researchLimits;
        Budgets.ExecutionLimits executionLimits;
        java.util.Map<Budgets.Kind,Long> usage = java.util.Map.of();
        long effects, calls;
        List<CropDelivery.CropReceipt> receipts = List.of();
        Reason storageError;
        boolean cancelPending, finishing, cancelRequested, jobFinished;
        Run(UUID id, TrustedContext owner, CapabilityRequest request, ValidatedRequest bound) {
            this.id = id; this.owner = owner; this.request = request; this.bound = bound;
            this.marker = new BootstrapJournal.RunMarker(id,
                    ((ActorValue)request.arguments().get("actor")).value().citizenId(), BootstrapJournal.Phase.ACTIVE,
                    null, null, 0, 0, null);
        }
    }
}
