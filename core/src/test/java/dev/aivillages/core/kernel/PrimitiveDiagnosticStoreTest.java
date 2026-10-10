package dev.aivillages.core.kernel;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.aivillages.core.kernel.PrimitiveDiagnosticCodec.*;
import static dev.aivillages.core.kernel.PrimitiveDiagnosticServiceTest.*;
import static dev.aivillages.core.kernel.PrimitiveDiagnostics.*;
import static org.junit.jupiter.api.Assertions.*;

class PrimitiveDiagnosticStoreTest {
    @TempDir Path temporary;
    static final String EVENT="00000000-0000-0000-0000-000000000001:1";
    private static void privateWrite(Path path,byte[] bytes)throws IOException {
        Files.write(path,bytes);Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
    }
    static List<Snapshot> read(PrimitiveDiagnosticStore store)throws Exception {
        List<Snapshot> rows=new ArrayList<>();boolean done=false;int cycles=0;
        while(!done) {
            assertTrue(++cycles<4096,"bounded replay completion");
            var slice=store.replay(32,131072);
            assertTrue(slice.inspected()<=32);assertTrue(slice.bytes()<=131072);
            rows.addAll(slice.rows());done=slice.complete();
        }
        return rows;
    }
    private Snapshot row(PrimitiveDiagnosticStore store,long n,long now) {
        return initial(observation(n),EVENT,now,store.salt(),Policy.metadata(),Map.of());
    }
    @Test void privateSaltAndWriterLockAreVerifiedAndReusedAcrossRestart()throws Exception {
        byte[] salt;Path root=temporary.resolve(PrimitiveDiagnosticStore.RELATIVE_ROOT);
        try(var store=PrimitiveDiagnosticStore.open(temporary,Policy.metadata())) {
            salt=store.salt();assertEquals(32,salt.length);
            assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(root));
            for(String name:List.of("salt.bin","writer.lock"))
                assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(root.resolve(name)));
            assertThrows(IOException.class,()->PrimitiveDiagnosticStore.open(temporary,Policy.metadata()));
            var row=row(store,1,new Time().wall);store.append(encode(row,8192),row.bucketStart(),row.lastSeen());
        }
        try(var reopened=PrimitiveDiagnosticStore.open(temporary,Policy.metadata())) {
            assertArrayEquals(salt,reopened.salt());assertEquals(1,read(reopened).size());
        }
    }
    @Test void concurrentSharedDirectoryCreationDoesNotDisablePrivateDiagnosticSubtree()throws Exception {
        Path world=temporary.resolve("shared-world");Files.createDirectory(world);
        AtomicInteger creates=new AtomicInteger();
        var faults=(PrimitiveDiagnosticStore.Faults)point->{
            if(point==PrimitiveDiagnosticStore.Point.DIRECTORY&&creates.getAndIncrement()==0) {
                // Deterministic authoritative-store interleaving before our first create.
                Files.createDirectories(world.resolve("data/cognitivecraft"));
                Files.setPosixFilePermissions(world.resolve("data"),PosixFilePermissions.fromString("rwxr-xr-x"));
                Files.setPosixFilePermissions(world.resolve("data/cognitivecraft"),PosixFilePermissions.fromString("rwxr-xr-x"));
            }
        };
        try(var store=PrimitiveDiagnosticStore.open(world,Policy.metadata(),faults)) {
            assertEquals(32,store.salt().length);
            Path root=world.resolve(PrimitiveDiagnosticStore.RELATIVE_ROOT);
            for(Path p=root;p.startsWith(root.getParent().getParent());p=p.getParent())
                assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(p));
        }
        Files.setPosixFilePermissions(world.resolve("data/cognitivecraft/diagnostics"),
                PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThrows(IOException.class,()->PrimitiveDiagnosticStore.open(world,Policy.metadata()));
    }
    @Test void hardSegmentReplayAndTemporaryReportByteCeilingsAreMeasuredAtExactMaximum()throws Exception {
        var time=new Time();Path root=temporary.resolve(PrimitiveDiagnosticStore.RELATIVE_ROOT);
        try(var store=PrimitiveDiagnosticStore.open(temporary,Policy.metadata())) {
            Snapshot row=row(store,1,time.wall);byte[] compact=encode(row,8192),padded=new byte[8192];
            Arrays.fill(padded,(byte)' ');System.arraycopy(compact,0,padded,0,compact.length-1);padded[8191]='\n';
            for(int n=0;n<1024;n++)store.append(padded,row.bucketStart(),time.wall);
            assertEquals(8,store.usage().segments());assertEquals(8L*1048576,store.usage().segmentBytes());
            for(int n=0;n<8;n++)assertEquals(1048576,Files.size(root.resolve("segment-"+n+".jsonl")));
            byte[] report=new byte[65536];Arrays.fill(report,(byte)' ');
            byte[] minimal=bytes(Map.of("schema",1L,"kind","primitive_diagnostic_report","expiresAt",row.bucketStart()+7*DAY));
            System.arraycopy(minimal,0,report,0,minimal.length);
            privateWrite(root.resolve("unknown.bin"),new byte[10485760-8388608-32-2*65536]);
            store.report(report,row.bucketStart()+7*DAY);store.report(report,row.bucketStart()+7*DAY);
            assertEquals(10485760,store.usage().peakBytes());assertEquals(13,store.usage().peakFiles());
            assertThrows(IOException.class,()->store.report(new byte[65537],row.bucketStart()+7*DAY));
            Path evidence=Path.of("build/primitive-diagnostic-evidence");Files.createDirectories(evidence);
            Files.write(evidence.resolve("storage-load.json"),bytes(Map.of("schema",1L,
                    "segmentCount",8L,"segmentBytes",8388608L,"maxSegmentBytes",1048576L,
                    "maxRecordBytes",8192L,"reportBytes",65536L,"peakDirectoryBytes",store.usage().peakBytes(),
                    "peakFilesIncludingTemporary",(long)store.usage().peakFiles())));
        }
        try(var reopened=PrimitiveDiagnosticStore.open(temporary,Policy.metadata())) {
            assertEquals(1024,read(reopened).size());assertEquals(8388608,reopened.usage().replayBytes());
        }
        assertThrows(IOException.class,()->PrimitiveDiagnosticStore.open(temporary,policy(Map.of(Limit.REPLAY_BYTES,8388607L))));
    }
    @Test void hardReplayRecordCeilingAcceptsExactly65536AndRejectsTheNextRow()throws Exception {
        Path root=temporary.resolve(PrimitiveDiagnosticStore.RELATIVE_ROOT);
        try(var ignored=PrimitiveDiagnosticStore.open(temporary,Policy.metadata())){}
        byte[] records=new byte[65536];Arrays.fill(records,(byte)'\n');privateWrite(root.resolve("segment-0.jsonl"),records);
        try(var exact=PrimitiveDiagnosticStore.open(temporary,Policy.metadata())) {
            assertTrue(read(exact).isEmpty());assertEquals(65536,exact.usage().replayRecords());
        }
        Files.write(root.resolve("segment-0.jsonl"),new byte[]{'\n'},StandardOpenOption.APPEND);
        try(var excess=PrimitiveDiagnosticStore.open(temporary,Policy.metadata())) {
            assertThrows(IOException.class,()->read(excess));assertEquals(65537,excess.usage().replayRecords());
        }
    }
    @Test void missingCorruptSaltNeverRegeneratesWhenHistoryExists()throws Exception {
        Path root=temporary.resolve(PrimitiveDiagnosticStore.RELATIVE_ROOT);
        try(var store=PrimitiveDiagnosticStore.open(temporary,Policy.metadata())) {
            var row=row(store,1,new Time().wall);store.append(encode(row,8192),row.bucketStart(),row.lastSeen());
        }
        Files.delete(root.resolve("salt.bin"));
        assertThrows(IOException.class,()->PrimitiveDiagnosticStore.open(temporary,Policy.metadata()));
        assertFalse(Files.exists(root.resolve("salt.bin")));
        privateWrite(root.resolve("salt.bin"),new byte[31]);
        assertThrows(IOException.class,()->PrimitiveDiagnosticStore.open(temporary,Policy.metadata()));
        assertEquals(31,Files.size(root.resolve("salt.bin")));
    }
    @Test void unsafePermissionsAndSymlinkFilesOrAncestorsDisableOnlyDiagnostics()throws Exception {
        try(var store=PrimitiveDiagnosticStore.open(temporary,Policy.metadata())){}
        Path root=temporary.resolve(PrimitiveDiagnosticStore.RELATIVE_ROOT);
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwxr-x---"));
        assertThrows(IOException.class,()->PrimitiveDiagnosticStore.open(temporary,Policy.metadata()));
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
        Files.delete(root.resolve("salt.bin"));Path foreign=temporary.resolve("private-other-store");
        privateWrite(foreign,new byte[32]);Files.createSymbolicLink(root.resolve("salt.bin"),foreign);
        assertThrows(IOException.class,()->PrimitiveDiagnosticStore.open(temporary,Policy.metadata()));
        assertEquals(32,Files.size(foreign));
        Path parent=temporary.resolve("linked-world");Files.createSymbolicLink(parent,temporary);
        assertThrows(IOException.class,()->PrimitiveDiagnosticStore.open(parent,Policy.metadata()));
    }
    @Test void rotationPrunesOnlyKnownSegmentsAndNeverExceedsFileByteCaps()throws Exception {
        var time=new Time();Policy small=policy(Map.of(Limit.RECORD_BYTES,4096L,Limit.SEGMENT_BYTES,4096L,Limit.SEGMENTS,2L,
                Limit.DIRECTORY_BYTES,16384L));
        Path unrelated=temporary.resolve("admitted-body.json");byte[] authoritative="immutable admitted knowledge".getBytes(StandardCharsets.UTF_8);
        Files.write(unrelated,authoritative);
        try(var store=PrimitiveDiagnosticStore.open(temporary,small)) {
            for(int i=0;i<12;i++) {
                var row=row(store,i+1,time.wall+i*DAY);
                store.append(encode(row,8192),row.bucketStart(),row.lastSeen());
                assertTrue(store.usage().segments()<=2);
                assertTrue(store.usage().segmentBytes()<=8192);
                assertTrue(store.usage().peakBytes()<=16384);
            }
            assertTrue(store.usage().peakFiles()<=16);
        }
        assertArrayEquals(authoritative,Files.readAllBytes(unrelated));
        try(var store=PrimitiveDiagnosticStore.open(temporary,small)) {
            var rows=read(store);assertEquals(2,rows.size());
            assertTrue(rows.stream().allMatch(r->r.lastSeen()>=time.wall+10*DAY));
        }
    }
    @Test void exactSegmentFitRotatesBeforeNextWriteAndKeepsOneDayPerSegment()throws Exception {
        var time=new Time();
        byte[] bytes;
        try(var store=PrimitiveDiagnosticStore.open(temporary,Policy.metadata())) {bytes=encode(row(store,1,time.wall),8192);}
        var p=policy(Map.of(Limit.RECORD_BYTES,(long)bytes.length,Limit.SEGMENT_BYTES,(long)bytes.length,Limit.SEGMENTS,2L));
        try(var store=PrimitiveDiagnosticStore.open(temporary,p)) {
            var first=row(store,1,time.wall);store.append(encode(first,8192),first.bucketStart(),time.wall);
            assertEquals(1,store.usage().segments());
            store.append(encode(first,8192),first.bucketStart(),time.wall);
            assertEquals(2,store.usage().segments());
            var next=row(store,2,time.wall+DAY);store.append(encode(next,8192),next.bucketStart(),time.wall+DAY);
            assertEquals(2,store.usage().segments());
        }
        try(var store=PrimitiveDiagnosticStore.open(temporary,p)) {assertEquals(2,read(store).size());}
    }
    @Test void cumulativeReplayTakesHighestRevisionAndRejectsMalformedTruncatedTail()throws Exception {
        var time=new Time();
        try(var store=PrimitiveDiagnosticStore.open(temporary,Policy.metadata())) {
            var first=row(store,1,time.wall);var second=first.next(EVENT,time.wall+1,Map.of());
            store.append(encode(first,8192),first.bucketStart(),time.wall);
            store.append(encode(second,8192),second.bucketStart(),time.wall+1);
        }
        Path root=temporary.resolve(PrimitiveDiagnosticStore.RELATIVE_ROOT);
        Files.writeString(root.resolve("segment-0.jsonl"),"{broken\n{\"schema\":1",StandardOpenOption.APPEND);
        var service=new PrimitiveDiagnosticService(Policy.metadata(),time,()->time.nano,
                ()->PrimitiveDiagnosticStore.open(temporary,Policy.metadata()),ignored->{},UUID.randomUUID(),false);
        service.cycle();
        assertEquals(1,service.snapshot().size());assertEquals(2,service.snapshot().getFirst().occurrences());
        assertEquals(2,count(service,Counter.REPLAY_INVALID));
        service.closeAsync();service.cycle();
    }
    @Test void idleExpiryDeletesActiveClosedReportAndTemporaryWithoutNewOffers()throws Exception {
        var time=new Time();
        try(var store=PrimitiveDiagnosticStore.open(temporary,Policy.metadata())) {
            var row=row(store,1,time.wall);store.append(encode(row,8192),row.bucketStart(),time.wall);
            var report=PrimitiveDiagnosticReport.render(List.of(row),Policy.metadata(),Map.of(),time.wall);
            store.report(report.json(),report.expiry());
            Path root=temporary.resolve(PrimitiveDiagnosticStore.RELATIVE_ROOT);
            privateWrite(root.resolve("report.json.tmp"),report.json());
        }
        Path root=temporary.resolve(PrimitiveDiagnosticStore.RELATIVE_ROOT);
        try(var store=PrimitiveDiagnosticStore.open(temporary,Policy.metadata())) {
            read(store);
            long expired=bucket(time.wall)+7*DAY;
            assertTrue(store.maintain(expired,32)<=32);
            assertFalse(Files.exists(root.resolve("segment-0.jsonl")));
            assertFalse(Files.exists(root.resolve("report.json")));assertFalse(Files.exists(root.resolve("report.json.tmp")));
            assertEquals(32,Files.size(root.resolve("salt.bin")));
        }
    }
    @Test void expiredRowsAreSuppressedImmediatelyEvenIfPhysicalDeleteFails()throws Exception {
        var time=new Time();Snapshot row;
        try(var store=PrimitiveDiagnosticStore.open(temporary,Policy.metadata())) {
            row=row(store,1,time.wall);store.append(encode(row,8192),row.bucketStart(),time.wall);
        }
        time.wall=bucket(time.wall)+7*DAY;
        var faults=(PrimitiveDiagnosticStore.Faults)point->{if(point==PrimitiveDiagnosticStore.Point.DELETE)throw new IOException("retention secret");};
        var s=new PrimitiveDiagnosticService(Policy.metadata(),time,()->time.nano,
                ()->PrimitiveDiagnosticStore.open(temporary,Policy.metadata(),faults),ignored->{},UUID.randomUUID(),false);
        s.cycle();assertTrue(s.snapshot().isEmpty());
        assertEquals(PrimitiveDiagnosticService.State.READY,s.status().state());
        time.advance();s.cycle();assertTrue(s.snapshot().isEmpty());
        assertEquals(PrimitiveDiagnosticService.State.DISABLED,s.status().state());
        assertTrue(count(s,Counter.RETENTION_FAILURE)>0);
        assertTrue(Files.exists(temporary.resolve(PrimitiveDiagnosticStore.RELATIVE_ROOT).resolve("segment-0.jsonl")));
        assertTrue(PrimitiveDiagnosticReport.render(List.of(row),Policy.metadata(),Map.of(),time.wall).rows()==0);
        s.closeAsync();s.cycle();
    }
    @Test void futureSchemaRemainsInactiveAndNeverCreatesQuarantineOrDeletesUnknownData()throws Exception {
        Path root=temporary.resolve(PrimitiveDiagnosticStore.RELATIVE_ROOT);
        try(var store=PrimitiveDiagnosticStore.open(temporary,Policy.metadata())){}
        byte[] future="{\"schema\":2,\"kind\":\"future\",\"secret\":\"host-only\"}\n".getBytes(StandardCharsets.UTF_8);
        privateWrite(root.resolve("segment-0.jsonl"),future);
        try(var store=PrimitiveDiagnosticStore.open(temporary,Policy.metadata())) {assertThrows(IOException.class,()->read(store));}
        assertArrayEquals(future,Files.readAllBytes(root.resolve("segment-0.jsonl")));
        try(var paths=Files.list(root)){assertEquals(3,paths.count());}
    }
    @Test void unknownFilesAndReportTemporaryFootprintCountTowardDirectoryQuota()throws Exception {
        Path root=temporary.resolve(PrimitiveDiagnosticStore.RELATIVE_ROOT);
        try(var store=PrimitiveDiagnosticStore.open(temporary,Policy.metadata())){}
        privateWrite(root.resolve("unrecognized.operator-data"),new byte[4096]);
        var small=policy(Map.of(Limit.RECORD_BYTES,4096L,Limit.SEGMENT_BYTES,4096L,Limit.DIRECTORY_BYTES,5000L));
        try(var store=PrimitiveDiagnosticStore.open(temporary,small)) {
            var row=row(store,1,new Time().wall);
            assertThrows(IOException.class,()->store.append(encode(row,8192),row.bucketStart(),row.lastSeen()));
            assertEquals(4096,Files.size(root.resolve("unrecognized.operator-data")));
        }
        Files.delete(root.resolve("unrecognized.operator-data"));
        try(var store=PrimitiveDiagnosticStore.open(temporary,Policy.metadata())) {
            var row=row(store,1,new Time().wall);var report=PrimitiveDiagnosticReport.render(List.of(row),Policy.metadata(),Map.of(),row.lastSeen());
            store.report(report.json(),report.expiry());
            assertTrue(store.usage().peakBytes()<=10485760);
            assertFalse(Files.exists(root.resolve("report.json.tmp")));
            assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(root.resolve("report.json")));
        }
    }
    @Test void startupRejectsSeventeenthFileAndScanRecordExcessWithFiniteWork()throws Exception {
        Path root=temporary.resolve(PrimitiveDiagnosticStore.RELATIVE_ROOT);
        try(var store=PrimitiveDiagnosticStore.open(temporary,Policy.metadata())){}
        for(int i=0;i<14;i++)privateWrite(root.resolve("unknown-"+i),new byte[0]);
        try(var store=PrimitiveDiagnosticStore.open(temporary,Policy.metadata())){assertEquals(16,store.usage().peakFiles());}
        privateWrite(root.resolve("unknown-14"),new byte[0]);
        assertThrows(IOException.class,()->PrimitiveDiagnosticStore.open(temporary,Policy.metadata()));
        for(int i=0;i<15;i++)Files.delete(root.resolve("unknown-"+i));
        privateWrite(root.resolve("segment-0.jsonl"),"\n\n\n\n".getBytes(StandardCharsets.UTF_8));
        try(var store=PrimitiveDiagnosticStore.open(temporary,policy(Map.of(Limit.REPLAY_RECORDS,3L)))) {
            assertThrows(IOException.class,()->read(store));assertTrue(store.usage().replayBytes()<=4);
        }
    }
    @Test void partialSingleWriteStopsPersistenceAndRestartTreatsItAsDisposableTail()throws Exception {
        var time=new Time();AtomicInteger writes=new AtomicInteger();
        var faults=new PrimitiveDiagnosticStore.Faults() {
            @Override public void check(PrimitiveDiagnosticStore.Point point){}
            @Override public int write(FileChannel channel,ByteBuffer bytes)throws IOException {
                writes.incrementAndGet();bytes.limit(Math.max(1,bytes.remaining()/2));return channel.write(bytes);
            }
        };
        var s=new PrimitiveDiagnosticService(Policy.metadata(),time,()->time.nano,
                ()->PrimitiveDiagnosticStore.open(temporary,Policy.metadata(),faults),ignored->{},UUID.randomUUID(),false);
        s.cycle();s.offer(observation(1));step(s,time);
        assertEquals(PrimitiveDiagnosticService.State.MEMORY_ONLY,s.status().state());assertEquals(1,writes.get());
        s.beginTick();s.offer(observation(2));step(s,time);assertEquals(1,writes.get());
        s.closeAsync();s.cycle();
        try(var store=PrimitiveDiagnosticStore.open(temporary,Policy.metadata())) {
            var slice=store.replay(32,131072);assertTrue(slice.rows().isEmpty());assertEquals(1,slice.invalid());
        }
    }
    @Test void blockedWriterLeavesIntakeBoundedAndCloseDeadlineNeverJoinsItsThread()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var faults=(PrimitiveDiagnosticStore.Faults)point->{
            if(point==PrimitiveDiagnosticStore.Point.WRITE) {
                entered.countDown();
                boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException ignored){}
            }
        };
        var s=PrimitiveDiagnosticService.open(temporary,Policy.metadata(),java.time.Clock.systemUTC(),ignored->{},faults);
        try {
            for(int n=0;n<500&&s.status().state()!=PrimitiveDiagnosticService.State.READY;n++)Thread.sleep(10);
            assertEquals(Offer.ACCEPTED,s.offer(observation(1)));assertTrue(entered.await(5,TimeUnit.SECONDS));
            for(int tick=0;tick<17;tick++){s.beginTick();for(int n=0;n<8;n++)s.offer(observation(100+tick*8+n));}
            assertEquals(Offer.FULL,s.offer(observation(99999)));
            var closure=s.closeAsync();assertEquals(Offer.UNAVAILABLE,s.offer(observation(2)));
            closure.get(3,TimeUnit.SECONDS);
            assertEquals(1,release.getCount(),"close completed without making the writer complete");
            assertTrue(Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive()
                    &&t.isDaemon()&&t.getName().equals("cognitivecraft-primitive-diagnostics")));
        } finally {release.countDown();s.closeAsync();}
    }
}
