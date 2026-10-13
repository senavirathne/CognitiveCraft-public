package dev.aivillages.fabric;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.Gson;
import dev.aivillages.core.kernel.*;
import static dev.aivillages.core.kernel.Contracts.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

/** Production KernelSession/Jobs physical admission. Mock principals are deliberately not GP-RB-01 network evidence. */
public final class OakLogKernelGameTests {
    private record Marker(UUID principal,UUID actor,UUID citizen,UUID run,String artifact,int x,int y,int z) { }
    @GameTest(maxTicks=3_000_000,padding=16)
    public void oakKernelAdmissionAndModelFreeSavedWorldReuse(GameTestHelper h) {
        var scene=new Scene(h);
        h.failIfEver(() -> { if(scene.failure!=null)h.fail(scene.failure); });
        h.succeedWhen(() -> { scene.step();h.assertTrue(scene.done,"Waiting for RB-001 phase="+scene.phase); });
    }
    private static final class Scene {
        final GameTestHelper h; final Path root,marker; final boolean warm,genuine;
        final CompletableFuture<Marker> loadedMarker;
        final OakLogFixtures.ControlledGeneration controlled=new OakLogFixtures.ControlledGeneration();
        final ServerPlayer owner,foreign;
        KernelSession session;ActorRef actor;BlockPos origin;Villager villager;UUID run;
        int phase,amount=4,before,round;long started,generationBefore;String artifact,failure;boolean done;
        CompletableFuture<Void> closing,writing;
        Scene(GameTestHelper h) {
            this.h=h;var world=h.getLevel().getServer().getWorldPath(LevelResource.ROOT);
            root=world.resolve("rb001-native-owner");marker=world.resolve("rb001-native-marker.json");
            warm="warm".equals(System.getenv("COGNITIVECRAFT_RB001_PHASE"));genuine="true".equals(System.getenv("COGNITIVECRAFT_RB001_GENUINE"));
            if(genuine && !warm && (System.getenv("COGNITIVECRAFT_OLLAMA_MODEL")==null || System.getenv("COGNITIVECRAFT_OLLAMA_MODEL").isBlank()))
                throw new IllegalStateException("Genuine RB gate requires explicit configured model");
            loadedMarker=warm ? CompletableFuture.supplyAsync(() -> {
                try { return new Gson().fromJson(Files.readString(marker),Marker.class); }
                catch(Exception e) { throw new CompletionException(e); }
            }) : null;
            owner=h.makeMockServerPlayerInLevel();foreign=h.makeMockServerPlayerInLevel();foreign.setUUID(UUID.randomUUID());
            session=new KernelSession(h.getLevel().getServer(),root,null,genuine || warm ? null : controlled);
            if(!warm) {
                origin=h.absolutePos(new BlockPos(64,0,64));setup();
                villager=h.getLevel().getEntitiesOfClass(Villager.class,new net.minecraft.world.phys.AABB(
                        origin.getX(),origin.getY(),origin.getZ(),origin.getX()+16,origin.getY()+5,origin.getZ()+11))
                        .stream().findFirst().orElseGet(() -> {
                            var v=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,65,1,65);v.setNoAi(false);return v;
                        });
            }
        }
        void setup() {
            BootstrapGameTests.forceFixtureChunks(h,origin);
            for(int x=0;x<16;x++)for(int z=0;z<10;z++)h.getLevel().setBlock(origin.offset(x,0,z),Blocks.STONE.defaultBlockState(),3);
            h.getLevel().setBlock(origin.offset(12,1,4),Blocks.CHEST.defaultBlockState(),3);
            h.getLevel().setBlock(origin.offset(12,1,7),Blocks.CHEST.defaultBlockState(),3);
            // A real obstruction requires the ordinary navigation owner to approach around it.
            h.getLevel().setBlock(origin.offset(3,1,3),Blocks.STONE.defaultBlockState(),3);
            h.getLevel().setBlock(origin.offset(3,2,3),Blocks.STONE.defaultBlockState(),3);
        }
        void source(int count,int z) {
            for(int x=4;x<12;x++)h.getLevel().setBlock(origin.offset(x,1,z),x<4+count ? Blocks.OAK_LOG.defaultBlockState() : Blocks.AIR.defaultBlockState(),3);
        }
        BlockPos destination() { return origin.offset(12,1,round==0 ? 4 : 7); }
        int stock() { return ((Container)h.getLevel().getBlockEntity(destination())).countItem(Items.OAK_LOG); }
        void submit() {
            source(amount,round==0 ? 4 : 6);
            var lo=origin.offset(4,1,round==0 ? 4 : 6);var hi=origin.offset(11,1,round==0 ? 4 : 6);var chest=destination();
            before=stock();started=h.getLevel().getGameTime();generationBefore=session.generationCalls();
            var s=session.gatherOak(owner,actor.citizenId(),amount,lo.getX(),lo.getY(),lo.getZ(),hi.getX(),hi.getY(),hi.getZ(),chest.getX(),chest.getY(),chest.getZ());
            h.assertTrue(s.accepted(),"Oak canonical submission rejected: "+s);run=s.id();
        }
        void step() {
            session.tick();if(!session.identityReady())return;
            if(phase==0 && warm) {
                if(!loadedMarker.isDone())return;var m=loadedMarker.join();
                owner.setUUID(m.principal());origin=new BlockPos(m.x(),m.y(),m.z());BootstrapGameTests.forceFixtureChunks(h,origin);
                var e=h.getLevel().getEntity(m.actor());if(!(e instanceof Villager v))return;villager=v;
                actor=session.citizen(owner,m.citizen()).actor();artifact=m.artifact();session.inference(false);
                h.assertTrue(stock()==4,"Saved-world standalone first destination changed");
                h.assertTrue(((Container)h.getLevel().getBlockEntity(origin.offset(12,1,7))).countItem(Items.OAK_LOG)==1,
                        "Saved-world standalone second destination changed");amount=7;round=2;phase=2;
            } else if(phase==0) {
                owner.setPos(origin.getX()+1.5,origin.getY()+1,origin.getZ()+1.5);
                actor=session.enroll(owner,villager);phase=1;
            } else if(phase==1) {
                owner.setPos(origin.getX()+.5,origin.getY()+1,origin.getZ()+8.5);foreign.setPos(origin.getX()+.5,origin.getY()+1,origin.getZ()+9.5);
                session.name(owner,actor.citizenId(),"Ada");session.inference(true);phase=2;
            } else if(phase==2) { submit();phase=3; }
            else if(phase==3) {
                var view=session.status(owner,run);
                if(view.phase()!=BootstrapController.Phase.TERMINAL)return;
                boolean acquired=view.research()!=null && view.research().status()==Outcomes.ResearchStatus.ADMITTED;
                if(!(acquired || view.outcome()!=null && view.outcome().status()==Outcomes.ExecutionStatus.SUCCEEDED)) {
                    failure="Oak acquisition failed "+KernelRunStatus.describe(view);return;
                }
                h.assertTrue(stock()==before+amount,"Actual oak slot conservation failed");
                h.assertTrue(villager.getInventory().countItem(Items.OAK_LOG)==0,"Unexpected carried fixture remainder");
                long deposited=view.resourceReceipts().stream().filter(r->r.stage()==ResourceDelivery.Stage.DEPOSIT).mapToLong(ResourceDelivery.Receipt::quantity).sum();
                h.assertTrue(deposited==amount && view.receipts().isEmpty(),"Resource receipt type/quantity mismatch");
                h.assertTrue(h.getLevel().getGameTime()-started>=60L*amount,"Bare-hand timing was bypassed");
                h.assertTrue(session.job(run,session.citizen(owner,actor.citizenId()).owner()).orElseThrow().state()==Jobs.State.SUCCEEDED,"Jobs did not own completion");
                var job=session.job(run,session.citizen(owner,actor.citizenId()).owner()).orElseThrow();
                h.assertTrue(job.dependencies().isEmpty() && job.children().isEmpty(),"Acquisition was coupled to a downstream Job");
                h.assertTrue(view.resourceReceipts().stream().allMatch(r -> r.destination().x()==destination().getX()
                        && r.destination().y()==destination().getY() && r.destination().z()==destination().getZ()),
                        "Runtime destination binding was baked into the method");
                try { session.status(foreign,run);h.fail("Foreign principal inspected oak run"); }
                catch(SecurityException expected) { }
                if(round==0 && !warm) {
                    h.assertTrue(acquired,"First candidate bypassed real trial/admission");artifact=view.marker().artifactSha256();
                    h.assertTrue(job.current().terminal()!=null && job.current().terminal().completion()!=null
                            && job.current().terminal().completion().scope().equals("attributable-resource-delivery"),
                            "Oak admission retained the legacy crop completion classification");
                    h.assertTrue(genuine ? session.generationCalls()>generationBefore : controlled.calls==1,"Generation identity/counter proof absent");
                    session.inference(false);amount=1;round=1;phase=2;
                } else {
                    h.assertTrue(session.generationCalls()==generationBefore,"Known oak execution called inference");
                    h.assertTrue(artifact.equals(view.marker().artifactSha256()),"Fresh binding did not reuse exact artifact");
                    System.out.println("RB001_NATIVE phase="+(warm ? "warm" : "cold")+" genuine="+genuine+" quantity="+amount
                            +" ref="+artifact+" run="+run+" receipts="+view.resourceReceipts()+" job="+session.job(run,session.citizen(owner,actor.citizenId()).owner()).orElseThrow());
                    if(warm) { closing=session.storeClosure();session.close();phase=5; }
                    else {
                        var m=new Marker(owner.getUUID(),villager.getUUID(),actor.citizenId(),run,artifact,origin.getX(),origin.getY(),origin.getZ());
                        writing=CompletableFuture.runAsync(() -> {try { Files.writeString(marker,new Gson().toJson(m)); }catch(Exception e){throw new CompletionException(e);} });
                        phase=4;
                    }
                }
            } else if(phase==4) {
                if(!writing.isDone())return;writing.join();closing=session.storeClosure();session.close();phase=5;
            } else if(phase==5) { if(closing.isDone()) { closing.join();done=true; } }
        }
    }
}
