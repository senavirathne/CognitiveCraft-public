package dev.aivillages.fabric;

import com.google.gson.*;
import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.runtime.persistence.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static dev.aivillages.core.kernel.runtime.persistence.ArchiveFormat.sha256;

/** A04 test observer only. Native effects still pass through the original production request/executor. */
public final class RuntimeAJobProbe implements ModInitializer {
    private final Path root=Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR"));
    private final Gson json=new Gson();private final UUID process=UUID.randomUUID();
    private final ScheduledExecutorService mailbox=Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService baselineIo=Executors.newSingleThreadExecutor();
    private final AtomicReference<JsonObject> request=new AtomicReference<>();private final AtomicBoolean busy=new AtomicBoolean();
    private final List<Map<String,Long>> captures=new ArrayList<>();
    private boolean readySent,measuring;private String seen="";
    public void onInitialize() {
        if(!"true".equalsIgnoreCase(System.getenv("COGNITIVECRAFT_A04_PROBE"))){mailbox.shutdown();baselineIo.shutdown();return;}
        mailbox.scheduleWithFixedDelay(this::poll,0,100,TimeUnit.MILLISECONDS);
        ServerTickEvents.END_SERVER_TICK.register(server->{
            JsonObject active=null;
            try {
                var kernel=AiVillages.kernel();if(kernel==null)return;long start=System.nanoTime();var view=kernel.runtimeStorageView();long nanos=System.nanoTime()-start;
                if(measuring && captures.size()<5000)captures.add(Map.of("time",System.currentTimeMillis(),"tick",server.overworld().getGameTime(),"nanos",nanos));
                if(!readySent){readySent=true;mailbox.execute(()->write("job-ready.json",map("process",process,"pid",ProcessHandle.current().pid(),"world",view.jobs().worldId())));}
                var command=request.getAndSet(null);if(command==null)return;active=command;
                String id=command.get("id").getAsString(),op=command.get("op").getAsString();
                if(op.equals("status")) {
                    reply(id,map("status","PASS","storage",kernel.runtimeHistoryStatus(),"jobs",kernel.runtimeJobHistoryStatus(),"leases",kernel.runtimeLeaseHistoryStatus(),
                            "ready",view.ready(),"hot",view.jobs(),"hotLeases",view.leases()),null);return;
                }
                if(op.equals("begin-window")){captures.clear();measuring=true;reply(id,map("status","PASS","startMillis",System.currentTimeMillis(),"startTick",server.overworld().getGameTime()),null);return;}
                if(op.equals("end-window")){measuring=false;reply(id,map("status","PASS","captures",List.copyOf(captures)),null);return;}
                if(op.equals("lookup")) {
                    UUID principal=UUID.fromString(command.get("principal").getAsString()),jobId=UUID.fromString(command.get("job").getAsString());
                    var player=server.getPlayerList().getPlayer(principal);if(player==null)throw new IllegalArgumentException("Actual connected principal required");
                    try {
                        var found=kernel.queuedJob(player,jobId);
                        var value=map("status","PASS","job",found,"hot",view.jobs().jobs().stream().anyMatch(j->j.id().equals(jobId)),"activity",kernel.activity(player,jobId));
                        var attempt=found.current();
                        if(attempt!=null && !attempt.executions().isEmpty())value.put("knowledge",kernel.knowledgeEligibility(attempt.worker(),attempt.executions().getLast().artifact(),found.origin()));
                        if(command.has("replay")) {
                            if(!found.state().terminal() || found.attempts().stream().anyMatch(Jobs.Attempt::open) || attempt==null)throw new IllegalArgumentException("Exact closed native outcome required");
                            var jobs=kernel.jobLifecycleOwner();var before=jobs.snapshot();
                            value.put("sameReceipt",jobs.recordExecutionResult(found.id(),found.guard(),new Jobs.Report(attempt.id(),attempt.generation(),attempt.effects(),attempt.usage(),attempt.receipts(),attempt.terminal(),attempt.resourceReceipts()),found.origin()));
                            value.put("changedReceipt",jobs.recordExecutionResult(found.id(),found.guard(),new Jobs.Report(attempt.id(),attempt.generation(),attempt.effects()+1,attempt.usage(),attempt.receipts(),attempt.terminal(),attempt.resourceReceipts()),found.origin()));
                            value.put("staleSubmission",jobs.create(UUID.randomUUID(),found.submissionId(),found.request().request(),found.origin(),found.request().observation(),found.allowance(),List.of()));
                            value.put("cancel",jobs.cancel(found.id(),found.guard(),found.origin()));
                            value.put("hotUnchanged",before==jobs.snapshot());
                        }
                        if(command.has("accomplishments"))kernel.accomplishments(found.responsible(),found.origin(),16)
                                .whenComplete((evidence,error)->{value.put("accomplishments",evidence);reply(id,value,error);});
                        else reply(id,value,null);
                    }catch(SecurityException denied){reply(id,map("status","DENIED"),null);}
                    catch(IllegalStateException unavailable){reply(id,map("status","UNAVAILABLE","reason",unavailable.toString()),null);}
                    return;
                }
                if(op.equals("arm")) {
                    String point=command.get("point").getAsString(),token=UUID.fromString(command.get("token").getAsString()).toString();
                    if(!Set.of("AFTER_SEGMENT_ACK","AFTER_DIRECTORY_ACK","BEFORE_HOT_PUBLICATION","AFTER_HOT_ACKNOWLEDGEMENT").contains(point))throw new IllegalArgumentException("Known boundary required");
                    boolean hot=point.endsWith("PUBLICATION") || point.endsWith("ACKNOWLEDGEMENT");
                    var offered=kernel.runtimeHistory().offer(writer->{writer.observePublication(ArchiveFormat.Owner.JOBS,p->{if(p.name().equals(point))barrier(point,token,false);});return map("status","PASS","point",point,"token",token);});
                    if(offered.isEmpty())reply(id,map("status","BUSY"),null);else {
                        kernel.jobHistoryOwner().observe(p->{if(p.name().equals(point))barrier(point,token,false);
                            else if(hot && p==JobArchive.Point.ARCHIVE_ACKNOWLEDGED)barrier("PREPARE_SAVE",token,true);});
                        offered.get().whenComplete((value,error)->reply(id,value,error));
                    }return;
                }
                if(op.equals("baseline-benchmark")) {
                    CompletableFuture.supplyAsync(()->{try{return RuntimeAJobCost.run(root.resolve("job-cost"),false);}catch(Exception failure){throw new CompletionException(failure);}},baselineIo)
                            .whenComplete((value,error)->reply(id,value,error));return;
                }
                Path nativeWorld=server.getWorldPath(LevelResource.ROOT);
                var offered=kernel.runtimeHistory().offer(writer->{
                    if(op.equals("freeze-benchmark"))return map("status","PASS","frozen",RuntimeAJobCost.freeze(root.resolve("job-cost"),nativeWorld,view));
                    if(op.equals("candidate-benchmark"))return RuntimeAJobCost.run(root.resolve("job-cost"),true);
                    if(op.equals("inventory")) {
                        var rows=writer.historyView().rows(ArchiveFormat.Owner.JOBS,ArchiveFormat.Kind.HISTORY).stream()
                                .map(r->map("id",r.id(),"sha256",sha256(r.payload()),"payload",new String(r.payload(),StandardCharsets.UTF_8))).toList();
                        return map("status","PASS","hot",view.jobs(),"archived",rows,"manifest",writer.snapshot());
                    }
                    throw new IOException("Unknown A04 operation");
                });
                if(offered.isEmpty())reply(id,map("status","BUSY"),null);else offered.get().whenComplete((value,error)->reply(id,value,error));
            }catch(IllegalStateException starting){if(active!=null)reply(active.get("id").getAsString(),map("status","UNAVAILABLE","reason",starting.toString()),null);}
            catch(Exception failure){if(active!=null)reply(active.get("id").getAsString(),null,failure);else System.err.println("A04 observer "+failure);}
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{mailbox.shutdown();baselineIo.shutdown();});
    }
    private void barrier(String point,String token,boolean releasable)throws IOException {
        write("job-cut-reached.json",map("process",process,"pid",ProcessHandle.current().pid(),"point",point,"token",token,"at",Instant.now().toString(),"thread",Thread.currentThread().getName()));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        while(System.nanoTime()<deadline) {
            Path path=root.resolve("server/job-cut-release.json");
            if(releasable && Files.isRegularFile(path)) {
                var release=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                if(token.equals(release.get("token").getAsString()) && process.toString().equals(release.get("process").getAsString()))return;
            }
            try{Thread.sleep(10);}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IOException(stopped);}
        }
        throw new IOException("A04 intended witnessed crash did not occur");
    }
    private void poll() {
        try {
            Path file=root.resolve("server/job-request.json");if(busy.get() || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>16384)return;
            var command=JsonParser.parseString(Files.readString(file)).getAsJsonObject();String id=command.get("id").getAsString();UUID.fromString(id);if(id.equals(seen))return;seen=id;
            if(!process.toString().equals(command.get("process").getAsString())){write("job-response.json",map("id",id,"process",process,"status","STALE_PROCESS"));return;}
            if(busy.compareAndSet(false,true))request.set(command);
        }catch(Exception failure){System.err.println("A04 mailbox "+failure);}
    }
    private void reply(String id,Map<String,Object> result,Throwable error) {
        mailbox.execute(()->{try {
            var value=map("id",id,"process",process,"pid",ProcessHandle.current().pid());
            if(error!=null){value.put("status","FAIL");value.put("error",error.toString());}else value.putAll(result);write("job-response.json",value);
        }finally{busy.set(false);}});
    }
    private void write(String name,Object value) {
        try {Path path=root.resolve("server").resolve(name),next=path.resolveSibling(name+".next");Files.writeString(next,json.toJson(value));Files.move(next,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(IOException failure){throw new IllegalStateException(failure);}
    }
    private static Map<String,Object> map(Object... pairs) {
        var result=new LinkedHashMap<String,Object>();for(int n=0;n<pairs.length;n+=2)result.put((String)pairs[n],pairs[n+1]);return result;
    }
}
