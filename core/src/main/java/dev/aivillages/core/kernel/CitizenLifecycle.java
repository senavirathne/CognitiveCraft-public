package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.CitizenRegistry.*;

/** IMP-029. Game-thread policy/coordination; all durable writes belong to CitizenRegistry. */
public final class CitizenLifecycle {
    public static final String ARCHITECTURE_VERSION = "0.6";
    public static final int ALL_ACKNOWLEDGED = 7;
    public enum Event { VERIFY, LOAD, UNLOAD, DEATH, REMOVE, MISSING, REPLACEMENT, DIMENSION }
    public enum Source { RESTART, ENTITY_LOAD, LOADED_LOOKUP, CHUNK_UNLOAD, AFTER_DEATH,
        KILLED, DISCARDED, UNKNOWN_REMOVAL, MISSING_LOOKUP, UNSUPPORTED_REPLACEMENT, DIMENSION_CHANGE }

    /** One fixed-size evidence/checkpoint per immutable binding, not an event history. */
    public record Marker(long generation, Event event, Source source, long tick, int acknowledged) {
        public Marker {
            Objects.requireNonNull(event); Objects.requireNonNull(source);
            if (generation < 0 || tick < 0 || acknowledged != 0 && acknowledged != 1
                    && acknowledged != 3 && acknowledged != 7 || !validSource(event, source)
                    || event == Event.LOAD && acknowledged != ALL_ACKNOWLEDGED)
                throw new IllegalArgumentException("Lifecycle evidence/checkpoint");
        }
        public static Marker initial() { return new Marker(0, Event.VERIFY, Source.RESTART, 0, 7); }
        public boolean terminal() { return event == Event.DEATH || event == Event.REMOVE; }
        public boolean blocked() { return terminal() || event == Event.REPLACEMENT || event == Event.DIMENSION; }
        public boolean pending() { return acknowledged != ALL_ACKNOWLEDGED; }
        public Marker acknowledge() { return new Marker(generation, event, source, tick,
                acknowledged == 0 ? 1 : acknowledged == 1 ? 3 : 7); }
        public Availability availability() {
            return switch (event) {
                case LOAD -> Availability.LOADED;
                case UNLOAD -> Availability.UNLOADED;
                case DEATH, REMOVE -> Availability.UNAVAILABLE;
                case REPLACEMENT, DIMENSION -> Availability.REPLACEMENT_UNRESOLVED;
                case VERIFY, MISSING -> Availability.UNKNOWN;
            };
        }
    }
    public record Fact(UUID world, ActorRef actor, long generation, Event event, Source source, long tick) {
        public Fact {
            Objects.requireNonNull(world); Objects.requireNonNull(actor);
            // Reuse the exact evidence validation, including the finite enum vocabulary.
            new Marker(generation, event, source, tick, event == Event.LOAD ? 7 : 0);
        }
    }
    public enum Decision { ACCEPTED, DUPLICATE, DEFERRED, STALE, FOREIGN, TERMINAL }
    public record Settings(int batch, int lookups, int notificationRetries) {
        public Settings {
            if (batch < 1 || batch > 8 || lookups < 1 || lookups > 8
                    || notificationRetries < 1 || notificationRetries > 4096)
                throw new IllegalArgumentException("Finite lifecycle limits");
        }
        public static Settings production() { return new Settings(4, 1, 1200); }
    }
    public interface Owners {
        boolean stop(Citizen citizen, Marker loss);
        boolean jobs(Citizen citizen, Marker loss);
        boolean leases(Citizen citizen, Marker loss);
    }
    public record Counters(int queued, int inspected, int notifications, int exhausted, long accepted, long stale) { }

    private final CitizenRegistry identities;
    private final Owners owners;
    private final Settings settings;
    private final Thread thread = Thread.currentThread();
    // At most one desired marker and retry counter for each of the owner's <=64 citizens.
    private final Map<UUID, Marker> desired = new LinkedHashMap<>();
    private final Map<UUID, Integer> retries = new HashMap<>();
    private int cursor, inspected, notifications;
    private long accepted, stale;

