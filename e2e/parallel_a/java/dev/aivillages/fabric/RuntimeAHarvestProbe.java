package dev.aivillages.fabric;

import com.google.gson.*;
import dev.aivillages.fabric.runtime.evidence.FabricHarvestProtocol;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.server.MinecraftServer;

/** Test-only observation and actual vanilla save/cut control. It cannot mint receipts or execute harvest. */
public final class RuntimeAHarvestProbe implements ModInitializer {
    private final Path root=Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR"));
    private final Gson json=new Gson();private final UUID process=UUID.randomUUID();
    private final ScheduledExecutorService writer=Executors.newSingleThreadScheduledExecutor();
    private final AtomicReference<JsonObject> request=new AtomicReference<>();
    private final AtomicReference<Object> pending=new AtomicReference<>();
    private String seen="";private boolean ready;
    @Override public void onInitialize() {
        if(!"true".equalsIgnoreCase(System.getenv("COGNITIVECRAFT_A06_HARVEST_PROBE"))){writer.shutdown();return;}
        writer.scheduleWithFixedDelay(this::poll,0,100,TimeUnit.MILLISECONDS);
        ServerTickEvents.END_SERVER_TICK.register(server->{
            JsonObject active=null;
            try {
                var kernel=AiVillages.kernel();if(kernel==null)return;var protocol=kernel.harvestEvidenceOwner();if(protocol==null)return;
                var evidence=protocol.evidence();
                if(!ready){ready=true;var body=Map.of("process",process,"pid",ProcessHandle.current().pid(),"world",evidence.snapshot().world());writer.execute(()->write("harvest-ready.json",body));}
                var classified=new LinkedHashMap<String,String>();for(var entry:evidence.snapshot().entries())classified.put(entry.token(),protocol.reconcile(entry.intent().key()).result().name());
                pending.set(Map.of("process",process,"runtime",kernel.runtimeStorageView(),"time",System.currentTimeMillis(),"gameTick",server.overworld().getGameTime(),
                        "journal",evidence.snapshot(),"pending",evidence.pending(),"fenced",evidence.fenced(),"reconciliation",classified,"protectedJobs",evidence.protectedJobs(),"metrics",protocol.metrics()));
                var command=request.getAndSet(null);if(command==null)return;active=command;
                String id=command.get("id").getAsString(),op=command.get("op").getAsString();
                if(op.equals("arm")) {
                    var point=FabricHarvestProtocol.Point.valueOf(command.get("point").getAsString());String token=UUID.fromString(command.get("token").getAsString()).toString();
                    boolean save=command.get("save").getAsBoolean();
                    protocol.observe(actual->{if(actual==point)barrier(server,protocol,actual,token,save);});
                    reply(id,Map.of("status","PASS","point",point,"token",token),null);return;
                }
                throw new IllegalArgumentException("Unknown A06 harvest observation operation");
            }catch(IllegalStateException starting){if(active!=null)reply(active.get("id").getAsString(),null,starting);}
            catch(Throwable error){if(active!=null)reply(active.get("id").getAsString(),null,error);else System.err.println("A06 harvest observer: "+error);}
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{writer.shutdown();flush();});
    }
    private void barrier(MinecraftServer server,FabricHarvestProtocol protocol,FabricHarvestProtocol.Point point,String token,boolean save) {
        var commands=new ArrayList<Map<String,Object>>();
        try {
            int freeze=server.getCommands().getDispatcher().execute("tick freeze",server.createCommandSourceStack());
            commands.add(Map.of("command","tick freeze","result",freeze));
            if(save) {
                int saved=server.getCommands().getDispatcher().execute("save-all flush",server.createCommandSourceStack());
                if(saved!=1)throw new IllegalStateException("Native save-all flush did not acknowledge");commands.add(Map.of("command","save-all flush","result",saved));
            }
        }catch(com.mojang.brigadier.exceptions.CommandSyntaxException failure){throw new IllegalStateException("Native harvest cut command refused",failure);}
        // Save completion and the exact original Job state are recorded before the driver kills this JVM.
        write("harvest-cut-reached.json",Map.of("process",process,"pid",ProcessHandle.current().pid(),"point",point,"token",token,"time",System.currentTimeMillis(),
                "commands",commands,"journal",protocol.evidence().snapshot(),"runtime",AiVillages.kernel().runtimeStorageView(),"metrics",protocol.metrics()));
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        while(System.nanoTime()<until)try{Thread.sleep(10);}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IllegalStateException(stopped);}
        throw new IllegalStateException("Intended harvest process kill was not witnessed");
    }
    private void poll() {
        flush();try {
            Path path=root.resolve("server/harvest-request.json");if(!Files.isRegularFile(path) || Files.size(path)>4096)return;
            var command=JsonParser.parseString(Files.readString(path)).getAsJsonObject();String id=command.get("id").getAsString();
            if(id.equals(seen) || !process.toString().equals(command.get("process").getAsString()))return;
            seen=id;request.compareAndSet(null,command);
        }catch(Exception error){System.err.println("A06 harvest request read: "+error);}
    }
    private void flush(){Object value=pending.getAndSet(null);if(value!=null)write("harvest-snapshot.json",value);}
    private void reply(String id,Object result,Throwable error){writer.execute(()->write("harvest-response.json",Map.of("process",process,"id",id,"status",error==null?"PASS":"FAIL","value",error==null?result:Map.of("failure",error.toString()))));}
    private void write(String name,Object value) {
        try {
            Path target=root.resolve("server/"+name),staged=target.resolveSibling(name+".next");Files.createDirectories(target.getParent());
            Files.writeString(staged,json.toJson(value),StandardCharsets.UTF_8);Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception error){throw new IllegalStateException("A06 harvest evidence write",error);}
    }
}
