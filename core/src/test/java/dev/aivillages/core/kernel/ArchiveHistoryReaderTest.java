package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.runtime.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static dev.aivillages.core.kernel.runtime.persistence.ArchiveFormat.*;
import static org.junit.jupiter.api.Assertions.*;

/** Format fixtures are test-only. No production archive encoder/writer exists in this reader stage. */
final class ArchiveHistoryReaderTest {
    @TempDir Path world;
    final UUID worldId=UUID.randomUUID();final PersistencePolicy policy=PersistencePolicy.defaults();
    Path directory()throws IOException {return Files.createDirectories(world.resolve(ArchiveHistory.RELATIVE_PATH));}
    static byte[] segment(Segment segment)throws IOException {
        var output=new ByteArrayOutputStream();var data=new DataOutputStream(output);
        data.writeLong(MAGIC);data.writeInt(SCHEMA);data.writeLong(segment.world().getMostSignificantBits());data.writeLong(segment.world().getLeastSignificantBits());
        data.writeByte(segment.owner().ordinal());data.writeLong(segment.guard().ownerRevision());data.writeLong(segment.guard().retentionGeneration());data.writeInt(segment.rows().size());
        for(var row:segment.rows()) {data.writeByte(row.kind().ordinal());data.writeLong(row.id().getMostSignificantBits());data.writeLong(row.id().getLeastSignificantBits());data.writeInt(row.payload().length);data.write(row.payload());}
        data.write(java.util.HexFormat.of().parseHex(sha256(output.toByteArray())));return output.toByteArray();
    }
    static byte[] manifest(Manifest value)throws IOException {
        var body=new LinkedHashMap<String,Object>();
        body.put("segments",value.segments().stream().map(r->Map.of("digest",r.digest(),"owner",r.owner().name(),"bytes",r.bytes(),"records",(long)r.records(),"createdEpochMillis",r.createdEpochMillis())).toList());
        body.put("slots",value.slots().stream().map(s->Map.of("id",s.id().toString(),"owner",s.owner().name(),"root",s.root().toString(),"attempt",s.attempt().toString(),"generation",s.generation(),"reservedBytes",s.reservedBytes(),"remainingBytes",s.remainingBytes(),"phase",s.phase().name())).toList());
        body.put("migrations",value.migrations().stream().map(m->Map.of("id",m.id().toString(),"owner",m.owner().name(),"sourceSchema",(long)m.sourceSchema(),"sourceDigest",m.sourceDigest(),"targetRevision",m.targetRevision(),"targetDigest",m.targetDigest())).toList());
        String payload=StrictJson.canonical(body);String header=StrictJson.canonical(Map.of("schema",(long)SCHEMA,"world",value.world().toString(),"revision",value.revision(),"owners",Map.of("JOBS",value.ownerRevisions().get(Owner.JOBS),"LEASES",value.ownerRevisions().get(Owner.LEASES)),"retentionGeneration",value.retentionGeneration(),"records",(long)value.segments().size(),"sha256",sha256(payload.getBytes(StandardCharsets.UTF_8))));
        return (header+"\n"+payload).getBytes(StandardCharsets.UTF_8);
    }
    Row row(UUID id,String payload){return new Row(Kind.HISTORY,id,payload.getBytes(StandardCharsets.UTF_8));}
    Ref store(Owner owner,Row... rows)throws IOException {
        byte[] bytes=segment(new Segment(worldId,owner,new Guard(1,1),List.of(rows)));String digest=sha256(bytes);
        Files.write(directory().resolve(digest+".seg"),bytes);return new Ref(digest,owner,bytes.length,rows.length,1);
    }
    Manifest view(long revision,Ref... refs){return new Manifest(worldId,revision,Map.of(Owner.JOBS,1L,Owner.LEASES,1L),1,List.of(refs),List.of(),List.of());}
    void publish(String name,Manifest value)throws IOException {Files.write(directory().resolve(name),manifest(value));}
    ArchiveHistory read()throws IOException{return ArchiveHistory.read(world,worldId,policy);}
    @Test void absentDirectoryIsReadOnlyEmptyAndNeverCreated()throws Exception {
        assertEquals(ArchiveHistory.State.READY,read().state());assertFalse(Files.exists(world.resolve(ArchiveHistory.RELATIVE_PATH)));
    }
    @Test void exactUnorderedLegacyUuidLookupAndOwnerNamespacesArePreserved()throws Exception {
        UUID id=UUID.fromString("00000000-0000-0000-0000-000000000001");var high=row(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),"high");
        var job=store(Owner.JOBS,high,row(id,"job"));var lease=store(Owner.LEASES,row(id,"lease"));publish(ArchiveHistory.CURRENT,view(1,job,lease));
        var history=read();assertEquals(ArchiveHistory.State.READY,history.state());
        assertEquals(row(id,"job"),history.lookup(new Key(Owner.JOBS,Kind.HISTORY,id)).orElseThrow());
        assertEquals(row(id,"lease"),history.lookup(new Key(Owner.LEASES,Kind.HISTORY,id)).orElseThrow());
        assertTrue(history.lookup(new Key(Owner.JOBS,Kind.HISTORY,UUID.randomUUID())).isEmpty());
    }
    @Test void legacyJournalBytesStayOpaqueAndExactlyRoundTripWithoutDomainReinterpretation()throws Exception {
        var scene=new JobLifecycleStoreTest.Scene();var job=scene.assign(scene.create(2));byte[] original=JobJournal.encode(scene.memory.state);
        var row=new Row(Kind.HISTORY,job.id(),original);var ref=store(Owner.JOBS,row);publish(ArchiveHistory.CURRENT,view(1,ref));
        assertArrayEquals(original,read().lookup(new Key(Owner.JOBS,Kind.HISTORY,job.id())).orElseThrow().payload());
    }
    @Test void identicalDuplicatesDoNotConflictAndPayloadArraysAreImmutable()throws Exception {
        UUID id=UUID.randomUUID();byte[] bytes={1,2,3};var value=new Row(Kind.HISTORY,id,bytes);bytes[0]=9;
        byte[] copy=value.payload();copy[1]=9;assertArrayEquals(new byte[]{1,2,3},value.payload());
        var first=store(Owner.JOBS,value);var second=store(Owner.JOBS,value,row(UUID.randomUUID(),"other"));publish(ArchiveHistory.CURRENT,view(2,first,second));
        assertEquals(ArchiveHistory.State.READY,read().state());
    }
    @Test void conflictingExactUuidNeverTurnsIntoAbsenceOrLastWriterWins()throws Exception {
        UUID id=UUID.randomUUID();var first=store(Owner.JOBS,row(id,"original"));var second=store(Owner.JOBS,row(id,"conflicting"));publish(ArchiveHistory.CURRENT,view(2,first,second));
        var history=read();assertEquals(ArchiveHistory.State.FENCED,history.state());assertThrows(IOException.class,()->history.lookup(new Key(Owner.JOBS,Kind.HISTORY,id)));
    }
    @Test void unknownCurrentKeepsOriginalAndBackupIsInspectionOnly()throws Exception {
        var ref=store(Owner.JOBS,row(UUID.randomUUID(),"retained"));publish(ArchiveHistory.PREVIOUS,view(1,ref));
        byte[] unknown=manifest(view(2,ref));unknown=new String(unknown,StandardCharsets.UTF_8).replaceFirst("\"schema\":1","\"schema\":99").getBytes(StandardCharsets.UTF_8);
        Path file=directory().resolve(ArchiveHistory.CURRENT);Files.write(file,unknown);
        var history=read();assertEquals(ArchiveHistory.State.FENCED,history.state());assertEquals(1,history.manifest().revision());assertArrayEquals(unknown,Files.readAllBytes(file));
    }
    @Test void corruptSegmentAndMissingReferenceFenceAllUuidAbsenceClaims()throws Exception {
        var ref=store(Owner.JOBS,row(UUID.randomUUID(),"retained"));publish(ArchiveHistory.CURRENT,view(1,ref));Path file=directory().resolve(ref.digest()+".seg");byte[] bytes=Files.readAllBytes(file);bytes[20]^=1;Files.write(file,bytes);
        assertEquals(ArchiveHistory.State.FENCED,read().state());assertArrayEquals(bytes,Files.readAllBytes(file));Files.delete(file);
        var absent=read();assertEquals(ArchiveHistory.State.FENCED,absent.state());assertThrows(IOException.class,()->absent.lookup(new Key(Owner.JOBS,Kind.HISTORY,UUID.randomUUID())));
    }
    @Test void previousAndPendingReferenceClosureIsPinnedAndPendingIsNotAcknowledgement()throws Exception {
        var old=store(Owner.JOBS,row(UUID.randomUUID(),"old"));var next=store(Owner.JOBS,row(UUID.randomUUID(),"next"));
        publish(ArchiveHistory.CURRENT,view(1,old));publish(ArchiveHistory.PENDING,view(2,old,next));
        var history=read();assertEquals(ArchiveHistory.State.FENCED,history.state());assertEquals(Set.of(old.digest(),next.digest()),history.protectedSegments());
    }
    @Test void acknowledgedRepackingKeepsEveryLegacyUuidWhileObsoletePhysicalCopiesStayAccounted()throws Exception {
        var a=row(UUID.randomUUID(),"a");var b=row(UUID.randomUUID(),"b");var old=store(Owner.JOBS,a);var combined=store(Owner.JOBS,a,b);
        publish(ArchiveHistory.CURRENT,view(2,combined));publish(ArchiveHistory.PREVIOUS,view(1,old));
        var first=read();assertEquals(ArchiveHistory.State.READY,first.state());assertTrue(first.protectedSegments().contains(old.digest()));
        Files.delete(directory().resolve(ArchiveHistory.PREVIOUS));var second=read();assertEquals(ArchiveHistory.State.READY,second.state());assertEquals(Set.of(old.digest()),second.redundantSegments());
        assertTrue(Files.exists(directory().resolve(old.digest()+".seg")));assertTrue(second.bytes()>combined.bytes());
    }
    @Test void droppingUuidFromPreviousOrLeavingUnacknowledgedOrphanFencesHistory()throws Exception {
        var first=store(Owner.JOBS,row(UUID.randomUUID(),"old"));var next=store(Owner.JOBS,row(UUID.randomUUID(),"next"));
        publish(ArchiveHistory.CURRENT,view(2,next));publish(ArchiveHistory.PREVIOUS,view(1,first));assertEquals(ArchiveHistory.State.FENCED,read().state());
        Files.delete(directory().resolve(ArchiveHistory.PREVIOUS));assertEquals(ArchiveHistory.State.FENCED,read().state());
    }
    @Test void foreignWorldOwnerRevisionAndReferenceSizeCannotBorrowCachedValidation()throws Exception {
        var ref=store(Owner.JOBS,row(UUID.randomUUID(),"a"));publish(ArchiveHistory.CURRENT,view(2,ref));
        var invalid=new Ref(ref.digest(),ref.owner(),ref.bytes()+1,ref.records(),ref.createdEpochMillis());publish(ArchiveHistory.PREVIOUS,view(1,invalid));
        assertEquals(ArchiveHistory.State.FENCED,read().state());
        assertEquals(ArchiveHistory.State.FENCED,ArchiveHistory.read(world,UUID.randomUUID(),policy).state());
        Files.delete(directory().resolve(ArchiveHistory.PREVIOUS));publish(ArchiveHistory.CURRENT,new Manifest(worldId,2,Map.of(Owner.JOBS,0L,Owner.LEASES,1L),1,List.of(ref),List.of(),List.of()));assertEquals(ArchiveHistory.State.FENCED,read().state());
    }
    @Test void malformedBoundedFramesRejectTruncationCountsEnumsAndTrailingData()throws Exception {
        byte[] valid=segment(new Segment(worldId,Owner.JOBS,new Guard(1,1),List.of(row(UUID.randomUUID(),"a"))));
        assertThrows(IOException.class,()->ArchiveFormat.segment(Arrays.copyOf(valid,valid.length-1),worldId));
        byte[] bad=valid.clone();bad[28]=(byte)255;byte[] body=Arrays.copyOf(bad,bad.length-32);System.arraycopy(HexFormat.of().parseHex(sha256(body)),0,bad,bad.length-32,32);
        assertThrows(IOException.class,()->ArchiveFormat.segment(bad,worldId));assertThrows(IOException.class,()->ArchiveFormat.segment(new byte[PersistencePolicy.SEGMENT_BYTES+1],worldId));
    }
    @Test void symbolicOrUnexpectedPathsPreserveEvidenceAndDoNotBecomeEmptyHistory()throws Exception {
        Path root=directory();Path target=world.resolve("outside");Files.writeString(target,"evidence");Files.createSymbolicLink(root.resolve("unknown"),target);
        assertThrows(IOException.class,this::read);assertEquals("evidence",Files.readString(target));Files.delete(root.resolve("unknown"));Files.writeString(root.resolve("unknown"),"preserve");
        assertEquals(ArchiveHistory.State.FENCED,read().state());assertEquals("preserve",Files.readString(root.resolve("unknown")));
    }
    @Test void reservationAndMigrationReadersKeepOriginalIdentityAndRemainingCapacity()throws Exception {
        var slot=new Slot(UUID.randomUUID(),Owner.JOBS,UUID.randomUUID(),UUID.randomUUID(),1,65_536,60_000,Phase.RESERVED);
        var receipt=new Migration(UUID.randomUUID(),Owner.JOBS,0,"0".repeat(64),1,"1".repeat(64));
        var value=new Manifest(worldId,2,Map.of(Owner.JOBS,1L,Owner.LEASES,1L),1,List.of(),List.of(slot),List.of(receipt));
        assertEquals(value,ArchiveFormat.manifest(manifest(value),worldId));
        assertThrows(IllegalArgumentException.class,()->new Slot(slot.id(),Owner.JOBS,slot.root(),slot.attempt(),0,1,1,Phase.RESERVED));
        assertThrows(IllegalArgumentException.class,()->new Migration(receipt.id(),Owner.LEASES,3,receipt.sourceDigest(),1,receipt.targetDigest()));
    }
    @Test void quotasAndSettlementReserveAreFiniteAndSeparateFromOrdinaryPressure()throws Exception {
        assertEquals(128,policy.segments());assertEquals(132,policy.files());assertEquals(512*1024,policy.reservedBytes());assertTrue(policy.ordinaryBytes()<policy.archiveBytes()-policy.reservedBytes());
        assertThrows(IllegalArgumentException.class,()->new PersistencePolicy(65,96,policy.jobBytes(),policy.leaseBytes(),policy.archiveBytes(),128,16,131_072,1,0,80,policy.reservedBytes(),8));
        assertThrows(IllegalArgumentException.class,()->new PersistencePolicy(48,96,policy.jobBytes(),policy.leaseBytes(),policy.archiveBytes(),128,16,131_072,1,0,80,65_536,8));
        assertThrows(IllegalArgumentException.class,()->new PersistencePolicy(48,96,policy.jobBytes(),policy.leaseBytes(),policy.archiveBytes(),129,16,131_072,1,0,80,policy.reservedBytes(),8));
    }
    @Test void maximumManifestFitsStrictParserWithAllReferenceAndReservedIdentityFields()throws Exception {
        var refs=new ArrayList<Ref>();var slots=new ArrayList<Slot>();var migrations=new ArrayList<Migration>();
        for(int n=0;n<128;n++)refs.add(new Ref(String.format("%064x",n+1),Owner.JOBS,103,1,1));
        for(int n=0;n<8;n++) {
            slots.add(new Slot(UUID.randomUUID(),Owner.JOBS,UUID.randomUUID(),UUID.randomUUID(),1,65_536,65_536,Phase.RESERVED));
            migrations.add(new Migration(UUID.randomUUID(),Owner.JOBS,n%4,"0".repeat(64),1,"1".repeat(64)));
        }
        var value=new Manifest(worldId,2,Map.of(Owner.JOBS,1L,Owner.LEASES,1L),1,refs,slots,migrations);
        byte[] bytes=manifest(value);assertTrue(bytes.length<PersistencePolicy.MANIFEST_BYTES);assertEquals(value,ArchiveFormat.manifest(bytes,worldId));
    }
    @Test void actualSegmentAndSparseByteBoundariesRefuseBeforeLoadingOrDeletingEvidence()throws Exception {
        var first=store(Owner.JOBS,row(UUID.randomUUID(),"first"));var second=store(Owner.JOBS,row(UUID.randomUUID(),"second"));publish(ArchiveHistory.CURRENT,view(2,first,second));
        var one=new PersistencePolicy(48,96,policy.jobBytes(),policy.leaseBytes(),policy.archiveBytes(),1,16,131_072,1,0,80,policy.reservedBytes(),8);
        assertThrows(IOException.class,()->ArchiveHistory.read(world,worldId,one));
        Path huge=directory().resolve("large");try(var file=java.nio.channels.FileChannel.open(huge,StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            file.position(policy.archiveBytes());file.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
        }
        assertThrows(IOException.class,this::read);assertEquals(policy.archiveBytes()+1,Files.size(huge));
    }
}
