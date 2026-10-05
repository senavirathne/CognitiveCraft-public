package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.CapabilityResolver.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.Outcomes.*;
import dev.aivillages.core.kernel.VersionedSkillRepository.AdmissionDecision;
import dev.aivillages.core.kernel.VersionedSkillRepository.Candidate;
import dev.aivillages.core.kernel.VersionedSkillRepository.EvidenceBundle;
import dev.aivillages.core.kernel.VersionedSkillRepository.FaultInjector;
import dev.aivillages.core.kernel.VersionedSkillRepository.Integrity;
import dev.aivillages.core.kernel.VersionedSkillRepository.Page;
import dev.aivillages.core.kernel.VersionedSkillRepository.PublishStatus;
import dev.aivillages.core.kernel.VersionedSkillRepository.RuntimeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** IMP-028: all seven routes, owner integration, precedence, limits and zero effects. */
final class ResolverJUnitTest {
    private static final String DIM = "minecraft:overworld";
    private static final ActorRef ACTOR = new ActorRef(new UUID(0, 1), new UUID(0, 2), DIM);
    private static final TrustedContext OWNER = new TrustedContext(new PrincipalRef(new UUID(0, 3)),
            new ScopeRef(new UUID(0, 4), new UUID(0, 5)));
    private static final TrustedContext FOREIGN = new TrustedContext(new PrincipalRef(new UUID(0, 6)),
            new ScopeRef(new UUID(0, 4), new UUID(0, 7)));
    private static final Cuboid AREA = new Cuboid(DIM, 0, 64, 0, 1, 64, 1);
    private static final ContainerRef CHEST = new ContainerRef(DIM, 3, 64, 0);
    private static final ObservationRef OBS = new ObservationRef(new UUID(0, 8), 1, DIM);
    private static final String SOURCE = "source:" + UUID.nameUUIDFromBytes(
            AREA.toString().getBytes(StandardCharsets.UTF_8));

    private static ValidatedRequest request(TrustedContext owner) {
        return new ValidatedRequest(new CapabilityRequest(CropDelivery.ID, Map.of(
                "actor", new ActorValue(ACTOR), "amount", new IntValue(2),
                "source", new AreaValue(AREA), "destination", new ContainerValue(CHEST))), owner, OBS);
    }
    private static ObservationSnapshot observed(ObservationStatus status, long tick) {
        return new ObservationSnapshot(OBS, status, SOURCE, tick, 4,
                Map.of("mature_wheat", status == ObservationStatus.PRESENT ? 2L : 0L,
                        "unknown_cells", 0L));
    }
    private static ArtifactRef ref(char hex) {
        return new ArtifactRef(CropDelivery.ID, Character.toString(hex).repeat(64));
    }
    private static ArtifactDescriptor descriptor(ArtifactRef ref, Set<Effect> effects) {
        return new ArtifactDescriptor(ref, Contracts.IR_VERSION, CropDelivery.SPEC.parameters(),
                Type.INT, effects, List.of(), List.of());
    }

