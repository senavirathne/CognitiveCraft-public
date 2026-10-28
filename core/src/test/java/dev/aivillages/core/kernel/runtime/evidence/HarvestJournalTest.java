package dev.aivillages.core.kernel.runtime.evidence;

import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dev.aivillages.core.kernel.runtime.evidence.NativeHarvest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real files and forced replacement faults; record bodies are synthetic, not Minecraft saves. */
final class HarvestJournalTest {
    @TempDir Path world;
    static HarvestJournal.Snapshot next(HarvestJournal.Snapshot s,List<Entry> entries){return new HarvestJournal.Snapshot(s.world(),s.epoch(),s.revision()+1,entries);}
    @Test void originalPreparedResultAndPositiveVerificationReopenExactly() throws Exception {
        Entry e=NativeHarvestTest.completed(NativeHarvestTest.intent());HarvestJournal.Snapshot expected;
        try(var j=HarvestJournal.open(world,e.intent().key().world())) {
            var start=j.snapshot();j.replace(start,next(start,List.of(new Entry(e.intent(),null))));
            var prepared=j.snapshot();j.replace(prepared,next(prepared,List.of(e)));
            var proof=new Verification(Reconciliation.SAVED_COMPLETE,NativeHarvestTest.actor(e,true),NativeHarvestTest.source(e,true),NativeHarvestTest.drops(e,true),UUID.randomUUID(),100);
            var complete=j.snapshot();expected=j.replace(complete,next(complete,List.of(new Entry(e.intent(),e.result(),proof))));
        }
        try(var reopened=HarvestJournal.open(world,e.intent().key().world())){assertFalse(reopened.readOnly());assertEquals(expected,reopened.snapshot());}
    }
    @Test void corruptCurrentPreservesItsBytesAndCannotAuthorizeFallbackWrites() throws Exception {
        Entry e=NativeHarvestTest.completed(NativeHarvestTest.intent());
        try(var j=HarvestJournal.open(world,e.intent().key().world())){var s=j.snapshot();j.replace(s,next(s,List.of(e)));}
        Path current=world.resolve(HarvestJournal.PATH).resolve("state.jsonl");byte[] corrupt=Files.readAllBytes(current);corrupt[corrupt.length-3]^=1;Files.write(current,corrupt);
        try(var j=HarvestJournal.open(world,e.intent().key().world())){assertTrue(j.readOnly());var s=j.snapshot();assertThrows(IOException.class,()->j.replace(s,next(s,s.entries())));}
        assertArrayEquals(corrupt,Files.readAllBytes(current));
    }
    @Test void stagedUnacknowledgedEvidenceStaysRequiredAndFencedAfterActualWriteFault() throws Exception {
        Entry e=NativeHarvestTest.completed(NativeHarvestTest.intent());
        try(var j=HarvestJournal.open(world,e.intent().key().world(),point->{if(point==HarvestJournal.Point.AFTER_STAGE)throw new IOException("fixture: after actual staged force");})) {
            var s=j.snapshot();assertThrows(IOException.class,()->j.replace(s,next(s,List.of(e))));assertTrue(j.readOnly());
        }
        Path pending=world.resolve(HarvestJournal.PATH).resolve("state.next.jsonl");byte[] original=Files.readAllBytes(pending);
        try(var j=HarvestJournal.open(world,e.intent().key().world())){assertTrue(j.readOnly());}
        assertArrayEquals(original,Files.readAllBytes(pending));
    }
    @Test void missingRequiredMarkerCannotTurnExistingHistoryIntoAFreshWriter() throws Exception {
        Entry e=NativeHarvestTest.completed(NativeHarvestTest.intent());
        try(var j=HarvestJournal.open(world,e.intent().key().world())){var s=j.snapshot();j.replace(s,next(s,List.of(e)));}
        Path current=world.resolve(HarvestJournal.PATH).resolve("state.jsonl");byte[] original=Files.readAllBytes(current);Files.delete(world.resolve(HarvestJournal.REQUIRED));
        assertTrue(HarvestJournal.exists(world));
        try(var j=HarvestJournal.open(world,e.intent().key().world())){assertTrue(j.readOnly());assertEquals(e,j.snapshot().entries().getFirst());}
        assertArrayEquals(original,Files.readAllBytes(current));assertFalse(Files.exists(world.resolve(HarvestJournal.REQUIRED)));
    }
    @Test void sixtyFourSyntheticRecordsRemainFiniteAndTheSixtyFifthCannotEvictOne() throws Exception {
        var entries=new ArrayList<Entry>();for(int n=0;n<64;n++)entries.add(new Entry(NativeHarvestTest.intent(),null));
        try(var j=HarvestJournal.open(world,entries.getFirst().intent().key().world())) {
            var s=j.snapshot();j.replace(s,next(s,entries));assertEquals(64,j.snapshot().entries().size());
            byte[] original=Files.readAllBytes(world.resolve(HarvestJournal.PATH).resolve("state.jsonl"));entries.add(new Entry(NativeHarvestTest.intent(),null));
            assertThrows(IllegalArgumentException.class,()->next(j.snapshot(),entries));
            assertArrayEquals(original,Files.readAllBytes(world.resolve(HarvestJournal.PATH).resolve("state.jsonl")));
        }
    }
    @Test void foreignWorldCannotRewriteTheOriginalEpochOrEvidence() throws Exception {
        Entry e=NativeHarvestTest.completed(NativeHarvestTest.intent());
        try(var j=HarvestJournal.open(world,e.intent().key().world())){var s=j.snapshot();j.replace(s,next(s,List.of(e)));}
        Path current=world.resolve(HarvestJournal.PATH).resolve("state.jsonl");byte[] original=Files.readAllBytes(current);
        try(var j=HarvestJournal.open(world,UUID.randomUUID())){assertTrue(j.readOnly());}
        assertArrayEquals(original,Files.readAllBytes(current));
    }
}
