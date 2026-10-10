package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static dev.aivillages.core.kernel.Contracts.*;
import static org.junit.jupiter.api.Assertions.*;

class PrimitiveDiagnosticsTest {
    private static final String REQUESTED = "cognitivecraft:smelt_item";
    private static final String FINGERPRINT = "0".repeat(64); // Requested metadata, not runtime support.
    private static final CapabilityCatalog CAPABILITIES = id -> id.equals(CropDelivery.ID)
            ? Optional.of(CropDelivery.SPEC) : Optional.empty();

    static Map<String, Object> missingCall() {
        return new HashMap<>(Map.of("op", "call", "kind", "primitive", "id", REQUESTED,
                "version", 1L, "fingerprint", FINGERPRINT, "into", "missingResult",
                "args", Map.of("actor", Map.of("param", "actor"), "item", Map.of("int", 1L))));
    }
    static String program(List<?> body, List<?> dependencies) {
        return StrictJson.canonical(Map.of("schema", 1L, "capability", CropDelivery.ID.name(),
                "capabilityVersion", 1L, "dependencies", dependencies, "body", body));
    }
    static String missingIr() {
        return program(List.of(missingCall(), Map.of("op", "result", "value", Map.of("param", "amount"))), List.of());
    }
    private static SkillCompiler compiler(PrimitiveCatalog primitives) {
        return new SkillCompiler(CAPABILITIES, primitives, ref -> Optional.empty());
    }
    private static SkillCompiler.Failure reject(String source) {
        return assertInstanceOf(SkillCompiler.Failure.class, compiler(GatewayPrimitives.instance()).compile(source));
    }

    @Test void absentExactPairHasTypedEvidenceAndOneLookup() {
        AtomicInteger lookups = new AtomicInteger();
        var result = assertInstanceOf(SkillCompiler.Failure.class, compiler((id, version) -> {
            lookups.incrementAndGet(); return Optional.empty();
        }).compile(missingIr()));
        assertEquals(List.of(new SkillCompiler.Diagnostic("$.body[0].id", "UNKNOWN_PRIMITIVE")), result.diagnostics());
        var proof = result.unsupportedPrimitive().orElseThrow();
        assertEquals(new CapabilityId(REQUESTED, 1), proof.requested());
        assertEquals(FINGERPRINT, proof.requestedFingerprint());
        assertEquals(List.of(new SkillCompiler.ArgumentObservation(0, Type.ACTOR),
                new SkillCompiler.ArgumentObservation(1, Type.INT)), proof.arguments());
        assertNull(proof.catalogFingerprint());
        assertEquals(1, lookups.get());
    }

    static Stream<Arguments> invalidLocalCalls() {
        List<Arguments> cases = new ArrayList<>();
        cases.add(Arguments.of("id", "smelt_item", "INVALID_CAPABILITY"));
        cases.add(Arguments.of("id", "cognitivecraft:" + "a".repeat(97), "INVALID_CAPABILITY"));
        cases.add(Arguments.of("version", 0L, "INVALID_VERSION"));
        cases.add(Arguments.of("version", 1_000_001L, "INVALID_VERSION"));
        cases.add(Arguments.of("version", Long.MAX_VALUE, "INVALID_VERSION"));
        cases.add(Arguments.of("fingerprint", "A".repeat(64), "INVALID_STRUCTURE"));
        cases.add(Arguments.of("fingerprint", "0".repeat(63), "INVALID_STRUCTURE"));
        cases.add(Arguments.of("fingerprint", "0".repeat(65), "INVALID_STRUCTURE"));
        cases.add(Arguments.of("args", List.of(), "EXPECTED_OBJECT"));
        cases.add(Arguments.of("args", Map.of("bad name", Map.of("int", 1L)), "INVALID_SYMBOL"));
        cases.add(Arguments.of("args", Map.of("a".repeat(65), Map.of("int", 1L)), "INVALID_SYMBOL"));
        cases.add(Arguments.of("args", Map.of("item", "raw instruction"), "EXPECTED_OBJECT"));
        cases.add(Arguments.of("args", Map.of("item", Map.of("local", "unbound")), "UNRESOLVED_SYMBOL"));
        cases.add(Arguments.of("args", Map.of("item", Map.of("param", "unbound")), "UNRESOLVED_SYMBOL"));
        cases.add(Arguments.of("args", Map.of("item", Map.of("int", 1_000_001L)), "LITERAL_LIMIT"));
        cases.add(Arguments.of("args", Map.of("item", Map.of("operator", "ADD",
                "left", Map.of("bool", true), "right", Map.of("int", 1L))), "TYPE_MISMATCH"));
        cases.add(Arguments.of("args", Map.of("item", Map.of("int", 1L, "extra", 2L)), "UNKNOWN_OR_MISSING_FIELD"));
        cases.add(Arguments.of("into", "bad name", "INVALID_SYMBOL"));
        cases.add(Arguments.of("into", "actor", "DUPLICATE_SYMBOL"));
        cases.add(Arguments.of("into", "a".repeat(65), "INVALID_SYMBOL"));
        cases.add(Arguments.of("extra", "not a primitive call field", "UNKNOWN_OR_MISSING_FIELD"));
        Map<String, Object> tooMany = new HashMap<>();
        for (int i = 0; i < 17; i++) tooMany.put("arg" + i, Map.of("int", 1L));
        cases.add(Arguments.of("args", tooMany, "ARGUMENT_MISMATCH"));
        return cases.stream();
    }
    @ParameterizedTest @MethodSource("invalidLocalCalls")
    void invalidUnknownCallDoesNotEstablishAbsence(String key, Object value, String code) {
        var call = missingCall(); call.put(key, value);
        var result = reject(program(List.of(call), List.of()));
        assertEquals(code, result.diagnostics().getFirst().code());
        assertTrue(result.unsupportedPrimitive().isEmpty());
    }

