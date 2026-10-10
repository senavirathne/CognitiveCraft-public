package dev.aivillages.core.kernel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.function.*;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.SurvivalGateway.*;
import static dev.aivillages.core.kernel.WorldRetentionManager.*;

/** IMP-017 owner. All disk/index operations run on the existing storage worker, never the tick. */
public final class ObservedWorldKnowledgeStore implements AutoCloseable {
    public static final int CONTRACT = 1, SCHEMA = 2, MAX_BYTES = 65_536;
    public static final String DOMAIN = "observed-world", WORLD_RELATIVE_PATH = "data/cognitivecraft/observations";
    public enum Status { RECORDED, UNCHANGED, INVALIDATED, SHARED, MIGRATED, DENIED, UNVERIFIED,
        OUT_OF_ORDER, LIMIT_REACHED, STALE_GENERATION, CANCELLED, INCOMPATIBLE, STORAGE_UNAVAILABLE }
    public enum Freshness { CURRENTLY_VERIFIED, OBSERVED_ABSENT, UNKNOWN, STALE, INVALID_TARGET, INCOMPATIBLE }
    public enum Invalidation { NONE, SUPERSEDED, TARGET_CHANGED, SOURCE_LOST, INCOMPATIBLE }
    public enum Qualification { NONE, EXPIRED, CLOCK_REGRESSED, SOURCE_LOST, SUPERSEDED,
        TARGET_CHANGED, INCOMPATIBLE, CONFLICTING, UNKNOWN_OBSERVATION, INCOMPLETE }
    public enum QueryStatus { COMPLETE, LIMIT_REACHED, INDEX_UNAVAILABLE, DENIED, STORAGE_UNAVAILABLE }
    public enum Point { BEFORE_STAGE, AFTER_STAGE, AFTER_BACKUP, BEFORE_REPLACE, AFTER_REPLACE }
    @FunctionalInterface public interface Faults { void at(Point point) throws IOException; }
    public record Limits(int records, int history, int bytes, long freshnessMillis, long retentionMillis) {
        public Limits {
            if (records < 1 || records > 128 || history < 1 || history > 8 || history > records
                    || bytes < 1024 || bytes > MAX_BYTES || freshnessMillis < 1 || freshnessMillis > 300_000
                    || retentionMillis < freshnessMillis || retentionMillis > 2_592_000_000L)
                throw new IllegalArgumentException("Observation bounds");
        }
        public static Limits defaults() { return new Limits(64, 4, MAX_BYTES, 30_000, 604_800_000); }
    }
    public record QueryLimits(int scanned, int results, int contextBytes) {
        public QueryLimits {
            if (scanned < 0 || scanned > 128 || results < 0 || results > 16
                    || contextBytes < 0 || contextBytes > 16_384) throw new IllegalArgumentException("Query bounds");
        }
        public static QueryLimits defaults() { return new QueryLimits(128, 8, 8192); }
    }
    /** A bounded copy of issued evidence. This value is never accepted by the ingestion API. */
    public record Proof(UUID source, long sequence, ActorRef actor, ObservationSnapshot observation, long at) {
        public Proof {
            Objects.requireNonNull(source); Objects.requireNonNull(actor); Objects.requireNonNull(observation);
            if (sequence < 1 || at < 0 || !actor.dimension().equals(observation.reference().dimension()))
                throw new IllegalArgumentException("Observation provenance");
        }
    }
    public record ObservationRecord(UUID id, TrustedContext owner, ObservationTarget target,
                                    Proof first, Proof latest, long count, long expiresAt, long retainUntil,
                                    Invalidation invalidation, long invalidatedAt, Set<TrustedContext> sharedWith) {
        public ObservationRecord {
            Objects.requireNonNull(id); Objects.requireNonNull(owner); Objects.requireNonNull(target);
            Objects.requireNonNull(first); Objects.requireNonNull(latest); Objects.requireNonNull(invalidation);
            sharedWith = Set.copyOf(sharedWith);
            if (!id.equals(first.observation.reference().snapshotId()) || !first.source.equals(latest.source)
                    || !first.actor.equals(latest.actor) || !sameFact(first, latest)
                    || first.sequence > latest.sequence || first.at > latest.at || count < 1
                    || expiresAt <= latest.at || expiresAt - latest.at > 300_000
                    || retainUntil < expiresAt || retainUntil - latest.at > 2_592_000_000L || invalidatedAt < 0
                    || invalidation == Invalidation.NONE && invalidatedAt != 0 || sharedWith.size() > 8
                    || sharedWith.contains(owner) || sharedWith.stream().anyMatch(c -> !c.scope().worldId().equals(owner.scope().worldId()))
                    || !target.area().dimension().equals(latest.observation.reference().dimension())
                    || target.entity() != null && !target.entity().equals(latest.actor.entityId()))
                throw new IllegalArgumentException("Retained observation");
            validateFacts(target, first.observation); validateFacts(target, latest.observation);
        }
        public String deduplicationIdentity() {
            return RepositoryCodec.digest(StrictJson.canonical(Map.of("owner", contextMap(owner),
                    "target", targetMap(target), "source", latest.source.toString(),
                    "observer", Map.of("citizen", latest.actor.citizenId().toString(), "entity", latest.actor.entityId().toString()),
                    "identity", latest.observation.targetIdentity(), "status", latest.observation.status().name(),
                    "counts", latest.observation.counts())));
        }
        public WorldRetentionManager.Key retentionKey() { return key(id); }
        public int schemaVersion() { return SCHEMA; }
    }
    private record State(long revision, List<ObservationRecord> records) {
        State {
            records = List.copyOf(records);
            if (revision < 0 || records.size() > 128 || records.stream().map(ObservationRecord::id).distinct().count() != records.size())
                throw new IllegalArgumentException("Observation snapshot");
        }
    }
    public record Result(Status status, UUID record, boolean publicationMayHaveSucceeded) { }
    public record Evidence(ObservationRecord record, Freshness freshness, Qualification qualification) { }
    /** Returned objects have no world/authority/effect handles and remain immutable after invalidation. */
    public static final class EvidenceSnapshot {
        private final TrustedContext viewer;
        private final ObservationTarget target;
        private final List<Evidence> evidence;
        private final QueryStatus status;
        private final int scanned, contextBytes;
        private final long at;
        private final String projection;
        private EvidenceSnapshot(TrustedContext viewer, ObservationTarget target, List<Evidence> evidence,
                                 QueryStatus status, int scanned, int contextBytes, long at, String projection) {
            this.viewer = viewer; this.target = target; this.evidence = List.copyOf(evidence);
            this.status = status; this.scanned = scanned; this.contextBytes = contextBytes;
            this.at = at; this.projection = projection;
        }
        public List<Evidence> evidence() { return evidence; }
        public QueryStatus status() { return status; }
        public int scanned() { return scanned; }
        public int contextBytes() { return contextBytes; }
        public long capturedAt() { return at; }
        public boolean complete() { return status == QueryStatus.COMPLETE; }
        public Freshness freshness() { return evidence.isEmpty()
                ? (status == QueryStatus.STORAGE_UNAVAILABLE ? Freshness.INCOMPATIBLE : Freshness.UNKNOWN) : evidence.getFirst().freshness; }
        public Set<WorldRetentionManager.Key> references() {
            var keys = new HashSet<WorldRetentionManager.Key>(); evidence.forEach(e -> keys.add(e.record.retentionKey()));
            return Set.copyOf(keys);
        }
    }
    private record IndexKey(TrustedContext viewer, ObservationTarget target) { }
    private record Index(long revision, Map<IndexKey, List<ObservationRecord>> records) { }
    public static final class IndexBuild {
        private final State captured;
        private final Map<IndexKey, List<ObservationRecord>> entries = new HashMap<>();
        private int cursor;
        private IndexBuild(State state) { captured = state; }
    }
    public enum IndexStatus { INCOMPLETE, READY, STALE }
    public record IndexResult(IndexStatus status, int inspected) { }

