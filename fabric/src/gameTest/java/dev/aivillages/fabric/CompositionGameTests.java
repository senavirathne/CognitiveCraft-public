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

/** Installed native effects, ordinary research/admission and normal prerequisite Job dispatch. */
public final class CompositionGameTests {
    @GameTest(maxTicks=4_000_000,padding=16)
    public void independentAdmissionGoldenGraphsAndFreshBindingsAfterBackendStoppedRestart(GameTestHelper h) {
        var scene=new Scene(h);h.failIfEver(()->{if(scene.failure!=null)h.fail(scene.failure);});
        h.succeedWhen(()->{scene.step();h.assertTrue(scene.done,"Waiting for RB-005 phase="+scene.phase+" row="+scene.row);});
    }
    private record Marker(UUID principal,UUID originalActor,Map<String,String> artifacts,int x,int y,int z,long process) { }
    private static final class Candidates implements GenerationPort {
        final OakLogFixtures.ControlledGeneration logs=new OakLogFixtures.ControlledGeneration();
        final OakPlankFixtures.ControlledGeneration planks=new OakPlankFixtures.ControlledGeneration();
        final StickFixtures.ControlledGeneration sticks=new StickFixtures.ControlledGeneration();
        final AxeFixtures.ControlledGeneration axes=new AxeFixtures.ControlledGeneration();
        public Generation.Handle generate(Generation.Request request,Budgets.InferenceLimits limits,Budgets.Ledger usage) {
            var c=request.bound().request().capability();
            return (c.equals(OakLogDelivery.ID)?logs:c.equals(OakPlankProduction.ID)?planks:c.equals(StickProduction.ID)?sticks:axes).generate(request,limits,usage);
        }
        public Generation.Descriptor descriptor(){return logs.descriptor();}
        public Generation.Status status(){return logs.status();}
    }
    private static final class Scene {
        // q, supplied logs/planks/sticks, final logs/planks/sticks, acquired logs, producer nodes excluding root
        static final int[][] ROWS={{1,0,3,2,0,0,0,0,0},{1,0,5,0,0,0,2,0,1},{1,1,2,0,0,1,2,0,2},
                {1,2,0,0,0,3,2,0,2},{1,0,0,0,0,3,2,2,3},{1,2,0,2,1,1,0,0,1},{1,0,0,0,0,3,2,2,3},{4,0,0,0,0,0,0,4,3}};
        final GameTestHelper h;final Path root,marker;final boolean warm,genuine;final ServerPlayer owner,foreign;
        final long started=System.currentTimeMillis();final CompletableFuture<Marker> reopening;
        final RecordedAxeGeneration recorded;final Candidates candidates=new Candidates();
        final Map<String,String> hashes=new TreeMap<>();
        KernelSession session;BlockPos origin;Villager villager;ActorRef actor;UUID run,job;int phase,row,learn,before,oldActorAxes;long calls,lastProgress,graphStarted;
        String failure;boolean done;CompletableFuture<Void> writing,closing;
        Scene(GameTestHelper h) {
            this.h=h;var world=h.getLevel().getServer().getWorldPath(LevelResource.ROOT);root=world.resolve("rb005-native-owner");marker=world.resolve("rb005-native-marker.json");
            warm="warm".equals(System.getenv("COGNITIVECRAFT_RB005_PHASE"));genuine="true".equals(System.getenv("COGNITIVECRAFT_RB005_GENUINE"));
            reopening=warm?CompletableFuture.supplyAsync(()->{try{return new Gson().fromJson(Files.readString(marker),Marker.class);}catch(Exception e){throw new CompletionException(e);}}):null;
            owner=h.makeMockServerPlayerInLevel();foreign=h.makeMockServerPlayerInLevel();foreign.setUUID(UUID.randomUUID());
            recorded=genuine&&!warm?new RecordedAxeGeneration(root.resolve("generated-wire")):null;
            session=new KernelSession(h.getLevel().getServer(),root,null,recorded!=null?recorded:warm?null:candidates);
        }
        BlockPos source(){return origin.offset(warm?11:3,1,3);}
        BlockPos destination(){return origin.offset(warm?14:6,1,5);}
        BlockPos table(){return origin.offset(warm?12:5,1,1);}
        BlockPos oak(){return origin.offset(warm?10:2,1,6);}
        Container box(BlockPos p){return (Container)h.getLevel().getBlockEntity(p);}
        ContainerRef ref(BlockPos p){return new ContainerRef(actor.dimension(),p.getX(),p.getY(),p.getZ());}
        Cuboid oakArea(){var p=oak();return new Cuboid(actor.dimension(),p.getX(),p.getY(),p.getZ(),p.getX()+3,p.getY(),p.getZ());}
        void setup() {
            BootstrapGameTests.forceFixtureChunks(h,origin);
            for(int x=0;x<19;x++)for(int z=0;z<10;z++)h.getLevel().setBlock(origin.offset(x,0,z),Blocks.STONE.defaultBlockState(),3);
            for(var p:List.of(source(),destination()))h.getLevel().setBlock(p,Blocks.CHEST.defaultBlockState(),3);
            h.getLevel().setBlock(table(),Blocks.CRAFTING_TABLE.defaultBlockState(),3);
            for(int x=0;x<4;x++)h.getLevel().setBlock(oak().east(x),Blocks.OAK_LOG.defaultBlockState(),3);
        }
        void supplied(int logs,int planks,int sticks) {
            box(source()).clearContent();
            if(logs>0)box(source()).setItem(0,new ItemStack(Items.OAK_LOG,logs));
            if(planks>0)box(source()).setItem(1,new ItemStack(Items.OAK_PLANKS,planks));
            if(sticks>0)box(source()).setItem(2,new ItemStack(Items.STICK,sticks));
        }
        void learn() {
            var s=source();var d=destination();var t=table();BootstrapController.Submission submitted;
            if(learn==0) {var a=oakArea();submitted=session.gatherOak(owner,actor.citizenId(),1,a.minX(),a.minY(),a.minZ(),a.maxX(),a.maxY(),a.maxZ(),s.getX(),s.getY(),s.getZ());}
            else if(learn==1){supplied(1,0,0);submitted=session.makePlanks(owner,actor.citizenId(),4,s.getX(),s.getY(),s.getZ(),d.getX(),d.getY(),d.getZ());}
            else if(learn==2){supplied(0,2,0);submitted=session.makeSticks(owner,actor.citizenId(),4,s.getX(),s.getY(),s.getZ(),d.getX(),d.getY(),d.getZ());}
            else {supplied(0,3,2);submitted=session.makeAxes(owner,actor.citizenId(),1,s.getX(),s.getY(),s.getZ(),d.getX(),d.getY(),d.getZ(),t.getX(),t.getY(),t.getZ());}
            h.assertTrue(submitted.accepted(),"Independent admission refused "+submitted);run=submitted.id();phase=3;
        }
        void compose() {
            int[] r=ROWS[row];supplied(r[1],r[2],r[3]);for(int x=0;x<4;x++)h.getLevel().setBlock(oak().east(x),Blocks.OAK_LOG.defaultBlockState(),3);
            if(row==6)box(destination()).setItem(20,new ItemStack(Items.WOODEN_AXE)); // Old output never fulfills MAKE.
            before=box(destination()).countItem(Items.WOODEN_AXE);oldActorAxes=villager.getInventory().countItem(Items.WOODEN_AXE);calls=session.generationCalls();
            var submitted=session.composeAxes(owner,actor.citizenId(),r[0],ref(source()),ref(destination()),new FacilityRef(actor.dimension(),table().getX(),table().getY(),table().getZ()),oakArea());
            h.assertTrue(submitted.accepted(),"Canonical composition refused "+submitted);job=submitted.id();graphStarted=System.currentTimeMillis();phase=5;
        }
        void step() {
            if(System.currentTimeMillis()-started>1_500_000){failure="Finite native watchdog phase="+phase+" row="+row;return;}
            if(phase==8){if(closing.isDone()){closing.join();if(recorded!=null)recorded.close();done=true;}return;}
            session.tick();if(System.currentTimeMillis()-lastProgress>20000){lastProgress=System.currentTimeMillis();System.out.println("RB005_PROGRESS phase="+phase+" learn="+learn+" row="+row+" jobsReady="+session.jobsReady()+" leasesReady="+session.leasesReady()+" run="+run+" job="+job);}
            if(!session.identityReady())return;
            if((phase<=2||phase==4)&&(!session.jobsReady()||!session.leasesReady()))return;
            if(phase==0) {
                if(warm) {
                    if(!reopening.isDone())return;var m=reopening.join();h.assertTrue(m.process()!=ProcessHandle.current().pid(),"Same JVM is not restart proof");
                    owner.setUUID(UUID.randomUUID());origin=new BlockPos(m.x(),m.y(),m.z());hashes.putAll(m.artifacts());
                    BootstrapGameTests.forceFixtureChunks(h,origin);var original=h.getLevel().getEntity(m.originalActor());h.assertTrue(original instanceof Villager && ((Villager)original).getInventory().countItem(Items.WOODEN_AXE)==1,"Saved old axe/citizen was not preserved");
                } else origin=h.absolutePos(new BlockPos(64,0,64));
                setup();var p=origin.offset(warm?10:1,1,1).subtract(h.absolutePos(BlockPos.ZERO));villager=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,p.getX(),p.getY(),p.getZ());villager.setNoAi(false);villager.setPersistenceRequired();
                owner.setPos(villager.getX(),villager.getY(),villager.getZ());actor=session.enroll(owner,villager);
                villager.getInventory().setItem(7,new ItemStack(Items.WOODEN_AXE));phase=1;
            } else if(phase==1) {
                if(!warm){session.name(owner,actor.citizenId(),"Ada");session.inference(true);phase=2;}
                else {session.inference(false);row=7;phase=4;}
            } else if(phase==2)learn();
            else if(phase==3) {
                var view=session.status(owner,run);if(view.phase()!=BootstrapController.Phase.TERMINAL)return;
                if(view.research()==null||view.research().status()!=Outcomes.ResearchStatus.ADMITTED){failure="Independent research failed "+KernelRunStatus.describe(view);return;}
                var j=session.job(run,session.citizen(owner,actor.citizenId()).owner()).orElseThrow();
                h.assertTrue(j.state()==Jobs.State.SUCCEEDED&&j.children().isEmpty(),"Producer lost standalone ownership");
                hashes.put(j.request().request().capability().name(),view.marker().artifactSha256());
                System.out.println("RB005_ADMISSION genuine="+genuine+" capability="+j.request().request().capability()+" artifact="+view.marker().artifactSha256()+" job="+j);
                if(++learn<4)phase=2;else{session.inference(false);phase=4;}
            } else if(phase==4)compose();
            else if(phase==5) {
                if(!session.queuedJobIds(owner,false).contains(job)) {
                    if(System.currentTimeMillis()-graphStarted>120000)failure="Bounded native root publication watchdog job="+job;
                    return;
                }
                var j=session.queuedJob(owner,job);
                if(j.state()==Jobs.State.FAILED||j.state()==Jobs.State.CANCELLED||j.state()==Jobs.State.INTERRUPTED){failure="Composition failed "+j;return;}
                if(j.current()!=null && j.current().terminal()!=null && j.current().terminal().status()!=Outcomes.ExecutionStatus.SUCCEEDED){failure="Prepared native attempt stopped "+j;return;}
                if(System.currentTimeMillis()-graphStarted>120000){failure="Bounded native graph watchdog root="+j+" children="+j.children().stream().map(id->session.queuedJob(owner,id)).toList()+" dispatch="+j.children().stream().map(id->session.dispatchDiagnostics(owner,id)).toList();return;}
                if(j.state()!=Jobs.State.SUCCEEDED)return;
                int[] r=ROWS[row];h.assertTrue(j.fulfilled()==r[0]&&j.children().size()==r[8],"Root credited stock or incorrect prerequisite cardinality");
                h.assertTrue(box(source()).countItem(Items.OAK_LOG)==r[4]&&box(source()).countItem(Items.OAK_PLANKS)==r[5]&&box(source()).countItem(Items.STICK)==r[6],"Native golden surplus mismatch row="+row+" box="+box(source()));
                h.assertTrue(box(destination()).countItem(Items.WOODEN_AXE)==before+r[0]&&villager.getInventory().countItem(Items.WOODEN_AXE)==oldActorAxes,"Old axe credited or replaced");
                long harvested=0;
                var graph=new ArrayList<Jobs.Job>();graph.add(j);for(var child:j.children())graph.add(session.queuedJob(owner,child));
                for(var node:graph) {
                    if(warm)h.assertTrue(node.attempts().stream().allMatch(a->a.worker().equals(actor)),"Warm graph did not execute with the fresh controlled actor");
                    h.assertTrue(node.state()==Jobs.State.SUCCEEDED&&node.preparation()!=null&&node.preparation().method().sha256().equals(hashes.get(node.request().request().capability().name())),"Changed independent method identity");
                    for(var a:node.attempts())for(var receipt:a.resourceReceipts())if(receipt.stage()==ResourceDelivery.Stage.HARVEST)harvested+=receipt.quantity();
                }
                h.assertTrue(harvested==r[7]&&session.generationCalls()==calls,"Extra acquisition or known-execution inference");
                try{session.queuedJob(foreign,job);h.fail("Foreign principal read composition");}catch(SecurityException expected){}
                System.out.println("RB005_NATIVE phase="+(warm?"warm":"cold")+" genuine="+genuine+" row="+row+" harvested="+harvested+" modelCalls="+(session.generationCalls()-calls)+" graph="+graph);
                if(!warm&&++row<ROWS.length){phase=4;return;}
                if(warm){closing=session.storeClosure();session.close();phase=8;}
                else {
                    var m=new Marker(owner.getUUID(),villager.getUUID(),hashes,origin.getX(),origin.getY(),origin.getZ(),ProcessHandle.current().pid());
                    writing=CompletableFuture.runAsync(()->{try{Files.writeString(marker,new Gson().toJson(m));}catch(Exception e){throw new CompletionException(e);}});phase=7;
                }
            } else if(phase==7){if(writing.isDone()){writing.join();closing=session.storeClosure();session.close();phase=8;}}
        }
    }
}
