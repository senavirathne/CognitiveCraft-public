package dev.aivillages.core.kernel;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.CitizenRegistry.*;

/** Sole identity publication store. Open/read/write/close run on the storage worker, never a tick. */
public final class CitizenIdentityStore implements AutoCloseable {
    public static final int SCHEMA = 1;
    public static final int MAX_BYTES = 65_536;
    public static final String WORLD_RELATIVE_PATH = "data/cognitivecraft/citizens/v1";
    public enum Point { AFTER_STAGE, AFTER_BACKUP, AFTER_REPLACE }
    @FunctionalInterface public interface FaultInjector {
        void at(Point point) throws IOException;
        static FaultInjector none() { return point -> { }; }
    }

    private final Path directory, current, previous;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final FaultInjector faults;
    private Snapshot state;
    private boolean readOnly;

    private CitizenIdentityStore(Path directory, FileChannel lockChannel, FileLock lock,
                                 Snapshot state, boolean readOnly, FaultInjector faults) {
        this.directory = directory; this.lockChannel = lockChannel; this.lock = lock;
        this.state = state; this.readOnly = readOnly; this.faults = faults;
        current = directory.resolve("state.json"); previous = directory.resolve("state.prev.json");
    }

    public static CitizenIdentityStore open(Path world, UUID worldId) throws IOException {
        return open(world, worldId, FaultInjector.none());
    }
    public static CitizenIdentityStore open(Path world, UUID worldId, FaultInjector faults) throws IOException {
        Objects.requireNonNull(world); Objects.requireNonNull(worldId); Objects.requireNonNull(faults);
        Path directory = world.resolve(WORLD_RELATIVE_PATH);
        Files.createDirectories(directory);
        for (Path path : List.of(directory, directory.resolve("state.json"),
                directory.resolve("state.prev.json"), directory.resolve("state.next.json"),
                directory.resolve("writer.lock")))
            if (Files.isSymbolicLink(path)) throw new IOException("Symbolic citizen path");
        var channel = FileChannel.open(directory.resolve("writer.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = null;
        try {
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException occupied) { throw new IOException("Citizen writer already open", occupied); }
            if (lock == null) throw new IOException("Citizen writer already open");
            Path current = directory.resolve("state.json"), previous = directory.resolve("state.prev.json");
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(previous, LinkOption.NOFOLLOW_LINKS))
                return new CitizenIdentityStore(directory, channel, lock,
                        new Snapshot(worldId, 0, List.of(), null), false, faults);
            Snapshot loaded; boolean recovered = false;
            try { loaded = read(current); }
            catch (IOException | StrictJson.Invalid invalid) {
                try { loaded = read(previous); recovered = true; }
                catch (IOException | StrictJson.Invalid invalidPrevious) {
                    throw new IOException("No readable citizen state; preserving files", invalid);
                }
            }
            if (!loaded.worldId().equals(worldId)) throw new IOException("Citizen world identity mismatch");
            return new CitizenIdentityStore(directory, channel, lock, loaded, recovered, faults);
        } catch (IOException | RuntimeException failure) {
            if (lock != null) lock.release(); channel.close(); throw failure;
        }
    }

    /** The composition root captures this immutable value before exposing the game-thread registry. */
    public synchronized Snapshot snapshot() { return state; }
    public synchronized boolean readOnly() { return readOnly; }

