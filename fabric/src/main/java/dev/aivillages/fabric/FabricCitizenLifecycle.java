package dev.aivillages.fabric;

import dev.aivillages.core.kernel.CitizenLifecycle;
import dev.aivillages.core.kernel.CitizenRegistry;
import dev.aivillages.core.kernel.Contracts.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import java.util.*;
import static dev.aivillages.core.kernel.CitizenLifecycle.*;

/** Bounded server-thread adapter. UUID lookup never loads a chunk or enumerates world entities. */
final class FabricCitizenLifecycle {
    private record Binding(Entity entity, long generation) { }
    private final MinecraftServer server;
    private final CitizenRegistry identities;
    private final CitizenLifecycle lifecycle;
    private final Map<UUID, Binding> bindings = new HashMap<>();
    private int cursor, lastLookups;

    FabricCitizenLifecycle(MinecraftServer server, CitizenRegistry identities, CitizenLifecycle lifecycle) {
        this.server = server; this.identities = identities; this.lifecycle = lifecycle;
    }
    void loaded(Entity entity) {
        checkThread();
        if (!(entity instanceof Villager v) || !v.isAlive() || v.isRemoved() || v.isBaby()) return;
        var c = known(entity); if (c == null) return;
        UUID id = c.actor().citizenId(); Marker current = lifecycle.marker(id);
        Binding old = bindings.get(id);
        if (old != null && old.entity() != entity && !old.entity().isRemoved()) {
            offer(c, current.generation(), Event.REPLACEMENT, Source.UNSUPPORTED_REPLACEMENT); return;
        }
        if (old != null && old.entity() != entity && current.event() == Event.LOAD)
            offer(c, old.generation(), Event.MISSING, Source.MISSING_LOOKUP);
        lifecycle.offer(new Fact(identities.snapshot().worldId(), c.actor(), lifecycle.marker(id).generation(),
                Event.LOAD, Source.ENTITY_LOAD, tick()));
        bindings.put(id, new Binding(entity, lifecycle.marker(id).generation()));
    }
    void died(Entity entity) { loss(entity, Event.DEATH, Source.AFTER_DEATH); }
    void replacement(Entity entity) { loss(entity, Event.REPLACEMENT, Source.UNSUPPORTED_REPLACEMENT); }
    void unloaded(Entity entity) {
        checkThread();
        var reason = entity.getRemovalReason();
        if (reason == Entity.RemovalReason.KILLED) loss(entity, Event.DEATH, Source.KILLED);
        else if (reason == Entity.RemovalReason.DISCARDED) loss(entity, Event.REMOVE, Source.DISCARDED);
        else if (reason == Entity.RemovalReason.UNLOADED_TO_CHUNK || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER)
            loss(entity, Event.UNLOAD, Source.CHUNK_UNLOAD);
        else if (reason == Entity.RemovalReason.CHANGED_DIMENSION) loss(entity, Event.DIMENSION, Source.DIMENSION_CHANGE);
        else loss(entity, Event.MISSING, Source.UNKNOWN_REMOVAL);
    }
    private void loss(Entity entity, Event event, Source source) {
        checkThread(); var c = known(entity); if (c == null) return;
        Binding binding = bindings.get(c.actor().citizenId());
        // Native callbacks from a previously loaded instance cannot fence its replacement generation.
        if (binding != null && binding.entity() != entity) return;
        offer(c, binding == null ? lifecycle.marker(c.actor().citizenId()).generation() : binding.generation(), event, source);
    }
    private void offer(CitizenRegistry.Citizen c, long generation, Event event, Source source) {
        lifecycle.offer(new Fact(identities.snapshot().worldId(), c.actor(), generation, event, source, tick()));
    }
    private CitizenRegistry.Citizen known(Entity entity) {
        if (!server.isSameThread() || entity.level().getServer() != server) return null;
        String dimension = entity.level().dimension().identifier().toString();
        return identities.snapshot().citizens().stream().filter(c -> c.actor().entityId().equals(entity.getUUID())
                && c.actor().dimension().equals(dimension)).findFirst().orElse(null);
    }
    void step() {
        checkThread(); lastLookups = 0;
        var rows = identities.snapshot().citizens();
        for (int n = 0; n < Math.min(lifecycle.settings().lookups(), rows.size()); n++) {
            var c = rows.get(cursor++ % rows.size());
            if (lifecycle.marker(c.actor().citizenId()).blocked()) continue;
            var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(c.actor().dimension())));
            lastLookups++;
            Entity entity = level == null ? null : level.getEntity(c.actor().entityId());
            if (entity instanceof Villager && entity.isAlive() && !entity.isRemoved()) loaded(entity);
            else if (lifecycle.marker(c.actor().citizenId()).event() == Event.LOAD)
                offer(c, lifecycle.marker(c.actor().citizenId()).generation(), Event.MISSING, Source.MISSING_LOOKUP);
        }
        if (cursor > CitizenRegistry.MAX_CITIZENS * 1024) cursor %= Math.max(1, rows.size());
        lifecycle.step();
    }
    int lastLookups() { return lastLookups; }
    private long tick() { return server.overworld().getGameTime(); }
    private void checkThread() { if (!server.isSameThread()) throw new IllegalStateException("Server thread required"); }
}
