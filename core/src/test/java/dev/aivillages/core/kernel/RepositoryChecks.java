package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.VersionedSkillRepository.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Filesystem acceptance checks also run without a JUnit runtime or Minecraft classes. */
public final class RepositoryChecks {
    private RepositoryChecks() { }
    private static final CapabilityId ID = new CapabilityId("cognitivecraft:repository_fixture", 1);
    private static final CapabilitySpec SPEC = new CapabilitySpec(ID,
            List.of(new Parameter("amount", Type.INT, 0, 64)), Type.INT,
            Set.of(), Completion.ATTRIBUTABLE_CROP_DELIVERY);
    private static final String A = "a".repeat(64), B = "b".repeat(64), C = "c".repeat(64);
    private static final String GAME = "minecraft-26.3";
    private static final EvidenceRef STATIC = new EvidenceRef("compile-1", "compiler-1", "fixture");
    private static final EvidenceRef FIXTURE = new EvidenceRef("fixture-1", "fixture-1", "fixture");
    private static final EvidenceRef TRIAL = new EvidenceRef("trial-1", "runtime-1", "world");
    private static final EvidenceBundle EVIDENCE = new EvidenceBundle(STATIC, FIXTURE, TRIAL, C);
    private static final TrustedContext OWNER_A = new TrustedContext(
            new PrincipalRef(new UUID(0, 1)), new ScopeRef(new UUID(0, 2), new UUID(0, 3)));
    private static final TrustedContext OWNER_B = new TrustedContext(
            new PrincipalRef(new UUID(0, 4)), new ScopeRef(new UUID(0, 2), new UUID(0, 5)));

    public static void main(String[] args) throws Exception {
        saveReload();
        dedupAndPrivacy();
        admissionAuthority();
        publicationFaults();
        corruptionAndUnknownSchema();
        compatibility();
        quotas();
        concurrentPublication();
        rootsAndCacheOutage();
        System.out.println("IMP-004 repository checks passed: 9 matrix groups");
        Path measured = Files.createTempDirectory("cc-repo-measured");
        try (VersionedSkillRepository store = store(measured, runtime())) {
            SkillArtifact fixture = simple();
            assertStatus(PublishStatus.ADMITTED, store.publish(fixture, decision(29, OWNER_A),
                    provenance(fixture, "model-a")));
            System.out.println("fixture bytes: body="
                    + Files.size(location(measured).resolve("bodies/"
                        + fixture.descriptor().ref().sha256() + ".json"))
                    + " manifest=" + Files.size(location(measured).resolve("manifest.json"))
                    + " accounted=" + store.status().accountedBytes()
                    + " artifacts=" + store.status().bodyCount()
                    + " orphans=" + store.status().orphanBodies());
        } finally { erase(measured); }
    }

    private static RuntimeSnapshot runtime() {
        return new RuntimeSnapshot(GAME, id -> id.equals(ID) ? Optional.of(SPEC) : Optional.empty(),
                (id, version) -> Optional.empty());
    }

    private static SkillArtifact compile(Object body) {
        return compile(body, List.of(), ref -> Optional.empty(), runtime());
    }

    private static SkillArtifact compile(Object body, List<ArtifactRef> dependencies,
                                         ArtifactCatalog artifacts, RuntimeSnapshot runtime) {
        Map<String, Object> root = Map.of("schema", 1L, "capability", ID.name(),
                "capabilityVersion", 1L,
                "dependencies", dependencies.stream().map(RepositoryCodec::ref).toList(),
                "body", body);
        SkillCompiler.CompileResult result = new SkillCompiler(runtime.capabilities(),
                runtime.primitives(), artifacts).compile(StrictJson.canonical(root));
        if (!(result instanceof SkillCompiler.Success success))
            throw new AssertionError("fixture did not compile: " + result);
        return success.skill().artifact();
    }

    private static SkillArtifact simple() {
        return compile(List.of(Map.of("op", "result", "value", Map.of("param", "amount"))));
    }

    private static SkillArtifact literal(int value) {
        return compile(List.of(Map.of("op", "result", "value", Map.of("int", (long) value))));
    }

    private static SkillArtifact calling(SkillArtifact child, ArtifactCatalog catalog) {
        return compile(List.of(
                Map.of("op", "call", "kind", "skill", "ref", RepositoryCodec.ref(
                        child.descriptor().ref()), "args", Map.of("amount", Map.of("param", "amount")),
                        "into", "selected"),
                Map.of("op", "result", "value", Map.of("local", "selected"))),
                List.of(child.descriptor().ref()), catalog, runtime());
    }

