package dev.aivillages.core.kernel.storage;

import dev.aivillages.core.kernel.storage.contract.StorageWire;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** One metadata owner, with pure administrative policy; no native registration or item ledger. */
public final class StorageRegistry {
    public enum OwnerKind { PLAYER, CITIZEN, ORGANIZATION, PUBLIC }
    public enum Role { DEFAULT, FOOD, MATERIALS, OUTPUT }
    public enum Permission { INSPECT, DEPOSIT, WITHDRAW, ASSIGN, RENAME, GRANT, REVOKE }
    public enum State { PENDING_BIND, ACTIVE, UNVERIFIED, TOPOLOGY_CHANGED, TOMBSTONED }
    public enum Action { REGISTER, READ, RENAME, ASSIGN, GRANT, REVOKE, TOMBSTONE, BIND, COMMIT_BIND, TRANSFER }
    public record Owner(OwnerKind kind, UUID id) {
        public Owner { Objects.requireNonNull(kind); if ((kind == OwnerKind.PUBLIC) != (id == null)) throw new IllegalArgumentException("owner identity"); }
    }
    public record Caller(UUID world, UUID principal) {
        public Caller { Objects.requireNonNull(world); Objects.requireNonNull(principal); }
    }
    public record Grant(Owner subject, Set<Permission> permissions) {
        public Grant {
            Objects.requireNonNull(subject); permissions = Set.copyOf(permissions);
            if (permissions.isEmpty() || (subject.kind() == OwnerKind.PUBLIC
                    && !Set.of(Permission.INSPECT,Permission.DEPOSIT,Permission.WITHDRAW).containsAll(permissions)))
                throw new IllegalArgumentException("invalid metadata grant");
        }
    }
    public record Record(UUID world, UUID id, String label, Owner owner, Set<Role> purposes,
                         List<Grant> acl, long revision, long generation, long aclRevision,
                         State state, String adapterId) {
        public Record {
            Objects.requireNonNull(world); Objects.requireNonNull(id); Objects.requireNonNull(owner); Objects.requireNonNull(state);
            label = StorageRegistry.label(label); purposes = Set.copyOf(purposes); acl = List.copyOf(acl);
            var subjects=new HashSet<Owner>();for(var grant:acl)if(!subjects.add(grant.subject()))throw new IllegalArgumentException("duplicate ACL subject");
            if (acl.size() > StorageRegistryLimits.MAX_GRANTS || purposes.size() > StorageRegistryLimits.MAX_ROLES
                    || revision < 0 || revision > StorageRegistryLimits.MAX_INTEGER || generation < 1 || generation > StorageRegistryLimits.MAX_INTEGER
                    || aclRevision < 0 || aclRevision > StorageRegistryLimits.MAX_INTEGER
                    || !Set.of("minecraft:chest_single/v1","minecraft:chest_double/v1","minecraft:barrel/v1").contains(adapterId))
                throw new IllegalArgumentException("invalid storage metadata");
        }
        Record rename(String value) { return new Record(world,id,value,owner,purposes,acl,next(revision),generation,aclRevision,state,adapterId); }
        Record acl(List<Grant> value) { return new Record(world,id,label,owner,purposes,value,next(revision),generation,next(aclRevision),state,adapterId); }
        Record tombstone() { return new Record(world,id,label,owner,purposes,List.of(),next(revision),next(generation),next(aclRevision),State.TOMBSTONED,adapterId); }
    }
    public record RoleKey(Owner beneficiary, Role role) {
        public RoleKey { Objects.requireNonNull(beneficiary); Objects.requireNonNull(role); if (beneficiary.kind() == OwnerKind.PUBLIC) throw new IllegalArgumentException("PUBLIC has no default roles"); }
    }
    public record Assignment(UUID world, RoleKey key, UUID storage, long storageGeneration, long revision) {
        public Assignment {
            Objects.requireNonNull(world); Objects.requireNonNull(key); Objects.requireNonNull(storage);
            if (storageGeneration < 1 || storageGeneration > StorageRegistryLimits.MAX_INTEGER || revision < 0 || revision > StorageRegistryLimits.MAX_INTEGER)
                throw new IllegalArgumentException("invalid role revision/generation");
        }
    }
    public record Snapshot(UUID world, long revision, Map<UUID,Record> records,
                           Map<RoleKey,Assignment> assignments, Map<UUID,StorageWire.Message> bindings) {
        public Snapshot {
            Objects.requireNonNull(world); records = Map.copyOf(records); assignments = Map.copyOf(assignments); bindings = Map.copyOf(bindings);
            if (revision < 0 || revision > StorageRegistryLimits.MAX_INTEGER || records.size() > StorageRegistryLimits.MAX_RECORDS
                    || assignments.size() > StorageRegistryLimits.MAX_ASSIGNMENTS || bindings.size() > records.size())
                throw new IllegalArgumentException("snapshot bounds");
            for (var entry : records.entrySet()) if (!entry.getKey().equals(entry.getValue().id()) || !world.equals(entry.getValue().world()))
                throw new IllegalArgumentException("record world/key mismatch");
            for (var entry : assignments.entrySet()) {
                Assignment a = entry.getValue(); Record target = records.get(a.storage());
                if (!entry.getKey().equals(a.key()) || !world.equals(a.world()) || target == null || a.storageGeneration() > target.generation())
                    throw new IllegalArgumentException("role target/world mismatch");
            }
            for (var entry : bindings.entrySet()) {
                var b = entry.getValue(); var target = records.get(entry.getKey());
                if (target == null || b.kind() != StorageWire.Kind.PhysicalStorageBinding
                        || !world.toString().equals(b.body().get("world_id")) || !entry.getKey().toString().equals(b.body().get("storage_id"))
                        || !target.adapterId().equals(b.body().get("adapter_id")) || target.generation() != (Long)b.body().get("binding_generation"))
                    throw new IllegalArgumentException("binding reference mismatch");
            }
            for (Record record : records.values()) if (record.state() == State.ACTIVE && !bindings.containsKey(record.id()))
                throw new IllegalArgumentException("active metadata requires a binding reference, not a native validity claim");
        }
        public static Snapshot empty(UUID world) { return new Snapshot(world,0,Map.of(),Map.of(),Map.of()); }
        Snapshot replace(Record record) {
            var next = new HashMap<>(records); next.put(record.id(),record);
            var refs = new HashMap<>(bindings); if (record.state() == State.TOMBSTONED) refs.remove(record.id());
            return new Snapshot(world,StorageRegistry.next(revision),next,assignments,refs);
        }
        Snapshot assign(Assignment assignment) {
            var next = new HashMap<>(assignments); next.put(assignment.key(),assignment);
            return new Snapshot(world,StorageRegistry.next(revision),records,next,bindings);
        }
    }
    /** A pure, fresh server-adapter policy. No permissive default, native object or serialized authority. */
    public interface Authority {
        boolean controls(Caller caller, Owner subject);
        boolean allows(Caller caller, Action action, Record record);
        /** Explicit operation/subject delegation ceiling. Absence never supplies grant authority. */
        default boolean mayGrant(Caller caller,Record record,Grant grant) { return false; }
        /** J01 supplies current target acceptance/policy in addition to old-owner control. */
        default boolean mayTransfer(Caller caller,Record record,Owner target) { return false; }
    }
    static final class Refused extends RuntimeException {
        final StorageMetadataStore.Status status;
        Refused(StorageMetadataStore.Status status, String reason) { super(reason); this.status = status; }
    }
    private final StorageMetadataStore store;
    private final Authority authority;
    public StorageRegistry(StorageMetadataStore store, Authority authority) { this.store = Objects.requireNonNull(store); this.authority = Objects.requireNonNull(authority); }
    public Snapshot snapshot() { return store.snapshot(); }
    public CompletionStage<StorageMetadataStore.Ack> registerMetadata(Caller caller, long expectedRevision, Owner owner,
            String label, String adapterId, Set<Role> purposes, List<Grant> acl) {
        String normalized = label(label); var roles = Set.copyOf(purposes); var grants = List.copyOf(acl);
        return store.transact(expectedRevision,snapshot -> {
            scope(caller,snapshot); require(authority.controls(caller,owner),StorageMetadataStore.Status.DENIED,"owner administration denied");
            require(snapshot.records().size() < store.limits().records(),StorageMetadataStore.Status.LIMIT,"record limit includes tombstones");
            UUID id; do { id = UUID.randomUUID(); } while (snapshot.records().containsKey(id));
            Record record = new Record(snapshot.world(),id,normalized,owner,roles,grants,0,1,0,State.PENDING_BIND,adapterId);
            require(grants.stream().allMatch(g -> authority.mayGrant(caller,record,g)),StorageMetadataStore.Status.DENIED,"registration grant ceiling denied");
            require(authority.allows(caller,Action.REGISTER,record),StorageMetadataStore.Status.DENIED,"registration metadata denied");
            return new StorageMetadataStore.Change(snapshot.replace(record),id,() -> authority.controls(caller,owner) && authority.allows(caller,Action.REGISTER,record) && grants.stream().allMatch(g -> authority.mayGrant(caller,record,g)));
        });
    }
    public CompletionStage<StorageMetadataStore.Ack> rename(Caller caller, long expectedRevision, UUID storage, long recordRevision, String label) {
        String normalized = label(label);
        return store.transact(expectedRevision,snapshot -> {
            Record old = record(caller,snapshot,storage,recordRevision,Action.RENAME);
            return new StorageMetadataStore.Change(snapshot.replace(old.rename(normalized)),storage,() -> authority.allows(caller,Action.RENAME,old));
        });
    }
    public CompletionStage<StorageMetadataStore.Ack> updateAcl(Caller caller, long expectedRevision, UUID storage, long recordRevision, List<Grant> grants) {
        var next = List.copyOf(grants);
        return store.transact(expectedRevision,snapshot -> {
            Record old = record(caller,snapshot,storage,recordRevision,Action.GRANT);
            require(authority.controls(caller,old.owner()) && authority.allows(caller,Action.REVOKE,old) && next.stream().allMatch(g -> authority.mayGrant(caller,old,g)),StorageMetadataStore.Status.DENIED,"current administration, revoke and grant ceiling required");
            return new StorageMetadataStore.Change(snapshot.replace(old.acl(next)),storage,() -> authority.controls(caller,old.owner()) && authority.allows(caller,Action.GRANT,old) && authority.allows(caller,Action.REVOKE,old) && next.stream().allMatch(g -> authority.mayGrant(caller,old,g)));
        });
    }
    public CompletionStage<StorageMetadataStore.Ack> grant(Caller caller,long expectedRevision,UUID storage,long recordRevision,Grant grant) {
        Objects.requireNonNull(grant);
        return store.transact(expectedRevision,snapshot -> {
            Record old=record(caller,snapshot,storage,recordRevision,Action.GRANT);
            require(authority.controls(caller,old.owner()) && authority.mayGrant(caller,old,grant),StorageMetadataStore.Status.DENIED,"current administrator and grant ceiling required");
            var grants=new ArrayList<Grant>();var permissions=EnumSet.copyOf(grant.permissions());
            for(var existing:old.acl())if(existing.subject().equals(grant.subject()))permissions.addAll(existing.permissions());else grants.add(existing);
            var combined=new Grant(grant.subject(),permissions);grants.add(combined);
            require(grants.size()<=StorageRegistryLimits.MAX_GRANTS,StorageMetadataStore.Status.LIMIT,"ACL grant bound");
            return new StorageMetadataStore.Change(snapshot.replace(old.acl(grants)),storage,() -> authority.controls(caller,old.owner()) && authority.allows(caller,Action.GRANT,old) && authority.mayGrant(caller,old,grant));
        });
    }
    public CompletionStage<StorageMetadataStore.Ack> revoke(Caller caller,long expectedRevision,UUID storage,long recordRevision,Owner subject,Set<Permission> permissions) {
        Objects.requireNonNull(subject);var removed=Set.copyOf(permissions);if(removed.isEmpty())throw new IllegalArgumentException("empty revoke");
        return store.transact(expectedRevision,snapshot -> {
            Record old=record(caller,snapshot,storage,recordRevision,Action.REVOKE);
            require(authority.controls(caller,old.owner()),StorageMetadataStore.Status.DENIED,"current administrator required");
            var grants=new ArrayList<Grant>();boolean found=false;
            for(var grant:old.acl())if(grant.subject().equals(subject)) {
                var kept=EnumSet.copyOf(grant.permissions());found=!Collections.disjoint(kept,removed);kept.removeAll(removed);if(!kept.isEmpty())grants.add(new Grant(subject,kept));
            }else grants.add(grant);
            require(found,StorageMetadataStore.Status.INVALID,"missing granted operation");
            return new StorageMetadataStore.Change(snapshot.replace(old.acl(grants)),storage,() -> authority.controls(caller,old.owner()) && authority.allows(caller,Action.REVOKE,old));
        });
    }
    public CompletionStage<StorageMetadataStore.Ack> transferOwnership(Caller caller,long expectedRevision,UUID storage,long recordRevision,Owner target) {
        Objects.requireNonNull(target);
        return store.transact(expectedRevision,snapshot -> {
            Record old=record(caller,snapshot,storage,recordRevision,Action.TRANSFER);
            require(!old.owner().equals(target),StorageMetadataStore.Status.INVALID,"same owner");
            require(authority.controls(caller,old.owner()) && authority.mayTransfer(caller,old,target),StorageMetadataStore.Status.DENIED,"current administrator and target policy required");
            var updated=new Record(old.world(),old.id(),old.label(),target,old.purposes(),List.of(),next(old.revision()),next(old.generation()),next(old.aclRevision()),State.UNVERIFIED,old.adapterId());
            var rows=new HashMap<>(snapshot.records());rows.put(storage,updated);var refs=new HashMap<>(snapshot.bindings());refs.remove(storage);
            return new StorageMetadataStore.Change(new Snapshot(snapshot.world(),next(snapshot.revision()),rows,snapshot.assignments(),refs),storage,() -> authority.controls(caller,old.owner()) && authority.allows(caller,Action.TRANSFER,old) && authority.mayTransfer(caller,old,target));
        });
    }
    public CompletionStage<StorageMetadataStore.Ack> tombstone(Caller caller, long expectedRevision, UUID storage, long recordRevision) {
        return store.transact(expectedRevision,snapshot -> {
            Record old = record(caller,snapshot,storage,recordRevision,Action.TOMBSTONE);
            return new StorageMetadataStore.Change(snapshot.replace(old.tombstone()),storage,() -> authority.allows(caller,Action.TOMBSTONE,old));
        });
    }
    /** expectedAssignmentRevision=-1 creates; nonnegative explicitly reassigns exactly that revision. */
    public CompletionStage<StorageMetadataStore.Ack> assignRole(Caller caller, long expectedRevision, Owner beneficiary,
            Role role, UUID storage, long expectedAssignmentRevision) {
        RoleKey key = new RoleKey(beneficiary,role);
        return store.transact(expectedRevision,snapshot -> {
            scope(caller,snapshot); Record target = snapshot.records().get(storage);
            require(target != null && target.state() != State.TOMBSTONED,StorageMetadataStore.Status.INVALID,"role target unavailable");
            require(authority.controls(caller,beneficiary) && authority.allows(caller,Action.ASSIGN,target),StorageMetadataStore.Status.DENIED,"beneficiary and target authority required");
            Assignment old = snapshot.assignments().get(key);
            require((old == null && expectedAssignmentRevision == -1) || (old != null && old.revision() == expectedAssignmentRevision),StorageMetadataStore.Status.REVISION_CONFLICT,"role assignment revision conflict");
            require(old != null || snapshot.assignments().size() < store.limits().assignments(),StorageMetadataStore.Status.LIMIT,"assignment limit");
            Assignment next = new Assignment(snapshot.world(),key,storage,target.generation(),old == null ? 0 : next(old.revision()));
            return new StorageMetadataStore.Change(snapshot.assign(next),storage,() -> authority.controls(caller,beneficiary) && authority.allows(caller,Action.ASSIGN,target));
        });
    }
    /** Durable intent before any native write. BIND policy must be provided by the server owner. */
    public CompletionStage<StorageMetadataStore.Ack> prepareBinding(Caller caller,long expectedRevision,UUID storage,long recordRevision,StorageWire.Message proposal) {
        var intent=StorageBindingRules.Binding.from(proposal);
        return store.transact(expectedRevision,snapshot -> {
            Record old=record(caller,snapshot,storage,recordRevision,Action.BIND);
            long generation=snapshot.bindings().containsKey(storage)?next(old.generation()):old.generation();
            require(intent.storage().equals(storage) && intent.topology().world().equals(snapshot.world()) && intent.generation()==generation
                    && intent.status().equals("UNKNOWN"),StorageMetadataStore.Status.INVALID,"binding intent identity/generation/status");
            var updated=new Record(old.world(),old.id(),old.label(),old.owner(),old.purposes(),old.acl(),next(old.revision()),generation,old.aclRevision(),
                    snapshot.bindings().containsKey(storage)?State.UNVERIFIED:State.PENDING_BIND,intent.topology().adapter());
            var rows=new HashMap<>(snapshot.records());rows.put(storage,updated);var refs=new HashMap<>(snapshot.bindings());refs.put(storage,proposal);
            return new StorageMetadataStore.Change(new Snapshot(snapshot.world(),next(snapshot.revision()),rows,snapshot.assignments(),refs),storage,() -> authority.allows(caller,Action.BIND,old));
        });
    }
    /** Native owner attests a fresh observation via COMMIT_BIND policy; this is journal acknowledgement, not a participant save. */
    public CompletionStage<StorageMetadataStore.Ack> acknowledgeBinding(Caller caller,long expectedRevision,UUID storage,long recordRevision,StorageWire.Message observation) {
        var live=StorageBindingRules.Binding.from(observation);
        return store.transact(expectedRevision,snapshot -> {
            Record old=record(caller,snapshot,storage,recordRevision,Action.COMMIT_BIND);
            var raw=snapshot.bindings().get(storage);require(raw!=null,StorageMetadataStore.Status.INVALID,"missing binding intent");
            var intent=StorageBindingRules.Binding.from(raw);
            require(intent.status().equals("UNKNOWN") && live.status().equals("VERIFIED_LIVE") && intent.epoch().equals(live.epoch())
                    && StorageBindingRules.sameIdentity(intent,live),StorageMetadataStore.Status.INVALID,"observation differs from durable intent");
            var updated=new Record(old.world(),old.id(),old.label(),old.owner(),old.purposes(),old.acl(),next(old.revision()),old.generation(),old.aclRevision(),State.ACTIVE,old.adapterId());
            var rows=new HashMap<>(snapshot.records());rows.put(storage,updated);var refs=new HashMap<>(snapshot.bindings());refs.put(storage,observation);
            return new StorageMetadataStore.Change(new Snapshot(snapshot.world(),next(snapshot.revision()),rows,snapshot.assignments(),refs),storage,() -> authority.allows(caller,Action.COMMIT_BIND,old));
        });
    }
    public List<Record> queryVisible(Caller caller, String label) {
        Snapshot snapshot = store.snapshot(); if (!caller.world().equals(snapshot.world())) return List.of();
        String key = StorageLabelFold.key(label(label));
        return snapshot.records().values().stream().filter(r -> r.state() != State.TOMBSTONED && StorageLabelFold.key(r.label()).equals(key)
                && authority.allows(caller,Action.READ,r)).sorted(Comparator.comparing(r -> r.id().toString())).toList();
    }
    /** Metadata lookup only: callers must still resolve/reobserve the physical target in D02/J. */
    public Optional<Record> roleMetadata(Caller caller, Owner beneficiary, Role role) {
        Snapshot snapshot = store.snapshot(); if (!caller.world().equals(snapshot.world()) || !authority.controls(caller,beneficiary)) return Optional.empty();
        Assignment a = snapshot.assignments().get(new RoleKey(beneficiary,role));
        Record r = a == null ? null : snapshot.records().get(a.storage());
        return r != null && r.state() != State.TOMBSTONED && r.generation() == a.storageGeneration()
                && authority.allows(caller,Action.READ,r) ? Optional.of(r) : Optional.empty();
    }
    private Record record(Caller caller, Snapshot snapshot, UUID id, long expected, Action action) {
        scope(caller,snapshot); Record old = snapshot.records().get(id);
        require(old != null && old.state() != State.TOMBSTONED,StorageMetadataStore.Status.INVALID,"metadata target unavailable");
        require(old.revision() == expected,StorageMetadataStore.Status.REVISION_CONFLICT,"record revision conflict");
        require(authority.allows(caller,action,old),StorageMetadataStore.Status.DENIED,"metadata operation denied"); return old;
    }
    private static void scope(Caller caller, Snapshot snapshot) { require(caller.world().equals(snapshot.world()),StorageMetadataStore.Status.DENIED,"world mismatch"); }
    static void require(boolean condition, StorageMetadataStore.Status status, String reason) { if (!condition) throw new Refused(status,reason); }
    static long next(long revision) { if (revision >= StorageRegistryLimits.MAX_INTEGER) throw new Refused(StorageMetadataStore.Status.LIMIT,"revision exhausted"); return revision+1; }
    static String label(String value) {
        Objects.requireNonNull(value); String normalized = Normalizer.normalize(value,Normalizer.Form.NFC);
        if (normalized.isBlank() || normalized.codePointCount(0,normalized.length()) > 64
                || normalized.codePoints().anyMatch(c -> c < 32 || c == 127 || (c >= 0xD800 && c <= 0xDFFF)))
            throw new IllegalArgumentException("invalid storage label");
        return normalized;
    }
}
