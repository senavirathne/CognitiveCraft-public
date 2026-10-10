package dev.aivillages.fabric;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.Outcomes.*;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import com.google.gson.JsonParser;

/** Controlled generation through production composition, including private storage and blocked I/O. */
public final class PrimitiveDiagnosticGameTests {
    @GameTest(maxTicks = 1200, padding = 32)
    public void rejectsMissingPrimitiveWithPrivateObservation(GameTestHelper h) { run(h, true); }

    @GameTest(maxTicks = 1200, padding = 32)
    public void rejectsMissingPrimitiveWhenDiagnosticsAreOff(GameTestHelper h) { run(h, false); }

    @GameTest(maxTicks = 1200, padding = 32)
    public void storesAndReplaysOnlyPrivateDemandAcrossOwnerReload(GameTestHelper h) {
        var driver=new Driver(h,false,true);
        h.succeedWhen(()->{driver.step();h.assertTrue(driver.phase==6,"Waiting for stored diagnostic reload");});
    }

    @GameTest(maxTicks = 2400, padding = 32)
    public void blockedDiagnosticWriteCannotBlockKnownJobsOrModelFreeOwnerReload(GameTestHelper h) {
        var driver=new BlockedDriver(h);
        h.succeedWhen(()->{driver.step();h.assertTrue(driver.phase==10,"Waiting for blocked diagnostic regression phase="+driver.phase);});
    }

