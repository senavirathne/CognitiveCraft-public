package dev.aivillages.fabric;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.Outcomes.ResearchStatus;
import dev.aivillages.providers.LocalGenerationAdapter;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** A fake candidate in normal CI; a genuine local generation path when the model tag is set. */
public final class BootstrapGameTests {
    private static final BlockPos CROP_A = new BlockPos(2, 1, 3);
    private static final BlockPos CROP_A_MAX = new BlockPos(3, 1, 4);
    private static final BlockPos CHEST_A = new BlockPos(4, 1, 4);
    // The acquired method uses harvest/pickup/transfer; each bound target must be in reach.
    private static final BlockPos CROP_B = new BlockPos(6, 1, 1);
    private static final BlockPos CROP_B_MAX = new BlockPos(7, 1, 3);
    private static final BlockPos CHEST_B = new BlockPos(7, 1, 0);
    private static final BlockPos REUSE_POSITION = new BlockPos(7, 1, 2);
    // The restart fixture lies outside the GameTest template cleared on the next boot.
    private static final BlockPos PERSISTENT_FIXTURE = new BlockPos(8, 0, 8);
    private static final String RESTART_PHASE = "COGNITIVECRAFT_BOOTSTRAP_RESTART";
    private record RestartRecord(UUID entity, UUID run, String artifact, BlockPos origin) { }

    private static Path restartWorld(GameTestHelper h) {
        return h.getLevel().getServer().getWorldPath(LevelResource.ROOT)
                .resolve("cognitivecraft-bootstrap-restart-fixture");
    }

    /** Test setup only: include each fixture chunk's neighbors before waiting for entity ticking. */
    static void forceFixtureChunks(GameTestHelper h, BlockPos origin) {
        // The 10 by 9 arena can straddle four chunks. A one-chunk halo bounds this
        // setup to at most sixteen chunks and keeps boundary actors/drops ticking.
        for (int x = (origin.getX() >> 4) - 1; x <= ((origin.getX() + 9) >> 4) + 1; x++)
            for (int z = (origin.getZ() >> 4) - 1; z <= ((origin.getZ() + 8) >> 4) + 1; z++) {
                h.getLevel().setChunkForced(x, z, true);
                // Keep simulation separate from the framework's temporary forced
                // tickets, which other completed tests may release at a shared
                // chunk boundary. This registered ticket does not persist and
                // cannot load chunks or bypass the gateway's availability guard.
                h.getLevel().getChunkSource().addTicketWithRadius(
                        net.minecraft.server.level.TicketType.PLAYER_SIMULATION,
                        new net.minecraft.world.level.ChunkPos(x, z), 2);
                h.getLevel().getChunk(x, z);
            }
    }
    private static RestartRecord readRestart(Path world) {
        try {
            String[] parts = Files.readString(world.resolve("restart.txt")).trim().split(" ");
            if (parts.length != 6) throw new IllegalStateException("Restart marker fields");
            return new RestartRecord(UUID.fromString(parts[0]), UUID.fromString(parts[1]),
                    parts[2], new BlockPos(Integer.parseInt(parts[3]),
                            Integer.parseInt(parts[4]), Integer.parseInt(parts[5])));
        } catch (Exception failure) { throw new IllegalStateException("Restart marker missing", failure); }
    }

