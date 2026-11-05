package dev.aivillages.core.kernel.runtime.evidence;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.function.Supplier;

/** Per-thread cache for hashes derived from immutable, canonical evidence records. */
final class NativeDigests {
    static final int CAPACITY = 128;
    private static final ThreadLocal<Cache> CURRENT = ThreadLocal.withInitial(Cache::new);

    private static final class Cache {
        final WeakReference<?>[] records = new WeakReference<?>[CAPACITY];
        final String[] digests = new String[CAPACITY];
        int next;
    }

    private NativeDigests() { }

    static String memo(Record record, Supplier<String> compute) {
        Objects.requireNonNull(record);
        var cache = CURRENT.get();
        for (int n = 0; n < CAPACITY; n++) {
            var reference = cache.records[n];
            if (reference != null && reference.get() == record) return cache.digests[n];
        }
        String digest = Objects.requireNonNull(compute.get());
        int slot = cache.next;
        cache.records[slot] = new WeakReference<>(record);
        cache.digests[slot] = digest;
        cache.next = (slot + 1) % CAPACITY;
        return digest;
    }
}