    private static void run(GameTestHelper h, boolean observe) {
        var driver = new Driver(h, observe);
        h.succeedWhen(() -> {
            driver.step();
            h.assertTrue(driver.phase == 4, "Waiting for missing primitive rejection phase=" + driver.phase);
        });
    }
    private static final class Model implements GenerationPort {
        int calls;
        String candidate;
        private final Generation.Descriptor descriptor = new Generation.Descriptor("controlled-gametest-v1", "missing-call-fixture", null);
        @Override public Generation.Descriptor descriptor() { return descriptor; }
        @Override public Generation.Status status() {
            return new Generation.Status(Generation.State.READY, 0, null, Generation.Compute.NOT_STARTED, false, false);
        }
        @Override public Generation.Handle generate(Generation.Request request, Budgets.InferenceLimits limits, Budgets.Ledger usage) {
            calls++;
            String ir = StrictJson.canonical(Map.of("schema", 1L, "capability", CropDelivery.ID.name(),
                    "capabilityVersion", 1L, "dependencies", List.of(), "body", List.of(Map.of("op", "call", "kind", "primitive",
                            "id", "cognitivecraft:smelt_item", "version", 1L, "fingerprint", "0".repeat(64),
                            "args", Map.of("item", Map.of("int", 1L)), "into", "unavailableResult"),
                            Map.of("op", "result", "value", Map.of("param", "amount")))));
            candidate=ir;
            var allowance = limits.chargeCall(usage, 128, request.role() == Generation.Role.REPAIR);
            allowance.accept(ir.getBytes(StandardCharsets.UTF_8).length); allowance.close();
            var result = new Generation.Result(request.id(), request.bound().context(), request.role(), Generation.Outcome.CANDIDATE,
                    null, ir, descriptor, new Generation.Usage(128, ir.getBytes(StandardCharsets.UTF_8).length,
                    -1, -1, Generation.Precision.UNKNOWN), Generation.Compute.COMPLETED);
            return new Generation.Handle() {
                @Override public UUID id() { return request.id(); }
                @Override public java.util.concurrent.CompletionStage<Generation.Result> result() { return CompletableFuture.completedFuture(result); }
                @Override public boolean cancel() { return false; }
                @Override public Generation.Compute compute() { return Generation.Compute.COMPLETED; }
            };
        }
    }
    private static final class Driver {
        final GameTestHelper h;
        final boolean observe;
        final BlockPos origin;
        final Model model = new Model();
        final List<PrimitiveDiagnostics.Observation> observations = new ArrayList<>();
        KernelSession session;
        final boolean persist;
        final Path root;
        final Villager villager;
        ServerPlayer owner;
        ActorRef actor;
        UUID run;
        CompletableFuture<Void> closing;
        CompletableFuture<Void> diagnosticClosing,verification;
        int phase;
        Driver(GameTestHelper h, boolean observe) {
            this(h,observe,false);
        }
        Driver(GameTestHelper h, boolean observe,boolean persist) {
            this.h = h; this.observe = observe;this.persist=persist;origin = h.absolutePos(BlockPos.ZERO);
            BootstrapGameTests.forceFixtureChunks(h, origin);
            for (int x = 0; x < 6; x++) for (int z = 0; z < 6; z++)
                h.getLevel().setBlockAndUpdate(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState());
            h.getLevel().setBlockAndUpdate(origin.offset(2, 0, 3), Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7));
            h.getLevel().setBlockAndUpdate(origin.offset(2, 1, 3), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
            h.getLevel().setBlockAndUpdate(origin.offset(4, 1, 3), Blocks.CHEST.defaultBlockState());
            villager = h.spawnWithNoFreeWill(EntityTypes.VILLAGER, 2, 1, 2);
            villager.setNoAi(true); villager.setPersistenceRequired();
            root = h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve(
                    persist?"cognitivecraft-primitive-store-fixture":observe ? "cognitivecraft-primitive-observation-fixture" : "cognitivecraft-primitive-off-fixture");
            PrimitiveDiagnostics.Sink sink = observe ? event -> { observations.add(event); return PrimitiveDiagnostics.Offer.ACCEPTED; }
                    : PrimitiveDiagnostics.noop();
            session = persist?new KernelSession(h.getLevel().getServer(),root,null,model,null,
                    PrimitiveDiagnostics.Policy.metadata(),PrimitiveDiagnosticStore.Faults.none())
                    :new KernelSession(h.getLevel().getServer(), root, null, model, sink);
        }
        void step() {
            if (phase == (persist?6:4)) return;
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            if (phase < 3) session.tick();
            switch (phase) {
                case 0 -> {
                    h.assertTrue(session.identityReady(), "Waiting for production identity store");
                    if(persist)h.assertTrue(session.diagnosticStatus().state()==PrimitiveDiagnosticService.State.READY,"Waiting for private diagnostic worker");
                    owner = h.makeMockServerPlayerInLevel();
                    owner.setUUID(UUID.randomUUID());
                    owner.setPos(villager.getX(), villager.getY(), villager.getZ());
                    actor = session.enroll(owner, villager); phase = 1;
                }
                case 1 -> {
                    h.assertTrue(session.identityReady(), "Waiting for enrollment acknowledgement");
                    session.inference(true);
                    var source = origin.offset(2, 1, 3); var destination = origin.offset(4, 1, 3);
                    var submitted = session.harvest(owner, actor.citizenId(), 1,
                            source.getX(), source.getY(), source.getZ(), source.getX(), source.getY(), source.getZ(),
                            destination.getX(), destination.getY(), destination.getZ());
                    h.assertTrue(submitted.accepted(), "Waiting for accepted actual production run");
                    run = submitted.id(); phase = 2;
                }
                case 2 -> {
                    var status = session.status(owner, run);
                    h.assertTrue(status.phase() == BootstrapController.Phase.TERMINAL, "Waiting for production rejection");
                    h.assertTrue(status.research() != null && status.research().status() == ResearchStatus.NOT_ADMITTED
                            && status.research().reason() == Reason.UNSUPPORTED_PRIMITIVE, "Wrong missing primitive classification");
                    h.assertTrue(model.calls == 1, "Missing primitive was repaired or regenerated");
                    h.assertTrue(observations.size() == (observe ? 1 : 0), "Incorrect private observation count");
                    if(persist)h.assertTrue(session.diagnosticStatus().persistedSnapshots()==1,"Waiting for one sanitized schema-1 record");
                    h.assertTrue(h.getLevel().getBlockState(origin.offset(2, 1, 3)).getValue(CropBlock.AGE) == 7,
                            "Rejected candidate harvested wheat");
                    h.assertTrue(((Container) h.getLevel().getBlockEntity(origin.offset(4, 1, 3))).countItem(Items.WHEAT) == 0,
                            "Rejected candidate transferred wheat");
                    h.assertTrue(villager.getInventory().countItem(Items.WHEAT) == 0, "Rejected candidate picked up wheat");
                    for (int i = 0; i < 4; i++) session.status(owner, run);
                    h.assertTrue(model.calls == 1 && observations.size() == (observe ? 1 : 0), "Status polling created demand");
                    session.close(); closing = session.storeClosure();diagnosticClosing=session.diagnosticClosure();phase = 3;
                }
                case 3 -> {
                    h.assertTrue(closing.isDone(), "Waiting for asynchronous owner closure");
                    h.assertTrue(!closing.isCompletedExceptionally(), "Production owner closure failed");
                    h.assertTrue(diagnosticClosing.isDone(),"Waiting for private store closure");
                    if(persist) {
                        if(verification==null) {
                            UUID principal=owner.getUUID();String candidate=model.candidate;
                            verification=CompletableFuture.runAsync(()->verifyRecord(root,principal,candidate));
                        }
                        h.assertTrue(verification.isDone(),"Reading private record off the game thread");verification.join();
                        session=new KernelSession(h.getLevel().getServer(),root,null,model,null,
                                PrimitiveDiagnostics.Policy.metadata(),PrimitiveDiagnosticStore.Faults.none());
                        phase=4;break;
                    }
                    System.out.println("IMP-014.1 controlled runtime accepted classification=UNSUPPORTED_PRIMITIVE generationCalls=1 repairCalls=0 effects=0 observations="
                            + observations.size() + " mode=" + (observe ? "test-observer" : "off"));
                    phase = 4;
                }
                case 4 -> {
                    session.tick();h.assertTrue(session.identityReady(),"Reloading private owner stores");
                    h.assertTrue(session.diagnosticStatus().state()==PrimitiveDiagnosticService.State.READY,"Replaying bounded diagnostic history");
                    h.assertTrue(session.diagnosticStatus().aggregateCount()==1&&session.diagnosticStatus().persistedSnapshots()==0,
                            "Cumulative diagnostic replay double-counted or appended");
                    h.assertTrue(model.calls==1,"Diagnostic replay triggered generation");
                    session.inference(false);session.close();closing=session.storeClosure();diagnosticClosing=session.diagnosticClosure();phase=5;
                }
                case 5 -> {
                    h.assertTrue(closing.isDone()&&diagnosticClosing.isDone(),"Closing reloaded owners");
                    h.assertTrue(!closing.isCompletedExceptionally(),"Reload closure failed");
                    System.out.println("IMP-014.1 stored rejection accepted schema=1 attempts=1 generationCalls=1 repairs=0 effects=0 replayCalls=0 privacy=host-only");
                    phase=6;
                }
                default -> throw new IllegalStateException("Primitive diagnostic fixture phase");
            }
        }
    }
    private static void verifyRecord(Path world,UUID principal,String candidate) {
        try {
            Path root=world.resolve(PrimitiveDiagnosticStore.RELATIVE_ROOT);
            List<Path> files;
            try(var paths=Files.list(root)){files=paths.filter(p->p.getFileName().toString().matches("segment-[0-7]\\.jsonl")).toList();}
            if(files.size()!=1)throw new AssertionError("One private segment required");
            String text=Files.readString(files.getFirst());var row=JsonParser.parseString(text).getAsJsonObject();
            if(row.get("schema").getAsInt()!=1||row.get("occurrences").getAsLong()!=1||row.get("revision").getAsLong()!=1)
                throw new AssertionError("Wrong diagnostic snapshot");
            if(text.contains(principal.toString())||text.contains("unavailableResult")||text.contains(candidate))
                throw new AssertionError("Private record retained raw identity/source");
            var rep=row.getAsJsonObject("representative");
            String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(candidate.getBytes(StandardCharsets.UTF_8)));
            if(!rep.get("candidateSha256").getAsString().equals(sha)||!rep.get("requestProjection").getAsString().isEmpty()
                    ||!rep.get("candidateExcerpt").getAsString().isEmpty())throw new AssertionError("Incorrect metadata projection");
            if(!row.getAsJsonObject("catalog").get("fingerprint").getAsString().equals(
                    PrimitiveDiagnostics.completeCatalog(GatewayPrimitives.instance().all()).fingerprint()))throw new AssertionError("Incomplete catalog identity");
            for(String field:List.of("worldRef","scopeRef","principalRef"))
                if(!row.get(field).getAsString().matches("[a-f0-9]{64}"))throw new AssertionError("Unhashed context");
        } catch(Exception failure){throw new IllegalStateException("Private fixture verification failed",failure);}
    }
    private static final class BlockedDriver {
        final GameTestHelper h;final BlockPos origin;final Path root;
        final BootstrapGameTests.Run setup;final Villager known,gap;
        final Model model=new Model();final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        KernelSession session,gapSession;PrimitiveDiagnosticService diagnostics;
        ServerPlayer knownOwner,gapOwner;ActorRef gapActor;
        CompletableFuture<Void> closing,gapClosing,diagnosticClosing,restartMarker;
        UUID gapRun,knownJob;int phase;long callsAfterGap;int delivered;
        BlockedDriver(GameTestHelper h) {
            this.h=h;origin=h.absolutePos(new BlockPos(8,0,8));BootstrapGameTests.forceFixtureChunks(h,origin);
            root=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("cognitivecraft-diagnostic-blocked");
            for(int x=0;x<10;x++)for(int z=0;z<9;z++)h.getLevel().setBlockAndUpdate(origin.offset(x,0,z),Blocks.STONE.defaultBlockState());
            for(int x=2;x<=3;x++)for(int z=3;z<=4;z++)plant(x,z);
            h.getLevel().setBlockAndUpdate(origin.offset(4,1,4),Blocks.CHEST.defaultBlockState());
            h.getLevel().setBlockAndUpdate(origin.offset(7,1,0),Blocks.CHEST.defaultBlockState());
            known=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,11,1,11);known.setNoAi(false);known.setPersistenceRequired();
            gap=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,15,1,9);gap.setNoAi(true);gap.setPersistenceRequired();
            setup=new BootstrapGameTests.Run(h,known,null,root,origin,null,true,true);
        }
        void plant(int x,int z) {
            h.getLevel().setBlockAndUpdate(origin.offset(x,0,z),Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));
            h.getLevel().setBlockAndUpdate(origin.offset(x,1,z),Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));
        }
        void await(CompletableFuture<?> future) {
            h.assertTrue(future.isDone(),"Waiting for independent owner closure");
            h.assertTrue(!future.isCompletedExceptionally(),"Owner closure failed");
        }
        void startKnown() {
            plant(2,3);var p=origin.offset(2,1,3);var d=origin.offset(4,1,4);
            var submitted=session.queueHarvest(knownOwner,setup.actor.citizenId(),1,
                    new Cuboid(setup.actor.dimension(),p.getX(),p.getY(),p.getZ(),p.getX(),p.getY(),p.getZ()),
                    new ContainerRef(setup.actor.dimension(),d.getX(),d.getY(),d.getZ()));
            h.assertTrue(submitted.accepted(),"Known admitted job rejected");knownJob=submitted.id();
        }
        void step() {
            if(phase==10)return;
            java.util.concurrent.locks.LockSupport.parkNanos(phase<=1?50_000_000:1_000_000);
            if(diagnostics!=null)diagnostics.beginTick();
            if(session!=null&&(phase>=2&&phase<=5||phase>=7&&phase<=8))session.tick();
            if(gapSession!=null&&phase>=2&&phase<=5)gapSession.tick();
            switch(phase) {
                case 0 -> {setup.advance();h.assertTrue(setup.stage==9,"Acquiring known method with explicit setup fake");
                    h.assertTrue(setup.fakeCalls==1,"Setup inference count changed");closing=setup.closeAcceptanceFixture();phase=1;}
                case 1 -> {
                    await(closing);
                    PrimitiveDiagnosticStore.Faults faults=point->{
                        if(point==PrimitiveDiagnosticStore.Point.WRITE){
                            entered.countDown();boolean done=false;
                            while(!done)try{release.await();done=true;}catch(InterruptedException ignored){}
                        }
                    };
                    // The production eligibility policy shares admitted world methods. An independent
                    // empty fixture store is required to establish a real supported composition gap.
                    // Both production controller compositions use this one diagnostic worker/sink.
                    Path gapRoot=root.resolveSibling("cognitivecraft-diagnostic-gap");
                    diagnostics=PrimitiveDiagnosticService.open(gapRoot,PrimitiveDiagnostics.Policy.metadata(),Clock.systemUTC(),ignored->{},faults);
                    session=new KernelSession(h.getLevel().getServer(),root,null,model,diagnostics);
                    gapSession=new KernelSession(h.getLevel().getServer(),gapRoot,null,model,diagnostics);
                    knownOwner=h.makeMockServerPlayerInLevel();knownOwner.setUUID(setup.owner.principal().id());
                    gapOwner=h.makeMockServerPlayerInLevel();gapOwner.setUUID(UUID.randomUUID());gapOwner.setPos(gap.getX(),gap.getY(),gap.getZ());
                    phase=2;
                }
                case 2 -> {
                    h.assertTrue(session.identityReady()&&gapSession.identityReady()
                            &&diagnostics.status().state()==PrimitiveDiagnosticService.State.READY,
                            "Loading production/private owners known="+session.identityReady()+" gap="+gapSession.identityReady()+
                                    " diagnostics="+diagnostics.status().state());
                    gapActor=gapSession.enroll(gapOwner,gap);phase=3;
                }
                case 3 -> {
                    h.assertTrue(gapSession.identityReady(),"Acknowledging foreign-scope gap actor");plant(6,1);
                    gapSession.inference(true);var p=origin.offset(6,1,1);var d=origin.offset(7,1,0);
                    var request=gapSession.harvest(gapOwner,gapActor.citizenId(),1,p.getX(),p.getY(),p.getZ(),p.getX(),p.getY(),p.getZ(),d.getX(),d.getY(),d.getZ());
                    h.assertTrue(request.accepted(),"Authorized missing strategy rejected before compiler");gapRun=request.id();phase=4;
                }
                case 4 -> {
                    var status=gapSession.status(gapOwner,gapRun);
                    h.assertTrue(status.phase()==BootstrapController.Phase.TERMINAL,"Waiting for unsupported rejection");
                    h.assertTrue(status.research()!=null&&status.research().reason()==Reason.UNSUPPORTED_PRIMITIVE&&model.calls==1,
                            "Missing call must reach generation and compiler rejection");
                    h.assertTrue(entered.getCount()==0,"Waiting for actual diagnostic write barrier");
                    h.assertTrue(h.getLevel().getBlockState(origin.offset(6,1,1)).getValue(CropBlock.AGE)==7,"Rejected strategy changed source");
                    callsAfterGap=session.generationCalls();session.inference(false);startKnown();phase=5;
                }
                case 5 -> {
                    h.assertTrue(session.queuedJobIds(knownOwner,false).contains(knownJob),"Acknowledging known job");
                    var job=session.queuedJob(knownOwner,knownJob);
                    h.assertTrue(job!=null&&job.state()==Jobs.State.SUCCEEDED,"Known physical job must progress during blocked diagnostic I/O");
                    h.assertTrue(job.fulfilled()==1&&job.attempts().size()==1,"Known job duplicated");
                    h.assertTrue(release.getCount()==1&&session.generationCalls()==callsAfterGap&&model.calls==1,"Known job waited for logger or used inference");
                    delivered=((Container)h.getLevel().getBlockEntity(origin.offset(4,1,4))).countItem(Items.WHEAT);
                    h.assertTrue(delivered==5,"Known physical delivery not conserved");
                    session.clearDispatchCache();session.close();closing=session.storeClosure();
                    gapSession.close();gapClosing=gapSession.storeClosure();diagnosticClosing=diagnostics.closeAsync();phase=6;
                }
                case 6 -> {
                    await(closing);await(gapClosing);h.assertTrue(diagnosticClosing.isDone(),"Waiting for bounded asynchronous diagnostic deadline");
                    h.assertTrue(release.getCount()==1,"Shutdown joined the hung writer");
                    session=new KernelSession(h.getLevel().getServer(),root,null,model,PrimitiveDiagnostics.noop());
                    phase=7;
                }
                case 7 -> {
                    h.assertTrue(session.identityReady(),"Reloading known admission without diagnostics/model");
                    session.inference(false);startKnown();phase=8;
                }
                case 8 -> {
                    h.assertTrue(session.queuedJobIds(knownOwner,false).contains(knownJob),"Acknowledging reloaded job");
                    var job=session.queuedJob(knownOwner,knownJob);
                    h.assertTrue(job!=null&&job.state()==Jobs.State.SUCCEEDED,"Known saved method failed with inference and diagnostics off");
                    h.assertTrue(((Container)h.getLevel().getBlockEntity(origin.offset(4,1,4))).countItem(Items.WHEAT)==delivered+1,"Reloaded method duplicated or lost delivery");
                    h.assertTrue(model.calls==1&&release.getCount()==1,"Reload invoked model or waited for diagnostic write");
                    session.clearDispatchCache();session.close();closing=session.storeClosure();release.countDown();phase=9;
                }
                case 9 -> {
                    await(closing);
                    if(restartMarker==null)restartMarker=PrimitiveDiagnosticRestartGameTests.record(root,known,setup.actor,knownOwner,origin);
                    await(restartMarker);
                    System.out.println("IMP-014.1 blocked writer accepted unknownGeneration=1 repairs=0 knownJobs=2 knownInference=0 modelFreeReload=true shutdown=bounded");phase=10;
                }
                default -> throw new IllegalStateException("Blocked diagnostic fixture phase");
            }
        }
    }
}
