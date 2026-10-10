package dev.aivillages.providers;

import dev.aivillages.core.kernel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static dev.aivillages.core.kernel.Budgets.Kind;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Controlled Ollama backend, actual adapter/broker/controller/compiler; no genuine-model claim. */
class PrimitiveDiagnosticAdapterTest {
    @TempDir Path temporary;
    private static String fullIr(Object body, List<?> dependencies) {
        return StrictJson.canonical(Map.of("schema", 1L, "capability", CropDelivery.ID.name(),
                "capabilityVersion", 1L, "dependencies", dependencies, "body", body));
    }
    private static String missingIr() {
        return fullIr(List.of(Map.of("op", "call", "kind", "primitive", "id", "cognitivecraft:smelt_item",
                "version", 1L, "fingerprint", "0".repeat(64), "args", Map.of("item", Map.of("int", 1L)),
                "into", "missingResult"), Map.of("op", "result", "value", Map.of("param", "amount"))), List.of());
    }
    private static Budgets.InferenceLimits inference(GenerationChecks.Fixture f, long perResponse) {
        return new Budgets.InferenceLimits(new Budgets.Limits(Map.of(Kind.CALLS, 3L, Kind.REPAIRS, 1L,
                Kind.INPUT_BYTES, 32_768L, Kind.OUTPUT_BYTES, 32_768L), f.clock.millis() + 10_000), 16_384, perResponse);
    }
    private static Generation.Result response(String candidate) {
        try (var f = new GenerationChecks.Fixture()) {
            var limits = inference(f, 8192);
            var handle = f.generate(Generation.Role.INITIAL, "", new Budgets.Ledger(limits.total(), f.clock), limits);
            f.backend.respond(0, candidate, 10L, 10L);
            return handle.result().toCompletableFuture().join();
        }
    }

    @Test void fullIrListPassesThroughUnchangedToCompilerOwnedAbsenceProof() {
        var source = missingIr(); var result = response(source);
        assertEquals(Generation.Outcome.CANDIDATE, result.outcome());
        assertEquals(source, result.candidateIr());
        var compiled = new SkillCompiler(id -> Optional.of(CropDelivery.SPEC), GatewayPrimitives.instance(),
                ref -> Optional.empty()).compile(result.candidateIr());
        var rejected = assertInstanceOf(SkillCompiler.Failure.class, compiled);
        assertEquals("UNKNOWN_PRIMITIVE", rejected.diagnostics().getFirst().code());
        assertTrue(rejected.unsupportedPrimitive().isPresent());
        assertEquals(source.getBytes(StandardCharsets.UTF_8).length, result.usage().outputBytes());
    }

    @Test void compactKnownBehaviorAndUnknownCompactRejectionRemainIntact() {
        assertEquals(Generation.Outcome.CANDIDATE, response(BrokerAdapterIntegrationTest.CANDIDATE).outcome());
        var unknown = response(BrokerAdapterIntegrationTest.CANDIDATE.replace("cognitivecraft:harvest_next_wheat", "cognitivecraft:smelt_item"));
        assertEquals(Generation.Outcome.MALFORMED, unknown.outcome());
        assertEquals(Reason.ARTIFACT_INVALID, unknown.reason()); assertNull(unknown.candidateIr());
    }

    @Test void undisclosedButRegisteredFullIrReferenceIsNotAFalseRuntimeGap() {
        var signature = GatewayPrimitives.Operation.OBSERVE_CONTAINER.signature();
        var source = fullIr(List.of(Map.of("op", "call", "kind", "primitive", "id", signature.id(),
                "version", 1L, "fingerprint", signature.fingerprint(), "args", Map.of("actor", Map.of("param", "actor"),
                        "destination", Map.of("param", "destination")), "into", "stock"),
                Map.of("op", "result", "value", Map.of("param", "amount"))), List.of());
        var result = response(source);
        assertEquals(Generation.Outcome.CANDIDATE, result.outcome());
        assertInstanceOf(SkillCompiler.Success.class, new SkillCompiler(id -> Optional.of(CropDelivery.SPEC),
                GatewayPrimitives.instance(), ref -> Optional.empty()).compile(result.candidateIr()));
    }

