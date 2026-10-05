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

/** Physical GP-04/05 shortage, attribution and explicit recovery with admitted fake knowledge. */
public final class BootstrapBlockerGameTests {
    private static final BlockPos FIRST = new BlockPos(2, 1, 3);
    private static final BlockPos LAST = new BlockPos(3, 1, 4);
    private static final BlockPos CHEST = new BlockPos(4, 1, 4);

    @GameTest(maxTicks = 1800, padding = 16)
    public void insufficientMatureCropsPreservePartialWorkUntilNewRequest(GameTestHelper h) {
        run(h, 2, 6);
    }

    @GameTest(maxTicks = 1800, padding = 16)
    public void unrelatedStockWithoutMatureCropsCannotFulfillRequest(GameTestHelper h) {
        run(h, 0, 4);
    }

    private static void run(GameTestHelper h, int mature, int amount) {
        Scene scene = new Scene(h, mature, amount);
        h.succeedWhen(() -> {
            scene.step();
            h.assertTrue(scene.phase == 5 && scene.closing.isDone(),
                    "Waiting for crop blocker mature=" + mature + " phase=" + scene.phase);
            scene.closing.join();
        });
    }

    private static final class Scene {
        final GameTestHelper h;
        final int initialMature, amount;
        final BootstrapGameTests.Run fixture;
        final AABB arena;
        int phase;
        UUID blockedId, continuedId;
        ValidatedRequest blockedBound, continuedBound;
        BootstrapController.View blocked, continued;
        long observedSeeds, observedHarvests, seedYield;
        long quietUntil, frozenWheat, frozenChest, frozenDrops, frozenMature, frozenSeeds;
        CompletableFuture<Void> closing;

