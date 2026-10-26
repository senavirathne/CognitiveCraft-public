package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.runtime.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.aivillages.core.kernel.ResourceLeases.*;
import static org.junit.jupiter.api.Assertions.*;

/** Storage/domain contracts only. The separate connected-world gate supplies native effects. */
final class LeaseArchiveTest {
    @TempDir Path world;
    static final class Background implements Executor {
        final ArrayDeque<Runnable> work=new ArrayDeque<>();
        public void execute(Runnable task){work.add(task);}
        void run()throws InterruptedException {assertEquals(1,work.size());Thread.ofPlatform().start(work.remove()).join();}
    }
    final ResourceLeaseServiceTest.Fixture f=new ResourceLeaseServiceTest.Fixture();
    final Background background=new Background();
    long references=1,jobsRevision=1;
    Set<UUID> settled=Set.of(f.alice.job(),f.bob.job());
    LeaseArchive.Closure closure(){return new LeaseArchive.Closure(jobsRevision,f.service.snapshot().revision(),references,settled,true);}
    LeaseArchive archive(ArchiveWriter writer,PersistencePolicy policy)throws Exception {
        var archive=new LeaseArchive(f.service,writer,new ArchiveService(writer,background),policy);
        archive.step(closure());background.run();archive.step(closure());assertTrue(f.service.ready());return archive;
    }
    Ref cycle(Owner owner,Contracts.TrustedContext caller) {
        var acquired=f.acquire(owner,caller,List.of(new Demand(f.facility(1),1)),100);
        Ref ref=f.one(acquired);assertEquals(Code.PENDING,f.service.release(acquired.leases(),caller).code());f.ack();return ref;
    }
    List<Ref> retired(int count) {
        var result=new ArrayList<Ref>();for(int n=0;n<count;n++)result.add(cycle(f.alice,f.a));return result;
    }
    void compact(LeaseArchive archive)throws Exception {archive.step(closure());background.run();archive.step(closure());f.ack();archive.step(closure());}
    @Test void archivedCopyIsAcknowledgedBeforeHotPublicationAndExactLookupSurvivesRestart()throws Exception {
        List<Ref> old=retired(96);Ref first=old.getFirst();Lease original=f.service.historical(first,f.alice,f.a).orElseThrow();
        try(var writer=ArchiveWriter.open(world,f.world,PersistencePolicy.defaults())) {
            var archive=archive(writer,PersistencePolicy.defaults());archive.step(closure());background.run();
            assertEquals(96,f.service.snapshot().leases().size());
            assertTrue(writer.lookup(new ArchiveFormat.Key(ArchiveFormat.Owner.LEASES,ArchiveFormat.Kind.HISTORY,first.id())).isPresent());
            archive.step(closure());assertFalse(f.service.ready());assertEquals(96,f.service.snapshot().leases().size());f.ack();archive.step(closure());
            assertEquals(80,f.service.snapshot().leases().size());assertEquals(original,f.service.historical(first,f.alice,f.a).orElseThrow());
            assertEquals(16,archive.status().archived());assertEquals(16,archive.status().evicted());
        }
        f.service=f.make(f.disk,false);f.ack();
        try(var writer=ArchiveWriter.open(world,f.world,PersistencePolicy.defaults())) {
            archive(writer,PersistencePolicy.defaults());assertEquals(original,f.service.historical(first,f.alice,f.a).orElseThrow());
            assertEquals(Code.STALE,f.service.validate(first,f.a).code());
            assertEquals(Code.STALE,f.service.release(List.of(first),f.a).code());
        }
    }
    @Test void unresolvedOwnerStaysHotWhileUnrelatedHistoryCrossesOriginalWall()throws Exception {
        Ref pinned=cycle(f.bob,f.b);List<Ref> old=retired(95);settled=Set.of(f.alice.job());
        try(var writer=ArchiveWriter.open(world,f.world,PersistencePolicy.defaults())) {
            var archive=archive(writer,PersistencePolicy.defaults());compact(archive);
            for(int n=0;n<60;n++) {
                cycle(f.alice,f.a);
                if(f.service.snapshot().leases().size()>=96)compact(archive);
            }
            assertTrue(f.service.snapshot().leases().stream().anyMatch(l->l.ref().equals(pinned)));
            assertFalse(writer.lookup(new ArchiveFormat.Key(ArchiveFormat.Owner.LEASES,ArchiveFormat.Kind.HISTORY,pinned.id())).isPresent());
            assertTrue(archive.status().evicted()>32);assertTrue(archive.status().hot()<=128);
            assertTrue(f.service.historical(old.getFirst(),f.alice,f.a).isPresent());
            assertEquals(Settings.production().active(),f.settings.active());assertEquals(128,f.settings.retained());
        }
    }
    @Test void changedReferenceClosureAfterArchiveAcknowledgementCancelsHotEviction()throws Exception {
        List<Ref> old=retired(96);
        try(var writer=ArchiveWriter.open(world,f.world,PersistencePolicy.defaults())) {
            var archive=archive(writer,PersistencePolicy.defaults());archive.step(closure());background.run();references++;
            archive.step(closure());assertEquals(96,f.service.snapshot().leases().size());assertEquals(0,archive.status().evicted());
            compact(archive);assertTrue(f.service.snapshot().leases().size()<96);
            assertTrue(f.service.historical(old.getFirst(),f.alice,f.a).isPresent());
        }
    }
    @Test void changedOwnerRevisionCancelsEvictionEvenWhenSelectedRowsAreUnchanged()throws Exception {
        retired(96);
        try(var writer=ArchiveWriter.open(world,f.world,PersistencePolicy.defaults())) {
            var archive=archive(writer,PersistencePolicy.defaults());archive.step(closure());background.run();cycle(f.bob,f.b);
            archive.step(closure());assertEquals(97,f.service.snapshot().leases().size());assertEquals(0,archive.status().evicted());
            jobsRevision++;compact(archive);assertTrue(f.service.snapshot().leases().size()<97);
        }
    }
    @Test void staleRenewReleaseAndForeignHistoryCannotChangeReplacementOwner()throws Exception {
        Ref old=retired(96).getFirst();
        try(var writer=ArchiveWriter.open(world,f.world,PersistencePolicy.defaults())) {
            var archive=archive(writer,PersistencePolicy.defaults());compact(archive);
            Ref replacement=f.one(f.bob(f.facility(1),1));Snapshot before=f.service.snapshot();
            assertEquals(Code.STALE,f.service.renew(List.of(old),100,f.a).code());
            assertEquals(Code.RELEASED,f.service.release(List.of(old),f.a).code());assertEquals(before,f.service.snapshot());
            assertTrue(f.service.validate(replacement,f.b).usable());
            assertThrows(SecurityException.class,()->f.service.historical(old,f.alice,f.b));
            assertThrows(SecurityException.class,()->f.service.historical(old,f.bob,f.a));
            assertTrue(f.service.historical(new Ref(old.id(),old.epoch(),old.generation()+1),f.alice,f.a).isEmpty());
        }
    }
    @Test void archiveQuotaAppliesBackpressureWithoutDeletingHistoryOrRaisingLiveLimits()throws Exception {
        List<Ref> old=retired(96);var d=PersistencePolicy.defaults();
        var small=new PersistencePolicy(d.jobRows(),d.leaseRows(),d.jobBytes(),d.leaseBytes(),d.archiveBytes(),32,
                d.scanRows(),d.scanBytes(),d.deleteFiles(),d.eligibleAgeMillis(),d.pressurePercent(),d.reservedBytes(),d.reservations());
        try(var writer=ArchiveWriter.open(world,f.world,small)) {
            var archive=archive(writer,small);compact(archive);assertEquals("BACKPRESSURE",archive.status().pressure());
            assertEquals(96,f.service.snapshot().leases().size());assertEquals(0,archive.status().archived());
            for(int n=0;n<32;n++)cycle(f.alice,f.a);
            assertEquals(Code.REJECTED,f.alice(f.facility(1),1).code());assertEquals(128,f.service.snapshot().leases().size());
            assertTrue(f.service.historical(old.getFirst(),f.alice,f.a).isPresent());
        }
    }
    @Test void hotWriteFailureRetainsBothExactCopiesAndFencesFurtherAuthority()throws Exception {
        Ref old=retired(96).getFirst();
        try(var writer=ArchiveWriter.open(world,f.world,PersistencePolicy.defaults())) {
            var archive=archive(writer,PersistencePolicy.defaults());archive.step(closure());background.run();f.holding=true;
            archive.step(closure());assertEquals(96,f.service.snapshot().leases().size());f.write.completeExceptionally(new IOException("hot write fault"));
            f.service.tick();assertFalse(f.service.ready());assertEquals(96,f.service.snapshot().leases().size());
            assertTrue(writer.lookup(new ArchiveFormat.Key(ArchiveFormat.Owner.LEASES,ArchiveFormat.Kind.HISTORY,old.id())).isPresent());
        }
    }
    @Test void unknownDomainPayloadFencesRatherThanReportingAbsentOldLease()throws Exception {
        try(var writer=ArchiveWriter.open(world,f.world,PersistencePolicy.defaults())) {
            var guard=new ArchiveFormat.Guard(f.service.snapshot().revision(),references);
            writer.append(writer.snapshot(),ArchiveFormat.Owner.LEASES,guard,List.of(new ArchiveFormat.Row(ArchiveFormat.Kind.HISTORY,
                    UUID.randomUUID(),"{\"schema\":99}".getBytes(StandardCharsets.UTF_8))),owner->guard);
            var archive=new LeaseArchive(f.service,writer,new ArchiveService(writer,background),PersistencePolicy.defaults());
            archive.step(closure());background.run();archive.step(closure());assertTrue(archive.readOnly());assertFalse(f.service.ready());
            assertThrows(IllegalStateException.class,()->f.service.historical(new Ref(UUID.randomUUID(),UUID.randomUUID(),1),f.alice,f.a));
        }
    }
    @Test void missingArchivedSegmentPreservesHotOriginalsAndRefusesNewPermission()throws Exception {
        retired(96);Path segment;
        try(var writer=ArchiveWriter.open(world,f.world,PersistencePolicy.defaults())) {
            var archive=archive(writer,PersistencePolicy.defaults());compact(archive);
            segment=world.resolve(ArchiveHistory.RELATIVE_PATH).resolve(writer.snapshot().segments().getFirst().digest()+".seg");
        }
        Files.move(segment,world.resolve("retained-missing-segment.seg"));f.service=f.make(f.disk,false);f.ack();
        try(var writer=ArchiveWriter.open(world,f.world,PersistencePolicy.defaults())) {
            var archive=new LeaseArchive(f.service,writer,new ArchiveService(writer,background),PersistencePolicy.defaults());archive.step(closure());
            assertTrue(archive.readOnly());assertFalse(f.service.ready());assertEquals(80,f.service.snapshot().leases().size());
            assertEquals(Code.REJECTED,f.alice(f.facility(1),1).code());assertTrue(Files.exists(world.resolve("retained-missing-segment.seg")));
        }
    }
    @Test void hostBoundaryObservationsBracketAcknowledgedHotEvictionWithoutGrantingIt()throws Exception {
        retired(96);var events=new ArrayList<LeaseArchive.Point>();
        try(var writer=ArchiveWriter.open(world,f.world,PersistencePolicy.defaults())) {
            var archive=archive(writer,PersistencePolicy.defaults());
            archive.observe(point->{
                events.add(point);assertEquals(point==LeaseArchive.Point.BEFORE_HOT_PUBLICATION?96:80,f.service.snapshot().leases().size());
            });
            compact(archive);assertEquals(List.of(LeaseArchive.Point.BEFORE_HOT_PUBLICATION,LeaseArchive.Point.AFTER_HOT_ACKNOWLEDGEMENT),events);
            assertEquals(16,archive.status().evicted());
        }
    }
}
