package dev.aivillages.core.kernel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.function.BooleanSupplier;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.WorldRetentionManager.*;

/** Task/research evidence owner, schema 1. Summaries are advisory; no admission or permission writes. */
public final class RetentionEvidenceStore implements AutoCloseable {
    public static final int SCHEMA = 1, MAX_BYTES = 65536;
    public static final String WORLD_RELATIVE_PATH = "data/cognitivecraft/evidence/v1";
    public enum Source { TASK, RESEARCH }
    public enum Status { RECORDED, COMPACTED, CANCELLED, STORAGE_LIMIT_REACHED, STORAGE_UNAVAILABLE }
    public enum Point { BEFORE_STAGE, AFTER_STAGE, AFTER_BACKUP, BEFORE_REPLACE, AFTER_REPLACE }
    @FunctionalInterface public interface Faults { void at(Point point) throws IOException; static Faults none() { return p -> { }; } }
    public record Limits(int aggregates, int recent, int samples, long premiseMillis, long retentionMillis, int bytes) {
        public Limits {
            if (aggregates < 1 || aggregates > 64 || recent < 0 || recent > 32 || samples < 1 || samples > 4
                    || premiseMillis < 1 || premiseMillis > 300000 || retentionMillis < premiseMillis
                    || retentionMillis > 2_592_000_000L || bytes < 1024 || bytes > MAX_BYTES)
                throw new IllegalArgumentException("Evidence limits");
        }
        public static Limits defaults() { return new Limits(32, 16, 3, 30000, 604800000, MAX_BYTES); }
    }
    public record Key(TrustedContext owner, CapabilityId capability, ArtifactRef artifact, Outcomes.Reason reason, String premise) {
        public Key {
            Objects.requireNonNull(owner); Objects.requireNonNull(capability);
            if (premise == null || !premise.matches("[0-9a-f]{64}") || artifact != null && !artifact.capability().equals(capability))
                throw new IllegalArgumentException("Failure context");
        }
        String id() { return RepositoryCodec.digest(StrictJson.canonical(keyMap(this))); }
    }
    public record Event(UUID id, Source source, TrustedContext owner, CapabilityId capability, ArtifactRef artifact,
                        Outcomes.Reason reason, String premise, long at, long effects, long modelCalls) {
        public Event {
            Objects.requireNonNull(id); Objects.requireNonNull(source); Objects.requireNonNull(owner); Objects.requireNonNull(capability);
            if (at < 0 || effects < 0 || modelCalls < 0 || premise == null || !premise.matches("[0-9a-f]{64}")
                    || artifact != null && !artifact.capability().equals(capability)) throw new IllegalArgumentException("Typed trace");
        }
        Key key() { return reason == null ? null : new Key(owner, capability, artifact, reason, premise); }
    }
    public record Sample(UUID id, Source source, long at, long effects, long modelCalls) {
        public Sample {
            Objects.requireNonNull(id); Objects.requireNonNull(source);
            if (at < 0 || effects < 0 || modelCalls < 0) throw new IllegalArgumentException("Sample counters");
        }
    }
    public record Aggregate(Key key, long count, long first, long last, long premiseUntil, long retainUntil, List<Sample> samples) {
        public Aggregate {
            Objects.requireNonNull(key); Objects.requireNonNull(key.reason); samples = List.copyOf(samples);
            if (count < 1 || first < 0 || last < first || premiseUntil < last || retainUntil < premiseUntil
                    || samples.isEmpty() || samples.size() > 4 || samples.size() > count
                    || samples.stream().anyMatch(s -> s.at < first || s.at > last)
                    || samples.stream().map(Sample::id).distinct().count() != samples.size()) throw new IllegalArgumentException("Aggregate bounds");
        }
        /** This is a temporary matching premise, never a permanent skill defect or routing veto. */
        public boolean premiseApplies(String observed, long now) {
            return (key.reason == Outcomes.Reason.RESOURCE_MISSING || key.reason == Outcomes.Reason.FACILITY_MISSING)
                    && key.premise.equals(observed) && now >= last && now < premiseUntil;
        }
    }
    public record Snapshot(long revision, List<Aggregate> aggregates, List<Event> recent, long omitted) {
        public Snapshot {
            aggregates = List.copyOf(aggregates); recent = List.copyOf(recent);
            if (revision < 0 || omitted < 0 || aggregates.size() > 64 || recent.size() > 32
                    || aggregates.stream().map(a -> a.key.id()).distinct().count() != aggregates.size()
                    || recent.stream().map(Event::id).distinct().count() != recent.size()) throw new IllegalArgumentException("Evidence snapshot");
        }
    }
    public record Result(Status status, long revision, boolean publicationMayHaveSucceeded) { }
    private final Path directory, current, previous, staged;
    private final UUID world;
    private final Limits limits;
    private final Clock clock;
    private final Faults faults;
    private final FileChannel channel;
    private final FileLock lock;
    private volatile Snapshot state = new Snapshot(0, List.of(), List.of(), 0);
    private volatile boolean readOnly;
    private boolean closed;
    private Snapshot rollback = new Snapshot(0, List.of(), List.of(), 0);
    private java.util.function.Predicate<Set<WorldRetentionManager.Key>> referenceGuard = refs -> true;
    private java.util.function.Function<Set<WorldRetentionManager.Key>, WorldRetentionManager.DiscardApproval> discardGuard;
    public synchronized void referenceGuard(java.util.function.Predicate<Set<WorldRetentionManager.Key>> guard) {
        referenceGuard = Objects.requireNonNull(guard);
        if (!guard.test(referenceRoots(state, rollback))) throw new IllegalStateException("Evidence root publication unavailable");
    }
    private RetentionEvidenceStore(Path worldPath, UUID world, Limits limits, Clock clock, Faults faults) throws IOException {
        this.world = Objects.requireNonNull(world); this.limits = Objects.requireNonNull(limits);
        this.clock = Objects.requireNonNull(clock); this.faults = Objects.requireNonNull(faults);
        directory = worldPath.resolve(WORLD_RELATIVE_PATH); RetentionFileOwner.safePath(directory); Files.createDirectories(directory);
        current = directory.resolve("state.json"); previous = directory.resolve("state.prev.json"); staged = directory.resolve("state.next.json");
        for (Path path : List.of(current, previous, staged, directory.resolve("writer.lock"))) RetentionFileOwner.safePath(path);
        channel = FileChannel.open(directory.resolve("writer.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            try { acquired = channel.tryLock(); } catch (OverlappingFileLockException occupied) { throw new IOException("Evidence writer already open", occupied); }
            if (acquired == null) throw new IOException("Evidence writer already open");
            lock = acquired;
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try { state = read(current); } catch (IOException invalid) {
                    // Preserve unsupported/corrupt current bytes. A backup is a private read-only view.
                    readOnly = true; if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) try { state = read(previous); } catch (IOException ignored) { }
                }
            } else if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                state = read(previous); readOnly = true;
            }
            if (!readOnly && Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                try { rollback = read(previous); } catch (IOException invalid) { readOnly = true; }
            }
            if (!readOnly) Files.deleteIfExists(staged);
        } catch (IOException | RuntimeException failure) { if (acquired != null) acquired.release(); channel.close(); throw failure; }
    }
    public static RetentionEvidenceStore open(Path worldPath, UUID world, Limits limits, Clock clock, Faults faults) throws IOException {
        return new RetentionEvidenceStore(worldPath, world, limits, clock, faults);
    }
    public Snapshot snapshot() { return state; }
    public boolean readOnly() { return readOnly; }
    private static Set<WorldRetentionManager.Key> referenceRoots(Snapshot first, Snapshot second) {
        var result = new HashSet<WorldRetentionManager.Key>();
        for (Snapshot snapshot : List.of(first, second)) {
            for (Aggregate row : snapshot.aggregates) if (row.key.artifact != null) result.add(WorldRetentionManager.artifact(row.key.artifact));
            for (Event row : snapshot.recent) if (row.artifact != null) result.add(WorldRetentionManager.artifact(row.artifact));
        }
        return Set.copyOf(result);
    }
    public List<Aggregate> failures(TrustedContext caller) {
        Objects.requireNonNull(caller); return state.aggregates.stream().filter(a -> a.key.owner.equals(caller)).toList();
    }
    public List<Event> recent(TrustedContext caller) {
        Objects.requireNonNull(caller); return state.recent.stream().filter(e -> e.owner.equals(caller)).toList();
    }
    /** Hash only trusted finite facts and request context, excluding random observation ID and tick. */
    public static String premise(CapabilityRequest request, ObservationSnapshot observation) {
        return RepositoryCodec.digest(StrictJson.canonical(Map.of("request", RequestCodec.encode(request),
                "status", observation.status().name(), "target", observation.targetIdentity(), "counts", observation.counts())));
    }
    /** One durable bounded replacement. Queue/backpressure are provided by the session service. */
    public synchronized Result record(Event event, BooleanSupplier cancel) {
        return recordBatch(List.of(event), cancel);
    }
    public synchronized Result recordBatch(List<Event> events, BooleanSupplier cancel) {
        Objects.requireNonNull(cancel);
        if (events.isEmpty() || events.size() > 16) throw new IllegalArgumentException("Evidence batch");
        Snapshot next = state;
        try {
            for (Event event : events) {
                Objects.requireNonNull(event);
                if (!event.owner.scope().worldId().equals(world)) throw new SecurityException("Evidence world");
                if (next.recent.stream().noneMatch(e -> e.id.equals(event.id))) next = prepare(next, event);
            }
            if (next.equals(state)) return new Result(Status.RECORDED, state.revision, false);
            return commit(next, Status.RECORDED, cancel);
        } catch (ArithmeticException invalid) { return new Result(Status.STORAGE_LIMIT_REACHED, state.revision, false); }
    }
    private Snapshot prepare(Snapshot before, Event event) {
        long now = Math.max(event.at, Math.max(0, clock.millis()));
        var groups = new ArrayList<>(before.aggregates.stream().filter(a -> a.retainUntil > now).toList());
        var traces = new ArrayList<>(before.recent); long omitted = before.omitted;
        Key key = event.key();
        if (key != null) {
            Aggregate old = groups.stream().filter(a -> a.key.equals(key)).findFirst().orElse(null); groups.remove(old);
            if (old == null && groups.size() >= limits.aggregates) {
                groups.sort(Comparator.comparingLong(Aggregate::last).thenComparing(a -> a.key.id()));
                groups.removeFirst(); omitted = saturated(omitted, 1);
            }
            var samples = new ArrayList<>(old == null ? List.<Sample>of() : old.samples);
            Sample sample = new Sample(event.id, event.source, event.at, event.effects, event.modelCalls);
            if (samples.stream().noneMatch(s -> s.id.equals(sample.id))) {
                if (samples.size() >= limits.samples) samples.remove(samples.size() == 1 ? 0 : 1);
                samples.add(sample);
            }
            long first = old == null ? event.at : Math.min(old.first, event.at);
            long last = old == null ? event.at : Math.max(old.last, event.at);
            groups.add(new Aggregate(key, old == null ? 1 : saturated(old.count, 1), first, last,
                Math.addExact(last, limits.premiseMillis), Math.addExact(last, limits.retentionMillis), samples));
        }
        if (limits.recent > 0) {
            while (traces.size() >= limits.recent) traces.removeFirst(); traces.add(event);
        }
        groups.sort(Comparator.comparing(a -> a.key.id()));
        return new Snapshot(Math.addExact(before.revision, 1), groups, traces, omitted);
    }
    public synchronized Result compact(long expected, BooleanSupplier cancel) {
        if (state.revision != expected) return new Result(Status.STORAGE_UNAVAILABLE, state.revision, false);
        long now = Math.max(0, clock.millis());
        var groups = state.aggregates.stream().filter(a -> a.retainUntil > now).toList();
        var traces = state.recent.stream().filter(e -> e.at > now - limits.retentionMillis).toList();
        if (groups.equals(state.aggregates) && traces.equals(state.recent))
            return new Result(cancel.getAsBoolean() ? Status.CANCELLED : Status.COMPACTED, state.revision, false);
        try { return commit(new Snapshot(Math.addExact(expected, 1), groups, traces, state.omitted), Status.COMPACTED, cancel); }
        catch (ArithmeticException invalid) { return new Result(Status.STORAGE_LIMIT_REACHED, state.revision, false); }
    }
    private static final class Cancelled extends IOException { }
    private void checkpoint(BooleanSupplier cancel, Point point) throws IOException {
        if (cancel.getAsBoolean()) throw new Cancelled(); faults.at(point);
        if (cancel.getAsBoolean()) throw new Cancelled();
    }
    private Result commit(Snapshot next, Status success, BooleanSupplier cancel) {
        return commit(next, success, cancel, false);
    }
    private static Set<WorldRetentionManager.Key> recordKeys(Snapshot snapshot) {
        var result = new HashSet<WorldRetentionManager.Key>();
        snapshot.aggregates.forEach(a -> result.add(new WorldRetentionManager.Key("evidence", a.key.id())));
        snapshot.recent.forEach(e -> result.add(new WorldRetentionManager.Key("evidence", "trace:" + e.id)));
        return result;
    }
    private Result commit(Snapshot next, Status success, BooleanSupplier cancel, boolean managerApproved) {
        if (readOnly) return new Result(Status.STORAGE_UNAVAILABLE, state.revision, false);
        boolean replaced = false;
        WorldRetentionManager.DiscardLease lease = null;
        try {
            byte[] bytes = encode(next);
            if (bytes.length > limits.bytes) return new Result(Status.STORAGE_LIMIT_REACHED, state.revision, false);
            StrictJson.object(new String(bytes, StandardCharsets.UTF_8));
            if (!referenceGuard.test(referenceRoots(state, next))) return new Result(Status.STORAGE_UNAVAILABLE, state.revision, false);
            var removed = new HashSet<>(recordKeys(state)); removed.removeAll(recordKeys(next));
            if (!managerApproved && discardGuard != null && !removed.isEmpty()) {
                var approval = discardGuard.apply(Set.copyOf(removed)); lease = approval.lease();
                if (approval.status() != Capacity.AVAILABLE) return new Result(approval.status() == Capacity.STORAGE_LIMIT_REACHED
                        ? Status.STORAGE_LIMIT_REACHED : Status.STORAGE_UNAVAILABLE, state.revision, false);
            }
            checkpoint(cancel, Point.BEFORE_STAGE); write(staged, bytes); checkpoint(cancel, Point.AFTER_STAGE);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                RetentionFileOwner.attributes(current); Files.copy(current, previous, StandardCopyOption.REPLACE_EXISTING);
                try (var backup = FileChannel.open(previous, StandardOpenOption.WRITE)) { backup.force(true); }
            }
            checkpoint(cancel, Point.AFTER_BACKUP); checkpoint(cancel, Point.BEFORE_REPLACE);
            if (lease != null && !lease.valid()) return new Result(Status.STORAGE_UNAVAILABLE, state.revision, false);
            Files.move(staged, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); replaced = true;
            try (var dir = FileChannel.open(directory, StandardOpenOption.READ)) { dir.force(true); }
            rollback = state; state = next; faults.at(Point.AFTER_REPLACE);
            return new Result(success, state.revision, false);
        } catch (Cancelled cancelled) { return new Result(Status.CANCELLED, state.revision, replaced); }
        catch (StrictJson.Invalid quota) { return new Result(Status.STORAGE_LIMIT_REACHED, state.revision, false); }
        catch (IOException | RuntimeException failure) { readOnly = true; return new Result(Status.STORAGE_UNAVAILABLE, state.revision, replaced); }
        finally {
            try { Files.deleteIfExists(staged); } catch (IOException failure) { readOnly = true; }
            if (lease != null) lease.close();
        }
    }
    private byte[] encode(Snapshot snapshot) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("schema", (long) SCHEMA); payload.put("world", world.toString()); payload.put("revision", snapshot.revision);
        payload.put("omitted", snapshot.omitted); payload.put("aggregates", snapshot.aggregates.stream().map(RetentionEvidenceStore::aggregateMap).toList());
        payload.put("recent", snapshot.recent.stream().map(RetentionEvidenceStore::eventMap).toList());
        String checksum = RepositoryCodec.digest(StrictJson.canonical(payload)); payload.put("checksum", checksum);
        return StrictJson.canonical(payload).getBytes(StandardCharsets.UTF_8);
    }
    private Snapshot read(Path path) throws IOException {
        RetentionFileOwner.attributes(path);
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > limits.bytes) throw new IOException("Evidence input quota");
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            var raw = StrictJson.object(text); RepositoryCodec.keys(raw, "schema", "world", "revision", "omitted", "aggregates", "recent", "checksum");
            if (RepositoryCodec.number(raw, "schema") != SCHEMA || !world.toString().equals(RepositoryCodec.string(raw, "world"))) throw new IOException("Evidence schema/world");
            var payload = new LinkedHashMap<>(raw); payload.remove("checksum");
            if (!RepositoryCodec.digest(StrictJson.canonical(payload)).equals(RepositoryCodec.string(raw, "checksum"))) throw new IOException("Evidence checksum");
            var groups = new ArrayList<Aggregate>(); var traces = new ArrayList<Event>();
            for (Object row : RepositoryCodec.array(raw, "aggregates")) groups.add(aggregate(RepositoryCodec.object(row)));
            for (Object row : RepositoryCodec.array(raw, "recent")) traces.add(event(RepositoryCodec.object(row)));
            if (groups.size() > limits.aggregates || traces.size() > limits.recent
                    || groups.stream().anyMatch(a -> a.samples.size() > limits.samples || !a.key.owner.scope().worldId().equals(world))
                    || traces.stream().anyMatch(e -> !e.owner.scope().worldId().equals(world))) throw new IOException("Evidence record bounds");
            return new Snapshot(RepositoryCodec.number(raw, "revision"), groups, traces, RepositoryCodec.number(raw, "omitted"));
        } catch (StrictJson.Invalid | RuntimeException invalid) { throw new IOException("Invalid evidence", invalid); }
    }
    private static Map<String, Object> keyMap(Key key) {
        return Map.of("principal", key.owner.principal().id().toString(), "world", key.owner.scope().worldId().toString(),
                "domain", key.owner.scope().domainId().toString(), "capability", key.capability.name(), "version", (long) key.capability.version(),
                "artifact", key.artifact == null ? Map.of() : RepositoryCodec.ref(key.artifact), "reason", key.reason == null ? "" : key.reason.name(), "premise", key.premise);
    }
    private static Key key(Map<String, Object> row) throws StrictJson.Invalid {
        RepositoryCodec.keys(row, "principal", "world", "domain", "capability", "version", "artifact", "reason", "premise");
        var owner = new TrustedContext(new PrincipalRef(UUID.fromString(RepositoryCodec.string(row, "principal"))),
                new ScopeRef(UUID.fromString(RepositoryCodec.string(row, "world")), UUID.fromString(RepositoryCodec.string(row, "domain"))));
        var artifact = RepositoryCodec.object(row.get("artifact"));
        return new Key(owner, new CapabilityId(RepositoryCodec.string(row, "capability"), RepositoryCodec.positive(row, "version")),
                artifact.isEmpty() ? null : RepositoryCodec.readRef(artifact), RepositoryCodec.string(row, "reason").isEmpty() ? null
                        : Outcomes.Reason.valueOf(RepositoryCodec.string(row, "reason")), RepositoryCodec.string(row, "premise"));
    }
    private static Map<String, Object> sampleMap(Sample sample) {
        return Map.of("id", sample.id.toString(), "source", sample.source.name(), "at", sample.at, "effects", sample.effects, "modelCalls", sample.modelCalls);
    }
    private static Sample sample(Map<String, Object> row) throws StrictJson.Invalid {
        RepositoryCodec.keys(row, "id", "source", "at", "effects", "modelCalls");
        return new Sample(UUID.fromString(RepositoryCodec.string(row, "id")), Source.valueOf(RepositoryCodec.string(row, "source")),
                RepositoryCodec.number(row, "at"), RepositoryCodec.number(row, "effects"), RepositoryCodec.number(row, "modelCalls"));
    }
    private static Map<String, Object> aggregateMap(Aggregate a) {
        return Map.of("key", keyMap(a.key), "count", a.count, "first", a.first, "last", a.last, "premiseUntil", a.premiseUntil,
                "retainUntil", a.retainUntil, "samples", a.samples.stream().map(RetentionEvidenceStore::sampleMap).toList());
    }
    private static Aggregate aggregate(Map<String, Object> row) throws StrictJson.Invalid {
        RepositoryCodec.keys(row, "key", "count", "first", "last", "premiseUntil", "retainUntil", "samples");
        var samples = new ArrayList<Sample>(); for (Object value : RepositoryCodec.array(row, "samples")) samples.add(sample(RepositoryCodec.object(value)));
        return new Aggregate(key(RepositoryCodec.object(row.get("key"))), RepositoryCodec.number(row, "count"), RepositoryCodec.number(row, "first"),
                RepositoryCodec.number(row, "last"), RepositoryCodec.number(row, "premiseUntil"), RepositoryCodec.number(row, "retainUntil"), samples);
    }
    private static Map<String, Object> eventMap(Event event) {
        var row = new LinkedHashMap<>(sampleMap(new Sample(event.id, event.source, event.at, event.effects, event.modelCalls)));
        // Success traces carry the same finite context without creating a failure aggregate.
        row.put("context", keyMap(new Key(event.owner, event.capability, event.artifact, event.reason, event.premise)));
        row.put("success", event.reason == null); return row;
    }
    private static Event event(Map<String, Object> row) throws StrictJson.Invalid {
        RepositoryCodec.keys(row, "id", "source", "at", "effects", "modelCalls", "context", "success");
        var values = new LinkedHashMap<>(row); values.remove("context"); values.remove("success"); Sample sample = sample(values);
        Key key = key(RepositoryCodec.object(row.get("context")));
        if (!(row.get("success") instanceof Boolean success)) throw RepositoryCodec.invalid("EVIDENCE_BOOLEAN");
        if (success != (key.reason == null)) throw RepositoryCodec.invalid("EVIDENCE_OUTCOME");
        return new Event(sample.id, sample.source, key.owner, key.capability, key.artifact, success ? null : key.reason, key.premise,
                sample.at, sample.effects, sample.modelCalls);
    }
    private static long saturated(long first, long second) { return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second; }
    private static void write(Path path, byte[] bytes) throws IOException {
        RetentionFileOwner.safePath(path);
        try (var output = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) output.write(buffer); output.force(true);
        }
    }
    public Owner retentionOwner() {
        RetentionFileOwner files = new RetentionFileOwner("evidence", directory, Category.EVIDENCE,
                () -> state.revision, () -> 0, 8);
        return new Owner() {
            @Override public String id() { return "evidence"; }
            @Override public void bindPolicy(WorldRetentionManager manager) { discardGuard = manager::approveDiscard; }
            @Override public long revision() { return state.revision; }
            @Override public Cursor open() {
                Snapshot captured = state; Snapshot old = rollback; Cursor physical = files.open();
                long now = Math.max(0, clock.millis());
                return new Cursor() {
                    int aggregateAt, traceAt; boolean rollbackEmitted;
                    @Override public long revision() { return captured.revision; }
                    @Override public Page next(int maximum, int bytes) throws IOException {
                        if (readOnly) return new Page(List.of(), true, false, 0, 0);
                        var entries = new ArrayList<Entry>(); int inspected = 0;
                        while (inspected < maximum) {
                            if (!rollbackEmitted) {
                                rollbackEmitted = true; inspected++;
                                entries.add(new Entry(new WorldRetentionManager.Key("evidence", "rollback-records"), Category.EVIDENCE,
                                        new Amount(0, old.aggregates.size() + old.recent.size()), List.of(), true, false, Set.of(), Long.toString(captured.revision)));
                            } else if (aggregateAt < captured.aggregates.size()) {
                                Aggregate a = captured.aggregates.get(aggregateAt++); inspected++;
                                entries.add(new Entry(new WorldRetentionManager.Key("evidence", a.key.id()), Category.EVIDENCE,
                                        new Amount(0, 1), List.of(), false, a.retainUntil <= now, Set.of(a.key.owner), Long.toString(captured.revision)));
                            } else if (traceAt < captured.recent.size()) {
                                Event e = captured.recent.get(traceAt++); inspected++;
                                entries.add(new Entry(new WorldRetentionManager.Key("evidence", "trace:" + e.id), Category.EVIDENCE,
                                        new Amount(0, 1), List.of(), false, false, Set.of(e.owner), Long.toString(captured.revision)));
                            } else {
                                Page page = physical.next(maximum - inspected, bytes); entries.addAll(page.entries());
                                return new Page(entries, page.complete(), page.valid(), inspected + page.inspected(), page.readBytes());
                            }
                        }
                        return new Page(entries, false, true, inspected, 0);
                    }
                    @Override public void close() throws IOException { physical.close(); }
                };
            }
            @Override public OwnerResult collect(Entry entry, long expected, BooleanSupplier cancel) {
                synchronized (RetentionEvidenceStore.this) {
                    if (state.revision != expected) return OwnerResult.of(OwnerStatus.STALE);
                    if (!entry.key().owner().equals("evidence") || state.aggregates.stream().noneMatch(a -> a.key.id().equals(entry.key().id()) && a.retainUntil <= clock.millis()))
                        return OwnerResult.of(OwnerStatus.PROTECTED);
                    Result result;
                    try {
                        var retained = state.aggregates.stream().filter(a -> !a.key.id().equals(entry.key().id())).toList();
                        result = commit(new Snapshot(Math.addExact(expected, 1), retained, state.recent, state.omitted), Status.COMPACTED, cancel, true);
                    } catch (ArithmeticException overflow) { return OwnerResult.of(OwnerStatus.UNAVAILABLE); }
                    return new OwnerResult(switch (result.status) {
                        case COMPACTED -> OwnerStatus.COLLECTED;
                        case CANCELLED -> OwnerStatus.CANCELLED;
                        default -> OwnerStatus.UNAVAILABLE;
                    }, result.publicationMayHaveSucceeded);
                }
            }
        };
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return; closed = true; readOnly = true;
        try { lock.release(); } finally { channel.close(); }
    }
}