        Scene(GameTestHelper h, int mature, int amount) {
            this.h = h; initialMature = mature; this.amount = amount;
            for (int x = 0; x < 10; x++) for (int z = 0; z < 9; z++)
                h.setBlock(x, 0, z, Blocks.STONE);
            patch(4); h.setBlock(CHEST, Blocks.CHEST);
            for (int x = 1; x <= 4; x++) for (int z = 2; z <= 5; z++)
                if ((x == 1 || x == 4 || z == 2 || z == 5) && !(x == 4 && z == 4))
                    h.setBlock(x, 1, z, Blocks.STONE);
            var villager = h.spawnWithNoFreeWill(EntityTypes.VILLAGER, 3, 1, 3);
            villager.setNoAi(true); // Stationary fixture setup; native harvest/drop/pickup/transfer still run.
            fixture = new BootstrapGameTests.Run(h, villager, null, null,
                    h.absolutePos(BlockPos.ZERO), null, true);
            fixture.retainFirstTerminal = true;
            BlockPos min = h.absolutePos(BlockPos.ZERO), max = h.absolutePos(new BlockPos(10, 5, 9));
            arena = new AABB(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
        }

        void step() {
            if (phase == 5) return;
            fixture.advance();
            if (phase == 0) {
                if (fixture.stage != 9) return;
                var acquired = fixture.controller.status(fixture.runId, fixture.owner);
                h.assertTrue("ADMITTED".equals(acquired.marker().outcome())
                                && acquired.research() != null && acquired.receipts().size() == 12
                                && fixture.fakeCalls == 1,
                        "Expected one physical fake acquisition with durable admission");
                fixture.admitted = acquired.research().artifact();
                h.assertTrue(fixture.repository().resolve(fixture.admitted).usable(),
                        "Acquisition did not publish usable knowledge");
                fixture.controller.inference(false);
                fixture.resumeAcceptanceController(); // Known reuse uses the existing throwing generator.
                h.assertTrue(!fixture.controller.inferenceEnabled(), "Reuse inference gate was not disabled");
                // Explicit setup reset is outside every new run and its receipts.
                h.getLevel().getEntitiesOfClass(ItemEntity.class, arena).forEach(ItemEntity::discard);
                patch(initialMature);
                fixture.villager.getInventory().clearContent();
                fixture.villager.getInventory().addItem(new ItemStack(Items.WHEAT, 7));
                fixture.villager.getInventory().addItem(new ItemStack(Items.WHEAT_SEEDS, 9));
                chest().clearContent(); chest().setItem(0, new ItemStack(Items.WHEAT, 20));
                BlockPos outside = h.absolutePos(new BlockPos(0, 1, 0));
                var drop = new ItemEntity(h.getLevel(), outside.getX() + 0.5, outside.getY(),
                        outside.getZ() + 0.5, new ItemStack(Items.WHEAT, 13));
                drop.setNoGravity(true); drop.setDeltaMovement(0, 0, 0);
                h.getLevel().addFreshEntity(drop);
                observedSeeds = seeds();
                var request = fixture.request(amount, FIRST, LAST, CHEST);
                blockedBound = bound(request);
                var submitted = fixture.controller.submit(request, fixture.owner);
                h.assertTrue(submitted.accepted(), "Valid crop-shortage request rejected: " + submitted.reason());
                blockedId = submitted.id(); phase = 1;
                return;
            }
            var status = fixture.controller.status(phase < 3 ? blockedId : continuedId, fixture.owner);
            trackSeeds(status);
            if (phase == 1) {
                if (status.phase() != BootstrapController.Phase.TERMINAL) return;
                blocked = status;
                h.assertTrue("BLOCKED".equals(status.marker().outcome())
                                && status.marker().reason() == Reason.RESOURCE_MISSING
                                && status.marker().effects() == 3L * initialMature
                                && status.receipts().size() == 3 * initialMature
                                && status.marker().modelCalls() == 0 && status.research() == null,
                        "Shortage did not report exact partial work: " + status);
                h.assertTrue(status.routing().status() == (initialMature == 0
                                ? ResolutionStatus.BLOCKED : ResolutionStatus.RESOLVED)
                                && (initialMature == 0 ? status.outcome() == null
                                    : fixture.admitted.sha256().equals(status.marker().artifactSha256())),
                        "Missing crops became a synthesis gap or invented execution");
                verifyReceipts(status, blockedBound, blockedId, initialMature);
                h.assertTrue(!CropDelivery.completed(blockedBound, blockedId, status.receipts()),
                        "Pre-existing worker/chest/outside wheat satisfied an incomplete request");
                verifyStock(initialMature, 0);
                // Restore only the fixture's crop supply; the terminated request must stay quiet.
                patch(4); freeze(); phase = 2;
                return;
            }
            if (phase == 2) {
                stable(blockedId, blocked); frozen();
                if (h.getLevel().getGameTime() < quietUntil) return;
                var request = fixture.request(1, FIRST, LAST, CHEST);
                continuedBound = bound(request);
                var submitted = fixture.controller.submit(request, fixture.owner);
                h.assertTrue(submitted.accepted() && !submitted.id().equals(blockedId),
                        "Restored supply did not require a new explicit request");
                continuedId = submitted.id(); observedHarvests = 0; observedSeeds = seeds();
                phase = 3;
                return;
            }
            if (phase == 3) {
                stable(blockedId, blocked);
                if (status.phase() != BootstrapController.Phase.TERMINAL) return;
                continued = status;
                h.assertTrue("SUCCEEDED".equals(status.marker().outcome())
                                && status.marker().reason() == null && status.marker().effects() == 3
                                && status.marker().modelCalls() == 0 && status.research() == null
                                && fixture.admitted.sha256().equals(status.marker().artifactSha256()),
                        "Explicit recovery changed knowledge, repeated work or invoked a model: " + status);
                verifyReceipts(status, continuedBound, continuedId, 1);
                h.assertTrue(CropDelivery.completed(continuedBound, continuedId, status.receipts()),
                        "Explicit recovery lacked trusted physical completion");
                verifyStock(initialMature + 1, 3); freeze(); phase = 4;
                return;
            }
            stable(blockedId, blocked); stable(continuedId, continued); frozen();
            if (h.getLevel().getGameTime() < quietUntil) return;
            System.out.println("IMP-007 crop blocker acceptance: initialMature=" + initialMature
                    + " requested=" + amount + " run=" + blockedId + " result=BLOCKED reason=RESOURCE_MISSING"
                    + " harvested=" + initialMature + " picked=" + initialMature + " deposited=" + initialMature
                    + " effects=" + blocked.marker().effects() + " receipts=" + blocked.receipts().size()
                    + " ref=" + fixture.admitted.sha256() + " continuation=" + continuedId
                    + " continuationEffects=3 continuationReceipts=3 reuseCalls=0 fakeCalls=" + fixture.fakeCalls
                    + " actorWheat=" + wheat() + " chestWheat=" + chest().countItem(Items.WHEAT)
                    + " droppedWheat=" + drops() + " matureCrops=" + matureCount()
                    + " seedYield=" + seedYield + " seedTotal=" + seeds()
                    + " quietAfterRestore=40 quietAfterNewRequest=40 custodyReleased=true");
            closing = fixture.closeAcceptanceFixture(); phase = 5;
        }

        private ValidatedRequest bound(CapabilityRequest request) {
            return new ValidatedRequest(request, fixture.owner,
                    new ObservationRef(UUID.randomUUID(), h.getLevel().getGameTime(), fixture.actor.dimension()));
        }

        private void verifyReceipts(BootstrapController.View view, ValidatedRequest request, UUID id, int amount) {
            h.assertTrue(view.receipts().size() == 3 * amount, "Unexpected physical receipt count");
            for (CropDelivery.Stage stage : CropDelivery.Stage.values())
                h.assertTrue(view.receipts().stream().filter(r -> r.stage() == stage)
                                .mapToLong(CropDelivery.CropReceipt::wheat).sum() == amount,
                        "Missing or duplicated physical stage " + stage);
            for (var receipt : view.receipts())
                h.assertTrue(receipt.runId().equals(id) && receipt.actor().equals(fixture.actor)
                                && receipt.source().equals(((AreaValue)request.request().arguments().get("source")).value())
                                && receipt.destination().equals(((ContainerValue)request.request().arguments().get("destination")).value()),
                        "Receipt escaped its run, actor, source or destination");
        }

        private void verifyStock(int delivered, int mature) {
            h.assertTrue(wheat() == 7 && chest().countItem(Items.WHEAT) == 20 + delivered
                            && drops() == 13 && matureCount() == mature && seeds() == 9 + seedYield
                            && fixture.fakeCalls == 1 && !fixture.controller.inferenceEnabled()
                            && fixture.repository().resolve(fixture.admitted).usable(),
                    "Shortage/recovery changed physical stock, seed yield or admitted knowledge");
        }

        private void trackSeeds(BootstrapController.View status) {
            long harvested = status.receipts().stream().filter(r -> r.stage() == CropDelivery.Stage.HARVEST)
                    .mapToLong(CropDelivery.CropReceipt::wheat).sum();
            long current = seeds();
            if (harvested > observedHarvests) {
                long delta = current - observedSeeds;
                h.assertTrue(delta >= 0 && delta <= 64 * (harvested - observedHarvests),
                        "Seed yield exceeds the real harvest envelope");
                seedYield += delta;
            } else h.assertTrue(current == observedSeeds, "Seeds changed without a new attributed harvest");
            observedSeeds = current; observedHarvests = harvested;
        }

        private void stable(UUID id, BootstrapController.View view) {
            h.assertTrue(fixture.controller.status(id, fixture.owner).equals(view)
                            && fixture.controller.cancel(id, fixture.owner).equals(view),
                    "A terminated attempt resumed or changed its receipts");
        }

        private void freeze() {
            frozenWheat = wheat(); frozenChest = chest().countItem(Items.WHEAT);
            frozenDrops = drops(); frozenMature = matureCount(); frozenSeeds = seeds();
            quietUntil = h.getLevel().getGameTime() + 40;
        }

        private void frozen() {
            h.assertTrue(wheat() == frozenWheat && chest().countItem(Items.WHEAT) == frozenChest
                            && drops() == frozenDrops && matureCount() == frozenMature && seeds() == frozenSeeds
                            && fixture.gateway.activeRuns() == 0 && !AiVillages.controls(fixture.villager)
                            && fixture.fakeCalls == 1,
                    "Restoration/late ticks replayed work or retained custody");
        }

        private Container chest() { return (Container)h.getLevel().getBlockEntity(h.absolutePos(CHEST)); }
        private long wheat() { return fixture.villager.getInventory().countItem(Items.WHEAT); }
        private long drops() {
            return h.getLevel().getEntitiesOfClass(ItemEntity.class, arena,
                    item -> item.getItem().is(Items.WHEAT)).stream().mapToLong(item -> item.getItem().getCount()).sum();
        }
        private long seeds() {
            return fixture.villager.getInventory().countItem(Items.WHEAT_SEEDS)
                    + chest().countItem(Items.WHEAT_SEEDS)
                    + h.getLevel().getEntitiesOfClass(ItemEntity.class, arena,
                        item -> item.getItem().is(Items.WHEAT_SEEDS)).stream().mapToLong(item -> item.getItem().getCount()).sum();
        }
        private void patch(int count) {
            int index = 0;
            for (int x = FIRST.getX(); x <= LAST.getX(); x++) for (int z = FIRST.getZ(); z <= LAST.getZ(); z++) {
                BlockPos crop = new BlockPos(x, FIRST.getY(), z);
                h.setBlock(crop.below(), Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7));
                h.setBlock(crop, index++ < count ? Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7)
                        : Blocks.AIR.defaultBlockState());
            }
        }
        private long matureCount() {
            long count = 0;
            for (int x = FIRST.getX(); x <= LAST.getX(); x++) for (int z = FIRST.getZ(); z <= LAST.getZ(); z++) {
                var state = h.getBlockState(new BlockPos(x, FIRST.getY(), z));
                if (state.is(Blocks.WHEAT) && state.getValue(CropBlock.AGE) == 7) count++;
            }
            return count;
        }
    }
}
