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
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** GP-15 retention: production owners, actual shortages, isolated stores and separate JVM restart. */
public final class RetentionGameTests {
    @GameTest(maxTicks=2400, padding=32)
    public void loadedWorldRetentionBoundsRepeatedShortagesAndReclaimsOnlyUncommittedBody(GameTestHelper h) { run(h,false); }
    static void run(GameTestHelper h, boolean warm) {
        h.assertTrue((warm?"warm":"cold").equals(System.getenv("COGNITIVECRAFT_RETENTION_RESTART")),"Explicit retention process phase required");
        var driver = new Driver(h,warm);
        h.succeedWhen(() -> { driver.step(); h.assertTrue(driver.phase==5,"Waiting for retention phase="+driver.phase+" completed="+driver.completed); });
    }
    static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private static final class Driver {
        final GameTestHelper h; final boolean warm; final Path root; final CompletableFuture<Map<String,Object>> setup;
        Map<String,Object> proof; KernelSession session; Villager villager; ServerPlayer player,foreign;
        ActorRef actor; TrustedContext owner; BlockPos origin,chest; UUID run; int phase,completed,modelCalls;
        CompletableFuture<Void> closing,verification;
        Driver(GameTestHelper h, boolean warm) {
            this.h=h;this.warm=warm;root=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("cognitivecraft-retention-fixture");
            if(warm) {
                setup=CompletableFuture.supplyAsync(() -> {
                    try {
                        var marker=StrictJson.object(Files.readString(root.resolve("retention-restart.json")));
                        // Delete only the isolated disposable retrieval cache, through its actual owner.
                        CapabilityCatalog capabilities=id -> id.equals(CropDelivery.ID)?Optional.of(CropDelivery.SPEC):Optional.empty();
                        try(var repository=VersionedSkillRepository.open(root,new VersionedSkillRepository.RuntimeSnapshot("minecraft-26.3",capabilities,GatewayPrimitives.instance()),
                                VersionedSkillRepository.Limits.defaults(),d -> false,TrustedContext::equals,VersionedSkillRepository.FaultInjector.none())) {
                            var lookups=new CapabilityRetrievalIndex.LookupRegistry(Map.of("wheat",CropDelivery.ID),Map.of(CropDelivery.ID,Set.of("farming")),capabilities);
                            try(var cache=new CapabilityRetrievalIndex(root.resolve(CapabilityRetrievalIndex.WORLD_RELATIVE_PATH),
                                    new CapabilityRetrievalIndex.Identity(UUID.fromString((String)marker.get("world")),UUID.randomUUID()),CapabilityRetrievalIndex.repositorySource(repository,lookups,() -> 0),
                                    lookups,capabilities,CapabilityRetrievalIndex.Limits.defaults(),() -> System.nanoTime()/1_000_000)) { cache.deleteCache(); }
                        }
                        return marker;
                    } catch(Exception failure) {throw new IllegalStateException("Retention restart fixture",failure);}
                }); return;
            }
            BlockPos offset=new BlockPos(8,0,8);origin=h.absolutePos(offset);BootstrapGameTests.forceFixtureChunks(h,origin);
            for(int x=0;x<6;x++)for(int z=0;z<6;z++)h.setBlock(offset.offset(x,0,z),Blocks.STONE);
            h.setBlock(offset.offset(4,1,4),Blocks.CHEST);chest=h.absolutePos(offset.offset(4,1,4));
            ((Container)h.getLevel().getBlockEntity(chest)).setItem(0,new ItemStack(Items.WHEAT,7));
            villager=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,offset.getX()+2,1,offset.getZ()+2);villager.setNoAi(true);villager.setPersistenceRequired();
            player=h.makeMockServerPlayerInLevel();player.setPos(villager.getX(),villager.getY(),villager.getZ());
            UUID principal=player.getUUID(),entity=villager.getUUID();BlockPos savedOrigin=origin,savedChest=chest;
            setup=CompletableFuture.supplyAsync(() -> {
                try(var journal=BootstrapJournal.open(root)) {
                    journal.replace(journal.state(),null,List.of());
                    var context=new TrustedContext(new PrincipalRef(principal),new ScopeRef(journal.state().worldId(),principal));
                    CapabilityCatalog capabilities=id -> id.equals(CropDelivery.ID)?Optional.of(CropDelivery.SPEC):Optional.empty();
                    var artifact=((SkillCompiler.Success)new SkillCompiler(capabilities,GatewayPrimitives.instance(),ref -> Optional.empty()).compile(ResearchGameTests.fixtureIr())).skill().artifact();
                    var discarded=((SkillCompiler.Success)new SkillCompiler(capabilities,GatewayPrimitives.instance(),ref -> Optional.empty()).compile(ResearchGameTests.fixtureIr().replace("\"int\":30","\"int\":29"))).skill().artifact();
                    if(artifact.descriptor().ref().equals(discarded.descriptor().ref()))throw new IllegalStateException("Distinct scratch variant required");
                    AtomicBoolean fault=new AtomicBoolean();
                    try(var repository=VersionedSkillRepository.open(root,new VersionedSkillRepository.RuntimeSnapshot("minecraft-26.3",capabilities,GatewayPrimitives.instance()),
                            VersionedSkillRepository.Limits.defaults(),d -> true,TrustedContext::equals,at -> {if(fault.get()&&at==VersionedSkillRepository.FaultPoint.AFTER_BODY_WRITE)throw new java.io.IOException("Synthetic uncommitted body");})) {
                        var evidence=new EvidenceRef("synthetic-retention-fixture","fixture-1","test");
                        var bundle=new VersionedSkillRepository.EvidenceBundle(evidence,evidence,evidence,"b".repeat(64));
                        for(var skill:List.of(artifact,discarded)) {
                            var result=repository.publish(skill,new VersionedSkillRepository.AdmissionDecision(UUID.randomUUID(),context,bundle),
                                    new Provenance(1,null,"compiler-1","minecraft-26.3",context.scope().worldId(),List.of(),List.of(evidence)));
                            if(result.status()!=(fault.get()?VersionedSkillRepository.PublishStatus.STORAGE_UNAVAILABLE:VersionedSkillRepository.PublishStatus.ADMITTED))throw new IllegalStateException("Fixture publication: "+result);
                            fault.set(true);
                        }
                    }
                    // Synthetic fixture admission is not physical behavioral-admission evidence.
                    return Map.of("world",context.scope().worldId().toString(),"principal",principal.toString(),"entity",entity.toString(),
                            "origin",List.of((long)savedOrigin.getX(),(long)savedOrigin.getY(),(long)savedOrigin.getZ()),
                            "chest",List.of((long)savedChest.getX(),(long)savedChest.getY(),(long)savedChest.getZ()),
                            "artifact",artifact.descriptor().ref().sha256(),"orphan",discarded.descriptor().ref().sha256(),"coldProcess",ProcessHandle.current().pid(),
                            "coldProcessStart",java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime());
                }catch(Exception failure){throw new IllegalStateException("Retention owner fixture",failure);}
            });
        }
        @SuppressWarnings("unchecked") BlockPos position(String key) {var p=(List<Long>)proof.get(key);return new BlockPos(Math.toIntExact(p.get(0)),Math.toIntExact(p.get(1)),Math.toIntExact(p.get(2)));}
        void step() {
            if(phase==5)return;
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            if(session!=null&&phase<3)session.tick();
            switch(phase) {
                case 0 -> {
                    h.assertTrue(setup.isDone(),"Fixture worker setup pending");proof=setup.join();
                    owner=new TrustedContext(new PrincipalRef(UUID.fromString((String)proof.get("principal"))),new ScopeRef(UUID.fromString((String)proof.get("world")),UUID.fromString((String)proof.get("principal"))));
                    if(warm) {
                        h.assertTrue((Long)proof.get("coldProcess")!=ProcessHandle.current().pid()
                                || (Long)proof.get("coldProcessStart")!=java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime(),"Separate actual JVM required");
                        origin=position("origin");chest=position("chest");BootstrapGameTests.forceFixtureChunks(h,origin);
                        var entity=h.getLevel().getEntity(UUID.fromString((String)proof.get("entity")));h.assertTrue(entity instanceof Villager,"Saved actor not loaded");villager=(Villager)entity;
                        player=h.makeMockServerPlayerInLevel();player.setUUID(owner.principal().id());player.setPos(villager.getX(),villager.getY(),villager.getZ());
                    }
                    foreign=h.makeMockServerPlayerInLevel();foreign.setUUID(UUID.randomUUID());
                    GenerationPort offline=new GenerationPort(){
                        @Override public Generation.Handle generate(Generation.Request request,Budgets.InferenceLimits limits,Budgets.Ledger ledger){modelCalls++;throw new AssertionError("Retention called a model");}
                        @Override public Generation.Descriptor descriptor(){return new Generation.Descriptor("retention-offline-v1","unavailable-test-backend",null);}
                        @Override public Generation.Status status(){return new Generation.Status(Generation.State.UNAVAILABLE,0,null,Generation.Compute.NOT_STARTED,false,false);}
                    };
                    session=new KernelSession(h.getLevel().getServer(),root,null,offline,PrimitiveDiagnostics.noop());phase=1;
                }
                case 1 -> {
                    h.assertTrue(session.identityReady()&&session.jobsReady()&&session.leasesReady(),"Production owners pending");
                    if(warm) {actor=session.enrollment().actor();h.assertTrue(actor.citizenId().toString().equals(proof.get("citizen")),"Saved control identity changed");completed=20;phase=2;}
                    else {if(actor==null)actor=session.enroll(player,villager);h.assertTrue(session.identityReady(),"Enrollment publication pending");phase=2;}
                }
                case 2 -> {
                    h.assertTrue(((Container)h.getLevel().getBlockEntity(chest)).countItem(Items.WHEAT)==7,"Retention changed Minecraft inventory");
                    if(!warm&&completed<20) {
                        if(run==null) {
                            var submission=session.harvest(player,actor.citizenId(),1,origin.getX()+2,origin.getY()+1,origin.getZ()+3,origin.getX()+3,origin.getY()+1,origin.getZ()+4,chest.getX(),chest.getY(),chest.getZ());
                            h.assertTrue(submission.accepted(),"Actual shortage request rejected before routing: "+submission.reason());run=submission.id();
                        }
                        var view=session.status(player,run);h.assertTrue(view.phase()==BootstrapController.Phase.TERMINAL,"Shortage request pending");
                        h.assertTrue(view.marker().reason()==Outcomes.Reason.RESOURCE_MISSING&&view.marker().effects()==0&&view.marker().modelCalls()==0,"Precise unchanged resource shortage required");
                        completed++;run=null;return;
                    }
                    var failures=session.retentionFailures(owner);h.assertTrue(failures.size()==1&&failures.getFirst().count()==20,"Typed failure aggregation pending");
                    h.assertTrue(failures.getFirst().samples().size()==3,"Finite representative samples required");
                    h.assertTrue(session.retentionFailures(foreign).isEmpty(),"Private histories leaked");
                    try{session.catalog(foreign);throw new AssertionError("Foreign catalog access accepted");}catch(SecurityException expected){}
                    h.assertTrue(session.catalog(player).contains((String)proof.get("artifact")),"Retained admitted method missing");
                    var status=session.retentionStatus();h.assertTrue(status.queued()==0&&status.maxInspected()<=16&&status.maxReadBytes()<=131072,"Retention worker bounds exceeded");
                    if(!warm)h.assertTrue(status.collections()>=1,"Real orphan collection pending");
                    h.assertTrue(modelCalls==0,"Model-independent retention required");
                    session.close();closing=session.storeClosure();phase=3;
                }
                case 3 -> {
                    h.assertTrue(closing.isDone(),"Owned storage worker close pending");closing.join();
                    var values=new LinkedHashMap<String,Object>(proof);values.put("citizen",actor.citizenId().toString());values.put("completed",20L);
                    verification=CompletableFuture.runAsync(() -> {
                        try {
                            Path skills=root.resolve(VersionedSkillRepository.WORLD_RELATIVE_PATH);
                            if(Files.exists(skills.resolve("bodies/"+proof.get("orphan")+".json")))throw new IllegalStateException("Eligible scratch was not collected");
                            var observed=Map.of("manifestSha",hash(skills.resolve("manifest.json")),"identitySha",hash(root.resolve(CitizenIdentityStore.WORLD_RELATIVE_PATH).resolve("state.json")),
                                    "jobsSha",hash(root.resolve(JobJournal.WORLD_RELATIVE_PATH).resolve("state.jsonl")),"evidenceSha",hash(root.resolve(RetentionEvidenceStore.WORLD_RELATIVE_PATH).resolve("state.json")));
                            if(warm) {
                                for(var entry:observed.entrySet())if(!entry.getValue().equals(proof.get(entry.getKey())))throw new IllegalStateException("Cache deletion/restart changed owner: "+entry.getKey());
                            } else {values.putAll(observed);Files.writeString(root.resolve("retention-restart.json"),StrictJson.canonical(values));}
                            System.out.println("IMP-016 loaded-world retention accepted phase="+(warm?"warm":"cold")+" shortages=20 samples=3 modelCalls=0 inventory=7 uncertainEffectsReplayed=0");
                        }catch(Exception failure){throw new IllegalStateException("Retention verification",failure);}
                    });phase=4;
                }
                case 4 -> {h.assertTrue(verification.isDone(),"Fixture verification worker pending");verification.join();phase=5;}
                default -> throw new IllegalStateException("Retention phase");
            }
        }
    }
}
