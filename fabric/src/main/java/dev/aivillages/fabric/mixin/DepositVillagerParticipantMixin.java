package dev.aivillages.fabric.mixin;

import dev.aivillages.fabric.runtime.evidence.*;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.Kind;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.ArrayList;
import net.minecraft.world.item.ItemStack;

@Mixin(Villager.class)
public abstract class DepositVillagerParticipantMixin implements DepositParticipant {
    @Unique private final DepositParticipantState cognitivecraft$deposit=new DepositParticipantState();
    @Override public DepositParticipantState cognitivecraft$depositParticipant(){return cognitivecraft$deposit;}
    @Inject(method="<init>",at=@At("TAIL"))
    private void cognitivecraft$bindInventory(CallbackInfo ci){((DepositTrackedInventory)((Villager)(Object)this).getInventory()).cognitivecraft$depositOwner(cognitivecraft$deposit);}
    @Inject(method="addAdditionalSaveData",at=@At("TAIL"))
    private void cognitivecraft$saveDeposit(ValueOutput output,CallbackInfo ci){cognitivecraft$deposit.save(output);}
    @Inject(method="readAdditionalSaveData",at=@At("TAIL"))
    private void cognitivecraft$loadDeposit(ValueInput input,CallbackInfo ci){
        cognitivecraft$deposit.load(input,Kind.ACTOR);if(!cognitivecraft$deposit.active() || !cognitivecraft$deposit.valid())return;var inventory=((Villager)(Object)this).getInventory();
        var slots=new ArrayList<ItemStack>();for(int n=0;n<inventory.getContainerSize();n++)slots.add(inventory.getItem(n));cognitivecraft$deposit.trackLoaded(slots);
    }
}
