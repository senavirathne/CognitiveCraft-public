package dev.aivillages.fabric.mixin;

import dev.aivillages.fabric.AiVillages;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gateway-owned paths are replanned only by the gateway's bounded route policy. */
@Mixin(PathNavigation.class)
public abstract class PathNavigationMixin {
    @Shadow @Final protected Mob mob;
    @Shadow private boolean hasDelayedRecomputation;

    @Inject(method = "recomputePath", at = @At("HEAD"), cancellable = true)
    private void cognitivecraft$keepOwnedPath(CallbackInfo ci) {
        if (mob instanceof Villager villager && AiVillages.gatewayControls(villager)) {
            // A block update or a delayed vanilla replan must not replace a path
            // behind NavigationRoute's identity and counted pathfinding checks.
            hasDelayedRecomputation = false;
            ci.cancel();
        }
    }
}
