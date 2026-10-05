package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.Contracts.AdmissionRecord;
import dev.aivillages.core.kernel.Contracts.AdmissionStatus;
import dev.aivillages.core.kernel.Contracts.ArtifactCatalog;
import dev.aivillages.core.kernel.Contracts.ArtifactDescriptor;
import dev.aivillages.core.kernel.Contracts.ArtifactRef;
import dev.aivillages.core.kernel.Contracts.CapabilityCatalog;
import dev.aivillages.core.kernel.Contracts.CapabilityId;
import dev.aivillages.core.kernel.Contracts.CapabilitySpec;
import dev.aivillages.core.kernel.Contracts.Compatibility;
import dev.aivillages.core.kernel.Contracts.CompatibilityStatus;
import dev.aivillages.core.kernel.Contracts.EvidenceRef;
import dev.aivillages.core.kernel.Contracts.PrimitiveCatalog;
import dev.aivillages.core.kernel.Contracts.Provenance;
import dev.aivillages.core.kernel.Contracts.ScopeRef;
import dev.aivillages.core.kernel.Contracts.SkillArtifact;
import dev.aivillages.core.kernel.Contracts.TrustedContext;
import dev.aivillages.core.kernel.Contracts.PrincipalRef;
import dev.aivillages.core.kernel.Outcomes.Reason;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * World-owned authoritative skill storage, manifest schema 1.
 *
 * <p>Open, publish, scrub and delete perform blocking I/O and must run on a background executor,
 * never the Minecraft tick. The bounded in-memory exact catalog and compatible view perform no I/O.
 * A single publication lock and atomic manifest replacement make readers see whole revisions.
 */
public final class VersionedSkillRepository implements ArtifactCatalog, AutoCloseable {
    public static final String WORLD_RELATIVE_PATH = "data/cognitivecraft/skills/v1";
    public static final int MANIFEST_SCHEMA = 1;
    private static final int MAX_MANIFEST_BYTES = StrictJson.MAX_BYTES;
    private final Path root;
    private final Path bodies;
    private final Path staging;
    private final Path quarantine;
    private final Path manifest;
    private final Path previous;
    private final Limits limits;
    private final AdmissionAuthority authority;
    private final VisibilityPolicy visibility;
    private final FaultInjector faults;
    private final FileChannel writerChannel;
    private final FileLock writerLock;
    private volatile RuntimeSnapshot runtime;
    private volatile Snapshot snapshot = new Snapshot(0, Map.of(), false, "OK");
    private volatile int orphanBodies;
    private volatile long accountedDiskBytes;
    private boolean replacementAttempted;

    public record Limits(int maxArtifacts, int maxBodyBytes, int maxMetadataBytes,
                         long maxTotalBytes, int maxVariantsPerCapability,
                         int maxPageSize, int maxRecoveryFiles) {
        public Limits {
            if (maxArtifacts < 1 || maxArtifacts > 128 || maxBodyBytes < 1
                    || maxBodyBytes > StrictJson.MAX_BYTES || maxMetadataBytes < 1
                    || maxMetadataBytes > 32_768 || maxTotalBytes < 1
                    || maxTotalBytes > 8_388_608 || maxVariantsPerCapability < 1
                    || maxVariantsPerCapability > 32 || maxPageSize < 1
                    || maxPageSize > 32 || maxRecoveryFiles < maxArtifacts
                    || maxRecoveryFiles > 512)
                throw new IllegalArgumentException("Repository limits");
        }
        public static Limits defaults() {
            return new Limits(48, 60_000, 8_192, 4_194_304, 8, 16, 256);
        }
    }

    /** Runtime descriptors can be replaced after a game or primitive upgrade. */
    public record RuntimeSnapshot(String gameTarget, CapabilityCatalog capabilities,
                                  PrimitiveCatalog primitives) {
        public RuntimeSnapshot {
            if (gameTarget == null || gameTarget.isBlank() || gameTarget.length() > 128)
                throw new IllegalArgumentException("Game target");
            Objects.requireNonNull(capabilities);
            Objects.requireNonNull(primitives);
        }
    }

