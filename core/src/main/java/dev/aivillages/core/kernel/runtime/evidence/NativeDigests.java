package dev.aivillages.core.kernel.runtime.evidence;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.Supplier;

/** Per-thread cache for hashes derived from immutable, canonical evidence records. */
final class NativeDigests {
    static final int CAPACITY = 128;
    private static final ThreadLocal<Cache> CURRENT = ThreadLocal.withInitial(Cache::new);

    private static final class Cache {
        final Key[] order = new Key[CAPACITY];
        final HashMap<Key,String> digests = new HashMap<>(CAPACITY * 2);
        int next;
    }

    private static final class Key extends WeakReference<Record> {
        private final int identity;

        Key(Record record) {
            super(record);
            identity = System.identityHashCode(record);
        }

        @Override public int hashCode() { return identity; }

        @Override public boolean equals(Object other) {
            if (other == this) return true;
            Record record = get();
            return record != null && other instanceof Key key && record == key.get();
        }
    }

    private NativeDigests() { }

    static String memo(Record record, Supplier<String> compute) {
        Objects.requireNonNull(record);
        var cache = CURRENT.get();
        var key = new Key(record);
        String cached = cache.digests.get(key);
        if (cached != null) return cached;
        String digest = Objects.requireNonNull(compute.get());
        // Preserve the original per-thread FIFO bound, including cleared weak keys.
        int slot = cache.next;
        if (cache.order[slot] != null) cache.digests.remove(cache.order[slot]);
        cache.order[slot] = key;
        cache.digests.put(key, digest);
        cache.next = (slot + 1) % CAPACITY;
        return digest;
    }
}
