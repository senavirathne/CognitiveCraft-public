package dev.aivillages.core.kernel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;

/** IMP-015 policy 1. Pure bounded assessments over a captured owner's graph; no persistence or AI. */
public final class ArtifactCompatibility {
    public static final int POLICY = 1;
    private ArtifactCompatibility() { }

    public record Limits(int visits, int depth, int diagnostics, long compilerBytes) {
        public Limits {
            if (visits < 0 || visits > 128 || depth < 0 || depth > 8
                    || diagnostics < 0 || diagnostics > 16
                    || compilerBytes < 0 || compilerBytes > 8_388_608)
                throw new IllegalArgumentException("Compatibility limits");
        }
        public static Limits defaults() { return new Limits(128, 8, 16, 8_388_608); }
    }
    public enum Issue {
        MISSING_ARTIFACT, CORRUPT_BODY, UNSUPPORTED_FORMAT, ADMISSION_RESTRICTED,
        GAME_TARGET, CAPABILITY_CONTRACT, PRIMITIVE_SIGNATURE, DEPENDENCY_CYCLE,
        TRAVERSAL_LIMIT, COMPILER_LIMIT, BODY_INVALID, RUNTIME_UNAVAILABLE
    }
    public enum Validation { CURRENT_STATIC_CHECK, CURRENT_FIXTURE, AUTHORIZED_BEHAVIORAL_ADMISSION }
    public record Cause(ArtifactRef affected, Issue issue, Reason reason,
                        PrimitiveRequirement primitive, List<ArtifactRef> path) {
        public Cause {
            Objects.requireNonNull(affected); Objects.requireNonNull(issue); Objects.requireNonNull(reason);
            path = List.copyOf(path);
            if (path.isEmpty() || path.size() > 10 || !path.getLast().equals(affected))
                throw new IllegalArgumentException("Compatibility cause path");
        }
    }
    /** Advisory requirements do not schedule compilation, research, repair or admission. */
    public record Revalidation(ArtifactRef affected, Issue issue, Set<Validation> stages) {
        public Revalidation { stages = Set.copyOf(stages); }
    }
    public record Assessment(Compatibility compatibility, List<Cause> causes,
                             List<Revalidation> revalidation, int visits, long compilerBytes,
                             boolean diagnosticsComplete, String relevantFingerprint) {
        public Assessment {
            causes = List.copyOf(causes); revalidation = List.copyOf(revalidation);
            if (causes.size() > 16 || revalidation.size() > 16 || visits < 0 || visits > 128
                    || compilerBytes < 0 || compilerBytes > 8_388_608
                    || !relevantFingerprint.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Compatibility assessment bounds");
        }
    }
    /** The repository supplies these immutable values; this class never owns an artifact graph. */
    public record Entry(CapabilitySpec spec, ArtifactDescriptor descriptor, String canonicalIr,
                        String gameTarget, AdmissionRecord admission,
                        VersionedSkillRepository.Integrity integrity) { }
    @FunctionalInterface public interface Graph { Optional<Entry> find(ArtifactRef ref); }
    @FunctionalInterface public interface Provider {
        Assessment assess(ArtifactRef ref, Limits limits, boolean validateBody);
    }

    public static Assessment assess(ArtifactRef root, Graph graph,
                                    VersionedSkillRepository.RuntimeSnapshot runtime,
                                    Limits limits, boolean validateBody) {
        Objects.requireNonNull(root); Objects.requireNonNull(graph);
        Objects.requireNonNull(runtime); Objects.requireNonNull(limits);
        var walk = new Walk(graph, runtime, limits, validateBody);
        Result result = walk.visit(root, new ArrayList<>(), new HashSet<>());
        String fingerprint = digest(StrictJson.canonical(Map.of("policy", POLICY,
                "contract", CONTRACT_VERSION, "ir", IR_VERSION, "game", runtime.gameTarget(),
                "requirements", walk.fingerprints)));
        var compatibility = new Compatibility(root, result.status(), result.reason() == null
                ? List.of() : List.of(result.reason()), runtime.gameTarget());
        return new Assessment(compatibility, walk.causes,
                walk.causes.stream().map(c -> new Revalidation(c.affected(), c.issue(),
                        Set.of(Validation.CURRENT_STATIC_CHECK, Validation.CURRENT_FIXTURE,
                                Validation.AUTHORIZED_BEHAVIORAL_ADMISSION))).toList(),
                walk.visits, walk.compilerBytes, walk.complete, fingerprint);
    }

    /** A trusted registered semantic transformation produces a proposal, never a repository write. */
    public record SemanticProposal(ArtifactRef original, SkillArtifact proposed,
                                   boolean changesIdentity, Set<Validation> required) {
        public SemanticProposal { required = Set.copyOf(required); }
    }
    public static SemanticProposal inspectTransformation(ArtifactRef original,
                                                         SkillCompiler.Success transformed) {
        Objects.requireNonNull(original); Objects.requireNonNull(transformed);
        SkillArtifact next = transformed.skill().artifact();
        boolean changed = !original.equals(next.descriptor().ref());
        return new SemanticProposal(original, next, changed, changed ? Set.of(
                Validation.CURRENT_STATIC_CHECK, Validation.CURRENT_FIXTURE,
                Validation.AUTHORIZED_BEHAVIORAL_ADMISSION) : Set.of());
    }
    private record Result(CompatibilityStatus status, Reason reason) { }
    private static final Result OK = new Result(CompatibilityStatus.COMPATIBLE, null);

    private static final class Walk {
        final Graph graph; final VersionedSkillRepository.RuntimeSnapshot runtime;
        final Limits limits; final boolean validateBody;
        final List<Cause> causes = new ArrayList<>();
        final SortedMap<String, String> fingerprints = new TreeMap<>();
        int visits; long compilerBytes; boolean complete = true;
        Walk(Graph graph, VersionedSkillRepository.RuntimeSnapshot runtime, Limits limits, boolean validateBody) {
            this.graph = graph; this.runtime = runtime; this.limits = limits; this.validateBody = validateBody;
        }
        Result fail(ArtifactRef ref, Issue issue, Reason reason, PrimitiveRequirement primitive,
                    List<ArtifactRef> path, boolean unknown) {
            if (causes.size() < limits.diagnostics()) causes.add(new Cause(ref, issue, reason, primitive, path));
            else complete = false;
            return new Result(unknown ? CompatibilityStatus.UNKNOWN : CompatibilityStatus.INCOMPATIBLE, reason);
        }
        Result visit(ArtifactRef ref, List<ArtifactRef> ancestors, Set<ArtifactRef> active) {
            var path = new ArrayList<>(ancestors); path.add(ref);
            if (ancestors.size() > limits.depth() || visits == limits.visits())
                return fail(ref, Issue.TRAVERSAL_LIMIT, Reason.DEPENDENCY_INCOMPATIBLE, null, path, true);
            visits++;
            if (!active.add(ref)) return fail(ref, Issue.DEPENDENCY_CYCLE,
                    Reason.DEPENDENCY_INCOMPATIBLE, null, path, false);
            try {
                Entry entry = graph.find(ref).orElse(null);
                if (entry == null) return fail(ref, Issue.MISSING_ARTIFACT, Reason.ARTIFACT_INVALID, null, path, false);
                if (entry.integrity() == VersionedSkillRepository.Integrity.UNKNOWN_SCHEMA)
                    return fail(ref, Issue.UNSUPPORTED_FORMAT, Reason.ARTIFACT_INCOMPATIBLE, null, path, true);
                if (entry.integrity() != VersionedSkillRepository.Integrity.VERIFIED || entry.descriptor() == null
                        || entry.spec() == null || !entry.descriptor().ref().equals(ref))
                    return fail(ref, Issue.CORRUPT_BODY, Reason.ARTIFACT_INVALID, null, path, false);
                if (entry.admission().status() != AdmissionStatus.ADMITTED)
                    return fail(ref, Issue.ADMISSION_RESTRICTED,
                            entry.admission().status() == AdmissionStatus.QUARANTINED
                                    ? Reason.ARTIFACT_QUARANTINED : Reason.ARTIFACT_INVALID, null, path, false);
                CapabilitySpec actual = runtime.capabilities().find(ref.capability()).orElse(null);
                fingerprints.put("artifact:" + ref.capability().name() + "@" + ref.capability().version() + ":" + ref.sha256(), digest(StrictJson.canonical(Map.of("stored", RepositoryCodec.spec(entry.spec()),
                        "actual", actual == null ? "missing" : RepositoryCodec.spec(actual)))));
                if (!entry.gameTarget().equals(runtime.gameTarget()))
                    return fail(ref, Issue.GAME_TARGET, Reason.ARTIFACT_INCOMPATIBLE, null, path, false);
                if (actual == null || !RepositoryCodec.spec(actual).equals(RepositoryCodec.spec(entry.spec())))
                    return fail(ref, Issue.CAPABILITY_CONTRACT, Reason.ARTIFACT_INCOMPATIBLE, null, path, false);
                for (PrimitiveRequirement need : entry.descriptor().primitives()) {
                    PrimitiveSignature signature = runtime.primitives().find(need.id(), need.version()).orElse(null);
                    fingerprints.put("primitive:" + need.id() + "@" + need.version(), signature == null ? "missing" : digest(StrictJson.canonical(Map.of(
                            "fingerprint", signature.fingerprint(), "parameters", signature.parameters().stream()
                                    .map(p -> Map.of("name", p.name(), "type", p.type().name(), "minimum", p.minimum(), "maximum", p.maximum())).toList(), "result", signature.resultType().name(),
                            "effects", signature.effects().stream().map(Enum::name).sorted().toList()))));
                    if (signature == null || !signature.id().equals(need.id()) || signature.version() != need.version()
                            || !signature.fingerprint().equals(need.fingerprint()))
                        return fail(ref, Issue.PRIMITIVE_SIGNATURE, Reason.DEPENDENCY_INCOMPATIBLE, need, path, false);
                }
                for (ArtifactRef pinned : entry.descriptor().dependencies()) {
                    Result child = visit(pinned, path, active);
                    if (child.status() != CompatibilityStatus.COMPATIBLE)
                        return new Result(child.status(), Reason.DEPENDENCY_INCOMPATIBLE);
                }
                if (validateBody) {
                    if (entry.canonicalIr() == null || entry.canonicalIr().length() > StrictJson.MAX_BYTES)
                        return fail(ref, Issue.BODY_INVALID, Reason.ARTIFACT_INVALID, null, path, false);
                    int bytes = entry.canonicalIr().getBytes(StandardCharsets.UTF_8).length;
                    if (bytes > StrictJson.MAX_BYTES || bytes > limits.compilerBytes() - compilerBytes)
                        return fail(ref, Issue.COMPILER_LIMIT, Reason.BUDGET_EXHAUSTED, null, path, true);
                    compilerBytes += bytes;
                    var compiled = new SkillCompiler(runtime.capabilities(), runtime.primitives(),
                            pinned -> graph.find(pinned).map(Entry::descriptor)).compile(entry.canonicalIr());
                    if (!(compiled instanceof SkillCompiler.Success success)
                            || !success.skill().artifact().descriptor().equals(entry.descriptor()))
                        return fail(ref, Issue.BODY_INVALID, Reason.ARTIFACT_INVALID, null, path, false);
                }
                return OK;
            } catch (RuntimeException unavailable) {
                return fail(ref, Issue.RUNTIME_UNAVAILABLE, Reason.ARTIFACT_INCOMPATIBLE, null, path, true);
            } finally { active.remove(ref); }
        }
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
