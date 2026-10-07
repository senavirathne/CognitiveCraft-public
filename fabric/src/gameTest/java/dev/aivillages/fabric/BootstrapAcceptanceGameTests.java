package dev.aivillages.fabric;

import dev.aivillages.core.kernel.BootstrapController;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.CropDelivery;
import dev.aivillages.core.kernel.Outcomes.Reason;
import dev.aivillages.core.kernel.Outcomes.ResolutionStatus;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.phys.AABB;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Fresh fake acquisition followed by real, model-free controller fault and conservation checks. */
public final class BootstrapAcceptanceGameTests {
    private static final BlockPos FIRST = new BlockPos(2, 1, 3);
    private static final BlockPos LAST = new BlockPos(3, 1, 4);
    private static final BlockPos CHEST = new BlockPos(4, 1, 4);
    private static final int ACTOR_STOCK = 7, SEED_STOCK = 9, OUTSIDE_STOCK = 13;
    private enum Case {
        CANCEL, EXTERNAL_DEPOSIT, INVENTORY_CHANGE, PARTIAL_CAPACITY,
        AUTHORITY_BEFORE_EFFECT, AUTHORITY_AFTER_PICKUP
    }

    @GameTest(maxTicks = 1800, padding = 16)
    public void cancelAfterFirstDeliveryPreservesPartialProgress(GameTestHelper h) {
        run(h, Case.CANCEL);
    }

    @GameTest(maxTicks = 1800, padding = 16)
    public void externalStockNeverReplacesAttributedDelivery(GameTestHelper h) {
        run(h, Case.EXTERNAL_DEPOSIT);
    }

    @GameTest(maxTicks = 1800, padding = 16)
    public void changedWorkerInventoryBlocksFurtherCredit(GameTestHelper h) {
        run(h, Case.INVENTORY_CHANGE);
    }

    @GameTest(maxTicks = 1800, padding = 16)
    public void partialChestCapacityTerminatesWithoutReplay(GameTestHelper h) {
        run(h, Case.PARTIAL_CAPACITY);
    }

    @GameTest(maxTicks = 1800, padding = 16)
    public void authorityRevokedAfterResolutionPreventsFirstEffect(GameTestHelper h) {
        run(h, Case.AUTHORITY_BEFORE_EFFECT);
    }

    @GameTest(maxTicks = 1800, padding = 16)
    public void authorityRevokedAfterPickupPreventsTransfer(GameTestHelper h) {
        run(h, Case.AUTHORITY_AFTER_PICKUP);
    }

    private static void run(GameTestHelper h, Case scenario) {
        Scene scene = new Scene(h, scenario);
        h.succeedWhen(() -> {
            scene.step();
            h.assertTrue(scene.phase == 4 && scene.closing.isDone(),
                    "Waiting for controller acceptance case " + scenario + " phase=" + scene.phase);
            scene.closing.join();
        });
    }

    private static final class Scene {
        final GameTestHelper h;
        final Case scenario;
        final BootstrapGameTests.Run fixture;
        final AABB arena;
        int phase, initialChest, injectedStock;
        UUID runId;
        ValidatedRequest bound;
        BootstrapController.View terminal;
        long quietUntil, frozenWheat, frozenSeeds, frozenChest, frozenDrops, frozenMature;
        long observedSeeds, seedYield, observedHarvests;
        boolean reportedTerminal;
        int deniedScopeOperations, frozenAuthorityDenials;
        TrustedContext foreignPrincipal, foreignScope;
        CompletableFuture<Void> closing;

        Scene(GameTestHelper h, Case scenario) {
            this.h = h; this.scenario = scenario;
            for (int x = 0; x < 10; x++) for (int z = 0; z < 9; z++)
                h.setBlock(x, 0, z, Blocks.STONE);
            maturePatch();
            h.setBlock(CHEST, Blocks.CHEST);
            // Contain real harvested drops, as in the accepted bootstrap fixture.
            for (int x = 1; x <= 4; x++) for (int z = 2; z <= 5; z++)
                if ((x == 1 || x == 4 || z == 2 || z == 5) && !(x == 4 && z == 4))
                    h.setBlock(x, 1, z, Blocks.STONE);
            var worker = h.spawnWithNoFreeWill(EntityTypes.VILLAGER, 3, 1, 3);
            if (revocationCase()) worker.setNoAi(true); // Fixed-position physical test setup.
            fixture = new BootstrapGameTests.Run(h, worker, null, null,
                    h.absolutePos(BlockPos.ZERO), null, true);
            BlockPos min = h.absolutePos(BlockPos.ZERO);
            BlockPos max = h.absolutePos(new BlockPos(10, 5, 9));
            arena = new AABB(min.getX(), min.getY(), min.getZ(),
                    max.getX(), max.getY(), max.getZ());
        }

