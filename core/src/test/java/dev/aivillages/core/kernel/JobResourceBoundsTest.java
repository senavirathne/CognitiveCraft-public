package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static dev.aivillages.core.kernel.Jobs.*;
import static org.junit.jupiter.api.Assertions.*;

/** Cloud evidence at the specified active-record limit; timing is evidence, counts/bytes are the gate. */
class JobResourceBoundsTest {
    @TempDir Path world;
    @Test void boundedGrowthAndReadySlicesAtThirtyTwoActiveJobs() throws Exception {
        var scene=new JobLifecycleStoreTest.Scene();
        for(int n=0;n<32;n++)scene.create(5);
        var before=scene.store.snapshot();
        var excess=scene.store.create(UUID.randomUUID(),UUID.randomUUID(),JobLifecycleStoreTest.request(scene.actor,1),
                scene.owner,JobLifecycleStoreTest.observation(),JobLifecycleStoreTest.limits(11_000),List.of());
        assertEquals(dev.aivillages.core.kernel.Outcomes.Reason.STORAGE_LIMIT_REACHED,excess.reason());
        assertEquals(before,scene.store.snapshot());
        for(var initial:before.jobs())for(int n=0;n<24;n++) {
            var job=scene.store.query(initial.id(),scene.owner);
            var result=scene.store.transition(job.id(),job.guard(),job.state()==State.READY?State.WAITING:State.READY,
                    job.state()==State.READY?dev.aivillages.core.kernel.Outcomes.Reason.RESOURCE_MISSING:null,scene.owner);
            assertTrue(result.accepted());scene.store.tick();
        }
        var measured=scene.store.snapshot();assertEquals(32,measured.jobs().size());
        assertTrue(measured.jobs().stream().allMatch(j->j.events().size()==16));
        long[] samples=new long[1000];
        for(int n=0;n<samples.length;n++) {
            long started=System.nanoTime();var page=scene.store.readyJobs(scene.owner,0);
            samples[n]=System.nanoTime()-started;
            assertEquals(8,page.jobs().size());assertEquals(0,page.inspectedEdges());
        }
        Arrays.sort(samples);long started=System.nanoTime();
        try(var journal=JobJournal.open(world,scene.world)) {
            journal.replace(journal.snapshot(),new Snapshot(scene.world,1,measured.jobs(),List.of()));
        }
        long publicationNanos=System.nanoTime()-started;
        long bytes=Files.size(world.resolve(JobJournal.WORLD_RELATIVE_PATH).resolve("state.jsonl"));
        assertTrue(bytes<=JobJournal.MAX_BYTES);
        var evidence=new LinkedHashMap<String,Object>();
        evidence.put("schema",1L);evidence.put("activeJobs",32L);evidence.put("retainedEventsPerJob",16L);
        evidence.put("readySlice",8L);evidence.put("querySamples",1000L);evidence.put("queryP95Nanos",samples[949]);
        evidence.put("queryMaxNanos",samples[999]);evidence.put("publicationNanos",publicationNanos);
        evidence.put("snapshotBytes",bytes);evidence.put("maximumSnapshotBytes",(long)JobJournal.MAX_BYTES);
        evidence.put("modelCalls",0L);
        Path output=Path.of("build/job-evidence/scale.json");Files.createDirectories(output.getParent());
        Files.writeString(output,StrictJson.canonical(evidence)+"\n");
    }
}
