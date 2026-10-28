package dev.aivillages.fabric.mixin;

import dev.aivillages.fabric.runtime.evidence.HarvestNativeLoad;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(ChunkMap.class)
public abstract class HarvestChunkStorageLoadMixin {
    @Redirect(method="lambda$scheduleChunkLoad$1",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;parse(Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/chunk/PalettedContainerFactory;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;"))
    private SerializableChunkData cognitivecraft$parseStoredSources(LevelHeightAccessor height,PalettedContainerFactory factory,CompoundTag root) {
        try(var scope=HarvestNativeLoad.enter()){return SerializableChunkData.parse(height,factory,root);}
    }
}
