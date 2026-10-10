package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import dev.aivillages.core.kernel.WorldRetentionManager.Scan;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.WorldRetentionManager.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.ArtifactCompatibilityTest.*;

final class WorldRetentionManagerTest {
    @TempDir Path world;
    static final class MemoryOwner implements Owner {
        final Map<Key, Entry> rows = new LinkedHashMap<>(); long revision;
        java.util.function.Consumer<Entry> before = entry -> { };
        @Override public String id() { return "fixture"; }
        @Override public long revision() { return revision; }
        @Override public Cursor open() {
            var snapshot = List.copyOf(rows.values()); long captured = revision;
            return new Cursor() {
                int at;
                @Override public long revision() { return captured; }
                @Override public Page next(int maximum, int bytes) {
                    var page = snapshot.subList(at, Math.min(at + maximum, snapshot.size())); at += page.size();
                    return new Page(page, at == snapshot.size(), true, page.size(), 0);
                }
            };
        }
        @Override public OwnerResult collect(Entry entry, long expected, java.util.function.BooleanSupplier cancel) {
            before.accept(entry);
            if (cancel.getAsBoolean()) return OwnerResult.of(OwnerStatus.CANCELLED);
            if (revision != expected) return OwnerResult.of(OwnerStatus.STALE);
            rows.remove(entry.key()); revision++; return OwnerResult.of(OwnerStatus.COLLECTED);
        }
        Entry put(String id, boolean protectedRecord, Key... dependencies) {
            var entry = new Entry(new Key(id(), id), Category.ARTIFACTS, new Amount(10, 1), List.of(dependencies),
                    protectedRecord, true, Set.of(OWNER), "fixture"); rows.put(entry.key(), entry); return entry;
        }
    }
    static WorldRetentionManager manager(RetentionRoots roots, Owner... owners) {
        var manager = new WorldRetentionManager(Policy.defaults(), roots, caller -> caller.equals(OWNER));
        for (var owner : owners) manager.register(owner); return manager;
    }
    static Scan ready(WorldRetentionManager manager) {
        Scan scan = manager.begin();
        for (int at = 0; at < 100000 && scan.state() != State.READY && scan.state() != State.INVALID; at++) {
            Work work = scan.step(() -> false); assertTrue(work.inspected() <= 16); assertTrue(work.readBytes() <= 131072);
        }
        assertEquals(State.READY, scan.state()); return scan;
    }
    static Policy quota(long categoryBytes, long categoryCount, long totalBytes, long totalCount) {
        var categories = new EnumMap<Category, Quota>(Category.class);
        for (Category c : Category.values()) categories.put(c, new Quota(categoryBytes, categoryCount));
        return new Policy(categories, new Quota(totalBytes, totalCount), 16, 8192, 65536, 16, 131072, 16);
    }
    @Test void sharedJobsResearchRunsRollbackAndLaterAccessRootsRetainExactClosure() {
        var owner = new MemoryOwner(); var child = owner.put("child", false); var parent = owner.put("parent", false, child.key());
        var scratch = owner.put("scratch", false); var alternative = owner.put("alternative", true);
        var roots = new RetentionRoots();
        for (String domain : List.of("jobs", "research", "runs", "rollback", "access"))
            assertEquals(RetentionRoots.Update.APPLIED, roots.publishNext(domain, Set.of(parent.key()), true));
        var manager = manager(roots, owner);
        try (var scan = ready(manager)) {
            assertEquals(1, scan.collect(() -> false).collected());
            assertFalse(owner.rows.containsKey(scratch.key()));
            assertEquals(Set.of(child.key(), parent.key(), alternative.key()), owner.rows.keySet());
        }
    }
    @Test void newPinAfterCompletedScanInvalidatesDeletionProposal() {
        var owner = new MemoryOwner(); var entry = owner.put("old", false); var roots = new RetentionRoots();
        try (var scan = ready(manager(roots, owner))) {
            assertEquals(RetentionRoots.Update.APPLIED, roots.publishNext("later_access", Set.of(entry.key()), true));
            assertEquals(1, scan.collect(() -> false).stale()); assertTrue(owner.rows.containsKey(entry.key()));
        }
    }
    @Test void referenceAcquisitionDuringOwnerDeletionReturnsBusyWithoutWaitingAndCannotBeUsed() {
        var owner = new MemoryOwner(); var entry = owner.put("scratch", false); var roots = new RetentionRoots();
        owner.before = ignored -> assertEquals(RetentionRoots.Update.BUSY, roots.publishNext("jobs", Set.of(entry.key()), true));
        try (var scan = ready(manager(roots, owner))) { assertEquals(1, scan.collect(() -> false).collected()); }
        assertEquals(RetentionRoots.Update.APPLIED, roots.publishNext("jobs", Set.of(), true));
    }
    @Test void staleOrIncompleteRootSourceFailsClosedAndDoesNotMutateRecords() {
        var owner = new MemoryOwner(); owner.put("scratch", false); var roots = new RetentionRoots();
        assertEquals(RetentionRoots.Update.APPLIED, roots.publish("jobs", new RetentionRoots.Source(2, Set.of(), false)));
        assertEquals(RetentionRoots.Update.STALE, roots.publish("jobs", new RetentionRoots.Source(1, Set.of(), true)));
        try (var scan = manager(roots, owner).begin()) {
            assertEquals(State.INVALID, scan.step(() -> false).state()); assertEquals(0, scan.collect(() -> false).attempted());
        }
        assertEquals(1, owner.rows.size());
    }
    @Test void changedDomainRevisionInvalidatesPlanEvenWhenTheTargetOwnerDidNotChange() {
        var owner = new MemoryOwner(); owner.put("scratch", false); var roots = new RetentionRoots();
        try (var scan = ready(manager(roots, owner))) { owner.revision++; assertEquals(1, scan.collect(() -> false).stale()); }
        assertEquals(1, owner.rows.size());
    }
    @Test void quotasAreExactAtZeroBoundaryCountBoundaryAndTotalBoundary() {
        var owner = new MemoryOwner(); owner.put("kept", true);
        var manager = new WorldRetentionManager(quota(10, 1, 10, 1), new RetentionRoots(), c -> false); manager.register(owner);
        try (var scan = ready(manager)) {
            assertEquals(Capacity.AVAILABLE, scan.assessReplacement("fixture", Category.ARTIFACTS, new Amount(10, 1)).status());
            assertEquals(Capacity.STORAGE_LIMIT_REACHED, scan.assessReplacement("fixture", Category.ARTIFACTS, new Amount(11, 1)).status());
            assertEquals(Capacity.STORAGE_LIMIT_REACHED, scan.assessReplacement("fixture", Category.ARTIFACTS, new Amount(10, 2)).status());
        }
        var total = new WorldRetentionManager(quota(100, 100, 9, 100), new RetentionRoots(), c -> false); total.register(owner);
        assertTrue(total.assessReplacement("fixture", Category.ARTIFACTS, new Amount(10, 1)).totalQuota());
        var zero = new WorldRetentionManager(quota(0, 0, 0, 0), new RetentionRoots(), c -> false); zero.register(owner);
        assertEquals(Capacity.AVAILABLE, zero.assessReplacement("fixture", Category.ARTIFACTS, new Amount(0, 0)).status());
        assertEquals(Capacity.STORAGE_LIMIT_REACHED, zero.assessReplacement("fixture", Category.ARTIFACTS, new Amount(1, 0)).status());
        assertThrows(IllegalArgumentException.class, () -> new Quota(-1, 0));
    }
    @Test void arithmeticOverflowCannotClaimCapacity() {
        var owner = new MemoryOwner(); owner.put("a", true);
        owner.rows.replaceAll((key, entry) -> new Entry(key, Category.ARTIFACTS, new Amount(Long.MAX_VALUE, 1), List.of(), true, false, Set.of(), ""));
        owner.put("b", true);
        assertEquals(Capacity.STORAGE_UNAVAILABLE, manager(new RetentionRoots(), owner).assessReplacement("fixture", Category.ARTIFACTS, new Amount(0, 0)).status());
    }
    @Test void independentDiagnosticWriterReservesItsCeilingInTotalAdmission() throws Exception {
        var owner = new MemoryOwner(); owner.put("admitted", true);
        var external = new RetentionFileOwner("diagnostics", world.resolve("diagnostics"), Category.DIAGNOSTICS, () -> 0, () -> 0, 16, 100);
        var manager = new WorldRetentionManager(quota(1000, 1000, 109, 1000), new RetentionRoots(), c -> false);
        manager.register(owner); manager.register(external);
        var result = manager.assessReplacement("fixture", Category.ARTIFACTS, new Amount(10, 1));
        assertEquals(Capacity.STORAGE_LIMIT_REACHED, result.status()); assertTrue(result.totalQuota()); assertEquals(110, result.projected().bytes());
    }
    @Test void graphWith4096NodesAndSharedDependenciesUsesFiniteSlicesAndNoRecursiveStack() throws Exception {
        var owner = new MemoryOwner(); Key child = null;
        for (int at = 0; at < 4096; at++) child = child == null ? owner.put("n" + at, false).key() : owner.put("n" + at, false, child).key();
        var roots = new RetentionRoots(); roots.publishNext("jobs", Set.of(child), true);
        int slices = 0, peak = 0;
        try (var scan = manager(roots, owner).begin()) {
            while (scan.state() != State.READY && scan.state() != State.INVALID && slices < 10000) {
                var work = scan.step(() -> false); peak = Math.max(peak, work.inspected()); slices++;
            }
            assertEquals(State.READY, scan.state()); assertEquals(0, scan.collect(() -> false).attempted()); assertEquals(4096, owner.rows.size());
        }
        assertTrue(peak <= 16); assertTrue(slices < 2000);
        Path output = Path.of("build/retention-evidence"); Files.createDirectories(output);
        Files.writeString(output.resolve("graph.json"), StrictJson.canonical(Map.of("nodes", 4096L, "slices", (long) slices, "maxInspected", (long) peak, "collected", 0L)));
    }
    @Test void unrootedCyclesAndCorruptDependencyEdgesAreDiagnosedBeforeAnyCollection() {
        var owner = new MemoryOwner(); owner.put("a", false, new Key("fixture", "b")); owner.put("b", false, new Key("fixture", "a"));
        try (var scan = manager(new RetentionRoots(), owner).begin()) {
            Work last = null; for (int at = 0; at < 20 && scan.state() != State.INVALID; at++) last = scan.step(() -> false);
            assertEquals(State.INVALID, scan.state()); assertTrue(last.diagnostics().contains(Diagnostic.CYCLE)); assertEquals(0, scan.collect(() -> false).attempted());
        }
        owner.rows.remove(new Key("fixture", "b"));
        try (var scan = manager(new RetentionRoots(), owner).begin()) {
            Work last = null; for (int at = 0; at < 20 && scan.state() != State.INVALID; at++) last = scan.step(() -> false);
            assertTrue(last.diagnostics().contains(Diagnostic.MISSING_DEPENDENCY)); assertEquals(1, owner.rows.size());
        }
    }
    @Test void nodeCapInvalidatesSnapshotWithoutGrowingPastItsMemoryCeiling() {
        var owner = new MemoryOwner(); owner.put("a", false); owner.put("b", false);
        var base = Policy.defaults(); var manager = new WorldRetentionManager(new Policy(base.categories(), base.total(), 1, 1, 32, 1, 65536, 0), new RetentionRoots(), c -> false);
        manager.register(owner);
        try (var scan = manager.begin()) {
            Work last = null; for (int at = 0; at < 5 && scan.state() != State.INVALID; at++) last = scan.step(() -> false);
            assertEquals(State.INVALID, scan.state()); assertEquals(0, last.diagnostics().size()); assertTrue(last.omittedDiagnostics() > 0);
        }
        assertEquals(2, owner.rows.size());
    }
    @Test void cancellationStopsCollectionAndScopedInspectionReturnsOnlyPermittedUsage() {
        var owner = new MemoryOwner(); owner.put("private", false); var roots = new RetentionRoots();
        var foreign = new TrustedContext(new PrincipalRef(UUID.randomUUID()), OWNER.scope());
        try (var scan = ready(manager(roots, owner))) {
            assertEquals(new Amount(10, 1), scan.privateUsage(OWNER)); assertEquals(new Amount(0, 0), scan.privateUsage(foreign));
            assertThrows(SecurityException.class, () -> scan.aggregateUsage(foreign));
            assertEquals(1, scan.collect(() -> true).cancelled()); assertEquals(1, owner.rows.size());
        }
    }
    @Test void laterOwnerRegistrationDoesNotAlterArtifactIdentityOrAdmission() throws Exception {
        var artifact = literal(7).skill().artifact();
        try (var repository = store(world)) {
            admit(repository, artifact); var before = repository.resolve(artifact.descriptor().ref()).admission();
            var roots = new RetentionRoots(); var manager = manager(roots, repository.retentionOwner());
            roots.publishNext("access", Set.of(WorldRetentionManager.artifact(artifact.descriptor().ref())), true);
            try (var scan = ready(manager)) { assertEquals(0, scan.collect(() -> false).attempted()); }
            assertEquals(before, repository.resolve(artifact.descriptor().ref()).admission()); assertTrue(repository.resolve(artifact.descriptor().ref()).usable());
        }
    }
    @Test void realRepositoryAdmittedAlternativesAndDependencyClosureSurviveWhileOrphansCollect() throws Exception {
        var child = literal(4).skill().artifact(); var parent = parent(child); var alternative = literal(9).skill().artifact();
        AtomicBoolean fault = new AtomicBoolean();
        try (var repository = VersionedSkillRepository.open(world, runtime(), VersionedSkillRepository.Limits.defaults(), d -> true,
                TrustedContext::equals, point -> { if (point == VersionedSkillRepository.FaultPoint.AFTER_BODY_WRITE && fault.get()) throw new java.io.IOException("Synthetic interruption"); })) {
            admit(repository, child); admit(repository, parent); admit(repository, alternative);
            var discarded = literal(11).skill().artifact(); fault.set(true);
            var bundle = new VersionedSkillRepository.EvidenceBundle(EVIDENCE, EVIDENCE, EVIDENCE, "b".repeat(64));
            assertEquals(VersionedSkillRepository.PublishStatus.STORAGE_UNAVAILABLE, repository.publish(discarded,
                    new VersionedSkillRepository.AdmissionDecision(UUID.randomUUID(), OWNER, bundle), new Provenance(1, null, "compiler-1", "minecraft-26.3", OWNER.scope().worldId(), List.of(), List.of(EVIDENCE))).status());
            var roots = new RetentionRoots(); roots.publishNext("jobs", Set.of(WorldRetentionManager.artifact(parent.descriptor().ref())), true);
            var before = hashes(world); var manager = manager(roots, repository.retentionOwner());
            try (var scan = ready(manager)) { assertEquals(1, scan.collect(() -> false).collected()); }
            var after = hashes(world); var removed = new HashSet<>(before.keySet()); removed.removeAll(after.keySet());
            assertEquals(Set.of(VersionedSkillRepository.WORLD_RELATIVE_PATH + "/bodies/" + discarded.descriptor().ref().sha256() + ".json"), removed);
            for (var kept : List.of(child, parent, alternative)) assertTrue(repository.resolve(kept.descriptor().ref()).usable());
            assertEquals(0, repository.status().orphanBodies());
        }
        try (var reopened = store(world)) { for (var kept : List.of(child, parent, alternative)) assertTrue(reopened.resolve(kept.descriptor().ref()).usable()); }
    }
    @Test void productionAdmissionGateRejectsNewKnowledgeWithNoExistingBodyMutation() throws Exception {
        try (var repository = store(world)) {
            var kept = literal(1).skill().artifact(); admit(repository, kept); var before = hashes(world);
            var manager = new WorldRetentionManager(quota(0, 100, Long.MAX_VALUE, 1000), new RetentionRoots(), c -> false); manager.register(repository.retentionOwner());
            repository.admissionCapacity((bytes, records) -> manager.assessReplacement("skills", Category.ARTIFACTS, new Amount(bytes, records)).status());
            var bundle = new VersionedSkillRepository.EvidenceBundle(EVIDENCE, EVIDENCE, EVIDENCE, "b".repeat(64)); var next = literal(2).skill().artifact();
            assertEquals(VersionedSkillRepository.PublishStatus.STORAGE_LIMIT_REACHED, repository.publish(next,
                    new VersionedSkillRepository.AdmissionDecision(UUID.randomUUID(), OWNER, bundle), new Provenance(1, null, "compiler-1", "minecraft-26.3", OWNER.scope().worldId(), List.of(), List.of(EVIDENCE))).status());
            assertEquals(before, hashes(world)); assertTrue(repository.resolve(kept.descriptor().ref()).usable());
        }
    }
    @Test void supportedPreviousManifestProtectsBodiesEvenWhenCurrentSnapshotHasNoRecords() throws Exception {
        var child = literal(3).skill().artifact(); var parent = parent(child);
        try (var repository = store(world)) { admit(repository, child); admit(repository, parent); }
        Path root = world.resolve(VersionedSkillRepository.WORLD_RELATIVE_PATH);
        Files.copy(root.resolve("manifest.json"), root.resolve("manifest.previous.json"), StandardCopyOption.REPLACE_EXISTING);
        // Synthetic supported restore fixture, not an admission/retraction or migration operation.
        var payload = Map.of("schema", 1L, "revision", 3L, "entries", List.of()); var envelope = new LinkedHashMap<String,Object>(payload);
        envelope.put("checksum", RepositoryCodec.digest(StrictJson.canonical(payload))); Files.writeString(root.resolve("manifest.json"), StrictJson.canonical(envelope));
        try (var repository = store(world); var scan = ready(manager(new RetentionRoots(), repository.retentionOwner()))) {
            assertEquals(0, scan.collect(() -> false).attempted()); assertEquals(2, repository.status().orphanBodies());
            assertTrue(Files.exists(root.resolve("bodies/" + child.descriptor().ref().sha256() + ".json")));
        }
    }
}
