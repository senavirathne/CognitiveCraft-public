package dev.aivillages.core.kernel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.SkillIr.*;

/** Strict, pure compiler for P0 IR schema 1. No source strings or instructions are executed here. */
public final class SkillCompiler {
    public static final int MAX_NODES = 64;
    public static final int MAX_CONTROL_DEPTH = 8;
    public static final int MAX_DEPENDENCIES = 16;
    public static final int MAX_DEPENDENCY_EDGES = 64;
    public static final long MAX_EXPANDED_WORK = 4_096;

    private final CapabilityCatalog capabilities;
    private final PrimitiveCatalog primitives;
    private final ArtifactCatalog artifacts;

    public SkillCompiler(CapabilityCatalog capabilities, PrimitiveCatalog primitives,
                         ArtifactCatalog artifacts) {
        this.capabilities = Objects.requireNonNull(capabilities);
        this.primitives = Objects.requireNonNull(primitives);
        this.artifacts = Objects.requireNonNull(artifacts);
    }

    public record Diagnostic(String path, String code) {
        public Diagnostic {
            if (path == null || path.length() > 256 || code == null || code.length() > 64)
                throw new IllegalArgumentException("Diagnostic limit");
        }
    }
    public record Statistics(int nodes, int dependencyEdges, long maximumExpandedWork, int inputBytes) { }
    public sealed interface CompileResult permits Success, Failure { }
    public record Success(CompiledSkill skill) implements CompileResult { }
    /** Observed argument slots contain types only, never candidate-defined names or values. */
    public record ArgumentObservation(int ordinal, Type observedType) {
        public ArgumentObservation {
            if (ordinal < 0 || ordinal >= 16) throw new IllegalArgumentException("Argument slot");
            Objects.requireNonNull(observedType);
        }
    }
    /** Proof of an absent exact ID/version after local validation; the whole program is rejected. */
    public record UnsupportedPrimitiveReference(CapabilityId requested, String requestedFingerprint,
            Diagnostic diagnostic, List<ArgumentObservation> arguments, String catalogFingerprint) {
        public UnsupportedPrimitiveReference {
            Objects.requireNonNull(requested);
            Objects.requireNonNull(diagnostic);
            arguments = List.copyOf(arguments);
            if (requestedFingerprint == null || !requestedFingerprint.matches("[a-f0-9]{64}")
                    || !diagnostic.code().equals("UNKNOWN_PRIMITIVE")
                    || !diagnostic.path().endsWith(".id") || arguments.size() > 16
                    || catalogFingerprint != null && !catalogFingerprint.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Unsupported reference evidence");
            for (int i = 0; i < arguments.size(); i++)
                if (arguments.get(i).ordinal() != i) throw new IllegalArgumentException("Argument order");
        }
    }
    public record Failure(List<Diagnostic> diagnostics,
            Optional<UnsupportedPrimitiveReference> unsupportedPrimitive) implements CompileResult {
        public Failure(List<Diagnostic> diagnostics) { this(diagnostics, Optional.empty()); }
        public Failure {
            diagnostics = List.copyOf(diagnostics);
            Objects.requireNonNull(unsupportedPrimitive);
            if (unsupportedPrimitive.isPresent()
                    && !diagnostics.contains(unsupportedPrimitive.orElseThrow().diagnostic()))
                throw new IllegalArgumentException("Evidence diagnostic");
        }
    }
    public record CompiledSkill(Program program, SkillArtifact artifact, Statistics statistics) { }

    public CompileResult compile(String source) {
        int bytes = source == null ? 0 : Math.min(source.length(), StrictJson.MAX_BYTES + 1);
        try {
            Map<String, Object> json = StrictJson.object(source);
            bytes = source.getBytes(StandardCharsets.UTF_8).length;
            requireKeys(json, "$", "schema", "capability", "capabilityVersion", "dependencies", "body");
            if (number(json, "schema", "$") != IR_VERSION) fail("$.schema", "UNKNOWN_SCHEMA");
            CapabilityId id = capability(json, "$", "capability", "capabilityVersion");
            CapabilitySpec spec = capabilities.find(id).orElse(null);
            if (spec == null) fail("$.capability", "UNKNOWN_CAPABILITY");

            List<Object> rawDependencies = array(json.get("dependencies"), "$.dependencies");
            if (rawDependencies.size() > MAX_DEPENDENCIES) fail("$.dependencies", "DEPENDENCY_LIMIT");
            List<ArtifactRef> dependencies = new ArrayList<>();
            Map<ArtifactRef, ArtifactDescriptor> direct = new HashMap<>();
            for (int i = 0; i < rawDependencies.size(); i++) {
                String path = "$.dependencies[" + i + "]";
                ArtifactRef ref = parseRef(object(rawDependencies.get(i), path), path);
                if (direct.containsKey(ref)) fail(path, "DUPLICATE_DEPENDENCY");
                ArtifactDescriptor descriptor = artifacts.find(ref).orElse(null);
                if (descriptor == null) fail(path, "MISSING_DEPENDENCY");
                if (!descriptor.ref().equals(ref)) fail(path, "DEPENDENCY_INCOMPATIBLE");
                dependencies.add(ref);
                direct.put(ref, descriptor);
            }
            Counter counter = new Counter(bytes);
            Map<ArtifactRef, Integer> visitedDepth = new HashMap<>();
            for (ArtifactRef dependency : dependencies)
                visit(dependency, new HashSet<>(), visitedDepth, 1, counter);

            List<Object> body = array(json.get("body"), "$.body");
            if (body.isEmpty()) fail("$.body", "MISSING_RESULT");
            Context context = new Context(spec, direct, counter);
            Map<String, Domain> variables = new HashMap<>();
            for (Parameter parameter : spec.parameters())
                variables.put(parameter.name(), new Domain(parameter.type(), parameter.minimum(),
                        parameter.maximum(), true));
            Block root = block(body, variables, context, "$.body", 0, true);
            if (!(root.nodes().get(root.nodes().size() - 1) instanceof Result))
                fail("$.body", "MISSING_RESULT");
            if (!context.calledDependencies.equals(direct.keySet()))
                fail("$.dependencies", "UNUSED_DEPENDENCY");

            rawDependencies.sort(Comparator.comparing(StrictJson::canonical));
            dependencies.sort(Comparator.comparing(ref -> StrictJson.canonical(Map.of(
                    "capability", ref.capability().name(), "version", (long) ref.capability().version(),
                    "sha256", ref.sha256()))));
            String canonicalIr = StrictJson.canonical(json);
            ArtifactRef ref = canonicalIdentity(spec, canonicalIr);
            if (visitedDepth.containsKey(ref)) fail("$.dependencies", "RECURSIVE_DEPENDENCY");
            ArtifactDescriptor descriptor = new ArtifactDescriptor(ref, IR_VERSION, spec.parameters(),
                    spec.resultType(), context.effects, dependencies,
                    context.primitiveRequirements.stream().sorted(Comparator.comparing(
                            p -> p.id() + "@" + p.version() + ":" + p.fingerprint())).toList());
            SkillArtifact artifact = new SkillArtifact(descriptor, canonicalIr,
                    new ArtifactMetadata(null, null, 0));
            Statistics stats = new Statistics(counter.nodes, counter.edges, root.work(), counter.bytes);
            return new Success(new CompiledSkill(new Program(id, dependencies, root.nodes()), artifact, stats));
        } catch (StrictJson.Invalid invalid) {
            return new Failure(List.of(new Diagnostic(invalid.path(), invalid.code())));
        } catch (Problem problem) {
            return new Failure(List.of(new Diagnostic(problem.path, problem.code)),
                    Optional.ofNullable(problem.unsupported));
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            return new Failure(List.of(new Diagnostic("$", "INVALID_STRUCTURE")));
        }
    }

    private void visit(ArtifactRef ref, Set<ArtifactRef> active,
                       Map<ArtifactRef, Integer> visitedDepth,
                       int depth, Counter counter) throws Problem {
        if (depth > MAX_CONTROL_DEPTH) fail("$.dependencies", "DEPENDENCY_DEPTH");
        if (!active.add(ref)) fail("$.dependencies", "RECURSIVE_DEPENDENCY");
        // A shared artifact must be checked again when a longer path reaches it.
        // Skipping it solely because it was seen on a shorter path can hide an overdeep child.
        if (depth > visitedDepth.getOrDefault(ref, 0)) {
            visitedDepth.put(ref, depth);
            ArtifactDescriptor descriptor = artifacts.find(ref).orElse(null);
            if (descriptor == null) fail("$.dependencies", "MISSING_DEPENDENCY");
            if (!descriptor.ref().equals(ref) || descriptor.irVersion() != IR_VERSION)
                fail("$.dependencies", "DEPENDENCY_INCOMPATIBLE");
            for (PrimitiveRequirement requirement : descriptor.primitives()) {
                PrimitiveSignature registered = primitives.find(requirement.id(), requirement.version())
                        .orElse(null);
                if (registered == null || !registered.fingerprint().equals(requirement.fingerprint()))
                    fail("$.dependencies", "DEPENDENCY_INCOMPATIBLE");
            }
            if (descriptor.dependencies().size() > MAX_DEPENDENCIES)
                fail("$.dependencies", "DEPENDENCY_LIMIT");
            for (ArtifactRef child : descriptor.dependencies()) {
                if (++counter.edges > MAX_DEPENDENCY_EDGES)
                    fail("$.dependencies", "DEPENDENCY_WORK_LIMIT");
                visit(child, active, visitedDepth, depth + 1, counter);
            }
        }
        active.remove(ref);
    }

    private Block block(List<Object> raw, Map<String, Domain> inherited, Context context,
                        String path, int depth, boolean root) throws Problem {
        if (depth > MAX_CONTROL_DEPTH) fail(path, "CONTROL_DEPTH");
        Map<String, Domain> symbols = new HashMap<>(inherited);
        List<Node> nodes = new ArrayList<>();
        long work = 0;
        for (int i = 0; i < raw.size(); i++) {
            String at = path + "[" + i + "]";
            if (++context.counter.nodes > MAX_NODES) fail(at, "NODE_LIMIT");
            Map<String, Object> node = object(raw.get(i), at);
            String op = string(node, "op", at);
            Node compiled;
            long cost = 1;
            switch (op) {
                case "bind" -> {
                    requireKeys(node, at, "op", "name", "type", "value");
                    String name = symbol(node, "name", at);
                    Type type = type(node, "type", at);
                    Expression value = expr(node.get("value"), symbols, at + ".value", 0);
                    if (value.domain.type != type) fail(at + ".value", "TYPE_MISMATCH");
                    addSymbol(symbols, name,
                            new Domain(type, value.domain.min, value.domain.max, false), at);
                    compiled = new Bind(name, type, value.ast);
                }
                case "call" -> compiled = call(node, symbols, context, at);
                case "if" -> {
                    requireKeys(node, at, "op", "test", "then", "else");
                    Expression test = expr(node.get("test"), symbols, at + ".test", 0);
                    if (test.domain.type != Type.BOOL) fail(at + ".test", "TYPE_MISMATCH");
                    Block left = block(array(node.get("then"), at + ".then"), symbols,
                            context, at + ".then", depth + 1, false);
                    Block right = block(array(node.get("else"), at + ".else"), symbols,
                            context, at + ".else", depth + 1, false);
                    cost = safeAdd(1, Math.max(left.work, right.work), at);
                    compiled = new Branch(test.ast, left.nodes, right.nodes);
                }
                case "repeat" -> {
                    requireKeys(node, at, "op", "count", "body");
                    Expression count = expr(node.get("count"), symbols, at + ".count", 0);
                    if (count.domain.type != Type.INT || count.domain.min < 0
                            || count.domain.max > 64) fail(at + ".count", "LOOP_BOUND");
                    Block repeated = block(array(node.get("body"), at + ".body"), symbols,
                            context, at + ".body", depth + 1, false);
                    cost = safeAdd(1, safeMultiply(count.domain.max, repeated.work, at), at);
                    compiled = new Repeat(count.ast, count.domain.max, repeated.nodes);
                }
                case "result" -> {
                    requireKeys(node, at, "op", "value");
                    if (!root || i != raw.size() - 1) fail(at, "RESULT_POSITION");
                    Expression value = expr(node.get("value"), symbols, at + ".value", 0);
                    if (value.domain.type != context.spec.resultType())
                        fail(at + ".value", "TYPE_MISMATCH");
                    compiled = new Result(value.ast);
                }
                default -> throw new Problem(at + ".op", "UNKNOWN_OPCODE");
            }
            work = safeAdd(work, cost, at);
            if (work > MAX_EXPANDED_WORK) fail(at, "WORK_LIMIT");
            nodes.add(compiled);
        }
        return new Block(List.copyOf(nodes), work);
    }

    private Node call(Map<String, Object> node, Map<String, Domain> symbols,
                      Context context, String at) throws Problem {
        String kind = string(node, "kind", at);
        List<Parameter> parameters;
        Type output;
        String primitiveId = null;
        int primitiveVersion = 0;
        String fingerprint = null;
        ArtifactRef ref = null;
        Set<Effect> effects;
        if (kind.equals("primitive")) {
            requireKeys(node, at, "op", "kind", "id", "version", "fingerprint", "args", "into");
            primitiveId = string(node, "id", at);
            primitiveVersion = positiveInt(node, "version", at);
            fingerprint = string(node, "fingerprint", at);
            PrimitiveSignature signature = primitives.find(primitiveId, primitiveVersion).orElse(null);
            if (signature == null) {
                // A lookup miss alone says nothing about malformed candidate structure.
                CapabilityId requested = capability(node, at, "id", "version");
                if (!fingerprint.matches("[a-f0-9]{64}"))
                    fail(at + ".fingerprint", "INVALID_STRUCTURE");
                Map<String, Object> args = object(node.get("args"), at + ".args");
                if (args.size() > 16) fail(at + ".args", "ARGUMENT_MISMATCH");
                List<ArgumentObservation> observations = new ArrayList<>();
                for (String name : args.keySet().stream().sorted().toList()) {
                    if (!name.matches("[a-z][a-zA-Z0-9_]{0,63}"))
                        fail(at + ".args", "INVALID_SYMBOL");
                    Expression value = expr(args.get(name), symbols, at + ".args." + name, 0);
                    observations.add(new ArgumentObservation(observations.size(), value.domain.type));
                }
                String into = string(node, "into", at);
                if (!into.isEmpty()) {
                    if (!into.matches("[a-z][a-zA-Z0-9_]{0,63}"))
                        fail(at + ".into", "INVALID_SYMBOL");
                    if (symbols.containsKey(into)) fail(at + ".into", "DUPLICATE_SYMBOL");
                }
                Diagnostic diagnostic = new Diagnostic(at + ".id", "UNKNOWN_PRIMITIVE");
                String catalog = primitives instanceof PrimitiveDiagnostics.CatalogSnapshot snapshot
                        ? snapshot.fingerprint() : null;
                throw new Problem(new UnsupportedPrimitiveReference(requested, fingerprint,
                        diagnostic, observations, catalog));
            }
            if (!signature.fingerprint().equals(fingerprint)) fail(at + ".fingerprint", "PRIMITIVE_INCOMPATIBLE");
            parameters = signature.parameters(); output = signature.resultType(); effects = signature.effects();
            context.primitiveRequirements.add(new PrimitiveRequirement(primitiveId,
                    primitiveVersion, fingerprint));
        } else if (kind.equals("skill")) {
            requireKeys(node, at, "op", "kind", "ref", "args", "into");
            ref = parseRef(object(node.get("ref"), at + ".ref"), at + ".ref");
            ArtifactDescriptor descriptor = context.direct.get(ref);
            if (descriptor == null) fail(at + ".ref", "UNPINNED_DEPENDENCY");
            context.calledDependencies.add(ref);
            parameters = descriptor.parameters(); output = descriptor.resultType();
            effects = descriptor.effects();
        } else throw new Problem(at + ".kind", "UNKNOWN_CALL_KIND");
        if (!context.spec.effects().containsAll(effects)) fail(at, "FORBIDDEN_EFFECT");
        context.effects.addAll(effects);
        Map<String, Object> args = object(node.get("args"), at + ".args");
        if (args.size() != parameters.size()) fail(at + ".args", "ARGUMENT_MISMATCH");
        Map<String, Expr> typed = new LinkedHashMap<>();
        for (Parameter parameter : parameters) {
            if (!args.containsKey(parameter.name())) fail(at + ".args", "ARGUMENT_MISMATCH");
            Expression argument = expr(args.get(parameter.name()), symbols,
                    at + ".args." + parameter.name(), 0);
            if (argument.domain.type != parameter.type()
                    || (parameter.type() == Type.INT &&
                    (argument.domain.min < parameter.minimum() || argument.domain.max > parameter.maximum())))
                fail(at + ".args." + parameter.name(), "TYPE_MISMATCH");
            typed.put(parameter.name(), argument.ast);
        }
        if (!typed.keySet().equals(args.keySet())) fail(at + ".args", "ARGUMENT_MISMATCH");
        String into = string(node, "into", at);
        if (output == Type.UNIT) {
            if (!into.isEmpty()) fail(at + ".into", "TYPE_MISMATCH");
        } else {
            if (!into.matches("[a-z][a-zA-Z0-9_]{0,63}")) fail(at + ".into", "INVALID_SYMBOL");
            addSymbol(symbols, into, new Domain(output, Long.MIN_VALUE, Long.MAX_VALUE, false),
                    at + ".into");
        }
        return new Call(primitiveId, primitiveVersion, fingerprint, ref, typed, into, output);
    }

    private Expression expr(Object raw, Map<String, Domain> symbols, String path, int depth)
            throws Problem {
        if (depth > MAX_CONTROL_DEPTH) fail(path, "EXPRESSION_DEPTH");
        Map<String, Object> value = object(raw, path);
        if (value.size() == 1 && value.containsKey("param")) {
            String name = string(value, "param", path);
            Domain domain = symbols.get(name);
            if (domain == null || !domain.parameter
                    || !name.matches("[a-z][a-zA-Z0-9_]{0,63}"))
                fail(path, "UNRESOLVED_SYMBOL");
            return new Expression(new ParameterExpr(name, domain.type), domain);
        }
        if (value.size() == 1 && value.containsKey("local")) {
            String name = string(value, "local", path);
            Domain domain = symbols.get(name);
            if (domain == null || domain.parameter
                    || !name.matches("[a-z][a-zA-Z0-9_]{0,63}"))
                fail(path, "UNRESOLVED_SYMBOL");
            return new Expression(new LocalExpr(name, domain.type), domain);
        }
        if (value.size() == 1 && value.containsKey("int")) {
            long n = number(value, "int", path);
            if (n < -1_000_000 || n > 1_000_000) fail(path, "LITERAL_LIMIT");
            return new Expression(new IntegerExpr(n), new Domain(Type.INT, n, n, false));
        }
        if (value.size() == 1 && value.containsKey("bool")) {
            if (!(value.get("bool") instanceof Boolean)) fail(path + ".bool", "EXPECTED_BOOLEAN");
            return new Expression(new BooleanExpr((Boolean) value.get("bool")),
                    new Domain(Type.BOOL, 0, 0, false));
        }
        requireKeys(value, path, "operator", "left", "right");
        Operator op;
        try { op = Operator.valueOf(string(value, "operator", path)); }
        catch (IllegalArgumentException invalid) { throw new Problem(path + ".operator", "UNKNOWN_OPERATOR"); }
        Expression left = expr(value.get("left"), symbols, path + ".left", depth + 1);
        Expression right = expr(value.get("right"), symbols, path + ".right", depth + 1);
        Domain a = left.domain, b = right.domain;
        if (a.type != b.type || (op != Operator.EQ && a.type != Type.INT))
            fail(path, "TYPE_MISMATCH");
        Domain result = switch (op) {
            case ADD -> new Domain(Type.INT, safeAdd(a.min, b.min, path), safeAdd(a.max, b.max, path), false);
            case SUB -> new Domain(Type.INT, safeSubtract(a.min, b.max, path),
                    safeSubtract(a.max, b.min, path), false);
            case EQ, LT, GTE -> new Domain(Type.BOOL, 0, 0, false);
        };
        return new Expression(new BinaryExpr(op, left.ast, right.ast, result.type), result);
    }

    private static Map<String, Object> semanticSpec(CapabilitySpec spec) {
        List<Object> parameters = new ArrayList<>();
        for (Parameter p : spec.parameters())
            parameters.add(Map.of("name", p.name(), "type", p.type().name(),
                    "minimum", p.minimum(), "maximum", p.maximum()));
        List<String> effects = spec.effects().stream().map(Enum::name).sorted().toList();
        return Map.of("capability", spec.id().name(), "version", (long) spec.id().version(),
                "parameters", parameters, "result", spec.resultType().name(),
                "effects", effects, "completion", spec.completion().name());
    }

    /** Recomputes the exact schema-1 executable identity from a saved semantic contract and IR. */
    public static ArtifactRef canonicalIdentity(CapabilitySpec spec, String canonicalIr)
            throws StrictJson.Invalid {
        Map<String, Object> ir = StrictJson.object(canonicalIr);
        String identity = StrictJson.canonical(Map.of("contract", (long) CONTRACT_VERSION,
                "outcome", semanticSpec(spec), "ir", ir));
        return new ArtifactRef(spec.id(), sha256(identity));
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) { throw new AssertionError(exception); }
    }
    private static ArtifactRef parseRef(Map<String, Object> map, String path) throws Problem {
        requireKeys(map, path, "capability", "version", "sha256");
        try { return new ArtifactRef(capability(map, path, "capability", "version"),
                string(map, "sha256", path)); }
        catch (IllegalArgumentException invalid) { throw new Problem(path, "INVALID_REFERENCE"); }
    }
    private static CapabilityId capability(Map<String, Object> map, String path, String name,
                                           String version) throws Problem {
        try { return new CapabilityId(string(map, name, path), positiveInt(map, version, path)); }
        catch (IllegalArgumentException invalid) { throw new Problem(path, "INVALID_CAPABILITY"); }
    }
    private static int positiveInt(Map<String, Object> map, String key, String path) throws Problem {
        long value = number(map, key, path);
        if (value < 1 || value > 1_000_000) throw new Problem(path + "." + key, "INVALID_VERSION");
        return (int) value;
    }
    private static void addSymbol(Map<String, Domain> symbols, String name, Domain value, String path)
            throws Problem {
        if (symbols.putIfAbsent(name, value) != null) fail(path, "DUPLICATE_SYMBOL");
    }
    private static String symbol(Map<String, Object> map, String key, String path) throws Problem {
        String name = string(map, key, path);
        if (!name.matches("[a-z][a-zA-Z0-9_]{0,63}")) fail(path + "." + key, "INVALID_SYMBOL");
        return name;
    }
    private static Type type(Map<String, Object> map, String key, String path) throws Problem {
        try { return Type.valueOf(string(map, key, path)); }
        catch (IllegalArgumentException invalid) { throw new Problem(path + "." + key, "UNKNOWN_TYPE"); }
    }
    private static Map<String, Object> object(Object value, String path) throws Problem {
        if (!(value instanceof Map<?, ?> raw)) throw new Problem(path, "EXPECTED_OBJECT");
        @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) raw;
        return map;
    }
    private static List<Object> array(Object value, String path) throws Problem {
        if (!(value instanceof List<?> raw)) throw new Problem(path, "EXPECTED_ARRAY");
        @SuppressWarnings("unchecked") List<Object> list = (List<Object>) raw;
        return list;
    }
    private static String string(Map<String, Object> map, String key, String path) throws Problem {
        if (!(map.get(key) instanceof String value)) throw new Problem(path + "." + key, "EXPECTED_STRING");
        return value;
    }
    private static long number(Map<String, Object> map, String key, String path) throws Problem {
        if (!(map.get(key) instanceof Long value)) throw new Problem(path + "." + key, "EXPECTED_INTEGER");
        return value;
    }
    private static void requireKeys(Map<String, Object> map, String path, String... fields)
            throws Problem {
        if (!map.keySet().equals(Set.of(fields))) fail(path, "UNKNOWN_OR_MISSING_FIELD");
    }
    private static long safeAdd(long left, long right, String path) throws Problem {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException overflow) { throw new Problem(path, "ARITHMETIC_OVERFLOW"); }
    }
    private static long safeSubtract(long left, long right, String path) throws Problem {
        try { return Math.subtractExact(left, right); }
        catch (ArithmeticException overflow) { throw new Problem(path, "ARITHMETIC_OVERFLOW"); }
    }
    private static long safeMultiply(long left, long right, String path) throws Problem {
        try { return Math.multiplyExact(left, right); }
        catch (ArithmeticException overflow) { throw new Problem(path, "ARITHMETIC_OVERFLOW"); }
    }
    private static void fail(String path, String code) throws Problem { throw new Problem(path, code); }
    private static final class Problem extends Exception {
        private static final long serialVersionUID = 1L;
        final String path; final String code;
        final UnsupportedPrimitiveReference unsupported;
        Problem(String path, String code) { this.path = path; this.code = code; unsupported = null; }
        Problem(UnsupportedPrimitiveReference unsupported) {
            path = unsupported.diagnostic().path(); code = unsupported.diagnostic().code();
            this.unsupported = unsupported;
        }
    }
    private record Domain(Type type, long min, long max, boolean parameter) { }
    private record Expression(Expr ast, Domain domain) { }
    private record Block(List<Node> nodes, long work) { }
    private static final class Counter {
        int nodes; int edges; final int bytes;
        Counter(int bytes) { this.bytes = bytes; }
    }
    private static final class Context {
        final CapabilitySpec spec;
        final Map<ArtifactRef, ArtifactDescriptor> direct;
        final Counter counter;
        final Set<ArtifactRef> calledDependencies = new HashSet<>();
        final Set<PrimitiveRequirement> primitiveRequirements = new HashSet<>();
        final EnumSet<Effect> effects = EnumSet.noneOf(Effect.class);
        Context(CapabilitySpec spec, Map<ArtifactRef, ArtifactDescriptor> direct, Counter counter) {
            this.spec = spec; this.direct = direct; this.counter = counter;
        }
    }
}
