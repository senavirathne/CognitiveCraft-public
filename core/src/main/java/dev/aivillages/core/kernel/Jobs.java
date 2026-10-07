package dev.aivillages.core.kernel;

import java.util.*;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;

/**
 * Architecture extension 0.2 / job schema 1. These immutable records contain no
 * executor frames, model handles, entities, implicit authority or worker selector.
 */
public final class Jobs {
    public static final String ARCHITECTURE_VERSION = "0.2";
    public static final String KERNEL_ARCHITECTURE_VERSION = "0.1";
    public static final int SCHEMA = 1;
    private Jobs() { }

    public enum State {
        READY, WAITING, ACTIVE, CANCELLING, INTERRUPTED, SUCCEEDED, FAILED, CANCELLED;
        public boolean terminal() { return this == SUCCEEDED || this == FAILED || this == CANCELLED; }
    }
    public enum EventKind {
        CREATED, DEPENDENCY, ASSIGNED, PINNED, OBSERVED, TRANSITIONED, CANCEL_REQUESTED,
        CANCEL_DELIVERY, RECONCILED, LATE_EFFECTS, ALLOCATED, RESTORED
    }
    public enum Code { PENDING, APPLIED, DUPLICATE, CONFLICT, REJECTED }

    public record Settings(int active, int total, int children, int depth, int edges,
                           int ready, int events, int attempts, int receipts, int allocations,
                           int cancellationDeliveries, long publicationMillis) {
        public Settings {
            if (active < 1 || active > 32 || total < active || total > 64
                    || children < 1 || children > 8 || depth < 1 || depth > 4
                    || edges < 1 || edges > 64 || ready < 1 || ready > 8
                    || events < 1 || events > 16 || attempts < 1 || attempts > 8
                    || receipts < 1 || receipts > 64 || allocations < 1 || allocations > 128
                    || cancellationDeliveries < 1 || cancellationDeliveries > 2
                    || publicationMillis < 1 || publicationMillis > 30_000)
                throw new IllegalArgumentException("Finite job settings");
        }
        public static Settings defaults() {
            return new Settings(32, 64, 8, 4, 64, 8, 16, 8, 64, 128, 2, 30_000);
        }
    }
    public record Guard(long revision, long generation) {
        public Guard {
            if (revision < 0 || generation < 0) throw new IllegalArgumentException("Job guard");
        }
    }
    public record Event(long revision, EventKind kind, UUID attempt, long generation) {
        public Event {
            Objects.requireNonNull(kind);
            if (revision < 0 || generation < 0) throw new IllegalArgumentException("Event counters");
        }
    }
    /** Immutable exact references, pinned before the execution owner may start. */
    public record ExecutionReference(UUID runId, ArtifactRef artifact, List<ArtifactRef> pinned) {
        public ExecutionReference {
            Objects.requireNonNull(runId); Objects.requireNonNull(artifact);
            pinned = List.copyOf(pinned);
            if (pinned.isEmpty() || pinned.size() > 16 || !pinned.contains(artifact)
                    || new HashSet<>(pinned).size() != pinned.size())
                throw new IllegalArgumentException("Exact pinned closure");
        }
    }
    /**
     * The assignment is distinct from its executions (research may first prepare
     * a candidate). An empty execution list never authorizes a physical action.
     * Open/uncertain assignments retain the unspent part of their reservation.
     */
    public record Attempt(UUID id, long generation, ActorRef worker, ValidatedRequest bound,
                          Budgets.Limits allowance, List<ExecutionReference> executions,
                          Map<Budgets.Kind, Long> usage, List<CropDelivery.CropReceipt> receipts,
                          long effects, long credited, Execution terminal, boolean uncertain,
                          int cancellationDeliveries) {
        public Attempt {
            Objects.requireNonNull(id); Objects.requireNonNull(worker); Objects.requireNonNull(bound);
            Objects.requireNonNull(allowance);
            executions = List.copyOf(executions); receipts = List.copyOf(receipts);
            usage = Jobs.usage(usage);
            if (generation < 1 || executions.size() > 8 || receipts.size() > 64
                    || effects < 0 || credited < 0 || cancellationDeliveries < 0
                    || cancellationDeliveries > 2
                    || !(bound.request().arguments().get("actor") instanceof ActorValue actor)
                    || !actor.value().equals(worker)
                    || executions.stream().map(ExecutionReference::runId).distinct().count() != executions.size()
                    || receipts.stream().map(CropDelivery.CropReceipt::receiptId).distinct().count() != receipts.size())
                throw new IllegalArgumentException("Assignment bounds");
            for (var entry : usage.entrySet())
                if (entry.getValue() > allowance.maximum(entry.getKey()))
                    throw new IllegalArgumentException("Assignment allowance");
        }
        public boolean open() { return terminal == null || uncertain; }
    }
    public record Job(UUID id, UUID submissionId, long revision, ValidatedRequest request,
                      UUID parent, List<UUID> dependencies, List<UUID> children,
                      State state, Reason reason, State cancellationOutcome,
                      Budgets.Limits allowance, Map<Budgets.Kind, Long> usage,
                      long generation, List<Attempt> attempts, List<Event> events) {
        public Job {
            Objects.requireNonNull(id); Objects.requireNonNull(submissionId);
            Objects.requireNonNull(request); Objects.requireNonNull(state); Objects.requireNonNull(allowance);
            dependencies = List.copyOf(dependencies); children = List.copyOf(children);
            attempts = List.copyOf(attempts); events = List.copyOf(events); usage = Jobs.usage(usage);
            if (revision < 0 || generation < 0 || dependencies.size() > 8 || children.size() > 8
                    || attempts.size() > 8 || events.size() > 16
                    || new HashSet<>(dependencies).size() != dependencies.size()
                    || new HashSet<>(children).size() != children.size()
                    || dependencies.contains(id) || children.contains(id)
                    || attempts.stream().map(Attempt::id).distinct().count() != attempts.size()
                    || attempts.stream().mapToInt(a -> a.receipts().size()).sum() > 64
                    || attempts.stream().filter(Attempt::open).count() > 1
                    || attempts.stream().anyMatch(a -> a.generation() > generation
                            || !a.bound().context().equals(request.context())
                            || a.allowance().deadlineEpochMillis() > allowance.deadlineEpochMillis())
                    || cancellationOutcome != null && cancellationOutcome != State.CANCELLED
                            && cancellationOutcome != State.FAILED
                    || state == State.SUCCEEDED && reason != null
                    || state == State.CANCELLED && reason != Reason.CANCELLED
                    || state == State.INTERRUPTED && reason != Reason.INTERRUPTED)
                throw new IllegalArgumentException("Job bounds or meaning");
            long prior = 0;
            for (Attempt attempt : attempts) {
                if (attempt.generation() <= prior) throw new IllegalArgumentException("Assignment generation order");
                prior = attempt.generation();
            }
        }
        public TrustedContext origin() { return request.context(); }
        public ActorRef responsible() { return ((ActorValue) request.request().arguments().get("actor")).value(); }
        public Guard guard() { return new Guard(revision, generation); }
        public Attempt current() { return attempts.isEmpty() ? null : attempts.getLast(); }
        public long fulfilled() {
            long result = 0;
            for (Attempt attempt : attempts) result = Math.addExact(result, attempt.credited());
            return result;
        }
        public long effects() {
            long result = 0;
            for (Attempt attempt : attempts) result = Math.addExact(result, attempt.effects());
            return result;
        }
    }
    public record Allocation(UUID id, UUID producer, UUID attempt, UUID receipt,
                             UUID demand, long quantity) {
        public Allocation {
            Objects.requireNonNull(id); Objects.requireNonNull(producer); Objects.requireNonNull(attempt);
            Objects.requireNonNull(receipt); Objects.requireNonNull(demand);
            if (quantity < 1) throw new IllegalArgumentException("Positive output allocation");
        }
    }
    public record Snapshot(UUID worldId, long revision, List<Job> jobs, List<Allocation> allocations) {
        public Snapshot {
            Objects.requireNonNull(worldId);
            jobs = jobs.stream().sorted(Comparator.comparing(j -> j.id().toString())).toList();
            allocations = List.copyOf(allocations);
            if (revision < 0 || jobs.size() > 64 || allocations.size() > 128
                    || jobs.stream().map(Job::id).distinct().count() != jobs.size()
                    || jobs.stream().map(Job::submissionId).distinct().count() != jobs.size()
                    || jobs.stream().filter(j -> !j.state().terminal()).count() > 32
                    || jobs.stream().anyMatch(j -> !j.origin().scope().worldId().equals(worldId))
                    || allocations.stream().map(Allocation::id).distinct().count() != allocations.size())
                throw new IllegalArgumentException("World job snapshot bounds");
            Set<UUID> ids = new HashSet<>();
            for (Job job : jobs) ids.add(job.id());
            for (Job job : jobs)
                if (job.parent() != null && !ids.contains(job.parent())
                        || !ids.containsAll(job.dependencies()) || !ids.containsAll(job.children()))
                    throw new IllegalArgumentException("Dangling job reference");
            for (Allocation allocation : allocations)
                if (!ids.contains(allocation.producer()) || !ids.contains(allocation.demand()))
                    throw new IllegalArgumentException("Dangling allocation");
        }
        public static Snapshot empty(UUID world) { return new Snapshot(world, 0, List.of(), List.of()); }
    }
    public record Change(Code code, Job job, Reason reason, int inspectedEdges) {
        public boolean accepted() { return code == Code.APPLIED || code == Code.PENDING || code == Code.DUPLICATE; }
    }
    public record ReadyPage(List<Job> jobs, int nextOffset, boolean more, int inspectedEdges) {
        public ReadyPage { jobs = List.copyOf(jobs); }
    }
    /** Private circular owner page, including inactive rows so inspection work is measurable. */
    public record DispatchPage(List<Job> jobs, int total) {
        public DispatchPage { jobs = List.copyOf(jobs); }
    }
    public record Roots(Set<ArtifactRef> artifacts, Set<UUID> runs, Set<UUID> jobs, boolean complete) {
        public Roots {
            artifacts = Set.copyOf(artifacts); runs = Set.copyOf(runs); jobs = Set.copyOf(jobs);
        }
    }
    public record Child(UUID id, CapabilityRequest request, ObservationRef observation,
                        Budgets.Limits allowance, List<UUID> dependencies) {
        public Child { dependencies = List.copyOf(dependencies); }
    }
    /** Trusted owner data; never decoded from a player command, model or Skill IR. */
    public record Report(UUID attemptId, long generation, long effects,
                         Map<Budgets.Kind, Long> usage, List<CropDelivery.CropReceipt> receipts,
                         Execution terminal) {
        public Report {
            Objects.requireNonNull(attemptId); usage = Jobs.usage(usage); receipts = List.copyOf(receipts);
            if (generation < 1 || effects < 0 || receipts.size() > 64)
                throw new IllegalArgumentException("Owner report bounds");
        }
    }
    public record Cancellation(UUID jobId, UUID attemptId, long generation, TrustedContext origin) { }

