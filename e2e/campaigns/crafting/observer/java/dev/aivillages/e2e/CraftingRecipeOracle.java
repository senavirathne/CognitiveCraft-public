package dev.aivillages.e2e;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;

/** Test-only read-only oracle. Never calls a kernel owner or mutates gameplay. */
public final class CraftingRecipeOracle implements ModInitializer {
    private final Path root = Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR"));
    private final Gson json = new Gson();
    private String previous = "";
    private boolean exported;

    @Override public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            try {
                if (!exported) { export(server); exported = true; }
                Path path = root.resolve("oracle-request.json");
                if (!Files.exists(path)) return;
                if (Files.size(path) > 8192) throw new IllegalArgumentException("Bounded recipe probe");
                var request = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                String id = request.get("id").getAsString();
                if (id.equals(previous)) return;
                var result = probe(server, request);
                write("oracle-response.json", result);
                previous = id;
            } catch (Exception failure) {
                throw new IllegalStateException("Crafting campaign read-only vanilla oracle", failure);
            }
        });
    }

    private CraftingRecipe recipe(MinecraftServer server, String key) {
        var holder = server.getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE, Identifier.parse(key)));
        return holder.isPresent() && holder.get().value() instanceof CraftingRecipe r ? r : null;
    }

    private void export(MinecraftServer server) throws Exception {
        Path path = root.resolve("oracle-candidates.json");
        if (Files.size(path) > 131072) throw new IllegalArgumentException("Bounded recipe catalogue");
        var candidates = JsonParser.parseString(Files.readString(path)).getAsJsonArray();
        var records = new JsonObject();
        for (var value : candidates) {
            var candidate = value.getAsJsonObject();
            String key = candidate.get("id").getAsString();
            var r = recipe(server, key);
            if (r == null) throw new IllegalArgumentException("Installed recipe missing: " + key);
            var placement = r.placementInfo();
            var out = candidate.deepCopy();
            out.addProperty("recipeClass", r.getClass().getName());
            out.addProperty("special", r.isSpecial());
            var ingredients = new JsonArray();
            for (var ingredient : placement.ingredients()) {
                var alternatives = new JsonArray();
                ingredient.items().map(i -> i.unwrapKey().orElseThrow().identifier().toString())
                        .sorted().forEach(alternatives::add);
                if (alternatives.isEmpty()) throw new IllegalArgumentException("Empty ingredient: " + key);
                ingredients.add(alternatives);
            }
            out.add("ingredients", ingredients);
            out.add("slots", json.toJsonTree(placement.slotsToIngredientIndex()));
            var grid = new JsonArray();
            for (int index : placement.slotsToIngredientIndex())
                grid.add(index < 0 ? "" : ingredients.get(index).getAsJsonArray().get(0).getAsString());
            var request = candidate.deepCopy(); request.addProperty("id", key); request.addProperty("recipe", key);
            request.add("grid", grid);
            var checked = probe(server, request);
            if (!checked.get("matches").getAsBoolean()) throw new IllegalArgumentException("Invalid discovered layout: " + key);
            out.addProperty("output", checked.get("output").getAsString());
            out.addProperty("count", checked.get("count").getAsInt());
            out.addProperty("remainderCount", checked.get("remainderCount").getAsInt());
            records.add(key, out);
        }
        var result = new JsonObject(); result.addProperty("minecraft", "26.3");
        result.addProperty("serverPid", ProcessHandle.current().pid());
        result.addProperty("worldEffects", 0); result.add("recipes", records);
        write("oracle-recipes.json", result);
    }

    private JsonObject probe(MinecraftServer server, JsonObject request) {
        var out = request.deepCopy();
        int width = request.get("width").getAsInt(), height = request.get("height").getAsInt();
        var grid = request.getAsJsonArray("grid");
        if (width < 1 || width > 3 || height < 1 || height > 3 || grid.size() != width * height)
            throw new IllegalArgumentException("Crafting grid shape");
        var items = new ArrayList<ItemStack>();
        for (var element : grid) {
            String key = element.getAsString();
            items.add(key.isEmpty() ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(key))));
        }
        var r = recipe(server, request.get("recipe").getAsString());
        var input = CraftingInput.of(width, height, items);
        boolean matches = r != null && r.matches(input, server.overworld());
        out.addProperty("matches", matches); out.addProperty("worldEffects", 0);
        out.addProperty("gameTick", server.overworld().getGameTime());
        if (matches) {
            var actual = r.assemble(input);
            out.addProperty("output", BuiltInRegistries.ITEM.getKey(actual.getItem()).toString());
            out.addProperty("count", actual.getCount());
            out.addProperty("remainderCount", r.getRemainingItems(input).stream().mapToInt(ItemStack::getCount).sum());
        }
        return out;
    }

    private void write(String name, JsonElement value) throws Exception {
        Path target = root.resolve(name), temporary = root.resolve(name + ".tmp");
        Files.writeString(temporary, json.toJson(value));
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