    @GameTest(maxTicks = 3_000_000, padding = 16)
    public void bootstrapPhysicalAcquisitionAndOwnerReloadReuse(GameTestHelper h) {
        var kernel = h.getLevel().getServer().getCommands().getDispatcher()
                .getRoot().getChild("aivillage").getChild("kernel");
        for (String name : List.of("enroll", "harvest", "status", "cancel", "inference",
                "catalog"))
            h.assertTrue(kernel.getChild(name) != null, "Missing registered kernel command " + name);
        String restart = System.getenv(RESTART_PHASE);
        String modelTag = System.getenv("COGNITIVECRAFT_OLLAMA_MODEL");
        Path world = restart == null ? null : restartWorld(h);
        CompletableFuture<RestartRecord> metadata = "warm".equals(restart)
                ? CompletableFuture.supplyAsync(() -> readRestart(world)) : null;
        Run[] current = new Run[1];
        long warmStartedTick = h.getLevel().getGameTime();
        boolean[] oldChunkLoaded = {false};
        int[] warmAttempts = {0};
        int[] lastStage = {-1};
        long[] lastWarmReport = {-1};
        if (!"warm".equals(restart)) {
            BlockPos fixture = "cold".equals(restart) ? PERSISTENT_FIXTURE : BlockPos.ZERO;
            System.out.println("IMP-007 fixture start phase=" + restart + " origin="
                    + h.absolutePos(fixture));
            for (int x = 0; x < 10; x++) for (int z = 0; z < 9; z++)
                h.setBlock(fixture.offset(x, 0, z), Blocks.STONE);
            System.out.println("IMP-007 fixture floor ready");
            for (BlockPos[] patch : List.of(new BlockPos[] {CROP_A, CROP_A_MAX},
                    new BlockPos[] {CROP_B, CROP_B_MAX}))
                for (int x = patch[0].getX(); x <= patch[1].getX(); x++)
                    for (int z = patch[0].getZ(); z <= patch[1].getZ(); z++) {
                        var crop = new BlockPos(x, patch[0].getY(), z);
                        h.setBlock(fixture.offset(crop.below()), Blocks.FARMLAND.defaultBlockState()
                                .setValue(FarmlandBlock.MOISTURE, 7));
                        h.setBlock(fixture.offset(crop), Blocks.WHEAT.defaultBlockState()
                                .setValue(CropBlock.AGE, 7));
                    }
            System.out.println("IMP-007 fixture crops ready");
            h.setBlock(fixture.offset(CHEST_A), Blocks.CHEST);
            h.setBlock(fixture.offset(CHEST_B), Blocks.CHEST);
            // Real collision keeps randomly drifting item entities near the
            // worker. The chests occupy a perimeter cell and remain reachable;
            // no drops are moved or inserted into inventories by this fixture.
            for (int x=1; x<=4; x++) for (int z=2; z<=5; z++)
                if ((x==1 || x==4 || z==2 || z==5) && !(x==4 && z==4))
                    h.setBlock(fixture.offset(new BlockPos(x,1,z)),Blocks.STONE);
            for (int x=5; x<=8; x++) for (int z=0; z<=4; z++)
                if ((x==5 || x==8 || z==0 || z==4) && !(x==7 && z==0))
                    h.setBlock(fixture.offset(new BlockPos(x,1,z)),Blocks.STONE);
            System.out.println("IMP-007 fixture chests ready");
            Villager villager = h.spawnWithNoFreeWill(EntityTypes.VILLAGER,
                    fixture.getX() + 3, 1, fixture.getZ() + 3);
            villager.setPersistenceRequired();
            System.out.println("IMP-007 fixture actor ready=" + villager.getUUID());
            current[0] = new Run(h, villager,
                    modelTag == null || modelTag.isBlank() ? null : modelTag,
                    world, h.absolutePos(fixture), restart);
        }
        h.failIfEver(() -> {
            Run test = current[0];
            if (test == null) return;
            if (test.fatal != null) h.fail(test.fatal);
            if (test.runId == null || test.controller == null) return;
            var status = test.controller.status(test.runId, test.owner);
            if (status.phase() == BootstrapController.Phase.TERMINAL
                    && status.marker() != null && !List.of("ADMITTED", "SUCCEEDED")
                    .contains(status.marker().outcome()))
                h.fail("Bootstrap " + test.stage + " result=" + status.marker()
                        + " routing=" + status.routing() + " research=" + status.research()
                        + " execution=" + status.outcome());
        });
        h.succeedWhen(() -> {
            if (current[0] == null) {
                warmAttempts[0]++;
                if (h.getLevel().getGameTime() - warmStartedTick > 200)
                    h.fail("Saved bootstrap villager did not load within 200 ticks");
                h.assertTrue(metadata.isDone(), "Reading first process marker");
                RestartRecord record = metadata.join();
                BlockPos spawn = record.origin().offset(3, 1, 3);
                if (!oldChunkLoaded[0]) {
                    System.out.println("IMP-007 restart loading actor=" + record.entity()
                            + " at=" + spawn + " attempts=" + warmAttempts[0]);
                    forceFixtureChunks(h, record.origin());
                    oldChunkLoaded[0] = true;
                    System.out.println("IMP-007 restart loaded actor chunk");
                }
                var entity = h.getLevel().getEntity(record.entity());
                if (warmAttempts[0] == 100 || warmAttempts[0] == 200)
                    System.out.println("IMP-007 restart actor wait=" + warmAttempts[0]
                            + " gameTicks=" + (h.getLevel().getGameTime() - warmStartedTick)
                            + " entity=" + entity);
                h.assertTrue(entity instanceof Villager,
                        "Enrolled villager not loaded from saved Minecraft world");
                current[0] = new Run(h, (Villager)entity, null, world,
                        record.origin(), "warm");
                current[0].admitted = new ArtifactRef(CropDelivery.ID, record.artifact());
                current[0].runId = record.run();
                current[0].stage = 5;
                System.out.println("IMP-007 restart loaded enrolled actor=" + entity.getUUID()
                        + " position=" + entity.position());
            }
            Run test = current[0];
            long elapsed = h.getLevel().getGameTime() - warmStartedTick;
            if ("warm".equals(restart) && elapsed > 1_200)
                test.fail("Warm bootstrap exceeded 1200 ticks stage=" + test.stage
                        + " opening=" + (test.opening == null ? "none" : test.opening.isDone())
                        + " closing=" + (test.closing == null ? "none" : test.closing.isDone())
                        + " actor=" + test.villager.position());
            if (!"warm".equals(restart) && test.model == null && elapsed > 1_200)
                test.fail("Fake bootstrap exceeded 1200 ticks stage=" + test.stage);
            test.advance();
            if (lastStage[0] != test.stage || elapsed / 100 != lastWarmReport[0]) {
                lastStage[0] = test.stage;
                lastWarmReport[0] = elapsed / 100;
                String status = test.controller == null || test.owner == null
                        || test.runId == null ? "none"
                        : String.valueOf(test.controller.status(test.runId, test.owner).phase());
                System.out.println("IMP-007 bootstrap phase=" + restart + " stage="
                        + test.stage + " elapsedTicks="
                        + elapsed + " actor=" + test.villager.position() + " status=" + status);
            }
            h.assertTrue(test.stage == 8, "Waiting for bootstrap phase " + test.stage);
        });
    }

