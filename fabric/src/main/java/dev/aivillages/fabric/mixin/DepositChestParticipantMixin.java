package dev.aivillages.fabric.mixin;

import dev.aivillages.fabric.runtime.evidence.*;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.Kind;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChestBlockEntity.class)
public abstract class DepositChestParticipantMixin implements DepositParticipant {
    @Unique private final DepositParticipantState cognitivecraft$deposit=new DepositParticipantState();
    @Override public DepositParticipantState cognitivecraft$depositParticipant(){return cognitivecraft$deposit;}
    @Inject(method="saveAdditional",at=@At("TAIL"))
    private void cognitivecraft$saveDeposit(ValueOutput output,CallbackInfo ci){cognitivecraft$deposit.save(output);}
    @Inject(method="loadAdditional",at=@At("TAIL"))
    private void cognitivecraft$loadDeposit(ValueInput input,CallbackInfo ci){cognitivecraft$deposit.load(input,Kind.CHEST);}
}
