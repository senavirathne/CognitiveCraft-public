package dev.aivillages.e2e.parallelb.mixin;

import dev.aivillages.core.kernel.runtime.evidence.NativeRecipeJournal;
import dev.aivillages.fabric.I00ChoiceCutFixture;
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
        I00ChoiceCutFixture.prepared(returned.getReturnValue());
    }

    @Inject(method="result",at=@At("HEAD"),remap=false)
    private void i00$watchAppliedBeforePublication(NativeRecipeJournal.Result result,
                                                  CallbackInfoReturnable<NativeRecipeJournal.Entry> returned) {
        I00ChoiceCutFixture.beforeResult(result);
    }

    @Inject(method="result",at=@At("RETURN"),remap=false)
    private void i00$watchPublishedBeforeAcknowledgement(NativeRecipeJournal.Result result,
                                                         CallbackInfoReturnable<NativeRecipeJournal.Entry> returned) {
        I00ChoiceCutFixture.afterResult(returned.getReturnValue());
    }
}
