package dev.aivillages.core.kernel;

import java.text.Normalizer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;

/** Game-thread identity owner. Publication uses an off-thread store; views contain no entities. */
public final class CitizenRegistry implements BootstrapController.Identity {
    public static final int MAX_CITIZENS = 64;
    public static final int MAX_RESULTS = 16;
    public static final int MAX_NAME_CODE_POINTS = 32;
    public static final int MAX_NAME_BYTES = 128;
    public static final long MAX_PUBLICATION_WAIT_MILLIS = 30_000;

    /** Unload and unsupported replacement never imply death or a new entity binding. */
    public enum Availability { UNKNOWN, LOADED, UNLOADED, UNAVAILABLE, REPLACEMENT_UNRESOLVED }

    /** Names are values. Identity, control and the bound entity are immutable. No aliases are retained. */
    public record Citizen(ActorRef actor, TrustedContext owner, String displayName,
                          Availability availability) {
        public Citizen {
            Objects.requireNonNull(actor); Objects.requireNonNull(owner);
            Objects.requireNonNull(availability);
            if (displayName != null && !normalizeName(displayName).equals(displayName))
                throw new IllegalArgumentException("Noncanonical citizen name");
        }
    }

    /** One bounded receipt for the schema-1 bootstrap import, including an empty enrollment. */
    public record Migration(long sourceRevision, String sourceSha256, UUID citizenId) {
        public Migration {
            if (sourceRevision < 0 || sourceSha256 == null
                    || !sourceSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Bootstrap migration receipt");
        }
    }

    /** Authoritative metadata snapshot; only trusted runtime owners consume this unfiltered form. */
    public record Snapshot(UUID worldId, long revision, List<Citizen> citizens, Migration migration) {
        public Snapshot {
            Objects.requireNonNull(worldId); Objects.requireNonNull(citizens);
            if (revision < 0 || citizens.size() > MAX_CITIZENS)
                throw new IllegalArgumentException("Citizen registry bounds");
            citizens = citizens.stream().sorted(Comparator.comparing(
                    c -> c.actor().citizenId().toString())).toList();
            var ids = new HashSet<UUID>();
            var entities = new HashSet<UUID>();
            for (Citizen citizen : citizens) {
                if (!ids.add(citizen.actor().citizenId()) || !entities.add(citizen.actor().entityId())
                        || !citizen.owner().scope().worldId().equals(worldId))
                    throw new IllegalArgumentException("Duplicate or foreign citizen binding");
            }
            if (migration != null && migration.citizenId() != null
                    && !ids.contains(migration.citizenId()))
                throw new IllegalArgumentException("Missing migrated citizen");
        }
    }

    @FunctionalInterface public interface Storage {
        CompletionStage<Snapshot> replace(Snapshot expected, Snapshot next);
    }
    /** An explicit addressing grant reveals this citizen's public address, never control or history. */
    @FunctionalInterface public interface AddressPolicy {
        boolean mayAddress(TrustedContext caller, Citizen citizen);
    }
    public static AddressPolicy privateAddresses() { return (caller, citizen) -> false; }

    /** Safe for input adapters: no controlling principal, private record or live world object. */
    public record Address(ActorRef actor, String displayName, Availability availability) { }
    public enum AddressStatus { FOUND, AMBIGUOUS, NOT_FOUND, UNAVAILABLE }
    public record Addresses(AddressStatus status, List<Address> candidates, boolean more) {
        public Addresses { candidates = List.copyOf(candidates); }
    }
    public record Page(List<Address> citizens, boolean more) {
        public Page { citizens = List.copyOf(citizens); }
    }
    public record Change(Citizen citizen, Reason reason, boolean pending) {
        public Change {
            if ((citizen == null) == (reason == null)) throw new IllegalArgumentException();
        }
        public boolean accepted() { return citizen != null; }
    }
    private record Written(Snapshot state, Throwable error, long completedMillis) { }

    private final Storage storage;
    private final AddressPolicy addressing;
    private final Clock clock;
    private final Thread gameThread;
    private final boolean readOnly;
    private final ConcurrentLinkedQueue<Written> writes = new ConcurrentLinkedQueue<>();
    private final Map<UUID, Availability> observed = new HashMap<>();
    private Snapshot state, pending;
    private long deadline;
    private boolean fenced;

    public CitizenRegistry(Snapshot initial, Storage storage, AddressPolicy addressing,
                           Clock clock, boolean readOnly) {
        state = Objects.requireNonNull(initial); this.storage = Objects.requireNonNull(storage);
        this.addressing = Objects.requireNonNull(addressing); this.clock = Objects.requireNonNull(clock);
        this.readOnly = readOnly; gameThread = Thread.currentThread();
        for (Citizen citizen : initial.citizens())
            observed.put(citizen.actor().citizenId(), citizen.availability() == Availability.LOADED
                    ? Availability.UNKNOWN : citizen.availability());
    }

