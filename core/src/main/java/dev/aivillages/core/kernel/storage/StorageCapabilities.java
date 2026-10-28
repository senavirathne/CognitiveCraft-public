package dev.aivillages.core.kernel.storage;

import java.util.*;

/** Finite, pure MVP eligibility. A computed capacity is a live observation, never inventory or save authority. */
public final class StorageCapabilities {
    public enum Type { SINGLE_CHEST, DOUBLE_CHEST, BARREL, TRAPPED_CHEST, ENDER_CHEST, SHULKER, HOPPER, FURNACE, MODDED, UNKNOWN }
    public enum Refusal { NONE, UNSUPPORTED, UNKNOWN, UNLOADED, TOPOLOGY, LOOT_TABLE, LOCKED, COMPONENTS, SLOT_POLICY, PROTECTION, CAPACITY }
    public record Item(String registryId,String fingerprint,int maxStack,boolean defaultComponents,boolean containerItem){
        public Item{Objects.requireNonNull(registryId);Objects.requireNonNull(fingerprint);if(!registryId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || registryId.length()>192 || !fingerprint.matches("[0-9a-f]{64}") || maxStack<1 || maxStack>99)throw new IllegalArgumentException("native item identity/limit");}
    }
    public record Slot(UUID participant,int index,Item item,int count,int limit,int insertionLimit,boolean insert,boolean remove){
        public Slot{Objects.requireNonNull(participant);if(index<0 || index>=27 || count<0 || count>99 || limit<1 || limit>99 || insertionLimit<1 || insertionLimit>99 || (item==null)!=(count==0))throw new IllegalArgumentException("native slot shape");}
    }
    public record Facts(Type type,boolean known,boolean loaded,boolean completeTopology,boolean lootTable,boolean locked,boolean protectedAllowed,List<Slot> slots){
        public Facts{Objects.requireNonNull(type);slots=List.copyOf(slots);if(slots.size()>54)throw new IllegalArgumentException("bounded slot facts");}
    }
    public record Eligibility(Refusal refusal,int matchingItems,int insertionCapacity){public boolean eligible(){return refusal==Refusal.NONE;}}
    public static boolean supported(Type type){return Set.of(Type.SINGLE_CHEST,Type.DOUBLE_CHEST,Type.BARREL).contains(type);}
    public static Eligibility evaluate(Facts f,Item probe){
        Objects.requireNonNull(f);Objects.requireNonNull(probe);
        if(!supported(f.type()))return refused(Refusal.UNSUPPORTED);
        if(!f.known())return refused(Refusal.UNKNOWN);if(!f.loaded())return refused(Refusal.UNLOADED);if(!f.completeTopology())return refused(Refusal.TOPOLOGY);
        if(!f.protectedAllowed())return refused(Refusal.PROTECTION);if(f.lootTable())return refused(Refusal.LOOT_TABLE);if(f.locked())return refused(Refusal.LOCKED);
        if(!probe.defaultComponents() || probe.containerItem())return refused(Refusal.COMPONENTS);
        var seen=new HashSet<String>();var participants=new HashSet<UUID>();int matching=0,capacity=0;
        if(f.slots().size()!=(f.type()==Type.DOUBLE_CHEST?54:27))return refused(Refusal.SLOT_POLICY);
        for(var slot:f.slots()){
            if(!seen.add(slot.participant()+":"+slot.index()))return refused(Refusal.SLOT_POLICY);participants.add(slot.participant());var item=slot.item();
            if(item!=null && (!item.defaultComponents() || item.containerItem()))return refused(Refusal.COMPONENTS);
            if(item!=null && slot.count()>Math.min(slot.limit(),item.maxStack()))return refused(Refusal.SLOT_POLICY);
            boolean same=item!=null && item.registryId().equals(probe.registryId()) && item.fingerprint().equals(probe.fingerprint());
            if(same && slot.remove())matching+=slot.count();
            if(slot.insert() && (item==null || same))capacity+=Math.max(0,Math.min(slot.insertionLimit(),probe.maxStack())-slot.count());
        }
        if(participants.size()!=(f.type()==Type.DOUBLE_CHEST?2:1))return refused(Refusal.TOPOLOGY);
        return new Eligibility(Refusal.NONE,matching,capacity);
    }
    private static Eligibility refused(Refusal refusal){return new Eligibility(refusal,0,0);}
    private StorageCapabilities(){}
}
