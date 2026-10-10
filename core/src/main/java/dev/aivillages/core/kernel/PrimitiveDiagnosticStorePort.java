package dev.aivillages.core.kernel;

import java.io.IOException;
import java.util.List;
import dev.aivillages.core.kernel.PrimitiveDiagnosticCodec.Snapshot;

/** Worker-only fault seam. No Minecraft, provider, broker or authoritative repository references. */
public interface PrimitiveDiagnosticStorePort extends AutoCloseable {
    record ReplaySlice(List<Snapshot> rows,int inspected,int bytes,int invalid,boolean complete) { }
    record Usage(int peakFiles,long peakBytes,int segments,long segmentBytes,
                 long replayRecords,long replayBytes,int maxMaintenance) { }
    byte[] salt();
    ReplaySlice replay(int records,int bytes) throws IOException;
    int maintain(long now,int allowance) throws IOException;
    void append(byte[] bytes,long bucket,long now) throws IOException;
    void report(byte[] bytes,long earliestExpiry) throws IOException;
    Usage usage();
    @Override void close() throws IOException;
}
