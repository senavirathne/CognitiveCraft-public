package dev.aivillages.providers;
import java.net.URI;
import java.util.Set;

/** Ordered server configuration; secrets are referenced by environment variable. */
public final class ProviderConfig {
    public String id = "ollama", type = "ollama", endpoint = "http://127.0.0.1:11434/api/chat", model = "", apiKeyEnv = "";
    public boolean enabled, jsonMode = true;
    public int dailyRequests = 40, minimumIntervalSeconds = 10, timeoutSeconds = 25;
    public long dailyTokenBudget = 300000;
    public void validate() {
        if (id == null || !id.matches("[a-z0-9_-]{1,32}") || type == null || model == null || apiKeyEnv == null || endpoint == null)
            throw new IllegalArgumentException("Invalid provider config");
        if (!Set.of("gemini", "groq", "cloudflare", "ollama", "openai-compatible").contains(type)) throw new IllegalArgumentException("Unknown provider type");
        if (enabled && model.isBlank()) throw new IllegalArgumentException("Choose a model for " + id);
        if (model.length() > 256 || dailyRequests < 0 || dailyTokenBudget < 0 || minimumIntervalSeconds < 0 || timeoutSeconds < 1 || timeoutSeconds > 60)
            throw new IllegalArgumentException("Invalid provider limits");
        URI uri = URI.create(endpoint);
        boolean local = Set.of("localhost", "127.0.0.1", "[::1]").contains(String.valueOf(uri.getHost()));
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null
            || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && local)))
            throw new IllegalArgumentException("Use HTTPS or loopback HTTP without URL credentials");
        if (!apiKeyEnv.isEmpty() && !apiKeyEnv.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid environment variable name");
    }
}