    public Snapshot snapshot() { thread(); return state; }
    public boolean ready() { thread(); return !readOnly && !fenced && pending == null; }
    public Reason unavailableReason() {
        thread(); return readOnly || fenced ? Reason.STORAGE_UNAVAILABLE
                : pending != null ? Reason.BUDGET_EXHAUSTED : null;
    }

    @Override public BootstrapJournal.Enrollment find(UUID citizenId) {
        thread(); Citizen citizen = byId(citizenId);
        return citizen == null ? null : new BootstrapJournal.Enrollment(citizen.actor(), citizen.owner());
    }
    @Override public BootstrapJournal.Enrollment primary() {
        thread();
        UUID migrated = state.migration() == null ? null : state.migration().citizenId();
        return migrated != null ? find(migrated) : state.citizens().isEmpty() ? null
                : find(state.citizens().getFirst().actor().citizenId());
    }

    /** Used by the trusted gateway policy; naming and addressing grants cannot satisfy this check. */
    public boolean controls(ActorRef actor, TrustedContext caller) {
        thread(); Citizen citizen = byId(actor.citizenId());
        return !fenced && !readOnly && citizen != null && citizen.actor().equals(actor)
                && citizen.owner().equals(caller);
    }

    public Change enroll(UUID entityId, String dimension, TrustedContext caller) {
        thread();
        Reason unavailable = unavailableReason();
        if (unavailable != null) return rejected(unavailable);
        if (!state.worldId().equals(caller.scope().worldId())) return rejected(Reason.AUTHORITY_DENIED);
        for (Citizen citizen : state.citizens())
            if (citizen.actor().entityId().equals(entityId))
                return citizen.owner().equals(caller) && citizen.actor().dimension().equals(dimension)
                        ? new Change(citizen, null, false) : rejected(Reason.AUTHORITY_DENIED);
        if (state.citizens().size() == MAX_CITIZENS) return rejected(Reason.STORAGE_LIMIT_REACHED);
        Citizen citizen;
        try { citizen = new Citizen(new ActorRef(UUID.randomUUID(), entityId, dimension),
                caller, null, Availability.UNKNOWN); }
        catch (IllegalArgumentException | NullPointerException invalid) { return rejected(Reason.REQUEST_INVALID); }
        var rows = new ArrayList<>(state.citizens()); rows.add(citizen);
        return publish(rows, citizen);
    }

    public Change rename(UUID citizenId, String name, TrustedContext caller) {
        thread(); Citizen citizen = byId(citizenId);
        if (citizen == null || !citizen.owner().equals(caller)) return rejected(Reason.AUTHORITY_DENIED);
        String normalized;
        try { normalized = normalizeName(name); }
        catch (IllegalArgumentException invalid) { return rejected(Reason.REQUEST_INVALID); }
        Reason unavailable = unavailableReason();
        if (unavailable != null) return rejected(unavailable);
        if (normalized.equals(citizen.displayName())) return new Change(current(citizen), null, false);
        Citizen renamed = new Citizen(citizen.actor(), citizen.owner(), normalized, citizen.availability());
        return publish(replacing(renamed), renamed);
    }

    /** Runtime facts only. Missing entities must be reported as unknown, not inferred dead. */
    public Change observeAvailability(UUID citizenId, Availability availability) {
        thread(); Objects.requireNonNull(availability);
        Citizen citizen = byId(citizenId);
        if (citizen == null) return rejected(Reason.ACTOR_UNAVAILABLE);
        observed.put(citizenId, availability);
        Reason unavailable = unavailableReason();
        if (unavailable != null) return rejected(unavailable);
        if (citizen.availability() == availability) return new Change(current(citizen), null, false);
        Citizen updated = new Citizen(citizen.actor(), citizen.owner(), citizen.displayName(), availability);
        return publish(replacing(updated), updated);
    }

    /** Private metadata is returned only to its controlling principal and scope. */
    public Citizen query(UUID citizenId, TrustedContext caller) {
        thread(); Citizen citizen = byId(citizenId);
        if (citizen == null || !citizen.owner().equals(caller))
            throw new SecurityException("Citizen unavailable in your control scope");
        return current(citizen);
    }

    public Page citizens(TrustedContext caller) {
        thread(); var found = new ArrayList<Address>(); boolean more = false;
        for (Citizen citizen : state.citizens()) if (visible(caller, citizen)) {
            if (found.size() == MAX_RESULTS) { more = true; break; }
            found.add(address(citizen));
        }
        return new Page(found, more);
    }

    /**
     * Private bounded enumeration for trusted deterministic selection. It exposes only citizens
     * controlled by the exact caller and is independent of the public addressing page size.
     */
    public List<Address> controlled(TrustedContext caller) {
        thread(); var found = new ArrayList<Address>();
        for (Citizen citizen : state.citizens())
            if (citizen.owner().equals(caller)) found.add(address(citizen));
        if (found.size() > MAX_CITIZENS) throw new IllegalStateException("Citizen population limit");
        return List.copyOf(found);
    }