    @Test void invalidBodyEnvelopeAndUndisclosedDependenciesStayMalformed() {
        var ref = Map.of("capability", CropDelivery.ID.name(), "version", 1L, "sha256", "0".repeat(64));
        for (String source : List.of(fullIr("scalar body", List.of()),
                missingIr().replace("\"schema\":1", "\"schema\":2"),
                missingIr().replace(CropDelivery.ID.name(), "fixture:foreign_capability"),
                fullIr(List.of(), List.of(ref)), missingIr().replace("\"dependencies\":[]", "\"dependencies\":[],\"extra\":true"))) {
            var result = response(source);
            assertEquals(Generation.Outcome.MALFORMED, result.outcome()); assertNull(result.candidateIr());
        }
    }

    @Test void fullIrRemainsUntrustedAndEnforcesTheSameResponseAndTokenLimits() {
        var malformedNodes = response(fullIr(List.of(Map.of("op", "invokeJava")), List.of()));
        assertEquals(Generation.Outcome.CANDIDATE, malformedNodes.outcome());
        var failure = assertInstanceOf(SkillCompiler.Failure.class, new SkillCompiler(id -> Optional.of(CropDelivery.SPEC),
                GatewayPrimitives.instance(), ref -> Optional.empty()).compile(malformedNodes.candidateIr()));
        assertEquals("UNKNOWN_OPCODE", failure.diagnostics().getFirst().code());
        assertTrue(failure.unsupportedPrimitive().isEmpty());
        for (boolean tokenExcess : List.of(false, true)) try (var f = new GenerationChecks.Fixture()) {
            var limits = inference(f, tokenExcess ? 8192 : 1);
            var handle = f.generate(Generation.Role.INITIAL, "", new Budgets.Ledger(limits.total(), f.clock), limits);
            f.backend.respond(0, missingIr(), 10L, tokenExcess ? 257L : 10L);
            var result = handle.result().toCompletableFuture().join();
            assertEquals(Generation.Outcome.LIMIT, result.outcome()); assertNull(result.candidateIr());
        }
    }

