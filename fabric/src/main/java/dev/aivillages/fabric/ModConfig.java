package dev.aivillages.fabric;
import dev.aivillages.core.Json;
import dev.aivillages.providers.ProviderConfig;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class ModConfig {
    public int workRadius=12, maxAgents=32, maxActivePlans=8, concurrentLlmRequests=2;
    public int autoFoodThreshold=8, autonomousCheckTicks=1200, replanCooldownTicks=6000, respawnDelaySeconds=60;
    public String deathMode="permanent";
    public boolean preserveMemory=true, allowCloudDialogue;
    public List<ProviderConfig> providers=defaults();
    private static List<ProviderConfig> defaults() {
        return new ArrayList<>(List.of(
            provider("gemini","https://generativelanguage.googleapis.com/v1beta","GEMINI_API_KEY"),
            provider("cloudflare","https://api.cloudflare.com/client/v4/accounts/YOUR_ACCOUNT_ID/ai/v1/chat/completions","CLOUDFLARE_API_TOKEN"),
            provider("groq","https://api.groq.com/openai/v1/chat/completions","GROQ_API_KEY"),
            provider("ollama","http://127.0.0.1:11434/api/chat","")
        ));
    }
    private static ProviderConfig provider(String id,String endpoint,String env) {var c=new ProviderConfig();c.id=id;c.type=id;c.endpoint=endpoint;c.apiKeyEnv=env;return c;}
    public static ModConfig load(Path path) throws IOException {
        if(!Files.exists(path)){Files.createDirectories(path.getParent());Files.writeString(path,Json.GSON.toJson(new ModConfig()));}
        try {var c=Json.GSON.fromJson(Json.object(Files.readString(path),64000),ModConfig.class);c.validate();return c;}
        catch(RuntimeException ex){throw new IOException("Invalid AI Villages config: "+ex.getMessage(),ex);}
    }
    public void validate() {
        if(workRadius<2 || workRadius>16 || maxAgents<1 || maxAgents>64 || maxActivePlans<1 || maxActivePlans>16
            || concurrentLlmRequests<1 || concurrentLlmRequests>4 || autonomousCheckTicks<200 || replanCooldownTicks<1200
            || autoFoodThreshold<1 || autoFoodThreshold>64 || respawnDelaySeconds<10 || respawnDelaySeconds>86400
            || deathMode==null || !Set.of("permanent","respawn").contains(deathMode) || providers==null || providers.size()>8)
            throw new IllegalArgumentException("Invalid limits or death mode");
        var ids=new HashSet<String>();for(var p:providers){p.validate();if(!ids.add(p.id))throw new IllegalArgumentException("Duplicate provider id");}
    }
}