    private final UUID world;
    private final Path directory, current, previous, stage, legacy;
    private final Limits limits;
    private final Clock clock;
    private final AuthorityPolicy authorization;
    private final VersionedSkillRepository.VisibilityPolicy visibility;
    private final Predicate<UUID> sourceAvailable;
    private final Faults faults;
    private final FileChannel channel;
    private final FileLock lock;
    private volatile State state = new State(0, List.of());
    private State rollback = new State(0, List.of());
    private volatile boolean readOnly;
    private boolean closed;
    private int format = SCHEMA;
    private Index index;
    private WorldRetentionManager manager;

    private ObservedWorldKnowledgeStore(Path worldPath, UUID world, Limits limits, Clock clock,
            AuthorityPolicy authorization, VersionedSkillRepository.VisibilityPolicy visibility,
            Predicate<UUID> sourceAvailable, Faults faults) throws IOException {
        this.world = Objects.requireNonNull(world); this.limits = Objects.requireNonNull(limits);
        this.clock = Objects.requireNonNull(clock); this.authorization = Objects.requireNonNull(authorization);
        this.visibility = Objects.requireNonNull(visibility); this.sourceAvailable = Objects.requireNonNull(sourceAvailable);
        this.faults = Objects.requireNonNull(faults);
        directory = worldPath.resolve(WORLD_RELATIVE_PATH); RetentionFileOwner.safePath(directory); Files.createDirectories(directory);
        current = directory.resolve("state.json"); previous = directory.resolve("state.prev.json");
        stage = directory.resolve("state.next.json"); legacy = directory.resolve("state.legacy-v1.json");
        for (Path path : List.of(current, previous, stage, legacy, directory.resolve("writer.lock"))) RetentionFileOwner.safePath(path);
        channel = FileChannel.open(directory.resolve("writer.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            try { acquired = channel.tryLock(); } catch (OverlappingFileLockException busy) { throw new IOException("Observation writer busy", busy); }
            if (acquired == null) throw new IOException("Observation writer busy");
            lock = acquired;
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try { var loaded = read(current); state = loaded.state; format = loaded.format; }
                catch (IOException invalid) {
                    readOnly = true;
                    if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) try { state = read(previous).state; } catch (IOException ignored) { }
                }
            } else if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                readOnly = true; try { state = read(previous).state; } catch (IOException ignored) { }
            }
            if (!readOnly && Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                try { rollback = read(previous).state; } catch (IOException invalid) { readOnly = true; }
            }
            if (!readOnly) Files.deleteIfExists(stage);
            rebuildAll();
        } catch (IOException | RuntimeException failure) {
            if (acquired != null) acquired.release(); channel.close(); throw failure;
        }
    }
    /** Policy callbacks must read immutable owner views; they must never access Minecraft off-thread. */
    public static ObservedWorldKnowledgeStore open(Path path, UUID world, Limits limits, Clock clock,
            AuthorityPolicy authorization, VersionedSkillRepository.VisibilityPolicy visibility,
            Predicate<UUID> sourceAvailable, Faults faults) throws IOException {
        return new ObservedWorldKnowledgeStore(path, world, limits, clock, authorization, visibility, sourceAvailable, faults);
    }
    public boolean readOnly() { return readOnly; }
    /** Trusted storage-owner revision, not exposed in principal query results or denial diagnostics. */
    public long revision() { return state.revision; }
    public int formatVersion() { return format; }
    public synchronized int privateRecordCount(TrustedContext caller) {
        return (int) state.records.stream().filter(r -> r.owner.equals(caller)).count();
    }
    private boolean inWorld(TrustedContext caller) { return caller != null && caller.scope().worldId().equals(world); }
    private boolean visible(ObservationRecord row, TrustedContext caller) {
        return inWorld(caller) && (row.owner.equals(caller)
                || row.sharedWith.contains(caller) && permittedSharing(caller, row.owner));
    }
    private boolean permittedSharing(TrustedContext viewer, TrustedContext owner) {
        try { return visibility.mayRead(viewer, owner); } catch (RuntimeException unavailable) { return false; }
    }
    private boolean available(UUID source) {
        try { return sourceAvailable.test(source); } catch (RuntimeException unavailable) { return false; }
    }
    private boolean authorized(ActorRef actor, TrustedContext caller) {
        try { return authorization.currentlyAllows(actor, Effect.OBSERVE, caller); } catch (RuntimeException unavailable) { return false; }
    }
    private Result result(Status status, UUID id) { return new Result(status, id, false); }

    /** Arbitrary snapshots, decoded model text and retained record values cannot enter this API. */
    public synchronized Result recordObservation(TrustedContext caller, Object candidate, BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled);
        if (!(candidate instanceof ObservedEvidence issued)) return result(Status.UNVERIFIED, null);
        if (!inWorld(caller) || !issued.owner().equals(caller)
                || !authorized(issued.actor(), caller)
                || !available(issued.source())) return result(Status.DENIED, null);
        if (issued.at() < 0 || issued.at() > clock.millis()) return result(Status.UNVERIFIED, null);
        if (readOnly) return result(Status.STORAGE_UNAVAILABLE, null);
        if (format != SCHEMA) return result(Status.INCOMPATIBLE, null);
        try {
            var proof = new Proof(issued.source(), issued.sequence(), issued.actor(), issued.snapshot(), issued.at());
            validateFacts(issued.target(), issued.snapshot());
            var rows = new ArrayList<>(state.records);
            var topic = rows.stream().filter(r -> r.owner.equals(caller) && r.target.equals(issued.target())).toList();
            var sourceRows = topic.stream().filter(r -> r.latest.source.equals(proof.source) && r.latest.actor.equals(proof.actor)).toList();
            for (var old : sourceRows) {
                if (old.latest.observation.reference().snapshotId().equals(proof.observation.reference().snapshotId()))
                    return result(Status.UNCHANGED, old.id);
                if (old.latest.sequence >= proof.sequence || old.latest.at > proof.at
                        || old.latest.observation.reference().revision() > proof.observation.reference().revision())
                    return result(Status.OUT_OF_ORDER, null);
            }
            ObservationRecord equivalent = sourceRows.stream().filter(r -> r.invalidation == Invalidation.NONE
                    && sameFact(r.latest, proof)).findFirst().orElse(null);
            ObservationRecord next;
            if (equivalent != null) {
                next = new ObservationRecord(equivalent.id, caller, issued.target(), equivalent.first, proof,
                        saturated(equivalent.count), Math.addExact(proof.at, limits.freshnessMillis),
                        Math.addExact(proof.at, limits.retentionMillis), Invalidation.NONE, 0, equivalent.sharedWith);
                rows.set(rows.indexOf(equivalent), next);
            } else {
                // Only comparable local issuance supersedes a fact. Other sources remain explicit alternatives.
                for (var old : sourceRows) if (old.invalidation == Invalidation.NONE) {
                    rows.set(rows.indexOf(old), invalidate(old, Invalidation.SUPERSEDED, proof.at));
                }
                if (topic.size() >= limits.history) {
                    var oldest = topic.stream().min(Comparator.comparingLong((ObservationRecord r) -> r.latest.at)
                            .thenComparing(r -> r.id.toString())).orElseThrow();
                    rows.removeIf(r -> r.id.equals(oldest.id));
                }
                if (rows.size() >= limits.records) return result(Status.LIMIT_REACHED, null);
                next = new ObservationRecord(proof.observation.reference().snapshotId(), caller, issued.target(), proof, proof, 1,
                        Math.addExact(proof.at, limits.freshnessMillis), Math.addExact(proof.at, limits.retentionMillis),
                        Invalidation.NONE, 0, Set.of());
                rows.add(next);
            }
            var changed = new State(Math.addExact(state.revision, 1), rows);
            return commit(changed, Status.RECORDED, next.id, cancelled, false,
                    () -> authorized(issued.actor(), caller) && available(issued.source()));
        } catch (IllegalArgumentException | ArithmeticException invalid) { return result(Status.LIMIT_REACHED, null); }
    }
    private static long saturated(long value) { return value == Long.MAX_VALUE ? value : value + 1; }
    private static boolean sameFact(Proof first, Proof second) {
        return first.observation.status() == second.observation.status()
                && first.observation.targetIdentity().equals(second.observation.targetIdentity())
                && first.observation.scannedCells() == second.observation.scannedCells()
                && first.observation.counts().equals(second.observation.counts());
    }
    private static void validateFacts(ObservationTarget target, ObservationSnapshot observation) {
        var counts = observation.counts(); Cuboid a = target.area();
        int volume = Math.toIntExact(((long)a.maxX() - a.minX() + 1) * ((long)a.maxY() - a.minY() + 1) * ((long)a.maxZ() - a.minZ() + 1));
        if (target.kind() == ObservationKind.SOURCE_CROPS) {
            if (!counts.keySet().equals(Set.of("mature_wheat", "unknown_cells")) || observation.scannedCells() != volume
                    || counts.get("mature_wheat") > volume || counts.get("unknown_cells") > volume
                    || counts.get("mature_wheat") + counts.get("unknown_cells") > volume
                    || (counts.get("unknown_cells") > 0) != (observation.status() == ObservationStatus.UNKNOWN)
                    || observation.status() == ObservationStatus.PRESENT && counts.get("mature_wheat") == 0
                    || observation.status() == ObservationStatus.ABSENT && counts.get("mature_wheat") != 0)
                throw new IllegalArgumentException("Crop evidence semantics");
        } else if (!counts.keySet().equals(Set.of("wheat", "seeds")) || observation.scannedCells() != 1
                || observation.status() == ObservationStatus.ABSENT && counts.values().stream().anyMatch(n -> n != 0))
            throw new IllegalArgumentException("Stock evidence semantics");
    }
    private static ObservationRecord invalidate(ObservationRecord r, Invalidation why, long at) {
        return new ObservationRecord(r.id, r.owner, r.target, r.first, r.latest, r.count, r.expiresAt, r.retainUntil,
                why, at, r.sharedWith);
    }
    public synchronized Result invalidateObservation(TrustedContext caller, UUID id, Invalidation why,
            long expected, BooleanSupplier cancelled) {
        var row = state.records.stream().filter(r -> r.id.equals(id) && r.owner.equals(caller)).findFirst().orElse(null);
        if (row == null || !inWorld(caller)) return result(Status.DENIED, null);
        if (why == null || why == Invalidation.NONE || why == Invalidation.SUPERSEDED) return result(Status.UNVERIFIED, null);
        if (state.revision != expected) return result(Status.STALE_GENERATION, null);
        var rows = new ArrayList<>(state.records); rows.set(rows.indexOf(row), invalidate(row, why, Math.max(0, clock.millis())));
        try { return commit(new State(Math.addExact(expected, 1), rows), Status.INVALIDATED, id, cancelled, false, () -> true); }
        catch (ArithmeticException exhausted) { return result(Status.LIMIT_REACHED, null); }
    }
    /** Explicit per-record exposure requires both its owner and the existing current visibility policy. */
    public synchronized Result shareObservation(TrustedContext caller, UUID id, TrustedContext recipient,
            long expected, BooleanSupplier cancelled) {
        var row = state.records.stream().filter(r -> r.id.equals(id) && r.owner.equals(caller)).findFirst().orElse(null);
        if (row == null || !inWorld(caller) || !inWorld(recipient) || recipient.equals(caller)
                || !permittedSharing(recipient, caller)) return result(Status.DENIED, null);
        if (state.revision != expected) return result(Status.STALE_GENERATION, null);
        if (row.sharedWith.contains(recipient)) return result(Status.UNCHANGED, id);
        if (row.sharedWith.size() >= 8) return result(Status.LIMIT_REACHED, null);
        var shares = new HashSet<>(row.sharedWith); shares.add(recipient);
        var replacement = new ObservationRecord(row.id, row.owner, row.target, row.first, row.latest, row.count,
                row.expiresAt, row.retainUntil, row.invalidation, row.invalidatedAt, shares);
        var rows = new ArrayList<>(state.records); rows.set(rows.indexOf(row), replacement);
        try { return commit(new State(Math.addExact(expected, 1), rows), Status.SHARED, id, cancelled, false,
                () -> permittedSharing(recipient, caller)); }
        catch (ArithmeticException exhausted) { return result(Status.LIMIT_REACHED, null); }
    }
    public synchronized EvidenceSnapshot queryFreshEvidence(TrustedContext caller, ObservationTarget target, QueryLimits bounds) {
        Objects.requireNonNull(target); Objects.requireNonNull(bounds); long now = Math.max(0, clock.millis());
        if (!inWorld(caller)) return empty(caller, target, QueryStatus.DENIED, now);
        if (index == null || index.revision != state.revision) return empty(caller, target,
                readOnly ? QueryStatus.STORAGE_UNAVAILABLE : QueryStatus.INDEX_UNAVAILABLE, now);
        var applicable = index.records.getOrDefault(new IndexKey(caller, target), List.of()).stream()
                .filter(r -> visible(r, caller)).map(r -> disclosed(r, caller)).sorted(order()).toList();
        var inspected = new ArrayList<ObservationRecord>();
        int scanned = 0, bytes = 0; boolean limited = false;
        for (var row : applicable) {
            if (scanned == bounds.scanned || inspected.size() == bounds.results) { limited = true; break; }
            scanned++;
            int context = StrictJson.canonical(recordMap(row, SCHEMA)).getBytes(StandardCharsets.UTF_8).length;
            if (context > bounds.contextBytes - bytes) { limited = true; break; }
            bytes += context; inspected.add(row);
        }
        var rows = new ArrayList<Evidence>();
        for (var row : inspected) {
            var evidence = classify(row, inspected, now);
            if (limited && (evidence.freshness == Freshness.CURRENTLY_VERIFIED || evidence.freshness == Freshness.OBSERVED_ABSENT))
                evidence = new Evidence(row, Freshness.STALE, Qualification.INCOMPLETE);
            rows.add(evidence);
        }
        if (inspected.stream().anyMatch(r -> !visible(r, caller))) return empty(caller, target, QueryStatus.DENIED, now);
        String projection = limited ? "" : projection(inspected, now);
        if (inspected.stream().anyMatch(r -> !visible(r, caller))) return empty(caller, target, QueryStatus.DENIED, now);
        // Revalidate sharing and the relevant projection before an asynchronous caller consumes this snapshot.
        return new EvidenceSnapshot(caller, target, rows, readOnly ? QueryStatus.STORAGE_UNAVAILABLE
                : limited ? QueryStatus.LIMIT_REACHED : QueryStatus.COMPLETE,
                scanned, bytes, now, projection);
    }
    public EvidenceSnapshot createEvidenceSnapshot(TrustedContext caller, ObservationTarget target, QueryLimits bounds) {
        return queryFreshEvidence(caller, target, bounds);
    }
    private EvidenceSnapshot empty(TrustedContext caller, ObservationTarget target, QueryStatus status, long now) {
        return new EvidenceSnapshot(caller, target, List.of(), status, 0, 0, now, "");
    }
    private static ObservationRecord disclosed(ObservationRecord r, TrustedContext caller) {
        if (r.owner.equals(caller)) return r;
        return new ObservationRecord(r.id, r.owner, r.target, r.first, r.latest, r.count, r.expiresAt, r.retainUntil,
                r.invalidation, r.invalidatedAt, Set.of(caller));
    }
    private static Comparator<ObservationRecord> order() {
        return Comparator.comparingLong((ObservationRecord r) -> r.latest.at).reversed()
                .thenComparing(Comparator.comparingLong((ObservationRecord r) -> r.latest.sequence).reversed())
                .thenComparing(r -> r.id.toString());
    }
    private Evidence classify(ObservationRecord r, List<ObservationRecord> applicable, long now) {
        if (readOnly || format != SCHEMA || r.invalidation == Invalidation.INCOMPATIBLE)
            return new Evidence(r, Freshness.INCOMPATIBLE, Qualification.INCOMPATIBLE);
        if (r.invalidation == Invalidation.TARGET_CHANGED) return new Evidence(r, Freshness.INVALID_TARGET, Qualification.TARGET_CHANGED);
        if (r.invalidation == Invalidation.SUPERSEDED) return new Evidence(r, Freshness.STALE, Qualification.SUPERSEDED);
        if (r.invalidation == Invalidation.SOURCE_LOST || !available(r.latest.source))
            return new Evidence(r, Freshness.STALE, Qualification.SOURCE_LOST);
        if (now < r.latest.at) return new Evidence(r, Freshness.STALE, Qualification.CLOCK_REGRESSED);
        if (now >= r.expiresAt) return new Evidence(r, Freshness.STALE, Qualification.EXPIRED);
        if (r.latest.observation.status() == ObservationStatus.UNKNOWN)
            return new Evidence(r, Freshness.UNKNOWN, Qualification.UNKNOWN_OBSERVATION);
        if (applicable.stream().anyMatch(other -> !other.id.equals(r.id) && other.invalidation == Invalidation.NONE
                && available(other.latest.source) && now >= other.latest.at && now < other.expiresAt
                && other.latest.observation.status() != ObservationStatus.UNKNOWN && !sameFact(r.latest, other.latest)))
            return new Evidence(r, Freshness.STALE, Qualification.CONFLICTING);
        return new Evidence(r, r.latest.observation.status() == ObservationStatus.ABSENT
                ? Freshness.OBSERVED_ABSENT : Freshness.CURRENTLY_VERIFIED, Qualification.NONE);
    }
    private String projection(List<ObservationRecord> rows, long now) {
        return RepositoryCodec.digest(StrictJson.canonical(rows.stream().map(r -> Map.of("record", recordMap(r, SCHEMA),
                "freshness", classify(r, rows, now).freshness.name(), "qualification", classify(r, rows, now).qualification.name())).toList()));
    }
    /** Rechecks relevant authority/freshness; unrelated private writes cannot reveal a world revision. */
    public synchronized boolean snapshotCurrent(EvidenceSnapshot snapshot, TrustedContext caller) {
        if (snapshot == null || !snapshot.complete() || !Objects.equals(snapshot.viewer, caller) || !inWorld(caller)
                || index == null || index.revision != state.revision) return false;
        var rows = index.records.getOrDefault(new IndexKey(caller, snapshot.target), List.of()).stream()
                .filter(r -> visible(r, caller)).map(r -> disclosed(r, caller)).sorted(order()).toList();
        return snapshot.projection.equals(projection(rows, Math.max(0, clock.millis()))) && rows.stream().allMatch(r -> visible(r, caller));
    }
    public synchronized void deleteDerivedIndexes() { index = null; }
    public synchronized IndexBuild beginIndexRebuild() { return new IndexBuild(state); }
    public synchronized IndexResult rebuildIndex(IndexBuild build, int maximum) {
        Objects.requireNonNull(build);
        if (maximum < 0 || maximum > 16) throw new IllegalArgumentException("Index slice");
        if (build.captured != state) return new IndexResult(IndexStatus.STALE, 0);
        int inspected = 0;
        while (inspected < maximum && build.cursor < state.records.size()) {
            var row = state.records.get(build.cursor++); inspected++;
            addIndex(build.entries, row.owner, row); for (var viewer : row.sharedWith) addIndex(build.entries, viewer, row);
        }
        if (build.cursor != state.records.size()) return new IndexResult(IndexStatus.INCOMPLETE, inspected);
        var immutable = new HashMap<IndexKey, List<ObservationRecord>>(); build.entries.forEach((k, v) -> immutable.put(k, List.copyOf(v)));
        index = new Index(state.revision, Map.copyOf(immutable)); return new IndexResult(IndexStatus.READY, inspected);
    }
    private static void addIndex(Map<IndexKey, List<ObservationRecord>> entries, TrustedContext viewer, ObservationRecord row) {
        entries.computeIfAbsent(new IndexKey(viewer, row.target), ignored -> new ArrayList<>()).add(row);
    }
    private void rebuildAll() {
        IndexBuild build = beginIndexRebuild(); while (rebuildIndex(build, 16).status == IndexStatus.INCOMPLETE) { }
    }

    /** Registered v1 -> v2 adapter: adds explicit invalidation time, preserving every observation timestamp. */
    public synchronized Result migrate(long expected, BooleanSupplier cancelled) {
        if (readOnly) return result(Status.STORAGE_UNAVAILABLE, null);
        if (expected != state.revision) return result(Status.STALE_GENERATION, null);
        if (format == SCHEMA) return result(Status.UNCHANGED, null);
        if (format != 1 || SaveCompatibility.registered().migration(new SaveCompatibility.Format(DOMAIN, format)).isEmpty())
            return result(Status.INCOMPATIBLE, null);
        try {
            if (cancelled.getAsBoolean()) return result(Status.CANCELLED, null);
            byte[] original = boundedBytes(current);
            if (manager == null) return result(Status.STORAGE_UNAVAILABLE, null);
            var capacity = manager.assessReplacement("observations", Category.EVIDENCE,
                    new Amount((long)limits.bytes * 3 + original.length, state.records.size() + rollback.records.size()));
            if (capacity.status() != Capacity.AVAILABLE) return result(capacity.status() == Capacity.STORAGE_LIMIT_REACHED
                    ? Status.LIMIT_REACHED : Status.STORAGE_UNAVAILABLE, null);
            if (Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)) {
                if (!Arrays.equals(original, boundedBytes(legacy))) return result(Status.STORAGE_UNAVAILABLE, null);
            } else write(legacy, original);
            return commit(new State(Math.addExact(expected, 1), state.records), Status.MIGRATED, null, cancelled, false, () -> true);
        } catch (IOException | ArithmeticException failure) { readOnly = true; return result(Status.STORAGE_UNAVAILABLE, null); }
    }
    private static final class Cancelled extends IOException { }
    private void checkpoint(BooleanSupplier cancelled, Point point) throws IOException {
        if (cancelled.getAsBoolean()) throw new Cancelled(); faults.at(point);
        if (cancelled.getAsBoolean()) throw new Cancelled();
    }
    private Result commit(State next, Status success, UUID id, BooleanSupplier cancelled,
                          boolean approved, BooleanSupplier permission) {
        if (readOnly || closed || manager == null) return result(Status.STORAGE_UNAVAILABLE, null);
        boolean replaced = false; WorldRetentionManager.DiscardLease lease = null;
        try {
            verifyCurrent();
            byte[] bytes = encode(next, SCHEMA);
            if (bytes.length > limits.bytes) return result(Status.LIMIT_REACHED, null);
            StrictJson.object(new String(bytes, StandardCharsets.UTF_8)); decode(bytes);
            // Reserve all possible owned envelopes and recovery headroom under the existing world policy.
            var capacity = manager.assessReplacement("observations", Category.EVIDENCE,
                    new Amount(Math.addExact((long) limits.bytes * 3, Files.exists(legacy) ? Files.size(legacy) : 0),
                            Math.max(next.records.size(), state.records.size()) + state.records.size()));
            if (capacity.status() != Capacity.AVAILABLE) return result(capacity.status() == Capacity.STORAGE_LIMIT_REACHED
                    ? Status.LIMIT_REACHED : Status.STORAGE_UNAVAILABLE, null);
            var removed = new HashSet<WorldRetentionManager.Key>(); state.records.forEach(r -> removed.add(r.retentionKey()));
            next.records.forEach(r -> removed.remove(r.retentionKey()));
            if (!approved && !removed.isEmpty()) {
                var approval = manager.approveDiscard(Set.copyOf(removed)); lease = approval.lease();
                if (approval.status() != Capacity.AVAILABLE) return result(approval.status() == Capacity.STORAGE_LIMIT_REACHED
                        ? Status.LIMIT_REACHED : Status.STORAGE_UNAVAILABLE, null);
            }
            checkpoint(cancelled, Point.BEFORE_STAGE); write(stage, bytes); checkpoint(cancelled, Point.AFTER_STAGE);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                RetentionFileOwner.attributes(current); Files.copy(current, previous, StandardCopyOption.REPLACE_EXISTING);
                try (var backup = FileChannel.open(previous, StandardOpenOption.WRITE)) { backup.force(true); }
                rollback = state;
            }
            checkpoint(cancelled, Point.AFTER_BACKUP); checkpoint(cancelled, Point.BEFORE_REPLACE);
            verifyCurrent();
            if (!permission.getAsBoolean()) return result(Status.DENIED, null);
            if (lease != null && !lease.valid()) return result(Status.STALE_GENERATION, null);
            Files.move(stage, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); replaced = true;
            try (var dir = FileChannel.open(directory, StandardOpenOption.READ)) { dir.force(true); }
            rollback = state; state = next; format = SCHEMA; rebuildAll(); faults.at(Point.AFTER_REPLACE);
            return result(success, id);
        } catch (Cancelled stop) { return new Result(Status.CANCELLED, null, replaced); }
        catch (StrictJson.Invalid | IllegalArgumentException quota) { return result(Status.LIMIT_REACHED, null); }
        catch (IOException | RuntimeException failure) { readOnly = true; return new Result(Status.STORAGE_UNAVAILABLE, null, replaced); }
        finally {
            try { Files.deleteIfExists(stage); } catch (IOException failure) { readOnly = true; }
            if (lease != null) lease.close();
        }
    }
    private void verifyCurrent() throws IOException {
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            var disk = read(current);
            if (disk.format != format || !disk.state.equals(state)) throw new IOException("Observation owner state changed");
        } else if (state.revision != 0 || !state.records.isEmpty()) throw new IOException("Observation owner state missing");
    }
    private static WorldRetentionManager.Key key(UUID id) { return new WorldRetentionManager.Key("observations", id.toString()); }
    private static void write(Path path, byte[] bytes) throws IOException {
        RetentionFileOwner.safePath(path);
        try (var out = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) out.write(buffer); out.force(true);
        }
    }
    private byte[] boundedBytes(Path path) throws IOException {
        RetentionFileOwner.attributes(path);
        try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(limits.bytes + 1); if (bytes.length > limits.bytes) throw new IOException("Observation input quota");
            return bytes;
        }
    }
    private record Loaded(State state, int format) { }
    private Loaded read(Path path) throws IOException { return decode(boundedBytes(path)); }
    private Loaded decode(byte[] bytes) throws IOException {
        try {
            var raw = StrictJson.object(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
            RepositoryCodec.keys(raw, "schema", "world", "revision", "records", "checksum");
            int version = RepositoryCodec.positive(raw, "schema");
            if (!SaveCompatibility.registered().readable(new SaveCompatibility.Format(DOMAIN, version))
                    || !world.toString().equals(RepositoryCodec.string(raw, "world"))) throw new IOException("Observation format/world");
            var payload = new LinkedHashMap<>(raw); payload.remove("checksum");
            if (!RepositoryCodec.digest(StrictJson.canonical(payload)).equals(RepositoryCodec.string(raw, "checksum")))
                throw new IOException("Observation checksum");
            var rows = new ArrayList<ObservationRecord>();
            for (Object item : RepositoryCodec.array(raw, "records")) rows.add(record(RepositoryCodec.object(item), version));
            if (rows.size() > limits.records || rows.stream().anyMatch(r -> !r.owner.scope().worldId().equals(world))
                    || rows.stream().collect(java.util.stream.Collectors.groupingBy(r -> new IndexKey(r.owner, r.target), java.util.stream.Collectors.counting()))
                        .values().stream().anyMatch(n -> n > limits.history)) throw new IOException("Observation record bounds");
            return new Loaded(new State(RepositoryCodec.number(raw, "revision"), rows), version);
        } catch (StrictJson.Invalid | RuntimeException invalid) { throw new IOException("Invalid observations", invalid); }
    }
    private byte[] encode(State snapshot, int schema) {
        var payload = new LinkedHashMap<String, Object>(); payload.put("schema", (long)schema); payload.put("world", world.toString());
        payload.put("revision", snapshot.revision); payload.put("records", snapshot.records.stream().map(r -> recordMap(r, schema)).toList());
        payload.put("checksum", RepositoryCodec.digest(StrictJson.canonical(payload)));
        return StrictJson.canonical(payload).getBytes(StandardCharsets.UTF_8);
    }
    private static Map<String, Object> contextMap(TrustedContext context) {
        return Map.of("principal", context.principal().id().toString(), "world", context.scope().worldId().toString(), "domain", context.scope().domainId().toString());
    }
    private static TrustedContext context(Map<String, Object> row) throws StrictJson.Invalid {
        RepositoryCodec.keys(row, "principal", "world", "domain");
        return new TrustedContext(new PrincipalRef(UUID.fromString(RepositoryCodec.string(row, "principal"))),
                new ScopeRef(UUID.fromString(RepositoryCodec.string(row, "world")), UUID.fromString(RepositoryCodec.string(row, "domain"))));
    }
    private static Map<String, Object> targetMap(ObservationTarget target) {
        Cuboid a = target.area();
        return Map.of("kind", target.kind().name(), "dimension", a.dimension(), "min", List.of((long)a.minX(), (long)a.minY(), (long)a.minZ()),
                "max", List.of((long)a.maxX(), (long)a.maxY(), (long)a.maxZ()), "entity", target.entity() == null ? "" : target.entity().toString());
    }
    private static ObservationTarget target(Map<String, Object> row) throws StrictJson.Invalid {
        RepositoryCodec.keys(row, "kind", "dimension", "min", "max", "entity");
        var min = RepositoryCodec.array(row, "min"); var max = RepositoryCodec.array(row, "max");
        if (min.size() != 3 || max.size() != 3) throw RepositoryCodec.invalid("OBSERVATION_COORDINATES");
        String entity = RepositoryCodec.string(row, "entity");
        return new ObservationTarget(ObservationKind.valueOf(RepositoryCodec.string(row, "kind")),
                new Cuboid(RepositoryCodec.string(row, "dimension"), integer(min.get(0)), integer(min.get(1)), integer(min.get(2)),
                        integer(max.get(0)), integer(max.get(1)), integer(max.get(2))), entity.isEmpty() ? null : UUID.fromString(entity));
    }
    private static int integer(Object value) throws StrictJson.Invalid {
        if (!(value instanceof Long n)) throw RepositoryCodec.invalid("OBSERVATION_INTEGER"); return Math.toIntExact(n);
    }
    private static Map<String, Object> proofMap(Proof p) {
        var o = p.observation; var row = new LinkedHashMap<String, Object>();
        row.put("source", p.source.toString()); row.put("sequence", p.sequence); row.put("at", p.at);
        row.put("citizen", p.actor.citizenId().toString()); row.put("entity", p.actor.entityId().toString()); row.put("dimension", p.actor.dimension());
        row.put("snapshot", o.reference().snapshotId().toString()); row.put("revision", o.reference().revision());
        row.put("tick", o.observedTick()); row.put("identity", o.targetIdentity()); row.put("status", o.status().name());
        row.put("scanned", (long)o.scannedCells()); row.put("counts", o.counts()); return row;
    }
    private static Proof proof(Map<String, Object> row) throws StrictJson.Invalid {
        RepositoryCodec.keys(row, "source", "sequence", "at", "citizen", "entity", "dimension", "snapshot", "revision", "tick", "identity", "status", "scanned", "counts");
        var actor = new ActorRef(UUID.fromString(RepositoryCodec.string(row, "citizen")), UUID.fromString(RepositoryCodec.string(row, "entity")), RepositoryCodec.string(row, "dimension"));
        var counts = new HashMap<String, Long>();
        for (var e : RepositoryCodec.object(row.get("counts")).entrySet()) {
            if (!(e.getValue() instanceof Long n)) throw RepositoryCodec.invalid("OBSERVATION_COUNT"); counts.put(e.getKey(), n);
        }
        return new Proof(UUID.fromString(RepositoryCodec.string(row, "source")), RepositoryCodec.number(row, "sequence"), actor,
                new ObservationSnapshot(new ObservationRef(UUID.fromString(RepositoryCodec.string(row, "snapshot")), RepositoryCodec.number(row, "revision"), actor.dimension()),
                        ObservationStatus.valueOf(RepositoryCodec.string(row, "status")), RepositoryCodec.string(row, "identity"),
                        RepositoryCodec.number(row, "tick"), Math.toIntExact(RepositoryCodec.number(row, "scanned")), counts), RepositoryCodec.number(row, "at"));
    }
    private static Map<String, Object> recordMap(ObservationRecord r, int schema) {
        var row = new LinkedHashMap<String, Object>(); row.put("id", r.id.toString()); row.put("owner", contextMap(r.owner)); row.put("target", targetMap(r.target));
        row.put("first", proofMap(r.first)); row.put("latest", proofMap(r.latest)); row.put("count", r.count); row.put("expires", r.expiresAt);
        row.put("retain", r.retainUntil); row.put("invalidation", r.invalidation.name());
        if (schema >= 2) row.put("invalidatedAt", r.invalidatedAt);
        row.put("shared", r.sharedWith.stream().sorted(Comparator.comparing(c -> c.principal().id().toString() + c.scope().domainId())).map(ObservedWorldKnowledgeStore::contextMap).toList());
        return row;
    }
    private static ObservationRecord record(Map<String, Object> row, int schema) throws StrictJson.Invalid {
        if (schema == 1) RepositoryCodec.keys(row, "id", "owner", "target", "first", "latest", "count", "expires", "retain", "invalidation", "shared");
        else RepositoryCodec.keys(row, "id", "owner", "target", "first", "latest", "count", "expires", "retain", "invalidation", "invalidatedAt", "shared");
        var shares = new HashSet<TrustedContext>();
        for (Object item : RepositoryCodec.array(row, "shared")) if (!shares.add(context(RepositoryCodec.object(item)))) throw RepositoryCodec.invalid("OBSERVATION_DUPLICATE_SHARE");
        var latest = proof(RepositoryCodec.object(row.get("latest"))); var invalidation = Invalidation.valueOf(RepositoryCodec.string(row, "invalidation"));
        return new ObservationRecord(UUID.fromString(RepositoryCodec.string(row, "id")), context(RepositoryCodec.object(row.get("owner"))),
                target(RepositoryCodec.object(row.get("target"))), proof(RepositoryCodec.object(row.get("first"))), latest,
                RepositoryCodec.number(row, "count"), RepositoryCodec.number(row, "expires"), RepositoryCodec.number(row, "retain"), invalidation,
                schema == 1 ? (invalidation == Invalidation.NONE ? 0 : latest.at) : RepositoryCodec.number(row, "invalidatedAt"), shares);
    }
    public Owner retentionOwner() {
        var files = new RetentionFileOwner("observations", directory, Category.EVIDENCE, () -> state.revision, () -> 0, 8);
        return new Owner() {
            @Override public String id() { return "observations"; }
            @Override public void bindPolicy(WorldRetentionManager policy) { manager = policy; }
            @Override public long revision() { return state.revision; }
            @Override public Cursor open() {
                State captured = state, old = rollback; Cursor physical = files.open(); long now = Math.max(0, clock.millis());
                return new Cursor() {
                    int at; boolean backup;
                    @Override public long revision() { return captured.revision; }
                    @Override public Page next(int maximum, int bytes) throws IOException {
                        if (readOnly) return new Page(List.of(), true, false, 0, 0);
                        var entries = new ArrayList<Entry>(); int inspected = 0;
                        while (inspected < maximum) {
                            if (!backup) {
                                backup = true; inspected++;
                                entries.add(new Entry(new WorldRetentionManager.Key("observations", "rollback-records"), Category.EVIDENCE,
                                        new Amount(0, old.records.size()), List.of(), true, false, Set.of(), Long.toString(captured.revision)));
                            } else if (at < captured.records.size()) {
                                var row = captured.records.get(at++); inspected++;
                                entries.add(new Entry(row.retentionKey(), Category.EVIDENCE, new Amount(0, 1), List.of(),
                                        false, row.retainUntil <= now, Set.of(row.owner), Long.toString(captured.revision)));
                            } else {
                                var page = physical.next(maximum - inspected, bytes); entries.addAll(page.entries());
                                return new Page(entries, page.complete(), page.valid(), inspected + page.inspected(), page.readBytes());
                            }
                        }
                        return new Page(entries, false, true, inspected, 0);
                    }
                    @Override public void close() throws IOException { physical.close(); }
                };
            }
            @Override public OwnerResult collect(Entry entry, long expected, BooleanSupplier cancelled) {
                synchronized (ObservedWorldKnowledgeStore.this) {
                    if (state.revision != expected) return OwnerResult.of(OwnerStatus.STALE);
                    var row = state.records.stream().filter(r -> r.retentionKey().equals(entry.key())).findFirst().orElse(null);
                    if (row == null || row.retainUntil > clock.millis()) return OwnerResult.of(OwnerStatus.PROTECTED);
                    var rows = state.records.stream().filter(r -> !r.id.equals(row.id)).toList();
                    if (expected == Long.MAX_VALUE) return OwnerResult.of(OwnerStatus.UNAVAILABLE);
                    var result = commit(new State(expected + 1, rows), Status.INVALIDATED, row.id, cancelled, true, () -> true);
                    return new OwnerResult(switch (result.status) {
                        case INVALIDATED -> OwnerStatus.COLLECTED;
                        case CANCELLED -> OwnerStatus.CANCELLED;
                        case STALE_GENERATION -> OwnerStatus.STALE;
                        default -> OwnerStatus.UNAVAILABLE;
                    }, result.publicationMayHaveSucceeded);
                }
            }
        };
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return; closed = true; readOnly = true; index = null;
        try { lock.release(); } finally { channel.close(); }
    }
}