    private static final class Catalog implements CandidateSource {
        final TreeMap<String, Candidate> entries = new TreeMap<>();
        final Map<ArtifactRef, ArtifactDescriptor> methods = new HashMap<>();
        long revision = 2;
        boolean unavailable, truncated, stale, badCursor;
        int pages, descriptors;
        void add(char sha, AdmissionStatus admission, CompatibilityStatus compatibility,
                 Integrity integrity, Reason reason, Set<Effect> effects) {
            ArtifactRef ref = ref(sha);
            List<Reason> reasons = compatibility == CompatibilityStatus.COMPATIBLE
                    ? List.of() : List.of(reason);
            entries.put(ref.sha256(), new Candidate(ref, admission,
                    new Compatibility(ref, compatibility, reasons, "minecraft-26.3"), integrity));
            methods.put(ref, ResolverJUnitTest.descriptor(ref, effects));
        }
        void admitted(char sha, Set<Effect> effects) {
            add(sha, AdmissionStatus.ADMITTED, CompatibilityStatus.COMPATIBLE,
                    Integrity.VERIFIED, null, effects);
        }
        @Override public Page page(CapabilityId capability, String afterSha256, int pageSize) {
            pages++;
            if (unavailable) throw new IllegalStateException("index offline");
            List<Candidate> candidates = entries.tailMap(afterSha256, false).values().stream()
                    .limit(pageSize).toList();
            boolean complete = entries.tailMap(afterSha256, false).size() <= candidates.size();
            if (truncated) complete = false;
            String cursor = complete ? "" : candidates.isEmpty() ? "unavailable"
                    : candidates.getLast().ref().sha256();
            if (badCursor && !complete) cursor = "bad";
            return new Page(candidates, cursor, complete, revision);
        }
        @Override public Optional<ArtifactDescriptor> descriptor(ArtifactRef ref) {
            descriptors++;
            return Optional.ofNullable(methods.get(ref));
        }
        @Override public long revision() { return revision + (stale ? 1 : 0); }
    }

    private static final class Fixture {
        final Catalog catalog = new Catalog();
        boolean owns = true, knows = true;
        Set<Effect> deniedEffects = Set.of();
        long tick = 100;
        int controls, eligibility, authorities, prerequisites, modelCalls, worldEffects;
        final Map<ArtifactRef, Reason> missing = new HashMap<>();
        CandidateSource primary = catalog, fallback;
        SupportPolicy support = CapabilityResolver.cropDeliverySupport();
        PrimitiveCatalog primitives = GatewayPrimitives.instance();
        Limits limits = Limits.defaults();
        Engine engine() {
            return new Engine(id -> id.equals(CropDelivery.ID) ? Optional.of(CropDelivery.SPEC)
                    : Optional.empty(), primitives, primary, fallback, support,
                    (actor, context) -> { controls++; return owns && context.equals(OWNER); },
                    (actor, ref, context) -> { eligibility++; return knows; },
                    (actor, effect, context) -> { authorities++; return !deniedEffects.contains(effect); },
                    (request, method, snapshot) -> {
                        prerequisites++;
                        return missing.get(method.ref());
                    }, () -> tick, limits);
        }
        Decision resolve() { return engine().resolve(request(OWNER), observed(ObservationStatus.PRESENT, tick)); }
        void pure() { assertEquals(0, modelCalls); assertEquals(0, worldEffects); }
    }

    @Test void sevenDecisionsAndNoModel() {
        for (ResolutionStatus expected : ResolutionStatus.values()) {
            Fixture fixture = new Fixture();
            ObservationSnapshot observation = observed(ObservationStatus.PRESENT, fixture.tick);
            switch (expected) {
                case RESOLVED -> fixture.catalog.admitted('a', Set.of(Effect.HARVEST));
                case BLOCKED -> observation = observed(ObservationStatus.ABSENT, fixture.tick);
                case UNSUPPORTED_RUNTIME -> fixture.primitives = (id, version) -> Optional.empty();
                case MISSING_IMPLEMENTATION -> { }
                case INCOMPATIBLE -> fixture.catalog.add('a', AdmissionStatus.ADMITTED,
                        CompatibilityStatus.INCOMPATIBLE, Integrity.VERIFIED,
                        Reason.DEPENDENCY_INCOMPATIBLE, Set.of(Effect.HARVEST));
                case UNAUTHORIZED -> fixture.owns = false;
                case NEEDS_PLANNING -> fixture.support = spec -> new Envelope(Domain.BROAD_GOAL,
                        CropDelivery.SPEC.effects());
            }
            Decision result = fixture.engine().resolve(request(OWNER), observation);
            assertEquals(expected, result.routing().status());
            assertEquals(expected == ResolutionStatus.RESOLVED, result.selectedBindings() != null);
            if (expected == ResolutionStatus.MISSING_IMPLEMENTATION
                    || expected == ResolutionStatus.NEEDS_PLANNING) assertTrue(result.complete());
            fixture.pure();
        }
    }

