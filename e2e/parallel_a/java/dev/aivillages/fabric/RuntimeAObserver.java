package dev.aivillages.fabric;

import com.google.gson.Gson;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Test-only event-bracketed tick observation, bounded to 20000 samples/process. */
public final class RuntimeAObserver implements ModInitializer {
    private final List<Map<String,Long>> samples = new ArrayList<>();
    private final Path target = Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR"), "server", "runtime-a-ticks.json");
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r,"runtime-a-measurement-writer"); t.setDaemon(true); return t;
    });
    private long start, tick;
    private volatile String activitySnapshot;
    public void onInitialize() {
        ServerTickEvents.START_SERVER_TICK.register(s -> start = System.nanoTime());
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            long elapsed = System.nanoTime() - start;
            synchronized(samples) { if(samples.size() < 20000) samples.add(Map.of(
                    "tick", ++tick, "time", System.currentTimeMillis(), "nanos", elapsed)); }
            if (tick % 5 == 0 && "true".equalsIgnoreCase(System.getenv("COGNITIVECRAFT_ACTIVITY_VIEW"))) {
                try {
                    var kernel = AiVillages.kernel();
                    if (kernel != null) {
                        var players = new LinkedHashMap<String,Object>();
                        for (var player : s.getPlayerList().getPlayers().stream().limit(2).toList())
                            players.put(player.getGameProfile().name(), kernel.activities(player,0,4));
                        activitySnapshot = new Gson().toJson(Map.of("schema",1,"time",System.currentTimeMillis(),
                                "players",players,"counters",kernel.recoveryOwner().counters()));
                    }
                } catch (IllegalStateException starting) { /* native startup is not a fabricated activity view */ }
            }
        });
        writer.scheduleWithFixedDelay(this::flush,1,1,TimeUnit.SECONDS);
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> { writer.shutdown(); flush(); });
    }
    private void flush() {
        try {
            List<Map<String,Long>> copy;
            synchronized(samples) { copy = List.copyOf(samples); }
            var body = Map.of("schema",1,"pid",ProcessHandle.current().pid(),"samples",copy,
                    "metric","START_SERVER_TICK to END_SERVER_TICK; excludes tick sleep; test observer overhead included");
            Files.createDirectories(target.getParent());
            var temp = target.resolveSibling(target.getFileName()+".next");
            Files.writeString(temp,new Gson().toJson(body));
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            String activities = activitySnapshot;
            if (activities != null) {
                var activityTarget = target.resolveSibling("runtime-a-activities.json");
                var staged = activityTarget.resolveSibling("runtime-a-activities.next");
                Files.writeString(staged,activities);
                Files.move(staged,activityTarget,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }
        } catch(Exception e) { System.err.println("Runtime A observation write failed: "+e); }
    }
}
