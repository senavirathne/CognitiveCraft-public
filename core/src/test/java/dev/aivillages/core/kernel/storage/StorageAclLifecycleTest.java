package dev.aivillages.core.kernel.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.storage.StorageTestSupport.*;
import static dev.aivillages.core.kernel.storage.StorageRegistry.*;

class StorageAclLifecycleTest {
    @TempDir Path root;
    static final Owner READER=new Owner(OwnerKind.PLAYER,UUID.fromString("d0300000-0000-4000-8000-000000000004"));
    static final class Admin implements Authority {
        final AtomicBoolean current=new AtomicBoolean(true),grant=new AtomicBoolean(true),revoke=new AtomicBoolean(true),acceptTransfer=new AtomicBoolean(true);
        public boolean controls(Caller caller,Owner subject){return current.get() && caller.equals(CALLER) && (subject.equals(OWNER) || subject.equals(CITIZEN));}
        public boolean allows(Caller caller,Action action,StorageRegistry.Record row){return current.get() && caller.equals(CALLER) && switch(action){case GRANT->grant.get();case REVOKE->revoke.get();default->true;};}
        public boolean mayGrant(Caller caller,StorageRegistry.Record row,Grant g){return current.get() && g.permissions().stream().allMatch(p->p==Permission.DEPOSIT);}
        public boolean mayTransfer(Caller caller,StorageRegistry.Record row,Owner target){return acceptTransfer.get();}
    }
    @Test void grantAndRevokeAreSeparateAndRevisionsAdvance() {
        var store=create(root.resolve("data"));try {var a=new Admin();var r=new StorageRegistry(store,a);UUID id=add(r,"private");a.revoke.set(false);
            assertEquals(StorageMetadataStore.Status.ACKNOWLEDGED,await(r.grant(CALLER,1,id,0,new Grant(READER,Set.of(Permission.DEPOSIT)))).status());
            assertEquals(StorageMetadataStore.Status.DENIED,await(r.revoke(CALLER,2,id,1,READER,Set.of(Permission.DEPOSIT))).status());
            a.revoke.set(true);a.grant.set(false);assertEquals(StorageMetadataStore.Status.ACKNOWLEDGED,await(r.revoke(CALLER,2,id,1,READER,Set.of(Permission.DEPOSIT))).status());
            assertTrue(r.snapshot().records().get(id).acl().isEmpty());assertEquals(2,r.snapshot().records().get(id).aclRevision());
        }finally{close(store);}
    }
    @Test void delegationCannotEscalateThroughGrantOrBulkAclReplacement() {
        var store=create(root.resolve("data"));try {var r=new StorageRegistry(store,new Admin());UUID id=add(r,"private");
            assertEquals(StorageMetadataStore.Status.DENIED,await(r.grant(CALLER,1,id,0,new Grant(READER,Set.of(Permission.DEPOSIT,Permission.WITHDRAW)))).status());
            assertEquals(StorageMetadataStore.Status.DENIED,await(r.updateAcl(CALLER,1,id,0,List.of(new Grant(READER,Set.of(Permission.GRANT))))).status());assertEquals(1,r.snapshot().revision());
        }finally{close(store);}
    }
    @Test void authorityWithoutExplicitGrantCeilingCannotSeedOrAddAcl() {
        var store=create(root.resolve("data"));try {Authority authority=new Authority(){public boolean controls(Caller c,Owner o){return true;}public boolean allows(Caller c,Action a,StorageRegistry.Record r){return true;}};
            var r=new StorageRegistry(store,authority);
            assertEquals(StorageMetadataStore.Status.DENIED,await(r.registerMetadata(CALLER,0,OWNER,"private",CHEST,Set.of(),List.of(new Grant(READER,Set.of(Permission.DEPOSIT))))).status());
            UUID id=add(r,"empty");assertEquals(StorageMetadataStore.Status.DENIED,await(r.grant(CALLER,1,id,0,new Grant(READER,Set.of(Permission.DEPOSIT)))).status());
        }finally{close(store);}
    }
    @Test void transferClearsGrantsFencesRolesBindingsAndFormerOwnerAdministration() {
        var store=create(root.resolve("data"));try {var a=new Admin();var r=new StorageRegistry(store,a);UUID id=add(r,"private");
            await(r.grant(CALLER,1,id,0,new Grant(READER,Set.of(Permission.DEPOSIT))));await(r.assignRole(CALLER,2,CITIZEN,Role.DEFAULT,id,-1));
            var next=new Owner(OwnerKind.PLAYER,READER.id());assertEquals(StorageMetadataStore.Status.ACKNOWLEDGED,await(r.transferOwnership(CALLER,3,id,1,next)).status());
            var row=r.snapshot().records().get(id);assertEquals(next,row.owner());assertEquals(2,row.generation());assertEquals(2,row.aclRevision());assertTrue(row.acl().isEmpty());assertEquals(State.UNVERIFIED,row.state());assertFalse(r.snapshot().bindings().containsKey(id));assertTrue(r.roleMetadata(CALLER,CITIZEN,Role.DEFAULT).isEmpty());
            assertEquals(StorageMetadataStore.Status.DENIED,await(r.grant(CALLER,4,id,2,new Grant(READER,Set.of(Permission.DEPOSIT)))).status());
            close(store);store=await(StorageMetadataStore.open(root.resolve("data"),WORLD));assertFalse(store.readOnly());assertEquals(next,store.snapshot().records().get(id).owner());assertTrue(store.snapshot().records().get(id).acl().isEmpty());
        }finally{close(store);}
    }
    @Test void transferRequiresExplicitTargetPolicyAndCurrentRevision() {
        var store=create(root.resolve("data"));try {var a=new Admin();var r=new StorageRegistry(store,a);UUID id=add(r,"private");a.acceptTransfer.set(false);
            assertEquals(StorageMetadataStore.Status.DENIED,await(r.transferOwnership(CALLER,1,id,0,READER)).status());a.acceptTransfer.set(true);
            assertEquals(StorageMetadataStore.Status.REVISION_CONFLICT,await(r.transferOwnership(CALLER,0,id,0,READER)).status());assertEquals(1,r.snapshot().records().get(id).generation());
        }finally{close(store);}
    }
    @Test void duplicateAclSubjectsAndPublicAdministrationGrantRefused() {
        var deposit=new Grant(READER,Set.of(Permission.DEPOSIT));assertThrows(IllegalArgumentException.class,()->new StorageRegistry.Record(WORLD,UUID.randomUUID(),"private",OWNER,Set.of(),List.of(deposit,deposit),0,1,0,State.PENDING_BIND,CHEST));
        assertThrows(IllegalArgumentException.class,()->new Grant(new Owner(OwnerKind.PUBLIC,null),Set.of(Permission.GRANT)));
    }
    @Test void queuedGrantRechecksAuthorityBeforeAnyPendingWrite() throws Exception {
        var a=new Admin();var armed=new AtomicBoolean();var path=root.resolve("data");
        var store=create(path,StorageRegistryLimits.defaults(),cut->{if(armed.get() && cut==StorageMetadataStore.Cut.BEFORE_PENDING)a.current.set(false);},p->Long.MAX_VALUE);
        try {var r=new StorageRegistry(store,a);UUID id=add(r,"private");byte[] before=java.nio.file.Files.readAllBytes(path.resolve(StorageMetadataStore.CURRENT));armed.set(true);
            assertEquals(StorageMetadataStore.Status.DENIED,await(r.grant(CALLER,1,id,0,new Grant(READER,Set.of(Permission.DEPOSIT)))).status());assertArrayEquals(before,java.nio.file.Files.readAllBytes(path.resolve(StorageMetadataStore.CURRENT)));assertFalse(java.nio.file.Files.exists(path.resolve(StorageMetadataStore.PENDING)));assertEquals(1,r.snapshot().revision());
        }finally{close(store);}
    }
    @Test void currentOwnerControlIsRequiredEvenWhenActionPortAllows() {
        var store=create(root.resolve("data"));try {var a=new Admin();var r=new StorageRegistry(store,a);UUID id=add(r,"private");a.current.set(false);
            assertEquals(StorageMetadataStore.Status.DENIED,await(r.grant(CALLER,1,id,0,new Grant(READER,Set.of(Permission.DEPOSIT)))).status());assertEquals(1,r.snapshot().revision());
        }finally{close(store);}
    }
}
