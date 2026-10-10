package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.ArtifactCompatibility.*;
import static dev.aivillages.core.kernel.VersionedSkillRepository.*;
import static org.junit.jupiter.api.Assertions.*;

final class ArtifactCompatibilityTest {
    @TempDir Path world;
    static final CapabilityId ID = new CapabilityId("test:compatibility", 1);
    static final CapabilitySpec SPEC = new CapabilitySpec(ID, List.of(new Parameter("amount", Type.INT, 0, 64)),
            Type.INT, Set.of(), Completion.ATTRIBUTABLE_CROP_DELIVERY);
    static final PrimitiveSignature PRIMITIVE = new PrimitiveSignature("test:value", 1, "a".repeat(64),
            List.of(), Type.INT, Set.of());
    static final TrustedContext OWNER = new TrustedContext(new PrincipalRef(new UUID(0, 1)),
            new ScopeRef(new UUID(0, 2), new UUID(0, 3)));
    static final EvidenceRef EVIDENCE = new EvidenceRef("synthetic", "fixture-1", "fixture");
    static RuntimeSnapshot runtime(PrimitiveCatalog catalog) {
        return new RuntimeSnapshot("minecraft-26.3", id -> id.equals(ID) ? Optional.of(SPEC) : Optional.empty(), catalog);
    }
    static RuntimeSnapshot runtime() { return runtime((id, v) -> id.equals(PRIMITIVE.id()) && v == 1 ? Optional.of(PRIMITIVE) : Optional.empty()); }
    static SkillCompiler.Success compile(List<?> body, List<ArtifactRef> dependencies, ArtifactCatalog catalog) {
        var result = new SkillCompiler(runtime().capabilities(), runtime().primitives(), catalog).compile(
                StrictJson.canonical(Map.of("schema", 1L, "capability", ID.name(), "capabilityVersion", 1L,
                        "dependencies", dependencies.stream().map(RepositoryCodec::ref).toList(), "body", body)));
        return assertInstanceOf(SkillCompiler.Success.class, result);
    }
    static SkillCompiler.Success literal(int n) { return compile(List.of(Map.of("op", "result", "value", Map.of("int", n))), List.of(), r -> Optional.empty()); }
    static SkillArtifact primitive() {
        return compile(List.of(Map.of("op", "call", "kind", "primitive", "id", PRIMITIVE.id(), "version", 1L,
                "fingerprint", PRIMITIVE.fingerprint(), "args", Map.of(), "into", "v"),
                Map.of("op", "result", "value", Map.of("local", "v"))), List.of(), r -> Optional.empty()).skill().artifact();
    }
    static SkillArtifact parent(SkillArtifact child) {
        return compile(List.of(Map.of("op", "call", "kind", "skill", "ref", RepositoryCodec.ref(child.descriptor().ref()),
                "args", Map.of("amount", Map.of("param", "amount")), "into", "v"),
                Map.of("op", "result", "value", Map.of("local", "v"))), List.of(child.descriptor().ref()),
                r -> r.equals(child.descriptor().ref()) ? Optional.of(child.descriptor()) : Optional.empty()).skill().artifact();
    }
    static VersionedSkillRepository store(Path world) throws Exception {
        return VersionedSkillRepository.open(world, runtime(), VersionedSkillRepository.Limits.defaults(), d -> true,
                TrustedContext::equals, FaultInjector.none());
    }
    static void admit(VersionedSkillRepository store, SkillArtifact artifact) {
        var bundle = new EvidenceBundle(EVIDENCE, EVIDENCE, EVIDENCE, "b".repeat(64));
        var decision = new AdmissionDecision(UUID.randomUUID(), OWNER, bundle);
        var origin = new Provenance(1, null, "compiler-1", "minecraft-26.3", OWNER.scope().worldId(),
                artifact.descriptor().dependencies(), List.of(EVIDENCE));
        assertEquals(PublishStatus.ADMITTED, store.publish(artifact, decision, origin).status());
    }
    static Map<String,String> hashes(Path world) throws Exception {
        var result = new TreeMap<String,String>();
        try (var paths = Files.walk(world)) {
            for (Path file : paths.filter(Files::isRegularFile).toList())
                result.put(world.relativize(file).toString(), HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        }
        return result;
    }

    @Test void relevantFingerprintChangesPreserveAdmissionEvidenceAndEveryRepositoryByte() throws Exception {
        var child = primitive();
        try (var store = store(world)) {
            admit(store, child); var admission = store.resolve(child.descriptor().ref()).admission();
            var before = hashes(world); var original = store.assessCompatibility(child.descriptor().ref());
            assertEquals(CompatibilityStatus.COMPATIBLE, original.compatibility().status());
            store.updateRuntime(runtime((id, v) -> id.equals("unused:extra") ? Optional.of(new PrimitiveSignature(
                    id, v, "c".repeat(64), List.of(), Type.INT, Set.of())) : runtime().primitives().find(id, v)));
            assertEquals(original.relevantFingerprint(), store.assessCompatibility(child.descriptor().ref()).relevantFingerprint());
            store.updateRuntime(runtime((id, v) -> Optional.of(new PrimitiveSignature(id, v, "d".repeat(64), List.of(), Type.INT, Set.of()))));
            var changed = store.assessCompatibility(child.descriptor().ref());
            assertEquals(CompatibilityStatus.INCOMPATIBLE, changed.compatibility().status());
            assertEquals(Issue.PRIMITIVE_SIGNATURE, changed.causes().getFirst().issue());
            assertEquals(child.descriptor().ref(), changed.causes().getFirst().affected());
            assertNotEquals(original.relevantFingerprint(), changed.relevantFingerprint());
            assertEquals(admission, store.resolve(child.descriptor().ref()).admission());
            assertEquals(before, hashes(world));
            store.updateRuntime(runtime()); assertTrue(store.resolve(child.descriptor().ref()).usable());
        }
    }
    @Test void transitivePinnedChildHasExactCauseAndNeverSubstitutesLatest() throws Exception {
        var child = primitive(); var parent = parent(child);
        try (var store = store(world)) {
            admit(store, child); admit(store, parent); admit(store, literal(9).skill().artifact());
            store.updateRuntime(runtime((id, v) -> Optional.empty()));
            var assessed = store.assessCompatibility(parent.descriptor().ref());
            assertEquals(List.of(Outcomes.Reason.DEPENDENCY_INCOMPATIBLE), assessed.compatibility().reasons());
            assertEquals(child.descriptor().ref(), assessed.causes().getFirst().affected());
            assertEquals(List.of(parent.descriptor().ref(), child.descriptor().ref()), assessed.causes().getFirst().path());
            assertFalse(store.resolve(parent.descriptor().ref()).usable());
            assertEquals(AdmissionStatus.ADMITTED, store.resolve(parent.descriptor().ref()).admission().status());
        }
    }
    @Test void capturedProviderDoesNotMixRuntimeRevisionsAndRebuildUsesCurrentSemantics() throws Exception {
        var artifact = primitive();
        try (var store = store(world)) {
            admit(store, artifact); var captured = store.compatibilityProvider();
            store.updateRuntime(new RuntimeSnapshot("changed-target", runtime().capabilities(), runtime().primitives()));
            assertEquals(CompatibilityStatus.COMPATIBLE, captured.provider().assess(artifact.descriptor().ref(),
                    ArtifactCompatibility.Limits.defaults(), true).compatibility().status());
            assertEquals(Issue.GAME_TARGET, store.assessCompatibility(artifact.descriptor().ref()).causes().getFirst().issue());
            assertNotEquals(captured.revision().runtime(), store.compatibilityProvider().revision().runtime());
        }
    }
    @Test void semanticTransformationIsNewUnadmittedProposalAndRepresentationalChangeKeepsIdentity() throws Exception {
        var original = literal(1); var changed = literal(2);
        try (var store = store(world)) {
            admit(store, original.skill().artifact()); var before = hashes(world);
            var proposal = inspectTransformation(original.skill().artifact().descriptor().ref(), changed);
            assertTrue(proposal.changesIdentity()); assertEquals(3, proposal.required().size());
            assertNull(store.resolve(proposal.proposed().descriptor().ref()));
            assertEquals(before, hashes(world));
            var same = inspectTransformation(original.skill().artifact().descriptor().ref(), original);
            assertFalse(same.changesIdentity()); assertTrue(same.required().isEmpty());
        }
    }
    static ArtifactRef ref(int n) { return new ArtifactRef(ID, "%064x".formatted(n)); }
    static Entry entry(ArtifactRef ref, List<ArtifactRef> children) {
        return new Entry(SPEC, new ArtifactDescriptor(ref, 1, SPEC.parameters(), Type.INT, Set.of(), children, List.of()),
                literal(1).skill().artifact().canonicalIr(), "minecraft-26.3",
                new AdmissionRecord(ref, AdmissionStatus.ADMITTED, EVIDENCE, null, 1), Integrity.VERIFIED);
    }
    @Test void missingChildCyclesUnknownFormatsAndHostileRuntimeAreInactiveWithBoundedReferences() {
        var root = ref(1); var missing = ref(2);
        var graph = Map.of(root, entry(root, List.of(missing)));
        var result = assess(root, r -> Optional.ofNullable(graph.get(r)), runtime(), ArtifactCompatibility.Limits.defaults(), false);
        assertEquals(missing, result.causes().getFirst().affected()); assertEquals(Issue.MISSING_ARTIFACT, result.causes().getFirst().issue());
        result = assess(root, r -> Optional.of(entry(root, List.of(root))), runtime(), ArtifactCompatibility.Limits.defaults(), false);
        assertEquals(Issue.DEPENDENCY_CYCLE, result.causes().getFirst().issue());
        result = assess(root, r -> Optional.of(new Entry(null, null, null, null, null, Integrity.UNKNOWN_SCHEMA)), runtime(), ArtifactCompatibility.Limits.defaults(), true);
        assertEquals(CompatibilityStatus.UNKNOWN, result.compatibility().status());
        result = assess(root, r -> Optional.of(entry(root, List.of())), runtime((id, v) -> { throw new IllegalStateException(); }),
                ArtifactCompatibility.Limits.defaults(), true);
        // This artifact uses no primitive; unavailable unrelated lookup must never be consulted.
        assertNotEquals(Issue.RUNTIME_UNAVAILABLE, result.causes().isEmpty() ? null : result.causes().getFirst().issue());
        result = assess(root, r -> { throw new IllegalStateException(); }, runtime(), ArtifactCompatibility.Limits.defaults(), false);
        assertEquals(CompatibilityStatus.UNKNOWN, result.compatibility().status());
        assertEquals(Issue.RUNTIME_UNAVAILABLE, result.causes().getFirst().issue());
    }
    @Test void exactTraversalLimitCompilerByteLimitAndZeroDiagnosticsStayFinite() throws Exception {
        var root = ref(1); var graph = new HashMap<ArtifactRef,Entry>(); var branches = new ArrayList<ArtifactRef>();
        for (int i=0; i<16; i++) {
            ArtifactRef branch=ref(2+i); branches.add(branch); var leaves=new ArrayList<ArtifactRef>();
            for(int j=0;j<8;j++){var leaf=ref(100+i*8+j);leaves.add(leaf);graph.put(leaf,entry(leaf,List.of()));}
            graph.put(branch,entry(branch,leaves));
        }
        graph.put(root,entry(root,branches));
        var result=assess(root,r->Optional.ofNullable(graph.get(r)),runtime(),ArtifactCompatibility.Limits.defaults(),false);
        assertEquals(128,result.visits()); assertEquals(CompatibilityStatus.UNKNOWN,result.compatibility().status());
        assertEquals(Issue.TRAVERSAL_LIMIT,result.causes().getFirst().issue());
        var literal=literal(1).skill().artifact();
        var valid=new Entry(SPEC,literal.descriptor(),literal.canonicalIr(),"minecraft-26.3",
                new AdmissionRecord(literal.descriptor().ref(),AdmissionStatus.ADMITTED,EVIDENCE,null,1),Integrity.VERIFIED);
        int bytes=literal.canonicalIr().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        var exact=assess(literal.descriptor().ref(),r->Optional.of(valid),runtime(),new ArtifactCompatibility.Limits(1,0,1,bytes),true);
        assertEquals(CompatibilityStatus.COMPATIBLE,exact.compatibility().status()); assertEquals(bytes,exact.compilerBytes());
        var below=assess(literal.descriptor().ref(),r->Optional.of(valid),runtime(),new ArtifactCompatibility.Limits(1,0,0,bytes-1),true);
        assertEquals(CompatibilityStatus.UNKNOWN,below.compatibility().status()); assertTrue(below.causes().isEmpty()); assertFalse(below.diagnosticsComplete());
        Path out=Path.of("build/compatibility-evidence");Files.createDirectories(out);
        Files.writeString(out.resolve("assessment.json"),StrictJson.canonical(Map.of("policy",1L,"maxVisitsMeasured",(long)result.visits(),
                "diagnostics",(long)result.causes().size(),"exactCompilerBytes",(long)exact.compilerBytes(),"zeroDiagnosticOmission",!below.diagnosticsComplete())));
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3}) void limitsAcceptZeroAndNButRejectNegativeNPlusOneAndOverflow(int field) {
        long[] max={128,8,16,8_388_608};
        for(long invalid:new long[]{-1,max[field]+1,field==3?Long.MAX_VALUE:Integer.MAX_VALUE}) {
            long[] values=max.clone();values[field]=invalid;
            assertThrows(IllegalArgumentException.class,()->new ArtifactCompatibility.Limits((int)values[0],(int)values[1],(int)values[2],values[3]));
        }
        long[] zero=max.clone();zero[field]=0;
        assertDoesNotThrow(()->new ArtifactCompatibility.Limits((int)zero[0],(int)zero[1],(int)zero[2],zero[3]));
        assertDoesNotThrow(ArtifactCompatibility.Limits::defaults);
    }
}
