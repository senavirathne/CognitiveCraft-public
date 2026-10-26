package dev.aivillages.core.kernel.runtime;

import dev.aivillages.core.kernel.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Jobs.*;

/** Bounded, disposable Jobs client. No activity store, claims ledger, executor or scheduler. */
public final class RecoveredJobOwners implements AutoCloseable {
    public enum Route { NONE, LIVE_BOOTSTRAP, LIVE_DISPATCHER, RECOVERY, UNRESOLVED }
    public enum Presence { AVAILABLE, WAIT_WORLD, UNAVAILABLE }
    public enum Blocker { NONE, WAIT_WORLD, NEEDS_ATTENTION, PARTIAL_CUSTODY, OWNERSHIP_CONFLICT, STORAGE_UNAVAILABLE }
    public interface LiveOwners {
        boolean bootstrap(Job job);
        boolean dispatcher(Job job);
        Presence presence(Job job);
    }
    public record View(UUID root, String goal, UUID authorizingPrincipal, ActorRef responsible,
                       long executionGeneration, UUID attempt, State state, Route route, UUID recoveryOwner,
                       UUID process, Blocker blocker, Outcomes.Reason jobReason, boolean cancellationIntent, Long verifiedLegacyQuantity,
                       String progressEvidence, long revision, String physicalEvidence) { }
    public record Page(List<View> activities, int offset, int nextOffset, boolean more) {
        public Page { activities = List.copyOf(activities); }
    }
    public record Counters(int inspected, int cached, long visits, UUID process) { }
    private final JobLifecycleStore jobs;
    private final LiveOwners live;
    private final RecoveryEvidenceAuthority authority;
    private final int perTick;
    private final Thread thread = Thread.currentThread();
    private final Map<UUID,View> projections = new LinkedHashMap<>();
    private Snapshot indexed;
    private int cursor, inspected;
    private long visits;
    private boolean closed;

