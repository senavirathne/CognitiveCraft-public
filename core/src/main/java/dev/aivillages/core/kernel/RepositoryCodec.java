package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.Contracts.ArtifactDescriptor;
import dev.aivillages.core.kernel.Contracts.ArtifactRef;
import dev.aivillages.core.kernel.Contracts.CapabilityId;
import dev.aivillages.core.kernel.Contracts.CapabilitySpec;
import dev.aivillages.core.kernel.Contracts.Completion;
import dev.aivillages.core.kernel.Contracts.Effect;
import dev.aivillages.core.kernel.Contracts.Parameter;
import dev.aivillages.core.kernel.Contracts.PrimitiveRequirement;
import dev.aivillages.core.kernel.Contracts.Type;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict schema-1 body and reference encoding; storage metadata is outside executable identity. */
final class RepositoryCodec {
    static final int SCHEMA = 1;
    private RepositoryCodec() { }

    record Body(CapabilitySpec spec, ArtifactDescriptor descriptor, String canonicalIr) { }

    static String encodeBody(CapabilitySpec spec, ArtifactDescriptor descriptor, String canonicalIr)
            throws StrictJson.Invalid {
        return StrictJson.canonical(Map.of("schema", (long) SCHEMA,
                "spec", spec(spec), "descriptor", descriptor(descriptor),
                "ir", StrictJson.object(canonicalIr)));
    }

    static Body decodeBody(String source) throws StrictJson.Invalid {
        Map<String, Object> root = StrictJson.object(source);
        keys(root, "schema", "spec", "descriptor", "ir");
        schema(root);
        CapabilitySpec spec = readSpec(object(root.get("spec")));
        ArtifactDescriptor descriptor = readDescriptor(object(root.get("descriptor")));
        String canonicalIr = StrictJson.canonical(object(root.get("ir")));
        if (!descriptor.ref().capability().equals(spec.id())
                || !SkillCompiler.canonicalIdentity(spec, canonicalIr).equals(descriptor.ref()))
            throw invalid("BODY_IDENTITY");
        return new Body(spec, descriptor, canonicalIr);
    }

    static Map<String, Object> spec(CapabilitySpec spec) {
        List<Object> parameters = spec.parameters().stream()
                .map(parameter -> (Object) parameter(parameter)).toList();
        return Map.of("capability", spec.id().name(), "version", (long) spec.id().version(),
                "parameters", parameters, "result", spec.resultType().name(),
                "effects", spec.effects().stream().map(Enum::name).sorted().toList(),
                "completion", spec.completion().name());
    }

    static CapabilitySpec readSpec(Map<String, Object> map) throws StrictJson.Invalid {
        keys(map, "capability", "version", "parameters", "result", "effects", "completion");
        try {
            return new CapabilitySpec(new CapabilityId(string(map, "capability"), positive(map, "version")),
                    parameters(array(map, "parameters")), Type.valueOf(string(map, "result")),
                    effects(array(map, "effects")), Completion.valueOf(string(map, "completion")));
        } catch (IllegalArgumentException failure) { throw invalid("BODY_STRUCTURE"); }
    }

    static Map<String, Object> descriptor(ArtifactDescriptor descriptor) {
        return Map.of("ref", ref(descriptor.ref()), "irVersion", (long) descriptor.irVersion(),
                "parameters", descriptor.parameters().stream().map(RepositoryCodec::parameter).toList(),
                "result", descriptor.resultType().name(),
                "effects", descriptor.effects().stream().map(Enum::name).sorted().toList(),
                "dependencies", descriptor.dependencies().stream().map(RepositoryCodec::ref).toList(),
                "primitives", descriptor.primitives().stream().map(RepositoryCodec::primitive).toList());
    }

    static ArtifactDescriptor readDescriptor(Map<String, Object> map) throws StrictJson.Invalid {
        keys(map, "ref", "irVersion", "parameters", "result", "effects",
                "dependencies", "primitives");
        List<ArtifactRef> dependencies = new ArrayList<>();
        for (Object raw : array(map, "dependencies")) dependencies.add(readRef(object(raw)));
        List<PrimitiveRequirement> primitives = new ArrayList<>();
        for (Object raw : array(map, "primitives")) {
            Map<String, Object> item = object(raw);
            keys(item, "id", "version", "fingerprint");
            try {
                primitives.add(new PrimitiveRequirement(string(item, "id"),
                        positive(item, "version"), string(item, "fingerprint")));
            } catch (IllegalArgumentException failure) { throw invalid("BODY_STRUCTURE"); }
        }
        try {
            return new ArtifactDescriptor(readRef(object(map.get("ref"))), positive(map, "irVersion"),
                    parameters(array(map, "parameters")), Type.valueOf(string(map, "result")),
                    effects(array(map, "effects")), dependencies, primitives);
        } catch (IllegalArgumentException failure) { throw invalid("BODY_STRUCTURE"); }
    }

