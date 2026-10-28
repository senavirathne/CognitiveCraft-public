package dev.aivillages.core.kernel.storage;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.BooleanSupplier;
import static dev.aivillages.core.kernel.storage.StorageRegistry.Snapshot;

/** Bounded asynchronous metadata IO. An acknowledgement never proves a Minecraft save. */
public final class StorageMetadataStore {
    public static final String CURRENT = "metadata.current.json";
    public static final String PREVIOUS = "metadata.previous.json";
    public static final String PENDING = "metadata.pending.json";
    public static final String PREVIOUS_PENDING = "metadata.previous.pending.json";
    public enum Status { ACKNOWLEDGED, REVISION_CONFLICT, DENIED, READ_ONLY, LIMIT, STORAGE_FULL, BUSY, INVALID, IO_ERROR }
    public enum Cut { BEFORE_PENDING, PENDING_CREATED, PENDING_FORCED, PREVIOUS_COPIED, REPLACED, DIRECTORY_FORCED, BEFORE_ACK }
    public record Ack(Status status, long revision, UUID storage, String reason) { }
    record Change(Snapshot next, UUID storage, BooleanSupplier authorized) { }
    @FunctionalInterface public interface Faults { void hit(Cut cut) throws IOException; }
    @FunctionalInterface public interface Capacity { long available(Path directory) throws IOException; }
    private final Path directory;
    private final StorageRegistryLimits limits;
    private final Faults faults;
    private final Capacity capacity;
    private final ThreadPoolExecutor io;
    private final Object admission = new Object();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private volatile Snapshot snapshot;
    private volatile boolean readOnly = true;
    private volatile String reason = "NOT_OPENED";
    private volatile boolean closing;
    private String currentDigest;
    private FileChannel lockChannel;
    private FileLock lock;

