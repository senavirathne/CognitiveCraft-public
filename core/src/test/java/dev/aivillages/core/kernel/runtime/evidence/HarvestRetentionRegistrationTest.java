package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dev.aivillages.core.kernel.WorldRetentionManager.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real journal accounting and the observed seventeen-owner startup failure; no gameplay claim. */
final class HarvestRetentionRegistrationTest {
    @TempDir Path world;
    static Owner empty(String id,long ceiling) {
        return new Owner() {
            public String id(){return id;}public long revision(){return 0;}
            public Optional<Amount> admissionCeiling(){return ceiling==0?Optional.empty():Optional.of(new Amount(ceiling,0));}
            public Cursor open(){return new Cursor(){public long revision(){return 0;}public Page next(int records,int bytes){return new Page(List.of(new Entry(new Key(id,"empty"),Category.EVIDENCE,new Amount(0,0),List.of(),true,false,Set.of(),"")),true,true,1,0);}};}
            public OwnerResult collect(Entry entry,long revision,java.util.function.BooleanSupplier cancel){return OwnerResult.of(OwnerStatus.PROTECTED);}
        };
    }
    @Test void legacySixteenOwnerCompositionReproducesTheRealHarvestRegistrationFailure() throws Exception {
        try(var journal=HarvestJournal.open(world,UUID.randomUUID())) {
            var manager=new WorldRetentionManager(Policy.defaults(),new RetentionRoots(),caller->false);
            for(int n=0;n<16;n++)manager.register(empty("existing"+n,0));
            assertThrows(IllegalArgumentException.class,()->manager.register(journal.retentionOwner()));
            assertFalse(journal.readOnly());assertTrue(journal.snapshot().entries().isEmpty());
        }
    }
    @Test void explicitHarvestSlotKeepsEveryExistingOwnerAndBoundsTheFullInventoryScan() throws Exception {
        var legacy=Policy.defaults();var extended=legacy.withHarvestEvidence();
        assertEquals(16,legacy.owners());assertEquals(17,extended.owners());assertEquals(legacy.categories(),extended.categories());assertEquals(legacy.total(),extended.total());
        assertEquals(legacy.nodes(),extended.nodes());assertEquals(legacy.links(),extended.links());assertEquals(legacy.sliceRecords(),extended.sliceRecords());assertEquals(legacy.sliceBytes(),extended.sliceBytes());
        assertThrows(IllegalStateException.class,extended::withHarvestEvidence);
        try(var journal=HarvestJournal.open(world,UUID.randomUUID())) {
            var manager=new WorldRetentionManager(extended,new RetentionRoots(),caller->false);for(int n=0;n<16;n++)manager.register(empty("existing"+n,0));
            manager.register(journal.retentionOwner());assertThrows(IllegalArgumentException.class,()->manager.register(empty("overflow",0)));
            try(var scan=manager.begin()) {
                for(int n=0;n<64 && scan.state()!=State.READY && scan.state()!=State.INVALID;n++) {
                    var work=scan.step(()->false);assertTrue(work.inspected()<=legacy.sliceRecords());assertTrue(work.readBytes()<=legacy.sliceBytes());
                }
                assertEquals(State.READY,scan.state());assertEquals(0,scan.collect(()->false).collected());
            }
            assertFalse(journal.readOnly());
        }
    }
    @Test void independentJournalGrowthIncludesTheCurrentReplacementInTheSameCategoryQuota() {
        var base=Policy.defaults();var quotas=new EnumMap<Category,Quota>(Category.class);quotas.putAll(base.categories());quotas.put(Category.EVIDENCE,new Quota(100,100));
        var manager=new WorldRetentionManager(new Policy(quotas,base.total(),16,base.nodes(),base.links(),base.sliceRecords(),base.sliceBytes(),base.diagnostics()),new RetentionRoots(),caller->false);
        manager.register(empty("writing",0));manager.register(empty("other",60));
        assertEquals(Capacity.AVAILABLE,manager.assessReplacement("writing",Category.EVIDENCE,new Amount(40,0)).status());
        assertEquals(Capacity.STORAGE_LIMIT_REACHED,manager.assessReplacement("writing",Category.EVIDENCE,new Amount(41,0)).status());
    }
    @Test void multipleIndependentJournalCeilingsAccumulateWithoutIncreasingTheCategoryLimit() {
        var base=Policy.defaults();var quotas=new EnumMap<Category,Quota>(Category.class);quotas.putAll(base.categories());quotas.put(Category.EVIDENCE,new Quota(100,100));
        var manager=new WorldRetentionManager(new Policy(quotas,base.total(),16,base.nodes(),base.links(),base.sliceRecords(),base.sliceBytes(),base.diagnostics()),new RetentionRoots(),caller->false);
        manager.register(empty("writing",0));manager.register(empty("first",40));manager.register(empty("second",40));
        assertEquals(Capacity.AVAILABLE,manager.assessReplacement("writing",Category.EVIDENCE,new Amount(20,0)).status());
        assertEquals(Capacity.STORAGE_LIMIT_REACHED,manager.assessReplacement("writing",Category.EVIDENCE,new Amount(21,0)).status());
    }
}