        void step() {
            if (phase == 4) return;
            fixture.advance();
            if (phase == 0) {
                if (fixture.stage != 9) return;
                resetBeforeRequest();
                var request = fixture.request(4, FIRST, LAST, CHEST);
                bound = new ValidatedRequest(request, fixture.owner,
                        new ObservationRef(UUID.randomUUID(), h.getLevel().getGameTime(),
                                fixture.actor.dimension()));
                if (revocationCase()) {
                    foreignPrincipal = new TrustedContext(new PrincipalRef(UUID.randomUUID()),
                            fixture.owner.scope());
                    foreignScope = new TrustedContext(fixture.owner.principal(), new ScopeRef(
                            fixture.owner.scope().worldId(), UUID.randomUUID()));
                }
                var submission = fixture.controller.submit(request, fixture.owner);
                h.assertTrue(submission.accepted(), "Admitted request rejected: " + submission.reason());
                runId = submission.id(); phase = 1;
                return;
            }
            var status = fixture.controller.status(runId, fixture.owner);
            trackSeedYield(status);
            if (phase == 1) {
                if (scenario == Case.PARTIAL_CAPACITY) phase = 2;
                else {
                    long deposits = wheat(status, CropDelivery.Stage.DEPOSIT);
                    long pickups = wheat(status, CropDelivery.Stage.PICKUP);
                    boolean barrier = scenario == Case.AUTHORITY_BEFORE_EFFECT
                            ? status.phase() == BootstrapController.Phase.EXECUTING
                            : scenario == Case.INVENTORY_CHANGE || scenario == Case.AUTHORITY_AFTER_PICKUP
                                    ? pickups == 1 && deposits == 0 : deposits == 1;
                    if (!barrier) {
                        h.assertTrue(status.phase() != BootstrapController.Phase.TERMINAL,
                                "Run terminated before the effect barrier: " + status);
                        return;
                    }
                    h.assertTrue(!CropDelivery.completed(bound, runId, status.receipts()),
                            "Setup stock satisfied the four-wheat predicate after one pickup/delivery");
                    switch (scenario) {
                        case CANCEL -> fixture.controller.cancel(runId, fixture.owner);
                        case EXTERNAL_DEPOSIT -> {
                            injectedStock = 37;
                            chest().setItem(0, new ItemStack(Items.WHEAT,
                                    chest().countItem(Items.WHEAT) + injectedStock));
                            h.assertTrue(!CropDelivery.completed(bound, runId, status.receipts()),
                                    "Injected chest stock became a qualifying receipt");
                        }
                        case INVENTORY_CHANGE -> {
                            injectedStock = 11;
                            fixture.villager.getInventory().addItem(new ItemStack(Items.WHEAT, injectedStock));
                        }
                        case AUTHORITY_BEFORE_EFFECT, AUTHORITY_AFTER_PICKUP -> {
                            h.assertTrue(status.routing() != null
                                            && status.routing().status() == ResolutionStatus.RESOLVED
                                            && status.routing().artifact().equals(fixture.admitted)
                                            && status.research() == null
                                            && (scenario != Case.AUTHORITY_BEFORE_EFFECT
                                                || status.receipts().isEmpty()),
                                    "Revocation did not occur after resolution and before the intended effect");
                            rejectForeignControl(status);
                            fixture.physicalAuthorityGranted = false;
                            System.out.println("IMP-007 authority revoke case=" + scenario
                                    + " actor=" + fixture.actor + " owner=" + fixture.owner
                                    + " run=" + runId + " ref=" + fixture.admitted.sha256()
                                    + " source=" + bound.request().arguments().get("source")
                                    + " destination=" + bound.request().arguments().get("destination")
                                    + " receipts=" + status.receipts().size());
                        }
                        default -> throw new AssertionError("Unexpected barrier case");
                    }
                    phase = 2;
                    return;
                }
            }
            if (phase == 2) {
                if (status.phase() != BootstrapController.Phase.TERMINAL) return;
                terminal = status;
                verifyTerminal();
                if (revocationCase()) {
                    rejectForeignControl(terminal);
                    frozenAuthorityDenials = fixture.deniedAuthorityChecks;
                    fixture.physicalAuthorityGranted = true;
                }
                if (scenario == Case.PARTIAL_CAPACITY)
                    chest().setItem(chest().getContainerSize() - 1, new ItemStack(Items.WHEAT, 63));
                // Restored capacity, repeated cancel/status and late ticks cannot resume this run.
                frozenWheat = inventoryWheat();
                frozenSeeds = totalSeeds();
                frozenChest = chest().countItem(Items.WHEAT);
                frozenDrops = droppedWheat(); frozenMature = matureCount();
                quietUntil = h.getLevel().getGameTime() + 40;
                phase = 3;
                return;
            }
            h.assertTrue(status.equals(terminal)
                            && fixture.controller.cancel(runId, fixture.owner).equals(terminal),
                    "Repeated status/cancel changed terminal receipts or outcome");
            h.assertTrue(inventoryWheat() == frozenWheat
                            && totalSeeds() == frozenSeeds
                            && chest().countItem(Items.WHEAT) == frozenChest
                            && droppedWheat() == frozenDrops && matureCount() == frozenMature,
                    "Late ticks replayed a terminated effect or changed conserved stock");
            h.assertTrue(!AiVillages.controls(fixture.villager) && fixture.gateway.activeRuns() == 0,
                    "Terminated controller retained physical custody");
            if (revocationCase())
                h.assertTrue(fixture.physicalAuthorityGranted
                                && fixture.deniedAuthorityChecks == frozenAuthorityDenials
                                && deniedScopeOperations == 8,
                        "Restoring authority restarted work or foreign control was not checked");
            if (h.getLevel().getGameTime() < quietUntil) return;
            System.out.println("IMP-007 controller acceptance case=" + scenario
                    + " run=" + runId + " ref=" + fixture.admitted.sha256()
                    + " result=" + terminal.marker().outcome() + " reason=" + terminal.marker().reason()
                    + " harvested=" + wheat(terminal, CropDelivery.Stage.HARVEST)
                    + " picked=" + wheat(terminal, CropDelivery.Stage.PICKUP)
                    + " deposited=" + wheat(terminal, CropDelivery.Stage.DEPOSIT)
                    + " effects=" + terminal.marker().effects() + " calls=" + terminal.marker().modelCalls()
                    + " injectedStock=" + injectedStock + " harvestedSeedYield=" + seedYield
                    + " seedTotal=" + totalSeeds() + " quietTicks=40"
                    + " authorityDenials=" + fixture.deniedAuthorityChecks
                    + " deniedEffect=" + fixture.lastDeniedEffect
                    + " authorityRestored=" + fixture.physicalAuthorityGranted
                    + " foreignControlDenials=" + deniedScopeOperations);
            closing = fixture.closeAcceptanceFixture(); phase = 4;
        }

