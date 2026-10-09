package dev.aivillages.e2e.mixin;

import dev.aivillages.e2e.ClientDriver;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.core.registries.BuiltInRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Map;
import java.util.List;

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
    @Inject(method="handleAddEntity", at=@At("TAIL"))
    private void entityAdded(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        ClientDriver.event("entity-add", Map.of("networkId", packet.getId(), "uuid", packet.getUUID().toString(),
                "type", BuiltInRegistries.ENTITY_TYPE.getKey(packet.getType()).toString(),
                "pos", List.of(packet.getX(), packet.getY(), packet.getZ())));
    }
    @Inject(method="handleRemoveEntities", at=@At("TAIL"))
    private void entitiesRemoved(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
        ClientDriver.event("entity-remove", Map.of("networkIds", packet.entityIds().toIntArray()));
    }
    @Inject(method="handleBlockUpdate", at=@At("TAIL"))
    private void blockUpdated(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        var pos = packet.getPos();
        if (pos.getY() >= 199 && pos.getY() <= 206 && pos.getX() >= -2 && pos.getX() <= 22
                && pos.getZ() >= -2 && pos.getZ() <= 16)
            ClientDriver.event("block-update", Map.of("pos", List.of(pos.getX(),pos.getY(),pos.getZ()),
                    "state", packet.getBlockState().toString()));
    }
    @Inject(method="handleContainerContent", at=@At("TAIL"))
    private void menuContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        ClientDriver.event("menu-content", Map.of("menu", packet.containerId(), "state", packet.stateId(),
                "slots", packet.items().stream().map(item -> Map.of("item",
                        BuiltInRegistries.ITEM.getKey(item.getItem()).toString(), "count", item.getCount())).toList()));
    }
    @Inject(method="handleContainerSetSlot", at=@At("TAIL"))
    private void menuSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        ClientDriver.event("menu-slot", Map.of("menu", packet.getContainerId(), "state", packet.getStateId(),
                "slot", packet.getSlot(), "item", BuiltInRegistries.ITEM.getKey(packet.getItem().getItem()).toString(),
                "count", packet.getItem().getCount()));
    }
}
