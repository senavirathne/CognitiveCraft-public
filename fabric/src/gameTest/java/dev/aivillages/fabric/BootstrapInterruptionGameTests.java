package dev.aivillages.fabric;

import dev.aivillages.core.kernel.BootstrapController;
import dev.aivillages.core.kernel.BootstrapJournal;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.CropDelivery;
import dev.aivillages.core.kernel.Outcomes.ExecutionStatus;
import dev.aivillages.core.kernel.Outcomes.Reason;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Two server processes preserve uncertain physical work and require an explicit offline request. */
public final class BootstrapInterruptionGameTests {
    private static final BlockPos FIRST = new BlockPos(2, 1, 3);
    private static final BlockPos LAST = new BlockPos(3, 1, 4);
    private static final BlockPos CHEST = new BlockPos(4, 1, 4);
    private static final BlockPos OFFSET = new BlockPos(8, 0, 8);
    private record Proof(UUID citizen, UUID entity, UUID run, String artifact,
                         TrustedContext owner, BlockPos origin, long seeds, long process) { }

    @GameTest(maxTicks = 1800, padding = 32)
    public void interruptedPartialDeliveryRequiresExplicitRequestAfterServerRestart(GameTestHelper h) {
        String phase = System.getenv("COGNITIVECRAFT_BOOTSTRAP_RESTART");
        run(h, phase, false);
    }

    static void run(GameTestHelper h, String phase, boolean hardKill) {
        h.assertTrue("cold".equals(phase) || "warm".equals(phase),
                "This saved-world fixture requires an explicit cold or warm server process");
        boolean kill = hardKill && "kill".equals(System.getenv("COGNITIVECRAFT_BOOTSTRAP_CRASH"));
        Driver driver = new Driver(h, "warm".equals(phase), hardKill, kill);
        h.failIfEver(() -> {
            if (driver.fatal != null) h.fail(driver.fatal);
        });
        h.succeedWhen(() -> {
            driver.step();
            h.assertTrue(driver.phase == 10, "Waiting for interrupted fixture phase=" + driver.phase);
        });
    }

    private static final class Driver {
        final GameTestHelper h;
        final boolean warm;
        final boolean hardKill, kill;
        final Path readyPath;
        final CompletableFuture<Map<String, Object>> crashEvidence;
        final Path world;
        final long startedTick, startedNanos = System.nanoTime();
        final CompletableFuture<Proof> metadata;
        BootstrapGameTests.Run fixture;
        Proof proof;
        AABB arena;
        BootstrapController.View partial, interrupted, completed;
        UUID runId, continuationId;
        CapabilityRequest request;
        CompletableFuture<Void> closing;
        long quietUntil, frozenSeeds;
        long lastPreparationReport = -1;
        int phase;
        String fatal;

        Driver(GameTestHelper h, boolean warm, boolean hardKill, boolean kill) {
            this.h = h; this.warm = warm; this.hardKill = hardKill; this.kill = kill;
            startedTick = h.getLevel().getGameTime();
            world = h.getLevel().getServer().getWorldPath(LevelResource.ROOT)
                    .resolve("cognitivecraft-bootstrap-interruption-fixture");
            readyPath = hardKill ? HardKillEvidence.configuredPath() : null;
            crashEvidence = hardKill && !kill
                    ? CompletableFuture.supplyAsync(() -> HardKillEvidence.verify(world, readyPath)) : null;
            metadata = warm ? CompletableFuture.supplyAsync(() -> readProof(world)) : null;
            if (warm) { phase = 4; return; }
            for (int x = 0; x < 10; x++) for (int z = 0; z < 9; z++)
                h.setBlock(OFFSET.offset(x, 0, z), Blocks.STONE);
            h.setBlock(OFFSET.offset(CHEST), Blocks.CHEST);
            for (int x = 1; x <= 4; x++) for (int z = 2; z <= 5; z++)
                if ((x == 1 || x == 4 || z == 2 || z == 5) && !(x == 4 && z == 4))
                    h.setBlock(OFFSET.offset(x, 1, z), Blocks.STONE);
            BootstrapGameTests.forceFixtureChunks(h, h.absolutePos(OFFSET));
            Villager villager = h.spawnWithNoFreeWill(EntityTypes.VILLAGER,
                    OFFSET.getX() + 3, 1, OFFSET.getZ() + 3);
            // Persist the fixed-position fixture setup. Goal suppression from the
            // helper alone does not keep the villager's brain stationary after load.
            villager.setNoAi(true);
            villager.setPersistenceRequired();
            fixture = new BootstrapGameTests.Run(h, villager, null, world,
                    h.absolutePos(OFFSET), null, true);
            arena = arena(fixture.origin);
            maturePatch(); stock();
        }

