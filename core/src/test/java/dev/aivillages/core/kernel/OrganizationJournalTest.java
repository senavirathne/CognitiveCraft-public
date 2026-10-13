package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Organizations.*;
import static org.junit.jupiter.api.Assertions.*;

class OrganizationJournalTest {
    @TempDir Path world;
    static final UUID WORLD = UUID.fromString("4e908a64-ab93-47a6-984a-34a3d8a80230");
    static final UUID ID = UUID.fromString("4e908a64-ab93-47a6-984a-34a3d8a80231");
    static final TrustedContext OWNER = new TrustedContext(new PrincipalRef(ID), new ScopeRef(WORLD, ID));
    static Snapshot sample(long revision, String name) {
        return new Snapshot(WORLD, revision, List.of(new Scope(ID, revision, OWNER, name, Kind.ORGANIZATION,
                1000, 0, false, PolicyRef.privateScope(), null, List.of(), List.of(),
                List.of(new Event(revision, revision == 1 ? EventKind.CREATED : EventKind.UPDATED, ID)))));
    }
    Path current() { return world.resolve(OrganizationJournal.WORLD_RELATIVE_PATH).resolve("state.jsonl"); }
    @Test void stableIdentityRenameAndExactReferencesSurviveReopen() throws Exception {
        Snapshot renamed = sample(2, "Explorers");
        try (var journal = OrganizationJournal.open(world, WORLD)) {
            assertEquals(Snapshot.empty(WORLD), journal.snapshot()); journal.replace(journal.snapshot(), sample(1, "Farmers"));
            journal.replace(journal.snapshot(), renamed);
        }
        byte[] before = Files.readAllBytes(current());
        try (var journal = OrganizationJournal.open(world, WORLD)) {
            assertFalse(journal.readOnly()); assertEquals(renamed, journal.snapshot());
            assertEquals(ID, journal.snapshot().scopes().getFirst().id());
        }
        assertArrayEquals(before, Files.readAllBytes(current()));
    }
    @Test void policyByteCostEqualsActualCanonicalPolicyEncoding() throws Exception {
        for (int n = 0; n <= 8; n++) {
            var named = new HashSet<TrustedContext>(); for (int i = 0; i < n; i++) named.add(JobLifecycleStoreTest.owner(WORLD));
            var policy = new PolicyRef(PolicyId.NAMED_SHARING, 1, named, named); Scope scope = sample(1, "Guild").scopes().getFirst();
            var changed = new Scope(scope.id(), 1, scope.owner(), scope.name(), scope.kind(), scope.createdAt(), 0, false,
                    policy, null, List.of(), List.of(), scope.history());
            String row = new String(OrganizationJournal.encode(new Snapshot(WORLD, 1, List.of(changed))), StandardCharsets.UTF_8).split("\n")[1];
            Object decoded = StrictJson.object(row).get("policy");
            assertEquals(StrictJson.canonical(decoded).getBytes(StandardCharsets.UTF_8).length, OrganizationJournal.policyBytes(policy));
        }
    }
    @ParameterizedTest @EnumSource(OrganizationJournal.Point.class)
    void interruptedPublicationPreservesOriginalOrCommittedNewDataWithoutActivatingStage(OrganizationJournal.Point point) throws Exception {
        Snapshot old = sample(1, "Farmers"), next = sample(2, "Explorers");
        try (var journal = OrganizationJournal.open(world, WORLD)) { journal.replace(journal.snapshot(), old); }
        byte[] original = Files.readAllBytes(current());
        try (var journal = OrganizationJournal.open(world, WORLD, at -> { if (at == point) throw new IOException("interrupted fixture"); })) {
            assertThrows(IOException.class, () -> journal.replace(old, next)); assertTrue(journal.readOnly()); assertEquals(old, journal.snapshot());
        }
        try (var reopened = OrganizationJournal.open(world, WORLD)) {
            if (point == OrganizationJournal.Point.AFTER_REPLACE) { assertEquals(next, reopened.snapshot()); assertFalse(reopened.readOnly()); }
            else { assertArrayEquals(original, Files.readAllBytes(current())); assertEquals(old, reopened.snapshot());
                assertEquals(point != OrganizationJournal.Point.BEFORE_STAGE, reopened.readOnly()); }
        }
        if (point != OrganizationJournal.Point.BEFORE_STAGE && point != OrganizationJournal.Point.AFTER_STAGE)
            assertArrayEquals(original, Files.readAllBytes(current().resolveSibling("state.prev.jsonl")));
    }
    @Test void unknownCurrentWithReadableBackupDoesNotRestoreOldPermissions() throws Exception {
        try (var journal = OrganizationJournal.open(world, WORLD)) {
            journal.replace(journal.snapshot(), sample(1, "Farmers")); journal.replace(journal.snapshot(), sample(2, "Explorers"));
        }
        byte[] future = new String(Files.readAllBytes(current()), StandardCharsets.UTF_8).replace("\"schema\":1", "\"schema\":99").getBytes(StandardCharsets.UTF_8);
        Files.write(current(), future);
        try (var journal = OrganizationJournal.open(world, WORLD)) {
            assertTrue(journal.readOnly()); assertTrue(journal.snapshot().scopes().isEmpty());
            assertThrows(IOException.class, () -> journal.replace(journal.snapshot(), sample(1, "Overwrite")));
        }
        assertArrayEquals(future, Files.readAllBytes(current())); assertTrue(Files.exists(current().resolveSibling("state.prev.jsonl")));
    }
    @Test void checksumFailureAndUnacknowledgedInitialStageRemainPreservedAndInactive() throws Exception {
        byte[] corrupted = new String(OrganizationJournal.encode(sample(1, "Farmers")), StandardCharsets.UTF_8).replace("Farmers", "Pirates").getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(current().getParent()); Files.write(current(), corrupted);
        try (var journal = OrganizationJournal.open(world, WORLD)) { assertTrue(journal.readOnly()); assertTrue(journal.snapshot().scopes().isEmpty()); }
        assertArrayEquals(corrupted, Files.readAllBytes(current()));
        Files.delete(current()); byte[] staged = OrganizationJournal.encode(sample(1, "Farmers"));
        Files.write(current().resolveSibling("state.next.jsonl"), staged);
        try (var journal = OrganizationJournal.open(world, WORLD)) { assertTrue(journal.readOnly()); assertTrue(journal.snapshot().scopes().isEmpty()); }
        assertArrayEquals(staged, Files.readAllBytes(current().resolveSibling("state.next.jsonl")));
    }
    @Test void malformedPolicyDuplicateFieldsUnknownFieldsAndExcessRowsAreRejected() throws Exception {
        byte[] valid = OrganizationJournal.encode(sample(1, "Guild")); String text = new String(valid, StandardCharsets.UTF_8);
        for (String changed : List.of(text.replace("\"id\":\"PRIVATE\"", "\"id\":\"run arbitrary policy\""),
                text.replace("\"version\":1", "\"version\":2"),
                text.replace("\"members\":[]", "\"members\":[],\"program\":[]"),
                text.replace("\"name\":\"Guild\"", "\"name\":\"Guild\",\"name\":\"Duplicate\""))) {
            byte[] bytes = checksum(changed); assertThrows(IOException.class, () -> OrganizationJournal.decode(bytes, WORLD));
        }
        assertThrows(IOException.class, () -> OrganizationJournal.decode(text.concat("{}\n".repeat(17)).getBytes(StandardCharsets.UTF_8), WORLD));
        assertThrows(IOException.class, () -> OrganizationJournal.decode(valid, UUID.randomUUID()));
    }
    @Test void byteUtf8TokenAndNumericLimitsAreStrictBeforeAdoption() throws Exception {
        assertThrows(IOException.class, () -> OrganizationJournal.decode(new byte[OrganizationJournal.MAX_BYTES + 1], WORLD));
        assertThrows(IOException.class, () -> OrganizationJournal.decode(new byte[]{(byte)0xc3, (byte)0x28}, WORLD));
        String text = new String(OrganizationJournal.encode(sample(1, "Guild")), StandardCharsets.UTF_8);
        for (String revision : List.of("-1", "1.5", "9223372036854775808", "-0")) {
            byte[] changed = checksum(text.replace("\"revision\":1", "\"revision\":" + revision));
            assertThrows(IOException.class, () -> OrganizationJournal.decode(changed, WORLD));
        }
        byte[] nested = checksum(text.replace("\"members\":[]", "\"members\":" + "[".repeat(34) + "0" + "]".repeat(34)));
        assertThrows(IOException.class, () -> OrganizationJournal.decode(nested, WORLD));
    }
    @Test void immutableOriginWorldAndLifetimeCannotBeRewrittenByStorageClient() throws Exception {
        Snapshot old = sample(1, "Guild"); Scope scope = old.scopes().getFirst();
        try (var journal = OrganizationJournal.open(world, WORLD)) {
            journal.replace(journal.snapshot(), old); byte[] original = Files.readAllBytes(current());
            var takeover = new Scope(ID, 2, JobLifecycleStoreTest.owner(WORLD), "Guild", Kind.ORGANIZATION, 1000, 0, false,
                    scope.policy(), null, List.of(), List.of(), List.of(new Event(2, EventKind.UPDATED, ID)));
            assertThrows(IOException.class, () -> journal.replace(old, new Snapshot(WORLD, 2, List.of(takeover))));
            assertThrows(IOException.class, () -> journal.replace(old, new Snapshot(WORLD, 2, List.of())));
            assertThrows(IOException.class, () -> journal.replace(Snapshot.empty(WORLD), sample(2, "Guild")));
            assertArrayEquals(original, Files.readAllBytes(current()));
        }
    }
    @Test void outsideFileChangeAndSymlinkReplacementAreFencedWithoutOverwritingData() throws Exception {
        try (var journal = OrganizationJournal.open(world, WORLD)) {
            journal.replace(journal.snapshot(), sample(1, "Guild")); byte[] foreign = "preserve unknown data".getBytes(StandardCharsets.UTF_8); Files.write(current(), foreign);
            assertThrows(IOException.class, () -> journal.replace(journal.snapshot(), sample(2, "Rename")));
            assertTrue(journal.readOnly()); assertArrayEquals(foreign, Files.readAllBytes(current()));
        }
        Path separate = world.resolve("outside.json"); Files.writeString(separate, "outside"); Files.delete(current());
        try (var journal = OrganizationJournal.open(world, WORLD)) {
            Files.createSymbolicLink(current(), separate);
            assertThrows(IOException.class, () -> journal.replace(journal.snapshot(), sample(1, "Guild")));
            assertEquals("outside", Files.readString(separate));
        }
    }
    @Test void worldQuotaRejectionCreatesNoStageAndLocksHaveOneWriter() throws Exception {
        try (var journal = OrganizationJournal.open(world, WORLD)) {
            assertThrows(IOException.class, () -> OrganizationJournal.open(world, WORLD));
            journal.capacity(bytes -> false);
            assertThrows(IOException.class, () -> journal.replace(journal.snapshot(), sample(1, "Guild")));
            assertEquals(Snapshot.empty(WORLD), journal.snapshot()); assertFalse(Files.exists(current()));
            assertFalse(Files.exists(current().resolveSibling("state.next.jsonl")));
        }
        try (var reopened = OrganizationJournal.open(world, WORLD)) { assertFalse(reopened.readOnly()); }
    }
    @Test void unexpectedStageAndBackupArePreservedWithoutTruncatingEither() throws Exception {
        for (String name : List.of("state.next.jsonl", "state.prev.jsonl")) {
            Path save = world.resolve(name.replace('.', '_'));
            try (var journal = OrganizationJournal.open(save, WORLD)) {
                Snapshot old = sample(1, "Guild"); journal.replace(journal.snapshot(), old);
                Path root = save.resolve(OrganizationJournal.WORLD_RELATIVE_PATH);
                Path foreign = root.resolve(name); byte[] unknown = "preserve future recovery data".getBytes(StandardCharsets.UTF_8);
                Files.write(foreign, unknown); byte[] committed = Files.readAllBytes(root.resolve("state.jsonl"));
                assertThrows(IOException.class, () -> journal.replace(old, sample(2, "Rename")));
                assertTrue(journal.readOnly()); assertEquals(old, journal.snapshot());
                assertArrayEquals(unknown, Files.readAllBytes(foreign));
                assertArrayEquals(committed, Files.readAllBytes(root.resolve("state.jsonl")));
            }
            try (var reopened = OrganizationJournal.open(save, WORLD)) {
                assertTrue(reopened.readOnly());
                assertThrows(IOException.class, () -> reopened.replace(reopened.snapshot(), sample(2, "Overwrite")));
            }
        }
    }
    @Test void closedWriterCannotPublishAfterLockHandoff() throws Exception {
        Snapshot old = sample(1, "Guild"), next = sample(2, "Rename");
        var retired = OrganizationJournal.open(world, WORLD);
        try { retired.replace(retired.snapshot(), old); } finally { retired.close(); }
        byte[] committed = Files.readAllBytes(current());
        try (var successor = OrganizationJournal.open(world, WORLD)) {
            assertTrue(retired.readOnly());
            assertThrows(IOException.class, () -> retired.replace(old, next));
            assertArrayEquals(committed, Files.readAllBytes(current()));
            assertEquals(old, successor.snapshot()); successor.replace(old, next);
            assertEquals(next, successor.snapshot());
        }
        retired.close();
        try (var reopened = OrganizationJournal.open(world, WORLD)) { assertEquals(next, reopened.snapshot()); }
    }
    static Stream<Arguments> driftWindows() {
        return Stream.of(OrganizationJournal.Point.AFTER_STAGE, OrganizationJournal.Point.AFTER_BACKUP,
                OrganizationJournal.Point.BEFORE_REPLACE).flatMap(point ->
                Stream.of("state.jsonl", "state.prev.jsonl", "state.next.jsonl").map(name -> Arguments.of(point, name)));
    }
    @ParameterizedTest @MethodSource("driftWindows")
    void driftAfterStagingCannotReplaceCommittedOrRecoveryBytes(OrganizationJournal.Point window, String name) throws Exception {
        Snapshot old = sample(1, "Guild");
        try (var journal = OrganizationJournal.open(world, WORLD)) { journal.replace(journal.snapshot(), old); }
        Path foreign = current().resolveSibling(name); byte[] unknown = "preserve concurrent recovery data".getBytes(StandardCharsets.UTF_8);
        byte[] committed = Files.readAllBytes(current());
        try (var journal = OrganizationJournal.open(world, WORLD, point -> {
            if (point == window) Files.write(foreign, unknown);
        })) {
            assertThrows(IOException.class, () -> journal.replace(old, sample(2, "Rename")));
            assertTrue(journal.readOnly()); assertEquals(old, journal.snapshot());
            assertArrayEquals(unknown, Files.readAllBytes(foreign));
            if (!name.equals("state.jsonl")) assertArrayEquals(committed, Files.readAllBytes(current()));
        }
        try (var reopened = OrganizationJournal.open(world, WORLD)) { assertTrue(reopened.readOnly()); }
        assertArrayEquals(unknown, Files.readAllBytes(foreign));
    }
    @Test void compatibilityReaderAddsOnlyExplicitOrganizationSchemaAndNoInventedMigration() {
        var registry = SaveCompatibility.registered();
        assertTrue(registry.readable(new SaveCompatibility.Format(Organizations.DOMAIN, 1)));
        assertFalse(registry.readable(new SaveCompatibility.Format(Organizations.DOMAIN, 2)));
        assertTrue(registry.migration(new SaveCompatibility.Format(Organizations.DOMAIN, 1)).isEmpty());
        assertEquals(2, registry.rules().size());
    }
    @Test void retentionAccountsScopeHistoryAndAllRecoveryEnvelopesWithoutDeletingReferences() throws Exception {
        try (var journal = OrganizationJournal.open(world, WORLD)) {
            journal.replace(journal.snapshot(), sample(1, "Guild")); journal.replace(journal.snapshot(), sample(2, "Rename"));
            var roots = new RetentionRoots(); var manager = new WorldRetentionManager(WorldRetentionManager.Policy.defaults(), roots, c -> true);
            var owner = journal.retentionOwner(); manager.register(owner);
            assertEquals(RetentionRoots.Update.APPLIED, roots.publishNext("organizations", Set.of(OrganizationRegistry.key(ID)), true));
            var entries = new ArrayList<WorldRetentionManager.Entry>(); int steps = 0;
            try (var cursor = owner.open()) {
                boolean done = false; while (!done && steps++ < 16) {
                    var page = cursor.next(1, 65_536); assertTrue(page.inspected() <= 1); assertTrue(page.valid()); entries.addAll(page.entries()); done = page.complete();
                }
                assertTrue(done);
            }
            long files = Files.size(current()) + Files.size(current().resolveSibling("state.prev.jsonl"));
            assertEquals(files, entries.stream().mapToLong(e -> e.usage().bytes()).sum());
            assertEquals(2, entries.stream().mapToLong(e -> e.usage().records()).sum());
            assertTrue(entries.stream().allMatch(WorldRetentionManager.Entry::protectedRecord));
            assertEquals(WorldRetentionManager.OwnerStatus.PROTECTED, owner.collect(entries.getFirst(), 2, () -> false).status());
            var scan = manager.begin(); for (int i = 0; i < 50 && scan.state() != WorldRetentionManager.State.READY; i++) scan.step(() -> false);
            assertEquals(WorldRetentionManager.State.READY, scan.state()); scan.close();
            journal.replace(journal.snapshot(), sample(3, "Another name"));
            assertTrue(Files.size(current()) + Files.size(current().resolveSibling("state.prev.jsonl")) <= 3L * OrganizationJournal.MAX_BYTES);
        }
    }
    static byte[] checksum(String text) throws Exception {
        int at = text.indexOf('\n'); String payload = text.substring(at + 1);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        // Header may intentionally contain an invalid numeric field, so replace the bounded digest text only.
        String header = text.substring(0, at).replaceAll("(\"sha256\":\")[0-9a-f]{64}(\")", "$1" + hash + "$2");
        return (header + "\n" + payload).getBytes(StandardCharsets.UTF_8);
    }
}
