package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.SaveCompatibility.*;
import static org.junit.jupiter.api.Assertions.*;

final class SaveCompatibilityTest {
    @TempDir Path world;
    BootstrapJournal.State seed(Path root) throws Exception {
        try(var journal=BootstrapJournal.open(root)) {
            var owner=CitizenRegistryTest.caller(journal.state().worldId());
            var actor=new ActorRef(UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld");
            var enrollment=new BootstrapJournal.Enrollment(actor,owner);
            journal.replace(journal.state(),enrollment,List.of());
            return journal.replace(journal.state(),enrollment,List.of(new BootstrapJournal.RunMarker(
                    UUID.randomUUID(),actor.citizenId(),BootstrapJournal.Phase.ACTIVE,null,null,3,1,"a".repeat(64))));
        }
    }
    Path bootstrap(Path root){return root.resolve(BootstrapJournal.WORLD_RELATIVE_PATH).resolve("state.json");}
    Path citizens(Path root){return root.resolve(CitizenIdentityStore.WORLD_RELATIVE_PATH).resolve("state.json");}
    Control control(SaveCompatibility.FaultInjector faults){return new Control(()->0,()->false,()->true,()->Long.MAX_VALUE,faults);}

    @Test void realOwnerMigrationPreservesIdentityRightsHistoryAndExactOriginalAndRepeatsAsNoOp() throws Exception {
        var legacy=seed(world); byte[] original=Files.readAllBytes(bootstrap(world));
        try(var journal=BootstrapJournal.open(world);var identities=CitizenIdentityStore.open(world,legacy.worldId())) {
            var before=ArtifactCompatibilityTest.hashes(world);
            var plan=inspectBootstrap(journal,identities,legacy.enrollment().owner());
            assertEquals(Status.READY,plan.status());assertEquals(1,plan.sourceVersion());assertEquals(2,plan.targetVersion());
            assertEquals(before,ArtifactCompatibilityTest.hashes(world));
            var report=migrateBootstrap(journal,identities,plan,Limits.defaults(),control(p->{}));
            assertEquals(Status.APPLIED,report.status()); assertEquals(7,report.checkpoints());
            var citizen=identities.snapshot().citizens().getFirst();
            assertEquals(legacy.enrollment().actor(),citizen.actor());assertEquals(legacy.enrollment().owner(),citizen.owner());
            assertEquals(legacy.runs(),journal.state().runs());assertTrue(journal.state().externalIdentities());
            assertArrayEquals(original,Files.readAllBytes(bootstrap(world).resolveSibling("state.legacy-v1.json")));
            before=ArtifactCompatibilityTest.hashes(world);
            assertEquals(Status.NO_OP,migrateAtStartup(journal,identities).status());
            assertEquals(before,ArtifactCompatibilityTest.hashes(world));
            Path out=Path.of("build/compatibility-evidence");Files.createDirectories(out);
            Files.writeString(out.resolve("migration.json"),StrictJson.canonical(Map.of("policy",1L,
                    "sourceSchema",plan.sourceVersion(),"targetSchema",plan.targetVersion(),"sourceSemanticSha256",plan.sourceSemanticSha256(),
                    "inputBytes",plan.inputBytes(),"reservedAdditionalBytes",plan.additionalBytes(),"checkpoints",(long)report.checkpoints(),
                    "rightsUnchanged",true,"retainedOriginalBytes",(long)original.length,"replayedEffects",0L)));
        }
        try(var journal=BootstrapJournal.open(world);var identities=CitizenIdentityStore.open(world,legacy.worldId())) {
            assertEquals(Status.NO_OP,migrateAtStartup(journal,identities).status());
            assertEquals(BootstrapJournal.Phase.INTERRUPTED,journal.interruptedOnReload().runs().getFirst().phase());
            assertEquals(3,journal.interruptedOnReload().runs().getFirst().effects());
        }
    }
    @Test void privateInspectionRejectsForeignPrincipalAndCopiedWorldWithoutAnyPayload() throws Exception {
        var legacy=seed(world);
        try(var journal=BootstrapJournal.open(world);var identities=CitizenIdentityStore.open(world,legacy.worldId())) {
            var foreign=CitizenRegistryTest.caller(legacy.worldId());
            var before=ArtifactCompatibilityTest.hashes(world);
            for(var viewer:List.of(foreign,CitizenRegistryTest.caller(UUID.randomUUID()))) {
                var plan=inspectBootstrap(journal,identities,viewer);
                assertEquals(Status.AUTHORITY_DENIED,plan.status());assertNull(plan.sourceSemanticSha256());
                assertEquals(0,plan.sourceRevision());assertEquals(0,plan.records());
            }
            assertEquals(before,ArtifactCompatibilityTest.hashes(world));
        }
    }
    @Test void unsupportedFutureCurrentIsPreservedEvenWhenAnOlderBackupIsReadable() throws Exception {
        var legacy=seed(world);String future=Files.readString(bootstrap(world)).replace("\"schema\":1","\"schema\":999");
        Files.writeString(bootstrap(world),future); var before=ArtifactCompatibilityTest.hashes(world);
        try(var journal=BootstrapJournal.open(world);var identities=CitizenIdentityStore.open(world,legacy.worldId())) {
            var plan=inspectBootstrap(journal,identities);assertEquals(Status.UNSUPPORTED_VERSION,plan.status());
            assertEquals(List.of(new Format("bootstrap",999)),plan.unsupportedFormats());
            assertNull(plan.sourceSemanticSha256());assertTrue(journal.readOnly());
            assertEquals(Status.UNSUPPORTED_VERSION,migrateBootstrap(journal,identities,plan,Limits.defaults(),control(p->{})).status());
        }
        assertEquals(future,Files.readString(bootstrap(world)));
        for(var e:before.entrySet())assertEquals(e.getValue(),ArtifactCompatibilityTest.hashes(world).get(e.getKey()));
    }
    @ParameterizedTest @EnumSource(Point.class)
    void interruptedCoordinatorStagesRecoverOriginalOrCommittedVersionWithoutDuplicateRights(Point point) throws Exception {
        var legacy=seed(world);byte[] original=Files.readAllBytes(bootstrap(world));
        try(var journal=BootstrapJournal.open(world);var identities=CitizenIdentityStore.open(world,legacy.worldId())) {
            var plan=inspectBootstrap(journal,identities);
            var report=migrateBootstrap(journal,identities,plan,Limits.defaults(),control(at->{if(at==point)throw new IOException("synthetic fault");}));
            assertEquals(Status.STORAGE_UNAVAILABLE,report.status());assertTrue(report.checkpoints()<=7);
            assertTrue(identities.snapshot().citizens().size()<=1);
            if(!journal.state().externalIdentities())assertArrayEquals(original,Files.readAllBytes(bootstrap(world)));
        }
        try(var journal=BootstrapJournal.open(world);var identities=CitizenIdentityStore.open(world,legacy.worldId())) {
            assertTrue(migrateAtStartup(journal,identities).complete());assertEquals(1,identities.snapshot().citizens().size());
            assertEquals(legacy.enrollment().owner(),identities.snapshot().citizens().getFirst().owner());
            assertArrayEquals(original,Files.readAllBytes(bootstrap(world).resolveSibling("state.legacy-v1.json")));
        }
    }
    @Test void futureCitizenFormatReportsItsExactVersionAndPreservesThePartialHandoff() throws Exception {
        var legacy=seed(world);
        try(var identities=CitizenIdentityStore.open(world,legacy.worldId())) {
            var imported=identities.migrateBootstrap(legacy);
            identities.replace(imported,new CitizenRegistry.Snapshot(imported.worldId(),imported.revision()+1,
                    imported.citizens(),imported.migration()));
        }
        String future=Files.readString(citizens(world)).replace("\"schema\":1","\"schema\":"+Long.MAX_VALUE);
        Files.writeString(citizens(world),future);var before=ArtifactCompatibilityTest.hashes(world);
        try(var journal=BootstrapJournal.open(world);var identities=CitizenIdentityStore.open(world,legacy.worldId())) {
            var plan=inspectBootstrap(journal,identities);
            assertEquals(Status.UNSUPPORTED_VERSION,plan.status());
            assertEquals(List.of(new Format("citizens",Long.MAX_VALUE)),plan.unsupportedFormats());
            assertNull(plan.sourceSemanticSha256());assertEquals(0,plan.records());assertTrue(identities.readOnly());
            assertEquals(Status.UNSUPPORTED_VERSION,migrateAtStartup(journal,identities).status());
            assertFalse(journal.state().externalIdentities());
        }
        assertEquals(before,ArtifactCompatibilityTest.hashes(world));
    }
    @ParameterizedTest @EnumSource(CitizenIdentityStore.Point.class)
    void actualIdentityStagingBackupAndReplaceFaultsReconcileOnReopen(CitizenIdentityStore.Point point) throws Exception {
        var legacy=seed(world);byte[] original=Files.readAllBytes(bootstrap(world));
        try(var journal=BootstrapJournal.open(world);var identities=CitizenIdentityStore.open(world,legacy.worldId(),at->{if(at==point)throw new IOException("owned I/O fault");})) {
            var report=migrateAtStartup(journal,identities);assertEquals(Status.STORAGE_UNAVAILABLE,report.status());
            assertTrue(identities.readOnly());assertArrayEquals(original,Files.readAllBytes(bootstrap(world)));
        }
        try(var journal=BootstrapJournal.open(world);var identities=CitizenIdentityStore.open(world,legacy.worldId())) {
            assertTrue(migrateAtStartup(journal,identities).complete());assertEquals(1,identities.snapshot().citizens().size());
        }
    }
    @Test void actualBlockedBootstrapStagePreservesLegacyArchiveAndRetriesAfterReopen() throws Exception {
        var legacy=seed(world);byte[] original=Files.readAllBytes(bootstrap(world));
        Path blocked=bootstrap(world).resolveSibling("state.next.json");Files.createDirectory(blocked);
        try(var journal=BootstrapJournal.open(world);var identities=CitizenIdentityStore.open(world,legacy.worldId())) {
            var result=migrateAtStartup(journal,identities);assertEquals(Status.STORAGE_UNAVAILABLE,result.status());
            assertTrue(journal.readOnly());assertTrue(result.publicationMayHaveSucceeded());
            assertArrayEquals(original,Files.readAllBytes(bootstrap(world)));
            assertArrayEquals(original,Files.readAllBytes(bootstrap(world).resolveSibling("state.legacy-v1.json")));
        }
        Files.deleteIfExists(blocked);
        try(var journal=BootstrapJournal.open(world);var identities=CitizenIdentityStore.open(world,legacy.worldId())) {
            assertEquals(Status.APPLIED,migrateAtStartup(journal,identities).status());
            assertEquals(1,identities.snapshot().citizens().size());
        }
    }
    @Test void cancellationBeforeAndAfterIdentityCommitAndElapsedDeadlineAreRecoverable() throws Exception {
        var legacy=seed(world);var cancel=new AtomicBoolean(true);var clock=new AtomicLong();
        try(var journal=BootstrapJournal.open(world);var identities=CitizenIdentityStore.open(world,legacy.worldId())) {
            var plan=inspectBootstrap(journal,identities);
            var c=new Control(clock::get,cancel::get,()->true,()->Long.MAX_VALUE,p->{});
            assertEquals(Status.CANCELLED,migrateBootstrap(journal,identities,plan,Limits.defaults(),c).status());
            assertTrue(identities.snapshot().citizens().isEmpty());cancel.set(false);
            var timeout=new Control(clock::get,()->false,()->true,()->Long.MAX_VALUE,p->clock.set(5_000_000_000L));
            assertEquals(Status.LIMIT_REACHED,migrateBootstrap(journal,identities,plan,Limits.defaults(),timeout).status());
            assertTrue(identities.snapshot().citizens().isEmpty());clock.set(0);
            var after=new Control(clock::get,cancel::get,()->true,()->Long.MAX_VALUE,p->{if(p==Point.AFTER_IDENTITY_COMMIT)cancel.set(true);});
            assertEquals(Status.CANCELLED,migrateBootstrap(journal,identities,plan,Limits.defaults(),after).status());
            assertEquals(1,identities.snapshot().citizens().size());assertFalse(journal.state().externalIdentities());
            cancel.set(false);assertEquals(Status.APPLIED,migrateAtStartup(journal,identities).status());
            assertEquals(1,identities.snapshot().citizens().size());
        }
    }
    @Test void exactByteRecordAndCheckpointAllowancesAcceptNAndRejectNMinusOneWithoutWrites() throws Exception {
        var legacy=seed(world);
        try(var journal=BootstrapJournal.open(world);var identities=CitizenIdentityStore.open(world,legacy.worldId())) {
            var plan=inspectBootstrap(journal,identities);var before=ArtifactCompatibilityTest.hashes(world);
            for(var cap:List.of(new Limits(plan.records()-1,plan.inputBytes(),plan.additionalBytes(),8,1),
                    new Limits(plan.records(),plan.inputBytes()-1,plan.additionalBytes(),8,1),
                    new Limits(plan.records(),plan.inputBytes(),plan.additionalBytes()-1,8,1),
                    new Limits(96,81920,262144,0,1),new Limits(96,81920,262144,8,0))) {
                assertEquals(Status.LIMIT_REACHED,migrateBootstrap(journal,identities,plan,cap,control(p->{})).status());
                assertEquals(before,ArtifactCompatibilityTest.hashes(world));
            }
            var unavailable=new Control(()->0,()->false,()->true,()->plan.additionalBytes()-1,p->{});
            assertEquals(Status.SPACE_UNAVAILABLE,migrateBootstrap(journal,identities,plan,Limits.defaults(),unavailable).status());
            assertEquals(before,ArtifactCompatibilityTest.hashes(world));
            var exact=new Limits(plan.records(),plan.inputBytes(),plan.additionalBytes(),7,1);
            var result=migrateBootstrap(journal,identities,plan,exact,control(p->{}));assertEquals(Status.APPLIED,result.status());
            assertEquals(7,result.checkpoints());
        }
    }
    @Test void quiescenceAndStalePlanFenceWritesAndConcurrentOwnersUseTheSamePublicationLock() throws Exception {
        var legacy=seed(world);
        try(var journal=BootstrapJournal.open(world);var identities=CitizenIdentityStore.open(world,legacy.worldId())) {
            var plan=inspectBootstrap(journal,identities);
            assertEquals(Status.NOT_QUIESCENT,migrateBootstrap(journal,identities,plan,Limits.defaults(),new Control(()->0,()->false,()->false,()->Long.MAX_VALUE,p->{})).status());
            journal.replace(journal.state(),legacy.enrollment(),legacy.runs());
            assertEquals(Status.STALE_PLAN,migrateBootstrap(journal,identities,plan,Limits.defaults(),control(p->{})).status());
            plan=inspectBootstrap(journal,identities);final var expected=plan;
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var ownerEntered=new CountDownLatch(1);
            try(var pool=Executors.newFixedThreadPool(2)) {
                var migration=pool.submit(()->migrateBootstrap(journal,identities,expected,Limits.defaults(),control(at->{
                    if(at==Point.BEFORE_IDENTITY_COMMIT){entered.countDown();try{if(!release.await(2,TimeUnit.SECONDS))throw new IOException("fixture release timeout");}catch(InterruptedException e){throw new IOException(e);}}
                })));
                assertTrue(entered.await(2,TimeUnit.SECONDS));
                var write=pool.submit(()->{ownerEntered.countDown();try{return journal.replace(legacy,legacy.enrollment(),legacy.runs());}catch(IOException fenced){return null;}});
                assertTrue(ownerEntered.await(2,TimeUnit.SECONDS));assertFalse(write.isDone());release.countDown();
                assertEquals(Status.APPLIED,migration.get(3,TimeUnit.SECONDS).status());assertNull(write.get(3,TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
    }
    @Test void readerRegistryIsExplicitFiniteAndDoesNotClaimInventedArtifactMigrations() {
        assertTrue(registered().readable(new Format("bootstrap",1)));assertTrue(registered().readable(new Format("bootstrap",2)));
        assertEquals(1,registered().rules().size());assertFalse(registered().migration(new Format("skill-body",1)).isPresent());
        for(long version:List.of(3L,Long.MAX_VALUE))assertFalse(registered().readable(new Format("bootstrap",version)));
        assertFalse(registered().readable(new Format("future-world-knowledge",1)));
        var readers=new ArrayList<Reader>();for(int i=0;i<16;i++)readers.add(new Reader("domain-"+i,1,Set.of(1)));
        assertEquals(16,new Registry(readers,List.of()).readers().size());readers.add(new Reader("overflow",1,Set.of(1)));
        assertThrows(IllegalArgumentException.class,()->new Registry(readers,List.of()));
        assertThrows(IllegalArgumentException.class,()->new Registry(List.of(new Reader("a",1,Set.of(1))),List.of(
                new Rule(new Format("a",2),new Format("a",1),false))));
    }
    @Test void actualMaximumInputsAndAllOwnerRecordsFitReservedTemporaryStorage() throws Exception {
        var legacy=seed(world);
        try(var journal=BootstrapJournal.open(world);var identities=CitizenIdentityStore.open(world,legacy.worldId())) {
            var runs=new ArrayList<BootstrapJournal.RunMarker>();
            for(int i=0;i<BootstrapJournal.MAX_RUNS;i++) runs.add(new BootstrapJournal.RunMarker(
                    UUID.randomUUID(),legacy.enrollment().actor().citizenId(),BootstrapJournal.Phase.TERMINAL,
                    "SUCCEEDED",null,i,0,"b".repeat(64)));
            journal.replace(journal.state(),legacy.enrollment(),runs);
            var rows=new ArrayList<CitizenRegistry.Citizen>();
            for(int i=0;i<CitizenRegistry.MAX_CITIZENS-1;i++)rows.add(new CitizenRegistry.Citizen(
                    new ActorRef(UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld"),legacy.enrollment().owner(),
                    "Fixture citizen "+i,CitizenRegistry.Availability.UNKNOWN));
            identities.replace(identities.snapshot(),new CitizenRegistry.Snapshot(legacy.worldId(),1,rows,null));
        }
        for(var entry:Map.of(bootstrap(world),16384,citizens(world),65536).entrySet()) {
            String content=Files.readString(entry.getKey());
            Files.writeString(entry.getKey(),content+" ".repeat(entry.getValue()-content.length()));
        }
        long baseline=directoryBytes(world);var peak=new AtomicLong(baseline);
        try(var journal=BootstrapJournal.open(world);var identities=CitizenIdentityStore.open(world,legacy.worldId(),
                point->peak.accumulateAndGet(directoryBytes(world),Math::max))) {
            var plan=inspectBootstrap(journal,identities);assertEquals(Status.READY,plan.status());
            assertEquals(81920,plan.inputBytes());assertEquals(80,plan.records());
            var before=ArtifactCompatibilityTest.hashes(world);
            assertEquals(Status.LIMIT_REACHED,migrateBootstrap(journal,identities,plan,
                    new Limits(96,81919,262144,8,5_000_000_000L),control(p->{})).status());
            assertEquals(before,ArtifactCompatibilityTest.hashes(world));
            var result=migrateBootstrap(journal,identities,plan,Limits.defaults(),
                    control(point->peak.accumulateAndGet(directoryBytes(world),Math::max)));
            assertEquals(Status.APPLIED,result.status());assertEquals(64,identities.snapshot().citizens().size());
            assertEquals(16,journal.state().runs().size());
            assertEquals(16384,Files.size(bootstrap(world).resolveSibling("state.legacy-v1.json")));
            assertTrue(peak.get()-baseline<=plan.additionalBytes());assertTrue(plan.additionalBytes()<=262144);
            var out=Path.of("build/compatibility-evidence");Files.createDirectories(out);
            Files.writeString(out.resolve("maximum-inputs.json"),StrictJson.canonical(Map.of(
                    "inputBytes",plan.inputBytes(),"citizens",64L,"runs",16L,"records",(long)plan.records(),
                    "baselineDirectoryBytes",baseline,"observedPeakDirectoryBytes",peak.get(),
                    "reservedAdditionalBytes",plan.additionalBytes(),"retainedExactLegacyBytes",16384L)));
        }
    }
    private static long directoryBytes(Path root) throws IOException {
        long total=0;try(var paths=Files.walk(root)) {
            for(Path path:paths.filter(Files::isRegularFile).toList())total=Math.addExact(total,Files.size(path));
        }return total;
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3,4}) void everyMigrationLimitAcceptsZeroAndNAndRejectsNegativeNPlusOneAndOverflow(int field) {
        long[] max={96,81920,262144,8,60_000_000_000L};
        for(long invalid:new long[]{-1,max[field]+1,field==0||field==3?Integer.MAX_VALUE:Long.MAX_VALUE}) {
            long[] values=max.clone();values[field]=invalid;
            assertThrows(IllegalArgumentException.class,()->new Limits((int)values[0],values[1],values[2],(int)values[3],values[4]));
        }
        long[] zero=max.clone();zero[field]=0;assertDoesNotThrow(()->new Limits((int)zero[0],zero[1],zero[2],(int)zero[3],zero[4]));
        assertDoesNotThrow(()->new Limits(96,81920,262144,8,60_000_000_000L));
    }
}
