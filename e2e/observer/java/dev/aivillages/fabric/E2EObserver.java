package dev.aivillages.fabric;

import com.google.gson.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Observes immutable snapshots only. No command execution, setup, admission or world writes. */
public final class E2EObserver implements ModInitializer {
    private static final Gson JSON = new Gson();
    private final Path root = Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR"));
    private final AtomicReference<String> pending = new AtomicReference<>();
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "e2e-observation-writer"); thread.setDaemon(true); return thread;
    });
    private long ticks;
    private final Map<UUID,ServerPlayer> observedPlayers = new LinkedHashMap<>();

    @Override public void onInitialize() {
        writer.scheduleWithFixedDelay(this::flush, 0, 100, TimeUnit.MILLISECONDS);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++ticks % 5 == 0) pending.set(JSON.toJson(snapshot(server)));
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { flush(); writer.shutdown(); });
    }

    private JsonObject snapshot(MinecraftServer server) {
        var out = new JsonObject();
        out.addProperty("time", System.currentTimeMillis());
        out.addProperty("tick", ticks);
        out.addProperty("pid", ProcessHandle.current().pid());
        out.addProperty("java", System.getProperty("java.version"));
        var level = server.overworld();
        out.addProperty("arenaLoaded",level.hasChunkAt(new BlockPos(2,201,5)));
        out.addProperty("arenaTicking",level.getChunkSource().isPositionTicking(0L));
        var blocks = new JsonObject(); var containers = new JsonObject();
        for (int x = -1; x <= 20; x++) for (int z = -1; z <= 14; z++) {
            var pos = new BlockPos(x, 201, z); String key = x + ",201," + z;
            if (!level.hasChunkAt(pos)) continue;
            blocks.addProperty(key, level.getBlockState(pos).toString());
            if (level.getBlockEntity(pos) instanceof Container container) {
                var contents = new JsonObject();
                for (int i = 0; i < container.getContainerSize(); i++) {
                    var item = container.getItem(i);
                    String type = BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
                    if (!item.isEmpty()) contents.addProperty(type,
                            (contents.has(type) ? contents.get(type).getAsInt() : 0) + item.getCount());
                }
                containers.add(key, contents);
            }
        }
        out.add("blocks", blocks); out.add("containers", containers);
        var actors = new JsonArray();
        for (var villager : level.getEntitiesOfClass(Villager.class, new AABB(-3,199,-3,23,206,17))) {
            var inventory = new JsonObject();
            for (int i = 0; i < villager.getInventory().getContainerSize(); i++) {
                var item = villager.getInventory().getItem(i);
                if (item.isEmpty()) continue;
                String type = BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
                inventory.addProperty(type, (inventory.has(type) ? inventory.get(type).getAsInt() : 0) + item.getCount());
            }
            var actor = new JsonObject(); actor.addProperty("uuid", villager.getUUID().toString());
            actor.addProperty("alive", villager.isAlive()); actor.addProperty("baby", villager.isBaby());
            actor.addProperty("noAI", villager.isNoAi());
            actor.add("pos", JSON.toJsonTree(List.of(villager.getX(), villager.getY(), villager.getZ())));
            actor.add("inventory", inventory); actor.addProperty("controlled", AiVillages.gatewayControls(villager));
            actors.add(actor);
        }
        out.add("actors", actors);
        var drops = new JsonArray();
        for (var item : level.getEntitiesOfClass(ItemEntity.class, new AABB(-3,199,-3,23,206,17))) {
            drops.add(JSON.toJsonTree(Map.of("uuid", item.getUUID().toString(), "networkId", item.getId(),
                    "item", BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString(),
                    "count", item.getItem().getCount(), "pos", List.of(item.getX(),item.getY(),item.getZ()))));
            if (drops.size() == 128) break;
        }
        out.add("drops", drops);
        var players = new JsonObject(); var knownPlayers = new JsonObject();
        var kernel = AiVillages.kernel();
        if (kernel != null) try {
            out.addProperty("needleCalls", kernel.languageCalls());
            out.addProperty("generationCalls", kernel.generationCalls());
            out.add("brokerStats", JSON.toJsonTree(kernel.inferenceBroker().stats()));
        } catch (IllegalStateException loading) { out.addProperty("kernelLoading", true); }
        for (var player : server.getPlayerList().getPlayers()) {
            if (observedPlayers.size() < 4 || observedPlayers.containsKey(player.getUUID()))
                observedPlayers.put(player.getUUID(),player);
        }
        for (var player : observedPlayers.values()) {
            var data = new JsonObject();
            boolean online = server.getPlayerList().getPlayer(player.getUUID()) == player;
            data.addProperty("online",online);
            data.addProperty("uuid", player.getUUID().toString());
            data.add("pos", JSON.toJsonTree(List.of(player.getX(), player.getY(), player.getZ())));
            if (kernel != null) try {
                data.addProperty("scope",kernel.scope(player.getUUID()));
                data.add("citizens", JSON.toJsonTree(kernel.citizens(player)));
                data.addProperty("catalog", kernel.catalog(player));
                var runs = new JsonObject();
                for (var id : kernel.runOrTicketIds(player, false)) {
                    var language = kernel.interpretationStatus(player, id);
                    if (language != null) runs.add(id.toString(), JSON.toJsonTree(language));
                    else {
                        var view = kernel.status(player, id);
                        var record = JSON.toJsonTree(view).getAsJsonObject();
                        record.addProperty("description", KernelRunStatus.describe(view));
                        if (view.marker() != null) {
                            var owner = kernel.citizen(player, view.marker().citizenId()).owner();
                            kernel.job(id, owner).ifPresent(job -> record.add("responsibility", JSON.toJsonTree(job)));
                        }
                        runs.add(id.toString(), record);
                    }
                }
                data.add("runs", runs);
                var jobs = new JsonObject();
                for (var id : kernel.queuedJobIds(player, false)) {
                    var job = kernel.queuedJob(player, id);
                    var record = JSON.toJsonTree(job).getAsJsonObject();
                    record.addProperty("fulfilled", job.fulfilled());
                    record.add("dispatch", JSON.toJsonTree(kernel.dispatchDiagnostics(player,id)));
                    jobs.add(id.toString(), record);
                }
                data.add("jobs", jobs); data.add("broker", JSON.toJsonTree(kernel.inferenceStatus(player)));
            } catch (IllegalStateException | SecurityException unavailable) {
                data.addProperty("unavailable", unavailable.getMessage());
            }
            knownPlayers.add(player.getGameProfile().name(),data);
            if (online) players.add(player.getGameProfile().name(), data);
        }
        out.add("players", players); out.add("knownPlayers",knownPlayers);
        return out;
    }

    private void flush() {
        var data = pending.getAndSet(null);
        if (data == null) return;
        try {
            Files.createDirectories(root.resolve("server"));
            var temp = root.resolve("server/snapshot.tmp");
            Files.writeString(temp, data);
            Files.move(temp, root.resolve("server/snapshot.json"), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(root.resolve("server/observations.jsonl"), data + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception failure) { throw new IllegalStateException("Read-only E2E evidence", failure); }
    }
}