    private StorageMetadataStore(Path directory, UUID world, StorageRegistryLimits limits, Faults faults, Capacity capacity) {
        this.directory = directory.toAbsolutePath().normalize(); this.snapshot = Snapshot.empty(world);
        this.limits = Objects.requireNonNull(limits); this.faults = Objects.requireNonNull(faults); this.capacity = Objects.requireNonNull(capacity);
        io = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(limits.queuedWrites()+1),task -> {
            Thread thread = new Thread(task,"cc-storage-metadata-"+world); thread.setDaemon(true); return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    public static CompletionStage<StorageMetadataStore> open(Path directory, UUID world) {
        return open(directory,world,StorageRegistryLimits.defaults(),cut -> {},path -> Files.getFileStore(path).getUsableSpace());
    }
    public static CompletionStage<StorageMetadataStore> createNew(Path directory, UUID world) {
        return createNew(directory,world,StorageRegistryLimits.defaults(),cut -> {},path -> Files.getFileStore(path).getUsableSpace());
    }
    public static CompletionStage<StorageMetadataStore> open(Path directory, UUID world, StorageRegistryLimits limits, Faults faults, Capacity capacity) {
        return start(directory,world,limits,faults,capacity,false);
    }
    public static CompletionStage<StorageMetadataStore> createNew(Path directory, UUID world, StorageRegistryLimits limits, Faults faults, Capacity capacity) {
        return start(directory,world,limits,faults,capacity,true);
    }
    private static CompletionStage<StorageMetadataStore> start(Path directory, UUID world, StorageRegistryLimits limits, Faults faults, Capacity capacity, boolean create) {
        StorageMetadataStore store = new StorageMetadataStore(directory,Objects.requireNonNull(world),limits,faults,capacity);
        var ready = new CompletableFuture<StorageMetadataStore>();
        store.io.execute(() -> { store.initialize(create); ready.complete(store); }); return ready;
    }
    public Snapshot snapshot() { return snapshot; }
    public boolean readOnly() { return readOnly; }
    public String reason() { return reason; }
    public StorageRegistryLimits limits() { return limits; }

    private void initialize(boolean create) {
        try {
            if (create) {
                if (Files.exists(directory,LinkOption.NOFOLLOW_LINKS)) { reason = "CREATE_REQUIRES_NEW_DIRECTORY"; return; }
                Path parent = directory.getParent(); if (parent != null) Files.createDirectories(parent);
                Files.createDirectory(directory);
            } else if (!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)) { reason = "MISSING_DIRECTORY"; return; }
            if (Files.isSymbolicLink(directory)) throw new IOException("metadata directory must not be a symlink");
            lockChannel = FileChannel.open(directory.resolve("metadata.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            try { lock = lockChannel.tryLock(); } catch (OverlappingFileLockException busy) { reason = "OWNER_ALREADY_OPEN"; return; }
            if (lock == null) { reason = "OWNER_ALREADY_OPEN"; return; }
            if (create) {
                ensureCapacity(); byte[] bytes = StorageMetadataCodec.encode(snapshot,limits);
                writeNew(directory.resolve(PENDING),bytes,false); atomic(directory.resolve(PENDING),directory.resolve(CURRENT)); forceDirectory();
                currentDigest = StorageMetadataCodec.digest(bytes); readOnly = false; reason = "OPEN"; return;
            }
            Snapshot current = null; byte[] currentBytes = null; String invalid = null;
            try {
                currentBytes = readBounded(directory.resolve(CURRENT)); current = StorageMetadataCodec.decode(currentBytes,snapshot.world(),limits);
            } catch (IOException | IllegalArgumentException bad) { invalid = "CURRENT_MISSING_CORRUPT_OR_UNSUPPORTED"; }
            Snapshot previous = null;
            Path previousPath = directory.resolve(PREVIOUS);
            if (Files.exists(previousPath,LinkOption.NOFOLLOW_LINKS)) {
                try { previous = StorageMetadataCodec.decode(readBounded(previousPath),snapshot.world(),limits); }
                catch (IOException | IllegalArgumentException bad) { invalid = "PREVIOUS_CORRUPT_OR_UNSUPPORTED"; }
            }
            if (current != null) snapshot = current;
            else if (previous != null) snapshot = previous;
            if (current != null && current.revision() > 0 && previous == null) invalid = "PREVIOUS_MISSING";
            if (previous != null && current != null && previous.revision() >= current.revision()) invalid = "REVISION_LINEAGE_INVALID";
            if (Files.exists(directory.resolve(PENDING),LinkOption.NOFOLLOW_LINKS) || Files.exists(directory.resolve(PREVIOUS_PENDING),LinkOption.NOFOLLOW_LINKS)) invalid = "PENDING_REQUIRES_OPERATOR_RECONCILIATION";
            if (invalid != null) { reason = invalid; return; }
            currentDigest = StorageMetadataCodec.digest(currentBytes); readOnly = false; reason = "OPEN";
        } catch (IOException | RuntimeException failure) { readOnly = true; reason = "OPEN_FAILED_"+failure.getClass().getSimpleName(); }
    }

    CompletionStage<Ack> transact(long expectedRevision, Function<Snapshot,Change> mutation) {
        var result = new CompletableFuture<Ack>();
        synchronized (admission) {
            if (closing || io.isShutdown()) return CompletableFuture.completedFuture(ack(Status.READ_ONLY,null,"metadata owner closing"));
            // One queue slot is reserved for orderly close; callers never run IO on rejection.
            if (io.getQueue().remainingCapacity() <= 1) return CompletableFuture.completedFuture(ack(Status.BUSY,null,"metadata IO queue full"));
            try {
                io.execute(() -> {
                    if (readOnly) { result.complete(ack(Status.READ_ONLY,null,reason)); return; }
                    if (expectedRevision != snapshot.revision()) { result.complete(ack(Status.REVISION_CONFLICT,null,"snapshot revision conflict")); return; }
                    Change change; byte[] bytes;
                    try {
                        change = Objects.requireNonNull(mutation.apply(snapshot));
                        if (!change.next().world().equals(snapshot.world()) || change.next().revision() != snapshot.revision()+1)
                            throw new IllegalArgumentException("metadata transaction lineage");
                        bytes = StorageMetadataCodec.encode(change.next(),limits);
                    } catch (StorageRegistry.Refused refusal) { result.complete(ack(refusal.status,null,refusal.getMessage())); return; }
                    catch (IllegalArgumentException failure) { result.complete(ack(Status.LIMIT,null,"invalid or oversized metadata mutation")); return; }
                    catch (RuntimeException failure) { result.complete(ack(Status.INVALID,null,"metadata policy/mutation failed")); return; }
                    boolean touched = false;
                    try {
                        byte[] old = readBounded(directory.resolve(CURRENT));
                        if (!StorageMetadataCodec.digest(old).equals(currentDigest)) throw new IOException("metadata changed outside owner");
                        ensureCapacity(); faults.hit(Cut.BEFORE_PENDING);
                        if (!change.authorized().getAsBoolean()) { result.complete(ack(Status.DENIED,null,"metadata authority revoked before write")); return; }
                        touched = true; writeNew(directory.resolve(PENDING),bytes,true); faults.hit(Cut.PENDING_FORCED);
                        writeNew(directory.resolve(PREVIOUS_PENDING),old,false);
                        atomic(directory.resolve(PREVIOUS_PENDING),directory.resolve(PREVIOUS)); faults.hit(Cut.PREVIOUS_COPIED);
                        atomic(directory.resolve(PENDING),directory.resolve(CURRENT)); faults.hit(Cut.REPLACED);
                        forceDirectory(); faults.hit(Cut.DIRECTORY_FORCED); faults.hit(Cut.BEFORE_ACK);
                        currentDigest = StorageMetadataCodec.digest(bytes); snapshot = change.next();
                        result.complete(ack(Status.ACKNOWLEDGED,change.storage(),"METADATA_ONLY_ACK"));
                    } catch (Full full) { result.complete(ack(Status.STORAGE_FULL,null,"replacement/rollback headroom unavailable")); }
                    catch (IOException | RuntimeException failure) {
                        readOnly = true; reason = touched ? "WRITE_UNCERTAIN_OR_FAILED" : "PRECONDITION_IO_FAILED";
                        result.complete(ack(Status.IO_ERROR,null,reason));
                    }
                });
            } catch (RejectedExecutionException busy) { result.complete(ack(Status.BUSY,null,"metadata IO queue unavailable")); }
        }
        return result;
    }
    private Ack ack(Status status, UUID storage, String reason) { return new Ack(status,snapshot.revision(),storage,reason); }
    private static final class Full extends IOException { }
    private void ensureCapacity() throws IOException { if (capacity.available(directory) < limits.replacementHeadroomBytes()) throw new Full(); }
    private byte[] readBounded(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || Files.size(path) > limits.snapshotBytes()) throw new IOException("metadata file type/size");
        try (var input = Files.newInputStream(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limits.snapshotBytes()+1); if (bytes.length > limits.snapshotBytes()) throw new IOException("metadata file grew beyond bound"); return bytes;
        }
    }
    private void writeNew(Path path, byte[] bytes, boolean inject) throws IOException {
        try (var channel = FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            if (inject) faults.hit(Cut.PENDING_CREATED);
            ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
    private static void atomic(Path from, Path to) throws IOException { Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
    private void forceDirectory() throws IOException { try (var channel = FileChannel.open(directory,StandardOpenOption.READ)) { channel.force(true); } }
    /** Returns immediately. All filesystem close work follows queued writes on this same IO owner. */
    public CompletionStage<Void> closeAsync() {
        synchronized (admission) {
            if (closing) return closed; closing = true;
            io.execute(() -> {
                try { if (lock != null) lock.release(); if (lockChannel != null) lockChannel.close(); closed.complete(null); }
                catch (IOException failure) { closed.completeExceptionally(failure); }
                finally { io.shutdown(); }
            });
        }
        return closed;
    }
}
