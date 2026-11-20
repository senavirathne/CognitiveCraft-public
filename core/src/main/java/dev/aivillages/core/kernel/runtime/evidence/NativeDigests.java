package dev.aivillages.core.kernel.runtime.evidence;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.function.Supplier;

/** Per-thread cache for hashes derived from immutable, canonical evidence records. */
final class NativeDigests {
    static final int CAPACITY = 128;
    private static final int WAYS = 4;
    private static final int BUCKETS = CAPACITY / WAYS;
    private static final ThreadLocal<Cache> CURRENT = ThreadLocal.withInitial(Cache::new);

    private static final class Cache {
        final WeakReference<?>[] records = new WeakReference<?>[CAPACITY];
        final String[] digests = new String[CAPACITY];
        final byte[] next = new byte[BUCKETS];
    }

    private NativeDigests() { }

    static String memo(Record record, Supplier<String> compute) {
        Objects.requireNonNull(record);
        var cache = CURRENT.get();
        int identity = System.identityHashCode(record);
        int bucket = (identity ^ (identity >>> 5)) & (BUCKETS - 1);
        int start = bucket * WAYS;
        int available = -1;
        for (int slot = start; slot < start + WAYS; slot++) {
            var reference = cache.records[slot];
            var previous = reference == null ? null : reference.get();
            if (previous == record) return cache.digests[slot];
            if (previous == null && available == -1) available = slot;
        }
        String digest = Objects.requireNonNull(compute.get());
        int slot = available;
        if (slot == -1) {
            slot = start + cache.next[bucket];
            cache.next[bucket] = (byte) ((cache.next[bucket] + 1) % WAYS);
        }
        cache.records[slot] = new WeakReference<>(record);
        cache.digests[slot] = digest;
        return digest;
    }
}
