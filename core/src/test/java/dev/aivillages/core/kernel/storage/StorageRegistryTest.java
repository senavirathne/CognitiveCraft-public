package dev.aivillages.core.kernel.storage;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.storage.StorageRegistry.*;
import static dev.aivillages.core.kernel.storage.StorageTestSupport.*;

class StorageRegistryTest {
    @TempDir Path temp;
    @Test void renamePreservesUuidAndGenerationWhileNamesakesRemainAmbiguous() {
        var store=create(temp.resolve("registry"));
        try {
            var registry=new StorageRegistry(store,new Policy()); UUID id=add(registry,"Old"); var before=registry.snapshot().records().get(id);
            var ack=await(registry.rename(CALLER,1,id,0,"Shared")); assertEquals(StorageMetadataStore.Status.ACKNOWLEDGED,ack.status());
            add(registry,"SHARED"); var matches=registry.queryVisible(CALLER,"shared");
            assertEquals(2,matches.size()); var after=registry.snapshot().records().get(id);
            assertEquals(before.id(),after.id()); assertEquals(before.generation(),after.generation()); assertEquals("Shared",after.label()); assertEquals(State.PENDING_BIND,after.state());
        } finally { close(store); }
    }
    @Test void fullUnicodeFoldNormalizesLookupWithoutChangingDisplayOrAuthority() {
        var store=create(temp.resolve("unicode"));
        try {
            var policy=new Policy(); var registry=new StorageRegistry(store,policy);
            UUID id=add(registry,"Straße"); assertEquals(id,registry.queryVisible(CALLER,"STRASSE").get(0).id());
            UUID nfc=add(registry,"Cafe\u0301"); assertEquals("Café",registry.snapshot().records().get(nfc).label());
            assertEquals(nfc,registry.queryVisible(CALLER,"CAFÉ").get(0).id());
            add(registry,"ı"); assertTrue(registry.queryVisible(CALLER,"i").isEmpty());
            assertEquals(StorageLabelFold.key("Σ"),StorageLabelFold.key("ς"));
            policy.allow.set(false); assertTrue(registry.queryVisible(CALLER,"STRASSE").isEmpty());
        } finally { close(store); }
    }
    @Test void concurrentExpectedRevisionWritesAcknowledgeExactlyOne() {
        var store=create(temp.resolve("cas"));
        try {
            var registry=new StorageRegistry(store,new Policy()); UUID id=add(registry,"Initial");
            var first=registry.rename(CALLER,1,id,0,"First"); var second=registry.rename(CALLER,1,id,0,"Second");
            var statuses=List.of(await(first).status(),await(second).status());
            assertEquals(1,Collections.frequency(statuses,StorageMetadataStore.Status.ACKNOWLEDGED));
            assertEquals(1,Collections.frequency(statuses,StorageMetadataStore.Status.REVISION_CONFLICT)); assertEquals(2,registry.snapshot().revision());
        } finally { close(store); }
    }
    @Test void duplicateDefaultConflictsAndReassignmentRequiresItsExactRevision() {
        var store=create(temp.resolve("roles"));
        try {
            var registry=new StorageRegistry(store,new Policy()); UUID a=add(registry,"A"), b=add(registry,"B");
            assertEquals(StorageMetadataStore.Status.ACKNOWLEDGED,await(registry.assignRole(CALLER,2,CITIZEN,Role.DEFAULT,a,-1)).status());
            assertEquals(StorageMetadataStore.Status.REVISION_CONFLICT,await(registry.assignRole(CALLER,3,CITIZEN,Role.DEFAULT,b,-1)).status());
            assertEquals(a,registry.roleMetadata(CALLER,CITIZEN,Role.DEFAULT).orElseThrow().id());
            assertEquals(StorageMetadataStore.Status.ACKNOWLEDGED,await(registry.assignRole(CALLER,3,CITIZEN,Role.DEFAULT,b,0)).status());
            assertEquals(b,registry.roleMetadata(CALLER,CITIZEN,Role.DEFAULT).orElseThrow().id());
            assertEquals(1,registry.snapshot().assignments().get(new RoleKey(CITIZEN,Role.DEFAULT)).revision());
        } finally { close(store); }
    }
    @Test void sharedOrganizationStorageCanBeDefaultWithoutOwnershipOrAclTransfer() {
        var store=create(temp.resolve("shared"));
        try {
            var registry=new StorageRegistry(store,new Policy()); Owner organization=new Owner(OwnerKind.ORGANIZATION,UUID.randomUUID());
            UUID id=await(registry.registerMetadata(CALLER,0,organization,"Community",CHEST,Set.of(Role.FOOD),List.of())).storage();
            assertEquals(StorageMetadataStore.Status.ACKNOWLEDGED,await(registry.assignRole(CALLER,1,CITIZEN,Role.DEFAULT,id,-1)).status());
            var result=registry.roleMetadata(CALLER,CITIZEN,Role.DEFAULT).orElseThrow(); assertEquals(organization,result.owner()); assertTrue(result.acl().isEmpty());
            assertEquals(Set.of(Role.FOOD),result.purposes());
        } finally { close(store); }
    }
    @Test void tombstonesKeepIdentityAndInvalidateRoleGenerationAcrossRestart() {
        Path path=temp.resolve("tombstone"); var store=create(path); UUID id;
        try {
            var registry=new StorageRegistry(store,new Policy()); id=add(registry,"Chest"); await(registry.assignRole(CALLER,1,CITIZEN,Role.DEFAULT,id,-1));
            assertEquals(StorageMetadataStore.Status.ACKNOWLEDGED,await(registry.tombstone(CALLER,2,id,0)).status());
            assertTrue(registry.roleMetadata(CALLER,CITIZEN,Role.DEFAULT).isEmpty()); assertEquals(1,registry.snapshot().records().size());
        } finally { close(store); }
        var reopened=await(StorageMetadataStore.open(path,WORLD));
        try {
            assertFalse(reopened.readOnly(),reopened.reason()); var registry=new StorageRegistry(reopened,new Policy());
            assertEquals(State.TOMBSTONED,registry.snapshot().records().get(id).state()); assertEquals(2,registry.snapshot().records().get(id).generation());
            assertEquals(1,registry.snapshot().assignments().get(new RoleKey(CITIZEN,Role.DEFAULT)).storageGeneration()); assertTrue(registry.roleMetadata(CALLER,CITIZEN,Role.DEFAULT).isEmpty());
        } finally { close(reopened); }
    }
    @Test void revokedAuthorityBeforeTheWriteBoundaryLeavesFilesAndRevisionUnchanged() throws Exception {
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1); AtomicBoolean block=new AtomicBoolean(false);
        var store=create(temp.resolve("revocation"),StorageRegistryLimits.defaults(),cut -> {
            if (block.get() && cut==StorageMetadataStore.Cut.BEFORE_PENDING) { entered.countDown(); try { if (!release.await(10,TimeUnit.SECONDS)) throw new java.io.IOException("test barrier timeout"); } catch (InterruptedException e) { throw new java.io.IOException(e); } }
        },p -> Long.MAX_VALUE);
        try {
            var policy=new Policy(); var registry=new StorageRegistry(store,policy); UUID id=add(registry,"Before");
            byte[] before=Files.readAllBytes(temp.resolve("revocation/"+StorageMetadataStore.CURRENT)); block.set(true);
            var pending=registry.rename(CALLER,1,id,0,"After"); assertTrue(entered.await(10,TimeUnit.SECONDS)); policy.allow.set(false); release.countDown();
            assertEquals(StorageMetadataStore.Status.DENIED,await(pending).status()); assertEquals(1,registry.snapshot().revision());
            assertArrayEquals(before,Files.readAllBytes(temp.resolve("revocation/"+StorageMetadataStore.CURRENT)));
        } finally { release.countDown(); close(store); }
    }
    @Test void boundedQueueRejectsOverflowWithoutCallerThreadIo() throws Exception {
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1); AtomicBoolean block=new AtomicBoolean(false); var threads=new CopyOnWriteArrayList<String>();
        var store=create(temp.resolve("queue"),new StorageRegistryLimits(8,8,8192,1),cut -> {
            threads.add(Thread.currentThread().getName());
            if (block.get() && cut==StorageMetadataStore.Cut.BEFORE_PENDING) { entered.countDown(); try { release.await(10,TimeUnit.SECONDS); } catch (InterruptedException e) { throw new java.io.IOException(e); } }
        },p -> Long.MAX_VALUE);
        try {
            var registry=new StorageRegistry(store,new Policy()); UUID id=add(registry,"Before"); block.set(true);
            var first=registry.rename(CALLER,1,id,0,"One"); assertTrue(entered.await(10,TimeUnit.SECONDS));
            var queued=registry.rename(CALLER,2,id,1,"Two"); var overflow=registry.rename(CALLER,3,id,2,"Three");
            assertEquals(StorageMetadataStore.Status.BUSY,await(overflow).status()); release.countDown();
            assertEquals(StorageMetadataStore.Status.ACKNOWLEDGED,await(first).status()); assertEquals(StorageMetadataStore.Status.ACKNOWLEDGED,await(queued).status());
            assertTrue(threads.stream().allMatch(name -> name.startsWith("cc-storage-metadata-")));
        } finally { release.countDown(); close(store); }
    }
    @Test void privateAclAndPurposesRemainIndependentOfDefaultAssignments() {
        var store=create(temp.resolve("acl"));
        try {
            var registry=new StorageRegistry(store,new Policy()); UUID id=add(registry,"Private");
            await(registry.assignRole(CALLER,1,CITIZEN,Role.DEFAULT,id,-1));
            assertTrue(registry.snapshot().records().get(id).acl().isEmpty()); assertTrue(registry.snapshot().records().get(id).purposes().isEmpty());
            var grants=List.of(new Grant(new Owner(OwnerKind.PUBLIC,null),Set.of(Permission.DEPOSIT)));
            assertEquals(StorageMetadataStore.Status.ACKNOWLEDGED,await(registry.updateAcl(CALLER,2,id,0,grants)).status());
            assertEquals(grants,registry.snapshot().records().get(id).acl()); assertEquals(1,registry.snapshot().records().get(id).aclRevision());
            assertEquals(id,registry.roleMetadata(CALLER,CITIZEN,Role.DEFAULT).orElseThrow().id());
        } finally { close(store); }
    }
    @Test void foreignWorldOrPrincipalCannotWriteOrEnumerateVisibleNames() {
        var store=create(temp.resolve("scope"));
        try {
            var registry=new StorageRegistry(store,new Policy()); UUID id=add(registry,"Secret");
            for (Caller caller:List.of(new Caller(UUID.randomUUID(),PRINCIPAL),new Caller(WORLD,UUID.randomUUID()))) {
                assertEquals(StorageMetadataStore.Status.DENIED,await(registry.rename(caller,1,id,0,"Forge")).status()); assertTrue(registry.queryVisible(caller,"Secret").isEmpty());
            }
            assertEquals("Secret",registry.snapshot().records().get(id).label());
        } finally { close(store); }
    }
    @Test void capacityIncludesTombstonesAndNeverEvictsToRegisterAnotherIdentity() {
        var store=create(temp.resolve("bound"),new StorageRegistryLimits(1,1,8192,4),cut -> {},p -> Long.MAX_VALUE);
        try {
            var registry=new StorageRegistry(store,new Policy()); UUID id=add(registry,"Only"); await(registry.tombstone(CALLER,1,id,0));
            assertEquals(StorageMetadataStore.Status.LIMIT,await(registry.registerMetadata(CALLER,2,OWNER,"Another",CHEST,Set.of(),List.of())).status());
            assertEquals(Set.of(id),registry.snapshot().records().keySet()); assertEquals(State.TOMBSTONED,registry.snapshot().records().get(id).state());
        } finally { close(store); }
    }
    @Test void assignmentCapacityAndPublicBeneficiaryFailClosed() {
        var store=create(temp.resolve("role-bound"),new StorageRegistryLimits(8,1,8192,4),cut -> {},p -> Long.MAX_VALUE);
        try {
            var registry=new StorageRegistry(store,new Policy()); UUID id=add(registry,"Chest"); await(registry.assignRole(CALLER,1,CITIZEN,Role.DEFAULT,id,-1));
            assertEquals(StorageMetadataStore.Status.LIMIT,await(registry.assignRole(CALLER,2,CITIZEN,Role.FOOD,id,-1)).status());
            assertThrows(IllegalArgumentException.class,() -> registry.assignRole(CALLER,2,new Owner(OwnerKind.PUBLIC,null),Role.DEFAULT,id,-1));
        } finally { close(store); }
    }
    @Test void snapshotsAndNestedMetadataAreImmutable() {
        var store=create(temp.resolve("immutable"));
        try {
            var registry=new StorageRegistry(store,new Policy()); UUID id=add(registry,"Chest"); var before=registry.snapshot();
            assertThrows(UnsupportedOperationException.class,() -> before.records().clear()); assertThrows(UnsupportedOperationException.class,() -> before.records().get(id).acl().add(new Grant(OWNER,Set.of(Permission.INSPECT))));
            await(registry.rename(CALLER,1,id,0,"Later")); assertEquals("Chest",before.records().get(id).label()); assertEquals(1,before.revision());
        } finally { close(store); }
    }
}
