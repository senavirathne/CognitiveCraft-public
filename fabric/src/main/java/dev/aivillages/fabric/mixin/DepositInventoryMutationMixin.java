package dev.aivillages.fabric.mixin;

import dev.aivillages.fabric.runtime.evidence.*;
import net.minecraft.world.SimpleContainer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SimpleContainer.class)
public abstract class DepositInventoryMutationMixin implements DepositTrackedInventory {
    @Unique private DepositParticipantState cognitivecraft$depositOwner;
    @Override public void cognitivecraft$depositOwner(DepositParticipantState owner){cognitivecraft$depositOwner=owner;}
    @Inject(method="setChanged",at=@At("HEAD"))
    private void cognitivecraft$changed(CallbackInfo ci){if(cognitivecraft$depositOwner!=null)cognitivecraft$depositOwner.mutation();}
    @Inject(method="removeItemNoUpdate",at=@At("HEAD"))
    private void cognitivecraft$removedWithoutNotification(int slot,org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.world.item.ItemStack> ci){if(cognitivecraft$depositOwner!=null)cognitivecraft$depositOwner.mutation();}
}
