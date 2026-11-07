package dev.aivillages.core.kernel.runtime.lifecycle;

import dev.aivillages.core.kernel.CitizenLifecycle.*;
import dev.aivillages.core.kernel.Contracts.ActorRef;
import java.util.*;

/** Optional process-local diagnostics. Never supplies lifecycle or custody authority. */
public final class LifecycleTrace {
    public static final int MAX_ACTORS = 64;
    public static final int MAX_ROWS_PER_ACTOR = 128;
    public enum Phase { FACT, STOP_REQUESTED, OWNER_NOTIFIED, PUBLICATION_REQUESTED, PUBLISHED }
    public record Row(long sequence, long observedTick, Phase phase, Marker marker,
                      Fact fact, Decision decision, Boolean result) { }
    public record View(boolean enabled, UUID process, ActorRef actor, int capacity,
                       long firstSequence, long nextSequence, long discarded, List<Row> rows) {
        public View { rows = List.copyOf(rows); }
    }
    private static final class Trail {
        long next;
        final ArrayDeque<Row> rows = new ArrayDeque<>();
    }
    private final int capacity;
    private final UUID process = UUID.randomUUID();
    private final Map<ActorRef, Trail> trails = new HashMap<>();
    private final Thread thread = Thread.currentThread();

    public LifecycleTrace(int capacity) {
        if (capacity < 0 || capacity > MAX_ROWS_PER_ACTOR)
            throw new IllegalArgumentException("Bounded lifecycle diagnostic capacity");
        this.capacity = capacity;
    }
    public void record(ActorRef actor, long tick, Phase phase, Marker marker,
                       Fact fact, Decision decision, Boolean result) {
        checkThread();
        if (capacity == 0) return;
        // Ordinary per-tick successful lookup duplicates add no new event order.
        if (phase == Phase.FACT && decision == Decision.DUPLICATE
                && fact != null && fact.source() == Source.LOADED_LOOKUP) return;
        Trail trail = trails.get(actor);
        if (trail == null) {
            if (trails.size() == MAX_ACTORS) return;
            trail = new Trail(); trails.put(actor, trail);
        }
        if (trail.next == Long.MAX_VALUE) return;
        if (trail.rows.size() == capacity) trail.rows.removeFirst();
        trail.rows.addLast(new Row(trail.next++, tick, phase, marker, fact, decision, result));
    }
    /** Caller authorization belongs to CitizenLifecycle before selecting the exact actor. */
    public View view(ActorRef actor) {
        checkThread();
        Trail trail = trails.get(actor);
        long next = trail == null ? 0 : trail.next;
        var rows = trail == null ? List.<Row>of() : List.copyOf(trail.rows);
        long first = rows.isEmpty() ? next : rows.getFirst().sequence();
        return new View(capacity != 0, process, actor, capacity, first, next, first, rows);
    }
    private void checkThread() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Server thread required");
    }
}