    public CitizenLifecycle(CitizenRegistry identities, Owners owners, Settings settings, long tick) {
        this.identities = Objects.requireNonNull(identities); this.owners = Objects.requireNonNull(owners);
        this.settings = Objects.requireNonNull(settings);
        for (Citizen c : identities.snapshot().citizens()) {
            Marker saved = c.lifecycle();
            identities.fenceAvailability(c.actor().citizenId(), saved.event() == Event.LOAD
                    ? Availability.UNKNOWN : saved.availability());
            if (saved.pending() || saved.blocked()) continue;
            // A saved observation is not a live actor or a stopped-work acknowledgement.
            desired.put(c.actor().citizenId(), new Marker(saved.generation(), Event.VERIFY, Source.RESTART, tick, 0));
        }
    }
    public Settings settings() { return settings; }
    public Marker marker(UUID id) {
        checkThread(); Citizen c = citizen(id);
        return c == null ? null : desired.getOrDefault(id, c.lifecycle());
    }
    /** Trusted server facts only. There is deliberately no command/network deserializer for Fact. */
    public Decision offer(Fact fact) {
        checkThread(); Objects.requireNonNull(fact);
        Citizen c = citizen(fact.actor().citizenId());
        if (!fact.world().equals(identities.snapshot().worldId()) || c == null || !c.actor().equals(fact.actor()))
            return Decision.FOREIGN;
        Marker old = marker(c.actor().citizenId());
        if (fact.generation() != old.generation()) { stale++; return Decision.STALE; }
        if (fact.tick() < old.tick() && fact.event() != Event.DEATH && fact.event() != Event.REMOVE) {
            stale++; return Decision.STALE;
        }
        if (old.terminal() && !(old.event() == Event.REMOVE && fact.event() == Event.DEATH))
            return fact.event() == old.event() ? Decision.DUPLICATE : Decision.TERMINAL;
        if (fact.event() == Event.LOAD) {
            if (old.blocked()) return Decision.TERMINAL;
            if (old.pending() || desired.containsKey(c.actor().citizenId())) return Decision.DEFERRED;
            if (old.event() == Event.LOAD) return Decision.DUPLICATE;
            if (old.generation() == Long.MAX_VALUE) return Decision.TERMINAL;
        } else if (old.blocked() && fact.event() != Event.DEATH) return Decision.DUPLICATE;
        else if (old.event() == fact.event()) return Decision.DUPLICATE;
        // An uncertain missing lookup must not erase an authoritative unload observation.
        else if (fact.event() == Event.MISSING && old.event() != Event.LOAD) return Decision.DUPLICATE;
        int ack = fact.event() == Event.LOAD ? 7 : old.event() == Event.LOAD ? 0 : old.acknowledged();
        Marker next = new Marker(old.generation() + (fact.event() == Event.LOAD ? 1 : 0),
                fact.event(), fact.source(), fact.tick(), ack);
        desired.put(c.actor().citizenId(), next); retries.remove(c.actor().citizenId()); accepted++;
        // Loss is visible synchronously, even while an earlier store write is in flight.
        if (fact.event() != Event.LOAD) {
            identities.fenceAvailability(c.actor().citizenId(), next.availability());
            // Cessation must not wait behind an unrelated or stalled identity publication.
            // This notification grants no acknowledgement until its checkpoint is durable.
            try { owners.stop(c, next); } catch (RuntimeException unavailable) { /* Retry the durable obligation. */ }
        }
        return Decision.ACCEPTED;
    }

    /** At most batch citizens, batch notifications and one asynchronous publication per slice. */
    public void step() {
        checkThread(); inspected = 0; notifications = 0;
        List<Citizen> rows = identities.snapshot().citizens();
        for (int n = 0; n < Math.min(settings.batch(), rows.size()); n++) {
            Citizen c = rows.get(cursor % rows.size()); cursor = (cursor + 1) % rows.size(); inspected++;
            UUID id = c.actor().citizenId(); Marker next = desired.get(id);
            if (next != null) {
                if (next.equals(c.lifecycle())) {
                    desired.remove(id);
                    identities.fenceAvailability(id, next.availability());
                } else if (identities.ready()) {
                    identities.reconcileLifecycle(id, next); return;
                }
                continue;
            }
            Marker loss = c.lifecycle();
            if (!loss.pending() || !identities.ready()
                    || retries.getOrDefault(id, 0) >= settings.notificationRetries()) continue;
            notifications++; retries.merge(id, 1, Integer::sum);
            boolean acknowledged;
            try { acknowledged = switch (loss.acknowledged()) {
                case 0 -> owners.stop(c, loss);
                case 1 -> owners.jobs(c, loss);
                case 3 -> owners.leases(c, loss);
                default -> throw new IllegalStateException("Lifecycle checkpoint");
            }; } catch (RuntimeException unavailable) { acknowledged = false; }
            if (acknowledged) {
                retries.remove(id); desired.put(id, loss.acknowledge());
                identities.reconcileLifecycle(id, loss.acknowledge()); return;
            }
        }
        if (cursor > MAX_CITIZENS * 1024) cursor %= Math.max(1, rows.size());
    }
    public Counters counters() {
        checkThread(); return new Counters(desired.size(), inspected, notifications,
                (int)retries.values().stream().filter(v -> v >= settings.notificationRetries()).count(), accepted, stale);
    }
    private Citizen citizen(UUID id) {
        return identities.snapshot().citizens().stream().filter(c -> c.actor().citizenId().equals(id)).findFirst().orElse(null);
    }
    private void checkThread() { if (Thread.currentThread() != thread) throw new IllegalStateException("Server thread required"); }
    private static boolean validSource(Event event, Source source) {
        return switch (event) {
            case VERIFY -> source == Source.RESTART;
            case LOAD -> source == Source.ENTITY_LOAD || source == Source.LOADED_LOOKUP;
            case UNLOAD -> source == Source.CHUNK_UNLOAD;
            case DEATH -> source == Source.AFTER_DEATH || source == Source.KILLED;
            case REMOVE -> source == Source.DISCARDED;
            case MISSING -> source == Source.UNKNOWN_REMOVAL || source == Source.MISSING_LOOKUP;
            case REPLACEMENT -> source == Source.UNSUPPORTED_REPLACEMENT;
            case DIMENSION -> source == Source.DIMENSION_CHANGE;
        };
    }
}
