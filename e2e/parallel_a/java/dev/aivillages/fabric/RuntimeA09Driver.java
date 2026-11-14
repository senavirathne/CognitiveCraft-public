package dev.aivillages.fabric;

import com.google.gson.*;
import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.runtime.activity.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.server.level.ServerPlayer;
import static dev.aivillages.core.kernel.Contracts.*;

/** Test-only deterministic caller of the PUBLIC A boundary. Never a planner, executor or receipt producer. */
public final class RuntimeA09Driver implements ModInitializer {
    private final Path root=Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR"),"server");
    private final UUID process=UUID.randomUUID();private final Gson json=new Gson();
    private final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"runtime-a-a09"));
    private final AtomicReference<JsonObject> request=new AtomicReference<>();private volatile Object pending;
    private final Map<UUID,UUID> watched=new LinkedHashMap<>();private String seen="";private long sequence;
    private UUID cutRoot,cutToken;
    public void onInitialize(){
        if(!"true".equalsIgnoreCase(System.getenv("COGNITIVECRAFT_A09_DRIVER"))){io.shutdown();return;}
        io.scheduleWithFixedDelay(this::poll,0,50,TimeUnit.MILLISECONDS);
        ServerTickEvents.END_SERVER_TICK.register(server->{
            JsonObject active=null;
            try {
                var kernel=AiVillages.kernel();if(kernel==null)return;var jobs=kernel.jobLifecycleOwner();
                if(jobs==null||kernel.leaseOwner()==null)return;
                var views=new LinkedHashMap<String,Object>();
                for(var e:watched.entrySet()) {
                    ServerPlayer player=server.getPlayerList().getPlayer(e.getValue());
                    if(player!=null)try{views.put(e.getKey().toString(),kernel.planningSnapshot(player,e.getKey()));}catch(SecurityException ignored){}
                }
                pending=Map.of("schema",1,"process",process,"sequence",++sequence,"time",System.currentTimeMillis(),
                        "tick",server.overworld().getGameTime(),"ready",jobs.ready()&&kernel.leaseOwner().ready(),"views",views,"jobs",jobs.snapshot(),"publicationTimings",jobs.publicationTimings());
                if(cutRoot!=null && jobs.ready() && kernel.leaseOwner().ready()) {
                    ServerPlayer player=server.getPlayerList().getPlayer(watched.get(cutRoot));
                    if(player!=null) {
                        var view=kernel.activity(player,cutRoot);var progress=view.progress();
                        if(progress!=null && progress.verifiedCheckpoints()==1 && progress.next()==ActivityRuntime.Next.PREPARE
                                && view.route()==dev.aivillages.core.kernel.runtime.RecoveredJobOwners.Route.NONE) {
                            UUID token=cutToken,id=cutRoot;cutRoot=null;cutToken=null;
                            var commands=server.getCommands().getDispatcher();var source=server.createCommandSourceStack();
                            int frozen=commands.execute("tick freeze",source),saved=commands.execute("save-all flush",source);
                            if(frozen!=1||saved!=1)throw new IllegalStateException("Exact checkpoint save cut refused");
                            write("a09-cut.json",Map.of("process",process,"pid",ProcessHandle.current().pid(),"time",System.currentTimeMillis(),
                                    "tick",server.overworld().getGameTime(),"token",token,"root",id,"freezeResult",frozen,"saveResult",saved,
                                    "activity",progress,"job",jobs.query(id,kernel.stagingContext(player))));
                        }
                    }
                }
                JsonObject command=request.getAndSet(null);if(command==null)return;active=command;
                if(!command.get("process").getAsString().equals(process.toString()))throw new SecurityException("Current native process required");
                String id=command.get("id").getAsString(),op=command.get("op").getAsString();
                ServerPlayer player=server.getPlayerList().getPlayer(UUID.fromString(command.get("principal").getAsString()));
                if(player==null)throw new SecurityException("Connected principal required");
                UUID job=UUID.fromString(command.get("root").getAsString());var result=new LinkedHashMap<String,Object>();result.put("status","PASS");result.put("sequence",sequence);
                if(op.equals("create")) {
                    if(watched.size()>=32)throw new IllegalStateException("Bounded driver cohort");
                    var actor=kernel.citizen(player,UUID.fromString(command.get("citizen").getAsString())).actor();
                    var min=command.getAsJsonArray("minimum");var max=command.getAsJsonArray("maximum");var dst=command.getAsJsonArray("destination");
                    var args=Map.<String,Value>of("actor",new ActorValue(actor),"amount",new IntValue(2),
                            "source",new AreaValue(new Cuboid(actor.dimension(),min.get(0).getAsInt(),min.get(1).getAsInt(),min.get(2).getAsInt(),max.get(0).getAsInt(),max.get(1).getAsInt(),max.get(2).getAsInt())),
                            "destination",new ContainerValue(new ContainerRef(actor.dimension(),dst.get(0).getAsInt(),dst.get(1).getAsInt(),dst.get(2).getAsInt())));
                    long window=command.has("deadlineMillis")?command.get("deadlineMillis").getAsLong():600_000;
                    if(window<100||window>600_000)throw new IllegalArgumentException("Fixture deadline bound");
                    var base=OakLogDelivery.executionLimits(System.currentTimeMillis()).total();var maxima=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);
                    base.maxima().forEach((k,n)->maxima.put(k,Math.multiplyExact(n,2)));
                    var limits=new Budgets.Limits(maxima,Math.addExact(System.currentTimeMillis(),window));
                    result.put("change",kernel.createActivity(player,job,new CapabilityRequest(OakLogDelivery.ID,args),limits,2));watched.put(job,player.getUUID());
                } else if(op.equals("prepare")) {
                    var snapshot=kernel.planningSnapshot(player,job);var args=new HashMap<>(snapshot.goal().arguments());args.put("amount",new IntValue(1));
                    var expected=new Jobs.Guard(command.get("revision").getAsLong(),command.get("generation").getAsLong());
                    var proposal=new ActivityRuntime.Proposal(1,UUID.fromString(command.get("proposal").getAsString()),job,expected,command.get("token").getAsString(),
                            new CapabilityRequest(snapshot.goal().capability(),args),new ArtifactRef(snapshot.goal().capability(),command.get("artifact").getAsString()),null,
                            ActivityPayload.Quantities.exact(1),snapshot.remaining(),2);
                    result.put("change",kernel.prepareActivity(player,proposal));
                } else if(op.equals("denied-prepare")) {
                    var a=command.getAsJsonObject("actor");var actor=new ActorRef(UUID.fromString(a.get("citizenId").getAsString()),UUID.fromString(a.get("entityId").getAsString()),a.get("dimension").getAsString());
                    var min=command.getAsJsonArray("minimum");var max=command.getAsJsonArray("maximum");var d=command.getAsJsonArray("destination");
                    var args=Map.<String,Value>of("actor",new ActorValue(actor),"amount",new IntValue(1),
                            "source",new AreaValue(new Cuboid(actor.dimension(),min.get(0).getAsInt(),min.get(1).getAsInt(),min.get(2).getAsInt(),max.get(0).getAsInt(),max.get(1).getAsInt(),max.get(2).getAsInt())),
                            "destination",new ContainerValue(new ContainerRef(actor.dimension(),d.get(0).getAsInt(),d.get(1).getAsInt(),d.get(2).getAsInt())));
                    var l=command.getAsJsonObject("allowance");var maxima=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);
                    for(var e:l.getAsJsonObject("maxima").entrySet())maxima.put(Budgets.Kind.valueOf(e.getKey()),e.getValue().getAsLong());
                    var proposal=new ActivityRuntime.Proposal(1,UUID.randomUUID(),job,new Jobs.Guard(command.get("revision").getAsLong(),command.get("generation").getAsLong()),"foreign-caller",
                            new CapabilityRequest(OakLogDelivery.ID,args),new ArtifactRef(OakLogDelivery.ID,command.get("artifact").getAsString()),null,ActivityPayload.Quantities.exact(1),
                            new Budgets.Limits(maxima,l.get("deadlineEpochMillis").getAsLong()),2);
                    result.put("change",kernel.prepareActivity(player,proposal));
                } else if(op.equals("snapshot")||op.equals("watch")) {
                    result.put("activity",kernel.planningSnapshot(player,job));
                    if(op.equals("watch")){if(watched.size()>=32&&!watched.containsKey(job))throw new IllegalStateException("Bounded monitor");watched.put(job,player.getUUID());}
                } else if(op.equals("arm")) {
                    var snapshot=kernel.planningSnapshot(player,job);if(snapshot.verifiedCheckpoints()!=0)throw new IllegalArgumentException("Cut must be armed before the first native segment");
                    cutRoot=job;cutToken=UUID.fromString(command.get("token").getAsString());result.put("token",cutToken);
                } else throw new IllegalArgumentException("Known A09 runtime-test operation required");
                reply(id,result);
            }catch(SecurityException denied){if(active!=null)reply(active.get("id").getAsString(),Map.of("status","DENIED","sequence",sequence,"reason","Private activity unavailable"));}
            catch(Exception failed){if(active!=null)reply(active.get("id").getAsString(),Map.of("status","ERROR","sequence",sequence,"reason",failed.toString()));else write("a09-error.json",Map.of("error",failed.toString(),"time",System.currentTimeMillis()));}
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s->{io.shutdown();flush();});
    }
    private void reply(String id,Object value){io.execute(()->write("a09-response.json",Map.of("id",id,"process",process,"value",value)));}
    private void poll(){try{Path p=root.resolve("a09-request.json");if(Files.isRegularFile(p)&&Files.size(p)<=16_384){String text=Files.readString(p);if(!text.equals(seen)){seen=text;request.set(JsonParser.parseString(text).getAsJsonObject());}}flush();}
        catch(Exception e){System.err.println("A09 driver I/O "+e);}}
    private void flush(){var value=pending;if(value!=null)write("a09-snapshot.json",value);}
    private synchronized void write(String name,Object value){try{Files.createDirectories(root);Path p=root.resolve(name),tmp=root.resolve(name+".part");Files.writeString(tmp,json.toJson(value));Files.move(tmp,p,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(Exception e){throw new IllegalStateException("A09 native evidence write",e);}}
}
