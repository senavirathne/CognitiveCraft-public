package dev.aivillages.core.kernel.storage;

import java.util.*;
import java.util.concurrent.atomic.*;
import static dev.aivillages.core.kernel.storage.StorageRegistry.*;
import static dev.aivillages.core.kernel.storage.StorageBindingRules.*;
import static dev.aivillages.core.kernel.storage.StorageAccessPolicy.*;

/** Synthetic policy projections only; these values confer no native authority or inventory proof. */
final class StoragePolicyTestSupport {
    static final UUID WORLD=UUID.fromString("d0300000-0000-4000-8000-000000000001"),DOMAIN=UUID.fromString("d0300000-0000-4000-8000-000000000002"),ADMIN=UUID.fromString("d0300000-0000-4000-8000-000000000003"),READER=UUID.fromString("d0300000-0000-4000-8000-000000000004"),CITIZEN=UUID.fromString("d0300000-0000-4000-8000-000000000005"),ORG=UUID.fromString("d0300000-0000-4000-8000-000000000006"),OTHER_ORG=UUID.fromString("d0300000-0000-4000-8000-000000000007"),STORAGE=UUID.fromString("d0300000-0000-4000-8000-000000000008"),MARKER=UUID.fromString("d0300000-0000-4000-8000-000000000009");
    static final Owner PLAYER_OWNER=new Owner(OwnerKind.PLAYER,ADMIN),READER_OWNER=new Owner(OwnerKind.PLAYER,READER),ORG_OWNER=new Owner(OwnerKind.ORGANIZATION,ORG),PUBLIC=new Owner(OwnerKind.PUBLIC,null);
    static final Scope READER_SCOPE=new Scope(WORLD,DOMAIN,READER,null),ADMIN_SCOPE=new Scope(WORLD,DOMAIN,ADMIN,null);
    static StorageRegistry.Record record(Owner owner,List<Grant> acl) { return new StorageRegistry.Record(WORLD,STORAGE,"Private stock",owner,Set.of(),acl,2,1,0,State.ACTIVE,SINGLE); }
    static Binding binding(UUID id,long generation,String status) { return new Binding(id,generation,DOMAIN,0,status,new Topology(WORLD,"minecraft:overworld",SINGLE,"NORTH",List.of(new Member(0,64,0,MARKER,1)))); }
    static Snapshot snapshot(StorageRegistry.Record record) { return new Snapshot(WORLD,3,Map.of(record.id(),record),Map.of(),record.state()==State.ACTIVE?Map.of(record.id(),binding(record.id(),record.generation(),"VERIFIED_LIVE").wire()):Map.of()); }
    static final class Environment {
        final AtomicReference<Snapshot> state=new AtomicReference<>(snapshot(record(PLAYER_OWNER,List.of())));
        final AtomicLong tick=new AtomicLong(100),membershipRevision=new AtomicLong(),controlRevision=new AtomicLong(),nativeRevision=new AtomicLong();
        final AtomicReference<OrganizationMembershipPort.State> membershipState=new AtomicReference<>(OrganizationMembershipPort.State.MEMBER);
        final AtomicBoolean control=new AtomicBoolean(true),nativeUnknown=new AtomicBoolean(),nativeDeny=new AtomicBoolean();
        final AtomicInteger orgQueries=new AtomicInteger(),controlQueries=new AtomicInteger(),nativeQueries=new AtomicInteger();
        final AtomicReference<Set<Permission>> delegation=new AtomicReference<>(Set.of(Permission.DEPOSIT));
        final StorageAccessPolicy policy;
        Environment() { policy=new StorageAccessPolicy(state::get,(scope,ids)->{
            orgQueries.incrementAndGet();var result=new HashMap<UUID,OrganizationMembershipPort.Membership>();
            for(var id:ids){var membership=scope.principal().equals(ADMIN)?OrganizationMembershipPort.State.MEMBER:membershipState.get();var permissions=membership==OrganizationMembershipPort.State.MEMBER?EnumSet.allOf(Permission.class):Set.<Permission>of();result.put(id,new OrganizationMembershipPort.Membership(membership,permissions,scope.principal().equals(ADMIN)?delegation.get():Set.of(),scope.principal().equals(ADMIN)));}
            return new OrganizationMembershipPort.View(scope,membershipRevision.get(),result);
        },(scope,owner)->{
            controlQueries.incrementAndGet();return new Control(scope,owner,controlRevision.get(),true,control.get(),control.get()?delegation.get():Set.of());
        },(scope,row,operation,now)->{
            nativeQueries.incrementAndGet();return new Protection(scope,row.id(),operation,nativeRevision.get(),now,row.generation(),nativeUnknown.get()?Result.UNKNOWN:nativeDeny.get()?Result.DENY:Result.ALLOW);
        },tick::get); }
        void row(Owner owner,Grant... grants) { state.set(snapshot(record(owner,List.of(grants)))); }
        Grant reader(Permission... permissions) { return new Grant(READER_OWNER,Set.of(permissions)); }
    }
    private StoragePolicyTestSupport() { }
}
