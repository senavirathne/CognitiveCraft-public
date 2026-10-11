package dev.aivillages.e2e.mixin;

import dev.aivillages.e2e.ClientDriver;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Map;

@Mixin(Connection.class)
public abstract class OutboundTraceMixin {
    @Inject(method="send(Lnet/minecraft/network/protocol/Packet;)V", at=@At("HEAD"))
    private void request(Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ServerboundCommandSuggestionPacket suggestion)
            ClientDriver.event("suggestion-request", Map.of("transaction", suggestion.id(), "text", suggestion.command()));
    }
}
