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

/** Both independent admissions plus stock-first graphs through the actual production owners. */
public final class BirchKernelGameTests {
    @GameTest(maxTicks=4_000_000,padding=16)
    public void separateBirchMethodsCompositionAndBackendStoppedSecondJvm(GameTestHelper h) {
        var scene=new Scene(h);h.failIfEver(()->{if(scene.failure!=null)h.fail(scene.failure);});
        h.succeedWhen(()->{scene.step();h.assertTrue(scene.done,"Waiting for RB-007 phase="+scene.phase+" round="+scene.round);});
    }
    private record Marker(UUID originalActor,Map<String,String> artifacts,int actorPlanks,int x,int y,int z,long process) { }
    private static final class Scene {
        final GameTestHelper h;final Path root,marker;final boolean warm,genuine;final ServerPlayer owner,foreign;
        final long started=System.currentTimeMillis();final CompletableFuture<Marker> reopening;
        final RecordedAxeGeneration recorded;final BirchFixtures.ControlledGeneration controlled=new BirchFixtures.ControlledGeneration();
        final Map<String,String> hashes=new TreeMap<>();
        KernelSession session;BlockPos origin;Villager villager;ActorRef actor;UUID run;int phase,round,amount,before,actorBefore,inputBefore;long calls,graphStarted;
        String failure;boolean done;CompletableFuture<Void> writing,closing;
        Scene(GameTestHelper h) {
            this.h=h;var world=h.getLevel().getServer().getWorldPath(LevelResource.ROOT);root=world.resolve("rb007-native-owner");marker=world.resolve("rb007-native-marker.json");
            warm="warm".equals(System.getenv("COGNITIVECRAFT_RB007_PHASE"));genuine="true".equals(System.getenv("COGNITIVECRAFT_RB007_GENUINE"));
            reopening=warm?CompletableFuture.supplyAsync(()->{try{return new Gson().fromJson(Files.readString(marker),Marker.class);}catch(Exception e){throw new CompletionException(e);}}):null;
            owner=h.makeMockServerPlayerInLevel();owner.setUUID(UUID.randomUUID());foreign=h.makeMockServerPlayerInLevel();foreign.setUUID(UUID.randomUUID());
            recorded=genuine&&!warm?new RecordedAxeGeneration(root.resolve("generated-wire")):null;
            session=new KernelSession(h.getLevel().getServer(),root,null,recorded!=null?recorded:warm?null:controlled);
        }
        BlockPos source(){return origin.offset(warm?11:3,1,3);}
        BlockPos destination(){return origin.offset(warm?14:6,1,5);}
        BlockPos logs(){return origin.offset(warm?10:2,1,7);}
        Container box(BlockPos at){return (Container)h.getLevel().getBlockEntity(at);}
        ContainerRef ref(BlockPos at){return new ContainerRef(actor.dimension(),at.getX(),at.getY(),at.getZ());}
        Cuboid area(){var p=logs();return new Cuboid(actor.dimension(),p.getX(),p.getY(),p.getZ(),p.getX()+7,p.getY(),p.getZ());}
        void resetLogs(){for(int i=0;i<8;i++)h.getLevel().setBlock(logs().east(i),Blocks.BIRCH_LOG.defaultBlockState(),3);}
        void seed(int quantity){box(source()).clearContent();if(quantity>0)box(source()).setItem(0,new ItemStack(Items.BIRCH_LOG,quantity));box(source()).setItem(1,new ItemStack(Items.OAK_LOG,64));box(source()).setItem(2,new ItemStack(Items.BIRCH_PLANKS,11));}
        void setup(){BootstrapGameTests.forceFixtureChunks(h,origin);for(int x=0;x<20;x++)for(int z=0;z<11;z++)h.getLevel().setBlock(origin.offset(x,0,z),Blocks.STONE.defaultBlockState(),3);for(var at:List.of(source(),destination()))h.getLevel().setBlock(at,Blocks.CHEST.defaultBlockState(),3);resetLogs();}
        void submitIndependent(){
            boolean acquisition=round==0;amount=acquisition?(warm?7:4):warm?4:round==1?5:round==2?1:round==3?4:64;
            var s=source();var d=destination();BootstrapController.Submission submitted;
            if(acquisition){resetLogs();before=box(source()).countItem(Items.BIRCH_LOG);var a=area();submitted=session.gatherBirch(owner,actor.citizenId(),amount,a.minX(),a.minY(),a.minZ(),a.maxX(),a.maxY(),a.maxZ(),s.getX(),s.getY(),s.getZ());}
            else {seed(BirchPlankProduction.batches(amount));before=box(destination()).countItem(Items.BIRCH_PLANKS);submitted=session.makeBirchPlanks(owner,actor.citizenId(),amount,s.getX(),s.getY(),s.getZ(),d.getX(),d.getY(),d.getZ());}
            actorBefore=villager.getInventory().countItem(Items.BIRCH_PLANKS);inputBefore=villager.getInventory().countItem(Items.BIRCH_LOG);calls=session.generationCalls();
            h.assertTrue(submitted.accepted(),"Independent birch submission rejected "+submitted);run=submitted.id();phase=3;
        }
        void submitGraph(){seed(round==0?2:round==1?1:0);resetLogs();before=box(destination()).countItem(Items.BIRCH_PLANKS);calls=session.generationCalls();
            var submitted=session.composeBirchPlanks(owner,actor.citizenId(),8,ref(source()),ref(destination()),area());
            h.assertTrue(submitted.accepted(),"Birch graph rejected "+submitted);run=submitted.id();graphStarted=System.currentTimeMillis();phase=5;}
        void step(){
            if(System.currentTimeMillis()-started>1_300_000){failure="Finite RB-007 native watchdog phase="+phase+" round="+round;return;}
            if(phase==8){if(closing.isDone()){closing.join();if(recorded!=null)recorded.close();done=true;}return;}
            session.tick();if(!session.identityReady())return;
            if((phase<=2||phase==4)&&(!session.jobsReady()||!session.leasesReady()))return;
            if(phase==0){
                if(warm){if(!reopening.isDone())return;var m=reopening.join();h.assertTrue(m.process()!=ProcessHandle.current().pid(),"Reuse ran in original JVM");origin=new BlockPos(m.x(),m.y(),m.z());hashes.putAll(m.artifacts());BootstrapGameTests.forceFixtureChunks(h,origin);var saved=h.getLevel().getEntity(m.originalActor());h.assertTrue(saved instanceof Villager && ((Villager)saved).getInventory().countItem(Items.BIRCH_PLANKS)==m.actorPlanks(),"Saved original custody changed");}
                else origin=h.absolutePos(new BlockPos(64,0,64));
                setup();var p=origin.offset(warm?10:1,1,1).subtract(h.absolutePos(BlockPos.ZERO));villager=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,p.getX(),p.getY(),p.getZ());villager.setNoAi(false);villager.setPersistenceRequired();owner.setPos(villager.getX(),villager.getY(),villager.getZ());actor=session.enroll(owner,villager);phase=1;
            }else if(phase==1){if(!warm)session.name(owner,actor.citizenId(),"Ada");session.inference(!warm);phase=2;}
            else if(phase==2)submitIndependent();
            else if(phase==3){
                var view=session.status(owner,run);if(view.phase()!=BootstrapController.Phase.TERMINAL)return;
                boolean acquired=view.research()!=null&&view.research().status()==Outcomes.ResearchStatus.ADMITTED;
                if(!(acquired||view.outcome()!=null&&view.outcome().status()==Outcomes.ExecutionStatus.SUCCEEDED)){failure="Birch independent attempt failed "+KernelRunStatus.describe(view);return;}
                var j=session.job(run,session.citizen(owner,actor.citizenId()).owner()).orElseThrow();var cap=j.request().request().capability();
                h.assertTrue(j.state()==Jobs.State.SUCCEEDED&&j.children().isEmpty()&&j.dependencies().isEmpty(),"Independent outcome coupled to another Job");
                if(!warm&&round<2){h.assertTrue(acquired,"Missing separate genuine/controlled admission");hashes.put(cap.name(),view.marker().artifactSha256());h.assertTrue(session.generationCalls()>calls,"Unmeasured acquisition");}
                else h.assertTrue(view.marker().artifactSha256().equals(hashes.get(cap.name()))&&session.generationCalls()==calls,"Known independent method changed or invoked inference");
                var rows=view.resourceReceipts();h.assertTrue(!rows.isEmpty()&&view.receipts().isEmpty(),"Birch relabeled as crop evidence");
                if(round==0){h.assertTrue(box(source()).countItem(Items.BIRCH_LOG)==before+amount,"Harvested birch destination mismatch");h.assertTrue(rows.stream().allMatch(r->r.item().equals(ResourceDelivery.BIRCH_LOG)),"Foreign species credited");}
                else {int batches=BirchPlankProduction.batches(amount);h.assertTrue(box(destination()).countItem(Items.BIRCH_PLANKS)==before+amount&&box(source()).countItem(Items.BIRCH_LOG)==0,"New birch transformation mismatch");h.assertTrue(box(source()).countItem(Items.OAK_LOG)==64&&box(source()).countItem(Items.BIRCH_PLANKS)==11,"Oak inputs or old output substituted");h.assertTrue(villager.getInventory().countItem(Items.BIRCH_PLANKS)==actorBefore+4*batches-amount&&villager.getInventory().countItem(Items.BIRCH_LOG)==inputBefore,"Actual surplus/custody mismatch");}
                try{session.status(foreign,run);h.fail("Foreign birch run was visible");}catch(SecurityException expected){}
                System.out.println("RB007_INDEPENDENT phase="+(warm?"warm":"cold")+" genuine="+genuine+" capability="+cap+" amount="+amount+" artifact="+view.marker().artifactSha256()+" modelCalls="+(session.generationCalls()-calls)+" job="+j);
                if(!warm&&round==1)session.inference(false);
                if(++round<(warm?2:5))phase=2;else{round=0;phase=4;}
            }else if(phase==4)submitGraph();
            else if(phase==5){
                if(!session.queuedJobIds(owner,false).contains(run))return;var j=session.queuedJob(owner,run);
                if(j.state()==Jobs.State.FAILED||j.state()==Jobs.State.CANCELLED||j.state()==Jobs.State.INTERRUPTED){failure="Birch graph stopped "+j;return;}
                if(System.currentTimeMillis()-graphStarted>150000){failure="Birch graph bounded watchdog "+j+" children="+j.children().stream().map(id->session.queuedJob(owner,id)).toList();return;}
                if(j.state()!=Jobs.State.SUCCEEDED)return;
                var graph=new ArrayList<Jobs.Job>();graph.add(j);for(var child:j.children())graph.add(session.queuedJob(owner,child));
                h.assertTrue(j.fulfilled()==8&&j.children().size()==(round==0?0:1),"Wrong birch graph cardinality or credit");long harvested=0;
                for(var node:graph){h.assertTrue(node.state()==Jobs.State.SUCCEEDED&&node.preparation().method().sha256().equals(hashes.get(node.request().request().capability().name())),"Changed producer artifact in graph");for(var a:node.attempts())for(var r:a.resourceReceipts())if(r.stage()==ResourceDelivery.Stage.HARVEST)harvested+=r.quantity();}
                h.assertTrue(harvested==(round==0?0:round==1?1:2)&&session.generationCalls()==calls,"Wrong actual deficit or known graph inference");
                h.assertTrue(box(destination()).countItem(Items.BIRCH_PLANKS)==before+8&&box(source()).countItem(Items.BIRCH_LOG)==0&&box(source()).countItem(Items.OAK_LOG)==64&&box(source()).countItem(Items.BIRCH_PLANKS)==11,"Birch graph native conservation/substitution");
                try{session.queuedJob(foreign,run);h.fail("Foreign graph visible");}catch(SecurityException expected){}
                System.out.println("RB007_NATIVE phase="+(warm?"warm":"cold")+" genuine="+genuine+" row="+round+" harvested="+harvested+" modelCalls=0 graph="+graph);
                if(++round<3){phase=4;return;}
                if(warm){closing=session.storeClosure();session.close();phase=8;}else{var m=new Marker(villager.getUUID(),hashes,villager.getInventory().countItem(Items.BIRCH_PLANKS),origin.getX(),origin.getY(),origin.getZ(),ProcessHandle.current().pid());writing=CompletableFuture.runAsync(()->{try{Files.writeString(marker,new Gson().toJson(m));}catch(Exception e){throw new CompletionException(e);}});phase=7;}
            }else if(phase==7&&writing.isDone()){writing.join();closing=session.storeClosure();session.close();phase=8;}
        }
    }
}