        private void resetBeforeRequest() {
            h.assertTrue(fixture.fakeCalls == 1 && !fixture.controller.inferenceEnabled(),
                    "Expected one fake acquisition and a disabled throwing generator for reuse");
            // Explicit fixture reset is outside the new request and its receipts.
            h.getLevel().getEntitiesOfClass(ItemEntity.class, arena).forEach(ItemEntity::discard);
            maturePatch();
            fixture.villager.getInventory().clearContent();
            fixture.villager.getInventory().addItem(new ItemStack(Items.WHEAT, ACTOR_STOCK));
            fixture.villager.getInventory().addItem(new ItemStack(Items.WHEAT_SEEDS, SEED_STOCK));
            Container box = chest(); box.clearContent();
            initialChest = scenario == Case.PARTIAL_CAPACITY ? 63 : 20;
            if (scenario == Case.PARTIAL_CAPACITY) {
                for (int slot = 0; slot < box.getContainerSize(); slot++)
                    box.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
                box.setItem(box.getContainerSize() - 1, new ItemStack(Items.WHEAT, initialChest));
            } else box.setItem(0, new ItemStack(Items.WHEAT, initialChest));
            BlockPos outside = h.absolutePos(new BlockPos(0, 1, 0));
            var drop = new ItemEntity(h.getLevel(), outside.getX() + 0.5, outside.getY(),
                    outside.getZ() + 0.5, new ItemStack(Items.WHEAT, OUTSIDE_STOCK));
            drop.setNoGravity(true); drop.setDeltaMovement(0, 0, 0);
            h.getLevel().addFreshEntity(drop);
            observedSeeds = totalSeeds();
            h.assertTrue(observedSeeds == SEED_STOCK, "Seed setup was not isolated from acquisition");
        }

