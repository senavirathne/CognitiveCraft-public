package dev.aivillages.core.kernel;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static dev.aivillages.core.kernel.Outcomes.Reason;

/**
 * Ephemeral deterministic world-reference selection. Discovery strategies observe candidates;
 * this owner accounts work, waits for a complete bounded scan, orders candidates and selects one.
 */
public final class WorldReferenceResolver {
    private WorldReferenceResolver() { }

    public enum Kind { CITIZEN, AREA, CONTAINER, FACILITY }
    public enum Relation { NEAREST }
    public enum ReferenceState {
        OMITTED, EXPLICIT_NEAREST, EXPLICIT_CONCRETE, EXPLICIT_UNSUPPORTED, AMBIGUOUS, INVALID
    }

    /** Input provenance is retained until a concrete reference has been selected and validated. */
    public record Reference<T>(ReferenceState state, T concrete, String evidence) {
        public Reference {
            Objects.requireNonNull(state);
            if (state == ReferenceState.EXPLICIT_CONCRETE && concrete == null)
                throw new IllegalArgumentException("Concrete reference required");
            if (state != ReferenceState.EXPLICIT_CONCRETE && concrete != null)
                throw new IllegalArgumentException("Only concrete references carry values");
            if (evidence != null && evidence.length() > 256)
                throw new IllegalArgumentException("Reference evidence limit");
        }
        public static <T> Reference<T> omitted() {
            return new Reference<>(ReferenceState.OMITTED, null, null);
        }
        public static <T> Reference<T> nearest(String evidence) {
            return new Reference<>(ReferenceState.EXPLICIT_NEAREST, null, evidence);
        }
        public static <T> Reference<T> concrete(T value, String evidence) {
            return new Reference<>(ReferenceState.EXPLICIT_CONCRETE, value, evidence);
        }
        public static <T> Reference<T> unsupported(String evidence) {
            return new Reference<>(ReferenceState.EXPLICIT_UNSUPPORTED, null, evidence);
        }
    }

    public record Point(String dimension, int x, int y, int z) {
        public Point {
            if (dimension == null || dimension.isBlank() || dimension.length() > 128)
                throw new IllegalArgumentException("Reference dimension");
        }
    }

