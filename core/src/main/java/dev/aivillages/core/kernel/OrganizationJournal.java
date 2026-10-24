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
import static dev.aivillages.core.kernel.Organizations.*;
import static dev.aivillages.core.kernel.WorldRetentionManager.*;

/** Sole off-thread schema-1 writer. Unknown/uncertain records are preserved and never activated from backup. */
public final class OrganizationJournal implements AutoCloseable {
    public static final int SCHEMA = Organizations.SCHEMA, MAX_BYTES = 262_144;
    public static final String WORLD_RELATIVE_PATH = "data/cognitivecraft/organizations/v1";
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

    private OrganizationJournal(Path directory, FileChannel channel, FileLock lock, UUID world, Faults faults) {
        this.directory = directory; this.channel = channel; this.lock = lock; this.faults = faults;
        current = directory.resolve("state.jsonl"); previous = directory.resolve("state.prev.jsonl");
        stage = directory.resolve("state.next.jsonl"); state = Snapshot.empty(world);
    }
    public static OrganizationJournal open(Path world, UUID id) throws IOException { return open(world, id, point -> { }); }
    public static OrganizationJournal open(Path world, UUID id, Faults faults) throws IOException {
        Objects.requireNonNull(id); Objects.requireNonNull(faults);
        Path directory = world.resolve(WORLD_RELATIVE_PATH); RetentionFileOwner.safePath(directory); Files.createDirectories(directory);
        for (String name : List.of("writer.lock", "state.jsonl", "state.prev.jsonl", "state.next.jsonl"))
            RetentionFileOwner.safePath(directory.resolve(name));
        var channel = FileChannel.open(directory.resolve("writer.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS); FileLock lock = null;
        try {
            try { lock = channel.tryLock(); } catch (OverlappingFileLockException occupied) { throw new IOException("Organization writer occupied", occupied); }
            if (lock == null) throw new IOException("Organization writer occupied");
            var store = new OrganizationJournal(directory, channel, lock, id, faults);
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
        if (readOnly || !state.equals(expected)) throw new IOException("Organization owner unavailable or stale");
        validateExtension(next);
        byte[] bytes = encode(next);
        // Verify against this exact reader before publication, including row token/UTF-8 limits.
        if (!decode(bytes, state.worldId()).equals(next)) throw new IOException("Organization encoding mismatch");
        long reserve = Math.multiplyExact((long) MAX_BYTES, 3);
        if (!capacity.test(reserve)) throw new IOException("Organization world quota unavailable");
        try {
            for (Path path : List.of(current, previous, stage)) {
                RetentionFileOwner.safePath(path);
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) RetentionFileOwner.attributes(path);
            }
            unchanged(current, ownedCurrent); unchanged(previous, ownedPrevious);
            if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Organization stage appeared outside its owner");
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
            faults.at(Point.AFTER_REPLACE); state = next; ownedPrevious = backup; ownedCurrent = bytes; return next;
        } catch (IOException uncertain) { readOnly = true; throw uncertain; }
    }
    private void validateExtension(Snapshot next) throws IOException {
        try {
            if (!next.worldId().equals(state.worldId()) || next.revision() != Math.addExact(state.revision(), 1))
                throw new IOException("Organization world or revision changed");
            OrganizationRegistry.validate(next, Limits.defaults());
            for (Scope old : state.scopes()) {
                Scope row = next.scopes().stream().filter(s -> s.id().equals(old.id())).findFirst().orElseThrow();
                if (!old.owner().equals(row.owner()) || old.kind() != row.kind() || old.createdAt() != row.createdAt()
                        || old.expiresAt() != row.expiresAt() || old.expired() && !row.expired()
                        || !row.equals(old) && row.revision() != Math.addExact(old.revision(), 1))
                    throw new IOException("Immutable organization reference or revision changed");
            }
            for (Scope row : next.scopes())
                if (state.scopes().stream().noneMatch(s -> s.id().equals(row.id())) && row.revision() != 1)
                    throw new IOException("New organization revision");
        } catch (IllegalArgumentException | NoSuchElementException | ArithmeticException invalid) { throw new IOException("Organization extension invalid", invalid); }
    }
    private static byte[] read(Path path) throws IOException {
        RetentionFileOwner.attributes(path);
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Organization input limit"); return bytes;
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
            throw new IOException("Organization envelope changed outside its owner: " + path.getFileName());
    }
    static byte[] encode(Snapshot snapshot) throws IOException {
        StringBuilder payload = new StringBuilder();
        for (Scope scope : snapshot.scopes()) payload.append(StrictJson.canonical(scope(scope))).append('\n');
        String header = StrictJson.canonical(Map.of("schema", SCHEMA, "worldId", snapshot.worldId().toString(),
                "revision", snapshot.revision(), "records", snapshot.scopes().size(), "sha256", digest(payload.toString())));
        byte[] bytes = (header + "\n" + payload).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Organization byte quota"); return bytes;
    }
    static Snapshot decode(byte[] bytes, UUID world) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Organization input limit");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            String[] lines = text.split("\n", -1);
            if (lines.length < 2 || lines.length > 18 || !lines[lines.length - 1].isEmpty()) throw new IOException("Organization line cap");
            Map<String, Object> header = StrictJson.object(lines[0]);
            fields(header, "schema", "worldId", "revision", "records", "sha256");
            if (number(header, "schema") != SCHEMA || !uuid(header, "worldId").equals(world)
                    || number(header, "records") != lines.length - 2) throw new IOException("Organization schema/world/count");
            String payload = text.substring(text.indexOf('\n') + 1);
            if (!digest(payload).equals(string(header, "sha256"))) throw new IOException("Organization checksum");
            var scopes = new ArrayList<Scope>();
            for (int i = 1; i < lines.length - 1; i++) scopes.add(scope(StrictJson.object(lines[i])));
            Snapshot result = new Snapshot(world, number(header, "revision"), scopes);
            OrganizationRegistry.validate(result, Limits.defaults()); return result;
        } catch (StrictJson.Invalid | RuntimeException invalid) { throw new IOException("Invalid organization state", invalid); }
    }
    /** Exact canonical byte cost computed from bounded UUID counts; no snapshot serialization on the tick. */
    static int policyBytes(PolicyRef ref) {
        int base = "{\"id\":\"\",\"memberOwners\":[],\"version\":1,\"viewers\":[]}".length() + ref.id().name().length();
        return base + contextsBytes(ref.memberOwners().size()) + contextsBytes(ref.viewers().size());
    }
    private static int contextsBytes(int size) { return size == 0 ? 0 : size * 147 + size - 1; }
    private static Map<String, Object> context(TrustedContext c) {
        return Map.of("principal", c.principal().id().toString(), "world", c.scope().worldId().toString(), "domain", c.scope().domainId().toString());
    }
    private static List<Object> contexts(Set<TrustedContext> values) {
        return values.stream().sorted(Comparator.comparing(c -> c.principal().id() + ":" + c.scope().domainId()))
                .map(c -> (Object) context(c)).toList();
    }
    private static Map<String, Object> policy(PolicyRef p) {
        return Map.of("id", p.id().name(), "version", p.version(), "memberOwners", contexts(p.memberOwners()), "viewers", contexts(p.viewers()));
    }
    private static Map<String, Object> actor(ActorRef a) { return Map.of("citizen", a.citizenId().toString(), "entity", a.entityId().toString(), "dimension", a.dimension()); }
    private static Map<String, Object> scope(Scope s) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", s.id().toString()); row.put("revision", s.revision()); row.put("owner", context(s.owner()));
        row.put("name", s.name()); row.put("kind", s.kind().name()); row.put("createdAt", s.createdAt());
        row.put("expiresAt", s.expiresAt()); row.put("expired", s.expired()); row.put("policy", policy(s.policy()));
        if (s.headquarters() != null) row.put("headquarters", Map.of("dimension", s.headquarters().dimension(),
                "x", s.headquarters().x(), "y", s.headquarters().y(), "z", s.headquarters().z()));
        row.put("members", s.members().stream().map(m -> Map.of("actor", actor(m.actor()), "owner", context(m.owner()), "role", m.role())).toList());
        row.put("jobs", s.jobs().stream().map(j -> Map.of("id", j.id().toString(), "origin", context(j.origin()), "readers", contexts(j.readers()))).toList());
        row.put("history", s.history().stream().map(e -> Map.of("revision", e.revision(), "kind", e.kind().name(), "reference", e.reference().toString())).toList());
        return row;
    }
    private static Scope scope(Map<String, Object> row) {
        var expected = new HashSet<>(Set.of("id", "revision", "owner", "name", "kind", "createdAt", "expiresAt", "expired", "policy", "members", "jobs", "history"));
        if (row.containsKey("headquarters")) expected.add("headquarters");
        if (!row.keySet().equals(expected)) throw new IllegalArgumentException("Organization fields");
        Map<String, Object> p = object(row.get("policy")); fields(p, "id", "version", "memberOwners", "viewers");
        PolicyRef policy = new PolicyRef(PolicyId.valueOf(string(p, "id")), Math.toIntExact(number(p, "version")),
                contexts(p.get("memberOwners")), contexts(p.get("viewers")));
        ContainerRef headquarters = null;
        if (row.containsKey("headquarters")) {
            var h = object(row.get("headquarters")); fields(h, "dimension", "x", "y", "z");
            headquarters = new ContainerRef(string(h, "dimension"), Math.toIntExact(number(h, "x")), Math.toIntExact(number(h, "y")), Math.toIntExact(number(h, "z")));
        }
        var members = new ArrayList<Member>();
        for (Object value : list(row.get("members"), 16)) {
            var m = object(value); fields(m, "actor", "owner", "role"); var a = object(m.get("actor")); fields(a, "citizen", "entity", "dimension");
            members.add(new Member(new ActorRef(uuid(a, "citizen"), uuid(a, "entity"), string(a, "dimension")), context(m.get("owner")), string(m, "role")));
        }
        var jobs = new ArrayList<JobLink>();
        for (Object value : list(row.get("jobs"), 16)) {
            var j = object(value); fields(j, "id", "origin", "readers"); jobs.add(new JobLink(uuid(j, "id"), context(j.get("origin")), contexts(j.get("readers"))));
        }
        var history = new ArrayList<Event>();
        for (Object value : list(row.get("history"), 8)) {
            var e = object(value); fields(e, "revision", "kind", "reference"); history.add(new Event(number(e, "revision"), EventKind.valueOf(string(e, "kind")), uuid(e, "reference")));
        }
        return new Scope(uuid(row, "id"), number(row, "revision"), context(row.get("owner")), string(row, "name"),
                Kind.valueOf(string(row, "kind")), number(row, "createdAt"), number(row, "expiresAt"), (Boolean) row.get("expired"),
                policy, headquarters, members, jobs, history);
    }
    private static TrustedContext context(Object value) {
        var c = object(value); fields(c, "principal", "world", "domain");
        return new TrustedContext(new PrincipalRef(uuid(c, "principal")), new ScopeRef(uuid(c, "world"), uuid(c, "domain")));
    }
    private static Set<TrustedContext> contexts(Object value) {
        var rows = list(value, 8); var result = new HashSet<TrustedContext>();
        for (Object row : rows) if (!result.add(context(row))) throw new IllegalArgumentException("Duplicate named context");
        return Set.copyOf(result);
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Organization object"); return (Map<String, Object>) value;
    }
    private static List<?> list(Object value, int cap) {
        if (!(value instanceof List<?> rows) || rows.size() > cap) throw new IllegalArgumentException("Organization list quota"); return rows;
    }
    private static void fields(Map<String, Object> row, String... fields) { if (!row.keySet().equals(Set.of(fields))) throw new IllegalArgumentException("Organization unknown fields"); }
    private static long number(Map<String, Object> row, String name) { return (Long) row.get(name); }
    private static String string(Map<String, Object> row, String name) { return (String) row.get(name); }
    private static UUID uuid(Map<String, Object> row, String name) {
        String value = string(row, name); UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException("Canonical UUID required"); return id;
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public synchronized Owner retentionOwner() {
        return new Owner() {
            public String id() { return "organizations"; }
            public long revision() { synchronized (OrganizationJournal.this) { return state.revision(); } }
            public void bindPolicy(WorldRetentionManager manager) {
                capacity(bytes -> manager.assessReplacement(id(), Category.IDENTITIES, new Amount(bytes, 1968)).status() == Capacity.AVAILABLE);
            }
            public Optional<Amount> admissionCeiling() { return Optional.of(new Amount(3L * MAX_BYTES, 1968)); }
            public Cursor open() {
                Snapshot captured = snapshot();
                var files = new RetentionFileOwner(id(), directory, Category.IDENTITIES, this::revision, () -> 0, 4).open();
                return new Cursor() {
                    int offset;
                    public long revision() { return captured.revision(); }
                    public Page next(int maximum, int bytes) throws IOException {
                        var entries = new ArrayList<Entry>(); int work = 0;
                        while (offset < captured.scopes().size() && work < maximum) {
                            Scope s = captured.scopes().get(offset++); work++; var dependencies = new ArrayList<Key>();
                            if (!s.members().isEmpty()) dependencies.add(new Key("citizens", "records"));
                            if (!s.jobs().isEmpty()) dependencies.add(new Key("jobs", "records"));
                            entries.add(new Entry(OrganizationRegistry.key(s.id()), Category.IDENTITIES,
                                    new Amount(0, 1L + s.members().size() + s.jobs().size() + s.history().size()),
                                    dependencies, true, false, Set.of(s.owner()), ""));
                        }
                        if (work == maximum) return new Page(entries, false, OrganizationJournal.this.snapshot().revision() == captured.revision(), work, 0);
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
