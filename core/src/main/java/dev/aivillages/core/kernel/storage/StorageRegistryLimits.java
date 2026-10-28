package dev.aivillages.core.kernel.storage;

/** Hard world ceilings include tombstones and stale role references; tests may only tighten them. */
public record StorageRegistryLimits(int records, int assignments, int snapshotBytes, int queuedWrites) {
    public static final int MAX_RECORDS = 4096;
    public static final int MAX_ASSIGNMENTS = 4096;
    public static final int MAX_GRANTS = 32;
    public static final int MAX_ROLES = 4;
    public static final int MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;
    public static final long MAX_INTEGER = 9_007_199_254_740_991L;
    public StorageRegistryLimits {
        if (records < 1 || records > MAX_RECORDS || assignments < 1 || assignments > MAX_ASSIGNMENTS
                || snapshotBytes < 1024 || snapshotBytes > MAX_SNAPSHOT_BYTES || queuedWrites < 1 || queuedWrites > 64)
            throw new IllegalArgumentException("metadata limits may only tighten hard ceilings");
    }
    public static StorageRegistryLimits defaults() { return new StorageRegistryLimits(MAX_RECORDS,MAX_ASSIGNMENTS,MAX_SNAPSHOT_BYTES,32); }
    /** Pending replacement, previous copy and rollback room, in addition to current allocations. */
    public long replacementHeadroomBytes() { return 3L * snapshotBytes + 65_536; }
}
