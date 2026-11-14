package dev.aivillages.fabric.mixin;

import dev.aivillages.fabric.runtime.evidence.DepositNativeLoad;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.Kind;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(LevelChunk.class)
public abstract class DepositChestStorageLoadMixin {
    @Redirect(method="promotePendingBlockEntity",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/entity/BlockEntity;loadStatic(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/world/level/block/entity/BlockEntity;"))
    private BlockEntity cognitivecraft$readStoredChest(BlockPos pos,BlockState state,CompoundTag tag,HolderLookup.Provider lookup) {
        try(var scope=DepositNativeLoad.enter(Kind.CHEST)){return BlockEntity.loadStatic(pos,state,tag,lookup);}
    }
}
