package dev.aivillages.fabric;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.providers.LocalGenerationAdapter;
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
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import static dev.aivillages.core.kernel.Budgets.Kind;

/** GP-14: actual production queue, adapter and physical kernel owners; explicitly controlled inference. */
public final class AIWorkBrokerGameTests {
    @GameTest(maxTicks=1800,padding=32)
    public void stalledSharedInferenceCannotBlockKnownPhysicalJobsOrReplayAfterRestart(GameTestHelper h){
        var driver=new Driver(h);h.failIfEver(()->{if(driver.fatal!=null)h.fail(driver.fatal);});
        h.succeedWhen(()->{driver.step();h.assertTrue(driver.phase==10,"Waiting for GP-14 phase="+driver.phase);});
    }
    private static final class Driver {
        final GameTestHelper h;final BlockPos origin;final Path root;final BootstrapGameTests.Run setup;
        final ScheduledThreadPoolExecutor timer=new ScheduledThreadPoolExecutor(1,r->{var t=new Thread(r,"broker-fixture-deadline");t.setDaemon(true);return t;});
        final LocalGenerationAdapter adapter;int physicalCalls,cancelRequests;
        KernelSession session;ServerPlayer owner,foreign;Villager first,second;ActorRef a,b;
        UUID jobA,jobB;Generation.Handle subscriberA,subscriberB,expired;CompletableFuture<Void> closing;
        int phase,last=-1,quiet;long setupCalls;String fatal;
        Driver(GameTestHelper h){
            this.h=h;origin=h.absolutePos(new BlockPos(8,0,8));BootstrapGameTests.forceFixtureChunks(h,origin);
            root=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("cognitivecraft-broker-wiring");
            for(int x=0;x<10;x++)for(int z=0;z<9;z++)set(x,0,z,Blocks.STONE.defaultBlockState());
            for(int x=2;x<=3;x++)for(int z=3;z<=4;z++)plant(x,z);
            set(4,1,4,Blocks.CHEST.defaultBlockState());set(7,1,0,Blocks.CHEST.defaultBlockState());
            first=spawn(3,3);setup=new BootstrapGameTests.Run(h,first,null,root,origin,null,true,true);
            timer.setRemoveOnCancelPolicy(true);
            adapter=new LocalGenerationAdapter(new LocalGenerationAdapter.Config(URI.create("http://127.0.0.1:11434/api/chat"),"controlled-gp14",true,256),
                    (body,remaining,max,sink)->{physicalCalls++;return ()->{cancelRequests++;return false;};},Clock.systemUTC(),Runnable::run,Runnable::run,timer);
        }
        void set(int x,int y,int z,net.minecraft.world.level.block.state.BlockState state){h.getLevel().setBlockAndUpdate(origin.offset(x,y,z),state);}
        void plant(int x,int z){set(x,0,z,Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));set(x,1,z,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));}
        Villager spawn(int x,int z){var v=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,8+x,1,8+z);v.setNoAi(false);v.setPersistenceRequired();return v;}
        void check(boolean ok,String text){if(!ok){fatal=text;h.fail(text);}}
        void await(CompletableFuture<?> f){h.assertTrue(f.isDone(),"Waiting for world owner closure");check(!f.isCompletedExceptionally(),"World owner close failed");}
        CapabilityRequest crop(ActorRef actor,int x,int z,int chestX,int chestZ){var p=origin.offset(x,1,z);var chest=origin.offset(chestX,1,chestZ);return new CapabilityRequest(CropDelivery.ID,Map.of("actor",new ActorValue(actor),"amount",new IntValue(1),"source",new AreaValue(new Cuboid(actor.dimension(),p.getX(),p.getY(),p.getZ(),p.getX(),p.getY(),p.getZ())),"destination",new ContainerValue(new ContainerRef(actor.dimension(),chest.getX(),chest.getY(),chest.getZ()))));}
        Generation.Request demand(ActorRef actor,TrustedContext context,ObservationRef observation,String text){return new Generation.Request(UUID.randomUUID(),new ValidatedRequest(crop(actor,actor.equals(a)?2:6,actor.equals(a)?3:1,actor.equals(a)?4:7,actor.equals(a)?4:0),context,observation),CropDelivery.SPEC,List.of(),List.of(),Generation.Role.INITIAL,text);}
        Generation.Handle submit(Generation.Request request,long deadline){var limits=new Budgets.InferenceLimits(new Budgets.Limits(Map.of(Kind.CALLS,1L,Kind.INPUT_BYTES,16384L,Kind.OUTPUT_BYTES,8192L),deadline),16384,8192);return session.inferenceBroker().generate(request,limits,new Budgets.Ledger(limits.total(),Clock.systemUTC()));}
        void step(){
            if(phase==10)return;
            java.util.concurrent.locks.LockSupport.parkNanos(phase<=5?50_000_000:1_000_000);
            if(last!=phase){System.out.println("IMP-014 production wiring phase="+phase);last=phase;}
            if(session!=null&&phase!=7&&phase<9)session.tick();
            switch(phase){
                case 0->{setup.advance();h.assertTrue(setup.stage==9,"Acquiring explicit fake setup method");check(setup.fakeCalls==1,"Setup fake acquisition not bounded");a=setup.actor;closing=setup.closeAcceptanceFixture();phase=1;}
                case 1->{await(closing);session=new KernelSession(h.getLevel().getServer(),root,null,adapter);owner=h.makeMockServerPlayerInLevel();owner.setUUID(setup.owner.principal().id());foreign=h.makeMockServerPlayerInLevel();foreign.setUUID(UUID.randomUUID());phase=2;}
                case 2->{h.assertTrue(session.identityReady(),"Loading actual production identities");setupCalls=session.generationCalls();check(setupCalls==1,"Setup acquisition was not retained");plant(2,3);plant(6,1);second=spawn(7,1);foreign.setPos(second.getX(),second.getY(),second.getZ());b=session.enroll(foreign,second);phase=3;}
                case 3->{
                    h.assertTrue(session.identityReady(),"Acknowledging second scope");var now=System.currentTimeMillis();var obs=new ObservationRef(UUID.randomUUID(),h.getLevel().getGameTime(),a.dimension());
                    subscriberA=submit(demand(a,setup.owner,obs,"equivalent authorized research snapshot"),now+120000);
                    subscriberB=submit(demand(a,setup.owner,obs,"equivalent authorized research snapshot"),now+120000);
                    var other=session.citizen(foreign,b.citizenId()).owner();
                    submit(demand(b,other,obs,"private incompatible research snapshot"),now+120000);
                    expired=submit(demand(a,setup.owner,obs,"expires while hardware stalled"),now+1500);
                    submit(demand(b,other,obs,"other private work"),now+120000);
                    var overflow=submit(demand(a,setup.owner,obs,"fifth pending work"),now+120000);
                    check(session.inferenceBroker().view(overflow.id(),setup.owner).state()==AIWorkBroker.State.REJECTED,"Queue overflow did not report backpressure");
                    check(session.inferenceBroker().stats().queuedWork()==4&&session.inferenceBroker().stats().coalesced()==1,"Equivalent demand or private isolation failed");phase=4;
                }
                case 4->{
                    check(physicalCalls==1,"Broker started more than one controlled transport");subscriberA.cancel();check(cancelRequests==0,"Cancelling one subscriber stopped shared work");subscriberB.cancel();check(cancelRequests==1&&session.inferenceBroker().stats().unconfirmed()==1,"Unconfirmed compute was released dishonestly");
                    var ra=crop(a,2,3,4,4);var rb=crop(b,6,1,7,0);
                    var left=session.queueHarvest(owner,a.citizenId(),1,((AreaValue)ra.arguments().get("source")).value(),((ContainerValue)ra.arguments().get("destination")).value());
                    check(left.accepted(),"First known physical job rejected");jobA=left.id();
                    // The actual job owner serializes durable publication; enqueue B after A's ACK.
                    phase=5;
                }
                case 5->{
                    h.assertTrue(session.job(jobA,setup.owner).isPresent(),"Acknowledging known job A");
                    if(jobB==null){var rb=crop(b,6,1,7,0);var right=session.queueHarvest(foreign,b.citizenId(),1,((AreaValue)rb.arguments().get("source")).value(),((ContainerValue)rb.arguments().get("destination")).value());if(!right.accepted())h.assertTrue(false,"Waiting for job B publication slot");jobB=right.id();}
                    h.assertTrue(session.queuedJobIds(foreign,false).contains(jobB),"Acknowledging known job B");
                    var ja=session.queuedJob(owner,jobA);var jb=session.queuedJob(foreign,jobB);
                    h.assertTrue(ja.state()==Jobs.State.SUCCEEDED&&jb.state()==Jobs.State.SUCCEEDED,"Known jobs still running during inference outage");
                    check(ja.fulfilled()==1&&jb.fulfilled()==1&&ja.attempts().size()==1&&jb.attempts().size()==1,"Known responsibility was duplicated");
                    check(physicalCalls==1&&session.generationCalls()==setupCalls&&session.languageCalls()==0,"Known execution invoked inference");
                    check(((Container)h.getLevel().getBlockEntity(origin.offset(4,1,4))).countItem(Items.WHEAT)==5&&((Container)h.getLevel().getBlockEntity(origin.offset(7,1,0))).countItem(Items.WHEAT)==1,"Known physical crop jobs did not conserve wheat");
                    session.cancelQueuedJob(owner,jobA);
                    check(session.queuedJob(owner,jobA).state()==Jobs.State.SUCCEEDED&&physicalCalls==1
                            &&session.generationCalls()==setupCalls&&session.languageCalls()==0,"Known status/cancel depended on inference or changed terminal credit");
                    check(session.inferenceBroker().view(expired.id(),setup.owner).state()==AIWorkBroker.State.EXPIRED,"Queue time was omitted from deadline");
                    var other=session.citizen(foreign,b.citizenId()).owner();try{session.inferenceBroker().view(subscriberA.id(),other);h.fail("Foreign inference view allowed");}catch(SecurityException expected){}
                    check(session.inferenceStatus(foreign).stream().noneMatch(v->v.subscriber().equals(subscriberA.id())),"Production queue status leaked private subscriber");
                    session.inference(false);check(session.inferenceBroker().stats().queuedWork()==0&&session.inferenceBroker().stats().occupied()==1,"Disabled inference lost truthful compute occupancy");
                    session.clearDispatchCache();session.close();closing=session.storeClosure();phase=7;
                }
                case 7->{await(closing);session=new KernelSession(h.getLevel().getServer(),root,null,adapter);phase=8;}
                case 8->{h.assertTrue(session.identityReady(),"Reloading authoritative owners");if(++quiet<20)h.assertTrue(false,"Quiet restart probe");check(session.inferenceBroker().stats().queuedWork()==0&&session.inferenceBroker().stats().dispatched()==0&&physicalCalls==1,"Restart replayed inference work");check(session.queuedJob(owner,jobA).state()==Jobs.State.SUCCEEDED&&session.queuedJob(foreign,jobB).state()==Jobs.State.SUCCEEDED,"Restart changed completed known jobs");session.close();closing=session.storeClosure();phase=9;}
                case 9->{await(closing);adapter.close();timer.shutdownNow();System.out.println("IMP-014 GP-14 accepted backend=controlled-fake coalesced=1 privateWork=separate physicalCalls=1 queueCap=4 concurrency=1 expired=true stopUnconfirmed=true knownJobs=2 delivered=2 knownGenerationCalls=0 knownLanguageCalls=0 restartReplay=0 architecture=0.5 brokerSchema=1");phase=10;}
                default->throw new IllegalStateException("GP-14 phase");
            }
        }
    }
}
