package dev.aivillages.e2e;

import java.util.Map;
import dev.aivillages.core.Plan;
import dev.aivillages.core.WorldPort;
import dev.aivillages.fabric.FarmPort;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = FarmPort.class, remap = false)
public abstract class CraftingLegacyTraceMixin {
    @Inject(method = "perform", at = @At("HEAD"), remap = false)
    private void crafting$start(Plan.Step step, CallbackInfoReturnable<WorldPort.Outcome> result) {
        CraftingPrimitiveTrace.emit("legacy-primitive-start", Map.of("step", step));
    }

    @Inject(method = "perform", at = @At("RETURN"), remap = false)
    private void crafting$end(Plan.Step step, CallbackInfoReturnable<WorldPort.Outcome> result) {
        CraftingPrimitiveTrace.emit("legacy-primitive-result", Map.of("step", step, "outcome", result.getReturnValue()));
    }
}
