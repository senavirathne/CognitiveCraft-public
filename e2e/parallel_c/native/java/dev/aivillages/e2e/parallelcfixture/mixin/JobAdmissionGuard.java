package dev.aivillages.e2e.parallelcfixture.mixin;
import dev.aivillages.core.kernel.JobLifecycleStore;
import dev.aivillages.e2e.parallelcfixture.ConversationGuards;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=JobLifecycleStore.class,remap=false)
public abstract class JobAdmissionGuard {
    @Inject(method="<init>",at=@At("TAIL"),require=1)
    private void ready(CallbackInfo ci){ConversationGuards.ready("JobLifecycleStore");}
    @Inject(method={"create","stageComposition","stageBlueprint","admitBlueprintChild","admitFacilityChild"},at=@At("HEAD"),require=1)
    private void attempted(CallbackInfoReturnable<?> ci){ConversationGuards.job();}
}
