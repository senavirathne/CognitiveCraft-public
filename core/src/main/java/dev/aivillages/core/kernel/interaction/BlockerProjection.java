package dev.aivillages.core.kernel.interaction;

import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import dev.aivillages.core.kernel.interaction.contract.InteractionWire;
import dev.aivillages.core.kernel.production.planning.PlanningModel.Snapshot;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Pure disclosure-safe blocker projection. Every wire value remains an untrusted projection. */
public final class BlockerProjection {
    public static final long MAX_INTEGER = 9_007_199_254_740_991L;
    public static final int MAX_ROWS = 128;
    public static final long MAX_AGE_TICKS = 2;
    public enum Code {
        INSUFFICIENT_MATERIAL, NO_SUPPORTED_PRODUCTION_PATH, UNSUPPORTED_EFFECT, UNKNOWN_WORLD_STATE,
        UNAUTHORIZED, DESTINATION_FULL, TARGET_UNAVAILABLE, AMBIGUOUS_TARGET, RESOURCE_RESERVED,
        ACTOR_UNAVAILABLE, BUDGET_OR_DEADLINE_EXCEEDED, STALE_REFERENCE, INCOMPATIBLE_SCHEMA,
        SAME_ENDPOINT, DEPENDENCY_NOT_QUALIFIED
    }

