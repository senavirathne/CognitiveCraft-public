package dev.aivillages.e2e.parallelb.mixin;

import dev.aivillages.core.kernel.runtime.evidence.NativeRecipeJournal;
import dev.aivillages.fabric.I00RecipeReloadFixture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test-only delay after the actual durable writer returns an original preparation. */
@Mixin(NativeRecipeJournal.class)
public abstract class I00RecipePreparationMixin {
    @Inject(method="prepare",at=@At("RETURN"),remap=false)
    private void i00$holdAcknowledgement(NativeRecipeJournal.Intent intent,
                                        CallbackInfoReturnable<NativeRecipeJournal.Entry> returned) {
        I00RecipeReloadFixture.prepared(returned.getReturnValue());
    }
}
