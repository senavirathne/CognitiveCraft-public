package dev.aivillages.e2e.parallelb.mixin;

import dev.aivillages.fabric.BGuardRead;
import dev.aivillages.core.kernel.RecipeTransaction;
import dev.aivillages.core.kernel.Outcomes.Reason;
import static dev.aivillages.core.kernel.Contracts.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Isolated observer JAR only. The dedicated Mixin package contains no normal entrypoints. */
@Mixin(targets="dev.aivillages.fabric.FabricRecipeTransactions",remap=false)
public abstract class BRecipeDiagnosticsMixin {
    @Shadow @Final private ServerLevel level;
    @Inject(method="preflight(Ldev/aivillages/core/kernel/Contracts$ValidatedRequest;Z)Ldev/aivillages/core/kernel/RecipeTransaction$Preflight;",at=@At("RETURN"),remap=false)
    private void parallelB$preflight(ValidatedRequest request,boolean future,CallbackInfoReturnable<RecipeTransaction.Preflight> returned){BGuardRead.preflight(level,request,future,returned.getReturnValue());}
    @Inject(method="guard",at=@At("RETURN"),remap=false)
    private void parallelB$guard(Villager actor,Container box,ContainerRef ref,String identity,String custody,CallbackInfoReturnable<Reason> returned){BGuardRead.guard(level,this,actor,box,ref,identity,custody,returned.getReturnValue());}
    @Inject(method="withdraw(Ldev/aivillages/core/kernel/Contracts$ActorRef;Ldev/aivillages/core/kernel/Contracts$ContainerRef;Ldev/aivillages/core/kernel/RecipeTransaction$Preflight;Ljava/lang/String;Ljava/lang/String;)Ldev/aivillages/core/kernel/RecipeTransaction$Mutation;",at=@At("RETURN"),remap=false)
    private void parallelB$withdraw(ActorRef actor,ContainerRef source,RecipeTransaction.Preflight captured,String custody,String fingerprint,CallbackInfoReturnable<RecipeTransaction.Mutation> returned){BGuardRead.withdraw(level,actor,source,captured,returned.getReturnValue());}
}
