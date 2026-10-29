package dev.aivillages.core.kernel.runtime.evidence;
import dev.aivillages.core.kernel.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dev.aivillages.core.kernel.WorldRetentionManager.*;
import static org.junit.jupiter.api.Assertions.*;

final class HarvestPickupRetentionTest {
    @TempDir Path world;
    static WorldRetentionManager manager(Owner family){var m=new WorldRetentionManager(Policy.defaults().withHarvestEvidence(),new RetentionRoots(),caller->false);m.register(family);return m;}
    static HarvestJournal.Snapshot hnext(HarvestJournal h,List<NativeHarvest.Entry> entries){var s=h.snapshot();return new HarvestJournal.Snapshot(s.world(),s.epoch(),s.revision()+1,entries);}
    static PickupJournal.Snapshot pnext(PickupJournal p,List<NativePickup.Entry> entries){var s=p.snapshot();return new PickupJournal.Snapshot(s.world(),s.epoch(),s.revision()+1,entries);}
    @Test void oneExistingOwnerSlotAccountsForEveryFileAndBothRecordEnvelopesInBoundedSlices() throws Exception {
        UUID id=UUID.randomUUID();var intent=PickupFixtures.intent(id);
        try(var h=HarvestJournal.open(world,id);var p=PickupJournal.open(world,id)) {
            var family=HarvestPickupRetention.bind(h,p);manager(family);
            h.replace(h.snapshot(),hnext(h,List.of(intent.harvest())));p.replace(p.snapshot(),pnext(p,List.of(new NativePickup.Entry(intent,null))));
            var keys=new HashSet<Key>();long bytes=0,records=0;int pages=0;
            try(var cursor=family.open()) {
                for(;;){var page=cursor.next(1,65536);assertTrue(page.valid());assertTrue(page.inspected()<=1);pages++;
                    for(var e:page.entries()){assertEquals("harvests",e.key().owner());assertTrue(keys.add(e.key()));assertTrue(e.protectedRecord());bytes+=e.usage().bytes();records+=e.usage().records();}
                    if(page.complete())break;assertTrue(pages<30);}
            }
            assertEquals(2,records);assertTrue(keys.contains(new Key("harvests","harvest:records")));assertTrue(keys.contains(new Key("harvests","pickup:records")));
            long physical;try(var paths=Files.walk(world)){physical=paths.filter(Files::isRegularFile).mapToLong(path->{try{return Files.size(path);}catch(IOException e){throw new java.io.UncheckedIOException(e);}}).sum();}
            assertEquals(physical,bytes);assertTrue(family.admissionCeiling().orElseThrow().bytes()>=bytes);
        }
    }
    @Test void originalSeventeenOwnerLimitStillRejectsAnEighteenthOwner() throws Exception {
        UUID id=UUID.randomUUID();try(var h=HarvestJournal.open(world,id);var p=PickupJournal.open(world,id)) {
            var m=manager(HarvestPickupRetention.bind(h,p));
            for(int n=0;n<16;n++)m.register(empty("owner"+n));
            assertThrows(IllegalArgumentException.class,()->m.register(empty("eighteenth")));
            assertEquals(17,Policy.defaults().withHarvestEvidence().owners());assertEquals(16,Policy.defaults().owners());
        }
    }
    static Owner empty(String id){return new Owner(){
        public String id(){return id;}public long revision(){return 0;}
        public Cursor open(){return new Cursor(){public long revision(){return 0;}public Page next(int r,int b){return new Page(List.of(),true,true,0,0);}};}
        public OwnerResult collect(Entry e,long revision,java.util.function.BooleanSupplier cancel){return OwnerResult.of(OwnerStatus.PROTECTED);}
    };}
    @Test void combinedSixtyFourRowReservationRefusesBothKindsOfAdditionalRowWithoutEviction() throws Exception {
        UUID id=UUID.randomUUID();try(var h=HarvestJournal.open(world,id);var p=PickupJournal.open(world,id)) {
            var family=HarvestPickupRetention.bind(h,p);manager(family);var hs=new ArrayList<NativeHarvest.Entry>();var ps=new ArrayList<NativePickup.Entry>();
            for(int n=0;n<32;n++){var i=PickupFixtures.intent(id);hs.add(i.harvest());ps.add(new NativePickup.Entry(i,null));}
            h.replace(h.snapshot(),hnext(h,hs));p.replace(p.snapshot(),pnext(p,ps));assertEquals(64,family.admissionCeiling().orElseThrow().records());
            var hb=Files.readAllBytes(world.resolve(HarvestJournal.PATH).resolve("state.jsonl"));var pb=Files.readAllBytes(world.resolve(PickupJournal.PATH).resolve("state.jsonl"));
            var hi=new ArrayList<>(hs);hi.add(PickupFixtures.intent(id).harvest());assertThrows(IOException.class,()->h.replace(h.snapshot(),hnext(h,hi)));
            var pi=new ArrayList<>(ps);pi.add(new NativePickup.Entry(PickupFixtures.intent(id),null));assertThrows(IOException.class,()->p.replace(p.snapshot(),pnext(p,pi)));
            assertArrayEquals(hb,Files.readAllBytes(world.resolve(HarvestJournal.PATH).resolve("state.jsonl")));assertArrayEquals(pb,Files.readAllBytes(world.resolve(PickupJournal.PATH).resolve("state.jsonl")));
            assertFalse(h.readOnly());assertFalse(p.readOnly());
        }
    }
    @Test void unchangedWorldCategoryQuotaReservesBothCompleteResultsBeforePublication() throws Exception {
        UUID id=UUID.randomUUID();try(var h=HarvestJournal.open(world,id);var p=PickupJournal.open(world,id)) {
            var family=HarvestPickupRetention.bind(h,p);var original=Policy.defaults().withHarvestEvidence();var categories=new EnumMap<Category,Quota>(Category.class);categories.putAll(original.categories());
            categories.put(Category.EVIDENCE,new Quota(120_000,4096));
            var m=new WorldRetentionManager(new Policy(categories,original.total(),original.owners(),original.nodes(),original.links(),original.sliceRecords(),original.sliceBytes(),original.diagnostics()),new RetentionRoots(),caller->false);m.register(family);
            var intent=PickupFixtures.intent(id);h.replace(h.snapshot(),hnext(h,List.of(intent.harvest())));
            byte[] before=Files.readAllBytes(world.resolve(PickupJournal.PATH).resolve("state.jsonl"));
            assertThrows(IOException.class,()->p.replace(p.snapshot(),pnext(p,List.of(new NativePickup.Entry(intent,null)))));
            assertArrayEquals(before,Files.readAllBytes(world.resolve(PickupJournal.PATH).resolve("state.jsonl")));assertEquals(0,p.snapshot().entries().size());
        }
    }
    @Test void bothJournalsRejectForeignStorageThreadsAndUnregisteredFamilyAdmissions() throws Exception {
        UUID id=UUID.randomUUID();try(var h=HarvestJournal.open(world,id);var p=PickupJournal.open(world,id)) {
            var family=HarvestPickupRetention.bind(h,p);var intent=PickupFixtures.intent(id);
            assertThrows(IOException.class,()->h.replace(h.snapshot(),hnext(h,List.of(intent.harvest()))));manager(family);
            var errors=new java.util.concurrent.atomic.AtomicInteger();var t=new Thread(()->{try{h.replace(h.snapshot(),hnext(h,List.of(intent.harvest())));}catch(IOException expected){errors.incrementAndGet();}
                try{p.replace(p.snapshot(),pnext(p,List.of(new NativePickup.Entry(intent,null))));}catch(IOException expected){errors.incrementAndGet();}});
            t.start();t.join();assertEquals(2,errors.get());assertTrue(h.snapshot().entries().isEmpty());assertTrue(p.snapshot().entries().isEmpty());
        }
    }
    @Test void aFencedChildPreventsPartialInventoriesAndPreservesItsPendingBytes() throws Exception {
        UUID id=UUID.randomUUID();var intent=PickupFixtures.intent(id);
        try(var p=PickupJournal.open(world,id,q->{if(q==PickupJournal.Point.AFTER_STAGE)throw new IOException("forced stage");})) {
            var s=p.snapshot();assertThrows(IOException.class,()->p.replace(s,pnext(p,List.of(new NativePickup.Entry(intent,null)))));
        }
        byte[] pending=Files.readAllBytes(world.resolve(PickupJournal.PATH).resolve("state.next.jsonl"));
        try(var h=HarvestJournal.open(world,id);var p=PickupJournal.open(world,id)) {
            assertTrue(p.readOnly());var family=HarvestPickupRetention.bind(h,p);var m=manager(family);
            assertThrows(IOException.class,family::open);
            assertEquals(Capacity.STORAGE_UNAVAILABLE,m.assessReplacement("harvests",Category.EVIDENCE,new Amount(1000,1)).status());
            assertThrows(IOException.class,()->h.replace(h.snapshot(),hnext(h,List.of(intent.harvest()))));
        }
        assertArrayEquals(pending,Files.readAllBytes(world.resolve(PickupJournal.PATH).resolve("state.next.jsonl")));
    }
    @Test void changedChildRevisionInvalidatesAnAlreadyCapturedFamilyCursor() throws Exception {
        UUID id=UUID.randomUUID();try(var h=HarvestJournal.open(world,id);var p=PickupJournal.open(world,id)) {
            var family=HarvestPickupRetention.bind(h,p);manager(family);
            try(var cursor=family.open()){var intent=PickupFixtures.intent(id);h.replace(h.snapshot(),hnext(h,List.of(intent.harvest())));assertFalse(cursor.next(1,65536).valid());}
        }
    }
}
