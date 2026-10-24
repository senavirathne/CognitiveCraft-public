package dev.aivillages.e2e.crafting.mixin;

import java.util.Map;
import dev.aivillages.e2e.CraftingPrimitiveTrace;
import dev.aivillages.core.kernel.Budgets;
import dev.aivillages.core.kernel.SurvivalGateway;
import static dev.aivillages.core.kernel.Contracts.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Testing-only observational injections. No cancellation, argument or return mutation. */
@Mixin(value = SurvivalGateway.class, remap = false)
public abstract class CraftingGatewayTraceMixin {
    @Inject(method = "start", at = @At("HEAD"), remap = false)
    private void crafting$start(ValidatedRequest request, RunCorrelation correlation,
            PrimitiveRequirement primitive, Map<String, Value> arguments, Budgets.Ledger usage,
            CallbackInfoReturnable<ActionHandle> result) {
        CraftingPrimitiveTrace.emit("primitive-start", Map.of("request", request,
                "correlation", correlation, "primitive", primitive, "arguments", arguments));
    }

    @Inject(method = "start", at = @At("RETURN"), remap = false)
    private void crafting$handle(ValidatedRequest request, RunCorrelation correlation,
            PrimitiveRequirement primitive, Map<String, Value> arguments, Budgets.Ledger usage,
            CallbackInfoReturnable<ActionHandle> result) {
        CraftingPrimitiveTrace.emit("primitive-handle", Map.of("handle", result.getReturnValue(), "primitive", primitive));
    }

    @Inject(method = "poll", at = @At("RETURN"), remap = false)
    private void crafting$poll(ActionHandle handle, CallbackInfoReturnable<ActionReceipt> result) {
        var receipt = result.getReturnValue();
        if (receipt != null && receipt.terminal())
            CraftingPrimitiveTrace.emit("primitive-terminal", Map.of("handle", handle, "receipt", receipt));
    }

    @Inject(method = "cancel", at = @At("RETURN"), remap = false)
    private void crafting$cancel(ActionHandle handle, CallbackInfoReturnable<ActionReceipt> result) {
        CraftingPrimitiveTrace.emit("primitive-cancel", Map.of("handle", handle, "receipt", result.getReturnValue()));
    }
}
