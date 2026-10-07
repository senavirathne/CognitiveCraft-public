package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static dev.aivillages.core.kernel.ResourceLeases.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;
import static org.junit.jupiter.api.Assertions.*;

class ResourceLeaseJournalTest {
    @TempDir Path world;
    static final UUID WORLD=ResourceLeaseServiceTest.WORLD;
    static Lease retired(Lease l){
        return new Lease(l.id(),l.group(),l.epoch(),l.generation(),l.owner(),l.origin(),l.resource(),l.quantity(),
                l.remaining(),l.identity(),l.granted(),l.renewed(),l.expires(),l.renewals(),State.RELEASED,Reason.CANCELLED,l.consumed());
    }
    Snapshot fixture() {
        var f=new ResourceLeaseServiceTest.Fixture();f.alice(f.stock,3);
        return new Snapshot(WORLD,f.disk.epoch(),1,f.disk.generation(),f.disk.leases());
    }
    Path current(){return world.resolve(ResourceLeaseJournal.WORLD_RELATIVE_PATH).resolve("state.jsonl");}
    void seed(Snapshot first)throws Exception{
        try(var j=ResourceLeaseJournal.open(world,WORLD)){assertEquals(first,j.replace(j.snapshot(),first));}
    }
    @Test void strictRoundTripPreservesIdsScopesGenerationsAndAccounting()throws Exception{
        Snapshot first=fixture();seed(first);
        try(var j=ResourceLeaseJournal.open(world,WORLD)){assertEquals(first,j.snapshot());assertFalse(j.readOnly());}
        assertTrue(Files.size(current())<ResourceLeaseJournal.MAX_BYTES);
    }
    @ParameterizedTest @EnumSource(ResourceLeaseJournal.Point.class)
    void everyPublicationBoundaryProducesWholeOldOrNewAndPreservesBackup(ResourceLeaseJournal.Point point)throws Exception{
        Snapshot old=fixture();seed(old);Lease ended=retired(old.leases().getFirst());
        Snapshot next=new Snapshot(WORLD,old.epoch(),2,old.generation(),List.of(ended));
        try(var j=ResourceLeaseJournal.open(world,WORLD,p->{if(p==point)throw new IOException("fault "+p);})){
            assertThrows(IOException.class,()->j.replace(old,next));assertTrue(j.readOnly());
        }
        try(var loaded=ResourceLeaseJournal.open(world,WORLD)){
            assertTrue(loaded.snapshot().equals(old)||loaded.snapshot().equals(next));
            assertEquals(point==ResourceLeaseJournal.Point.AFTER_REPLACE?next:old,loaded.snapshot());
        }
    }
    @Test void onlyOnePublicationWriterMayOwnAWorld()throws Exception{
        try(var first=ResourceLeaseJournal.open(world,WORLD)){
            assertThrows(IOException.class,()->ResourceLeaseJournal.open(world,WORLD));
        }
        try(var second=ResourceLeaseJournal.open(world,WORLD)){assertFalse(second.readOnly());}
    }
    @Test void futureCurrentDoesNotUseOldBackupAsPermissionToOverwrite()throws Exception{
        Snapshot first=fixture();seed(first);
        Snapshot next=new Snapshot(WORLD,first.epoch(),2,first.generation(),List.of(retired(first.leases().getFirst())));
        try(var j=ResourceLeaseJournal.open(world,WORLD)){j.replace(first,next);}
        byte[] future=Files.readAllBytes(current());
        future=new String(future,StandardCharsets.UTF_8).replaceFirst("\"schema\":1","\"schema\":99").getBytes(StandardCharsets.UTF_8);
        Files.write(current(),future);byte[] original=future;
        try(var j=ResourceLeaseJournal.open(world,WORLD)){
            assertTrue(j.readOnly());assertEquals(first,j.snapshot());
            assertThrows(IOException.class,()->j.replace(j.snapshot(),next));
        }
        assertArrayEquals(original,Files.readAllBytes(current()));
    }
    @Test void corruptCurrentAndBackupStayPreservedInactive()throws Exception{
        Snapshot first=fixture();seed(first);byte[] invalid="not-json\n".getBytes(StandardCharsets.UTF_8);
        Files.write(current(),invalid);
        try(var j=ResourceLeaseJournal.open(world,WORLD)){assertTrue(j.readOnly());assertTrue(j.snapshot().leases().isEmpty());}
        assertArrayEquals(invalid,Files.readAllBytes(current()));
    }
    @Test void oldValidBackupIsRecoveredReadOnly()throws Exception{
        Snapshot first=fixture();seed(first);
        Snapshot next=new Snapshot(WORLD,first.epoch(),2,first.generation(),List.of(retired(first.leases().getFirst())));
        try(var j=ResourceLeaseJournal.open(world,WORLD)){j.replace(first,next);}
        byte[] corrupt="broken\n".getBytes(StandardCharsets.UTF_8);Files.write(current(),corrupt);
        try(var j=ResourceLeaseJournal.open(world,WORLD)){assertTrue(j.readOnly());assertEquals(first,j.snapshot());}
        assertArrayEquals(corrupt,Files.readAllBytes(current()));
    }
    @Test void wrongWorldPreservesOriginalWithoutAdmittingClaims()throws Exception{
        seed(fixture());byte[] original=Files.readAllBytes(current());UUID other=UUID.randomUUID();
        try(var j=ResourceLeaseJournal.open(world,other)){assertTrue(j.readOnly());assertTrue(j.snapshot().leases().isEmpty());}
        assertArrayEquals(original,Files.readAllBytes(current()));
    }
    @Test void forgedUnknownFieldOrConsumedQuantityIsNotLoaded()throws Exception{
        Snapshot first=fixture();seed(first);
        var row=new LinkedHashMap<>(StrictJson.object(ResourceLeaseCodec.encode(first.leases().getFirst())));
        row.put("untrustedAuthority",true);writeRows(first,List.of(StrictJson.canonical(row)));
        try(var j=ResourceLeaseJournal.open(world,WORLD)){assertTrue(j.readOnly());assertTrue(j.snapshot().leases().isEmpty());}
        row.remove("untrustedAuthority");row.put("remaining",4L);writeRows(first,List.of(StrictJson.canonical(row)));
        try(var j=ResourceLeaseJournal.open(world,WORLD)){assertTrue(j.readOnly());}
    }
    @Test void forgedConflictingExclusiveRecordsAreNotLoaded()throws Exception{
        var f=new ResourceLeaseServiceTest.Fixture();f.alice(f.facility(1),1);Snapshot first=f.disk;
        Lease a=first.leases().getFirst();
        Lease b=new Lease(UUID.randomUUID(),UUID.randomUUID(),a.epoch(),a.generation(),f.bob,f.b,a.resource(),1,1,
                a.identity(),a.granted(),a.renewed(),a.expires(),0,State.ACTIVE,null,Map.of());
        writeRows(first,List.of(ResourceLeaseCodec.encode(a),ResourceLeaseCodec.encode(b)));
        try(var j=ResourceLeaseJournal.open(world,WORLD)){assertTrue(j.readOnly());assertTrue(j.snapshot().leases().isEmpty());}
    }
    @Test void duplicateHeaderKeysMalformedUtf8AndOversizedInputAreRejected()throws Exception{
        Files.createDirectories(current().getParent());
        for(byte[] bytes:List.of("{\"schema\":1,\"schema\":1}\n".getBytes(StandardCharsets.UTF_8),
                new byte[]{(byte)0xc3,(byte)0x28,10},new byte[ResourceLeaseJournal.MAX_BYTES+1])){
            Files.write(current(),bytes);
            try(var j=ResourceLeaseJournal.open(world,WORLD)){assertTrue(j.readOnly());assertTrue(j.snapshot().leases().isEmpty());}
            assertArrayEquals(bytes,Files.readAllBytes(current()));
        }
    }
    @Test void staleRevisionAndSymlinkNeverPublish()throws Exception{
        Snapshot first=fixture();seed(first);
        try(var j=ResourceLeaseJournal.open(world,WORLD)){
            assertThrows(IOException.class,()->j.replace(Snapshot.empty(WORLD),first));
        }
        Files.delete(current());Path outside=world.resolve("outside");Files.writeString(outside,"preserve");
        Files.createSymbolicLink(current(),outside);assertThrows(IOException.class,()->ResourceLeaseJournal.open(world,WORLD));
        assertEquals("preserve",Files.readString(outside));
    }
    @Test void dataOnlyJournalReadsDoNotTouchPhysicalOrJobFiles()throws Exception{
        Files.writeString(world.resolve("physical-world"),"five real items");
        Files.writeString(world.resolve("job-owner"),"private responsibility");
        seed(fixture());
        try(var j=ResourceLeaseJournal.open(world,WORLD)){ResourceLeaseJournal.encode(j.snapshot());}
        assertEquals("five real items",Files.readString(world.resolve("physical-world")));
        assertEquals("private responsibility",Files.readString(world.resolve("job-owner")));
    }
    void writeRows(Snapshot first,List<String> rows)throws Exception{
        Files.createDirectories(current().getParent());String payload=String.join("\n",rows)+"\n";
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        String header=StrictJson.canonical(Map.of("schema",1L,"world",WORLD.toString(),"epoch",first.epoch().toString(),
                "revision",first.revision(),"generation",first.generation(),"records",(long)rows.size(),"sha256",digest));
        Files.writeString(current(),header+"\n"+payload);
    }
}
