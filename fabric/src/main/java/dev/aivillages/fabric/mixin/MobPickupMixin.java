package dev.aivillages.fabric.mixin;

import dev.aivillages.fabric.AiVillages;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native collision pickup would bypass the run's drop identity and custody checks. */
@Mixin(Mob.class)
public abstract class MobPickupMixin {
    @Inject(method="pickUpItem",at=@At("HEAD"),cancellable=true)
    private void cognitivecraft$gatewayOwnsPickup(ServerLevel level,ItemEntity item,CallbackInfo ci) {
        if ((Object)this instanceof Villager villager && AiVillages.gatewayControls(villager)) ci.cancel();
    }
}
