package dev.aivillages.core.kernel.runtime.persistence;

/** Finite storage configuration. Quotas are logical capacity, never a disk durability promise. */
public record PersistencePolicy(int jobRows, int leaseRows, long jobBytes, long leaseBytes,
        long archiveBytes, int segments, int scanRows, int scanBytes, int deleteFiles,
        long eligibleAgeMillis, int pressurePercent, long reservedBytes, int reservations) {
    public static final int RECORD_BYTES = 65_536, SEGMENT_BYTES = 131_072, SEGMENT_ROWS = 64;
    public static final int MANIFEST_BYTES = 65_536, MAX_SEGMENTS = 128, IN_FLIGHT = 1;
    public static final int INTENT_BYTES = 4_096, RESULT_BYTES = 49_152,
            CANCELLATION_BYTES = 4_096, SETTLEMENT_BYTES = 8_192;
    public static final long MIB = 1_048_576L;
    public PersistencePolicy {
        long reservation = Math.addExact(Math.addExact(INTENT_BYTES, RESULT_BYTES),
                Math.addExact(CANCELLATION_BYTES, SETTLEMENT_BYTES));
        if (jobRows < 1 || jobRows > 64 || leaseRows < 1 || leaseRows > 128
                || jobBytes < RECORD_BYTES || jobBytes > 2*MIB || leaseBytes < RECORD_BYTES || leaseBytes > MIB
                || archiveBytes < MIB || archiveBytes > 64*MIB || segments < 1 || segments > MAX_SEGMENTS
                || scanRows < 1 || scanRows > 64 || scanBytes < RECORD_BYTES || scanBytes > SEGMENT_BYTES
                || deleteFiles < 0 || deleteFiles > 1 || eligibleAgeMillis < 0 || eligibleAgeMillis > 315_360_000_000L
                || pressurePercent < 50 || pressurePercent > 90 || reservations < 1 || reservations > 8
                || reservedBytes < Math.multiplyExact(reservation,reservations) || reservedBytes > archiveBytes/2)
            throw new IllegalArgumentException("Finite persistence capacity and reserved result headroom required");
    }
    public static PersistencePolicy defaults() {
        return new PersistencePolicy(48,96,MIB,MIB/2,8*MIB,128,16,131_072,1,
                30L*24*60*60*1000,80,512*1024,8);
    }
    public long ordinaryBytes() {
        return Math.min(Math.multiplyExact(archiveBytes,pressurePercent)/100,
                Math.subtractExact(archiveBytes,reservedBytes));
    }
    public int files() { return Math.addExact(segments,4); }
}
