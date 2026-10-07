package dev.aivillages.fabric;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static net.minecraft.commands.Commands.literal;

/** Real production composition and commands; one explicit fake acquisition seeds the test-only known method. */
public final class WorkerDispatchWiringGameTests {
    @GameTest(maxTicks=1800,padding=32)
    public void productionQueueDispatchesTwoScopesWithInferenceDisabled(GameTestHelper h){
        var d=new Driver(h);h.failIfEver(()->{if(d.fatal!=null)h.fail(d.fatal);});
        h.succeedWhen(()->{d.step();h.assertTrue(d.phase==9,"Waiting for production dispatcher phase="+d.phase);});
    }
    private static final class Driver {
        final GameTestHelper h;final BlockPos origin;final Path root;final BootstrapGameTests.Run fixture;
        KernelSession session;ServerPlayer owner,foreign;Villager first,second;
        ActorRef a,b;UUID jobA,jobB;CompletableFuture<Void> closing;int phase,last=-1;long setupCalls;String fatal;
        Driver(GameTestHelper h){
            this.h=h;origin=h.absolutePos(new BlockPos(8,0,8));BootstrapGameTests.forceFixtureChunks(h,origin);
            root=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("cognitivecraft-dispatch-wiring");
            for(int x=0;x<10;x++)for(int z=0;z<9;z++)set(x,0,z,Blocks.STONE.defaultBlockState());
            for(int x=2;x<=3;x++)for(int z=3;z<=4;z++)plant(x,z);
            set(4,1,4,Blocks.CHEST.defaultBlockState());set(7,1,0,Blocks.CHEST.defaultBlockState());
            first=spawn(3,3);fixture=new BootstrapGameTests.Run(h,first,null,root,origin,null,true,true);
        }
        void set(int x,int y,int z,net.minecraft.world.level.block.state.BlockState state){h.getLevel().setBlockAndUpdate(origin.offset(x,y,z),state);}
        void plant(int x,int z){set(x,0,z,Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));set(x,1,z,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));}
        Villager spawn(int x,int z){var v=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,8+x,1,8+z);v.setNoAi(false);v.setPersistenceRequired();return v;}
        void check(boolean ok,String text){if(!ok){fatal=text;h.fail(text);}}
        void await(CompletableFuture<?> f){h.assertTrue(f.isDone(),"Waiting for owner disk closure");check(!f.isCompletedExceptionally(),"Owner close failed");}
        void step(){
            if(phase==9)return;
            java.util.concurrent.locks.LockSupport.parkNanos(phase<=5?50_000_000:1_000_000);
            if(last!=phase){System.out.println("IMP-013 production wiring phase="+phase);last=phase;}
            if(session!=null&&phase<7)session.tick();
            switch(phase){
                case 0->{fixture.advance();h.assertTrue(fixture.stage==9,"Acquiring explicitly fake setup method");check(fixture.fakeCalls==1,"Setup acquisition must be explicit");a=fixture.actor;closing=fixture.closeAcceptanceFixture();phase=1;}
                case 1->{await(closing);session=new KernelSession(h.getLevel().getServer(),root);owner=h.makeMockServerPlayerInLevel();foreign=h.makeMockServerPlayerInLevel();owner.setUUID(fixture.owner.principal().id());foreign.setUUID(UUID.randomUUID());owner.setPos(origin.getX()+20,origin.getY()+1,origin.getZ()+20);foreign.setPos(origin.getX()+21,origin.getY()+1,origin.getZ()+20);var command=literal("ccdispatchfixture");KernelCommands.attach(command,()->session);h.getLevel().getServer().getCommands().getDispatcher().register(command);phase=2;}
                case 2->{h.assertTrue(session.identityReady(),"Loading production identities");setupCalls=session.generationCalls();check(setupCalls==1,"Known setup acquisition was not preserved");session.inference(false);plant(2,3);plant(6,1);second=spawn(7,1);foreign.setPos(second.getX(),second.getY(),second.getZ());b=session.enroll(foreign,second);foreign.setPos(origin.getX()+21,origin.getY()+1,origin.getZ()+20);phase=3;}
                case 3->{h.assertTrue(session.identityReady(),"Acknowledging second worker");var p=origin.offset(2,1,3);var box=origin.offset(4,1,4);var result=session.queueHarvest(owner,a.citizenId(),1,new Cuboid(a.dimension(),p.getX(),p.getY(),p.getZ(),p.getX(),p.getY(),p.getZ()),new ContainerRef(a.dimension(),box.getX(),box.getY(),box.getZ()));check(result.accepted(),"First production queue rejected: "+result.reason());jobA=result.id();phase=4;}
                case 4->{h.assertTrue(session.job(jobA,fixture.owner).isPresent(),"Acknowledging first queued job");var p=origin.offset(6,1,1);var box=origin.offset(7,1,0);String coordinates=p.getX()+" "+p.getY()+" "+p.getZ()+" "+p.getX()+" "+p.getY()+" "+p.getZ()+" "+box.getX()+" "+box.getY()+" "+box.getZ();h.assertTrue(command(foreign,"queue "+b.citizenId()+" 1 "+coordinates)==1,"Waiting for second queue command admission");phase=5;}
                case 5->{
                    if(jobB==null){var ids=session.queuedJobIds(foreign,false);h.assertTrue(ids.size()==1,"Acknowledging exactly one queued command job");jobB=ids.getFirst();}
                    var ja=session.queuedJob(owner,jobA);var jb=session.queuedJob(foreign,jobB);
                    h.assertTrue(ja.state()==Jobs.State.SUCCEEDED&&jb.state()==Jobs.State.SUCCEEDED,"Waiting for both physical queued jobs: "+ja.state()+" "+jb.state());
                    check(ja.fulfilled()==1&&jb.fulfilled()==1&&ja.attempts().size()==1&&jb.attempts().size()==1,"Production dispatch lost or duplicated accounting");
                    check(ja.current().worker().equals(a)&&jb.current().worker().equals(b),"Production queue recruited a foreign worker");
                    check(session.generationCalls()==setupCalls&&session.languageCalls()==0,"Model-disabled queue invoked inference");
                    check(((Container)h.getLevel().getBlockEntity(origin.offset(4,1,4))).countItem(Items.WHEAT)==5&&((Container)h.getLevel().getBlockEntity(origin.offset(7,1,0))).countItem(Items.WHEAT)==1,"Production gateway did not conserve physical wheat");
                    try{session.dispatchDiagnostics(foreign,jobA);h.fail("Foreign diagnostics allowed");}catch(SecurityException expected){}
                    check(!session.queuedJobIds(foreign,false).contains(jobA),"Queue suggestions leaked foreign IDs");
                    check(command(owner,"job-status "+jobA)==1&&command(foreign,"job-status "+jobA)==0,"Production status command lost scope");
                    session.clearDispatchCache();phase=6;
                }
                case 6->{check(session.queuedJob(owner,jobA).state()==Jobs.State.SUCCEEDED&&session.queuedJob(foreign,jobB).state()==Jobs.State.SUCCEEDED,"Cache deletion altered authoritative jobs");session.close();closing=session.storeClosure();phase=7;}
                case 7->{await(closing);System.out.println("IMP-013 production wiring accepted scopes=2 workers=2 queuedJobs=2 selectedRuns=2 delivered=2 generationCalls=0 languageCalls=0 cacheDeleted=true architecture=0.4");phase=9;}
                default->throw new IllegalStateException("Production dispatch phase");
            }
        }
        int command(ServerPlayer player,String suffix){try{return h.getLevel().getServer().getCommands().getDispatcher().execute("ccdispatchfixture kernel "+suffix,player.createCommandSourceStack());}catch(Exception e){throw new IllegalStateException(e);}}
    }
}
