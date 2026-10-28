package dev.aivillages.core.kernel.storage;

import java.util.*;
import java.util.function.*;
import dev.aivillages.core.kernel.storage.contract.*;
import static dev.aivillages.core.kernel.storage.StorageRegistry.*;

/** Pure mod-actor ACL policy. No cache, scheduler, native effects, player claims or implicit owner permissions. */
public final class StorageAccessPolicy {
    public record Scope(UUID world,UUID domain,UUID principal,UUID actorCitizen) {
        public Scope { Objects.requireNonNull(world);Objects.requireNonNull(domain);Objects.requireNonNull(principal); }
        Map<String,Object> body() { return Map.of("world_id",world.toString(),"domain_id",domain.toString(),"principal_id",principal.toString()); }
    }
    public enum Result { ALLOW, DENY, UNKNOWN }
    public record Control(Scope scope,Owner subject,long revision,boolean known,boolean controlled,Set<Permission> delegable) {
        public Control {
            Objects.requireNonNull(scope);Objects.requireNonNull(subject);delegable=Set.copyOf(delegable);
            if(revision<0 || revision>StorageRegistryLimits.MAX_INTEGER || (!known || !controlled) && !delegable.isEmpty() || !known && controlled)throw new IllegalArgumentException("control projection");
        }
    }
    @FunctionalInterface public interface ControlPort { Control observe(Scope scope,Owner subject); }
    public record Protection(Scope scope,UUID storage,Permission operation,long revision,long tick,long generation,Result result) {
        public Protection {
            Objects.requireNonNull(scope);Objects.requireNonNull(storage);Objects.requireNonNull(operation);Objects.requireNonNull(result);
            if(revision<0 || revision>StorageRegistryLimits.MAX_INTEGER || tick<0 || tick>StorageRegistryLimits.MAX_INTEGER || generation<1 || generation>StorageRegistryLimits.MAX_INTEGER)throw new IllegalArgumentException("native projection bounds");
        }
    }
    /** J01 must freshly observe verified live identity and native protection together. Missing/unknown vetoes. */
    @FunctionalInterface public interface ProtectionPort { Protection observe(Scope scope,StorageRegistry.Record record,Permission operation,long tick); }
    private record Facts(OrganizationMembershipPort.View organizations,Map<Owner,Control> controls,Protection nativePolicy) { }
    private record Stamp(long registry,long record,long generation,long acl,Owner owner,State state) { }
    public final class Decision {
        private final Scope scope;private final UUID storage;private final Permission operation;private final Result result;
        private final long tick,through;private final Stamp stamp;private final Facts facts;
        private Decision(Scope scope,UUID storage,Permission operation,Result result,long tick,Stamp stamp,Facts facts) {
            this.scope=scope;this.storage=storage;this.operation=operation;this.result=result;this.tick=tick;this.through=Math.min(StorageRegistryLimits.MAX_INTEGER,tick+2);this.stamp=stamp;this.facts=facts;
        }
        public Result result() { return result; }
        public long evaluatedTick() { return tick; }
        public long validThroughTick() { return through; }
        /** Server-side projection only. Client presentation must use StorageVisibility, which hides refusals. */
        public StorageWire.Message wire() {
            var body=new TreeMap<String,Object>();body.put("scope",scope.body());body.put("storage_id",storage.toString());body.put("operation",operation.name());body.put("decision",result.name());
            body.put("registry_revision",stamp.registry());body.put("acl_revision",stamp.acl());body.put("binding_generation",stamp.generation());
            body.put("membership_revision",facts.organizations()==null?null:facts.organizations().revision());body.put("evaluated_tick",tick);body.put("valid_through_tick",through);
            body.put("reason",result==Result.ALLOW?null:result==Result.UNKNOWN?"UNKNOWN_WORLD_STATE":"UNAUTHORIZED");
            return StorageCodec.decode(StorageMetadataCodec.canonical(Map.of("schema",StorageWire.VERSION,"kind","StorageAccessDecision","body",body)));
        }
        /** Always re-observes policy/identity, even inside the two-tick window. No wire ALLOW is accepted. */
        public boolean current(Scope requestedScope,UUID requestedStorage,Permission requestedOperation) {
            long now=clock.getAsLong();if(now<tick || now>through || !scope.equals(requestedScope) || !storage.equals(requestedStorage) || operation!=requestedOperation)return false;
            try { Decision fresh=evaluate(requestedScope,requestedStorage,requestedOperation);
                return fresh.tick>=tick && fresh.tick<=through && stamp.equals(fresh.stamp) && result==fresh.result && sameFacts(facts,fresh.facts);
            }catch(RuntimeException unavailable){return false;}
        }
        public boolean authorizesEffect(Scope requestedScope,UUID requestedStorage,Permission requestedOperation) { return result==Result.ALLOW && current(requestedScope,requestedStorage,requestedOperation); }
    }
    private final Supplier<Snapshot> snapshots;private final OrganizationMembershipPort organizations;private final ControlPort controls;private final ProtectionPort protection;private final LongSupplier clock;
    public StorageAccessPolicy(Supplier<Snapshot> snapshots,OrganizationMembershipPort organizations,ControlPort controls,ProtectionPort protection,LongSupplier clock) {
        this.snapshots=Objects.requireNonNull(snapshots);this.organizations=Objects.requireNonNull(organizations);this.controls=Objects.requireNonNull(controls);this.protection=Objects.requireNonNull(protection);this.clock=Objects.requireNonNull(clock);
    }
    public Decision evaluate(Scope scope,UUID storage,Permission operation) {
        Objects.requireNonNull(scope);Objects.requireNonNull(storage);Objects.requireNonNull(operation);
        long tick=clock.getAsLong();if(tick<0 || tick>StorageRegistryLimits.MAX_INTEGER)throw new IllegalStateException("server tick range");
        Snapshot snapshot=snapshots.get();var row=snapshot.records().get(storage);
        var stamp=row==null?new Stamp(snapshot.revision(),0,1,0,null,null):new Stamp(snapshot.revision(),row.revision(),row.generation(),row.aclRevision(),row.owner(),row.state());
        var empty=new Facts(null,Map.of(),null);
        if(!scope.world().equals(snapshot.world()) || row==null || row.state()!=State.ACTIVE)return new Decision(scope,storage,operation,Result.DENY,tick,stamp,empty);
        // A registered metadata reference is not itself a live native identity or a grant.
        var raw=snapshot.bindings().get(storage);
        if(raw==null || !StorageBindingRules.Binding.from(raw).status().equals("VERIFIED_LIVE"))return new Decision(scope,storage,operation,Result.UNKNOWN,tick,stamp,empty);
        try {
        var orgIds=new HashSet<UUID>();if(row.owner().kind()==OwnerKind.ORGANIZATION)orgIds.add(row.owner().id());
        for(var grant:row.acl())if(grant.subject().kind()==OwnerKind.ORGANIZATION && grant.permissions().contains(operation))orgIds.add(grant.subject().id());
        var membership=orgIds.isEmpty()?null:organizations.observe(scope,Set.copyOf(orgIds));
        var observedControls=new HashMap<Owner,Control>();
        if(membership!=null && (!membership.scope().equals(scope) || !orgIds.containsAll(membership.memberships().keySet())))return new Decision(scope,storage,operation,Result.UNKNOWN,tick,stamp,empty);
        boolean granted=false,unknown=false;
        for(var grant:row.acl()) {
            if(!grant.permissions().contains(operation))continue;var subject=grant.subject();
            boolean matches=switch(subject.kind()) {
                case PUBLIC -> true;
                case PLAYER -> subject.id().equals(scope.principal());
                case CITIZEN -> subject.id().equals(scope.actorCitizen()) && controlled(scope,subject,observedControls);
                case ORGANIZATION -> membership!=null && membership.membership(subject.id()).state()==OrganizationMembershipPort.State.MEMBER && membership.membership(subject.id()).permitted().contains(operation);
            };
            granted|=matches;
            if(subject.kind()==OwnerKind.ORGANIZATION && (membership==null || membership.membership(subject.id()).state()==OrganizationMembershipPort.State.UNKNOWN))unknown=true;
        }
        if(row.owner().kind()==OwnerKind.ORGANIZATION) {
            var ownerPolicy=membership==null?OrganizationMembershipPort.Membership.unknown():membership.membership(row.owner().id());
            unknown|=ownerPolicy.state()==OrganizationMembershipPort.State.UNKNOWN;
            granted&=ownerPolicy.state()!=OrganizationMembershipPort.State.UNKNOWN && ownerPolicy.permitted().contains(operation);
        }
        if(operation==Permission.GRANT || operation==Permission.REVOKE)granted&=administrator(scope,row.owner(),membership,observedControls);
        var nativePolicy=protection.observe(scope,row,operation,tick);boolean nativeKnown=validNative(nativePolicy,scope,row,operation,tick);
        var facts=new Facts(membership,Map.copyOf(observedControls),nativePolicy);
        Result result=!nativeKnown || nativePolicy.result()==Result.UNKNOWN || unknown?Result.UNKNOWN:granted && nativePolicy.result()==Result.ALLOW?Result.ALLOW:Result.DENY;
        return new Decision(scope,storage,operation,result,tick,stamp,facts);
        }catch(RuntimeException unavailable){return new Decision(scope,storage,operation,Result.UNKNOWN,tick,stamp,empty);}
    }
    /** Metadata administration is separate from automation ACL grants, so an empty private ACL can be administered. */
    public boolean mayAdminister(Scope scope,StorageRegistry.Record row,Permission operation,Set<Permission> proposedPermissions) {
        if(operation!=Permission.GRANT && operation!=Permission.REVOKE)throw new IllegalArgumentException("administrative operation");
        try {
        long tick=clock.getAsLong();Snapshot current=snapshots.get();
        if(!scope.world().equals(current.world()) || !row.equals(current.records().get(row.id())) || row.state()==State.TOMBSTONED)return false;
        var ids=row.owner().kind()==OwnerKind.ORGANIZATION?Set.of(row.owner().id()):Set.<UUID>of();var members=ids.isEmpty()?null:organizations.observe(scope,ids);
        if(members!=null && !members.scope().equals(scope))return false;
        var observed=new HashMap<Owner,Control>();if(!administrator(scope,row.owner(),members,observed))return false;
        Set<Permission> ceiling=row.owner().kind()==OwnerKind.ORGANIZATION?members.membership(row.owner().id()).delegable():observed.get(row.owner()).delegable();
        var veto=protection.observe(scope,row,operation,tick);
        return ceiling.containsAll(proposedPermissions) && validNative(veto,scope,row,operation,tick) && veto.result()==Result.ALLOW;
        }catch(RuntimeException unavailable){return false;}
    }
    private boolean controlled(Scope scope,Owner subject,Map<Owner,Control> observed) {
        Control fact=observed.computeIfAbsent(subject,s->controls.observe(scope,s));
        return fact!=null && fact.scope().equals(scope) && fact.subject().equals(subject) && fact.known() && fact.controlled();
    }
    private boolean administrator(Scope scope,Owner owner,OrganizationMembershipPort.View membership,Map<Owner,Control> observed) {
        return switch(owner.kind()) {
            case ORGANIZATION -> membership!=null && membership.membership(owner.id()).state()==OrganizationMembershipPort.State.MEMBER && membership.membership(owner.id()).administrator();
            case PLAYER -> owner.id().equals(scope.principal()) && controlled(scope,owner,observed);
            case CITIZEN,PUBLIC -> controlled(scope,owner,observed);
        };
    }
    private static boolean validNative(Protection p,Scope scope,StorageRegistry.Record row,Permission op,long tick) { return p!=null && p.scope().equals(scope) && p.storage().equals(row.id()) && p.operation()==op && p.generation()==row.generation() && p.tick()==tick; }
    private static boolean sameFacts(Facts old,Facts current) {
        if(!Objects.equals(old.organizations(),current.organizations()) || !old.controls().equals(current.controls()))return false;
        Protection a=old.nativePolicy(),b=current.nativePolicy();
        return a==null?b==null:b!=null && a.scope().equals(b.scope()) && a.storage().equals(b.storage()) && a.operation()==b.operation() && a.revision()==b.revision() && a.generation()==b.generation() && a.result()==b.result();
    }
}
