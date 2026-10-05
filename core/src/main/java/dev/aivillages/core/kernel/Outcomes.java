package dev.aivillages.core.kernel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static dev.aivillages.core.kernel.Contracts.*;

/** Shared reason encoding with three distinct stage outcomes; wire schema 1. */
public final class Outcomes {
    private Outcomes() { }

    public enum Reason {
        REQUEST_INVALID, RESOURCE_MISSING, FACILITY_MISSING, KNOWLEDGE_REQUIRED,
        AUTHORITY_DENIED, UNSUPPORTED_PRIMITIVE, STALE_OBSERVATION,
        TARGET_UNAVAILABLE, TARGET_INVALID, DEPENDENCY_INCOMPATIBLE,
        ARTIFACT_INCOMPATIBLE, ARTIFACT_INVALID, ARTIFACT_QUARANTINED,
        BUDGET_EXHAUSTED, MODEL_UNAVAILABLE, STORAGE_LIMIT_REACHED,
        STORAGE_UNAVAILABLE, ACTOR_UNAVAILABLE, ACTION_TIMEOUT, ACTION_FAILED,
        CANCELLED, INTERRUPTED
    }
    public enum ResolutionStatus {
        RESOLVED, BLOCKED, UNSUPPORTED_RUNTIME, MISSING_IMPLEMENTATION,
        INCOMPATIBLE, UNAUTHORIZED, NEEDS_PLANNING
    }
    public enum ExecutionStatus { SUCCEEDED, BLOCKED, FAILED, CANCELLED, INTERRUPTED }
    public enum ResearchStatus { ADMITTED, NOT_ADMITTED, BLOCKED, CANCELLED, INTERRUPTED }

    public sealed interface StageOutcome permits Resolution, Execution, Research { }

    public record Resolution(ResolutionStatus status, Reason reason, ArtifactRef artifact,
                             String snapshot) implements StageOutcome {
        public Resolution {
            Objects.requireNonNull(status);
            if (snapshot == null || snapshot.isBlank() || snapshot.length() > 128)
                throw new IllegalArgumentException("Snapshot");
            if (status == ResolutionStatus.RESOLVED) {
                if (artifact == null || reason != null) throw new IllegalArgumentException("Resolved shape");
            } else if (status == ResolutionStatus.MISSING_IMPLEMENTATION
                    || status == ResolutionStatus.NEEDS_PLANNING) {
                if (artifact != null || reason != null) throw new IllegalArgumentException("Routing shape");
            } else if (artifact != null || reason == null) {
                throw new IllegalArgumentException("Rejection shape");
            }
        }
    }
    public record Execution(ExecutionStatus status, Reason reason, long committedEffects,
                            EvidenceRef completion) implements StageOutcome {
        public Execution {
            Objects.requireNonNull(status);
            if (committedEffects < 0) throw new IllegalArgumentException("Effect count");
            if (status == ExecutionStatus.SUCCEEDED ? reason != null || completion == null
                    : reason == null || completion != null) throw new IllegalArgumentException("Execution shape");
            if ((status == ExecutionStatus.CANCELLED && reason != Reason.CANCELLED)
                    || (status == ExecutionStatus.INTERRUPTED && reason != Reason.INTERRUPTED))
                throw new IllegalArgumentException("Terminal reason");
        }
    }
    public record Research(ResearchStatus status, Reason reason, ArtifactRef artifact,
                           EvidenceRef evidence, boolean published, long modelCalls)
                           implements StageOutcome {
        public Research {
            Objects.requireNonNull(status);
            if (modelCalls < 0) throw new IllegalArgumentException("Model calls");
            if (status == ResearchStatus.ADMITTED
                    ? reason != null || artifact == null || evidence == null || !published
                    : reason == null || published)
                throw new IllegalArgumentException("Research shape");
            if ((status == ResearchStatus.CANCELLED && reason != Reason.CANCELLED)
                    || (status == ResearchStatus.INTERRUPTED && reason != Reason.INTERRUPTED))
                throw new IllegalArgumentException("Terminal reason");
        }
    }

    public static String encode(StageOutcome result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schema", 1L);
        if (result instanceof Resolution value) {
            map.put("stage", "resolution"); map.put("status", value.status().name());
            map.put("snapshot", value.snapshot());
            if (value.artifact() != null) map.put("artifact", ref(value.artifact()));
            if (value.reason() != null) map.put("reason", value.reason().name());
        } else if (result instanceof Execution value) {
            map.put("stage", "execution"); map.put("status", value.status().name());
            map.put("committedEffects", value.committedEffects());
            if (value.completion() != null) map.put("completion", evidence(value.completion()));
            if (value.reason() != null) map.put("reason", value.reason().name());
        } else if (result instanceof Research value) {
            map.put("stage", "research"); map.put("status", value.status().name());
            map.put("published", value.published()); map.put("modelCalls", value.modelCalls());
            if (value.artifact() != null) map.put("artifact", ref(value.artifact()));
            if (value.evidence() != null) map.put("evidence", evidence(value.evidence()));
            if (value.reason() != null) map.put("reason", value.reason().name());
        } else throw new IllegalArgumentException("Stage");
        return StrictJson.canonical(map);
    }

