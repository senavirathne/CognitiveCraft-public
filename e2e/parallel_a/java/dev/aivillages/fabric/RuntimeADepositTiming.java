package dev.aivillages.fabric;

import com.google.gson.Gson;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Registered last, so the A05 bracket includes preceding kernel and observation callbacks. */
public final class RuntimeADepositTiming implements ModInitializer {
    private final List<Map<String,Long>> samples=new ArrayList<>();
    private final UUID process=UUID.randomUUID();
    private long began;
    public void onInitialize() {
        if(!"true".equalsIgnoreCase(System.getenv("COGNITIVECRAFT_A05_PROBE")))return;
        Path target=Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR"),"server/a05-tick-samples.json");
        var writer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"a05-raw-tick-writer");t.setDaemon(true);return t;});
        Runnable flush=()->{
            try {
                List<Map<String,Long>> captured;synchronized(samples){captured=List.copyOf(samples);}
                var staged=target.resolveSibling("a05-tick-samples.next");
                Files.writeString(staged,new Gson().toJson(Map.of("process",process,"pid",ProcessHandle.current().pid(),"time",System.currentTimeMillis(),"samples",captured,
                        "metric","START_SERVER_TICK through last A05 END callback; tick sleep excluded; preceding kernel/observer callbacks included")));
                Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }catch(Exception error){throw new IllegalStateException("A05 raw timing write",error);}
        };
        ServerTickEvents.START_SERVER_TICK.register(server->began=System.nanoTime());
        ServerTickEvents.END_SERVER_TICK.register(server->{long nanos=System.nanoTime()-began;
            synchronized(samples){if(samples.size()<20000)samples.add(Map.of("gameTick",server.overworld().getGameTime(),"time",System.currentTimeMillis(),"nanos",nanos));}
        });
        writer.scheduleWithFixedDelay(flush,1,1,TimeUnit.SECONDS);
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{writer.shutdown();flush.run();});
    }
}