    @Test void alternativesAndMixedPrecedence() {
        Fixture fixture = new Fixture();
        fixture.catalog.admitted('a', Set.of(Effect.HARVEST));
        fixture.catalog.admitted('b', Set.of(Effect.MOVE));
        fixture.catalog.admitted('c', Set.of());
        fixture.deniedEffects = Set.of(Effect.MOVE);
        fixture.missing.put(ref('a'), Reason.FACILITY_MISSING);
        Decision result = fixture.resolve();
        assertEquals(ResolutionStatus.RESOLVED, result.routing().status());
        assertEquals(ref('c'), result.routing().artifact());
        assertEquals(request(OWNER), result.selectedBindings());
        assertEquals(3, result.candidates().size());
        assertEquals(List.of(Reason.FACILITY_MISSING), result.candidates().get(0).reasons());
        assertEquals(List.of(Reason.AUTHORITY_DENIED), result.candidates().get(1).reasons());
        fixture.catalog.entries.remove(ref('c').sha256());
        assertRoute(fixture.resolve(), ResolutionStatus.BLOCKED, Reason.FACILITY_MISSING);
        fixture.catalog.entries.remove(ref('a').sha256());
        fixture.catalog.add('d', AdmissionStatus.ADMITTED, CompatibilityStatus.INCOMPATIBLE,
                Integrity.VERIFIED, Reason.DEPENDENCY_INCOMPATIBLE, Set.of());
        fixture.catalog.add('e', AdmissionStatus.QUARANTINED, CompatibilityStatus.INCOMPATIBLE,
                Integrity.VERIFIED, Reason.ARTIFACT_QUARANTINED, Set.of());
        result = fixture.resolve();
        assertRoute(result, ResolutionStatus.UNAUTHORIZED, Reason.AUTHORITY_DENIED);
        assertEquals(3, result.candidates().size());
        fixture.catalog.entries.remove(ref('b').sha256());
        assertRoute(fixture.resolve(), ResolutionStatus.INCOMPATIBLE, Reason.DEPENDENCY_INCOMPATIBLE);
        fixture.catalog.entries.remove(ref('d').sha256());
        assertRoute(fixture.resolve(), ResolutionStatus.BLOCKED, Reason.ARTIFACT_QUARANTINED);
        fixture.pure();
    }

    @Test void runtimeKnowledgeAndAuthorityStayDistinct() {
        Fixture fixture = new Fixture();
        fixture.catalog.admitted('a', Set.of(Effect.MOVE));
        fixture.knows = false;
        assertRoute(fixture.resolve(), ResolutionStatus.BLOCKED, Reason.KNOWLEDGE_REQUIRED);
        fixture.knows = true;
        fixture.deniedEffects = Set.of(Effect.MOVE);
        assertRoute(fixture.resolve(), ResolutionStatus.UNAUTHORIZED, Reason.AUTHORITY_DENIED);
        assertTrue(fixture.eligibility > 0 && fixture.authorities > 0);
        int before = fixture.catalog.pages;
        fixture.deniedEffects = Set.of(Effect.HARVEST);
        assertRoute(fixture.resolve(), ResolutionStatus.UNAUTHORIZED, Reason.AUTHORITY_DENIED);
        assertEquals(before, fixture.catalog.pages, "unavoidable effect denial precedes catalog");
        fixture.owns = false;
        assertRoute(fixture.resolve(), ResolutionStatus.UNAUTHORIZED, Reason.AUTHORITY_DENIED);
        assertEquals(before, fixture.catalog.pages, "request-wide denial precedes catalog queries");
        assertRoute(fixture.engine().resolve(request(FOREIGN), observed(ObservationStatus.PRESENT, 100)),
                ResolutionStatus.UNAUTHORIZED, Reason.AUTHORITY_DENIED);
        fixture.pure();
    }

