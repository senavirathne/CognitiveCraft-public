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
import dev.aivillages.core.kernel.Contracts.Cuboid;
import dev.aivillages.core.kernel.ObservedWorldKnowledgeStore;
import dev.aivillages.core.kernel.SurvivalGateway;

/** Observes immutable snapshots only. No command execution, setup, admission or world writes. */
public final class E2EObserver implements ModInitializer {
    private static final Gson JSON = new Gson();
    private final Path root = Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR"));
    private final int[] observationArea = observationArea();
    private final boolean assessment = Files.exists(root.resolve("observation-area.json"));
    private JsonObject vanillaRecipeEvidence;
    private JsonObject assessmentLanguageEvidence;
    private final AtomicReference<String> pending = new AtomicReference<>();
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "e2e-observation-writer"); thread.setDaemon(true); return thread;
    });
    private long ticks;
    private final int snapshotTicks = Math.max(1, Math.min(5,
            Integer.parseInt(System.getenv().getOrDefault("COGNITIVECRAFT_E2E_SNAPSHOT_TICKS", "5"))));
    private final Map<UUID,ServerPlayer> observedPlayers = new LinkedHashMap<>();
    private final Map<UUID,CompletableFuture<ObservedWorldKnowledgeStore.EvidenceSnapshot>> knowledgeQueries = new HashMap<>();
    private final Map<UUID,JsonElement> knowledgeViews = new HashMap<>();

    @Override public void onInitialize() {
        writer.scheduleWithFixedDelay(this::flush, 0, 100, TimeUnit.MILLISECONDS);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++ticks % snapshotTicks == 0) pending.set(JSON.toJson(snapshot(server)));
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
        out.addProperty("gameTick", level.getGameTime());
        if (assessment) {
            if (vanillaRecipeEvidence == null) vanillaRecipeEvidence = vanillaRecipes(server);
            out.add("assessmentVanillaRecipes",vanillaRecipeEvidence);
            if (assessmentLanguageEvidence == null) assessmentLanguageEvidence = languageEvidence();
            out.add("assessmentLanguage",assessmentLanguageEvidence);
        }
        out.addProperty("arenaLoaded",level.hasChunkAt(new BlockPos(observationArea[0]+3,observationArea[1],observationArea[2]+6)));
        out.addProperty("arenaTicking",level.getChunkSource().isPositionTicking(0L));
        var blocks = new JsonObject(); var containers = new JsonObject();
        var placementColumnsClear = new JsonObject();
        for (int x = observationArea[0]; x <= observationArea[3]; x++)
            for (int z = observationArea[2]; z <= observationArea[4]; z++) {
            var pos = new BlockPos(x, observationArea[1], z); String key = x + ","+observationArea[1]+"," + z;
            if (!level.hasChunkAt(pos)) continue;
            blocks.addProperty(key, level.getBlockState(pos).toString());
            // Fixture precondition only: observe current native entity bounds,
            // including entities above the target that could fall into it.
            var column = new AABB(x, observationArea[1], z, x + 1, observationArea[1] + 3, z + 1);
            placementColumnsClear.addProperty(key, level.getEntities((net.minecraft.world.entity.Entity)null,
                    column, e -> e.isAlive() && !e.isSpectator()).isEmpty());
            if (assessment) for (int dy = -1; dy <= 2; dy++)
                blocks.addProperty(x+","+(observationArea[1]+dy)+","+z,level.getBlockState(pos.offset(0,dy,0)).toString());
            if ((x == 6 || x == 7) && (z == 6 || z == 7 || z == 8 || z == 10 || z == 11 || z == 12))
                blocks.addProperty(x + ",200," + z, level.getBlockState(pos.below()).toString());
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
        out.add("placementColumnsClear", placementColumnsClear);
        var actors = new JsonArray();
        var observedActors = new LinkedHashMap<UUID,Villager>();
        for (var villager : level.getEntitiesOfClass(Villager.class, new AABB(observationArea[0]-2,observationArea[1]-2,observationArea[2]-2,
                observationArea[3]+3,observationArea[1]+5,observationArea[4]+3)))
            observedActors.put(villager.getUUID(),villager);
        // The legacy speech fixture includes a corridor beyond the crop arena.
        // Retain its enrolled, already loaded subject without loading any chunks.
        var legacy = AiVillages.session();
        if (legacy != null) for (var agent : legacy.agents().stream().limit(32).toList()) {
            var villager = legacy.find(agent);
            if (villager != null && villager.level() == level)
                observedActors.put(villager.getUUID(),villager);
        }
        for (var villager : observedActors.values()) {
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
        for (var item : level.getEntitiesOfClass(ItemEntity.class, new AABB(observationArea[0]-2,observationArea[1]-2,observationArea[2]-2,
                observationArea[3]+3,observationArea[1]+5,observationArea[4]+3))) {
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
            out.add("primitiveDiagnostics", JSON.toJsonTree(kernel.diagnosticStatus()));
            out.add("retention", JSON.toJsonTree(kernel.retentionStatus()));
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
            if (assessment) {
                var inventory=new JsonObject();
                for(int i=0;i<player.getInventory().getContainerSize();i++) {
                    var item=player.getInventory().getItem(i);
                    if(!item.isEmpty()) {String key=BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
                        inventory.addProperty(key,(inventory.has(key)?inventory.get(key).getAsInt():0)+item.getCount());}
                }
                data.add("assessmentInventory",inventory);
            }
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
                // Bounded read-only query, with the actual requesting player's trusted scope.
                var query = knowledgeQueries.get(player.getUUID());
                if (query != null && query.isDone()) {
                    if (!query.isCompletedExceptionally()) knowledgeViews.put(player.getUUID(), JSON.toJsonTree(query.join()));
                    knowledgeQueries.remove(player.getUUID());
                }
                if (online && ticks % 20 == 0 && !knowledgeQueries.containsKey(player.getUUID())) {
                    var primary = server.getPlayerList().getPlayerByName("PlayerA");
                    var addresses = primary == null ? null : kernel.citizens(primary).citizens();
                    if (addresses != null && !addresses.isEmpty()) {
                        // Genuine movement can record stock at either feet height in this fixture.
                        // Alternate two bounded targets; both viewers use the same target and their own trusted scope.
                        int stockY = (ticks / 20) % 2 == 0 ? 201 : 200;
                        var target = new SurvivalGateway.ObservationTarget(SurvivalGateway.ObservationKind.ACTOR_STOCK,
                                new Cuboid("minecraft:overworld",6,stockY,6,6,stockY,6), addresses.getFirst().actor().entityId());
                        knowledgeQueries.put(player.getUUID(), kernel.observedKnowledge(player, target,
                                new ObservedWorldKnowledgeStore.QueryLimits(64,8,8192)));
                    }
                }
                if (knowledgeViews.containsKey(player.getUUID())) data.add("observedKnowledge", knowledgeViews.get(player.getUUID()));
            } catch (IllegalStateException | SecurityException unavailable) {
                data.addProperty("unavailable", unavailable.getMessage());
            }
            knownPlayers.add(player.getGameProfile().name(),data);
            if (online) players.add(player.getGameProfile().name(), data);
        }
        out.add("players", players); out.add("knownPlayers",knownPlayers);
        return out;
    }

    private int[] observationArea() {
        Path path = root.resolve("observation-area.json");
        if (!Files.exists(path)) return new int[]{-1,201,-1,20,14};
        try {
            if (Files.size(path)>2048) throw new IllegalArgumentException("Observation bounds file");
            var value=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            int[] area=java.util.stream.Stream.of("minX","footY","minZ","maxX","maxZ")
                    .mapToInt(key->value.get(key).getAsInt()).toArray();
            long width=(long)area[3]-area[0]+1,depth=(long)area[4]-area[2]+1;
            if(width<1 || depth<1 || width*depth>512 || area[1]<-60 || area[1]>300)
                throw new IllegalArgumentException("Bounded observation area required");
            return area;
        } catch(Exception failure) {throw new IllegalStateException("Assessment observation bounds",failure);}
    }

    /** Observe the unchanged raw-text classifier; no model call, binding or execution. */
    private JsonObject languageEvidence() {
        try {
            Path path=root.resolve("assessment-language.json");
            if(Files.size(path)>4096)throw new IllegalArgumentException("Bounded language observations");
            var phrases=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if(phrases.size()!=5)throw new IllegalArgumentException("Five frozen language observations required");
            var result=new JsonObject();
            for(var entry:phrases.entrySet()) {
                String text=entry.getValue().getAsString();
                var value=dev.aivillages.core.kernel.LanguageReferenceClassifier.classify(text,List.of("Ada"));
                var row=new JsonObject();row.addProperty("text",text);row.addProperty("intent",value.intent().name());
                row.addProperty("actorReference",value.actor().state().name());
                row.addProperty("sourceReference",value.source().state().name());
                row.addProperty("destinationReference",value.destination().state().name());result.add(entry.getKey(),row);
            }
            return result;
        } catch(Exception failure) {throw new IllegalStateException("Read-only assessment language observation",failure);}
    }

    /** Pure installed-game recipe matching; never withdraws, crafts or grants items. */
    private JsonObject vanillaRecipes(MinecraftServer server) {
        var out=new JsonObject();
        record Probe(String id,String recipe,int width,int height,List<String> items) { }
        var probes=List.of(
                new Probe("B02","spruce_planks",1,1,List.of("spruce_log")),
                new Probe("B03","stick",2,2,List.of("birch_planks","","birch_planks","")),
                new Probe("B04","wooden_axe",3,3,List.of("birch_planks","birch_planks","","birch_planks","stick","","","stick","")),
                new Probe("B05","wooden_pickaxe",3,3,List.of("oak_planks","oak_planks","oak_planks","","stick","","","stick","")),
                new Probe("B06","crafting_table",2,2,List.of("oak_planks","birch_planks","birch_planks","oak_planks")),
                new Probe("B08","oak_fence",3,2,List.of("oak_planks","stick","oak_planks","oak_planks","stick","oak_planks")),
                new Probe("B12","oak_planks",1,1,List.of("stripped_oak_log")));
        for(var probe:probes) {
            var value=new JsonObject();value.addProperty("recipe","minecraft:"+probe.recipe());
            var holder=server.getRecipeManager().byKey(net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.RECIPE,net.minecraft.resources.Identifier.parse("minecraft:"+probe.recipe())));
            boolean matches=false;
            if(holder.isPresent() && holder.get().value() instanceof net.minecraft.world.item.crafting.CraftingRecipe recipe) {
                var stacks=probe.items().stream().map(id->id.isEmpty() ? net.minecraft.world.item.ItemStack.EMPTY
                        : new net.minecraft.world.item.ItemStack(BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse("minecraft:"+id)))).toList();
                var input=net.minecraft.world.item.crafting.CraftingInput.of(probe.width(),probe.height(),stacks);
                matches=recipe.matches(input,server.overworld());
                if(matches) {var result=recipe.assemble(input);value.addProperty("output",BuiltInRegistries.ITEM.getKey(result.getItem()).toString());value.addProperty("count",result.getCount());}
            }
            value.addProperty("installedVanillaMatch",matches);value.addProperty("worldEffects",0);out.add(probe.id(),value);
        }
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