    @Test void actualAdapterBrokerResearchCompilerOffersOneGeneratedGapWithoutRepair() throws Exception {
        try (var f = new GenerationChecks.Fixture()) {
            var request = GenerationChecks.request(Generation.Role.INITIAL, "controlled integration");
            var bound = request.bound(); var owner = bound.context();
            CapabilityCatalog capabilities = id -> id.equals(CropDelivery.ID) ? Optional.of(CropDelivery.SPEC) : Optional.empty();
            var primitives = PrimitiveDiagnostics.completeCatalog(GatewayPrimitives.instance().all());
            var guard = new ResearchAdmissionController.DecisionGuard();
            try (var repository = VersionedSkillRepository.open(temporary.resolve("adapter-flow"),
                    new VersionedSkillRepository.RuntimeSnapshot("minecraft-26.3", capabilities, primitives),
                    VersionedSkillRepository.Limits.defaults(), guard, TrustedContext::equals,
                    VersionedSkillRepository.FaultInjector.none());
                 var broker = new AIWorkBroker(f.adapter, f.clock, AIWorkBroker.Settings.defaults(),
                         AIWorkBroker.sessionLimits(f.clock.millis()), r -> true, AIWorkBroker.Sharing.privateScopes())) {
                var area = ((AreaValue) bound.request().arguments().get("source")).value();
                var observation = new ObservationSnapshot(bound.observation(), ObservationStatus.PRESENT,
                        "source:" + UUID.nameUUIDFromBytes(area.toString().getBytes(StandardCharsets.UTF_8)),
                        100, Math.toIntExact((area.maxX() - area.minX() + 1L) * (area.maxY() - area.minY() + 1L)
                        * (area.maxZ() - area.minZ() + 1L)), Map.of("mature_wheat", 2L, "unknown_cells", 0L));
                var resolver = new CapabilityResolver.Engine(capabilities, primitives, CapabilityResolver.exactCatalog(repository),
                        null, CapabilityResolver.cropDeliverySupport(), (actor, caller) -> owner.equals(caller),
                        (actor, ref, caller) -> true, (actor, effect, caller) -> owner.equals(caller),
                        (r, method, observed) -> null, () -> 100, CapabilityResolver.Limits.defaults());
                var tasks = new ArrayDeque<Runnable>();
                var observed = new ArrayList<PrimitiveDiagnostics.Observation>();
                var staging = new ResearchAdmissionController.TrialArtifacts(repository);
                var grants = new ResearchAdmissionController.TrialGrants(f.clock);
                var trial = new ResearchAdmissionController.TrialPort() {
                    @Override public BoundedSkillExecutor.Start start(ValidatedRequest r, ArtifactRef ref, RunCorrelation run,
                            Budgets.ExecutionLimits limits, Budgets.Ledger usage, BoundedSkillExecutor.TrialPermit permit) {
                        throw new AssertionError("Unknown primitive cannot start a trial");
                    }
                    @Override public BoundedSkillExecutor.Progress tick(TrustedContext context) { throw new AssertionError("No trial"); }
                    @Override public BoundedSkillExecutor.Progress cancel(TrustedContext context) { throw new AssertionError("No trial"); }
                };
                var controller = new ResearchAdmissionController(capabilities, primitives, repository, broker,
                        (skill, r, limits, usage, cancelled) -> { throw new AssertionError("Unknown primitive cannot start fixtures"); },
                        trial, staging, grants, ResearchAdmissionController.repositoryPublication(repository, Runnable::run),
                        guard, resolver, (actor, caller) -> owner.equals(caller), (actor, effect, caller) -> owner.equals(caller),
                        f.clock, tasks::add, new ResearchAdmissionController.Settings(3, 8, 2048),
                        event -> { observed.add(event); return PrimitiveDiagnostics.Offer.ACCEPTED; });
                var inference = inference(f, 8192);
                var total = new HashMap<>(inference.total().maxima());
                total.put(Kind.CANDIDATES, 3L); total.put(Kind.TRIALS, 1L);
                var trialLimits = new Budgets.ExecutionLimits(new Budgets.Limits(Map.of(Kind.CALLS, 1L),
                        inference.total().deadlineEpochMillis()), 5000);
                var limits = new Budgets.ResearchLimits(inference, trialLimits,
                        new Budgets.Limits(total, inference.total().deadlineEpochMillis()));
                var route = resolver.resolve(bound, observation);
                assertEquals(ResolutionStatus.MISSING_IMPLEMENTATION, route.routing().status(), route.toString());
                var attempt = controller.startMissing(route, bound, limits);
                broker.step(); assertEquals(1, f.backend.sinks.size(), attempt.status(owner) + "; " + broker.stats());
                var source = missingIr(); f.backend.respond(0, source, 10L, 10L); broker.step();
                assertEquals(ResearchAdmissionController.Phase.VALIDATING, attempt.tick(owner).phase());
                assertEquals(1, tasks.size()); tasks.remove().run();
                var status = attempt.tick(owner);
                assertEquals(ResearchStatus.NOT_ADMITTED, status.outcome().status());
                assertEquals(Reason.UNSUPPORTED_PRIMITIVE, status.outcome().reason());
                assertEquals(1, status.outcome().modelCalls()); assertEquals(1, observed.size());
                assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))),
                        observed.getFirst().candidateSha256());
                assertEquals("ollama-chat-v1", observed.getFirst().provider().protocol());
                f.backend.respond(0, source, 10L, 10L); broker.step(); attempt.tick(owner);
                assertEquals(1, observed.size()); assertEquals(1, f.backend.sinks.size());
                assertEquals(0, status.usage().getOrDefault(Kind.REPAIRS, 0L));
                assertEquals(0, status.usage().getOrDefault(Kind.TRIALS, 0L));
                assertEquals(0, status.committedEffects());
            }
        }
    }
}