    public static StageOutcome decode(String json) throws StrictJson.Invalid {
        Map<String, Object> map = StrictJson.object(json);
        long schema = number(map, "schema", "$");
        if (schema != 1) throw new StrictJson.Invalid("$.schema", "UNKNOWN_SCHEMA");
        String stage = string(map, "stage", "$");
        try {
            Reason reason = map.containsKey("reason") ? Reason.valueOf(string(map, "reason", "$")) : null;
            return switch (stage) {
                case "resolution" -> {
                    allowed(map, "schema", "stage", "status", "snapshot", "artifact", "reason");
                    ResolutionStatus status = ResolutionStatus.valueOf(string(map, "status", "$"));
                    ArtifactRef artifact = map.containsKey("artifact")
                            ? ref(object(map, "artifact", "$")) : null;
                    yield new Resolution(status, reason, artifact, string(map, "snapshot", "$"));
                }
                case "execution" -> {
                    exact(map, "schema", "stage", "status", "committedEffects",
                            reason == null ? "completion" : "reason");
                    ExecutionStatus status = ExecutionStatus.valueOf(string(map, "status", "$"));
                    EvidenceRef evidence = reason == null ? evidence(object(map, "completion", "$")) : null;
                    yield new Execution(status, reason, number(map, "committedEffects", "$"), evidence);
                }
                case "research" -> {
                    boolean published = bool(map, "published", "$");
                    allowed(map, "schema", "stage", "status", "published", "modelCalls",
                            "artifact", "reason", "evidence");
                    ResearchStatus status = ResearchStatus.valueOf(string(map, "status", "$"));
                    yield new Research(status, reason,
                            map.containsKey("artifact") ? ref(object(map, "artifact", "$")) : null,
                            map.containsKey("evidence") ? evidence(object(map, "evidence", "$")) : null,
                            published, number(map, "modelCalls", "$"));
                }
                default -> throw new StrictJson.Invalid("$.stage", "UNKNOWN_STAGE");
            };
        } catch (IllegalArgumentException exception) {
            throw new StrictJson.Invalid("$", "INVALID_OUTCOME");
        }
    }

    public static String encodeReason(Reason reason) {
        return StrictJson.canonical(Map.of("schema", 1L, "reason", reason.name()));
    }
    public static Reason decodeReason(String json) throws StrictJson.Invalid {
        Map<String, Object> map = StrictJson.object(json);
        exact(map, "schema", "reason");
        if (number(map, "schema", "$") != 1) throw new StrictJson.Invalid("$.schema", "UNKNOWN_SCHEMA");
        try { return Reason.valueOf(string(map, "reason", "$")); }
        catch (IllegalArgumentException exception) { throw new StrictJson.Invalid("$.reason", "INVALID_REASON"); }
    }

    private static Map<String, Object> ref(ArtifactRef ref) {
        return Map.of("capability", ref.capability().name(), "version", (long) ref.capability().version(),
                "sha256", ref.sha256());
    }
    private static ArtifactRef ref(Map<String, Object> map) throws StrictJson.Invalid {
        exact(map, "capability", "version", "sha256");
        return new ArtifactRef(new CapabilityId(string(map, "capability", "$"),
                Math.toIntExact(number(map, "version", "$"))), string(map, "sha256", "$"));
    }
    private static Map<String, Object> evidence(EvidenceRef evidence) {
        return Map.of("id", evidence.id(), "validatorVersion", evidence.validatorVersion(),
                "scope", evidence.scope());
    }
    private static EvidenceRef evidence(Map<String, Object> map) throws StrictJson.Invalid {
        exact(map, "id", "validatorVersion", "scope");
        return new EvidenceRef(string(map, "id", "$"), string(map, "validatorVersion", "$"),
                string(map, "scope", "$"));
    }
    static void exact(Map<String, Object> map, String... keys) throws StrictJson.Invalid {
        java.util.Set<String> expected = new java.util.HashSet<>();
        for (String key : keys) if (key != null) expected.add(key);
        if (!map.keySet().equals(expected)) throw new StrictJson.Invalid("$", "UNKNOWN_OR_MISSING_FIELD");
    }
    private static void allowed(Map<String, Object> map, String... keys) throws StrictJson.Invalid {
        if (!java.util.Set.of(keys).containsAll(map.keySet()))
            throw new StrictJson.Invalid("$", "UNKNOWN_FIELD");
    }
    static Map<String, Object> object(Map<String, Object> map, String key, String path)
            throws StrictJson.Invalid {
        if (!(map.get(key) instanceof Map<?, ?> raw))
            throw new StrictJson.Invalid(path + "." + key, "EXPECTED_OBJECT");
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) raw;
        return result;
    }
    static String string(Map<String, Object> map, String key, String path) throws StrictJson.Invalid {
        if (!(map.get(key) instanceof String value))
            throw new StrictJson.Invalid(path + "." + key, "EXPECTED_STRING");
        return value;
    }
    static long number(Map<String, Object> map, String key, String path) throws StrictJson.Invalid {
        if (!(map.get(key) instanceof Long value))
            throw new StrictJson.Invalid(path + "." + key, "EXPECTED_INTEGER");
        return value;
    }
    static boolean bool(Map<String, Object> map, String key, String path) throws StrictJson.Invalid {
        if (!(map.get(key) instanceof Boolean value))
            throw new StrictJson.Invalid(path + "." + key, "EXPECTED_BOOLEAN");
        return value;
    }
}
