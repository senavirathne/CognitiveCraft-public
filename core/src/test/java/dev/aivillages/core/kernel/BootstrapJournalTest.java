package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.Contracts.ActorRef;
import dev.aivillages.core.kernel.Contracts.PrincipalRef;
import dev.aivillages.core.kernel.Contracts.ScopeRef;
import dev.aivillages.core.kernel.Contracts.TrustedContext;
import dev.aivillages.core.kernel.Outcomes.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class BootstrapJournalTest {
    @TempDir Path world;

    private static BootstrapJournal.Enrollment enrollment(UUID worldId) {
        var actor = new ActorRef(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld");
        return new BootstrapJournal.Enrollment(actor,
                new TrustedContext(new PrincipalRef(UUID.randomUUID()),
                        new ScopeRef(worldId, UUID.randomUUID())));
    }

    @Test void persistsEnrollmentAndInterruptsWithoutReplaying() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID citizenId;
        try (var journal = BootstrapJournal.open(world)) {
            var owner = enrollment(journal.state().worldId());
            citizenId = owner.actor().citizenId();
            var first = journal.replace(journal.state(), owner, List.of());
            assertEquals(1, first.revision());
            var active = new BootstrapJournal.RunMarker(runId, citizenId,
                    BootstrapJournal.Phase.ACTIVE, null, null, 2, 1, null);
            assertEquals(2, journal.replace(first, owner, List.of(active)).revision());
            assertThrows(IOException.class, () -> BootstrapJournal.open(world));
        }
        try (var reopened = BootstrapJournal.open(world)) {
            var recovered = reopened.interruptedOnReload();
            var marker = recovered.runs().getFirst();
            assertEquals(BootstrapJournal.Phase.INTERRUPTED, marker.phase());
            assertEquals(Reason.INTERRUPTED, marker.reason());
            assertEquals(2, marker.effects());
            assertEquals(1, marker.modelCalls());
            assertEquals(runId, marker.id());
            assertEquals(citizenId, reopened.state().enrollment().actor().citizenId());
            reopened.replace(reopened.state(), recovered.enrollment(), recovered.runs());
        }
        try (var reopened = BootstrapJournal.open(world)) {
            assertEquals(BootstrapJournal.Phase.INTERRUPTED,
                    reopened.state().runs().getFirst().phase());
            assertTrue(reopened.state().runs().stream().noneMatch(r ->
                    r.phase() == BootstrapJournal.Phase.ACTIVE));
        }
    }

    @Test void staleAndBoundedStateCannotOverwrite() throws Exception {
        try (var journal = BootstrapJournal.open(world)) {
            var initial = journal.state();
            var owner = enrollment(initial.worldId());
            journal.replace(initial, owner, List.of());
            assertThrows(IOException.class, () -> journal.replace(initial, owner, List.of()));
            List<BootstrapJournal.RunMarker> many = new ArrayList<>();
            for (int i = 0; i < BootstrapJournal.MAX_RUNS + 1; i++)
                many.add(new BootstrapJournal.RunMarker(UUID.randomUUID(),
                        owner.actor().citizenId(), BootstrapJournal.Phase.TERMINAL,
                        "BLOCKED", Reason.RESOURCE_MISSING, 0, 0, null));
            assertThrows(IllegalArgumentException.class,
                    () -> journal.replace(journal.state(), owner, many));
            assertEquals(1, journal.state().revision());
        }
    }

    @Test void corruptOrUnknownCurrentPreservesBackupAndDisablesWrites() throws Exception {
        Path current = world.resolve(BootstrapJournal.WORLD_RELATIVE_PATH).resolve("state.json");
        for (String damage : List.of("", "{broken", "{\"schema\":999}")) {
            try (var journal = BootstrapJournal.open(world)) {
                var owner = journal.state().enrollment() == null
                        ? enrollment(journal.state().worldId()) : journal.state().enrollment();
                journal.replace(journal.state(), owner, List.of());
                journal.replace(journal.state(), owner, List.of());
            }
            Files.writeString(current, damage);
            try (var recovered = BootstrapJournal.open(world)) {
                assertTrue(recovered.readOnly());
                assertNotNull(recovered.state().enrollment());
                assertThrows(IOException.class, () -> recovered.replace(recovered.state(),
                        recovered.state().enrollment(), List.of()));
                assertEquals(damage, Files.readString(current));
            }
            // Restore the independently preserved prior snapshot for the next case.
            Files.copy(current.resolveSibling("state.prev.json"), current,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test void exactInputByteLimitRemainsReadable() throws Exception {
        var saved = saveEnrollmentAndBackup();
        Path current = stateFile();
        String json = Files.readString(current);
        Files.writeString(current, json + " ".repeat(16_384 - json.length()));
        assertEquals(16_384, Files.size(current));
        try (var reopened = BootstrapJournal.open(world)) {
            assertFalse(reopened.readOnly());
            assertEquals(saved, reopened.state());
        }
        assertEquals(16_384, Files.size(current));
    }

    @Test void excessiveInputRecoversPriorStateWithoutReplacingEvidence() throws Exception {
        var saved = saveEnrollmentAndBackup();
        Path current = stateFile(), previous = current.resolveSibling("state.prev.json");
        byte[] backup = Files.readAllBytes(previous);
        Files.writeString(current, " ".repeat(16_385));
        try (var reopened = BootstrapJournal.open(world)) {
            assertTrue(reopened.readOnly());
            assertEquals(saved.enrollment(), reopened.state().enrollment());
            assertEquals(saved.revision() - 1, reopened.state().revision());
            assertThrows(IOException.class, () -> reopened.replace(reopened.state(),
                    reopened.state().enrollment(), List.of()));
        }
        assertEquals(16_385, Files.size(current));
        assertArrayEquals(backup, Files.readAllBytes(previous));
    }

    @Test void sparseFileBeyondJvmArrayLimitStillRecoversReadOnly() throws Exception {
        var saved = saveEnrollmentAndBackup();
        Path current = stateFile(), previous = current.resolveSibling("state.prev.json");
        byte[] backup = Files.readAllBytes(previous);
        growBeyondArrayLimit(current);
        try (var reopened = BootstrapJournal.open(world)) {
            assertTrue(reopened.readOnly());
            assertEquals(saved.enrollment(), reopened.state().enrollment());
            assertThrows(IOException.class, () -> reopened.replace(reopened.state(),
                    reopened.state().enrollment(), List.of()));
        }
        assertEquals((long) Integer.MAX_VALUE + 1, Files.size(current));
        assertArrayEquals(backup, Files.readAllBytes(previous));
    }

    @Test void twoOversizedCopiesStayPreservedAndInactive() throws Exception {
        saveEnrollmentAndBackup();
        Path current = stateFile(), previous = current.resolveSibling("state.prev.json");
        growBeyondArrayLimit(current);
        growBeyondArrayLimit(previous);
        assertThrows(IOException.class, () -> BootstrapJournal.open(world));
        assertEquals((long) Integer.MAX_VALUE + 1, Files.size(current));
        assertEquals((long) Integer.MAX_VALUE + 1, Files.size(previous));
    }

    private Path stateFile() {
        return world.resolve(BootstrapJournal.WORLD_RELATIVE_PATH).resolve("state.json");
    }

    private BootstrapJournal.State saveEnrollmentAndBackup() throws Exception {
        try (var journal = BootstrapJournal.open(world)) {
            var owner = enrollment(journal.state().worldId());
            journal.replace(journal.state(), owner, List.of());
            return journal.replace(journal.state(), owner, List.of());
        }
    }

    private static void growBeyondArrayLimit(Path path) throws IOException {
        // A sparse file exercises the old unbounded allocation without filling the disk.
        try (var output = FileChannel.open(path, StandardOpenOption.WRITE)) {
            output.position(Integer.MAX_VALUE);
            output.write(ByteBuffer.wrap(new byte[]{0}));
        }
    }
}
