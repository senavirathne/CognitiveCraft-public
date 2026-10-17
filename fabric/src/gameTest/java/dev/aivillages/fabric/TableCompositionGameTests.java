package dev.aivillages.fabric;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import dev.aivillages.core.kernel.*;
import static dev.aivillages.core.kernel.Contracts.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

/** Controlled proposals; real standalone trials and stock-first Jobs/leases/native effects. */
public final class TableCompositionGameTests {
    @GameTest(maxTicks=4_000_000,padding=16)
    public void realShortfallsUseExistingPrerequisitesWithoutAWorldTable(GameTestHelper h) {
        // Four real observation waits keep this controlled proposal within the unchanged 1200-tick assignment.
        // Native pickup still enforces its actual delay; the ordinary retained RB-001 fixture keeps twenty waits.
        var logs=new OakLogFixtures.ControlledGeneration(4);var planks=new OakPlankFixtures.ControlledGeneration();var tables=new TableFixtures.ControlledGeneration();
        GenerationPort proposals=new GenerationPort() {
            public Generation.Handle generate(Generation.Request r,Budgets.InferenceLimits l,Budgets.Ledger u) {
                return (r.capability().id().equals(OakLogDelivery.ID)?logs:r.capability().id().equals(OakPlankProduction.ID)?planks:tables).generate(r,l,u);
            }
            public Generation.Descriptor descriptor(){return tables.descriptor();}
            public Generation.Status status(){return tables.status();}
        };
        var session=new KernelSession(h.getLevel().getServer(),h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("rb006a-composition"),null,proposals);
        var owner=h.makeMockServerPlayerInLevel();var foreign=h.makeMockServerPlayerInLevel();foreign.setUUID(UUID.randomUUID());
        var origin=h.absolutePos(new BlockPos(64,0,64));BootstrapGameTests.forceFixtureChunks(h,origin);
        for(int x=0;x<14;x++)for(int z=0;z<10;z++)h.getLevel().setBlock(origin.offset(x,0,z),Blocks.STONE.defaultBlockState(),3);
        var s=origin.offset(3,1,3);var d=origin.offset(6,1,5);var oak=origin.offset(2,1,7);
        h.getLevel().setBlock(s,Blocks.CHEST.defaultBlockState(),3);h.getLevel().setBlock(d,Blocks.CHEST.defaultBlockState(),3);
        for(int x=0;x<4;x++)h.getLevel().setBlock(oak.east(x),Blocks.OAK_LOG.defaultBlockState(),3);
        var relative=origin.offset(1,1,1).subtract(h.absolutePos(BlockPos.ZERO));Villager v=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,relative.getX(),relative.getY(),relative.getZ());v.setNoAi(false);v.setPersistenceRequired();
        String dim=h.getLevel().dimension().identifier().toString();var source=new ContainerRef(dim,s.getX(),s.getY(),s.getZ());var destination=new ContainerRef(dim,d.getX(),d.getY(),d.getZ());
        var area=new Cuboid(dim,oak.getX(),oak.getY(),oak.getZ(),oak.getX()+3,oak.getY(),oak.getZ());
        Container from=(Container)h.getLevel().getBlockEntity(s),to=(Container)h.getLevel().getBlockEntity(d);
        int[][] rows={{1,0,4,0,0,0,0},{1,0,2,0,2,1,2},{1,1,0,0,0,0,1},{1,0,0,0,0,1,2},{4,0,0,0,0,4,2}};
        int[] phase={0},learn={0},row={0},before={0};ActorRef[] actor={null};UUID[] run={null},root={null};
        long[] calls={0},submittedAt={0};Map<CapabilityId,String> hashes=new HashMap<>();CompletableFuture<?>[] closing={null};
        h.succeedWhen(()->{
            // Pace the fast test-server clock while actual asynchronous journal writes complete.
            // Production ticks, assignment limits, action deadlines and the wall watchdog are unchanged.
            java.util.concurrent.locks.LockSupport.parkNanos(2_000_000);
            if(phase[0]==7){h.assertTrue(closing[0].isDone(),"Waiting for table composition storage closure");closing[0].join();return;}
            session.tick();h.assertTrue(session.identityReady(),"Waiting for table composition identity");
            if((phase[0]<=2 || phase[0]==4) && (!session.jobsReady() || !session.leasesReady())){h.assertTrue(false,"Waiting for owner publication");return;}
            if(phase[0]==0){owner.setPos(v.getX(),v.getY(),v.getZ());actor[0]=session.enroll(owner,v);phase[0]=1;}
            else if(phase[0]==1){session.name(owner,actor[0].citizenId(),"Ada");session.inference(true);phase[0]=2;}
            else if(phase[0]==2){
                BootstrapController.Submission submitted;
                if(learn[0]==0)submitted=session.gatherOak(owner,actor[0].citizenId(),1,area.minX(),area.minY(),area.minZ(),area.maxX(),area.maxY(),area.maxZ(),s.getX(),s.getY(),s.getZ());
                else {from.clearContent();from.setItem(0,new ItemStack(learn[0]==1?Items.OAK_LOG:Items.OAK_PLANKS,learn[0]==1?1:4));
                    submitted=learn[0]==1?session.makePlanks(owner,actor[0].citizenId(),4,s.getX(),s.getY(),s.getZ(),d.getX(),d.getY(),d.getZ()):session.makeTables(owner,actor[0].citizenId(),1,s.getX(),s.getY(),s.getZ(),d.getX(),d.getY(),d.getZ());}
                h.assertTrue(submitted.accepted(),"Standalone prerequisite admission refused "+submitted);run[0]=submitted.id();phase[0]=3;
            }else if(phase[0]==3){
                var view=session.status(owner,run[0]);if(view.phase()!=BootstrapController.Phase.TERMINAL){h.assertTrue(false,"Waiting for standalone physical admission");return;}
                h.assertTrue(view.research()!=null && view.research().status()==Outcomes.ResearchStatus.ADMITTED,"Standalone method did not pass actual trial: "+KernelRunStatus.describe(view));
                var job=session.job(run[0],session.citizen(owner,actor[0].citizenId()).owner()).orElseThrow();hashes.put(job.request().request().capability(),view.marker().artifactSha256());
                if(++learn[0]<3)phase[0]=2;else{session.inference(false);phase[0]=4;}
            }else if(phase[0]==4){
                int[] r=rows[row[0]];from.clearContent();if(r[1]>0)from.setItem(0,new ItemStack(Items.OAK_LOG,r[1]));if(r[2]>0)from.setItem(1,new ItemStack(Items.OAK_PLANKS,r[2]));
                for(int x=0;x<4;x++)h.getLevel().setBlock(oak.east(x),Blocks.OAK_LOG.defaultBlockState(),3);
                before[0]=to.countItem(Items.CRAFTING_TABLE);calls[0]=session.generationCalls();
                var submitted=session.composeTables(owner,actor[0].citizenId(),r[0],source,destination,area);h.assertTrue(submitted.accepted(),"Table MAKE composition refused "+submitted);
                root[0]=submitted.id();submittedAt[0]=System.currentTimeMillis();phase[0]=5;
            }else if(phase[0]==5){
                if(!session.queuedJobIds(owner,false).contains(root[0])) {
                    h.assertTrue(System.currentTimeMillis()-submittedAt[0]<180000,"Bounded native table root publication watchdog");
                    h.assertTrue(false,"Waiting for acknowledged owned root visibility");return;
                }
                var job=session.queuedJob(owner,root[0]);h.assertTrue(!job.state().terminal() || job.state()==Jobs.State.SUCCEEDED,"Native table graph failed: "+job);
                h.assertTrue(System.currentTimeMillis()-submittedAt[0]<180000,"Bounded native table graph watchdog: "+job);
                if(job.state()!=Jobs.State.SUCCEEDED){h.assertTrue(false,"Waiting for actual table prerequisite Jobs");return;}
                int[] r=rows[row[0]];h.assertTrue(job.fulfilled()==r[0] && job.children().size()==r[6],"Table MAKE counted old stock or unnecessary producers");
                h.assertTrue(from.countItem(Items.OAK_LOG)==r[3] && from.countItem(Items.OAK_PLANKS)==r[4] && to.countItem(Items.CRAFTING_TABLE)==before[0]+r[0],"Native table graph conservation mismatch");
                var graph=new ArrayList<Jobs.Job>();graph.add(job);job.children().forEach(id->graph.add(session.queuedJob(owner,id)));long harvested=0;
                for(var node:graph){h.assertTrue(node.state()==Jobs.State.SUCCEEDED && node.preparation().method().sha256().equals(hashes.get(node.request().request().capability())),"Independent method changed");
                    for(var a:node.attempts())for(var receipt:a.resourceReceipts())if(receipt.stage()==ResourceDelivery.Stage.HARVEST)harvested+=receipt.quantity();}
                h.assertTrue(harvested==r[5] && session.generationCalls()==calls[0],"Extra logs or inference in known table graph");
                try{session.queuedJob(foreign,root[0]);h.fail("Foreign principal read table MAKE graph");}catch(SecurityException expected){}
                System.out.println("RB006A_COMPOSITION row="+row[0]+" harvested="+harvested+" modelCalls=0 graph="+graph);
                if(++row[0]<rows.length)phase[0]=4;else{closing[0]=session.storeClosure();session.close();phase[0]=7;}
            }
            h.assertTrue(false,"Waiting for native table composition phase="+phase[0]);
        });
    }
}