    /** Explicit correlation supplied by a read-only server adapter, never inferred from ordinary chat. */
    public record ActivityRef(UUID world, UUID domain, UUID principal, UUID root, UUID originalOwner,
                              UUID attempt, UUID citizen, long generation, long revision,
                              String originalBudgetDigest, long absoluteDeadlineMillis) {
        public ActivityRef {
            Objects.requireNonNull(world); Objects.requireNonNull(domain); Objects.requireNonNull(principal);
            Objects.requireNonNull(root); Objects.requireNonNull(originalOwner); Objects.requireNonNull(attempt); Objects.requireNonNull(citizen);
            bounded(generation, 1); bounded(revision, 0); bounded(absoluteDeadlineMillis, 1);
            if (originalBudgetDigest == null || !originalBudgetDigest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("budget digest");
        }
        public Map<String, Object> value() {
            return Map.of("scope", Map.of("world_id", world.toString(), "domain_id", domain.toString(), "principal_id", principal.toString()),
                    "root_id", root.toString(), "original_owner_id", originalOwner.toString(), "attempt_id", attempt.toString(),
                    "responsible_citizen_id", citizen.toString(), "execution_generation", generation, "root_revision", revision,
                    "original_budget_digest", originalBudgetDigest, "absolute_deadline_ms", absoluteDeadlineMillis);
        }
        boolean sameScope(Snapshot s) {
            return world.equals(s.world()) && world.equals(s.context().scope().worldId())
                    && domain.equals(s.context().scope().domainId()) && principal.equals(s.context().principal().id());
        }
        boolean matches(Snapshot s) {
            return sameScope(s) && root.equals(s.root()) && originalOwner.equals(s.owner().originalOwner())
                    && citizen.equals(s.owner().executor().citizenId()) && generation == s.owner().executionGeneration()
                    && revision == s.revision() && originalBudgetDigest.equals(s.budget().initialDigest())
                    && absoluteDeadlineMillis == s.budget().absoluteDeadlineMillis();
        }
    }

    /** No raw item/name/count fields: irrelevant private rows cannot become disclosure data. */
    public record Row(UUID id, boolean relevant, boolean authorized, boolean fresh, Code code) {
        public Row { Objects.requireNonNull(id); }
    }
    public record Evidence(ActivityRef activity, UUID evidenceId, long observedTick, long worldRevision,
                           boolean complete, boolean factsAuthorized, boolean assistancePermitted, List<Row> rows) {
        public Evidence {
            Objects.requireNonNull(activity); Objects.requireNonNull(evidenceId); bounded(observedTick, 0); bounded(worldRevision, 0);
            rows = List.copyOf(rows);
            if (rows.size() > MAX_ROWS || rows.stream().map(Row::id).distinct().count() != rows.size())
                throw new IllegalArgumentException("bounded distinct observation rows");
        }
    }
    public static final class Projection {
        private final InteractionWire.UntrustedMessage wire;
        private Projection(Map<String, Object> body) {
            wire = new InteractionCodec().decode(InteractionCodec.canonical(Map.of("schema", InteractionWire.VERSION,
                    "kind", "BlockerEvidence", "body", body)));
        }
        public InteractionWire.UntrustedMessage wire() { return wire; }
        public Code code() { return Code.valueOf((String) wire.body().get("code")); }
        public String json() { return new InteractionCodec().encode(wire); }
    }

    public Optional<Projection> project(Evidence e, long currentTick) {
        bounded(currentTick, 0);
        if (!e.factsAuthorized() || !e.assistancePermitted()
                || e.rows().stream().anyMatch(r -> r.relevant() && (!r.authorized() || r.code() == Code.UNAUTHORIZED)))
            return Optional.of(failure(e, Code.UNAUTHORIZED, "A_RUNTIME"));
        if (currentTick < e.observedTick() || currentTick - e.observedTick() > MAX_AGE_TICKS
                || e.rows().stream().anyMatch(r -> r.relevant() && (!r.fresh() || r.code() == Code.STALE_REFERENCE)))
            return Optional.of(failure(e, Code.STALE_REFERENCE, "A_RUNTIME"));
        if (!e.complete()) return Optional.of(failure(e, Code.UNKNOWN_WORLD_STATE, "A_RUNTIME"));
        return e.rows().stream().filter(Row::relevant).filter(r -> r.code() != null)
                .map(r -> r.code() == Code.INSUFFICIENT_MATERIAL ? Code.UNKNOWN_WORLD_STATE : r.code())
                .min(Comparator.comparingInt(BlockerProjection::rank).thenComparing(Enum::name))
                .map(code -> failure(e, code, "A_RUNTIME"));
    }

    static int rank(Code code) {
        return switch (code) {
            case UNAUTHORIZED -> 0;
            case STALE_REFERENCE, UNKNOWN_WORLD_STATE, INCOMPATIBLE_SCHEMA -> 1;
            case UNSUPPORTED_EFFECT, NO_SUPPORTED_PRODUCTION_PATH -> 2;
            case ACTOR_UNAVAILABLE, TARGET_UNAVAILABLE, AMBIGUOUS_TARGET, DESTINATION_FULL,
                    RESOURCE_RESERVED, SAME_ENDPOINT, DEPENDENCY_NOT_QUALIFIED, BUDGET_OR_DEADLINE_EXCEEDED -> 3;
            case INSUFFICIENT_MATERIAL -> 4;
        };
    }
    static Projection failure(Evidence e, Code code, String source) {
        boolean redacted = code == Code.UNAUTHORIZED;
        boolean unknown = redacted || rank(code) == 1 || code == Code.DEPENDENCY_NOT_QUALIFIED;
        return projection(e, code, source, redacted, unknown ? "UNKNOWN" : "KNOWN",
                code == Code.UNSUPPORTED_EFFECT || code == Code.NO_SUPPORTED_PRODUCTION_PATH ? "UNSUPPORTED" : "UNKNOWN", List.of(), null);
    }
    static Projection material(Evidence e, List<Map<String, Object>> requirements, UUID storage) {
        return projection(e, Code.INSUFFICIENT_MATERIAL, "B_PLANNER", false, "KNOWN", "FEASIBLE_AFTER_ASSISTANCE", requirements, storage);
    }
    private static Projection projection(Evidence e, Code code, String source, boolean redacted, String knowledge,
                                         String continuation, List<Map<String, Object>> requirements, UUID storage) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.name()); body.put("source", source); body.put("activity", redacted ? null : e.activity().value());
        body.put("evidence_id", e.evidenceId().toString()); body.put("observed_tick", e.observedTick()); body.put("world_revision", e.worldRevision());
        body.put("visibility", redacted ? "REDACTED" : "AUTHORIZED_FACTS"); body.put("knowledge", knowledge);
        body.put("continuation", continuation); body.put("requirements", requirements); body.put("storage_id", storage == null ? null : storage.toString());
        return new Projection(body);
    }
    static void bounded(long n, long minimum) {
        if (n < minimum || n > MAX_INTEGER) throw new IllegalArgumentException("exact transport integer range");
    }
}
