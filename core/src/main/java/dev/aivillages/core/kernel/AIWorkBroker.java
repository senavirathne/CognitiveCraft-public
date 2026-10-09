package dev.aivillages.core.kernel;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static dev.aivillages.core.kernel.Budgets.Kind;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;

/** IMP-014 / architecture 0.5. Serialized, disposable scheduling over the existing generation port.
 * Transport threads debit a frozen bounded accounting cohort; only step() delivers candidates.
 * This owner never resolves capabilities, performs trials, publishes artifacts or persists a queue. */
public final class AIWorkBroker implements GenerationPort, AutoCloseable {
    public static final int SCHEMA = 1;
    public static final String ARCHITECTURE = "0.5";
    public enum Priority { URGENT, NORMAL, BACKGROUND }
    public enum State { QUEUED, IN_FLIGHT, CANDIDATE, REJECTED, EXPIRED, UNAVAILABLE,
                        CANCELLED, DISCARDED, FAILED, ABANDONED;
        public boolean terminal() { return this != QUEUED && this != IN_FLIGHT; }
    }
    public record Contract(String name, int version, int generationSchema) {
        public Contract {
            if (name == null || name.isBlank() || name.length() > 64 || version < 1
                    || generationSchema != Generation.SCHEMA) throw new IllegalArgumentException("Broker contract");
        }
    }
    public static final Contract RESEARCH = new Contract("research-generation", 1, Generation.SCHEMA);
    public record Settings(int maxQueued, int maxSubscribers, int maxHistory, int agingStarts) {
        public Settings {
            if (maxQueued < 1 || maxQueued > 4 || maxSubscribers < 1 || maxSubscribers > 8
                    || maxHistory < maxSubscribers * (maxQueued + 1) || maxHistory > 64
                    || agingStarts < 1 || agingStarts > 8) throw new IllegalArgumentException("Broker bounds");
        }
        public static Settings defaults() { return new Settings(4, 8, 64, 4); }
    }
    @FunctionalInterface public interface Authorization { boolean current(Generation.Request request); }
    /** Explicit trusted policy; exact input/context equality is still mandatory. */
    @FunctionalInterface public interface Sharing {
        boolean allows(Generation.Request first, Generation.Request other);
        static Sharing privateScopes() { return (a, b) -> false; }
    }
    public record View(int schema, UUID subscriber, UUID work, TrustedContext owner, State state,
                       Reason reason, Generation.Compute compute, Map<Kind, Long> chargedUsage,
                       long reservedOutputBytes, long unmeasuredOutputBytes) {
        public View { chargedUsage = Map.copyOf(chargedUsage); }
    }
    /** Service aggregate contains no prompts, owners or private identifiers. */
    public record Stats(int schema, int queuedWork, int occupied, int subscribers, int retained,
                        long dispatched, long calls, long inputBytes, long outputBytes,
                        long coalesced, long rejected, long cancelled, long discarded,
                        long abandoned, int unconfirmed, long reservedOutputBytes, long unmeasuredOutputBytes) { }
    /** Enumerated rows; dispatch/delivery additionally revalidate at most sixteen members. */
    public record Slice(int workInspected, int subscribersInspected, int starts) { }
    public record Terminal(int schema, View subscriber, Generation.Result generation) {
        public Terminal {
            if (schema != SCHEMA || subscriber.schema() != SCHEMA || !subscriber.state().terminal()
                    || !subscriber.subscriber().equals(generation.id())
                    || !subscriber.owner().equals(generation.owner())) throw new IllegalArgumentException("Broker terminal envelope");
        }
    }
    private record Event(Generation.Result result, Throwable error) { }
    private final GenerationPort backend;
    private final Generation.Descriptor descriptor;
    private final Clock clock;
    private final Settings settings;
    private final Authorization authorization;
    private final Sharing sharing;
    private final Budgets.Ledger hardware;
    private final Thread ownerThread = Thread.currentThread();
    private final List<Work> queue = new ArrayList<>();
    private final LinkedHashMap<UUID, Subscriber> subscribers = new LinkedHashMap<>();
    private Work active;
    private boolean enabled = true, closed;
    private long sequence, starts, coalesced, rejected, cancelled, discarded, abandoned;
    private long settledCalls, settledInput, settledOutput;
    private long unmeasuredOutput;

