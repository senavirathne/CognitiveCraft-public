package dev.aivillages.fabric.runtime.evidence;

import dev.aivillages.core.kernel.StrictJson;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Native metadata codec contracts. These synthetic tags never qualify a real chunk save. */
final class HarvestSourceStateTest {
    static final String DIM="minecraft:overworld";static final ChunkPos CHUNK=new ChunkPos(4,4);static final BlockPos POS=new BlockPos(73,208,73);
    static Marker marker(){return new Marker("a".repeat(64),"b".repeat(64),MarkerPhase.RESULT,"c".repeat(64));}
    static HarvestSourceState active(){var state=new HarvestSourceState();state.snapshot(DIM,CHUNK,POS,"minecraft:oak_log[axis=y]",true);state.mark(POS,marker(),()->{});return state;}
    @Test void untouchedLegacyChunkAddsNoTagAndNoInventedSourceIdentity() {
        var state=new HarvestSourceState();assertTrue(state.capture().isEmpty());assertNull(state.snapshot(DIM,CHUNK,POS,"minecraft:air",false));assertTrue(state.capture().isEmpty());
    }
    @Test void metadataRoundTripsOnlyThroughTrustedStorageAndKeepsOriginalIncarnation() {
        var live=active();var tag=live.capture();assertFalse(live.restored(POS));var restored=HarvestSourceState.load(tag,true,DIM,CHUNK);
        assertTrue(restored.valid());assertTrue(restored.restored(POS));assertEquals(live.snapshot(DIM,CHUNK,POS,"minecraft:air",false),restored.snapshot(DIM,CHUNK,POS,"minecraft:air",false));
        assertEquals(marker(),restored.marker(POS));assertEquals(tag,restored.capture());
    }
    @Test void detachedChunkRecordCannotBeChangedByALaterLiveMutation() {
        var state=active();var captured=state.capture();state.mutation(POS);state.mark(POS,new Marker("a".repeat(64),"b".repeat(64),MarkerPhase.MUTATING,""),()->{});
        var saved=HarvestSourceState.load(captured,true,DIM,CHUNK);assertEquals(0,saved.snapshot(DIM,CHUNK,POS,"minecraft:air",false).revision());assertEquals(marker(),saved.marker(POS));
        assertNotEquals(captured,state.capture());
    }
    @Test void replacementAndRestorationOfTheSameBlockCannotRecreateAnOldRevision() {
        var state=HarvestSourceState.load(active().capture(),true,DIM,CHUNK);var before=state.snapshot(DIM,CHUNK,POS,"minecraft:oak_log[axis=y]",false);
        state.mutation(POS);state.mutation(POS);var after=state.snapshot(DIM,CHUNK,POS,before.state(),false);
        assertEquals(before.incarnation(),after.incarnation());assertEquals(before.revision()+2,after.revision());assertFalse(state.restored(POS));
    }
    @Test void copiedMetadataOrAnotherChunkCannotBecomeSavedSourceProof() {
        var original=active().capture();var imported=HarvestSourceState.load(original,false,DIM,CHUNK);
        assertFalse(imported.valid());assertFalse(HarvestSourceState.load(imported.capture(),true,DIM,CHUNK).valid());
        assertFalse(HarvestSourceState.load(original,true,DIM,new ChunkPos(5,4)).valid());assertFalse(HarvestSourceState.load(original,true,"minecraft:the_nether",CHUNK).valid());
        assertThrows(IllegalStateException.class,()->imported.snapshot(DIM,CHUNK,POS,"minecraft:air",true));
    }
    @Test void unknownAndMalformedMetadataArePreservedWithoutAWriter() {
        var tag=new CompoundTag();tag.putString(HarvestSourceState.TAG,"{\"future\":true,\"schema\":99}");var state=HarvestSourceState.load(tag,true,DIM,CHUNK);
        assertFalse(state.valid());assertEquals(tag,state.capture());assertThrows(IllegalStateException.class,()->state.snapshot(DIM,CHUNK,POS,"minecraft:air",true));
        var wrong=new CompoundTag();wrong.putInt(HarvestSourceState.TAG,7);assertEquals(wrong,HarvestSourceState.load(wrong,true,DIM,CHUNK).capture());
    }
    @Test void sixtyFourPositionsAreBoundedWithoutEvictingTheOldestUnresolvedSource() {
        var state=new HarvestSourceState();var first=state.snapshot(DIM,CHUNK,POS,"minecraft:oak_log",true);
        for(int n=0;n<63;n++)state.snapshot(DIM,CHUNK,new BlockPos(64+n%16,190+n/16,64),"minecraft:oak_log",true);
        assertEquals(64,state.size());assertThrows(IllegalStateException.class,()->state.snapshot(DIM,CHUNK,new BlockPos(75,211,75),"minecraft:oak_log",true));
        assertEquals(first,state.snapshot(DIM,CHUNK,POS,first.state(),false));assertTrue(HarvestSourceState.load(state.capture(),true,DIM,CHUNK).valid());
    }
    @Test void duplicateUnknownAndNoncanonicalFieldsAreRejectedAndPreserved() throws Exception {
        var tag=active().capture();var root=StrictJson.object(tag.getString(HarvestSourceState.TAG).orElseThrow());
        root.put("unknown",1L);tag.putString(HarvestSourceState.TAG,StrictJson.canonical(root));assertFalse(HarvestSourceState.load(tag,true,DIM,CHUNK).valid());
        tag=active().capture();tag.putString(HarvestSourceState.TAG,tag.getString(HarvestSourceState.TAG).orElseThrow()+"\n");assertFalse(HarvestSourceState.load(tag,true,DIM,CHUNK).valid());
    }
    @Test void sourceStorageScopeIsNestedAndAlwaysRestoredAfterFailure() {
        assertFalse(HarvestNativeLoad.saved());assertThrows(IllegalStateException.class,()->{
            try(var outer=HarvestNativeLoad.enter()){try(var inner=HarvestNativeLoad.enter()){assertTrue(HarvestNativeLoad.saved());}assertTrue(HarvestNativeLoad.saved());throw new IllegalStateException("fixture");}
        });assertFalse(HarvestNativeLoad.saved());
    }
}