    public Addresses address(String name, TrustedContext caller) {
        thread(); String key = normalizeName(name).toLowerCase(Locale.ROOT);
        var found = new ArrayList<Address>(); boolean more = false;
        for (Citizen citizen : state.citizens())
            if (citizen.displayName() != null && visible(caller, citizen)
                    && citizen.displayName().toLowerCase(Locale.ROOT).equals(key)) {
                if (found.size() == MAX_RESULTS) { more = true; break; }
                found.add(address(citizen));
            }
        return result(found, more);
    }

    /** An explicit ID disambiguates an address; it does not create a grant. */
    public Addresses address(UUID citizenId, TrustedContext caller) {
        thread(); Citizen citizen = byId(citizenId);
        return citizen != null && visible(caller, citizen)
                ? result(List.of(address(citizen)), false) : result(List.of(), false);
    }

    private Addresses result(List<Address> candidates, boolean more) {
        AddressStatus status = candidates.isEmpty() ? AddressStatus.NOT_FOUND
                : candidates.size() > 1 || more ? AddressStatus.AMBIGUOUS
                : candidates.getFirst().availability() == Availability.LOADED
                        ? AddressStatus.FOUND : AddressStatus.UNAVAILABLE;
        return new Addresses(status, candidates, more);
    }
    private boolean visible(TrustedContext caller, Citizen citizen) {
        return caller.scope().worldId().equals(state.worldId())
                && (citizen.owner().equals(caller) || addressing.mayAddress(caller, citizen));
    }
    private Citizen current(Citizen citizen) {
        return new Citizen(citizen.actor(), citizen.owner(), citizen.displayName(),
                observed.getOrDefault(citizen.actor().citizenId(), Availability.UNKNOWN));
    }
    private Address address(Citizen citizen) {
        var live = current(citizen);
        return new Address(live.actor(), live.displayName(), live.availability());
    }
    private Citizen byId(UUID id) {
        return state.citizens().stream().filter(c -> c.actor().citizenId().equals(id)).findFirst().orElse(null);
    }
    private List<Citizen> replacing(Citizen next) {
        return state.citizens().stream().map(c -> c.actor().citizenId().equals(next.actor().citizenId())
                ? next : c).toList();
    }
    private Change rejected(Reason reason) { return new Change(null, reason, false); }

    private Change publish(List<Citizen> rows, Citizen changed) {
        try {
            deadline = Math.addExact(clock.millis(), MAX_PUBLICATION_WAIT_MILLIS);
            pending = new Snapshot(state.worldId(), Math.addExact(state.revision(), 1), rows, state.migration());
            storage.replace(state, pending).whenComplete((written, error) ->
                    writes.add(new Written(written, error, clock.millis())));
            return new Change(changed, null, true);
        } catch (RuntimeException failure) {
            pending = null; fenced = true;
            return rejected(Reason.STORAGE_UNAVAILABLE);
        }
    }

    /** One completion per tick. A late worker commit cannot publish live control after expiry. */
    public void tick() {
        thread();
        if (fenced) { writes.clear(); return; }
        Written written = writes.poll();
        if (written == null) {
            if (pending != null && clock.millis() >= deadline) { fenced = true; pending = null; }
            return;
        }
        if (pending == null || written.error() != null || !pending.equals(written.state())
                || written.completedMillis() > deadline) {
            fenced = true; pending = null; return;
        }
        state = written.state(); pending = null;
        for (Citizen citizen : state.citizens())
            observed.putIfAbsent(citizen.actor().citizenId(), Availability.UNKNOWN);
    }

    /** NFC, trimmed/collapsed ASCII spaces; letters, marks, digits, space, dash and underscore only. */
    public static String normalizeName(String name) {
        if (name == null || name.length() > MAX_NAME_BYTES
                || name.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Name must contain 1 to 32 characters");
        String value = Normalizer.normalize(name.strip(), Normalizer.Form.NFC).replaceAll(" +", " ");
        if (value.isEmpty() || value.codePointCount(0, value.length()) > MAX_NAME_CODE_POINTS
                || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_NAME_BYTES)
            throw new IllegalArgumentException("Name must contain 1 to 32 characters");
        for (int at = 0; at < value.length();) {
            int cp = value.codePointAt(at); at += Character.charCount(cp);
            int type = Character.getType(cp);
            if (!Character.isLetterOrDigit(cp) && type != Character.NON_SPACING_MARK
                    && type != Character.COMBINING_SPACING_MARK && cp != ' ' && cp != '-' && cp != '_')
                throw new IllegalArgumentException("Name contains an unsupported character");
        }
        return value;
    }
    private void thread() {
        if (Thread.currentThread() != gameThread) throw new IllegalStateException("Server thread required");
    }
}
