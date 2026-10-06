package dev.aivillages.core.kernel;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Jobs.*;
import static dev.aivillages.core.kernel.Outcomes.*;

/**
 * Sole game-thread job owner. One immutable publication/acknowledgement is in
 * flight. Storage and cancellation ports must be asynchronous/nonblocking.
 * No method executes, dispatches, observes inventory, or calls a model.
 */
public final class JobLifecycleStore {
    @FunctionalInterface public interface Storage {
        CompletionStage<Snapshot> replace(Snapshot expected, Snapshot next);
    }
    public interface Policy {
        boolean mayRead(TrustedContext caller, Job job);
        boolean mayControl(TrustedContext caller, Job job);
        boolean controls(ActorRef actor, TrustedContext origin);
    }
    @FunctionalInterface public interface CancellationPort { void request(Cancellation cancellation); }
    public static Policy privateJobs(java.util.function.BiPredicate<ActorRef, TrustedContext> control) {
        Objects.requireNonNull(control);
        return new Policy() {
            public boolean mayRead(TrustedContext caller, Job job) { return job.origin().equals(caller); }
            public boolean mayControl(TrustedContext caller, Job job) { return job.origin().equals(caller); }
            public boolean controls(ActorRef actor, TrustedContext origin) { return control.test(actor, origin); }
        };
    }
    private record Written(Snapshot snapshot, Throwable error, long completedAt) { }
    private static final class Limited extends RuntimeException {
        final Reason reason;
        Limited(Reason reason) { this.reason = reason; }
    }
    private final class Work {
        int edges;
        void edge() { if (edges == settings.edges()) throw new Limited(Reason.BUDGET_EXHAUSTED); edges++; }
    }
    private final Storage storage;
    private final Policy policy;
    private final CancellationPort cancellations;
    private final CapabilityCatalog capabilities;
    private final RequestEnvironment environment;
    private final Clock clock;
    private final Settings settings;
    private final Thread gameThread = Thread.currentThread();
    private final ConcurrentLinkedQueue<Written> writes = new ConcurrentLinkedQueue<>();
    private Snapshot state, pending;
    private Runnable afterWrite;
    private long deadline;
    private boolean fenced, readOnly;
    private Reason failure;

    public JobLifecycleStore(Snapshot initial, Storage storage, Policy policy,
                             CancellationPort cancellations, CapabilityCatalog capabilities,
                             RequestEnvironment environment, Clock clock, Settings settings,
                             boolean readOnly) {
        this.state = Objects.requireNonNull(initial); this.storage = Objects.requireNonNull(storage);
        this.policy = Objects.requireNonNull(policy); this.cancellations = Objects.requireNonNull(cancellations);
        this.capabilities = Objects.requireNonNull(capabilities); this.environment = Objects.requireNonNull(environment);
        this.clock = Objects.requireNonNull(clock); this.settings = Objects.requireNonNull(settings);
        this.readOnly = readOnly;
        validateSnapshot(initial, settings);
        if (!readOnly) {
            var rows = rows();
            boolean changed = false;
            for (Job job : initial.jobs()) if (job.state() == State.ACTIVE || job.state() == State.CANCELLING
                    || job.current() != null && job.current().open() && job.state() != State.INTERRUPTED) {
                var edit = new Edit(job);
                edit.state = State.INTERRUPTED; edit.reason = Reason.INTERRUPTED;
                var attempts = new ArrayList<>(job.attempts());
                if (!attempts.isEmpty()) {
                    Attempt a = attempts.getLast();
                    attempts.set(attempts.size() - 1, attempt(a, a.executions(), a.usage(), a.receipts(),
                            a.effects(), a.credited(), a.terminal(), true, a.cancellationDeliveries()));
                }
                edit.attempts = attempts;
                rows.put(job.id(), edit.finish(EventKind.RESTORED, settings.events())); changed = true;
            }
            if (changed) publish(rows, state.allocations(), null, null, 0);
        }
    }
    public boolean ready() { thread(); return !readOnly && !fenced && pending == null; }
    public Reason unavailableReason() {
        thread(); return readOnly || fenced ? Reason.STORAGE_UNAVAILABLE
                : pending != null ? Reason.BUDGET_EXHAUSTED : null;
    }
    public Reason lastFailure() { thread(); return failure; }
    /** Trusted composition/retention seam, not an unscoped player query. */
    public Snapshot snapshot() { thread(); return state; }
    public Settings settings() { return settings; }