    /** Strategy IDs are registered Java policy names, never model-authored predicates. */
    public record Criteria(String id) {
        public Criteria {
            if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_/.-]{1,96}"))
                throw new IllegalArgumentException("Reference criteria");
        }
    }

    public record Limits(int workPerPoll, long totalWork, int maxCandidates,
                         int maxDiagnostics, long deadlineMillis) {
        public Limits {
            if (workPerPoll < 1 || workPerPoll > 4096 || totalWork < workPerPoll
                    || totalWork > 1_000_000 || maxCandidates < 1 || maxCandidates > 4096
                    || maxDiagnostics < 1 || maxDiagnostics > 128 || deadlineMillis < 1)
                throw new IllegalArgumentException("Reference limits");
        }
    }

    public record Search(Kind kind, Relation relation, Point anchor, Criteria criteria, Limits limits) {
        public Search {
            Objects.requireNonNull(kind); Objects.requireNonNull(relation);
            Objects.requireNonNull(anchor); Objects.requireNonNull(criteria); Objects.requireNonNull(limits);
        }
    }

    public record Candidate<T>(T value, Point rankPoint, String stableIdentity) {
        public Candidate {
            Objects.requireNonNull(value); Objects.requireNonNull(rankPoint);
            if (stableIdentity == null || stableIdentity.isBlank() || stableIdentity.length() > 128)
                throw new IllegalArgumentException("Stable candidate identity");
        }
    }

    /**
     * One bounded discovery slice. chargedWork includes all nested observation, neighbour,
     * inventory-slot and candidate work performed by the strategy.
     */
    public record Scan<T>(List<Candidate<T>> candidates, int chargedWork, boolean complete,
                          Reason emptyReason, List<String> diagnostics) {
        public Scan {
            candidates = List.copyOf(candidates);
            diagnostics = List.copyOf(diagnostics);
            if (chargedWork < 0 || candidates.size() > 4096 || diagnostics.size() > 128)
                throw new IllegalArgumentException("Reference scan bounds");
            if (!complete && emptyReason != null)
                throw new IllegalArgumentException("Incomplete scan cannot claim a terminal reason");
        }
        public static <T> Scan<T> progress(List<Candidate<T>> candidates, int chargedWork,
                                           List<String> diagnostics) {
            return new Scan<>(candidates, chargedWork, false, null, diagnostics);
        }
        public static <T> Scan<T> complete(List<Candidate<T>> candidates, int chargedWork,
                                           Reason emptyReason, List<String> diagnostics) {
            return new Scan<>(candidates, chargedWork, true, emptyReason, diagnostics);
        }
    }

    public interface Discovery<T> {
        Scan<T> poll(int maxWork);
        void cancel();
    }

    @FunctionalInterface
    public interface Strategy<T> {
        Discovery<T> start(Search search);
    }

    public enum Phase { RESOLVING, RESOLVED, FAILED, CANCELLED }

    public record Result<T>(Phase phase, T value, Reason reason, long chargedWork,
                            List<String> diagnostics) {
        public Result {
            Objects.requireNonNull(phase);
            diagnostics = List.copyOf(diagnostics);
            if (chargedWork < 0 || diagnostics.size() > 128)
                throw new IllegalArgumentException("Reference result bounds");
            if (phase == Phase.RESOLVED ? value == null || reason != null
                    : phase == Phase.FAILED ? value != null || reason == null
                    : value != null || reason != null)
                throw new IllegalArgumentException("Reference result shape");
        }
    }

    /** Cumulative parent allowance shared by dependent searches and final revalidation. */
    public static final class Allowance {
        private final long limit;
        private long used;
        public Allowance(long limit) {
            if (limit < 1 || limit > 1_000_000) throw new IllegalArgumentException("Work allowance");
            this.limit = limit;
        }
        public long remaining() { return limit - used; }
        public long used() { return used; }
        public void debit(int work) {
            if (work < 0 || work > remaining()) throw new IllegalStateException("Work exhausted");
            used += work;
        }
    }

    private record Key(Kind kind, Criteria criteria) { }

    /** Small registration point for concrete Java discovery strategies. */
    public static final class Registry {
        private final Map<Key, Strategy<?>> strategies = new HashMap<>();

        public <T> void register(Kind kind, Criteria criteria, Strategy<T> strategy) {
            Objects.requireNonNull(kind); Objects.requireNonNull(criteria); Objects.requireNonNull(strategy);
            if (strategies.size() >= 64) throw new IllegalStateException("Reference registry limit");
            if (strategies.putIfAbsent(new Key(kind, criteria), strategy) != null)
                throw new IllegalStateException("Reference strategy already registered");
        }

        @SuppressWarnings("unchecked")
        public <T> Attempt<T> start(Search search, long nowMillis) {
            return start(search, nowMillis, new Allowance(search.limits().totalWork()));
        }

        @SuppressWarnings("unchecked")
        public <T> Attempt<T> start(Search search, long nowMillis, Allowance allowance) {
            Strategy<T> strategy = (Strategy<T>) strategies.get(new Key(search.kind(), search.criteria()));
            if (strategy == null)
                return Attempt.failed(search, Reason.TARGET_UNAVAILABLE);
            return new Attempt<>(search, Objects.requireNonNull(strategy.start(search)), nowMillis, allowance);
        }
    }

    public static final class Attempt<T> {
        private final Search search;
        private Discovery<T> discovery;
        private Map<String, Candidate<T>> candidates = new LinkedHashMap<>();
        private final List<String> diagnostics = new ArrayList<>();
        private final Allowance allowance;
        private Scan<T> buffered;
        private int candidateCursor, diagnosticCursor;
        private Candidate<T> best;
        private long work;
        private boolean terminal;
        private Result<T> result;

        private Attempt(Search search, Discovery<T> discovery, long nowMillis, Allowance allowance) {
            this.search = Objects.requireNonNull(search);
            this.discovery = Objects.requireNonNull(discovery);
            this.allowance = Objects.requireNonNull(allowance);
            if (nowMillis > search.limits().deadlineMillis())
                finishFailed(Reason.BUDGET_EXHAUSTED);
            else result = new Result<>(Phase.RESOLVING, null, null, 0, List.of());
        }

        private static <T> Attempt<T> failed(Search search, Reason reason) {
            Attempt<T> attempt = new Attempt<>(search, new Discovery<>() {
                @Override public Scan<T> poll(int maxWork) {
                    return Scan.complete(List.of(), 0, reason, List.of());
                }
                @Override public void cancel() { }
            }, Math.min(search.limits().deadlineMillis(), Long.MAX_VALUE), new Allowance(search.limits().totalWork()));
            attempt.finishFailed(reason);
            return attempt;
        }

        public Result<T> poll(long nowMillis) {
            return poll(nowMillis,search.limits().workPerPoll());
        }

        /** A parent may reserve work for orchestration in this same tick. */
        public Result<T> poll(long nowMillis, int workThisPoll) {
            if (workThisPoll < 1 || workThisPoll > search.limits().workPerPoll())
                throw new IllegalArgumentException("Reference slice allowance");
            if (terminal) return result;
            if (nowMillis > search.limits().deadlineMillis()) return exhausted();
            int slice = (int)Math.min(workThisPoll,
                    Math.min(search.limits().totalWork() - work, allowance.remaining()));
            if (slice <= 0) return exhausted();
            int remaining = slice;
            if (buffered == null) {
                try { buffered = Objects.requireNonNull(discovery.poll(remaining)); }
                catch (RuntimeException failure) {
                    cancelDiscovery(); finishFailed(Reason.STALE_OBSERVATION); return result;
                }
                if (buffered.chargedWork() > remaining) return exhausted();
                charge(buffered.chargedWork());
                remaining -= buffered.chargedWork();
                candidateCursor = diagnosticCursor = 0;
                if (buffered.chargedWork() == 0 && !buffered.complete()) return exhausted();
            }
            while (remaining > 0 && diagnosticCursor < buffered.diagnostics().size()) {
                String diagnostic = buffered.diagnostics().get(diagnosticCursor++);
                charge(1); remaining--;
                appendDiagnostics(List.of(diagnostic));
            }
            while (remaining > 0 && diagnosticCursor == buffered.diagnostics().size()
                    && candidateCursor < buffered.candidates().size()) {
                Candidate<T> candidate = buffered.candidates().get(candidateCursor++);
                charge(1); remaining--;
                if (!candidate.rankPoint().dimension().equals(search.anchor().dimension())) {
                    cancelDiscovery(); finishFailed(Reason.REQUEST_INVALID); return result;
                }
                Candidate<T> prior = candidates.get(candidate.stableIdentity());
                if (prior != null && !prior.equals(candidate)) {
                    cancelDiscovery(); finishFailed(Reason.STALE_OBSERVATION); return result;
                }
                if (prior == null) {
                    if (candidates.size() >= search.limits().maxCandidates()) return exhausted();
                    candidates.put(candidate.stableIdentity(), candidate);
                    if (best == null || WorldReferenceResolver.<T>candidateOrder(search.anchor()).compare(candidate, best) < 0)
                        best = candidate;
                }
            }
            if (diagnosticCursor < buffered.diagnostics().size()
                    || candidateCursor < buffered.candidates().size())
                return progressOrExhausted();
            boolean complete = buffered.complete();
            Reason emptyReason = buffered.emptyReason();
            buffered = null;
            if (!complete) return progressOrExhausted();
            if (emptyReason == Reason.BUDGET_EXHAUSTED || emptyReason == Reason.STALE_OBSERVATION
                    || emptyReason == Reason.AUTHORITY_DENIED || emptyReason == Reason.REQUEST_INVALID) {
                finishFailed(emptyReason); return result;
            }
            if (best == null) {
                finishFailed(Optional.ofNullable(emptyReason).orElse(Reason.TARGET_UNAVAILABLE));
                return result;
            }
            terminal = true;
            result = new Result<>(Phase.RESOLVED, best.value(), null, work, diagnostics);
            candidates = Map.of(); best = null;
            cancelDiscovery();
            return result;
        }

        private void charge(int units) { allowance.debit(units); work += units; }
        private Result<T> exhausted() {
            cancelDiscovery(); finishFailed(Reason.BUDGET_EXHAUSTED); return result;
        }
        private Result<T> progressOrExhausted() {
            if (work >= search.limits().totalWork() || allowance.remaining() <= 0) return exhausted();
            return result = new Result<>(Phase.RESOLVING, null, null, work, diagnostics);
        }

        public Result<T> cancel() {
            if (terminal) return result;
            cancelDiscovery();
            terminal = true;
            result = new Result<>(Phase.CANCELLED, null, null, work, diagnostics);
            return result;
        }

        public Result<T> result() { return result; }

        private void appendDiagnostics(List<String> added) {
            for (String diagnostic : added) {
                if (diagnostic == null || diagnostic.isBlank()) continue;
                if (diagnostic.length() > 256) diagnostic = diagnostic.substring(0, 256);
                if (diagnostics.size() < search.limits().maxDiagnostics()) diagnostics.add(diagnostic);
            }
        }

        private void cancelDiscovery() {
            Discovery<T> ending = discovery;
            discovery = null;
            if (ending != null) try { ending.cancel(); } catch (RuntimeException ignored) { }
        }

        private void finishFailed(Reason reason) {
            cancelDiscovery();
            buffered = null; candidates = Map.of(); best = null;
            terminal = true;
            result = new Result<>(Phase.FAILED, null, Objects.requireNonNull(reason), work, diagnostics);
        }
    }

    static <T> Comparator<Candidate<T>> candidateOrder(Point anchor) {
        return Comparator.<Candidate<T>, BigInteger>comparing(candidate -> squaredDistance(anchor, candidate.rankPoint()))
                .thenComparingInt(candidate -> candidate.rankPoint().x())
                .thenComparingInt(candidate -> candidate.rankPoint().y())
                .thenComparingInt(candidate -> candidate.rankPoint().z())
                .thenComparing(Candidate::stableIdentity);
    }

    static BigInteger squaredDistance(Point a, Point b) {
        if (!a.dimension().equals(b.dimension())) throw new IllegalArgumentException("Distance dimension");
        long dx = (long)a.x() - b.x(), dy = (long)a.y() - b.y(), dz = (long)a.z() - b.z();
        return BigInteger.valueOf(dx).pow(2).add(BigInteger.valueOf(dy).pow(2))
                .add(BigInteger.valueOf(dz).pow(2));
    }
}
