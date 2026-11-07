package dev.aivillages.fabric.mixin;

import dev.aivillages.fabric.runtime.evidence.LifecycleItemDamage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemEntity.class)
public abstract class LifecycleItemDamageMixin {
    @Inject(method="hurtServer",at=@At("RETURN"))
    private void cognitivecraft$observedLifecycleDamage(ServerLevel level,DamageSource source,float amount,CallbackInfoReturnable<Boolean> result) {
        LifecycleItemDamage.observed((ItemEntity)(Object)this,source.getMsgId(),result.getReturnValue());
    }
}
