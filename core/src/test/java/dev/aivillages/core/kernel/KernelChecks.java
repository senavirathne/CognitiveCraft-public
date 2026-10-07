package dev.aivillages.core.kernel;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Budgets.*;
import static dev.aivillages.core.kernel.Outcomes.*;

/** Deterministic fixtures; runnable without a game, model, JUnit runtime or disk I/O. */
public final class KernelChecks {
    private KernelChecks() { }
    private static final String DIMENSION = "minecraft:overworld";
    private static final String A = "a".repeat(64), B = "b".repeat(64);
    private static final UUID RUN = new UUID(0, 10);
    private static final ActorRef ACTOR = new ActorRef(new UUID(0, 1), new UUID(0, 2), DIMENSION);
    private static final TrustedContext CONTEXT = new TrustedContext(new PrincipalRef(new UUID(0, 3)),
            new ScopeRef(new UUID(0, 4), new UUID(0, 5)));
    private static final TrustedContext OTHER_CONTEXT = new TrustedContext(new PrincipalRef(new UUID(0, 30)),
            new ScopeRef(new UUID(0, 4), new UUID(0, 50)));
    private static final ObservationRef OBSERVATION = new ObservationRef(new UUID(0, 6), 1, DIMENSION);
    private static final Cuboid SOURCE = new Cuboid(DIMENSION, 0, 64, 0, 2, 64, 2);
    private static final ContainerRef TARGET = new ContainerRef(DIMENSION, 5, 64, 0);

    public static void main(String[] args) throws Exception {
        typedRequests(); irTyping(); strictRepresentation(); controlFlowBounds();
        canonicalIdentity(); parameterReuse(); outcomeTaxonomy(); budgetComposition();
        dependencyIntegrity(); trustedCompletion(); noExternalDependencies();
        System.out.println("IMP-001 deterministic checks passed: 11 matrix groups");
        SkillCompiler.Statistics stats = success(new Fake().compiler(),
                json(program(body(), List.of()))).skill().statistics();
        System.out.println("fixture counters: inputBytes=" + stats.inputBytes()
                + " nodes=" + stats.nodes() + " dependencyEdges=" + stats.dependencyEdges()
                + " maximumExpandedWork=" + stats.maximumExpandedWork());
    }

    public static void typedRequests() {
        Fake fixture = new Fake();
        CapabilityRequest valid = request(4, SOURCE, TARGET);
        check(RequestValidator.validate(valid, CONTEXT, OBSERVATION, fixture::spec, fixture.environment)
                instanceof RequestValidator.Accepted, "valid request");
        for (long invalid : new long[] {0, -1, 65, Long.MAX_VALUE})
            invalidRequest(request(invalid, SOURCE, TARGET), fixture);
        invalidRequest(new CapabilityRequest(new CapabilityId("unknown:capability", 1), valid.arguments()), fixture);
        Map<String, Value> missingActor = new HashMap<>(valid.arguments());
        missingActor.remove("actor");
        invalidRequest(new CapabilityRequest(CropDelivery.ID, missingActor), fixture);
        CapabilitySpec malformedCropSpec = new CapabilitySpec(CropDelivery.ID,
                List.of(new Parameter("amount", Type.INT, 1, 64)), Type.INT,
                Set.of(Effect.HARVEST), Completion.ATTRIBUTABLE_CROP_DELIVERY);
        check(RequestValidator.validate(new CapabilityRequest(CropDelivery.ID,
                Map.of("amount", new IntValue(4))), CONTEXT, OBSERVATION,
                id -> Optional.of(malformedCropSpec), fixture.environment)
                instanceof RequestValidator.Rejected, "incomplete registered crop contract rejects");
        fixture.enrolled = false;
        invalidRequest(valid, fixture);
        fixture.enrolled = true;
        fixture.loaded = false;
        invalidRequest(valid, fixture);
        fixture.loaded = true;
        invalidRequest(request(4, SOURCE, new ContainerRef("minecraft:the_nether", 5, 64, 0)), fixture);
        check(RequestValidator.validate(valid, OTHER_CONTEXT, OBSERVATION, fixture::spec,
                fixture.environment) instanceof RequestValidator.Rejected, "cross-scope denied");
        fixture.allowedContexts.add(OTHER_CONTEXT);
        fixture.effectGranted = true;
        check(RequestValidator.validate(valid, OTHER_CONTEXT, OBSERVATION, fixture::spec,
                fixture.environment) instanceof RequestValidator.Accepted, "explicitly authorized sharing");
        check(fixture.authority.currentlyAllows(ACTOR, Effect.HARVEST, OTHER_CONTEXT),
                "explicit effect policy");
        fixture.allowedContexts.remove(OTHER_CONTEXT);
        check(!fixture.authority.currentlyAllows(ACTOR, Effect.HARVEST, OTHER_CONTEXT),
                "revocation removes effect authority");
        check(fixture.worldEffects == 0 && fixture.modelCalls == 0, "validation side effects");
        check(RequestCodec.decode(RequestCodec.encode(valid)) instanceof RequestCodec.Valid, "request round trip");
        String wire = RequestCodec.encode(valid);
        check(RequestCodec.decode(wire.replace("\"value\":4", "\"value\":4.5"))
                instanceof RequestCodec.Invalid, "fractional quantity");
        check(RequestCodec.decode(wire.replace("\"schema\":1", "\"schema\":2"))
                instanceof RequestCodec.Invalid, "future request schema");
        check(RequestCodec.decode(wire.substring(0, wire.length() - 1) + ",\"authority\":true}")
                instanceof RequestCodec.Invalid, "input cannot grant authority");
        Map<String, Long> observedCounts = new HashMap<>(Map.of("mature_wheat", 4L));
        ObservationSnapshot observation = new ObservationSnapshot(OBSERVATION,
                ObservationStatus.PRESENT, "minecraft:overworld/0,64,0", 42, 9, observedCounts);
        observedCounts.clear();
        check(observation.counts().get("mature_wheat") == 4, "observation is immutable");
        ActionHandle handle = new ActionHandle(new UUID(0, 7), RUN);
        ActionReceipt typed = new ActionReceipt(handle, true, 0, List.of(), null,
                new IntValue(4), observation);
        check(typed.result().type() == Type.INT && typed.observation().reference().equals(OBSERVATION),
                "typed observation result travels with handle receipt");
        expectIllegal(() -> new ObservationSnapshot(OBSERVATION, ObservationStatus.UNKNOWN,
                "unloaded", 42, 4_097, Map.of()));
        expectIllegal(() -> new Cuboid(DIMENSION, Integer.MIN_VALUE, 64, 0,
                Integer.MAX_VALUE, 64, 0));
    }

