package dev.aivillages.fabric.runtime.evidence;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.*;
import static dev.aivillages.fabric.runtime.evidence.EntityChunkRemovalStateTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic record contracts; no original pickup qualification. */
final class EntityChunkRecordTest {
    @BeforeAll static void version(){SharedConstants.tryDetectVersion();}
    static CompoundTag record(UUID... ids) {
        var root=EntityChunkRecord.empty(POS);var entities=new ListTag();
        for(UUID id:ids){var e=new CompoundTag();e.store("UUID",UUIDUtil.CODEC,id);e.putString("id","minecraft:item");entities.add(e);}
        root.put("Entities",entities);return root;
    }
    static CompoundTag marked(){return EntityChunkRecord.merge(record(),null,DIM,POS,List.of(row(0)));}
    static EntityChunkRemovalState restored(CompoundTag root){return restoredFrame(EntityChunkRecord.read(root,DIM,POS));}
    static EntityChunkRemovalState restoredFrame(EntityChunkRecord.Frame f){return EntityChunkRemovalState.load(f.metadata(),true,DIM,POS,f.members());}
    @Test void legacyAbsenceNeverCreatesRemovalProof() {
        assertNull(EntityChunkRecord.merge(record(),null,DIM,POS,List.of()));var f=EntityChunkRecord.read(null,DIM,POS);
        assertFalse(f.exists());assertTrue(f.supported());assertTrue(f.members().isEmpty());assertTrue(f.metadata().isEmpty());
        assertFalse(restored(record()).restored(row(0),Set.of()));
    }
    @Test void emptyRecordRetainsActualNativePositionVersionAndRemoval() {
        var root=marked();assertEquals(POS,root.read("Position",ChunkPos.CODEC).orElseThrow());assertTrue(root.contains("DataVersion"));
        assertTrue(root.getListOrEmpty("Entities").isEmpty());assertTrue(EntityChunkRecord.read(root,DIM,POS).supported());
        assertTrue(restored(root).restored(row(0),Set.of()));
    }
    @Test void vanillaEntitiesPassengersAndUnrelatedFieldsRemainExact() {
        var root=record(UUID.randomUUID());var children=new ListTag();children.add(record(UUID.randomUUID()).getListOrEmpty("Entities").getFirst());
        ((CompoundTag)root.getListOrEmpty("Entities").getFirst()).put("Passengers",children);root.putString("unrelated","unchanged");var before=root.copy();
        var merged=EntityChunkRecord.merge(record(),root,DIM,POS,List.of(row(0)));
        assertEquals(before.get("Entities"),merged.get("Entities"));assertEquals(before.get("Position"),merged.get("Position"));
        assertEquals("unchanged",merged.getString("unrelated").orElseThrow());assertEquals(2,EntityChunkRecord.read(merged,DIM,POS).members().size());
    }
    @Test void futureSchemaAndWrongTagSurviveBothNonemptyAndDeleteSaves() {
        for(Tag value:List.of(StringTag.valueOf("{\"schema\":99}"),IntTag.valueOf(7))) {
            var previous=record();previous.put(EntityChunkRemovalState.TAG,value);
            for(CompoundTag fresh:Arrays.asList(record(UUID.randomUUID()),null)) {
                var merged=EntityChunkRecord.merge(previous,fresh,DIM,POS,List.of(row(0)));
                assertEquals(value,merged.get(EntityChunkRemovalState.TAG));assertFalse(EntityChunkRecord.read(merged,DIM,POS).supported());
            }
        }
    }
    @Test void futureMembershipAndOrphanQuarantineStayOpaque() {
        for(String key:List.of(EntityChunkRecord.MEMBERSHIP_TAG,EntityChunkRecord.MEMBERSHIP_TAG+"_original",EntityChunkRemovalState.TAG+"_original")) {
            var previous=record();previous.putString(key,"future exact bytes");var merged=EntityChunkRecord.merge(previous,null,DIM,POS,List.of(row(0)));
            assertEquals(previous.get(key),merged.get(key));assertFalse(EntityChunkRecord.read(merged,DIM,POS).supported());
            assertFalse(restored(merged).restored(row(0),Set.of()));
        }
    }
    @Test void savedContradictionCannotBecomeProofAfterLaterAbsence() {
        var bad=marked();bad.put("Entities",record(row(0).entity()).get("Entities"));assertFalse(EntityChunkRecord.read(bad,DIM,POS).supported());
        var later=EntityChunkRecord.merge(bad,null,DIM,POS,List.of());assertTrue(later.contains(EntityChunkRemovalState.TAG+"_original"));
        assertFalse(restored(later).valid());assertEquals(later,EntityChunkRecord.merge(later,null,DIM,POS,List.of()));
    }
    @Test void currentNativeMembershipContradictsStagedRemovalPermanently() {
        var live=record(row(0).entity());var merged=EntityChunkRecord.merge(record(),live,DIM,POS,List.of(row(0)));
        assertEquals(live.get("Entities"),merged.get("Entities"));assertFalse(restored(merged).valid());
        assertFalse(restored(EntityChunkRecord.merge(merged,null,DIM,POS,List.of())).restored(row(0),Set.of()));
    }
    @Test void wrongPositionAndMalformedNativeListQuarantineExistingMetadataIdempotently() {
        var wrong=marked();wrong.store("Position",ChunkPos.CODEC,new ChunkPos(99,99));var malformed=marked();malformed.putString("Entities","bad");
        for(var previous:List.of(wrong,malformed)) {
            assertFalse(EntityChunkRecord.read(previous,DIM,POS).supported());var later=EntityChunkRecord.merge(previous,null,DIM,POS,List.of());
            assertFalse(EntityChunkRecord.read(later,DIM,POS).supported());assertTrue(later.contains(EntityChunkRemovalState.TAG+"_original"));
            assertEquals(later,EntityChunkRecord.merge(later,null,DIM,POS,List.of()));
        }
    }
    @Test void duplicateMissingAndDeepPassengerUuidsRefuseExactMembership() {
        var duplicate=record(row(0).entity(),row(0).entity());var missing=record();missing.getListOrEmpty("Entities").add(new CompoundTag());
        var deep=record(UUID.randomUUID());var node=(CompoundTag)deep.getListOrEmpty("Entities").getFirst();
        for(int n=0;n<EntityChunkRecord.MAX_DEPTH+1;n++){var child=new CompoundTag();child.store("UUID",UUIDUtil.CODEC,UUID.randomUUID());var list=new ListTag();list.add(child);node.put("Passengers",list);node=child;}
        for(var root:List.of(duplicate,missing,deep))assertFalse(EntityChunkRecord.read(root,DIM,POS).supported());
    }
    @Test void oversizedNativeRecordsRefuseProofAndPreserveTheirEvidence() {
        var previous=marked();previous.putByteArray("large",new byte[EntityChunkRecord.MAX_ROOT_BYTES]);assertFalse(EntityChunkRecord.read(previous,DIM,POS).supported());
        var later=EntityChunkRecord.merge(previous,null,DIM,POS,List.of());assertTrue(later.contains(EntityChunkRemovalState.TAG+"_original"));assertFalse(restored(later).valid());
    }
    @Test void detachedSupportedFramesCannotBeChangedThroughTheirTagsOrMembership() {
        var root=marked();var frame=EntityChunkRecord.read(root,DIM,POS);String digest=frame.digest();
        root.putString(EntityChunkRemovalState.TAG,"changed");frame.metadata().putString(EntityChunkRemovalState.TAG,"also changed");
        assertEquals(digest,frame.digest());assertTrue(restoredFrame(frame).restored(row(0),Set.of()));
        assertThrows(UnsupportedOperationException.class,()->frame.members().add(UUID.randomUUID()));
    }
    @Test void conflictingResultFailsBeforeOverwritingOriginalNativeBytes() {
        var previous=marked();var before=previous.copy();var r=row(0);
        var conflict=new EntityChunkRemovalState.Removal(r.entity(),r.incarnation(),r.revision()+1,r.marker());
        assertThrows(IllegalStateException.class,()->EntityChunkRecord.merge(previous,null,DIM,POS,List.of(conflict)));
        assertEquals(before,previous);assertTrue(restored(previous).restored(row(0),Set.of()));
    }
}
