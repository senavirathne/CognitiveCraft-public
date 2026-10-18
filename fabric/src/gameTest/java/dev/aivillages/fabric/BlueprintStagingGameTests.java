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

/** Real native inventory/recipe/harvest/claim observations. Controlled proposals remain test-only. */
public final class BlueprintStagingGameTests {
    @GameTest(maxTicks=2_000_000,padding=16)
    public void supportedStagingConservationAndConservativeSameSaveReopen(GameTestHelper h) {
        var scene=new Scene(h);h.failIfEver(()->{if(scene.failure!=null)h.fail(scene.failure);});
        h.succeedWhen(()->{scene.step();h.assertTrue(scene.done,"Waiting for RB008 phase="+scene.phase+" row="+scene.row);});
    }
    private record Marker(UUID principal,UUID entity,UUID interrupted,Blueprint.Ref blueprint,int x,int y,int z,long process,int planks,int logs) { }
    private static final class Candidates implements GenerationPort {
        final OakLogFixtures.ControlledGeneration logs=new OakLogFixtures.ControlledGeneration();
        final OakPlankFixtures.ControlledGeneration planks=new OakPlankFixtures.ControlledGeneration();
        public Generation.Handle generate(Generation.Request r,Budgets.InferenceLimits l,Budgets.Ledger u){return (r.bound().request().capability().equals(OakLogDelivery.ID)?logs:planks).generate(r,l,u);}
        public Generation.Descriptor descriptor(){return logs.descriptor();}
        public Generation.Status status(){return logs.status();}
    }
    private static final class Scene {
        final GameTestHelper h;final Path root,marker;final boolean warm;final ServerPlayer owner,foreign;
        final long started=System.currentTimeMillis();final CompletableFuture<Marker> reopening;
        KernelSession session;BlockPos origin;Villager villager;ActorRef actor;Blueprint.Ref blueprint;UUID run,job;int phase,learn,row,actorBefore;
        long calls;String failure;boolean done;CompletableFuture<BlueprintStore.Publication> storing;
        CompletableFuture<KernelSession.MaterialResult> material;CompletableFuture<KernelSession.MaterialStatus> status;
        CompletableFuture<Void> writing,closing;
        Scene(GameTestHelper h){this.h=h;var world=h.getLevel().getServer().getWorldPath(LevelResource.ROOT);root=world.resolve("rb008-native-owner");marker=world.resolve("rb008-native-marker.json");
            warm="warm".equals(System.getenv("COGNITIVECRAFT_RB008_PHASE"));reopening=warm?CompletableFuture.supplyAsync(()->{try{return new Gson().fromJson(Files.readString(marker),Marker.class);}catch(Exception e){throw new CompletionException(e);}}):null;
            owner=h.makeMockServerPlayerInLevel();foreign=h.makeMockServerPlayerInLevel();foreign.setUUID(UUID.randomUUID());
            session=new KernelSession(h.getLevel().getServer(),root,null,warm?null:new Candidates());}
        BlockPos box(){return origin.offset(3,1,3);}BlockPos dest(){return origin.offset(6,1,5);}BlockPos logs(){return origin.offset(2,1,7);}BlockPos site(){return origin.offset(11,1,5);}
        Container box(BlockPos p){return (Container)h.getLevel().getBlockEntity(p);}
        ContainerRef ref(BlockPos p){return new ContainerRef(actor.dimension(),p.getX(),p.getY(),p.getZ());}
        Cuboid area(){var p=logs();return new Cuboid(actor.dimension(),p.getX(),p.getY(),p.getZ(),p.getX()+7,p.getY(),p.getZ());}
        void setup(){BootstrapGameTests.forceFixtureChunks(h,origin);for(int x=0;x<20;x++)for(int z=0;z<11;z++)h.getLevel().setBlock(origin.offset(x,0,z),Blocks.STONE.defaultBlockState(),3);
            for(var p:List.of(box(),dest()))h.getLevel().setBlock(p,Blocks.CHEST.defaultBlockState(),3);reset();}
        void reset(){box(box()).clearContent();for(int i=0;i<8;i++)h.getLevel().setBlock(logs().east(i),Blocks.OAK_LOG.defaultBlockState(),3);for(int i=0;i<5;i++)h.getLevel().setBlock(site().east(i),Blocks.AIR.defaultBlockState(),3);}
        static String body(){var cells=new ArrayList<Blueprint.Cell>();for(int i=0;i<5;i++)cells.add(new Blueprint.Cell(i,0,0,0));return new Blueprint.Body(new Blueprint.Dimensions(5,1,1),new Blueprint.Constraints(true,true,0),List.of(new Blueprint.State("minecraft:oak_planks",Map.of())),cells).canonical();}
        CompletableFuture<KernelSession.MaterialResult> request(boolean stage){var p=site();return session.blueprintMaterials(owner,actor.citizenId(),blueprint,0,new FacilityRef(actor.dimension(),p.getX(),p.getY(),p.getZ()),
                new Cuboid(actor.dimension(),p.getX(),p.getY(),p.getZ(),p.getX()+4,p.getY(),p.getZ()),ref(box()),ref(box()),area(),stage);}
        void seed(){reset();int planks=row==0||row==4?5:row==1?2:0,logs=row==1||row==2?1:row==3?2:0;
            if(planks>0)box(box()).setItem(0,new ItemStack(Items.OAK_PLANKS,planks));if(logs>0)box(box()).setItem(1,new ItemStack(Items.OAK_LOG,logs));
            if(row==3)h.getLevel().setBlock(site(),Blocks.OAK_PLANKS.defaultBlockState(),3);actorBefore=villager.getInventory().countItem(Items.OAK_PLANKS);calls=session.generationCalls();material=request(false);phase=6;}
        void cancel(int next){var change=session.cancelQueuedJob(owner,job);h.assertTrue(change.accepted(),"Cancellation rejected "+change);phase=next;}
        boolean cancellationAcknowledged(){return session.jobsReady()&&session.leasesReady()
                &&session.queuedJob(owner,job).state()==Jobs.State.CANCELLED
                &&session.leaseOwner().snapshot().leases().stream().noneMatch(l->l.owner().job().equals(job)&&l.state()==ResourceLeases.State.ACTIVE);}
        void step(){
            if(System.currentTimeMillis()-started>900000){failure="Bounded RB008 watchdog phase="+phase+" row="+row;return;}
            if(phase==12){if(closing.isDone()){closing.join();done=true;}return;}
            if(phase==15){if(writing.isDone()&&closing.isDone()){writing.join();closing.join();done=true;}return;}
            session.tick();if(!session.identityReady())return;
            if(phase==0){
                if(warm){if(!reopening.isDone())return;var m=reopening.join();h.assertTrue(m.process()!=ProcessHandle.current().pid(),"Reopen reused original JVM");origin=new BlockPos(m.x(),m.y(),m.z());owner.setUUID(m.principal());blueprint=m.blueprint();BootstrapGameTests.forceFixtureChunks(h,origin);
                    h.assertTrue(box(box()).countItem(Items.OAK_PLANKS)==m.planks()&&box(box()).countItem(Items.OAK_LOG)==m.logs(),"Same-save stock changed");
                    if(!session.jobsReady()||!session.leasesReady())return;var old=session.queuedJob(owner,m.interrupted());h.assertTrue(old.state()==Jobs.State.INTERRUPTED,"Unfinished staging replayed on reopen");
                    h.assertTrue(session.leaseOwner().snapshot().leases().stream().noneMatch(l->l.state()==ResourceLeases.State.ACTIVE),"Prior claims survived restart active");
                }else{owner.setUUID(UUID.randomUUID());origin=h.absolutePos(new BlockPos(64,0,64));setup();}
                var p=origin.offset(1,1,1).subtract(h.absolutePos(BlockPos.ZERO));villager=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,p.getX(),p.getY(),p.getZ());villager.setNoAi(false);villager.setPersistenceRequired();owner.setPos(villager.getX(),villager.getY(),villager.getZ());actor=session.enroll(owner,villager);phase=1;
            }else if(phase==1){if(!session.jobsReady()||!session.leasesReady())return;session.inference(!warm);phase=warm?5:2;}
            else if(phase==2){if(!session.jobsReady()||!session.leasesReady())return;var s=box();var d=dest();BootstrapController.Submission submitted;
                if(learn==0){var a=area();submitted=session.gatherOak(owner,actor.citizenId(),1,a.minX(),a.minY(),a.minZ(),a.maxX(),a.maxY(),a.maxZ(),s.getX(),s.getY(),s.getZ());}
                else{box(box()).setItem(0,new ItemStack(Items.OAK_LOG));submitted=session.makePlanks(owner,actor.citizenId(),4,s.getX(),s.getY(),s.getZ(),d.getX(),d.getY(),d.getZ());}
                h.assertTrue(submitted.accepted(),"Independent method admission rejected "+submitted);run=submitted.id();phase=3;
            }else if(phase==3){var v=session.status(owner,run);if(v.phase()!=BootstrapController.Phase.TERMINAL)return;
                h.assertTrue(v.research()!=null&&v.research().status()==Outcomes.ResearchStatus.ADMITTED,"Controlled method was not admitted "+KernelRunStatus.describe(v));
                if(++learn<2)phase=2;else{session.inference(false);storing=session.storeBlueprint(owner,body(),"rb008-plank-only");phase=4;}
            }else if(phase==4){if(!storing.isDone())return;var stored=storing.join();h.assertTrue(stored.ref()!=null,"Blueprint publication failed "+stored);blueprint=stored.ref();phase=5;}
            else if(phase==5){if(!session.jobsReady()||!session.leasesReady())return;seed();}
            else if(phase==6){if(!material.isDone())return;var r=material.join();if(r.job()!=null||!r.report().canStage()){failure="Preflight rejected row="+row+" "+r;return;}
                h.assertTrue(session.generationCalls()==calls,"Preflight called inference");long expected=row==0||row==4?0:row==1?3:row==2?5:4;
                h.assertTrue(r.report().shortage()==expected,"Wrong exact shortage "+r.report());material=request(true);phase=7;}
            else if(phase==7){if(!material.isDone())return;var r=material.join();h.assertTrue(r.accepted(),"Stage rejected "+r);job=r.job();phase=8;}
            else if(phase==8){if(!session.queuedJobIds(owner,false).contains(job))return;var j=session.queuedJob(owner,job);
                if(j.state()==Jobs.State.FAILED||j.state()==Jobs.State.CANCELLED||j.state()==Jobs.State.INTERRUPTED){failure="Native staging stopped "+j+" children="+j.children().stream().map(id->session.queuedJob(owner,id)).toList();return;}
                if(j.state()!=Jobs.State.SUCCEEDED)return;status=session.blueprintMaterialStatus(owner,job);phase=9;}
            else if(phase==9){if(!status.isDone())return;var current=status.join();if(!current.currentlyAllocated()){
                    if(current.report().reason()==Outcomes.Reason.STORAGE_UNAVAILABLE){if(session.jobsReady()&&session.leasesReady())status=session.blueprintMaterialStatus(owner,job);return;}
                    failure="No current physical allocation row="+row+" "+current;return;}
                var j=current.job();int q=row==3?4:5;h.assertTrue(box(box()).countItem(Items.OAK_PLANKS)==q&&current.allocated()==q,"Staged item conservation mismatch");
                h.assertTrue(session.generationCalls()==calls&&j.attempts().isEmpty(),"Staging root executed or inferred");
                for(int i=0;i<5;i++)h.assertTrue(h.getLevel().getBlockState(site().east(i)).is(row==3&&i==0?Blocks.OAK_PLANKS:Blocks.AIR),"Staging placed blueprint blocks");
                if(row==0||row==4)h.assertTrue(j.children().isEmpty(),"Existing staged stock caused manufacture");
                else{var child=session.queuedJob(owner,j.children().getFirst());h.assertTrue(child.state()==Jobs.State.SUCCEEDED&&child.fulfilled()==(row==1?3:row==2?5:4),"Wrong producer shortage");
                    h.assertTrue(child.children().size()==(row==2?1:0),"Wrong missing-log graph");h.assertTrue(villager.getInventory().countItem(Items.OAK_PLANKS)==actorBefore+(row==1?1:row==2?3:0),"Actual batch surplus lost");}
                try{session.queuedJob(foreign,job);h.fail("Foreign stage Job leaked");}catch(SecurityException expected){}
                try{session.cancelQueuedJob(foreign,job);h.fail("Foreign stage cancellation allowed");}catch(SecurityException expected){}
                System.out.println("RB008_NATIVE phase="+(warm?"warm":"cold")+" row="+row+" required="+q+" allocated="+current.allocated()+" modelCalls=0 job="+j.id()+" children="+j.children());
                if(row==4){box(box()).setItem(0,new ItemStack(Items.OAK_PLANKS,4));status=session.blueprintMaterialStatus(owner,job);phase=10;}
                else cancel(16);
            }else if(phase==10){if(!status.isDone())return;h.assertTrue(!status.join().currentlyAllocated(),"Moved-away stock remained allocated");cancel(17);
            }else if(phase==16){if(cancellationAcknowledged()){row++;phase=5;}
            }else if(phase==17){if(!cancellationAcknowledged())return;
                if(warm){closing=session.storeClosure();session.close();phase=12;}else{phase=11;}
            }else if(phase==11){if(!session.jobsReady()||!session.leasesReady())return;
                // Persist an accepted, not-yet-advanced stage demand and its exact immutable ref.
                material=request(true);phase=13;
            }else if(phase==13){if(!material.isDone())return;var result=material.join();h.assertTrue(result.accepted(),"Restart preparation refused "+result);job=result.job();phase=14;}
            else if(phase==14){if(!session.queuedJobIds(owner,false).contains(job))return;
                var m=new Marker(owner.getUUID(),villager.getUUID(),job,blueprint,origin.getX(),origin.getY(),origin.getZ(),ProcessHandle.current().pid(),box(box()).countItem(Items.OAK_PLANKS),box(box()).countItem(Items.OAK_LOG));
                writing=CompletableFuture.runAsync(()->{try{Files.writeString(marker,new Gson().toJson(m));}catch(Exception e){throw new CompletionException(e);}});closing=session.storeClosure();session.close();phase=15;
            }else if(phase==15&&writing.isDone()&&closing.isDone()){writing.join();closing.join();done=true;}
        }
    }
}
