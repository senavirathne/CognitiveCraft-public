package dev.aivillages.e2e.parallelcfixture.mixin;

import dev.aivillages.e2e.parallelcfixture.ChatTransport;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Map;

@Mixin(ClientPacketListener.class)
public abstract class ChatFeedback {
    @Inject(method = "handleSystemChat", at = @At("TAIL"))
    private void feedback(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        ChatTransport.event("system-chat-received", Map.of("text", packet.content().getString(), "overlay", packet.overlay()));
    }
}
