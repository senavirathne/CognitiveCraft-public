package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Jobs.*;
import static org.junit.jupiter.api.Assertions.*;

final class JobJournalTest {
    @TempDir Path world;
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
}
