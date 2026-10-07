package dev.aivillages.fabric;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Finite, game-thread-only policy for one physical MOVE action. Minecraft owns the path. */
final class NavigationRoute {
    static final int STALL_TICKS = 20;
    // Euclidean distance in blocks, measured from the actor's continuous position to
    // Minecraft's entity-adjusted next waypoint. Sub-epsilon gains accumulate.
    static final double PROGRESS_EPSILON_BLOCKS = 0.05;
    private static final int HISTORY_LIMIT = 12;

    record Position(double x, double y, double z) {
        double distance(Position other) {
            double dx = x - other.x, dy = y - other.y, dz = z - other.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }
    record Approach(int x, int y, int z) {
        Position feet() { return new Position(x + 0.5, y, z + 0.5); }
    }
    record PathSample(Object identity, long geometry, int node, int count,
                      Position waypoint, Position actor) {
        PathSample {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(waypoint);
            Objects.requireNonNull(actor);
        }
        double distance() { return actor.distance(waypoint); }
    }
    enum Stage { SELECT_APPROACH, FOLLOW_PATH, TERMINAL }
    enum Follow { CONTINUE, ABANDON }

    private final List<Approach> remaining;
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private Stage stage = Stage.SELECT_APPROACH;
    private Approach approach;
    private Object path;
    private long geometry;
    private int highestNode = -1;
    private double bestDistance = Double.POSITIVE_INFINITY;
    private long lastProgress;
    private long attemptStart;
    private long lastSampleTick = Long.MIN_VALUE;
    private String lastReason = "new";
    private int attempts;
    private int navigationCalls;
    private int generation;
    private PathSample lastSample;

    NavigationRoute(List<Approach> candidates) {
        if (candidates.size() > 8 || candidates.size() != candidates.stream().distinct().count())
            throw new IllegalArgumentException("At most eight distinct approaches");
        remaining = new ArrayList<>(candidates);
        event("select " + remaining.size() + " samples");
    }
    Stage stage() { return stage; }
    boolean hasRemaining() { return !remaining.isEmpty(); }
    Approach approach() { return approach; }
    int attempts() { return attempts; }
    int navigationCalls() { return navigationCalls; }
    int generation() { return generation; }
    long attemptStart() { return attemptStart; }
    long lastProgress() { return lastProgress; }
    double bestDistance() { return bestDistance; }
    String lastReason() { return lastReason; }
    PathSample lastSample() { return lastSample; }
    List<String> history() { return List.copyOf(history); }

    Approach select(Position actor) {
        if (stage != Stage.SELECT_APPROACH) throw new IllegalStateException("Not selecting");
        if (remaining.isEmpty()) return null;
        // Re-rank only untried candidates from the actor's current position.
        remaining.sort(Comparator.comparingDouble((Approach p) -> p.feet().distance(actor))
                .thenComparingInt(Approach::x).thenComparingInt(Approach::y)
                .thenComparingInt(Approach::z));
        approach = remaining.removeFirst();
        attempts++;
        event("start " + approach + " attempt=" + attempts);
        return approach;
    }
    void chargedPathfinding() { navigationCalls++; }
    void note(String result) { event(result); }

    void accepted(PathSample sample, long tick) {
        if (stage != Stage.SELECT_APPROACH || approach == null)
            throw new IllegalStateException("No selected approach");
        stage = Stage.FOLLOW_PATH;
        generation++;
        path = sample.identity();
        geometry = sample.geometry();
        highestNode = sample.node();
        bestDistance = sample.distance();
        lastSample = sample;
        attemptStart = lastProgress = lastSampleTick = tick;
        event("follow generation=" + generation + " node=" + highestNode
                + "/" + sample.count() + " waypoint=" + sample.waypoint());
    }

    Follow follow(PathSample sample, long tick) {
        if (stage != Stage.FOLLOW_PATH) throw new IllegalStateException("No active path");
        if (tick == lastSampleTick) return Follow.CONTINUE;
        lastSampleTick = tick;
        if (sample == null) return abandon("lost-path");
        lastSample = sample;
        if (path != sample.identity()) return abandon("replaced-path");
        if (geometry != sample.geometry()) return abandon("changed-geometry");
        if (sample.node() < highestNode) return abandon("regressed-node");
        if (sample.node() >= sample.count()) return abandon("finished-outside-reach");
        if (sample.node() > highestNode) {
            highestNode = sample.node();
            bestDistance = sample.distance();
            lastProgress = tick;
            event("node=" + highestNode + " generation=" + generation);
        } else if (sample.distance() < bestDistance - PROGRESS_EPSILON_BLOCKS) {
            bestDistance = sample.distance();
            lastProgress = tick;
            event("waypoint-gain node=" + highestNode + " distance=" + bestDistance);
        }
        return tick - lastProgress >= STALL_TICKS ? abandon("stalled") : Follow.CONTINUE;
    }

    Follow abandon(String reason) {
        lastReason = reason;
        stage = Stage.SELECT_APPROACH;
        path = null;
        event("abandon " + reason + " approach=" + approach);
        approach = null;
        return Follow.ABANDON;
    }
    void terminal(String reason) {
        lastReason = reason;
        stage = Stage.TERMINAL;
        event("terminal " + reason + " attempts=" + attempts + " calls=" + navigationCalls);
    }
    private void event(String value) {
        if (history.size() == HISTORY_LIMIT) history.removeFirst();
        history.addLast(value);
    }
}
