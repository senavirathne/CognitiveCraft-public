package dev.aivillages.fabric;

import com.google.gson.Gson;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Test-only private snapshots; no entity/world mutation or lifecycle fact injection. */
public final class RuntimeALifecycleProbe implements ModInitializer {
    private static final Gson JSON=new Gson();
    private volatile Map<String,Object> pending;
    private final ScheduledExecutorService writer=Executors.newSingleThreadScheduledExecutor(r->{
        var t=new Thread(r,"runtime-a-lifecycle-observer");t.setDaemon(true);return t;
    });
    private long ticks;
    public void onInitialize() {
        if(!"true".equalsIgnoreCase(System.getenv("COGNITIVECRAFT_LIFECYCLE_TRACE"))) {
            writer.shutdown();return;
        }
        ServerTickEvents.END_SERVER_TICK.register(server->{
            if(++ticks%5!=0)return;
            var kernel=AiVillages.kernel();if(kernel==null)return;
            try {
                var players=new LinkedHashMap<String,Object>();
                for(var player:server.getPlayerList().getPlayers().stream().limit(2).toList()) {
                    var citizens=new LinkedHashMap<String,Object>();
                    for(var address:kernel.citizens(player).citizens()) {
                        var id=address.actor().citizenId();
                        citizens.put(id.toString(),Map.of("identity",kernel.citizen(player,id),
                                "trace",kernel.lifecycleDiagnostics(player,id)));
                    }
                    players.put(player.getGameProfile().name(),Map.copyOf(citizens));
                }
                pending=Map.of("schema",1,"pid",ProcessHandle.current().pid(),"time",System.currentTimeMillis(),
                        "tick",server.overworld().getGameTime(),"phase","END_SERVER_TICK","players",Map.copyOf(players));
            }catch(IllegalStateException opening){ /* No startup observation is fabricated. */ }
        });
        writer.scheduleWithFixedDelay(this::flush,1,1,TimeUnit.SECONDS);
        ServerLifecycleEvents.SERVER_STOPPED.register(s->{writer.shutdown();flush();});
    }
    private void flush() {
        var value=pending;if(value==null)return;
        try {
            var target=Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR"),"server","lifecycle-snapshot.json");
            var staged=target.resolveSibling("lifecycle-snapshot.next");
            Files.createDirectories(target.getParent());Files.writeString(staged,JSON.toJson(value));
            Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception failure){System.err.println("Runtime A lifecycle observation write failed: "+failure);}
    }
}
