package dev.aivillages.core.kernel;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import java.util.IdentityHashMap;

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
        // Short accounting transactions only: no callback, transport or storage under this lock.
        // A shared call can touch independent roots atomically, without lock-order deadlocks.
        private static final Object ACCOUNTING = new Object();
        private final Limits limits;
        private final Clock clock;
        private final Ledger parent;
        private final List<Ledger> beneficiaries;
        private final Object monitor;
        private final EnumMap<Kind, Long> usage = new EnumMap<>(Kind.class);
        private long reservedOutput;
        private Reservation outputReservation;

        public Ledger(Limits limits, Clock clock) {
            this(limits, clock, null);
        }
        private Ledger(Limits limits, Clock clock, Ledger parent) {
            this(limits, clock, parent, List.of());
        }
        private Ledger(Limits limits, Clock clock, Ledger parent, List<Ledger> beneficiaries) {
            this.limits = Objects.requireNonNull(limits);
            this.clock = Objects.requireNonNull(clock);
            this.parent = parent;
            this.beneficiaries = List.copyOf(beneficiaries);
            this.monitor = ACCOUNTING;
            if (parent != null && limits.deadlineEpochMillis() > parent.limits.deadlineEpochMillis())
                throw new IllegalArgumentException("Deadline widens parent");
        }
        /** IMP-014: one physical debit, once per distinct beneficiary and shared ancestor.
         * Cohorts are frozen before transport starts. Cancellation does not refund performed work. */
        public static Ledger shared(Limits limits, Clock clock, List<Ledger> beneficiaries) {
            if (beneficiaries.isEmpty() || beneficiaries.size() > 9)
                throw new IllegalArgumentException("Shared accounting cohort");
            for (Ledger beneficiary : beneficiaries) {
                if (limits.deadlineEpochMillis() > beneficiary.deadline())
                    throw new IllegalArgumentException("Shared deadline widens beneficiary");
                for (Kind kind : Kind.values())
                    if (limits.maximum(kind) > beneficiary.remaining(kind)) throw new Exhausted();
            }
            return new Ledger(limits, clock, null, beneficiaries);
        }
        private List<Ledger> lineage() {
            var result = new ArrayList<Ledger>();
            var seen = new IdentityHashMap<Ledger, Boolean>();
            collect(this, result, seen);
            return result;
        }
        private static void collect(Ledger ledger, List<Ledger> result,
                                    IdentityHashMap<Ledger, Boolean> seen) {
            if (seen.put(ledger, Boolean.TRUE) != null) return;
            if (result.size() == 64) throw new IllegalArgumentException("Accounting graph exceeds 64 nodes");
            result.add(ledger);
            if (ledger.parent != null) collect(ledger.parent, result, seen);
            for (Ledger beneficiary : ledger.beneficiaries) collect(beneficiary, result, seen);
        }
        public long deadline() {
            synchronized (monitor) {
                return lineage().stream().mapToLong(l -> l.limits.deadlineEpochMillis()).min().orElseThrow();
            }
        }
        public long remaining(Kind kind) {
            synchronized (monitor) {
                return lineage().stream().mapToLong(l -> l.limits.maximum(kind)
                        - l.usage.getOrDefault(kind, 0L) - (kind == Kind.OUTPUT_BYTES ? l.reservedOutput : 0)).min().orElseThrow();
            }
        }
        /** Reserve one frozen shared response before preparation. Actual chunks consume this hold.
         * Unknown abandoned output forfeits its remaining hold; proven unused output releases it. */
        public Reservation reserveOutput(long amount) {
            synchronized (monitor) {
                if (beneficiaries.isEmpty() || outputReservation != null || amount <= 0
                        || !canDebit(Kind.OUTPUT_BYTES, amount)) throw new Exhausted();
                List<Ledger> charged = lineage();
                charged.forEach(l -> l.reservedOutput += amount);
                return outputReservation = new Reservation(charged, amount);
            }
        }
        public final class Reservation {
            private final List<Ledger> charged;
            private long remaining;
            private Reservation(List<Ledger> charged, long remaining) { this.charged = charged; this.remaining = remaining; }
            public long remaining() { synchronized (monitor) { return remaining; } }
            public void release() { finish(false); }
            /** Already reserved work is accounted even when its subscriber deadline has elapsed. */
            public long forfeit() { return finish(true); }
            private long finish(boolean consumed) {
                synchronized (monitor) {
                    long amount = remaining;
                    for (Ledger ledger : charged) {
                        ledger.reservedOutput -= amount;
                        if (consumed) ledger.usage.merge(Kind.OUTPUT_BYTES, amount, Math::addExact);
                    }
                    remaining = 0; return amount;
                }
            }
        }
        public Ledger child(Limits allowance) {
            synchronized (monitor) {
                for (Kind kind : Kind.values()) {
                    long remaining = limits.maximum(kind) - usage.getOrDefault(kind, 0L)
                            - (kind == Kind.OUTPUT_BYTES ? reservedOutput : 0);
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
                for (Ledger cursor : lineage()) {
                    if (clock.millis() > cursor.limits.deadlineEpochMillis()) return false;
                    long current = cursor.usage.getOrDefault(kind, 0L);
                    long reserved = kind == Kind.OUTPUT_BYTES ? cursor.reservedOutput : 0;
                    long owned = kind == Kind.OUTPUT_BYTES && outputReservation != null ? outputReservation.remaining : 0;
                    if (amount > cursor.limits.maximum(kind) - current - reserved + owned) return false;
                }
                return true;
            }
        }
        public void debit(Kind kind, long amount) { debit(Map.of(kind, amount)); }
        public void debit(Map<Kind, Long> delta) {
            Objects.requireNonNull(delta);
            synchronized (monitor) {
                List<Ledger> charged = lineage();
                long output = delta.getOrDefault(Kind.OUTPUT_BYTES, 0L);
                long owned = outputReservation == null ? 0 : Math.min(output, outputReservation.remaining);
                for (Ledger cursor : charged) {
                    if (clock.millis() > cursor.limits.deadlineEpochMillis()) throw new Exhausted();
                    for (var entry : delta.entrySet()) {
                        Kind kind = Objects.requireNonNull(entry.getKey());
                        Long amount = Objects.requireNonNull(entry.getValue());
                        if (amount < 0) throw new IllegalArgumentException("Negative debit");
                        long current = cursor.usage.getOrDefault(kind, 0L);
                        long next;
                        try { next = Math.addExact(current, amount); }
                        catch (ArithmeticException exception) { throw new Exhausted(); }
                        long reserved = kind == Kind.OUTPUT_BYTES ? cursor.reservedOutput - owned : 0;
                        if (next > cursor.limits.maximum(kind) - reserved) throw new Exhausted();
                    }
                }
                for (Ledger cursor : charged)
                    for (var entry : delta.entrySet())
                        cursor.usage.merge(entry.getKey(), entry.getValue(), Math::addExact);
                if (owned > 0) {
                    for (Ledger cursor : charged) cursor.reservedOutput -= owned;
                    outputReservation.remaining -= owned;
                }
            }
        }
    }
}