    public record EvidenceBundle(EvidenceRef staticCheck, EvidenceRef fixtureCheck,
                                 EvidenceRef liveTrial, String receiptSha256) {
        public EvidenceBundle {
            Objects.requireNonNull(staticCheck);
            Objects.requireNonNull(fixtureCheck);
            Objects.requireNonNull(liveTrial);
            if (receiptSha256 == null || !receiptSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Attributable receipt digest");
        }
    }

    /** The server-owned research controller supplies the decision; the injected guard authenticates it. */
    public record AdmissionDecision(UUID decisionId, TrustedContext owner, EvidenceBundle evidence) {
        public AdmissionDecision {
            Objects.requireNonNull(decisionId);
            Objects.requireNonNull(owner);
            Objects.requireNonNull(evidence);
        }
    }
    public record QuarantineDecision(UUID decisionId, TrustedContext owner,
                                     EvidenceRef evidence, Reason reason) {
        public QuarantineDecision {
            Objects.requireNonNull(decisionId);
            Objects.requireNonNull(owner);
            Objects.requireNonNull(evidence);
            Objects.requireNonNull(reason);
        }
    }

    @FunctionalInterface public interface AdmissionAuthority {
        boolean authorizes(AdmissionDecision decision);
        default boolean authorizes(QuarantineDecision decision) { return false; }
    }
    @FunctionalInterface public interface VisibilityPolicy {
        boolean mayRead(TrustedContext viewer, TrustedContext owner);
    }

    public enum FaultPoint {
        BEFORE_BODY_WRITE, AFTER_BODY_WRITE, AFTER_EVIDENCE_STAGE,
        BEFORE_MANIFEST_REPLACE, AFTER_MANIFEST_REPLACE
    }
    @FunctionalInterface public interface FaultInjector {
        void check(FaultPoint point) throws IOException;
        static FaultInjector none() { return point -> { }; }
    }

    public enum PublishStatus {
        ADMITTED, QUARANTINED, UNAUTHORIZED, EVIDENCE_REQUIRED, ARTIFACT_INVALID,
        DEPENDENCY_INCOMPATIBLE, STORAGE_LIMIT_REACHED, STORAGE_UNAVAILABLE
    }
    public record PublishResult(PublishStatus status, ArtifactRef artifact, long revision,
                                boolean commitMayHaveSucceeded) { }
    public enum Integrity { VERIFIED, CORRUPT, UNKNOWN_SCHEMA }
    public record ArtifactView(ArtifactRef ref, SkillArtifact artifact, AdmissionRecord admission,
                               Compatibility compatibility, Integrity integrity) {
        public boolean usable() {
            return artifact != null && admission.status() == AdmissionStatus.ADMITTED
                    && compatibility.status() == CompatibilityStatus.COMPATIBLE
                    && integrity == Integrity.VERIFIED;
        }
    }
    public record Candidate(ArtifactRef ref, AdmissionStatus admission, Compatibility compatibility,
                            Integrity integrity) { }
    /** `complete` is true only when the exact capability/version has no more entries. */
    public record Page(List<Candidate> candidates, String nextCursor, boolean complete, long revision) {
        public Page { candidates = List.copyOf(candidates); }
    }
    public record PrivateOrigin(UUID decisionId, TrustedContext owner, EvidenceBundle evidence,
                                Provenance provenance) { }
    public record QuarantineEvent(UUID decisionId, String source, TrustedContext owner,
                                  EvidenceRef evidence, Reason reason) { }
    public record StorageStatus(long revision, long accountedBytes, int bodyCount, int orphanBodies,
                                boolean readOnly, String recoveryState) { }
    public enum DeleteStatus { DELETED, PROTECTED, NOT_FOUND, STORAGE_UNAVAILABLE }

    private record Stored(RepositoryCodec.Body body, List<ArtifactRef> pinned,
                          String bodySha256, int bodyBytes,
                          String gameTarget, AdmissionRecord admission,
                          List<PrivateOrigin> origins, List<QuarantineEvent> quarantines,
                          Integrity integrity) {
        Stored {
            pinned = List.copyOf(pinned);
            origins = List.copyOf(origins);
            quarantines = List.copyOf(quarantines);
        }
        Stored withAdmission(AdmissionRecord updated, Integrity integrity) {
            return new Stored(body, pinned, bodySha256, bodyBytes, gameTarget,
                    updated, origins, quarantines, integrity);
        }
        Stored withQuarantine(AdmissionRecord updated, Integrity integrity,
                              QuarantineEvent event) {
            List<QuarantineEvent> appended = new ArrayList<>(quarantines);
            appended.add(event);
            return new Stored(body, pinned, bodySha256, bodyBytes, gameTarget,
                    updated, origins, appended, integrity);
        }
    }
    private record Snapshot(long revision, Map<ArtifactRef, Stored> records,
                            boolean readOnly, String recoveryState) {
        Snapshot { records = Map.copyOf(records); }
    }

    /**
     * Opens a world store off the server tick. Legacy data/ai-villages/state.json and its backups
     * are not read, migrated, overwritten or deleted by this schema owner.
     */
    public static VersionedSkillRepository open(Path worldRoot, RuntimeSnapshot runtime,
                                                Limits limits, AdmissionAuthority authority,
                                                VisibilityPolicy visibility,
                                                FaultInjector faults) throws IOException {
        VersionedSkillRepository store = new VersionedSkillRepository(
                worldRoot.resolve(WORLD_RELATIVE_PATH), runtime, limits,
                authority, visibility, faults);
        try {
            store.recover();
            return store;
        } catch (IOException | RuntimeException failure) {
            store.close();
            throw failure;
        }
    }

    private VersionedSkillRepository(Path root, RuntimeSnapshot runtime, Limits limits,
                                     AdmissionAuthority authority, VisibilityPolicy visibility,
                                     FaultInjector faults) throws IOException {
        this.root = root;
        this.bodies = root.resolve("bodies");
        this.staging = root.resolve("staging");
        this.quarantine = root.resolve("quarantine");
        this.manifest = root.resolve("manifest.json");
        this.previous = root.resolve("manifest.previous.json");
        this.runtime = Objects.requireNonNull(runtime);
        this.limits = Objects.requireNonNull(limits);
        this.authority = Objects.requireNonNull(authority);
        this.visibility = Objects.requireNonNull(visibility);
        this.faults = Objects.requireNonNull(faults);
        Files.createDirectories(bodies);
        Files.createDirectories(staging);
        Files.createDirectories(quarantine);
        writerChannel = FileChannel.open(root.resolve(".writer.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = writerChannel.tryLock(); }
        catch (java.nio.channels.OverlappingFileLockException held) { acquired = null; }
        if (acquired == null) {
            writerChannel.close();
            throw new IOException("Another repository writer owns this world");
        }
        writerLock = acquired;
    }

    @Override public void close() throws IOException {
        writerLock.release();
        writerChannel.close();
    }

    /** No I/O; a catalog signature is available only while admitted, intact and compatible. */
    @Override public Optional<ArtifactDescriptor> find(ArtifactRef ref) {
        ArtifactView view = resolve(ref);
        return view == null || !view.usable()
                ? Optional.empty() : Optional.of(view.artifact().descriptor());
    }

    /** Bounded and I/O-free. Private origins are intentionally absent from the shared body view. */
    public ArtifactView resolve(ArtifactRef ref) {
        return resolve(ref, snapshot, runtime);
    }

    private ArtifactView resolve(ArtifactRef ref, Snapshot current, RuntimeSnapshot environment) {
        Stored stored = current.records().get(ref);
        if (stored == null) return null;
        Compatibility compatibility = compatibility(ref, current, environment,
                new HashSet<>(), 0, new int[] {0});
        SkillArtifact artifact = compatibility.status() == CompatibilityStatus.COMPATIBLE
                && stored.integrity() == Integrity.VERIFIED
                && stored.admission().status() == AdmissionStatus.ADMITTED
                ? new SkillArtifact(stored.body().descriptor(), stored.body().canonicalIr(),
                        new Contracts.ArtifactMetadata(null, null, 0)) : null;
        return new ArtifactView(ref, artifact, stored.admission(), compatibility, stored.integrity());
    }

    public Page page(CapabilityId capability, String afterSha256, int pageSize) {
        Objects.requireNonNull(capability);
        if (pageSize < 1 || pageSize > limits.maxPageSize()
                || afterSha256 == null || (!afterSha256.isEmpty()
                && !afterSha256.matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("Page bound/cursor");
        Snapshot current = snapshot;
        RuntimeSnapshot environment = runtime;
        List<ArtifactRef> matches = current.records().keySet().stream()
                .filter(ref -> ref.capability().equals(capability))
                .sorted(Comparator.comparing(ArtifactRef::sha256)).toList();
        List<Candidate> result = new ArrayList<>();
        boolean more = false;
        for (ArtifactRef ref : matches) {
            if (ref.sha256().compareTo(afterSha256) <= 0) continue;
            if (result.size() == pageSize) { more = true; break; }
            ArtifactView view = resolve(ref, current, environment);
            result.add(new Candidate(ref, view.admission().status(), view.compatibility(),
                    view.integrity()));
        }
        String next = more ? result.get(result.size() - 1).ref().sha256() : "";
        return new Page(result, next, !more, current.revision());
    }

    /** Owner-scoped metadata. Sharing a body does not disclose another owner’s trial context. */
    public List<PrivateOrigin> privateOrigins(ArtifactRef ref, TrustedContext viewer) {
        Objects.requireNonNull(viewer);
        Stored stored = snapshot.records().get(ref);
        if (stored == null) return List.of();
        return stored.origins().stream()
                .filter(origin -> visibility.mayRead(viewer, origin.owner())).toList();
    }

    public List<QuarantineEvent> quarantineEvents(ArtifactRef ref, TrustedContext viewer) {
        Objects.requireNonNull(viewer);
        Stored stored = snapshot.records().get(ref);
        if (stored == null) return List.of();
        return stored.quarantines().stream().filter(event -> event.owner() == null
                || visibility.mayRead(viewer, event.owner())).toList();
    }

    public StorageStatus status() {
        Snapshot current = snapshot;
        return new StorageStatus(current.revision(), accountedDiskBytes, current.records().size(),
                orphanBodies, current.readOnly(), current.recoveryState());
    }

    public void updateRuntime(RuntimeSnapshot replacement) { runtime = Objects.requireNonNull(replacement); }

    public CompletableFuture<PublishResult> publishAsync(SkillArtifact artifact,
            AdmissionDecision decision, Provenance provenance, Executor worker) {
        Objects.requireNonNull(worker);
        return CompletableFuture.supplyAsync(() -> publish(artifact, decision, provenance), worker);
    }

    /**
     * Blocking publication. Return ADMITTED only after the atomic durable manifest commit.
     * The guard must be wired to a trusted controller and must never accept user/model assertions.
     */
    public synchronized PublishResult publish(SkillArtifact artifact, AdmissionDecision decision,
                                              Provenance provenance) {
        replacementAttempted = false;
        Snapshot current = snapshot;
        ArtifactRef ref = artifact == null ? null : artifact.descriptor().ref();
        if (current.readOnly()) return result(PublishStatus.STORAGE_UNAVAILABLE, ref, false);
        if (decision == null || !authority.authorizes(decision))
            return result(PublishStatus.UNAUTHORIZED, ref, false);
        if (artifact == null || provenance == null)
            return result(PublishStatus.EVIDENCE_REQUIRED, ref, false);
        RuntimeSnapshot environment = runtime;
        CapabilitySpec spec = environment.capabilities().find(ref.capability()).orElse(null);
        if (spec == null || !provenance.actualDependencies().equals(artifact.descriptor().dependencies())
                || !provenance.evidence().containsAll(List.of(decision.evidence().staticCheck(),
                    decision.evidence().fixtureCheck(), decision.evidence().liveTrial())))
            return result(PublishStatus.ARTIFACT_INVALID, ref, false);
        SkillCompiler.CompileResult compiled = new SkillCompiler(environment.capabilities(),
                environment.primitives(), dependency -> Optional.ofNullable(
                        current.records().get(dependency)).map(Stored::body)
                        .map(RepositoryCodec.Body::descriptor)).compile(artifact.canonicalIr());
        if (!(compiled instanceof SkillCompiler.Success success)
                || !success.skill().artifact().descriptor().equals(artifact.descriptor())
                || !success.skill().artifact().canonicalIr().equals(artifact.canonicalIr()))
            return result(PublishStatus.ARTIFACT_INVALID, ref, false);
        try {
            // Publication validates durable references, not only the in-memory copy loaded
            // at startup. An external disk change cannot gain a fresh admitted manifest.
            Stored existing = current.records().get(ref);
            if (existing != null && loadBody(ref, existing).integrity() != Integrity.VERIFIED)
                return result(PublishStatus.ARTIFACT_INVALID, ref, false);
            for (ArtifactRef dependency : artifact.descriptor().dependencies()) {
                ArtifactView known = resolve(dependency, current, environment);
                if (known == null || !known.usable())
                    return result(PublishStatus.DEPENDENCY_INCOMPATIBLE, ref, false);
                for (ArtifactRef pinned : dependencyClosure(dependency)) {
                    Stored record = current.records().get(pinned);
                    if (record == null || loadBody(pinned, record).integrity()
                            != Integrity.VERIFIED)
                        return result(PublishStatus.DEPENDENCY_INCOMPATIBLE, ref, false);
                }
            }
        } catch (IOException unavailable) {
            return result(PublishStatus.STORAGE_UNAVAILABLE, ref, false);
        }
        try {
            String body = RepositoryCodec.encodeBody(spec, artifact.descriptor(), artifact.canonicalIr());
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            if (bodyBytes.length > limits.maxBodyBytes())
                return result(PublishStatus.STORAGE_LIMIT_REACHED, ref, false);
            String bodyDigest = RepositoryCodec.digest(body);
            Stored former = current.records().get(ref);
            if (former != null && (former.integrity() != Integrity.VERIFIED
                    || !former.bodySha256().equals(bodyDigest) || !former.body().equals(
                            new RepositoryCodec.Body(spec, artifact.descriptor(), artifact.canonicalIr()))))
                return result(PublishStatus.ARTIFACT_INVALID, ref, false);
            PrivateOrigin origin = new PrivateOrigin(decision.decisionId(), decision.owner(),
                    decision.evidence(), provenance);
            if (former != null && former.origins().stream().anyMatch(existing ->
                    existing.decisionId().equals(decision.decisionId())))
                return result(PublishStatus.ARTIFACT_INVALID, ref, false);
            List<PrivateOrigin> origins = new ArrayList<>(former == null ? List.of() : former.origins());
            origins.add(origin);
            if (origins.size() > 32)
                return result(PublishStatus.STORAGE_LIMIT_REACHED, ref, false);
            long revision = Math.addExact(current.revision(), 1);
            Stored next = new Stored(new RepositoryCodec.Body(spec, artifact.descriptor(),
                    artifact.canonicalIr()), artifact.descriptor().dependencies(), bodyDigest,
                    bodyBytes.length, environment.gameTarget(),
                    new AdmissionRecord(ref, AdmissionStatus.ADMITTED, decision.evidence().liveTrial(),
                            null, revision), origins, former == null ? List.of() :
                            former.quarantines(), Integrity.VERIFIED);
            if (metadataBytes(next) > limits.maxMetadataBytes())
                return result(PublishStatus.STORAGE_LIMIT_REACHED, ref, false);
            if (former == null && (current.records().size() >= limits.maxArtifacts()
                    || current.records().keySet().stream().filter(
                        existing -> existing.capability().equals(ref.capability())).count()
                    >= limits.maxVariantsPerCapability()))
                return result(PublishStatus.STORAGE_LIMIT_REACHED, ref, false);
            Map<ArtifactRef, Stored> changed = new HashMap<>(current.records());
            changed.put(ref, next);
            String manifestText = encodeManifest(revision, changed);
            byte[] manifestBytes = manifestText.getBytes(StandardCharsets.UTF_8);
            if (manifestBytes.length > MAX_MANIFEST_BYTES)
                return result(PublishStatus.STORAGE_LIMIT_REACHED, ref, false);
            Account account = accountFiles();
            long projected = Math.addExact(account.bytes(),
                    (former == null ? bodyBytes.length : 0) + manifestBytes.length);
            // One previous manifest and one staged new manifest may coexist at publication.
            projected = Math.addExact(projected, Files.exists(manifest) ? Files.size(manifest) : 0);
            if (projected > limits.maxTotalBytes())
                return result(PublishStatus.STORAGE_LIMIT_REACHED, ref, false);
            if (former == null) {
                faults.check(FaultPoint.BEFORE_BODY_WRITE);
                writeImmutableBody(ref, bodyBytes);
                faults.check(FaultPoint.AFTER_BODY_WRITE);
            }
            commit(manifestText, changed, revision);
            refreshAccounting();
            replacementAttempted = false;
            return result(PublishStatus.ADMITTED, ref, false);
        } catch (ArithmeticException limit) {
            return result(PublishStatus.STORAGE_LIMIT_REACHED, ref, false);
        } catch (StrictJson.Invalid invalid) {
            return result(PublishStatus.ARTIFACT_INVALID, ref, false);
        } catch (IOException failure) {
            return result(PublishStatus.STORAGE_UNAVAILABLE, ref, replacementAttempted);
        }
    }

    /** Records a research-controller quarantine without editing the immutable body or old evidence. */
    public synchronized PublishResult quarantine(ArtifactRef ref, QuarantineDecision decision) {
        replacementAttempted = false;
        Snapshot current = snapshot;
        if (current.readOnly()) return result(PublishStatus.STORAGE_UNAVAILABLE, ref, false);
        if (decision == null || !authority.authorizes(decision))
            return result(PublishStatus.UNAUTHORIZED, ref, false);
        Stored old = current.records().get(ref);
        if (old == null) return result(PublishStatus.ARTIFACT_INVALID, ref, false);
        if (old.quarantines().stream().anyMatch(event ->
                event.decisionId().equals(decision.decisionId())))
            return result(PublishStatus.ARTIFACT_INVALID, ref, false);
        if (old.quarantines().size() >= 32)
            return result(PublishStatus.STORAGE_LIMIT_REACHED, ref, false);
        try {
            long revision = Math.addExact(current.revision(), 1);
            AdmissionRecord admission = new AdmissionRecord(ref, AdmissionStatus.QUARANTINED,
                    old.admission().evidence(), decision.reason(), revision);
            Stored updated = old.withQuarantine(admission, old.integrity(),
                    new QuarantineEvent(decision.decisionId(), "research-controller",
                            decision.owner(), decision.evidence(), decision.reason()));
            if (metadataBytes(updated) > limits.maxMetadataBytes())
                return result(PublishStatus.STORAGE_LIMIT_REACHED, ref, false);
            Map<ArtifactRef, Stored> changed = new HashMap<>(current.records());
            changed.put(ref, updated);
            String encoded = encodeManifest(revision, changed);
            if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_MANIFEST_BYTES)
                return result(PublishStatus.STORAGE_LIMIT_REACHED, ref, false);
            Account account = accountFiles();
            if (Math.addExact(account.bytes(), Math.addExact(
                    encoded.getBytes(StandardCharsets.UTF_8).length,
                    Files.exists(manifest) ? Files.size(manifest) : 0))
                    > limits.maxTotalBytes())
                return result(PublishStatus.STORAGE_LIMIT_REACHED, ref, false);
            commit(encoded, changed, revision);
            refreshAccounting();
            replacementAttempted = false;
            return result(PublishStatus.QUARANTINED, ref, false);
        } catch (ArithmeticException overflow) {
            return result(PublishStatus.STORAGE_LIMIT_REACHED, ref, false);
        } catch (IOException failure) {
            return result(PublishStatus.STORAGE_UNAVAILABLE, ref, replacementAttempted);
        }
    }

    /** Catalog roots include admitted knowledge and explicitly supplied active/rollback references. */
    public Set<ArtifactRef> roots(Set<ArtifactRef> externallyProtected) {
        Objects.requireNonNull(externallyProtected);
        Set<ArtifactRef> roots = new HashSet<>(externallyProtected);
        snapshot.records().forEach((ref, stored) -> {
            if (stored.admission().status() == AdmissionStatus.ADMITTED
                    || stored.admission().status() == AdmissionStatus.QUARANTINED) roots.add(ref);
        });
        return Set.copyOf(roots);
    }

    public Set<ArtifactRef> dependencyClosure(ArtifactRef root) {
        Snapshot current = snapshot;
        Set<ArtifactRef> visited = new HashSet<>();
        traverse(root, current, visited, 0);
        return Set.copyOf(visited);
    }

    /** Only non-admitted, unreferenced records may be removed; future retention owns policy. */
    public synchronized DeleteStatus delete(ArtifactRef ref, Set<ArtifactRef> externallyProtected) {
        Snapshot current = snapshot;
        if (current.readOnly()) return DeleteStatus.STORAGE_UNAVAILABLE;
        Stored record = current.records().get(ref);
        if (record == null) return DeleteStatus.NOT_FOUND;
        if (roots(externallyProtected).contains(ref)
                || current.records().values().stream().anyMatch(other ->
                    other.pinned().contains(ref)))
            return DeleteStatus.PROTECTED;
        try {
            Map<ArtifactRef, Stored> changed = new HashMap<>(current.records());
            changed.remove(ref);
            long revision = Math.addExact(current.revision(), 1);
            commit(encodeManifest(revision, changed), changed, revision);
            // Retain immutable body as an accounted orphan. Compaction can remove it later.
            refreshAccounting();
            return DeleteStatus.DELETED;
        } catch (IOException | ArithmeticException failure) { return DeleteStatus.STORAGE_UNAVAILABLE; }
    }

    /** Re-verifies bounded stored bodies off-thread and durably quarantines damaged records. */
    public synchronized StorageStatus scrub() throws IOException {
        Snapshot current = snapshot;
        if (current.readOnly()) return status();
        Map<ArtifactRef, Stored> changed = new HashMap<>(current.records());
        for (Map.Entry<ArtifactRef, Stored> entry : current.records().entrySet()) {
            Stored old = entry.getValue();
            Stored checked = loadBody(entry.getKey(), old);
            if (checked.integrity() == Integrity.CORRUPT
                    && (old.integrity() != Integrity.CORRUPT
                            || old.admission().status() != AdmissionStatus.QUARANTINED)) {
                changed.put(entry.getKey(), old.withQuarantine(
                        new AdmissionRecord(entry.getKey(), AdmissionStatus.QUARANTINED,
                                old.admission().evidence(), Reason.ARTIFACT_INVALID,
                                Math.addExact(current.revision(), 1)), Integrity.CORRUPT,
                        integrityEvent(entry.getKey(), current.revision() + 1)));
            }
        }
        if (!changed.equals(current.records())) {
            long revision = Math.addExact(current.revision(), 1);
            commit(encodeManifest(revision, changed), changed, revision);
        }
        refreshAccounting();
        return status();
    }

    private Compatibility compatibility(ArtifactRef ref, Snapshot current,
                                        RuntimeSnapshot environment, Set<ArtifactRef> active,
                                        int depth, int[] work) {
        Stored stored = current.records().get(ref);
        if (stored == null || stored.integrity() == Integrity.CORRUPT)
            return incompatible(ref, Reason.ARTIFACT_INVALID, environment);
        if (stored.integrity() == Integrity.UNKNOWN_SCHEMA)
            return new Compatibility(ref, CompatibilityStatus.UNKNOWN,
                    List.of(Reason.ARTIFACT_INCOMPATIBLE), environment.gameTarget());
        if (stored.admission().status() == AdmissionStatus.QUARANTINED)
            return incompatible(ref, Reason.ARTIFACT_QUARANTINED, environment);
        if (stored.admission().status() != AdmissionStatus.ADMITTED)
            return incompatible(ref, Reason.ARTIFACT_INVALID, environment);
        if (depth > 8 || ++work[0] > 128 || !active.add(ref))
            return incompatible(ref, Reason.DEPENDENCY_INCOMPATIBLE, environment);
        if (!stored.gameTarget().equals(environment.gameTarget())
                || !environment.capabilities().find(ref.capability()).map(
                    spec -> RepositoryCodec.spec(spec).equals(RepositoryCodec.spec(stored.body().spec()))
                ).orElse(false)) {
            active.remove(ref);
            return incompatible(ref, Reason.ARTIFACT_INCOMPATIBLE, environment);
        }
        for (var primitive : stored.body().descriptor().primitives()) {
            if (!environment.primitives().find(primitive.id(), primitive.version())
                    .map(actual -> actual.fingerprint().equals(primitive.fingerprint()))
                    .orElse(false)) {
                active.remove(ref);
                return incompatible(ref, Reason.DEPENDENCY_INCOMPATIBLE, environment);
            }
        }
        for (ArtifactRef dependency : stored.body().descriptor().dependencies()) {
            Compatibility nested = compatibility(dependency, current, environment, active,
                    depth + 1, work);
            if (nested.status() != CompatibilityStatus.COMPATIBLE) {
                active.remove(ref);
                return incompatible(ref, Reason.DEPENDENCY_INCOMPATIBLE, environment);
            }
        }
        active.remove(ref);
        SkillCompiler.CompileResult compiled = new SkillCompiler(environment.capabilities(),
                environment.primitives(), dep -> Optional.ofNullable(current.records().get(dep))
                        .map(Stored::body).map(RepositoryCodec.Body::descriptor))
                .compile(stored.body().canonicalIr());
        if (!(compiled instanceof SkillCompiler.Success success)
                || !success.skill().artifact().descriptor().equals(stored.body().descriptor()))
            return incompatible(ref, Reason.ARTIFACT_INVALID, environment);
        return new Compatibility(ref, CompatibilityStatus.COMPATIBLE, List.of(),
                environment.gameTarget());
    }

    private static Compatibility incompatible(ArtifactRef ref, Reason reason,
                                               RuntimeSnapshot environment) {
        return new Compatibility(ref, CompatibilityStatus.INCOMPATIBLE, List.of(reason),
                environment.gameTarget());
    }

    private void traverse(ArtifactRef ref, Snapshot current, Set<ArtifactRef> visited, int depth) {
        if (depth > 8 || visited.size() > 128 || !visited.add(ref)) return;
        Stored record = current.records().get(ref);
        if (record != null) for (ArtifactRef child : record.pinned())
            traverse(child, current, visited, depth + 1);
    }

    private PublishResult result(PublishStatus code, ArtifactRef ref, boolean uncertain) {
        return new PublishResult(code, ref, snapshot.revision(), uncertain);
    }

    private void recover() throws IOException {
        Snapshot recovered;
        if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    recovered = decodeManifest(readBounded(previous, MAX_MANIFEST_BYTES));
                    writeAtomic(manifest, Files.readAllBytes(previous));
                    recovered = new Snapshot(recovered.revision(), recovered.records(), false,
                            "RECOVERED_PREVIOUS_MANIFEST");
                } catch (StrictJson.Invalid invalid) {
                    snapshot = new Snapshot(0, Map.of(), true, "CORRUPT_BACKUP");
                    refreshAccounting();
                    return;
                }
            } else recovered = new Snapshot(0, Map.of(), false, "EMPTY");
        } else {
            try {
                recovered = decodeManifest(readBounded(manifest, MAX_MANIFEST_BYTES));
            } catch (StrictJson.Invalid malformed) {
                if ("UNKNOWN_SCHEMA".equals(malformed.code())) {
                    snapshot = new Snapshot(0, Map.of(), true, "UNKNOWN_MANIFEST_SCHEMA");
                    refreshAccounting();
                    return;
                }
                preserveCorruptManifest();
                if (!Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                    snapshot = new Snapshot(0, Map.of(), true, "CORRUPT_MANIFEST");
                    refreshAccounting();
                    return;
                }
                try { recovered = decodeManifest(readBounded(previous, MAX_MANIFEST_BYTES)); }
                catch (StrictJson.Invalid badBackup) {
                    snapshot = new Snapshot(0, Map.of(), true, "CORRUPT_MANIFEST_AND_BACKUP");
                    refreshAccounting();
                    return;
                }
                writeAtomic(manifest, Files.readAllBytes(previous));
                recovered = new Snapshot(recovered.revision(), recovered.records(), false,
                        "RECOVERED_PREVIOUS_MANIFEST");
            }
        }
        snapshot = recovered;
        // Staging files have no authority. Cleanup is bounded and only after a supported manifest.
        cleanupStaging();
        Map<ArtifactRef, Stored> verified = new HashMap<>();
        boolean corrupt = false;
        for (Map.Entry<ArtifactRef, Stored> entry : recovered.records().entrySet()) {
            Stored record = loadBody(entry.getKey(), entry.getValue());
            if (record.integrity() == Integrity.CORRUPT
                    && record.admission().status() != AdmissionStatus.QUARANTINED) {
                record = record.withQuarantine(new AdmissionRecord(entry.getKey(),
                        AdmissionStatus.QUARANTINED, record.admission().evidence(),
                        Reason.ARTIFACT_INVALID, Math.addExact(recovered.revision(), 1)),
                        Integrity.CORRUPT,
                        integrityEvent(entry.getKey(), recovered.revision() + 1));
                corrupt = true;
            }
            verified.put(entry.getKey(), record);
        }
        snapshot = new Snapshot(recovered.revision(), verified, false, recovered.recoveryState());
        if (corrupt) {
            long revision = Math.addExact(recovered.revision(), 1);
            try {
                commit(encodeManifest(revision, verified), verified, revision);
                snapshot = new Snapshot(revision, verified, false,
                        recovered.recoveryState().equals("OK")
                                ? "QUARANTINED_CORRUPT_BODY"
                                : recovered.recoveryState() + "_AND_QUARANTINED_BODY");
            }
            catch (IOException unavailable) {
                // Inactive in memory; next open repeats integrity verification.
                snapshot = new Snapshot(recovered.revision(), verified, true,
                        "QUARANTINE_COMMIT_UNAVAILABLE");
            }
        }
        refreshAccounting();
    }

    private Stored loadBody(ArtifactRef ref, Stored record) throws IOException {
        Path path = bodyPath(ref);
        try {
            String source = readBounded(path, limits.maxBodyBytes());
            if (source.getBytes(StandardCharsets.UTF_8).length != record.bodyBytes()
                    || !RepositoryCodec.digest(source).equals(record.bodySha256()))
                return record.withAdmission(record.admission(), Integrity.CORRUPT);
            RepositoryCodec.Body body = RepositoryCodec.decodeBody(source);
            if (!body.descriptor().ref().equals(ref)
                    || !body.descriptor().dependencies().equals(record.pinned()))
                return record.withAdmission(record.admission(), Integrity.CORRUPT);
            return new Stored(body, record.pinned(), record.bodySha256(), record.bodyBytes(),
                    record.gameTarget(), record.admission(), record.origins(),
                    record.quarantines(), Integrity.VERIFIED);
        } catch (StrictJson.Invalid malformed) {
            return record.withAdmission(record.admission(),
                    "UNKNOWN_SCHEMA".equals(malformed.code())
                    ? Integrity.UNKNOWN_SCHEMA : Integrity.CORRUPT);
        } catch (java.nio.charset.MalformedInputException malformed) {
            return record.withAdmission(record.admission(), Integrity.CORRUPT);
        } catch (java.nio.file.NoSuchFileException missing) {
            return record.withAdmission(record.admission(), Integrity.CORRUPT);
        }
    }

    private String encodeManifest(long revision, Map<ArtifactRef, Stored> entries) {
        List<Object> records = entries.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(
                        ref -> ref.capability().name() + "@" + ref.capability().version()
                                + ":" + ref.sha256())))
                .map(entry -> (Object) entryMap(entry.getKey(), entry.getValue())).toList();
        Map<String, Object> payload = Map.of("schema", (long) MANIFEST_SCHEMA,
                "revision", revision, "entries", records);
        return StrictJson.canonical(Map.of("schema", (long) MANIFEST_SCHEMA,
                "revision", revision, "entries", records,
                "checksum", RepositoryCodec.digest(StrictJson.canonical(payload))));
    }

    private Snapshot decodeManifest(String source) throws StrictJson.Invalid {
        Map<String, Object> rootMap = StrictJson.object(source);
        if (RepositoryCodec.number(rootMap, "schema") != MANIFEST_SCHEMA)
            throw RepositoryCodec.invalid("UNKNOWN_SCHEMA");
        RepositoryCodec.keys(rootMap, "schema", "revision", "entries", "checksum");
        long revision = RepositoryCodec.number(rootMap, "revision");
        if (revision < 0) throw RepositoryCodec.invalid("MANIFEST_STRUCTURE");
        List<Object> records = RepositoryCodec.array(rootMap, "entries");
        if (records.size() > limits.maxArtifacts()) throw RepositoryCodec.invalid("MANIFEST_LIMIT");
        Map<String, Object> payload = Map.of("schema", (long) MANIFEST_SCHEMA,
                "revision", revision, "entries", records);
        if (!RepositoryCodec.digest(StrictJson.canonical(payload))
                .equals(RepositoryCodec.string(rootMap, "checksum")))
            throw RepositoryCodec.invalid("MANIFEST_CHECKSUM");
        Map<ArtifactRef, Stored> loaded = new HashMap<>();
        for (Object raw : records) {
            Map<String, Object> entry = RepositoryCodec.object(raw);
            RepositoryCodec.keys(entry, "ref", "bodySha256", "bodyBytes", "gameTarget",
                    "admission", "evidence", "reason", "revision", "pinned", "origins",
                    "quarantines");
            ArtifactRef ref = RepositoryCodec.readRef(RepositoryCodec.object(entry.get("ref")));
            String digest = RepositoryCodec.string(entry, "bodySha256");
            int bytes = RepositoryCodec.positive(entry, "bodyBytes");
            if (!digest.matches("[0-9a-f]{64}") || bytes > limits.maxBodyBytes())
                throw RepositoryCodec.invalid("MANIFEST_STRUCTURE");
            String game = RepositoryCodec.string(entry, "gameTarget");
            if (game.isBlank() || game.length() > 128)
                throw RepositoryCodec.invalid("MANIFEST_STRUCTURE");
            List<ArtifactRef> pinned = new ArrayList<>();
            for (Object rawPinned : RepositoryCodec.array(entry, "pinned"))
                pinned.add(RepositoryCodec.readRef(RepositoryCodec.object(rawPinned)));
            if (pinned.size() > 16 || pinned.size() != Set.copyOf(pinned).size())
                throw RepositoryCodec.invalid("MANIFEST_STRUCTURE");
            List<PrivateOrigin> origins = new ArrayList<>();
            for (Object rawOrigin : RepositoryCodec.array(entry, "origins"))
                origins.add(readOrigin(RepositoryCodec.object(rawOrigin)));
            if (origins.isEmpty() || origins.size() > 32)
                throw RepositoryCodec.invalid("MANIFEST_STRUCTURE");
            List<QuarantineEvent> quarantines = new ArrayList<>();
            for (Object rawEvent : RepositoryCodec.array(entry, "quarantines"))
                quarantines.add(readQuarantineEvent(RepositoryCodec.object(rawEvent)));
            if (quarantines.size() > 32) throw RepositoryCodec.invalid("MANIFEST_LIMIT");
            AdmissionStatus admission;
            Reason reason;
            try {
                admission = AdmissionStatus.valueOf(RepositoryCodec.string(entry, "admission"));
                String reasonName = RepositoryCodec.string(entry, "reason");
                reason = reasonName.isEmpty() ? null : Reason.valueOf(reasonName);
            } catch (IllegalArgumentException invalid) {
                throw RepositoryCodec.invalid("MANIFEST_STRUCTURE");
            }
            if (admission == AdmissionStatus.CANDIDATE) throw RepositoryCodec.invalid("MANIFEST_STRUCTURE");
            EvidenceRef evidence = readEvidence(RepositoryCodec.object(entry.get("evidence")));
            AdmissionRecord record;
            try { record = new AdmissionRecord(ref, admission, evidence, reason,
                    RepositoryCodec.number(entry, "revision")); }
            catch (IllegalArgumentException invalid) { throw RepositoryCodec.invalid("MANIFEST_STRUCTURE"); }
            Stored stored = new Stored(null, pinned, digest, bytes, game,
                    record, origins, quarantines, Integrity.UNKNOWN_SCHEMA);
            if (metadataBytes(stored) > limits.maxMetadataBytes()
                    || loaded.putIfAbsent(ref, stored) != null)
                throw RepositoryCodec.invalid("MANIFEST_LIMIT");
        }
        Map<CapabilityId, Long> counts = new HashMap<>();
        loaded.keySet().forEach(ref -> counts.merge(ref.capability(), 1L, Long::sum));
        if (counts.values().stream().anyMatch(n -> n > limits.maxVariantsPerCapability()))
            throw RepositoryCodec.invalid("MANIFEST_LIMIT");
        return new Snapshot(revision, loaded, false, "OK");
    }

    private Map<String, Object> entryMap(ArtifactRef ref, Stored stored) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ref", RepositoryCodec.ref(ref));
        result.put("bodySha256", stored.bodySha256());
        result.put("bodyBytes", (long) stored.bodyBytes());
        result.put("gameTarget", stored.gameTarget());
        result.put("admission", stored.admission().status().name());
        result.put("evidence", evidence(stored.admission().evidence()));
        result.put("reason", stored.admission().reason() == null
                ? "" : stored.admission().reason().name());
        result.put("revision", stored.admission().revision());
        result.put("pinned", stored.pinned().stream().map(RepositoryCodec::ref).toList());
        result.put("origins", stored.origins().stream().map(this::originMap).toList());
        result.put("quarantines", stored.quarantines().stream().map(
                this::quarantineEventMap).toList());
        return result;
    }

    private int metadataBytes(Stored stored) {
        String encoded = StrictJson.canonical(entryMap(stored.admission().artifact(), stored));
        return encoded.getBytes(StandardCharsets.UTF_8).length;
    }

    private Map<String, Object> originMap(PrivateOrigin origin) {
        return Map.of("decisionId", origin.decisionId().toString(),
                "owner", Map.of("principal", origin.owner().principal().id().toString(),
                    "world", origin.owner().scope().worldId().toString(),
                    "domain", origin.owner().scope().domainId().toString()),
                "evidence", Map.of("static", evidence(origin.evidence().staticCheck()),
                    "fixture", evidence(origin.evidence().fixtureCheck()),
                    "trial", evidence(origin.evidence().liveTrial()),
                    "receipt", origin.evidence().receiptSha256()),
                "provenance", provenanceMap(origin.provenance()));
    }

    private PrivateOrigin readOrigin(Map<String, Object> map) throws StrictJson.Invalid {
        RepositoryCodec.keys(map, "decisionId", "owner", "evidence", "provenance");
        Map<String, Object> owner = RepositoryCodec.object(map.get("owner"));
        RepositoryCodec.keys(owner, "principal", "world", "domain");
        Map<String, Object> evidence = RepositoryCodec.object(map.get("evidence"));
        RepositoryCodec.keys(evidence, "static", "fixture", "trial", "receipt");
        try {
            TrustedContext context = new TrustedContext(
                    new PrincipalRef(UUID.fromString(RepositoryCodec.string(owner, "principal"))),
                    new ScopeRef(UUID.fromString(RepositoryCodec.string(owner, "world")),
                            UUID.fromString(RepositoryCodec.string(owner, "domain"))));
            EvidenceBundle bundle = new EvidenceBundle(
                    readEvidence(RepositoryCodec.object(evidence.get("static"))),
                    readEvidence(RepositoryCodec.object(evidence.get("fixture"))),
                    readEvidence(RepositoryCodec.object(evidence.get("trial"))),
                    RepositoryCodec.string(evidence, "receipt"));
            return new PrivateOrigin(UUID.fromString(RepositoryCodec.string(map, "decisionId")),
                    context, bundle, readProvenance(RepositoryCodec.object(map.get("provenance"))));
        } catch (IllegalArgumentException invalid) { throw RepositoryCodec.invalid("MANIFEST_STRUCTURE"); }
    }

    private Map<String, Object> quarantineEventMap(QuarantineEvent event) {
        Map<String, Object> owner = event.owner() == null ? Map.of() :
                Map.of("principal", event.owner().principal().id().toString(),
                        "world", event.owner().scope().worldId().toString(),
                        "domain", event.owner().scope().domainId().toString());
        return Map.of("decisionId", event.decisionId().toString(),
                "source", event.source(), "owner", owner,
                "evidence", evidence(event.evidence()), "reason", event.reason().name());
    }

    private QuarantineEvent readQuarantineEvent(Map<String, Object> map)
            throws StrictJson.Invalid {
        RepositoryCodec.keys(map, "decisionId", "source", "owner", "evidence", "reason");
        String source = RepositoryCodec.string(map, "source");
        if (!source.equals("repository:integrity") && !source.equals("research-controller"))
            throw RepositoryCodec.invalid("MANIFEST_STRUCTURE");
        Map<String, Object> owner = RepositoryCodec.object(map.get("owner"));
        TrustedContext context = null;
        try {
            if (source.equals("research-controller")) {
                RepositoryCodec.keys(owner, "principal", "world", "domain");
                context = new TrustedContext(
                        new PrincipalRef(UUID.fromString(RepositoryCodec.string(owner, "principal"))),
                        new ScopeRef(UUID.fromString(RepositoryCodec.string(owner, "world")),
                                UUID.fromString(RepositoryCodec.string(owner, "domain"))));
            } else if (!owner.isEmpty()) throw RepositoryCodec.invalid("MANIFEST_STRUCTURE");
            return new QuarantineEvent(UUID.fromString(RepositoryCodec.string(map, "decisionId")),
                    source, context, readEvidence(RepositoryCodec.object(map.get("evidence"))),
                    Reason.valueOf(RepositoryCodec.string(map, "reason")));
        } catch (IllegalArgumentException invalid) { throw RepositoryCodec.invalid("MANIFEST_STRUCTURE"); }
    }

    private static QuarantineEvent integrityEvent(ArtifactRef ref, long revision) {
        String key = "repository-integrity:" + ref.sha256() + ":" + revision;
        return new QuarantineEvent(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
                "repository:integrity", null,
                new EvidenceRef("sha256-" + ref.sha256().substring(0, 16), "repository-v1",
                        "world"), Reason.ARTIFACT_INVALID);
    }

    private static Map<String, Object> evidence(EvidenceRef evidence) {
        return Map.of("id", evidence.id(), "validator", evidence.validatorVersion(),
                "scope", evidence.scope());
    }

    private static EvidenceRef readEvidence(Map<String, Object> raw) throws StrictJson.Invalid {
        RepositoryCodec.keys(raw, "id", "validator", "scope");
        try { return new EvidenceRef(RepositoryCodec.string(raw, "id"),
                RepositoryCodec.string(raw, "validator"), RepositoryCodec.string(raw, "scope")); }
        catch (IllegalArgumentException invalid) { throw RepositoryCodec.invalid("MANIFEST_STRUCTURE"); }
    }

    private static Map<String, Object> provenanceMap(Provenance provenance) {
        return Map.of("generationContract", (long) provenance.generationContractVersion(),
                "model", provenance.modelDescriptor() == null ? "" : provenance.modelDescriptor(),
                "compiler", provenance.compilerVersion(), "runtime", provenance.runtimeFingerprint(),
                "originWorld", provenance.originWorld() == null
                    ? "" : provenance.originWorld().toString(),
                "actualDependencies", provenance.actualDependencies().stream()
                    .map(RepositoryCodec::ref).toList(),
                "evidence", provenance.evidence().stream().map(
                    VersionedSkillRepository::evidence).toList());
    }

    private static Provenance readProvenance(Map<String, Object> map) throws StrictJson.Invalid {
        RepositoryCodec.keys(map, "generationContract", "model", "compiler", "runtime",
                "originWorld", "actualDependencies", "evidence");
        List<ArtifactRef> dependencies = new ArrayList<>();
        for (Object raw : RepositoryCodec.array(map, "actualDependencies"))
            dependencies.add(RepositoryCodec.readRef(RepositoryCodec.object(raw)));
        List<EvidenceRef> evidence = new ArrayList<>();
        for (Object raw : RepositoryCodec.array(map, "evidence"))
            evidence.add(readEvidence(RepositoryCodec.object(raw)));
        try {
            String model = RepositoryCodec.string(map, "model");
            String origin = RepositoryCodec.string(map, "originWorld");
            return new Provenance(RepositoryCodec.positive(map, "generationContract"),
                    model.isEmpty() ? null : model,
                    RepositoryCodec.string(map, "compiler"),
                    RepositoryCodec.string(map, "runtime"),
                    origin.isEmpty() ? null : UUID.fromString(origin),
                    dependencies, evidence);
        } catch (IllegalArgumentException invalid) { throw RepositoryCodec.invalid("MANIFEST_STRUCTURE"); }
    }

    private void commit(String source, Map<ArtifactRef, Stored> changed, long revision)
            throws IOException {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_MANIFEST_BYTES) throw new IOException("Manifest exceeds schema cap");
        Path pending = writeStaged(bytes);
        faults.check(FaultPoint.AFTER_EVIDENCE_STAGE);
        if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
            byte[] prior;
            try { prior = readBounded(manifest, MAX_MANIFEST_BYTES)
                    .getBytes(StandardCharsets.UTF_8); }
            catch (StrictJson.Invalid invalid) {
                throw new IOException("Current manifest is unreadable", invalid);
            }
            writeAtomic(previous, prior);
        }
        faults.check(FaultPoint.BEFORE_MANIFEST_REPLACE);
        replacementAttempted = true;
        moveAtomic(pending, manifest);
        snapshot = new Snapshot(revision, changed, false, "OK");
        faults.check(FaultPoint.AFTER_MANIFEST_REPLACE);
    }

    private void writeImmutableBody(ArtifactRef ref, byte[] bytes) throws IOException {
        Path target = bodyPath(ref);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(target) || !java.util.Arrays.equals(
                    bytes, Files.readAllBytes(target))) throw new IOException("Body hash collision/corruption");
            return;
        }
        moveAtomic(writeStaged(bytes), target);
    }

    private Path bodyPath(ArtifactRef ref) { return bodies.resolve(ref.sha256() + ".json"); }

    private Path writeStaged(byte[] bytes) throws IOException {
        Path pending = staging.resolve(UUID.randomUUID() + ".part");
        try (FileChannel channel = FileChannel.open(pending,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        forceDirectory(staging);
        return pending;
    }

    private void writeAtomic(Path target, byte[] bytes) throws IOException {
        moveAtomic(writeStaged(bytes), target);
    }

    private static void moveAtomic(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        forceDirectory(target.getParent());
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private String readBounded(Path source, int cap) throws IOException, StrictJson.Invalid {
        if (Files.isSymbolicLink(source)) throw new IOException("Symlink in authoritative data");
        if (Files.size(source) > cap) throw RepositoryCodec.invalid("INPUT_LIMIT");
        return Files.readString(source, StandardCharsets.UTF_8);
    }

    private void cleanupStaging() throws IOException {
        int scanned = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(staging)) {
            for (Path item : files) {
                if (++scanned > limits.maxRecoveryFiles()) throw new IOException("Recovery scan limit");
                if (Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS)
                        && item.getFileName().toString().endsWith(".part"))
                    Files.delete(item);
            }
        }
    }

    private void preserveCorruptManifest() throws IOException {
        Path diagnostic = quarantine.resolve("manifest-" + UUID.randomUUID() + ".json");
        Files.copy(manifest, diagnostic);
        forceDirectory(quarantine);
    }

    private record Account(long bytes, int orphans) { }
    private Account accountFiles() throws IOException {
        long bytes = 0;
        int scanned = 0;
        int orphans = 0;
        for (Path directory : List.of(bodies, staging, quarantine)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
                for (Path file : files) {
                    if (++scanned > limits.maxRecoveryFiles())
                        throw new IOException("Recovery scan limit");
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("Unexpected storage entry");
                    bytes = Math.addExact(bytes, Files.size(file));
                    if (directory.equals(bodies)) {
                        String filename = file.getFileName().toString();
                        if (!filename.endsWith(".json") || filename.length() != 69)
                            throw new IOException("Unrecognized body file");
                        String sha = filename.substring(0, 64);
                        if (snapshot.records().keySet().stream().noneMatch(ref ->
                                ref.sha256().equals(sha))) orphans++;
                    }
                }
            }
        }
        if (Files.exists(manifest)) bytes = Math.addExact(bytes, Files.size(manifest));
        if (Files.exists(previous)) bytes = Math.addExact(bytes, Files.size(previous));
        return new Account(bytes, orphans);
    }

    private void refreshAccounting() throws IOException {
        Account account = accountFiles();
        accountedDiskBytes = account.bytes();
        orphanBodies = account.orphans();
    }
}
