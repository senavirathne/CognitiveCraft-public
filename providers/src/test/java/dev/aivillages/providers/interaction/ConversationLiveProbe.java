package dev.aivillages.providers.interaction;

import com.google.gson.JsonObject;
import dev.aivillages.core.Json;
import dev.aivillages.core.kernel.interaction.BlockerProjection;
import dev.aivillages.core.kernel.interaction.ResponseFacts;
import dev.aivillages.core.kernel.interaction.ResponseTemplates;
import dev.aivillages.providers.LlmProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Actual configured llama.cpp HTTP generation/tokenization. Never starts or downloads a model server. */
public final class ConversationLiveProbe {
    static String sha(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    static UUID id(long n) { return new UUID(0, n); }
    static ResponseFacts facts(String key) {
        if (key.equals("SMALL_TALK")) return ResponseFacts.smallTalk("en");
        var activity = new BlockerProjection.ActivityRef(id(1), id(2), id(3), id(4), id(5), id(6), id(7), 1, 0, "0".repeat(64), 1_000_000);
        var evidence = new BlockerProjection.Evidence(activity, id(8), 1, 1, true, true, true,
                List.of(new BlockerProjection.Row(id(9), true, true, true, BlockerProjection.Code.valueOf(key))));
        return ResponseFacts.forBlocker(new BlockerProjection().project(evidence, 1).orElseThrow(), "en");
    }
    static final class Capture implements LlmProvider {
        final URI base; final String model, digest, tokenizer; final Path folder;
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        final List<Map<String, Object>> tokenizations = new CopyOnWriteArrayList<>(), generations = new CopyOnWriteArrayList<>();
        Capture(URI base, String model, String digest, String tokenizer, Path folder) { this.base = base; this.model = model; this.digest = digest; this.tokenizer = tokenizer; this.folder = folder; }
        HttpRequest post(String path, Object body) {
            return HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.GSON.toJson(body))).build();
        }
        JsonObject response(HttpResponse<java.io.InputStream> response) {
            try (var stream = response.body()) {
                byte[] bytes = stream.readNBytes(131073);
                if (bytes.length > 131072 || response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalArgumentException("backend HTTP/size failure");
                return Json.object(new String(bytes, StandardCharsets.UTF_8), 131072);
            } catch (java.io.IOException failure) { throw new IllegalStateException("backend read failure"); }
        }
        List<Map<String, String>> messages(Request request) { return List.of(Map.of("role", "system", "content", request.system()), Map.of("role", "user", "content", request.user())); }
        long count(Request request) {
            try {
                JsonObject applied = response(http.send(post("/apply-template", Map.of("messages", messages(request))), HttpResponse.BodyHandlers.ofInputStream()));
                String prompt = applied.get("prompt").getAsString();
                JsonObject tokens = response(http.send(post("/tokenize", Map.of("content", prompt, "add_special", true, "parse_special", true)), HttpResponse.BodyHandlers.ofInputStream()));
                long count = tokens.getAsJsonArray("tokens").size();
                var row = Map.<String,Object>of("model_sha256", digest, "tokenizer_sha256", tokenizer, "messages", messages(request),
                        "prompt", prompt, "prompt_sha256", sha(prompt), "tokens", tokens.get("tokens"), "count", count);
                tokenizations.add(row); save(); return count;
            } catch (Exception failure) { throw new IllegalStateException("actual tokenizer unavailable"); }
        }
        void save() {
            synchronized (this) {
                try { Files.writeString(folder.resolve("wire.json"), Json.GSON.toJson(Map.of("tokenizations", tokenizations, "generations", generations)) + "\n"); }
                catch (java.io.IOException failure) { throw new IllegalStateException("evidence write failure"); }
            }
        }
        public String id() { return "configured-llama-cpp"; }
        public CompletableFuture<Response> generate(Request request) {
            Object body = Map.of("model", model, "messages", messages(request), "stream", false, "max_tokens", request.maxOutputTokens(),
                    "temperature", 0, "response_format", Map.of("type", "json_object"));
            return http.sendAsync(post("/v1/chat/completions", body), HttpResponse.BodyHandlers.ofInputStream()).thenApply(raw -> {
                JsonObject data = response(raw); generations.add(Map.of("request", body, "response", data, "http_status", raw.statusCode())); save();
                var choice = data.getAsJsonArray("choices").get(0).getAsJsonObject();
                if (!choice.get("finish_reason").getAsString().equals("stop")) throw new IllegalArgumentException("generation did not finish");
                return new Response(choice.getAsJsonObject("message").get("content").getAsString(), id(), model);
            });
        }
    }
    public static void main(String[] args) throws Exception {
        URI base = URI.create(args[0]); String model = args[1], digest = args[2], tokenizer = args[3]; Path root = Path.of(args[4]);
        Path productionJar = Path.of(ConversationProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isRegularFile(productionJar)) throw new IllegalStateException("production JAR classpath required");
        String productionSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(productionJar)));
        List<Map<String,Object>> rows = new ArrayList<>();
        List<String> fixtures = List.of("SMALL_TALK", "UNAUTHORIZED", "UNKNOWN_WORLD_STATE", "UNSUPPORTED_EFFECT", "DESTINATION_FULL", "STALE_REFERENCE");
        try (var timer = Executors.newSingleThreadScheduledExecutor()) {
            for (int i = 0; i < fixtures.size(); i++) {
                String caseId = "C03-L" + String.format("%02d", i + 1); Path folder = root.resolve(caseId); Files.createDirectory(folder);
                ResponseFacts facts = facts(fixtures.get(i)); var capture = new Capture(base, model, digest, tokenizer, folder);
                var budget = new ConversationProvider.TokenBudget(digest, tokenizer, capture::count);
                var wrapper = new ConversationProvider(capture, budget, timer);
                var ticket = new ConversationProvider.Ticket(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1, UUID.randomUUID());
                long started = System.nanoTime(); var result = wrapper.respond(ticket, facts, t -> true).get(12, TimeUnit.SECONDS); capture.save();
                Map<String,Object> row = new LinkedHashMap<>(); row.put("case_id", caseId); row.put("fact_packet", facts.packet()); row.put("facts_sha256", facts.digest());
                row.put("tier", "GENUINE_MODEL_SYNTHETIC_FACTS"); row.put("reason", result.reason().name()); row.put("text", result.text()); row.put("deliverable", result.deliverable());
                row.put("provider_calls", result.providerCalls()); row.put("reserved_input_tokens", result.reservedInputTokens()); row.put("reserved_output_tokens", result.reservedOutputTokens());
                row.put("actual_input_tokens", result.actualInputTokens()); row.put("actual_output_tokens", result.actualOutputTokens()); row.put("compute_termination_confirmed", result.computeTerminationConfirmed());
                row.put("elapsed_ns", System.nanoTime() - started); row.put("model_sha256", digest); row.put("tokenizer_sha256", tokenizer);
                row.put("production_jar_sha256", productionSha);
                row.put("wire", caseId + "/wire.json"); row.put("allowed_replies", ResponseTemplates.choices(facts)); rows.add(row);
                Files.writeString(root.resolve("responses.json"), Json.GSON.toJson(rows) + "\n");
            }
        }
    }
}
