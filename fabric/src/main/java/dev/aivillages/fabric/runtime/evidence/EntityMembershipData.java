package dev.aivillages.fabric.runtime.evidence;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;
import dev.aivillages.core.kernel.runtime.evidence.NativePickup;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.*;

/** Actual entity serialization and movement only. Restored data cannot resume an original callback. */
public final class EntityMembershipData {
    private final Entity entity;private EntityMembershipTrail trail;
    private Marker marker;private long revision;private boolean membership;private Dynamic<?> opaque;
    public EntityMembershipData(Entity entity){this.entity=Objects.requireNonNull(entity);}
    private NativePickup.Chunk chunk(){var p=entity.chunkPosition();return new NativePickup.Chunk(entity.level().dimension().identifier().toString(),p.x(),p.z());}
    public EntityMembershipTrail begin(UUID incarnation) {
        if(trail==null){trail=new EntityMembershipTrail(entity.getUUID(),incarnation,chunk());if(opaque!=null)trail.gap();}
        else if(trail.complete() && !trail.capture().incarnation().equals(incarnation))trail.gap();
        trail.observe(this::moved);return trail;
    }
    public EntityMembershipTrail trail(){return trail;}
    static boolean definitiveDeparture(Entity.RemovalReason reason) {
        return reason==Entity.RemovalReason.KILLED || reason==Entity.RemovalReason.DISCARDED;
    }
    /** A native destruction after a classified result changes membership, never goal credit. */
    public void removed(Entity.RemovalReason reason) {
        if(!(entity.level() instanceof ServerLevel) || !entity.isRemoved() || entity.getRemovalReason()!=reason
                || !definitiveDeparture(reason) || trail==null || !trail.complete() || !membership || marker==null)return;
        var state=((DepositParticipant)entity).cognitivecraft$depositParticipant();
        if(!state.valid() || state.revision()!=revision || state.marker()==null
                || state.marker().phase()!=MarkerPhase.RESULT || !state.marker().equals(marker)){trail.gap();return;}
        moved(trail.capture());
    }
    public void result(Marker marker,long revision,boolean membership) {
        if(marker.phase()!=MarkerPhase.RESULT)throw new IllegalArgumentException("Native trail result marker");
        this.marker=marker;this.revision=revision;this.membership=membership;
        if(trail!=null)trail.observe(this::moved);
    }
    private void moved(NativePickup.Trail captured) {
        if(!membership || marker==null || !(entity.level() instanceof ServerLevel level))return;
        var state=((DepositParticipant)entity).cognitivecraft$depositParticipant();
        if(!state.valid() || state.revision()!=revision || state.marker()==null
                || state.marker().phase()==MarkerPhase.RESULT && !state.marker().equals(marker)){trail.gap();return;}
        var storage=NativeEntityStorage.get(level);if(storage==null || !storage.available()){trail.gap();return;}
        var role=entity instanceof ItemEntity?NativePickup.Role.DROP:NativePickup.Role.ACTOR;
        for(var c:captured.chunks())if(!storage.stageMembership(new ChunkPos(c.x(),c.z()),new EntityChunkMembershipState.Row(role,captured,revision,marker,
                !entity.isRemoved() && c.equals(captured.current()),null))){trail.gap();return;}
    }
    public void save(ValueOutput output,DepositParticipantState state) {
        if(opaque!=null){output.store(EntityTrailRecord.TAG,Codec.PASSTHROUGH,opaque);return;}
        if(trail!=null && marker!=null && state.valid())output.putString(EntityTrailRecord.TAG,new EntityTrailRecord(trail.description(),marker,revision,trail.complete(),membership).encode());
    }
    public void load(ValueInput input,DepositParticipantState state) {
        opaque=input.read(EntityTrailRecord.TAG,Codec.PASSTHROUGH).orElse(null);if(opaque==null)return;
        try {
            var saved=EntityTrailRecord.decode(input.getString(EntityTrailRecord.TAG).orElseThrow());var t=saved.trail();
            if(!state.restored() || !state.valid() || !entity.getUUID().equals(t.entity()) || !state.incarnation().equals(t.incarnation())
                    || state.revision()!=saved.revision() || !chunk().equals(t.current()) || state.marker()==null
                    || state.marker().phase()==MarkerPhase.RESULT && !state.marker().equals(saved.marker()))throw new IllegalArgumentException("Unbound native trail");
            trail=EntityMembershipTrail.restored(t,saved.complete());marker=saved.marker();revision=saved.revision();membership=saved.membership();opaque=null;
            trail.observe(this::moved);
        }catch(RuntimeException invalid){
            if(trail!=null)trail.gap();
        }
    }
}