    @Test void mandatorySupportRequiresSignaturesAndMethodSpecificFailureDoesNot() {
        Fixture fixture = new Fixture();
        fixture.primitives = (id, version) -> id.equals(GatewayPrimitives.Operation.HARVEST_WHEAT
                .signature().id()) || id.equals(GatewayPrimitives.Operation.HARVEST_NEXT_WHEAT
                .signature().id()) ? Optional.empty() : GatewayPrimitives.instance().find(id, version);
        Decision unsupported = fixture.resolve();
        assertRoute(unsupported, ResolutionStatus.UNSUPPORTED_RUNTIME, Reason.UNSUPPORTED_PRIMITIVE);
        assertEquals(Effect.HARVEST, unsupported.missingRuntimeEffect());
        fixture.primitives = GatewayPrimitives.instance();
        fixture.catalog.add('a', AdmissionStatus.ADMITTED, CompatibilityStatus.INCOMPATIBLE,
                Integrity.VERIFIED, Reason.DEPENDENCY_INCOMPATIBLE, Set.of());
        fixture.catalog.admitted('b', Set.of(Effect.HARVEST));
        assertEquals(ref('b'), fixture.resolve().routing().artifact());
        fixture.pure();
    }

    @Test void onlyCompleteSupportedGapMayResearch() {
        Fixture fixture = new Fixture();
        assertRoute(fixture.resolve(), ResolutionStatus.MISSING_IMPLEMENTATION, null);
        fixture.catalog.add('a', AdmissionStatus.CANDIDATE, CompatibilityStatus.INCOMPATIBLE,
                Integrity.VERIFIED, Reason.ARTIFACT_INVALID, Set.of());
        Decision temporary = fixture.resolve();
        assertRoute(temporary, ResolutionStatus.MISSING_IMPLEMENTATION, null);
        assertEquals(CandidateState.TEMPORARY, temporary.candidates().getFirst().state());
        ValidatedRequest unknown = new ValidatedRequest(new CapabilityRequest(
                new CapabilityId("unknown:capability", 1), request(OWNER).request().arguments()), OWNER, OBS);
        assertThrows(IllegalArgumentException.class,
                () -> fixture.engine().resolve(unknown, observed(ObservationStatus.PRESENT, 100)));
        ValidatedRequest unbound = new ValidatedRequest(new CapabilityRequest(CropDelivery.ID,
                Map.of("actor", new ActorValue(ACTOR))), OWNER, OBS);
        assertThrows(IllegalArgumentException.class,
                () -> fixture.engine().resolve(unbound, observed(ObservationStatus.PRESENT, 100)));
        ValidatedRequest overflow = new ValidatedRequest(new CapabilityRequest(CropDelivery.ID,
                Map.of("actor", new ActorValue(ACTOR), "amount", new IntValue(Long.MAX_VALUE),
                        "source", new AreaValue(AREA), "destination", new ContainerValue(CHEST))),
                OWNER, OBS);
        assertThrows(IllegalArgumentException.class,
                () -> fixture.engine().resolve(overflow, observed(ObservationStatus.PRESENT, 100)));
        fixture.pure();
    }

    @Test void truncatedRetrievalUsesBoundedExactFallback() {
        Fixture fixture = new Fixture();
        Catalog index = new Catalog();
        index.admitted('a', Set.of(Effect.HARVEST));
        index.truncated = true;
        fixture.primary = index;
        fixture.catalog.admitted('a', Set.of(Effect.HARVEST));
        fixture.catalog.admitted('b', Set.of(Effect.TRANSFER));
        fixture.missing.put(ref('a'), Reason.RESOURCE_MISSING);
        fixture.fallback = fixture.catalog;
        fixture.limits = new Limits(8, 1, 6, 8_192, 40);
        Decision decision = fixture.resolve();
        assertEquals(ref('b'), decision.routing().artifact());
        assertTrue(decision.complete() && decision.fallbackUsed());
        assertEquals(2, index.pages);
        assertEquals(2, fixture.catalog.pages);
        index.truncated = false;
        index.revision = 1;
        int exactBefore = fixture.catalog.pages;
        Decision staleIndex = fixture.resolve();
        assertEquals(ref('b'), staleIndex.routing().artifact());
        assertTrue(staleIndex.fallbackUsed());
        assertTrue(fixture.catalog.pages > exactBefore);
        index.truncated = true;
        fixture.catalog.unavailable = true;
        assertRoute(fixture.resolve(), ResolutionStatus.BLOCKED, Reason.STORAGE_UNAVAILABLE);
        fixture.fallback = null;
        fixture.limits = new Limits(1, 1, 1, 8_192, 40);
        assertRoute(fixture.resolve(), ResolutionStatus.BLOCKED, Reason.BUDGET_EXHAUSTED);
        fixture.pure();
    }

