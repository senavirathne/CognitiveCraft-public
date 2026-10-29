package dev.aivillages.fabric;

import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;
import dev.aivillages.fabric.runtime.evidence.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.world.level.entity.ChunkEntities;

/** Isolated native I/O/movement controls. Synthetic markers never qualify an original pickup. */
public final class RuntimeAEntityStorageChecks {
    private RuntimeAEntityStorageChecks(){}
    private static void require(boolean test,String why){if(!test)throw new IllegalStateException(why);}
    private static EntityChunkRemovalState.Removal row(ItemEntity e,int n) {
        return new EntityChunkRemovalState.Removal(e.getUUID(),UUID.randomUUID(),n,
                new Marker(String.format("%064x",n+1),"b".repeat(64),MarkerPhase.RESULT,"c".repeat(64)));
    }
    private static SimpleRegionStorage region(ServerLevel level,Path path) {
        return new SimpleRegionStorage(new RegionStorageInfo("a06-storage-mechanics",level.dimension(),"entities"),path,DataFixers.getDataFixer(),false,DataFixTypes.ENTITY_CHUNK);
    }
    private static EntityStorageEvidence evidence(EntityStorage storage){return ((EntityStorageEvidenceOwner)(Object)storage).cognitivecraft$entityEvidence();}
    private static boolean restored(EntityChunkRecord.Frame f,EntityChunkRemovalState.Removal row,String dim) {
        return f.supported()&&EntityChunkRemovalState.load(f.metadata(),true,dim,f.position(),f.members()).restored(row,Set.of());
    }
    public static Map<String,Object> run(MinecraftServer server,Path root) throws Exception {
        ServerLevel level=server.overworld();String dim=level.dimension().identifier().toString();
        Path path=root.resolve("storage-mechanics/entities");Files.createDirectories(path);
        try(var files=Files.list(path)){require(files.findAny().isEmpty(),"Fresh isolated native fixture required");}
        var result=new LinkedHashMap<String,Object>();var pos=new ChunkPos(4,4);var unknownPos=new ChunkPos(5,4);var conflictPos=new ChunkPos(6,4);
        var item=new ItemEntity(level,65,208,65,new ItemStack(Items.OAK_LOG,1));var removal=row(item,0);
        var conflict=new ItemEntity(level,97,208,65,new ItemStack(Items.BIRCH_LOG,1));var conflicting=row(conflict,1);
        var nativeRegion=region(level,path);
        try(var storage=new EntityStorage(nativeRegion,level,Runnable::run)) {
            var port=evidence(storage);storage.storeEntities(new ChunkEntities<Entity>(pos,List.of(item)));storage.flush(true);
            var initial=port.readFrame(pos).join();
            require(initial.supported()&&initial.members().equals(Set.of(item.getUUID()))&&initial.metadata().isEmpty(),"Actual initial native UUID membership");
            require(!restored(initial,removal,dim),"Live save cannot fabricate removal");result.put("initialEntity",item.getUUID());result.put("initialNativeDigest",initial.digest());
            item.discard();require(item.isRemoved(),"Isolated native entity actually removed");require(port.stageRemoval(pos,removal),"Removal capacity");
            storage.storeEntities(new ChunkEntities<Entity>(pos,List.of()));storage.flush(true);var saved=port.readFrame(pos).join();
            require(saved.exists()&&saved.members().isEmpty()&&restored(saved,removal,dim),"Actual native empty write must retain removal");
            result.put("firstEmptyDigest",saved.digest());result.put("removal",removal);
            var opaque=EntityChunkRecord.empty(unknownPos);opaque.putInt(EntityChunkRemovalState.TAG,77);nativeRegion.write(unknownPos,opaque).join();
            require(storage.loadEntities(unknownPos).join().isEmpty(),"Actual empty opaque native record");
            storage.storeEntities(new ChunkEntities<Entity>(unknownPos,List.of()));storage.flush(true);
            require(!port.readFrame(unknownPos).join().supported(),"Unknown tag unsupported");
            require(port.stageRemoval(conflictPos,conflicting),"Conflict control capacity");
            storage.storeEntities(new ChunkEntities<Entity>(conflictPos,List.of(conflict)));storage.flush(true);
            require(!port.readFrame(conflictPos).join().supported(),"Actual saved UUID contradicts synthetic removal");result.put("conflictingEntity",conflict.getUUID());
        }
        try(var storage=new EntityStorage(region(level,path),level,Runnable::run)) {
            require(storage.loadEntities(pos).join().isEmpty(),"Native close/reopen loads actually empty Entities");
            var port=evidence(storage);var loaded=port.readFrame(pos).join();
            require(loaded.exists()&&restored(loaded,removal,dim),"Native removal survives real close/reopen");
            require(loaded.digest().equals(result.get("firstEmptyDigest")),"Native digest survives close/reopen");
            // Repeat the real vanilla supplier/delete branch after caching an empty save.
            for(int n=0;n<2;n++){storage.storeEntities(new ChunkEntities<Entity>(pos,List.of()));storage.flush(true);}
            require(restored(port.readFrame(pos).join(),removal,dim),"Repeated empty-cache saves retain metadata");
            require(storage.loadEntities(unknownPos).join().isEmpty(),"Opaque native control reloaded");
            storage.storeEntities(new ChunkEntities<Entity>(unknownPos,List.of()));storage.flush(true);
            require(port.readFrame(unknownPos).join().metadata().getInt(EntityChunkRemovalState.TAG).orElseThrow()==77,"Opaque exact native value preserved");
            require(storage.loadEntities(conflictPos).join().getEntities().map(Entity::getUUID).toList().equals(List.of(conflict.getUUID())),"Conflict control actual saved UUID");
            storage.storeEntities(new ChunkEntities<Entity>(conflictPos,List.of()));storage.flush(true);var later=port.readFrame(conflictPos).join();
            require(!later.supported()&&later.metadata().contains(EntityChunkRemovalState.TAG+"_original"),"Later native absence cannot launder saved contradiction");
            result.put("reopenedEmptyDigest",port.readFrame(pos).join().digest());
        }
        try(var storage=new EntityStorage(region(level,path),level,Runnable::run)) {
            require(storage.loadEntities(conflictPos).join().isEmpty(),"Quarantine control actually became empty");
            require(!evidence(storage).readFrame(conflictPos).join().supported(),"Quarantine survives second native reopen");
            result.put("emptyRemoval","PASS_NATIVE_CLOSE_REOPEN_AND_TWO_EMPTY_SAVES");result.put("opaqueMetadata","PASS_EXACT_INT_77_PRESERVED");
            result.put("contradiction","PASS_PERMANENT_NATIVE_QUARANTINE");
        }
        var moving=new ItemEntity(level,65,208,65,new ItemStack(Items.OAK_LOG,1));
        var participant=(EntityMembershipParticipant)(Object)moving;var trail=participant.cognitivecraft$beginMembership(UUID.randomUUID());
        moving.setPosRaw(81,208,65);moving.setPosRaw(97,208,65);moving.setPosRaw(65,208,65);
        var capture=trail.capture();require(capture.chunks().size()==3&&capture.current().x()==4&&capture.current().z()==4,"Every setPosRaw change including same-tick return captured");
        result.put("movement",capture);moving.setUUID(UUID.randomUUID());require(!trail.complete(),"Changed UUID permanently gaps membership");result.put("changedUuid","PASS_GAP");
        result.put("status","PASS_NATIVE_STORAGE_MECHANICS_ONLY");result.put("nativePickupProducer","INACTIVE");result.put("originalOwnerSettlements",0);
        result.put("directory","storage-mechanics/entities");return result;
    }
}
