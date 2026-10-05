package dev.aivillages.fabric;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.entity.npc.villager.Villager;

/** Only the physical gateway owns this transient worker-control table. */
final class GatewayControl {
    private static final Map<UUID, UUID> RUNS = new HashMap<>();
    private GatewayControl() { }
    static boolean acquire(Villager villager, UUID runId) {
        if (AiVillages.session() != null && AiVillages.session().controls(villager)) return false;
        UUID owner = RUNS.get(villager.getUUID());
        if (owner != null && !owner.equals(runId)) return false;
        RUNS.put(villager.getUUID(), runId);
        return true;
    }
    static boolean holds(Villager villager, UUID runId) {
        return runId.equals(RUNS.get(villager.getUUID()));
    }
    static boolean controls(Villager villager) { return RUNS.containsKey(villager.getUUID()); }
    static UUID owner(Villager villager) { return RUNS.get(villager.getUUID()); }
    static void release(Villager villager, UUID runId) {
        if (holds(villager, runId)) RUNS.remove(villager.getUUID());
    }
    static void releaseByEntity(UUID entityId, UUID runId) {
        if (runId.equals(RUNS.get(entityId))) RUNS.remove(entityId);
    }
    static void clearForServerLifecycle() { RUNS.clear(); }
}
