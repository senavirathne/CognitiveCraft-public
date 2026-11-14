package dev.aivillages.fabric.runtime.evidence;

import dev.aivillages.core.kernel.runtime.evidence.*;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
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
    @Test void stagedPickupAndDepositKeepTheSavedPredecessorAcrossBothNativeWriteOrders(){
        var pickup=new Marker("d".repeat(64),"e".repeat(64),MarkerPhase.RESULT,"f".repeat(64));
        var deposit=new Marker("1".repeat(64),"2".repeat(64),MarkerPhase.RESULT,"3".repeat(64));
        for(boolean savePickup:List.of(false,true)) {
            var saved=new AtomicReference<>(EntityChunkRecord.merge(null,nativeFrame(a,true),dim,a,List.of(),List.of(row(a,true,marker,null))));
            var port=new EntityStorageEvidence(new EntityStorageEvidence.Access(){
                public CompletableFuture<Optional<CompoundTag>> read(ChunkPos pos){return CompletableFuture.completedFuture(Optional.of(saved.get().copy()));}
                public CompletableFuture<Void> write(ChunkPos pos,CompoundTag root){
                    saved.set(root.copy());
                    return CompletableFuture.completedFuture(null);
                }
            },dim,Runnable::run);
            assertTrue(port.stageMembership(a,row(a,true,pickup,marker)));
            if(savePickup)port.write(a,nativeFrame(a,true)).join();
            assertFalse(port.stageMembership(a,row(a,true,deposit,marker)));
            assertTrue(port.stageMembership(a,row(a,true,deposit,pickup)));
            port.write(a,nativeFrame(a,true)).join();
            assertEquals(row(a,true,deposit,pickup),port.readFrame(a).join().membership(entity));
            assertTrue(port.stageMembership(a,row(b,false,deposit,null)));
            port.write(a,nativeFrame(a,false)).join();
            assertEquals(row(b,false,deposit,pickup),port.readFrame(a).join().membership(entity));
        }
    }
    @Test void unknownNativeMembershipIsPreservedWithoutAuthority(){
        var unknown=nativeFrame(a,false);unknown.putInt(EntityChunkMembershipState.TAG,77);
        var next=EntityChunkRecord.merge(unknown,null,dim,a,List.of(),List.of());
        assertFalse(EntityChunkRecord.read(next,dim,a).supported());assertEquals(77,next.getInt(EntityChunkMembershipState.TAG).orElseThrow());
    }
    @Test void nextPickupUsesTheActualStagedDepositBeforeAnyOrEitherNativeSave(){
        var pickup1=marker;
        var deposit1=new Marker("d".repeat(64),"e".repeat(64),MarkerPhase.RESULT,"f".repeat(64));
        var pickup2=new Marker("1".repeat(64),"2".repeat(64),MarkerPhase.RESULT,"3".repeat(64));
        var deposit2=new Marker("4".repeat(64),"5".repeat(64),MarkerPhase.RESULT,"6".repeat(64));
        for(int savedStage=0;savedStage<=2;savedStage++) {
            var files=new AtomicReference<>(nativeFrame(a,true));
            var writes=new java.util.concurrent.atomic.AtomicInteger();
            var port=new EntityStorageEvidence(new EntityStorageEvidence.Access(){
                public CompletableFuture<Optional<CompoundTag>> read(ChunkPos pos){return CompletableFuture.completedFuture(Optional.of(files.get().copy()));}
                public CompletableFuture<Void> write(ChunkPos pos,CompoundTag root){files.set(root.copy());writes.incrementAndGet();return CompletableFuture.completedFuture(null);}
            },dim,Runnable::run);
            assertNull(port.stagedMembership(a,entity));
            assertTrue(port.stageMembership(a,row(a,true,pickup1,null)));
            if(savedStage==1)port.write(a,nativeFrame(a,true)).join();
            assertTrue(port.stageMembership(a,row(a,true,deposit1,pickup1)));
            if(savedStage==2)port.write(a,nativeFrame(a,true)).join();
            int written=writes.get();var staged=port.stagedMembership(a,entity);
            assertEquals(deposit1,staged.marker());assertEquals(written,writes.get());
            var saved=port.readFrame(a).join().membership(entity);
            assertEquals(savedStage==0?null:savedStage==1?pickup1:deposit1,saved==null?null:saved.marker());
            assertNull(port.stagedMembership(a,UUID.randomUUID()));
            assertFalse(port.stageMembership(a,row(a,true,pickup2,null)));
            assertTrue(port.stageMembership(a,row(a,true,pickup2,staged.marker())));
            assertTrue(port.stageMembership(a,row(a,true,deposit2,pickup2)));
            port.write(a,nativeFrame(a,true)).join();
            var finalFrame=port.readFrame(a).join();assertTrue(finalFrame.supported());
            assertEquals(row(a,true,deposit2,pickup2),finalFrame.membership(entity));
        }
    }
    @Test void wrongChunkAndReplacementIncarnationCannotAdoptTheOldTrail(){
        var initial=EntityChunkRecord.merge(null,nativeFrame(a,true),dim,a,List.of(),List.of(row(a,true,marker,null)));
        assertFalse(EntityChunkRecord.read(initial,dim,b).supported());
        var replacement=new EntityChunkMembershipState.Row(NativePickup.Role.ACTOR,new NativePickup.Trail(entity,UUID.randomUUID(),List.of(c(a)),c(a)),4,marker,true,null);
        assertThrows(IllegalArgumentException.class,()->EntityChunkRecord.merge(initial,nativeFrame(a,true),dim,a,List.of(),List.of(replacement)));
    }
    @Test void onlyNativeDestructionCanDeclareDepartureWithoutAChunkMove(){
        for(var reason:net.minecraft.world.entity.Entity.RemovalReason.values())
            assertEquals(reason==net.minecraft.world.entity.Entity.RemovalReason.KILLED
                    || reason==net.minecraft.world.entity.Entity.RemovalReason.DISCARDED,
                    EntityMembershipData.definitiveDeparture(reason));
    }
    @Test void confirmedDepartureRetainsTheOriginalMarkerWhileAnUnobservedOmissionStaysQuarantined(){
        var saved=new AtomicReference<>(nativeFrame(a,true));
        var port=new EntityStorageEvidence(new EntityStorageEvidence.Access(){
            public CompletableFuture<Optional<CompoundTag>> read(ChunkPos pos){return CompletableFuture.completedFuture(Optional.of(saved.get().copy()));}
            public CompletableFuture<Void> write(ChunkPos pos,CompoundTag root){saved.set(root.copy());return CompletableFuture.completedFuture(null);}
        },dim,Runnable::run);
        assertTrue(port.stageMembership(a,row(a,true,marker,null)));port.write(a,nativeFrame(a,true)).join();
        var before=saved.get().copy();
        assertTrue(port.stageMembership(a,row(a,false,marker,null)));port.write(a,nativeFrame(a,false)).join();
        var absent=port.readFrame(a).join();assertTrue(absent.supported());assertFalse(absent.members().contains(entity));
        assertEquals(row(a,false,marker,null),absent.membership(entity));
        var unknown=EntityChunkRecord.merge(before,nativeFrame(a,false),dim,a,List.of(),List.of());
        assertFalse(EntityChunkRecord.read(unknown,dim,a).supported());
        assertTrue(unknown.contains(EntityChunkMembershipState.TAG+"_original"));
    }
}
