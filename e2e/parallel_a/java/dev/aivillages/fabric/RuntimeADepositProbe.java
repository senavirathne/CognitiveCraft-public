package dev.aivillages.fabric;

import com.google.gson.*;
import dev.aivillages.core.kernel.runtime.evidence.*;
import dev.aivillages.fabric.runtime.evidence.FabricDepositProtocol;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** A05 independent test oracle. It never issues a deposit, receipt or recovery capability. */
public final class RuntimeADepositProbe implements ModInitializer {
    private final Path root=Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR"));
    private final Gson json=new Gson();private final UUID process=UUID.randomUUID();
    private final ScheduledExecutorService writer=Executors.newSingleThreadScheduledExecutor();
    private final AtomicReference<JsonObject> request=new AtomicReference<>();
    private final AtomicReference<Object> pending=new AtomicReference<>();
    private String seen="";private boolean ready;
    public void onInitialize() {
        if(!"true".equalsIgnoreCase(System.getenv("COGNITIVECRAFT_A05_PROBE"))){writer.shutdown();return;}
        writer.scheduleWithFixedDelay(this::poll,0,100,TimeUnit.MILLISECONDS);
        ServerTickEvents.END_SERVER_TICK.register(server->{
            JsonObject active=null;
            try {
                var kernel=AiVillages.kernel();if(kernel==null)return;
                var protocol=kernel.depositEvidenceOwner();if(protocol==null)return;
                var evidence=protocol.evidence();
                if(!ready){ready=true;var body=Map.of("process",process,"pid",ProcessHandle.current().pid(),"world",evidence.snapshot().world());writer.execute(()->write("deposit-ready.json",body));}
                var classified=new LinkedHashMap<String,String>();
                for(var entry:evidence.snapshot().entries())classified.put(entry.token(),protocol.reconcile(entry.intent().key()).result().name());
                pending.set(Map.of("process",process,"pid",ProcessHandle.current().pid(),"time",System.currentTimeMillis(),"gameTick",server.overworld().getGameTime(),
                        "journal",evidence.snapshot(),"pending",evidence.pending(),"fenced",evidence.fenced(),"reconciliation",classified,"protectedJobs",evidence.protectedJobs(),"metrics",protocol.metrics()));
                var command=request.getAndSet(null);if(command==null)return;active=command;
                String id=command.get("id").getAsString(),op=command.get("op").getAsString();
                if(op.equals("window-marker")){reply(id,Map.of("status","PASS","gameTick",server.overworld().getGameTime(),"time",System.currentTimeMillis()),null);return;}
                if(op.equals("verify")) {
                    String token=command.get("token").getAsString();var entry=evidence.snapshot().entries().stream().filter(e->e.token().equals(token)).findFirst().orElseThrow();
                    var proof=protocol.reconcile(entry.intent().key());var code=protocol.verify(proof);
                    reply(id,Map.of("status","PASS","classification",proof.result(),"code",code),null);return;
                }
                if(op.equals("component-roundtrip")) {
                    var entry=evidence.snapshot().entries().getFirst();String[] c=entry.intent().chest().address().split(",");
                    var pos=new net.minecraft.core.BlockPos(Integer.parseInt(c[0]),Integer.parseInt(c[1]),Integer.parseInt(c[2]));
                    var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)server.overworld().getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4).getBlockEntities().get(pos);
                    var stack=chest.getItem(entry.intent().chestSlot());var type=net.minecraft.core.component.DataComponents.CUSTOM_NAME;
                    var old=stack.get(type);stack.set(type,net.minecraft.network.chat.Component.literal("A05 external component mutation"));
                    if(old==null)stack.remove(type);else stack.set(type,old);chest.setChanged();
                    reply(id,Map.of("status","PASS","count",stack.getCount(),"componentsRestored",old==null?stack.get(type)==null:old.equals(stack.get(type))),null);return;
                }
                if(op.equals("arm")) {
                    var point=FabricDepositProtocol.Point.valueOf(command.get("point").getAsString());String token=UUID.fromString(command.get("token").getAsString()).toString();
                    boolean savePrepared=command.has("savePrepared") && command.get("savePrepared").getAsBoolean();
                    boolean saveBeforeMutation=command.has("saveBeforeMutation") && command.get("saveBeforeMutation").getAsBoolean();
                    String saveParticipant=command.has("saveParticipant")?command.get("saveParticipant").getAsString():"";
                    protocol.observe(actual->{
                        if(saveBeforeMutation && actual==FabricDepositProtocol.Point.PREPARED_MARKED)server.saveEverything(true,true,true);
                        if(actual==point) {
                            if(savePrepared)server.saveEverything(true,true,true);
                            if(saveParticipant.equals("CHEST"))server.overworld().getChunkSource().save(true);
                            if(saveParticipant.equals("ACTOR"))try {
                                var field=net.minecraft.server.level.ServerLevel.class.getDeclaredField("entityManager");field.setAccessible(true);
                                ((net.minecraft.world.level.entity.PersistentEntitySectionManager<?>)field.get(server.overworld())).saveAll();
                            }catch(ReflectiveOperationException error){throw new IllegalStateException("Unknown native actor save hook",error);}
                            barrier(actual,token,savePrepared,saveBeforeMutation,saveParticipant,protocol.metrics());
                        }
                    });
                    reply(id,Map.of("status","PASS","point",point,"token",token),null);return;
                }
                throw new IllegalArgumentException("Unknown A05 native observation operation");
            }catch(IllegalStateException starting){if(active!=null)reply(active.get("id").getAsString(),null,starting);}
            catch(Throwable error){if(active!=null)reply(active.get("id").getAsString(),null,error);else System.err.println("A05 observer: "+error);}
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{writer.shutdown();flush();});
    }
    private void barrier(FabricDepositProtocol.Point point,String token,boolean saved,boolean savedBefore,String participant,FabricDepositProtocol.Metrics metrics) {
        write("deposit-cut-reached.json",Map.of("process",process,"pid",ProcessHandle.current().pid(),"point",point,"token",token,"time",System.currentTimeMillis(),
                "nativeSaveExplicitlyRequested",saved,"preparedSaveExplicitlyRequested",savedBefore,"onlyParticipantSaveExplicitlyRequested",participant,"thread",Thread.currentThread().getName(),"protocolMetrics",metrics));
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        while(System.nanoTime()<until)try{Thread.sleep(10);}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IllegalStateException(stopped);}
        throw new IllegalStateException("Intended A05 process interruption was not witnessed");
    }
    private void poll() {
        flush();
        try {
            Path path=root.resolve("server/deposit-request.json");if(!Files.isRegularFile(path) || Files.size(path)>4096)return;
            var command=JsonParser.parseString(Files.readString(path)).getAsJsonObject();String id=command.get("id").getAsString();
            if(id.equals(seen) || !process.toString().equals(command.get("process").getAsString()))return;
            seen=id;request.compareAndSet(null,command);
        }catch(Exception error){System.err.println("A05 request read: "+error);}
    }
    private void flush(){Object value=pending.getAndSet(null);if(value!=null)write("deposit-snapshot.json",value);}
    private void reply(String id,Object result,Throwable error) {
        writer.execute(()->write("deposit-response.json",Map.of("process",process,"id",id,"status",error==null?"PASS":"FAIL",
                "value",error==null?result:Map.of("failure",error.toString()))));
    }
    private void write(String name,Object value) {
        try {
            Path target=root.resolve("server/"+name),staged=target.resolveSibling(name+".next");Files.createDirectories(target.getParent());
            Files.writeString(staged,json.toJson(value),StandardCharsets.UTF_8);
            Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception error){throw new IllegalStateException("A05 evidence write",error);}
    }
}
