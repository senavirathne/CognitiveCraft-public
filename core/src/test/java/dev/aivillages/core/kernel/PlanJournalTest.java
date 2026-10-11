package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class PlanJournalTest {
    @TempDir Path temp;
    final UUID world=UUID.randomUUID();
    PlanJournal.Link link(){UUID parent=UUID.randomUUID();return new PlanJournal.Link(Plan.id(parent),parent,JobLifecycleStoreTest.owner(world),
            List.of(Plan.child(parent,0),Plan.child(parent,1)),List.of(JobLifecycleStoreTest.REF));}
    PlanJournal.Snapshot next(PlanJournal.Snapshot before,PlanJournal.Link link){return new PlanJournal.Snapshot(world,before.revision()+1,List.of(link),List.of());}
    @Test void roundTripPreservesOnlyLinkageAndSanitizedDemonstratedTopology()throws Exception {
        var link=link();var topology=new Plan.Proposal(List.of(new Plan.Node(0,0,1,List.of()),new Plan.Node(1,1,1,List.of(0))));
        var template=new PlanJournal.Template(UUID.randomUUID(),link.origin(),topology);PlanJournal.Snapshot expected;
        try(var journal=PlanJournal.open(temp,world)){
            expected=new PlanJournal.Snapshot(world,1,List.of(link),List.of(template));journal.replace(journal.snapshot(),expected);
            assertThrows(IOException.class,()->PlanJournal.open(temp,world));
        }
        try(var journal=PlanJournal.open(temp,world)){assertFalse(journal.readOnly());assertEquals(expected,journal.snapshot());}
        String bytes=Files.readString(temp.resolve(PlanJournal.WORLD_RELATIVE_PATH).resolve("state.jsonl"));
        assertFalse(bytes.contains("observedTick"));assertFalse(bytes.contains("receipts"));assertFalse(bytes.contains("amount\":2"));
        assertTrue(SaveCompatibility.registered().readable(new SaveCompatibility.Format(PlanJournal.DOMAIN,1)));
        assertFalse(SaveCompatibility.registered().readable(new SaveCompatibility.Format(PlanJournal.DOMAIN,2)));
    }
    @ParameterizedTest @EnumSource(PlanJournal.Point.class)
    void everyInterruptedPublicationBoundaryRecoversAWholeGenerationWithoutReplay(PlanJournal.Point point)throws Exception {
        var first=link();PlanJournal.Snapshot original;
        try(var journal=PlanJournal.open(temp,world)){original=next(journal.snapshot(),first);journal.replace(journal.snapshot(),original);}
        var second=link();
        try(var journal=PlanJournal.open(temp,world,p->{if(p==point)throw new IOException("interrupted");})){
            assertThrows(IOException.class,()->journal.replace(journal.snapshot(),next(journal.snapshot(),second)));assertTrue(journal.readOnly());
        }
        try(var journal=PlanJournal.open(temp,world)) {
            var recovered=journal.snapshot();assertEquals(point==PlanJournal.Point.AFTER_REPLACE?second:first,recovered.links().getFirst());
            assertTrue(recovered.templates().isEmpty());assertFalse(Files.exists(temp.resolve(JobJournal.WORLD_RELATIVE_PATH)));
        }
    }
    @Test void corruptCurrentCannotOverwriteValidatedPriorBackupDuringStaging()throws Exception {
        var first=link();Path current=temp.resolve(PlanJournal.WORLD_RELATIVE_PATH).resolve("state.jsonl");
        try(var journal=PlanJournal.open(temp,world)){journal.replace(journal.snapshot(),next(journal.snapshot(),first));}
        try(var journal=PlanJournal.open(temp,world,p->{if(p==PlanJournal.Point.AFTER_STAGE)Files.writeString(current,"externally damaged");})){
            assertThrows(IOException.class,()->journal.replace(journal.snapshot(),next(journal.snapshot(),link())));
        }
        try(var journal=PlanJournal.open(temp,world)){assertTrue(journal.readOnly());assertEquals(first,journal.snapshot().links().getFirst());}
        assertEquals("externally damaged",Files.readString(current));
    }
    @Test void unknownCurrentAndValidBackupRemainPreservedInactive()throws Exception {
        var first=link();Path dir=temp.resolve(PlanJournal.WORLD_RELATIVE_PATH);
        try(var journal=PlanJournal.open(temp,world)){journal.replace(journal.snapshot(),next(journal.snapshot(),first));journal.replace(journal.snapshot(),next(journal.snapshot(),link()));}
        byte[] unknown="{\"schema\":999,\"newMeaning\":true}\n".getBytes();Files.write(dir.resolve("state.jsonl"),unknown);
        byte[] backup=Files.readAllBytes(dir.resolve("state.prev.jsonl"));
        try(var journal=PlanJournal.open(temp,world)){assertTrue(journal.readOnly());assertEquals(first,journal.snapshot().links().getFirst());
            assertThrows(IOException.class,()->journal.replace(journal.snapshot(),next(journal.snapshot(),link())));}
        assertArrayEquals(unknown,Files.readAllBytes(dir.resolve("state.jsonl")));assertArrayEquals(backup,Files.readAllBytes(dir.resolve("state.prev.jsonl")));
    }
    @Test void corruptStageCannotBecomeAnAcknowledgedGeneration()throws Exception {
        var first=link();Path stage=temp.resolve(PlanJournal.WORLD_RELATIVE_PATH).resolve("state.next.jsonl");
        try(var journal=PlanJournal.open(temp,world)){journal.replace(journal.snapshot(),next(journal.snapshot(),first));}
        try(var journal=PlanJournal.open(temp,world,p->{if(p==PlanJournal.Point.BEFORE_REPLACE)Files.writeString(stage,"damaged stage");})){
            assertThrows(IOException.class,()->journal.replace(journal.snapshot(),next(journal.snapshot(),link())));}
        try(var journal=PlanJournal.open(temp,world)){assertEquals(first,journal.snapshot().links().getFirst());}
    }
    @Test void quotasRejectNewPublicationWithoutEvictingLinkageOrTemplates()throws Exception {
        try(var journal=PlanJournal.open(temp,world)) {
            var first=link();journal.replace(journal.snapshot(),next(journal.snapshot(),first));byte[] before=Files.readAllBytes(temp.resolve(PlanJournal.WORLD_RELATIVE_PATH).resolve("state.jsonl"));
            journal.capacity(bytes->false);assertThrows(IOException.class,()->journal.replace(journal.snapshot(),next(journal.snapshot(),link())));
            assertFalse(journal.readOnly());assertEquals(first,journal.snapshot().links().getFirst());assertArrayEquals(before,Files.readAllBytes(temp.resolve(PlanJournal.WORLD_RELATIVE_PATH).resolve("state.jsonl")));
            assertThrows(IllegalArgumentException.class,()->new PlanJournal.Snapshot(world,2,java.util.stream.IntStream.range(0,17).mapToObj(i->link()).toList(),List.of()));
        }
    }
    @Test void retentionAccountsEveryRecoveryFileAndProtectsActiveLinkage()throws Exception {
        try(var journal=PlanJournal.open(temp,world)) {
            journal.replace(journal.snapshot(),next(journal.snapshot(),link()));journal.replace(journal.snapshot(),next(journal.snapshot(),link()));
            var owner=journal.retentionOwner();try(var cursor=owner.open()){
                var page=cursor.next(8,8192);assertTrue(page.complete());assertTrue(page.valid());assertEquals(4,page.entries().size());
                for(var entry:page.entries())assertEquals(WorldRetentionManager.OwnerStatus.PROTECTED,owner.collect(entry,owner.revision(),()->false).status());
            }
        }
    }
    @Test void symlinkedPlanPathsAreNeverFollowed()throws Exception {
        var dir=temp.resolve(PlanJournal.WORLD_RELATIVE_PATH);Files.createDirectories(dir);Path outside=temp.resolve("outside");Files.writeString(outside,"untouched");
        Files.createSymbolicLink(dir.resolve("state.jsonl"),outside);assertThrows(IOException.class,()->PlanJournal.open(temp,world));assertEquals("untouched",Files.readString(outside));
    }
}
