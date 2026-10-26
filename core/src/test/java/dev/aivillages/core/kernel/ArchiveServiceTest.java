package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.runtime.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

final class ArchiveServiceTest {
    @TempDir Path world;
    @Test void pendingWorkAppliesBackpressureAndShutdownStillDrainsIssuedWork()throws Exception {
        try(var writer=ArchiveWriter.open(world,UUID.randomUUID(),PersistencePolicy.defaults());var worker=Executors.newSingleThreadExecutor()) {
            var service=new ArchiveService(writer,worker);var started=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
            var first=service.offer(w->{started.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new java.io.IOException(e);}return calls.incrementAndGet();}).orElseThrow();
            assertTrue(started.await(5,TimeUnit.SECONDS));assertTrue(service.pending());assertTrue(service.offer(w->calls.incrementAndGet()).isEmpty());
            service.stopAdmissions();var drained=service.drained();assertFalse(drained.isDone());release.countDown();assertEquals(1,first.get(5,TimeUnit.SECONDS));drained.get(5,TimeUnit.SECONDS);
            assertEquals(1,calls.get());assertTrue(service.offer(w->2).isEmpty());
        }
    }
    @Test void rejectedAndInlineExecutorsCannotIssueStorageWork()throws Exception {
        try(var writer=ArchiveWriter.open(world,UUID.randomUUID(),PersistencePolicy.defaults())) {
            var calls=new AtomicInteger();var rejected=new ArchiveService(writer,r->{throw new RejectedExecutionException("fixture");});
            assertTrue(rejected.offer(w->calls.incrementAndGet()).isEmpty());assertFalse(rejected.pending());
            var inline=new ArchiveService(writer,Runnable::run);var future=inline.offer(w->calls.incrementAndGet()).orElseThrow();
            assertThrows(ExecutionException.class,()->future.get(1,TimeUnit.SECONDS));assertFalse(inline.pending());assertEquals(0,calls.get());assertEquals(0,writer.snapshot().revision());
        }
    }
}
