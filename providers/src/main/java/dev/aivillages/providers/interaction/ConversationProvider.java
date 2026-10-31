package dev.aivillages.providers.interaction;

import dev.aivillages.core.kernel.interaction.ResponseFacts;
import dev.aivillages.core.kernel.interaction.ResponseTemplates;
import dev.aivillages.core.kernel.interaction.ResponseValidator;
import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import dev.aivillages.providers.LlmProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/** Optional phrasing only. No generation/research authority, world writer, dispatcher or raw-chat history. */
public final class ConversationProvider {
    public static final int MAX_INPUT_TOKENS = 2048, MAX_OUTPUT_TOKENS = 256, MAX_CONCURRENT = 2, MAX_PAIRS = 128;
    public static final long DEADLINE_MILLIS = 10_000, PAIR_INTERVAL_MILLIS = 2000, IDLE_MILLIS = 600_000;
    private static final int ATTEMPT_INPUT = MAX_INPUT_TOKENS / 2, ATTEMPT_OUTPUT = MAX_OUTPUT_TOKENS / 2;
    private static final String SYSTEM = "Return JSON only. Choose one allowed reply exactly. Copy facts_sha256. Do not add facts, instructions, commands, history or claims of completed actions.";
    private static final Gate GLOBAL = new Gate();
    public enum Reason { MODEL, OFFLINE, RATE_LIMIT, CONCURRENCY, CAPACITY, INPUT_BUDGET, TOKENIZER_UNAVAILABLE, TIMEOUT, INVALID_RESPONSE, PROVIDER_FAILURE, STALE, REPLAY, CANCELLED, CLOCK }
    /** Counts the actual serialized model prompt, including its chat template; byte length is not tokenization. */
    public record TokenBudget(String modelDigest, String tokenizerDigest, ToLongFunction<LlmProvider.Request> countInputTokens) {
        public TokenBudget {
            if (modelDigest == null || !modelDigest.matches("[0-9a-f]{64}") || tokenizerDigest == null || !tokenizerDigest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("pinned model and tokenizer digests required");
            Objects.requireNonNull(countInputTokens);
        }
    }
    public record Ticket(UUID world, UUID principal, UUID citizen, UUID session, long generation, long sequence, UUID message) {
        public Ticket {
            Objects.requireNonNull(world); Objects.requireNonNull(principal); Objects.requireNonNull(citizen);
            Objects.requireNonNull(session); Objects.requireNonNull(message);
            if (generation < 1 || generation > 9_007_199_254_740_991L || sequence < 0 || sequence > 9_007_199_254_740_991L)
                throw new IllegalArgumentException("bounded session/message generation");
        }
    }
    public record Result(String text, Reason reason, boolean deliverable, int providerCalls,
                         int reservedInputTokens, int reservedOutputTokens, Long actualInputTokens,
                         Long actualOutputTokens, boolean computeTerminationConfirmed) { }
    @FunctionalInterface public interface Stop { void cancel(); }
    @FunctionalInterface public interface Scheduler { Stop after(long milliseconds, Runnable action); }
    private record Pair(UUID principal, UUID citizen) { }
    private static final class PairState { Ticket latest; long lastSeen, lastAdmission = -1; Operation operation; }
    private static final class Gate { final Map<Pair, PairState> pairs = new HashMap<>(); int outstanding; }
    private final LlmProvider backend;
    private final TokenBudget tokenBudget;
    private final LongSupplier clock;
    private final Scheduler scheduler;
    private final Executor invocations;

    /** J supplies the existing timer owner. Invocation is isolated from the caller/tick thread. */
    public ConversationProvider(LlmProvider backend, ScheduledExecutorService timer) {
        this(backend, null, timer);
    }
    public ConversationProvider(LlmProvider backend, TokenBudget tokenBudget, ScheduledExecutorService timer) {
        this(backend, tokenBudget, () -> System.nanoTime() / 1_000_000L,
                (delay, action) -> { var future = timer.schedule(action, delay, TimeUnit.MILLISECONDS); return () -> future.cancel(false); },
                ForkJoinPool.commonPool());
    }
    /** Explicit clock/executor ports support deterministic boundary tests; they do not provide native authority. */
    public ConversationProvider(LlmProvider backend, TokenBudget tokenBudget, LongSupplier clock, Scheduler scheduler, Executor invocations) {
        this.backend = backend; this.tokenBudget = tokenBudget; this.clock = Objects.requireNonNull(clock);
        this.scheduler = Objects.requireNonNull(scheduler); this.invocations = Objects.requireNonNull(invocations);
    }

