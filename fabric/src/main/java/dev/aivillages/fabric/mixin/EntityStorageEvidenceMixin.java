package dev.aivillages.fabric.mixin;

import dev.aivillages.fabric.runtime.evidence.*;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.world.level.entity.ChunkEntities;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(EntityStorage.class)
public abstract class EntityStorageEvidenceMixin implements EntityStorageEvidenceOwner {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private SimpleRegionStorage simpleRegionStorage;
    @Shadow @Final private LongSet emptyChunks;
    @Unique private EntityStorageEvidence cognitivecraft$evidence;
    @Override public EntityStorageEvidence cognitivecraft$entityEvidence() {
        if(cognitivecraft$evidence==null)cognitivecraft$evidence=new EntityStorageEvidence(new EntityStorageEvidence.Access() {
            public CompletableFuture<Optional<CompoundTag>> read(ChunkPos pos){return simpleRegionStorage.read(pos);}
            public CompletableFuture<Void> write(ChunkPos pos,CompoundTag root){return simpleRegionStorage.write(pos,root);}
        },level.dimension().identifier().toString());return cognitivecraft$evidence;
    }
    @Inject(method="loadEntities",at=@At("HEAD"))
    private void cognitivecraft$readRetainedEmptyFrame(ChunkPos pos,CallbackInfoReturnable<?> ci){if(cognitivecraft$entityEvidence().needsRetention(pos))emptyChunks.remove(pos.pack());}
    @Inject(method="storeEntities",at=@At("HEAD"))
    private void cognitivecraft$saveRetainedEmptyFrame(ChunkEntities<?> entities,CallbackInfo ci){if(cognitivecraft$entityEvidence().needsRetention(entities.getPos()))emptyChunks.remove(entities.getPos().pack());}
    @Redirect(method="loadEntities",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/chunk/storage/SimpleRegionStorage;read(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Optional<CompoundTag>> cognitivecraft$nativeRead(SimpleRegionStorage storage,ChunkPos pos){return cognitivecraft$entityEvidence().readForLoad(pos);}
    @Redirect(method="storeEntities",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/chunk/storage/SimpleRegionStorage;write(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> cognitivecraft$nativeWrite(SimpleRegionStorage storage,ChunkPos pos,CompoundTag root){return cognitivecraft$entityEvidence().write(pos,root);}
    @Redirect(method="storeEntities",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/chunk/storage/SimpleRegionStorage;write(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> cognitivecraft$nativeEmptyWrite(SimpleRegionStorage storage,ChunkPos pos,Supplier<CompoundTag> root){return cognitivecraft$entityEvidence().write(pos,root);}
    @Inject(method="flush",at=@At("HEAD"))
    private void cognitivecraft$flushOwnedMetadata(boolean synchronize,CallbackInfo ci){if(cognitivecraft$evidence!=null)cognitivecraft$evidence.awaitWrites();}
    @Inject(method="close",at=@At("HEAD"))
    private void cognitivecraft$closeOwnedMetadata(CallbackInfo ci){if(cognitivecraft$evidence!=null)cognitivecraft$evidence.awaitWrites();}
}
