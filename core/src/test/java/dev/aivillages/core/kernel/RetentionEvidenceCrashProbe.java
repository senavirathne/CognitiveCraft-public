package dev.aivillages.core.kernel;

import java.nio.file.*;
import java.time.*;
import java.util.UUID;

/** Forked acceptance process: no shutdown hook or finally block runs after SIGKILL. */
public final class RetentionEvidenceCrashProbe {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]), flag = Path.of(args[3]);
        var point = RetentionEvidenceStore.Point.valueOf(args[2]);
        Clock clock = Clock.fixed(Instant.ofEpochMilli(604800101), ZoneOffset.UTC);
        try (var store = RetentionEvidenceStore.open(root, UUID.fromString(args[1]), RetentionEvidenceStore.Limits.defaults(), clock, at -> {
            if (at == point) {
                Files.writeString(flag, point.name());
                for (;;) try { Thread.sleep(1000); } catch (InterruptedException interrupted) { throw new java.io.IOException(interrupted); }
            }
        })) { store.compact(store.snapshot().revision(), () -> false); }
    }
}
