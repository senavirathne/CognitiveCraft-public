package dev.aivillages.fabric.mixin;

import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Clears only a gateway-owned navigation's queued native recomputation on release. */
@Mixin(PathNavigation.class)
public interface PathNavigationAccessor {
    @Accessor("hasDelayedRecomputation")
    boolean cognitivecraft$getHasDelayedRecomputation();

    @Accessor("hasDelayedRecomputation")
    void cognitivecraft$setHasDelayedRecomputation(boolean delayed);
}
