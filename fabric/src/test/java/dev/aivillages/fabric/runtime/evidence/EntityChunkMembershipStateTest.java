package dev.aivillages.fabric.runtime.evidence;

import dev.aivillages.core.kernel.runtime.evidence.*;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;
import java.util.*;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class EntityChunkMembershipStateTest {
    final String dim="minecraft:overworld";final ChunkPos a=new ChunkPos(4,4),b=new ChunkPos(5,4);
    final UUID entity=UUID.randomUUID(),incarnation=UUID.randomUUID();
    final Marker marker=new Marker("a".repeat(64),"b".repeat(64),MarkerPhase.RESULT,"c".repeat(64));
    NativePickup.Chunk c(ChunkPos p){return new NativePickup.Chunk(dim,p.x(),p.z());}
    EntityChunkMembershipState.Row row(ChunkPos current,boolean present,Marker m,Marker prior){
        return new EntityChunkMembershipState.Row(NativePickup.Role.ACTOR,new NativePickup.Trail(entity,incarnation,List.of(c(a),c(b)),c(current)),4,m,present,prior);
    }
    CompoundTag nativeFrame(ChunkPos chunk,boolean present){
        var root=EntityChunkRecord.empty(chunk);
        if(present){var tag=new CompoundTag();tag.store("UUID",UUIDUtil.CODEC,entity);root.getList("Entities").orElseThrow().add(tag);}return root;
    }
    @Test void exactNativePresenceAndCompleteTrailAreRequiredTogether(){
        var r=row(a,true,marker,null);var saved=EntityChunkRecord.merge(null,nativeFrame(a,true),dim,a,List.of(),List.of(r));
        var frame=EntityChunkRecord.read(saved,dim,a);assertTrue(frame.supported());assertEquals(Set.of(entity),frame.members());
        assertEquals(r,EntityChunkMembershipState.load(frame.metadata(),dim,a,frame.members()).row(entity));
    }
    @Test void departureAndGenuineEmptyNativeRecordRetainFullTrail(){
        var r=row(b,false,marker,null);var saved=EntityChunkRecord.merge(null,null,dim,a,List.of(),List.of(r));
        var frame=EntityChunkRecord.read(saved,dim,a);assertTrue(frame.exists());assertTrue(frame.members().isEmpty());assertTrue(frame.supported());
        assertEquals(r,EntityChunkMembershipState.load(frame.metadata(),dim,a,frame.members()).row(entity));
    }
    @Test void contradictoryNativeMembershipStaysQuarantinedAfterLaterAbsence(){
        var saved=EntityChunkRecord.merge(null,nativeFrame(a,true),dim,a,List.of(),List.of(row(b,false,marker,null)));
        assertFalse(EntityChunkRecord.read(saved,dim,a).supported());
        var later=EntityChunkRecord.merge(saved,null,dim,a,List.of(),List.of());assertFalse(EntityChunkRecord.read(later,dim,a).supported());
        assertTrue(later.contains(EntityChunkMembershipState.TAG+"_original"));
    }
    @Test void legitimateDepartureAndReentryRequireTheSameTrackedLifetime(){
        var initial=EntityChunkRecord.merge(null,nativeFrame(a,true),dim,a,List.of(),List.of(row(a,true,marker,null)));
        var gone=EntityChunkRecord.merge(initial,nativeFrame(a,false),dim,a,List.of(),List.of(row(b,false,marker,null)));
        assertTrue(EntityChunkRecord.read(gone,dim,a).supported());
        var back=EntityChunkRecord.merge(gone,nativeFrame(a,true),dim,a,List.of(),List.of(row(a,true,marker,null)));
        assertTrue(EntityChunkRecord.read(back,dim,a).supported());
    }
    @Test void laterDepositMustNameItsActualPickupPredecessor(){
        var initial=EntityChunkRecord.merge(null,nativeFrame(a,true),dim,a,List.of(),List.of(row(a,true,marker,null)));
        var d=new Marker("d".repeat(64),"e".repeat(64),MarkerPhase.RESULT,"f".repeat(64));
        assertThrows(IllegalArgumentException.class,()->EntityChunkRecord.merge(initial,nativeFrame(a,true),dim,a,List.of(),List.of(row(a,true,d,null))));
        var next=EntityChunkRecord.merge(initial,nativeFrame(a,true),dim,a,List.of(),List.of(row(a,true,d,marker)));
        assertTrue(EntityChunkRecord.read(next,dim,a).supported());
    }
    @Test void unknownNativeMembershipIsPreservedWithoutAuthority(){
        var unknown=nativeFrame(a,false);unknown.putInt(EntityChunkMembershipState.TAG,77);
        var next=EntityChunkRecord.merge(unknown,null,dim,a,List.of(),List.of());
        assertFalse(EntityChunkRecord.read(next,dim,a).supported());assertEquals(77,next.getInt(EntityChunkMembershipState.TAG).orElseThrow());
    }
    @Test void wrongChunkAndReplacementIncarnationCannotAdoptTheOldTrail(){
        var initial=EntityChunkRecord.merge(null,nativeFrame(a,true),dim,a,List.of(),List.of(row(a,true,marker,null)));
        assertFalse(EntityChunkRecord.read(initial,dim,b).supported());
        var replacement=new EntityChunkMembershipState.Row(NativePickup.Role.ACTOR,new NativePickup.Trail(entity,UUID.randomUUID(),List.of(c(a)),c(a)),4,marker,true,null);
        assertThrows(IllegalArgumentException.class,()->EntityChunkRecord.merge(initial,nativeFrame(a,true),dim,a,List.of(),List.of(replacement)));
    }
}
