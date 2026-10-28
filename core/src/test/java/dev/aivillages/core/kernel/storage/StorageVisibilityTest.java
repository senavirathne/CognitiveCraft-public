package dev.aivillages.core.kernel.storage;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.storage.StoragePolicyTestSupport.*;
import static dev.aivillages.core.kernel.storage.StorageRegistry.*;

class StorageVisibilityTest {
    @Test void foreignEnumerationOmitsPrivateLabelsIdsAndCounts() {
        var e=new Environment();var v=new StorageVisibility(e.state::get,e.policy);assertEquals(List.of(),v.enumerate(READER_SCOPE,Permission.INSPECT));
        assertEquals(List.of(),v.named(READER_SCOPE,Permission.INSPECT,"Private stock"));
    }
    @Test void privateAndAbsentHaveIdenticalUnavailableResult() {
        var e=new Environment();var v=new StorageVisibility(e.state::get,e.policy);
        assertSame(StorageVisibility.Unavailable.INSTANCE,v.lookup(READER_SCOPE,STORAGE,Permission.INSPECT));assertSame(v.lookup(READER_SCOPE,STORAGE,Permission.INSPECT),v.lookup(READER_SCOPE,UUID.randomUUID(),Permission.INSPECT));
    }
    @Test void explicitDepositShareCanBeSelectedWithoutStockInspection() {
        var e=new Environment();e.row(PLAYER_OWNER,e.reader(Permission.DEPOSIT));var v=new StorageVisibility(e.state::get,e.policy);
        var selected=(StorageVisibility.Visible)v.lookup(READER_SCOPE,STORAGE,Permission.DEPOSIT);assertEquals(STORAGE,selected.storage());assertEquals("Private stock",selected.label());
        assertSame(StorageVisibility.Unavailable.INSTANCE,v.lookup(READER_SCOPE,STORAGE,Permission.INSPECT));assertEquals(List.of(),v.enumerate(READER_SCOPE,Permission.WITHDRAW));
        assertEquals(Set.of("storage","label","adapter","generation"),Arrays.stream(StorageVisibility.Visible.class.getRecordComponents()).map(c->c.getName()).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void revokedShareDisappearsFromEveryLookup() {
        var e=new Environment();e.row(PLAYER_OWNER,e.reader(Permission.DEPOSIT));var v=new StorageVisibility(e.state::get,e.policy);assertEquals(1,v.enumerate(READER_SCOPE,Permission.DEPOSIT).size());e.row(PLAYER_OWNER);
        assertEquals(List.of(),v.enumerate(READER_SCOPE,Permission.DEPOSIT));assertSame(StorageVisibility.Unavailable.INSTANCE,v.lookup(READER_SCOPE,STORAGE,Permission.DEPOSIT));
    }
    @Test void visibleNamesUseNfcCasefoldAndNeverAddHiddenAmbiguity() {
        var e=new Environment();e.row(PLAYER_OWNER,e.reader(Permission.DEPOSIT));var row=e.state.get().records().get(STORAGE);
        var shared=new StorageRegistry.Record(WORLD,STORAGE,"Café",row.owner(),row.purposes(),row.acl(),row.revision(),row.generation(),row.aclRevision(),row.state(),row.adapterId());
        var hidden=new StorageRegistry.Record(WORLD,UUID.randomUUID(),"CAFÉ",row.owner(),Set.of(),List.of(),2,1,0,State.ACTIVE,row.adapterId());
        e.state.set(new Snapshot(WORLD,3,Map.of(shared.id(),shared,hidden.id(),hidden),Map.of(),Map.of(shared.id(),binding(shared.id(),1,"VERIFIED_LIVE").wire(),hidden.id(),binding(hidden.id(),1,"VERIFIED_LIVE").wire())));
        var v=new StorageVisibility(e.state::get,e.policy);assertEquals(List.of(new StorageVisibility.Visible(shared.id(),"Café",shared.adapterId(),1)),v.named(READER_SCOPE,Permission.DEPOSIT,"CAFE\u0301"));
    }
    @Test void unknownMembershipAndNativeVetoRevealNoExistence() {
        var e=new Environment();e.row(ORG_OWNER,e.reader(Permission.DEPOSIT));e.membershipState.set(OrganizationMembershipPort.State.UNKNOWN);var v=new StorageVisibility(e.state::get,e.policy);
        assertEquals(List.of(),v.enumerate(READER_SCOPE,Permission.DEPOSIT));assertSame(StorageVisibility.Unavailable.INSTANCE,v.lookup(READER_SCOPE,STORAGE,Permission.DEPOSIT));
        e.membershipState.set(OrganizationMembershipPort.State.MEMBER);e.nativeDeny.set(true);assertSame(StorageVisibility.Unavailable.INSTANCE,v.lookup(READER_SCOPE,STORAGE,Permission.DEPOSIT));
        var failing=new StorageAccessPolicy(e.state::get,(sc,ids)->{throw new IllegalStateException("unavailable projection");},(sc,o)->null,(sc,r,p,t)->null,e.tick::get);
        assertEquals(StorageAccessPolicy.Result.UNKNOWN,failing.evaluate(READER_SCOPE,STORAGE,Permission.DEPOSIT).result());
        var redacted=new StorageVisibility(e.state::get,failing);assertSame(redacted.lookup(READER_SCOPE,STORAGE,Permission.DEPOSIT),redacted.lookup(READER_SCOPE,UUID.randomUUID(),Permission.DEPOSIT));assertEquals(List.of(),redacted.enumerate(READER_SCOPE,Permission.DEPOSIT));
    }
    @Test void visibilityDoesNotImplyOwnershipPermissionOrMutableSnapshot() {
        var e=new Environment();e.row(PUBLIC,new Grant(PUBLIC,Set.of(Permission.DEPOSIT)));var v=new StorageVisibility(e.state::get,e.policy);var list=v.enumerate(READER_SCOPE,Permission.DEPOSIT);
        assertThrows(UnsupportedOperationException.class,()->list.clear());assertFalse(e.policy.mayAdminister(READER_SCOPE,e.state.get().records().get(STORAGE),Permission.GRANT,Set.of(Permission.WITHDRAW)));
    }
    @Test void changedSnapshotDuringObservationDoesNotPublishMixedResults() {
        var e=new Environment();e.row(PLAYER_OWNER,e.reader(Permission.DEPOSIT));var first=e.state.get();var counter=new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<Snapshot> changing=()->counter.incrementAndGet()==1?first:Snapshot.empty(WORLD);
        var v=new StorageVisibility(changing,e.policy);assertEquals(List.of(),v.enumerate(READER_SCOPE,Permission.DEPOSIT));
    }
}