    public static boolean legal(State from, State to) {
        return switch (from) {
            case READY -> Set.of(State.WAITING, State.ACTIVE, State.CANCELLED, State.FAILED).contains(to);
            case WAITING -> Set.of(State.READY, State.ACTIVE, State.CANCELLING, State.CANCELLED,
                    State.FAILED, State.INTERRUPTED).contains(to);
            case ACTIVE -> Set.of(State.WAITING, State.CANCELLING, State.SUCCEEDED, State.FAILED,
                    State.CANCELLED, State.INTERRUPTED).contains(to);
            case CANCELLING -> Set.of(State.CANCELLED, State.FAILED, State.INTERRUPTED).contains(to);
            case INTERRUPTED -> Set.of(State.READY, State.WAITING, State.CANCELLING,
                    State.CANCELLED, State.FAILED).contains(to);
            case SUCCEEDED, FAILED, CANCELLED -> false;
        };
    }
    static Map<Budgets.Kind, Long> usage(Map<Budgets.Kind, Long> values) {
        var result = new EnumMap<Budgets.Kind, Long>(Budgets.Kind.class);
        Objects.requireNonNull(values);
        values.forEach((kind, amount) -> {
            if (kind == null || amount == null || amount < 0) throw new IllegalArgumentException("Usage");
            if (amount != 0) result.put(kind, amount);
        });
        return Map.copyOf(result);
    }
    /** Internal copy editor: only JobLifecycleStore publishes the resulting immutable record. */
    static final class Edit {
        final Job old;
        State state, cancellationOutcome;
        Reason reason;
        List<UUID> dependencies, children;
        List<Attempt> attempts;
        Map<Budgets.Kind, Long> usage;
        long generation;
        Edit(Job job) {
            old = job; state = job.state(); reason = job.reason(); cancellationOutcome = job.cancellationOutcome();
            dependencies = job.dependencies(); children = job.children(); attempts = job.attempts();
            usage = job.usage(); generation = job.generation();
        }
        Job finish(EventKind kind, int eventCap) {
            long revision = Math.addExact(old.revision(), 1);
            var events = new ArrayList<>(old.events());
            if (events.size() >= eventCap) events.removeFirst();
            events.add(new Event(revision, kind, attempts.isEmpty() ? null : attempts.getLast().id(), generation));
            return new Job(old.id(), old.submissionId(), revision, old.request(), old.parent(),
                    dependencies, children, state, reason, cancellationOutcome, old.allowance(),
                    usage, generation, attempts, events);
        }
    }
}