    public AIWorkBroker(GenerationPort backend, Clock clock, Settings settings,
                        Budgets.Limits sharedLimits, Authorization authorization, Sharing sharing) {
        this.backend = Objects.requireNonNull(backend);
        descriptor = Objects.requireNonNull(backend.descriptor());
        this.clock = Objects.requireNonNull(clock);
        this.settings = Objects.requireNonNull(settings);
        this.authorization = Objects.requireNonNull(authorization);
        this.sharing = Objects.requireNonNull(sharing);
        hardware = new Budgets.Ledger(sharedLimits, clock);
    }
    /** Finite world-session envelope; never refreshed by retry, cancellation or gate changes. */
    public static Budgets.Limits sessionLimits(long now) {
        return new Budgets.Limits(Map.of(Kind.CALLS, 128L, Kind.REPAIRS, 64L,
                Kind.INPUT_BYTES, 2_097_152L, Kind.OUTPUT_BYTES, 1_048_576L),
                Math.addExact(now, 86_400_000L));
    }
    private void thread() {
        if (Thread.currentThread() != ownerThread) throw new IllegalStateException("Broker owner thread required");
    }
    @Override public Generation.Descriptor descriptor() { return descriptor; }
    @Override public Generation.Handle generate(Generation.Request request, Budgets.InferenceLimits limits,
                                                 Budgets.Ledger allowance) {
        return submit(request, limits, allowance, RESEARCH, Priority.NORMAL);
    }
    public Generation.Handle submit(Generation.Request request, Budgets.InferenceLimits limits,
                                    Budgets.Ledger allowance, Contract contract, Priority priority) {
        thread(); Objects.requireNonNull(request); Objects.requireNonNull(limits);
        Objects.requireNonNull(allowance); Objects.requireNonNull(contract); Objects.requireNonNull(priority);
        Subscriber previous = subscribers.get(request.id());
        if (previous != null) {
            previous.inspect(request.bound().context());
            if (!previous.request.equals(request) || previous.parent != allowance
                    || !previous.limits.equals(limits) || !previous.contract.equals(contract)
                    || previous.priority != priority) throw new IllegalArgumentException("Subscriber id reused");
            return previous;
        }
        prune();
        Subscriber subscriber = new Subscriber(request, limits, allowance, contract, priority);
        if (subscribers.size() < settings.maxHistory()) subscribers.put(request.id(), subscriber);
        else return refuse(subscriber, State.REJECTED, Reason.BUDGET_EXHAUSTED);
        if (closed) return refuse(subscriber, State.ABANDONED, Reason.INTERRUPTED);
        if (!enabled || unavailable()) return refuse(subscriber, State.UNAVAILABLE, Reason.MODEL_UNAVAILABLE);
        if (!authorized(request)) return refuse(subscriber, State.DISCARDED, Reason.AUTHORITY_DENIED);
        if (clock.millis() >= limits.total().deadlineEpochMillis()
                || clock.millis() >= allowance.deadline()) return refuse(subscriber, State.EXPIRED, Reason.BUDGET_EXHAUSTED);
        try {
            var remaining = new EnumMap<Kind, Long>(Kind.class);
            limits.total().maxima().forEach((kind, maximum) -> remaining.put(kind, Math.min(maximum, allowance.remaining(kind))));
            subscriber.allowance = allowance.child(new Budgets.Limits(remaining,
                    Math.min(limits.total().deadlineEpochMillis(), allowance.deadline())));
        }
        catch (Budgets.Exhausted | IllegalArgumentException invalid) {
            return refuse(subscriber, State.REJECTED, Reason.BUDGET_EXHAUSTED);
        }
        if (!subscriber.allowance.canDebit(Kind.CALLS, 1) || subscriber.allowance.remaining(Kind.OUTPUT_BYTES) == 0
                || subscriber.allowance.remaining(Kind.INPUT_BYTES) == 0 || request.role() == Generation.Role.REPAIR
                && !subscriber.allowance.canDebit(Kind.REPAIRS, 1))
            return refuse(subscriber, State.REJECTED, Reason.BUDGET_EXHAUSTED);
        for (Work work : queue) if (compatible(work, subscriber)
                && work.members.size() < settings.maxSubscribers()) {
            work.members.add(subscriber); subscriber.work = work; coalesced++; return subscriber;
        }
        if (queue.size() == settings.maxQueued()) return refuse(subscriber, State.REJECTED, Reason.BUDGET_EXHAUSTED);
        Work work = new Work(++sequence, starts, subscriber);
        subscriber.work = work; queue.add(work); return subscriber;
    }
    /** Explicit subscription to an unstarted cohort. In-flight cohorts are sealed. */
    public Generation.Handle subscribe(UUID workId, Generation.Request request, Budgets.InferenceLimits limits,
            Budgets.Ledger allowance, Contract contract, Priority priority) {
        thread(); Work work = queue.stream().filter(w -> w.id.equals(workId)).findFirst().orElseThrow();
        if (work.members.stream().noneMatch(s -> s.request.bound().context().equals(request.bound().context())))
            throw new SecurityException("Private work identifier");
        Subscriber probe = new Subscriber(request, limits, allowance, contract, priority);
        if (!compatible(work, probe)) throw new IllegalArgumentException("Incompatible subscription");
        return submit(request, limits, allowance, contract, priority);
    }
    private boolean authorized(Generation.Request request) {
        try { return authorization.current(request); } catch (RuntimeException failure) { return false; }
    }
    private boolean unavailable() {
        var state = backend.status().state();
        return state == Generation.State.DISABLED || state == Generation.State.UNAVAILABLE || state == Generation.State.CLOSED;
    }
    private boolean compatible(Work work, Subscriber other) {
        Subscriber first = work.members.getFirst();
        var a = first.request; var b = other.request;
        if (!first.contract.equals(other.contract) || a.role() != b.role()
                || !a.bound().context().scope().worldId().equals(b.bound().context().scope().worldId())
                || !a.bound().request().equals(b.bound().request())
                || !a.bound().observation().equals(b.bound().observation())
                || !a.capability().equals(b.capability()) || !a.primitives().equals(b.primitives())
                || !a.dependencies().equals(b.dependencies()) || !a.context().equals(b.context())) return false;
        // Pairwise authorization avoids a broad grant bridging incompatible private scopes.
        return work.members.stream().allMatch(s -> s.request.bound().context().equals(b.bound().context())
                || sharing.allows(s.request, b));
    }
    private Subscriber refuse(Subscriber subscriber, State state, Reason reason) {
        if (state == State.REJECTED) rejected++;
        finish(subscriber, state, reason, null); return subscriber;
    }
    public View view(UUID id, TrustedContext caller) {
        thread(); Subscriber subscriber = Objects.requireNonNull(subscribers.get(id), "Unknown subscriber");
        subscriber.inspect(caller); return subscriber.view();
    }
    public List<View> queueStatus(TrustedContext caller) {
        thread(); return subscribers.values().stream().filter(s -> s.request.bound().context().equals(caller))
                .map(Subscriber::view).toList();
    }
    public Optional<Terminal> terminalResult(UUID id, TrustedContext caller) {
        thread(); Subscriber subscriber = Objects.requireNonNull(subscribers.get(id), "Unknown subscriber");
        subscriber.inspect(caller); Generation.Result result = subscriber.result.getNow(null);
        return result == null ? Optional.empty() : Optional.of(new Terminal(SCHEMA, subscriber.view(), result));
    }
    public boolean cancel(UUID id, TrustedContext caller) {
        thread(); Subscriber subscriber = Objects.requireNonNull(subscribers.get(id), "Unknown subscriber");
        subscriber.inspect(caller); return subscriber.cancel();
    }
    public void enabled(boolean value) {
        thread(); enabled = value;
        if (!value) for (Subscriber subscriber : List.copyOf(subscribers.values()))
            if (!subscriber.state.terminal()) finish(subscriber, State.UNAVAILABLE, Reason.MODEL_UNAVAILABLE, null);
        abandonEmpty();
    }
    /** At most five work records, forty subscribers and one start; no transport future wait. */
    public Slice step() {
        thread(); int inspected = queue.size() + (active == null ? 0 : 1), checked = 0;
        for (Work work : allWork()) for (Subscriber subscriber : work.members) {
            checked++;
            if (subscriber.state.terminal()) continue;
            if (!authorized(subscriber.request)) finish(subscriber, State.DISCARDED, Reason.AUTHORITY_DENIED, null);
            else if (clock.millis() >= subscriber.limits.total().deadlineEpochMillis()
                    || clock.millis() >= subscriber.allowance.deadline())
                finish(subscriber, State.EXPIRED, Reason.BUDGET_EXHAUSTED, null);
        }
        abandonEmpty();
        if (active != null) {
            Event event = active.event.get();
            if (event != null) deliver(active, event);
            if (active != null && ceased(active) && active.members.stream().allMatch(s -> s.state.terminal())) settle();
        }
        if (closed || !enabled) return new Slice(inspected, checked, 0);
        if (unavailable()) {
            for (Work work : List.copyOf(queue)) for (Subscriber subscriber : work.members)
                if (!subscriber.state.terminal()) finish(subscriber, State.UNAVAILABLE, Reason.MODEL_UNAVAILABLE, null);
            abandonEmpty(); return new Slice(inspected, checked, 0);
        }
        if (active != null || queue.isEmpty() || backend.status().state() != Generation.State.READY)
            return new Slice(inspected, checked, 0);
        Comparator<Work> order = Comparator.comparingInt((Work w) -> starts - w.enqueuedStart >= settings.agingStarts()
                ? -1 : w.members.stream().filter(s -> !s.state.terminal()).mapToInt(s -> s.priority.ordinal()).min().orElse(3))
                .thenComparingLong(w -> w.sequence);
        Work next = queue.stream().min(order).orElseThrow(); queue.remove(next); active = next;
        dispatch(next); return new Slice(inspected, checked, 1);
    }
    private List<Work> allWork() {
        var work = new ArrayList<>(queue); if (active != null) work.add(active); return work;
    }
    private void dispatch(Work work) {
        for (Subscriber subscriber : work.members) if (!subscriber.state.terminal()) {
            if (!authorized(subscriber.request)) finish(subscriber, State.DISCARDED, Reason.AUTHORITY_DENIED, null);
            else if (clock.millis() >= subscriber.limits.total().deadlineEpochMillis()
                    || clock.millis() >= subscriber.allowance.deadline())
                finish(subscriber, State.EXPIRED, Reason.BUDGET_EXHAUSTED, null);
        }
        List<Subscriber> live = work.members.stream().filter(s -> !s.state.terminal()).toList();
        if (live.isEmpty()) { settle(); return; }
        var beneficiaries = new ArrayList<Budgets.Ledger>(); beneficiaries.add(hardware);
        live.forEach(s -> beneficiaries.add(s.allowance));
        var maxima = new EnumMap<Kind, Long>(Kind.class);
        for (Kind kind : List.of(Kind.CALLS, Kind.REPAIRS, Kind.INPUT_BYTES, Kind.OUTPUT_BYTES))
            maxima.put(kind, beneficiaries.stream().mapToLong(l -> l.remaining(kind)).min().orElseThrow());
        long deadline = beneficiaries.stream().mapToLong(Budgets.Ledger::deadline).min().orElseThrow();
        long input = Math.min(maxima.get(Kind.INPUT_BYTES), live.stream().mapToLong(s -> s.limits.perCallInputBytes()).min().orElseThrow());
        long output = Math.min(maxima.get(Kind.OUTPUT_BYTES), live.stream().mapToLong(s -> s.limits.perResponseOutputBytes()).min().orElseThrow());
        try {
            var limits = new Budgets.InferenceLimits(new Budgets.Limits(maxima, deadline), input, output);
            work.usage = Budgets.Ledger.shared(limits.total(), clock, beneficiaries);
            work.reservation = work.usage.reserveOutput(output);
            for (Subscriber subscriber : live) { subscriber.state = State.IN_FLIGHT; subscriber.charged = true; }
            work.transportRequest = live.getFirst().request;
            work.handle = backend.generate(work.transportRequest, limits, work.usage);
            starts++;
            work.handle.result().whenComplete((result, error) -> work.event.compareAndSet(null, new Event(result, error)));
        } catch (RuntimeException failure) {
            for (Subscriber subscriber : live) finish(subscriber, State.REJECTED, Reason.BUDGET_EXHAUSTED, null);
            if (work.handle == null) settle();
        }
    }
    private void deliver(Work work, Event event) {
        Generation.Result result = event.result();
        boolean valid = event.error() == null && result != null && result.id().equals(work.transportRequest.id())
                && result.owner().equals(work.transportRequest.bound().context()) && result.role() == work.transportRequest.role();
        for (Subscriber subscriber : work.members) if (!subscriber.state.terminal()) {
            if (!authorized(subscriber.request)) finish(subscriber, State.DISCARDED, Reason.AUTHORITY_DENIED, null);
            else if (clock.millis() >= subscriber.limits.total().deadlineEpochMillis()
                    || clock.millis() >= subscriber.allowance.deadline())
                finish(subscriber, State.EXPIRED, Reason.BUDGET_EXHAUSTED, null);
            else if (!valid) finish(subscriber, State.FAILED, Reason.ACTION_FAILED, null);
            else finish(subscriber, result.outcome() == Generation.Outcome.CANDIDATE ? State.CANDIDATE
                    : result.reason() == Reason.BUDGET_EXHAUSTED ? State.EXPIRED
                    : result.reason() == Reason.MODEL_UNAVAILABLE ? State.UNAVAILABLE : State.FAILED,
                    result.reason(), result);
        }
        abandonEmpty();
    }
    private boolean ceased(Work work) {
        if (work.handle == null) return true;
        var compute = work.handle.compute();
        return compute == Generation.Compute.COMPLETED || compute == Generation.Compute.STOP_CONFIRMED
                || compute == Generation.Compute.NOT_STARTED && work.event.get() != null;
    }
    private void abandonEmpty() {
        queue.removeIf(w -> w.members.stream().allMatch(s -> s.state.terminal()));
        if (active != null && active.members.stream().allMatch(s -> s.state.terminal())
                && !active.stopRequested && active.handle != null && !ceased(active)) {
            active.stopRequested = true;
            try { active.handle.cancel(); } catch (RuntimeException ignored) { /* occupancy retained */ }
        }
    }
    private void settle() {
        if (active.usage != null) {
            Map<Kind, Long> usage = active.usage.snapshot();
            settledCalls += usage.getOrDefault(Kind.CALLS, 0L);
            settledInput += usage.getOrDefault(Kind.INPUT_BYTES, 0L);
            settledOutput += usage.getOrDefault(Kind.OUTPUT_BYTES, 0L);
            Event event = active.event.get();
            Generation.Result result = event == null ? null : event.result();
            boolean measured = result != null && (result.outcome() == Generation.Outcome.CANDIDATE
                    || result.outcome() == Generation.Outcome.LIMIT || result.outcome() == Generation.Outcome.MALFORMED)
                    && result.compute() == Generation.Compute.COMPLETED;
            if (active.reservation != null) {
                if (usage.getOrDefault(Kind.CALLS, 0L) == 0 || measured) active.reservation.release();
                else { active.unmeasured = active.reservation.forfeit(); unmeasuredOutput += active.unmeasured; }
            }
        }
        active = null;
    }
    private void finish(Subscriber subscriber, State state, Reason reason, Generation.Result source) {
        if (subscriber.state.terminal()) return;
        subscriber.state = state; subscriber.reason = reason;
        if (state == State.CANCELLED) cancelled++;
        if (state == State.DISCARDED) discarded++;
        if (state == State.ABANDONED) abandoned++;
        Generation.Outcome outcome = state == State.CANDIDATE ? Generation.Outcome.CANDIDATE
                : state == State.CANCELLED ? Generation.Outcome.ABANDONED
                : state == State.ABANDONED || state == State.DISCARDED ? Generation.Outcome.ABANDONED
                : state == State.UNAVAILABLE ? Generation.Outcome.MODEL_UNAVAILABLE
                : state == State.REJECTED || state == State.EXPIRED ? Generation.Outcome.LIMIT
                : source == null ? Generation.Outcome.TRANSPORT_FAILED : source.outcome();
        Map<Kind, Long> charged = subscriber.charges();
        Generation.Usage usage = new Generation.Usage(charged.getOrDefault(Kind.INPUT_BYTES, 0L),
                charged.getOrDefault(Kind.OUTPUT_BYTES, 0L), -1, -1, Generation.Precision.UNKNOWN);
        subscriber.result.complete(new Generation.Result(subscriber.id(), subscriber.request.bound().context(),
                subscriber.request.role(), outcome, reason, state == State.CANDIDATE ? source.candidateIr() : null,
                source == null ? descriptor : source.descriptor(), source == null ? usage : source.usage(), subscriber.compute()));
    }
    private void prune() {
        if (subscribers.size() < settings.maxHistory()) return;
        var iterator = subscribers.values().iterator();
        while (iterator.hasNext() && subscribers.size() >= settings.maxHistory()) {
            Subscriber subscriber = iterator.next();
            if (subscriber.state.terminal() && (subscriber.work == null
                    || subscriber.work != active && !queue.contains(subscriber.work))) {
                iterator.remove();
                if (subscriber.work != null) subscriber.work.members.remove(subscriber);
            }
        }
    }
    public Stats stats() {
        thread(); Map<Kind, Long> live = active == null || active.usage == null ? Map.of() : active.usage.snapshot();
        Generation.Status external = backend.status();
        boolean externalBusy = external.compute() == Generation.Compute.RUNNING || external.compute() == Generation.Compute.STOP_UNCONFIRMED;
        return new Stats(SCHEMA, queue.size(), active == null && !externalBusy ? 0 : 1,
                (int)subscribers.values().stream().filter(s -> !s.state.terminal()).count(), subscribers.size(), starts,
                settledCalls + live.getOrDefault(Kind.CALLS, 0L), settledInput + live.getOrDefault(Kind.INPUT_BYTES, 0L),
                settledOutput + live.getOrDefault(Kind.OUTPUT_BYTES, 0L), coalesced, rejected, cancelled, discarded, abandoned,
                active != null && !ceased(active) && active.members.stream().allMatch(s -> s.state.terminal())
                        || active == null && external.compute() == Generation.Compute.STOP_UNCONFIRMED ? 1 : 0,
                active == null || active.reservation == null ? 0 : active.reservation.remaining(), unmeasuredOutput);
    }
    @Override public Generation.Status status() {
        thread(); var external = backend.status();
        return new Generation.Status(closed ? Generation.State.CLOSED : !enabled ? Generation.State.DISABLED
                : active == null ? external.state() : active.members.stream().allMatch(s -> s.state.terminal())
                ? Generation.State.CANCELLING : Generation.State.IN_FLIGHT, queue.size(),
                active == null ? external.active() : active.id, active == null ? external.compute()
                : active.handle == null ? Generation.Compute.NOT_STARTED : active.handle.compute(), false, false);
    }
    @Override public void close() {
        thread(); if (closed) return; closed = true;
        for (Subscriber subscriber : List.copyOf(subscribers.values()))
            if (!subscriber.state.terminal()) finish(subscriber, State.ABANDONED, Reason.INTERRUPTED, null);
        abandonEmpty();
    }
    private final class Work {
        final UUID id = UUID.randomUUID(); final long sequence, enqueuedStart;
        final List<Subscriber> members = new ArrayList<>();
        final AtomicReference<Event> event = new AtomicReference<>();
        Generation.Request transportRequest; Budgets.Ledger usage; Generation.Handle handle;
        Budgets.Ledger.Reservation reservation; long unmeasured;
        boolean stopRequested;
        Work(long sequence, long enqueuedStart, Subscriber first) {
            this.sequence = sequence; this.enqueuedStart = enqueuedStart; members.add(first);
        }
    }
    private final class Subscriber implements Generation.Handle {
        final Generation.Request request; final Budgets.InferenceLimits limits; final Budgets.Ledger parent;
        final Contract contract; final Priority priority;
        final CompletableFuture<Generation.Result> result = new CompletableFuture<>();
        final CompletionStage<Generation.Result> readOnly = result.minimalCompletionStage();
        Budgets.Ledger allowance; Work work; State state = State.QUEUED; Reason reason; boolean charged;
        Subscriber(Generation.Request request, Budgets.InferenceLimits limits, Budgets.Ledger parent,
                   Contract contract, Priority priority) {
            this.request = request; this.limits = limits; this.parent = parent; this.contract = contract; this.priority = priority;
        }
        void inspect(TrustedContext caller) {
            if (!request.bound().context().equals(caller)) throw new SecurityException("Private inference subscriber");
        }
        Map<Kind, Long> charges() { return !charged || work.usage == null ? Map.of() : work.usage.snapshot(); }
        View view() { return new View(SCHEMA, id(), work == null ? null : work.id, request.bound().context(), state, reason, compute(), charges(),
                !charged || work.reservation == null ? 0 : work.reservation.remaining(), !charged ? 0 : work.unmeasured); }
        @Override public UUID id() { return request.id(); }
        @Override public CompletionStage<Generation.Result> result() { return readOnly; }
        @Override public boolean cancel() {
            thread(); if (state.terminal()) return false;
            finish(this, State.CANCELLED, Reason.CANCELLED, null); abandonEmpty(); return true;
        }
        @Override public Generation.Compute compute() {
            thread(); return work == null || work.handle == null ? Generation.Compute.NOT_STARTED : work.handle.compute();
        }
    }
}
