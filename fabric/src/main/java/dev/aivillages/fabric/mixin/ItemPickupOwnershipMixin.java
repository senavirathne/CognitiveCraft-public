package dev.aivillages.fabric.mixin;

import java.util.UUID;
import dev.aivillages.fabric.AiVillages;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla fields retain drop isolation across saves. They never restore a run or grant credit. */
@Mixin(ItemEntity.class)
public abstract class ItemPickupOwnershipMixin {
    @Shadow private UUID target;
    @Inject(method="tick",at=@At("HEAD"))
    private void cognitivecraft$releaseInterruptedCustody(CallbackInfo ci) {
        ItemEntity item=(ItemEntity)(Object)this;
        if(target!=null && target.equals(item.getUUID()) && (item.getItem().is(Items.OAK_LOG) || item.getItem().is(Items.BIRCH_LOG))
                && (!(item.getOwner() instanceof Villager actor) || !actor.isAlive() || actor.isRemoved()
                        || !AiVillages.gatewayControls(actor)))
            target=null;
    }
}