    public CompletableFuture<Result> respond(Ticket ticket, ResponseFacts facts, Predicate<Ticket> current) {
        Objects.requireNonNull(ticket); Objects.requireNonNull(facts); Objects.requireNonNull(current);
        synchronized (GLOBAL) {
            long now = clock.getAsLong();
            if (now < 0) return immediate(facts, Reason.CLOCK, false);
            if (!current(current, ticket)) return immediate(facts, Reason.STALE, false);
            Pair key = new Pair(ticket.principal(), ticket.citizen());
            PairState state = GLOBAL.pairs.get(key);
            if (state != null) {
                if (now < state.lastSeen) return immediate(facts, Reason.CLOCK, false);
                Ticket previous = state.latest;
                if (ticket.equals(previous)) return immediate(facts, Reason.REPLAY, false);
                if (ticket.generation() < previous.generation()
                        || ticket.generation() == previous.generation() && (!ticket.session().equals(previous.session())
                        || !ticket.world().equals(previous.world()) || ticket.sequence() <= previous.sequence()))
                    return immediate(facts, Reason.STALE, false);
            } else {
                GLOBAL.pairs.entrySet().removeIf(e -> e.getValue().operation == null
                        && now >= e.getValue().lastSeen && now - e.getValue().lastSeen >= IDLE_MILLIS);
                if (GLOBAL.pairs.size() >= MAX_PAIRS) return immediate(facts, Reason.CAPACITY, current(current, ticket));
                state = new PairState(); GLOBAL.pairs.put(key, state);
            }
            state.latest = ticket; state.lastSeen = now;
            if (state.operation != null) state.operation.finish(Reason.STALE, null);
            if (backend == null) return immediate(facts, Reason.OFFLINE, current(current, ticket));
            if (tokenBudget == null) return immediate(facts, Reason.TOKENIZER_UNAVAILABLE, current(current, ticket));
            if (state.lastAdmission >= 0 && now - state.lastAdmission < PAIR_INTERVAL_MILLIS)
                return immediate(facts, Reason.RATE_LIMIT, current(current, ticket));
            if (GLOBAL.outstanding >= MAX_CONCURRENT) return immediate(facts, Reason.CONCURRENCY, current(current, ticket));
            if (inputReservation(request(facts, false)) > 4096)
                return immediate(facts, Reason.INPUT_BUDGET, current(current, ticket));
            if (now > Long.MAX_VALUE - DEADLINE_MILLIS) return immediate(facts, Reason.CLOCK, false);
            state.lastAdmission = now;
            Operation operation = new Operation(ticket, facts, current, state, now + DEADLINE_MILLIS);
            state.operation = operation; GLOBAL.outstanding++;
            try {
                operation.timeout = Objects.requireNonNull(scheduler.after(DEADLINE_MILLIS, () -> { synchronized (GLOBAL) { operation.finish(Reason.TIMEOUT, null); } }));
                operation.dispatch();
            } catch (RuntimeException failure) { operation.finish(Reason.PROVIDER_FAILURE, null); }
            return operation.result;
        }
    }

    /** Cancels only a C response. SPI has no confirmed remote-stop operation; pending compute retains its global slot. */
    public boolean cancel(Ticket ticket) {
        synchronized (GLOBAL) {
            PairState state = GLOBAL.pairs.get(new Pair(ticket.principal(), ticket.citizen()));
            if (state == null || state.operation == null || !state.latest.equals(ticket)) return false;
            state.operation.finish(Reason.CANCELLED, null); return true;
        }
    }
    public static int outstandingOperations() { synchronized (GLOBAL) { return GLOBAL.outstanding; } }
    private static long inputReservation(LlmProvider.Request request) { return request.tokenReservation() - request.maxOutputTokens(); }
    private static LlmProvider.Request request(ResponseFacts facts, boolean correction) {
        String user = InteractionCodec.canonical(Map.of("facts", facts.packet(), "facts_sha256", facts.digest(),
                "allowed_replies", ResponseTemplates.choices(facts)));
        String schema = InteractionCodec.canonical(Map.of("type", "object", "properties", Map.of(
                "text", Map.of("type", "string", "enum", ResponseTemplates.choices(facts)),
                "facts_sha256", Map.of("type", "string", "const", facts.digest())),
                "required", List.of("text", "facts_sha256"), "additionalProperties", false));
        return new LlmProvider.Request(SYSTEM + (correction ? " Previous reply invalid. Choose an allowed reply exactly." : ""), user, schema, ATTEMPT_OUTPUT);
    }
    private static boolean current(Predicate<Ticket> current, Ticket ticket) {
        try { return current.test(ticket); } catch (RuntimeException failure) { return false; }
    }
    private static CompletableFuture<Result> immediate(ResponseFacts facts, Reason reason, boolean deliverable) {
        return CompletableFuture.completedFuture(new Result(deliverable ? ResponseTemplates.fallback(facts) : "", reason, deliverable, 0, 0, 0, null, null, false));
    }

