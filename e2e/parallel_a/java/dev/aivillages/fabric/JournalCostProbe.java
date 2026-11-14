package dev.aivillages.fabric;

import dev.aivillages.core.kernel.JobJournal;
import dev.aivillages.core.kernel.Jobs;
import java.nio.file.*;
import java.util.*;

/** Baseline metadata-only force/rename timing. Never presented as world-save latency. */
public final class JournalCostProbe {
    public static void main(String[] args) throws Exception {
        var durations = new ArrayList<Long>();
        var world = Path.of(args[0]);
        try(var journal = JobJournal.open(world, UUID.randomUUID())) {
            for(int n=0;n<100;n++) {
                var old = journal.snapshot();
                var next = new Jobs.Snapshot(old.worldId(),old.revision()+1,List.of(),List.of());
                long start=System.nanoTime(); journal.replace(old,next); durations.add(System.nanoTime()-start);
            }
        }
        System.out.println("PARALLEL_A_JOURNAL_COST_NANOS="+durations);
        Files.writeString(world.resolve("journal-cost.json"),"{\"schema\":1,\"metric\":\"empty JobJournal.replace including file/directory force and atomic rename\",\"nanos\":"+durations+"}\n");
    }
}