    public static void irTyping() {
        Fake fake = new Fake();
        SkillCompiler compiler = fake.compiler();
        check(compiler.compile(json(program(body(), List.of()))) instanceof SkillCompiler.Success,
                "typed composition should compile");
        Map<String, Object> wrong = program(body(), List.of());
        callAt(wrong, 1).put("args", Map.of("actor", Map.of("param", "actor"),
                "source", Map.of("param", "source"), "amount", Map.of("param", "source")));
        reject(compiler, json(wrong), "TYPE_MISMATCH", "$.body[1].args.amount");
        Map<String, Object> missing = program(body(), List.of());
        callAt(missing, 0).put("args", Map.of());
        reject(compiler, json(missing), "ARGUMENT_MISMATCH", "$.body[0].args");
        Map<String, Object> unknown = program(body(), List.of());
        callAt(unknown, 1).put("args", Map.of("actor", Map.of("param", "actor"),
                "source", Map.of("param", "source"), "amount", Map.of("local", "neverBound")));
        reject(compiler, json(unknown), "UNRESOLVED_SYMBOL", "$.body[1].args.amount");
        Map<String, Object> opcode = program(body(), List.of());
        bodyOf(opcode).add(0, Map.of("op", "invokeJava", "text", "System.exit(0)"));
        reject(compiler, json(opcode), "UNKNOWN_OPCODE", "$.body[0].op");
        Map<String, Object> branch = program(body(), List.of());
        bodyOf(branch).add(0, Map.of("op", "if", "test", Map.of("bool", false),
                "then", List.of(), "else", List.of(Map.of("op", "invokeJava"))));
        reject(compiler, json(branch), "UNKNOWN_OPCODE", "$.body[0].else[0].op");
        Map<String, Object> localAsParameter = program(body(), List.of());
        callAt(localAsParameter, 3).put("args", Map.of("actor", Map.of("param", "actor"),
                "destination", Map.of("param", "destination"), "amount", Map.of("param", "picked")));
        reject(compiler, json(localAsParameter), "UNRESOLVED_SYMBOL", "$.body[3].args.amount");
        Map<String, Object> arithmetic = program(body(), List.of());
        bodyOf(arithmetic).add(4, Map.of("op", "bind", "name", "overflow", "type", "INT",
                "value", Map.of("operator", "ADD", "left", Map.of("local", "observed"),
                        "right", Map.of("int", 1))));
        reject(compiler, json(arithmetic), "ARITHMETIC_OVERFLOW", "$.body[4].value");
        Map<String, Object> primitive = program(body(), List.of());
        callAt(primitive, 0).put("id", "cognitivecraft:unregistered");
        reject(compiler, json(primitive), "UNKNOWN_PRIMITIVE", "$.body[0].id");
    }

    public static void strictRepresentation() {
        strictReject("{\"a\":1,\"a\":2}", "DUPLICATE_FIELD");
        strictReject("{\"a\":1} true", "INVALID_JSON");
        strictReject("{\"a\":1.0}", "INVALID_NUMBER");
        strictReject("{\"a\":1e0}", "INVALID_NUMBER");
        strictReject("{\"a\":-0}", "INVALID_NUMBER");
        strictReject("{\"a\":01}", "INVALID_NUMBER");
        strictReject("{\"a\":9223372036854775808}", "INVALID_NUMBER");
        strictReject("{\"a\":NaN}", "INVALID_JSON");
        strictReject("{\"a\":Infinity}", "INVALID_JSON");
        strictReject("{\"a\":\"\\ud800\"}", "INVALID_STRING");
        strictReject("{\"a\":\"" + "x".repeat(1_025) + "\"}", "INPUT_LIMIT");
        strictReject("[".repeat(33) + "0" + "]".repeat(33), "INPUT_LIMIT");
        strictReject("{" + " ".repeat(StrictJson.MAX_BYTES) + "}", "INPUT_LIMIT");
    }

