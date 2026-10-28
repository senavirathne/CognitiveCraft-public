package dev.aivillages.fabric.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import dev.aivillages.core.kernel.storage.StorageCapabilities.Refusal;

/** Exact vanilla barrel only; inheritance or a generic Container cast cannot expand support. */
public final class BarrelStorageAdapter {
    public static Refusal refusal(ServerLevel level,BlockPos pos){
        if(!level.hasChunkAt(pos))return Refusal.UNLOADED;var entity=level.getBlockEntity(pos);
        return level.getBlockState(pos).is(Blocks.BARREL) && entity!=null && entity.getClass()==BarrelBlockEntity.class?Refusal.NONE:Refusal.UNSUPPORTED;
    }
    private BarrelStorageAdapter(){}
}
