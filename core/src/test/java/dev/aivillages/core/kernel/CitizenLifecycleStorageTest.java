package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.CitizenRegistry.*;
import static dev.aivillages.core.kernel.CitizenLifecycle.*;
import static org.junit.jupiter.api.Assertions.*;

final class CitizenLifecycleStorageTest {
    @TempDir Path world;
    final UUID worldId = UUID.randomUUID();
    final TrustedContext owner = CitizenRegistryTest.caller(worldId);
    final ActorRef actor = new ActorRef(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld");
    Path file() { return world.resolve(CitizenIdentityStore.WORLD_RELATIVE_PATH).resolve("state.json"); }
    byte[] legacy() throws Exception {
        Files.createDirectories(file().getParent());
        var row = Map.of("citizenId", actor.citizenId().toString(), "entityId", actor.entityId().toString(),
                "dimension", actor.dimension(), "principal", owner.principal().id().toString(),
                "scopeWorld", worldId.toString(), "scopeDomain", owner.scope().domainId().toString(),
                "name", "Ada", "availability", "LOADED");
        Files.writeString(file(), StrictJson.canonical(Map.of("schema", 1L, "worldId", worldId.toString(),
                "revision", 3L, "citizens", List.of(row))));
        return Files.readAllBytes(file());
    }
    @Test void explicitSchemaOneMigrationPreservesOriginalIdentityAndRequiresVerification() throws Exception {
        byte[] original = legacy();
        try (var store = CitizenIdentityStore.open(world, worldId)) {
            assertEquals(4, store.snapshot().revision());
            assertEquals(2L, StrictJson.object(Files.readString(file())).get("schema"));
            var c = store.snapshot().citizens().getFirst();
            assertEquals(actor, c.actor()); assertEquals(owner, c.owner()); assertEquals("Ada", c.displayName());
            var registry = new CitizenRegistry(store.snapshot(), (a,b) -> { throw new AssertionError("read only"); },
                    privateAddresses(), new CitizenRegistryTest.TestClock(), false);
            assertFalse(registry.available(actor));
        }
        assertArrayEquals(original, Files.readAllBytes(file().resolveSibling("state.legacy-v1.json")));
        try (var store = CitizenIdentityStore.open(world, worldId)) { assertEquals(4, store.snapshot().revision()); }
        assertTrue(SaveCompatibility.registered().readable(new SaveCompatibility.Format("citizens", 1)));
        assertTrue(SaveCompatibility.registered().migration(new SaveCompatibility.Format("citizens", 1)).isPresent());
    }
    @ParameterizedTest @EnumSource(CitizenIdentityStore.Point.class)
    void interruptedSchemaMigrationKeepsExactOriginalAndRetriesOnce(CitizenIdentityStore.Point point) throws Exception {
        byte[] original = legacy();
        assertThrows(IOException.class, () -> CitizenIdentityStore.open(world, worldId, p -> {
            if (p == point) throw new IOException("injected migration boundary");
        }));
        assertArrayEquals(original, Files.readAllBytes(file().resolveSibling("state.legacy-v1.json")));
        try (var store = CitizenIdentityStore.open(world, worldId)) {
            assertFalse(store.readOnly()); assertEquals(4, store.snapshot().revision());
            assertEquals(1, store.snapshot().citizens().size());
        }
    }
    @ParameterizedTest @EnumSource(CitizenIdentityStore.Point.class)
    void terminalPublicationFaultPreservesLastCommittedEvidenceAndNeverLoadsActor(CitizenIdentityStore.Point point) throws Exception {
        legacy(); try (var ignored = CitizenIdentityStore.open(world, worldId)) { }
        try (var store = CitizenIdentityStore.open(world, worldId, p -> {
            if (p == point) throw new IOException("injected terminal publication");
        })) {
            var old = store.snapshot(); var c = old.citizens().getFirst();
            var dead = new Citizen(c.actor(), c.owner(), c.displayName(), Availability.UNAVAILABLE,
                    new Marker(0, Event.DEATH, Source.AFTER_DEATH, 20, 0));
            assertThrows(IOException.class, () -> store.replace(old,
                    new Snapshot(worldId, old.revision()+1, List.of(dead), old.migration())));
            assertTrue(store.readOnly());
        }
        try (var store = CitizenIdentityStore.open(world, worldId)) {
            var registry = new CitizenRegistry(store.snapshot(), (a,b) -> { throw new AssertionError(); },
                    privateAddresses(), new CitizenRegistryTest.TestClock(), false);
            assertFalse(registry.available(actor));
            assertEquals(point == CitizenIdentityStore.Point.AFTER_REPLACE ? Event.DEATH : Event.VERIFY,
                    store.snapshot().citizens().getFirst().lifecycle().event());
        }
    }
    @Test void terminalFactAndEachAcknowledgementRoundTripAndCannotRewindOrRevive() throws Exception {
        legacy();
        for (int ack : List.of(0, 1, 3, 7)) {
            try (var store = CitizenIdentityStore.open(world, worldId)) {
                var old = store.snapshot(); var c = old.citizens().getFirst();
                var marker = new Marker(0, Event.DEATH, Source.AFTER_DEATH, 20, ack);
                var dead = new Citizen(actor, owner, c.displayName(), Availability.UNAVAILABLE, marker);
                store.replace(old, new Snapshot(worldId, old.revision()+1, List.of(dead), old.migration()));
            }
        }
        try (var store = CitizenIdentityStore.open(world, worldId)) {
            var old = store.snapshot(); assertEquals(7, old.citizens().getFirst().lifecycle().acknowledged());
            for (var marker : List.of(Marker.initial(), new Marker(1, Event.LOAD, Source.ENTITY_LOAD, 21, 7),
                    new Marker(0, Event.DEATH, Source.AFTER_DEATH, 20, 0))) {
                var invalid = new Citizen(actor, owner, "Ada", marker.availability(), marker);
                assertThrows(IOException.class, () -> store.replace(old,
                        new Snapshot(worldId, old.revision()+1, List.of(invalid), old.migration())));
            }
        }
    }
    @Test void corruptAndUnknownInputsRemainPreservedAndCannotCreateSpeculativeControl() throws Exception {
        byte[] original = legacy();
        Files.writeString(file(), "{\"schema\":9223372036854775807}");
        byte[] unknown = Files.readAllBytes(file());
        assertThrows(IOException.class, () -> CitizenIdentityStore.open(world, worldId));
        assertArrayEquals(unknown, Files.readAllBytes(file()));
        assertFalse(Files.exists(file().resolveSibling("state.legacy-v1.json")));
        Files.write(file(), original); try (var ignored = CitizenIdentityStore.open(world, worldId)) { }
        Files.writeString(file(), "{broken");
        try (var recovered = CitizenIdentityStore.open(world, worldId)) { assertTrue(recovered.readOnly()); }
        assertEquals("{broken", Files.readString(file()));
    }
}
