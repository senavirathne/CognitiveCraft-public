package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.CitizenRegistry.*;
import static org.junit.jupiter.api.Assertions.*;

final class CitizenIdentityStoreTest {
    @TempDir Path world;

    private BootstrapJournal.State seedBootstrap() throws Exception {
        try (var journal = BootstrapJournal.open(world)) {
            var owner = CitizenRegistryTest.caller(journal.state().worldId());
            var actor = new ActorRef(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld");
            var enrollment = new BootstrapJournal.Enrollment(actor, owner);
            journal.replace(journal.state(), enrollment, List.of());
            return journal.replace(journal.state(), enrollment, List.of(new BootstrapJournal.RunMarker(
                    UUID.randomUUID(), actor.citizenId(), BootstrapJournal.Phase.TERMINAL,
                    "SUCCEEDED", null, 3, 1, "a".repeat(64))));
        }
    }
    private Path bootstrapFile() { return world.resolve(BootstrapJournal.WORLD_RELATIVE_PATH).resolve("state.json"); }
    private Path identityFile() { return world.resolve(CitizenIdentityStore.WORLD_RELATIVE_PATH).resolve("state.json"); }

    @Test void migrationPreservesBindingRightsRunHistoryAndOriginalBytes() throws Exception {
        var legacy = seedBootstrap(); byte[] original = Files.readAllBytes(bootstrapFile());
        try (var journal = BootstrapJournal.open(world);
             var identities = CitizenIdentityStore.open(world, legacy.worldId())) {
            var imported = identities.migrateBootstrap(journal.state());
            assertEquals(1, imported.citizens().size());
            var citizen = imported.citizens().getFirst();
            assertEquals(legacy.enrollment().actor(), citizen.actor());
            assertEquals(legacy.enrollment().owner(), citizen.owner());
            assertEquals(BootstrapJournal.semanticSha256(legacy), imported.migration().sourceSha256());
            var handedOff = journal.migrateIdentity(imported);
            assertTrue(handedOff.externalIdentities()); assertNull(handedOff.enrollment());
            assertEquals(legacy.runs(), handedOff.runs());
            assertArrayEquals(original, Files.readAllBytes(bootstrapFile().resolveSibling("state.legacy-v1.json")));
            assertFalse(Files.readString(bootstrapFile()).contains("enrollment"));
            assertEquals(2L, StrictJson.object(Files.readString(bootstrapFile())).get("schema"));
            assertThrows(IOException.class, () -> journal.replace(handedOff, legacy.enrollment(), legacy.runs()));
            var renamed = new Citizen(citizen.actor(), citizen.owner(), "Mira", Availability.LOADED);
            var next = identities.replace(imported, new Snapshot(imported.worldId(), imported.revision() + 1,
                    List.of(renamed), imported.migration()));
            journal.replaceRuns(handedOff, handedOff.runs(), next);
        }
        try (var journal = BootstrapJournal.open(world);
             var identities = CitizenIdentityStore.open(world, legacy.worldId())) {
            assertTrue(journal.state().externalIdentities());
            assertEquals(legacy.runs(), journal.state().runs());
            var registry = new CitizenRegistry(identities.snapshot(),
                    (expected, next) -> { throw new AssertionError("read must not publish"); },
                    privateAddresses(), new CitizenRegistryTest.TestClock(), false);
            var actual = registry.query(legacy.enrollment().actor().citizenId(), legacy.enrollment().owner());
            assertEquals("Mira", actual.displayName()); assertEquals(legacy.enrollment().actor(), actual.actor());
            assertEquals(legacy.enrollment().owner(), actual.owner());
            assertEquals(Availability.UNKNOWN, actual.availability());
            assertArrayEquals(original, Files.readAllBytes(bootstrapFile().resolveSibling("state.legacy-v1.json")));
        }
    }

    @Test void interruptionAfterRegistryCommitImportsOnceOnReopen() throws Exception {
        var legacy = seedBootstrap(); byte[] original = Files.readAllBytes(bootstrapFile());
        long revision;
        try (var identities = CitizenIdentityStore.open(world, legacy.worldId())) {
            revision = identities.migrateBootstrap(legacy).revision();
        }
        assertArrayEquals(original, Files.readAllBytes(bootstrapFile()));
        try (var journal = BootstrapJournal.open(world);
             var identities = CitizenIdentityStore.open(world, legacy.worldId())) {
            var repeated = identities.migrateBootstrap(journal.state());
            assertEquals(revision, repeated.revision()); assertEquals(1, repeated.citizens().size());
            journal.migrateIdentity(repeated);
        }
        try (var journal = BootstrapJournal.open(world);
             var identities = CitizenIdentityStore.open(world, legacy.worldId())) {
            var same = journal.migrateIdentity(identities.snapshot());
            assertEquals(legacy.revision() + 1, same.revision());
            assertEquals(revision, identities.snapshot().revision());
        }
    }

    @Test void interruptedReferencePublicationKeepsOriginalAndSingleIdentity() throws Exception {
        var legacy = seedBootstrap(); byte[] original = Files.readAllBytes(bootstrapFile());
        Path blocked = bootstrapFile().resolveSibling("state.next.json");
        try (var journal = BootstrapJournal.open(world);
             var identities = CitizenIdentityStore.open(world, legacy.worldId())) {
            var imported = identities.migrateBootstrap(legacy);
            Files.createDirectory(blocked);
            assertThrows(IOException.class, () -> journal.migrateIdentity(imported));
            assertTrue(journal.readOnly());
            assertArrayEquals(original, Files.readAllBytes(bootstrapFile()));
            assertArrayEquals(original, Files.readAllBytes(bootstrapFile().resolveSibling("state.legacy-v1.json")));
            assertEquals(1, identities.snapshot().citizens().size());
        }
        Files.deleteIfExists(blocked);
        try (var journal = BootstrapJournal.open(world);
             var identities = CitizenIdentityStore.open(world, legacy.worldId())) {
            var imported = identities.migrateBootstrap(journal.state());
            assertEquals(1, imported.revision()); assertEquals(1, imported.citizens().size());
            assertTrue(journal.migrateIdentity(imported).externalIdentities());
        }
    }

    @Test void emptyBootstrapAlsoHasDurableHandoffAndUnknownReferencesReject() throws Exception {
        try (var journal = BootstrapJournal.open(world);
             var identities = CitizenIdentityStore.open(world, journal.state().worldId())) {
            assertThrows(IOException.class, () -> journal.migrateIdentity(identities.snapshot()));
            var imported = identities.migrateBootstrap(journal.state());
            assertNotNull(imported.migration()); assertNull(imported.migration().citizenId());
            var handedOff = journal.migrateIdentity(imported);
            assertTrue(handedOff.externalIdentities());
            var unknown = new BootstrapJournal.RunMarker(UUID.randomUUID(), UUID.randomUUID(),
                    BootstrapJournal.Phase.ACTIVE, null, null, 0, 0, null);
            assertThrows(IOException.class, () -> journal.replaceRuns(handedOff, List.of(unknown), imported));
            assertEquals(handedOff, journal.state());
        }
    }

    @Test void changedImportCannotReassignAnExistingCitizen() throws Exception {
        var legacy = seedBootstrap();
        try (var identities = CitizenIdentityStore.open(world, legacy.worldId())) {
            var imported = identities.migrateBootstrap(legacy);
            var otherOwner = new BootstrapJournal.Enrollment(legacy.enrollment().actor(), CitizenRegistryTest.caller(legacy.worldId()));
            var forged = new BootstrapJournal.State(legacy.worldId(), legacy.revision(), otherOwner, legacy.runs());
            assertThrows(IOException.class, () -> identities.migrateBootstrap(forged));
            assertEquals(imported, identities.snapshot());
        }
    }

    @Test void publicationFaultsReopenPriorOrCommittedMetadataWithoutBindingChanges() throws Exception {
        for (var point : CitizenIdentityStore.Point.values()) {
            Path root = world.resolve(point.name()); UUID worldId = UUID.randomUUID();
            var inject = new AtomicBoolean(); Snapshot original;
            try (var store = CitizenIdentityStore.open(root, worldId, at -> {
                if (inject.get() && at == point) throw new IOException("injected " + point);
            })) {
                var owner = CitizenRegistryTest.caller(worldId);
                var citizen = new Citizen(new ActorRef(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld"), owner, "Ada", Availability.UNKNOWN);
                original = store.replace(store.snapshot(), new Snapshot(worldId, 1, List.of(citizen), null));
                var renamed = new Citizen(citizen.actor(), citizen.owner(), "Mira", citizen.availability());
                inject.set(true);
                assertThrows(IOException.class, () -> store.replace(original, new Snapshot(worldId, 2, List.of(renamed), null)));
                assertTrue(store.readOnly()); assertEquals(original, store.snapshot());
            }
            try (var reopened = CitizenIdentityStore.open(root, worldId)) {
                var actual = reopened.snapshot().citizens().getFirst();
                assertEquals(point == CitizenIdentityStore.Point.AFTER_REPLACE ? "Mira" : "Ada", actual.displayName());
                assertEquals(original.citizens().getFirst().actor(), actual.actor());
                assertEquals(original.citizens().getFirst().owner(), actual.owner());
            }
        }
    }

    private Snapshot seedIdentities() throws Exception {
        UUID worldId = UUID.randomUUID();
        try (var store = CitizenIdentityStore.open(world, worldId)) {
            var citizen = new Citizen(new ActorRef(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld"),
                    CitizenRegistryTest.caller(worldId), "Ada", Availability.UNKNOWN);
            store.replace(store.snapshot(), new Snapshot(worldId, 1, List.of(citizen), null));
            return store.replace(store.snapshot(), new Snapshot(worldId, 2,
                    List.of(new Citizen(citizen.actor(), citizen.owner(), "Mira", Availability.UNKNOWN)), null));
        }
    }

    @Test void exactByteLimitReadsAndEmptyCorruptFutureOrAliasFieldsStayReadOnly() throws Exception {
        var state = seedIdentities(); Path current = identityFile();
        String json = Files.readString(current);
        Files.writeString(current, json + " ".repeat(CitizenIdentityStore.MAX_BYTES - json.length()));
        try (var store = CitizenIdentityStore.open(world, state.worldId())) {
            assertFalse(store.readOnly()); assertEquals(state, store.snapshot());
        }
        byte[] backup = Files.readAllBytes(current.resolveSibling("state.prev.json"));
        for (String invalid : List.of("", "{broken", "{\"schema\":999}", json.replace("\"citizens\":", "\"aliases\":[],\"citizens\":"))) {
            Files.writeString(current, invalid);
            try (var recovered = CitizenIdentityStore.open(world, state.worldId())) {
                assertTrue(recovered.readOnly()); assertEquals("Ada", recovered.snapshot().citizens().getFirst().displayName());
                assertThrows(IOException.class, () -> recovered.replace(recovered.snapshot(), state));
            }
            assertEquals(invalid, Files.readString(current));
            assertArrayEquals(backup, Files.readAllBytes(current.resolveSibling("state.prev.json")));
        }
    }

    @Test void oversizedFilesNeverRequireWholeFileAllocationOrEraseValidPriorCopy() throws Exception {
        var state = seedIdentities(); Path current = identityFile(), previous = current.resolveSibling("state.prev.json");
        byte[] backup = Files.readAllBytes(previous);
        Files.writeString(current, " ".repeat(CitizenIdentityStore.MAX_BYTES + 1));
        try (var recovered = CitizenIdentityStore.open(world, state.worldId())) { assertTrue(recovered.readOnly()); }
        grow(current);
        try (var recovered = CitizenIdentityStore.open(world, state.worldId())) { assertTrue(recovered.readOnly()); }
        assertArrayEquals(backup, Files.readAllBytes(previous));
        assertEquals((long) Integer.MAX_VALUE + 1, Files.size(current));
        grow(previous);
        assertThrows(IOException.class, () -> CitizenIdentityStore.open(world, state.worldId()));
        assertEquals((long) Integer.MAX_VALUE + 1, Files.size(current));
        assertEquals((long) Integer.MAX_VALUE + 1, Files.size(previous));
    }

    @Test void fileLockWorldMismatchStaleAndBindingReplacementReject() throws Exception {
        var state = seedIdentities();
        try (var store = CitizenIdentityStore.open(world, state.worldId())) {
            assertThrows(IOException.class, () -> CitizenIdentityStore.open(world, state.worldId()));
            assertThrows(IOException.class, () -> store.replace(new Snapshot(state.worldId(), 0, List.of(), null), state));
            var original = state.citizens().getFirst();
            var replacement = new Citizen(new ActorRef(original.actor().citizenId(), UUID.randomUUID(), original.actor().dimension()),
                    original.owner(), original.displayName(), original.availability());
            assertThrows(IOException.class, () -> store.replace(state, new Snapshot(state.worldId(), state.revision() + 1, List.of(replacement), null)));
            assertThrows(IOException.class, () -> store.replace(state, new Snapshot(state.worldId(), state.revision() + 1, List.of(), null)));
            assertEquals(state, store.snapshot());
        }
        byte[] bytes = Files.readAllBytes(identityFile());
        assertThrows(IOException.class, () -> CitizenIdentityStore.open(world, UUID.randomUUID()));
        assertArrayEquals(bytes, Files.readAllBytes(identityFile()));
    }

    private static void grow(Path path) throws IOException {
        try (var file = FileChannel.open(path, StandardOpenOption.WRITE)) {
            file.position(Integer.MAX_VALUE); file.write(ByteBuffer.wrap(new byte[]{0}));
        }
    }
}
