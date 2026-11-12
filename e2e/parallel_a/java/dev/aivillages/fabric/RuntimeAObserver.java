package dev.aivillages.fabric;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Test-only event-bracketed tick observation, bounded to 20000 samples/process. */
public final class RuntimeAObserver implements ModInitializer {
    private static final Gson JSON=new Gson();
    private final List<Map<String,Long>> samples = new ArrayList<>();
    private final Path target = Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR"), "server", "runtime-a-ticks.json");
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r,"runtime-a-measurement-writer"); t.setDaemon(true); return t;
    });
    private long start, tick;
    private volatile Map<String,Object> activitySnapshot;
    private volatile List<UUID> watchedRoots=List.of();
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
                        for (var player : s.getPlayerList().getPlayers().stream().limit(2).toList()) {
                            var page=kernel.activities(player,0,4);
                            var watched=new ArrayList<Object>();
                            for(var root:watchedRoots)try {watched.add(kernel.activity(player,root));}
                            catch(IllegalArgumentException|SecurityException denied) { /* original public scoped read refuses a foreign root */ }
                            players.put(player.getGameProfile().name(),Map.of("activities",page.activities(),
                                    "offset",page.offset(),"nextOffset",page.nextOffset(),"more",page.more(),
                                    "watchedActivities",List.copyOf(watched)));
                        }
                        activitySnapshot = Map.of("schema",1,"time",System.currentTimeMillis(),
                                "players",Map.copyOf(players),"counters",kernel.recoveryOwner().counters());
                    }
                } catch (IllegalStateException starting) { /* native startup is not a fabricated activity view */ }
            }
        });
        writer.scheduleWithFixedDelay(this::flush,1,1,TimeUnit.SECONDS);
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> { writer.shutdown(); flush(); });
    }
    private void flush() {
        try {
            var watch=target.resolveSibling("runtime-a-watch.json");
            List<UUID> requested=List.of();
            if(Files.exists(watch)) {
                if(Files.size(watch)>1024)throw new IllegalArgumentException("Activity watch byte bound");
                requested=watch(Files.readString(watch));
            }
            watchedRoots=requested;
            List<Map<String,Long>> copy;
            synchronized(samples) { copy = List.copyOf(samples); }
            var body = Map.of("schema",1,"pid",ProcessHandle.current().pid(),"samples",copy,
                    "metric","START_SERVER_TICK to END_SERVER_TICK; excludes tick sleep; test observer overhead included");
            Files.createDirectories(target.getParent());
            var temp = target.resolveSibling(target.getFileName()+".next");
            Files.writeString(temp,JSON.toJson(body));
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            var activities = activitySnapshot;
            if (activities != null) {
                var activityTarget = target.resolveSibling("runtime-a-activities.json");
                var staged = activityTarget.resolveSibling("runtime-a-activities.next");
                Files.writeString(staged,JSON.toJson(activities));
                Files.move(staged,activityTarget,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }
        } catch(Exception e) { watchedRoots=List.of();System.err.println("Runtime A observation write failed: "+e); }
    }
    /** Bounded test observation only. Each requested view still uses the public principal-scoped query. */
    static List<UUID> watch(String raw) {
        if(raw.length()>1024)throw new IllegalArgumentException("Activity watch bound");
        var value=JsonParser.parseString(raw).getAsJsonObject();
        if(!value.keySet().equals(Set.of("schema","roots")) || !value.get("schema").toString().equals("1"))
            throw new IllegalArgumentException("Activity watch schema");
        var roots=value.getAsJsonArray("roots");
        if(roots.size()<1||roots.size()>4)throw new IllegalArgumentException("Activity watch root bound");
        var result=new LinkedHashSet<UUID>();
        for(var root:roots) {
            if(!root.isJsonPrimitive()||!root.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Activity watch UUID");
            String text=root.getAsString();UUID id=UUID.fromString(text);
            if(!id.toString().equals(text)||!result.add(id))throw new IllegalArgumentException("Activity watch duplicate/noncanonical UUID");
        }
        return List.copyOf(result);
    }
    public static void main(String[] args) {
        String root="e993b733-4d93-4303-83c6-1fedc4dab50b";
        if(!watch("{\"schema\":1,\"roots\":[\""+root+"\"]}").equals(List.of(UUID.fromString(root))))
            throw new AssertionError("Explicit original root observation changed");
        String four="{\"schema\":1,\"roots\":[\"00000000-0000-0000-0000-000000000001\",\"00000000-0000-0000-0000-000000000002\",\"00000000-0000-0000-0000-000000000003\",\""+root+"\"]}";
        if(watch(four).size()!=4)throw new AssertionError("Original four-root observation bound changed");
        for(String raw:List.of("{\"schema\":1,\"roots\":[]}","{\"schema\":2,\"roots\":[\""+root+"\"]}",
                "{\"schema\":1,\"roots\":[\""+root+"\",\""+root+"\"]}",
                "{\"schema\":1,\"roots\":[\""+root+"\"],\"authority\":true}",
                four.replace("]}",",\"00000000-0000-0000-0000-000000000005\"]}"))) {
            try {watch(raw);throw new AssertionError("Invalid watch accepted");}catch(IllegalArgumentException expected){}
        }
        System.out.println("A01_EXPLICIT_PRIVATE_ACTIVITY_WATCH_SELF_TEST_PASS bounded-four original-root no-mutation");
    }
}
