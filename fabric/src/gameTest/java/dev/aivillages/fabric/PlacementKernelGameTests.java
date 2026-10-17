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

/** Independent research, real table-only prerequisites and backend-stopped saved-world reuse. */
public final class PlacementKernelGameTests {
    private record Marker(UUID originalActor,Map<String,String> artifacts,int x,int y,int z,long process) { }
    @GameTest(maxTicks=4_000_000,padding=16)
    public void independentPlacementThenRealFacilityProvisioningAndOfflineReopen(GameTestHelper h){
        var s=new Scene(h);h.failIfEver(()->{if(s.failure!=null)h.fail(s.failure);});h.succeedWhen(()->{s.step();h.assertTrue(s.done,"Waiting for RB-006B phase="+s.phase+" row="+s.row);});
    }
    private static final class Candidates implements GenerationPort {
        final OakLogFixtures.ControlledGeneration logs=new OakLogFixtures.ControlledGeneration(4);
        final OakPlankFixtures.ControlledGeneration planks=new OakPlankFixtures.ControlledGeneration();
        final StickFixtures.ControlledGeneration sticks=new StickFixtures.ControlledGeneration();
        final TableFixtures.ControlledGeneration tables=new TableFixtures.ControlledGeneration();
        final PlacementFixtures.ControlledGeneration placement=new PlacementFixtures.ControlledGeneration();
        final AxeFixtures.ControlledGeneration axes=new AxeFixtures.ControlledGeneration();
        public Generation.Handle generate(Generation.Request r,Budgets.InferenceLimits l,Budgets.Ledger u){var id=r.capability().id();return(id.equals(OakLogDelivery.ID)?logs:id.equals(OakPlankProduction.ID)?planks:id.equals(StickProduction.ID)?sticks:id.equals(CraftingTableProduction.ID)?tables:id.equals(CraftingTablePlacement.ID)?placement:axes).generate(r,l,u);}
        public Generation.Descriptor descriptor(){return placement.descriptor();}public Generation.Status status(){return placement.status();}
    }
    private static final class Scene {
        final GameTestHelper h;final Path root,marker;final boolean genuine,warm;final RecordedAxeGeneration recorded;final Candidates controlled=new Candidates();
        final ServerPlayer owner,foreign;final long started=System.currentTimeMillis();final CompletableFuture<Marker> reopening;final Map<String,String> hashes=new TreeMap<>();
        KernelSession session;BlockPos origin;Villager worker;ActorRef actor;UUID run,job;int phase,learn,row,before,oldActorAxes;long graphStarted,calls;String failure;boolean done;CompletableFuture<?> closing,writing;
        Scene(GameTestHelper h){this.h=h;var world=h.getLevel().getServer().getWorldPath(LevelResource.ROOT);root=world.resolve("rb006b-native-owner");marker=world.resolve("rb006b-native-marker.json");
            genuine="true".equals(System.getenv("COGNITIVECRAFT_RB006B_GENUINE"));warm="warm".equals(System.getenv("COGNITIVECRAFT_RB006B_PHASE"));
            if(genuine&&!warm&&(System.getenv("COGNITIVECRAFT_OLLAMA_MODEL")==null||System.getenv("COGNITIVECRAFT_OLLAMA_MODEL").isBlank()))throw new IllegalStateException("Configured genuine backend required");
            recorded=genuine&&!warm?new RecordedAxeGeneration(root.resolve("generated-wire")):null;
            reopening=warm?CompletableFuture.supplyAsync(()->{try{return new Gson().fromJson(Files.readString(marker),Marker.class);}catch(Exception e){throw new CompletionException(e);}}):null;
            owner=h.makeMockServerPlayerInLevel();foreign=h.makeMockServerPlayerInLevel();foreign.setUUID(UUID.randomUUID());session=new KernelSession(h.getLevel().getServer(),root,null,recorded!=null?recorded:warm?null:controlled);
        }
        BlockPos source(){return origin.offset(warm?4:3,1,3);}BlockPos destination(){return origin.offset(warm?7:6,1,3);}BlockPos approved(){return origin.offset(warm?10:9,1,5);}BlockPos learningSite(){return origin.offset(5,1,5);}BlockPos learningTable(){return origin.offset(5,1,1);}BlockPos oak(){return origin.offset(2,1,8);}
        Container box(BlockPos p){return(Container)h.getLevel().getBlockEntity(p);}ContainerRef ref(BlockPos p){return new ContainerRef(actor.dimension(),p.getX(),p.getY(),p.getZ());}FacilityRef facility(BlockPos p){return new FacilityRef(actor.dimension(),p.getX(),p.getY(),p.getZ());}
        Cuboid area(){var p=oak();return new Cuboid(actor.dimension(),p.getX(),p.getY(),p.getZ(),p.getX()+3,p.getY(),p.getZ());}
        void supplied(int planks,int sticks,int tables){box(source()).clearContent();if(planks>0)box(source()).setItem(0,new ItemStack(Items.OAK_PLANKS,planks));if(sticks>0)box(source()).setItem(1,new ItemStack(Items.STICK,sticks));if(tables>0)box(source()).setItem(2,new ItemStack(Items.CRAFTING_TABLE,tables));}
        void setup(){BootstrapGameTests.forceFixtureChunks(h,origin);for(int x=0;x<17;x++)for(int z=0;z<12;z++)h.getLevel().setBlock(origin.offset(x,0,z),Blocks.STONE.defaultBlockState(),3);
            for(var p:List.of(source(),destination()))h.getLevel().setBlock(p,Blocks.CHEST.defaultBlockState(),3);
            if(!warm)h.getLevel().setBlock(learningTable(),Blocks.CRAFTING_TABLE.defaultBlockState(),3);
            for(int i=0;i<4;i++)h.getLevel().setBlock(oak().east(i),Blocks.OAK_LOG.defaultBlockState(),3);
        }
        void learn(){var s=source();var d=destination();BootstrapController.Submission result;
            if(learn==0){var a=area();result=session.gatherOak(owner,actor.citizenId(),1,a.minX(),a.minY(),a.minZ(),a.maxX(),a.maxY(),a.maxZ(),s.getX(),s.getY(),s.getZ());}
            else if(learn==1){supplied(0,0,0);box(s).setItem(0,new ItemStack(Items.OAK_LOG));result=session.makePlanks(owner,actor.citizenId(),4,s.getX(),s.getY(),s.getZ(),d.getX(),d.getY(),d.getZ());}
            else if(learn==2){supplied(2,0,0);result=session.makeSticks(owner,actor.citizenId(),4,s.getX(),s.getY(),s.getZ(),d.getX(),d.getY(),d.getZ());}
            else if(learn==3){supplied(4,0,0);result=session.makeTables(owner,actor.citizenId(),1,s.getX(),s.getY(),s.getZ(),d.getX(),d.getY(),d.getZ());}
            else if(learn==4){supplied(0,0,1);result=session.placeTable(owner,actor.citizenId(),ref(s),facility(learningSite()));}
            else{supplied(3,2,0);var t=learningTable();result=session.makeAxes(owner,actor.citizenId(),1,s.getX(),s.getY(),s.getZ(),d.getX(),d.getY(),d.getZ(),t.getX(),t.getY(),t.getZ());}
            h.assertTrue(result.accepted(),"Independent RB-006B prerequisite refused "+result);run=result.id();phase=3;
        }
        void submit(){
            h.getLevel().setBlock(approved(),row==0?Blocks.CRAFTING_TABLE.defaultBlockState():Blocks.AIR.defaultBlockState(),3);
            supplied(row<2?3:0,row<2?2:0,row==1?1:0);for(int i=0;i<4;i++)h.getLevel().setBlock(oak().east(i),Blocks.OAK_LOG.defaultBlockState(),3);
            before=box(destination()).countItem(Items.WOODEN_AXE);oldActorAxes=worker.getInventory().countItem(Items.WOODEN_AXE);calls=session.generationCalls();
            var result=session.provisionAxe(owner,actor.citizenId(),ref(source()),ref(destination()),facility(approved()),area());
            h.assertTrue(result.accepted(),"Canonical facility provisioning refused "+result);job=result.id();graphStarted=System.currentTimeMillis();phase=5;
        }
        List<Jobs.Job> graph(Jobs.Job root){var result=new ArrayList<Jobs.Job>();var pending=new ArrayDeque<Jobs.Job>();var seen=new HashSet<UUID>();pending.add(root);while(!pending.isEmpty()){var next=pending.remove();if(!seen.add(next.id()))continue;result.add(next);next.children().forEach(id->pending.add(session.queuedJob(owner,id)));}return result;}
        void step(){
            if(System.currentTimeMillis()-started>1_500_000){failure="Bounded RB-006B watchdog phase="+phase+" row="+row;return;}
            if(phase==8){if(closing.isDone()){closing.join();if(recorded!=null)recorded.close();done=true;}return;}
            session.tick();if(!session.identityReady())return;if((phase<=2||phase==4)&&(!session.jobsReady()||!session.leasesReady()))return;
            if(phase==0){
                if(warm){if(!reopening.isDone())return;var m=reopening.join();h.assertTrue(m.process()!=ProcessHandle.current().pid(),"Warm phase reused cold JVM");origin=new BlockPos(m.x(),m.y(),m.z());hashes.putAll(m.artifacts());owner.setUUID(UUID.randomUUID());
                    BootstrapGameTests.forceFixtureChunks(h,origin);h.assertTrue(h.getLevel().getEntity(m.originalActor()) instanceof Villager,"Saved original citizen missing");}
                else origin=h.absolutePos(new BlockPos(64,0,64));
                setup();var p=origin.offset(1,1,2).subtract(h.absolutePos(BlockPos.ZERO));worker=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,p.getX(),p.getY(),p.getZ());worker.setNoAi(false);worker.setPersistenceRequired();owner.setPos(worker.getX(),worker.getY(),worker.getZ());actor=session.enroll(owner,worker);phase=1;
            }else if(phase==1){if(warm){session.inference(false);row=2;phase=4;}else{session.name(owner,actor.citizenId(),"Ada");session.inference(true);phase=2;}}
            else if(phase==2)learn();
            else if(phase==3){var v=session.status(owner,run);if(v.phase()!=BootstrapController.Phase.TERMINAL)return;
                if(v.research()==null||v.research().status()!=Outcomes.ResearchStatus.ADMITTED){failure="Independent RB-006B method failed "+KernelRunStatus.describe(v);return;}
                var owned=session.job(run,session.citizen(owner,actor.citizenId()).owner()).orElseThrow();h.assertTrue(owned.state()==Jobs.State.SUCCEEDED&&owned.children().isEmpty(),"Standalone method borrowed a prerequisite");hashes.put(owned.request().request().capability().name(),v.marker().artifactSha256());
                System.out.println("RB006B_ADMISSION genuine="+genuine+" capability="+owned.request().request().capability()+" artifact="+v.marker().artifactSha256()+" job="+owned);
                if(++learn<6)phase=2;else{session.inference(false);phase=4;}
            }else if(phase==4)submit();
            else if(phase==5){
                if(!session.queuedJobIds(owner,false).contains(job)){if(System.currentTimeMillis()-graphStarted>180000)failure="Root publication deadline";return;}
                var j=session.queuedJob(owner,job);if(j.state().terminal()&&j.state()!=Jobs.State.SUCCEEDED){failure="Facility graph failed "+j;return;}
                var graph=graph(j);for(var n:graph)if(n.current()!=null&&n.current().terminal()!=null&&n.current().terminal().status()!=Outcomes.ExecutionStatus.SUCCEEDED){failure="Facility native attempt stopped "+n;return;}
                if(System.currentTimeMillis()-graphStarted>180000){failure="Bounded facility graph deadline graph="+graph+" dispatch="+graph.stream().map(n->session.dispatchDiagnostics(owner,n.id())).toList();return;}
                if(j.state()!=Jobs.State.SUCCEEDED)return;
                h.assertTrue(j.fulfilled()==1&&box(destination()).countItem(Items.WOODEN_AXE)==before+1&&worker.getInventory().countItem(Items.WOODEN_AXE)==oldActorAxes,"New axe attribution failed");
                h.assertTrue(h.getLevel().getBlockState(approved()).is(Blocks.CRAFTING_TABLE),"Approved facility missing");
                h.assertTrue(box(source()).countItem(Items.OAK_PLANKS)==(row==2?3:0)&&box(source()).countItem(Items.STICK)==(row==2?2:0)&&box(source()).countItem(Items.CRAFTING_TABLE)==0,"Facility and axe material conservation");
                long harvested=0;int tableJobs=0,placements=0;
                for(var n:graph){h.assertTrue(n.state()==Jobs.State.SUCCEEDED&&n.preparation()!=null&&n.preparation().method().sha256().equals(hashes.get(n.request().request().capability().name())),"Changed independent method");
                    if(n.request().request().capability().equals(CraftingTableProduction.ID))tableJobs++;if(CraftingTablePlacement.is(n.request().request().capability()))placements++;
                    for(var a:n.attempts())for(var r:a.resourceReceipts())if(r.stage()==ResourceDelivery.Stage.HARVEST)harvested+=r.quantity();}
                h.assertTrue(tableJobs==(row==2?1:0)&&placements==(row==0?0:1)&&harvested==(row==2?3:0),"Unnecessary facility production or incorrect real log cost");h.assertTrue(session.generationCalls()==calls,"Known facility graph used inference");
                try{session.queuedJob(foreign,job);h.fail("Foreign principal inspected facility graph");}catch(SecurityException expected){}
                System.out.println("RB006B_NATIVE phase="+(warm?"warm":"cold")+" genuine="+genuine+" row="+row+" harvested="+harvested+" tableJobs="+tableJobs+" placements="+placements+" modelCalls=0 graph="+graph);
                if(!warm&&++row<3){phase=4;return;}
                if(warm){closing=session.storeClosure();session.close();phase=8;}else{var m=new Marker(worker.getUUID(),hashes,origin.getX(),origin.getY(),origin.getZ(),ProcessHandle.current().pid());writing=CompletableFuture.runAsync(()->{try{Files.writeString(marker,new Gson().toJson(m));}catch(Exception e){throw new CompletionException(e);}});phase=7;}
            }else if(phase==7&&writing.isDone()){writing.join();closing=session.storeClosure();session.close();phase=8;}
        }
    }
}
