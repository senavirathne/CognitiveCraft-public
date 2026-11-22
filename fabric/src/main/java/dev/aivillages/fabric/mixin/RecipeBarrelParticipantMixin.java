package dev.aivillages.fabric.mixin;

import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.Kind;
import dev.aivillages.fabric.runtime.evidence.DepositParticipant;
import dev.aivillages.fabric.runtime.evidence.DepositParticipantState;
import java.util.ArrayList;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reuse the saved native container participant envelope for exact recipes using barrels. */
@Mixin(BarrelBlockEntity.class)
public abstract class RecipeBarrelParticipantMixin implements DepositParticipant {
    @Unique private final DepositParticipantState cognitivecraft$recipeParticipant=new DepositParticipantState();
    @Shadow protected abstract NonNullList<ItemStack> getItems();
    @Override public DepositParticipantState cognitivecraft$depositParticipant(){return cognitivecraft$recipeParticipant;}

    @Inject(method="saveAdditional",at=@At("TAIL"))
    private void cognitivecraft$saveRecipeParticipant(ValueOutput output,CallbackInfo ignored){cognitivecraft$recipeParticipant.save(output);}

    @Inject(method="loadAdditional",at=@At("TAIL"))
    private void cognitivecraft$loadRecipeParticipant(ValueInput input,CallbackInfo ignored){
        cognitivecraft$recipeParticipant.load(input,Kind.BARREL);
        if(cognitivecraft$recipeParticipant.active() && cognitivecraft$recipeParticipant.valid())
            cognitivecraft$recipeParticipant.trackLoaded(new ArrayList<>(getItems()));
    }
}
