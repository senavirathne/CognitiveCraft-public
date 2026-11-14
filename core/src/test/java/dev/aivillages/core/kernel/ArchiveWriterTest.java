package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.runtime.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.io.IOException;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import static dev.aivillages.core.kernel.runtime.persistence.ArchiveFormat.*;
import static org.junit.jupiter.api.Assertions.*;

final class ArchiveWriterTest {
    @TempDir Path world;
    final UUID worldId=UUID.randomUUID();final PersistencePolicy policy=PersistencePolicy.defaults();
    final Guard guard=new Guard(1,1);final ArchiveWriter.Fences fences=owner->guard;
    Row row(UUID id,String text){return new Row(Kind.HISTORY,id,text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    Slot slot(){UUID attempt=UUID.randomUUID();return new Slot(attempt,Owner.JOBS,UUID.randomUUID(),attempt,1,65_536,65_536,Phase.RESERVED);}
    ArchiveWriter writer()throws IOException{return ArchiveWriter.open(world,worldId,policy);}
    @Test void domainFilteredObserverCannotMistakeLeasePublicationForAJobCut()throws Exception {
        try(var writer=writer()) {
            var points=new ArrayList<ArchiveWriter.Point>();writer.observePublication(Owner.JOBS,points::add);
            assertTrue(writer.append(writer.snapshot(),Owner.LEASES,guard,List.of(row(UUID.randomUUID(),"lease fixture")),fences).acknowledged());
            assertTrue(points.isEmpty());
            assertTrue(writer.append(writer.snapshot(),Owner.JOBS,guard,List.of(row(UUID.randomUUID(),"Job fixture")),fences).acknowledged());
            assertEquals(List.of(ArchiveWriter.Point.BEFORE_SEGMENT,ArchiveWriter.Point.AFTER_SEGMENT_ACK,
                    ArchiveWriter.Point.BEFORE_MANIFEST_STAGE,ArchiveWriter.Point.AFTER_MANIFEST_STAGE,ArchiveWriter.Point.AFTER_BACKUP,
                    ArchiveWriter.Point.AFTER_MANIFEST_REPLACE,ArchiveWriter.Point.AFTER_DIRECTORY_ACK),points);
            assertThrows(IllegalStateException.class,()->writer.observePublication(Owner.LEASES,p->{}));
        }
    }
    @Test void forcedSegmentAndManifestReopenWithExactUuidAndOpaqueProcessAck()throws Exception {
        Row row=row(UUID.randomUUID(),"actual owner bytes");ArchiveWriter.Acknowledgement old;
        try(var writer=writer()) {
            assertThrows(IOException.class,this::writer);
            var result=writer.append(writer.snapshot(),Owner.JOBS,guard,List.of(row),fences);assertTrue(result.acknowledged());old=result.acknowledgement();
            assertTrue(writer.authentic(old,Owner.JOBS,guard));assertFalse(writer.authentic(old,Owner.LEASES,guard));
            assertEquals(row,writer.lookup(new Key(Owner.JOBS,Kind.HISTORY,row.id())).orElseThrow());
            assertEquals(ArchiveWriter.Status.DUPLICATE,writer.append(writer.snapshot(),Owner.JOBS,guard,List.of(row),fences).status());
        }
        try(var reopened=writer()) {assertFalse(reopened.readOnly());assertFalse(reopened.authentic(old,Owner.JOBS,guard));assertEquals(row,reopened.lookup(new Key(Owner.JOBS,Kind.HISTORY,row.id())).orElseThrow());}
    }
    @Test void conflictingReplayAndStaleOwnerNeverOverwriteOriginalHistory()throws Exception {
        UUID id=UUID.randomUUID();try(var writer=writer()) {
            assertTrue(writer.append(writer.snapshot(),Owner.JOBS,guard,List.of(row(id,"original")),fences).acknowledged());var before=writer.snapshot();
            assertThrows(IOException.class,()->writer.append(before,Owner.JOBS,guard,List.of(row(id,"different")),fences));assertEquals(before,writer.snapshot());
            assertEquals(ArchiveWriter.Status.STALE,writer.append(before,Owner.JOBS,new Guard(2,1),List.of(row(UUID.randomUUID(),"new")),fences).status());
            assertEquals(row(id,"original"),writer.lookup(new Key(Owner.JOBS,Kind.HISTORY,id)).orElseThrow());
        }
    }
    @ParameterizedTest @EnumSource(value=ArchiveWriter.Point.class,names={"BEFORE_CLEANUP","AFTER_CLEANUP"},mode=EnumSource.Mode.EXCLUDE)
    void everyPublicationFaultKeepsCompleteResolvableDataOrExplicitFence(ArchiveWriter.Point point)throws Exception {
        Row original=row(UUID.randomUUID(),"old");try(var writer=writer()){assertTrue(writer.append(writer.snapshot(),Owner.JOBS,guard,List.of(original),fences).acknowledged());}
        Row next=row(UUID.randomUUID(),"new");
        try(var writer=ArchiveWriter.open(world,worldId,policy,p->{if(p==point)throw new IOException("fixture fault "+p);},Clock.systemUTC())) {
            var before=writer.snapshot();assertThrows(IOException.class,()->writer.append(before,Owner.JOBS,guard,List.of(next),fences));assertTrue(writer.readOnly());assertEquals(before,writer.snapshot());
        }
        var read=ArchiveHistory.read(world,worldId,policy);
        if(read.state()==ArchiveHistory.State.READY)assertEquals(original,read.lookup(new Key(Owner.JOBS,Kind.HISTORY,original.id())).orElseThrow());
        else assertThrows(IOException.class,()->read.lookup(new Key(Owner.JOBS,Kind.HISTORY,next.id())));
    }
    @Test void changedOwnerOrRetentionFenceAfterSegmentAckCannotAuthorizeHotEviction()throws Exception {
        Guard[] current={guard};
        try(var writer=ArchiveWriter.open(world,worldId,policy,p->{if(p==ArchiveWriter.Point.AFTER_SEGMENT_ACK)current[0]=new Guard(2,2);},Clock.systemUTC())) {
            var initial=writer.snapshot();assertThrows(IOException.class,()->writer.append(initial,Owner.JOBS,guard,List.of(row(UUID.randomUUID(),"unacknowledged")),owner->current[0]));
            assertTrue(writer.readOnly());assertEquals(initial,writer.snapshot());
        }
        assertEquals(ArchiveHistory.State.FENCED,ArchiveHistory.read(world,worldId,policy).state());
    }
    @Test void eightDurableReservationsKeepResultCancellationAndSettlementAfterOrdinaryAdmissionExhausts()throws Exception {
        try(var writer=writer()) {
            var slots=new ArrayList<Slot>();
            for(int n=0;n<8;n++) {var s=slot();slots.add(s);assertTrue(writer.reserve(writer.snapshot(),s,guard,fences).acknowledged());assertTrue(writer.record(writer.snapshot(),s.id(),Kind.INTENT,new byte[]{1},guard,fences).acknowledged());}
            assertEquals(ArchiveWriter.Status.BACKPRESSURE,writer.reserve(writer.snapshot(),slot(),guard,fences).status());
            int admitted=0;for(int n=0;n<128;n++) {var result=writer.append(writer.snapshot(),Owner.JOBS,guard,List.of(new Row(Kind.HISTORY,UUID.randomUUID(),new byte[48_000])),fences);if(result.status()==ArchiveWriter.Status.BACKPRESSURE)break;assertTrue(result.acknowledged());admitted++;}
            assertTrue(admitted>0 && admitted<128);
            for(var slot:slots) {
                assertTrue(writer.record(writer.snapshot(),slot.id(),Kind.CANCELLATION,new byte[]{2},guard,fences).acknowledged());
                assertTrue(writer.record(writer.snapshot(),slot.id(),Kind.RESULT,new byte[32_000],guard,fences).acknowledged());
                assertEquals(Phase.CANCELLING,writer.snapshot().slots().stream().filter(s->s.id().equals(slot.id())).findFirst().orElseThrow().phase());
                assertTrue(writer.record(writer.snapshot(),slot.id(),Kind.SETTLEMENT,new byte[]{3},guard,fences).acknowledged());
            }
            assertTrue(writer.snapshot().slots().stream().allMatch(s->s.phase()==Phase.SETTLED));
        }
        try(var reopened=writer()) {assertFalse(reopened.readOnly());assertTrue(reopened.snapshot().slots().stream().allMatch(s->s.phase()==Phase.SETTLED));}
    }
    @Test void originalReservationSurvivesRestartAndDuplicatePhasesDoNotConsumeCapacityTwice()throws Exception {
        var slot=slot();try(var writer=writer()) {
            assertTrue(writer.reserve(writer.snapshot(),slot,guard,fences).acknowledged());assertTrue(writer.record(writer.snapshot(),slot.id(),Kind.INTENT,new byte[]{1},guard,fences).acknowledged());
        }
        try(var writer=writer()) {
            var before=writer.snapshot();assertEquals(ArchiveWriter.Status.DUPLICATE,writer.reserve(before,slot,guard,fences).status());
            assertEquals(ArchiveWriter.Status.DUPLICATE,writer.record(before,slot.id(),Kind.INTENT,new byte[]{1},guard,fences).status());assertEquals(before,writer.snapshot());
            var changed=new Slot(slot.id(),Owner.JOBS,UUID.randomUUID(),slot.attempt(),slot.generation(),slot.reservedBytes(),slot.reservedBytes(),Phase.RESERVED);
            assertThrows(IOException.class,()->writer.reserve(writer.snapshot(),changed,guard,fences));
            assertEquals(ArchiveWriter.Status.STALE,writer.record(writer.snapshot(),slot.id(),Kind.SETTLEMENT,new byte[]{1},guard,fences).status());
            assertTrue(writer.record(writer.snapshot(),slot.id(),Kind.RESULT,new byte[]{2},guard,fences).acknowledged());
            assertTrue(writer.record(writer.snapshot(),slot.id(),Kind.SETTLEMENT,new byte[]{3},guard,fences).acknowledged());
            assertEquals(ArchiveWriter.Status.ALREADY_SETTLED,writer.reserve(writer.snapshot(),slot,guard,fences).status());
        }
    }
    @Test void migrationReceiptBindsExactSourceAndAcknowledgedTargetIdempotently()throws Exception {
        try(var writer=writer()) {
            var ack=writer.append(writer.snapshot(),Owner.JOBS,guard,List.of(row(UUID.randomUUID(),"original IDs unchanged")),fences).acknowledgement();byte[] original={1,2,3};
            var receipt=new Migration(UUID.randomUUID(),Owner.JOBS,0,sha256(original),ack.revision(),ack.digest());
            assertTrue(writer.migration(writer.snapshot(),receipt,original,guard,fences).acknowledged());var before=writer.snapshot();
            assertEquals(ArchiveWriter.Status.DUPLICATE,writer.migration(before,receipt,original,guard,fences).status());assertEquals(before,writer.snapshot());
            assertThrows(IOException.class,()->writer.migration(writer.snapshot(),receipt,new byte[]{9},guard,fences));
        }
    }
    @Test void unknownCurrentSchemaIsPreservedAndNeverDowngradedByWriterOpen()throws Exception {
        try(var writer=writer()){assertTrue(writer.append(writer.snapshot(),Owner.JOBS,guard,List.of(row(UUID.randomUUID(),"old")),fences).acknowledged());}
        Path current=world.resolve(ArchiveHistory.RELATIVE_PATH).resolve(ArchiveHistory.CURRENT);byte[] bytes=Files.readString(current).replaceFirst("\"schema\":1","\"schema\":99").getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(current,bytes);
        try(var writer=writer()){assertTrue(writer.readOnly());assertEquals(ArchiveWriter.Status.FENCED,writer.append(writer.snapshot(),Owner.JOBS,guard,List.of(row(UUID.randomUUID(),"new")),fences).status());}
        assertArrayEquals(bytes,Files.readAllBytes(current));
    }
    @Test void cleanupRequiresExactScopedRetentionLeaseAndKeepsUuidCopies()throws Exception {
        var f=new ArchiveHistoryReaderTest();f.world=world;var a=f.row(UUID.randomUUID(),"a");var b=f.row(UUID.randomUUID(),"b");
        var old=f.store(Owner.JOBS,a);var other=f.store(Owner.JOBS,b);var current=f.store(Owner.JOBS,a,b);
        f.publish(ArchiveHistory.CURRENT,f.view(2,current));f.publish(ArchiveHistory.PREVIOUS,f.view(1,old,other));
        var p=new PersistencePolicy(48,96,policy.jobBytes(),policy.leaseBytes(),policy.archiveBytes(),128,16,131_072,1,0,80,policy.reservedBytes(),8);
        try(var writer=ArchiveWriter.open(world,f.worldId,p)) {
            assertTrue(writer.candidate(old.digest()).isEmpty());assertTrue(writer.checkpoint(writer.snapshot(),Owner.JOBS,guard,fences).acknowledged());
            var candidate=writer.candidate(old.digest()).orElseThrow();var candidateOther=writer.candidate(other.digest()).orElseThrow();
            var roots=new RetentionRoots();roots.publishNext("fixture",Set.of(),true);var manager=new WorldRetentionManager(WorldRetentionManager.Policy.defaults(),roots,caller->false);manager.register(writer.retentionOwner());
            var unrelated=manager.approveDiscard(Set.of(candidateOther.key()));assertEquals(WorldRetentionManager.Capacity.AVAILABLE,unrelated.status());
            try(var lease=unrelated.lease()){assertFalse(writer.cleanup(candidate,lease,()->false));}
            var approved=manager.approveDiscard(Set.of(candidate.key()));assertEquals(WorldRetentionManager.Capacity.AVAILABLE,approved.status());
            try(var lease=approved.lease()){assertTrue(writer.cleanup(candidate,lease,()->false));}
            assertFalse(Files.exists(f.directory().resolve(old.digest()+".seg")));assertEquals(a,writer.lookup(new Key(Owner.JOBS,Kind.HISTORY,a.id())).orElseThrow());assertEquals(b,writer.lookup(new Key(Owner.JOBS,Kind.HISTORY,b.id())).orElseThrow());
        }
    }
    @Test void changedOwnerRevisionInvalidatesPreviouslyApprovedCleanup()throws Exception {
        var f=new ArchiveHistoryReaderTest();f.world=world;var a=f.row(UUID.randomUUID(),"a");var old=f.store(Owner.JOBS,a);var current=f.store(Owner.JOBS,a,f.row(UUID.randomUUID(),"b"));
        f.publish(ArchiveHistory.CURRENT,f.view(2,current));var p=new PersistencePolicy(48,96,policy.jobBytes(),policy.leaseBytes(),policy.archiveBytes(),128,16,131_072,1,0,80,policy.reservedBytes(),8);
        try(var writer=ArchiveWriter.open(world,f.worldId,p)) {
            var candidate=writer.candidate(old.digest()).orElseThrow();var roots=new RetentionRoots();roots.publishNext("fixture",Set.of(),true);
            var manager=new WorldRetentionManager(WorldRetentionManager.Policy.defaults(),roots,caller->false);manager.register(writer.retentionOwner());var approved=manager.approveDiscard(Set.of(candidate.key()));assertNotNull(approved.lease());
            try(var lease=approved.lease()){assertTrue(writer.checkpoint(writer.snapshot(),Owner.JOBS,guard,fences).acknowledged());assertFalse(writer.cleanup(candidate,lease,()->false));}
            assertTrue(Files.exists(f.directory().resolve(old.digest()+".seg")));
        }
    }
    @ParameterizedTest @EnumSource(value=ArchiveWriter.Point.class,names={"BEFORE_CLEANUP","AFTER_CLEANUP"})
    void cleanupFaultBeforeOrAfterDeletionKeepsCompleteExactUuidHistory(ArchiveWriter.Point point)throws Exception {
        var f=new ArchiveHistoryReaderTest();f.world=world;var a=f.row(UUID.randomUUID(),"a");var b=f.row(UUID.randomUUID(),"b");
        var old=f.store(Owner.JOBS,a);var current=f.store(Owner.JOBS,a,b);f.publish(ArchiveHistory.CURRENT,f.view(2,current));
        var p=new PersistencePolicy(48,96,policy.jobBytes(),policy.leaseBytes(),policy.archiveBytes(),128,16,131_072,1,0,80,policy.reservedBytes(),8);
        try(var writer=ArchiveWriter.open(world,f.worldId,p,where->{if(where==point)throw new IOException("fixture cleanup fault");},Clock.systemUTC())) {
            var candidate=writer.candidate(old.digest()).orElseThrow();var roots=new RetentionRoots();roots.publishNext("fixture",Set.of(),true);
            var manager=new WorldRetentionManager(WorldRetentionManager.Policy.defaults(),roots,caller->false);manager.register(writer.retentionOwner());
            var approved=manager.approveDiscard(Set.of(candidate.key()));assertNotNull(approved.lease());
            try(var lease=approved.lease()){assertThrows(IOException.class,()->writer.cleanup(candidate,lease,()->false));assertTrue(writer.readOnly());}
        }
        var reloaded=ArchiveHistory.read(world,f.worldId,p);assertEquals(ArchiveHistory.State.READY,reloaded.state());
        assertEquals(a,reloaded.lookup(new Key(Owner.JOBS,Kind.HISTORY,a.id())).orElseThrow());assertEquals(b,reloaded.lookup(new Key(Owner.JOBS,Kind.HISTORY,b.id())).orElseThrow());
    }
    @Test void identicalInventoryFromAnotherRegisteredOwnerCannotLendCleanupAuthority()throws Exception {
        var f=new ArchiveHistoryReaderTest();f.world=world;var a=f.row(UUID.randomUUID(),"a");var old=f.store(Owner.JOBS,a);var current=f.store(Owner.JOBS,a,f.row(UUID.randomUUID(),"b"));
        f.publish(ArchiveHistory.CURRENT,f.view(2,current));var p=new PersistencePolicy(48,96,policy.jobBytes(),policy.leaseBytes(),policy.archiveBytes(),128,16,131_072,1,0,80,policy.reservedBytes(),8);
        try(var writer=ArchiveWriter.open(world,f.worldId,p)) {
            var candidate=writer.candidate(old.digest()).orElseThrow();var actual=writer.retentionOwner();assertSame(actual,writer.retentionOwner());
            var wrapper=new WorldRetentionManager.Owner() {
                public String id(){return actual.id();}public long revision()throws IOException{return actual.revision();}
                public WorldRetentionManager.Cursor open()throws IOException{return actual.open();}
                public WorldRetentionManager.OwnerResult collect(WorldRetentionManager.Entry e,long revision,java.util.function.BooleanSupplier cancel){return WorldRetentionManager.OwnerResult.of(WorldRetentionManager.OwnerStatus.PROTECTED);}
            };
            var roots=new RetentionRoots();roots.publishNext("fixture",Set.of(),true);var other=new WorldRetentionManager(WorldRetentionManager.Policy.defaults(),roots,caller->false);other.register(wrapper);
            var approved=other.approveDiscard(Set.of(candidate.key()));assertEquals(WorldRetentionManager.Capacity.AVAILABLE,approved.status());
            try(var lease=approved.lease()){assertFalse(writer.cleanup(candidate,lease,()->false));}
            assertTrue(Files.exists(f.directory().resolve(old.digest()+".seg")));
        }
    }
}