    static final class Run {
        final GameTestHelper h;
        final Villager villager;
        final String modelTag;
        final AdjustableClock clock = new AdjustableClock();
        final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "bootstrap-gametest-storage");
            t.setDaemon(true);
            return t;
        });
        final Path world;
        final BlockPos origin;
        final String restartPhase;
        final boolean retainAfterAdmission;
        final VersionedSkillRepository.Limits repositoryLimits;
        final VersionedSkillRepository.FaultInjector repositoryFaults;
        final CapabilityCatalog capabilities = id -> id.equals(CropDelivery.ID)
                ? Optional.of(CropDelivery.SPEC) : Optional.empty();
        final PrincipalRef principal = new PrincipalRef(UUID.randomUUID());
        final LocalGenerationAdapter model;
        CompletableFuture<Stores> opening;
        CompletableFuture<Void> closing;
        Stores stores;
        BootstrapController controller;
        SurvivalGateway gateway;
        ActorRef actor;
        TrustedContext owner;
        UUID runId;
        ArtifactRef admitted;
        String fatal;
        int stage, fakeCalls;
        boolean retainFirstTerminal, failTerminalWrite;
        final boolean movementCandidate;
        boolean physicalAuthorityGranted = true;
        int deniedAuthorityChecks;
        Effect lastDeniedEffect;
        Path storeWorld;
        int failedTerminalWrites;
        boolean holdInterruptionWrite;
        int heldInterruptionWrites;
        final CompletableFuture<BootstrapJournal.State> heldInterruptionWrite = new CompletableFuture<>();
        UUID delayedTerminalRun;
        int delayedTerminalWrites, storageWriteRequests;
        final CompletableFuture<Void> terminalWriteGate = new CompletableFuture<>();
        final AtomicInteger journalWritesStarted = new AtomicInteger();
        final AtomicInteger journalWritesFinished = new AtomicInteger();
        final AtomicInteger journalWriteFailures = new AtomicInteger();
        long coldStartedNanos, warmStartedNanos, maxTickNanos, peakHeapBytes;
        final String dimension;

        /** Test-only offset advances a storage deadline after physical work has stopped. */
        private static final class AdjustableClock extends Clock {
            private final java.util.concurrent.atomic.AtomicLong offset =
                    new java.util.concurrent.atomic.AtomicLong();
            @Override public java.time.ZoneId getZone() { return java.time.ZoneId.of("UTC"); }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public java.time.Instant instant() { return java.time.Instant.ofEpochMilli(millis()); }
            @Override public long millis() { return Math.addExact(System.currentTimeMillis(), offset.get()); }
        }

        void advanceStorageClock(long millis) {
            clock.offset.addAndGet(millis);
        }

        Run(GameTestHelper h, Villager villager, String modelTag, Path world,
            BlockPos origin, String restartPhase) {
            this(h, villager, modelTag, world, origin, restartPhase, false);
        }

        Run(GameTestHelper h, Villager villager, String modelTag, Path world,
            BlockPos origin, String restartPhase, boolean retainAfterAdmission) {
            this(h, villager, modelTag, world, origin, restartPhase, retainAfterAdmission, false);
        }

        Run(GameTestHelper h, Villager villager, String modelTag, Path world,
            BlockPos origin, String restartPhase, boolean retainAfterAdmission,
            boolean movementCandidate) {
            this(h, villager, modelTag, world, origin, restartPhase, retainAfterAdmission,
                    movementCandidate, VersionedSkillRepository.Limits.defaults(),
                    VersionedSkillRepository.FaultInjector.none());
        }

        Run(GameTestHelper h, Villager villager, String modelTag, Path world,
            BlockPos origin, String restartPhase, boolean retainAfterAdmission,
            VersionedSkillRepository.Limits repositoryLimits,
            VersionedSkillRepository.FaultInjector repositoryFaults) {
            this(h, villager, modelTag, world, origin, restartPhase, retainAfterAdmission, false,
                    repositoryLimits, repositoryFaults);
        }

        private Run(GameTestHelper h, Villager villager, String modelTag, Path world,
            BlockPos origin, String restartPhase, boolean retainAfterAdmission,
            boolean movementCandidate, VersionedSkillRepository.Limits repositoryLimits,
            VersionedSkillRepository.FaultInjector repositoryFaults) {
            this.h = h; this.villager = villager; this.modelTag = modelTag;
            this.world = world == null ? Path.of(System.getProperty("java.io.tmpdir"),
                    "cc-bootstrap-gametest-" + UUID.randomUUID()) : world;
            this.origin = origin; this.restartPhase = restartPhase;
            this.movementCandidate = movementCandidate;
            if (movementCandidate || modelTag != null) villager.setNoAi(false);
            forceFixtureChunks(h, origin);
            storeWorld = this.world;
            this.retainAfterAdmission = retainAfterAdmission;
            this.repositoryLimits = repositoryLimits;
            this.repositoryFaults = repositoryFaults;
            dimension = h.getLevel().dimension().identifier().toString();
            model = modelTag == null ? null : new LocalGenerationAdapter(
                    new LocalGenerationAdapter.Config(URI.create("http://127.0.0.1:11434/api/chat"),
                            modelTag, true, 3_072));
            opening = CompletableFuture.supplyAsync(this::open, worker);
        }

        private record Stores(BootstrapJournal journal, VersionedSkillRepository repository,
                              ResearchAdmissionController.DecisionGuard guard) { }
        private Stores open() {
            try {
                var journal = BootstrapJournal.open(storeWorld);
                try {
                    var guard = new ResearchAdmissionController.DecisionGuard();
                    var repository = VersionedSkillRepository.open(storeWorld,
                            new VersionedSkillRepository.RuntimeSnapshot("minecraft-26.3",
                                    capabilities, GatewayPrimitives.instance()),
                            repositoryLimits, guard, TrustedContext::equals, repositoryFaults);
                    return new Stores(journal, repository, guard);
                } catch (Exception failed) { journal.close(); throw failed; }
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        private void closeStores() {
            try { stores.repository().close(); stores.journal().close(); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        private BlockPos absolute(BlockPos relative) { return origin.offset(relative); }
        private void fail(String message) { fatal = message; h.fail(message); }
        boolean loadBindings(BlockPos first, BlockPos last, BlockPos destination) {
            var access = new FabricGatewayWorld(h.getLevel());
            boolean ready = true;
            for (int x = first.getX(); x <= last.getX(); x++)
                for (int z = first.getZ(); z <= last.getZ(); z++) {
                    var position = absolute(new BlockPos(x, first.getY(), z));
                    h.getLevel().setChunkForced(position.getX() >> 4,
                            position.getZ() >> 4, true);
                    h.getLevel().getChunk(position.getX() >> 4, position.getZ() >> 4);
                    if (access.crop(new SurvivalGateway.Cell(dimension, position.getX(),
                            position.getY(), position.getZ())).status()
                            == ObservationStatus.UNKNOWN) ready = false;
                }
            var target = absolute(destination);
            h.getLevel().setChunkForced(target.getX() >> 4, target.getZ() >> 4, true);
            h.getLevel().getChunk(target.getX() >> 4, target.getZ() >> 4);
            if (access.container(new ContainerRef(dimension, target.getX(), target.getY(),
                    target.getZ())).status() == ObservationStatus.UNKNOWN) ready = false;
            return ready && access.actor(actor).loaded();
        }

        void advance() {
            if (controller != null && stage != 4) {
                long start = System.nanoTime();
                gateway.tick(); controller.tick();
                maxTickNanos = Math.max(maxTickNanos, System.nanoTime() - start);
                Runtime runtime = Runtime.getRuntime();
                peakHeapBytes = Math.max(peakHeapBytes,
                        runtime.totalMemory() - runtime.freeMemory());
            }
            switch (stage) {
                case 0 -> {
                    h.assertTrue(opening.isDone(), "Opening bootstrap world data");
                    if (opening.isCompletedExceptionally()) fail("Bootstrap world open failed");
                    stores = opening.join();
                    compose(false);
                    actor = controller.enroll(villager.getUUID(), dimension, principal);
                    stage = 1;
                }
                case 1 -> {
                    h.assertTrue(controller.ready(), "Persisting bootstrap enrollment");
                    owner = controller.enrollment().owner();
                    controller.inference(true);
                    stage = 2;
                }
                case 2 -> {
                    h.assertTrue(loadBindings(CROP_A, CROP_A_MAX, CHEST_A),
                            "Waiting for cold fixture chunks to tick");
                    coldStartedNanos = System.nanoTime();
                    var submitted = controller.submit(request(4, CROP_A, CROP_A_MAX, CHEST_A), owner);
                    System.out.println("IMP-007 first submission accepted=" + submitted.accepted()
                            + " reason=" + submitted.reason());
                    if (!submitted.accepted()) fail("First request rejected: " + submitted.reason());
                    runId = submitted.id(); stage = 3;
                }
                case 3 -> {
                    var status = controller.status(runId, owner);
                    h.assertTrue(status.phase() == BootstrapController.Phase.TERMINAL
                            && status.marker() != null, "Generating and trialing");
                    if (retainFirstTerminal) {
                        stage = 9; // The storage fixture owns assertions for failed or uncertain publication.
                        return;
                    }
                    if (!"ADMITTED".equals(status.marker().outcome())
                            || status.research() == null
                            || status.research().status() != ResearchStatus.ADMITTED)
                        fail("Expected one trial fulfillment: " + status);
                    admitted = status.research().artifact();
                    if (status.marker().effects() < 12 || status.receipts().size() < 12)
                        fail("Missing attributable physical receipts: " + status);
                    if (!(((Container)h.getLevel().getBlockEntity(absolute(CHEST_A)))
                            .countItem(Items.WHEAT) >= 4
                            && h.getLevel().getBlockState(absolute(CROP_A)).isAir()
                            && h.getLevel().getBlockState(absolute(CROP_A_MAX)).isAir()))
                        fail("Acquisition did not move real wheat");
                    h.assertTrue(stores.repository().resolve(admitted).usable(),
                            "Admission missing from durable catalog");
                    if (model != null) h.assertTrue(status.marker().modelCalls() > 0,
                            "Configured local model was never invoked");
                    System.out.println("IMP-007 acquire model="
                            + (modelTag == null ? "FAKE" : modelTag) + " ref=" + admitted.sha256()
                            + " calls=" + status.marker().modelCalls() + " effects="
                            + status.marker().effects() + " receipts=" + status.receipts().size()
                            + " coldMs=" + (System.nanoTime() - coldStartedNanos) / 1_000_000
                            + " peakHeapBytes=" + peakHeapBytes
                            + " maxKernelTickNanos=" + maxTickNanos);
                    if (model != null) model.close();
                    if (retainAfterAdmission) {
                        compose(true); // All later requests use the admitted body and a throwing generator.
                        stage = 9;
                        return;
                    }
                    closing = CompletableFuture.runAsync(() -> {
                        closeStores();
                        if ("cold".equals(restartPhase)) try {
                            Files.writeString(world.resolve("restart.txt"), villager.getUUID()
                                    + " " + runId + " " + admitted.sha256() + " "
                                    + origin.getX() + " " + origin.getY() + " " + origin.getZ());
                        } catch (Exception failure) { throw new IllegalStateException(failure); }
                    }, worker);
                    stage = 4;
                }
                case 4 -> {
                    h.assertTrue(closing.isDone(), "Closing authoritative stores");
                    if (closing.isCompletedExceptionally()) fail("Bootstrap store close failed");
                    closing.join(); controller = null; gateway = null;
                    if ("cold".equals(restartPhase)) stage = 8;
                    else {
                        opening = CompletableFuture.supplyAsync(this::open, worker);
                        stage = 5;
                    }
                }
                case 5 -> {
                    h.assertTrue(opening.isDone(), "Reloading authoritative stores");
                    if (opening.isCompletedExceptionally()) fail("Bootstrap world reopen failed");
                    stores = opening.join();
                    if ("warm".equals(restartPhase)) {
                        var enrollment = stores.journal().state().enrollment();
                        h.assertTrue(enrollment != null
                                        && enrollment.actor().entityId().equals(villager.getUUID()),
                                "Enrolled actor did not survive process reload");
                        actor = enrollment.actor(); owner = enrollment.owner();
                        h.assertTrue(loadBindings(CROP_B, CROP_B_MAX, CHEST_B),
                                "Waiting for saved fixture chunks to tick");
                        h.assertTrue(h.getLevel().getBlockEntity(absolute(CHEST_A))
                                        instanceof Container saved && saved.countItem(Items.WHEAT) >= 4
                                        && h.getLevel().getBlockState(absolute(CROP_A)).isAir()
                                        && h.getLevel().getBlockState(absolute(CROP_A_MAX)).isAir(),
                                "Cold physical effects did not survive world reload");
                        h.assertTrue(h.getLevel().getBlockEntity(absolute(CHEST_B))
                                        instanceof Container
                                        && h.getLevel().getBlockState(absolute(CROP_B))
                                                .is(Blocks.WHEAT)
                                        && h.getLevel().getBlockState(absolute(CROP_B_MAX))
                                                .is(Blocks.WHEAT),
                                "Changed-binding fixture did not survive world reload");
                    }
                    h.assertTrue(stores.repository().resolve(admitted).usable(),
                            "Admitted ref lost on reload");
                    compose(true); // The generator throws if known reuse calls it.
                    h.assertTrue(!controller.inferenceEnabled(), "Reload must default inference off");
                    h.assertTrue("ADMITTED".equals(controller.status(runId, owner).marker().outcome()),
                            "First fulfillment did not survive reload");
                    // Fixture relocation places the unchanged actor beside patch B. The
                    // generated four-primitive method has no movement operation.
                    var next = absolute(REUSE_POSITION);
                    villager.setPos(next.getX() + 0.5, next.getY(), next.getZ() + 0.5);
                    villager.getNavigation().stop();
                    stage = 6;
                }
                case 6 -> {
                    h.assertTrue(loadBindings(CROP_B, CROP_B_MAX, CHEST_B),
                            "Waiting for changed-binding chunks to tick");
                    warmStartedNanos = System.nanoTime();
                    var submitted = controller.submit(request(6, CROP_B, CROP_B_MAX, CHEST_B), owner);
                    if (!submitted.accepted()) fail("Reuse rejected: " + submitted.reason());
                    runId = submitted.id(); stage = 7;
                }
                case 7 -> {
                    var status = controller.status(runId, owner);
                    h.assertTrue(status.phase() == BootstrapController.Phase.TERMINAL
                            && status.marker() != null, "Executing admitted method offline");
                    if (!"SUCCEEDED".equals(status.marker().outcome())
                            || !admitted.sha256().equals(status.marker().artifactSha256()))
                        fail("Changed bindings did not reuse admitted ref: " + status);
                    if (status.marker().modelCalls() != 0 || status.receipts().size() < 18)
                        fail("Reuse called model or lacks physical custody receipts: " + status);
                    if (!(((Container)h.getLevel().getBlockEntity(absolute(CHEST_B)))
                            .countItem(Items.WHEAT) >= 6
                            && h.getLevel().getBlockState(absolute(CROP_B)).isAir()
                            && h.getLevel().getBlockState(absolute(CROP_B_MAX)).isAir()))
                        fail("Offline reuse did not deliver six wheat");
                    System.out.println("IMP-007 reuse ref=" + admitted.sha256()
                            + " calls=" + status.marker().modelCalls() + " effects="
                            + status.marker().effects() + " receipts=" + status.receipts().size()
                            + " warmMs=" + (System.nanoTime() - warmStartedNanos) / 1_000_000
                            + " peakHeapBytes=" + peakHeapBytes
                            + " maxKernelTickNanos=" + maxTickNanos);
                    closing = CompletableFuture.runAsync(this::closeStores, worker);
                    stage = 8;
                }
            }
            if (stage == 8) {
                h.assertTrue(closing.isDone(), "Closing bootstrap fixture");
                if (closing.isCompletedExceptionally()) fail("Final bootstrap close failed");
                closing.join();
                if (model != null) model.close();
                worker.shutdown();
                h.assertTrue(fakeCalls <= 1, "Fake model invoked again on reuse");
            }
        }

        CapabilityRequest request(int amount, BlockPos first, BlockPos last,
                                          BlockPos destination) {
            BlockPos from = absolute(first), to = absolute(last);
            BlockPos box = absolute(destination);
            return new CapabilityRequest(CropDelivery.ID, Map.of(
                    "actor", new ActorValue(actor), "amount", new IntValue(amount),
                    "source", new AreaValue(new Cuboid(dimension, from.getX(), from.getY(),
                            from.getZ(), to.getX(), to.getY(), to.getZ())),
                    "destination", new ContainerValue(new ContainerRef(dimension, box.getX(),
                            box.getY(), box.getZ()))));
        }

        CompletableFuture<Void> closeAcceptanceFixture() {
            return closeAcceptanceFixture(() -> { });
        }

        CompletableFuture<Void> closeAcceptanceFixture(Runnable verification) {
            return CompletableFuture.runAsync(() -> {
                try { closeStores(); verification.run(); }
                finally { worker.shutdown(); }
            }, worker);
        }

        VersionedSkillRepository repository() { return stores.repository(); }
        BootstrapJournal.State durableState() { return stores.journal().state(); }
        boolean acceptanceJournalReadOnly() { return stores.journal().readOnly(); }

        CompletableFuture<Void> reopenAcceptanceStores() {
            return reopenAcceptanceStores(Function.identity());
        }

        CompletableFuture<Void> reopenAcceptanceStores(Function<Path, Path> prepare) {
            if (gateway.activeRuns() != 0) throw new IllegalStateException("Active physical custody");
            controller = null; gateway = null;
            return CompletableFuture.runAsync(() -> {
                closeStores();
                storeWorld = prepare.apply(storeWorld);
                stores = open();
            }, worker);
        }

        void resumeAcceptanceController() { compose(true); }

        private BootstrapJournal.State writeAcceptanceJournal(BootstrapJournal.State expected,
                BootstrapJournal.Enrollment enrollment, List<BootstrapJournal.RunMarker> runs) {
            journalWritesStarted.incrementAndGet();
            try { return stores.journal().replace(expected, enrollment, runs); }
            catch (Exception failure) {
                journalWriteFailures.incrementAndGet();
                throw new IllegalStateException(failure);
            } finally { journalWritesFinished.incrementAndGet(); }
        }

        BootstrapJournal.State openedAcceptanceState() {
            if (!opening.isDone()) throw new IllegalStateException("Stores have not opened");
            return opening.join().journal().state();
        }

        void initializeOfflineAcceptanceController() {
            if (!opening.isDone()) throw new IllegalStateException("Stores have not opened");
            stores = opening.join();
            var enrollment = stores.journal().state().enrollment();
            if (enrollment == null || !enrollment.actor().entityId().equals(villager.getUUID()))
                throw new IllegalStateException("Saved enrollment does not identify the loaded actor");
            actor = enrollment.actor(); owner = enrollment.owner();
            compose(true);
            stage = 9;
        }

        private void compose(boolean offline) {
            var primitives = GatewayPrimitives.instance();
            var repository = stores.repository();
            var staging = new ResearchAdmissionController.TrialArtifacts(repository);
            var grants = new ResearchAdmissionController.TrialGrants(clock);
            CapabilityResolver.ControlPolicy control = (candidate, context) ->
                    candidate.equals(actor) && context.equals(owner);
            AuthorityPolicy authority = (candidate, effect, context) -> {
                boolean enrolledOwner = candidate.equals(actor) && context.equals(owner);
                boolean allowed = enrolledOwner
                        && (effect == Effect.OBSERVE || physicalAuthorityGranted);
                if (enrolledOwner && !allowed) {
                    deniedAuthorityChecks++;
                    lastDeniedEffect = effect;
                }
                return allowed;
            };
            gateway = new SurvivalGateway(new FabricGatewayWorld(h.getLevel()), authority,
                    new SurvivalGateway.Limits(1, 512, 64, 16, 16, 1, 200, 32), clock);
            var executor = new BoundedSkillExecutor(staging, capabilities, primitives,
                    CapabilityResolver.enrolledWorldSkills(control), grants,
                    new BoundedSkillExecutor.ControlPolicy() {
                        @Override public boolean mayInspect(TrustedContext caller,
                                TrustedContext target, UUID run) { return caller.equals(target); }
                        @Override public boolean mayCancel(TrustedContext caller,
                                TrustedContext target, UUID run) { return caller.equals(target); }
                    }, gateway, gateway::releaseRun, clock, () -> h.getLevel().getGameTime(),
                    new BoundedSkillExecutor.Settings(8, 16, 32, 64));
            var resolver = new CapabilityResolver.Engine(capabilities, primitives,
                    CapabilityResolver.exactCatalog(repository), null,
                    CapabilityResolver.cropDeliverySupport(), control,
                    CapabilityResolver.enrolledWorldSkills(control), authority,
                    CapabilityResolver.cropPrerequisites(), () -> h.getLevel().getGameTime(),
                    CapabilityResolver.Limits.defaults());
            GenerationPort generator = offline ? (request, limits, usage) -> {
                throw new AssertionError("Known reuse cannot invoke inference");
            } : model != null ? model : (request, limits, usage) -> {
                fakeCalls++;
                String ir = movementCandidate ? ResearchGameTests.movementFixtureIr()
                        : ResearchGameTests.fixtureIr();
                byte[] body = ir.getBytes(StandardCharsets.UTF_8);
                Budgets.ResponseAllowance allowance = limits.chargeCall(usage, 128,
                        request.role() == Generation.Role.REPAIR);
                allowance.accept(body.length); allowance.close();
                var answer = CompletableFuture.completedFuture(new Generation.Result(request.id(),
                        request.bound().context(), request.role(), Generation.Outcome.CANDIDATE,
                        null, ir, new Generation.Descriptor("ollama-chat-v1", "fixture-model", null),
                        new Generation.Usage(128, body.length, -1, -1, Generation.Precision.UNKNOWN),
                        Generation.Compute.COMPLETED));
                return new Generation.Handle() {
                    @Override public UUID id() { return request.id(); }
                    @Override public java.util.concurrent.CompletionStage<Generation.Result> result() {
                        return answer;
                    }
                    @Override public boolean cancel() { return false; }
                    @Override public Generation.Compute compute() {
                        return Generation.Compute.COMPLETED;
                    }
                };
            };
            var research = new ResearchAdmissionController(capabilities, primitives, repository,
                    generator, new CropFixtureRunner(staging, capabilities, primitives,
                    CapabilityResolver.enrolledWorldSkills(control), worker, clock),
                    new ResearchAdmissionController.ExecutorTrialPort(executor), staging, grants,
                    ResearchAdmissionController.repositoryPublication(repository, worker),
                    stores.guard(), resolver, control, authority, clock, worker,
                    new ResearchAdmissionController.Settings(2, 8, 2_048));
            RequestEnvironment environment = new RequestEnvironment() {
                @Override public boolean enrolled(ActorRef selected, TrustedContext context) {
                    return control.controls(selected, context);
                }
                @Override public boolean loaded(Cuboid area, ObservationRef observation) {
                    for (int x = area.minX(); x <= area.maxX(); x++)
                        for (int z = area.minZ(); z <= area.maxZ(); z++)
                            if (new FabricGatewayWorld(h.getLevel()).crop(new SurvivalGateway.Cell(
                                    area.dimension(), x, area.minY(), z)).status()
                                    == ObservationStatus.UNKNOWN) return false;
                    return true;
                }
                @Override public boolean available(ContainerRef destination, ObservationRef observation) {
                    return new FabricGatewayWorld(h.getLevel()).container(destination).status()
                            == ObservationStatus.PRESENT;
                }
            };
            controller = new BootstrapController(stores.journal().state(),
                    (expected, enrollment, runs) -> {
                        storageWriteRequests++;
                        if (holdInterruptionWrite && runs.stream().anyMatch(marker ->
                                marker.phase() == BootstrapJournal.Phase.INTERRUPTED
                                        && expected.runs().stream().anyMatch(prior ->
                                                prior.id().equals(marker.id())
                                                        && prior.phase() == BootstrapJournal.Phase.ACTIVE))) {
                            heldInterruptionWrites++;
                            return heldInterruptionWrite; // CI kills this process before any journal replacement.
                        }
                        if (failTerminalWrite && runs.stream().anyMatch(marker ->
                                marker.phase() == BootstrapJournal.Phase.TERMINAL
                                        && expected.runs().stream().anyMatch(prior ->
                                                prior.id().equals(marker.id())
                                                        && prior.phase() == BootstrapJournal.Phase.ACTIVE))) {
                            failedTerminalWrites++;
                            return CompletableFuture.failedFuture(
                                    new java.io.IOException("Injected terminal-marker write failure"));
                        }
                        if (delayedTerminalRun != null && runs.stream().anyMatch(marker ->
                                marker.id().equals(delayedTerminalRun)
                                        && marker.phase() == BootstrapJournal.Phase.TERMINAL)) {
                            delayedTerminalWrites++;
                            return terminalWriteGate.thenApplyAsync(ignored ->
                                    writeAcceptanceJournal(expected, enrollment, runs), worker);
                        }
                        return CompletableFuture.supplyAsync(() ->
                                writeAcceptanceJournal(expected, enrollment, runs), worker);
                    }, this::observe, resolver::resolve,
                    (decision, bound, limits) -> {
                        var attempt = research.startMissing(decision, bound, limits);
                        return new BootstrapController.Research.Handle() {
                            @Override public ResearchAdmissionController.Status tick(TrustedContext caller) {
                                var status = attempt.tick(caller);
                                if (status.phase() == ResearchAdmissionController.Phase.TERMINAL)
                                    System.out.println("IMP-007 research usage=" + status.usage());
                                return status;
                            }
                            @Override public ResearchAdmissionController.Status status(TrustedContext caller) {
                                return attempt.status(caller);
                            }
                            @Override public ResearchAdmissionController.Status cancel(TrustedContext caller) {
                                return attempt.cancel(caller);
                            }
                        };
                    }, (bound, ref, id, limits, usage) -> {
                        var started = executor.startAdmitted(bound, ref,
                                new RunCorrelation(id, ref, null), limits, usage);
                        if (started instanceof BoundedSkillExecutor.Rejected rejected)
                            return new BootstrapController.Execution.Start(null, rejected.reason());
                        var run = ((BoundedSkillExecutor.Started)started).run();
                        return new BootstrapController.Execution.Start(
                                new BootstrapController.Execution.Handle() {
                                @Override public BoundedSkillExecutor.Progress tick(TrustedContext caller) {
                                    var progress = run.tick(caller);
                                    if (progress.phase() == BoundedSkillExecutor.Phase.TERMINAL)
                                        System.out.println("IMP-007 execution usage="
                                                + progress.summary().usage());
                                    return progress;
                                    }
                                    @Override public BoundedSkillExecutor.Progress progress(TrustedContext caller) {
                                        return run.progress(caller);
                                    }
                                    @Override public BoundedSkillExecutor.Progress cancel(TrustedContext caller) {
                                        return run.cancel(caller);
                                    }
                                    @Override public BoundedSkillExecutor.Progress interrupt(TrustedContext caller) {
                                        return run.interrupt(caller);
                                    }
                                }, null);
                    }, environment, capabilities, now -> ResearchGameTests.limits(clock),
                    now -> ResearchGameTests.limits(clock).trial(), clock);
        }
        private ObservationSnapshot observe(CapabilityRequest request, ActorRef selected) {
            Cuboid area = ((AreaValue)request.arguments().get("source")).value();
            var world = new FabricGatewayWorld(h.getLevel());
            int scanned = 0; long mature = 0, unknown = 0;
            for (int x = area.minX(); x <= area.maxX(); x++)
                for (int z = area.minZ(); z <= area.maxZ(); z++) {
                    var crop = world.crop(new SurvivalGateway.Cell(dimension, x, area.minY(), z));
                    scanned++;
                    if (crop.status() == ObservationStatus.UNKNOWN) unknown++;
                    if (crop.matureWheat()) mature++;
                }
            long tick = h.getLevel().getGameTime();
            return new ObservationSnapshot(new ObservationRef(UUID.randomUUID(), tick, dimension),
                    unknown > 0 ? ObservationStatus.UNKNOWN
                            : mature > 0 ? ObservationStatus.PRESENT : ObservationStatus.ABSENT,
                    "source:" + UUID.nameUUIDFromBytes(
                            area.toString().getBytes(StandardCharsets.UTF_8)), tick, scanned,
                    Map.of("mature_wheat", mature, "unknown_cells", unknown));
        }
    }
}
