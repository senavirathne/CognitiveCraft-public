package dev.aivillages.fabric.mixin;

import dev.aivillages.fabric.runtime.evidence.LiveHarvestDrop;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Other native actors cannot consume a live gateway's exact wheat birth before its pickup. */
@Mixin({Mob.class,Villager.class})
public abstract class HarvestCustodyPickupMixin {
    @Inject(method="pickUpItem",at=@At("HEAD"),cancellable=true)
    private void cognitivecraft$protectOriginalWheat(ServerLevel level,ItemEntity item,CallbackInfo ci) {
        if(item.getItem().is(Items.WHEAT) && ((LiveHarvestDrop)item).cognitivecraft$liveCustody())ci.cancel();
    }
}
