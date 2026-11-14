package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.runtime.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static dev.aivillages.core.kernel.runtime.persistence.ArchiveFormat.*;
import static org.junit.jupiter.api.Assertions.*;

final class ArchiveRequirementTest {
    @TempDir Path world;
    final UUID id=UUID.randomUUID();final PersistencePolicy policy=PersistencePolicy.defaults();
    void publish()throws Exception {
        try(var writer=ArchiveWriter.open(world,id,policy)) {
            Guard guard=new Guard(1,1);
            assertTrue(writer.append(writer.snapshot(),Owner.LEASES,guard,List.of(new Row(Kind.HISTORY,UUID.randomUUID(),new byte[]{1})),owner->guard).acknowledged());
        }
    }
    @Test void requiredReaderSurvivesRemovalOfTheWholeVersionDirectory()throws Exception {
        assertFalse(ArchiveRequirement.shouldRead(world));publish();assertTrue(ArchiveRequirement.shouldRead(world));
        byte[] requirement=Files.readAllBytes(world.resolve(ArchiveRequirement.PATH));
        Files.move(world.resolve(ArchiveHistory.RELATIVE_PATH),world.resolve("preserved-version-directory"));
        assertTrue(ArchiveRequirement.shouldRead(world));assertEquals(ArchiveHistory.State.FENCED,ArchiveHistory.read(world,id,policy).state());
        try(var writer=ArchiveWriter.open(world,id,policy)){assertTrue(writer.readOnly());}
        assertArrayEquals(requirement,Files.readAllBytes(world.resolve(ArchiveRequirement.PATH)));
    }
    @Test void existingAcknowledgedLegacyArchiveGainsAForcedReaderRequirement()throws Exception {
        publish();Files.move(world.resolve(ArchiveRequirement.PATH),world.resolve("preserved-legacy-requirement"));
        assertTrue(ArchiveRequirement.shouldRead(world));
        try(var writer=ArchiveWriter.open(world,id,policy)){assertFalse(writer.readOnly());assertEquals(1,writer.historyView().records());}
        assertTrue(Files.isRegularFile(world.resolve(ArchiveRequirement.PATH)));
    }
    @Test void corruptRequirementFencesAndPreservesEveryByte()throws Exception {
        publish();Path path=world.resolve(ArchiveRequirement.PATH);byte[] invalid="future-schema-99".getBytes();Files.write(path,invalid);
        assertEquals(ArchiveHistory.State.FENCED,ArchiveHistory.read(world,id,policy).state());
        try(var writer=ArchiveWriter.open(world,id,policy)){assertTrue(writer.readOnly());}
        assertArrayEquals(invalid,Files.readAllBytes(path));
    }
    @Test void pendingRequirementCannotBeTreatedAsEmptyHistory()throws Exception {
        publish();Path pending=world.resolve(ArchiveRequirement.PENDING);Files.copy(world.resolve(ArchiveRequirement.PATH),pending);
        assertEquals(ArchiveHistory.State.FENCED,ArchiveHistory.read(world,id,policy).state());assertTrue(Files.exists(pending));
    }
    @Test void requirementIsCountedByTheExistingRetentionOwnerAndByteCeiling()throws Exception {
        publish();try(var writer=ArchiveWriter.open(world,id,policy);var cursor=writer.retentionOwner().open()) {
            long bytes=0;int requirements=0,files=0;
            for(int n=0;n<policy.files()+1;n++) {
                var page=cursor.next(2,65_536);
                for(var entry:page.entries()) {
                    bytes+=entry.usage().bytes();if(entry.key().id().startsWith("requirement:"))requirements++;
                    if(entry.usage().bytes()>0)files++;
                }
                if(page.complete())break;
            }
            assertEquals(1,requirements);assertEquals(writer.historyView().bytes(),bytes);assertTrue(files<=policy.files());
            assertTrue(bytes<policy.archiveBytes());
        }
    }
}
