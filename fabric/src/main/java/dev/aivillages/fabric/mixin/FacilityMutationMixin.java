package dev.aivillages.fabric.mixin;
import dev.aivillages.fabric.FabricFacilityIdentity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Invalidate a captured table even when the same block type is removed and replaced. */
@Mixin(Level.class)
public abstract class FacilityMutationMixin {
    @Inject(method="setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",at=@At("RETURN"))
    private void cognitivecraft$facilityChanged(BlockPos pos,BlockState state,int flags,int depth,CallbackInfoReturnable<Boolean> result) {
        if(result.getReturnValue() && (Object)this instanceof ServerLevel level)FabricFacilityIdentity.changed(level,pos);
    }
}
