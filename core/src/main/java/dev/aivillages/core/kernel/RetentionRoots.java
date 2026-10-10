package dev.aivillages.core.kernel;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Nonblocking reference publication. A source must publish successfully before using new refs. */
public final class RetentionRoots {
    public static final int MAX_PROVIDERS = 16, MAX_ROOTS = 4096;
    public enum Update { APPLIED, UNCHANGED, BUSY, STALE, LIMIT }
    public record Source(long revision, Set<WorldRetentionManager.Key> roots, boolean complete) {
        public Source {
            if (revision < 0 || roots.size() > MAX_ROOTS) throw new IllegalArgumentException("Root bounds");
            roots = Set.copyOf(roots);
        }
    }
    public record Snapshot(long generation, Map<String, Source> sources) {
        public Snapshot { sources = Map.copyOf(sources); }
        public boolean complete() { return sources.values().stream().allMatch(Source::complete); }
        public Set<WorldRetentionManager.Key> roots() {
            var result = new HashSet<WorldRetentionManager.Key>();
            sources.values().forEach(s -> result.addAll(s.roots()));
            return Set.copyOf(result);
        }
    }
    private record State(Snapshot snapshot, Object lease) { }
    private final AtomicReference<State> state = new AtomicReference<>(new State(new Snapshot(0, Map.of()), null));

    public Snapshot snapshot() { return state.get().snapshot(); }
    public Update publishNext(String id, Set<WorldRetentionManager.Key> roots, boolean complete) {
        Source old = snapshot().sources().get(id);
        if (old != null && old.roots().equals(roots) && old.complete() == complete) return Update.UNCHANGED;
        if (old != null && old.revision() == Long.MAX_VALUE) return Update.LIMIT;
        return publish(id, new Source(old == null ? 0 : old.revision() + 1, roots, complete));
    }
    public Update publish(String id, Source next) {
        if (id == null || !id.matches("[a-z][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("Root owner");
        Objects.requireNonNull(next);
        // A finite CAS budget: the game thread never waits on a deletion or storage monitor.
        for (int attempt = 0; attempt < 8; attempt++) {
            State prior = state.get();
            Source old = prior.snapshot().sources().get(id);
            if (old != null && next.revision() < old.revision()) return Update.STALE;
            if (next.equals(old)) return Update.UNCHANGED;
            if (old != null && next.revision() == old.revision()) return Update.STALE;
            if (prior.lease() != null) return Update.BUSY;
            var changed = new HashMap<>(prior.snapshot().sources()); changed.put(id, next);
            long count = changed.values().stream().mapToLong(s -> s.roots().size()).sum();
            if (changed.size() > MAX_PROVIDERS || count > MAX_ROOTS
                    || prior.snapshot().generation() == Long.MAX_VALUE) return Update.LIMIT;
            State replacement = new State(new Snapshot(prior.snapshot().generation() + 1, changed), null);
            if (state.compareAndSet(prior, replacement)) return Update.APPLIED;
        }
        return Update.BUSY;
    }
    /** Trusted manager seam. The token excludes reference changes until the owner's delete returns. */
    Object beginDelete(long generation) {
        State prior = state.get();
        if (prior.lease() != null || prior.snapshot().generation() != generation || !prior.snapshot().complete()) return null;
        Object token = new Object();
        return state.compareAndSet(prior, new State(prior.snapshot(), token)) ? token : null;
    }
    void endDelete(Object token) {
        State prior = state.get();
        if (prior.lease() != token || !state.compareAndSet(prior, new State(prior.snapshot(), null)))
            throw new IllegalStateException("Reference lease ownership");
    }
}
