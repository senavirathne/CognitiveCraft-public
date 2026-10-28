package dev.aivillages.core.kernel.storage;

import com.google.gson.stream.JsonReader;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.storage.StorageRegistry.*;
import static dev.aivillages.core.kernel.storage.StorageTestSupport.*;

class StorageMetadataStoreTest {
    @TempDir Path temp;
    @Test void acknowledgementPersistsChecksummedMetadataAndPreviousRevision() throws Exception {
        Path path=temp.resolve("ack"); var store=create(path); UUID id;
        try {
            var registry=new StorageRegistry(store,new Policy()); id=add(registry,"Chest");
            var ack=await(registry.rename(CALLER,1,id,0,"Renamed")); assertEquals("METADATA_ONLY_ACK",ack.reason());
            assertEquals(2,StorageMetadataCodec.decode(Files.readAllBytes(path.resolve(StorageMetadataStore.CURRENT)),WORLD,store.limits()).revision());
            assertEquals(1,StorageMetadataCodec.decode(Files.readAllBytes(path.resolve(StorageMetadataStore.PREVIOUS)),WORLD,store.limits()).revision());
            assertFalse(Files.exists(path.resolve(StorageMetadataStore.PENDING)));
        } finally { close(store); }
        var restored=await(StorageMetadataStore.open(path,WORLD));
        try { assertFalse(restored.readOnly(),restored.reason()); assertEquals("Renamed",restored.snapshot().records().get(id).label()); }
        finally { close(restored); }
    }
    @ParameterizedTest(name="error-{0}") @EnumSource(StorageMetadataStore.Cut.class)
    void errorAtEachBoundaryKeepsValidCurrentOrReadOnlyOriginals(StorageMetadataStore.Cut cut) throws Exception {
        Path path=temp.resolve("error-"+cut); AtomicBoolean enabled=new AtomicBoolean(false);
        var store=create(path,StorageRegistryLimits.defaults(),point -> { if (enabled.get() && point==cut) throw new IOException("injected "+cut); },p -> Long.MAX_VALUE);
        byte[] before=Files.readAllBytes(path.resolve(StorageMetadataStore.CURRENT));
        try {
            enabled.set(true); var registry=new StorageRegistry(store,new Policy());
            var ack=await(registry.registerMetadata(CALLER,0,OWNER,"Candidate",CHEST,Set.of(),List.of()));
            assertEquals(StorageMetadataStore.Status.IO_ERROR,ack.status()); assertTrue(store.readOnly()); assertEquals(0,store.snapshot().revision());
            assertTrue(Files.exists(path.resolve(StorageMetadataStore.CURRENT)));
            StorageMetadataCodec.decode(Files.readAllBytes(path.resolve(StorageMetadataStore.CURRENT)),WORLD,store.limits());
        } finally { close(store); }
        Map<String,String> originals=hashes(path); var restored=await(StorageMetadataStore.open(path,WORLD));
        try {
            assertTrue(restored.readOnly() || StorageMetadataCodec.decode(Files.readAllBytes(path.resolve(StorageMetadataStore.CURRENT)),WORLD,restored.limits()) != null);
            assertEquals(originals,hashes(path));
            capture("error-"+cut,cut,path,originals,restored.readOnly(),"IO_ERROR",null,null);
            if (cut==StorageMetadataStore.Cut.BEFORE_PENDING || cut==StorageMetadataStore.Cut.PENDING_CREATED || cut==StorageMetadataStore.Cut.PENDING_FORCED || cut==StorageMetadataStore.Cut.PREVIOUS_COPIED)
                assertArrayEquals(before,Files.readAllBytes(path.resolve(StorageMetadataStore.CURRENT)));
        } finally { close(restored); }
    }
    @ParameterizedTest(name="kill-{0}") @EnumSource(value=StorageMetadataStore.Cut.class,names={"PENDING_CREATED","PREVIOUS_COPIED","REPLACED"})
    void actualProcessKillPreservesChecksumsOrReadOnlyFiles(StorageMetadataStore.Cut cut) throws Exception {
        Path path=temp.resolve("kill-"+cut); var seed=create(path); close(seed);
        Path output=temp.resolve("child-"+cut+".log");
        String cp=String.join(File.pathSeparator,location(StorageMetadataProbe.class),location(StorageMetadataStore.class),location(JsonReader.class));
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        Process process=new ProcessBuilder(java,"-cp",cp,StorageMetadataProbe.class.getName(),"fault-write",WORLD.toString(),path.toString(),cut.name()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime()<deadline && process.isAlive() && (!Files.exists(output) || !Files.readString(output).contains("CC_D01_CUT "+cut))) Thread.sleep(20);
            assertTrue(Files.readString(output).contains("CC_D01_CUT "+cut),Files.readString(output));
            process.destroyForcibly(); assertTrue(process.waitFor(10,TimeUnit.SECONDS));
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10,TimeUnit.SECONDS); } }
        Map<String,String> originals=hashes(path); var restored=await(StorageMetadataStore.open(path,WORLD));
        try {
            StorageMetadataCodec.decode(Files.readAllBytes(path.resolve(StorageMetadataStore.CURRENT)),WORLD,restored.limits());
            assertEquals(originals,hashes(path));
            capture("kill-"+cut,cut,path,originals,restored.readOnly(),null,process.exitValue(),output);
            if (cut!=StorageMetadataStore.Cut.REPLACED) assertTrue(restored.readOnly());
            else assertEquals(1,restored.snapshot().revision());
        } finally { close(restored); }
    }
    @Test void missingFilesOpenReadOnlyAndCreateNeverOverwritesExistingDirectory() throws Exception {
        Path path=temp.resolve("missing"); Files.createDirectory(path); byte[] sentinel="operator data".getBytes(); Files.write(path.resolve("original.txt"),sentinel);
        var store=await(StorageMetadataStore.open(path,WORLD));
        try { assertTrue(store.readOnly()); assertEquals(StorageMetadataStore.Status.READ_ONLY,await(new StorageRegistry(store,new Policy()).registerMetadata(CALLER,0,OWNER,"No",CHEST,Set.of(),List.of())).status()); }
        finally { close(store); }
        var existing=await(StorageMetadataStore.createNew(path,WORLD));
        try { assertTrue(existing.readOnly()); assertArrayEquals(sentinel,Files.readAllBytes(path.resolve("original.txt"))); assertFalse(Files.exists(path.resolve(StorageMetadataStore.CURRENT))); }
        finally { close(existing); }
    }
    @Test void corruptCurrentUsesPreviousOnlyAsReadOnlyDiagnosticView() throws Exception {
        Path path=temp.resolve("corrupt"); var store=create(path); var registry=new StorageRegistry(store,new Policy()); add(registry,"Saved"); close(store);
        Files.writeString(path.resolve(StorageMetadataStore.CURRENT),"{truncated"); var before=hashes(path); var reopened=await(StorageMetadataStore.open(path,WORLD));
        try { assertTrue(reopened.readOnly()); assertEquals(0,reopened.snapshot().revision()); assertEquals(before,hashes(path)); }
        finally { close(reopened); }
    }
    @Test void diskFullRefusesBeforeTouchingFilesAndAllowsRetryAfterSpaceReturns() throws Exception {
        AtomicLong free=new AtomicLong(Long.MAX_VALUE); Path path=temp.resolve("disk");
        var store=create(path,StorageRegistryLimits.defaults(),cut -> {},p -> free.get());
        try {
            var registry=new StorageRegistry(store,new Policy()); var before=hashes(path); free.set(0);
            var ack=await(registry.registerMetadata(CALLER,0,OWNER,"No room",CHEST,Set.of(),List.of()));
            assertEquals(StorageMetadataStore.Status.STORAGE_FULL,ack.status()); assertFalse(store.readOnly()); assertEquals(before,hashes(path));
            free.set(Long.MAX_VALUE); add(registry,"Room returned");
        } finally { close(store); }
    }
    @Test void serializedByteCeilingRejectsBeforeReplacementWithoutEviction() throws Exception {
        Path path=temp.resolve("bytes"); var store=create(path,new StorageRegistryLimits(8,8,1024,4),cut -> {},p -> Long.MAX_VALUE);
        try {
            var registry=new StorageRegistry(store,new Policy()); UUID id=add(registry,"Original"); var before=hashes(path);
            var grants=new ArrayList<Grant>(); for (int i=0;i<32;i++) grants.add(new Grant(new Owner(OwnerKind.PLAYER,UUID.randomUUID()),Set.of(Permission.INSPECT)));
            assertEquals(StorageMetadataStore.Status.LIMIT,await(registry.updateAcl(CALLER,1,id,0,grants)).status()); assertEquals(before,hashes(path)); assertEquals(1,registry.snapshot().records().size());
        } finally { close(store); }
    }
    @Test void secondOwnerCannotWriteWhileFirstOwnerHoldsTheWorldLock() {
        Path path=temp.resolve("lock"); var first=create(path); var second=await(StorageMetadataStore.open(path,WORLD));
        try { assertTrue(second.readOnly()); assertEquals("OWNER_ALREADY_OPEN",second.reason()); add(new StorageRegistry(first,new Policy()),"First only"); }
        finally { close(second); close(first); }
    }
    @Test void externalFileReplacementInvalidatesTheOwnerBeforeItWrites() throws Exception {
        Path path=temp.resolve("external"); var store=create(path);
        try {
            Files.writeString(path.resolve(StorageMetadataStore.CURRENT),"external edit"); var before=hashes(path);
            var ack=await(new StorageRegistry(store,new Policy()).registerMetadata(CALLER,0,OWNER,"Must refuse",CHEST,Set.of(),List.of()));
            assertEquals(StorageMetadataStore.Status.IO_ERROR,ack.status()); assertTrue(store.readOnly()); assertEquals(before,hashes(path));
        } finally { close(store); }
    }
    @Test void futureSchemaAndLegacyMissingAclNeverGainPermissiveDefaults() throws Exception {
        Path path=temp.resolve("legacy"); var store=create(path); add(new StorageRegistry(store,new Policy()),"Private"); close(store);
        String content=Files.readString(path.resolve(StorageMetadataStore.CURRENT));
        for (String bad:List.of(content.replace(StorageMetadataCodec.VERSION,"cc.storage-metadata/2"),content.replace("\"acl\":[],","")))
            assertThrows(IllegalArgumentException.class,() -> StorageMetadataCodec.decode(bad.getBytes(java.nio.charset.StandardCharsets.UTF_8),WORLD,StorageRegistryLimits.defaults()));
    }
    private static String location(Class<?> type) throws Exception { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
    private static Map<String,String> hashes(Path directory) throws Exception {
        var result=new TreeMap<String,String>();
        try (var files=Files.list(directory)) {
            for (Path p:files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) result.put(p.getFileName().toString(),StorageMetadataCodec.digest(Files.readAllBytes(p)));
        }
        return result;
    }
}
