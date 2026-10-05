package dev.aivillages.core;
import java.util.*;

/** Durable world-owned state. It never contains credentials or live Minecraft objects. */
public final class WorldData {
    public int schemaVersion = 1;
    public Map<String, Agent> agents = new LinkedHashMap<>();
    public Map<String, Skill> skills = new LinkedHashMap<>();
    public Map<String, Usage> usage = new LinkedHashMap<>();
    public List<String> villageMemory = new ArrayList<>();
    public record Place(String dimension, int x, int y, int z) { }
    public static final class Agent {
        public String id, entityId, owner, name;
        public Place home;
        public String profession = "minecraft:none", form = "villager", voiceId = "default";
        public boolean alive = true, autoFood;
        public long revision, respawnAtEpochMillis;
        public List<String> memories = new ArrayList<>();
        public Map<String, Integer> trust = new LinkedHashMap<>();
        public double courage = 0.5, sociability = 0.5;
        public String lastFoodEvidence = "";
        public void remember(String message) { boundedAdd(memories, message, 40); }
        public void interaction(String player) {
            if (!trust.containsKey(player) && trust.size() >= 32) trust.remove(trust.keySet().iterator().next());
            trust.put(player, Math.min(100, trust.getOrDefault(player, 0) + 1));
        }
    }
    public static final class Skill {
        public Plan plan; public String provider, createdAt; public int successes, failures; public long totalTicks; public String lastFailure = "";
        public Skill() { }
        public Skill(Plan plan, String provider) { this.plan = plan; this.provider = provider; createdAt = java.time.Instant.now().toString(); }
        public boolean reusable() { return successes > 0 && failures <= successes; }
    }
    public static final class Usage { public String utcDay; public int requests; public long reservedTokens; }
    public Optional<Skill> reusableFoodSkill() {
        return skills.values().stream().filter(Skill::reusable).filter(s -> s.plan.goal().equals("FOOD_SUPPLY"))
            .max(Comparator.comparingDouble(s -> (double) s.successes / (s.successes + s.failures)));
    }
    public void record(Plan plan, String provider, boolean success, String reason, long ticks) {
        String key = fingerprint(plan); Skill skill = skills.get(key);
        if (skill == null && success) {
            if (skills.size() >= 64) skills.remove(skills.keySet().iterator().next());
            skill = new Skill(plan, provider); skills.put(key, skill);
        }
        if (skill != null) {
            if (success) skill.successes++; else { skill.failures++; skill.lastFailure = reason; }
            skill.totalTicks += ticks;
        }
    }
    public static String fingerprint(Plan plan) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Json.GSON.toJson(plan).getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public static void boundedAdd(List<String> list, String value, int max) {
        list.add(value.length() > 512 ? value.substring(0, 512) : value); while (list.size() > max) list.removeFirst();
    }
}