    private final class Operation {
        final Ticket ticket; final ResponseFacts facts; final Predicate<Ticket> current; final PairState state; final long deadline;
        final CompletableFuture<Result> result = new CompletableFuture<>();
        Stop timeout; boolean finished, pending, released; int calls, reservedInput;
        Operation(Ticket ticket, ResponseFacts facts, Predicate<Ticket> current, PairState state, long deadline) {
            this.ticket = ticket; this.facts = facts; this.current = current; this.state = state; this.deadline = deadline;
            result.whenComplete((value, error) -> { if (result.isCancelled()) synchronized (GLOBAL) { finish(Reason.CANCELLED, null); } });
        }
        boolean current() { return ticket.equals(state.latest) && ConversationProvider.current(current, ticket); }
        void dispatch() {
            try { invocations.execute(this::invoke); }
            catch (RuntimeException failure) { finish(Reason.PROVIDER_FAILURE, null); }
        }
        void invoke() {
            LlmProvider.Request input;
            synchronized (GLOBAL) {
                if (finished) { release(); return; }
                if (!current()) { finish(Reason.STALE, null); return; }
                long now = clock.getAsLong();
                if (now < state.lastSeen) { finish(Reason.CLOCK, null); return; }
                if (now >= deadline) { finish(Reason.TIMEOUT, null); return; }
                input = request(facts, calls != 0);
                if (calls >= 2 || inputReservation(input) > 4096) { finish(Reason.INPUT_BUDGET, null); return; }
                pending = true;
            }
            try {
                long inputTokens = tokenBudget.countInputTokens().applyAsLong(input);
                synchronized (GLOBAL) {
                    if (finished) { pending = false; release(); return; }
                    if (!current()) { pending = false; finish(Reason.STALE, null); return; }
                    long countedAt = clock.getAsLong();
                    if (countedAt < state.lastSeen) { pending = false; finish(Reason.CLOCK, null); return; }
                    if (countedAt >= deadline) { pending = false; finish(Reason.TIMEOUT, null); return; }
                    if (inputTokens < 1) { pending = false; finish(Reason.TOKENIZER_UNAVAILABLE, null); return; }
                    if (inputTokens > ATTEMPT_INPUT || inputTokens > MAX_INPUT_TOKENS - reservedInput) { pending = false; finish(Reason.INPUT_BUDGET, null); return; }
                    reservedInput += (int) inputTokens; calls++;
                }
                CompletableFuture<LlmProvider.Response> response = Objects.requireNonNull(backend.generate(input));
                response.whenComplete(this::complete);
            } catch (RuntimeException failure) { complete(null, failure); }
        }
        void complete(LlmProvider.Response response, Throwable error) {
            synchronized (GLOBAL) {
                pending = false;
                if (finished) { release(); return; }
                if (!current()) { finish(Reason.STALE, null); return; }
                long now = clock.getAsLong();
                if (now < state.lastSeen) { finish(Reason.CLOCK, null); return; }
                if (now >= deadline) { finish(Reason.TIMEOUT, null); return; }
                if (error != null || response == null) { finish(Reason.PROVIDER_FAILURE, null); return; }
                var text = new ResponseValidator().validate(facts, response.text());
                if (text.isPresent()) { finish(Reason.MODEL, text.get()); return; }
                if (calls == 1) dispatch(); else finish(Reason.INVALID_RESPONSE, null);
            }
        }
        void finish(Reason reason, String text) {
            if (finished) return;
            finished = true;
            boolean deliverable = reason != Reason.CANCELLED && reason != Reason.STALE && reason != Reason.CLOCK && current();
            if (!deliverable && reason != Reason.CANCELLED && reason != Reason.CLOCK) reason = Reason.STALE;
            if (timeout != null) try { timeout.cancel(); } catch (RuntimeException ignored) { }
            if (state.operation == this) state.operation = null;
            if (!pending) release();
            result.complete(new Result(deliverable ? text == null ? ResponseTemplates.fallback(facts) : text : "", reason, deliverable,
                    calls, reservedInput, calls * ATTEMPT_OUTPUT, null, null, false));
        }
        void release() { if (!released) { released = true; GLOBAL.outstanding--; } }
    }
}
