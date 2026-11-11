package dev.aivillages.fabric;

import com.google.gson.*;
import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.runtime.claims.StagedTransfers;
import dev.aivillages.core.kernel.runtime.claims.StagedTransfers.*;
import dev.aivillages.core.kernel.runtime.claims.StagedTransfers.Plan;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.server.level.ServerPlayer;

/** Explicit deterministic A08 test driver. Real Kernel Jobs/leases, players, villagers and native effects.
 * No LLM method, autonomy, inventory injection, receipt production or successful goal claim. */
public final class RuntimeA08Driver implements ModInitializer {
    private final Path root=Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR"),"server");
    private final Gson json=new Gson();private final UUID process=UUID.randomUUID();
    private final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor();
    private final AtomicReference<JsonObject> request=new AtomicReference<>();private volatile Object pending;
    private String seen="";private long sequence;private final Map<UUID,Frame> frames=new LinkedHashMap<>();private final Map<UUID,Plan> plans=new LinkedHashMap<>();
    private final Map<UUID,ResourceLeaseService.ImmediateTicket> tickets=new LinkedHashMap<>();
    public void onInitialize(){
        if(!"true".equalsIgnoreCase(System.getenv("COGNITIVECRAFT_A08_DRIVER"))){io.shutdown();return;}
        io.scheduleWithFixedDelay(this::poll,0,50,TimeUnit.MILLISECONDS);
        ServerTickEvents.END_SERVER_TICK.register(server->{
            JsonObject active=null;
            try {
                var kernel=AiVillages.kernel();if(kernel==null)return;
                var jobs=kernel.jobLifecycleOwner();var leases=kernel.leaseOwner();if(jobs==null||leases==null)return;
                var body=new LinkedHashMap<String,Object>();body.put("schema",1);body.put("process",process);body.put("time",System.currentTimeMillis());
                body.put("tick",server.overworld().getGameTime());body.put("sequence",++sequence);body.put("ready",jobs.ready()&&leases.ready());body.put("jobs",jobs.snapshot());body.put("leases",leases.snapshot());
                body.put("fairLimits",leases.fairLimits());body.put("stagingLimits",StagedTransfers.Settings.defaults());
                var frameRows=new LinkedHashMap<String,Object>();for(var entry:frames.entrySet()){
                    var job=jobs.snapshot().jobs().stream().filter(j->j.id().equals(entry.getKey())).findFirst().orElseThrow();
                    frameRows.put(entry.getKey().toString(),kernel.stagingOwner().view(entry.getValue(),job.origin()));
                }body.put("frames",frameRows);pending=body;
                JsonObject command=request.getAndSet(null);if(command==null)return;active=command;
                String id=command.get("id").getAsString(),op=command.get("op").getAsString();
                if(!command.get("process").getAsString().equals(process.toString()))throw new SecurityException("Current native process required");
                ServerPlayer player=server.getPlayerList().getPlayer(UUID.fromString(command.get("principal").getAsString()));
                if(player==null)throw new SecurityException("Actual connected player required");
                TrustedContext caller=kernel.stagingContext(player);var out=new LinkedHashMap<String,Object>();out.put("status","PASS");out.put("sequence",sequence);
                if(op.equals("status")){out.put("fair",leases.immediateView(caller));out.put("activity",kernel.activities(player,0,16));}
                else if(op.equals("create")){
                    if(plans.size()>=32)throw new IllegalStateException("Bounded test cohort");
                    var citizen=kernel.citizen(player,UUID.fromString(command.get("citizen").getAsString()));
                    if(!citizen.owner().equals(caller))throw new SecurityException("Private staging caller");
                    ActorRef actor=citizen.actor();ContainerRef source=container(command,"source",actor.dimension()),destination=container(command,"destination",actor.dimension());
                    var units=new ArrayList<Units>();for(var element:command.getAsJsonArray("units")){
                        var unit=element.getAsJsonObject();units.add(new Units(new ResourceDelivery.ItemIdentity(unit.get("item").getAsString(),"default"),unit.get("quantity").getAsInt()));
                    }
                    Plan plan=new Plan(source,destination,source,units);boolean axe=command.has("axe")&&command.get("axe").getAsBoolean();
                    var arguments=new HashMap<String,Value>();arguments.put("actor",new ActorValue(actor));arguments.put("source",new ContainerValue(source));arguments.put("destination",new ContainerValue(destination));
                    arguments.put("amount",new IntValue(axe?1:units.getFirst().quantity()));
                    if(!axe)arguments.put("source",new AreaValue(ResourceLeases.point(source)));
                    if(axe){var t=container(command,"table",actor.dimension());arguments.put("table",new FacilityValue(new FacilityRef(t.dimension(),t.x(),t.y(),t.z())));}
                    var capability=axe?WoodenAxeProduction.ID:OakLogDelivery.ID;
                    UUID jobId=UUID.fromString(command.get("job").getAsString());kernel.reserveStaging(jobId);
                    var allowance=new Budgets.Limits(Map.of(Budgets.Kind.INSTRUCTIONS,8192L,Budgets.Kind.OBSERVATIONS,131072L,
                            Budgets.Kind.ATTEMPTED_EFFECTS,16L,Budgets.Kind.COMMITTED_EFFECTS,16L,Budgets.Kind.ELAPSED_TICKS,1200L),System.currentTimeMillis()+120_000);
                    var result=jobs.create(jobId,jobId,new CapabilityRequest(capability,arguments),caller,
                            new ObservationRef(UUID.randomUUID(),server.overworld().getGameTime(),actor.dimension()),allowance,List.of());
                    out.put("change",result);if(!result.accepted())throw new IllegalStateException("Staging create: "+result);plans.put(jobId,plan);
                }else if(op.equals("cancel")){
                    var job=jobs.query(uuid(command,"job"),caller);var result=jobs.cancel(job.id(),job.guard(),caller);
                    out.put("change",result);if(!result.accepted())throw new IllegalStateException("Staging cancel: "+result);
                }else if(op.equals("assign")){
                    var job=jobs.query(uuid(command,"job"),caller);
                    var result=jobs.assign(job.id(),job.guard(),UUID.randomUUID(),job.responsible(),null,List.of(),job.allowance(),caller);
                    out.put("change",result);if(!result.accepted())throw new IllegalStateException("Staging reserve: "+result);
                }else if(op.equals("open")){
                    UUID jobId=uuid(command,"job");Frame frame=kernel.stagingOwner().open(jobId,plans.get(jobId),caller);
                    frames.put(jobId,frame);kernel.stagingOpened(jobId);out.put("view",kernel.stagingOwner().view(frame,caller));
                }else if(op.equals("step")){
                    UUID jobId=uuid(command,"job");out.put("view",kernel.stagingOwner().step(frames.get(jobId),Operation.valueOf(command.get("operation").getAsString()),caller));
                }else if(op.equals("report")){
                    var job=jobs.query(uuid(command,"job"),caller);View view=kernel.stagingOwner().view(frames.get(job.id()),caller);var a=job.current();
                    var result=jobs.recordExecutionResult(job.id(),job.guard(),new Jobs.Report(a.id(),a.generation(),view.moves().size(),view.usage(),List.of(),null,List.of()),caller);
                    out.put("change",result);if(!result.accepted())throw new IllegalStateException("Staging accounting: "+result);
                }else if(op.equals("arrival")){
                    if(tickets.size()>=16)throw new IllegalStateException("Bounded arrival cohort");var job=jobs.query(uuid(command,"job"),caller);Plan plan=plans.get(job.id());
                    var demands=List.of(new ResourceLeases.Demand(new ResourceLeases.Resource(caller.scope().worldId(),ResourceLeases.Kind.STOCK,ResourceLeases.point(plan.source()),plan.units().getFirst().item().id(),-1),1),
                            new ResourceLeases.Demand(ResourceLeases.Resource.facility(caller.scope().worldId(),plan.source()),1),new ResourceLeases.Demand(ResourceLeases.Resource.facility(caller.scope().worldId(),plan.destination()),1));
                    var nativeWorld=new FabricStagedTransfers(server.overworld(),new FabricGatewayWorld(server.overworld()),(actor,effect,c)->{
                        try{return kernel.citizen(player,actor.citizenId()).actor().equals(actor)&&kernel.citizen(player,actor.citizenId()).owner().equals(c);}
                        catch(SecurityException unavailable){return false;}
                    });
                    var ticket=leases.immediateTicket(new ResourceLeases.Owner(job.id(),job.generation()),demands,caller,()->{
                        Snapshot s=nativeWorld.observe(job.current().bound(),plan,Operation.WITHDRAW);
                        return s.blocker()!=null?s.blocker():!s.reachable()||!s.actorFits()||!s.destinationFits()?Outcomes.Reason.TARGET_UNAVAILABLE:
                                s.sourceStock().getOrDefault(plan.units().getFirst().item().id(),0)<1?Outcomes.Reason.RESOURCE_MISSING:null;
                    });tickets.put(ticket.id(),ticket);out.put("ticket",ticket.id());out.put("claim",leases.acquireImmediate(ticket,caller));
                }else if(op.equals("ticket-complete")){out.put("claim",leases.completeImmediate(tickets.get(uuid(command,"ticket")),caller));}
                else if(op.equals("old-reference")){
                    var ref=json.fromJson(command.get("ref"),ResourceLeases.Ref.class);out.put("validate",leases.validate(ref,caller));out.put("release",leases.release(List.of(ref),caller));
                }else if(op.equals("private-view")){out.put("view",kernel.stagingOwner().view(frames.get(uuid(command,"job")),caller));}
                else if(op.equals("fence")){
                    var job=jobs.query(uuid(command,"job"),caller);var c=plans.containsKey(job.id())?plans.get(job.id()).source():ResourceDelivery.destinationAddress(job.request().request());
                    if(command.has("source"))c=container(command,"source",job.responsible().dimension());
                    out.put("fenced",kernel.recoveryOwner().fenced(job.current().bound(),Effect.WITHDRAW,new SurvivalGateway.Cell(c.dimension(),c.x(),c.y(),c.z())));
                }
                else throw new IllegalArgumentException("Known A08 test operation required");
                reply(id,out);
            }catch(SecurityException denied){if(active!=null)reply(active.get("id").getAsString(),Map.of("status","DENIED","sequence",sequence,"reason",denied.toString()));}
            catch(Exception error){if(active!=null)reply(active.get("id").getAsString(),Map.of("status","ERROR","sequence",sequence,"reason",error.toString()));}
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{io.shutdown();flush();});
    }
    private static UUID uuid(JsonObject c,String name){return UUID.fromString(c.get(name).getAsString());}
    private static ContainerRef container(JsonObject c,String name,String dimension){var a=c.getAsJsonArray(name);return new ContainerRef(dimension,a.get(0).getAsInt(),a.get(1).getAsInt(),a.get(2).getAsInt());}
    private void reply(String id,Object value){io.execute(()->write("a08-response.json",Map.of("id",id,"process",process,"value",value)));}
    private void poll(){try{var p=root.resolve("a08-request.json");if(Files.exists(p)){
        String text=Files.readString(p);if(text.length()<=16384&&!text.equals(seen)){seen=text;request.set(JsonParser.parseString(text).getAsJsonObject());}
    }flush();}catch(Exception error){write("a08-io-error.json",Map.of("error",error.toString()));}}
    private void flush(){var value=pending;if(value!=null)write("a08-snapshot.json",value);}
    private void write(String name,Object value){try{Path p=root.resolve(name),tmp=root.resolve(name+".part");Files.writeString(tmp,json.toJson(value));Files.move(tmp,p,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(Exception error){System.err.println("A08 test evidence "+error);}}
}
