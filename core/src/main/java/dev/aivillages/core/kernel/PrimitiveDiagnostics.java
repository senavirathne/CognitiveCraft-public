package dev.aivillages.core.kernel;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.EnumMap;

import static dev.aivillages.core.kernel.Contracts.*;

/** IMP-014.1 observation boundary, schema 1. No provider, world, storage or executable authority. */
public final class PrimitiveDiagnostics {
    public static final int SCHEMA = 1;
    public static final int CATALOG_SCHEMA = 1;
    public static final int MAX_CATALOG_SIGNATURES = 128;
    private PrimitiveDiagnostics() { }

    public enum Offer { ACCEPTED, DISABLED, FULL, UNAVAILABLE }

    /** Implementations must offer without I/O, waiting, retries or gameplay callbacks. */
    @FunctionalInterface public interface Sink {
        Offer offer(Observation observation);
        /** A bounded local counter may record this omission; there is no observation to persist. */
        default void catalogUnavailable() { }
    }
    private static final Sink NOOP = observation -> Offer.DISABLED;
    public static Sink noop() { return NOOP; }

    public enum Mode { OFF, METADATA, EXCERPTS }
    /** Schema-1 ceilings. Operators can only lower optional allowances. */
    public enum Limit {
        RECORD_BYTES(8192), REQUEST_BYTES(1024), EXCERPT_BYTES(4096),
        QUEUE(128), QUEUE_BYTES(1048576), OFFERS_PER_TICK(8),
        DEQUEUE(32), MAINTENANCE(32), WRITES(16), WRITE_BYTES(131072),
        AGGREGATES(512), DEDUP(1024), SEGMENT_BYTES(1048576), SEGMENTS(8),
        DIRECTORY_BYTES(10485760), FILES(16), REPLAY_BYTES(8388608),
        REPLAY_RECORDS(65536), REPORT_ROWS(100), REPORT_BYTES(65536),
        DRAIN_EVENTS(32), DRAIN_BYTES(131072), DRAIN_MILLIS(2000),
        RETENTION_MILLIS(604800000);
        public final int maximum;
        Limit(int maximum) { this.maximum = maximum; }
    }
    public record Policy(Mode mode, Map<Limit, Long> allowances, Set<ScopeRef> excerptScopes) {
        public Policy {
            Objects.requireNonNull(mode); Objects.requireNonNull(allowances);
            excerptScopes = Set.copyOf(excerptScopes);
            if (excerptScopes.size() > 64 || mode == Mode.EXCERPTS && excerptScopes.isEmpty())
                throw new IllegalArgumentException("Diagnostic scope policy");
            var checked = new EnumMap<Limit, Long>(Limit.class);
            for (Limit limit : Limit.values()) {
                long value = allowances.getOrDefault(limit, (long) limit.maximum);
                if (value < 0 || value > limit.maximum)
                    throw new IllegalArgumentException("Diagnostic allowance");
                checked.put(limit, value);
            }
            long queue = checked.get(Limit.QUEUE), bytes = checked.get(Limit.QUEUE_BYTES);
            if (queue > 0 && bytes > 0 && bytes < Math.multiplyExact(queue, 8192L))
                throw new IllegalArgumentException("Diagnostic queue reservation");
            long record = checked.get(Limit.RECORD_BYTES), segment = checked.get(Limit.SEGMENT_BYTES);
            long write = checked.get(Limit.WRITE_BYTES), directory = checked.get(Limit.DIRECTORY_BYTES);
            if (record > 0 && segment > 0 && record > segment
                    || record > 0 && write > 0 && record > write
                    || segment > 0 && directory > 0 && Math.addExact(segment,32) > directory)
                throw new IllegalArgumentException("Diagnostic size relationships");
            long reservation = Math.addExact(2L * 1024 * 1024,
                    Math.addExact(Math.multiplyExact(queue, 8192L),
                    Math.addExact(Math.multiplyExact(checked.get(Limit.AGGREGATES), 24576L),
                            Math.multiplyExact(checked.get(Limit.DEDUP), 512L))));
            if (reservation > 16L * 1024 * 1024)
                throw new IllegalArgumentException("Diagnostic working reservation");
            allowances = Map.copyOf(checked);
        }
        public static Policy off() { return new Policy(Mode.OFF, Map.of(), Set.of()); }
        public static Policy metadata() { return new Policy(Mode.METADATA, Map.of(), Set.of()); }
        public int limit(Limit limit) { return Math.toIntExact(allowances.get(limit)); }
        public boolean enabled() {
            return mode != Mode.OFF && List.of(Limit.RECORD_BYTES, Limit.QUEUE, Limit.QUEUE_BYTES,
                    Limit.OFFERS_PER_TICK, Limit.DEQUEUE, Limit.MAINTENANCE, Limit.WRITES,
                    Limit.WRITE_BYTES, Limit.AGGREGATES, Limit.DEDUP, Limit.SEGMENT_BYTES,
                    Limit.SEGMENTS, Limit.DIRECTORY_BYTES, Limit.FILES, Limit.REPLAY_BYTES,
                    Limit.REPLAY_RECORDS, Limit.RETENTION_MILLIS).stream().allMatch(l -> limit(l) > 0);
        }
        public long workingReservationBytes() {
            return 2L * 1024 * 1024 + (long) limit(Limit.QUEUE) * 8192
                    + (long) limit(Limit.AGGREGATES) * 24576 + (long) limit(Limit.DEDUP) * 512;
        }
    }
    public enum Counter {
        ACCEPTED, QUEUE_DROP, TICK_DROP, NOT_READY, INVALID, DUPLICATE,
        AGGREGATE_DROP, OVERSIZE, SATURATED, CATALOG_OMITTED, IO_FAILURE,
        RETENTION_FAILURE, REPLAY_INVALID, CLOCK_ANOMALY, SHUTDOWN_DROP
    }

