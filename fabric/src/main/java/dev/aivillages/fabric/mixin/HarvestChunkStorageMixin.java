package dev.aivillages.fabric.mixin;

import dev.aivillages.fabric.runtime.evidence.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Capture source metadata with the exact block save record; preserve unknown bytes without authority. */
@Mixin(SerializableChunkData.class)
public abstract class HarvestChunkStorageMixin implements HarvestSourceRecord {
    @Unique private CompoundTag cognitivecraft$sourceMetadata;
    @Unique private boolean cognitivecraft$nativeSources;
    @Override public void cognitivecraft$harvestRecord(CompoundTag metadata,boolean nativeStorage) {
        cognitivecraft$sourceMetadata=metadata.copy();cognitivecraft$nativeSources=nativeStorage;
    }
    @Inject(method="parse",at=@At("RETURN"))
    private static void cognitivecraft$parseSources(LevelHeightAccessor height,PalettedContainerFactory factory,CompoundTag root,CallbackInfoReturnable<SerializableChunkData> cir) {
        if(cir.getReturnValue()!=null)((HarvestSourceRecord)(Object)cir.getReturnValue()).cognitivecraft$harvestRecord(HarvestSourceState.metadata(root),HarvestNativeLoad.saved());
    }
    @Inject(method="copyOf",at=@At("RETURN"))
    private static void cognitivecraft$captureSources(ServerLevel level,ChunkAccess chunk,CallbackInfoReturnable<SerializableChunkData> cir) {
        if(cir.getReturnValue()!=null)((HarvestSourceRecord)(Object)cir.getReturnValue()).cognitivecraft$harvestRecord(((HarvestSourceParticipant)chunk).cognitivecraft$harvestSources().capture(),false);
    }
    @Inject(method="write",at=@At("RETURN"))
    private void cognitivecraft$writeSources(CallbackInfoReturnable<CompoundTag> cir) {
        if(cognitivecraft$sourceMetadata!=null)HarvestSourceState.append(cir.getReturnValue(),cognitivecraft$sourceMetadata);
    }
    @Unique private void cognitivecraft$restoreSources(ServerLevel level,ChunkAccess chunk) {
        if(cognitivecraft$sourceMetadata!=null)((HarvestSourceParticipant)chunk).cognitivecraft$harvestSources(
                HarvestSourceState.load(cognitivecraft$sourceMetadata,cognitivecraft$nativeSources,level.dimension().identifier().toString(),chunk.getPos()));
    }
    // FULL chunks return an ImposterProtoChunk. Attach to its actual LevelChunk before wrapping.
    @Redirect(method="read",at=@At(value="NEW",target="(Lnet/minecraft/world/level/chunk/LevelChunk;Z)Lnet/minecraft/world/level/chunk/ImposterProtoChunk;"))
    private ImposterProtoChunk cognitivecraft$restoreFullSources(LevelChunk chunk,boolean writes,ServerLevel level,PoiManager poi,RegionStorageInfo storage,ChunkPos expected) {
        cognitivecraft$restoreSources(level,chunk);return new ImposterProtoChunk(chunk,writes);
    }
    @Inject(method="read",at=@At("RETURN"))
    private void cognitivecraft$restoreProtoSources(ServerLevel level,PoiManager poi,RegionStorageInfo storage,ChunkPos expected,CallbackInfoReturnable<ProtoChunk> cir) {
        if(cir.getReturnValue()!=null && !(cir.getReturnValue() instanceof ImposterProtoChunk))cognitivecraft$restoreSources(level,cir.getReturnValue());
    }
}
