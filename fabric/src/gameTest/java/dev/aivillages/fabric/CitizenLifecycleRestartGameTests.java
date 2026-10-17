package dev.aivillages.fabric;

import com.google.gson.Gson;
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
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.nbt.CompoundTag;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** GP-16 physical partial effects and real-store recovery in two separate Minecraft JVMs. */
public final class CitizenLifecycleRestartGameTests {
    private record Proof(UUID citizen, UUID entity, UUID principal, UUID other, UUID run,
                         int x,int y,int z,int deposited,int receipts,long effects,long process) { }
    @GameTest(maxTicks=2200,padding=32)
    public void ordinaryLossRetainsPartialEffectsAndRecoversWithoutModels(GameTestHelper h) {
        boolean warm="warm".equals(System.getenv("COGNITIVECRAFT_LIFECYCLE_PHASE"));
        h.assertTrue(warm||"cold".equals(System.getenv("COGNITIVECRAFT_LIFECYCLE_PHASE")),"Explicit lifecycle phase required");
        var driver=new Driver(h,warm);
        h.failIfEver(()->{if(driver.fixture!=null&&driver.fixture.fatal!=null)h.fail(driver.fixture.fatal);});
        h.succeedWhen(()->{driver.step();h.assertTrue(driver.phase==20,"Waiting for lifecycle physical phase="+driver.phase);});
    }
    private static final class Driver {
        final GameTestHelper h; final boolean warm; final Path world;
        BootstrapGameTests.Run fixture; KernelSession session; Villager actorEntity, foreignEntity;
        ServerPlayer owner,other; ActorRef actor,foreign; BlockPos origin; CompoundTag saved;
        CompletableFuture<Void> closing,writing; CompletableFuture<Proof> reading;
        UUID run; Proof proof; int phase,last=-1,partial; long generation,quiet,modelBaseline;
        Driver(GameTestHelper h,boolean warm) {
            this.h=h;this.warm=warm;
            world=Path.of(System.getenv("COGNITIVECRAFT_LIFECYCLE_WORLD")).toAbsolutePath();
            System.out.println("IMP-029 fixture world="+world+" warm="+warm);
            if(warm){reading=CompletableFuture.supplyAsync(()->{try{return new Gson().fromJson(Files.readString(world.resolve("lifecycle-proof.json")),Proof.class);}catch(Exception e){throw new IllegalStateException(e);}});phase=12;return;}
            var offset=new BlockPos(8,0,8); origin=h.absolutePos(offset);
            BootstrapGameTests.forceFixtureChunks(h,origin);
            for(int x=0;x<10;x++)for(int z=0;z<9;z++)h.setBlock(offset.offset(x,0,z),Blocks.STONE);
            for(int x=2;x<=3;x++)for(int z=3;z<=4;z++)plant(x,z);
            h.setBlock(offset.offset(4,1,4),Blocks.CHEST); h.setBlock(offset.offset(7,1,0),Blocks.CHEST);
            actorEntity=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,11,1,11);actorEntity.setPersistenceRequired();
            fixture=new BootstrapGameTests.Run(h,actorEntity,null,world,origin,null,true,true);
        }
        void plant(int x,int z) {
            h.getLevel().setBlockAndUpdate(origin.offset(x,0,z),Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));
            h.getLevel().setBlockAndUpdate(origin.offset(x,1,z),Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));
        }
        void players(UUID a,UUID b) {
            owner=h.makeMockServerPlayerInLevel();owner.setUUID(a);other=h.makeMockServerPlayerInLevel();other.setUUID(b);
            park(owner);park(other);
        }
        void park(ServerPlayer p){p.setPos(origin.getX()+20.5,origin.getY()+1,origin.getZ()+20.5);}
        void ready(){h.assertTrue(session.identityReady()&&session.jobsReady()&&session.leasesReady(),"Waiting for durable lifecycle owners");}
        int stock(){return ((Container)h.getLevel().getBlockEntity(origin.offset(7,1,0))).countItem(Items.WHEAT);}
        void await(CompletableFuture<?> future){h.assertTrue(future.isDone(),"Waiting for off-thread storage");future.join();}
        void step() {
            java.util.concurrent.locks.LockSupport.parkNanos(2_000_000);
            if(last!=phase){System.out.println("IMP-029 physical warm="+warm+" phase="+phase);last=phase;}
            if(session!=null&&phase<18)session.tick();
            switch(phase) {
                case 0 -> {fixture.advance();h.assertTrue(fixture.stage==9,"Preparing explicitly controlled test-only method");
                    actor=fixture.actor;closing=fixture.closeAcceptanceFixture();phase=1;}
                case 1 -> {await(closing);session=new KernelSession(h.getLevel().getServer(),world);
                    players(fixture.owner.principal().id(),UUID.randomUUID());phase=2;}
                case 2 -> {ready();modelBaseline=session.generationCalls();session.inference(false);session.name(owner,actor.citizenId(),"Ada");phase=3;}
                case 3 -> {ready();h.assertTrue(session.citizen(owner,actor.citizenId()).availability()==CitizenRegistry.Availability.LOADED,"Verified identity");
                    CitizenLifecycleGameTests.relay(actor.entityId(),session.lifecycleAdapter());
                    generation=session.citizen(owner,actor.citizenId()).lifecycle().generation();
                    saved=CitizenLifecycleGameTests.unload(actorEntity);
                    h.assertTrue(session.citizen(owner,actor.citizenId()).availability()==CitizenRegistry.Availability.UNLOADED,"Actual unload must fence immediately");phase=4;}
                case 4 -> {ready();h.assertTrue(session.citizen(owner,actor.citizenId()).lifecycle().event()==CitizenLifecycle.Event.UNLOAD,"Unload is not death");
                    actorEntity=CitizenLifecycleGameTests.reload(h,saved);phase=5;}
                case 5 -> {ready();var c=session.citizen(owner,actor.citizenId());
                    h.assertTrue(c.availability()==CitizenRegistry.Availability.LOADED&&c.lifecycle().generation()==generation+1
                            &&"Ada".equals(c.displayName()),"Exact saved binding reload");
                    for(int x=6;x<=7;x++)for(int z=1;z<=3;z++)plant(x,z);
                    actorEntity.setPos(origin.getX()+6.5,origin.getY()+1,origin.getZ()+1.5);actorEntity.setNoAi(false);
                    foreignEntity=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,10,1,11);foreignEntity.setPersistenceRequired();
                    other.setPos(foreignEntity.getX(),foreignEntity.getY(),foreignEntity.getZ());
                    foreign=session.enroll(other,foreignEntity);park(other);phase=6;}
                case 6 -> {ready();h.assertTrue(actorEntity.onGround(),"Ordinary grounded worker setup");
                    var from=origin.offset(6,1,1);var to=origin.offset(7,1,3);var box=origin.offset(7,1,0);
                    var submitted=session.harvest(owner,actor.citizenId(),5,from.getX(),from.getY(),from.getZ(),to.getX(),to.getY(),to.getZ(),box.getX(),box.getY(),box.getZ());
                    h.assertTrue(submitted.accepted(),"Known physical task: "+submitted.reason());run=submitted.id();phase=7;}
                case 7 -> {var view=session.status(owner,run);
                    h.assertTrue(view.phase()!=BootstrapController.Phase.TERMINAL,"Task ended before physical death boundary: "+view);
                    h.assertTrue(view.receipts().stream().anyMatch(r->r.stage()==CropDelivery.Stage.DEPOSIT),"Waiting for a real partial deposit");
                    partial=stock();h.assertTrue(partial>0&&partial<5,"Actual partial material boundary");
                    actorEntity.kill(h.getLevel());
                    h.assertTrue(session.citizen(owner,actor.citizenId()).availability()==CitizenRegistry.Availability.UNAVAILABLE,"Death fence was delayed");phase=8;}
                case 8 -> {ready();var view=session.status(owner,run);
                    h.assertTrue(view.phase()==BootstrapController.Phase.TERMINAL,"Waiting for interrupted Run");
                    h.assertTrue("INTERRUPTED".equals(view.marker().outcome()),"Loss must remain interruption: "+view);
                    var c=session.citizen(owner,actor.citizenId());var job=session.job(run,c.owner()).orElseThrow();
                    h.assertTrue(c.lifecycle().event()==CitizenLifecycle.Event.DEATH&&job.state()==Jobs.State.INTERRUPTED
                            &&job.current().uncertain()&&job.fulfilled()==partial&&stock()==partial,"Identity/Job/material accounting diverged");
                    h.assertTrue(session.leaseOwner().snapshot().leases().stream().noneMatch(l->l.origin().equals(c.owner())&&l.state()==ResourceLeases.State.ACTIVE),"Affected lease remains live");
                    h.assertTrue(session.citizens(other).citizens().size()==1&&session.citizen(other,foreign.citizenId()).availability()==CitizenRegistry.Availability.LOADED,"Other owner interrupted");
                    try{session.citizen(other,actor.citizenId());h.fail("Foreign identity exposed");}catch(SecurityException expected){}
                    proof=new Proof(actor.citizenId(),actor.entityId(),owner.getUUID(),other.getUUID(),run,origin.getX(),origin.getY(),origin.getZ(),partial,view.receipts().size(),view.marker().effects(),ProcessHandle.current().pid());
                    quiet=h.getLevel().getGameTime()+30;phase=9;}
                case 9 -> {h.assertTrue(h.getLevel().getGameTime()>=quiet,"Checking late effects");
                    h.assertTrue(stock()==partial&&session.generationCalls()==modelBaseline&&session.languageCalls()==0,"Late replay or inference");
                    closing=session.storeClosure();session.close();phase=10;}
                case 10 -> {await(closing);writing=CompletableFuture.runAsync(()->{try{Files.writeString(world.resolve("lifecycle-proof.json"),new Gson().toJson(proof));
                        System.out.println("IMP-029 cold durable="+Files.readString(world.resolve(CitizenIdentityStore.WORLD_RELATIVE_PATH).resolve("state.json")));}catch(Exception e){throw new IllegalStateException(e);}});phase=11;}
                case 11 -> {await(writing);phase=20;}
                case 12 -> {await(reading);proof=reading.join();h.assertTrue(proof.process()!=ProcessHandle.current().pid(),"Warm phase reused JVM");
                    origin=new BlockPos(proof.x(),proof.y(),proof.z());BootstrapGameTests.forceFixtureChunks(h,origin);
                    session=new KernelSession(h.getLevel().getServer(),world);players(proof.principal(),proof.other());phase=13;}
                case 13 -> {ready();var c=session.citizen(owner,proof.citizen());
                    h.assertTrue(c.actor().entityId().equals(proof.entity())&&c.lifecycle().event()==CitizenLifecycle.Event.DEATH
                            &&c.lifecycle().acknowledged()==7&&c.availability()==CitizenRegistry.Availability.UNAVAILABLE,"Terminal saved binding revived: "+c);
                    var view=session.status(owner,proof.run());var job=session.job(proof.run(),c.owner()).orElseThrow();
                    h.assertTrue(view.marker().effects()==proof.effects()&&job.fulfilled()==proof.deposited()&&job.current().receipts().size()==proof.receipts()
                            &&job.state()==Jobs.State.INTERRUPTED&&stock()==proof.deposited(),"Saved effects/receipts lost or replayed");
                    h.assertTrue(session.generationCalls()==1&&session.languageCalls()==0&&session.lifecycleLookups()<=1,"Unbounded recovery or model dependence");
                    closing=session.storeClosure();session.close();phase=18;}
                case 18 -> {await(closing);phase=20;}
                case 20 -> { }
                default -> throw new IllegalStateException("Lifecycle phase "+phase);
            }
        }
    }
}
