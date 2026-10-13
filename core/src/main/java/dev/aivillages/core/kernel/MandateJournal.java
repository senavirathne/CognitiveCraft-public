package dev.aivillages.core.kernel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.LongPredicate;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Mandates.*;
import static dev.aivillages.core.kernel.WorldRetentionManager.*;

/** Sole off-thread schema-1 writer. Unknown/uncertain records are preserved and never activated from backup. */
public final class MandateJournal implements AutoCloseable {
    public static final int SCHEMA = Mandates.SCHEMA, MAX_BYTES = 524_288;
    public static final String WORLD_RELATIVE_PATH = "data/cognitivecraft/mandates/v1";
    public enum Point { BEFORE_STAGE, AFTER_STAGE, AFTER_BACKUP, BEFORE_REPLACE, AFTER_REPLACE }
    @FunctionalInterface public interface Faults { void at(Point point) throws IOException; }
    private final Path directory, current, previous, stage;
    private final FileChannel channel;
    private final FileLock lock;
    private final Faults faults;
    private Snapshot state;
    private byte[] ownedCurrent, ownedPrevious;
    private boolean readOnly;
    private LongPredicate capacity = bytes -> true;

    private MandateJournal(Path directory, FileChannel channel, FileLock lock, UUID world, Faults faults) {
        this.directory = directory; this.channel = channel; this.lock = lock; this.faults = faults;
        current = directory.resolve("state.jsonl"); previous = directory.resolve("state.prev.jsonl");
        stage = directory.resolve("state.next.jsonl"); state = Snapshot.empty(world);
    }
    public static MandateJournal open(Path world, UUID id) throws IOException { return open(world, id, point -> { }); }
    public static MandateJournal open(Path world, UUID id, Faults faults) throws IOException {
        Objects.requireNonNull(id); Objects.requireNonNull(faults);
        Path directory = world.resolve(WORLD_RELATIVE_PATH); RetentionFileOwner.safePath(directory); Files.createDirectories(directory);
        for (String name : List.of("writer.lock", "state.jsonl", "state.prev.jsonl", "state.next.jsonl"))
            RetentionFileOwner.safePath(directory.resolve(name));
        var channel = FileChannel.open(directory.resolve("writer.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS); FileLock lock = null;
        try {
            try { lock = channel.tryLock(); } catch (OverlappingFileLockException occupied) { throw new IOException("Mandate writer occupied", occupied); }
            if (lock == null) throw new IOException("Mandate writer occupied");
            var store = new MandateJournal(directory, channel, lock, id, faults);
            if (Files.exists(store.current, LinkOption.NOFOLLOW_LINKS)) {
                try { store.ownedCurrent = read(store.current); store.state = decode(store.ownedCurrent, id); }
                catch (IOException invalid) { store.readOnly = true; }
            } else if (Files.exists(store.previous, LinkOption.NOFOLLOW_LINKS)) store.readOnly = true;
            // A staged transaction is not an acknowledged grant. Keep exact bytes for owner recovery.
            if (Files.exists(store.stage, LinkOption.NOFOLLOW_LINKS)) store.readOnly = true;
            if (Files.exists(store.previous, LinkOption.NOFOLLOW_LINKS)) {
                try { store.ownedPrevious = read(store.previous); decode(store.ownedPrevious, id); }
                catch (IOException invalid) { store.readOnly = true; }
            }
            return store;
        } catch (IOException | RuntimeException failure) {
            if (lock != null) lock.release(); channel.close(); throw failure;
        }
    }
    public synchronized Snapshot snapshot() { return state; }
    public synchronized boolean readOnly() { return readOnly; }
    public synchronized void capacity(LongPredicate admission) { capacity = Objects.requireNonNull(admission); }
    public synchronized Snapshot replace(Snapshot expected, Snapshot next) throws IOException {
        if (readOnly || !state.equals(expected)) throw new IOException("Mandate owner unavailable or stale");
        validateExtension(next);
        byte[] bytes = encode(next);
        // Verify against this exact reader before publication, including row token/UTF-8 limits.
        if (!decode(bytes, state.worldId()).equals(next)) throw new IOException("Mandate encoding mismatch");
        long reserve = Math.multiplyExact((long) MAX_BYTES, 3);
        if (!capacity.test(reserve)) throw new IOException("Mandate world quota unavailable");
        try {
            for (Path path : List.of(current, previous, stage)) {
                RetentionFileOwner.safePath(path);
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) RetentionFileOwner.attributes(path);
            }
            unchanged(current, ownedCurrent); unchanged(previous, ownedPrevious);
            if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Mandate stage appeared outside its owner");
            faults.at(Point.BEFORE_STAGE); write(stage, bytes); faults.at(Point.AFTER_STAGE);
            unchanged(current, ownedCurrent); unchanged(previous, ownedPrevious); unchanged(stage, bytes);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                RetentionFileOwner.safePath(previous);
                Files.copy(current, previous, StandardCopyOption.REPLACE_EXISTING);
                try (var backup = FileChannel.open(previous, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { backup.force(true); }
            }
            faults.at(Point.AFTER_BACKUP); faults.at(Point.BEFORE_REPLACE);
            byte[] backup = ownedCurrent == null ? ownedPrevious : ownedCurrent;
            unchanged(current, ownedCurrent); unchanged(previous, backup); unchanged(stage, bytes);
            Files.move(stage, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var dir = FileChannel.open(directory, StandardOpenOption.READ)) { dir.force(true); }
            faults.at(Point.AFTER_REPLACE);
            unchanged(current, bytes); unchanged(previous, backup);
            if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Mandate stage appeared after replacement");
            state = next; ownedPrevious = backup; ownedCurrent = bytes; return next;
        } catch (IOException uncertain) { readOnly = true; throw uncertain; }
    }
    private void validateExtension(Snapshot next) throws IOException {
        try {
            if (!next.worldId().equals(state.worldId()) || next.revision() != Math.addExact(state.revision(), 1))
                throw new IOException("Mandate world/revision changed");
            MandateRegistry.validate(next, Limits.defaults());
            for (Grant old : state.grants()) {
                Grant row = next.grants().stream().filter(g -> g.id().equals(old.id())).findFirst().orElseThrow();
                if (!definition(old, row) || old.revoked() && !row.revoked()
                        || !row.equals(old) && row.revision() != Math.addExact(old.revision(), 1))
                    throw new IOException("Mandate definition/revocation changed");
                for (var kind : Budgets.Kind.values()) if (row.reserved().getOrDefault(kind, 0L) < old.reserved().getOrDefault(kind, 0L))
                    throw new IOException("Mandate budget refunded");
            }
            for (Grant row : next.grants()) if (state.grants().stream().noneMatch(g -> g.id().equals(row.id())) && row.revision() != 1)
                throw new IOException("New mandate revision");
            if (!next.bindings().containsAll(state.bindings())) throw new IOException("Mandate reservation changed or removed");
        } catch (IllegalArgumentException | NoSuchElementException | ArithmeticException invalid) {
            throw new IOException("Mandate extension invalid", invalid);
        }
    }
    private static boolean definition(Grant a, Grant b) {
        return a.id().equals(b.id()) && a.issuer().equals(b.issuer()) && a.grantee().equals(b.grantee())
                && Objects.equals(a.parent(), b.parent()) && Objects.equals(a.organization(), b.organization())
                && a.rights().equals(b.rights()) && a.issuedAt() == b.issuedAt() && a.expiresAt() == b.expiresAt()
                && a.budget().equals(b.budget());
    }
    private static byte[] read(Path path) throws IOException {
        RetentionFileOwner.attributes(path);
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Mandate input limit"); return bytes;
        }
    }
    private static void write(Path path, byte[] bytes) throws IOException {
        RetentionFileOwner.safePath(path);
        try (var out = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) out.write(buffer); out.force(true);
        }
    }
    private static void unchanged(Path path, byte[] expected) throws IOException {
        if (expected == null ? Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                : !Arrays.equals(expected, read(path)))
            throw new IOException("Mandate envelope changed outside its owner: " + path.getFileName());
    }
    public static byte[] encode(Snapshot snapshot) throws IOException {
        StringBuilder payload = new StringBuilder();
        for (Grant grant : snapshot.grants()) payload.append(MandateCodec.grant(grant)).append('\n');
        for (Binding binding : snapshot.bindings()) payload.append(MandateCodec.binding(binding)).append('\n');
        String header = StrictJson.canonical(Map.of("schema", SCHEMA, "worldId", snapshot.worldId().toString(),
                "revision", snapshot.revision(), "grants", snapshot.grants().size(), "bindings", snapshot.bindings().size(),
                "sha256", digest(payload.toString())));
        byte[] bytes = (header + "\n" + payload).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Mandate byte quota"); return bytes;
    }
    public static Snapshot decode(byte[] bytes, UUID world) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Mandate input limit");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            String[] lines = text.split("\n", -1);
            if (lines.length < 2 || lines.length > 98 || !lines[lines.length - 1].isEmpty()) throw new IOException("Mandate line cap");
            Map<String,Object> header = StrictJson.object(lines[0]);
            Outcomes.exact(header, "schema", "worldId", "revision", "grants", "bindings", "sha256");
            long grantsCount = Outcomes.number(header, "grants", "$"), bindingsCount = Outcomes.number(header, "bindings", "$");
            if (Outcomes.number(header, "schema", "$") != SCHEMA || !world.toString().equals(Outcomes.string(header, "worldId", "$"))
                    || grantsCount < 0 || grantsCount > 32 || bindingsCount < 0 || bindingsCount > 64
                    || grantsCount + bindingsCount != lines.length - 2) throw new IOException("Mandate schema/world/count");
            String payload = text.substring(text.indexOf('\n') + 1);
            if (!digest(payload).equals(Outcomes.string(header, "sha256", "$"))) throw new IOException("Mandate checksum");
            var grants = new ArrayList<Grant>(); var bindings = new ArrayList<Binding>();
            for (int i = 1; i <= grantsCount; i++) grants.add(MandateCodec.grant(StrictJson.object(lines[i])));
            for (int i = (int)grantsCount + 1; i < lines.length - 1; i++) bindings.add(MandateCodec.binding(StrictJson.object(lines[i])));
            Snapshot result = new Snapshot(world, Outcomes.number(header, "revision", "$"), grants, bindings);
            MandateRegistry.validate(result, Limits.defaults()); return result;
        } catch (StrictJson.Invalid | RuntimeException invalid) { throw new IOException("Invalid mandate state", invalid); }
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public synchronized Owner retentionOwner() {
        return new Owner() {
            public String id() { return "mandates"; }
            public long revision() { synchronized (MandateJournal.this) { return state.revision(); } }
            public void bindPolicy(WorldRetentionManager manager) {
                capacity(bytes -> manager.assessReplacement(id(), Category.IDENTITIES, new Amount(bytes, 2048)).status() == Capacity.AVAILABLE);
            }
            public Optional<Amount> admissionCeiling() { return Optional.of(new Amount(3L * MAX_BYTES, 2048)); }
            public Cursor open() {
                Snapshot captured = snapshot();
                var files = new RetentionFileOwner(id(), directory, Category.IDENTITIES, this::revision, () -> 0, 4).open();
                return new Cursor() {
                    int offset;
                    public long revision() { return captured.revision(); }
                    public Page next(int maximum, int bytes) throws IOException {
                        var entries = new ArrayList<Entry>(); int work = 0;
                        while (offset < captured.grants().size() + captured.bindings().size() && work < maximum) {
                            if (offset >= captured.grants().size()) {
                                Binding b = captured.bindings().get(offset++ - captured.grants().size()); work++;
                                entries.add(new Entry(new Key("mandates", "run-" + b.run()), Category.IDENTITIES, new Amount(0, 1),
                                        List.of(new Key("mandates", b.mandate().toString()),new Key("jobs", "records"),WorldRetentionManager.artifact(b.artifact())),
                                        true,false,Set.of(b.request().context()),""));
                                continue;
                            }
                            Grant g = captured.grants().get(offset++); work++;
                            var dependencies = new ArrayList<Key>(); dependencies.add(new Key("citizens", "records"));
                            if (g.parent() != null) dependencies.add(new Key("mandates", g.parent().toString()));
                            if (g.organization() != null) dependencies.add(new Key("organizations", g.organization().toString()));
                            entries.add(new Entry(new Key("mandates", g.id().toString()), Category.IDENTITIES,
                                    new Amount(0, 3L + g.rights().workers().size() + g.events().size()),
                                    dependencies, true, false, Set.of(g.issuer(), g.grantee()), ""));
                        }
                        if (work == maximum) return new Page(entries, false, MandateJournal.this.snapshot().revision() == captured.revision(), work, 0);
                        Page tail = files.next(maximum - work, bytes); entries.addAll(tail.entries());
                        return new Page(entries, tail.complete(), tail.valid(), work + tail.inspected(), tail.readBytes());
                    }
                    public void close() throws IOException { files.close(); }
                };
            }
            public OwnerResult collect(Entry entry, long expectedRevision, java.util.function.BooleanSupplier cancel) { return OwnerResult.of(OwnerStatus.PROTECTED); }
        };
    }
    @Override public synchronized void close() throws IOException {
        readOnly = true;
        try { if (lock.isValid()) lock.release(); } finally { channel.close(); }
    }
}
