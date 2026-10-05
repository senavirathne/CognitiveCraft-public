package dev.aivillages.core.kernel;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immutable finite allowances and runtime-owned, checked cumulative usage. */
public final class Budgets {
    private Budgets() { }

    public enum Kind {
        CALLS, REPAIRS, INPUT_BYTES, OUTPUT_BYTES, CANDIDATES, TRIALS,
        INSTRUCTIONS, OBSERVATIONS, TRAVEL_BLOCKS, ATTEMPTED_EFFECTS, COMMITTED_EFFECTS,
        ELAPSED_TICKS
    }

    public record Limits(Map<Kind, Long> maxima, long deadlineEpochMillis) {
        public Limits {
            Objects.requireNonNull(maxima);
            EnumMap<Kind, Long> copy = new EnumMap<>(Kind.class);
            for (var entry : maxima.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0)
                    throw new IllegalArgumentException("Finite nonnegative limits required");
                copy.put(entry.getKey(), entry.getValue());
            }
            if (copy.isEmpty() || deadlineEpochMillis <= 0) throw new IllegalArgumentException("Deadline");
            maxima = Map.copyOf(copy);
        }
        public long maximum(Kind kind) { return maxima.getOrDefault(kind, 0L); }
    }
    public record InferenceLimits(Limits total, long perCallInputBytes,
                                  long perResponseOutputBytes) {
        public InferenceLimits {
            Objects.requireNonNull(total);
            if (perCallInputBytes <= 0 || perResponseOutputBytes <= 0
                    || perCallInputBytes > total.maximum(Kind.INPUT_BYTES)
                    || perResponseOutputBytes > total.maximum(Kind.OUTPUT_BYTES)
                    || total.maximum(Kind.REPAIRS) > total.maximum(Kind.CALLS))
                throw new IllegalArgumentException("Inference envelope");
        }
        public ResponseAllowance chargeCall(Ledger usage, long inputBytes, boolean repair) {
            if (inputBytes < 0 || inputBytes > perCallInputBytes) throw new Exhausted();
            EnumMap<Kind, Long> delta = new EnumMap<>(Kind.class);
            delta.put(Kind.CALLS, 1L);
            delta.put(Kind.INPUT_BYTES, inputBytes);
            if (repair) delta.put(Kind.REPAIRS, 1L);
            usage.debit(delta);
            return new ResponseAllowance(usage, perResponseOutputBytes);
        }
    }
    /** One response stream. Each chunk debits the parent; chunks cannot reset the response cap. */
    public static final class ResponseAllowance {
        private final Ledger usage;
        private final long maximum;
        private long bytes;
        private boolean closed;

        private ResponseAllowance(Ledger usage, long maximum) {
            this.usage = usage;
            this.maximum = maximum;
        }
        public synchronized void accept(long chunkBytes) {
            if (closed || chunkBytes < 0) throw new Exhausted();
            long next;
            try { next = Math.addExact(bytes, chunkBytes); }
            catch (ArithmeticException overflow) { throw new Exhausted(); }
            if (next > maximum) throw new Exhausted();
            usage.debit(Kind.OUTPUT_BYTES, chunkBytes);
            bytes = next;
        }
        public synchronized void close() { closed = true; }
        public synchronized long consumed() { return bytes; }
    }
    public record ExecutionLimits(Limits total, long actionDeadlineMillis) {
        public ExecutionLimits {
            Objects.requireNonNull(total);
            if (actionDeadlineMillis <= 0) throw new IllegalArgumentException("Action deadline");
        }
    }
    public record ResearchLimits(InferenceLimits inference, ExecutionLimits trial,
                                 Limits total) {
        public ResearchLimits {
            Objects.requireNonNull(inference);
            Objects.requireNonNull(trial);
            Objects.requireNonNull(total);
            if (inference.total().deadlineEpochMillis() > total.deadlineEpochMillis()
                    || trial.total().deadlineEpochMillis() > total.deadlineEpochMillis())
                throw new IllegalArgumentException("Child deadline widens parent");
            for (var entry : inference.total().maxima().entrySet()) {
                if (entry.getValue() > total.maximum(entry.getKey()))
                    throw new IllegalArgumentException("Inference widens research");
            }
            for (var entry : trial.total().maxima().entrySet()) {
                if (entry.getValue() > total.maximum(entry.getKey()))
                    throw new IllegalArgumentException("Trial widens research");
            }
        }
    }
    public static final class Exhausted extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        public Exhausted() { super(Outcomes.Reason.BUDGET_EXHAUSTED.name()); }
    }

    /** A child debits itself and every ancestor atomically under the root's monitor. */
    public static final class Ledger {
        private final Limits limits;
        private final Clock clock;
        private final Ledger parent;
        private final Object monitor;
        private final EnumMap<Kind, Long> usage = new EnumMap<>(Kind.class);

        public Ledger(Limits limits, Clock clock) {
            this(limits, clock, null);
        }
        private Ledger(Limits limits, Clock clock, Ledger parent) {
            this.limits = Objects.requireNonNull(limits);
            this.clock = Objects.requireNonNull(clock);
            this.parent = parent;
            this.monitor = parent == null ? new Object() : parent.monitor;
            if (parent != null && limits.deadlineEpochMillis() > parent.limits.deadlineEpochMillis())
                throw new IllegalArgumentException("Deadline widens parent");
        }
        public Ledger child(Limits allowance) {
            synchronized (monitor) {
                for (Kind kind : Kind.values()) {
                    long remaining = limits.maximum(kind) - usage.getOrDefault(kind, 0L);
                    if (allowance.maximum(kind) > remaining) throw new Exhausted();
                }
                return new Ledger(allowance, clock, this);
            }
        }
        public Map<Kind, Long> snapshot() {
            synchronized (monitor) { return Map.copyOf(usage); }
        }
        /** Read-only preflight, not a reservation. Serialize competing effects at the gateway. */
        public boolean canDebit(Kind kind, long amount) {
            Objects.requireNonNull(kind);
            if (amount < 0) throw new IllegalArgumentException("Negative amount");
            synchronized (monitor) {
                for (Ledger cursor = this; cursor != null; cursor = cursor.parent) {
                    if (clock.millis() > cursor.limits.deadlineEpochMillis()) return false;
                    long current = cursor.usage.getOrDefault(kind, 0L);
                    if (amount > cursor.limits.maximum(kind) - current) return false;
                }
                return true;
            }
        }
        public void debit(Kind kind, long amount) { debit(Map.of(kind, amount)); }
        public void debit(Map<Kind, Long> delta) {
            Objects.requireNonNull(delta);
            synchronized (monitor) {
                for (Ledger cursor = this; cursor != null; cursor = cursor.parent) {
                    if (clock.millis() > cursor.limits.deadlineEpochMillis()) throw new Exhausted();
                    for (var entry : delta.entrySet()) {
                        Kind kind = Objects.requireNonNull(entry.getKey());
                        Long amount = Objects.requireNonNull(entry.getValue());
                        if (amount < 0) throw new IllegalArgumentException("Negative debit");
                        long current = cursor.usage.getOrDefault(kind, 0L);
                        long next;
                        try { next = Math.addExact(current, amount); }
                        catch (ArithmeticException exception) { throw new Exhausted(); }
                        if (next > cursor.limits.maximum(kind)) throw new Exhausted();
                    }
                }
                for (Ledger cursor = this; cursor != null; cursor = cursor.parent)
                    for (var entry : delta.entrySet())
                        cursor.usage.merge(entry.getKey(), entry.getValue(), Math::addExact);
            }
        }
    }
}
