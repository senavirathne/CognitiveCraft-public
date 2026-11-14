package dev.aivillages.core.kernel;

import java.io.IOException;

/** A-only native-host metadata fixture. Uses the real codec; creates no Job or physical proof. */
public final class RuntimeAClosedSnapshotFixture {
    private RuntimeAClosedSnapshotFixture() { }
    public static byte[] encode(Jobs.Snapshot actual) throws IOException {
        try {
            byte[] encoded=JobHistoryCodec.encode(actual);
            var decoded=JobHistoryCodec.decode(encoded,actual.worldId());
            if(!decoded.jobs().equals(actual.jobs()) || !decoded.allocations().equals(actual.allocations()))
                throw new IOException("Actual closed Job metadata changed during archive round trip");
            return encoded;
        } catch(StrictJson.Invalid | IllegalArgumentException invalid) {
            throw new IOException("A02 fixture requires genuinely closed current Job metadata",invalid);
        }
    }
}
