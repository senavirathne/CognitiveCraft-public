package dev.aivillages.e2e.parallelcfixture;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** Test-only real chat transport. Sends bounded chat, observes feedback; has no world/effect port. */
public final class ChatTransport implements ClientModInitializer {
    private static final Gson JSON = new Gson();
    private static Path box;
    private String last = "";
    private long tick;

    @Override public void onInitializeClient() {
        if (!"chat-only".equals(System.getenv("COGNITIVECRAFT_C_FIXTURE_ALLOWED")))
            throw new IllegalStateException("C chat fixture requires explicit isolated harness opt-in");
        String root = System.getenv("COGNITIVECRAFT_E2E_DIR"), player = System.getenv("COGNITIVECRAFT_E2E_PLAYER");
        if (root == null || player == null || !player.matches("CPlayer[AB]"))
            throw new IllegalStateException("C fixture needs a dedicated C client evidence identity");
        box = Path.of(root).resolve("clients").resolve(player).resolve("c-chat");
        event("fixture-start", Map.of("pid", ProcessHandle.current().pid(), "java", System.getProperty("java.version")));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        tick++;
        if (tick % 2 != 0 || client.player == null || client.getConnection() == null) return;
        try {
            Path request = box.resolve("request.json");
            if (!Files.isRegularFile(request) || Files.size(request) > 8192) return;
            JsonObject input = JsonParser.parseString(Files.readString(request)).getAsJsonObject();
            if (!input.keySet().equals(java.util.Set.of("id", "text"))) throw new IllegalArgumentException("chat fields");
            String id = input.get("id").getAsString(), text = input.get("text").getAsString();
            if (id.equals(last)) return;
            if (id.length() > 64 || text.isBlank() || text.length() > 256 || text.startsWith("/")
                    || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) throw new IllegalArgumentException("bounded ordinary chat");
            last = id;
            client.getConnection().sendChat(text);
            event("chat-submitted", Map.of("id", id, "text", text, "tick", tick,
                    "native_player_uuid", client.player.getUUID().toString()));
        } catch (Exception failure) { event("fixture-error", Map.of("error", failure.toString(), "tick", tick)); }
    }

    public static synchronized void event(String kind, Object data) {
        if (box == null) throw new IllegalStateException("C fixture not initialized");
        try {
            Files.createDirectories(box);
            Files.writeString(box.resolve("events.jsonl"), JSON.toJson(Map.of("kind", kind,
                    "time", System.currentTimeMillis(), "data", data)) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception failure) { throw new IllegalStateException("C chat evidence", failure); }
    }
}