    @Test void quarantineAndHistoricalCompatibilityArePreserved() {
        Fixture fixture = new Fixture();
        fixture.catalog.add('a', AdmissionStatus.QUARANTINED, CompatibilityStatus.INCOMPATIBLE,
                Integrity.VERIFIED, Reason.ARTIFACT_QUARANTINED, Set.of());
        assertRoute(fixture.resolve(), ResolutionStatus.BLOCKED, Reason.ARTIFACT_QUARANTINED);
        fixture.catalog.add('b', AdmissionStatus.ADMITTED, CompatibilityStatus.INCOMPATIBLE,
                Integrity.VERIFIED, Reason.ARTIFACT_INCOMPATIBLE, Set.of());
        Decision result = fixture.resolve();
        assertRoute(result, ResolutionStatus.INCOMPATIBLE, Reason.ARTIFACT_INCOMPATIBLE);
        assertEquals(Reason.ARTIFACT_QUARANTINED, result.candidates().getFirst().reasons().getFirst());
        fixture.pure();
    }

    @Test void freshnessAndUnknownNeverProveAbsence() {
        Fixture fixture = new Fixture();
        for (ObservationSnapshot snapshot : List.of(observed(ObservationStatus.UNKNOWN, 100),
                observed(ObservationStatus.PRESENT, 59),
                new ObservationSnapshot(new ObservationRef(new UUID(0, 99), 1, DIM),
                        ObservationStatus.PRESENT, SOURCE, 100, 4, Map.of()),
                new ObservationSnapshot(OBS, ObservationStatus.PRESENT, "other-source", 100, 4,
                        Map.of("mature_wheat", 0L)))) {
            assertRoute(fixture.engine().resolve(request(OWNER), snapshot),
                    ResolutionStatus.BLOCKED, Reason.STALE_OBSERVATION);
        }
        assertEquals(0, fixture.catalog.pages);
        fixture.tick = Long.MAX_VALUE;
        assertRoute(fixture.engine().resolve(request(OWNER), observed(ObservationStatus.PRESENT, 100)),
                ResolutionStatus.BLOCKED, Reason.STALE_OBSERVATION);
        fixture.pure();
    }

