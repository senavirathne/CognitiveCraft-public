package dev.aivillages.core.kernel.runtime.evidence;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeDigestsTest {
    private record Value(String text) {
        @Override public int hashCode() { return 0; }
    }

    private String hash(Value value, AtomicInteger calls) {
        return NativeDigests.memo(value, () -> {
            calls.incrementAndGet();
            return NativeDeposit.sha(value.text());
        });
    }

    @Test void sameImmutableRecordReusesHashAndCollidingRecordsKeepSeparateHashes() {
        var calls = new AtomicInteger();
        var value = new Value("original");
        String expected = NativeDeposit.sha("original");
        assertEquals(expected, hash(value, calls));
        assertEquals(expected, hash(value, calls));
        assertEquals(1, calls.get());
        assertEquals(expected, hash(new Value("original"), calls));
        assertNotEquals(expected, hash(new Value("different"), calls));
        assertEquals(3, calls.get());
    }

    @Test void evictionKeepsTheCanonicalHash() {
        var calls = new AtomicInteger();
        var first = new Value("bounded");
        String expected = hash(first, calls);
        for (int n = 0; n < NativeDigests.CAPACITY; n++) hash(new Value("other-" + n), new AtomicInteger());
        assertEquals(expected, hash(first, calls));
        assertEquals(2, calls.get());
    }

    @Test void storageWorkerHasAnIndependentCache() {
        var calls = new AtomicInteger();
        var value = new Value("worker");
        String expected = hash(value, calls);
        assertEquals(expected, CompletableFuture.supplyAsync(() -> hash(value, calls)).join());
        assertEquals(expected, hash(value, calls));
        assertEquals(2, calls.get());
    }
}