    private static AdmissionDecision decision(int n, TrustedContext owner) {
        return new AdmissionDecision(new UUID(0, n), owner, EVIDENCE);
    }

    private static Provenance provenance(SkillArtifact artifact, String model) {
        return new Provenance(1, model, "compiler-1", GAME, OWNER_A.scope().worldId(),
                artifact.descriptor().dependencies(), List.of(STATIC, FIXTURE, TRIAL));
    }

    private static VersionedSkillRepository store(Path world, RuntimeSnapshot runtime,
                                                  Limits limits, AdmissionAuthority guard,
                                                  VisibilityPolicy policy,
                                                  FaultInjector faults) throws IOException {
        return VersionedSkillRepository.open(world, runtime, limits, guard, policy, faults);
    }

    private static VersionedSkillRepository store(Path world, RuntimeSnapshot runtime) throws IOException {
        return store(world, runtime, Limits.defaults(), decision -> true,
                TrustedContext::equals, FaultInjector.none());
    }

    public static void saveReload() throws Exception {
        Path world = Files.createTempDirectory("cc-repo-reload");
        Path legacy = world.resolve("data/ai-villages/state.json");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "{\"legacy\":true}");
        SkillArtifact child = simple();
        ArtifactRef parentRef;
        try {
            try (VersionedSkillRepository store = store(world, runtime())) {
                assertStatus(PublishStatus.ADMITTED, store.publish(child, decision(1, OWNER_A),
                        provenance(child, "model-a")));
                SkillArtifact parent = calling(child, store);
                parentRef = parent.descriptor().ref();
                assertStatus(PublishStatus.ADMITTED, store.publish(parent, decision(2, OWNER_A),
                        provenance(parent, "model-a")));
                check(store.resolve(parentRef).usable(), "parent currently usable");
                check(store.page(ID, "", 1).candidates().size() == 1, "bounded page");
                Page first = store.page(ID, "", 1);
                check(!first.complete() && !first.nextCursor().isEmpty(), "paging completeness");
                Page second = store.page(ID, first.nextCursor(), 1);
                check(second.complete() && second.candidates().size() == 1
                        && second.revision() == first.revision(), "end of exact catalog");
                illegal(() -> store.page(ID, "", 0));
                illegal(() -> store.page(ID, "", Limits.defaults().maxPageSize() + 1));
                check(store.dependencyClosure(parentRef).equals(
                        Set.of(parentRef, child.descriptor().ref())), "pinned closure");
            }
            Files.createDirectories(world.resolve("cache"));
            Files.writeString(world.resolve("cache/disposable.index"), "garbage");
            Files.delete(world.resolve("cache/disposable.index"));
            try (VersionedSkillRepository reloaded = store(world, runtime())) {
                check(reloaded.status().revision() == 2, "revision survived restart");
                check(reloaded.resolve(parentRef).usable(), "model-free parent after cache deletion");
                check(reloaded.find(child.descriptor().ref()).isPresent(), "exact child after restart");
                check(reloaded.privateOrigins(parentRef, OWNER_A).size() == 1, "evidence restored");
            }
            check(Files.readString(legacy).equals("{\"legacy\":true}"), "legacy data preserved");
        } finally { erase(world); }
    }

    public static void dedupAndPrivacy() throws Exception {
        Path world = Files.createTempDirectory("cc-repo-dedup");
        SkillArtifact artifact = simple();
        Set<String> grants = new HashSet<>();
        VisibilityPolicy policy = (viewer, owner) -> viewer.equals(owner)
                || grants.contains(viewer.principal().id() + ":" + owner.principal().id());
        try (VersionedSkillRepository store = store(world, runtime(), Limits.defaults(),
                decision -> true, policy, FaultInjector.none())) {
            assertStatus(PublishStatus.ADMITTED, store.publish(artifact, decision(3, OWNER_A),
                    provenance(artifact, "private-model-a")));
            long firstBytes = store.status().accountedBytes();
            assertStatus(PublishStatus.ADMITTED, store.publish(
                    artifact.withMetadata(new ArtifactMetadata("model-b", "trial-b", 80)),
                    decision(4, OWNER_B), provenance(artifact, "private-model-b")));
            check(store.status().bodyCount() == 1 && store.status().accountedBytes() > firstBytes,
                    "one body, independent metadata");
            check(store.privateOrigins(artifact.descriptor().ref(), OWNER_A).size() == 1,
                    "A sees only A metadata");
            check(store.privateOrigins(artifact.descriptor().ref(), OWNER_B).size() == 1
                    && store.privateOrigins(artifact.descriptor().ref(), OWNER_B).get(0)
                    .provenance().modelDescriptor().equals("private-model-b"),
                    "B does not retrieve A model/trial context");
            grants.add(OWNER_B.principal().id() + ":" + OWNER_A.principal().id());
            check(store.privateOrigins(artifact.descriptor().ref(), OWNER_B).size() == 2,
                    "explicit grant of metadata");
            grants.clear();
            check(store.privateOrigins(artifact.descriptor().ref(), OWNER_B).size() == 1,
                    "revocation takes immediate effect");
            SkillArtifact changed = literal(1);
            check(!artifact.descriptor().ref().equals(changed.descriptor().ref()),
                    "semantic change changes immutable identity");
            assertStatus(PublishStatus.ADMITTED, store.publish(changed, decision(5, OWNER_A),
                    provenance(changed, "model-a")));
            check(store.status().bodyCount() == 2, "new body for changed semantics");
        } finally { erase(world); }
    }

    public static void admissionAuthority() throws Exception {
        Path world = Files.createTempDirectory("cc-repo-authority");
        SkillArtifact artifact = simple();
        AdmissionDecision permit = decision(6, OWNER_A);
        AdmissionAuthority guard = new AdmissionAuthority() {
            @Override public boolean authorizes(AdmissionDecision decision) {
                return decision.decisionId().equals(permit.decisionId());
            }
            @Override public boolean authorizes(QuarantineDecision decision) {
                return decision.owner().equals(OWNER_A);
            }
        };
        try (VersionedSkillRepository store = store(world, runtime(), Limits.defaults(), guard,
                TrustedContext::equals, FaultInjector.none())) {
            assertStatus(PublishStatus.UNAUTHORIZED, store.publish(artifact,
                    decision(7, OWNER_A), provenance(artifact, "model-a")));
            assertStatus(PublishStatus.UNAUTHORIZED, store.publish(artifact, null,
                    provenance(artifact, "model-a")));
            assertStatus(PublishStatus.EVIDENCE_REQUIRED, store.publish(artifact, permit, null));
            assertStatus(PublishStatus.ARTIFACT_INVALID, store.publish(artifact, permit,
                    new Provenance(1, "model", "compiler", GAME, null, List.of(), List.of())));
            check(store.status().revision() == 0 && store.page(ID, "", 1).candidates().isEmpty(),
                    "failed promotion changed no authority");
            assertStatus(PublishStatus.ADMITTED, store.publish(artifact, permit,
                    provenance(artifact, "model-a")));
            assertStatus(PublishStatus.ARTIFACT_INVALID, store.publish(artifact, permit,
                    provenance(artifact, "model-a")));
            check(store.status().revision() == 1, "decision replay rejected");
            QuarantineDecision unauthorized = new QuarantineDecision(new UUID(0, 60),
                    OWNER_B, FIXTURE, dev.aivillages.core.kernel.Outcomes.Reason.ARTIFACT_INVALID);
            assertStatus(PublishStatus.UNAUTHORIZED,
                    store.quarantine(artifact.descriptor().ref(), unauthorized));
            QuarantineDecision authorized = new QuarantineDecision(new UUID(0, 61),
                    OWNER_A, FIXTURE, dev.aivillages.core.kernel.Outcomes.Reason.ARTIFACT_INVALID);
            assertStatus(PublishStatus.QUARANTINED,
                    store.quarantine(artifact.descriptor().ref(), authorized));
            ArtifactView view = store.resolve(artifact.descriptor().ref());
            check(!view.usable() && view.admission().evidence().equals(TRIAL)
                    && view.admission().status() == AdmissionStatus.QUARANTINED,
                    "behavioral quarantine retains admission evidence");
            check(view.compatibility().reasons().contains(
                    dev.aivillages.core.kernel.Outcomes.Reason.ARTIFACT_QUARANTINED),
                    "quarantine has its own reason");
            check(store.quarantineEvents(artifact.descriptor().ref(), OWNER_B).isEmpty()
                    && store.quarantineEvents(artifact.descriptor().ref(), OWNER_A).size() == 1,
                    "private quarantine evidence is scope-filtered");
            assertStatus(PublishStatus.ARTIFACT_INVALID,
                    store.quarantine(artifact.descriptor().ref(), authorized));
        }
        try (VersionedSkillRepository store = store(world, runtime())) {
            check(store.resolve(artifact.descriptor().ref()).admission().status()
                    == AdmissionStatus.QUARANTINED
                    && store.quarantineEvents(artifact.descriptor().ref(), OWNER_A).size() == 1,
                    "controller quarantine survives reload");
        } finally { erase(world); }
    }

    public static void publicationFaults() throws Exception {
        for (FaultPoint point : FaultPoint.values()) {
            Path world = Files.createTempDirectory("cc-repo-fault");
            AtomicReference<FaultPoint> injected = new AtomicReference<>(point);
            SkillArtifact first = simple();
            SkillArtifact second = literal(3);
            try {
                try (VersionedSkillRepository store = store(world, runtime(), Limits.defaults(),
                        decision -> true, TrustedContext::equals,
                        step -> { if (injected.compareAndSet(step, null))
                            throw new IOException("Injected " + step); })) {
                    // First publication should not be affected by the selected fault.
                    injected.set(null);
                    assertStatus(PublishStatus.ADMITTED, store.publish(first, decision(10, OWNER_A),
                            provenance(first, "model-a")));
                    injected.set(point);
                    PublishResult attempt = store.publish(second, decision(11, OWNER_A),
                            provenance(second, "model-b"));
                    assertStatus(PublishStatus.STORAGE_UNAVAILABLE, attempt);
                    check(attempt.commitMayHaveSucceeded()
                            == (point == FaultPoint.AFTER_MANIFEST_REPLACE),
                            "post-commit uncertainty flag " + point);
                }
                try (VersionedSkillRepository reopened = store(world, runtime())) {
                    check(reopened.resolve(first.descriptor().ref()).usable(),
                            "prior commit always intact " + point);
                    boolean secondPresent = reopened.resolve(second.descriptor().ref()) != null;
                    check(secondPresent == (point == FaultPoint.AFTER_MANIFEST_REPLACE),
                            "old or new complete commit " + point);
                    check(reopened.status().accountedBytes() <= Limits.defaults().maxTotalBytes(),
                            "fault storage accounted");
                    if (point == FaultPoint.AFTER_BODY_WRITE
                            || point == FaultPoint.AFTER_EVIDENCE_STAGE
                            || point == FaultPoint.BEFORE_MANIFEST_REPLACE)
                        check(reopened.status().orphanBodies() == 1,
                                "orphan body counted " + point);
                }
            } finally { erase(world); }
        }
    }

    public static void corruptionAndUnknownSchema() throws Exception {
        Path world = Files.createTempDirectory("cc-repo-corruption");
        SkillArtifact first = simple();
        SkillArtifact second = literal(4);
        try {
            try (VersionedSkillRepository store = store(world, runtime())) {
                assertStatus(PublishStatus.ADMITTED, store.publish(first, decision(12, OWNER_A),
                        provenance(first, "model-a")));
                assertStatus(PublishStatus.ADMITTED, store.publish(second, decision(13, OWNER_A),
                        provenance(second, "model-a")));
                Path modified = location(world).resolve("bodies/"
                        + second.descriptor().ref().sha256() + ".json");
                Files.writeString(modified, "corrupt body");
                assertStatus(PublishStatus.ARTIFACT_INVALID,
                        store.publish(second, decision(30, OWNER_B),
                                provenance(second, "model-b")));
                check(store.status().revision() == 2,
                        "tampered durable body cannot gain another admission");
            }
            Path location = location(world);
            Path body = location.resolve("bodies/" + second.descriptor().ref().sha256() + ".json");
            try (VersionedSkillRepository store = store(world, runtime())) {
                ArtifactView view = store.resolve(second.descriptor().ref());
                check(view.integrity() == Integrity.CORRUPT && !view.usable()
                        && view.admission().status() == AdmissionStatus.QUARANTINED,
                        "corruption quarantines independently of behavioral admission");
                check(view.admission().evidence().equals(TRIAL), "prior evidence preserved");
                check(store.quarantineEvents(second.descriptor().ref(), OWNER_B).get(0)
                        .source().equals("repository:integrity"), "corruption attributed to repository");
                check(store.resolve(first.descriptor().ref()).usable(), "unaffected body usable");
                long revision = store.status().revision();
                store.scrub();
                check(store.status().revision() == revision,
                        "repeated scrub does not grow quarantine metadata");
            }
            check(Files.readString(body).equals("corrupt body"), "corrupt original retained");
            Path manifest = location.resolve("manifest.json");
            String valid = Files.readString(manifest);
            Files.writeString(manifest, valid.replace("\"checksum\":\"", "\"checksum\":\"f"));
            try (VersionedSkillRepository store = store(world, runtime())) {
                check(store.status().recoveryState().startsWith("RECOVERED_PREVIOUS_MANIFEST"),
                        "corrupt manifest falls back to valid committed backup");
                check(store.resolve(first.descriptor().ref()).usable(), "backup never references missing body");
            }
            try (var diagnostics = Files.list(location.resolve("quarantine"))) {
                check(diagnostics.count() == 1, "diagnostic original retained");
            }
            // A future manifest is inactive and preserved; it must not fall back to an older
            // schema-1 backup and silently discard later world rights.
            String future = Files.readString(manifest).replace("\"schema\":1", "\"schema\":2");
            Files.writeString(manifest, future);
            try (VersionedSkillRepository store = store(world, runtime())) {
                check(store.status().readOnly() && store.page(ID, "", 1).candidates().isEmpty(),
                        "future manifest inactive");
            }
            check(Files.readString(manifest).equals(future), "future original preserved");
        } finally { erase(world); }

        Path futureBodyWorld = Files.createTempDirectory("cc-repo-future-body");
        try {
            try (VersionedSkillRepository store = store(futureBodyWorld, runtime())) {
                assertStatus(PublishStatus.ADMITTED, store.publish(first, decision(14, OWNER_A),
                        provenance(first, "model-a")));
            }
            Path body = location(futureBodyWorld).resolve("bodies/"
                    + first.descriptor().ref().sha256() + ".json");
            String future = Files.readString(body).replace("\"schema\":1,\"spec\"",
                    "\"schema\":2,\"spec\"");
            Files.writeString(body, future);
            rewriteManifestBodyHash(location(futureBodyWorld).resolve("manifest.json"),
                    first.descriptor().ref(), future);
            try (VersionedSkillRepository store = store(futureBodyWorld, runtime())) {
                ArtifactView view = store.resolve(first.descriptor().ref());
                check(view.integrity() == Integrity.UNKNOWN_SCHEMA && !view.usable()
                        && view.admission().status() == AdmissionStatus.ADMITTED,
                        "future body preserved, inactive, historical admission intact");
                store.scrub();
                check(store.resolve(first.descriptor().ref()).admission().status()
                        == AdmissionStatus.ADMITTED,
                        "unknown body is not reclassified as corruption");
            }
        } finally { erase(futureBodyWorld); }

        Path dependencyWorld = Files.createTempDirectory("cc-repo-dependency-corruption");
        try {
            ArtifactRef rootRef;
            try (VersionedSkillRepository store = store(dependencyWorld, runtime())) {
                assertStatus(PublishStatus.ADMITTED,
                        store.publish(first, decision(27, OWNER_A), provenance(first, "model-a")));
                SkillArtifact parent = calling(first, store);
                rootRef = parent.descriptor().ref();
                assertStatus(PublishStatus.ADMITTED,
                        store.publish(parent, decision(28, OWNER_A), provenance(parent, "model-a")));
            }
            Path manifest = location(dependencyWorld).resolve("manifest.json");
            Map<String, Object> doc = StrictJson.object(Files.readString(manifest));
            for (Object raw : RepositoryCodec.array(doc, "entries")) {
                Map<String, Object> entry = RepositoryCodec.object(raw);
                if (RepositoryCodec.readRef(RepositoryCodec.object(entry.get("ref"))).equals(rootRef))
                    entry.put("pinned", List.of());
            }
            rewriteChecksum(doc);
            Files.writeString(manifest, StrictJson.canonical(doc));
            try (VersionedSkillRepository store = store(dependencyWorld, runtime())) {
                check(store.resolve(rootRef).integrity() == Integrity.CORRUPT
                        && !store.resolve(rootRef).usable(),
                        "manifest dependency tamper quarantined");
                check(store.resolve(first.descriptor().ref()).usable(),
                        "untampered dependency remains usable");
            }
        } finally { erase(dependencyWorld); }
    }

    private static void rewriteManifestBodyHash(Path manifest, ArtifactRef ref, String body)
            throws Exception {
        Map<String, Object> root = StrictJson.object(Files.readString(manifest));
        List<Object> entries = RepositoryCodec.array(root, "entries");
        Map<String, Object> record = RepositoryCodec.object(entries.get(0));
        check(RepositoryCodec.readRef(RepositoryCodec.object(record.get("ref"))).equals(ref),
                "single record fixture");
        record.put("bodySha256", RepositoryCodec.digest(body));
        record.put("bodyBytes", (long) body.getBytes(StandardCharsets.UTF_8).length);
        rewriteChecksum(root);
        Files.writeString(manifest, StrictJson.canonical(root));
    }
    private static void rewriteChecksum(Map<String, Object> root) throws Exception {
        Map<String, Object> payload = Map.of("schema", 1L,
                "revision", RepositoryCodec.number(root, "revision"),
                "entries", RepositoryCodec.array(root, "entries"));
        root.put("checksum", RepositoryCodec.digest(StrictJson.canonical(payload)));
    }

    public static void compatibility() throws Exception {
        Path world = Files.createTempDirectory("cc-repo-compat");
        try (VersionedSkillRepository store = store(world, runtime())) {
            SkillArtifact artifact = simple();
            assertStatus(PublishStatus.ADMITTED, store.publish(artifact, decision(15, OWNER_A),
                    provenance(artifact, "model-a")));
            store.updateRuntime(new RuntimeSnapshot("minecraft-27.0",
                    runtime().capabilities(), runtime().primitives()));
            ArtifactView view = store.resolve(artifact.descriptor().ref());
            check(!view.usable() && view.admission().status() == AdmissionStatus.ADMITTED
                    && view.integrity() == Integrity.VERIFIED,
                    "incompatible game target does not quarantine");
            store.updateRuntime(runtime());
            check(store.resolve(artifact.descriptor().ref()).usable(), "compatible again");
        } finally { erase(world); }

        Path primitiveWorld = Files.createTempDirectory("cc-repo-primitive");
        PrimitiveSignature initial = new PrimitiveSignature("cognitivecraft:sample", 1, A,
                List.of(new Parameter("amount", Type.INT, 0, 64)), Type.INT, Set.of());
        PrimitiveSignature changed = new PrimitiveSignature("cognitivecraft:sample", 1, B,
                initial.parameters(), Type.INT, Set.of());
        RuntimeSnapshot withPrimitive = new RuntimeSnapshot(GAME, runtime().capabilities(),
                (id, version) -> id.equals(initial.id()) && version == 1
                        ? Optional.of(initial) : Optional.empty());
        try (VersionedSkillRepository store = store(primitiveWorld, withPrimitive)) {
            SkillArtifact artifact = compile(List.of(
                    Map.of("op", "call", "kind", "primitive", "id", initial.id(),
                            "version", 1L, "fingerprint", A,
                            "args", Map.of("amount", Map.of("param", "amount")), "into", "received"),
                    Map.of("op", "result", "value", Map.of("local", "received"))),
                    List.of(), ref -> Optional.empty(), withPrimitive);
            assertStatus(PublishStatus.ADMITTED, store.publish(artifact, decision(16, OWNER_A),
                    provenance(artifact, "model-a")));
            RuntimeSnapshot unrelated = new RuntimeSnapshot(GAME, runtime().capabilities(),
                    (id, version) -> id.equals(initial.id())
                            ? Optional.of(initial) : Optional.of(changed));
            store.updateRuntime(unrelated);
            check(store.resolve(artifact.descriptor().ref()).usable(),
                    "unrelated primitive change does not invalidate artifact");
            RuntimeSnapshot changedRelevant = new RuntimeSnapshot(GAME, runtime().capabilities(),
                    (id, version) -> id.equals(initial.id()) ? Optional.of(changed)
                            : Optional.empty());
            store.updateRuntime(changedRelevant);
            check(!store.resolve(artifact.descriptor().ref()).usable()
                    && store.resolve(artifact.descriptor().ref()).admission().status()
                    == AdmissionStatus.ADMITTED, "relevant signature invalidates use only");
        } finally { erase(primitiveWorld); }
    }

    public static void quotas() throws Exception {
        SkillArtifact artifact = simple();
        int bodySize = RepositoryCodec.encodeBody(SPEC, artifact.descriptor(),
                artifact.canonicalIr()).getBytes(StandardCharsets.UTF_8).length;
        Path tooSmallBodyWorld = Files.createTempDirectory("cc-repo-body-minus-one");
        try (VersionedSkillRepository store = store(tooSmallBodyWorld, runtime(),
                new Limits(1, bodySize - 1, 8_192, 4_194_304, 1, 1, 128),
                decision -> true, TrustedContext::equals, FaultInjector.none())) {
            assertStatus(PublishStatus.STORAGE_LIMIT_REACHED,
                    store.publish(artifact, decision(25, OWNER_A),
                            provenance(artifact, "model-a")));
            check(store.status().revision() == 0, "body N+1 rejected");
        } finally { erase(tooSmallBodyWorld); }
        Path world = Files.createTempDirectory("cc-repo-quota");
        try {
            Limits exactBody = new Limits(1, bodySize, 8_192, 4_194_304, 1, 1, 128);
            try (VersionedSkillRepository store = store(world, runtime(), exactBody,
                    decision -> true, TrustedContext::equals, FaultInjector.none())) {
                assertStatus(PublishStatus.ADMITTED, store.publish(artifact, decision(17, OWNER_A),
                        provenance(artifact, "model-a")));
                long committed = store.status().accountedBytes();
                assertStatus(PublishStatus.STORAGE_LIMIT_REACHED,
                        store.publish(literal(1), decision(18, OWNER_A),
                                provenance(literal(1), "model-a")));
                check(store.status().accountedBytes() == committed && store.status().bodyCount() == 1,
                        "full body/count/variant cap changes no committed knowledge");
                illegal(() -> store.page(ID, "", 2));
            }
            try (VersionedSkillRepository recovered = store(world, runtime())) {
                check(recovered.resolve(artifact.descriptor().ref()).usable(),
                        "capacity failure never evicts protected version");
            }
        } finally { erase(world); }

        Path measureWorld = Files.createTempDirectory("cc-repo-measure");
        long exactTotal;
        try (VersionedSkillRepository measure = store(measureWorld, runtime())) {
            assertStatus(PublishStatus.ADMITTED, measure.publish(artifact, decision(19, OWNER_A),
                    provenance(artifact, "model-a")));
            exactTotal = measure.status().accountedBytes();
        } finally { erase(measureWorld); }
        Path exactWorld = Files.createTempDirectory("cc-repo-byte-cap");
        try (VersionedSkillRepository exact = store(exactWorld, runtime(),
                new Limits(2, bodySize, 8_192, exactTotal, 2, 2, 128),
                decision -> true, TrustedContext::equals, FaultInjector.none())) {
            assertStatus(PublishStatus.ADMITTED, exact.publish(artifact, decision(19, OWNER_A),
                    provenance(artifact, "model-a")));
            check(exact.status().accountedBytes() == exactTotal, "exact byte cap admitted");
            assertStatus(PublishStatus.STORAGE_LIMIT_REACHED,
                    exact.publish(literal(2), decision(20, OWNER_A),
                            provenance(literal(2), "model-a")));
        } finally { erase(exactWorld); }
        Path metaWorld = Files.createTempDirectory("cc-repo-metadata-cap");
        try {
            try (VersionedSkillRepository store = store(metaWorld, runtime())) {
                assertStatus(PublishStatus.ADMITTED,
                        store.publish(artifact, decision(26, OWNER_A),
                                provenance(artifact, "model-a")));
            }
            Map<String, Object> manifest = StrictJson.object(Files.readString(
                    location(metaWorld).resolve("manifest.json")));
            int metadataSize = StrictJson.canonical(
                    RepositoryCodec.object(RepositoryCodec.array(manifest, "entries").get(0)))
                    .getBytes(StandardCharsets.UTF_8).length;
            Path below = Files.createTempDirectory("cc-repo-metadata-minus-one");
            try (VersionedSkillRepository store = store(below, runtime(),
                    new Limits(1, bodySize, metadataSize - 1, 4_194_304, 1, 1, 128),
                    decision -> true, TrustedContext::equals, FaultInjector.none())) {
                assertStatus(PublishStatus.STORAGE_LIMIT_REACHED,
                        store.publish(artifact, decision(26, OWNER_A),
                                provenance(artifact, "model-a")));
            } finally { erase(below); }
            Path exact = Files.createTempDirectory("cc-repo-metadata-exact");
            try (VersionedSkillRepository store = store(exact, runtime(),
                    new Limits(1, bodySize, metadataSize, 4_194_304, 1, 1, 128),
                    decision -> true, TrustedContext::equals, FaultInjector.none())) {
                assertStatus(PublishStatus.ADMITTED,
                        store.publish(artifact, decision(26, OWNER_A),
                                provenance(artifact, "model-a")));
            } finally { erase(exact); }
        } finally { erase(metaWorld); }
        illegal(() -> new Limits(0, 1, 1, 1, 1, 1, 1));
        illegal(() -> new Limits(1, 0, 1, 1, 1, 1, 1));
        illegal(() -> new Limits(1, 1, 1, 0, 1, 1, 1));
        illegal(() -> new Limits(1, 1, 1, 1, 0, 1, 1));
    }

    public static void concurrentPublication() throws Exception {
        Path world = Files.createTempDirectory("cc-repo-concurrent");
        SkillArtifact first = simple(), second = literal(5);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try (VersionedSkillRepository store = store(world, runtime())) {
            CountDownLatch begin = new CountDownLatch(1);
            AtomicInteger incomplete = new AtomicInteger();
            var reader = pool.submit(() -> {
                begin.await();
                for (int n = 0; n < 250; n++) {
                    Page page = store.page(ID, "", 2);
                    if (page.candidates().size() != page.revision()) incomplete.incrementAndGet();
                    for (Candidate candidate : page.candidates())
                        if (store.resolve(candidate.ref()) == null) incomplete.incrementAndGet();
                    if (!page.complete()) incomplete.incrementAndGet();
                }
                return null;
            });
            var a = pool.submit(() -> { begin.await(); return store.publish(first,
                    decision(21, OWNER_A), provenance(first, "model-a")); });
            var b = pool.submit(() -> { begin.await(); return store.publish(second,
                    decision(22, OWNER_B), provenance(second, "model-b")); });
            begin.countDown();
            assertStatus(PublishStatus.ADMITTED, a.get(10, TimeUnit.SECONDS));
            assertStatus(PublishStatus.ADMITTED, b.get(10, TimeUnit.SECONDS));
            reader.get(10, TimeUnit.SECONDS);
            check(incomplete.get() == 0 && store.status().revision() == 2,
                    "readers never see partial manifest");
            try {
                store(world, runtime());
                throw new AssertionError("a second writer acquired the same world");
            } catch (IOException expected) {
                check(expected.getMessage().contains("Another repository writer"),
                        "one writer per world");
            }
        } finally {
            pool.shutdownNow();
            erase(world);
        }
    }

    public static void rootsAndCacheOutage() throws Exception {
        Path world = Files.createTempDirectory("cc-repo-roots");
        SkillArtifact child = simple();
        try (VersionedSkillRepository store = store(world, runtime())) {
            assertStatus(PublishStatus.ADMITTED, store.publish(child, decision(23, OWNER_A),
                    provenance(child, "model-a")));
            SkillArtifact parent = calling(child, store);
            assertStatus(PublishStatus.ADMITTED, store.publish(parent, decision(24, OWNER_A),
                    provenance(parent, "model-a")));
            check(store.roots(Set.of(parent.descriptor().ref())).containsAll(
                    List.of(parent.descriptor().ref(), child.descriptor().ref())),
                    "admitted roots protected");
            check(store.delete(child.descriptor().ref(), Set.of()) == DeleteStatus.PROTECTED
                    && store.delete(parent.descriptor().ref(), Set.of()) == DeleteStatus.PROTECTED,
                    "referenced/admitted knowledge cannot be evicted");
            check(store.delete(literal(9).descriptor().ref(), Set.of())
                    == DeleteStatus.NOT_FOUND, "unknown target absent");
            check(store.resolve(parent.descriptor().ref()).usable(), "zero model dependencies");
        } finally { erase(world); }
    }

    private static Path location(Path world) {
        return world.resolve(VersionedSkillRepository.WORLD_RELATIVE_PATH);
    }
    private static void assertStatus(PublishStatus expected, PublishResult actual) {
        check(actual.status() == expected, "expected " + expected + ", got " + actual);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void illegal(Runnable invocation) {
        try { invocation.run(); throw new AssertionError("expected invalid bounds"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void erase(Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList())
                Files.delete(path);
        }
    }
}
