package dev.aivillages.fabric;

import com.google.gson.*;
import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.runtime.persistence.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.ResourceLeases.*;
import static dev.aivillages.core.kernel.runtime.persistence.ArchiveFormat.sha256;

/** A03 test-only host observation and witnessed crash barriers. No physical world mutation. */
public final class RuntimeALeaseProbe implements ModInitializer {
    private final Path root=Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR"));
    private final Gson json=new Gson();private final UUID process=UUID.randomUUID();
    private final ScheduledExecutorService mailbox=Executors.newSingleThreadScheduledExecutor();
    private final AtomicReference<JsonObject> request=new AtomicReference<>();private final AtomicBoolean busy=new AtomicBoolean();
    private String seen="";private boolean readySent;
    public void onInitialize() {
        if(!"true".equalsIgnoreCase(System.getenv("COGNITIVECRAFT_A03_PROBE"))){mailbox.shutdown();return;}
        mailbox.scheduleWithFixedDelay(this::poll,0,100,TimeUnit.MILLISECONDS);
        ServerTickEvents.END_SERVER_TICK.register(server->{
            JsonObject active=null;
            try {
                var kernel=AiVillages.kernel();if(kernel==null)return;var view=kernel.runtimeStorageView();
                if(!readySent){readySent=true;mailbox.execute(()->write("lease-ready.json",map("process",process,"pid",ProcessHandle.current().pid(),"world",view.jobs().worldId())));}
                var command=request.getAndSet(null);if(command==null)return;active=command;
                String id=command.get("id").getAsString(),op=command.get("op").getAsString();
                if(op.equals("status")){reply(id,map("status","PASS","storage",kernel.runtimeHistoryStatus(),"metrics",kernel.runtimeLeaseHistoryStatus(),
                        "ready",view.ready(),"hot",view.leases(),"jobs",view.jobs().jobs().size()),null);return;}
                if(op.equals("lookup")) {
                    var old=command.getAsJsonObject("lease");var origin=old.getAsJsonObject("origin");
                    var scope=origin.getAsJsonObject("scope");UUID principal=UUID.fromString(command.has("principal")?command.get("principal").getAsString():origin.getAsJsonObject("principal").get("id").getAsString());
                    var caller=new TrustedContext(new PrincipalRef(principal),new ScopeRef(UUID.fromString(scope.get("worldId").getAsString()),UUID.fromString(scope.get("domainId").getAsString())));
                    var ref=new Ref(UUID.fromString(old.get("id").getAsString()),UUID.fromString(old.get("epoch").getAsString()),old.get("generation").getAsLong());
                    var owner=new Owner(UUID.fromString(old.getAsJsonObject("owner").get("job").getAsString()),old.getAsJsonObject("owner").get("generation").getAsLong());
                    try {
                        Lease found=kernel.leaseOwner().historical(ref,owner,caller).orElseThrow();
                        var result=map("status","PASS","lease",found,"hot",view.leases().leases().stream().anyMatch(l->l.id().equals(ref.id())));
                        if(command.has("staleControls")) {
                            if(found.state()==State.ACTIVE)throw new IllegalArgumentException("Retired evidence only");
                            var before=kernel.leaseOwner().snapshot();
                            result.put("validate",kernel.leaseOwner().validate(ref,caller));result.put("renew",kernel.leaseOwner().renew(List.of(ref),100,caller));
                            result.put("release",kernel.leaseOwner().release(List.of(ref),caller));result.put("hotUnchanged",before==kernel.leaseOwner().snapshot());
                        }
                        reply(id,result,null);
                    }catch(SecurityException denied){reply(id,map("status","DENIED"),null);}return;
                }
                if(op.equals("arm")) {
                    String point=command.get("point").getAsString();String token=UUID.fromString(command.get("token").getAsString()).toString();
                    if(!Set.of("AFTER_SEGMENT_ACK","AFTER_DIRECTORY_ACK","BEFORE_HOT_PUBLICATION","AFTER_HOT_ACKNOWLEDGEMENT").contains(point))throw new IllegalArgumentException("Known boundary required");
                    boolean hot=point.endsWith("PUBLICATION") || point.endsWith("ACKNOWLEDGEMENT");
                    var work=kernel.runtimeHistory().offer(writer->{writer.observePublication(p->{
                        if(p.name().equals(point))barrier(point,token,false);
                        else if(hot && p==ArchiveWriter.Point.AFTER_DIRECTORY_ACK)barrier("PREPARE_SAVE",token,true);
                    });return map("status","PASS","point",point,"token",token);});
                    if(work.isEmpty())reply(id,map("status","BUSY"),null);else {
                        kernel.leaseHistoryOwner().observe(p->{if(p.name().equals(point))barrier(point,token,false);});
                        work.get().whenComplete((value,error)->reply(id,value,error));
                    }return;
                }
                var work=kernel.runtimeHistory().offer(writer->{
                    if(op.equals("inventory")) {
                        var rows=writer.historyView().rows(ArchiveFormat.Owner.LEASES,ArchiveFormat.Kind.HISTORY).stream()
                                .map(r->map("id",r.id(),"sha256",sha256(r.payload()),"payload",new String(r.payload(),StandardCharsets.UTF_8))).toList();
                        return map("status","PASS","hot",view.leases(),"archived",rows,"manifest",writer.snapshot(),"jobs",view.jobs().jobs().size());
                    }
                    if(op.equals("pressure")) {
                        var guard=new ArchiveFormat.Guard(view.jobs().revision(),view.retentionGeneration());int added=0;
                        for(int n=0;n<128;n++) {
                            var result=writer.append(writer.snapshot(),ArchiveFormat.Owner.JOBS,guard,List.of(new ArchiveFormat.Row(ArchiveFormat.Kind.HISTORY,
                                    UUID.nameUUIDFromBytes(("fixture:a03-quota:"+n).getBytes(StandardCharsets.UTF_8)),new byte[64])),owner->guard);
                            if(result.status()==ArchiveWriter.Status.BACKPRESSURE)return map("status","PASS","fixture",true,"nativeEffects",0,"added",added,"manifest",writer.snapshot());
                            if(!result.acknowledged())throw new IOException("Quota fixture "+result.status());added++;
                        }
                        throw new IOException("Ordinary pressure not witnessed");
                    }
                    throw new IOException("Unknown A03 operation");
                });
                if(work.isEmpty())reply(id,map("status","BUSY"),null);else work.get().whenComplete((value,error)->reply(id,value,error));
            }catch(IllegalStateException starting){if(active!=null)reply(active.get("id").getAsString(),null,starting);}
            catch(Exception error){if(active!=null)reply(active.get("id").getAsString(),null,error);else System.err.println("A03 native probe "+error);}
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server->mailbox.shutdown());
    }
    private void barrier(String point,String token,boolean releasable)throws IOException {
        write("lease-cut-reached.json",map("process",process,"pid",ProcessHandle.current().pid(),"point",point,"token",token,"at",Instant.now().toString(),"thread",Thread.currentThread().getName()));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        while(System.nanoTime()<deadline) {
            Path release=root.resolve("server/lease-cut-release.json");
            if(releasable && Files.isRegularFile(release)) {
                var value=JsonParser.parseString(Files.readString(release)).getAsJsonObject();
                if(token.equals(value.get("token").getAsString()) && process.toString().equals(value.get("process").getAsString()))return;
            }
            try{Thread.sleep(10);}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IOException(stopped);}
        }
        throw new IOException("A03 witnessed boundary timed out without its intended crash");
    }
    private void poll() {
        try {
            Path file=root.resolve("server/lease-request.json");if(busy.get() || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>16384)return;
            var command=JsonParser.parseString(Files.readString(file)).getAsJsonObject();String id=command.get("id").getAsString();UUID.fromString(id);if(id.equals(seen))return;seen=id;
            if(!process.toString().equals(command.get("process").getAsString())){write("lease-response.json",map("id",id,"process",process,"status","STALE_PROCESS"));return;}
            if(busy.compareAndSet(false,true))request.set(command);
        }catch(Exception error){System.err.println("A03 mailbox "+error);}
    }
    private void reply(String id,Map<String,Object> result,Throwable error) {
        mailbox.execute(()->{try {var value=map("id",id,"process",process,"pid",ProcessHandle.current().pid());
            if(error!=null){value.put("status","FAIL");value.put("error",error.toString());}else value.putAll(result);write("lease-response.json",value);
        }finally{busy.set(false);}});
    }
    private void write(String name,Object value) {
        try {Path target=root.resolve("server").resolve(name),next=target.resolveSibling(name+".next");Files.writeString(next,json.toJson(value));Files.move(next,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(IOException error){throw new IllegalStateException(error);}
    }
    private static Map<String,Object> map(Object... pairs){var value=new LinkedHashMap<String,Object>();for(int n=0;n<pairs.length;n+=2)value.put((String)pairs[n],pairs[n+1]);return value;}
}
