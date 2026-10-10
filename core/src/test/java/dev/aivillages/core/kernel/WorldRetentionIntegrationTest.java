package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.WorldRetentionManager.*;

final class WorldRetentionIntegrationTest {
    @TempDir Path world;
    static final UUID WORLD = new UUID(0, 77);
    static final TrustedContext OWNER = new TrustedContext(new PrincipalRef(new UUID(0,78)), new ScopeRef(WORLD,new UUID(0,78)));
    static final EvidenceRef EVIDENCE = new EvidenceRef("synthetic-retention-fixture", "fixture-1", "unit");
    static final CapabilityCatalog CAPABILITIES = id -> id.equals(CropDelivery.ID) ? Optional.of(CropDelivery.SPEC) : Optional.empty();
    static SkillArtifact artifact(int n) {
        var result = new SkillCompiler(CAPABILITIES, GatewayPrimitives.instance(), r -> Optional.empty()).compile(StrictJson.canonical(Map.of(
                "schema", 1L, "capability", CropDelivery.ID.name(), "capabilityVersion", 1L, "dependencies", List.of(),
                "body", List.of(Map.of("op", "result", "value", Map.of("int", (long) n))))));
        return assertInstanceOf(SkillCompiler.Success.class, result).skill().artifact();
    }
    static VersionedSkillRepository.PublishResult publish(VersionedSkillRepository repository, SkillArtifact artifact) {
        var bundle = new VersionedSkillRepository.EvidenceBundle(EVIDENCE,EVIDENCE,EVIDENCE,"b".repeat(64));
        return repository.publish(artifact,new VersionedSkillRepository.AdmissionDecision(UUID.randomUUID(),OWNER,bundle),
                new Provenance(1,null,"compiler-1","minecraft-26.3",WORLD,artifact.descriptor().dependencies(),List.of(EVIDENCE)));
    }
    @Test void longActualRepositoryJobRootSimulationIsBoundedAndReopensWithUnchangedAccounting() throws Exception {
        var clock = new JobLifecycleStoreTest.TestClock(); AtomicBoolean fault = new AtomicBoolean();
        var runtime = new VersionedSkillRepository.RuntimeSnapshot("minecraft-26.3",CAPABILITIES,GatewayPrimitives.instance());
        Jobs.Snapshot durable; Set<ArtifactRef> expectedRefs; long peakBytes = 0; int collected = 0;
        try (var journal = JobJournal.open(world,WORLD); var repository = VersionedSkillRepository.open(world,runtime,VersionedSkillRepository.Limits.defaults(), d -> true,
                TrustedContext::equals, at -> { if (fault.get() && at == VersionedSkillRepository.FaultPoint.AFTER_BODY_WRITE) throw new java.io.IOException("Synthetic uncommitted body"); })) {
            var first = artifact(1); var second = artifact(2);
            assertEquals(VersionedSkillRepository.PublishStatus.ADMITTED,publish(repository,first).status());
            assertEquals(VersionedSkillRepository.PublishStatus.ADMITTED,publish(repository,second).status());
            var jobs = new JobLifecycleStore(journal.snapshot(),(expected,next) -> { try { return CompletableFuture.completedFuture(journal.replace(expected,next)); }
                    catch (Exception e) { return CompletableFuture.failedFuture(e); } },JobLifecycleStore.privateJobs((a,c) -> OWNER.equals(c)),c -> { throw new AssertionError("Retention delivered cancellation"); },
                    CAPABILITIES,JobLifecycleStoreTest.environment(),clock,Jobs.Settings.defaults(),false);
            var roots = new RetentionRoots(); var manager = WorldRetentionManagerTest.manager(roots,repository.retentionOwner());
            manager.register(new RetentionFileOwner("jobs",world.resolve(JobJournal.WORLD_RELATIVE_PATH),Category.JOBS,() -> journal.snapshot().revision(),
                    () -> journal.snapshot().jobs().size()+journal.snapshot().allocations().size(),8));
            jobs.retentionGuard(next -> {
                var refs = new HashSet<WorldRetentionManager.Key>();
                for (var job : next.jobs()) for (var attempt : job.attempts()) for (var execution : attempt.executions())
                    execution.pinned().forEach(r -> refs.add(WorldRetentionManager.artifact(r)));
                var update = roots.publishNext("jobs",refs,true); return update == RetentionRoots.Update.APPLIED || update == RetentionRoots.Update.UNCHANGED;
            });
            for (int at = 0; at < 16; at++) {
                var actor = JobLifecycleStoreTest.actor();
                UUID id = new UUID(1,at+1); var created = jobs.create(id,id,JobLifecycleStoreTest.request(actor,1),OWNER,
                        JobLifecycleStoreTest.observation(),JobLifecycleStoreTest.limits(clock.now+10000),List.of());
                assertTrue(created.accepted()); jobs.tick(); var job = jobs.query(id,OWNER); ArtifactRef ref = at % 2 == 0 ? first.descriptor().ref() : second.descriptor().ref();
                var assigned=jobs.assign(id,job.guard(),UUID.randomUUID(),actor,ref,List.of(ref),JobLifecycleStoreTest.limits(clock.now+10000),OWNER);
                assertTrue(assigned.accepted(),assigned.toString()); jobs.tick();
            }
            assertEquals(2,jobs.protectedRoots().artifacts().size()); assertTrue(jobs.protectedRoots().complete());
            durable = journal.snapshot(); expectedRefs = jobs.protectedRoots().artifacts(); fault.set(true);
            for (int at = 3; at < 259; at++) {
                var discarded = artifact(at); assertEquals(VersionedSkillRepository.PublishStatus.STORAGE_UNAVAILABLE,publish(repository,discarded).status());
                try (var scan = WorldRetentionManagerTest.ready(manager)) {
                    peakBytes = Math.max(peakBytes,scan.usage().total().bytes()); var report = scan.collect(() -> false); collected += report.collected(); assertEquals(1,report.collected());
                }
                assertEquals(0,repository.status().orphanBodies()); assertTrue(repository.resolve(first.descriptor().ref()).usable()); assertTrue(repository.resolve(second.descriptor().ref()).usable());
                assertEquals(durable,journal.snapshot());
            }
            assertEquals(256,collected); assertTrue(peakBytes < 1_000_000);
        }
        try (var journal = JobJournal.open(world,WORLD); var repository = VersionedSkillRepository.open(world,runtime,VersionedSkillRepository.Limits.defaults(),d -> true,TrustedContext::equals,VersionedSkillRepository.FaultInjector.none())) {
            assertEquals(durable,journal.snapshot()); for (var ref : expectedRefs) assertTrue(repository.resolve(ref).usable());
            for (var job : journal.snapshot().jobs()) { assertEquals(0,job.effects()); assertEquals(0,job.fulfilled()); assertEquals(Map.of(),job.usage()); }
        }
        Path output = Path.of("build/retention-evidence"); Files.createDirectories(output);
        Files.writeString(output.resolve("owners.json"),StrictJson.canonical(Map.of("iterations",256L,"collected",(long)collected,"jobs",16L,"protectedArtifacts",2L,"maxTotalBytes",peakBytes,"accountingUnchanged",true,"replayedEffects",0L)));
    }
    @Test void deletingActualRetrievalCachePreservesAdmittedArtifactsAccessAndDurableJobs() throws Exception {
        var runtime = new VersionedSkillRepository.RuntimeSnapshot("minecraft-26.3",CAPABILITIES,GatewayPrimitives.instance());
        try (var journal = JobJournal.open(world,WORLD); var repository = VersionedSkillRepository.open(world,runtime,VersionedSkillRepository.Limits.defaults(),d -> true,TrustedContext::equals,VersionedSkillRepository.FaultInjector.none())) {
            var skill = artifact(1); assertEquals(VersionedSkillRepository.PublishStatus.ADMITTED,publish(repository,skill).status());
            var lookups = new CapabilityRetrievalIndex.LookupRegistry(Map.of("wheat",CropDelivery.ID),Map.of(CropDelivery.ID,Set.of("farming")),CAPABILITIES);
            Path path = world.resolve(CapabilityRetrievalIndex.WORLD_RELATIVE_PATH);
            try (var cache = new CapabilityRetrievalIndex(path,new CapabilityRetrievalIndex.Identity(WORLD,UUID.randomUUID()),
                    CapabilityRetrievalIndex.repositorySource(repository,lookups,() -> 0),lookups,CAPABILITIES,CapabilityRetrievalIndex.Limits.defaults(),() -> System.nanoTime()/1_000_000)) {
                for (int turn = 0; turn < 50; turn++) cache.maintain(Runnable::run);
                var before = repository.privateOrigins(skill.descriptor().ref(),OWNER); var admitted = repository.resolve(skill.descriptor().ref()).admission(); var jobs = journal.snapshot();
                cache.deleteCache(); assertEquals(before,repository.privateOrigins(skill.descriptor().ref(),OWNER)); assertEquals(admitted,repository.resolve(skill.descriptor().ref()).admission()); assertEquals(jobs,journal.snapshot());
                assertTrue(repository.resolve(skill.descriptor().ref()).usable());
                var foreign = new TrustedContext(new PrincipalRef(UUID.randomUUID()),OWNER.scope()); assertTrue(repository.privateOrigins(skill.descriptor().ref(),foreign).isEmpty());
            }
        }
        try (var repository = VersionedSkillRepository.open(world,runtime,VersionedSkillRepository.Limits.defaults(),d -> true,TrustedContext::equals,VersionedSkillRepository.FaultInjector.none())) { assertTrue(repository.resolve(artifact(1).descriptor().ref()).usable()); }
    }
}