    /** Imports once before switching the bootstrap journal to ID references. No game work is replayed. */
    public synchronized Snapshot migrateBootstrap(BootstrapJournal.State bootstrap) throws IOException {
        if (readOnly || bootstrap.externalIdentities() || !state.worldId().equals(bootstrap.worldId()))
            throw new IOException("Bootstrap identity import unavailable");
        var enrollment = bootstrap.enrollment();
        UUID id = enrollment == null ? null : enrollment.actor().citizenId();
        var receipt = new Migration(bootstrap.revision(), BootstrapJournal.semanticSha256(bootstrap), id);
        if (state.migration() != null) {
            if (!state.migration().equals(receipt)) throw new IOException("Bootstrap import changed");
            verifyEnrollment(enrollment);
            return state;
        }
        var rows = new ArrayList<>(state.citizens());
        if (enrollment != null) {
            var existing = rows.stream().filter(c -> c.actor().citizenId().equals(id)).findFirst();
            if (existing.isPresent()) verifyEnrollment(enrollment);
            else {
                if (rows.size() == MAX_CITIZENS || rows.stream().anyMatch(c ->
                        c.actor().entityId().equals(enrollment.actor().entityId())))
                    throw new IOException("Bootstrap import conflicts with citizen binding");
                rows.add(new Citizen(enrollment.actor(), enrollment.owner(), null, Availability.UNKNOWN));
            }
        }
        try { return replace(state, new Snapshot(state.worldId(), Math.addExact(state.revision(), 1), rows, receipt)); }
        catch (ArithmeticException overflow) { throw new IOException("Citizen revision exhausted", overflow); }
    }

    private void verifyEnrollment(BootstrapJournal.Enrollment enrollment) throws IOException {
        if (enrollment == null) return;
        var citizen = state.citizens().stream().filter(c ->
                c.actor().citizenId().equals(enrollment.actor().citizenId())).findFirst();
        if (citizen.isEmpty() || !citizen.get().actor().equals(enrollment.actor())
                || !citizen.get().owner().equals(enrollment.owner()))
            throw new IOException("Bootstrap identity/control mismatch");
    }

    public synchronized Snapshot replace(Snapshot expected, Snapshot next) throws IOException {
        if (readOnly || !state.equals(expected)) throw new IOException("Stale or recovered citizen state");
        validateExtension(next);
        byte[] bytes = encode(next).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Citizen byte quota");
        Path staged = directory.resolve("state.next.json");
        try {
            try (var output = FileChannel.open(staged, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var content = ByteBuffer.wrap(bytes);
                while (content.hasRemaining()) output.write(content);
                output.force(true);
            }
            faults.at(Point.AFTER_STAGE);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                Files.copy(current, previous, StandardCopyOption.REPLACE_EXISTING);
                try (var backup = FileChannel.open(previous, StandardOpenOption.WRITE)) { backup.force(true); }
            }
            faults.at(Point.AFTER_BACKUP);
            Files.move(staged, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var dir = FileChannel.open(directory, StandardOpenOption.READ)) { dir.force(true); }
            faults.at(Point.AFTER_REPLACE);
            state = next;
            return next;
        } catch (IOException uncertain) {
            readOnly = true; throw uncertain;
        } finally { Files.deleteIfExists(staged); }
    }

    private void validateExtension(Snapshot next) throws IOException {
        long revision;
        try { revision = Math.addExact(state.revision(), 1); }
        catch (ArithmeticException overflow) { throw new IOException("Citizen revision exhausted", overflow); }
        if (!next.worldId().equals(state.worldId()) || next.revision() != revision
                || state.migration() != null && !state.migration().equals(next.migration()))
            throw new IOException("Invalid citizen revision or migration");
        for (Citizen old : state.citizens()) {
            var nextCitizen = next.citizens().stream().filter(c ->
                    c.actor().citizenId().equals(old.actor().citizenId())).findFirst();
            if (nextCitizen.isEmpty() || !old.actor().equals(nextCitizen.get().actor())
                    || !old.owner().equals(nextCitizen.get().owner()))
                throw new IOException("Identity binding/control is immutable");
        }
    }

