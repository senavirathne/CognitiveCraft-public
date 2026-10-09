package dev.aivillages.e2e;

import com.google.gson.*;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.*;
import java.util.*;

/** Bounded local mailbox drives ordinary Minecraft client actions, never server owners. */
public final class ClientDriver implements ClientModInitializer {
    private static final Gson JSON = new Gson();
    private static final Path ROOT = Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR"));
    private static final String NAME = System.getenv("COGNITIVECRAFT_E2E_PLAYER");
    private static final Path BOX = ROOT.resolve("clients/" + NAME);
    private String last = "";
    private long ticks;
    private boolean wasPlaying;
    private Runnable nextTick;
    private BlockPos breaking;
    private int moveTicks;

    public static synchronized void event(String kind, Object data) {
        try {
            Files.createDirectories(BOX);
            Files.writeString(BOX.resolve("events.jsonl"), JSON.toJson(Map.of(
                    "time", System.currentTimeMillis(), "kind", kind, "player", NAME,
                    "data", data)) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception failure) { throw new IllegalStateException("E2E evidence write", failure); }
    }

    @Override public void onInitializeClient() {
        event("driver", Map.of("pid", ProcessHandle.current().pid(), "java", System.getProperty("java.version")));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        ticks++;
        boolean play = mc.player != null && mc.level != null && mc.getConnection() != null
                && mc.getConnection().hasClientLoaded();
        if (play != wasPlaying) {
            event(play ? "play" : "disconnect", Map.of("tick", ticks,
                    "uuid", mc.player == null ? "absent" : mc.player.getUUID().toString()));
            wasPlaying = play;
        }
        if (nextTick != null) { var action = nextTick; nextTick = null; action.run(); }
        if (breaking != null && play) {
            if (mc.level.getBlockState(breaking).isAir()) {
                mc.gameMode.stopDestroyBlock(); breaking = null;
            } else mc.gameMode.continueDestroyBlock(breaking, Direction.UP);
        }
        if (moveTicks > 0 && --moveTicks == 0) releaseKeys(mc);
        if (ticks % 2 != 0) return;
        try {
            Path request = BOX.resolve("request.json");
            if (!Files.exists(request) || Files.size(request) > 32_768) return;
            JsonObject input = JsonParser.parseString(Files.readString(request)).getAsJsonObject();
            String id = input.get("id").getAsString();
            if (id.equals(last)) return;
            last = id;
            execute(mc, id, input);
        } catch (Exception failure) {
            reply(last, Map.of("error", failure.toString()));
        }
    }

    private void execute(Minecraft mc, String id, JsonObject request) {
        String action = request.get("action").getAsString();
        event("action", request);
        if (action.equals("connect")) {
            String address = request.get("address").getAsString();
            ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(address),
                    new ServerData("E2E", address, ServerData.Type.OTHER), false, null);
            reply(id, Map.of("connecting", address)); return;
        }
        if (action.equals("snapshot")) { reply(id, snapshot(mc)); return; }
        if (action.equals("disconnect")) {
            releaseKeys(mc); mc.disconnect(new TitleScreen(), false);
            reply(id, Map.of("disconnected", true)); return;
        }
        if (action.equals("exit")) { reply(id, Map.of("exiting", true)); mc.stop(); return; }
        if (mc.player == null || mc.level == null || mc.getConnection() == null)
            throw new IllegalStateException("Client is not in multiplayer play");
        switch (action) {
            case "command" -> {
                mc.setScreenAndShow(null);
                String command = request.get("text").getAsString();
                mc.getConnection().sendCommand(command.startsWith("/") ? command.substring(1) : command);
                reply(id, Map.of("sent", command));
            }
            case "tab" -> {
                String text = request.get("text").getAsString();
                var chat = new ChatScreen(text, false);
                mc.setScreenAndShow(chat);
                nextTick = () -> {
                    chat.keyPressed(new KeyEvent(InputConstants.KEY_TAB, InputConstants.KEY_TAB, 0));
                    event("tab-key", Map.of("text", text));
                    reply(id, Map.of("tabPressed", text));
                };
            }
            case "suggest" -> {
                mc.setScreenAndShow(null);
                String text = request.get("text").getAsString();
                var dispatcher = mc.getConnection().getCommands();
                var parsed = dispatcher.parse(text.startsWith("/") ? text.substring(1) : text,
                        mc.getConnection().getSuggestionsProvider());
                dispatcher.getCompletionSuggestions(parsed).whenComplete((suggestions, error) -> {
                    if (error != null) reply(id, Map.of("error", error.toString()));
                    else reply(id, Map.of("suggestions", suggestions.getList().stream().map(s -> s.getText()).toList(),
                            "start", suggestions.getRange().getStart(), "end", suggestions.getRange().getEnd()));
                });
            }
            case "look" -> {
                BlockPos pos = position(request);
                Vec3 delta = Vec3.atCenterOf(pos).subtract(mc.player.getEyePosition());
                mc.player.setYRot((float)(Math.toDegrees(Math.atan2(-delta.x, delta.z))));
                mc.player.setXRot((float)(-Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)))));
                reply(id, Map.of("looking", List.of(pos.getX(), pos.getY(), pos.getZ())));
            }
            case "move" -> {
                mc.setScreenAndShow(null);
                mc.player.setYRot(request.get("yaw").getAsFloat());
                moveTicks = Math.min(200, request.get("ticks").getAsInt());
                if (moveTicks < 1) throw new IllegalArgumentException("Movement bound");
                mc.options.keyUp.setDown(true);
                reply(id, Map.of("walkingTicks", moveTicks));
            }
            case "use", "open", "place" -> {
                releaseKeys(mc); mc.setScreenAndShow(null);
                BlockPos pos = position(request);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
                reply(id, Map.of("interactionSent", pos.toShortString()));
            }
            case "break" -> {
                mc.setScreenAndShow(null); breaking = position(request);
                mc.gameMode.startDestroyBlock(breaking, Direction.UP);
                reply(id, Map.of("breaking", breaking.toShortString()));
            }
            case "close-menu" -> { mc.player.closeContainer(); reply(id, Map.of("closed", true)); }
            case "menu-click" -> {
                int slot = request.get("slot").getAsInt();
                if (slot < 0 || slot >= mc.player.containerMenu.slots.size()) throw new IllegalArgumentException("Slot");
                mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slot, 0,
                        request.has("quick") && request.get("quick").getAsBoolean()
                                ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP, mc.player);
                reply(id, Map.of("clicked", slot));
            }
            default -> throw new IllegalArgumentException("Unknown client action " + action);
        }
    }

    private static BlockPos position(JsonObject request) {
        var p = request.getAsJsonArray("pos");
        return new BlockPos(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt());
    }

    private static void releaseKeys(Minecraft mc) {
        mc.options.keyUp.setDown(false); mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false); mc.options.keyRight.setDown(false);
    }

    private static JsonObject snapshot(Minecraft mc) {
        JsonObject out = new JsonObject();
        out.addProperty("play", mc.player != null && mc.level != null && mc.getConnection() != null
                && mc.getConnection().hasClientLoaded());
        out.addProperty("singleplayer", mc.hasSingleplayerServer());
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return out;
        out.addProperty("uuid", mc.player.getUUID().toString());
        out.add("pos", JSON.toJsonTree(List.of(mc.player.getX(), mc.player.getY(), mc.player.getZ())));
        out.add("tree", tree(mc.getConnection().getCommands().getRoot().getChild("aivillage")));
        var entities = new JsonArray();
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity.distanceToSqr(mc.player) > 4096) continue;
            entities.add(JSON.toJsonTree(Map.of("uuid", entity.getUUID().toString(),
                    "type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                    "pos", List.of(entity.getX(), entity.getY(), entity.getZ()))));
            if (entities.size() == 128) break;
        }
        out.add("entities", entities);
        var blocks = new JsonObject();
        for (int x = -1; x <= 20; x++) for (int z = -1; z <= 14; z++) {
            BlockPos pos = new BlockPos(x, 201, z);
            blocks.addProperty(x + ",201," + z, mc.level.getBlockState(pos).toString());
        }
        out.add("blocks", blocks);
        out.addProperty("menu", mc.player.containerMenu.containerId);
        var slots = new JsonArray();
        for (var slot : mc.player.containerMenu.slots) {
            var item = slot.getItem();
            slots.add(JSON.toJsonTree(Map.of("slot", slot.index, "item", BuiltInRegistries.ITEM.getKey(item.getItem()).toString(),
                    "count", item.getCount(), "playerInventory", slot.container == mc.player.getInventory())));
        }
        out.add("slots", slots);
        return out;
    }

    private static JsonElement tree(CommandNode<?> node) {
        if (node == null) return JsonNull.INSTANCE;
        var out = new JsonObject();
        for (var child : node.getChildren()) out.add(child.getName(), tree(child));
        return out;
    }

    private static void reply(String id, Object result) {
        try {
            var target = BOX.resolve("response.json");
            var temp = target.resolveSibling("response.tmp");
            Files.writeString(temp, JSON.toJson(Map.of("id", id, "result", result)));
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception failure) { throw new IllegalStateException("E2E response", failure); }
    }
}