        private void trackSeedYield(BootstrapController.View status) {
            long harvested = wheat(status, CropDelivery.Stage.HARVEST);
            long seeds = totalSeeds();
            if (harvested > observedHarvests) {
                long delta = seeds - observedSeeds;
                h.assertTrue(delta >= 0 && delta <= 64 * (harvested - observedHarvests),
                        "Harvest seed yield exceeds the bounded physical envelope");
                seedYield += delta;
            } else h.assertTrue(seeds == observedSeeds,
                    "Seed sum changed without an actual new crop harvest");
            observedSeeds = seeds; observedHarvests = harvested;
        }

        private void verifyTerminal() {
            long harvested = wheat(terminal, CropDelivery.Stage.HARVEST);
            long picked = wheat(terminal, CropDelivery.Stage.PICKUP);
            long deposited = wheat(terminal, CropDelivery.Stage.DEPOSIT);
            if (!reportedTerminal) {
                reportedTerminal = true;
                System.out.println("IMP-007 acceptance stock case=" + scenario
                        + " actorWheat=" + inventoryWheat()
                        + " actorSeeds=" + fixture.villager.getInventory().countItem(Items.WHEAT_SEEDS)
                        + " chestWheat=" + chest().countItem(Items.WHEAT)
                        + " droppedWheat=" + droppedWheat() + " matureCrops=" + matureCount()
                        + " harvested=" + harvested + " picked=" + picked + " deposited=" + deposited
                        + " injected=" + injectedStock + " actor=" + fixture.villager.position()
                        + " arena=" + arena + " drops="
                        + h.getLevel().getEntitiesOfClass(ItemEntity.class, arena).stream()
                                .map(item -> item.getItem() + "@" + item.position()).toList());
            }
            String expectedOutcome = scenario == Case.EXTERNAL_DEPOSIT ? "SUCCEEDED"
                    : scenario == Case.CANCEL ? "CANCELLED" : "BLOCKED";
            Reason expectedReason = switch (scenario) {
                case CANCEL -> Reason.CANCELLED;
                case EXTERNAL_DEPOSIT -> null;
                case INVENTORY_CHANGE -> Reason.STALE_OBSERVATION;
                case PARTIAL_CAPACITY -> Reason.TARGET_UNAVAILABLE;
                case AUTHORITY_BEFORE_EFFECT, AUTHORITY_AFTER_PICKUP -> Reason.AUTHORITY_DENIED;
            };
            long expectedHarvest = scenario == Case.EXTERNAL_DEPOSIT ? 4
                    : scenario == Case.PARTIAL_CAPACITY ? 2
                    : scenario == Case.AUTHORITY_BEFORE_EFFECT ? 0 : 1;
            long expectedDeposit = scenario == Case.EXTERNAL_DEPOSIT ? 4
                    : scenario == Case.INVENTORY_CHANGE || revocationCase() ? 0 : 1;
            h.assertTrue(terminal.marker() != null && terminal.outcome() != null
                            && expectedOutcome.equals(terminal.marker().outcome())
                            && terminal.marker().reason() == expectedReason,
                    "Incorrect typed controller result: " + terminal);
            h.assertTrue(harvested == expectedHarvest && picked == expectedHarvest
                            && deposited == expectedDeposit
                            && terminal.marker().effects() == harvested + picked + deposited
                            && terminal.receipts().size() == harvested + picked + deposited,
                    "Incorrect attributable partial progress: " + terminal);
            h.assertTrue(terminal.marker().modelCalls() == 0 && fixture.fakeCalls == 1
                            && fixture.admitted.sha256().equals(terminal.marker().artifactSha256()),
                    "Blockage or changed stock invoked research or changed artifact identity");
            if (revocationCase())
                h.assertTrue(!fixture.physicalAuthorityGranted && fixture.deniedAuthorityChecks > 0
                                && fixture.lastDeniedEffect == (scenario == Case.AUTHORITY_BEFORE_EFFECT
                                        ? Effect.HARVEST : Effect.TRANSFER)
                                && terminal.routing().status() == ResolutionStatus.RESOLVED
                                && terminal.research() == null,
                        "Current authority was not checked at the denied physical effect");
            h.assertTrue(CropDelivery.completed(bound, runId, terminal.receipts())
                            == (scenario == Case.EXTERNAL_DEPOSIT),
                    "Completion was inferred from unrelated stock or incomplete receipts");
            for (var receipt : terminal.receipts())
                h.assertTrue(receipt.runId().equals(runId) && receipt.actor().equals(fixture.actor)
                                && receipt.source().equals(((AreaValue)bound.request().arguments().get("source")).value())
                                && receipt.destination().equals(((ContainerValue)bound.request().arguments().get("destination")).value()),
                        "Receipt escaped its run, actor or permitted bindings");
            long actorInjection = scenario == Case.INVENTORY_CHANGE ? injectedStock : 0;
            long chestInjection = scenario == Case.EXTERNAL_DEPOSIT ? injectedStock : 0;
            h.assertTrue(inventoryWheat() == ACTOR_STOCK + actorInjection + picked - deposited
                            && fixture.villager.getInventory().countItem(Items.WHEAT_SEEDS) >= SEED_STOCK
                            && totalSeeds() == SEED_STOCK + seedYield
                            && chest().countItem(Items.WHEAT) == initialChest + chestInjection + deposited
                            && droppedWheat() == OUTSIDE_STOCK + harvested - picked
                            && matureCount() == 4 - harvested,
                    "Physical conservation case=" + scenario + " actor=" + inventoryWheat()
                            + " seeds=" + fixture.villager.getInventory().countItem(Items.WHEAT_SEEDS)
                            + " chest=" + chest().countItem(Items.WHEAT) + " drops=" + droppedWheat()
                            + " mature=" + matureCount() + " harvested=" + harvested
                            + " picked=" + picked + " deposited=" + deposited + " injected=" + injectedStock);
            h.assertTrue(inventoryWheat() + chest().countItem(Items.WHEAT) + droppedWheat()
                            == ACTOR_STOCK + initialChest + OUTSIDE_STOCK + injectedStock + harvested,
                    "Physical wheat sum differs from setup, external stock and legitimate harvest yield");
        }

