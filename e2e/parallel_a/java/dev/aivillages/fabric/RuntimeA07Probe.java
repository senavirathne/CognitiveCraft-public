package dev.aivillages.fabric;

import com.google.gson.*;
import dev.aivillages.core.kernel.*;
import dev.aivillages.fabric.runtime.evidence.LifecycleItemDamage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;

/** A07 test-only native reads, clock cuts and bounded fixture ticket release. No inventory or lifecycle-fact injection. */
public final class RuntimeA07Probe implements ModInitializer {
    private final Path root=Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR"),"server");
    private final Gson json=new Gson();private final UUID process=UUID.randomUUID();
    private final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"runtime-a-a07"));
    private final AtomicReference<JsonObject> request=new AtomicReference<>();
    private volatile Object pending;private String seen="";private KernelSession attached;
    private final List<List<Integer>> positions=new ArrayList<>();
    private final Map<UUID,Entity> tracked=new LinkedHashMap<>();
    private final List<Map<String,Object>> events=new ArrayList<>();
    private Set<UUID> existingJobs=Set.of();private String mode,token,lastCutToken;private UUID actor;private boolean coherentLifecycle;
    public void onInitialize() {
        if(!"true".equalsIgnoreCase(System.getenv("COGNITIVECRAFT_A07_PROBE"))){io.shutdown();return;}
        ServerEntityEvents.ENTITY_LOAD.register((e,w)->event(e,"ENTITY_LOAD"));
        ServerEntityEvents.ENTITY_UNLOAD.register((e,w)->event(e,"ENTITY_UNLOAD"));
        io.scheduleWithFixedDelay(this::poll,0,100,TimeUnit.MILLISECONDS);
        ServerTickEvents.END_SERVER_TICK.register(server->{
            try {
                var kernel=AiVillages.kernel();if(kernel==null)return;
                SurvivalGateway gateway;
                try{gateway=kernel.lifecycleGateway();}
                catch(IllegalStateException opening){
                    if(attached==null&&"Kernel world data is loading or unavailable".equals(opening.getMessage()))return;
                    throw opening;
                }
                if(attached!=kernel){attached=kernel;gateway.observeActions(observed->{
                    if(mode==null||actor==null||!observed.actor().entityId().equals(actor))return;
                    boolean match=mode.equals("PREFLIGHT")&&observed.point()==SurvivalGateway.ActionPoint.BEFORE_POLL
                            ||mode.equals("EFFECT")&&observed.point()==SurvivalGateway.ActionPoint.AFTER_POLL&&observed.receipt().committedEffects()>0;
                    if(match)cut(server,kernel,observed);
                });
                    kernel.jobLifecycleOwner().observePublications(observed->{
                        if(!"PUBLICATION".equals(mode))return;
                        for(var job:observed.requested().jobs())if(!existingJobs.contains(job.id())&&job.current()!=null
                                &&job.current().worker().entityId().equals(actor)&&job.current().receiptCount()>0){
                            var before=observed.before().jobs().stream().filter(j->j.id().equals(job.id())).findFirst().orElse(null);
                            if(before!=null&&before.state()==Jobs.State.ACTIVE){cut(server,kernel,observed);break;}
                        }
                    });
                }
                var command=request.getAndSet(null);
                if(command!=null) {
                    String id=command.get("id").getAsString(),op=command.get("op").getAsString();
                    if(op.equals("watch")) {
                        coherentLifecycle=command.has("coherentLifecycle")&&command.get("coherentLifecycle").getAsBoolean();
                        positions.clear();tracked.clear();events.clear();
                        for(var point:command.getAsJsonArray("positions")){
                            var a=point.getAsJsonArray();positions.add(List.of(a.get(0).getAsInt(),a.get(1).getAsInt(),a.get(2).getAsInt()));
                        }
                        if(positions.size()>8)throw new IllegalArgumentException("Bounded A07 native target set");
                        var items=new ArrayList<UUID>();
                        for(var value:command.getAsJsonArray("entities")){var entityId=UUID.fromString(value.getAsString());tracked.put(entityId,null);items.add(entityId);}
                        if(tracked.size()>16)throw new IllegalArgumentException("Bounded A07 native entity set");
                        LifecycleItemDamage.watch(items);
                    }else if(op.equals("arm")) {
                        mode=command.get("mode").getAsString();if(!Set.of("PREFLIGHT","EFFECT","PUBLICATION").contains(mode))throw new IllegalArgumentException("A07 native phase");
                        token=command.get("token").getAsString();actor=UUID.fromString(command.get("actor").getAsString());
                        existingJobs=kernel.jobLifecycleOwner().snapshot().jobs().stream().map(Jobs.Job::id).collect(java.util.stream.Collectors.toSet());
                    }else if(op.equals("release-fixture-tickets")) {
                        if(lastCutToken==null||!lastCutToken.equals(command.get("token").getAsString()))throw new IllegalArgumentException("Actual A07 cut token required");
                        var level=server.overworld();var chunks=new TreeSet<Long>();
                        for(var p:positions) {
                            for(var player:server.getPlayerList().getPlayers())if(player.level()==level
                                    &&Math.hypot(player.getX()-p.get(0),player.getZ()-p.get(2))<128)
                                throw new IllegalArgumentException("Players must already have left the bounded fixture");
                            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)chunks.add(new net.minecraft.world.level.ChunkPos((p.get(0)>>4)+dx,(p.get(2)>>4)+dz).pack());
                        }
                        for(long forced:level.getChunkSource().getForceLoadedChunks().toLongArray())if(chunks.contains(forced))throw new IllegalArgumentException("Explicit fixture force tickets must already be removed");
                        for(var packed:chunks)level.getChunkSource().removeTicketWithRadius(net.minecraft.server.level.TicketType.UNKNOWN,net.minecraft.world.level.ChunkPos.unpack(packed),0);
                        write("a07-fixture-ticket-release.json",Map.of("token",lastCutToken,"time",System.currentTimeMillis(),"tick",level.getGameTime(),
                                "requestedUnknownFullChunkTicketReleases",List.copyOf(chunks),"claim","Test fixture residency control only; native ChunkMap and entity callbacks must independently establish actual unload. W04 never uses this operation."));
                    }else if(op.equals("clear")){mode=null;token=null;actor=null;lastCutToken=null;}
                    else throw new IllegalArgumentException("A07 observation operation");
                    write("a07-response.json",Map.of("id",id,"status","PASS","process",process));
                }
                var level=server.overworld();var targetRows=new LinkedHashMap<String,Object>();
                for(var xyz:positions) {
                    var p=new BlockPos(xyz.get(0),xyz.get(1),xyz.get(2));var chunk=level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4);
                    var row=new LinkedHashMap<String,Object>();row.put("loaded",chunk!=null);
                    if(chunk!=null) {
                        row.put("block",chunk.getBlockState(p).toString());
                        var block=chunk.getBlockEntities().get(p);
                        if(block instanceof Container box){var stock=new TreeMap<String,Integer>();for(int n=0;n<box.getContainerSize();n++){
                            var stack=box.getItem(n);if(!stack.isEmpty())stock.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),stack.getCount(),Integer::sum);
                        }row.put("stock",stock);}
                    }
                    targetRows.put(p.getX()+","+p.getY()+","+p.getZ(),Map.copyOf(row));
                }
                var entityRows=new LinkedHashMap<String,Object>();
                for(var id:List.copyOf(tracked.keySet())) {
                    var current=level.getEntity(id);if(current!=null)tracked.put(id,current);var remembered=tracked.get(id);
                    var row=new LinkedHashMap<String,Object>();row.put("loaded",current!=null);row.put("uuid",id);
                    if(remembered!=null){row.put("alive",remembered.isAlive());row.put("removed",remembered.isRemoved());row.put("removal",String.valueOf(remembered.getRemovalReason()));
                        row.put("pos",List.of(remembered.getX(),remembered.getY(),remembered.getZ()));
                        if(remembered instanceof ItemEntity item){row.put("item",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString());row.put("count",item.getItem().getCount());}
                        if(remembered instanceof Villager v){var stock=new TreeMap<String,Integer>();for(int n=0;n<v.getInventory().getContainerSize();n++){
                            var item=v.getInventory().getItem(n);if(!item.isEmpty())stock.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).toString(),item.getCount(),Integer::sum);
                        }row.put("inventory",stock);}
                    }entityRows.put(id.toString(),Map.copyOf(row));
                }
                var lifecycleRows=new LinkedHashMap<String,Object>();
                if(coherentLifecycle)for(var player:server.getPlayerList().getPlayers().stream().limit(2).toList()) {
                    var citizens=new LinkedHashMap<String,Object>();
                    for(var address:kernel.citizens(player).citizens()) {
                        if(!tracked.containsKey(address.actor().entityId()))continue;
                        var id=address.actor().citizenId();
                        citizens.put(id.toString(),Map.of("identity",kernel.citizen(player,id),
                                "trace",kernel.lifecycleDiagnostics(player,id)));
                    }
                    lifecycleRows.put(player.getGameProfile().name(),Map.copyOf(citizens));
                }
                pending=Map.of("schema",1,"process",process,"pid",ProcessHandle.current().pid(),"time",System.currentTimeMillis(),
                        "tick",level.getGameTime(),"targets",Map.copyOf(targetRows),"entities",Map.copyOf(entityRows),
                        "nativeEvents",List.copyOf(events),"damage",LifecycleItemDamage.facts(),"lifecycle",Map.copyOf(lifecycleRows));
            }catch(Exception failure){write("a07-error.json",Map.of("error",failure.toString()));}
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s->{io.shutdown();flush();});
    }
    private void event(Entity entity,String name) {
        if(!tracked.containsKey(entity.getUUID())||events.size()==512)return;
        events.add(Map.of("event",name,"entity",entity.getUUID(),"tick",entity.level().getGameTime(),"removal",String.valueOf(entity.getRemovalReason())));
        tracked.put(entity.getUUID(),entity);
    }
    private void cut(net.minecraft.server.MinecraftServer server,KernelSession kernel,Object witnessed) {
        var selected=mode;var value=token;mode=null;lastCutToken=value;
        try{
            int result=server.getCommands().getDispatcher().execute("tick freeze",server.createCommandSourceStack());
            write("a07-cut.json",Map.of("process",process,"token",value,"mode",selected,"time",System.currentTimeMillis(),
                    "tick",server.overworld().getGameTime(),"freezeResult",result,"witnessed",witnessed,"jobs",kernel.jobLifecycleOwner().snapshot()));
        }catch(Exception failure){write("a07-error.json",Map.of("error",failure.toString()));}
    }
    private void poll(){try{var p=root.resolve("a07-request.json");if(Files.exists(p)){
        var value=JsonParser.parseString(Files.readString(p)).getAsJsonObject();String id=value.get("id").getAsString();
        if(!id.equals(seen)){
            seen=id;
            if(value.has("process")&&process.toString().equals(value.get("process").getAsString()))request.set(value);
        }
    }flush();}catch(Exception failure){System.err.println("A07 observer poll: "+failure);}}
    private void flush(){var value=pending;if(value!=null)write("a07-snapshot.json",value);}
    private void write(String file,Object value){try{Files.createDirectories(root);var p=root.resolve(file);var staged=p.resolveSibling(file+".next");
        synchronized(this){Files.writeString(staged,json.toJson(value));Files.move(staged,p,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    }catch(Exception failure){throw new IllegalStateException(failure);}}
}
