package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.WorldRetentionManager.*;
import static dev.aivillages.core.kernel.ArtifactCompatibilityTest.*;

final class WorldRetentionSafetyTest {
    @TempDir Path world;
    @Test void transitiveEvidencePinBlocksWindowCompactionWithoutChangingAnyOwnerByte() throws Exception {
        var clock = new RetentionEvidenceStoreTest.MutableClock(); var roots = new RetentionRoots();
        try(var store = RetentionEvidenceStoreTest.open(world,clock,RetentionEvidenceStore.Faults.none())) {
            store.record(RetentionEvidenceStoreTest.event(1,"a".repeat(64),clock.now),() -> false);
            var graph = new WorldRetentionManagerTest.MemoryOwner();
            graph.put("access",true,new Key("evidence",store.failures(OWNER).getFirst().key().id()));
            WorldRetentionManagerTest.manager(roots,graph,store.retentionOwner());
            var before = hashes(world); clock.now += RetentionEvidenceStore.Limits.defaults().retentionMillis()+1;
            assertEquals(RetentionEvidenceStore.Status.STORAGE_LIMIT_REACHED,store.compact(store.snapshot().revision(),() -> false).status());
            assertEquals(before,hashes(world)); assertEquals(1,store.failures(OWNER).size());
        }
    }
    @Test void pinningARecentTraceMakesHistoryPressureVisibleAndPreservesItsSummary() throws Exception {
        var clock = new RetentionEvidenceStoreTest.MutableClock(); var roots = new RetentionRoots();
        try(var store = RetentionEvidenceStoreTest.open(world,clock,RetentionEvidenceStore.Faults.none())) {
            var batch = new ArrayList<RetentionEvidenceStore.Event>();
            for(int at=1;at<=16;at++)batch.add(RetentionEvidenceStoreTest.event(at,"a".repeat(64),clock.now));
            store.recordBatch(batch,() -> false);
            WorldRetentionManagerTest.manager(roots,store.retentionOwner());
            roots.publishNext("access",Set.of(new Key("evidence","trace:"+new UUID(0,1))),true);
            var before=hashes(world);
            assertEquals(RetentionEvidenceStore.Status.STORAGE_LIMIT_REACHED,store.record(RetentionEvidenceStoreTest.event(17,"a".repeat(64),clock.now),() -> false).status());
            assertEquals(before,hashes(world)); assertEquals(16,store.recent(OWNER).size()); assertEquals(16,store.failures(OWNER).getFirst().count());
        }
    }
    @Test void registeredOwnerEpochInvalidatesAnAlreadyPreparedPlan() throws Exception {
        var owner = new WorldRetentionManagerTest.MemoryOwner(); owner.put("scratch",false);
        var manager = WorldRetentionManagerTest.manager(new RetentionRoots(),owner);
        try(var scan = WorldRetentionManagerTest.ready(manager)) {
            manager.register(new RetentionFileOwner("later",world.resolve("later"),Category.JOBS,() -> 0,() -> 0,8));
            assertEquals(1,scan.collect(() -> false).stale()); assertEquals(1,owner.rows.size());
        }
    }
    @Test void emptyProgressPagesCannotCauseAnUnboundedInventoryTraversal() {
        var policy = new Policy(Policy.defaults().categories(),Policy.defaults().total(),1,1,1,1,65536,2);
        var manager = new WorldRetentionManager(policy,new RetentionRoots(),c -> false);
        manager.register(new Owner(){
            public String id(){return "broken";} public long revision(){return 0;}
            public Cursor open(){return new Cursor(){public long revision(){return 0;}public Page next(int r,int b){return new Page(List.of(),false,true,1,0);}};}
            public OwnerResult collect(Entry e,long r,java.util.function.BooleanSupplier c){throw new AssertionError("Invalid owner deletion");}
        });
        try(var scan=manager.begin()) {
            int steps=0;Work work=null;
            while(scan.state()!=State.INVALID&&steps++<20)work=scan.step(() -> false);
            assertEquals(State.INVALID,scan.state());assertTrue(steps<=10);assertTrue(work.diagnostics().contains(Diagnostic.WORK_LIMIT));
        }
    }
    @Test void maximumCustomEvidenceLimitsNeverPublishAnEnvelopeTheirReaderRejects() throws Exception {
        var clock = new RetentionEvidenceStoreTest.MutableClock();
        var limits = new RetentionEvidenceStore.Limits(64,32,4,30000,604800000,65536);
        RetentionEvidenceStore.Snapshot last;
        try(var store=RetentionEvidenceStore.open(world,OWNER.scope().worldId(),limits,clock,RetentionEvidenceStore.Faults.none())) {
            for(int at=1;at<=256;at++) {
                var status=store.record(RetentionEvidenceStoreTest.event(at,String.format("%064x",at%64),clock.now),() -> false).status();
                assertTrue(status==RetentionEvidenceStore.Status.RECORDED||status==RetentionEvidenceStore.Status.STORAGE_LIMIT_REACHED);
            }
            last=store.snapshot();assertFalse(store.readOnly());
        }
        try(var reopened=RetentionEvidenceStore.open(world,OWNER.scope().worldId(),limits,clock,RetentionEvidenceStore.Faults.none())) {assertFalse(reopened.readOnly());assertEquals(last,reopened.snapshot());}
    }
    @Test void repositoryRecoveryFileCeilingIncludesPublicationHeadroom() throws Exception {
        var limits=new VersionedSkillRepository.Limits(1,60000,8192,4194304,1,1,1);
        try(var repository=VersionedSkillRepository.open(world,runtime(),limits,d -> true,Contracts.TrustedContext::equals,VersionedSkillRepository.FaultInjector.none())) {
            var bundle=new VersionedSkillRepository.EvidenceBundle(EVIDENCE,EVIDENCE,EVIDENCE,"b".repeat(64));var skill=literal(1).skill().artifact();
            var before=hashes(world);
            assertEquals(VersionedSkillRepository.PublishStatus.STORAGE_LIMIT_REACHED,repository.publish(skill,new VersionedSkillRepository.AdmissionDecision(UUID.randomUUID(),OWNER,bundle),
                    new Contracts.Provenance(1,null,"compiler-1","minecraft-26.3",OWNER.scope().worldId(),List.of(),List.of(EVIDENCE))).status());
            assertEquals(before,hashes(world));
        }
    }
    @Test void supersededRollbackBodiesKeepTheirDependencyClosureUntilPinsAreReleased() throws Exception {
        var child=literal(3).skill().artifact();var parent=parent(child);var fresh=literal(4).skill().artifact();
        try(var repository=store(world)){admit(repository,child);admit(repository,parent);}
        Path root=world.resolve(VersionedSkillRepository.WORLD_RELATIVE_PATH);
        Files.copy(root.resolve("manifest.json"),root.resolve("manifest.previous.json"),StandardCopyOption.REPLACE_EXISTING);
        var payload=Map.of("schema",1L,"revision",3L,"entries",List.of());var current=new LinkedHashMap<String,Object>(payload);
        current.put("checksum",RepositoryCodec.digest(StrictJson.canonical(payload)));Files.writeString(root.resolve("manifest.json"),StrictJson.canonical(current));
        var roots=new RetentionRoots();roots.publishNext("rollback",Set.of(WorldRetentionManager.artifact(parent.descriptor().ref())),true);
        try(var repository=store(world)) {
            // The repository owns this normal publication, which replaces the supported previous envelope.
            admit(repository,fresh);var manager=WorldRetentionManagerTest.manager(roots,repository.retentionOwner());
            try(var scan=WorldRetentionManagerTest.ready(manager)){assertEquals(0,scan.collect(() -> false).attempted());}
            roots.publishNext("rollback",Set.of(),true);
            try(var scan=WorldRetentionManagerTest.ready(manager)){assertEquals(1,scan.collect(() -> false).collected());}
            assertFalse(Files.exists(root.resolve("bodies/"+parent.descriptor().ref().sha256()+".json")));
            assertTrue(Files.exists(root.resolve("bodies/"+child.descriptor().ref().sha256()+".json")));
            try(var scan=WorldRetentionManagerTest.ready(manager)){assertEquals(1,scan.collect(() -> false).collected());}
            assertTrue(repository.resolve(fresh.descriptor().ref()).usable());assertEquals(0,repository.status().orphanBodies());
        }
    }
}
