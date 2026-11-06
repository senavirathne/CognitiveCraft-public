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
                if(!ready){
                    ready=true;
                    var body=Map.of("process",process,"pid",ProcessHandle.current().pid(),"world",evidence.snapshot().world(),
                            "availableProcessors",Runtime.getRuntime().availableProcessors(),
                            "inputArguments",java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
                    writer.execute(()->write("harvest-ready.json",body));
                }
                var stageViews=stages(kernel);
                pending.set(Map.ofEntries(Map.entry("process",process),Map.entry("runtime",kernel.runtimeStorageView()),Map.entry("time",System.currentTimeMillis()),Map.entry("gameTick",server.overworld().getGameTime()),
                        Map.entry("journal",evidence.snapshot()),Map.entry("pending",evidence.pending()),Map.entry("fenced",evidence.fenced()),Map.entry("reconciliation",stageViews.get("HARVEST").get("reconciliation")),Map.entry("protectedJobs",evidence.protectedJobs()),Map.entry("metrics",stageViews.get("HARVEST").get("metrics")),Map.entry("membershipBirths",protocol.membershipBirths()),Map.entry("stages",stageViews)));
                var command=request.getAndSet(null);if(command==null)return;active=command;
                String id=command.get("id").getAsString(),op=command.get("op").getAsString();
                if(op.equals("storage-contracts")){reply(id,RuntimeAEntityStorageChecks.run(server,root),null);return;}
                if(op.equals("arm")) {
                    String point=command.get("point").getAsString(),stage=command.has("stage")?command.get("stage").getAsString():"HARVEST";
                    String token=UUID.fromString(command.get("token").getAsString()).toString();boolean save=command.get("save").getAsBoolean();
                    switch(stage) {
                        case "HARVEST"->{FabricHarvestProtocol.Point.valueOf(point);protocol.observe(actual->{if(actual.name().equals(point))barrier(server,protocol,stage,point,token,save);});}
                        case "PICKUP"->{var pickup=Objects.requireNonNull(kernel.pickupEvidenceOwner());dev.aivillages.fabric.runtime.evidence.FabricPickupProtocol.Point.valueOf(point);pickup.observe(actual->{if(actual.name().equals(point))barrier(server,protocol,stage,point,token,save);});}
                        case "DEPOSIT"->{var deposit=Objects.requireNonNull(kernel.depositEvidenceOwner());dev.aivillages.fabric.runtime.evidence.FabricDepositProtocol.Point.valueOf(point);deposit.observe(actual->{if(actual.name().equals(point))barrier(server,protocol,stage,point,token,save);});}
                        default->throw new IllegalArgumentException("Unsupported original stage");
                    }
                    reply(id,Map.of("status","PASS","stage",stage,"point",point,"token",token),null);return;
                }
                throw new IllegalArgumentException("Unknown A06 harvest observation operation");
            }catch(IllegalStateException starting){if(active!=null)reply(active.get("id").getAsString(),null,starting);}
            catch(Throwable error){if(active!=null)reply(active.get("id").getAsString(),null,error);else System.err.println("A06 harvest observer: "+error);}
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{writer.shutdown();flush();});
    }
    private void barrier(MinecraftServer server,FabricHarvestProtocol protocol,String stage,String point,String token,boolean save) {
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
        var body=new LinkedHashMap<String,Object>();body.put("process",process);body.put("pid",ProcessHandle.current().pid());body.put("point",point);body.put("stage",stage);body.put("token",token);body.put("time",System.currentTimeMillis());
        body.put("commands",commands);body.put("journal",protocol.evidence().snapshot());body.put("runtime",AiVillages.kernel().runtimeStorageView());body.put("metrics",protocol.metrics());body.put("membershipBirths",protocol.membershipBirths());body.put("stages",stages(AiVillages.kernel()));
        write("harvest-cut-reached.json",body);
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        while(System.nanoTime()<until)try{Thread.sleep(10);}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IllegalStateException(stopped);}
        throw new IllegalStateException("Intended harvest process kill was not witnessed");
    }
    private Map<String,Map<String,Object>> stages(KernelSession kernel) {
        var result=new LinkedHashMap<String,Map<String,Object>>();var h=kernel.harvestEvidenceOwner();var hc=new LinkedHashMap<String,String>();
        for(var e:h.evidence().snapshot().entries())hc.put(e.token(),h.reconcile(e.intent().key()).result().name());
        result.put("HARVEST",Map.of("journal",h.evidence().snapshot(),"pending",h.evidence().pending(),"fenced",h.evidence().fenced(),"reconciliation",hc,"metrics",h.metrics()));
        var p=kernel.pickupEvidenceOwner();if(p!=null) {
            var pc=new LinkedHashMap<String,String>();for(var e:p.evidence().snapshot().entries())pc.put(e.token(),p.evidence().reconcile(e.intent().key()).result().name());
            result.put("PICKUP",Map.of("journal",p.evidence().snapshot(),"pending",p.evidence().pending(),"fenced",p.evidence().fenced(),"reconciliation",pc,
                    "metrics",Map.of("captureNanos",p.captureNanos(),"captureCpuNanos",p.captureCpuNanos(),"capturePhaseNames",p.capturePhaseNames(),
                            "capturePhaseNanos",p.capturePhaseNanos(),"acknowledgementNanos",p.evidence().acknowledgementNanos())));
        }
        var d=kernel.depositEvidenceOwner();if(d!=null) {
            var dc=new LinkedHashMap<String,String>();for(var e:d.evidence().snapshot().entries())dc.put(e.token(),d.reconcile(e.intent().key()).result().name());
            result.put("DEPOSIT",Map.of("journal",d.evidence().snapshot(),"pending",d.evidence().pending(),"fenced",d.evidence().fenced(),"reconciliation",dc,"metrics",d.metrics()));
        }
        return result;
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
