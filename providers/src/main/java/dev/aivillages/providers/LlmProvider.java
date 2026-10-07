package dev.aivillages.providers;
import java.util.concurrent.CompletableFuture;

/** Provider SPI. No Minecraft class is visible at this boundary. */
public interface LlmProvider {
    record Request(String system, String user, String jsonSchema, int maxOutputTokens) {
        public Request {
            if (system == null || user == null || system.length() + user.length() > 24000 || maxOutputTokens < 1 || maxOutputTokens > 4096)
                throw new IllegalArgumentException("Prompt or output exceeds budget");
        }
        public long tokenReservation() {
            return system.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + user.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + (jsonSchema == null ? 0 : jsonSchema.length()) + maxOutputTokens;
        }
    }
    record Response(String text, String provider, String model) { }
    String id();
    CompletableFuture<Response> generate(Request request);
}
