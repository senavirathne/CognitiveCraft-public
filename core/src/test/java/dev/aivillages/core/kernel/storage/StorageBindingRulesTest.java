package dev.aivillages.core.kernel.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.storage.StorageBindingRules.*;
import static dev.aivillages.core.kernel.storage.StorageTestSupport.*;

class StorageBindingRulesTest {
    @TempDir Path directory;
    private static final UUID ID=UUID.fromString("00000000-0000-4000-8000-000000000001"), SECOND=UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static Member member(int x,int y,int z,UUID id) { return new Member(x,y,z,id,1); }
    private static Topology single(UUID marker) { return new Topology(WORLD,"minecraft:overworld",SINGLE,"NORTH",List.of(member(1,64,1,marker))); }
    private static Binding binding(UUID storage,long generation,String status,Topology topology) { return new Binding(storage,generation,ID,0,status,topology); }
    @Test void orderedDigestMatchesFrozenCanonicalBytes() {
        var a=member(1,64,1,ID);var b=member(2,64,1,SECOND);
        var left=new Topology(WORLD,"minecraft:overworld",DOUBLE,"NORTH",List.of(a,b));var right=new Topology(WORLD,"minecraft:overworld",DOUBLE,"NORTH",List.of(b,a));
        assertEquals(left,right);assertEquals(left.digest(),right.digest());
        assertEquals(left,Binding.from(binding(SECOND,1,"UNKNOWN",left).wire()).topology());
        assertNotEquals(left.digest(),new Topology(WORLD,"minecraft:overworld",DOUBLE,"SOUTH",List.of(a,b)).digest());
    }
    @Test void sameCoordinateNewIncarnationRejectsOldEndpoint() {
        var a=binding(ID,1,"VERIFIED_LIVE",single(ID));var b=binding(ID,1,"VERIFIED_LIVE",single(SECOND));
        assertFalse(currentEndpoint(a,b));assertTrue(currentEndpoint(a,a));
    }
    @Test void nonAdjacentOrVerticalDoubleChestRejected() {
        for (var second:List.of(member(4,64,1,SECOND),member(1,65,1,SECOND),member(1,64,2,SECOND)))
            assertThrows(IllegalArgumentException.class,()->new Topology(WORLD,"minecraft:overworld",DOUBLE,"NORTH",List.of(member(1,64,1,ID),second)));
    }
    @Test void duplicateIncarnationAndWrongTypeOrFacingRefused() {
        assertThrows(IllegalArgumentException.class,()->new Topology(WORLD,"minecraft:overworld",DOUBLE,"NORTH",List.of(member(1,64,1,ID),member(2,64,1,ID))));
        assertThrows(IllegalArgumentException.class,()->new Topology(WORLD,"minecraft:overworld",SINGLE,"UP",List.of(member(1,64,1,ID))));
        assertDoesNotThrow(()->new Topology(WORLD,"minecraft:overworld",BARREL,"UP",List.of(member(1,64,1,ID))));
    }
    @Test void generationEpochAndUnknownStatusRefuseStaleEndpoint() {
        var a=binding(ID,1,"VERIFIED_LIVE",single(ID));
        assertFalse(currentEndpoint(a,binding(ID,2,"VERIFIED_LIVE",single(ID))));
        assertFalse(currentEndpoint(a,new Binding(ID,1,SECOND,0,"VERIFIED_LIVE",single(ID))));
        assertFalse(currentEndpoint(a,binding(ID,1,"UNKNOWN",single(ID))));
    }
    @Test void topologyMembersCannotBeMutatedByCaller() {
        var input=new ArrayList<>(List.of(member(1,64,1,ID)));var t=new Topology(WORLD,"minecraft:overworld",SINGLE,"NORTH",input);input.clear();
        assertEquals(1,t.members().size());assertThrows(UnsupportedOperationException.class,()->t.members().clear());
    }
    @Test void durableIntentReopensPendingAndCannotGrantLiveAccess() {
        var store=create(directory.resolve("store"));var registry=new StorageRegistry(store,new Policy());UUID id=add(registry,"pending");
        var intent=binding(id,1,"UNKNOWN",single(ID));
        assertEquals(StorageMetadataStore.Status.ACKNOWLEDGED,await(registry.prepareBinding(CALLER,registry.snapshot().revision(),id,0,intent.wire())).status());
        close(store);store=await(StorageMetadataStore.open(directory.resolve("store"),WORLD));
        assertFalse(store.readOnly());assertEquals(StorageRegistry.State.PENDING_BIND,store.snapshot().records().get(id).state());
        assertEquals("UNKNOWN",Binding.from(store.snapshot().bindings().get(id)).status());close(store);
    }
    @Test void mismatchedObservationAndStaleRevisionCannotPublish() {
        var store=create(directory.resolve("store"));try {
            var registry=new StorageRegistry(store,new Policy());UUID id=add(registry,"test");
            assertEquals(StorageMetadataStore.Status.INVALID,await(registry.acknowledgeBinding(CALLER,1,id,0,binding(id,1,"VERIFIED_LIVE",single(ID)).wire())).status());
            assertEquals(StorageMetadataStore.Status.ACKNOWLEDGED,await(registry.prepareBinding(CALLER,1,id,0,binding(id,1,"UNKNOWN",single(ID)).wire())).status());
            assertEquals(StorageMetadataStore.Status.INVALID,await(registry.acknowledgeBinding(CALLER,2,id,1,binding(id,1,"VERIFIED_LIVE",single(SECOND)).wire())).status());
            assertEquals(StorageMetadataStore.Status.REVISION_CONFLICT,await(registry.acknowledgeBinding(CALLER,1,id,1,binding(id,1,"VERIFIED_LIVE",single(ID)).wire())).status());
        } finally { close(store); }
    }
    @Test void authorizedRebindAdvancesGenerationAndLeavesRoleStale() {
        var store=create(directory.resolve("store"));try {
            var registry=new StorageRegistry(store,new Policy());UUID id=add(registry,"test");
            await(registry.prepareBinding(CALLER,1,id,0,binding(id,1,"UNKNOWN",single(ID)).wire()));
            await(registry.acknowledgeBinding(CALLER,2,id,1,binding(id,1,"VERIFIED_LIVE",single(ID)).wire()));
            await(registry.assignRole(CALLER,3,CITIZEN,StorageRegistry.Role.DEFAULT,id,-1));
            assertTrue(registry.roleMetadata(CALLER,CITIZEN,StorageRegistry.Role.DEFAULT).isPresent());
            assertEquals(StorageMetadataStore.Status.ACKNOWLEDGED,await(registry.prepareBinding(CALLER,4,id,2,binding(id,2,"UNKNOWN",single(SECOND)).wire())).status());
            assertEquals(StorageRegistry.State.UNVERIFIED,registry.snapshot().records().get(id).state());
            assertTrue(registry.roleMetadata(CALLER,CITIZEN,StorageRegistry.Role.DEFAULT).isEmpty());
            assertEquals(2,registry.snapshot().records().get(id).generation());
        } finally { close(store); }
    }
    @Test void revokedAuthorityRefusesIntentAndPublication() {
        var store=create(directory.resolve("store"));try {
            var policy=new Policy();var registry=new StorageRegistry(store,policy);UUID id=add(registry,"test");policy.allow.set(false);
            assertEquals(StorageMetadataStore.Status.DENIED,await(registry.prepareBinding(CALLER,1,id,0,binding(id,1,"UNKNOWN",single(ID)).wire())).status());
            policy.allow.set(true);await(registry.prepareBinding(CALLER,1,id,0,binding(id,1,"UNKNOWN",single(ID)).wire()));policy.allow.set(false);
            assertEquals(StorageMetadataStore.Status.DENIED,await(registry.acknowledgeBinding(CALLER,2,id,1,binding(id,1,"VERIFIED_LIVE",single(ID)).wire())).status());
        } finally { close(store); }
    }
}