    private static String encode(Snapshot state) {
        var rows = new ArrayList<Object>();
        for (Citizen citizen : state.citizens()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("citizenId", citizen.actor().citizenId().toString());
            row.put("entityId", citizen.actor().entityId().toString());
            row.put("dimension", citizen.actor().dimension());
            row.put("principal", citizen.owner().principal().id().toString());
            row.put("scopeWorld", citizen.owner().scope().worldId().toString());
            row.put("scopeDomain", citizen.owner().scope().domainId().toString());
            row.put("availability", citizen.availability().name());
            if (citizen.displayName() != null) row.put("name", citizen.displayName());
            rows.add(row);
        }
        var root = new LinkedHashMap<String, Object>();
        root.put("schema", (long) SCHEMA); root.put("worldId", state.worldId().toString());
        root.put("revision", state.revision()); root.put("citizens", rows);
        if (state.migration() != null) {
            var receipt = new LinkedHashMap<String, Object>();
            receipt.put("sourceRevision", state.migration().sourceRevision());
            receipt.put("sourceSha256", state.migration().sourceSha256());
            if (state.migration().citizenId() != null) receipt.put("citizenId", state.migration().citizenId().toString());
            root.put("bootstrapMigration", receipt);
        }
        return StrictJson.canonical(root);
    }

    private static Snapshot read(Path path) throws IOException, StrictJson.Invalid {
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) throw new IOException("Citizen input quota");
        String json;
        try { json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException malformed) { throw new IOException("Invalid citizen UTF-8", malformed); }
        var root = StrictJson.object(json);
        if (!Long.valueOf(SCHEMA).equals(root.get("schema"))) throw new IOException("Unknown citizen schema");
        if (!root.keySet().equals(root.containsKey("bootstrapMigration")
                ? Set.of("schema", "worldId", "revision", "citizens", "bootstrapMigration")
                : Set.of("schema", "worldId", "revision", "citizens"))) throw new IOException("Citizen fields");
        try {
            if (!(root.get("citizens") instanceof List<?> rows) || rows.size() > MAX_CITIZENS)
                throw new IllegalArgumentException();
            var citizens = new ArrayList<Citizen>();
            for (Object value : rows) {
                if (!(value instanceof Map<?, ?> row) || !row.keySet().equals(row.containsKey("name")
                        ? Set.of("citizenId", "entityId", "dimension", "principal", "scopeWorld", "scopeDomain", "availability", "name")
                        : Set.of("citizenId", "entityId", "dimension", "principal", "scopeWorld", "scopeDomain", "availability")))
                    throw new IllegalArgumentException();
                citizens.add(new Citizen(new ActorRef(UUID.fromString((String) row.get("citizenId")),
                        UUID.fromString((String) row.get("entityId")), (String) row.get("dimension")),
                        new TrustedContext(new PrincipalRef(UUID.fromString((String) row.get("principal"))),
                                new ScopeRef(UUID.fromString((String) row.get("scopeWorld")),
                                        UUID.fromString((String) row.get("scopeDomain")))),
                        (String) row.get("name"), Availability.valueOf((String) row.get("availability"))));
            }
            Migration migration = null;
            if (root.containsKey("bootstrapMigration")) {
                if (!(root.get("bootstrapMigration") instanceof Map<?, ?> receipt)
                        || !receipt.keySet().equals(receipt.containsKey("citizenId")
                        ? Set.of("sourceRevision", "sourceSha256", "citizenId")
                        : Set.of("sourceRevision", "sourceSha256"))) throw new IllegalArgumentException();
                migration = new Migration((Long) receipt.get("sourceRevision"), (String) receipt.get("sourceSha256"),
                        receipt.containsKey("citizenId") ? UUID.fromString((String) receipt.get("citizenId")) : null);
            }
            return new Snapshot(UUID.fromString((String) root.get("worldId")),
                    (Long) root.get("revision"), citizens, migration);
        } catch (IllegalArgumentException | NullPointerException | ClassCastException malformed) {
            throw new IOException("Invalid citizen state", malformed);
        }
    }

    @Override public synchronized void close() throws IOException {
        try { lock.release(); } finally { lockChannel.close(); }
    }
}
