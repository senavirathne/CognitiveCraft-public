package dev.aivillages.core.kernel.runtime.evidence;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dev.aivillages.core.kernel.runtime.evidence.NativePickup.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual forced files with synthetic records; no native original-owner qualification. */
final class PickupJournalTest {
    @TempDir Path world;
    static PickupJournal.Snapshot next(PickupJournal.Snapshot s,List<Entry> entries){return new PickupJournal.Snapshot(s.world(),s.epoch(),s.revision()+1,entries);}
    static Entry entry(){return NativePickupTest.completed(PickupFixtures.intent(UUID.randomUUID()),0);}
    @Test void preparedResultAndPositiveSavedVerificationReopenExactly() throws Exception {
        var e=entry();PickupJournal.Snapshot expected;
        try(var j=PickupJournal.open(world,e.intent().key().world())) {
            var start=j.snapshot();j.replace(start,next(start,List.of(new Entry(e.intent(),null))));var prepared=j.snapshot();j.replace(prepared,next(prepared,List.of(e)));
            var verified=new Verification(Reconciliation.SAVED_COMPLETE,NativePickupTest.saved(e),UUID.randomUUID(),100);
            var complete=j.snapshot();expected=j.replace(complete,next(complete,List.of(new Entry(e.intent(),e.result(),verified))));
        }
        try(var j=PickupJournal.open(world,e.intent().key().world())){assertFalse(j.readOnly());assertEquals(expected,j.snapshot());}
    }
    @Test void corruptCurrentCannotAuthorizeFallbackWritesAndItsBytesStayExact() throws Exception {
        var e=entry();try(var j=PickupJournal.open(world,e.intent().key().world())){var s=j.snapshot();j.replace(s,next(s,List.of(e)));}
        Path path=world.resolve(PickupJournal.PATH).resolve("state.jsonl");byte[] corrupt=Files.readAllBytes(path);corrupt[corrupt.length-3]^=1;Files.write(path,corrupt);
        try(var j=PickupJournal.open(world,e.intent().key().world())){assertTrue(j.readOnly());var s=j.snapshot();assertThrows(IOException.class,()->j.replace(s,next(s,s.entries())));}
        assertArrayEquals(corrupt,Files.readAllBytes(path));
    }
    @Test void actualStagedWriteFaultRetainsRequiredPendingBytesAndFencesReopen() throws Exception {
        var e=entry();try(var j=PickupJournal.open(world,e.intent().key().world(),p->{if(p==PickupJournal.Point.AFTER_STAGE)throw new IOException("after forced stage");})) {
            var s=j.snapshot();assertThrows(IOException.class,()->j.replace(s,next(s,List.of(e))));assertTrue(j.readOnly());
        }
        Path path=world.resolve(PickupJournal.PATH).resolve("state.next.jsonl");byte[] pending=Files.readAllBytes(path);
        try(var j=PickupJournal.open(world,e.intent().key().world())){assertTrue(j.readOnly());}
        assertArrayEquals(pending,Files.readAllBytes(path));
    }
    @Test void deletingRequiredMarkerNeverCreatesAFreshWriterOverOriginalEvidence() throws Exception {
        var e=entry();try(var j=PickupJournal.open(world,e.intent().key().world())){var s=j.snapshot();j.replace(s,next(s,List.of(e)));}
        Path path=world.resolve(PickupJournal.PATH).resolve("state.jsonl");byte[] original=Files.readAllBytes(path);Files.delete(world.resolve(PickupJournal.REQUIRED));
        assertTrue(PickupJournal.exists(world));try(var j=PickupJournal.open(world,e.intent().key().world())){assertTrue(j.readOnly());assertEquals(e,j.snapshot().entries().getFirst());}
        assertArrayEquals(original,Files.readAllBytes(path));assertFalse(Files.exists(world.resolve(PickupJournal.REQUIRED)));
    }
    @Test void sixtyFourPreparedRowsDoNotEvictOriginalsWhenTheNextRowIsRefused() throws Exception {
        UUID id=UUID.randomUUID();var entries=new ArrayList<Entry>();for(int n=0;n<64;n++)entries.add(new Entry(PickupFixtures.intent(id),null));
        try(var j=PickupJournal.open(world,id)) {
            var s=j.snapshot();j.replace(s,next(s,entries));assertEquals(64,j.snapshot().entries().size());
            byte[] original=Files.readAllBytes(world.resolve(PickupJournal.PATH).resolve("state.jsonl"));entries.add(new Entry(PickupFixtures.intent(id),null));
            assertThrows(IllegalArgumentException.class,()->next(j.snapshot(),entries));assertArrayEquals(original,Files.readAllBytes(world.resolve(PickupJournal.PATH).resolve("state.jsonl")));
        }
    }
    @Test void foreignWorldPreservesOriginalEpochAndRawBytes() throws Exception {
        var e=entry();try(var j=PickupJournal.open(world,e.intent().key().world())){var s=j.snapshot();j.replace(s,next(s,List.of(e)));}
        Path path=world.resolve(PickupJournal.PATH).resolve("state.jsonl");byte[] original=Files.readAllBytes(path);
        try(var j=PickupJournal.open(world,UUID.randomUUID())){assertTrue(j.readOnly());}
        assertArrayEquals(original,Files.readAllBytes(path));
    }
    @Test void independentPickupFilesNeverChangeExistingHarvestBytesOrSchemas() throws Exception {
        var e=entry();Path original=world.resolve(HarvestJournal.PATH).resolve("state.jsonl");byte[] before;
        try(var h=HarvestJournal.open(world,e.intent().key().world())){var s=h.snapshot();h.replace(s,new HarvestJournal.Snapshot(s.world(),s.epoch(),s.revision()+1,List.of(e.intent().harvest())));before=Files.readAllBytes(original);}
        try(var p=PickupJournal.open(world,e.intent().key().world())){var s=p.snapshot();p.replace(s,next(s,List.of(e)));}
        assertArrayEquals(before,Files.readAllBytes(original));try(var h=HarvestJournal.open(world,e.intent().key().world())){assertFalse(h.readOnly());assertEquals(e.intent().harvest(),h.snapshot().entries().getFirst());}
    }
    @Test void everyForcedPublicationFaultLeavesNoAcknowledgedInProcessState() throws Exception {
        for(var fault:PickupJournal.Point.values()) {
            var e=entry();Path root=world.resolve(fault.name());PickupJournal.Snapshot original;
            try(var j=PickupJournal.open(root,e.intent().key().world(),p->{if(p==fault)throw new IOException("forced "+p);})) {
                original=j.snapshot();assertThrows(IOException.class,()->j.replace(original,next(original,List.of(e))));assertSame(original,j.snapshot());assertTrue(j.readOnly());
            }
            try(var j=PickupJournal.open(root,e.intent().key().world())) {
                assertTrue(j.snapshot().entries().isEmpty()||j.snapshot().entries().equals(List.of(e)));
                // A persisted replacement after a lost ACK is historical data, never an execution capability.
                assertTrue(Files.isRegularFile(root.resolve(PickupJournal.PATH).resolve("state.jsonl")));
            }
        }
    }
    @Test void changedOrRemovedOriginalRowsAndIdentityCollisionsCannotReplaceTheJournal() throws Exception {
        var e=entry();try(var j=PickupJournal.open(world,e.intent().key().world())) {
            var s=j.snapshot();j.replace(s,next(s,List.of(e)));var current=j.snapshot();byte[] original=Files.readAllBytes(world.resolve(PickupJournal.PATH).resolve("state.jsonl"));
            assertThrows(IOException.class,()->j.replace(current,next(current,List.of())));
            assertThrows(IllegalArgumentException.class,()->next(current,List.of(e,e)));
            assertArrayEquals(original,Files.readAllBytes(world.resolve(PickupJournal.PATH).resolve("state.jsonl")));
        }
    }
}
