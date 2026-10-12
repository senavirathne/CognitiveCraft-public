package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import dev.aivillages.core.kernel.BlueprintStore.Provenance;
import dev.aivillages.core.kernel.WorldRetentionManager.State;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.Blueprint.*;
import static dev.aivillages.core.kernel.BlueprintStore.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.WorldRetentionManager.*;

final class BlueprintStoreTest {
    @TempDir Path temp;
    final UUID world=UUID.randomUUID();
    final TrustedContext owner=owner(world);
    static TrustedContext owner(UUID world) {
        return new TrustedContext(new PrincipalRef(UUID.randomUUID()),new ScopeRef(world,UUID.randomUUID()));
    }
    Provenance provenance(){return new Provenance(owner,"supplied test structure");}
    Publication store(BlueprintStore store,Body body){return store.store(body.canonical(),BlueprintFixture.registry(),provenance(),()->false);}
    Inspection inspect(BlueprintStore store,Ref ref){return store.inspect(ref,owner,BlueprintFixture.registry(),()->false);}
    Path directory(){return temp.resolve(WORLD_RELATIVE_PATH);}
    BlueprintStore open()throws IOException{return BlueprintStore.open(temp,world);}
    @Test void closedOwnerCannotPublishAfterReplacementWriterAcquiresLock()throws Exception {
        var closed=open();store(closed,BlueprintFixture.tiny());closed.close();
        byte[] original=Files.readAllBytes(directory().resolve("manifest.jsonl"));
        try(var replacement=open()) {
            assertEquals(Status.UNAVAILABLE,store(closed,BlueprintFixture.changed()).status());
            assertArrayEquals(original,Files.readAllBytes(directory().resolve("manifest.jsonl")));
            assertEquals(Status.STORED,store(replacement,BlueprintFixture.changed()).status());
        }
    }
    @Test void storeReloadResolveInspectDeduplicatesGeometryAndSeparatesMutableMetadata()throws Exception {
        Ref ref;String body;long revision;
        try(var store=open()) {
            Publication first=store(store,BlueprintFixture.timber());assertEquals(Status.STORED,first.status());ref=first.ref();
            Inspection result=inspect(store,ref);assertTrue(result.compatible());assertEquals(BlueprintFixture.bill(),result.materials());
            body=Files.readString(directory().resolve(ref.sha256()+".body.json"));
            assertEquals(Status.STORED,store.store(result.body().canonical(),BlueprintFixture.registry(),
                    new Provenance(owner,"updated label"),()->false).status());
            assertEquals(1,store.variants());assertEquals(body,Files.readString(directory().resolve(ref.sha256()+".body.json")));
            revision=store.revision();
            assertEquals(Status.STORED,store.store(result.body().canonical(),BlueprintFixture.registry(),
                    new Provenance(owner,"updated label"),()->false).status());
            assertEquals(revision,store.revision());
            assertThrows(IOException.class,()->BlueprintStore.open(temp,world));
        }
        try(var store=open()) {
            assertFalse(store.readOnly());assertEquals(revision,store.revision());
            assertEquals(body,inspect(store,ref).body().canonical());assertEquals(ref,inspect(store,ref).body().ref());
            assertEquals("updated label",inspect(store,ref).provenance().getFirst().label());
        }
        assertTrue(SaveCompatibility.registered().readable(new SaveCompatibility.Format(Blueprint.DOMAIN,1)));
        assertTrue(SaveCompatibility.registered().readable(new SaveCompatibility.Format(BlueprintStore.DOMAIN,1)));
        assertFalse(SaveCompatibility.registered().readable(new SaveCompatibility.Format(BlueprintStore.DOMAIN,2)));
    }
    @ParameterizedTest @EnumSource(Point.class)
    void everyFaultBoundaryRecoversWholeOriginalOrNewGeneration(Point point)throws Exception {
        Ref first;try(var store=open()){first=store(store,BlueprintFixture.tiny()).ref();}
        Ref second=BlueprintValidator.validate(BlueprintFixture.changed().canonical(),BlueprintFixture.registry(),
                BlueprintValidator.Limits.defaults(),()->false).body().ref();
        try(var store=BlueprintStore.open(temp,world,Limits.defaults(),TrustedContext::equals,p->{if(p==point)throw new IOException("interrupted");})) {
            Publication failed=store(store,BlueprintFixture.changed());assertEquals(Status.UNAVAILABLE,failed.status());
            assertEquals(point==Point.AFTER_REPLACE,failed.publicationMayHaveSucceeded());assertTrue(store.readOnly());
        }
        try(var store=open()) {
            assertFalse(store.readOnly());assertTrue(inspect(store,first).compatible());
            assertEquals(point==Point.AFTER_REPLACE?Status.RESOLVED:Status.NOT_FOUND,inspect(store,second).status());
            assertEquals(point==Point.AFTER_REPLACE?2:1,store.variants());
        }
    }
    @ParameterizedTest @EnumSource(Point.class)
    void cooperativeCancellationAtEveryPublicationCheckpointNeverClaimsSuccess(Point point)throws Exception {
        Ref first;try(var store=open()){first=store(store,BlueprintFixture.tiny()).ref();}
        var cancel=new AtomicBoolean();
        try(var store=BlueprintStore.open(temp,world,Limits.defaults(),TrustedContext::equals,p->{if(p==point)cancel.set(true);})) {
            var result=store.store(BlueprintFixture.changed().canonical(),BlueprintFixture.registry(),provenance(),cancel::get);
            assertEquals(Status.CANCELLED,result.status());assertEquals(point==Point.AFTER_REPLACE,result.publicationMayHaveSucceeded());
        }
        try(var store=open()){assertTrue(inspect(store,first).compatible());assertEquals(point==Point.AFTER_REPLACE?2:1,store.variants());}
    }
    @Test void corruptionFutureFormatAndInvalidUtf8PreserveCurrentAndBackupWithoutRewriting()throws Exception {
        Ref first;
        try(var store=open()){first=store(store,BlueprintFixture.tiny()).ref();store(store,BlueprintFixture.changed());}
        byte[] backup=Files.readAllBytes(directory().resolve("manifest.prev.jsonl"));
        for(byte[] corrupt:List.of("{\"schema\":999}\n".getBytes(),new byte[]{(byte)0xc3,(byte)0x28},"garbage".getBytes())) {
            Files.write(directory().resolve("manifest.jsonl"),corrupt);
            try(var store=open()){
                assertTrue(store.readOnly());assertEquals(Status.UNAVAILABLE,store(store,BlueprintFixture.tiny()).status());
            }
            assertArrayEquals(corrupt,Files.readAllBytes(directory().resolve("manifest.jsonl")));
            assertArrayEquals(backup,Files.readAllBytes(directory().resolve("manifest.prev.jsonl")));
            assertTrue(Files.exists(directory().resolve(first.sha256()+".body.json")));
        }
    }
    @Test void corruptOrExternallyChangedBodyAndStageRemainInactiveAndCannotOverwriteOriginal()throws Exception {
        Ref first;try(var store=open()){first=store(store,BlueprintFixture.tiny()).ref();}
        Path body=directory().resolve(first.sha256()+".body.json");Files.writeString(body,"corrupt immutable body");
        try(var store=open()){assertTrue(store.readOnly());assertEquals(Status.UNAVAILABLE,inspect(store,first).status());}
        assertEquals("corrupt immutable body",Files.readString(body));
        Path other=temp.resolve("other");Ref ref;
        try(var store=BlueprintStore.open(other,world)){ref=store(store,BlueprintFixture.tiny()).ref();}
        Path dir=other.resolve(WORLD_RELATIVE_PATH);
        try(var store=BlueprintStore.open(other,world,Limits.defaults(),TrustedContext::equals,p->{
            if(p==Point.BEFORE_REPLACE)Files.writeString(dir.resolve("manifest.next.jsonl"),"corrupt stage");
        })) {assertEquals(Status.UNAVAILABLE,store(store,BlueprintFixture.changed()).status());}
        try(var store=BlueprintStore.open(other,world)){
            assertEquals(1,store.variants());assertEquals(Status.UNAVAILABLE,inspect(store,ref).status());
            assertEquals("corrupt stage",Files.readString(dir.resolve("manifest.next.jsonl")));
            assertEquals(ref,inspect(store,ref).body().ref());
        }
    }
    @Test void currentMutationDuringStagingIsNotCopiedIntoRollback()throws Exception {
        try(var store=open()){store(store,BlueprintFixture.tiny());}
        byte[] original=Files.readAllBytes(directory().resolve("manifest.jsonl"));
        try(var store=BlueprintStore.open(temp,world,Limits.defaults(),TrustedContext::equals,p->{
            if(p==Point.AFTER_STAGE)Files.writeString(directory().resolve("manifest.jsonl"),"foreign mutation");
        })) {assertEquals(Status.UNAVAILABLE,store(store,BlueprintFixture.changed()).status());}
        assertArrayEquals(original,Files.readAllBytes(directory().resolve("manifest.prev.jsonl")));
        try(var store=open()){assertTrue(store.readOnly());}
        assertEquals("foreign mutation",Files.readString(directory().resolve("manifest.jsonl")));
    }
    @Test void registryMeaningStateAndTargetChangesRemainInactiveWithoutSubstitutionOrLostEvidence()throws Exception {
        Ref ref;String original;
        try(var store=open()) {
            ref=store(store,BlueprintFixture.timber()).ref();original=Files.readString(directory().resolve(ref.sha256()+".body.json"));
            assertEquals(Status.INCOMPATIBLE,store.inspect(ref,owner,BlueprintFixture.changedMeaning(),()->false).status());
            assertEquals(Status.INCOMPATIBLE,store.recordCompatibility(ref,owner,BlueprintFixture.changedMeaning(),()->false).status());
            assertEquals(Status.INCOMPATIBLE,store.store(original,BlueprintFixture.changedMeaning(),provenance(),()->false).status());
            var blocks=new TreeMap<>(BlueprintFixture.registry().blocks());blocks.remove("minecraft:oak_door");
            assertEquals(Status.INCOMPATIBLE,store.inspect(ref,owner,new BlueprintValidator.Registry("minecraft-26.3",blocks),()->false).status());
            assertEquals(Status.INCOMPATIBLE,store.inspect(ref,owner,new BlueprintValidator.Registry("future",BlueprintFixture.registry().blocks()),()->false).status());
            assertEquals(original,Files.readString(directory().resolve(ref.sha256()+".body.json")));
        }
        try(var store=open()){
            Inspection current=inspect(store,ref);assertTrue(current.compatible());
            assertEquals(BlueprintValidator.Status.INCOMPATIBLE,current.evidence().getFirst().status());
            assertEquals(BlueprintFixture.bill(),current.materials());
        }
    }
    @Test void deniedAccessAuthorizedSharingAndRevocationPreserveOneSharedBodyAndPrivateLabels()throws Exception {
        var outsider=owner(world);var shared=new AtomicBoolean();Ref ref;
        try(var store=BlueprintStore.open(temp,world,Limits.defaults(),(a,b)->a.equals(b)||shared.get(),p->{})) {
            ref=store(store,BlueprintFixture.tiny()).ref();var denied=store.inspect(ref,outsider,BlueprintFixture.registry(),()->false);
            assertEquals(Status.DENIED,denied.status());assertNull(denied.body());assertTrue(denied.materials().isEmpty());
            shared.set(true);assertTrue(store.inspect(ref,outsider,BlueprintFixture.registry(),()->false).compatible());
            shared.set(false);assertEquals(Status.DENIED,store.inspect(ref,outsider,BlueprintFixture.registry(),()->false).status());
            assertEquals(Status.STORED,store.store(BlueprintFixture.tiny().canonical(),BlueprintFixture.registry(),new Provenance(outsider,"their label"),()->false).status());
            assertEquals(1,store.variants());assertEquals(List.of(provenance()),inspect(store,ref).provenance());
            assertEquals("their label",store.inspect(ref,outsider,BlueprintFixture.registry(),()->false).provenance().getFirst().label());
            assertEquals(Status.DENIED,store.store(BlueprintFixture.tiny().canonical(),BlueprintFixture.registry(),new Provenance(owner(UUID.randomUUID()),"foreign"),()->false).status());
        }
    }
    @Test void exactVariantCapacityAndZeroOrExceededCapacityRejectWithoutEviction()throws Exception {
        try(var store=BlueprintStore.open(temp,world,new Limits(1,4_194_304,4,4),TrustedContext::equals,p->{})) {
            Ref first=store(store,BlueprintFixture.tiny()).ref();assertEquals(Status.QUOTA,store(store,BlueprintFixture.changed()).status());
            assertEquals(1,store.variants());assertTrue(inspect(store,first).compatible());assertEquals(Status.STORED,store(store,BlueprintFixture.tiny()).status());
        }
        for(Limits limits:List.of(new Limits(0,4_194_304,4,4),new Limits(32,0,4,4),new Limits(32,4_194_304,0,4))) {
            try(var store=BlueprintStore.open(temp.resolve("zero"+limits.toString().hashCode()),world,limits,TrustedContext::equals,p->{})){
                assertEquals(Status.QUOTA,store(store,BlueprintFixture.tiny()).status());assertEquals(0,store.variants());
            }
        }
        assertThrows(IllegalArgumentException.class,()->new Limits(33,4_194_304,4,4));
        assertThrows(IllegalArgumentException.class,()->new Limits(32,4_194_305,4,4));
        assertThrows(IllegalArgumentException.class,()->new Limits(32,4_194_304,5,4));
        assertThrows(IllegalArgumentException.class,()->new Limits(32,4_194_304,4,5));
    }
    @Test void exactPhysicalBytesIncludeStagingBackupAndRecoveryHeadroom()throws Exception {
        long bytes;try(var store=open()){store(store,BlueprintFixture.tiny());}
        try(var files=Files.list(directory())){bytes=files.mapToLong(p->{try{return Files.size(p);}catch(IOException e){throw new AssertionError(e);}}).sum();}
        try(var exact=BlueprintStore.open(temp.resolve("exact"),world,new Limits(32,bytes,4,4),TrustedContext::equals,p->{})) {
            assertEquals(Status.STORED,store(exact,BlueprintFixture.tiny()).status());
            assertEquals(Status.QUOTA,exact.store(BlueprintFixture.tiny().canonical(),BlueprintFixture.registry(),new Provenance(owner,"changed metadata"),()->false).status());
        }
        try(var under=BlueprintStore.open(temp.resolve("under"),world,new Limits(32,bytes-1,4,4),TrustedContext::equals,p->{})) {
            assertEquals(Status.QUOTA,store(under,BlueprintFixture.tiny()).status());assertEquals(0,under.variants());
        }
    }
    @Test void provenanceEvidenceAndUnknownRefsAreBounded()throws Exception {
        try(var store=BlueprintStore.open(temp,world,new Limits(32,4_194_304,1,1),TrustedContext::equals,p->{})) {
            Ref ref=store(store,BlueprintFixture.tiny()).ref();
            assertEquals(Status.QUOTA,store.store(BlueprintFixture.tiny().canonical(),BlueprintFixture.registry(),new Provenance(owner(world),"second"),()->false).status());
            assertEquals(Status.INCOMPATIBLE,store.recordCompatibility(ref,owner,BlueprintFixture.changedMeaning(),()->false).status());
            assertEquals(Status.RESOLVED,store.recordCompatibility(ref,owner,BlueprintFixture.registry(),()->false).status());
            assertEquals(1,inspect(store,ref).evidence().size());assertEquals(BlueprintValidator.Status.VALID,inspect(store,ref).evidence().getFirst().status());
            assertEquals(Status.NOT_FOUND,inspect(store,new Ref(1,"0".repeat(64))).status());
        }
    }
    @Test void registeredRootsProtectReferencedOrphansAndFreshScanReclaimsOnlyUnreferencedBodies()throws Exception {
        Ref first;try(var store=open()){first=store(store,BlueprintFixture.tiny()).ref();}
        Ref orphan;
        try(var store=BlueprintStore.open(temp,world,Limits.defaults(),TrustedContext::equals,p->{if(p==Point.AFTER_BODY)throw new IOException("stop");})) {
            orphan=store(store,BlueprintFixture.changed()).ref();
        }
        try(var store=open()) {
            var roots=new RetentionRoots();var manager=new WorldRetentionManager(Policy.defaults(),roots,c->true);
            manager.register(store.retentionOwner());
            assertTrue(roots.snapshot().roots().contains(retentionKey(first)));
            try(var stale=complete(manager)){
                assertEquals(RetentionRoots.Update.APPLIED,roots.publishNext("future-consumer",Set.of(retentionKey(orphan)),true));
                assertEquals(1,stale.collect(()->false).stale());
            }
            try(var scan=complete(manager)){assertEquals(0,scan.collect(()->false).collected());}
            assertTrue(Files.exists(directory().resolve(orphan.sha256()+".body.json")));
            roots.publishNext("future-consumer",Set.of(),true);
            try(var scan=complete(manager)){assertEquals(1,scan.collect(()->false).collected());}
            assertFalse(Files.exists(directory().resolve(orphan.sha256()+".body.json")));assertTrue(inspect(store,first).compatible());
        }
    }
    @Test void rootChangeOrPublicationInvalidatesOldCollectionAndWorldCapacityRefusesSafely()throws Exception {
        try(var store=open()) {
            var roots=new RetentionRoots();var policy=Policy.defaults();var categories=new EnumMap<Category,Quota>(policy.categories());
            categories.put(Category.ARTIFACTS,new Quota(0,0));
            var manager=new WorldRetentionManager(new Policy(categories,policy.total(),policy.owners(),policy.nodes(),
                    policy.links(),policy.sliceRecords(),policy.sliceBytes(),policy.diagnostics()),roots,c->false);
            manager.register(store.retentionOwner());assertEquals(Status.QUOTA,store(store,BlueprintFixture.tiny()).status());
            assertEquals(0,store.variants());
        }
        Path other=temp.resolve("other");try(var store=BlueprintStore.open(other,world)) {
            var roots=new RetentionRoots();var manager=new WorldRetentionManager(Policy.defaults(),roots,c->false);manager.register(store.retentionOwner());
            try(var stale=complete(manager)){
                assertEquals(Status.STORED,store(store,BlueprintFixture.tiny()).status());
                assertEquals(Capacity.STORAGE_UNAVAILABLE,stale.assessReplacement("blueprints",Category.ARTIFACTS,new Amount(1,1)).status());
            }
        }
    }
    @Test void symbolicPathsAndOpaqueFilesFailClosedWithoutCollection()throws Exception {
        Path outside=temp.resolve("outside");Files.createDirectories(outside);
        Path root=temp.resolve("linked");Files.createSymbolicLink(root,outside);
        assertThrows(IOException.class,()->BlueprintStore.open(root,world));
        try(var store=open()) {
            var roots=new RetentionRoots();var manager=new WorldRetentionManager(Policy.defaults(),roots,c->false);manager.register(store.retentionOwner());
            Files.writeString(directory().resolve("0".repeat(64)+".body.json"),"unknown");
            try(var scan=manager.begin()){
                for(int i=0;i<100 && scan.state()!=State.INVALID;i++)scan.step(()->false);
                assertEquals(State.INVALID,scan.state());assertEquals(0,scan.collect(()->false).collected());
            }
            assertTrue(Files.exists(directory().resolve("0".repeat(64)+".body.json")));
        }
    }
    @Test void unavailableModelsAndGatewaySpyNeverReceiveCallsAndNoExecutionStateIsCreated()throws Exception {
        var nativeEffects=new AtomicInteger();var modelCalls=new AtomicInteger();
        GatewayPort gateway=(GatewayPort)java.lang.reflect.Proxy.newProxyInstance(GatewayPort.class.getClassLoader(),new Class<?>[]{GatewayPort.class},
                (proxy,method,args)->{nativeEffects.incrementAndGet();throw new AssertionError("Structural data reached gateway");});
        GenerationPort model=(GenerationPort)java.lang.reflect.Proxy.newProxyInstance(GenerationPort.class.getClassLoader(),new Class<?>[]{GenerationPort.class},
                (proxy,method,args)->{modelCalls.incrementAndGet();throw new AssertionError("Model unavailable");});
        assertNotNull(gateway);assertNotNull(model);
        Ref ref;try(var store=open()){ref=store(store,BlueprintFixture.timber()).ref();assertTrue(inspect(store,ref).compatible());}
        try(var store=open()){assertTrue(inspect(store,ref).compatible());}
        assertEquals(0,nativeEffects.get());assertEquals(0,modelCalls.get());
        for(String path:List.of(JobJournal.WORLD_RELATIVE_PATH,BootstrapJournal.WORLD_RELATIVE_PATH,ResourceLeaseJournal.WORLD_RELATIVE_PATH))
            assertFalse(Files.exists(temp.resolve(path)));
    }
    @Test void futureStageAndForeignWorldRemainPreservedInactive()throws Exception {
        Ref ref;try(var store=open()){ref=store(store,BlueprintFixture.tiny()).ref();}
        byte[] original=Files.readAllBytes(directory().resolve("manifest.jsonl"));
        byte[] stage="{\"schema\":999,\"future\":true}\n".getBytes();
        Files.write(directory().resolve("manifest.next.jsonl"),stage);
        try(var store=open()){
            assertTrue(store.readOnly());assertEquals(Status.UNAVAILABLE,store(store,BlueprintFixture.changed()).status());
        }
        try(var foreign=BlueprintStore.open(temp,UUID.randomUUID())){assertTrue(foreign.readOnly());assertEquals(0,foreign.variants());}
        assertArrayEquals(stage,Files.readAllBytes(directory().resolve("manifest.next.jsonl")));
        assertArrayEquals(original,Files.readAllBytes(directory().resolve("manifest.jsonl")));
        assertTrue(Files.exists(directory().resolve(ref.sha256()+".body.json")));
    }
    @Test void zeroEvidenceAndInvalidStorageAllowancesAreExplicit()throws Exception {
        try(var store=BlueprintStore.open(temp,world,new Limits(32,4_194_304,4,0),TrustedContext::equals,p->{})){
            Ref ref=store(store,BlueprintFixture.tiny()).ref();
            assertEquals(Status.QUOTA,store.recordCompatibility(ref,owner,BlueprintFixture.registry(),()->false).status());
            assertTrue(inspect(store,ref).compatible());
        }
        assertThrows(IllegalArgumentException.class,()->new Limits(-1,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new Limits(0,-1,0,0));
        assertThrows(IllegalArgumentException.class,()->new Limits(0,0,-1,0));
        assertThrows(IllegalArgumentException.class,()->new Limits(0,0,0,-1));
    }
    @Test void maximumVariantProvenanceAndEvidenceAllowancesNeverEvictProtectedData()throws Exception {
        var refs=new ArrayList<Ref>();
        try(var store=open()){
            var tiny=BlueprintFixture.tiny();
            for(int i=0;i<32;i++){
                var body=new Body(new Dimensions(64,1,1),tiny.constraints(),tiny.palette(),List.of(new Cell(i,0,0,0)));
                var result=store(store,body);assertEquals(Status.STORED,result.status());refs.add(result.ref());
            }
            assertEquals(32,store.variants());
            var extra=new Body(new Dimensions(64,1,1),tiny.constraints(),tiny.palette(),List.of(new Cell(32,0,0,0)));
            assertEquals(Status.QUOTA,store(store,extra).status());
            for(Ref ref:refs)assertTrue(inspect(store,ref).compatible());
        }
        try(var store=open()){assertEquals(32,store.variants());for(Ref ref:refs)assertTrue(inspect(store,ref).compatible());}
        try(var store=BlueprintStore.open(temp.resolve("metadataMaximum"),world)){
            Ref ref=store(store,BlueprintFixture.tiny()).ref();
            for(int i=0;i<3;i++)assertEquals(Status.STORED,store.store(BlueprintFixture.tiny().canonical(),BlueprintFixture.registry(),
                    new Provenance(owner(world),"origin "+i),()->false).status());
            assertEquals(Status.QUOTA,store.store(BlueprintFixture.tiny().canonical(),BlueprintFixture.registry(),
                    new Provenance(owner(world),"one over"),()->false).status());
            for(int i=0;i<5;i++){
                var registry=BlueprintFixture.registry();var map=new TreeMap<>(registry.blocks());var d=map.get("minecraft:oak_planks");
                map.put(d.block(),new BlueprintValidator.Definition(d.block(),d.item(),d.kind(),d.values(),d.defaults(),"meaning-"+i));
                assertEquals(Status.INCOMPATIBLE,store.recordCompatibility(ref,owner,new BlueprintValidator.Registry(registry.target(),map),()->false).status());
            }
            assertEquals(4,inspect(store,ref).evidence().size());assertTrue(inspect(store,ref).compatible());
            assertEquals(1,store.variants());
        }
    }
    @Test void retentionSlicesMeterManifestAndBodyReadsAndRemainWithinEveryPageAllowance()throws Exception {
        try(var store=open()){
            Ref ref=store(store,BlueprintFixture.timber()).ref();
            var owner=store.retentionOwner();long read=0;int inspected=0;boolean complete=false;
            try(var cursor=owner.open()){
                for(int i=0;i<100 && !complete;i++){
                    Page page=cursor.next(2,65536);assertTrue(page.valid());assertTrue(page.inspected()<=2);assertTrue(page.readBytes()<=65536);
                    read+=page.readBytes();inspected+=page.inspected();complete=page.complete();
                }
            }
            assertTrue(complete);assertTrue(inspected>=4);
            assertEquals(Files.size(directory().resolve("manifest.jsonl"))+Files.size(directory().resolve(ref.sha256()+".body.json")),read);
        }
    }
    @Test void cancellationBeforeAnyWriteLeavesOwnerWritableAndCurrentCapacitySnapshotValid()throws Exception {
        try(var store=open()){
            var roots=new RetentionRoots();var manager=new WorldRetentionManager(Policy.defaults(),roots,c->false);manager.register(store.retentionOwner());
            try(var scan=complete(manager)){
                var cancelled=store.store(BlueprintFixture.timber().canonical(),BlueprintFixture.registry(),provenance(),()->true);
                assertEquals(Status.CANCELLED,cancelled.status());assertFalse(cancelled.publicationMayHaveSucceeded());
                assertEquals(0,store.variants());assertFalse(store.readOnly());assertFalse(Files.exists(directory().resolve("manifest.jsonl")));
                assertEquals(Capacity.AVAILABLE,scan.assessReplacement("blueprints",Category.ARTIFACTS,new Amount(1,1)).status());
            }
            assertEquals(Status.STORED,store(store,BlueprintFixture.tiny()).status());
        }
    }
    @Test void rejectedDuplicateCandidateCannotPublishOrAlterAnExistingReference()throws Exception {
        var fixture=BlueprintFixture.compact();var cells=new ArrayList<>(fixture.cells());cells.add(cells.getFirst());
        var duplicate=new Body(fixture.dimensions(),fixture.constraints(),fixture.palette(),cells);
        Ref ref=BlueprintValidator.validate(fixture.canonical(),BlueprintFixture.registry(),BlueprintValidator.Limits.defaults(),()->false).body().ref();
        try(var store=open()) {
            var rejected=store(store,duplicate);assertEquals(Status.INVALID_DATA,rejected.status());assertNull(rejected.ref());
            assertFalse(rejected.publicationMayHaveSucceeded());assertEquals(0,store.variants());assertEquals(0,store.revision());
            assertFalse(Files.exists(directory().resolve("manifest.jsonl")));assertFalse(Files.exists(directory().resolve(ref.sha256()+".body.json")));
            assertEquals(Status.STORED,store(store,fixture).status());
            byte[] metadata=Files.readAllBytes(directory().resolve("manifest.jsonl")),body=Files.readAllBytes(directory().resolve(ref.sha256()+".body.json"));
            long revision=store.revision();
            assertEquals(Status.INVALID_DATA,store(store,duplicate).status());assertEquals(revision,store.revision());assertEquals(1,store.variants());
            assertArrayEquals(metadata,Files.readAllBytes(directory().resolve("manifest.jsonl")));
            assertArrayEquals(body,Files.readAllBytes(directory().resolve(ref.sha256()+".body.json")));
            assertFalse(Files.exists(directory().resolve("manifest.next.jsonl")));assertTrue(inspect(store,ref).compatible());
        }
    }
    @Test void simultaneousEquivalentSubmissionsRetainOneBodyAndOneMetadataGeneration()throws Exception {
        var fixture=BlueprintFixture.compact();var palette=new ArrayList<>(fixture.palette());Collections.reverse(palette);
        var cells=new ArrayList<>(fixture.cells().stream().map(c->new Cell(c.x(),c.y(),c.z(),palette.size()-1-c.palette())).toList());
        Collections.reverse(cells);var reordered=new Body(fixture.dimensions(),fixture.constraints(),palette,cells);
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try(var store=open()) {
            try {
                var first=pool.submit(()->{ready.countDown();if(!start.await(5,TimeUnit.SECONDS))throw new AssertionError("Start gate");return store(store,fixture);});
                var second=pool.submit(()->{ready.countDown();if(!start.await(5,TimeUnit.SECONDS))throw new AssertionError("Start gate");return store(store,reordered);});
                assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
                var a=first.get(5,TimeUnit.SECONDS);var b=second.get(5,TimeUnit.SECONDS);
                assertEquals(Status.STORED,a.status());assertEquals(Status.STORED,b.status());assertEquals(a.ref(),b.ref());
                assertEquals(1,store.variants());assertEquals(1,store.revision());assertEquals(BlueprintFixture.compactBill(),inspect(store,a.ref()).materials());
                try(var files=Files.list(directory())){assertEquals(1,files.filter(p->p.getFileName().toString().endsWith(".body.json")).count());}
            } finally {start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        }
    }
    @Test void reopeningRebuildsLookupWithoutChangingBodiesMetadataOrRetainedEvidence()throws Exception {
        Ref ref;Inspection before;Map<String,String> hashes=new TreeMap<>();
        try(var store=open()) {
            ref=store(store,BlueprintFixture.compact()).ref();
            assertEquals(Status.INCOMPATIBLE,store.recordCompatibility(ref,owner,BlueprintFixture.changedMeaning(),()->false).status());
            before=inspect(store,ref);assertTrue(before.compatible());assertEquals(1,before.evidence().size());
        }
        // Closing discards the derived in-memory lookup; every filesystem record here is authoritative/recovery data.
        for(String name:List.of("manifest.jsonl","manifest.prev.jsonl",ref.sha256()+".body.json"))
            hashes.put(name,Blueprint.digest(Files.readAllBytes(directory().resolve(name))));
        try(var rebuilt=open()) {
            var after=inspect(rebuilt,ref);assertTrue(after.compatible());assertEquals(ref,after.body().ref());
            assertEquals(before.body(),after.body());assertEquals(before.materials(),after.materials());
            assertEquals(before.provenance(),after.provenance());assertEquals(before.evidence(),after.evidence());assertEquals(1,rebuilt.variants());
        }
        for(var entry:hashes.entrySet())assertEquals(entry.getValue(),Blueprint.digest(Files.readAllBytes(directory().resolve(entry.getKey()))));
    }
    static WorldRetentionManager.Scan complete(WorldRetentionManager manager) {
        var scan=manager.begin();
        for(int i=0;i<2000 && scan.state()!=State.READY && scan.state()!=State.INVALID;i++)scan.step(()->false);
        assertEquals(State.READY,scan.state());return scan;
    }
}