    /** Registered request types only, prepared off-thread; no values or free-form context. */
    public static String requestShape(CapabilitySpec spec, Generation.Role role) {
        return StrictJson.canonical(Map.of("capability", spec.id().name(),
                "version", (long) spec.id().version(), "role", role.name(),
                "parameters", spec.parameters().stream().map(p -> (Object) Map.of(
                        "name", p.name(), "type", p.type().name())).toList()));
    }

    /** Safe descriptive metadata prepared off the owner thread; omitted text is never retained. */
    public record ProviderMetadata(String protocol, String model, String digest,
                                   boolean modelOmitted, boolean digestOmitted) {
        public ProviderMetadata {
            if (protocol == null || !protocol.matches("[a-z0-9][a-z0-9._-]{0,63}")
                    || model != null && !safeDescriptor(model)
                    || digest != null && !safeDescriptor(digest)
                    || modelOmitted && model != null || digestOmitted && digest != null)
                throw new IllegalArgumentException("Diagnostic provider metadata");
        }
        public static ProviderMetadata project(Generation.Descriptor descriptor) {
            Objects.requireNonNull(descriptor);
            boolean modelSafe = safeDescriptor(descriptor.model());
            boolean digestSafe = descriptor.digest() == null || safeDescriptor(descriptor.digest());
            return new ProviderMetadata(descriptor.protocol(), modelSafe ? descriptor.model() : null,
                    digestSafe ? descriptor.digest() : null, !modelSafe, !digestSafe);
        }
    }
    private static boolean safeDescriptor(String value) {
        // Allowlisted ASCII metadata excludes controls, bidi, invalid Unicode and escaping growth.
        return value != null && value.matches("[a-zA-Z0-9._:/@+\\-]{1,128}");
    }

    /** Internal trusted correlation only. Persistence must HMAC these IDs; public status omits it. */
    public record Observation(TrustedContext owner, UUID attemptId, UUID generationId,
            Generation.Role role, CapabilityId capability, ProviderMetadata provider,
            SkillCompiler.UnsupportedPrimitiveReference reference, String candidateSha256,
            String requestShape) {
        public Observation(TrustedContext owner, UUID attemptId, UUID generationId,
                Generation.Role role, CapabilityId capability, ProviderMetadata provider,
                SkillCompiler.UnsupportedPrimitiveReference reference, String candidateSha256) {
            this(owner, attemptId, generationId, role, capability, provider, reference, candidateSha256, "");
        }
        public Observation {
            Objects.requireNonNull(owner); Objects.requireNonNull(attemptId);
            Objects.requireNonNull(generationId); Objects.requireNonNull(role);
            Objects.requireNonNull(capability); Objects.requireNonNull(provider);
            Objects.requireNonNull(reference);
            if (reference.catalogFingerprint() == null || candidateSha256 == null
                    || !candidateSha256.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Diagnostic correlation metadata");
            if (requestShape == null || requestShape.length() > 2048)
                throw new IllegalArgumentException("Diagnostic request projection");
        }
    }

    /** Explicit complete, immutable catalog: lookup and diagnostic identity use the same snapshot. */
    public static final class CatalogSnapshot implements PrimitiveCatalog {
        private final Map<CapabilityId, PrimitiveSignature> signatures;
        private final String fingerprint;
        private CatalogSnapshot(List<PrimitiveSignature> completeRuntimeSignatures) {
            Objects.requireNonNull(completeRuntimeSignatures);
            if (completeRuntimeSignatures.size() > MAX_CATALOG_SIGNATURES)
                throw new IllegalArgumentException("Diagnostic catalog bound");
            Map<CapabilityId, PrimitiveSignature> entries = new HashMap<>();
            for (PrimitiveSignature signature : completeRuntimeSignatures) {
                CapabilityId key = new CapabilityId(signature.id(), signature.version());
                if (entries.putIfAbsent(key, signature) != null)
                    throw new IllegalArgumentException("Duplicate catalog pair");
            }
            signatures = Map.copyOf(entries);
            MessageDigest digest = sha256();
            digest.update(ByteBuffer.allocate(8).putInt(CATALOG_SCHEMA).putInt(entries.size()).array());
            for (PrimitiveSignature signature : entries.values().stream().sorted(
                    Comparator.comparing(PrimitiveSignature::id)
                            .thenComparingInt(PrimitiveSignature::version)).toList()) {
                List<Object> parameters = signature.parameters().stream().map(p -> (Object) Map.of(
                        "name", p.name(), "type", p.type().name(),
                        "minimum", p.minimum(), "maximum", p.maximum())).toList();
                byte[] row = StrictJson.canonical(Map.of("id", signature.id(),
                        "version", (long) signature.version(), "fingerprint", signature.fingerprint(),
                        "parameters", parameters, "result", signature.resultType().name(),
                        "effects", signature.effects().stream().map(Enum::name).sorted().toList()))
                        .getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(row.length).array());
                digest.update(row);
            }
            fingerprint = HexFormat.of().formatHex(digest.digest());
        }
        @Override public Optional<PrimitiveSignature> find(String id, int version) {
            try { return Optional.ofNullable(signatures.get(new CapabilityId(id, version))); }
            catch (IllegalArgumentException invalid) { return Optional.empty(); }
        }
        public String fingerprint() { return fingerprint; }
        public int size() { return signatures.size(); }
    }
    /** Only the concrete catalog owner can assert completeness; never use a generation subset. */
    public static CatalogSnapshot completeCatalog(List<PrimitiveSignature> completeRuntimeSignatures) {
        return new CatalogSnapshot(completeRuntimeSignatures);
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
