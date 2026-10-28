package dev.aivillages.core.kernel.storage;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Test-only executable for independent file/process tests; never packaged into production. */
public final class StorageMetadataProbe {
    public static void main(String[] args) throws Exception {
        String action=args[0]; UUID world=UUID.fromString(args[1]); Path path=Path.of(args[2]);
        if (action.equals("validate")) {
            try { var snapshot=StorageMetadataCodec.decode(Files.readAllBytes(path),world,StorageRegistryLimits.defaults());
                System.out.println("{\"status\":\"VALID\",\"records\":"+snapshot.records().size()+",\"revision\":"+snapshot.revision()+"}"); }
            catch (IllegalArgumentException failure) { System.out.println("{\"status\":\"REFUSED_EXPECTED\"}"); System.exit(2); } return;
        }
        StorageMetadataStore.Faults fault=cut -> {
            if (action.equals("fault-write") && cut.name().equals(args[3])) {
                System.out.println("CC_D01_CUT "+cut); System.out.flush();
                try { new CountDownLatch(1).await(); } catch (InterruptedException interrupted) { throw new java.io.IOException(interrupted); }
            }
        };
        var store=StorageMetadataStore.open(path,world,StorageRegistryLimits.defaults(),fault,p -> Long.MAX_VALUE).toCompletableFuture().get(30,TimeUnit.SECONDS);
        if (action.equals("open")) {
            System.out.println("{\"read_only\":"+store.readOnly()+",\"reason\":\""+store.reason()+"\",\"revision\":"+store.snapshot().revision()+"}");
            store.closeAsync().toCompletableFuture().get(30,TimeUnit.SECONDS); return;
        }
        if ((!action.equals("fault-write") && !action.equals("register")) || store.readOnly()) throw new IllegalArgumentException("invalid test probe action/state");
        var principal=UUID.fromString("7b2d1a66-d01d-4000-8000-000000000002");
        var policy=new StorageRegistry.Authority() {
            public boolean controls(StorageRegistry.Caller caller,StorageRegistry.Owner subject) { return caller.principal().equals(principal); }
            public boolean allows(StorageRegistry.Caller caller,StorageRegistry.Action a,StorageRegistry.Record r) { return caller.principal().equals(principal); }
        };
        var registry=new StorageRegistry(store,policy);
        var ack=registry.registerMetadata(new StorageRegistry.Caller(world,principal),store.snapshot().revision(),new StorageRegistry.Owner(StorageRegistry.OwnerKind.PLAYER,principal),
                "Interrupted metadata", "minecraft:chest_single/v1",Set.of(),List.of()).toCompletableFuture().get(60,TimeUnit.SECONDS);
        System.out.println("{\"status\":\""+ack.status()+"\",\"records\":"+store.snapshot().records().size()+"}");
        store.closeAsync().toCompletableFuture().get(30,TimeUnit.SECONDS);
    }
    private StorageMetadataProbe() { }
}