        void step() {
            if (phase == 10) return;
            // Match normal 20 TPS while preparing chunks/enrollment before the
            // first request or loading the saved actor. Accelerated GameTest
            // polls otherwise spend 1,800 game ticks in a few wall-clock seconds
            // while asynchronous chunk work is still pending. Work itself keeps
            // the 1 ms scheduling opportunity; all existing game-tick deadlines,
            // controller/action budgets and assertions remain unchanged. This
            // test-only preparation delay is outside measured kernel slices.
            boolean preparing = phase == 0 && fixture != null && fixture.stage <= 2
                    || phase == 4;
            java.util.concurrent.locks.LockSupport.parkNanos(
                    preparing ? 50_000_000 : 1_000_000);
            if (fixture != null && (phase <= 2 || phase >= 6 && phase <= 8)) {
                try { fixture.advance(); }
                finally {
                    long elapsed = h.getLevel().getGameTime() - startedTick;
                    if (phase == 0 && fixture.actor != null && elapsed / 100 != lastPreparationReport) {
                        lastPreparationReport = elapsed / 100;
                        var access = new FabricGatewayWorld(h.getLevel());
                        var position = fixture.villager.blockPosition();
                        System.out.println("IMP-007 interruption preparation stage=" + fixture.stage
                                + " elapsedTicks=" + elapsed + " origin=" + fixture.origin
                                + " position=" + fixture.villager.position() + " actor=" + access.actor(fixture.actor)
                                + " hasChunk=" + h.getLevel().hasChunkAt(position)
                                + " entityTicking=" + h.getLevel().isPositionEntityTicking(position)
                                + " insideBorder=" + h.getLevel().getWorldBorder().isWithinBounds(position)
                                + " bindings=" + bindingReadiness());
                    }
                }
            }
            switch (phase) {
                case 0 -> {
                    if (fixture.stage != 9) return;
                    require(fixture.fakeCalls == 1 && fixture.admitted != null
                                    && fixture.repository().resolve(fixture.admitted).usable(),
                            "Initial fake acquisition did not durably admit its physical method");
                    // This reset is test setup before the interrupted request, with no retained receipts.
                    h.getLevel().getEntitiesOfClass(ItemEntity.class, arena).forEach(ItemEntity::discard);
                    maturePatch(); stock();
                    phase = 1;
                }
                case 1 -> {
                    if (!fixture.loadBindings(FIRST, LAST, CHEST)) return;
                    verifyStock(0);
                    require(seeds() == 9, "Interrupted request did not start with its declared seed stock");
                    request = fixture.request(4, FIRST, LAST, CHEST);
                    var submitted = fixture.controller.submit(request, fixture.owner);
                    require(submitted.accepted(), "Offline request rejected before the interruption");
                    runId = submitted.id(); phase = 2;
                }
                case 2 -> {
                    var status = fixture.controller.status(runId, fixture.owner);
                    if (wheat(status, CropDelivery.Stage.DEPOSIT) != 1) {
                        require(status.phase() != BootstrapController.Phase.TERMINAL,
                                "Interrupted request terminated before the first-delivery barrier: " + status);
                        return;
                    }
                    require(status.phase() != BootstrapController.Phase.TERMINAL
                                    && status.marker().phase() == BootstrapJournal.Phase.ACTIVE
                                    && status.marker().effects() == 0,
                            "Interrupted physical progress was already terminal or checkpointed");
                    verifyReceipts(status, runId, 1);
                    partial = status;
                    // Retire this test driver without cancel/interrupt/terminal persistence. The next
                    // Minecraft process must reconcile the authoritative ACTIVE marker itself.
                    fixture.gateway.releaseRun(runId);
                    verifyStock(1); frozenSeeds = seeds();
                    require(frozenSeeds >= 9 && frozenSeeds <= 9 + 64,
                            "First physical harvest exceeded bounded legitimate seed yield");
                    quietUntil = h.getLevel().getGameTime() + 40;
                    phase = 3;
                }
                case 3 -> {
                    verifyStock(1);
                    require(seeds() == frozenSeeds && fixture.fakeCalls == 1
                                    && fixture.gateway.activeRuns() == 0 && !AiVillages.controls(fixture.villager)
                                    && fixture.controller.status(runId, fixture.owner).equals(partial),
                            "Retired driver resumed work before server exit");
                    var marker = fixture.durableState().runs().stream()
                            .filter(m -> m.id().equals(runId)).findFirst().orElseThrow();
                    require(marker.equals(partial.marker()) && marker.phase() == BootstrapJournal.Phase.ACTIVE,
                            "Retired driver wrote a terminal marker");
                    if (h.getLevel().getGameTime() < quietUntil) return;
                    proof = new Proof(fixture.actor.citizenId(), fixture.villager.getUUID(), runId,
                            fixture.admitted.sha256(), fixture.owner, fixture.origin,
                            frozenSeeds, ProcessHandle.current().pid());
                    log("cold-active", partial, 3);
                    closing = CompletableFuture.runAsync(() -> writeProof(world, proof), fixture.worker)
                            .thenCompose(ignored -> fixture.closeAcceptanceFixture());
                    phase = 9;
                }
                case 4 -> {
                    require(h.getLevel().getGameTime() - startedTick <= 200,
                            "Saved interrupted actor failed to load within 200 ticks");
                    if (!metadata.isDone()) return;
                    proof = metadata.join();
                    require(proof.process() != ProcessHandle.current().pid(),
                            "Interrupted fixture did not start a different Minecraft process");
                    BootstrapGameTests.forceFixtureChunks(h, proof.origin());
                    var entity = h.getLevel().getEntity(proof.entity());
                    if (!(entity instanceof Villager villager)) return;
                    require(villager.isNoAi(), "Reload lost the saved fixed-position fixture setup");
                    fixture = new BootstrapGameTests.Run(h, villager, null, world, proof.origin(), null);
                    fixture.stage = 9;
                    fixture.admitted = new ArtifactRef(CropDelivery.ID, proof.artifact());
                    arena = arena(proof.origin()); runId = proof.run();
                    phase = 5;
                }
                case 5 -> {
                    if (!fixture.opening.isDone()) return;
                    if (crashEvidence != null) {
                        if (!crashEvidence.isDone()) return;
                        var evidence = crashEvidence.join();
                        long killed = ((Number)evidence.get("process")).longValue();
                        require(killed != proof.process() && killed != ProcessHandle.current().pid()
                                        && proof.run().toString().equals(evidence.get("run"))
                                        && proof.artifact().equals(evidence.get("artifact")),
                                "Hard-kill evidence did not identify the saved run and different process");
                        System.out.println("IMP-007 hard-kill phase=warm-before-recovery process="
                                + ProcessHandle.current().pid() + " killedProcess=" + killed
                                + " sourceFilesUnchanged=true run=" + runId);
                    }
                    var active = fixture.openedAcceptanceState().runs().stream()
                            .filter(m -> m.id().equals(runId)).findFirst().orElseThrow();
                    require(active.phase() == BootstrapJournal.Phase.ACTIVE && active.effects() == 0,
                            "Previous process failed to preserve its uncertain ACTIVE marker: " + active);
                    fixture.holdInterruptionWrite = kill;
                    fixture.initializeOfflineAcceptanceController();
                    require(fixture.actor.citizenId().equals(proof.citizen())
                                    && fixture.actor.entityId().equals(proof.entity())
                                    && fixture.owner.equals(proof.owner())
                                    && fixture.repository().resolve(fixture.admitted).usable()
                                    && fixture.fakeCalls == 0 && !fixture.controller.inferenceEnabled(),
                            "Process reload changed identity, authority, admission or inference");
                    require(!fixture.controller.ready()
                                    && !fixture.controller.submit(fixture.request(3, FIRST, LAST, CHEST),
                                            fixture.owner).accepted(),
                            "New work ran before durable interruption reconciliation");
                    verifyStock(1);
                    require(seeds() == proof.seeds(), "Process restart lost physical seed stock");
                    if (hardKill) {
                        require(!fixture.repository().privateOrigins(fixture.admitted, fixture.owner).isEmpty()
                                        && fixture.repository().privateOrigins(fixture.admitted,
                                                new TrustedContext(new PrincipalRef(UUID.randomUUID()),
                                                        fixture.owner.scope())).isEmpty(),
                                "Hard-kill recovery lost private provenance or exposed it to another principal");
                    }
                    phase = 6;
                }
                case 6 -> {
                    verifyStock(1);
                    require(seeds() == proof.seeds() && fixture.fakeCalls == 0
                                    && fixture.gateway.activeRuns() == 0 && !AiVillages.controls(fixture.villager),
                            "Reload replayed uncertain physical work");
                    if (kill) {
                        require(!fixture.controller.ready() && fixture.heldInterruptionWrites == 1
                                        && fixture.durableState().runs().stream().anyMatch(marker ->
                                                marker.id().equals(runId)
                                                        && marker.phase() == BootstrapJournal.Phase.ACTIVE
                                                        && marker.effects() == 0),
                                "Held reconciliation mutated its journal or admitted work");
                        if (quietUntil == 0) quietUntil = h.getLevel().getGameTime() + 40;
                        if (h.getLevel().getGameTime() < quietUntil) return;
                        require(!fixture.controller.submit(fixture.request(3, FIRST, LAST, CHEST),
                                        fixture.owner).accepted(),
                                "New work bypassed the held interruption write");
                        log("kill-ready", null, 0);
                        Map<String, Object> evidence = Map.ofEntries(
                                Map.entry("schema", 1L), Map.entry("point", "before-interrupted-journal-replace"),
                                Map.entry("process", ProcessHandle.current().pid()),
                                Map.entry("coldProcess", proof.process()), Map.entry("run", runId.toString()),
                                Map.entry("artifact", proof.artifact()),
                                Map.entry("actor", fixture.actor.entityId().toString()),
                                Map.entry("principal", fixture.owner.principal().id().toString()),
                                Map.entry("worldId", fixture.owner.scope().worldId().toString()),
                                Map.entry("domainId", fixture.owner.scope().domainId().toString()),
                                Map.entry("chestWheat", chest().countItem(Items.WHEAT)),
                                Map.entry("actorWheat", fixture.villager.getInventory().countItem(Items.WHEAT)),
                                Map.entry("matureCrops", 3L), Map.entry("seeds", seeds()),
                                Map.entry("checkpointEffects", 0L), Map.entry("reconstructedReceipts", 0L),
                                Map.entry("modelCalls", 0L), Map.entry("quietTicks", 40L));
                        closing = CompletableFuture.runAsync(() ->
                                HardKillEvidence.publish(world, readyPath, evidence), fixture.worker);
                        phase = 11;
                        return;
                    }
                    if (!fixture.controller.ready()) return;
                    if (interrupted == null) {
                        interrupted = fixture.controller.status(runId, fixture.owner);
                        require(interrupted.marker().phase() == BootstrapJournal.Phase.INTERRUPTED
                                        && "INTERRUPTED".equals(interrupted.marker().outcome())
                                        && interrupted.marker().reason() == Reason.INTERRUPTED
                                        && interrupted.marker().effects() == 0 && interrupted.receipts().isEmpty()
                                        && fixture.durableState().runs().contains(interrupted.marker()),
                                "Reload invented receipts or failed to persist interruption");
                        require(KernelRunStatus.describe(interrupted).contains("effectsAtCheckpoint=0"),
                                "Reload status treats a checkpoint as exact physical progress");
                        quietUntil = h.getLevel().getGameTime() + 40;
                        log("warm-interrupted", interrupted, 0);
                    }
                    require(fixture.controller.status(runId, fixture.owner).equals(interrupted)
                                    && fixture.controller.cancel(runId, fixture.owner).equals(interrupted),
                            "Interrupted status/cancel restarted the old request");
                    if (h.getLevel().getGameTime() < quietUntil) return;
                    if (!fixture.loadBindings(FIRST, LAST, CHEST)) return;
                    request = fixture.request(3, FIRST, LAST, CHEST);
                    var submitted = fixture.controller.submit(request, fixture.owner);
                    require(submitted.accepted() && !submitted.id().equals(runId),
                            "Explicit remaining-crop request failed revalidation");
                    continuationId = submitted.id(); phase = 7;
                }
                case 7 -> {
                    var status = fixture.controller.status(continuationId, fixture.owner);
                    if (status.phase() != BootstrapController.Phase.TERMINAL) return;
                    completed = status;
                    require(status.outcome().status() == ExecutionStatus.SUCCEEDED
                                    && status.outcome().committedEffects() == 9
                                    && status.marker().effects() == 9 && status.marker().modelCalls() == 0
                                    && proof.artifact().equals(status.marker().artifactSha256())
                                    && fixture.fakeCalls == 0,
                            "Explicit request failed offline artifact reuse: " + status);
                    verifyReceipts(status, continuationId, 3); verifyStock(4);
                    require(seeds() >= proof.seeds() && seeds() <= proof.seeds() + 3 * 64,
                            "Explicit new harvest changed seed accounting without bounded yield");
                    frozenSeeds = seeds(); quietUntil = h.getLevel().getGameTime() + 40;
                    phase = 8;
                }
                case 8 -> {
                    verifyStock(4);
                    require(seeds() == frozenSeeds && fixture.fakeCalls == 0
                                    && fixture.gateway.activeRuns() == 0 && !AiVillages.controls(fixture.villager)
                                    && fixture.controller.status(continuationId, fixture.owner).equals(completed)
                                    && fixture.controller.cancel(continuationId, fixture.owner).equals(completed)
                                    && fixture.controller.status(runId, fixture.owner).equals(interrupted),
                            "Late ticks changed either terminal result or replayed work");
                    if (h.getLevel().getGameTime() < quietUntil) return;
                    log("warm-explicit", completed, 9);
                    closing = fixture.closeAcceptanceFixture(); phase = 9;
                }
                case 9 -> {
                    if (!closing.isDone()) return;
                    closing.join(); phase = 10;
                }
                case 11 -> {
                    if (!closing.isDone()) return;
                    closing.join();
                    verifyStock(1);
                    require(seeds() == proof.seeds() && fixture.heldInterruptionWrites == 1
                                    && !fixture.controller.ready() && fixture.fakeCalls == 0
                                    && fixture.gateway.activeRuns() == 0 && !AiVillages.controls(fixture.villager),
                            "Work changed while waiting for the external SIGKILL");
                    // Deliberately never succeed/close stores: the bounded CI harness kills this JVM.
                }
                default -> throw new IllegalStateException("Unknown interruption phase " + phase);
            }
        }

