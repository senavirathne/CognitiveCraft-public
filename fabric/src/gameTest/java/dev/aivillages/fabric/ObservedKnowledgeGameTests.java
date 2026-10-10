package dev.aivillages.fabric;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.SurvivalGateway.*;
import dev.aivillages.core.kernel.GatewayPrimitives.Operation;
import dev.aivillages.core.kernel.ObservedWorldKnowledgeStore.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** GP-17 uses the supported stock/crop gateway and its actual Fabric world adapter, with no planner. */
public final class ObservedKnowledgeGameTests {
    @GameTest(maxTicks=2400,padding=32)
    public void observedStockFreshnessPrivacySharingAndPhysicalRevalidation(GameTestHelper h) { run(h,false); }
    static void run(GameTestHelper h,boolean warm) {
        h.assertTrue((warm?"warm":"cold").equals(System.getenv("COGNITIVECRAFT_OBSERVATION_RESTART")),"Explicit observation restart phase required");
        var driver=new Driver(h,warm);
        h.succeedWhen(()->{driver.step();h.assertTrue(driver.done,"Observation worker phase pending: "+driver.phase);});
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    private static final class Time extends Clock {
        volatile long now=1_000_000;
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return Instant.ofEpochMilli(now);}public long millis(){return now;}
    }
    private record Stores(ObservedWorldKnowledgeStore store,ObservedKnowledgeService service) { }
    private static final class Driver {
        final GameTestHelper h;final boolean warm;final Path root;final Time clock=new Time();
        final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"observation-gametest-storage");t.setDaemon(true);return t;});
        final AtomicBoolean sharing=new AtomicBoolean();
        final CompletableFuture<Map<String,Object>> opening;
        Map<String,Object> marker;Stores owners;TrustedContext owner,foreign;ActorRef actor,other;
        Cuboid source;ContainerRef container;ObservationTarget target;SurvivalGateway gateway;FabricGatewayWorld physics;
        BlockPos crop,chest;ObservedEvidence original,changed,crops;CompletableFuture<?> turn;
        int phase;boolean done;
        Driver(GameTestHelper h,boolean warm) {
            this.h=h;this.warm=warm;root=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("cognitivecraft-observation-fixture");
            if(warm){opening=CompletableFuture.supplyAsync(()->{try{return StrictJson.object(Files.readString(root.resolve("restart.json")));}catch(Exception e){throw new CompletionException(e);}},worker);return;}
            BlockPos offset=new BlockPos(8,0,8);BootstrapGameTests.forceFixtureChunks(h,h.absolutePos(offset));
            for(int x=0;x<6;x++)for(int z=0;z<6;z++)h.setBlock(offset.offset(x,0,z),Blocks.STONE);
            h.setBlock(offset.offset(2,0,2),Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));
            h.setBlock(offset.offset(2,1,2),Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));crop=h.absolutePos(offset.offset(2,1,2));
            h.setBlock(offset.offset(2,1,0),Blocks.CHEST);chest=h.absolutePos(offset.offset(2,1,0));
            ((Container)h.getLevel().getBlockEntity(chest)).setItem(0,new ItemStack(Items.WHEAT,7));
            Villager a=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,offset.getX()+3,1,offset.getZ()+2),b=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,offset.getX()+4,1,offset.getZ()+2);
            a.setNoAi(true);b.setNoAi(true);a.setPersistenceRequired();b.setPersistenceRequired();
            var p=h.makeMockServerPlayerInLevel();var q=h.makeMockServerPlayerInLevel();q.setUUID(UUID.randomUUID());
            UUID world=UUID.randomUUID();owner=context(world,p.getUUID());foreign=context(world,q.getUUID());
            String dimension=h.getLevel().dimension().identifier().toString();actor=new ActorRef(UUID.randomUUID(),a.getUUID(),dimension);other=new ActorRef(UUID.randomUUID(),b.getUUID(),dimension);
            setupGateway();opening=CompletableFuture.completedFuture(Map.of());
        }
        static TrustedContext context(UUID world,UUID principal){return new TrustedContext(new PrincipalRef(principal),new ScopeRef(world,principal));}
        void setupGateway() {
            String d=actor.dimension();source=new Cuboid(d,crop.getX(),crop.getY(),crop.getZ(),crop.getX(),crop.getY(),crop.getZ());
            container=new ContainerRef(d,chest.getX(),chest.getY(),chest.getZ());
            target=new ObservationTarget(ObservationKind.CONTAINER_STOCK,new Cuboid(d,chest.getX(),chest.getY(),chest.getZ(),chest.getX(),chest.getY(),chest.getZ()),null);
            physics=new FabricGatewayWorld(h.getLevel());
            gateway=new SurvivalGateway(physics,(a,e,c)->a.equals(actor)&&c.equals(owner)||a.equals(other)&&c.equals(foreign),
                    new SurvivalGateway.Limits(2,64,16,16,16,1,200,16),clock);
        }
        Stores open() {
            try {
                var store=ObservedWorldKnowledgeStore.open(root,owner.scope().worldId(),ObservedWorldKnowledgeStore.Limits.defaults(),clock,
                        (a,e,c)->a.equals(actor)&&c.equals(owner)||a.equals(other)&&c.equals(foreign),
                        (viewer,origin)->viewer.equals(origin)||sharing.get()&&viewer.equals(foreign)&&origin.equals(owner),
                        id->id.equals(gateway.observationSource()),p->{});
                var manager=new WorldRetentionManager(WorldRetentionManager.Policy.defaults(),new RetentionRoots(),c->false);manager.register(store.retentionOwner());
                return new Stores(store,new ObservedKnowledgeService(store));
            }catch(Exception e){throw new CompletionException(e);}
        }
        ValidatedRequest request(ActorRef selected,TrustedContext caller,Cuboid area,ContainerRef destination) {
            return new ValidatedRequest(new CapabilityRequest(CropDelivery.ID,Map.of("actor",new ActorValue(selected),"amount",new IntValue(1),
                    "source",new AreaValue(area),"destination",new ContainerValue(destination))),caller,new ObservationRef(UUID.randomUUID(),h.getLevel().getGameTime(),selected.dimension()));
        }
        ActionReceipt action(Operation op,ActorRef selected,TrustedContext caller,Cuboid area,ContainerRef destination) {
            clock.now++;
            Map<String,Value> args=switch(op){case OBSERVE_SOURCE->Map.of("actor",new ActorValue(selected),"source",new AreaValue(area));
                case OBSERVE_CONTAINER->Map.of("actor",new ActorValue(selected),"destination",new ContainerValue(destination));
                case HARVEST_WHEAT->Map.of("actor",new ActorValue(selected),"x",new IntValue(area.minX()),"y",new IntValue(area.minY()),"z",new IntValue(area.minZ()));
                default->throw new IllegalArgumentException();};
            var ledger=new Budgets.Ledger(new Budgets.Limits(Map.of(Budgets.Kind.OBSERVATIONS,64L,Budgets.Kind.INSTRUCTIONS,128L,
                    Budgets.Kind.ELAPSED_TICKS,200L,Budgets.Kind.ATTEMPTED_EFFECTS,4L,Budgets.Kind.COMMITTED_EFFECTS,4L),clock.now+10000),clock);
            UUID run=UUID.randomUUID();var handle=gateway.start(request(selected,caller,area,destination),new RunCorrelation(run,new ArtifactRef(CropDelivery.ID,"a".repeat(64)),null),op.requirement(),args,ledger);
            var receipt=gateway.poll(handle);h.assertTrue(receipt.terminal(),"One-cell primitive should finish in one poll");gateway.releaseRun(run);return receipt;
        }
        ObservedEvidence capture(Operation op,ActorRef selected,TrustedContext caller,Cuboid area,ContainerRef destination) {
            var receipt=action(op,selected,caller,area,destination);h.assertTrue(receipt.reason()==null&&receipt.committedEffects()==0,"Actual permitted observation required");
            return gateway.observedEvidence(receipt.handle(),caller);
        }
        EvidenceSnapshot query(TrustedContext caller,ObservationTarget target){return owners.store.queryFreshEvidence(caller,target,QueryLimits.defaults());}
        void flush(){owners.service.maintain(worker);}
        void waitTurn(){h.assertTrue(turn.isDone(),"Observation storage worker pending");turn.join();}
        @SuppressWarnings("unchecked") BlockPos position(String key){var p=(List<Long>)marker.get(key);return new BlockPos(Math.toIntExact(p.get(0)),Math.toIntExact(p.get(1)),Math.toIntExact(p.get(2)));}
        void step() {
            if(done)return;
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            if(owners!=null)owners.service.beginTick();
            switch(phase) {
                case 0->{
                    h.assertTrue(opening.isDone(),"Restart marker pending");marker=opening.join();
                    if(warm){
                        h.assertTrue((Long)marker.get("process")!=ProcessHandle.current().pid()||(Long)marker.get("processStart")!=java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime(),"Separate actual JVM required");
                        UUID world=UUID.fromString((String)marker.get("world"));owner=context(world,UUID.fromString((String)marker.get("owner")));foreign=context(world,UUID.fromString((String)marker.get("foreign")));
                        actor=new ActorRef(UUID.fromString((String)marker.get("citizen")),UUID.fromString((String)marker.get("entity")),(String)marker.get("dimension"));
                        other=new ActorRef(UUID.fromString((String)marker.get("otherCitizen")),UUID.fromString((String)marker.get("otherEntity")),actor.dimension());
                        crop=position("crop");chest=position("chest");BootstrapGameTests.forceFixtureChunks(h,chest);clock.now=(Long)marker.get("clock");sharing.set(true);setupGateway();
                        h.assertTrue(h.getLevel().getBlockState(chest).isAir(),"Actual removed saved facility survived restart");
                    }
                    turn=CompletableFuture.supplyAsync(this::open,worker);phase=1;
                }
                case 1->{
                    waitTurn();owners=(Stores)turn.join();gateway.observationSink(owners.service::offer);
                    if(warm){turn=CompletableFuture.runAsync(()->{
                        var before=query(owner,target);check(before.complete()&&before.evidence().size()==2,"Retained private history missing");
                        var row=before.evidence().getFirst().record();check(row.id().toString().equals(marker.get("latest"))&&row.latest().at()==(Long)marker.get("latestAt"),"Reload changed provenance or time");
                        check(before.freshness()==Freshness.STALE&&before.evidence().getFirst().qualification()==Qualification.SOURCE_LOST,"Restart cannot verify old gateway source");
                        check(query(foreign,target).evidence().size()==2,"Exact explicit share lost");
                        owners.store.deleteDerivedIndexes();check(query(owner,target).status()==QueryStatus.INDEX_UNAVAILABLE,"Index deletion hidden");
                        var build=owners.store.beginIndexRebuild();while(owners.store.rebuildIndex(build,1).status()==IndexStatus.INCOMPLETE){}
                        check(query(owner,target).evidence().equals(before.evidence()),"Index rebuild changed retained meaning");
                    },worker);phase=5;return;}
                    h.assertTrue(physics.actor(actor).loaded()&&physics.actor(other).loaded(),"Both fixture actors must be ticking before capture");
                    original=capture(Operation.OBSERVE_CONTAINER,actor,owner,source,container);crops=capture(Operation.OBSERVE_SOURCE,actor,owner,source,container);flush();
                    turn=CompletableFuture.runAsync(()->{
                        var q=query(owner,target);check(q.freshness()==Freshness.CURRENTLY_VERIFIED&&q.evidence().getFirst().record().latest().observation().counts().get("wheat")==7,"Actual observed stock missing");
                        check(query(foreign,target).evidence().isEmpty(),"Foreign private observation leaked");
                    },worker);phase=2;
                }
                case 2->{
                    waitTurn();h.getLevel().setBlockAndUpdate(chest,Blocks.AIR.defaultBlockState());h.getLevel().setBlockAndUpdate(crop,Blocks.AIR.defaultBlockState());
                    changed=capture(Operation.OBSERVE_CONTAINER,actor,owner,source,container);var effect=action(Operation.HARVEST_WHEAT,actor,owner,source,container);
                    h.assertTrue(effect.reason()==Outcomes.Reason.TARGET_INVALID&&effect.committedEffects()==0,"Effects must revalidate actual removed crop");flush();
                    turn=CompletableFuture.runAsync(()->{
                        var q=query(owner,target);check(q.freshness()==Freshness.OBSERVED_ABSENT&&q.evidence().size()==2,"Changed stock not superseded");
                        check(q.evidence().getLast().freshness()==Freshness.STALE&&q.evidence().getLast().record().latest().at()==original.at(),"Conflicting historical provenance lost");
                        check(owners.store.recordObservation(owner,"model says chest contains wheat",()->false).status()==Status.UNVERIFIED,"Model assertion entered physical store");
                        var id=owners.store.queryFreshEvidence(owner,crops.target(),QueryLimits.defaults()).evidence().getFirst().record().id();
                        check(owners.store.invalidateObservation(owner,id,Invalidation.TARGET_CHANGED,owners.store.revision(),()->false).status()==Status.INVALIDATED,"Changed target invalidation failed");
                    },worker);phase=3;
                }
                case 3->{
                    waitTurn();
                    h.assertTrue(physics.actor(actor).loaded()&&physics.actor(other).loaded(),"Both fixture actors must be ticking before private captures");
                    var unloaded=new Cuboid(actor.dimension(),20_000_000,64,20_000_000,20_000_000,64,20_000_000);
                    var unknown=capture(Operation.OBSERVE_SOURCE,actor,owner,unloaded,container);h.assertTrue(unknown.snapshot().status()==ObservationStatus.UNKNOWN,"Unloaded gateway observation became absence");
                    capture(Operation.OBSERVE_CONTAINER,other,foreign,source,container);flush();
                    turn=CompletableFuture.runAsync(()->{
                        check(query(owner,unknown.target()).freshness()==Freshness.UNKNOWN,"Unknown retained as absent");
                        var privateForeign=query(foreign,target);var privateOwner=query(owner,target);
                        check(privateForeign.evidence().size()==1&&privateOwner.evidence().size()==2,
                                "Private history counts: foreign="+privateForeign.evidence().size()+" owner="+privateOwner.evidence().size()+" intake="+owners.service.status());
                        var id=query(owner,target).evidence().getFirst().record().id();
                        check(owners.store.shareObservation(owner,id,foreign,owners.store.revision(),()->false).status()==Status.DENIED,"Policy-free share permitted");
                        sharing.set(true);check(owners.store.shareObservation(owner,id,foreign,owners.store.revision(),()->false).status()==Status.SHARED,"Explicit approved share failed");
                        check(query(foreign,target).evidence().size()==2&&query(foreign,crops.target()).evidence().isEmpty(),"Sharing exposed unrelated records");
                        var shared=query(foreign,target);sharing.set(false);check(!owners.store.snapshotCurrent(shared,foreign)&&query(foreign,target).evidence().size()==1,"Revoked share stayed visible");sharing.set(true);
                        clock.now=query(owner,target).evidence().getFirst().record().expiresAt()+100;
                        check(query(owner,target).freshness()==Freshness.STALE,"Expiry falsely refreshed evidence");
                        check(query(owner,crops.target()).freshness()==Freshness.INVALID_TARGET,"Changed facility regained validity");
                        var unobserved=new ObservationTarget(ObservationKind.SOURCE_CROPS,new Cuboid(actor.dimension(),20_000_001,64,20_000_001,20_000_001,64,20_000_001),null);
                        check(query(owner,unobserved).freshness()==Freshness.UNKNOWN&&query(owner,unobserved).evidence().isEmpty(),"Unobserved area not unknown");
                    },worker);phase=4;
                }
                case 4->{
                    waitTurn();var denied=action(Operation.OBSERVE_CONTAINER,actor,foreign,source,container);
                    h.assertTrue(denied.reason()==Outcomes.Reason.AUTHORITY_DENIED&&denied.committedEffects()==0,"Sharing granted physical control");
                    gateway.observationSink(e->{});turn=CompletableFuture.runAsync(()->{
                        try {
                            var before=query(owner,target);owners.service.closeWorker();owners.store.close();owners=open();
                            check(query(owner,target).evidence().equals(before.evidence()),"Same-process reload changed age or provenance");
                            owners.store.deleteDerivedIndexes();var build=owners.store.beginIndexRebuild();while(owners.store.rebuildIndex(build,1).status()==IndexStatus.INCOMPLETE){}
                            check(query(owner,target).evidence().equals(before.evidence()),"Deleting indexes erased observations");
                            var row=query(owner,target).evidence().getFirst().record();var proof=new LinkedHashMap<String,Object>();
                            proof.put("world",owner.scope().worldId().toString());proof.put("owner",owner.principal().id().toString());proof.put("foreign",foreign.principal().id().toString());
                            proof.put("citizen",actor.citizenId().toString());proof.put("entity",actor.entityId().toString());proof.put("otherCitizen",other.citizenId().toString());proof.put("otherEntity",other.entityId().toString());
                            proof.put("dimension",actor.dimension());proof.put("crop",List.of((long)crop.getX(),(long)crop.getY(),(long)crop.getZ()));proof.put("chest",List.of((long)chest.getX(),(long)chest.getY(),(long)chest.getZ()));
                            proof.put("latest",row.id().toString());proof.put("latestAt",row.latest().at());proof.put("clock",clock.now);proof.put("modelCalls",0L);proof.put("queryEffects",0L);
                            proof.put("process",ProcessHandle.current().pid());proof.put("processStart",java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime());
                            Files.writeString(root.resolve("restart.json"),StrictJson.canonical(proof));
                        }catch(Exception e){throw new CompletionException(e);}
                    },worker);phase=5;
                }
                case 5->{waitTurn();turn=CompletableFuture.runAsync(()->{try{owners.service.closeWorker();owners.store.close();}catch(Exception e){throw new CompletionException(e);}},worker);phase=6;}
                case 6->{waitTurn();worker.shutdown();done=true;System.out.println("IMP-017 GP-17 accepted phase="+(warm?"warm":"cold")+" modelCalls=0 queryEffects=0 retainedProvenance=true");}
            }
        }
    }
}
