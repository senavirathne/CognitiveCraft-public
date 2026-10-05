package dev.aivillages.providers;

import com.google.gson.*;
import dev.aivillages.core.Json;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Gemini native, Groq/Cloudflare/OpenAI-compatible, and Ollama native HTTP dialects. */
public final class HttpLlmProvider implements LlmProvider {
    private final ProviderConfig config; private final HttpClient client; private final String apiKey;
    public HttpLlmProvider(ProviderConfig config, HttpClient client, String apiKey) { config.validate(); this.config = config; this.client = client; this.apiKey = apiKey; }
    public static HttpClient newClient() { return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build(); }
    @Override public String id() { return config.id; }
    @Override public CompletableFuture<Response> generate(Request request) {
        String endpoint = config.type.equals("gemini") ? config.endpoint.replaceAll("/$", "") + "/models/"
            + URLEncoder.encode(config.model, StandardCharsets.UTF_8) + ":generateContent" : config.endpoint;
        var builder = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(config.timeoutSeconds))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.GSON.toJson(body(request))));
        if (apiKey != null && !apiKey.isBlank()) builder.header(config.type.equals("gemini") ? "x-goog-api-key" : "Authorization", config.type.equals("gemini") ? apiKey : "Bearer " + apiKey);
        CompletableFuture<HttpResponse<byte[]>> transport = client.sendAsync(builder.build(), ignored -> new LimitedBody(131072));
        CompletableFuture<Response> result = transport.thenApply(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                long delay = switch (response.statusCode()) { case 401, 403, 404 -> 3600; case 429 -> 60; default -> 30; };
                throw new ProviderFailure("HTTP " + response.statusCode(), retryAfter(response.headers().firstValue("Retry-After").orElse(""), delay));
            }
            try {
                var json = Json.object(new String(response.body(), StandardCharsets.UTF_8), 131072); String content;
                if (config.type.equals("gemini")) {
                    var candidate = json.getAsJsonArray("candidates").get(0).getAsJsonObject();
                    if (candidate.has("finishReason") && !candidate.get("finishReason").getAsString().equals("STOP")) throw new IllegalArgumentException();
                    var text = new StringBuilder();
                    for (var part : candidate.getAsJsonObject("content").getAsJsonArray("parts")) {
                        var p = part.getAsJsonObject();
                        if (p.has("text") && !(p.has("thought") && p.get("thought").getAsBoolean())) text.append(p.get("text").getAsString());
                    }
                    content = text.toString();
                } else if (config.type.equals("ollama")) {
                    if (!json.has("done") || !json.get("done").getAsBoolean() || (json.has("done_reason") && json.get("done_reason").getAsString().equals("length"))) throw new IllegalArgumentException();
                    content = json.getAsJsonObject("message").get("content").getAsString();
                } else {
                    var choice = json.getAsJsonArray("choices").get(0).getAsJsonObject();
                    if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull() && !choice.get("finish_reason").getAsString().equals("stop")) throw new IllegalArgumentException();
                    content = choice.getAsJsonObject("message").get("content").getAsString();
                }
                if (content.isBlank() || content.length() > 24000) throw new IllegalArgumentException();
                return new Response(content, config.id, config.model);
            } catch (RuntimeException ex) { throw new ProviderFailure("Invalid provider response", 30); }
        });
        result.whenComplete((v, ex) -> { if (result.isCancelled()) transport.cancel(true); }); return result;
    }
    JsonObject body(Request request) {
        var body = new JsonObject();
        if (config.type.equals("gemini")) {
            body.add("systemInstruction", Json.GSON.toJsonTree(Map.of("parts", List.of(Map.of("text", request.system())))));
            body.add("contents", Json.GSON.toJsonTree(List.of(Map.of("role", "user", "parts", List.of(Map.of("text", request.user()))))));
            var options = new JsonObject(); options.addProperty("temperature", 0.2); options.addProperty("maxOutputTokens", request.maxOutputTokens());
            if (config.jsonMode && request.jsonSchema() != null) { options.addProperty("responseMimeType", "application/json"); options.add("responseJsonSchema", Json.object(request.jsonSchema(), 24000)); }
            body.add("generationConfig", options);
        } else {
            body.addProperty("model", config.model); body.addProperty("stream", false);
            body.add("messages", Json.GSON.toJsonTree(List.of(Map.of("role", "system", "content", request.system()), Map.of("role", "user", "content", request.user()))));
            if (config.type.equals("ollama")) {
                body.add("options", Json.GSON.toJsonTree(Map.of("temperature", 0.2, "num_predict", request.maxOutputTokens())));
                if (config.jsonMode && request.jsonSchema() != null) body.add("format", Json.object(request.jsonSchema(), 24000));
            } else {
                body.addProperty("temperature", 0.2); body.addProperty("max_tokens", request.maxOutputTokens());
                if (config.jsonMode && request.jsonSchema() != null) body.add("response_format", Json.GSON.toJsonTree(Map.of("type", "json_object")));
            }
        }
        return body;
    }
    static long retryAfter(String value, long fallback) {
        try { return Math.clamp(Long.parseLong(value), 1, 86400); }
        catch (RuntimeException ex) {
            try { return Math.clamp(Duration.between(Instant.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds(), 1, 86400); }
            catch (RuntimeException ignored) { return fallback; }
        }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int max; private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>(); private Flow.Subscription subscription;
        LimitedBody(int max) { this.max = max; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > max - bytes.size()) { subscription.cancel(); result.completeExceptionally(new ProviderFailure("Response too large", 60)); return; }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable ex) { result.completeExceptionally(ex); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
