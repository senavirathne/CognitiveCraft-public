package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.RetentionEvidenceStore.*;
import static dev.aivillages.core.kernel.ArtifactCompatibilityTest.*;

final class RetentionEvidenceStoreTest {
    @TempDir Path world;
    static final class MutableClock extends Clock {
        long now = 100;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
    }
    static Event event(long id, String premise, long at) {
        return new Event(new UUID(0, id), Source.TASK, OWNER, ID, null, Outcomes.Reason.RESOURCE_MISSING, premise, at, 0, 0);
    }
    static RetentionEvidenceStore open(Path world, MutableClock clock, Faults faults) throws Exception {
        return RetentionEvidenceStore.open(world, OWNER.scope().worldId(), Limits.defaults(), clock, faults);
    }
    static long bytes(Path root) throws Exception {
        if (!Files.exists(root)) return 0;
        try (var files = Files.list(root)) { long total = 0; for (Path path : files.toList()) total += Files.size(path); return total; }
    }
    @Test void tenThousandEquivalentShortagesHaveExactCountFiniteSamplesAndPersistAcrossRestart() throws Exception {
        var clock = new MutableClock(); long peak = 0; int groupCount;
        try (var store = open(world, clock, Faults.none())) {
            for (int at = 0; at < 10000; at += 16) {
                var batch = new ArrayList<Event>(); for (int n = at; n < Math.min(10000, at + 16); n++) batch.add(event(n + 1, "a".repeat(64), clock.now));
                assertEquals(Status.RECORDED, store.recordBatch(batch, () -> false).status());
                peak = Math.max(peak, bytes(world.resolve(WORLD_RELATIVE_PATH)));
            }
            var aggregate = store.failures(OWNER).getFirst();
            assertEquals(10000, aggregate.count()); assertEquals(3, aggregate.samples().size());
            assertEquals(1, aggregate.samples().getFirst().id().getLeastSignificantBits());
            assertEquals(10000, aggregate.samples().getLast().id().getLeastSignificantBits());
            assertEquals(16, store.recent(OWNER).size()); groupCount = store.snapshot().aggregates().size(); assertEquals(1, groupCount);
        }
        try (var reopened = open(world, clock, Faults.none())) {
            assertEquals(10000, reopened.failures(OWNER).getFirst().count()); assertEquals(16, reopened.recent(OWNER).size());
        }
        assertTrue(peak <= 2L * MAX_BYTES);
        Path output = Path.of("build/retention-evidence"); Files.createDirectories(output);
        Files.writeString(output.resolve("failures.json"), StrictJson.canonical(Map.of("events", 10000L, "groups", (long) groupCount,
                "samples", 3L, "recent", 16L, "maxDirectoryBytes", peak, "directoryCeiling", 3L * MAX_BYTES, "modelCalls", 0L)));
    }
    @Test void manyDifferentConditionsRemainBoundedAndDoNotMergeDifferentPremises() throws Exception {
        var clock = new MutableClock();
        try (var store = open(world, clock, Faults.none())) {
            for (int at = 0; at < 1024; at += 16) {
                var batch = new ArrayList<Event>();
                for (int n = at; n < at + 16; n++) batch.add(event(n + 1, String.format("%064x", n), clock.now));
                assertEquals(Status.RECORDED, store.recordBatch(batch, () -> false).status());
            }
            assertEquals(32, store.failures(OWNER).size()); assertEquals(16, store.recent(OWNER).size()); assertEquals(992, store.snapshot().omitted());
            assertTrue(store.failures(OWNER).stream().allMatch(a -> a.count() == 1)); assertTrue(bytes(world.resolve(WORLD_RELATIVE_PATH)) <= 2L * MAX_BYTES);
        }
    }
    @Test void changedEnvironmentalConditionOrExpiredPremiseNeverBecomesPermanentSkillDegradation() throws Exception {
        var clock = new MutableClock();
        try (var store = open(world, clock, Faults.none())) {
            store.record(event(1, "a".repeat(64), clock.now), () -> false); var aggregate = store.failures(OWNER).getFirst();
            assertTrue(aggregate.premiseApplies("a".repeat(64), 100)); assertFalse(aggregate.premiseApplies("b".repeat(64), 100));
            assertFalse(aggregate.premiseApplies("a".repeat(64), 30100)); assertFalse(aggregate.premiseApplies("a".repeat(64), 99));
            store.record(event(2, "b".repeat(64), clock.now), () -> false); assertEquals(2, store.failures(OWNER).size());
        }
        assertFalse(Files.exists(world.resolve(VersionedSkillRepository.WORLD_RELATIVE_PATH)));
    }
    @Test void privateQueriesAndPersistedShapeContainNoRawConversationsOrRequestPayload() throws Exception {
        var clock = new MutableClock(); var foreign = new Contracts.TrustedContext(new Contracts.PrincipalRef(UUID.randomUUID()), OWNER.scope());
        try (var store = open(world, clock, Faults.none())) {
            store.record(event(1, "c".repeat(64), clock.now), () -> false);
            assertEquals(List.of(), store.failures(foreign)); assertEquals(List.of(), store.recent(foreign));
            String body = Files.readString(world.resolve(WORLD_RELATIVE_PATH).resolve("state.json"));
            for (String prohibited : List.of("prompt", "conversation", "transcript", "canonicalIr", "exception", "arguments")) assertFalse(body.contains(prohibited));
        }
    }
    @Test void aggregationSeparatesScopesReasonsAndExactArtifacts() throws Exception {
        var clock = new MutableClock(); var artifact = literal(5).skill().artifact().descriptor().ref();
        var foreign = new Contracts.TrustedContext(new Contracts.PrincipalRef(UUID.randomUUID()), OWNER.scope());
        try (var store = open(world, clock, Faults.none())) {
            store.recordBatch(List.of(event(1, "d".repeat(64), clock.now), new Event(new UUID(0,2), Source.RESEARCH, OWNER, ID, artifact,
                    Outcomes.Reason.RESOURCE_MISSING, "d".repeat(64), clock.now, 0, 0), new Event(new UUID(0,3), Source.TASK, foreign, ID, null,
                    Outcomes.Reason.RESOURCE_MISSING, "d".repeat(64), clock.now, 0, 0)), () -> false);
            assertEquals(3, store.snapshot().aggregates().size()); assertEquals(2, store.failures(OWNER).size()); assertEquals(1, store.failures(foreign).size());
        }
    }
    @Test void successesRemainFiniteRecentSummariesAndAreNeverEncodedAsFailures() throws Exception {
        var clock = new MutableClock();
        try (var store = open(world, clock, Faults.none())) {
            var success = new Event(new UUID(0,1), Source.TASK, OWNER, ID, null, null, "e".repeat(64), clock.now, 3, 0);
            assertEquals(Status.RECORDED, store.record(success, () -> false).status()); assertEquals(0, store.failures(OWNER).size());
            assertFalse(Files.readString(world.resolve(WORLD_RELATIVE_PATH).resolve("state.json")).contains("ACTION_FAILED"));
        }
        try (var reopened = open(world, clock, Faults.none())) { assertNull(reopened.recent(OWNER).getFirst().reason()); assertEquals(3, reopened.recent(OWNER).getFirst().effects()); }
    }
    @Test void futureCorruptOversizedAndForeignWorldEvidenceArePreservedAndFenced() throws Exception {
        var clock = new MutableClock();
        for (String text : List.of("{\"schema\":999}", "invalid", "x".repeat(MAX_BYTES + 1))) {
            Path root = world.resolve(UUID.randomUUID().toString()); Path file = root.resolve(WORLD_RELATIVE_PATH).resolve("state.json"); Files.createDirectories(file.getParent()); Files.writeString(file, text);
            try (var store = open(root, clock, Faults.none())) { assertTrue(store.readOnly()); assertEquals(Status.STORAGE_UNAVAILABLE, store.record(event(1,"a".repeat(64),clock.now), () -> false).status()); }
            assertEquals(text, Files.readString(file));
        }
        try (var store = open(world, clock, Faults.none())) { store.record(event(1,"a".repeat(64),clock.now), () -> false); }
        Path file = world.resolve(WORLD_RELATIVE_PATH).resolve("state.json"); byte[] before = Files.readAllBytes(file);
        try (var other = RetentionEvidenceStore.open(world, UUID.randomUUID(), Limits.defaults(), clock, Faults.none())) { assertTrue(other.readOnly()); }
        assertArrayEquals(before, Files.readAllBytes(file));
    }
    @Test void unknownCurrentCannotBeReplacedByAnOlderValidBackup() throws Exception {
        var clock = new MutableClock();
        try (var store = open(world, clock, Faults.none())) {
            store.record(event(1,"a".repeat(64),clock.now), () -> false); store.record(event(2,"a".repeat(64),clock.now), () -> false);
        }
        Path file = world.resolve(WORLD_RELATIVE_PATH).resolve("state.json"); Files.writeString(file, "{\"schema\":999}");
        try (var reopened = open(world, clock, Faults.none())) { assertTrue(reopened.readOnly()); assertEquals(1, reopened.failures(OWNER).getFirst().count()); }
        assertEquals("{\"schema\":999}", Files.readString(file));
    }
    @ParameterizedTest @EnumSource(Point.class)
    void faultsAtEveryCompactionBoundarySelectValidOriginalOrNewVersionAfterRestart(Point point) throws Exception {
        var clock = new MutableClock(); AtomicBoolean armed = new AtomicBoolean(); AtomicLong peak = new AtomicLong();
        try (var store = open(world, clock, p -> {
            if (armed.get()) { try { peak.accumulateAndGet(bytes(world.resolve(WORLD_RELATIVE_PATH)), Math::max); } catch (Exception e) { throw new java.io.IOException(e); }
                if (p == point) throw new java.io.IOException("Synthetic crash boundary"); }
        })) {
            store.record(event(1,"a".repeat(64),clock.now), () -> false); clock.now += Limits.defaults().retentionMillis() + 1; armed.set(true);
            var result = store.compact(store.snapshot().revision(), () -> false);
            assertEquals(Status.STORAGE_UNAVAILABLE, result.status()); assertEquals(point == Point.AFTER_REPLACE, result.publicationMayHaveSucceeded());
        }
        try (var reopened = open(world, clock, Faults.none())) {
            assertFalse(reopened.readOnly()); assertEquals(point == Point.AFTER_REPLACE ? 0 : 1, reopened.failures(OWNER).size());
        }
        assertTrue(peak.get() <= 3L * MAX_BYTES);
    }
    @ParameterizedTest @EnumSource(value=Point.class, names={"BEFORE_STAGE","AFTER_STAGE","AFTER_BACKUP","BEFORE_REPLACE"})
    void cancellationBeforePublicationLeavesTheOriginalUsable(Point point) throws Exception {
        var clock = new MutableClock(); AtomicBoolean cancel = new AtomicBoolean(), armed = new AtomicBoolean();
        try (var store = open(world, clock, p -> { if (armed.get() && p == point) cancel.set(true); })) {
            store.record(event(1,"a".repeat(64),clock.now), () -> false); clock.now += Limits.defaults().retentionMillis() + 1; armed.set(true);
            var result = store.compact(store.snapshot().revision(), cancel::get);
            assertEquals(Status.CANCELLED, result.status()); assertFalse(result.publicationMayHaveSucceeded()); assertFalse(store.readOnly()); assertEquals(1, store.failures(OWNER).size());
        }
        try (var reopened = open(world, clock, Faults.none())) { assertEquals(1, reopened.failures(OWNER).size()); }
    }
    @Test void expirationCollectionThroughManagerPreservesAnotherPinnedAggregate() throws Exception {
        var clock = new MutableClock(); var roots = new RetentionRoots();
        try (var store = open(world, clock, Faults.none())) {
            store.recordBatch(List.of(event(1,"a".repeat(64),clock.now), event(2,"b".repeat(64),clock.now)), () -> false);
            clock.now += Limits.defaults().retentionMillis() + 1;
            String kept = store.failures(OWNER).getFirst().key().id();
            roots.publishNext("access", Set.of(new WorldRetentionManager.Key("evidence", kept)), true);
            var manager = WorldRetentionManagerTest.manager(roots, store.retentionOwner());
            try (var scan = WorldRetentionManagerTest.ready(manager)) { assertEquals(1, scan.collect(() -> false).collected()); }
            assertEquals(1, store.failures(OWNER).size()); assertEquals(kept, store.failures(OWNER).getFirst().key().id());
        }
    }
    @Test void duplicateRecentEventDoesNotMultiplyCountsAndByteLimitRejectsWithoutPublication() throws Exception {
        var clock = new MutableClock();
        try (var store = open(world, clock, Faults.none())) {
            var one = event(1,"a".repeat(64),clock.now); store.record(one, () -> false); store.record(one, () -> false); assertEquals(1, store.failures(OWNER).getFirst().count());
        }
        Path limited = world.resolve("limited");
        try (var store = RetentionEvidenceStore.open(limited, OWNER.scope().worldId(), new Limits(32,16,3,30000,604800000,1024), clock, Faults.none())) {
            assertEquals(Status.STORAGE_LIMIT_REACHED, store.record(event(1,"a".repeat(64),clock.now), () -> false).status());
            assertEquals(0, store.snapshot().revision()); assertFalse(Files.exists(limited.resolve(WORLD_RELATIVE_PATH).resolve("state.json")));
        }
    }
    @Test void singleWriterAndSymbolicPathCannotMutateAnotherOwnersRecords() throws Exception {
        var clock = new MutableClock();
        try (var store = open(world, clock, Faults.none())) { assertThrows(java.io.IOException.class, () -> open(world, clock, Faults.none())); }
        Path linked = world.resolve("linked"); Files.createDirectories(linked.resolve("data")); Files.createSymbolicLink(linked.resolve("data/cognitivecraft"), world.resolve("data/cognitivecraft"));
        assertThrows(java.io.IOException.class, () -> open(linked, clock, Faults.none()));
    }
    @Test void serviceIntakeAndBackgroundWorkStayBoundedWithNoModelDependency() throws Exception {
        var clock = new MutableClock();
        try (var store = open(world, clock, Faults.none())) {
            var manager = WorldRetentionManagerTest.manager(new RetentionRoots(), store.retentionOwner());
            var service = new WorldRetentionService(manager, store);
            for (int tick = 0; tick < 20; tick++) { service.beginTick(); for (int n = 0; n < 20; n++) service.offer(event(1 + tick * 20L + n, "a".repeat(64),clock.now)); }
            assertEquals(64, service.status().queued()); assertTrue(service.status().dropped() > 0);
            for (int turn = 0; turn < 100; turn++) service.cycle();
            assertEquals(64, service.status().recorded()); assertEquals(0, service.status().queued()); assertTrue(service.status().maxInspected() <= 16);
            assertTrue(service.status().maxReadBytes() <= 131072); assertEquals(0, service.status().failures()); service.closeWorker();
        }
    }
    @ParameterizedTest @EnumSource(Point.class)
    void realProcessKillAtEachReplacementBoundaryPreservesAReadableOwner(Point point) throws Exception {
        var clock = new MutableClock();
        try (var store = open(world, clock, Faults.none())) { store.record(event(1,"a".repeat(64),clock.now), () -> false); }
        String separator = java.io.File.pathSeparator;
        String cp = RetentionEvidenceCrashProbe.class.getProtectionDomain().getCodeSource().getLocation().getPath() + separator
                + RetentionEvidenceStore.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        Path flag = world.resolve("paused"); Path output = world.resolve("child.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",cp,
                RetentionEvidenceCrashProbe.class.getName(),world.toString(),OWNER.scope().worldId().toString(),point.name(),flag.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (!Files.exists(flag) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(flag), () -> "Child did not reach boundary: " + point + ", exit=" + (process.isAlive()?"running":process.exitValue()));
            process.destroyForcibly(); assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)); assertNotEquals(0, process.exitValue());
        } finally { if (process.isAlive()) process.destroyForcibly(); }
        try (var reopened = open(world, clock, Faults.none())) { assertFalse(reopened.readOnly()); assertEquals(point == Point.AFTER_REPLACE ? 0 : 1, reopened.failures(OWNER).size()); }
    }
}
