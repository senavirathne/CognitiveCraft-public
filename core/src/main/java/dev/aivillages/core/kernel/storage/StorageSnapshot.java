package dev.aivillages.core.kernel.storage;

import java.util.*;

/** Permission-filtered immutable live projection; contains no receipt or save-confirmation claim. */
public sealed interface StorageSnapshot permits StorageSnapshot.Visible,StorageSnapshot.Unavailable {
    enum Unavailable implements StorageSnapshot { INSTANCE }
    record Participant(UUID incarnation,long markerGeneration,int slots){
        public Participant{Objects.requireNonNull(incarnation);if(markerGeneration<1 || slots!=27)throw new IllegalArgumentException("participant identity");}
    }
    record Slot(UUID participant,int index,StorageCapabilities.Item item,int count,int limit){
        public Slot{new StorageCapabilities.Slot(participant,index,item,count,limit,limit,false,false);}
    }
    record Visible(UUID world,UUID storage,long generation,UUID epoch,long tick,List<Participant> participants,List<Slot> slots,String inventoryRevision) implements StorageSnapshot {
        public Visible{
            Objects.requireNonNull(world);Objects.requireNonNull(storage);Objects.requireNonNull(epoch);participants=List.copyOf(participants);slots=List.copyOf(slots);Objects.requireNonNull(inventoryRevision);
            if(generation<1 || tick<0 || participants.isEmpty() || participants.size()>2 || slots.size()!=27*participants.size() || !inventoryRevision.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("snapshot bounds");
            var ids=new HashSet<UUID>();for(var p:participants)if(!ids.add(p.incarnation()))throw new IllegalArgumentException("duplicate participant");
            var seen=new HashSet<String>();for(var slot:slots)if(!ids.contains(slot.participant()) || !seen.add(slot.participant()+":"+slot.index()))throw new IllegalArgumentException("duplicate/foreign native slot");
        }
    }
}