    public Job query(UUID id, TrustedContext caller) {
        thread(); Job job = find(id);
        if (job == null || !sameWorld(caller) || !policy.mayRead(caller, job))
            throw new SecurityException("Job unavailable in your scope");
        return job;
    }
    public Optional<Job> bySubmission(UUID submission, TrustedContext caller) {
        thread();
        return state.jobs().stream().filter(j -> j.submissionId().equals(submission)
                && sameWorld(caller) && policy.mayRead(caller, j)).findFirst();
    }
    public ReadyPage readyJobs(TrustedContext caller, int offset) {
        thread();
        if (!sameWorld(caller) || offset < 0) throw new SecurityException("Job query unavailable");
        // The cursor counts authorized rows only; it never exposes a foreign job ID.
        List<Job> visible = state.jobs().stream().filter(j -> policy.mayRead(caller, j)).toList();
        if (offset > visible.size()) throw new IllegalArgumentException("Ready cursor");
        List<Job> result = new ArrayList<>();
        int at = offset;
        if (!ready()) return new ReadyPage(result, at, at < visible.size(), 0);
        for (; at < visible.size(); at++) {
            Job job = visible.get(at);
            if (job.state() == State.READY && clock.millis() <= job.allowance().deadlineEpochMillis()) {
                if (result.size() == settings.ready()) break;
                result.add(job);
            }
        }
        return new ReadyPage(result, at, at < visible.size(), 0);
    }
    public Change create(UUID id, UUID submission, CapabilityRequest request, TrustedContext caller,
                         ObservationRef observation, Budgets.Limits allowance, List<UUID> dependencies) {
        thread(); var work = new Work();
        if (!sameWorld(caller)) return reject(Reason.AUTHORITY_DENIED, work);
        if (!ready()) return reject(unavailableReason(), work);
        try {
            if (id == null || submission == null || find(id) != null
                    || state.jobs().stream().anyMatch(j -> j.submissionId().equals(submission)))
                return reject(Reason.REQUEST_INVALID, work);
            RequestValidator.Result validation = RequestValidator.validate(request, caller, observation,
                    capabilities, environment);
            if (validation instanceof RequestValidator.Rejected rejected) return reject(rejected.reason(), work);
            if (allowance == null || clock.millis() > allowance.deadlineEpochMillis())
                return reject(Reason.BUDGET_EXHAUSTED, work);
            Job job = fresh(id, submission, ((RequestValidator.Accepted) validation).value(),
                    null, allowance, dependencies);
            var rows = rows(); rows.put(id, job);
            checkCapacity(rows);
            checkGraph(job.id(), rows, work);
            job = dependencyState(job, rows, false);
            rows.put(id, job);
            var failed = new HashSet<UUID>();
            for (UUID dependency : dependencies) if (rows.get(dependency).state() == State.CANCELLED
                    || rows.get(dependency).state() == State.FAILED) failed.add(dependency);
            propagate(rows, failed, work);
            return publish(rows, state.allocations(), rows.get(id), null, work.edges);
        } catch (Limited limited) { return reject(limited.reason, work); }
        catch (IllegalArgumentException | NullPointerException | ArithmeticException invalid) {
            return reject(Reason.REQUEST_INVALID, work);
        }
    }
    public Change transition(UUID id, Guard guard, State target, Reason reason, TrustedContext caller) {
        thread(); var work = new Work(); Job job = controlled(id, caller);
        if (job == null) return reject(Reason.AUTHORITY_DENIED, work);
        Change denied = guard(job, guard, work); if (denied != null) return denied;
        if (!Jobs.legal(job.state(), target) || job.current() != null && job.current().open())
            return reject(Reason.REQUEST_INVALID, work);
        // Completion, assignment and observed termination require their dedicated operations.
        if (target != State.WAITING && target != State.READY && target != State.FAILED && target != State.INTERRUPTED)
            return reject(Reason.REQUEST_INVALID, work);
        try {
            if (target == State.READY && (!dependenciesSucceeded(job, rows(), work)
                    || clock.millis() > job.allowance().deadlineEpochMillis()))
                return reject(Reason.BUDGET_EXHAUSTED, work);
            if (target == State.FAILED && reason == null || target == State.INTERRUPTED && reason != Reason.INTERRUPTED)
                return reject(Reason.REQUEST_INVALID, work);
            var edit = new Edit(job); edit.state = target; edit.reason = target == State.READY ? null : reason;
            Job changed = edit.finish(EventKind.TRANSITIONED, settings.events());
            var rows = rows(); rows.put(id, changed); propagate(rows, Set.of(id), work);
            return publish(rows, state.allocations(), rows.get(id), null, work.edges);
        } catch (Limited limited) { return reject(limited.reason, work); }
        catch (ArithmeticException overflow) { return reject(Reason.BUDGET_EXHAUSTED, work); }
    }
    public Change addDependency(UUID id, Guard guard, UUID dependency, TrustedContext caller) {
        thread(); var work = new Work(); Job job = controlled(id, caller);
        if (job == null) return reject(Reason.AUTHORITY_DENIED, work);
        Change denied = guard(job, guard, work); if (denied != null) return denied;
        if (job.state() != State.READY && job.state() != State.WAITING)
            return reject(Reason.REQUEST_INVALID, work);
        try {
            Job needed = find(dependency);
            if (needed == null || !needed.origin().equals(job.origin())) return reject(Reason.AUTHORITY_DENIED, work);
            if (job.dependencies().contains(dependency)) return duplicate(job, work);
            if (job.dependencies().size() >= settings.children()) return reject(Reason.STORAGE_LIMIT_REACHED, work);
            var edit = new Edit(job); var deps = new ArrayList<>(job.dependencies()); deps.add(dependency);
            edit.dependencies = deps;
            Job changed = edit.finish(EventKind.DEPENDENCY, settings.events());
            var rows = rows(); rows.put(id, changed);
            checkGraph(id, rows, work); changed = dependencyState(changed, rows, false); rows.put(id, changed);
            propagate(rows, Set.of(dependency), work);
            return publish(rows, state.allocations(), rows.get(id), null, work.edges);
        } catch (Limited limited) { return reject(limited.reason, work); }
        catch (IllegalArgumentException | ArithmeticException invalid) { return reject(Reason.REQUEST_INVALID, work); }
    }
    public Change addChildren(UUID id, Guard guard, List<Child> children, TrustedContext caller) {
        thread(); var work = new Work(); Job parent = controlled(id, caller);
        if (parent == null) return reject(Reason.AUTHORITY_DENIED, work);
        Change denied = guard(parent, guard, work); if (denied != null) return denied;
        if (parent.state() != State.READY && parent.state() != State.WAITING)
            return reject(Reason.REQUEST_INVALID, work);
        try {
            if (children == null || children.isEmpty() || parent.children().size() + children.size() > settings.children()
                    || parent.dependencies().size() + children.size() > settings.children())
                return reject(Reason.STORAGE_LIMIT_REACHED, work);
            var rows = rows(); var ids = new ArrayList<>(parent.children()); var deps = new ArrayList<>(parent.dependencies());
            var admitted = new ArrayList<Job>();
            for (Child child : children) {
                if (child.id() == null || rows.containsKey(child.id())) return reject(Reason.REQUEST_INVALID, work);
                var validation = RequestValidator.validate(child.request(), parent.origin(), child.observation(),
                        capabilities, environment);
                if (validation instanceof RequestValidator.Rejected rejected) return reject(rejected.reason(), work);
                envelope(child.allowance(), parent.allowance());
                for (Budgets.Kind kind : Budgets.Kind.values())
                    if (child.allowance().maximum(kind) > available(parent.id(), kind, rows))
                        throw new Limited(Reason.BUDGET_EXHAUSTED);
                Job job = fresh(child.id(), child.id(), ((RequestValidator.Accepted)validation).value(),
                        parent.id(), child.allowance(), child.dependencies());
                rows.put(job.id(), job); admitted.add(job); ids.add(job.id()); deps.add(job.id());
            }
            var edit = new Edit(parent); edit.children = ids; edit.dependencies = deps;
            edit.state = State.WAITING; edit.reason = null;
            Job changed = edit.finish(EventKind.DEPENDENCY, settings.events()); rows.put(id, changed);
            checkCapacity(rows); checkGraph(id, rows, work);
            for (Job child : admitted) rows.put(child.id(), dependencyState(child, rows, false));
            return publish(rows, state.allocations(), changed, null, work.edges);
        } catch (Limited limited) { return reject(limited.reason, work); }
        catch (IllegalArgumentException | NullPointerException | ArithmeticException invalid) {
            return reject(Reason.REQUEST_INVALID, work);
        }
    }
    /** Explicit worker input; null artifact reserves preparation, not executable authority. */
    public Change assign(UUID id, Guard guard, UUID attemptId, ActorRef worker, ArtifactRef artifact,
                         List<ArtifactRef> pinned, Budgets.Limits allowance, TrustedContext caller) {
        thread(); var work = new Work(); Job job = controlled(id, caller);
        if (job == null) return reject(Reason.AUTHORITY_DENIED, work);
        Change denied = guard(job, guard, work); if (denied != null) return denied;
        try {
            if ((job.state() != State.READY && !(job.state() == State.WAITING && artifact == null))
                    || job.current() != null && job.current().open()
                    || !dependenciesSucceeded(job, rows(), work))
                return reject(Reason.REQUEST_INVALID, work);
            if (!policy.controls(worker, job.origin())) return reject(Reason.AUTHORITY_DENIED, work);
            if (job.attempts().size() == settings.attempts()) return reject(Reason.STORAGE_LIMIT_REACHED, work);
            for (Job other : state.jobs()) for (Attempt a : other.attempts())
                if (a.id().equals(attemptId) || a.open() && a.worker().entityId().equals(worker.entityId()))
                    return reject(Reason.ACTOR_UNAVAILABLE, work);
            envelope(allowance, job.allowance());
            if (clock.millis() > allowance.deadlineEpochMillis()) return reject(Reason.BUDGET_EXHAUSTED, work);
            for (Job ancestor : ancestors(job.id(), rows()))
                for (Budgets.Kind kind : Budgets.Kind.values())
                    if (allowance.maximum(kind) > available(ancestor.id(), kind, rows()))
                        return reject(Reason.BUDGET_EXHAUSTED, work);
            ValidatedRequest bound = remainingRequest(job, worker);
            var references = artifact == null ? List.<ExecutionReference>of()
                    : List.of(new ExecutionReference(attemptId, artifact, pinned));
            long generation = Math.addExact(job.generation(), 1);
            var attempt = new Attempt(attemptId, generation, worker, bound, allowance, references,
                    Map.of(), List.of(), 0, 0, null, false, 0);
            var edit = new Edit(job); edit.generation = generation; edit.state = State.ACTIVE; edit.reason = null;
            var attempts = new ArrayList<>(job.attempts()); attempts.add(attempt); edit.attempts = attempts;
            Job changed = edit.finish(EventKind.ASSIGNED, settings.events()); var rows = rows(); rows.put(id, changed);
            return publish(rows, state.allocations(), changed, null, work.edges);
        } catch (Limited limited) { return reject(limited.reason, work); }
        catch (IllegalArgumentException | NullPointerException | ArithmeticException invalid) {
            return reject(Reason.BUDGET_EXHAUSTED, work);
        }
    }
    public Change pin(UUID id, Guard guard, ExecutionReference reference, TrustedContext caller) {
        thread(); var work = new Work(); Job job = controlled(id, caller);
        if (job == null) return reject(Reason.AUTHORITY_DENIED, work);
        Change denied = guard(job, guard, work); if (denied != null) return denied;
        Attempt a = job.current();
        if (job.state() != State.ACTIVE || a == null || !a.open()) return reject(Reason.REQUEST_INVALID, work);
        if (!reference.artifact().capability().equals(job.request().request().capability()))
            return reject(Reason.REQUEST_INVALID, work);
        var same = a.executions().stream().filter(r -> r.runId().equals(reference.runId())).findFirst();
        if (same.isPresent()) return same.get().equals(reference) ? duplicate(job, work)
                : reject(Reason.REQUEST_INVALID, work);
        if (a.executions().size() == 8) return reject(Reason.STORAGE_LIMIT_REACHED, work);
        var refs = new ArrayList<>(a.executions()); refs.add(reference);
        Attempt next = attempt(a, refs, a.usage(), a.receipts(), a.effects(), a.credited(),
                a.terminal(), a.uncertain(), a.cancellationDeliveries());
        return replaceAttempt(job, next, EventKind.PINNED, state.allocations(), work, null);
    }
    /** Non-execution preparation work is still charged to every original parent. */
    public Change charge(UUID id, Guard guard, Map<Budgets.Kind, Long> delta, TrustedContext caller) {
        thread(); var work = new Work(); Job job = controlled(id, caller);
        if (job == null) return reject(Reason.AUTHORITY_DENIED, work);
        Change denied = guard(job, guard, work); if (denied != null) return denied;
        try {
            delta = Jobs.usage(delta); var rows = rows();
            for (Job ancestor : ancestors(id, rows))
                for (var entry : delta.entrySet())
                    if (clock.millis() > ancestor.allowance().deadlineEpochMillis()
                            || entry.getValue() > available(ancestor.id(), entry.getKey(), rows))
                        return reject(Reason.BUDGET_EXHAUSTED, work);
            if (delta.isEmpty()) return duplicate(job, work);
            debit(rows, id, delta, EventKind.OBSERVED);
            return publish(rows, state.allocations(), rows.get(id), null, work.edges);
        } catch (IllegalArgumentException | ArithmeticException invalid) { return reject(Reason.BUDGET_EXHAUSTED, work); }
    }
    public Change recordExecutionResult(UUID id, Guard guard, Report report, TrustedContext caller) {
        return observe(id, guard, report, caller, false, false);
    }
    /** Certain owner observations are required; inventory totals cannot supply this report. */
    public Change reconcile(UUID id, Guard guard, Report report, boolean completeStoppedObservation,
                            TrustedContext caller) {
        return observe(id, guard, report, caller, true, completeStoppedObservation);
    }
    private Change observe(UUID id, Guard guard, Report report, TrustedContext caller,
                           boolean reconciliation, boolean certain) {
        thread(); var work = new Work(); Job job = controlled(id, caller);
        if (job == null) return reject(Reason.AUTHORITY_DENIED, work);
        if (!ready()) return reject(unavailableReason(), work);
        try {
            Attempt old = job.attempts().stream().filter(a -> a.id().equals(report.attemptId())
                    && a.generation() == report.generation()).findFirst().orElse(null);
            if (old == null) return reject(Reason.STALE_OBSERVATION, work);
            boolean current = old.generation() == job.generation();
            if (reconciliation && (!current || job.state() != State.INTERRUPTED || !certain || report.terminal() == null))
                return reject(Reason.STALE_OBSERVATION, work);
            Map<UUID, CropDelivery.CropReceipt> receipts = new LinkedHashMap<>();
            old.receipts().forEach(r -> receipts.put(r.receiptId(), r));
            for (var receipt : report.receipts()) {
                validateReceipt(old, receipt);
                var previous = receipts.putIfAbsent(receipt.receiptId(), receipt);
                if (previous != null && !previous.equals(receipt)) return reject(Reason.REQUEST_INVALID, work);
                for (Job other : state.jobs()) for (Attempt otherAttempt : other.attempts())
                    if (!otherAttempt.id().equals(old.id()) && otherAttempt.receipts().stream()
                            .anyMatch(r -> r.receiptId().equals(receipt.receiptId())))
                        return reject(Reason.REQUEST_INVALID, work);
            }
            if (receipts.size() + job.attempts().stream().filter(a -> !a.id().equals(old.id()))
                    .mapToInt(a -> a.receipts().size()).sum() > settings.receipts())
                return reject(Reason.STORAGE_LIMIT_REACHED, work);
            var usage = new EnumMap<Budgets.Kind, Long>(Budgets.Kind.class); usage.putAll(old.usage());
            for (var entry : report.usage().entrySet()) {
                if (entry.getValue() > old.allowance().maximum(entry.getKey()))
                    return reject(Reason.BUDGET_EXHAUSTED, work);
                usage.merge(entry.getKey(), entry.getValue(), Math::max);
            }
            long effects = Math.max(old.effects(), report.effects());
            var delta = new EnumMap<Budgets.Kind, Long>(Budgets.Kind.class);
            for (var entry : usage.entrySet()) {
                long amount = entry.getValue() - old.usage().getOrDefault(entry.getKey(), 0L);
                if (amount != 0) delta.put(entry.getKey(), amount);
            }
            boolean obsolete = !current || old.terminal() != null && !reconciliation;
            List<CropDelivery.CropReceipt> merged = List.copyOf(receipts.values());
            long credit = obsolete ? old.credited() : Math.min(quantity(old, merged),
                    ((IntValue)old.bound().request().arguments().get("amount")).value());
            Execution terminal = obsolete ? old.terminal() : report.terminal();
            if (!obsolete && terminal != null && terminal.status() == ExecutionStatus.SUCCEEDED
                    && old.executions().stream().noneMatch(r ->
                    CropDelivery.completed(old.bound(), r.runId(), merged)))
                return reject(Reason.ACTION_FAILED, work);
            boolean uncertain = reconciliation ? false : old.uncertain()
                    || !obsolete && terminal != null && terminal.status() == ExecutionStatus.INTERRUPTED;
            Attempt updated = attempt(old, old.executions(), usage, merged, effects, credit,
                    terminal, uncertain, old.cancellationDeliveries());
            if (updated.equals(old)) return duplicate(job, work);
            if (current) {
                Change denied = guard(job, guard, work); if (denied != null) return denied;
            } // Historical owner evidence is fenced by the immutable attempt/generation, not current work.
            var rows = rows();
            for (Job ancestor : ancestors(id, rows))
                for (var entry : delta.entrySet())
                    if (Math.addExact(ancestor.usage().getOrDefault(entry.getKey(), 0L), entry.getValue())
                            > ancestor.allowance().maximum(entry.getKey()))
                        return reject(Reason.BUDGET_EXHAUSTED, work);
            var edit = new Edit(job);
            edit.attempts = job.attempts().stream().map(a -> a.id().equals(old.id()) ? updated : a).toList();
            if ((!obsolete && terminal != null && job.state() != State.INTERRUPTED) || reconciliation) {
                if (!reconciliation && terminal.status() == ExecutionStatus.INTERRUPTED) {
                    edit.state = State.INTERRUPTED; edit.reason = Reason.INTERRUPTED;
                } else if (job.cancellationOutcome() != null || job.state() == State.CANCELLING) {
                    edit.state = job.cancellationOutcome() == State.FAILED ? State.FAILED : State.CANCELLED;
                    edit.reason = edit.state == State.CANCELLED ? Reason.CANCELLED : Reason.ACTION_FAILED;
                } else switch (terminal.status()) {
                    case SUCCEEDED -> { edit.state = State.SUCCEEDED; edit.reason = null; }
                    case BLOCKED -> { edit.state = State.WAITING; edit.reason = terminal.reason(); }
                    case FAILED -> { edit.state = State.FAILED; edit.reason = terminal.reason(); }
                    case CANCELLED -> { edit.state = State.CANCELLED; edit.reason = Reason.CANCELLED; }
                    case INTERRUPTED -> {
                        edit.state = reconciliation ? State.READY : State.INTERRUPTED;
                        edit.reason = reconciliation ? null : Reason.INTERRUPTED;
                    }
                }
            }
            Job changed = edit.finish(obsolete ? EventKind.LATE_EFFECTS
                    : reconciliation ? EventKind.RECONCILED : EventKind.OBSERVED, settings.events());
            rows.put(id, changed);
            // The assignment usage was updated once; ancestor aggregates include the same delta once.
            if (!delta.isEmpty()) for (Job ancestor : ancestors(id, rows)) {
                var charged = new Edit(rows.get(ancestor.id()));
                var amounts = new EnumMap<Budgets.Kind, Long>(Budgets.Kind.class);
                amounts.putAll(charged.usage);
                delta.forEach((k, v) -> amounts.merge(k, v, Math::addExact));
                charged.usage = amounts;
                Job value = charged.old.id().equals(id)
                        ? copyUsage(rows.get(id), amounts)
                        : charged.finish(EventKind.OBSERVED, settings.events());
                rows.put(value.id(), value);
            }
            if (!obsolete) propagate(rows, Set.of(id), work);
            return publish(rows, state.allocations(), rows.get(id), null, work.edges);
        } catch (Limited limited) { return reject(limited.reason, work); }
        catch (IllegalArgumentException | NullPointerException | ArithmeticException invalid) {
            return reject(Reason.REQUEST_INVALID, work);
        }
    }
    public Change cancel(UUID id, Guard guard, TrustedContext caller) {
        thread(); var work = new Work(); Job job = controlled(id, caller);
        if (job == null) return reject(Reason.AUTHORITY_DENIED, work);
        if (job.state().terminal() || job.state() == State.CANCELLING) return duplicate(job, work);
        Change denied = guard(job, guard, work); if (denied != null) return denied;
        try {
            var rows = rows(); cascade(rows, id, State.CANCELLED, work, new HashSet<>());
            propagate(rows, Set.of(id), work);
            return publish(rows, state.allocations(), rows.get(id), null, work.edges);
        } catch (Limited limited) { return reject(limited.reason, work); }
        catch (ArithmeticException overflow) { return reject(Reason.BUDGET_EXHAUSTED, work); }
    }
    /** Allocate real deposited output; neither physical stock nor a resource lease is created. */
    public Change allocate(Allocation allocation, Guard demandGuard, TrustedContext caller) {
        thread(); var work = new Work(); Job demand = controlled(allocation.demand(), caller);
        Job producer = controlled(allocation.producer(), caller);
        if (demand == null || producer == null || !producer.origin().equals(demand.origin()))
            return reject(Reason.AUTHORITY_DENIED, work);
        var existing = state.allocations().stream().filter(a -> a.id().equals(allocation.id())).findFirst();
        if (existing.isPresent()) return existing.get().equals(allocation) ? duplicate(demand, work)
                : reject(Reason.REQUEST_INVALID, work);
        Change denied = guard(demand, demandGuard, work); if (denied != null) return denied;
        try {
            if (state.allocations().size() == settings.allocations()) return reject(Reason.STORAGE_LIMIT_REACHED, work);
            if (demand.state() != State.READY || !dependenciesSucceeded(demand, rows(), work)
                    || !sameBindings(producer.request(), demand.request()))
                return reject(Reason.REQUEST_INVALID, work);
            Attempt attempt = producer.attempts().stream().filter(a -> a.id().equals(allocation.attempt())).findFirst().orElseThrow();
            if (attempt.terminal() == null || attempt.uncertain()) return reject(Reason.STALE_OBSERVATION, work);
            var receipt = attempt.receipts().stream().filter(r -> r.receiptId().equals(allocation.receipt())
                    && r.stage() == CropDelivery.Stage.DEPOSIT).findFirst().orElseThrow();
            long receiptUse = 0, batchUse = 0, demandUse = 0;
            for (Allocation used : state.allocations()) {
                if (used.demand().equals(demand.id())) demandUse = Math.addExact(demandUse, used.quantity());
                if (used.producer().equals(producer.id()) && used.attempt().equals(attempt.id())) {
                    if (used.receipt().equals(receipt.receiptId())) receiptUse = Math.addExact(receiptUse, used.quantity());
                    var other = attempt.receipts().stream().filter(r -> r.receiptId().equals(used.receipt())).findFirst().orElseThrow();
                    if (other.batchId().equals(receipt.batchId()) && other.runId().equals(receipt.runId()))
                        batchUse = Math.addExact(batchUse, used.quantity());
                }
            }
            long needed = ((IntValue)demand.request().request().arguments().get("amount")).value();
            long batch = batchQuantity(attempt.receipts(), receipt.runId(), receipt.batchId());
            if (Math.addExact(receiptUse, allocation.quantity()) > receipt.wheat()
                    || Math.addExact(batchUse, allocation.quantity()) > batch
                    || Math.addExact(demandUse, allocation.quantity()) > needed - demand.fulfilled())
                return reject(Reason.RESOURCE_MISSING, work);
            var allocations = new ArrayList<>(state.allocations()); allocations.add(allocation);
            var edit = new Edit(demand);
            if (demandUse + allocation.quantity() + demand.fulfilled() == needed) {
                edit.state = State.SUCCEEDED; edit.reason = null;
            }
            Job changed = edit.finish(EventKind.ALLOCATED, settings.events()); var rows = rows(); rows.put(demand.id(), changed);
            propagate(rows, Set.of(demand.id()), work);
            return publish(rows, allocations, rows.get(demand.id()), null, work.edges);
        } catch (Limited limited) { return reject(limited.reason, work); }
        catch (IllegalArgumentException | NoSuchElementException | ArithmeticException invalid) {
            return reject(Reason.REQUEST_INVALID, work);
        }
    }
    public Roots protectedRoots() {
        thread(); var artifacts = new HashSet<ArtifactRef>(); var runs = new HashSet<UUID>(); var jobs = new HashSet<UUID>();
        for (Snapshot snapshot : pending == null ? List.of(state) : List.of(state, pending))
            for (Job job : snapshot.jobs()) {
                jobs.add(job.id()); jobs.addAll(job.dependencies()); jobs.addAll(job.children());
                for (Attempt a : job.attempts()) {
                    runs.add(a.id());
                    for (var ref : a.executions()) { runs.add(ref.runId()); artifacts.addAll(ref.pinned()); }
                }
            }
        return new Roots(artifacts, runs, jobs, !readOnly && !fenced);
    }
    /** One ack, or one durable notification preparation, per tick. */
    public void tick() {
        thread();
        if (fenced || readOnly) { writes.clear(); return; }
        Written written = writes.poll();
        if (written != null) {
            if (pending == null || written.error() != null || !pending.equals(written.snapshot())
                    || written.completedAt() > deadline) {
                fenced = true; failure = Reason.STORAGE_UNAVAILABLE; afterWrite = null; return;
            }
            state = pending; pending = null;
            Runnable after = afterWrite; afterWrite = null;
            if (after != null) try { after.run(); }
            catch (RuntimeException notificationFailure) { failure = Reason.ACTION_FAILED; }
            return;
        }
        if (pending != null) {
            if (clock.millis() >= deadline) { fenced = true; afterWrite = null; failure = Reason.STORAGE_UNAVAILABLE; }
            return;
        }
        for (Job job : state.jobs()) if (job.state() == State.CANCELLING && job.current() != null
                && job.current().open() && job.current().cancellationDeliveries() < settings.cancellationDeliveries()) {
            Attempt a = job.current();
            Attempt next = attempt(a, a.executions(), a.usage(), a.receipts(), a.effects(), a.credited(),
                    a.terminal(), a.uncertain(), a.cancellationDeliveries() + 1);
            var cancellation = new Cancellation(job.id(), a.id(), a.generation(), job.origin());
            replaceAttempt(job, next, EventKind.CANCEL_DELIVERY, state.allocations(), new Work(),
                    () -> cancellations.request(cancellation));
            return;
        }
    }
    private Change replaceAttempt(Job job, Attempt next, EventKind kind, List<Allocation> allocations,
                                  Work work, Runnable after) {
        try {
            var edit = new Edit(job);
            edit.attempts = job.attempts().stream().map(a -> a.id().equals(next.id()) ? next : a).toList();
            Job changed = edit.finish(kind, settings.events()); var rows = rows(); rows.put(job.id(), changed);
            return publish(rows, allocations, changed, after, work.edges);
        } catch (ArithmeticException overflow) { return reject(Reason.BUDGET_EXHAUSTED, work); }
    }
    private Change publish(Map<UUID, Job> rows, List<Allocation> allocations, Job changed,
                           Runnable after, int inspectedEdges) {
        try {
            if (!ready()) return new Change(Code.REJECTED, null, unavailableReason(), inspectedEdges);
            Snapshot next = new Snapshot(state.worldId(), Math.addExact(state.revision(), 1),
                    List.copyOf(rows.values()), allocations);
            deadline = Math.addExact(clock.millis(), settings.publicationMillis());
            pending = next; afterWrite = after;
            storage.replace(state, next).whenComplete((value, error) ->
                    writes.add(new Written(value, error, clock.millis())));
            return new Change(Code.PENDING, changed, null, inspectedEdges);
        } catch (RuntimeException error) {
            fenced = true; afterWrite = null; failure = Reason.STORAGE_UNAVAILABLE;
            return new Change(Code.REJECTED, null, failure, inspectedEdges);
        }
    }
    private Job fresh(UUID id, UUID submission, ValidatedRequest bound, UUID parent,
                      Budgets.Limits allowance, List<UUID> dependencies) {
        return new Job(id, submission, 1, bound, parent, dependencies, List.of(),
                dependencies.isEmpty() ? State.READY : State.WAITING, null, null, allowance,
                Map.of(), 0, List.of(), List.of(new Event(1, EventKind.CREATED, null, 0)));
    }
    private Change guard(Job job, Guard guard, Work work) {
        if (!ready()) return reject(unavailableReason(), work);
        if (guard == null || !job.guard().equals(guard))
            return new Change(Code.CONFLICT, null, Reason.STALE_OBSERVATION, work.edges);
        return null;
    }
    private Job controlled(UUID id, TrustedContext caller) {
        Job job = find(id);
        return job != null && sameWorld(caller) && policy.mayControl(caller, job) ? job : null;
    }
    private boolean sameWorld(TrustedContext caller) {
        return caller != null && caller.scope().worldId().equals(state.worldId());
    }
    private Job find(UUID id) { return state.jobs().stream().filter(j -> j.id().equals(id)).findFirst().orElse(null); }
    private LinkedHashMap<UUID, Job> rows() {
        var rows = new LinkedHashMap<UUID, Job>(); state.jobs().forEach(j -> rows.put(j.id(), j)); return rows;
    }
    private Change reject(Reason reason, Work work) {
        return new Change(Code.REJECTED, null, Objects.requireNonNull(reason), Math.min(work.edges, settings.edges()));
    }
    private Change duplicate(Job job, Work work) { return new Change(Code.DUPLICATE, job, null, work.edges); }
    private void checkCapacity(Map<UUID, Job> rows) {
        if (rows.size() > settings.total() || rows.values().stream().filter(j -> !j.state().terminal()).count() > settings.active())
            throw new Limited(Reason.STORAGE_LIMIT_REACHED);
    }
    private void checkGraph(UUID id, Map<UUID, Job> rows, Work work) {
        graph(id, rows, work, new HashSet<>(), 0);
    }
    private void graph(UUID id, Map<UUID, Job> rows, Work work, Set<UUID> path, int depth) {
        if (depth > settings.depth()) throw new Limited(Reason.BUDGET_EXHAUSTED);
        if (!path.add(id)) throw new Limited(Reason.REQUEST_INVALID);
        Job job = rows.get(id); if (job == null) throw new Limited(Reason.REQUEST_INVALID);
        for (UUID dependency : job.dependencies()) {
            work.edge(); Job needed = rows.get(dependency);
            if (needed == null || !needed.origin().equals(job.origin())) throw new Limited(Reason.AUTHORITY_DENIED);
            graph(dependency, rows, work, path, depth + 1);
        }
        path.remove(id);
    }
    private boolean dependenciesSucceeded(Job job, Map<UUID, Job> rows, Work work) {
        for (UUID id : job.dependencies()) { work.edge(); if (rows.get(id).state() != State.SUCCEEDED) return false; }
        return true;
    }
    private Job dependencyState(Job job, Map<UUID, Job> rows, boolean increment) {
        boolean ready = job.dependencies().stream().allMatch(id -> rows.get(id).state() == State.SUCCEEDED);
        if (job.state() == State.READY && !ready || job.state() == State.WAITING && ready && job.reason() == null) {
            var edit = new Edit(job); edit.state = ready ? State.READY : State.WAITING;
            return increment ? edit.finish(EventKind.TRANSITIONED, settings.events())
                    : new Job(job.id(), job.submissionId(), job.revision(), job.request(), job.parent(), job.dependencies(),
                    job.children(), edit.state, job.reason(), job.cancellationOutcome(), job.allowance(),
                    job.usage(), job.generation(), job.attempts(), job.events());
        }
        return job;
    }
    private void propagate(Map<UUID, Job> rows, Set<UUID> changed, Work work) {
        Map<UUID, List<UUID>> reverse = new HashMap<>();
        for (Job job : rows.values()) for (UUID dep : job.dependencies())
            reverse.computeIfAbsent(dep, ignored -> new ArrayList<>()).add(job.id());
        var queue = new ArrayDeque<>(changed); var visited = new HashSet<UUID>();
        while (!queue.isEmpty()) {
            UUID id = queue.removeFirst(); if (!visited.add(id)) continue;
            for (UUID dependent : reverse.getOrDefault(id, List.of())) {
                work.edge(); Job job = rows.get(dependent);
                if (job.state().terminal()) continue;
                Job child = rows.get(id);
                if (child.state() == State.CANCELLED || child.state() == State.FAILED)
                    cascade(rows, dependent, child.state() == State.CANCELLED ? State.CANCELLED : State.FAILED,
                            work, new HashSet<>());
                else if (job.state() == State.WAITING && (job.reason() == null || job.reason() == Reason.INTERRUPTED)
                        && (job.current() == null || !job.current().open())
                        && dependenciesSucceeded(job, rows, work)) {
                    var edit = new Edit(job); edit.state = State.READY; edit.reason = null;
                    rows.put(dependent, edit.finish(EventKind.TRANSITIONED, settings.events()));
                }
                if (rows.get(dependent).state().terminal()) queue.addLast(dependent);
            }
        }
        for (Job job : List.copyOf(rows.values())) if (job.state() == State.CANCELLING
                && (job.current() == null || !job.current().open())
                && job.children().stream().allMatch(id -> rows.get(id).state().terminal())) {
            var edit = new Edit(job);
            edit.state = job.cancellationOutcome() == State.FAILED ? State.FAILED : State.CANCELLED;
            edit.reason = edit.state == State.CANCELLED ? Reason.CANCELLED : Reason.ACTION_FAILED;
            rows.put(job.id(), edit.finish(EventKind.TRANSITIONED, settings.events()));
        }
    }
    private void cascade(Map<UUID, Job> rows, UUID id, State terminal, Work work, Set<UUID> visited) {
        if (!visited.add(id)) return;
        Job job = rows.get(id); if (job.state().terminal()) return;
        if (job.cancellationOutcome() == State.FAILED) terminal = State.FAILED;
        if (job.state() == State.CANCELLING && job.cancellationOutcome() == terminal) return;
        for (UUID child : job.children()) { work.edge(); cascade(rows, child, terminal, work, visited); }
        boolean open = job.current() != null && job.current().open()
                || job.children().stream().anyMatch(child -> !rows.get(child).state().terminal());
        var edit = new Edit(job); edit.cancellationOutcome = terminal;
        edit.state = open ? State.CANCELLING : terminal;
        edit.reason = terminal == State.CANCELLED ? Reason.CANCELLED : Reason.ACTION_FAILED;
        rows.put(id, edit.finish(EventKind.CANCEL_REQUESTED, settings.events()));
    }
    private List<Job> ancestors(UUID id, Map<UUID, Job> rows) {
        var result = new ArrayList<Job>(); var seen = new HashSet<UUID>();
        while (id != null) {
            if (result.size() > settings.depth() || !seen.add(id)) throw new Limited(Reason.BUDGET_EXHAUSTED);
            Job job = rows.get(id); if (job == null) throw new Limited(Reason.REQUEST_INVALID);
            result.add(job); id = job.parent();
        }
        return result;
    }
    private long available(UUID id, Budgets.Kind kind, Map<UUID, Job> rows) {
        Job job = rows.get(id); long reserved = 0;
        for (Job child : rows.values()) if (ancestors(child.id(), rows).stream().anyMatch(a -> a.id().equals(id)))
            for (Attempt a : child.attempts()) if (a.open())
                reserved = Math.addExact(reserved, a.allowance().maximum(kind) - a.usage().getOrDefault(kind, 0L));
        return Math.subtractExact(Math.subtractExact(job.allowance().maximum(kind),
                job.usage().getOrDefault(kind, 0L)), reserved);
    }
    private void debit(Map<UUID, Job> rows, UUID id, Map<Budgets.Kind, Long> delta, EventKind event) {
        for (Job ancestor : ancestors(id, rows)) {
            var edit = new Edit(rows.get(ancestor.id()));
            var values = new EnumMap<Budgets.Kind, Long>(Budgets.Kind.class); values.putAll(edit.usage);
            delta.forEach((kind, amount) -> values.merge(kind, amount, Math::addExact)); edit.usage = values;
            rows.put(ancestor.id(), edit.finish(event, settings.events()));
        }
    }
    private static void envelope(Budgets.Limits child, Budgets.Limits parent) {
        if (child == null || child.deadlineEpochMillis() > parent.deadlineEpochMillis())
            throw new Limited(Reason.BUDGET_EXHAUSTED);
        for (Budgets.Kind kind : Budgets.Kind.values())
            if (child.maximum(kind) > parent.maximum(kind)) throw new Limited(Reason.BUDGET_EXHAUSTED);
    }
    private ValidatedRequest remainingRequest(Job job, ActorRef worker) {
        var args = new HashMap<>(job.request().request().arguments()); args.put("actor", new ActorValue(worker));
        long allocated = state.allocations().stream().filter(a -> a.demand().equals(job.id()))
                .mapToLong(Allocation::quantity).reduce(0, Math::addExact);
        long remaining = ((IntValue)args.get("amount")).value() - job.fulfilled() - allocated;
        if (remaining <= 0) throw new Limited(Reason.REQUEST_INVALID);
        args.put("amount", new IntValue(remaining));
        return new ValidatedRequest(new CapabilityRequest(job.request().request().capability(), args),
                job.origin(), job.request().observation());
    }
    private static Attempt attempt(Attempt old, List<ExecutionReference> refs, Map<Budgets.Kind, Long> usage,
                                   List<CropDelivery.CropReceipt> receipts, long effects, long credited,
                                   Execution terminal, boolean uncertain, int deliveries) {
        return new Attempt(old.id(), old.generation(), old.worker(), old.bound(), old.allowance(),
                refs, usage, receipts, effects, credited, terminal, uncertain, deliveries);
    }
    private static Job copyUsage(Job job, Map<Budgets.Kind, Long> usage) {
        return new Job(job.id(), job.submissionId(), job.revision(), job.request(), job.parent(),
                job.dependencies(), job.children(), job.state(), job.reason(), job.cancellationOutcome(),
                job.allowance(), usage, job.generation(), job.attempts(), job.events());
    }
    private static void validateReceipt(Attempt attempt, CropDelivery.CropReceipt receipt) {
        if (attempt.executions().stream().noneMatch(r -> r.runId().equals(receipt.runId()))
                || !receipt.actor().equals(attempt.worker())
                || !receipt.source().equals(((AreaValue)attempt.bound().request().arguments().get("source")).value())
                || !receipt.destination().equals(((ContainerValue)attempt.bound().request().arguments().get("destination")).value()))
            throw new Limited(Reason.AUTHORITY_DENIED);
    }
    private static long quantity(Attempt attempt, List<CropDelivery.CropReceipt> receipts) {
        var batches = new HashSet<List<UUID>>(); long result = 0;
        for (var receipt : receipts) batches.add(List.of(receipt.runId(), receipt.batchId()));
        for (var batch : batches) result = Math.addExact(result, batchQuantity(receipts, batch.getFirst(), batch.getLast()));
        return result;
    }
    private static long batchQuantity(List<CropDelivery.CropReceipt> receipts, UUID run, UUID batch) {
        long[] stages = new long[3];
        for (var receipt : receipts) if (receipt.runId().equals(run) && receipt.batchId().equals(batch))
            stages[receipt.stage().ordinal()] = Math.addExact(stages[receipt.stage().ordinal()], receipt.wheat());
        return Math.min(stages[0], Math.min(stages[1], stages[2]));
    }
    private static boolean sameBindings(ValidatedRequest a, ValidatedRequest b) {
        var left = new HashMap<>(a.request().arguments()); var right = new HashMap<>(b.request().arguments());
        left.remove("amount"); right.remove("amount");
        return a.context().equals(b.context()) && a.request().capability().equals(b.request().capability()) && left.equals(right);
    }
    /** Bounded load/import verification. No unknown field or graph becomes active by parsing alone. */
    public static void validateSnapshot(Snapshot snapshot, Settings settings) {
        if (snapshot.jobs().size() > settings.total()
                || snapshot.jobs().stream().filter(j -> !j.state().terminal()).count() > settings.active()
                || snapshot.allocations().size() > settings.allocations()) throw new IllegalArgumentException("Job quota");
        Map<UUID, Job> rows = new HashMap<>(); snapshot.jobs().forEach(j -> rows.put(j.id(), j));
        for (Allocation allocation : snapshot.allocations()) {
            Job producer = rows.get(allocation.producer()), demand = rows.get(allocation.demand());
            if (producer == null || demand == null || producer.id().equals(demand.id())
                    || !sameBindings(producer.request(), demand.request()))
                throw new IllegalArgumentException("Allocation scope/bindings");
            Attempt attempt = producer.attempts().stream().filter(a -> a.id().equals(allocation.attempt()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Allocation attempt"));
            var receipt = attempt.receipts().stream().filter(r -> r.receiptId().equals(allocation.receipt()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Allocation receipt"));
            if (attempt.open() || receipt.stage() != CropDelivery.Stage.DEPOSIT)
                throw new IllegalArgumentException("Unobserved allocation");
            long receiptSpent = 0, batchSpent = 0, demandSpent = 0;
            for (Allocation other : snapshot.allocations()) {
                if (other.receipt().equals(receipt.receiptId())) receiptSpent = Math.addExact(receiptSpent, other.quantity());
                if (other.producer().equals(producer.id()) && other.attempt().equals(attempt.id())) {
                    var source = attempt.receipts().stream().filter(r -> r.receiptId().equals(other.receipt())).findFirst();
                    if (source.isPresent() && source.get().batchId().equals(receipt.batchId())
                            && source.get().runId().equals(receipt.runId())) batchSpent = Math.addExact(batchSpent, other.quantity());
                }
                if (other.demand().equals(demand.id())) demandSpent = Math.addExact(demandSpent, other.quantity());
            }
            if (receiptSpent > receipt.wheat() || batchSpent > batchQuantity(attempt.receipts(), receipt.runId(), receipt.batchId())
                    || Math.addExact(demandSpent, demand.fulfilled()) > ((IntValue)demand.request().request().arguments().get("amount")).value())
                throw new IllegalArgumentException("Allocation overspent");
        }
        Set<UUID> attempts = new HashSet<>(), receipts = new HashSet<>(), openWorkers = new HashSet<>();
        for (Job job : snapshot.jobs()) {
            if (job.children().size() > settings.children() || job.dependencies().size() > settings.children()
                    || job.attempts().size() > settings.attempts() || job.events().size() > settings.events()
                    || job.attempts().stream().mapToInt(a -> a.receipts().size()).sum() > settings.receipts()
                    || job.attempts().stream().anyMatch(a -> a.cancellationDeliveries() > settings.cancellationDeliveries()))
                throw new IllegalArgumentException("Configured job bounds");
            if (!job.request().request().capability().equals(CropDelivery.ID)
                    || !(job.request().request().arguments().get("amount") instanceof IntValue quantity)
                    || quantity.value() < 1 || quantity.value() > 64
                    || !(job.request().request().arguments().get("actor") instanceof ActorValue)
                    || !(job.request().request().arguments().get("source") instanceof AreaValue)
                    || !(job.request().request().arguments().get("destination") instanceof ContainerValue))
                throw new IllegalArgumentException("Bound job request");
            validateGraphLoad(job.id(), rows, new HashSet<>(), 0, settings.depth(), new int[]{0}, settings.edges());
            for (UUID child : job.children())
                if (!job.dependencies().contains(child) || !job.id().equals(rows.get(child).parent())
                        || !job.origin().equals(rows.get(child).origin())) throw new IllegalArgumentException("Child linkage");
            if (job.parent() != null && (!rows.get(job.parent()).children().contains(job.id())
                    || !rows.get(job.parent()).origin().equals(job.origin()))) throw new IllegalArgumentException("Parent linkage");
            for (Attempt attempt : job.attempts()) {
                if (!attempts.add(attempt.id()) || attempt.open() && !openWorkers.add(attempt.worker().entityId()))
                    throw new IllegalArgumentException("Duplicate assignment");
                envelope(attempt.allowance(), job.allowance());
                for (var receipt : attempt.receipts()) {
                    validateReceipt(attempt, receipt);
                    if (!receipts.add(receipt.receiptId())) throw new IllegalArgumentException("Duplicate receipt ownership");
                }
                if (attempt.credited() > quantity(attempt, attempt.receipts()))
                    throw new IllegalArgumentException("Unattributed credit");
            }
            for (var entry : job.usage().entrySet())
                if (entry.getValue() > job.allowance().maximum(entry.getKey())) throw new IllegalArgumentException("Budget widened");
            var minimum = new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);
            for (Attempt a : job.attempts())
                a.usage().forEach((k,v) -> minimum.merge(k,v,Math::addExact));
            for (UUID child : job.children())
                rows.get(child).usage().forEach((k,v) -> minimum.merge(k,v,Math::addExact));
            for (var entry : minimum.entrySet())
                if (job.usage().getOrDefault(entry.getKey(),0L) < entry.getValue())
                    throw new IllegalArgumentException("Lost cumulative parent work");
            if (job.current() != null && job.current().open()
                    && job.state() != State.ACTIVE && job.state() != State.CANCELLING && job.state() != State.INTERRUPTED)
                throw new IllegalArgumentException("Unreconciled assignment");
            if (job.state() == State.FAILED && job.reason() == null
                    || job.state() == State.ACTIVE && (job.current() == null || !job.current().open()))
                throw new IllegalArgumentException("Job state evidence");
            long allocated = snapshot.allocations().stream().filter(a -> a.demand().equals(job.id()))
                    .mapToLong(Allocation::quantity).reduce(0,Math::addExact);
            if (job.state() == State.SUCCEEDED && Math.addExact(job.fulfilled(),allocated) < quantity.value())
                throw new IllegalArgumentException("Unattributed job success");
        }
    }
    private static void validateGraphLoad(UUID id, Map<UUID, Job> rows, Set<UUID> path, int depth, int maximum, int[] edges, int edgeLimit) {
        if (depth > maximum || !path.add(id)) throw new IllegalArgumentException("Job graph cycle/depth");
        Job job = rows.get(id);
        for (UUID dependency : job.dependencies()) {
            if (!job.origin().equals(rows.get(dependency).origin())) throw new IllegalArgumentException("Foreign dependency");
            if (edges[0] == edgeLimit) throw new IllegalArgumentException("Job graph work");
            edges[0]++;
            validateGraphLoad(dependency, rows, path, depth + 1, maximum, edges, edgeLimit);
        }
        path.remove(id);
    }
    private void thread() {
        if (Thread.currentThread() != gameThread) throw new IllegalStateException("Server thread required");
    }
}
