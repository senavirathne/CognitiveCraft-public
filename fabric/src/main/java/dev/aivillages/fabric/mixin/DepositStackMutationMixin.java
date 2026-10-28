package dev.aivillages.fabric.mixin;

import dev.aivillages.fabric.runtime.evidence.DepositStackOwners;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStack.class)
public abstract class DepositStackMutationMixin {
    @Inject(method="setCount",at=@At("HEAD"))
    private void cognitivecraft$countChanged(int count,CallbackInfo ci){DepositStackOwners.changed((ItemStack)(Object)this);}
}
