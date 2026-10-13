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

/** Real installed recipes through production KernelSession, Jobs and leases; native mock callers are not network evidence. */
public final class OakPlankKernelGameTests {
    private record Marker(UUID principal,UUID originalActor,UUID citizen,String artifact,int x,int y,int z,long process) { }
    @GameTest(maxTicks=24_000,padding=16)
    public void stockedPlanksGenuineAdmissionAndIndependentOfflineSameSaveReuse(GameTestHelper h) {
        var scene=new Scene(h);h.failIfEver(()->{if(scene.failure!=null)h.fail(scene.failure);});
        h.succeedWhen(()->{scene.step();h.assertTrue(scene.done,"Waiting for RB-002 phase="+scene.phase);});
    }
    private static final class Scene {
        final GameTestHelper h;final Path root,marker;final boolean warm,genuine;
        final ServerPlayer owner,foreign;final OakPlankFixtures.ControlledGeneration controlled=new OakPlankFixtures.ControlledGeneration();
        final CompletableFuture<Marker> loadedMarker;KernelSession session;BlockPos origin;Villager villager;
        ActorRef actor;UUID run,originalActor;int phase,round,amount=8,before,actorBefore,logsBefore;
        long generationBefore;String artifact,failure;boolean done;CompletableFuture<Void> writing,closing;
        Scene(GameTestHelper h) {
            this.h=h;Path world=h.getLevel().getServer().getWorldPath(LevelResource.ROOT);
            root=world.resolve("rb002-native-owner");marker=world.resolve("rb002-native-marker.json");
            warm="warm".equals(System.getenv("COGNITIVECRAFT_RB002_PHASE"));genuine="true".equals(System.getenv("COGNITIVECRAFT_RB002_GENUINE"));
            if(genuine && !warm && (System.getenv("COGNITIVECRAFT_OLLAMA_MODEL")==null || System.getenv("COGNITIVECRAFT_OLLAMA_MODEL").isBlank()))
                throw new IllegalStateException("Genuine configured model must be explicit");
            loadedMarker=warm ? CompletableFuture.supplyAsync(()->{try{return new Gson().fromJson(Files.readString(marker),Marker.class);}
                catch(Exception e){throw new CompletionException(e);}}) : null;
            owner=h.makeMockServerPlayerInLevel();foreign=h.makeMockServerPlayerInLevel();foreign.setUUID(UUID.randomUUID());
            session=new KernelSession(h.getLevel().getServer(),root,null,genuine || warm ? null : controlled);
            if(!warm) {
                origin=h.absolutePos(new BlockPos(64,0,64));setup();villager=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,65,1,65);
                villager.setNoAi(false);villager.setPersistenceRequired();originalActor=villager.getUUID();
                villager.getInventory().setItem(0,new ItemStack(Items.OAK_PLANKS,7));villager.getInventory().setItem(1,new ItemStack(Items.OAK_LOG,3));
            }
        }
        void setup() {
            BootstrapGameTests.forceFixtureChunks(h,origin);
            for(int x=0;x<16;x++)for(int z=0;z<10;z++)h.getLevel().setBlock(origin.offset(x,0,z),Blocks.STONE.defaultBlockState(),3);
            for(var at:List.of(origin.offset(4,1,4),origin.offset(4,1,7),origin.offset(7,1,4),origin.offset(7,1,7)))
                h.getLevel().setBlock(at,Blocks.CHEST.defaultBlockState(),3);
            container(origin.offset(4,1,7)).setItem(2,new ItemStack(Items.OAK_PLANKS,11));
            container(origin.offset(7,1,4)).setItem(0,new ItemStack(Items.OAK_PLANKS,20));
            h.getLevel().setBlock(origin.offset(10,1,1),Blocks.OAK_LOG.defaultBlockState(),3);
        }
        Container container(BlockPos at) {return (Container)h.getLevel().getBlockEntity(at);}
        BlockPos source() {return origin.offset(4,1,round==1 || round==3 ? 7 : 4);}
        BlockPos destination() {return warm ? origin.offset(7,1,7) : round==1 || round==3 ? source() : origin.offset(7,1,4);}
        void submit() {
            Container from=container(source());from.setItem(0,new ItemStack(Items.OAK_LOG,1));
            int needed=OakPlankProduction.batches(amount);from.setItem(1,needed>1 ? new ItemStack(Items.OAK_LOG,needed-1) : ItemStack.EMPTY);
            before=container(destination()).countItem(Items.OAK_PLANKS);actorBefore=villager.getInventory().countItem(Items.OAK_PLANKS);
            logsBefore=villager.getInventory().countItem(Items.OAK_LOG);generationBefore=session.generationCalls();
            var a=source();var b=destination();var submitted=session.makePlanks(owner,actor.citizenId(),amount,a.getX(),a.getY(),a.getZ(),b.getX(),b.getY(),b.getZ());
            h.assertTrue(submitted.accepted(),"Stocked plank request rejected: "+submitted);run=submitted.id();
        }
        void step() {
            if(phase==5) {if(closing.isDone()) {closing.join();done=true;}return;}
            session.tick();if(!session.identityReady())return;
            if(phase<=2 && (!session.jobsReady() || !session.leasesReady()))return;
            if(phase==0 && warm) {
                if(!loadedMarker.isDone())return;var m=loadedMarker.join();
                h.assertTrue(m.process()!=ProcessHandle.current().pid(),"Offline test reused its cold JVM");
                owner.setUUID(m.principal());origin=new BlockPos(m.x(),m.y(),m.z());BootstrapGameTests.forceFixtureChunks(h,origin);
                var old=h.getLevel().getEntity(m.originalActor());h.assertTrue(old instanceof Villager,"Saved citizen missing");
                h.assertTrue(((Villager)old).getInventory().countItem(Items.OAK_PLANKS)==13,"Cold surplus/old stock changed across process restart");
                h.assertTrue(container(origin.offset(4,1,7)).countItem(Items.OAK_PLANKS)==80,"Saved same-container output changed");
                h.assertTrue(container(origin.offset(7,1,4)).countItem(Items.OAK_PLANKS)==29,"Saved standalone output changed");
                var relative=origin.offset(1,1,1).subtract(h.absolutePos(BlockPos.ZERO));
                villager=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,relative.getX(),relative.getY(),relative.getZ());villager.setNoAi(false);villager.setPersistenceRequired();
                owner.setPos(villager.getX(),villager.getY(),villager.getZ());actor=session.enroll(owner,villager);originalActor=m.originalActor();
                artifact=m.artifact();amount=4;session.inference(false);phase=1;
            } else if(phase==0) {
                owner.setPos(villager.getX(),villager.getY(),villager.getZ());actor=session.enroll(owner,villager);phase=1;
            } else if(phase==1) {
                if(!warm) {session.name(owner,actor.citizenId(),"Ada");session.inference(true);}phase=2;
            } else if(phase==2) {submit();phase=3;}
            else if(phase==3) {
                var view=session.status(owner,run);if(view.storageError()!=null) {failure="Durable production failed: "+KernelRunStatus.describe(view);return;}if(view.phase()!=BootstrapController.Phase.TERMINAL)return;
                boolean acquired=view.research()!=null && view.research().status()==Outcomes.ResearchStatus.ADMITTED;
                if(!(acquired || view.outcome()!=null && view.outcome().status()==Outcomes.ExecutionStatus.SUCCEEDED)) {failure="Plank production failed: "+KernelRunStatus.describe(view);return;}
                int batches=OakPlankProduction.batches(amount),surplus=batches*4-amount;
                h.assertTrue(container(destination()).countItem(Items.OAK_PLANKS)==before+amount,"Actual deposited quantity mismatch");
                h.assertTrue(container(source()).countItem(Items.OAK_LOG)==0,"Actual input consumption mismatch");
                h.assertTrue(villager.getInventory().countItem(Items.OAK_PLANKS)==actorBefore+surplus,"Surplus was discarded or old output credited");
                h.assertTrue(villager.getInventory().countItem(Items.OAK_LOG)==logsBefore,"Preexisting actor logs were erased");
                h.assertTrue(h.getLevel().getBlockState(origin.offset(10,1,1)).is(Blocks.OAK_LOG),"Stocked production harvested a log");
                var receipts=view.resourceReceipts();h.assertTrue(view.receipts().isEmpty() && receipts.size()==batches*3,"Wrong evidence type/receipt cardinality");
                h.assertTrue(receipts.stream().filter(r->r.stage()==ResourceDelivery.Stage.WITHDRAW).mapToLong(ResourceDelivery.Receipt::quantity).sum()==batches,"Withdrawal lineage missing");
                h.assertTrue(receipts.stream().filter(r->r.stage()==ResourceDelivery.Stage.CRAFT).mapToLong(ResourceDelivery.Receipt::quantity).sum()==batches*4L,"Installed craft output missing");
                h.assertTrue(receipts.stream().filter(r->r.stage()==ResourceDelivery.Stage.DEPOSIT).mapToLong(ResourceDelivery.Receipt::quantity).sum()==amount,"New deposit attribution mismatch");
                h.assertTrue(receipts.stream().allMatch(r->r.transformation()!=null && r.dropIds().isEmpty()),"Crafting masqueraded as harvesting");
                var job=session.job(run,session.citizen(owner,actor.citizenId()).owner()).orElseThrow();
                h.assertTrue(job.state()==Jobs.State.SUCCEEDED && job.fulfilled()==amount && job.children().isEmpty() && job.dependencies().isEmpty(),"Jobs did not own independent completion");
                try {session.status(foreign,run);h.fail("Second principal inspected private production");}catch(SecurityException expected) { }
                if(round==0 && !warm) {
                    h.assertTrue(acquired,"Cold candidate bypassed compile/fixture/physical admission");artifact=view.marker().artifactSha256();
                    h.assertTrue(genuine ? session.generationCalls()>generationBefore : controlled.calls==1,"Acquisition was not separately measured");
                    h.assertTrue(job.current().terminal().completion().scope().equals("attributable-transformation-delivery"),"Wrong research Jobs classification");
                    session.inference(false);
                } else {
                    h.assertTrue(session.generationCalls()==generationBefore,"Known production invoked generation");
                    h.assertTrue(artifact.equals(view.marker().artifactSha256()),"New bindings did not reuse the immutable method");
                }
                System.out.println("RB002_NATIVE phase="+(warm ? "warm" : "cold")+" genuine="+genuine+" quantity="+amount+" batches="+batches
                        +" surplus="+surplus+" artifact="+artifact+" actor="+actor+" source="+source()+" destination="+destination()+" modelCalls="+(session.generationCalls()-generationBefore)
                        +" receipts="+receipts+" job="+job);
                if(!warm && round<3) {round++;amount=round==1 ? 5 : round==2 ? 1 : 64;phase=2;return;}
                if(warm) {h.assertTrue(!actor.entityId().equals(originalActor),"Offline binding did not change the controlled citizen");closing=session.storeClosure();session.close();phase=5;}
                else {
                    var m=new Marker(owner.getUUID(),villager.getUUID(),actor.citizenId(),artifact,origin.getX(),origin.getY(),origin.getZ(),ProcessHandle.current().pid());
                    writing=CompletableFuture.runAsync(()->{try{Files.writeString(marker,new Gson().toJson(m));}catch(Exception e){throw new CompletionException(e);}});phase=4;
                }
            } else if(phase==4) {if(!writing.isDone())return;writing.join();closing=session.storeClosure();session.close();phase=5;}
            else if(phase==5 && closing.isDone()) {closing.join();done=true;}
        }
    }
}