        private void require(boolean condition, String message) {
            if (!condition) { fatal = message; h.fail(message); }
        }
        private BlockPos absolute(BlockPos relative) { return fixture.origin.offset(relative); }
        private String bindingReadiness() {
            StringBuilder result = new StringBuilder();
            for (BlockPos relative : java.util.List.of(FIRST, LAST, CHEST)) {
                BlockPos target = absolute(relative);
                result.append(target).append("[hasChunk=").append(h.getLevel().hasChunkAt(target))
                        .append(",entityTicking=").append(h.getLevel().isPositionEntityTicking(target))
                        .append(",insideBorder=").append(h.getLevel().getWorldBorder().isWithinBounds(target))
                        .append("]");
            }
            return result.toString();
        }
        private Container chest() { return (Container)h.getLevel().getBlockEntity(absolute(CHEST)); }
        private long wheat(BootstrapController.View view, CropDelivery.Stage stage) {
            return view.receipts().stream().filter(r -> r.stage() == stage)
                    .mapToLong(CropDelivery.CropReceipt::wheat).sum();
        }
        private void verifyReceipts(BootstrapController.View view, UUID id, int amount) {
            require(view.receipts().size() == amount * 3
                            && wheat(view, CropDelivery.Stage.HARVEST) == amount
                            && wheat(view, CropDelivery.Stage.PICKUP) == amount
                            && wheat(view, CropDelivery.Stage.DEPOSIT) == amount,
                    "Attributable physical progress is inaccurate");
            for (var receipt : view.receipts())
                require(receipt.runId().equals(id) && receipt.actor().equals(fixture.actor)
                                && receipt.source().equals(((AreaValue)request.arguments().get("source")).value())
                                && receipt.destination().equals(((ContainerValue)request.arguments()
                                        .get("destination")).value()),
                        "Receipt escaped actor/run/source/destination bindings");
            var bound = new ValidatedRequest(request, fixture.owner,
                    new ObservationRef(UUID.randomUUID(), h.getLevel().getGameTime(), fixture.dimension));
            require(CropDelivery.completed(bound, id, view.receipts())
                            == (amount == ((IntValue)request.arguments().get("amount")).value()),
                    "Unrelated stock or partial work falsely satisfied completion");
        }
        private void verifyStock(int delivered) {
            long drops = h.getLevel().getEntitiesOfClass(ItemEntity.class, arena,
                            i -> i.getItem().is(Items.WHEAT)).stream()
                    .mapToLong(i -> i.getItem().getCount()).sum();
            long mature = 0;
            for (int x = FIRST.getX(); x <= LAST.getX(); x++) for (int z = FIRST.getZ(); z <= LAST.getZ(); z++) {
                var state = h.getLevel().getBlockState(absolute(new BlockPos(x, FIRST.getY(), z)));
                if (state.is(Blocks.WHEAT) && state.getValue(CropBlock.AGE) == 7) mature++;
            }
            require(fixture.villager.getInventory().countItem(Items.WHEAT) == 7
                            && chest().countItem(Items.WHEAT) == 20 + delivered
                            && drops == 0 && mature == 4 - delivered,
                    "Saved physical wheat was lost, duplicated or silently resumed");
        }
        private long seeds() {
            return fixture.villager.getInventory().countItem(Items.WHEAT_SEEDS)
                    + chest().countItem(Items.WHEAT_SEEDS)
                    + h.getLevel().getEntitiesOfClass(ItemEntity.class, arena,
                                    i -> i.getItem().is(Items.WHEAT_SEEDS)).stream()
                            .mapToLong(i -> i.getItem().getCount()).sum();
        }
        private void maturePatch() {
            for (int x = FIRST.getX(); x <= LAST.getX(); x++) for (int z = FIRST.getZ(); z <= LAST.getZ(); z++) {
                BlockPos crop = new BlockPos(x, FIRST.getY(), z);
                h.getLevel().setBlock(absolute(crop.below()), Blocks.FARMLAND.defaultBlockState()
                        .setValue(FarmlandBlock.MOISTURE, 7), 3);
                h.getLevel().setBlock(absolute(crop), Blocks.WHEAT.defaultBlockState()
                        .setValue(CropBlock.AGE, 7), 3);
            }
        }
        private void stock() {
            fixture.villager.getInventory().clearContent();
            fixture.villager.getInventory().addItem(new ItemStack(Items.WHEAT, 7));
            fixture.villager.getInventory().addItem(new ItemStack(Items.WHEAT_SEEDS, 9));
            chest().clearContent(); chest().setItem(0, new ItemStack(Items.WHEAT, 20));
        }
        private void log(String phase, BootstrapController.View view, int knownEffects) {
            System.out.println("IMP-007 " + (hardKill ? "hard-kill" : "interruption")
                    + " phase=" + phase + " process="
                    + ProcessHandle.current().pid() + " coldProcess=" + (proof == null ? "none" : proof.process())
                    + " actor=" + fixture.actor + " position=" + fixture.villager.position()
                    + " fixedPosition=" + fixture.villager.isNoAi()
                    + " owner=" + fixture.owner + " run=" + (view == null ? runId : view.id())
                    + " artifact=" + fixture.admitted.sha256() + " marker="
                    + (view == null ? fixture.durableState().runs().stream()
                            .filter(marker -> marker.id().equals(runId)).findFirst().orElseThrow() : view.marker())
                    + " knownEffects=" + knownEffects + " receipts=" + (view == null ? 0 : view.receipts().size())
                    + " fakeCalls=" + fixture.fakeCalls + " chestWheat=" + chest().countItem(Items.WHEAT)
                    + " seedTotal=" + seeds() + " elapsedMs=" + (System.nanoTime() - startedNanos) / 1_000_000
                    + " peakHeapBytes=" + fixture.peakHeapBytes + " maxKernelTickNanos=" + fixture.maxTickNanos);
        }
    }

