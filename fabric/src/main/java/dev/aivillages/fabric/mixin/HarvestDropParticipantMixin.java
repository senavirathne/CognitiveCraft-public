package dev.aivillages.fabric.mixin;

import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.Kind;
import dev.aivillages.fabric.runtime.evidence.*;
import java.util.List;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The entity's own stored tag and tracked stack establish drop incarnation/revision. */
@Mixin(ItemEntity.class)
public abstract class HarvestDropParticipantMixin implements DepositParticipant {
    @Unique private DepositParticipantState cognitivecraft$harvestDrop;
    @Override public DepositParticipantState cognitivecraft$depositParticipant(){
        if(cognitivecraft$harvestDrop==null)cognitivecraft$harvestDrop=new DepositParticipantState();return cognitivecraft$harvestDrop;
    }
    @Inject(method="addAdditionalSaveData",at=@At("TAIL"))
    private void cognitivecraft$saveHarvestDrop(ValueOutput output,CallbackInfo ci) {
        if(cognitivecraft$harvestDrop!=null)cognitivecraft$harvestDrop.save(output);
    }
    @Inject(method="readAdditionalSaveData",at=@At("TAIL"))
    private void cognitivecraft$loadHarvestDrop(ValueInput input,CallbackInfo ci) {
        var state=cognitivecraft$depositParticipant();state.load(input,Kind.ACTOR);
        if(state.active() && state.valid())state.trackLoaded(List.of(((ItemEntity)(Object)this).getItem()));
    }
    @Inject(method="setItem",at=@At("HEAD"))
    private void cognitivecraft$dropStackChanged(ItemStack stack,CallbackInfo ci) {
        if(cognitivecraft$harvestDrop!=null)cognitivecraft$harvestDrop.mutation();
    }
    @Inject(method="setItem",at=@At("TAIL"))
    private void cognitivecraft$trackDropStack(ItemStack stack,CallbackInfo ci) {
        if(cognitivecraft$harvestDrop!=null && cognitivecraft$harvestDrop.active() && cognitivecraft$harvestDrop.valid())
            cognitivecraft$harvestDrop.trackLoaded(List.of(((ItemEntity)(Object)this).getItem()));
    }
}
