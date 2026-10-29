package dev.aivillages.e2e.paralleld.mixin;
import dev.aivillages.e2e.paralleld.DComponentSaveCut;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(EntityStorage.class)
public abstract class DEntityChunkSaveMixin {
 @Shadow @Final private ServerLevel level;
 @Inject(method="storeEntities",at=@At("HEAD"),cancellable=true)
 private void parallelD$withhold(ChunkEntities<Entity> entities,CallbackInfo ci){if(DComponentSaveCut.withhold(level,entities))ci.cancel();}
}
