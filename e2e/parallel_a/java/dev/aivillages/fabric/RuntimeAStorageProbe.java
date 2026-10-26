package dev.aivillages.fabric;

import com.google.gson.*;
import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.runtime.persistence.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static dev.aivillages.core.kernel.runtime.persistence.ArchiveFormat.*;

/** Isolated A02 native host fixture. Its opaque snapshots/benchmark IDs never count as physical proof. */
public final class RuntimeAStorageProbe implements ModInitializer {
    private final Path root=Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR"));
    private final UUID process=UUID.randomUUID();private final Gson json=new Gson();
    private final ScheduledExecutorService mailbox=Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService baselineIo=Executors.newSingleThreadExecutor();
    private final AtomicReference<JsonObject> request=new AtomicReference<>();private final AtomicBoolean busy=new AtomicBoolean();
    private final AtomicReference<KernelSession.RuntimeStorageView> live=new AtomicReference<>();
    private final List<Long> capture=new ArrayList<>();private boolean measuring,readySent;private String seen="";
    public void onInitialize() {
        if(!"true".equalsIgnoreCase(System.getenv("COGNITIVECRAFT_A02_PROBE"))){mailbox.shutdown();baselineIo.shutdown();return;}
        mailbox.scheduleWithFixedDelay(this::poll,0,100,TimeUnit.MILLISECONDS);
        ServerTickEvents.END_SERVER_TICK.register(server->{
            try {
                var kernel=AiVillages.kernel();if(kernel==null)return;long start=System.nanoTime();var view=kernel.runtimeStorageView();long nanos=System.nanoTime()-start;live.set(view);
                if(measuring && capture.size()<5000)capture.add(nanos);
                if(!readySent){readySent=true;mailbox.execute(()->write("persistence-ready.json",map("process",process,"pid",ProcessHandle.current().pid(),"world",view.jobs().worldId())));}
                if(!view.ready())return;var command=request.getAndSet(null);if(command==null)return;
                String id=command.get("id").getAsString(),op=command.get("op").getAsString();
                if(op.equals("status")){reply(id,map("status","PASS","storage",kernel.runtimeHistoryStatus(),"view",summary(view)),null);return;}
                if(op.equals("begin-window")){capture.clear();measuring=true;reply(id,map("status","PASS"),null);return;}
                if(op.equals("end-window")){measuring=false;reply(id,map("status","PASS","captureNanos",List.copyOf(capture)),null);return;}
                Path world=server.getWorldPath(LevelResource.ROOT);
                if(op.equals("baseline-benchmark")) {
                    CompletableFuture.supplyAsync(()->{try{return baseline(view);}catch(Exception e){throw new CompletionException(e);}},baselineIo)
                            .whenComplete((value,error)->reply(id,value,error));return;
                }
                var work=kernel.runtimeHistory().offer(writer->switch(op) {
                    case "compat" -> compatibility(writer,view);
                    case "verify" -> {
                        UUID key=UUID.fromString(command.get("key").getAsString());var row=writer.lookup(new Key(Owner.JOBS,Kind.HISTORY,key)).orElseThrow();
                        yield map("status","PASS","payloadSha256",sha256(row.payload()),"manifest",writer.snapshot(),"view",summary(view));
                    }
                    case "candidate-benchmark" -> candidate(view);
                    case "headroom" -> headroom(view);
                    default -> throw new java.io.IOException("Unknown A02 fixture operation");
                });
                if(work.isEmpty()){reply(id,map("status","BACKPRESSURE","storage",kernel.runtimeHistoryStatus()),null);return;}
                work.get().whenComplete((value,error)->reply(id,value,error));
            }catch(IllegalStateException starting){/* no fabricated startup snapshot */}
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{mailbox.shutdown();baselineIo.shutdown();});
    }
    private void poll() {
        try {
            Path path=root.resolve("server/persistence-request.json");if(busy.get() || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>4096)return;
            var value=JsonParser.parseString(Files.readString(path)).getAsJsonObject();String id=value.get("id").getAsString();if(id.equals(seen))return;
            UUID.fromString(id);seen=id;
            if(!process.toString().equals(value.get("process").getAsString())){write("persistence-response.json",map("id",id,"process",process,"status","STALE_PROCESS"));return;}
            if(busy.compareAndSet(false,true))request.set(value);
        }catch(Exception failure){System.err.println("A02 fixture mailbox: "+failure);}
    }
    private void reply(String id,Map<String,Object> value,Throwable error) {
        mailbox.execute(()->{try {
            var body=map("id",id,"process",process,"pid",ProcessHandle.current().pid());
            if(error!=null){body.put("status","FAIL");body.put("error",error.toString());}
            else body.putAll(value);write("persistence-response.json",body);
        }finally{busy.set(false);}});
    }
    private Map<String,Object> compatibility(ArchiveWriter writer,KernelSession.RuntimeStorageView view)throws java.io.IOException {
        byte[] snapshot=JobJournal.encode(view.jobs());String source=sha256(snapshot);
        byte[] prefix="fixture:opaque-native-job-snapshot\n".getBytes(StandardCharsets.UTF_8);byte[] payload=new byte[prefix.length+snapshot.length];
        System.arraycopy(prefix,0,payload,0,prefix.length);System.arraycopy(snapshot,0,payload,prefix.length,snapshot.length);
        UUID id=UUID.nameUUIDFromBytes(("fixture:world-snapshot:"+source).getBytes(StandardCharsets.UTF_8));
        Guard guard=new Guard(view.jobs().revision(),view.retentionGeneration());ArchiveWriter.Fences fences=owner->{var current=live.get();return current!=null && current.ready()?new Guard(owner==Owner.JOBS?current.jobs().revision():current.leases().revision(),current.retentionGeneration()):null;};
        var result=writer.append(writer.snapshot(),Owner.JOBS,guard,List.of(new Row(Kind.HISTORY,id,payload)),fences);
        if(!result.acknowledged())throw new java.io.IOException("Native metadata append: "+result.status());
        var receipt=writer.snapshot().migrations().stream().filter(m->m.owner()==Owner.JOBS && m.sourceDigest().equals(source)).findFirst().orElse(null);
        if(receipt==null) {
            receipt=new Migration(UUID.nameUUIDFromBytes(("fixture:migration:"+source).getBytes(StandardCharsets.UTF_8)),Owner.JOBS,2,source,
                    result.acknowledgement().revision(),result.acknowledgement().digest());
            var migrated=writer.migration(writer.snapshot(),receipt,snapshot,guard,fences);if(!migrated.acknowledged())throw new java.io.IOException("Native migration receipt: "+migrated.status());
        }
        return map("status","PASS","executionClass","NATIVE_HOST_OPAQUE_REAL_METADATA","physicalProof",false,"domainEviction",false,
                "key",id,"sourceSha256",source,"payloadSha256",sha256(payload),"manifest",writer.snapshot(),"migration",receipt,"view",summary(view));
    }
    private Map<String,Object> baseline(KernelSession.RuntimeStorageView view)throws java.io.IOException {
        Path fixture=root.resolve("bench-baseline-world");Path directory=fixture.resolve(JobJournal.WORLD_RELATIVE_PATH);Files.createDirectories(directory);
        byte[] source=JobJournal.encode(view.jobs());Files.write(directory.resolve("state.jsonl"),source);
        var samples=new ArrayList<Long>();
        try(var journal=JobJournal.open(fixture,view.jobs().worldId())) {
            for(int n=0;n<64;n++){long start=System.nanoTime();var old=journal.snapshot();journal.replace(old,new Jobs.Snapshot(old.worldId(),old.revision()+1,old.jobs(),old.allocations()));samples.add(System.nanoTime()-start);}
        }
        return map("status","PASS","mode","legacy-full-snapshot-force","fixture",true,"samplesNanos",samples,"sourceBytes",source.length,"sourceSha256",sha256(source));
    }
    private Map<String,Object> candidate(KernelSession.RuntimeStorageView view)throws java.io.IOException {
        var samples=new ArrayList<Long>();UUID fixtureId=UUID.nameUUIDFromBytes(("fixture:benchmark:"+view.jobs().worldId()).getBytes(StandardCharsets.UTF_8));
        Path fixture=root.resolve("bench-candidate-world");Guard guard=new Guard(1,1);byte[] last=null;
        try(var writer=ArchiveWriter.open(fixture,fixtureId,PersistencePolicy.defaults())) {
            for(int n=0;n<64;n++){long start=System.nanoTime();last=JobJournal.encode(view.jobs());UUID id=UUID.nameUUIDFromBytes(("fixture:benchmark-row:"+n).getBytes(StandardCharsets.UTF_8));
                var result=writer.append(writer.snapshot(),Owner.JOBS,guard,List.of(new Row(Kind.HISTORY,id,last)),owner->guard);if(!result.acknowledged())throw new java.io.IOException("Benchmark pressure before 64 samples: "+result.status());samples.add(System.nanoTime()-start);}
        }
        return map("status","PASS","mode","immutable-segment-and-manifest-force","fixture",true,"samplesNanos",samples,"sourceBytes",last.length,"sourceSha256",sha256(last));
    }
    private Map<String,Object> headroom(KernelSession.RuntimeStorageView view)throws java.io.IOException {
        UUID fixtureId=UUID.nameUUIDFromBytes(("fixture:headroom:"+view.jobs().worldId()).getBytes(StandardCharsets.UTF_8));Guard guard=new Guard(1,1);
        var slots=new ArrayList<Slot>();int admitted=0,settled=0;
        try(var writer=ArchiveWriter.open(root.resolve("headroom-fixture-world"),fixtureId,PersistencePolicy.defaults())) {
            for(int n=0;n<8;n++){UUID attempt=UUID.nameUUIDFromBytes(("fixture:attempt:"+n).getBytes(StandardCharsets.UTF_8));var slot=new Slot(attempt,Owner.JOBS,UUID.nameUUIDFromBytes(("fixture:root:"+n).getBytes(StandardCharsets.UTF_8)),attempt,1,65_536,65_536,Phase.RESERVED);slots.add(slot);
                if(!writer.reserve(writer.snapshot(),slot,guard,owner->guard).acknowledged() || !writer.record(writer.snapshot(),attempt,Kind.INTENT,new byte[]{1},guard,owner->guard).acknowledged())throw new java.io.IOException("Reservation not acknowledged");}
            for(int n=0;n<128;n++){var row=new Row(Kind.HISTORY,UUID.nameUUIDFromBytes(("fixture:pressure:"+n).getBytes(StandardCharsets.UTF_8)),new byte[48_000]);var result=writer.append(writer.snapshot(),Owner.JOBS,guard,List.of(row),owner->guard);if(result.status()==ArchiveWriter.Status.BACKPRESSURE)break;if(!result.acknowledged())throw new java.io.IOException("Pressure outcome");admitted++;}
            if(admitted==0 || admitted==128)throw new java.io.IOException("Ordinary pressure was not witnessed");
            for(var slot:slots){for(var kind:List.of(Kind.CANCELLATION,Kind.RESULT,Kind.SETTLEMENT))if(!writer.record(writer.snapshot(),slot.id(),kind,new byte[]{2},guard,owner->guard).acknowledged())throw new java.io.IOException("Reserved publication lost under ordinary pressure");settled++;}
        }
        return map("status","PASS","executionClass","NATIVE_HOST_FILESYSTEM_FIXTURE","fixture",true,"nativeEffects",0,"ordinaryAdmittedBeforePressure",admitted,"reservedOriginalsSettled",settled);
    }
    private static Map<String,Object> summary(KernelSession.RuntimeStorageView v){return map("world",v.jobs().worldId(),"jobRevision",v.jobs().revision(),"leaseRevision",v.leases().revision(),"retentionGeneration",v.retentionGeneration(),"jobs",v.jobs().jobs().size(),"ready",v.ready());}
    private void write(String name,Object value) {
        try {Path path=root.resolve("server").resolve(name),next=path.resolveSibling(name+".next");Files.writeString(next,json.toJson(value));Files.move(next,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(Exception failure){System.err.println("A02 fixture evidence write failed: "+failure);}
    }
    private static Map<String,Object> map(Object... pairs){var result=new LinkedHashMap<String,Object>();for(int n=0;n<pairs.length;n+=2)result.put((String)pairs[n],pairs[n+1]);return result;}
}
