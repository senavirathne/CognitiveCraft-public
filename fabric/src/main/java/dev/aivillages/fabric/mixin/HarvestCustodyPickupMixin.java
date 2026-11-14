package dev.aivillages.fabric.mixin;

import dev.aivillages.fabric.AiVillages;
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

/** Keep the exact native crop outputs available for live work and retained recovery. */
@Mixin({Mob.class,Villager.class})
public abstract class HarvestCustodyPickupMixin {
    @Inject(method="pickUpItem",at=@At("HEAD"),cancellable=true)
    private void cognitivecraft$protectOriginalWheat(ServerLevel level,ItemEntity item,CallbackInfo ci) {
        boolean wheat=item.getItem().is(Items.WHEAT);
        if(!wheat && !item.getItem().is(Items.WHEAT_SEEDS))return;
        var kernel=AiVillages.kernel();
        if(wheat && ((LiveHarvestDrop)item).cognitivecraft$blocksNativePickup(((Mob)(Object)this).getUUID())
                || kernel!=null && kernel.retainsNativeCropOutput(item))ci.cancel();
    }
}
