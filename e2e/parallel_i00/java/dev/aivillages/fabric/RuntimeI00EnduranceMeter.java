package dev.aivillages.fabric;

import com.google.gson.Gson;
import dev.aivillages.core.kernel.JobLifecycleStore.PublicationTiming;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Test-only measurement. The production runtime cannot read observer output. */
public final class RuntimeI00EnduranceMeter implements ModInitializer {
    private record Sample(long sequence, long nativeTick, long timeMillis, UUID world, long tickNanos, long captureNanos,
                          int hotJobs, int activeJobs, int hotLeases, int activeLeases, long archiveRevision,
                          long jobHotBytes, long leaseHotBytes, long archiveBytes, int archiveSegments,
                          List<PublicationTiming> jobAcknowledgements) { }
    private record Closure(UUID process, long pid, long startedMillis, long observed, long dropped, String error) { }

    private final UUID process = UUID.randomUUID();
    private final ArrayBlockingQueue<Sample> samples = new ArrayBlockingQueue<>(512);
    private volatile boolean stopping;
    private volatile String failure;
    private long observed, dropped, tickStart, lastAcknowledgedRevision = -1;

    @Override
    public void onInitialize() {
        if (!"true".equals(System.getenv("COGNITIVECRAFT_I00_ENDURANCE_OBSERVER"))) return;
        Path directory = Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR"), "server");
        Thread writer = Thread.ofPlatform().name("i00-endurance-meter").daemon().start(() -> write(directory));
        ServerTickEvents.START_SERVER_TICK.register(server -> tickStart = System.nanoTime());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long startCapture = System.nanoTime();
            var kernel = AiVillages.kernel();
            if (kernel == null || tickStart == 0) return;
            try {
                var view = kernel.runtimeStorageView();
                var jobs = kernel.runtimeJobHistoryStatus();
                var leases = kernel.runtimeLeaseHistoryStatus();
                var storage = kernel.runtimeHistoryStatus();
                if (jobs == null || leases == null || storage.manifest() == null) return;
                var acknowledgements = kernel.jobLifecycleOwner().publicationTimings().stream()
                        .filter(value -> value.revision() > lastAcknowledgedRevision).toList();
                if (!acknowledgements.isEmpty()) lastAcknowledgedRevision = acknowledgements.getLast().revision();
                long captured = System.nanoTime() - startCapture;
                var sample = new Sample(++observed, server.overworld().getGameTime(), System.currentTimeMillis(),
                        view.jobs().worldId(), startCapture - tickStart, captured, jobs.hot(), jobs.active(),
                        leases.hot(), leases.active(), storage.manifest().revision(), jobs.hotBytes(), leases.hotBytes(),
                        leases.archiveBytes(), storage.manifest().segments().size(), acknowledgements);
                if (!samples.offer(sample)) dropped++;
            } catch (IllegalStateException startup) {
                if (observed > 0) failure = startup.toString();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            stopping = true;
            try {
                writer.join(5000);
                if (writer.isAlive()) throw new IllegalStateException("Endurance measurements did not drain");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Endurance measurement shutdown interrupted", interrupted);
            }
        });
    }

    private void write(Path directory) {
        var gson = new Gson();
        try {
            Files.createDirectories(directory);
            String name = "i00-endurance-ticks-" + process;
            try (var output = Files.newBufferedWriter(directory.resolve(name + ".jsonl"), StandardOpenOption.CREATE_NEW)) {
                while (!stopping || !samples.isEmpty()) {
                    var sample = samples.poll(250, TimeUnit.MILLISECONDS);
                    if (sample == null) continue;
                    output.write(gson.toJson(sample));
                    output.newLine();
                    if (sample.sequence() % 20 == 0 || stopping) output.flush();
                }
            }
            var closure = new Closure(process, ProcessHandle.current().pid(), ManagementFactory.getRuntimeMXBean().getStartTime(),
                    observed, dropped, failure);
            Files.writeString(directory.resolve(name + ".json"), gson.toJson(closure), StandardOpenOption.CREATE_NEW);
        } catch (IOException error) {
            failure = error.toString();
            throw new IllegalStateException("Cannot preserve native endurance measurements", error);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failure = interrupted.toString();
            throw new IllegalStateException("Native endurance measurement writer interrupted", interrupted);
        }
    }
}