    @Test void limitsTieAndMalformedCatalog() {
        assertThrows(IllegalArgumentException.class, () -> new Limits(0, 1, 1, 256, 0));
        assertThrows(IllegalArgumentException.class, () -> new Limits(65, 1, 1, 256, 0));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 0, 1, 256, 0));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, 0, 256, 0));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, 1, 255, 0));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, 1, 256, Long.MAX_VALUE));
        Fixture fixture = new Fixture();
        fixture.catalog.admitted('b', Set.of());
        fixture.catalog.admitted('a', Set.of());
        assertEquals(ref('a'), fixture.resolve().routing().artifact(), "stable tie independent of insertion");
        fixture.limits = new Limits(1, 1, 1, 256, 0);
        Decision capped = fixture.resolve();
        assertRoute(capped, ResolutionStatus.BLOCKED, Reason.BUDGET_EXHAUSTED);
        assertEquals(1, capped.queriedPages());
        assertEquals(1, capped.candidateWork());
        fixture.limits = new Limits(2, 1, 2, 512, 0);
        assertEquals(ref('a'), fixture.resolve().routing().artifact());
        fixture.limits = new Limits(2, 2, 1, 256, 0);
        assertRoute(fixture.resolve(), ResolutionStatus.BLOCKED, Reason.BUDGET_EXHAUSTED);
        fixture.limits = new Limits(2, 1, 2, 512, 0);
        fixture.catalog.stale = true;
        assertRoute(fixture.resolve(), ResolutionStatus.BLOCKED, Reason.STALE_OBSERVATION);
        fixture.catalog.stale = false;
        fixture.catalog.badCursor = true;
        assertRoute(fixture.resolve(), ResolutionStatus.BLOCKED, Reason.STORAGE_UNAVAILABLE);
        fixture.pure();
    }

    @Test void realRepositoryAndGatewaySignaturesWithoutEffects(@TempDir Path world) throws Exception {
        RuntimeSnapshot runtime = new RuntimeSnapshot("minecraft-26.3",
                id -> id.equals(CropDelivery.ID) ? Optional.of(CropDelivery.SPEC) : Optional.empty(),
                GatewayPrimitives.instance());
        try (VersionedSkillRepository repository = VersionedSkillRepository.open(world, runtime,
                VersionedSkillRepository.Limits.defaults(), decision -> true,
                TrustedContext::equals, FaultInjector.none())) {
            String program = StrictJson.canonical(Map.of("schema", 1L,
                    "capability", CropDelivery.ID.name(), "capabilityVersion", 1L,
                    "dependencies", List.of(), "body", List.of(Map.of("op", "result",
                            "value", Map.of("param", "amount")))));
            SkillCompiler.CompileResult compiled = new SkillCompiler(runtime.capabilities(),
                    runtime.primitives(), repository).compile(program);
            assertInstanceOf(SkillCompiler.Success.class, compiled);
            SkillArtifact artifact = ((SkillCompiler.Success) compiled).skill().artifact();
            EvidenceRef evidence = new EvidenceRef("fixture", "validator-1", "test-only");
            EvidenceBundle bundle = new EvidenceBundle(evidence, evidence, evidence, "c".repeat(64));
            AdmissionDecision admission = new AdmissionDecision(new UUID(0, 19), OWNER, bundle);
            Provenance provenance = new Provenance(1, null, "compiler-1", "minecraft-26.3",
                    OWNER.scope().worldId(), List.of(), List.of(evidence));
            assertEquals(PublishStatus.ADMITTED,
                    repository.publish(artifact, admission, provenance).status());
            long revision = repository.status().revision();
            long bytes = repository.status().accountedBytes();
            Set<TrustedContext> grants = new HashSet<>(Set.of(OWNER));
            ControlPolicy enrollment = (actor, context) -> actor.equals(ACTOR)
                    && grants.contains(context);
            Engine engine = new Engine(runtime.capabilities(), GatewayPrimitives.instance(),
                    CapabilityResolver.exactCatalog(repository), null,
                    CapabilityResolver.cropDeliverySupport(), enrollment,
                    CapabilityResolver.enrolledWorldSkills(enrollment),
                    (actor, effect, context) -> grants.contains(context),
                    CapabilityResolver.cropPrerequisites(), () -> 100,
                    Limits.defaults());
            Decision selected = engine.resolve(request(OWNER), observed(ObservationStatus.PRESENT, 100));
            assertEquals(ResolutionStatus.RESOLVED, selected.routing().status());
            assertEquals(artifact.descriptor().ref(), selected.routing().artifact());
            assertEquals(revision, selected.catalogRevision());
            assertEquals(CandidateState.USABLE, selected.candidates().getFirst().state());
            assertRoute(engine.resolve(request(OWNER), observed(ObservationStatus.ABSENT, 100)),
                    ResolutionStatus.BLOCKED, Reason.RESOURCE_MISSING);
            assertRoute(engine.resolve(request(FOREIGN), observed(ObservationStatus.PRESENT, 100)),
                    ResolutionStatus.UNAUTHORIZED, Reason.AUTHORITY_DENIED);
            grants.add(FOREIGN);
            assertEquals(artifact.descriptor().ref(), engine.resolve(request(FOREIGN),
                    observed(ObservationStatus.PRESENT, 100)).routing().artifact());
            assertTrue(repository.privateOrigins(artifact.descriptor().ref(), FOREIGN).isEmpty(),
                    "shared body did not expose another principal's evidence");
            grants.remove(FOREIGN);
            assertRoute(engine.resolve(request(FOREIGN), observed(ObservationStatus.PRESENT, 100)),
                    ResolutionStatus.UNAUTHORIZED, Reason.AUTHORITY_DENIED);
            assertEquals(revision, repository.status().revision(), "resolve cannot publish");
            assertEquals(bytes, repository.status().accountedBytes(), "resolve cannot persist");
            repository.updateRuntime(new RuntimeSnapshot("minecraft-changed",
                    runtime.capabilities(), runtime.primitives()));
            assertRoute(engine.resolve(request(OWNER), observed(ObservationStatus.PRESENT, 100)),
                    ResolutionStatus.INCOMPATIBLE, Reason.ARTIFACT_INCOMPATIBLE);
            assertEquals(AdmissionStatus.ADMITTED,
                    repository.resolve(artifact.descriptor().ref()).admission().status());
            assertEquals(revision, repository.status().revision(), "compatibility did not alter history");
        }
    }

    @Test void unreadableRepositoryCannotProveMissingImplementation(@TempDir Path world) throws Exception {
        Path root = world.resolve(VersionedSkillRepository.WORLD_RELATIVE_PATH);
        Files.createDirectories(root);
        Path manifest = root.resolve("manifest.json");
        byte[] future = "{\"schema\":2,\"newWorldRights\":\"preserve\"}".getBytes(StandardCharsets.UTF_8);
        Files.write(manifest, future);
        RuntimeSnapshot runtime = new RuntimeSnapshot("minecraft-26.3",
                id -> id.equals(CropDelivery.ID) ? Optional.of(CropDelivery.SPEC) : Optional.empty(),
                GatewayPrimitives.instance());
        try (VersionedSkillRepository repository = VersionedSkillRepository.open(world, runtime,
                VersionedSkillRepository.Limits.defaults(), decision -> true,
                TrustedContext::equals, FaultInjector.none())) {
            assertTrue(repository.status().readOnly());
            long revision = repository.status().revision();
            long bytes = repository.status().accountedBytes();
            ControlPolicy enrollment = (actor, context) -> actor.equals(ACTOR) && context.equals(OWNER);
            Engine engine = new Engine(runtime.capabilities(), runtime.primitives(),
                    CapabilityResolver.exactCatalog(repository), null,
                    CapabilityResolver.cropDeliverySupport(), enrollment,
                    CapabilityResolver.enrolledWorldSkills(enrollment),
                    (actor, effect, context) -> context.equals(OWNER),
                    CapabilityResolver.cropPrerequisites(), () -> 100, Limits.defaults());
            Decision decision = engine.resolve(request(OWNER), observed(ObservationStatus.PRESENT, 100));
            assertRoute(decision, ResolutionStatus.BLOCKED, Reason.STORAGE_UNAVAILABLE);
            assertEquals(1, decision.queriedPages());
            assertEquals(0, decision.candidateWork());
            assertEquals(revision, repository.status().revision());
            assertEquals(bytes, repository.status().accountedBytes());
            assertArrayEquals(future, Files.readAllBytes(manifest));
        }
    }

    private static void assertRoute(Decision decision, ResolutionStatus status, Reason reason) {
        assertEquals(status, decision.routing().status());
        assertEquals(reason, decision.routing().reason());
        assertNull(decision.routing().artifact());
    }
}
