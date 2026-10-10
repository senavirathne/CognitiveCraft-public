package dev.aivillages.core.kernel;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static dev.aivillages.core.kernel.SurvivalGateway.*;

/** Bounded gateway intake. The established world-storage executor is the sole publication worker. */
public final class ObservedKnowledgeService implements AutoCloseable {
    public static final int MAX_QUEUE = 32, MAX_OFFERS = 8, BATCH = 4;
    public record Status(int queued, long recorded, long dropped, long rejected) { }
    private final ObservedWorldKnowledgeStore store;
    private final ConcurrentLinkedQueue<ObservedEvidence> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger(), offers = new AtomicInteger();
    private final AtomicBoolean running = new AtomicBoolean(), closed = new AtomicBoolean();
    private final AtomicLong recorded = new AtomicLong(), dropped = new AtomicLong(), rejected = new AtomicLong();
    public ObservedKnowledgeService(ObservedWorldKnowledgeStore store) { this.store = Objects.requireNonNull(store); }
    public void beginTick() { offers.set(0); }
    public boolean offer(ObservedEvidence evidence) {
        Objects.requireNonNull(evidence);
        if (closed.get() || !reserve(offers, MAX_OFFERS) || !reserve(queued, MAX_QUEUE)) { increment(dropped); return false; }
        queue.offer(evidence); return true;
    }
    private static boolean reserve(AtomicInteger value, int maximum) {
        int prior = value.get();
        for (int attempt = 0; attempt < 8 && prior < maximum; attempt++) {
            if (value.compareAndSet(prior, prior + 1)) return true; prior = value.get();
        }
        return false;
    }
    private static void increment(AtomicLong value) { value.updateAndGet(n -> n == Long.MAX_VALUE ? n : n + 1); }
    public void maintain(Executor worker) {
        if (closed.get() || queued.get() == 0 || !running.compareAndSet(false, true)) return;
        try { worker.execute(() -> { try { cycle(); } finally { running.set(false); } }); }
        catch (RuntimeException refused) { running.set(false); increment(rejected); }
    }
    /** Same worker turn used by deterministic scheduling tests. No Minecraft or inference port exists. */
    void cycle() {
        for (int at = 0; at < BATCH && !closed.get(); at++) {
            var evidence = queue.poll(); if (evidence == null) return; queued.decrementAndGet();
            var result = store.recordObservation(evidence.owner(), evidence, closed::get);
            if (result.status() == ObservedWorldKnowledgeStore.Status.RECORDED
                    || result.status() == ObservedWorldKnowledgeStore.Status.UNCHANGED) increment(recorded);
            else increment(rejected);
        }
    }
    public Status status() { return new Status(queued.get(), recorded.get(), dropped.get(), rejected.get()); }
    @Override public void close() { closed.set(true); }
    public void closeWorker() {
        close(); int omitted = queued.getAndSet(0); queue.clear();
        dropped.updateAndGet(n -> n > Long.MAX_VALUE - omitted ? Long.MAX_VALUE : n + omitted);
    }
}
