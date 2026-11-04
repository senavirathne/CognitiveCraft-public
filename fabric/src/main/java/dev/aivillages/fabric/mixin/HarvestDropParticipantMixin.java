package dev.aivillages.fabric.mixin;

import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.Kind;
import dev.aivillages.fabric.runtime.evidence.*;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The entity's own stored tag and tracked stack establish drop incarnation/revision. */
@Mixin(ItemEntity.class)
public abstract class HarvestDropParticipantMixin implements DepositParticipant,LiveHarvestDrop {
    @Shadow private UUID target;
    @Unique private final LiveDropCustody cognitivecraft$liveDrop=new LiveDropCustody();
    @Unique private DepositParticipantState cognitivecraft$harvestDrop;
    @Override public void cognitivecraft$liveCustody(BooleanSupplier owner){cognitivecraft$liveDrop.bind(owner);}
    @Override public boolean cognitivecraft$liveCustody(){return cognitivecraft$liveDrop.active();}
    @Inject(method="tick",at=@At("HEAD"))
    private void cognitivecraft$releaseStoppedWheat(CallbackInfo ci) {
        var item=(ItemEntity)(Object)this;
        if(item.getItem().is(Items.WHEAT) && item.getUUID().equals(target) && !cognitivecraft$liveCustody())target=null;
    }
    @Override public DepositParticipantState cognitivecraft$depositParticipant(){
        if(cognitivecraft$harvestDrop==null)cognitivecraft$harvestDrop=new DepositParticipantState();return cognitivecraft$harvestDrop;
    }
    @Inject(method="addAdditionalSaveData",at=@At("TAIL"))
    private void cognitivecraft$saveHarvestDrop(ValueOutput output,CallbackInfo ci) {
        if(cognitivecraft$harvestDrop!=null){cognitivecraft$harvestDrop.save(output);((EntityMembershipParticipant)this).cognitivecraft$membershipData().save(output,cognitivecraft$harvestDrop);}
    }
    @Inject(method="readAdditionalSaveData",at=@At("TAIL"))
    private void cognitivecraft$loadHarvestDrop(ValueInput input,CallbackInfo ci) {
        cognitivecraft$liveDrop.revoke();
        var state=cognitivecraft$depositParticipant();state.load(input,Kind.ACTOR);
        if(state.active() && state.valid()){state.trackLoaded(List.of(((ItemEntity)(Object)this).getItem()));((EntityMembershipParticipant)this).cognitivecraft$membershipData().load(input,state);}
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
