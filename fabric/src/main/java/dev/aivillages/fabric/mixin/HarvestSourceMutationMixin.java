package dev.aivillages.fabric.mixin;

import dev.aivillages.fabric.runtime.evidence.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(LevelChunk.class)
public abstract class HarvestSourceMutationMixin {
    @Inject(method="setBlockState",at=@At("RETURN"))
    private void cognitivecraft$sourceChanged(BlockPos pos,BlockState state,int flags,CallbackInfoReturnable<BlockState> cir) {
        if(cir.getReturnValue()!=null)((HarvestSourceParticipant)this).cognitivecraft$harvestSources().mutation(pos);
    }
    @Inject(method="<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V",at=@At("TAIL"))
    private void cognitivecraft$promoteSources(ServerLevel level,ProtoChunk proto,LevelChunk.PostLoadProcessor postLoad,CallbackInfo ci) {
        ((HarvestSourceParticipant)this).cognitivecraft$harvestSources(((HarvestSourceParticipant)proto).cognitivecraft$harvestSources());
    }
}