    private static AABB arena(BlockPos origin) {
        return new AABB(origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + 10, origin.getY() + 5, origin.getZ() + 9);
    }
    private static Proof readProof(Path world) {
        try {
            Path path = world.resolve("interruption.txt");
            if (Files.size(path) > 2048) throw new IllegalStateException("Oversized test proof");
            String[] p = Files.readString(path).trim().split(" ");
            if (p.length != 13 || !p[0].equals("1")) throw new IllegalStateException("Test proof fields");
            return new Proof(UUID.fromString(p[1]), UUID.fromString(p[2]), UUID.fromString(p[3]), p[4],
                    new TrustedContext(new PrincipalRef(UUID.fromString(p[5])),
                            new ScopeRef(UUID.fromString(p[6]), UUID.fromString(p[7]))),
                    new BlockPos(Integer.parseInt(p[8]), Integer.parseInt(p[9]), Integer.parseInt(p[10])),
                    Long.parseLong(p[11]), Long.parseLong(p[12]));
        } catch (Exception failed) { throw new IllegalStateException("Interrupted proof missing", failed); }
    }
    private static void writeProof(Path world, Proof p) {
        try {
            Files.writeString(world.resolve("interruption.txt"), "1 " + p.citizen() + " " + p.entity()
                    + " " + p.run() + " " + p.artifact() + " " + p.owner().principal().id()
                    + " " + p.owner().scope().worldId() + " " + p.owner().scope().domainId()
                    + " " + p.origin().getX() + " " + p.origin().getY() + " " + p.origin().getZ()
                    + " " + p.seeds() + " " + p.process());
        } catch (Exception failed) { throw new IllegalStateException("Interrupted proof write failed", failed); }
    }
}