        private boolean revocationCase() {
            return scenario == Case.AUTHORITY_BEFORE_EFFECT || scenario == Case.AUTHORITY_AFTER_PICKUP;
        }

        private void rejectForeignControl(BootstrapController.View expected) {
            for (TrustedContext caller : new TrustedContext[]{foreignPrincipal, foreignScope}) {
                boolean inspectDenied = false, cancelDenied = false;
                try { fixture.controller.status(runId, caller); }
                catch (SecurityException required) { inspectDenied = true; deniedScopeOperations++; }
                try { fixture.controller.cancel(runId, caller); }
                catch (SecurityException required) { cancelDenied = true; deniedScopeOperations++; }
                h.assertTrue(inspectDenied && cancelDenied,
                        "A different principal or domain inspected or cancelled private work");
            }
            h.assertTrue(fixture.controller.status(runId, fixture.owner).equals(expected),
                    "Denied foreign operations changed the owner's result or receipts");
        }

        private long wheat(BootstrapController.View status, CropDelivery.Stage stage) {
            return status.receipts().stream().filter(receipt -> receipt.stage() == stage)
                    .mapToLong(CropDelivery.CropReceipt::wheat).sum();
        }
        private Container chest() { return (Container)h.getLevel().getBlockEntity(h.absolutePos(CHEST)); }
        private long inventoryWheat() { return fixture.villager.getInventory().countItem(Items.WHEAT); }
        private long droppedWheat() {
            return h.getLevel().getEntitiesOfClass(ItemEntity.class, arena,
                            item -> item.getItem().is(Items.WHEAT)).stream()
                    .mapToLong(item -> item.getItem().getCount()).sum();
        }
        private long totalSeeds() {
            return fixture.villager.getInventory().countItem(Items.WHEAT_SEEDS)
                    + chest().countItem(Items.WHEAT_SEEDS)
                    + h.getLevel().getEntitiesOfClass(ItemEntity.class, arena,
                                    item -> item.getItem().is(Items.WHEAT_SEEDS)).stream()
                            .mapToLong(item -> item.getItem().getCount()).sum();
        }
        private void maturePatch() {
            for (int x = FIRST.getX(); x <= LAST.getX(); x++)
                for (int z = FIRST.getZ(); z <= LAST.getZ(); z++) {
                    BlockPos crop = new BlockPos(x, FIRST.getY(), z);
                    h.setBlock(crop.below(), Blocks.FARMLAND.defaultBlockState()
                            .setValue(FarmlandBlock.MOISTURE, 7));
                    h.setBlock(crop, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
                }
        }
        private long matureCount() {
            long count = 0;
            for (int x = FIRST.getX(); x <= LAST.getX(); x++)
                for (int z = FIRST.getZ(); z <= LAST.getZ(); z++) {
                    var state = h.getBlockState(new BlockPos(x, FIRST.getY(), z));
                    if (state.is(Blocks.WHEAT) && state.getValue(CropBlock.AGE) == 7) count++;
                }
            return count;
        }
    }
}
