package dev.aivillages.e2e.mixin;

import dev.aivillages.e2e.ClientDriver;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Map;

@Mixin(ClientPacketListener.class)
public abstract class PacketTraceMixin {
    @Inject(method="handleSystemChat", at=@At("TAIL"))
    private void feedback(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        ClientDriver.event("feedback", Map.of("text", packet.content().getString(), "overlay", packet.overlay()));
    }
    @Inject(method="handleCommandSuggestions", at=@At("TAIL"))
    private void suggestions(ClientboundCommandSuggestionsPacket packet, CallbackInfo ci) {
        ClientDriver.event("suggestion-response", Map.of("transaction", packet.id(), "start", packet.start(),
                "length", packet.length(), "values", packet.toSuggestions().getList().stream().map(s -> s.getText()).toList()));
    }
    @Inject(method="handleCommands", at=@At("TAIL"))
    private void commands(ClientboundCommandsPacket packet, CallbackInfo ci) {
        ClientDriver.event("command-tree", Map.of("received", true));
    }
}
