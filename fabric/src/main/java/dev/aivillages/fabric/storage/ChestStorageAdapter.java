package dev.aivillages.fabric.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import dev.aivillages.core.kernel.storage.StorageCapabilities.Refusal;

/** Exact normal-chest adapter. Trapped subclasses and incomplete/blocked participants are refused. */
public final class ChestStorageAdapter {
    public static Refusal refusal(ServerLevel level,BlockPos pos){
        if(!level.hasChunkAt(pos))return Refusal.UNLOADED;
        var state=level.getBlockState(pos);var entity=level.getBlockEntity(pos);
        if(!state.is(Blocks.CHEST) || entity==null || entity.getClass()!=ChestBlockEntity.class)return Refusal.UNSUPPORTED;
        if(ChestBlock.isChestBlockedAt(level,pos))return Refusal.TOPOLOGY;return Refusal.NONE;
    }
    private ChestStorageAdapter(){}
}
