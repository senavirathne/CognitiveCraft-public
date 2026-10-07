package dev.aivillages.fabric.mixin;
import dev.aivillages.fabric.AiVillages;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lease only the brain; navigation, movement and health still tick normally. */
@Mixin(Villager.class)
public abstract class VillagerMixin {
    @Inject(method="customServerAiStep",at=@At("HEAD"),cancellable=true)
    private void aiVillages$planOwnsBrain(ServerLevel level,CallbackInfo ci){if(AiVillages.controls((Villager)(Object)this))ci.cancel();}
    /** Villager overrides Mob pickup; prevent this native path from bypassing custody. */
    @Inject(method="pickUpItem",at=@At("HEAD"),cancellable=true,require=0)
    private void cognitivecraft$gatewayOwnsVillagerPickup(ServerLevel level,ItemEntity item,CallbackInfo ci) {
        if(AiVillages.gatewayControls((Villager)(Object)this))ci.cancel();
    }
}
