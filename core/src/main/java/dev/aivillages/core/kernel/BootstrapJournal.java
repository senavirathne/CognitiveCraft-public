package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.Contracts.ActorRef;
import dev.aivillages.core.kernel.Contracts.PrincipalRef;
import dev.aivillages.core.kernel.Contracts.ScopeRef;
import dev.aivillages.core.kernel.Contracts.TrustedContext;
import dev.aivillages.core.kernel.Outcomes.Reason;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** World-owned bounded run journal; schema 2 references registry identities. All I/O is off tick. */
public final class BootstrapJournal implements AutoCloseable {
    public static final int SCHEMA = 1;
    public static final int IDENTITY_REFERENCE_SCHEMA = 2;
    public static final String WORLD_RELATIVE_PATH = "data/cognitivecraft/bootstrap/v1";
    public static final int MAX_RUNS = 16;
    private static final int MAX_BYTES = 16_384;

    public enum Phase { ACTIVE, TERMINAL, INTERRUPTED }
    public record Enrollment(ActorRef actor, TrustedContext owner) {
        public Enrollment { Objects.requireNonNull(actor); Objects.requireNonNull(owner); }
    }
    /** Summary only; no interpreter frame, model prompt, or inferred authority is persisted. */
    public record RunMarker(UUID id, UUID citizenId, Phase phase, String outcome,
                            Reason reason, long effects, long modelCalls, String artifactSha256) {
        public RunMarker {
            Objects.requireNonNull(id); Objects.requireNonNull(citizenId);
            Objects.requireNonNull(phase);
            if (effects < 0 || modelCalls < 0 || (phase == Phase.ACTIVE) != (outcome == null)
                    || phase == Phase.ACTIVE && reason != null
                    || phase == Phase.INTERRUPTED && reason != Reason.INTERRUPTED
                    || phase == Phase.TERMINAL && (outcome == null || outcome.isBlank()))
                throw new IllegalArgumentException("Run marker");
            if (artifactSha256 != null && !artifactSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Artifact digest");
            if (outcome != null && (outcome.length() > 64
                    || !outcome.matches("[A-Z_]+"))) throw new IllegalArgumentException("Outcome");
        }
    }
    public record State(UUID worldId, long revision, Enrollment enrollment, List<RunMarker> runs,
                        boolean externalIdentities) {
        /** Schema-1 compatibility for existing bootstrap fixtures and recoverable original records. */
        public State(UUID worldId, long revision, Enrollment enrollment, List<RunMarker> runs) {
            this(worldId, revision, enrollment, runs, false);
        }
        public State {
            Objects.requireNonNull(worldId);
            runs = List.copyOf(runs);
            if (revision < 0 || runs.size() > MAX_RUNS
                    || externalIdentities && enrollment != null
                    || !externalIdentities && enrollment == null && !runs.isEmpty()
                    || enrollment != null && !enrollment.owner().scope().worldId().equals(worldId)
                    || runs.stream().map(RunMarker::id).distinct().count() != runs.size()
                    || runs.stream().filter(r -> r.phase() == Phase.ACTIVE).count() > 1
                    || enrollment != null && runs.stream().anyMatch(r ->
                            !r.citizenId().equals(enrollment.actor().citizenId())))
                throw new IllegalArgumentException("Bootstrap state");
        }
    }

    private final Path directory, current, previous;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private State state;
    private boolean readOnly;

    private BootstrapJournal(Path directory, FileChannel lockChannel, FileLock lock,
                             State state, boolean readOnly) {
        this.directory = directory; current = directory.resolve("state.json");
        previous = directory.resolve("state.prev.json");
        this.lockChannel = lockChannel; this.lock = lock;
        this.state = state; this.readOnly = readOnly;
    }

    public static BootstrapJournal open(Path worldRoot) throws IOException {
        Path directory = Objects.requireNonNull(worldRoot).resolve(WORLD_RELATIVE_PATH);
        Files.createDirectories(directory);
        for (Path file : List.of(directory, directory.resolve("state.json"),
                directory.resolve("state.prev.json"), directory.resolve("writer.lock")))
            if (Files.isSymbolicLink(file)) throw new IOException("Symbolic bootstrap path");
        FileChannel channel = FileChannel.open(directory.resolve("writer.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = null;
        try {
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException occupied) {
                throw new IOException("Bootstrap writer already open", occupied);
            }
            if (lock == null) throw new IOException("Bootstrap writer already open");
            Path current = directory.resolve("state.json"), previous = directory.resolve("state.prev.json");
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(previous, LinkOption.NOFOLLOW_LINKS))
                return new BootstrapJournal(directory, channel, lock,
                        new State(UUID.randomUUID(), 0, null, List.of()), false);
            try {
                return new BootstrapJournal(directory, channel, lock, read(current), false);
            } catch (IOException | StrictJson.Invalid invalidCurrent) {
                try {
                    return new BootstrapJournal(directory, channel, lock, read(previous), true);
                } catch (IOException | StrictJson.Invalid invalidPrevious) {
                    throw new IOException("No readable bootstrap state; preserving files", invalidCurrent);
                }
            }
        } catch (IOException | RuntimeException failure) {
            if (lock != null) lock.release();
            channel.close();
            throw failure;
        }
    }

    public synchronized State state() { return state; }
    public synchronized boolean readOnly() { return readOnly; }

    /** Expected revision prevents late background work from overwriting a later decision. */
    public synchronized State replace(State expected, Enrollment enrollment,
                                      List<RunMarker> runs) throws IOException {
        if (readOnly || !state.equals(expected) || state.externalIdentities())
            throw new IOException("Stale, migrated or recovered bootstrap state");
        State next = new State(state.worldId(), Math.addExact(state.revision(), 1), enrollment, runs);
        return publish(next);
    }

    /** Run references only: this method cannot publish citizen identity or control rights. */
    public synchronized State replaceRuns(State expected, List<RunMarker> runs,
                                          CitizenRegistry.Snapshot identities) throws IOException {
        if (readOnly || !state.equals(expected) || !state.externalIdentities())
            throw new IOException("Bootstrap registry handoff required");
        verifyReferences(runs, identities);
        return publish(new State(state.worldId(), Math.addExact(state.revision(), 1), null, runs, true));
    }

    /** Registry import commits first; an archived original then precedes the schema-2 handoff. */
    public synchronized State migrateIdentity(CitizenRegistry.Snapshot identities) throws IOException {
        if (readOnly) throw new IOException("Recovered bootstrap cannot migrate");
        verifyReferences(state.runs(), identities);
        if (state.externalIdentities()) return state;
        var receipt = identities.migration();
        UUID citizenId = state.enrollment() == null ? null : state.enrollment().actor().citizenId();
        if (receipt == null || receipt.sourceRevision() != state.revision()
                || !receipt.sourceSha256().equals(semanticSha256(state))
                || !Objects.equals(receipt.citizenId(), citizenId))
            throw new IOException("Missing exact bootstrap import receipt");
        if (state.enrollment() != null) {
            var citizen = identities.citizens().stream().filter(c ->
                    c.actor().citizenId().equals(citizenId)).findFirst();
            if (citizen.isEmpty() || !citizen.get().actor().equals(state.enrollment().actor())
                    || !citizen.get().owner().equals(state.enrollment().owner()))
                throw new IOException("Bootstrap identity/control mismatch");
        }
        Path original = directory.resolve("state.legacy-v1.json");
        if (Files.isSymbolicLink(original)) throw new IOException("Symbolic bootstrap archive");
        if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
            try { if (!read(original).equals(state)) throw new IOException("Bootstrap archive mismatch"); }
            catch (StrictJson.Invalid malformed) { throw new IOException("Invalid bootstrap archive", malformed); }
        } else {
            byte[] originalBytes = Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    ? readBytes(current) : encode(state).getBytes(StandardCharsets.UTF_8);
            Path staged = directory.resolve("state.legacy-v1.next.json");
            try {
                try (var output = FileChannel.open(staged, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    var content = ByteBuffer.wrap(originalBytes);
                    while (content.hasRemaining()) output.write(content);
                    output.force(true);
                }
                Files.move(staged, original, StandardCopyOption.ATOMIC_MOVE);
                try (var dir = FileChannel.open(directory, StandardOpenOption.READ)) { dir.force(true); }
            } finally { Files.deleteIfExists(staged); }
        }
        return publish(new State(state.worldId(), Math.addExact(state.revision(), 1), null, state.runs(), true));
    }

    private void verifyReferences(List<RunMarker> runs, CitizenRegistry.Snapshot identities) throws IOException {
        if (!state.worldId().equals(identities.worldId()) || identities.migration() == null
                || runs.stream().anyMatch(run ->
                identities.citizens().stream().noneMatch(c -> c.actor().citizenId().equals(run.citizenId()))))
            throw new IOException("Unknown or foreign citizen run reference");
    }

    /** Canonical metadata digest, not executable identity or an exact raw-file checksum. */
    public static String semanticSha256(State state) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(encode(state).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private State publish(State next) throws IOException {
        byte[] bytes = encode(next).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Bootstrap quota");
        Path staged = directory.resolve("state.next.json");
        try {
            try (FileChannel output = FileChannel.open(staged, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer content = ByteBuffer.wrap(bytes);
                while (content.hasRemaining()) output.write(content);
                output.force(true);
            }
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                Files.copy(current, previous, StandardCopyOption.REPLACE_EXISTING);
                try (FileChannel backup = FileChannel.open(previous, StandardOpenOption.WRITE)) {
                    backup.force(true);
                }
            }
            Files.move(staged, current, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) { dir.force(true); }
            state = next;
            return next;
        } catch (IOException failure) {
            readOnly = true; // Publication uncertainty requires reopen/reconciliation.
            throw failure;
        } finally { Files.deleteIfExists(staged); }
    }

    /** Reload never resumes an active run; caller persists the resulting marker before new work. */
    public synchronized State interruptedOnReload() {
        List<RunMarker> recovered = state.runs().stream().map(run -> run.phase() != Phase.ACTIVE
                ? run : new RunMarker(run.id(), run.citizenId(), Phase.INTERRUPTED,
                        "INTERRUPTED", Reason.INTERRUPTED, run.effects(), run.modelCalls(),
                        run.artifactSha256())).toList();
        return new State(state.worldId(), state.revision(), state.enrollment(), recovered, state.externalIdentities());
    }

    private static String encode(State state) {
        List<Object> rows = new ArrayList<>();
        for (RunMarker run : state.runs()) {
            var row = new java.util.LinkedHashMap<String, Object>();
            row.put("id", run.id().toString()); row.put("citizenId", run.citizenId().toString());
            row.put("phase", run.phase().name()); row.put("effects", run.effects());
            row.put("modelCalls", run.modelCalls());
            if (run.outcome() != null) row.put("outcome", run.outcome());
            if (run.reason() != null) row.put("reason", run.reason().name());
            if (run.artifactSha256() != null) row.put("artifactSha256", run.artifactSha256());
            rows.add(row);
        }
        var root = new java.util.LinkedHashMap<String, Object>();
        root.put("schema", (long) (state.externalIdentities() ? IDENTITY_REFERENCE_SCHEMA : SCHEMA));
        root.put("worldId", state.worldId().toString());
        root.put("revision", state.revision()); root.put("runs", rows);
        if (state.enrollment() != null) {
            Enrollment e = state.enrollment();
            root.put("enrollment", Map.of("citizenId", e.actor().citizenId().toString(),
                    "entityId", e.actor().entityId().toString(), "dimension", e.actor().dimension(),
                    "principal", e.owner().principal().id().toString(),
                    "scopeWorld", e.owner().scope().worldId().toString(),
                    "scopeDomain", e.owner().scope().domainId().toString()));
        }
        return StrictJson.canonical(root);
    }

    private static byte[] readBytes(Path path) throws IOException {
        byte[] bytes;
        // Bound allocation and reads even when a file is corrupt or grows while opening.
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) throw new IOException("Bootstrap input quota");
        return bytes;
    }

    private static State read(Path path) throws IOException, StrictJson.Invalid {
        byte[] bytes = readBytes(path);
        String json;
        try { json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException malformed) { throw new IOException("Invalid UTF-8", malformed); }
        Map<String, Object> root = StrictJson.object(json);
        boolean external = Long.valueOf(IDENTITY_REFERENCE_SCHEMA).equals(root.get("schema"));
        if (!external && !Long.valueOf(SCHEMA).equals(root.get("schema")))
            throw new IOException("Unknown bootstrap schema");
        if (external && root.containsKey("enrollment")) throw new IOException("Migrated journal contains identity");
        if (!root.keySet().equals(root.containsKey("enrollment")
                ? java.util.Set.of("schema", "worldId", "revision", "enrollment", "runs")
                : java.util.Set.of("schema", "worldId", "revision", "runs")))
            throw new IOException("Bootstrap fields");
        try {
            Enrollment enrollment = null;
            if (root.get("enrollment") instanceof Map<?, ?> e) {
                if (!e.keySet().equals(java.util.Set.of("citizenId", "entityId", "dimension",
                        "principal", "scopeWorld", "scopeDomain"))) throw new IllegalArgumentException();
                enrollment = new Enrollment(new ActorRef(UUID.fromString((String)e.get("citizenId")),
                        UUID.fromString((String)e.get("entityId")), (String)e.get("dimension")),
                        new TrustedContext(new PrincipalRef(UUID.fromString((String)e.get("principal"))),
                                new ScopeRef(UUID.fromString((String)e.get("scopeWorld")),
                                        UUID.fromString((String)e.get("scopeDomain")))));
            }
            if (!(root.get("runs") instanceof List<?> rows) || rows.size() > MAX_RUNS)
                throw new IllegalArgumentException();
            List<RunMarker> runs = new ArrayList<>();
            for (Object value : rows) {
                if (!(value instanceof Map<?, ?> row)
                        || !java.util.Set.of("id", "citizenId", "phase", "effects", "modelCalls",
                                "outcome", "reason", "artifactSha256").containsAll(row.keySet()))
                    throw new IllegalArgumentException();
                runs.add(new RunMarker(UUID.fromString((String)row.get("id")),
                        UUID.fromString((String)row.get("citizenId")),
                        Phase.valueOf((String)row.get("phase")), (String)row.get("outcome"),
                        row.containsKey("reason") ? Reason.valueOf((String)row.get("reason")) : null,
                        (Long)row.get("effects"), (Long)row.get("modelCalls"),
                        (String)row.get("artifactSha256")));
            }
            return new State(UUID.fromString((String)root.get("worldId")),
                    (Long)root.get("revision"), enrollment, runs, external);
        } catch (IllegalArgumentException | ClassCastException | NullPointerException malformed) {
            throw new IOException("Invalid bootstrap state", malformed);
        }
    }

    @Override public synchronized void close() throws IOException {
        lock.release(); lockChannel.close();
    }
}
