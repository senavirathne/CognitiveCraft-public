package dev.aivillages.core.kernel;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Session adapter: bounded tick intake; the existing world-storage worker owns all I/O. */
public final class WorldRetentionService implements AutoCloseable {
    public static final int MAX_QUEUE = 64, MAX_OFFERS = 8, BATCH = 8;
    public record Status(int queued, long recorded, long dropped, long failures, long collections,
                         int maxQueue, int maxInspected, int maxReadBytes, boolean running) { }
    private final WorldRetentionManager manager;
    private final RetentionEvidenceStore evidence;
    private final ConcurrentLinkedQueue<RetentionEvidenceStore.Event> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger(), offers = new AtomicInteger(), highQueue = new AtomicInteger();
    private final AtomicBoolean running = new AtomicBoolean(), closed = new AtomicBoolean();
    private final AtomicLong recorded = new AtomicLong(), dropped = new AtomicLong(), failures = new AtomicLong(), collections = new AtomicLong();
    private volatile int maxInspected, maxReadBytes;
    private WorldRetentionManager.Scan scan;
    private int idle;
    public WorldRetentionService(WorldRetentionManager manager, RetentionEvidenceStore evidence) {
        this.manager = Objects.requireNonNull(manager); this.evidence = Objects.requireNonNull(evidence);
        evidence.referenceGuard(refs -> {
            var update = manager.references().publishNext("evidence", refs, true);
            return update == RetentionRoots.Update.APPLIED || update == RetentionRoots.Update.UNCHANGED;
        });
    }
    public void beginTick() { offers.set(0); }
    public boolean offer(RetentionEvidenceStore.Event event) {
        Objects.requireNonNull(event);
        if (closed.get() || !reserve(offers, MAX_OFFERS) || !reserve(queued, MAX_QUEUE)) { increment(dropped); return false; }
        queue.offer(event); highQueue.accumulateAndGet(queued.get(), Math::max); return true;
    }
    private static boolean reserve(AtomicInteger value, int maximum) {
        int prior = value.get();
        for (int attempt = 0; attempt < 8 && prior < maximum; attempt++) {
            if (value.compareAndSet(prior, prior + 1)) return true; prior = value.get();
        }
        return false;
    }
    private static void increment(AtomicLong value) { value.updateAndGet(old -> old == Long.MAX_VALUE ? old : old + 1); }
    public void maintain(Executor worker) {
        if (closed.get() || !running.compareAndSet(false, true)) return;
        try { worker.execute(() -> { try { cycle(); } finally { running.set(false); } }); }
        catch (RuntimeException rejected) { running.set(false); increment(failures); }
    }
    /** One background turn; deterministic tests use the identical path. */
    void cycle() {
        if (closed.get()) { if (scan != null) scan.close(); scan = null; return; }
        try {
            var batch = new ArrayList<RetentionEvidenceStore.Event>();
            for (int at = 0; at < BATCH; at++) {
                var event = queue.poll(); if (event == null) break; queued.decrementAndGet(); batch.add(event);
            }
            if (!batch.isEmpty()) {
                var result = evidence.recordBatch(batch, closed::get);
                if (result.status() == RetentionEvidenceStore.Status.RECORDED) {
                    for (int at = 0; at < batch.size(); at++) increment(recorded);
                } else increment(failures);
            }
            if (scan == null || !batch.isEmpty() || scan.state() == WorldRetentionManager.State.INVALID
                    || scan.state() == WorldRetentionManager.State.CANCELLED || idle++ >= 100) {
                if (scan != null) scan.close(); scan = manager.begin(); idle = 0;
            }
            if (scan.state() != WorldRetentionManager.State.READY) {
                var work = scan.step(closed::get); maxInspected = Math.max(maxInspected, work.inspected()); maxReadBytes = Math.max(maxReadBytes, work.readBytes());
            } else {
                var report = scan.collect(closed::get);
                for (int at = 0; at < report.collected(); at++) increment(collections);
                if (report.unavailable() > 0) increment(failures);
            }
        } catch (RuntimeException failure) { increment(failures); if (scan != null) scan.close(); scan = null; }
    }
    public Status status() { return new Status(queued.get(), recorded.get(), dropped.get(), failures.get(), collections.get(), highQueue.get(), maxInspected, maxReadBytes, running.get()); }
    /** Nonblocking stop. The composition closes stores after queued worker turns have exited. */
    @Override public void close() { closed.set(true); }
    /** Call on the storage worker before releasing its stores. */
    public void closeWorker() {
        close(); if (scan != null) scan.close(); scan = null;
        int discarded = queued.getAndSet(0); queue.clear();
        dropped.updateAndGet(old -> old > Long.MAX_VALUE - discarded ? Long.MAX_VALUE : old + discarded);
    }
}
