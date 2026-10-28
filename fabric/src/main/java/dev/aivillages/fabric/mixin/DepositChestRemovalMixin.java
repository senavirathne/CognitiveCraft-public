package dev.aivillages.fabric.mixin;

import dev.aivillages.fabric.runtime.evidence.DepositParticipant;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BaseContainerBlockEntity.class)
public abstract class DepositChestRemovalMixin {
    @Inject(method="removeItemNoUpdate",at=@At("HEAD"))
    private void cognitivecraft$removedWithoutNotification(int slot,CallbackInfoReturnable<ItemStack> ci){if((Object)this instanceof DepositParticipant participant)participant.cognitivecraft$depositParticipant().mutation();}
    @Inject(method="removeItem",at=@At("HEAD"))
    private void cognitivecraft$removed(int slot,int count,CallbackInfoReturnable<ItemStack> ci){if((Object)this instanceof DepositParticipant participant)participant.cognitivecraft$depositParticipant().mutation();}
    @Inject(method="setItem",at=@At("HEAD"))
    private void cognitivecraft$replaced(int slot,ItemStack stack,CallbackInfo ci){if((Object)this instanceof DepositParticipant participant)participant.cognitivecraft$depositParticipant().mutation();}
    @Inject(method="clearContent",at=@At("HEAD"))
    private void cognitivecraft$cleared(CallbackInfo ci){if((Object)this instanceof DepositParticipant participant)participant.cognitivecraft$depositParticipant().mutation();}
}
