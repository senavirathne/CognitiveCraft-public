package dev.aivillages.core.kernel.storage;

import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static dev.aivillages.core.kernel.storage.StorageRegistry.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic metadata policy only. No native caller, inventory or A authority is fabricated. */
final class StorageTestSupport {
    static final UUID WORLD = UUID.fromString("7b2d1a66-d01d-4000-8000-000000000001");
    static final UUID PRINCIPAL = UUID.fromString("7b2d1a66-d01d-4000-8000-000000000002");
    static final Owner OWNER = new Owner(OwnerKind.PLAYER,PRINCIPAL);
    static final Owner CITIZEN = new Owner(OwnerKind.CITIZEN,UUID.fromString("7b2d1a66-d01d-4000-8000-000000000003"));
    static final Caller CALLER = new Caller(WORLD,PRINCIPAL);
    static final String CHEST = "minecraft:chest_single/v1";
    static final class Policy implements Authority {
        final AtomicBoolean allow = new AtomicBoolean(true);
        public boolean controls(Caller caller, Owner subject) { return allow.get() && caller.principal().equals(PRINCIPAL); }
        public boolean allows(Caller caller, Action action, StorageRegistry.Record record) { return allow.get() && caller.principal().equals(PRINCIPAL); }
        public boolean mayGrant(Caller caller,StorageRegistry.Record record,Grant grant) { return allow.get() && caller.principal().equals(PRINCIPAL) && grant.permissions().stream().allMatch(p -> p==Permission.DEPOSIT || p==Permission.INSPECT); }
    }
    static StorageMetadataStore create(Path path) { return create(path,StorageRegistryLimits.defaults(),cut -> {},p -> Long.MAX_VALUE); }
    static StorageMetadataStore create(Path path, StorageRegistryLimits limits, StorageMetadataStore.Faults faults, StorageMetadataStore.Capacity capacity) {
        var store = await(StorageMetadataStore.createNew(path,WORLD,limits,faults,capacity));
        assertFalse(store.readOnly(),store.reason()); return store;
    }
    static UUID add(StorageRegistry registry, String label) {
        var ack = await(registry.registerMetadata(CALLER,registry.snapshot().revision(),OWNER,label,CHEST,Set.of(),List.of()));
        assertEquals(StorageMetadataStore.Status.ACKNOWLEDGED,ack.status(),ack.reason()); return ack.storage();
    }
    static <T> T await(CompletionStage<T> stage) { try { return stage.toCompletableFuture().get(30,TimeUnit.SECONDS); } catch (Exception failure) { throw new AssertionError(failure); } }
    static void close(StorageMetadataStore store) { await(store.closeAsync()); }
    static void capture(String id, StorageMetadataStore.Cut cut, Path directory, Map<String,String> before,
            boolean reopenedReadOnly, String ack, Integer exit, Path childLog) throws Exception {
        Path repo=Path.of("").toAbsolutePath();
        while (repo!=null && !Files.isDirectory(repo.resolve("e2e/parallel_d"))) repo=repo.getParent();
        if (repo==null) throw new IOException("source root missing");
        Path target=repo.resolve("build/parallel-d-metadata").resolve(id);Files.createDirectories(target);
        var after=new TreeMap<String,String>();var files=new ArrayList<Map<String,Object>>();
        try (var paths=Files.list(directory)) {
            for (Path file:paths.filter(v -> v.getFileName().toString().endsWith(".json")).sorted().toList()) {
                String name=file.getFileName().toString();byte[] bytes=Files.readAllBytes(file);String hash=StorageMetadataCodec.digest(bytes);
                Files.write(target.resolve(name),bytes);after.put(name,hash);files.add(Map.of("path",name,"sha256",hash,"bytes",bytes.length));
            }
        }
        var frame=new TreeMap<String,Object>();frame.put("id",id);frame.put("tier","METADATA_FILESYSTEM_ONLY");frame.put("cut",cut.name());
        frame.put("before_reopen",before);frame.put("after_reopen",after);frame.put("reopened_read_only",reopenedReadOnly);
        frame.put("ack_status",ack);frame.put("process_exit",exit);frame.put("files",files);
        if (childLog!=null) Files.copy(childLog,target.resolve("child.log"),StandardCopyOption.REPLACE_EXISTING);
        Files.write(target.resolve("observation.json"),StorageMetadataCodec.canonical(frame));
    }
    private StorageTestSupport() { }
}
