package dev.aivillages.fabric;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Required warm fixture in the dedicated workflow: a second actual Minecraft JVM. */
public final class PrimitiveDiagnosticRestartGameTests {
    private static final String MARKER="primitive-diagnostic-restart.json";
    static Map<String,String> hashes(Path root) throws Exception {
        Map<String,String> hashes=new TreeMap<>();
        try(var paths=Files.walk(root)) {
            for(Path file:paths.filter(Files::isRegularFile).filter(p->!p.getFileName().toString().equals(MARKER)).toList()) {
                if(hashes.size()>=256||Files.size(file)>1048576)throw new AssertionError("Fixture store cap");
                hashes.put(root.relativize(file).toString(),HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
            }
        }
        return Map.copyOf(hashes);
    }
    static CompletableFuture<Void> record(Path root,Villager villager,ActorRef actor,ServerPlayer owner,BlockPos origin) {
        // Capture immutable test values on the server thread before the off-thread file operation.
        Map<String,Object> identity=Map.of("actor",actor.citizenId().toString(),"entity",villager.getUUID().toString(),
                "principal",owner.getUUID().toString(),"x",(long)Math.floor(villager.getX()),
                "y",(long)Math.floor(villager.getY()),"z",(long)Math.floor(villager.getZ()),
                "source",List.of((long)origin.getX()+2,(long)origin.getY()+1,(long)origin.getZ()+3),
                "destination",List.of((long)origin.getX()+4,(long)origin.getY()+1,(long)origin.getZ()+4));
        return CompletableFuture.runAsync(()->{
            try {
                Files.writeString(root.resolve(MARKER),StrictJson.canonical(Map.of("schema",1L,"identity",identity,"hashes",hashes(root))));
            } catch(Exception failed){throw new IllegalStateException("Synthetic restart marker unavailable",failed);}
        });
    }
    @GameTest(maxTicks=1800,padding=32)
    public void separateServerRestartReplaysPrivateDemandAndReusesSavedSkillWithoutModel(GameTestHelper h) {
        if(!"warm".equals(System.getenv("COGNITIVECRAFT_PRIMITIVE_DIAGNOSTIC_RESTART"))){h.succeed();return;}
        var driver=new Driver(h);
        h.succeedWhen(()->{driver.step();h.assertTrue(driver.phase==6,"Waiting for actual saved-world diagnostic reload phase="+driver.phase);});
    }
    private static final class Driver {
        final GameTestHelper h;final Path knownRoot,recordRoot;
        final CompletableFuture<Map<String,Object>> marker;
        CompletableFuture<Map<String,String>> before,after;
        CompletableFuture<Void> diagnosticClose,storeClose;
        PrimitiveDiagnosticService diagnostics;KernelSession session;
        ServerPlayer owner;Villager actor;UUID citizen,job;Map<String,Object> identity;
        int phase,generationCalls,initialWheat;
        Driver(GameTestHelper h) {
            this.h=h;Path world=h.getLevel().getServer().getWorldPath(LevelResource.ROOT);
            knownRoot=world.resolve("cognitivecraft-diagnostic-blocked");recordRoot=world.resolve("cognitivecraft-primitive-store-fixture");
            marker=CompletableFuture.supplyAsync(()->{
                try{return StrictJson.object(Files.readString(knownRoot.resolve(MARKER)));}
                catch(Exception failed){throw new IllegalStateException("Cold synthetic proof missing",failed);}
            });
        }
        @SuppressWarnings("unchecked") void step() {
            if(phase==6)return;java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            if(session!=null&&phase>=3&&phase<=4)session.tick();
            switch(phase) {
                case 0 -> {
                    h.assertTrue(marker.isDone(),"Reading cold proof off-thread");
                    var m=marker.join();identity=(Map<String,Object>)m.get("identity");
                    before=CompletableFuture.supplyAsync(()->{try{return hashes(knownRoot);}catch(Exception e){throw new IllegalStateException(e);}});
                    phase=1;
                }
                case 1 -> {
                    h.assertTrue(before.isDone(),"Comparing saved authoritative stores");
                    h.assertTrue(before.join().equals(marker.join().get("hashes")),"Separate process changed saved owner stores before diagnostic replay");
                    diagnostics=PrimitiveDiagnosticService.open(recordRoot,PrimitiveDiagnostics.Policy.metadata(),Clock.systemUTC(),ignored->{});
                    phase=2;
                }
                case 2 -> {
                    if(diagnosticClose==null) {
                        h.assertTrue(diagnostics.status().state()==PrimitiveDiagnosticService.State.READY,"Replaying actual private segments in second JVM");
                        h.assertTrue(diagnostics.status().aggregateCount()==1&&diagnostics.status().persistedSnapshots()==0,"Reload inflated demand or replayed logging");
                        diagnosticClose=diagnostics.closeAsync();
                    }
                    if(after==null)after=CompletableFuture.supplyAsync(()->{try{return hashes(knownRoot);}catch(Exception e){throw new IllegalStateException(e);}});
                    h.assertTrue(after.isDone(),"Comparing bytes after bounded diagnostic replay");
                    h.assertTrue(after.join().equals(before.join()),"Diagnostics changed admitted bodies or unrelated owner stores");
                    h.assertTrue(diagnosticClose.isDone(),"Closing bounded replay worker");
                    int x=Math.toIntExact((Long)identity.get("x")),y=Math.toIntExact((Long)identity.get("y")),z=Math.toIntExact((Long)identity.get("z"));
                    BootstrapGameTests.forceFixtureChunks(h,new BlockPos(x,y,z));
                    h.getLevel().getChunk(x>>4,z>>4);
                    var entity=h.getLevel().getEntity(UUID.fromString((String)identity.get("entity")));
                    h.assertTrue(entity instanceof Villager,"Saved physical citizen missing after server process restart");
                    actor=(Villager)entity;actor.setNoAi(false);
                    owner=h.makeMockServerPlayerInLevel();owner.setUUID(UUID.fromString((String)identity.get("principal")));
                    owner.setPos(actor.getX(),actor.getY(),actor.getZ());citizen=UUID.fromString((String)identity.get("actor"));
                    GenerationPort offline=new GenerationPort() {
                        @Override public Generation.Handle generate(Generation.Request request,Budgets.InferenceLimits limits,Budgets.Ledger ledger) {
                            generationCalls++;throw new AssertionError("Saved known method invoked model in second process");
                        }
                        @Override public Generation.Descriptor descriptor(){return new Generation.Descriptor("offline-reload-v1","unavailable-test-backend",null);}
                        @Override public Generation.Status status(){return new Generation.Status(Generation.State.UNAVAILABLE,0,null,Generation.Compute.NOT_STARTED,false,false);}
                    };
                    session=new KernelSession(h.getLevel().getServer(),knownRoot,null,offline,PrimitiveDiagnostics.noop());phase=3;
                }
                case 3 -> {
                    h.assertTrue(session.identityReady(),"Loading actual saved skill owner composition");
                    session.inference(false);
                    var source=(List<Long>)identity.get("source");var dest=(List<Long>)identity.get("destination");
                    BlockPos s=new BlockPos(Math.toIntExact(source.get(0)),Math.toIntExact(source.get(1)),Math.toIntExact(source.get(2)));
                    BlockPos d=new BlockPos(Math.toIntExact(dest.get(0)),Math.toIntExact(dest.get(1)),Math.toIntExact(dest.get(2)));
                    h.getLevel().setBlockAndUpdate(s.below(),Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));
                    h.getLevel().setBlockAndUpdate(s,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));
                    initialWheat=((Container)h.getLevel().getBlockEntity(d)).countItem(Items.WHEAT);
                    h.assertTrue(initialWheat==6,"Saved physical delivery missing in second process");
                    var submitted=session.queueHarvest(owner,citizen,1,new Cuboid("minecraft:overworld",s.getX(),s.getY(),s.getZ(),s.getX(),s.getY(),s.getZ()),
                            new ContainerRef("minecraft:overworld",d.getX(),d.getY(),d.getZ()));
                    h.assertTrue(submitted.accepted(),"Saved known method rejected with model/diagnostics off");job=submitted.id();phase=4;
                }
                case 4 -> {
                    h.assertTrue(session.queuedJobIds(owner,false).contains(job),"Acknowledging reloaded physical job");
                    h.assertTrue(session.queuedJob(owner,job).state()==Jobs.State.SUCCEEDED,"Saved method must deliver without model or diagnostics");
                    var dest=(List<Long>)identity.get("destination");
                    var d=new BlockPos(Math.toIntExact(dest.get(0)),Math.toIntExact(dest.get(1)),Math.toIntExact(dest.get(2)));
                    h.assertTrue(((Container)h.getLevel().getBlockEntity(d)).countItem(Items.WHEAT)==initialWheat+1&&generationCalls==0,
                            "Model-free saved delivery lost, duplicated, or invoked model");
                    session.clearDispatchCache();session.close();storeClose=session.storeClosure();phase=5;
                }
                case 5 -> {
                    h.assertTrue(storeClose.isDone()&&!storeClose.isCompletedExceptionally(),"Closing saved-world owners");
                    System.out.println("IMP-014.1 cold process reload accepted replayOccurrences=1 admittedBytes=identical savedWheat=6 deliveredAfterRestart=1 inference=0 diagnosticsOffForKnownJob=true");phase=6;
                }
                default -> throw new IllegalStateException("Diagnostic restart phase");
            }
        }
    }
}
