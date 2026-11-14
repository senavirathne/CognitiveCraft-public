package dev.aivillages.core.kernel.runtime.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;
import static dev.aivillages.core.kernel.runtime.evidence.NativeDepositTest.*;
import static org.junit.jupiter.api.Assertions.*;

final class DepositJournalTest {
    @TempDir Path world;
    static DepositJournal.Snapshot next(DepositJournal.Snapshot before,List<Entry> entries){return new DepositJournal.Snapshot(before.world(),before.epoch(),before.revision()+1,entries);}
    @Test void globalCapacityRefusalPreservesTheExactCurrentAndRequirementBytes()throws Exception {
        try(var journal=DepositJournal.open(world,WORLD)) {
            var current=world.resolve(DepositJournal.PATH+"/state.jsonl");byte[] before=Files.readAllBytes(current);
            var required=world.resolve(DepositJournal.REQUIRED);byte[] requirement=Files.readAllBytes(required);
            journal.capacity(amount->false);
            assertThrows(IOException.class,()->journal.replace(journal.snapshot(),next(journal.snapshot(),List.of(new Entry(intent(),null)))));
            assertTrue(journal.snapshot().entries().isEmpty());assertArrayEquals(before,Files.readAllBytes(current));assertArrayEquals(requirement,Files.readAllBytes(required));
            assertFalse(Files.exists(world.resolve(DepositJournal.PATH+"/state.next.jsonl")));
        }
    }
    @Test void boundedRetentionAccountsRequirementsAndPinsEveryPhysicalFile()throws Exception {
        try(var journal=DepositJournal.open(world,WORLD)) {
            var owner=journal.retentionOwner();assertTrue(owner.admissionCeiling().isPresent());
            var cursor=owner.open();var rows=new ArrayList<dev.aivillages.core.kernel.WorldRetentionManager.Entry>();
            boolean complete=false;
            for(int n=0;n<5 && !complete;n++) {
                var page=cursor.next(2,65536);assertTrue(page.valid());assertTrue(page.inspected()<=2);rows.addAll(page.entries());complete=page.complete();
            }
            assertTrue(complete);assertEquals(5,rows.size()); // record summary, two requirements, current, lock
            long total=rows.stream().mapToLong(e->e.usage().bytes()).sum();long expected;
            try(var paths=Files.walk(world)){expected=paths.filter(Files::isRegularFile).mapToLong(p->{try{return Files.size(p);}catch(IOException e){throw new java.io.UncheckedIOException(e);}}).sum();}
            assertEquals(expected,total);
            for(var row:rows)assertEquals(dev.aivillages.core.kernel.WorldRetentionManager.OwnerStatus.PROTECTED,owner.collect(row,owner.revision(),()->false).status());
            var old=owner.open();journal.replace(journal.snapshot(),next(journal.snapshot(),List.of(new Entry(intent(),null))));
            assertFalse(old.next(2,65536).valid());
        }
    }
    @Test void acknowledgedIntentAndResultRetainExactIdentityAcrossARealFileReopen()throws Exception {
        Entry prepared=new Entry(intent(),null);DepositJournal.Snapshot complete;
        try(var journal=DepositJournal.open(world,WORLD)) {
            var first=journal.replace(journal.snapshot(),next(journal.snapshot(),List.of(prepared)));
            complete=journal.replace(first,next(first,List.of(completed(prepared.intent()))));
        }
        byte[] original=Files.readAllBytes(world.resolve(DepositJournal.PATH+"/state.jsonl"));
        try(var journal=DepositJournal.open(world,WORLD)){assertFalse(journal.readOnly());assertEquals(complete,journal.snapshot());assertArrayEquals(original,DepositJournal.encode(journal.snapshot()));}
    }
    @Test void corruptCurrentUsesReadOnlyPreviousWithoutReplacingEitherEvidenceFile()throws Exception {
        try(var journal=DepositJournal.open(world,WORLD)) {
            var state=journal.replace(journal.snapshot(),next(journal.snapshot(),List.of(new Entry(intent(),null))));
            journal.replace(state,next(state,List.of(completed(state.entries().getFirst().intent()))));
        }
        Path current=world.resolve(DepositJournal.PATH+"/state.jsonl"),previous=world.resolve(DepositJournal.PATH+"/state.prev.jsonl");
        byte[] originalPrevious=Files.readAllBytes(previous),corrupt=Files.readAllBytes(current);corrupt[corrupt.length-3]^=1;Files.write(current,corrupt);
        try(var journal=DepositJournal.open(world,WORLD)){assertTrue(journal.readOnly());assertNull(journal.snapshot().entries().getFirst().result());assertThrows(IOException.class,()->journal.replace(journal.snapshot(),next(journal.snapshot(),journal.snapshot().entries())));}
        assertArrayEquals(corrupt,Files.readAllBytes(current));assertArrayEquals(originalPrevious,Files.readAllBytes(previous));
    }
    @Test void missingVersionCannotTurnARequiredWorldIntoAnEmptyWritableJournal()throws Exception {
        try(var journal=DepositJournal.open(world,WORLD)){journal.replace(journal.snapshot(),next(journal.snapshot(),List.of(new Entry(intent(),null))));}
        Path version=world.resolve(DepositJournal.PATH);Files.move(version,world.resolve("preserved-deposit-version"));
        try(var journal=DepositJournal.open(world,WORLD)){assertTrue(journal.readOnly());assertThrows(IOException.class,()->journal.replace(journal.snapshot(),next(journal.snapshot(),List.of())));}
        assertTrue(Files.exists(world.resolve("preserved-deposit-version/state.jsonl")));
    }
    @ParameterizedTest @EnumSource(DepositJournal.Point.class)
    void everyPublicationFailureFencesThisWriterAndPreservesTheOriginalIntent(DepositJournal.Point point)throws Exception {
        Entry prepared=new Entry(intent(),null);
        try(var journal=DepositJournal.open(world,WORLD)){journal.replace(journal.snapshot(),next(journal.snapshot(),List.of(prepared)));}
        try(var journal=DepositJournal.open(world,WORLD,p->{if(p==point)throw new IOException("fixture fault "+point);})) {
            assertThrows(IOException.class,()->journal.replace(journal.snapshot(),next(journal.snapshot(),List.of(completed(prepared.intent())))));
            assertTrue(journal.readOnly());assertEquals(prepared,journal.snapshot().entries().getFirst());
            assertThrows(IOException.class,()->journal.replace(journal.snapshot(),next(journal.snapshot(),List.of(completed(prepared.intent())))));
        }
        try(var journal=DepositJournal.open(world,WORLD)){assertEquals(prepared.intent(),journal.snapshot().entries().getFirst().intent());}
    }
    @Test void pendingBytesAndUnknownRequirementEpochArePreservedAndReadOnly()throws Exception {
        try(var journal=DepositJournal.open(world,WORLD)) { }
        Path pending=world.resolve(DepositJournal.PATH+"/state.next.jsonl");byte[] staged="unacknowledged evidence".getBytes();Files.write(pending,staged);
        try(var journal=DepositJournal.open(world,WORLD)){assertTrue(journal.readOnly());}
        assertArrayEquals(staged,Files.readAllBytes(pending));
    }
    @Test void noDeletionChangedBodiesOrCrossWorldSnapshotCanBecomeTheCurrentHistory()throws Exception {
        try(var journal=DepositJournal.open(world,WORLD)) {
            Entry prepared=new Entry(intent(),null);var first=journal.replace(journal.snapshot(),next(journal.snapshot(),List.of(prepared)));
            assertThrows(IOException.class,()->journal.replace(first,next(first,List.of())));
            assertThrows(IOException.class,()->journal.replace(first,next(first,List.of(new Entry(intent(),null)))));
            assertThrows(IllegalArgumentException.class,()->new DepositJournal.Snapshot(UUID.randomUUID(),first.epoch(),first.revision()+1,first.entries()));
            assertEquals(first,journal.snapshot());
        }
    }
    @Test void quotasAndDuplicatePhysicalOrdinalsFailBeforeAnyReplacement()throws Exception {
        var entries=new ArrayList<Entry>();for(int n=0;n<64;n++)entries.add(new Entry(intent(),null));
        try(var journal=DepositJournal.open(world,WORLD)) {
            var full=journal.replace(journal.snapshot(),next(journal.snapshot(),entries));assertEquals(64,full.entries().size());
            entries.add(new Entry(intent(),null));assertThrows(IllegalArgumentException.class,()->next(full,entries));
            assertTrue(Files.size(world.resolve(DepositJournal.PATH+"/state.jsonl"))<DepositJournal.MAX_BYTES);
        }
        Intent i=intent();Key key=new Key(i.key().world(),i.key().root(),UUID.randomUUID(),i.key().attempt(),i.key().generation(),i.key().ordinal());
        Intent sameOrdinal=new Intent(key,i.origin(),UUID.randomUUID(),UUID.randomUUID(),i.actor(),i.chest(),i.item(),i.actorSlot(),i.chestSlot(),i.capacity(),i.conditions());
        assertThrows(IllegalArgumentException.class,()->new DepositJournal.Snapshot(WORLD,UUID.randomUUID(),0,List.of(new Entry(i,null),new Entry(sameOrdinal,null))));
    }
    @Test void externalCurrentCorruptionAndUnknownFilesAreNeverOverwrittenByAnOpenWriter()throws Exception {
        try(var journal=DepositJournal.open(world,WORLD)) {
            Path path=world.resolve(DepositJournal.PATH+"/state.jsonl");byte[] corrupt="corrupt current".getBytes();Files.write(path,corrupt);
            assertThrows(IOException.class,()->journal.replace(journal.snapshot(),next(journal.snapshot(),List.of(new Entry(intent(),null)))));
            assertTrue(journal.readOnly());assertArrayEquals(corrupt,Files.readAllBytes(path));
        }
        Path extra=world.resolve(DepositJournal.PATH+"/unknown-future-format");Files.writeString(extra,"preserve");
        try(var journal=DepositJournal.open(world,WORLD)){assertTrue(journal.readOnly());}
        assertEquals("preserve",Files.readString(extra));
    }
    @Test void missingWholeEvidenceDirectoryStillRequiresTheOriginalReader()throws Exception {
        try(var journal=DepositJournal.open(world,WORLD)){journal.replace(journal.snapshot(),next(journal.snapshot(),List.of(new Entry(intent(),null))));}
        Files.move(world.resolve(DepositJournal.BASE),world.resolve("preserved-all-deposit-evidence"));assertTrue(DepositJournal.exists(world));
        try(var journal=DepositJournal.open(world,WORLD)){assertTrue(journal.readOnly());assertThrows(IOException.class,()->journal.replace(journal.snapshot(),next(journal.snapshot(),List.of())));}
        assertTrue(Files.exists(world.resolve("preserved-all-deposit-evidence/v1/state.jsonl")));
    }
    @Test void conflictingActualReceiptBodyCannotReplaceTheAcknowledgedResult()throws Exception {
        Entry complete=completed(intent());var r=complete.result().receipt();
        var changed=new dev.aivillages.core.kernel.ResourceDelivery.Receipt(UUID.randomUUID(),r.origin(),r.runId(),r.operationId(),r.batchId(),r.actor(),r.source(),r.destination(),r.item(),r.stage(),r.quantity(),r.sourceBlock(),r.dropIds());
        Result different=new Result(complete.result().intent(),complete.result().live(),complete.result().actor(),complete.result().chest(),complete.result().failure(),changed,complete.result().usage());
        try(var journal=DepositJournal.open(world,WORLD)) {
            var state=journal.replace(journal.snapshot(),next(journal.snapshot(),List.of(complete)));byte[] bytes=Files.readAllBytes(world.resolve(DepositJournal.PATH+"/state.jsonl"));
            assertThrows(IOException.class,()->journal.replace(state,next(state,List.of(new Entry(complete.intent(),different)))));
            assertArrayEquals(bytes,Files.readAllBytes(world.resolve(DepositJournal.PATH+"/state.jsonl")));
        }
    }
}
