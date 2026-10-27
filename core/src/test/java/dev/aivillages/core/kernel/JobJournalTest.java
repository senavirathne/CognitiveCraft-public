package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Jobs.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static org.junit.jupiter.api.Assertions.*;

final class JobJournalTest {
    @TempDir Path world;
    @Test void complete64JobJournalRetainsTheExactLegacyWireBytes() throws Exception {
        var scene=new JobLifecycleStoreTest.Scene();
        for(int n=0;n<64;n++)scene.report(scene.assign(scene.create(1)),1,ExecutionStatus.SUCCEEDED);
        Snapshot source=scene.memory.state;byte[] encoded=JobJournal.encode(source);
        assertEquals(64,source.jobs().size());assertTrue(encoded.length>StrictJson.MAX_BYTES);
        assertArrayEquals(legacyBytes(source),encoded);
        Path directory=world.resolve(JobJournal.WORLD_RELATIVE_PATH);Files.createDirectories(directory);
        Files.write(directory.resolve("state.jsonl"),encoded);
        try(var journal=JobJournal.open(world,scene.world)){assertFalse(journal.readOnly());assertEquals(source,journal.snapshot());}
        assertArrayEquals(encoded,Files.readAllBytes(directory.resolve("state.jsonl")));
    }
    @Test void recordScopedStrictUtf8PreservesMultibyteEvidenceWithoutChangingTheJournal() throws Exception {
        var scene=new JobLifecycleStoreTest.Scene();Snapshot source=unicodeSnapshot(scene);byte[] encoded=JobJournal.encode(source);
        assertArrayEquals(legacyBytes(source),encoded);
        Path directory=world.resolve(JobJournal.WORLD_RELATIVE_PATH);Files.createDirectories(directory);Files.write(directory.resolve("state.jsonl"),encoded);
        try(var journal=JobJournal.open(world,scene.world)) {
            assertFalse(journal.readOnly());assertEquals(source,journal.snapshot());
            assertEquals("résumé λ 🚀",journal.snapshot().jobs().getFirst().current().terminal().completion().id());
        }
        assertArrayEquals(encoded,Files.readAllBytes(directory.resolve("state.jsonl")));
    }
    @ParameterizedTest @ValueSource(strings={"MISSING_FINAL_LF","EMPTY_RECORD","COUNT","MALFORMED_UTF8","SPLIT_UTF8"})
    void framedRecordCorruptionWithAValidDigestStillFencesAndPreservesBothVersions(String mutation) throws Exception {
        var scene=new JobLifecycleStoreTest.Scene();Snapshot source=unicodeSnapshot(scene);byte[] original=JobJournal.encode(source);
        int first=0;while(original[first]!='\n')first++;
        var header=StrictJson.object(new String(original,0,first,StandardCharsets.UTF_8));byte[] payload=Arrays.copyOfRange(original,first+1,original.length);
        long records=1;
        switch(mutation) {
            case "MISSING_FINAL_LF" -> payload=Arrays.copyOf(payload,payload.length-1);
            case "EMPTY_RECORD" -> {payload=Arrays.copyOf(payload,payload.length+1);payload[payload.length-1]='\n';records=2;}
            case "COUNT" -> records=0;
            default -> {
                int at=-1;for(int n=0;n<payload.length-1;n++)if(payload[n]==(byte)0xc3 && payload[n+1]==(byte)0xa9){at=n;break;}
                assertTrue(at>=0,"Fixture must contain a real two-byte UTF-8 character");
                if(mutation.equals("MALFORMED_UTF8"))payload[at+1]=0x28;
                else {payload=ByteBuffer.allocate(payload.length+1).put(payload,0,at+1).put((byte)'\n').put(payload,at+1,payload.length-at-1).array();records=2;}
            }
        }
        header.put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)));header.put("records",records);
        byte[] prefix=(StrictJson.canonical(header)+"\n").getBytes(StandardCharsets.UTF_8);
        byte[] broken=ByteBuffer.allocate(prefix.length+payload.length).put(prefix).put(payload).array();
        Path directory=world.resolve(JobJournal.WORLD_RELATIVE_PATH);Files.createDirectories(directory);
        Files.write(directory.resolve("state.jsonl"),broken);Files.write(directory.resolve("state.prev.jsonl"),original);
        try(var journal=JobJournal.open(world,scene.world)){assertTrue(journal.readOnly());assertEquals(source,journal.snapshot());}
        assertArrayEquals(broken,Files.readAllBytes(directory.resolve("state.jsonl")));assertArrayEquals(original,Files.readAllBytes(directory.resolve("state.prev.jsonl")));
    }
    private static Snapshot unicodeSnapshot(JobLifecycleStoreTest.Scene scene) {
        Job job=scene.assign(scene.create(1));Attempt attempt=job.current();
        var terminal=new Execution(ExecutionStatus.SUCCEEDED,null,1,new EvidenceRef("résumé λ 🚀","crop-delivery:1",scene.owner.scope().domainId().toString()));
        assertTrue(scene.store.recordExecutionResult(job.id(),job.guard(),new Report(attempt.id(),attempt.generation(),1,
                Map.of(Budgets.Kind.CALLS,1L),JobLifecycleStoreTest.receipts(attempt,1),terminal),scene.owner).accepted());
        scene.settle();return scene.memory.state;
    }
    /** Frozen pre-change wire construction is an independent byte-compatibility oracle. */
    private static byte[] legacyBytes(Snapshot snapshot) throws Exception {
        var lines=new ArrayList<String>();for(Job job:snapshot.jobs())lines.add(JobCodec.job(job));
        for(Allocation allocation:snapshot.allocations())lines.add(JobCodec.allocation(allocation));
        String payload=lines.isEmpty()?"":String.join("\n",lines)+"\n";
        long schema=snapshot.jobs().stream().anyMatch(j->j.preparation()!=null)||snapshot.allocations().stream().anyMatch(Allocation::material)?3L:
                snapshot.jobs().stream().anyMatch(JobCodec::resourceForm)?2L:1L;
        String header=StrictJson.canonical(Map.of("schema",schema,"world",snapshot.worldId().toString(),"revision",snapshot.revision(),
                "records",(long)lines.size(),"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)))));
        return (header+"\n"+payload).getBytes(StandardCharsets.UTF_8);
    }
    @Test void boundJobsRoundTripWithoutDuplicatingRequestOutcomeOrArtifactSemantics() throws Exception {
        var scene=new JobLifecycleStoreTest.Scene();
        var job=scene.assign(scene.create(5));
        try(var journal=JobJournal.open(world,scene.world)){
            var ready=new Snapshot(scene.world,1,List.of(new Job(job.id(),job.submissionId(),1,job.request(),null,List.of(),List.of(),
                    State.READY,null,null,job.allowance(),Map.of(),0,List.of(),job.events().subList(0,1))),List.of());
            journal.replace(journal.snapshot(),ready);
            journal.replace(ready,scene.memory.state);
            assertEquals(scene.memory.state,journal.snapshot());
            assertThrows(IOException.class,()->JobJournal.open(world,scene.world));
        }
        try(var opened=JobJournal.open(world,scene.world)){
            assertFalse(opened.readOnly());assertEquals(scene.memory.state,opened.snapshot());
            assertEquals(job.current().executions(),opened.snapshot().jobs().getFirst().current().executions());
        }
    }
    @ParameterizedTest @EnumSource(JobJournal.Point.class)
    void interruptionAtEveryPublicationBoundaryKeepsAnEntireOldOrNewSnapshot(JobJournal.Point point) throws Exception {
        var scene=new JobLifecycleStoreTest.Scene();var job=scene.create(5);Snapshot old=scene.memory.state;
        scene.assign(job);Snapshot next=scene.memory.state;
        try(var journal=JobJournal.open(world,scene.world)){journal.replace(journal.snapshot(),old);}
        byte[] original=Files.readAllBytes(world.resolve(JobJournal.WORLD_RELATIVE_PATH).resolve("state.jsonl"));
        try(var journal=JobJournal.open(world,scene.world,p->{if(p==point)throw new IOException("injected "+p);})){
            assertThrows(IOException.class,()->journal.replace(old,next));assertTrue(journal.readOnly());
            assertEquals(old,journal.snapshot());
        }
        try(var reload=JobJournal.open(world,scene.world)){
            Snapshot recovered=reload.snapshot();assertTrue(recovered.equals(old)||recovered.equals(next));
            if(recovered.equals(next))assertEquals(JobLifecycleStoreTest.REF,recovered.jobs().getFirst().current().executions().getFirst().artifact());
            else assertArrayEquals(original,Files.readAllBytes(world.resolve(JobJournal.WORLD_RELATIVE_PATH).resolve("state.jsonl")));
        }
    }
    @Test void futureCurrentSchemaRemainsUntouchedEvenWithAValidBackup() throws Exception {
        var scene=new JobLifecycleStoreTest.Scene();scene.create(1);Snapshot old=scene.memory.state;
        try(var journal=JobJournal.open(world,scene.world)){journal.replace(journal.snapshot(),old);}
        Path directory=world.resolve(JobJournal.WORLD_RELATIVE_PATH),current=directory.resolve("state.jsonl");
        Files.copy(current,directory.resolve("state.prev.jsonl"),StandardCopyOption.REPLACE_EXISTING);
        String text=Files.readString(current).replaceFirst("\"schema\":1","\"schema\":99");Files.writeString(current,text);
        byte[] future=Files.readAllBytes(current);
        try(var journal=JobJournal.open(world,scene.world)){
            assertTrue(journal.readOnly());assertEquals(old,journal.snapshot());
            assertThrows(IOException.class,()->journal.replace(old,new Snapshot(scene.world,old.revision()+1,old.jobs(),old.allocations())));
        }
        assertArrayEquals(future,Files.readAllBytes(current));
    }
    @Test void corruptCurrentRecoversReadOnlyAndPreservesBothOriginals() throws Exception {
        var scene=new JobLifecycleStoreTest.Scene();scene.create(1);Snapshot old=scene.memory.state;
        try(var journal=JobJournal.open(world,scene.world)){journal.replace(journal.snapshot(),old);}
        Path directory=world.resolve(JobJournal.WORLD_RELATIVE_PATH),current=directory.resolve("state.jsonl"),previous=directory.resolve("state.prev.jsonl");
        Files.copy(current,previous,StandardCopyOption.REPLACE_EXISTING);byte[] backup=Files.readAllBytes(previous);
        Files.writeString(current,"{\"schema\":1,\"world\":\"wrong\"}\n");byte[] broken=Files.readAllBytes(current);
        try(var journal=JobJournal.open(world,scene.world)){assertTrue(journal.readOnly());assertEquals(old,journal.snapshot());}
        assertArrayEquals(backup,Files.readAllBytes(previous));assertArrayEquals(broken,Files.readAllBytes(current));
    }
    @Test void supportedImportMigratesOnceWithStableIdsAccountingAndRecoverableOriginal() throws Exception {
        var scene=new JobLifecycleStoreTest.Scene();scene.create(1);Snapshot source=scene.memory.state;
        Path directory=world.resolve(JobJournal.WORLD_RELATIVE_PATH);Files.createDirectories(directory);
        byte[] original=JobJournal.encodeImport(source);Files.write(directory.resolve("state.jsonl"),original);
        Snapshot imported;
        try(var journal=JobJournal.open(world,scene.world)){
            imported=journal.snapshot();assertFalse(journal.readOnly());assertEquals(source.jobs(),imported.jobs());
            assertEquals(source.revision()+1,imported.revision());
        }
        byte[] published=Files.readAllBytes(directory.resolve("state.jsonl"));
        try(var second=JobJournal.open(world,scene.world)){assertEquals(imported,second.snapshot());}
        assertArrayEquals(published,Files.readAllBytes(directory.resolve("state.jsonl")));
        assertArrayEquals(original,Files.readAllBytes(directory.resolve("state.schema0.original.jsonl")));
        assertEquals(1,imported.jobs().size());
    }
    @Test void unknownOrAmbiguousLegacyTasksAreNeverAdmittedAsJobs() throws Exception {
        Path legacy=world.resolve("data/ai-villages/state.json");Files.createDirectories(legacy.getParent());
        byte[] original="{\"schemaVersion\":2,\"tasks\":[{\"status\":\"DONE\",\"bread\":12}]}".getBytes(StandardCharsets.UTF_8);
        Files.write(legacy,original);UUID id=UUID.randomUUID();
        try(var journal=JobJournal.open(world,id)){assertEquals(List.of(),journal.snapshot().jobs());}
        assertArrayEquals(original,Files.readAllBytes(legacy));
        Path current=world.resolve(JobJournal.WORLD_RELATIVE_PATH).resolve("state.jsonl");
        byte[] unknown="{\"schema\":999,\"unknown\":\"private future references\"}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(current,unknown);
        try(var journal=JobJournal.open(world,id)){assertTrue(journal.readOnly());assertTrue(journal.snapshot().jobs().isEmpty());}
        assertArrayEquals(unknown,Files.readAllBytes(current));
    }
    @Test void sparseOversizedInputIsBoundedAndInactiveWithoutTruncatingEvidence() throws Exception {
        Path directory=world.resolve(JobJournal.WORLD_RELATIVE_PATH);Files.createDirectories(directory);
        Path current=directory.resolve("state.jsonl");
        try(var file=FileChannel.open(current,StandardOpenOption.CREATE,StandardOpenOption.WRITE)){
            file.position(4L*JobJournal.MAX_BYTES);file.write(ByteBuffer.wrap(new byte[]{1}));
        }
        long bytes=Files.size(current);
        try(var journal=JobJournal.open(world,UUID.randomUUID())){assertTrue(journal.readOnly());assertTrue(journal.snapshot().jobs().isEmpty());}
        assertEquals(bytes,Files.size(current));
    }
    @Test void strictJobRecordsRejectUnknownFieldsAndUnattributedSuccess() throws Exception {
        var scene=new JobLifecycleStoreTest.Scene();Job job=scene.create(1);
        String encoded=JobCodec.job(job);
        var row=StrictJson.object(encoded);row.put("inventedAuthority",true);
        assertThrows(StrictJson.Invalid.class,()->JobCodec.job(row));
        var forged=new Job(job.id(),job.submissionId(),job.revision(),job.request(),null,List.of(),List.of(),
                State.SUCCEEDED,null,null,job.allowance(),Map.of(),0,List.of(),job.events());
        assertThrows(IllegalArgumentException.class,()->JobLifecycleStore.validateSnapshot(
                new Snapshot(scene.world,1,List.of(forged),List.of()),Settings.defaults()));
        var changed = StrictJson.object(encoded);
        @SuppressWarnings("unchecked") var bound = (Map<String,Object>) changed.get("request");
        @SuppressWarnings("unchecked") var request = (Map<String,Object>) bound.get("request");
        request.put("schema",9L);
        assertThrows(StrictJson.Invalid.class,()->JobCodec.job(changed));
    }
    @Test void schemaAndDependencyReferencesRoundTripWithoutExecutingOrCallingProviders() throws Exception {
        var scene=new JobLifecycleStoreTest.Scene();Job dependency=scene.create(1);scene.create(1,List.of(dependency.id()));
        Snapshot state=scene.memory.state;
        // Import allows testing the reader of one complete multi-record commit without
        // manufacturing an invalid skipped publication revision.
        Path directory=world.resolve(JobJournal.WORLD_RELATIVE_PATH);Files.createDirectories(directory);
        Files.write(directory.resolve("state.jsonl"),JobJournal.encodeImport(state));
        try(var journal=JobJournal.open(world,scene.world)){
            assertEquals(state.jobs(),journal.snapshot().jobs());
            assertEquals(2,journal.snapshot().jobs().size());assertEquals(0,scene.notices.size());
        }
    }
    @Test void forgedAllocationCannotSpendMoreThanActualAttributableOutput() throws Exception {
        var scene=new JobLifecycleStoreTest.Scene();
        Job producer=scene.report(scene.assign(scene.create(5)),5,ExecutionStatus.SUCCEEDED);
        Job demand=scene.create(6);var attempt=producer.current();
        var deposit=attempt.receipts().stream().filter(r->r.stage()==CropDelivery.Stage.DEPOSIT).findFirst().orElseThrow();
        var forged=new Snapshot(scene.world,scene.memory.state.revision()+1,scene.memory.state.jobs(),
                List.of(new Allocation(UUID.randomUUID(),producer.id(),attempt.id(),deposit.receiptId(),demand.id(),6)));
        assertThrows(IllegalArgumentException.class,()->JobLifecycleStore.validateSnapshot(forged,Settings.defaults()));
        try(var journal=JobJournal.open(world,scene.world)) {
            var before=journal.snapshot();
            var bad=new Snapshot(scene.world,1,forged.jobs(),forged.allocations());
            assertThrows(IllegalArgumentException.class,()->journal.replace(before,bad));
            assertEquals(before,journal.snapshot());
        }
    }

}