    public static void controlFlowBounds() {
        Fake fake = new Fake();
        SkillCompiler compiler = fake.compiler();
        List<Object> sixtyFour = new ArrayList<>();
        for (int i = 0; i < 63; i++) sixtyFour.add(bind("v" + i, i));
        sixtyFour.add(Map.of("op", "result", "value", Map.of("param", "amount")));
        check(success(compiler, json(program(sixtyFour, List.of()))).skill().statistics().nodes() == 64,
                "64 nodes allowed and counted");
        sixtyFour.add(0, bind("overflow", 0));
        reject(compiler, json(program(sixtyFour, List.of())), "NODE_LIMIT", "$.body[64]");
        List<Object> nested = List.of();
        for (int i = 0; i < 8; i++) nested = List.of(Map.of("op", "if",
                "test", Map.of("bool", true), "then", nested, "else", List.of()));
        List<Object> depthEight = new ArrayList<>(nested);
        depthEight.add(Map.of("op", "result", "value", Map.of("param", "amount")));
        check(compiler.compile(json(program(depthEight, List.of()))) instanceof SkillCompiler.Success,
                "depth eight");
        List<Object> depthNine = List.of(Map.of("op", "if", "test", Map.of("bool", true),
                "then", nested, "else", List.of()),
                Map.of("op", "result", "value", Map.of("param", "amount")));
        reject(compiler, json(program(depthNine, List.of())), "CONTROL_DEPTH", "$.body[0].then");
        Map<String, Object> loop = program(List.of(Map.of("op", "repeat", "count", Map.of("int", 65),
                "body", List.of()), Map.of("op", "result", "value", Map.of("param", "amount"))), List.of());
        reject(compiler, json(loop), "LOOP_BOUND", "$.body[0].count");
        Map<String, Object> expanded = program(List.of(Map.of("op", "repeat", "count", Map.of("int", 64),
                "body", List.of(Map.of("op", "repeat", "count", Map.of("int", 64),
                        "body", List.of(bind("v", 1))))),
                Map.of("op", "result", "value", Map.of("param", "amount"))), List.of());
        reject(compiler, json(expanded), "WORK_LIMIT", "$.body[0]");
        String bounded = json(program(body(), List.of()));
        String exactly = bounded + " ".repeat(StrictJson.MAX_BYTES - bounded.length());
        check(compiler.compile(exactly) instanceof SkillCompiler.Success, "64 KiB body");
        reject(compiler, exactly + " ", "INPUT_LIMIT", "$");
        List<Object> refs = new ArrayList<>();
        List<Object> calls = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            ArtifactRef ref = fake.addDependency(i, List.of());
            refs.add(refMap(ref));
            calls.add(skillCall(ref, "d" + i));
        }
        calls.add(Map.of("op", "result", "value", Map.of("param", "amount")));
        reject(compiler, json(program(calls, refs)), "DEPENDENCY_LIMIT", "$.dependencies");
        check(compiler.compile(json(program(calls.subList(0, 16), refs.subList(0, 16))))
                instanceof SkillCompiler.Failure, "missing final result must fail");
        List<Object> sixteen = new ArrayList<>(calls.subList(0, 16));
        sixteen.add(calls.get(17));
        check(compiler.compile(json(program(sixteen, refs.subList(0, 16))))
                instanceof SkillCompiler.Success, "16 dependencies allowed");
    }

    public static void canonicalIdentity() {
        Fake fake = new Fake();
        SkillCompiler compiler = fake.compiler();
        Map<String, Object> source = program(body(), List.of());
        SkillCompiler.Success first = success(compiler, json(source));
        String differentOrder = "{\"body\":" + StrictJson.canonical(source.get("body"))
                + ",\"dependencies\":[],\"capabilityVersion\":1,\"capability\":\""
                + CropDelivery.ID.name() + "\",\"schema\":1}";
        check(first.skill().artifact().descriptor().ref().equals(success(compiler, differentOrder)
                .skill().artifact().descriptor().ref()), "key order and formatting");
        Map<String, Object> changed = program(body(), List.of());
        bodyOf(changed).add(0, bind("semanticConstant", 7));
        check(!first.skill().artifact().descriptor().ref().equals(success(compiler, json(changed))
                .skill().artifact().descriptor().ref()), "semantic literal changes identity");
        SkillArtifact updated = first.skill().artifact().withMetadata(new ArtifactMetadata(
                "different-model", "evidence-2", 50));
        check(first.skill().artifact().descriptor().ref().equals(updated.descriptor().ref()),
                "metadata never changes executable identity");
        ArtifactRef firstRef = first.skill().artifact().descriptor().ref();
        EvidenceRef retainedEvidence = new EvidenceRef("trial-1", "compiler-1", "isolated-world");
        expectIllegal(() -> new AdmissionRecord(firstRef, AdmissionStatus.ADMITTED,
                null, null, 1));
        AdmissionRecord admitted = new AdmissionRecord(firstRef, AdmissionStatus.ADMITTED,
                retainedEvidence, null, 1);
        Compatibility incompatible = new Compatibility(firstRef, CompatibilityStatus.INCOMPATIBLE,
                List.of(Reason.ARTIFACT_INCOMPATIBLE), "runtime-fingerprint-2");
        check(admitted.status() == AdmissionStatus.ADMITTED
                && incompatible.status() == CompatibilityStatus.INCOMPATIBLE
                && firstRef.equals(admitted.artifact()), "admission and compatibility remain independent");
        ArtifactRef dep1 = fake.addDependency(20, List.of());
        ArtifactRef dep2 = fake.addDependency(21, List.of());
        var a = success(compiler, json(program(List.of(skillCall(dep1, "x"),
                Map.of("op", "result", "value", Map.of("local", "x"))), List.of(refMap(dep1)))));
        var b = success(compiler, json(program(List.of(skillCall(dep2, "x"),
                Map.of("op", "result", "value", Map.of("local", "x"))), List.of(refMap(dep2)))));
        check(!a.skill().artifact().descriptor().ref().equals(b.skill().artifact().descriptor().ref()),
                "pinned dependency changes identity");
    }

    public static void parameterReuse() {
        Fake fake = new Fake();
        SkillCompiler.Success compiled = success(fake.compiler(), json(program(body(), List.of())));
        CapabilityRequest a = request(4, SOURCE, TARGET);
        CapabilityRequest b = request(6, new Cuboid(DIMENSION, 0, 64, 5, 2, 64, 7),
                new ContainerRef(DIMENSION, 5, 64, 6));
        check(RequestValidator.validate(a, CONTEXT, OBSERVATION, fake::spec, fake.environment)
                instanceof RequestValidator.Accepted, "first binding");
        check(RequestValidator.validate(b, CONTEXT, OBSERVATION, fake::spec, fake.environment)
                instanceof RequestValidator.Accepted, "second binding");
        check(compiled.skill().artifact().descriptor().ref().equals(success(fake.compiler(),
                json(program(body(), List.of()))).skill().artifact().descriptor().ref()),
                "bindings do not enter artifact identity");
    }

    public static void outcomeTaxonomy() throws Exception {
        ArtifactRef ref = new ArtifactRef(CropDelivery.ID, A);
        EvidenceRef evidence = new EvidenceRef("trial-1", "validator-1", "world-test");
        for (ResolutionStatus status : ResolutionStatus.values()) {
            Reason reason = switch (status) {
                case RESOLVED, MISSING_IMPLEMENTATION, NEEDS_PLANNING -> null;
                case BLOCKED -> Reason.RESOURCE_MISSING;
                case UNSUPPORTED_RUNTIME -> Reason.UNSUPPORTED_PRIMITIVE;
                case INCOMPATIBLE -> Reason.ARTIFACT_INCOMPATIBLE;
                case UNAUTHORIZED -> Reason.AUTHORITY_DENIED;
            };
            StageOutcome item = new Resolution(status, reason,
                    status == ResolutionStatus.RESOLVED ? ref : null, "snapshot-1");
            check(item.equals(Outcomes.decode(Outcomes.encode(item))), "resolution round trip " + status);
        }
        for (ExecutionStatus status : ExecutionStatus.values()) {
            Reason reason = switch (status) {
                case SUCCEEDED -> null; case BLOCKED -> Reason.RESOURCE_MISSING;
                case FAILED -> Reason.ACTION_FAILED; case CANCELLED -> Reason.CANCELLED;
                case INTERRUPTED -> Reason.INTERRUPTED;
            };
            StageOutcome item = new Execution(status, reason, 2,
                    status == ExecutionStatus.SUCCEEDED ? evidence : null);
            check(item.equals(Outcomes.decode(Outcomes.encode(item))), "execution round trip " + status);
        }
        for (ResearchStatus status : ResearchStatus.values()) {
            Reason reason = switch (status) {
                case ADMITTED -> null; case NOT_ADMITTED -> Reason.ARTIFACT_INVALID;
                case BLOCKED -> Reason.STORAGE_UNAVAILABLE; case CANCELLED -> Reason.CANCELLED;
                case INTERRUPTED -> Reason.INTERRUPTED;
            };
            StageOutcome item = new Research(status, reason, ref, evidence,
                    status == ResearchStatus.ADMITTED, 2);
            check(item.equals(Outcomes.decode(Outcomes.encode(item))), "research round trip " + status);
        }
        for (Reason reason : Reason.values())
            check(reason == Outcomes.decodeReason(Outcomes.encodeReason(reason)), "reason round trip");
        expectIllegal(() -> new Resolution(ResolutionStatus.RESOLVED, Reason.RESOURCE_MISSING, ref, "s"));
        expectIllegal(() -> new Research(ResearchStatus.ADMITTED, null, ref, evidence, false, 1));
        expectIllegal(() -> new Execution(ExecutionStatus.SUCCEEDED, null, 0, null));
        try { Outcomes.decode("{\"schema\":1,\"stage\":\"resolution\",\"status\":\"FAILED\",\"snapshot\":\"x\"}");
            throw new AssertionError("invalid taxonomy accepted"); }
        catch (StrictJson.Invalid expected) { /* required */ }
    }

    public static void budgetComposition() {
        MutableClock clock = new MutableClock();
        Limits parentLimits = new Limits(Map.of(Kind.CALLS, 3L, Kind.REPAIRS, 1L,
                Kind.INPUT_BYTES, 300L, Kind.OUTPUT_BYTES, 300L), 2_000);
        Ledger parent = new Ledger(parentLimits, clock);
        check(parent.canDebit(Kind.CALLS, 3) && !parent.canDebit(Kind.CALLS, 4),
                "preflight observes cap without charging");
        InferenceLimits inference = new InferenceLimits(parentLimits, 100, 100);
        ResponseAllowance initial = inference.chargeCall(parent, 100, false);
        Ledger child = parent.child(new Limits(Map.of(Kind.CALLS, 2L, Kind.REPAIRS, 1L,
                Kind.INPUT_BYTES, 200L, Kind.OUTPUT_BYTES, 200L), 1_900));
        ResponseAllowance repaired = inference.chargeCall(child, 50, true);
        Ledger nested = child.child(new Limits(Map.of(Kind.CALLS, 1L, Kind.REPAIRS, 0L,
                Kind.INPUT_BYTES, 100L, Kind.OUTPUT_BYTES, 100L), 1_800));
        ResponseAllowance nestedOutput = inference.chargeCall(nested, 50, false);
        check(parent.snapshot().get(Kind.CALLS) == 3 && parent.snapshot().get(Kind.REPAIRS) == 1,
                "parent includes repair and nested call");
        check(!nested.canDebit(Kind.CALLS, 1), "child preflight checks parent use");
        expectExhausted(() -> inference.chargeCall(parent, 1, false));
        check(parent.snapshot().get(Kind.CALLS) == 3, "failed debit is atomic");
        expectExhausted(() -> inference.chargeCall(child, 1, true));
        expectExhausted(() -> initial.accept(101));
        initial.accept(60);
        expectExhausted(() -> initial.accept(41));
        initial.accept(40);
        initial.close();
        expectExhausted(() -> initial.accept(1));
        repaired.accept(100);
        nestedOutput.accept(100);
        expectExhausted(() -> nestedOutput.accept(1));
        expectIllegal(() -> parent.debit(Kind.CALLS, -1));
        Ledger overflow = new Ledger(new Limits(Map.of(Kind.TRAVEL_BLOCKS, Long.MAX_VALUE), 2_000), clock);
        overflow.debit(Kind.TRAVEL_BLOCKS, Long.MAX_VALUE);
        expectExhausted(() -> overflow.debit(Kind.TRAVEL_BLOCKS, 1));
        check(parent.snapshot().get(Kind.CALLS) == 3, "cancellation does not refund");
        clock.value = 2_001;
        check(!parent.canDebit(Kind.CALLS, 0), "preflight checks expired deadline");
        expectExhausted(() -> parent.debit(Kind.CALLS, 0));
        expectIllegal(() -> new Limits(Map.of(Kind.CALLS, -1L), 2_000));
        expectIllegal(() -> new InferenceLimits(new Limits(Map.of(Kind.CALLS, 0L,
                Kind.INPUT_BYTES, 0L, Kind.OUTPUT_BYTES, 0L), 2_000), 1, 1));
        Limits research = new Limits(Map.of(Kind.CALLS, 3L, Kind.REPAIRS, 1L,
                Kind.INPUT_BYTES, 300L, Kind.OUTPUT_BYTES, 300L, Kind.CANDIDATES, 2L,
                Kind.TRIALS, 1L, Kind.INSTRUCTIONS, 100L, Kind.ATTEMPTED_EFFECTS, 5L,
                Kind.COMMITTED_EFFECTS, 5L), 3_000);
        Limits inferenceChild = new Limits(Map.of(Kind.CALLS, 3L, Kind.REPAIRS, 1L,
                Kind.INPUT_BYTES, 300L, Kind.OUTPUT_BYTES, 300L), 3_000);
        Limits trialChild = new Limits(Map.of(Kind.INSTRUCTIONS, 100L,
                Kind.ATTEMPTED_EFFECTS, 5L, Kind.COMMITTED_EFFECTS, 5L), 3_000);
        new ResearchLimits(new InferenceLimits(inferenceChild, 100, 100),
                new ExecutionLimits(trialChild, 1_000), research);
        new ResearchLimits(new InferenceLimits(new Limits(inferenceChild.maxima(), 2_900),
                100, 100), new ExecutionLimits(new Limits(trialChild.maxima(), 2_900), 1_000),
                research);
        expectIllegal(() -> new ResearchLimits(new InferenceLimits(
                new Limits(inferenceChild.maxima(), 3_001), 100, 100),
                new ExecutionLimits(trialChild, 1_000), research));
        expectIllegal(() -> new ResearchLimits(new InferenceLimits(inferenceChild, 100, 100),
                new ExecutionLimits(new Limits(trialChild.maxima(), 3_001), 1_000), research));
        Ledger trialUsage = new Ledger(research, new MutableClock());
        trialUsage.debit(Kind.ATTEMPTED_EFFECTS, 5);
        trialUsage.debit(Kind.COMMITTED_EFFECTS, 3);
        check(trialUsage.snapshot().get(Kind.COMMITTED_EFFECTS) == 3,
                "attempted and committed effects are separate");
        expectExhausted(() -> trialUsage.debit(Kind.ATTEMPTED_EFFECTS, 1));
    }

    public static void dependencyIntegrity() {
        Fake fake = new Fake();
        SkillCompiler compiler = fake.compiler();
        ArtifactRef missing = new ArtifactRef(new CapabilityId("cognitivecraft:missing", 1), B);
        reject(compiler, json(program(List.of(skillCall(missing, "x"),
                Map.of("op", "result", "value", Map.of("param", "amount"))),
                List.of(refMap(missing)))), "MISSING_DEPENDENCY", "$.dependencies[0]");
        ArtifactRef a = fake.addDependency(30, List.of());
        ArtifactRef b = fake.addDependency(31, List.of(a));
        fake.descriptors.put(a, fake.descriptor(a, List.of(b)));
        reject(compiler, json(program(List.of(skillCall(a, "x"),
                Map.of("op", "result", "value", Map.of("param", "amount"))),
                List.of(refMap(a)))), "RECURSIVE_DEPENDENCY", "$.dependencies");
        fake.descriptors.put(a, fake.descriptor(a, List.of()));
        fake.descriptors.put(b, fake.descriptor(b, List.of(a)));
        ArtifactRef c = fake.addDependency(32, List.of(a, b));
        check(compiler.compile(json(program(List.of(skillCall(c, "x"),
                Map.of("op", "result", "value", Map.of("param", "amount"))),
                List.of(refMap(c))))) instanceof SkillCompiler.Success, "shared graph bounded");
        ArtifactRef terminal = fake.addDependency(40, List.of());
        ArtifactRef shared = fake.addDependency(41, List.of(terminal));
        ArtifactRef deep = shared;
        for (int i = 0; i < 6; i++) deep = fake.addDependency(42 + i, List.of(deep));
        ArtifactRef shallowAndDeep = fake.addDependency(50, List.of(shared, deep));
        reject(compiler, json(program(List.of(skillCall(shallowAndDeep, "x"),
                Map.of("op", "result", "value", Map.of("param", "amount"))),
                List.of(refMap(shallowAndDeep)))), "DEPENDENCY_DEPTH", "$.dependencies");
        ArtifactDescriptor original = fake.descriptors.get(a);
        fake.descriptors.put(a, new ArtifactDescriptor(a, 1, original.parameters(),
                original.resultType(), original.effects(), original.dependencies(),
                List.of(new PrimitiveRequirement("cognitivecraft:observe", 1, B))));
        reject(compiler, json(program(List.of(skillCall(c, "x"),
                Map.of("op", "result", "value", Map.of("param", "amount"))),
                List.of(refMap(c)))), "DEPENDENCY_INCOMPATIBLE", "$.dependencies");
        fake.descriptors.put(a, original);
        Map<String, Object> badRef = refMap(c);
        badRef.put("sha256", "F".repeat(64));
        reject(compiler, json(program(List.of(Map.of("op", "result", "value", Map.of("param", "amount"))),
                List.of(badRef))), "INVALID_REFERENCE", "$.dependencies[0]");
    }

    public static void trustedCompletion() {
        Fake fake = new Fake();
        SkillCompiler compiler = fake.compiler();
        Map<String, Object> forged = program(body(), List.of());
        forged.put("authority", "operator");
        reject(compiler, json(forged), "UNKNOWN_OR_MISSING_FIELD", "$");
        PrimitiveSignature forbidden = new PrimitiveSignature("cognitivecraft:danger", 1, A,
                List.of(), Type.UNIT, Set.of(Effect.TRANSFER));
        fake.primitiveMap.put(forbidden.id(), forbidden);
        Map<String, Object> extra = program(new ArrayList<>(List.of(
                primitiveCall("cognitivecraft:danger", A, Map.of(), ""),
                Map.of("op", "result", "value", Map.of("param", "amount")))), List.of());
        // The registered outcome permits transfer; no unregistered effect class can be injected.
        callAt(extra, 0).put("effect", "CREATE_ITEMS");
        reject(compiler, json(extra), "UNKNOWN_OR_MISSING_FIELD", "$.body[0]");
        CapabilityRequest request = request(4, SOURCE, TARGET);
        var accepted = (RequestValidator.Accepted) RequestValidator.validate(request, CONTEXT,
                OBSERVATION, fake::spec, fake.environment);
        UUID batch = new UUID(0, 11);
        List<CropDelivery.CropReceipt> receipts = List.of(
                receipt(RUN, batch, CropDelivery.Stage.HARVEST, 4),
                receipt(RUN, batch, CropDelivery.Stage.PICKUP, 4),
                receipt(RUN, batch, CropDelivery.Stage.DEPOSIT, 4));
        check(CropDelivery.completed(accepted.value(), RUN, receipts), "actual three-stage custody");
        check(!CropDelivery.completed(accepted.value(), new UUID(0, 99), receipts), "wrong run");
        check(!CropDelivery.completed(accepted.value(), RUN, receipts.subList(0, 2)), "no deposit");
        check(!CropDelivery.completed(accepted.value(), RUN, List.of(
                receipt(RUN, batch, CropDelivery.Stage.HARVEST, 4),
                receipt(RUN, new UUID(0, 12), CropDelivery.Stage.PICKUP, 4),
                receipt(RUN, batch, CropDelivery.Stage.DEPOSIT, 4))), "mixed batch");
        check(!CropDelivery.completed(accepted.value(), RUN, List.of(receipts.get(2), receipts.get(2),
                receipts.get(2))), "polling cannot duplicate credit");
        check(!fake.authority.currentlyAllows(ACTOR, Effect.HARVEST, CONTEXT),
                "compiled IR never grants effect authority");
    }

    public static void noExternalDependencies() {
        Fake fake = new Fake();
        success(fake.compiler(), json(program(body(), List.of())));
        check(fake.worldEffects == 0 && fake.modelCalls == 0 && fake.diskWrites == 0,
                "pure compiler cannot call gateway, model or filesystem");
    }

    private static CropDelivery.CropReceipt receipt(UUID run, UUID batch, CropDelivery.Stage stage,
                                                     long amount) {
        UUID receiptId = UUID.nameUUIDFromBytes((run + ":" + batch + ":" + stage + ":" + amount)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new CropDelivery.CropReceipt(receiptId, run, batch, ACTOR,
                SOURCE, TARGET, stage, amount);
    }
    private static CapabilityRequest request(long amount, Cuboid source, ContainerRef destination) {
        return new CapabilityRequest(CropDelivery.ID, Map.of("actor", new ActorValue(ACTOR),
                "amount", new IntValue(amount), "source", new AreaValue(source),
                "destination", new ContainerValue(destination)));
    }
    private static void invalidRequest(CapabilityRequest request, Fake fake) {
        var result = RequestValidator.validate(request, CONTEXT, OBSERVATION, fake::spec, fake.environment);
        check(result instanceof RequestValidator.Rejected rejected && rejected.reason() == Reason.REQUEST_INVALID,
                "REQUEST_INVALID expected");
    }
    private static Map<String, Object> program(List<Object> body, List<Object> dependencies) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", 1L); root.put("capability", CropDelivery.ID.name());
        root.put("capabilityVersion", 1L); root.put("dependencies", new ArrayList<>(dependencies));
        root.put("body", new ArrayList<>(body));
        return root;
    }
    private static List<Object> body() {
        return new ArrayList<>(List.of(
                primitiveCall("cognitivecraft:observe", A,
                        Map.of("source", Map.of("param", "source")), "observed"),
                primitiveCall("cognitivecraft:harvest", B,
                        Map.of("actor", Map.of("param", "actor"),
                                "source", Map.of("param", "source"),
                                "amount", Map.of("param", "amount")), "harvested"),
                primitiveCall("cognitivecraft:pickup", A,
                        Map.of("actor", Map.of("param", "actor"),
                                "amount", Map.of("param", "amount")), "picked"),
                primitiveCall("cognitivecraft:transfer", B,
                        Map.of("actor", Map.of("param", "actor"),
                                "destination", Map.of("param", "destination"),
                                "amount", Map.of("param", "amount")), "delivered"),
                Map.of("op", "result", "value", Map.of("local", "delivered"))));
    }
    private static Map<String, Object> primitiveCall(String id, String fingerprint,
                                                     Map<String, Object> args, String into) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("op", "call"); map.put("kind", "primitive"); map.put("id", id);
        map.put("version", 1L); map.put("fingerprint", fingerprint);
        map.put("args", args); map.put("into", into);
        return map;
    }
    private static Map<String, Object> skillCall(ArtifactRef ref, String into) {
        return new LinkedHashMap<>(Map.of("op", "call", "kind", "skill", "ref", refMap(ref),
                "args", Map.of("amount", Map.of("param", "amount")), "into", into));
    }
    private static Map<String, Object> bind(String name, long number) {
        return Map.of("op", "bind", "name", name, "type", "INT",
                "value", Map.of("int", number));
    }
    private static Map<String, Object> refMap(ArtifactRef ref) {
        return new LinkedHashMap<>(Map.of("capability", ref.capability().name(),
                "version", (long) ref.capability().version(), "sha256", ref.sha256()));
    }
    @SuppressWarnings("unchecked") private static List<Object> bodyOf(Map<String, Object> root) {
        return (List<Object>) root.get("body");
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> callAt(Map<String, Object> root, int index) {
        return (Map<String, Object>) bodyOf(root).get(index);
    }
    private static String json(Object value) { return StrictJson.canonical(value); }
    private static SkillCompiler.Success success(SkillCompiler compiler, String input) {
        SkillCompiler.CompileResult result = compiler.compile(input);
        if (!(result instanceof SkillCompiler.Success value))
            throw new AssertionError("Expected success, got " + result);
        return value;
    }
    private static void reject(SkillCompiler compiler, String input, String code, String path) {
        SkillCompiler.CompileResult result = compiler.compile(input);
        if (!(result instanceof SkillCompiler.Failure failure))
            throw new AssertionError("Expected " + code + ", got " + result);
        SkillCompiler.Diagnostic first = failure.diagnostics().get(0);
        check(first.code().equals(code), "Expected " + code + ", got " + first);
        check(first.path().startsWith(path), "Expected path " + path + ", got " + first.path());
    }
    private static void strictReject(String input, String code) {
        try { StrictJson.object(input); throw new AssertionError("Accepted malformed JSON: " + code); }
        catch (StrictJson.Invalid expected) { check(expected.code().equals(code), "JSON " + expected); }
    }
    private static void expectExhausted(Runnable work) {
        try { work.run(); throw new AssertionError("Expected budget exhaustion"); }
        catch (Budgets.Exhausted expected) { /* required */ }
    }
    private static void expectIllegal(Runnable work) {
        try { work.run(); throw new AssertionError("Expected invalid value"); }
        catch (IllegalArgumentException expected) { /* required */ }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class MutableClock extends Clock {
        long value = 1_000;
        public ZoneId getZone() { return ZoneId.of("UTC"); }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(value); }
        public long millis() { return value; }
    }
    private static final class Fake {
        boolean enrolled = true, loaded = true, effectGranted;
        final Set<TrustedContext> allowedContexts = new HashSet<>(Set.of(CONTEXT));
        int worldEffects, modelCalls, diskWrites;
        final Map<String, PrimitiveSignature> primitiveMap = new HashMap<>();
        final Map<ArtifactRef, ArtifactDescriptor> descriptors = new HashMap<>();
        final AuthorityPolicy authority = (actor, effect, context) -> effectGranted
                && actor.equals(ACTOR) && allowedContexts.contains(context);
        final RequestEnvironment environment = new RequestEnvironment() {
            public boolean enrolled(ActorRef actor, TrustedContext context) {
                return Fake.this.enrolled && actor.equals(ACTOR)
                        && allowedContexts.contains(context);
            }
            public boolean loaded(Cuboid cuboid, ObservationRef observation) {
                return Fake.this.loaded && observation.equals(OBSERVATION);
            }
            public boolean available(ContainerRef target, ObservationRef observation) {
                return observation.equals(OBSERVATION);
            }
        };
        Fake() {
            primitiveMap.put("cognitivecraft:observe", new PrimitiveSignature("cognitivecraft:observe",
                    1, A, List.of(new Parameter("source", Type.AREA, 0, 0)),
                    Type.INT, Set.of(Effect.OBSERVE)));
            primitiveMap.put("cognitivecraft:harvest", new PrimitiveSignature("cognitivecraft:harvest",
                    1, B, List.of(new Parameter("actor", Type.ACTOR, 0, 0),
                    new Parameter("source", Type.AREA, 0, 0), new Parameter("amount", Type.INT, 1, 64)),
                    Type.INT, Set.of(Effect.HARVEST)));
            primitiveMap.put("cognitivecraft:pickup", new PrimitiveSignature("cognitivecraft:pickup",
                    1, A, List.of(new Parameter("actor", Type.ACTOR, 0, 0),
                    new Parameter("amount", Type.INT, 1, 64)), Type.INT, Set.of(Effect.PICKUP)));
            primitiveMap.put("cognitivecraft:transfer", new PrimitiveSignature("cognitivecraft:transfer",
                    1, B, List.of(new Parameter("actor", Type.ACTOR, 0, 0),
                    new Parameter("destination", Type.CONTAINER, 0, 0),
                    new Parameter("amount", Type.INT, 1, 64)), Type.INT, Set.of(Effect.TRANSFER)));
        }
        Optional<CapabilitySpec> spec(CapabilityId id) {
            return id.equals(CropDelivery.ID) ? Optional.of(CropDelivery.SPEC) : Optional.empty();
        }
        SkillCompiler compiler() {
            return new SkillCompiler(this::spec,
                    (id, version) -> Optional.ofNullable(primitiveMap.get(id))
                            .filter(s -> s.version() == version),
                    ref -> Optional.ofNullable(descriptors.get(ref)));
        }
        ArtifactRef addDependency(int number, List<ArtifactRef> children) {
            ArtifactRef ref = new ArtifactRef(new CapabilityId("cognitivecraft:dep" + number, 1),
                    String.format("%064x", number + 1));
            descriptors.put(ref, descriptor(ref, children));
            return ref;
        }
        ArtifactDescriptor descriptor(ArtifactRef ref, List<ArtifactRef> children) {
            return new ArtifactDescriptor(ref, 1, List.of(new Parameter("amount", Type.INT, 1, 64)),
                    Type.INT, Set.of(Effect.OBSERVE), children, List.of());
        }
    }
}
