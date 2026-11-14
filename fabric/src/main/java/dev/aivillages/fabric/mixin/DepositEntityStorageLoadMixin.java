package dev.aivillages.fabric.mixin;

import dev.aivillages.fabric.runtime.evidence.DepositNativeLoad;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.Kind;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(EntityStorage.class)
public abstract class DepositEntityStorageLoadMixin {
    @Redirect(method="lambda$loadEntities$0",at=@At(value="INVOKE",target="Ljava/util/stream/Stream;toList()Ljava/util/List;"))
    private List<?> cognitivecraft$readStoredActors(Stream<?> stream) {
        try(var scope=DepositNativeLoad.enter(Kind.ACTOR)){return stream.toList();}
    }
}
