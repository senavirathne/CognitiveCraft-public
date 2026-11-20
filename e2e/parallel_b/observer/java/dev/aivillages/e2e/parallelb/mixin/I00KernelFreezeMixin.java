package dev.aivillages.e2e.parallelb.mixin;

import dev.aivillages.fabric.I00NativeBoundaryFixture;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep the test JVM at the observed tick until the native save and process stop. */
@Mixin(targets="dev.aivillages.fabric.KernelSession",remap=false)
public abstract class I00KernelFreezeMixin {
    @Shadow @Final private MinecraftServer server;
    @Inject(method="tick",at=@At("HEAD"),cancellable=true,remap=false)
    private void i00$holdAtCut(CallbackInfo callback) {
        if(I00NativeBoundaryFixture.atMeasuredStop() || server.tickRateManager().isFrozen())callback.cancel();
    }
}