    public RecoveredJobOwners(JobLifecycleStore jobs, LiveOwners live, TrustedStoppedSource nativeSource, int perTick) {
        this.jobs = Objects.requireNonNull(jobs); this.live = Objects.requireNonNull(live);
        if (perTick < 1 || perTick > 4) throw new IllegalArgumentException("Recovery slice 1..4");
        this.perTick = perTick;
        authority = jobs.attachRecovery(Objects.requireNonNull(nativeSource));
    }
    private void check() { if (Thread.currentThread() != thread) throw new IllegalStateException("Server thread required"); }
    public UUID process() { check(); return authority.process(); }
    private Route route(Job job) {
        boolean bootstrap = live.bootstrap(job), dispatch = live.dispatcher(job);
        if (bootstrap && dispatch) return Route.UNRESOLVED;
        if (bootstrap) return Route.LIVE_BOOTSTRAP;
        if (dispatch) return Route.LIVE_DISPATCHER;
        return job.current() != null && job.current().open() ? Route.RECOVERY : Route.NONE;
    }
    private UUID owner(Job job) {
        return UUID.nameUUIDFromBytes(("runtime-a:recovery:" + job.origin().scope().worldId() + ":"
                + job.id() + ":" + job.current().id() + ":" + job.generation()).getBytes(StandardCharsets.UTF_8));
    }
    private Presence presence(Job job) {
        try { return Objects.requireNonNull(live.presence(job)); }
        catch (RuntimeException unavailableAuthority) { return Presence.UNAVAILABLE; }
    }
    private View projection(Job job, StoppedObservation observed) {
        Route route = route(job); Blocker blocker = Blocker.NONE;
        String physical = "not recorded";
        if (route == Route.UNRESOLVED) blocker = Blocker.OWNERSHIP_CONFLICT;
        else if (route == Route.RECOVERY) {
            Presence present = presence(job);
            blocker = present == Presence.WAIT_WORLD ? Blocker.WAIT_WORLD : Blocker.NEEDS_ATTENTION;
            physical = "ambiguous; no save-confirmed effect proof";
            if (observed != null && observed.kind() == StoppedKind.PARTIAL) blocker = Blocker.PARTIAL_CUSTODY;
            if (observed != null && observed.kind() == StoppedKind.WAIT_WORLD) blocker = Blocker.WAIT_WORLD;
            if (!jobs.ready()) blocker = Blocker.STORAGE_UNAVAILABLE;
        }
        boolean recorded = job.attempts().stream().anyMatch(a -> a.receiptCount() > 0);
        var a = job.current();
        return new View(job.id(), job.request().request().capability().name(), job.origin().principal().id(),
                a == null ? job.responsible() : a.worker(), job.generation(), a == null ? null : a.id(),
                job.state(), route, route == Route.RECOVERY ? owner(job) : null,
                authority.process(), blocker, job.reason(), job.cancellationOutcome() != null || job.state() == State.CANCELLING,
                recorded ? job.fulfilled() : null, recorded ? "legacy receipt credit; current availability unknown" : "unknown/not recorded",
                job.revision(), physical);
    }
    public void step() {
        check(); inspected = 0;
        if (closed) return;
        Snapshot snapshot = jobs.snapshot();
        if (indexed != snapshot) { projections.clear(); indexed = snapshot; }
        var rows = snapshot.jobs();
        for (int n = 0; n < Math.min(perTick, rows.size()); n++) {
            cursor %= rows.size(); Job job = rows.get(cursor++); inspected++; visits++;
            StoppedObservation observation = null;
            if (route(job) == Route.RECOVERY && presence(job) == Presence.AVAILABLE) {
                try {
                    var evidence = authority.capture(StoppedContext.of(job)); observation = evidence.observation();
                    if (jobs.ready() && (observation.kind() == StoppedKind.NO_EFFECT || observation.kind() == StoppedKind.EFFECT_COMPLETE)) {
                        var changed = jobs.reconcileStopped(job.id(), job.guard(), evidence, job.origin());
                        if (changed.accepted()) { projections.put(job.id(), projection(job, observation)); return; }
                    }
                } catch (RuntimeException invalidHostEvidence) {
                    observation = StoppedObservation.ambiguous("host evidence unavailable or conflicting");
                }
            }
            projections.put(job.id(), projection(job, observation));
        }
    }
    /** Fresh owner/authority projection; cache invalidation cannot expose a stale writer or private row. */
    public View view(UUID root, TrustedContext caller) {
        check(); Job job = jobs.query(root, caller);
        View old = projections.get(root);
        var fresh = projection(job, null);
        if (old != null && old.revision() == job.revision() && old.route() == fresh.route()
                && old.blocker() == Blocker.PARTIAL_CUSTODY && fresh.blocker() == Blocker.NEEDS_ATTENTION)
            return old;
        return fresh;
    }
    public Page page(TrustedContext caller, int offset, int maximum) {
        check();
        if (caller == null || !caller.scope().worldId().equals(jobs.snapshot().worldId())
                || offset < 0 || offset > jobs.settings().total() || maximum < 1 || maximum > 4)
            throw new IllegalArgumentException("Private activity page bounds");
        var own = jobs.snapshot().jobs().stream().filter(j -> j.origin().equals(caller))
                .sorted(Comparator.comparing(j -> j.id().toString())).toList();
        var result = new ArrayList<View>();
        int end = Math.min(own.size(), offset + maximum);
        for (int at = offset; at < end; at++) result.add(view(own.get(at).id(), caller));
        return new Page(result, offset, end, end < own.size());
    }
    /** Derived quarantine for unresolved old effects, including when scheduling leases expire. */
    public boolean fenced(ValidatedRequest incoming, Effect effect, SurvivalGateway.Cell target) {
        check();
        if (effect == Effect.OBSERVE || effect == Effect.MOVE) return false;
        for (Job job : jobs.snapshot().jobs()) {
            var a = job.current();
            if (a == null || !a.open() || route(job) != Route.RECOVERY && route(job) != Route.UNRESOLVED) continue;
            if (a.worker().entityId().equals(((ActorValue)incoming.request().arguments().get("actor")).value().entityId())) return true;
            for (Value value : a.bound().request().arguments().values()) {
                if (value instanceof AreaValue area) {
                    var r = area.value();
                    if (r.dimension().equals(target.dimension()) && target.x() >= r.minX() && target.x() <= r.maxX()
                            && target.y() >= r.minY() && target.y() <= r.maxY() && target.z() >= r.minZ() && target.z() <= r.maxZ()) return true;
                } else if (value instanceof ContainerValue container) {
                    var p = container.value();
                    // A double chest can be addressed through either half. Without
                    // an incarnation protocol, conservatively include face neighbors.
                    if (p.dimension().equals(target.dimension()) && p.y() == target.y()
                            && Math.abs((long)p.x()-target.x()) + Math.abs((long)p.z()-target.z()) <= 1) return true;
                } else if (value instanceof FacilityValue facility) {
                    var p = facility.value();
                    if (p.dimension().equals(target.dimension()) && p.x() == target.x() && p.y() == target.y() && p.z() == target.z()) return true;
                }
            }
        }
        return false;
    }
    public Counters counters() { check(); return new Counters(inspected, projections.size(), visits, authority.process()); }
    public void close() { check(); closed = true; projections.clear(); }
}
