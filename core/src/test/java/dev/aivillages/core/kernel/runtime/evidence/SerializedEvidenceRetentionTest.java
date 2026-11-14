package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dev.aivillages.core.kernel.WorldRetentionManager.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real journal/quota contracts with synthetic rows; no native effects or saved gameplay evidence. */
final class SerializedEvidenceRetentionTest {
    @TempDir Path world;
    static long reserved(int rows){return 3L*(1024L+rows*(NativeHarvest.MAX_RECORD_BYTES+1L))+4096;}
    static Owner independent(String id) {
        return new Owner() {
            public String id(){return id;}public long revision(){return 0;}
            public Optional<Amount> admissionCeiling(){return Optional.of(new Amount(3L*1_048_576,0));}
            public Cursor open(){return new Cursor(){
                public long revision(){return 0;}
                public Page next(int records,int bytes){return new Page(List.of(new Entry(new Key(id,"files"),Category.EVIDENCE,new Amount(0,0),List.of(),true,false,Set.of(),"")),true,true,1,0);}
            };}
            public OwnerResult collect(Entry entry,long revision,java.util.function.BooleanSupplier cancel){return OwnerResult.of(OwnerStatus.PROTECTED);}
        };
    }
    static WorldRetentionManager manager(Owner deposits,Owner harvests) {
        var manager=new WorldRetentionManager(Policy.defaults().withHarvestEvidence(),new RetentionRoots(),caller->false);
        manager.register(independent("accomplishments"));manager.register(independent("knowledge-access"));
        manager.register(deposits);manager.register(harvests);return manager;
    }
    @Test void independentMaximumCeilingsReproduceTheNativePreMutationCapacityRefusal() throws Exception {
        try(var deposits=DepositJournal.open(world,NativeDepositTest.WORLD);var harvests=HarvestJournal.open(world,NativeDepositTest.WORLD)) {
            var manager=manager(deposits.retentionOwner(),harvests.retentionOwner());
            var decision=manager.assessReplacement("harvests",Category.EVIDENCE,new Amount(reserved(1),1));
            assertEquals(Capacity.STORAGE_LIMIT_REACHED,decision.status());
            assertEquals(16_777_216,decision.limit().bytes());assertTrue(decision.projected().bytes()>decision.limit().bytes());
            assertTrue(harvests.snapshot().entries().isEmpty());assertFalse(harvests.readOnly());
        }
    }
    @Test void serializedAdmissionKeepsEveryPreparedResultReservedAfterFurtherRowsAreRefused() throws Exception {
        try(var deposits=DepositJournal.open(world,NativeDepositTest.WORLD);var harvests=HarvestJournal.open(world,NativeDepositTest.WORLD)) {
            var manager=manager(deposits.retentionOwnerForCurrentWriter(),harvests.retentionOwnerForCurrentWriter());
            deposits.capacity(value->manager.assessReplacement("deposits",Category.EVIDENCE,value).status()==Capacity.AVAILABLE);
            harvests.capacity(value->manager.assessReplacement("harvests",Category.EVIDENCE,value).status()==Capacity.AVAILABLE);
            var depositsBefore=deposits.snapshot();var depositRows=new ArrayList<NativeDeposit.Entry>();
            for(int n=0;n<NativeDeposit.MAX_ENTRIES;n++)depositRows.add(new NativeDeposit.Entry(NativeDepositTest.intent(),null));
            deposits.replace(depositsBefore,new DepositJournal.Snapshot(depositsBefore.world(),depositsBefore.epoch(),depositsBefore.revision()+1,depositRows));
            var first=new NativeHarvest.Entry(NativeHarvestTest.intent(),null);var snapshot=harvests.snapshot();
            harvests.replace(snapshot,new HarvestJournal.Snapshot(snapshot.world(),snapshot.epoch(),snapshot.revision()+1,List.of(first)));
            boolean refused=false;
            for(int n=1;n<NativeHarvest.MAX_ENTRIES;n++) {
                snapshot=harvests.snapshot();var rows=new ArrayList<>(snapshot.entries());rows.add(new NativeHarvest.Entry(NativeHarvestTest.intent(),null));
                byte[] before=Files.readAllBytes(world.resolve(HarvestJournal.PATH).resolve("state.jsonl"));
                try{harvests.replace(snapshot,new HarvestJournal.Snapshot(snapshot.world(),snapshot.epoch(),snapshot.revision()+1,rows));}
                catch(IOException capacity) {
                    assertEquals("World harvest evidence capacity unavailable",capacity.getMessage());
                    refused=true;assertSame(snapshot,harvests.snapshot());assertArrayEquals(before,Files.readAllBytes(world.resolve(HarvestJournal.PATH).resolve("state.jsonl")));break;
                }
            }
            assertTrue(refused,"Original category cap must refuse additional rows before they can acknowledge");
            snapshot=harvests.snapshot();int count=snapshot.entries().size();var rows=new ArrayList<>(snapshot.entries());
            rows.set(0,NativeHarvestTest.completed(first.intent()));
            harvests.replace(snapshot,new HarvestJournal.Snapshot(snapshot.world(),snapshot.epoch(),snapshot.revision()+1,rows));
            assertEquals(count,harvests.snapshot().entries().size());assertNotNull(harvests.snapshot().entries().getFirst().result());
            assertEquals(NativeDeposit.MAX_ENTRIES,deposits.snapshot().entries().size());assertFalse(harvests.readOnly());
        }
    }
    @Test void boundJournalRejectsAnotherWriterBeforeChangingAnyNativeEvidenceBytes() throws Exception {
        try(var harvests=HarvestJournal.open(world,NativeDepositTest.WORLD)) {
            harvests.retentionOwnerForCurrentWriter();var before=harvests.snapshot();byte[] raw=Files.readAllBytes(world.resolve(HarvestJournal.PATH).resolve("state.jsonl"));
            var next=new HarvestJournal.Snapshot(before.world(),before.epoch(),before.revision()+1,List.of(new NativeHarvest.Entry(NativeHarvestTest.intent(),null)));
            Throwable failure=CompletableFuture.supplyAsync(()->{try{harvests.replace(before,next);return null;}catch(Exception error){return error;}}).join();
            assertInstanceOf(IOException.class,failure);assertTrue(failure.getMessage().contains("bound serialized writer"));
            assertSame(before,harvests.snapshot());assertArrayEquals(raw,Files.readAllBytes(world.resolve(HarvestJournal.PATH).resolve("state.jsonl")));
        }
    }
    @Test void differentSerializedWriterDomainsCannotRegisterReducedReservationsTogether() throws Exception {
        try(var deposits=DepositJournal.open(world,NativeDepositTest.WORLD);var harvests=HarvestJournal.open(world,NativeDepositTest.WORLD)) {
            var manager=new WorldRetentionManager(Policy.defaults().withHarvestEvidence(),new RetentionRoots(),caller->false);
            var first=deposits.retentionOwnerForCurrentWriter();manager.register(first);
            var other=CompletableFuture.supplyAsync(harvests::retentionOwnerForCurrentWriter).join();
            assertNotSame(first.serializedWriter().orElseThrow(),other.serializedWriter().orElseThrow());
            assertThrows(IllegalArgumentException.class,()->manager.register(other));
            assertTrue(harvests.snapshot().entries().isEmpty());assertTrue(deposits.snapshot().entries().isEmpty());
        }
    }
}