    @Test void zeroAndSixteenArgumentsAndExactLocalNameLimitAreValidLocalEvidence() {
        for (int count : List.of(0, 16)) {
            Map<String, Object> args = new HashMap<>();
            for (int i = 0; i < count; i++) args.put("arg" + i, Map.of("bool", true));
            var call = missingCall(); call.put("args", args); call.put("into", "a".repeat(64));
            var proof = reject(program(List.of(call), List.of())).unsupportedPrimitive().orElseThrow();
            assertEquals(count, proof.arguments().size());
        }
        var call = missingCall(); call.put("into", "");
        assertTrue(reject(program(List.of(call), List.of())).unsupportedPrimitive().isPresent());
    }

    @Test void compilerKeepsFailFastOrderingWithoutClaimingWholeProgramValidity() {
        var invalid = Map.of("op", "invokeJava", "text", "UNKNOWN_PRIMITIVE");
        var earlier = reject(program(List.of(invalid, missingCall()), List.of()));
        assertEquals("UNKNOWN_OPCODE", earlier.diagnostics().getFirst().code());
        assertTrue(earlier.unsupportedPrimitive().isEmpty());
        var later = reject(program(List.of(missingCall(), invalid, missingCall()), List.of()));
        assertEquals(1, later.diagnostics().size());
        assertTrue(later.unsupportedPrimitive().isPresent());
        assertEquals("$.body[0].id", later.diagnostics().getFirst().path());
    }

    @Test void dependencyFailuresCannotBecomeGeneratedPrimitiveGaps() {
        var ref = Map.of("capability", CropDelivery.ID.name(), "version", 1L, "sha256", FINGERPRINT);
        var failure = reject(program(List.of(missingCall()), List.of(ref)));
        assertEquals("MISSING_DEPENDENCY", failure.diagnostics().getFirst().code());
        assertTrue(failure.unsupportedPrimitive().isEmpty());
        failure = reject(program(List.of(Map.of("op", "call", "kind", "skill", "ref", ref,
                "args", Map.of(), "into", ""), missingCall()), List.of()));
        assertEquals("UNPINNED_DEPENDENCY", failure.diagnostics().getFirst().code());
        assertTrue(failure.unsupportedPrimitive().isEmpty());
    }

    @Test void knownPairWrongFingerprintAndTypeStaySeparateFromAbsentVersion() {
        var signature = GatewayPrimitives.Operation.OBSERVE_INVENTORY.signature();
        var call = missingCall(); call.put("id", signature.id());
        var failure = reject(program(List.of(call), List.of()));
        assertEquals("PRIMITIVE_INCOMPATIBLE", failure.diagnostics().getFirst().code());
        assertTrue(failure.unsupportedPrimitive().isEmpty());
        call.put("fingerprint", signature.fingerprint()); call.put("args", Map.of("actor", Map.of("int", 1L)));
        failure = reject(program(List.of(call), List.of()));
        assertEquals("TYPE_MISMATCH", failure.diagnostics().getFirst().code());
        assertTrue(failure.unsupportedPrimitive().isEmpty());
        call.put("version", 2L);
        var proof = reject(program(List.of(call), List.of())).unsupportedPrimitive().orElseThrow();
        assertEquals(new CapabilityId(signature.id(), 2), proof.requested());
    }

    @Test void earlierDuplicateKeysAndInvalidSchemaDoNotCarryEvidence() {
        var duplicate = reject(missingIr().replace("\"schema\":1", "\"schema\":1,\"schema\":1"));
        assertEquals("DUPLICATE_FIELD", duplicate.diagnostics().getFirst().code());
        assertTrue(duplicate.unsupportedPrimitive().isEmpty());
        var schema = reject(missingIr().replace("\"schema\":1", "\"schema\":2"));
        assertEquals("UNKNOWN_SCHEMA", schema.diagnostics().getFirst().code());
        assertTrue(schema.unsupportedPrimitive().isEmpty());
    }

    @Test void candidateDefinedNamesAndValuesNeverEnterTypedEvidence() {
        String canary = "foreignPrincipalCredentialCanary";
        var call = missingCall(); call.put("into", canary);
        call.put("args", Map.of(canary, Map.of("local", canary + "Local")));
        var source = program(List.of(Map.of("op", "bind", "name", canary + "Local", "type", "INT",
                "value", Map.of("int", 987_654L)), call), List.of());
        var proof = reject(source).unsupportedPrimitive().orElseThrow();
        assertFalse(proof.toString().contains(canary));
        assertFalse(proof.toString().contains("987654"));
        assertEquals(List.of(new SkillCompiler.ArgumentObservation(0, Type.INT)), proof.arguments());
    }

