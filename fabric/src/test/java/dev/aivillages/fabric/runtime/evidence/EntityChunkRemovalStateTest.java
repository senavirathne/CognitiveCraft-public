package dev.aivillages.fabric.runtime.evidence;

import dev.aivillages.core.kernel.StrictJson;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static dev.aivillages.fabric.runtime.evidence.EntityChunkRemovalState.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic native-record contracts only. No real pickup, storage hook, or original P pass. */
final class EntityChunkRemovalStateTest {
    static final String DIM="minecraft:overworld";static final ChunkPos POS=new ChunkPos(4,4);
    static Removal row(int n){return new Removal(UUID.nameUUIDFromBytes(("entity"+n).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            UUID.nameUUIDFromBytes(("incarnation"+n).getBytes(java.nio.charset.StandardCharsets.UTF_8)),n,
            new Marker(String.format("%064x",n+1),"b".repeat(64),MarkerPhase.RESULT,"c".repeat(64)));}
    static EntityChunkRemovalState active(){var state=new EntityChunkRemovalState(DIM,POS);state.record(row(0));return state;}
    static EntityChunkRemovalState saved(CompoundTag tag){return load(tag,true,DIM,POS,Set.of());}
    @Test void bareAbsenceAndLegacyEmptyChunksHaveNoRemovalWitnessOrAddedMetadata() {
        var state=saved(new CompoundTag());assertTrue(state.valid());assertEquals(0,state.size());
        assertFalse(state.restored(row(0),Set.of()));assertTrue(state.capture(Set.of()).metadata().isEmpty());
    }
    @Test void liveDeclarationAndSerializationNeverBecomeNativeSavedProof() {
        var state=active();assertFalse(state.restored(row(0),Set.of()));
        assertTrue(state.capture(Set.of()).supported());assertFalse(state.restored(row(0),Set.of()));
    }
    @Test void trustedNativeRecordRoundTripsExactIdentityRevisionAndResultMarker() {
        var original=active().capture(Set.of()).metadata();var state=saved(original);
        assertTrue(state.valid());assertTrue(state.restored(row(0),Set.of()));assertEquals(original,state.capture(Set.of()).metadata());
    }
    @Test void missingOrChangedOriginalFieldsCannotMatchAStoredRemoval() {
        var state=saved(active().capture(Set.of()).metadata());var r=row(0);
        assertFalse(state.restored(row(1),Set.of()));
        assertFalse(state.restored(new Removal(UUID.randomUUID(),r.incarnation(),r.revision(),r.marker()),Set.of()));
        assertFalse(state.restored(new Removal(r.entity(),UUID.randomUUID(),r.revision(),r.marker()),Set.of()));
        assertFalse(state.restored(new Removal(r.entity(),r.incarnation(),r.revision()+1,r.marker()),Set.of()));
        assertFalse(state.restored(new Removal(r.entity(),r.incarnation(),r.revision(),
                new Marker(r.marker().effect(),"d".repeat(64),MarkerPhase.RESULT,"e".repeat(64))),Set.of()));
    }
    @Test void importedMetadataIsPreservedQuarantinedAndCannotBecomeTrustedByResaving() {
        var state=load(active().capture(Set.of()).metadata(),false,DIM,POS,Set.of());
        assertFalse(state.valid());var capture=state.capture(Set.of());assertFalse(capture.supported());
        assertTrue(capture.metadata().contains(TAG+"_original"));assertFalse(saved(capture.metadata()).valid());
        assertThrows(IllegalStateException.class,()->state.record(row(1)));
    }
    @Test void sameMetadataInAnotherDimensionOrEntityChunkIsNotAStoredDeparture() {
        var tag=active().capture(Set.of()).metadata();
        assertFalse(load(tag,true,"minecraft:the_nether",POS,Set.of()).valid());
        assertFalse(load(tag,true,DIM,new ChunkPos(5,4),Set.of()).valid());
    }
    @Test void anEntityStillInTheActualNativeRecordContradictsItsRemoval() {
        var tag=active().capture(Set.of()).metadata();
        var state=load(tag,true,DIM,POS,Set.of(row(0).entity()));assertFalse(state.valid());
        assertFalse(state.restored(row(0),Set.of()));assertEquals(tag,state.capture(Set.of()).metadata());
    }
    @Test void currentEntityPresenceCannotBeReplacedByAZeroOrUnrelatedCount() {
        var state=saved(active().capture(Set.of()).metadata());
        assertFalse(state.restored(row(0),Set.of(row(0).entity())));
        assertTrue(state.restored(row(0),Set.of(UUID.randomUUID())));
        var capture=state.capture(Set.of(row(0).entity()));assertFalse(capture.supported());
        assertFalse(state.valid());assertFalse(state.restored(row(0),Set.of()));
        assertFalse(saved(capture.metadata()).valid());
    }
    @Test void detachedFramesAndTheirReturnedTagsCannotBeChangedByLaterLiveRows() {
        var state=active();var frame=state.capture(Set.of());state.record(row(1));
        assertEquals(1,saved(frame.metadata()).size());assertEquals(2,saved(state.capture(Set.of()).metadata()).size());
        var changed=frame.metadata();changed.putString(TAG,"changed");assertTrue(saved(frame.metadata()).valid());
    }
    @Test void exactRepeatedDeclarationsAreIdempotentButChangedOnesCannotReplaceTheOriginal() {
        var state=active();state.record(row(0));assertEquals(1,state.size());var r=row(0);
        assertThrows(IllegalStateException.class,()->state.record(new Removal(r.entity(),r.incarnation(),r.revision()+1,r.marker())));
        assertEquals(1,state.size());assertTrue(saved(state.capture(Set.of()).metadata()).restored(row(0),Set.of()));
    }
    @Test void sixtyFourRowsDoNotEvictTheOldestWhenTheNextDeclarationIsRefused() {
        var state=active();for(int n=1;n<64;n++)state.record(row(n));
        var before=state.capture(Set.of()).metadata();assertThrows(IllegalStateException.class,()->state.record(row(64)));
        assertEquals(before,state.capture(Set.of()).metadata());assertEquals(64,state.size());
        assertTrue(saved(before).restored(row(0),Set.of()));
    }
    @Test void unknownMalformedAndOversizedTagsAreKeptWithoutWriterAuthority() {
        var unknown=new CompoundTag();unknown.putString(TAG,"{\"schema\":99,\"future\":true}");
        var wrong=new CompoundTag();wrong.putInt(TAG,9);
        var oversized=new CompoundTag();oversized.putString(TAG,"x".repeat(MAX_TEXT+1));
        for(var tag:List.of(unknown,wrong,oversized)) {
            var state=saved(tag);assertFalse(state.valid());assertEquals(tag,state.capture(Set.of()).metadata());
            assertThrows(IllegalStateException.class,()->state.record(row(1)));
        }
    }
    @Test void duplicateUnknownAndNoncanonicalFieldsCannotCreateRemovalProof() throws Exception {
        var good=active().capture(Set.of()).metadata();String text=good.getString(TAG).orElseThrow();
        var newline=good.copy();newline.putString(TAG,text+"\n");assertFalse(saved(newline).valid());
        var unknown=StrictJson.object(text);unknown.put("unknown",1L);var tag=new CompoundTag();tag.putString(TAG,StrictJson.canonical(unknown));assertFalse(saved(tag).valid());
        var duplicate=StrictJson.object(text);var values=new ArrayList<Object>((List<?>)duplicate.get("removals"));values.add(values.getFirst());duplicate.put("removals",values);
        tag=new CompoundTag();tag.putString(TAG,StrictJson.canonical(duplicate));assertFalse(saved(tag).valid());
    }
    @Test void orphanQuarantineCannotBeSilentlyDroppedAndNativeFieldsAreNotOwned() {
        var tag=new CompoundTag();tag.putString(TAG+"_original","future");tag.putString("Entities","native-owned");
        var state=saved(tag);assertFalse(state.valid());
        assertEquals(metadata(tag),state.capture(Set.of()).metadata());
        var root=new CompoundTag();root.putString("Entities","native-owned");append(root,active().capture(Set.of()));
        assertEquals("native-owned",root.getString("Entities").orElseThrow());assertTrue(root.contains(TAG));
    }
    @Test void workerDeserializationDoesNotOwnTheLaterGameThreadAndOtherLiveWritersRefuse() throws Exception {
        var encoded=active().capture(Set.of()).metadata();var restored=new AtomicReference<EntityChunkRemovalState>();
        Thread reader=new Thread(()->restored.set(saved(encoded)));reader.start();reader.join();
        var state=restored.get();state.record(row(1));var before=state.capture(Set.of()).metadata();
        var rejected=new AtomicReference<Throwable>();Thread writer=new Thread(()->{try{state.record(row(2));}catch(Throwable failure){rejected.set(failure);}});
        writer.start();writer.join();assertInstanceOf(IllegalStateException.class,rejected.get());
        assertEquals(before,state.capture(Set.of()).metadata());
    }
    @Test void preparedMutatingNegativeRevisionAndInvalidDimensionCannotDeclareACompletedRemoval() {
        var r=row(0);
        assertThrows(IllegalArgumentException.class,()->new Removal(r.entity(),r.incarnation(),-1,r.marker()));
        for(var phase:List.of(MarkerPhase.PREPARED,MarkerPhase.MUTATING))
            assertThrows(IllegalArgumentException.class,()->new Removal(r.entity(),r.incarnation(),0,new Marker("a".repeat(64),"b".repeat(64),phase,"")));
        assertThrows(IllegalArgumentException.class,()->new EntityChunkRemovalState("unknown",POS));
    }
}
