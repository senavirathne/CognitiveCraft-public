package dev.aivillages.core.kernel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

import static dev.aivillages.core.kernel.Jobs.*;

/** Sole off-tick job writer. JSON-lines keeps each strict record below the kernel parser limits. */
public final class JobJournal implements AutoCloseable {
    public static final int SCHEMA = 1;
    public static final int MAX_BYTES = 2_097_152;
    public static final int MAX_RECORDS = 192;
    public static final String WORLD_RELATIVE_PATH = "data/cognitivecraft/jobs/v1";
    public enum Point { BEFORE_STAGE, AFTER_STAGE, AFTER_BACKUP, BEFORE_REPLACE, AFTER_REPLACE }
    @FunctionalInterface public interface FaultInjector {
        void at(Point point) throws IOException;
        static FaultInjector none() { return point -> { }; }
    }
    private static final class UnknownSchema extends IOException { }
    private final Path directory, current, previous;
    private final FileChannel channel;
    private final FileLock lock;
    private final FaultInjector faults;
    private Snapshot state;
    private boolean readOnly;
    private JobJournal(Path directory, FileChannel channel, FileLock lock, Snapshot state,
                       boolean readOnly, FaultInjector faults) {
        this.directory = directory; this.channel = channel; this.lock = lock; this.state = state;
        this.readOnly = readOnly; this.faults = faults;
        current = directory.resolve("state.jsonl"); previous = directory.resolve("state.prev.jsonl");
    }
    public static JobJournal open(Path world, UUID worldId) throws IOException {
        return open(world,worldId,FaultInjector.none());
    }
    public static JobJournal open(Path world, UUID worldId, FaultInjector faults) throws IOException {
        Objects.requireNonNull(world); Objects.requireNonNull(worldId); Objects.requireNonNull(faults);
        Path directory = world.resolve(WORLD_RELATIVE_PATH); Files.createDirectories(directory);
        for (Path path : List.of(directory,directory.resolve("state.jsonl"),directory.resolve("state.prev.jsonl"),
                directory.resolve("state.next.jsonl"),directory.resolve("state.schema0.original.jsonl"),
                directory.resolve("writer.lock")))
            if (Files.isSymbolicLink(path)) throw new IOException("Symbolic job path");
        FileChannel channel = FileChannel.open(directory.resolve("writer.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        FileLock lock = null;
        try {
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException occupied) { throw new IOException("Job writer already open",occupied); }
            if (lock == null) throw new IOException("Job writer already open");
            Path current = directory.resolve("state.jsonl"), previous = directory.resolve("state.prev.jsonl");
            Snapshot initial; boolean recovered = false;
            if (!Files.exists(current,LinkOption.NOFOLLOW_LINKS) && !Files.exists(previous,LinkOption.NOFOLLOW_LINKS))
                initial = Snapshot.empty(worldId);
            else try { initial = read(current,worldId); }
            catch (UnknownSchema future) {
                // A valid old backup never authorizes replacing a future current format.
                try { initial = read(previous,worldId); } catch (IOException ignored) { initial = Snapshot.empty(worldId); }
                recovered = true;
            } catch (IOException invalid) {
                try { initial = read(previous,worldId); } catch (IOException ignored) { initial = Snapshot.empty(worldId); }
                recovered = true;
            }
            var store = new JobJournal(directory,channel,lock,initial,recovered,faults);
            if (!recovered && Files.exists(current,LinkOption.NOFOLLOW_LINKS)) {
                var header = firstObject(bytes(current));
                if (Long.valueOf(0).equals(header.get("schema"))) store.migrateImport();
            }
            return store;
        } catch (IOException | RuntimeException failure) {
            if (lock != null) lock.release(); channel.close(); throw failure;
        }
    }
    public synchronized Snapshot snapshot() { return state; }
    public synchronized boolean readOnly() { return readOnly; }
    public synchronized Snapshot replace(Snapshot expected, Snapshot next) throws IOException {
        if (readOnly || !state.equals(expected) || !state.worldId().equals(next.worldId())
                || next.revision() != Math.addExact(state.revision(),1))
            throw new IOException("Stale, recovered or invalid job commit");
        JobLifecycleStore.validateSnapshot(next,Settings.defaults());
        byte[] content = encode(next);
        Path staged = directory.resolve("state.next.jsonl");
        try {
            faults.at(Point.BEFORE_STAGE);
            write(staged,content);
            faults.at(Point.AFTER_STAGE);
            if (Files.exists(current,LinkOption.NOFOLLOW_LINKS)) {
                Files.copy(current,previous,StandardCopyOption.REPLACE_EXISTING);
                try (var backup = FileChannel.open(previous,StandardOpenOption.WRITE)) { backup.force(true); }
            }
            faults.at(Point.AFTER_BACKUP); faults.at(Point.BEFORE_REPLACE);
            Files.move(staged,current,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            try (var dir = FileChannel.open(directory,StandardOpenOption.READ)) { dir.force(true); }
            faults.at(Point.AFTER_REPLACE);
            state = next; return next;
        } catch (IOException uncertain) { readOnly = true; throw uncertain; }
        finally { Files.deleteIfExists(staged); }
    }
    private void migrateImport() throws IOException {
        byte[] original = bytes(current);
        Path archive = directory.resolve("state.schema0.original.jsonl");
        if (Files.exists(archive,LinkOption.NOFOLLOW_LINKS)) {
            if (!Arrays.equals(bytes(archive),original)) throw new IOException("Different schema-0 import original");
        } else write(archive,original);
        // Semantic fields and IDs are identical. Only the storage envelope/revision changes.
        replace(state,new Snapshot(state.worldId(),Math.addExact(state.revision(),1),state.jobs(),state.allocations()));
    }
    public static byte[] encode(Snapshot snapshot) throws IOException {
        var lines = new ArrayList<String>();
        for (Job job : snapshot.jobs()) lines.add(JobCodec.job(job));
        for (Allocation allocation : snapshot.allocations()) lines.add(JobCodec.allocation(allocation));
        for (String line : lines) try { StrictJson.object(line); }
        catch (StrictJson.Invalid limit) { throw new IOException("Job record byte/token quota",limit); }
        String payload = lines.isEmpty() ? "" : String.join("\n",lines)+"\n";
        String header = StrictJson.canonical(Map.of("schema",1L,"world",snapshot.worldId().toString(),
                "revision",snapshot.revision(),"records",(long)lines.size(),"sha256",sha(payload.getBytes(StandardCharsets.UTF_8))));
        byte[] bytes = (header+"\n"+payload).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES || lines.size() > MAX_RECORDS) throw new IOException("Job snapshot byte/record quota");
        return bytes;
    }
    /** Documented schema-0 import fixture, with the exact same schema-1 semantic records. */
    public static byte[] encodeImport(Snapshot snapshot) throws IOException {
        var rows = new ArrayList<Object>();
        try {
            for (Job job : snapshot.jobs()) rows.add(StrictJson.object(JobCodec.job(job)));
            for (Allocation allocation : snapshot.allocations()) rows.add(StrictJson.object(JobCodec.allocation(allocation)));
            String json = StrictJson.canonical(Map.of("schema",0L,"world",snapshot.worldId().toString(),
                    "revision",snapshot.revision(),"records",rows));
            StrictJson.object(json);
            return json.getBytes(StandardCharsets.UTF_8);
        } catch (StrictJson.Invalid limit) { throw new IOException("Import envelope quota",limit); }
    }
    private static Snapshot read(Path path, UUID expectedWorld) throws IOException {
        byte[] bytes = bytes(path);
        Map<String,Object> header = firstObject(bytes);
        Object version = header.get("schema");
        if (!Long.valueOf(1).equals(version) && !Long.valueOf(0).equals(version)) throw new UnknownSchema();
        try {
            UUID world = UUID.fromString(Outcomes.string(header,"world","$"));
            if (!world.equals(expectedWorld)) throw new IOException("Job world identity mismatch");
            long revision = Outcomes.number(header,"revision","$");
            var jobs = new ArrayList<Job>(); var allocations = new ArrayList<Allocation>();
            if (Long.valueOf(0).equals(version)) {
                Outcomes.exact(header,"schema","world","revision","records");
                if (!(header.get("records") instanceof List<?> records) || records.size() > MAX_RECORDS)
                    throw new IOException("Job import records");
                for (Object value : records) {
                    if (!(value instanceof Map<?,?> row)) throw new IOException("Job import record");
                    @SuppressWarnings("unchecked") var typed = (Map<String,Object>)row;
                    decode(typed,jobs,allocations);
                }
            } else {
                Outcomes.exact(header,"schema","world","revision","records","sha256");
                String text = utf8(bytes);
                int end = text.indexOf('\n'); if (end < 0) throw new IOException("Job snapshot header newline");
                String payload = text.substring(end+1);
                if (!sha(payload.getBytes(StandardCharsets.UTF_8)).equals(Outcomes.string(header,"sha256","$")))
                    throw new IOException("Job snapshot digest");
                String[] lines = payload.isEmpty() ? new String[0] : payload.split("\n",-1);
                int count = lines.length == 0 ? 0 : lines.length-1;
                if (count > MAX_RECORDS || count != Outcomes.number(header,"records","$")
                        || lines.length > 0 && !lines[lines.length-1].isEmpty()) throw new IOException("Job snapshot rows");
                for (int i=0;i<count;i++) decode(StrictJson.object(lines[i]),jobs,allocations);
            }
            var result = new Snapshot(world,revision,jobs,allocations);
            JobLifecycleStore.validateSnapshot(result,Settings.defaults());
            return result;
        } catch (StrictJson.Invalid | RuntimeException invalid) { throw new IOException("Invalid job snapshot",invalid); }
    }
    private static void decode(Map<String,Object> row,List<Job> jobs,List<Allocation> allocations) throws StrictJson.Invalid {
        String kind = Outcomes.string(row,"kind","$");
        if ("job".equals(kind)) jobs.add(JobCodec.job(row));
        else if ("allocation".equals(kind)) allocations.add(JobCodec.allocation(row));
        else throw new StrictJson.Invalid("$","RECORD_KIND");
    }
    private static Map<String,Object> firstObject(byte[] bytes) throws IOException {
        String text = utf8(bytes); int end = text.indexOf('\n');
        try { return StrictJson.object(end < 0 ? text : text.substring(0,end)); }
        catch (StrictJson.Invalid invalid) { throw new IOException("Invalid job header",invalid); }
    }
    private static byte[] bytes(Path path) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(MAX_BYTES+1); }
        if (bytes.length > MAX_BYTES) throw new IOException("Job input byte quota");
        return bytes;
    }
    private static String utf8(byte[] bytes) throws IOException {
        try { return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException invalid) { throw new IOException("Job UTF-8",invalid); }
    }
    private static void write(Path path,byte[] bytes) throws IOException {
        try (var output = FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            var content = ByteBuffer.wrap(bytes); while (content.hasRemaining()) output.write(content); output.force(true);
        }
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @Override public synchronized void close() throws IOException {
        try { lock.release(); } finally { channel.close(); }
    }
}