    @Test void completeCatalogIdentityIsStableAndIncludesAllSignatureSemantics() {
        var signatures = GatewayPrimitives.instance().all();
        var snapshot = PrimitiveDiagnostics.completeCatalog(signatures);
        var shuffled = new ArrayList<>(signatures); Collections.reverse(shuffled);
        assertEquals(snapshot.fingerprint(), PrimitiveDiagnostics.completeCatalog(shuffled).fingerprint());
        assertEquals(signatures.size(), snapshot.size());
        signatures.forEach(s -> assertEquals(Optional.of(s), snapshot.find(s.id(), s.version())));
        var proof = assertInstanceOf(SkillCompiler.Failure.class, compiler(snapshot).compile(missingIr()))
                .unsupportedPrimitive().orElseThrow();
        assertEquals(snapshot.fingerprint(), proof.catalogFingerprint());
        var original = signatures.getFirst();
        var changed = new ArrayList<>(signatures);
        changed.set(0, new PrimitiveSignature(original.id(), original.version(), original.fingerprint(),
                original.parameters(), Type.BOOL, original.effects()));
        assertNotEquals(snapshot.fingerprint(), PrimitiveDiagnostics.completeCatalog(changed).fingerprint());
        assertThrows(IllegalArgumentException.class, () -> PrimitiveDiagnostics.completeCatalog(List.of(original, original)));
    }

    @Test void completeCatalogHasFiniteExactMaximumAndDoesNotAddSupport() {
        var signatures = new ArrayList<PrimitiveSignature>();
        for (int i = 0; i < 128; i++) signatures.add(new PrimitiveSignature("fixture:operation" + i,
                1, FINGERPRINT, List.of(), Type.UNIT, Set.of()));
        assertEquals(128, PrimitiveDiagnostics.completeCatalog(signatures).size());
        signatures.add(new PrimitiveSignature("fixture:excess", 1, FINGERPRINT, List.of(), Type.UNIT, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> PrimitiveDiagnostics.completeCatalog(signatures));
        assertEquals(0, PrimitiveDiagnostics.completeCatalog(List.of()).size());
    }

    @Test void supportedCompilationKeepsArtifactIdentityAndLegacyFailureConstructor() {
        var signature = GatewayPrimitives.Operation.OBSERVE_INVENTORY.signature();
        var source = program(List.of(Map.of("op", "call", "kind", "primitive", "id", signature.id(),
                "version", 1L, "fingerprint", signature.fingerprint(),
                "args", Map.of("actor", Map.of("param", "actor")), "into", "stock"),
                Map.of("op", "result", "value", Map.of("param", "amount"))), List.of());
        var normal = assertInstanceOf(SkillCompiler.Success.class, compiler(GatewayPrimitives.instance()).compile(source));
        var diagnostic = assertInstanceOf(SkillCompiler.Success.class,
                compiler(PrimitiveDiagnostics.completeCatalog(GatewayPrimitives.instance().all())).compile(source));
        assertEquals(normal.skill().artifact(), diagnostic.skill().artifact());
        assertEquals(normal.skill().program(), diagnostic.skill().program());
        assertTrue(new SkillCompiler.Failure(List.of(new SkillCompiler.Diagnostic("$", "INVALID_STRUCTURE")))
                .unsupportedPrimitive().isEmpty());
    }

    @Test void protocolMetadataIsBoundedOpaqueAndUnsafeDiagnosticTextIsOmitted() {
        for (String protocol : List.of("ollama-chat-v1", "fixture.generation_v2", "a", "a".repeat(64)))
            assertEquals(protocol, new Generation.Descriptor(protocol, "fixture-model", null).protocol());
        for (String protocol : List.of("", "A", "-protocol", "a".repeat(65), "a\n", "a/route", "λ"))
            assertThrows(IllegalArgumentException.class, () -> new Generation.Descriptor(protocol, "model", null));
        assertThrows(IllegalArgumentException.class, () -> new Generation.Descriptor(null, "model", null));
        var safe = PrimitiveDiagnostics.ProviderMetadata.project(new Generation.Descriptor("fixture-v2", "Qwen3:0.6b-q4", "sha256:abc"));
        assertFalse(safe.modelOmitted()); assertFalse(safe.digestOmitted());
        for (String text : List.of("private\ncanary", "private\u202ecanary", "λ".repeat(100), "\ud800")) {
            var metadata = PrimitiveDiagnostics.ProviderMetadata.project(new Generation.Descriptor("fixture-v2", text, text));
            assertNull(metadata.model()); assertNull(metadata.digest());
            assertTrue(metadata.modelOmitted()); assertTrue(metadata.digestOmitted());
        }
        assertEquals(PrimitiveDiagnostics.Offer.DISABLED, PrimitiveDiagnostics.noop().offer(null));
    }
}