    static Map<String, Object> ref(ArtifactRef ref) {
        return Map.of("capability", ref.capability().name(),
                "version", (long) ref.capability().version(), "sha256", ref.sha256());
    }

    static ArtifactRef readRef(Map<String, Object> map) throws StrictJson.Invalid {
        keys(map, "capability", "version", "sha256");
        try { return new ArtifactRef(new CapabilityId(string(map, "capability"),
                positive(map, "version")), string(map, "sha256")); }
        catch (IllegalArgumentException failure) { throw invalid("BODY_STRUCTURE"); }
    }

    private static Map<String, Object> parameter(Parameter parameter) {
        return Map.of("name", parameter.name(), "type", parameter.type().name(),
                "minimum", parameter.minimum(), "maximum", parameter.maximum());
    }

    private static Map<String, Object> primitive(PrimitiveRequirement primitive) {
        return Map.of("id", primitive.id(), "version", (long) primitive.version(),
                "fingerprint", primitive.fingerprint());
    }

    private static List<Parameter> parameters(List<Object> items) throws StrictJson.Invalid {
        if (items.size() > 16) throw invalid("BODY_LIMIT");
        List<Parameter> parameters = new ArrayList<>();
        for (Object raw : items) {
            Map<String, Object> item = object(raw);
            keys(item, "name", "type", "minimum", "maximum");
            try {
                parameters.add(new Parameter(string(item, "name"), Type.valueOf(string(item, "type")),
                        number(item, "minimum"), number(item, "maximum")));
            } catch (IllegalArgumentException failure) { throw invalid("BODY_STRUCTURE"); }
        }
        return List.copyOf(parameters);
    }

    private static Set<Effect> effects(List<Object> raw) throws StrictJson.Invalid {
        if (raw.size() > Effect.values().length) throw invalid("BODY_LIMIT");
        Set<Effect> effects = new HashSet<>();
        for (Object item : raw) {
            try { if (!effects.add(Effect.valueOf(asString(item)))) throw invalid("BODY_STRUCTURE"); }
            catch (IllegalArgumentException failure) { throw invalid("BODY_STRUCTURE"); }
        }
        return Set.copyOf(effects);
    }

    static String digest(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
    }

    static Map<String, Object> object(Object value) throws StrictJson.Invalid {
        if (!(value instanceof Map<?, ?> raw)) throw invalid("EXPECTED_OBJECT");
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) raw;
        return result;
    }

    static List<Object> array(Map<String, Object> map, String key) throws StrictJson.Invalid {
        if (!(map.get(key) instanceof List<?> raw)) throw invalid("EXPECTED_ARRAY");
        return new ArrayList<>(raw);
    }

    static String string(Map<String, Object> map, String key) throws StrictJson.Invalid {
        return asString(map.get(key));
    }

    static String asString(Object value) throws StrictJson.Invalid {
        if (!(value instanceof String result)) throw invalid("EXPECTED_STRING");
        return result;
    }

    static long number(Map<String, Object> map, String key) throws StrictJson.Invalid {
        if (!(map.get(key) instanceof Long result)) throw invalid("EXPECTED_INTEGER");
        return result;
    }

    static int positive(Map<String, Object> map, String key) throws StrictJson.Invalid {
        long number = number(map, key);
        if (number < 1 || number > 1_000_000) throw invalid("BODY_STRUCTURE");
        return (int) number;
    }

    static void schema(Map<String, Object> map) throws StrictJson.Invalid {
        if (number(map, "schema") != SCHEMA) throw invalid("UNKNOWN_SCHEMA");
    }

    static void keys(Map<String, Object> map, String... expected) throws StrictJson.Invalid {
        if (!map.keySet().equals(Set.of(expected))) throw invalid("UNKNOWN_OR_MISSING_FIELD");
    }

    static StrictJson.Invalid invalid(String code) { return new StrictJson.Invalid("$", code); }
}
