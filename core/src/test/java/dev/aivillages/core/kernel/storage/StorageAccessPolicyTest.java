package dev.aivillages.core.kernel.storage;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.storage.StoragePolicyTestSupport.*;
import static dev.aivillages.core.kernel.storage.StorageRegistry.*;
import static dev.aivillages.core.kernel.storage.StorageAccessPolicy.*;

class StorageAccessPolicyTest {
    @Test void depositOnlyCannotInspectWithdrawOrAdminister() {
        var e=new Environment();e.row(PLAYER_OWNER,e.reader(Permission.DEPOSIT));
        for(var p:Permission.values())assertEquals(p==Permission.DEPOSIT?Result.ALLOW:Result.DENY,e.policy.evaluate(READER_SCOPE,STORAGE,p).result());
    }
    @Test void absentAclAndUnregisteredChestDenyEvenOwner() {
        var e=new Environment();
        for(var p:Permission.values()){assertEquals(Result.DENY,e.policy.evaluate(ADMIN_SCOPE,STORAGE,p).result());assertEquals(Result.DENY,e.policy.evaluate(ADMIN_SCOPE,UUID.randomUUID(),p).result());}
    }
    @Test void publicOwnerRequiresEachExplicitOperation() {
        var e=new Environment();e.row(PUBLIC,new Grant(PUBLIC,Set.of(Permission.DEPOSIT)));
        assertEquals(Result.ALLOW,e.policy.evaluate(READER_SCOPE,STORAGE,Permission.DEPOSIT).result());
        assertEquals(Result.DENY,e.policy.evaluate(READER_SCOPE,STORAGE,Permission.INSPECT).result());assertEquals(Result.DENY,e.policy.evaluate(READER_SCOPE,STORAGE,Permission.WITHDRAW).result());
        e.row(PUBLIC);assertEquals(Result.DENY,e.policy.evaluate(READER_SCOPE,STORAGE,Permission.DEPOSIT).result());
    }
    @Test void nativeProtectionCanVetoAnyModAllow() {
        var e=new Environment();e.row(PLAYER_OWNER,e.reader(Permission.DEPOSIT));var old=e.policy.evaluate(READER_SCOPE,STORAGE,Permission.DEPOSIT);assertTrue(old.authorizesEffect(READER_SCOPE,STORAGE,Permission.DEPOSIT));
        e.nativeDeny.set(true);e.nativeRevision.incrementAndGet();assertFalse(old.authorizesEffect(READER_SCOPE,STORAGE,Permission.DEPOSIT));assertEquals(Result.DENY,e.policy.evaluate(READER_SCOPE,STORAGE,Permission.DEPOSIT).result());
        e.nativeUnknown.set(true);assertEquals(Result.UNKNOWN,e.policy.evaluate(READER_SCOPE,STORAGE,Permission.DEPOSIT).result());
    }
    @Test void twoTickWindowNeverSkipsEffectTimeObservation() {
        var e=new Environment();e.row(PLAYER_OWNER,e.reader(Permission.DEPOSIT));var d=e.policy.evaluate(READER_SCOPE,STORAGE,Permission.DEPOSIT);int calls=e.nativeQueries.get();
        e.tick.addAndGet(2);assertTrue(d.authorizesEffect(READER_SCOPE,STORAGE,Permission.DEPOSIT));assertTrue(e.nativeQueries.get()>calls);assertEquals(d.evaluatedTick()+2,d.validThroughTick());
        e.tick.incrementAndGet();assertFalse(d.authorizesEffect(READER_SCOPE,STORAGE,Permission.DEPOSIT));e.tick.set(99);assertFalse(d.current(READER_SCOPE,STORAGE,Permission.DEPOSIT));
    }
    @Test void scopeActorOperationAndWorldCannotBeSwapped() {
        var e=new Environment();e.row(PLAYER_OWNER,e.reader(Permission.DEPOSIT));var d=e.policy.evaluate(READER_SCOPE,STORAGE,Permission.DEPOSIT);
        for(var s:List.of(new Scope(WORLD,UUID.randomUUID(),READER,null),new Scope(WORLD,DOMAIN,ADMIN,null),new Scope(WORLD,DOMAIN,READER,CITIZEN)))assertFalse(d.authorizesEffect(s,STORAGE,Permission.DEPOSIT));
        assertFalse(d.authorizesEffect(READER_SCOPE,STORAGE,Permission.WITHDRAW));assertFalse(d.authorizesEffect(READER_SCOPE,UUID.randomUUID(),Permission.DEPOSIT));assertEquals(Result.DENY,e.policy.evaluate(new Scope(UUID.randomUUID(),DOMAIN,READER,null),STORAGE,Permission.DEPOSIT).result());
    }
    @Test void citizenGrantRequiresExactControlledActor() {
        var e=new Environment();e.row(PLAYER_OWNER,new Grant(new Owner(OwnerKind.CITIZEN,CITIZEN),Set.of(Permission.DEPOSIT)));
        assertEquals(Result.DENY,e.policy.evaluate(READER_SCOPE,STORAGE,Permission.DEPOSIT).result());var scope=new Scope(WORLD,DOMAIN,READER,CITIZEN);
        var d=e.policy.evaluate(scope,STORAGE,Permission.DEPOSIT);assertEquals(Result.ALLOW,d.result());e.control.set(false);e.controlRevision.incrementAndGet();assertFalse(d.authorizesEffect(scope,STORAGE,Permission.DEPOSIT));
    }
    @Test void organizationRequiresExplicitAclAndCurrentKnownMemberPolicy() {
        var e=new Environment();e.row(ORG_OWNER);assertEquals(Result.DENY,e.policy.evaluate(READER_SCOPE,STORAGE,Permission.DEPOSIT).result());
        e.row(ORG_OWNER,new Grant(ORG_OWNER,Set.of(Permission.DEPOSIT)));var d=e.policy.evaluate(READER_SCOPE,STORAGE,Permission.DEPOSIT);assertEquals(Result.ALLOW,d.result());
        e.membershipState.set(OrganizationMembershipPort.State.UNKNOWN);e.membershipRevision.incrementAndGet();assertFalse(d.current(READER_SCOPE,STORAGE,Permission.DEPOSIT));assertEquals(Result.UNKNOWN,e.policy.evaluate(READER_SCOPE,STORAGE,Permission.DEPOSIT).result());
        e.membershipState.set(OrganizationMembershipPort.State.NON_MEMBER);assertEquals(Result.DENY,e.policy.evaluate(READER_SCOPE,STORAGE,Permission.DEPOSIT).result());
    }
    @Test void membershipRevisionInvalidatesEvenRestoredRights() {
        var e=new Environment();e.row(ORG_OWNER,e.reader(Permission.DEPOSIT));var d=e.policy.evaluate(READER_SCOPE,STORAGE,Permission.DEPOSIT);
        e.membershipRevision.addAndGet(2);assertEquals(Result.ALLOW,e.policy.evaluate(READER_SCOPE,STORAGE,Permission.DEPOSIT).result());assertFalse(d.authorizesEffect(READER_SCOPE,STORAGE,Permission.DEPOSIT));
    }
    @Test void aclRecordAndBindingChangesInvalidateOldDecision() {
        var e=new Environment();e.row(PLAYER_OWNER,e.reader(Permission.DEPOSIT));var d=e.policy.evaluate(READER_SCOPE,STORAGE,Permission.DEPOSIT);var r=e.state.get().records().get(STORAGE);
        e.state.set(snapshot(new StorageRegistry.Record(WORLD,STORAGE,r.label(),r.owner(),r.purposes(),r.acl(),r.revision()+1,r.generation()+1,r.aclRevision()+1,State.ACTIVE,r.adapterId())));
        assertFalse(d.current(READER_SCOPE,STORAGE,Permission.DEPOSIT));assertEquals(Result.ALLOW,e.policy.evaluate(READER_SCOPE,STORAGE,Permission.DEPOSIT).result());
    }
    @Test void pendingUnverifiedAndTombstonedNeverGrant() {
        var e=new Environment();for(var state:List.of(State.PENDING_BIND,State.UNVERIFIED,State.TOPOLOGY_CHANGED,State.TOMBSTONED)){
            var r=record(PLAYER_OWNER,List.of(e.reader(Permission.DEPOSIT)));e.state.set(snapshot(new StorageRegistry.Record(r.world(),r.id(),r.label(),r.owner(),r.purposes(),r.acl(),r.revision(),r.generation(),r.aclRevision(),state,r.adapterId())));assertEquals(Result.DENY,e.policy.evaluate(READER_SCOPE,STORAGE,Permission.DEPOSIT).result());}
    }
    @Test void administrationRequiresCurrentControlNativeVetoAndDelegationCeiling() {
        var e=new Environment();var row=e.state.get().records().get(STORAGE);
        assertTrue(e.policy.mayAdminister(ADMIN_SCOPE,row,Permission.GRANT,Set.of(Permission.DEPOSIT)));assertFalse(e.policy.mayAdminister(ADMIN_SCOPE,row,Permission.GRANT,Set.of(Permission.WITHDRAW)));
        assertFalse(e.policy.mayAdminister(READER_SCOPE,row,Permission.GRANT,Set.of(Permission.DEPOSIT)));e.control.set(false);assertFalse(e.policy.mayAdminister(ADMIN_SCOPE,row,Permission.GRANT,Set.of()));e.control.set(true);e.nativeDeny.set(true);assertFalse(e.policy.mayAdminister(ADMIN_SCOPE,row,Permission.REVOKE,Set.of()));
    }
    @Test void citizenAndPublicAdministrationAreNeverInferredFromLabelOrOwnerKind() {
        var e=new Environment();e.row(new Owner(OwnerKind.CITIZEN,CITIZEN));var citizen=e.state.get().records().get(STORAGE);assertTrue(e.policy.mayAdminister(ADMIN_SCOPE,citizen,Permission.GRANT,Set.of(Permission.DEPOSIT)));
        e.row(PUBLIC);var pub=e.state.get().records().get(STORAGE);e.control.set(false);assertFalse(e.policy.mayAdminister(ADMIN_SCOPE,pub,Permission.GRANT,Set.of()));
    }
    @Test void wireProjectionExpiresAndCannotMintDecisionAuthority() {
        var e=new Environment();e.row(PLAYER_OWNER,e.reader(Permission.DEPOSIT));var d=e.policy.evaluate(READER_SCOPE,STORAGE,Permission.DEPOSIT);var b=d.wire().body();assertEquals("ALLOW",b.get("decision"));assertEquals(102L,b.get("valid_through_tick"));
        assertEquals(0,StorageAccessPolicy.Decision.class.getConstructors().length);assertThrows(UnsupportedOperationException.class,()->b.put("decision","DENY"));
    }
    @Test void currentNativeProjectionMustMatchExactScopeTickGenerationAndOperation() {
        var e=new Environment();e.row(PLAYER_OWNER,e.reader(Permission.DEPOSIT));
        for(int variant=0;variant<4;variant++){
            final int v=variant;var policy=new StorageAccessPolicy(e.state::get,(sc,ids)->null,(sc,o)->null,(sc,r,p,t)->new Protection(v==0?ADMIN_SCOPE:sc,r.id(),v==1?Permission.INSPECT:p,0,v==2?t-1:t,v==3?r.generation()+1:r.generation(),Result.ALLOW),e.tick::get);
            assertEquals(Result.UNKNOWN,policy.evaluate(READER_SCOPE,STORAGE,Permission.DEPOSIT).result());
        }
    }
    @Test void organizationProjectionIsBoundedAndObservedOncePerEvaluation() {
        var e=new Environment();var grants=new ArrayList<Grant>();for(int i=0;i<32;i++)grants.add(new Grant(new Owner(OwnerKind.ORGANIZATION,new UUID(1,i)),Set.of(Permission.DEPOSIT)));
        e.row(ORG_OWNER,grants.toArray(Grant[]::new));assertEquals(Result.ALLOW,e.policy.evaluate(READER_SCOPE,STORAGE,Permission.DEPOSIT).result());assertEquals(1,e.orgQueries.get());assertEquals(1,e.nativeQueries.get());assertEquals(0,e.controlQueries.get());
        var oversized=new HashMap<UUID,OrganizationMembershipPort.Membership>();for(int i=0;i<34;i++)oversized.put(new UUID(1,i),OrganizationMembershipPort.Membership.unknown());assertThrows(IllegalArgumentException.class,()->new OrganizationMembershipPort.View(READER_SCOPE,0,oversized));
    }
    @Test void swappedOrganizationPrincipalCannotAuthorizeMembership() {
        var e=new Environment();e.row(ORG_OWNER,new Grant(ORG_OWNER,Set.of(Permission.DEPOSIT)));
        var policy=new StorageAccessPolicy(e.state::get,(sc,ids)->new OrganizationMembershipPort.View(ADMIN_SCOPE,0,Map.of(ORG,new OrganizationMembershipPort.Membership(OrganizationMembershipPort.State.MEMBER,Set.of(Permission.DEPOSIT),Set.of(),false))),(sc,o)->null,(sc,r,p,t)->new Protection(sc,r.id(),p,0,t,r.generation(),Result.ALLOW),e.tick::get);
        assertEquals(Result.UNKNOWN,policy.evaluate(READER_SCOPE,STORAGE,Permission.DEPOSIT).result());
    }
}
