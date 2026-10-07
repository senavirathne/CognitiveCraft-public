package dev.aivillages.fabric;

import dev.aivillages.core.kernel.BootstrapController;
import dev.aivillages.core.kernel.BootstrapJournal;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.CropDelivery;
import dev.aivillages.core.kernel.Outcomes.*;
import dev.aivillages.core.kernel.VersionedSkillRepository;
import dev.aivillages.core.kernel.VersionedSkillRepository.FaultPoint;
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

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Real physical fulfillment, injected persistence failures and explicit recovery without replay. */
public final class BootstrapStorageGameTests {
    private static final BlockPos FIRST = new BlockPos(2, 1, 3);
    private static final BlockPos LAST = new BlockPos(3, 1, 4);
    private static final BlockPos CHEST = new BlockPos(4, 1, 4);
    private enum Case {
        BODY_WRITE, MANIFEST_WRITE, COMMITTED_MANIFEST, BODY_QUOTA,
        TERMINAL_MARKER, TERMINAL_DELAY, TERMINAL_TIMEOUT
    }

    @GameTest(maxTicks = 1800, padding = 16)
    public void failedBodyWriteKeepsDeliveredWorkUnadmitted(GameTestHelper h) {
        run(h, Case.BODY_WRITE);
    }

    @GameTest(maxTicks = 1800, padding = 16)
    public void failedManifestWriteKeepsOrphanUnadmitted(GameTestHelper h) {
        run(h, Case.MANIFEST_WRITE);
    }

    @GameTest(maxTicks = 1800, padding = 16)
    public void committedManifestSurvivesLostPublicationAcknowledgement(GameTestHelper h) {
        run(h, Case.COMMITTED_MANIFEST);
    }

    @GameTest(maxTicks = 1800, padding = 16)
    public void admissionQuotaPreservesPhysicalFulfillment(GameTestHelper h) {
        run(h, Case.BODY_QUOTA);
    }

    @GameTest(maxTicks = 1800, padding = 16)
    public void failedTerminalMarkerRequiresExplicitRecoveryRequest(GameTestHelper h) {
        run(h, Case.TERMINAL_MARKER);
    }

    @GameTest(maxTicks = 1800, padding = 16)
    public void delayedTerminalWritePreservesLiveFulfillmentWithoutReplay(GameTestHelper h) {
        run(h, Case.TERMINAL_DELAY);
    }

    @GameTest(maxTicks = 1800, padding = 16)
    public void expiredTerminalWriteFencesLateCommitWithoutLosingFulfillment(GameTestHelper h) {
        run(h, Case.TERMINAL_TIMEOUT);
    }

    private static void run(GameTestHelper h, Case scenario) {
        Scene scene = new Scene(h, scenario);
        h.succeedWhen(() -> {
            scene.step();
            scene.trace();
            h.assertTrue(scene.phase == 7 && scene.closing.isDone(),
                    "Waiting for storage fixture " + scenario + " " + scene.diagnostic());
            scene.closing.join();
        });
    }

    private static final class Scene {
        final GameTestHelper h;
        final Case scenario;
        final BootstrapGameTests.Run fixture;
        final AABB arena;
        final AtomicInteger repositoryFaults = new AtomicInteger();
        int phase;
        boolean cancelled;
        UUID runId, continuationId;
        ArtifactRef candidate;
        CapabilityRequest request;
        BootstrapController.View terminal, reloaded, continued;
        BootstrapJournal.State reopenedCheckpoint;
        CompletableFuture<Void> reopening, closing;
        long quietUntil, frozenSeeds, frozenWheat, frozenChest, frozenDrops, frozenCrops;
        final long startedNanos = System.nanoTime();
        int stepTicks, traceLines, tracedPhase = -1, tracedStage = -1;
        long tracedTick = -100, delayedUntil;
        int writesBeforeDelay, finishedBeforeDelay, pendingBusyRejections, foreignPendingDenials;
        BootstrapController.View pendingTerminal;
        boolean storageExpired, lateCommitObserved;

        Scene(GameTestHelper h, Case scenario) {
            this.h = h; this.scenario = scenario;
            for (int x = 0; x < 10; x++) for (int z = 0; z < 9; z++)
                h.setBlock(x, 0, z, Blocks.STONE);
            maturePatch();
            h.setBlock(CHEST, Blocks.CHEST);
            for (int x = 1; x <= 4; x++) for (int z = 2; z <= 5; z++)
                if ((x == 1 || x == 4 || z == 2 || z == 5) && !(x == 4 && z == 4))
                    h.setBlock(x, 1, z, Blocks.STONE);
            var villager = h.spawnWithNoFreeWill(EntityTypes.VILLAGER, 3, 1, 3);
            var limits = VersionedSkillRepository.Limits.defaults();
            if (scenario == Case.BODY_QUOTA)
                limits = new VersionedSkillRepository.Limits(limits.maxArtifacts(), 1,
                        limits.maxMetadataBytes(), limits.maxTotalBytes(),
                        limits.maxVariantsPerCapability(), limits.maxPageSize(), limits.maxRecoveryFiles());
            FaultPoint fault = switch (scenario) {
                case BODY_WRITE -> FaultPoint.BEFORE_BODY_WRITE;
                case MANIFEST_WRITE -> FaultPoint.BEFORE_MANIFEST_REPLACE;
                case COMMITTED_MANIFEST -> FaultPoint.AFTER_MANIFEST_REPLACE;
                default -> null;
            };
            fixture = new BootstrapGameTests.Run(h, villager, null, null,
                    h.absolutePos(BlockPos.ZERO), null, markerCase(),
                    limits, point -> {
                        if (point == fault && repositoryFaults.compareAndSet(0, 1))
                            throw new IOException("Injected publication failure at " + point);
                    });
            fixture.retainFirstTerminal = !markerCase();
            BlockPos min = h.absolutePos(BlockPos.ZERO), max = h.absolutePos(new BlockPos(10, 5, 9));
            arena = new AABB(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
            stock();
        }

        void step() {
            stepTicks++;
            if (phase == 7) return;
            if (phase != 3) fixture.advance();
            if (phase == 0) {
                if (fixture.stage != 9) return;
                if (markerCase()) {
                    // Reset physical setup outside the new run's receipts.
                    h.getLevel().getEntitiesOfClass(ItemEntity.class, arena).forEach(ItemEntity::discard);
                    maturePatch(); stock();
                    candidate = fixture.admitted;
                    fixture.failTerminalWrite = true;
                    request = fixture.request(4, FIRST, LAST, CHEST);
                    var submitted = fixture.controller.submit(request, fixture.owner);
                    h.assertTrue(submitted.accepted(), "Offline partial request rejected");
                    runId = submitted.id(); phase = 1;
                } else {
                    request = fixture.request(4, FIRST, LAST, CHEST);
                    runId = fixture.runId;
                    terminal = fixture.controller.status(runId, fixture.owner);
                    candidate = terminal.research().artifact();
                    verifyPublication(); freeze(); phase = 2;
                }
                return;
            }
            if (phase == 1) {
                var status = fixture.controller.status(runId, fixture.owner);
                if (!cancelled) {
                    if (wheat(status, CropDelivery.Stage.DEPOSIT) != 1) {
                        h.assertTrue(status.phase() != BootstrapController.Phase.TERMINAL,
                                "Terminal-marker fixture missed the first-delivery barrier");
                        return;
                    }
                    fixture.controller.cancel(runId, fixture.owner); cancelled = true;
                    return;
                }
                if (status.phase() != BootstrapController.Phase.TERMINAL) return;
                terminal = status;
                h.assertTrue(terminal.storageError() == Reason.STORAGE_UNAVAILABLE
                                && terminal.outcome().status() == ExecutionStatus.CANCELLED
                                && terminal.outcome().reason() == Reason.CANCELLED
                                && terminal.outcome().committedEffects() == 3
                                && fixture.failedTerminalWrites == 1 && !fixture.controller.ready(),
                        "Failed marker did not fence the controller: " + terminal);
                verifyReceipts(terminal, runId, 1);
                var durable = fixture.durableState().runs().stream()
                        .filter(marker -> marker.id().equals(runId)).findFirst().orElseThrow();
                h.assertTrue(durable.phase() == BootstrapJournal.Phase.ACTIVE && durable.effects() == 0
                                && terminal.marker().equals(durable),
                        "Unwritten partial effects became authoritative metadata");
                String commandStatus = KernelRunStatus.describe(terminal);
                h.assertTrue(commandStatus.contains("outcome=CANCELLED reason=CANCELLED")
                                && commandStatus.contains("effectsAtCheckpoint=0")
                                && commandStatus.contains(" effects=3")
                                && commandStatus.contains("receipts=3")
                                && commandStatus.contains("storage=STORAGE_UNAVAILABLE"),
                        "Player status hid committed work after marker failure: " + commandStatus);
                verifyStock(1); freeze(); phase = 2;
                return;
            }
            if (phase == 2) {
                stable(runId, terminal);
                if (h.getLevel().getGameTime() < quietUntil) return;
                reopening = fixture.reopenAcceptanceStores(); phase = 3;
                return;
            }
            if (phase == 3) {
                if (!reopening.isDone()) return;
                reopening.join(); fixture.failTerminalWrite = false;
                reopenedCheckpoint = fixture.durableState(); // Before composition can start a new writer.
                fixture.resumeAcceptanceController();
                if (markerCase()) {
                    h.assertTrue(!fixture.controller.ready()
                                    && !fixture.controller.submit(request, fixture.owner).accepted(),
                            "New work ran before durable interrupted-marker reconciliation");
                }
                phase = 4;
                return;
            }
            if (phase == 4) {
                if (!fixture.controller.ready()) return;
                if (reloaded == null) {
                    reloaded = fixture.controller.status(runId, fixture.owner);
                    verifyReload(); quietUntil = h.getLevel().getGameTime() + 40;
                }
                stable(runId, reloaded);
                if (h.getLevel().getGameTime() < quietUntil) return;
                if (markerCase()) {
                    request = fixture.request(3, FIRST, LAST, CHEST);
                    var submitted = fixture.controller.submit(request, fixture.owner);
                    h.assertTrue(submitted.accepted() && !submitted.id().equals(runId),
                            "Explicit request did not revalidate the three remaining crops");
                    continuationId = submitted.id();
                    if (scenario == Case.TERMINAL_DELAY || scenario == Case.TERMINAL_TIMEOUT)
                        fixture.delayedTerminalRun = continuationId;
                    phase = 5;
                } else finish();
                return;
            }
            if (phase == 5) {
                var status = fixture.controller.status(continuationId, fixture.owner);
                if ((scenario == Case.TERMINAL_DELAY || scenario == Case.TERMINAL_TIMEOUT)
                        && holdTerminalWrite(status)) return;
                if (status.phase() != BootstrapController.Phase.TERMINAL) return;
                continued = status;
                h.assertTrue((scenario == Case.TERMINAL_TIMEOUT
                                ? status.outcome().status() == ExecutionStatus.SUCCEEDED
                                : "SUCCEEDED".equals(status.marker().outcome()))
                                && status.marker().modelCalls() == 0
                                && (scenario == Case.TERMINAL_TIMEOUT
                                    ? lateCommitObserved && status.storageError() == Reason.STORAGE_UNAVAILABLE
                                    : candidate.sha256().equals(status.marker().artifactSha256())),
                        "Explicit recovery request did not reuse admitted knowledge: " + status);
                verifyReceipts(status, continuationId, 3);
                h.assertTrue((scenario == Case.TERMINAL_TIMEOUT
                                ? status.outcome().committedEffects() : status.marker().effects()) == 9
                                && totalSeeds() >= frozenSeeds && totalSeeds() <= frozenSeeds + 3 * 64,
                        "Explicit recovery accounting changed prior effects or seed stock");
                if (scenario == Case.TERMINAL_DELAY)
                    h.assertTrue(fixture.delayedTerminalWrites == 1
                                    && fixture.terminalWriteGate.isDone()
                                    && fixture.journalWritesStarted.get() == writesBeforeDelay + 1
                                    && fixture.journalWritesFinished.get() == finishedBeforeDelay + 1
                                    && fixture.journalWriteFailures.get() == 0
                                    && pendingBusyRejections == 2 && foreignPendingDenials == 2
                                    && fixture.durableState().runs().stream().anyMatch(marker ->
                                            marker.equals(status.marker()))
                                    && fixture.controller.ready() && status.storageError() == null,
                            "Released terminal write did not publish exactly once: " + diagnostic());
                verifyStock(4); freeze(); phase = 6;
                return;
            }
            stable(continuationId, continued);
            if (h.getLevel().getGameTime() >= quietUntil) finish();
        }

        private void verifyPublication() {
            boolean admitted = scenario == Case.COMMITTED_MANIFEST;
            Reason reason = scenario == Case.BODY_QUOTA ? Reason.STORAGE_LIMIT_REACHED
                    : admitted ? null : Reason.STORAGE_UNAVAILABLE;
            h.assertTrue(terminal.phase() == BootstrapController.Phase.TERMINAL
                            && terminal.marker().phase() == BootstrapJournal.Phase.TERMINAL
                            && terminal.marker().outcome().equals(admitted ? "ADMITTED" : "BLOCKED")
                            && candidate.sha256().equals(terminal.marker().artifactSha256())
                            && terminal.research().status() == (admitted ? ResearchStatus.ADMITTED
                            : ResearchStatus.BLOCKED) && terminal.marker().reason() == reason
                            && terminal.research().published() == admitted
                            && terminal.marker().effects() == 12
                            && terminal.marker().modelCalls() == 1 && fixture.fakeCalls == 1,
                    "Publication failure misreported fulfillment or admission: " + terminal);
            UUID trial = terminal.receipts().getFirst().runId();
            verifyReceipts(terminal, trial, 4);
            verifyCatalog(admitted);
            h.assertTrue(repositoryFaults.get() == (scenario == Case.BODY_QUOTA ? 0 : 1),
                    "Publication fault did not fire exactly once");
            verifyStock(4);
        }

        private void verifyReload() {
            h.assertTrue(!fixture.controller.inferenceEnabled() && fixture.fakeCalls == 1
                            && fixture.controller.enrollment().actor().equals(fixture.actor)
                            && fixture.controller.enrollment().owner().equals(fixture.owner)
                            && reloaded.receipts().isEmpty(),
                    "Reload changed ownership, inferred receipts or enabled generation");
            boolean admitted = scenario == Case.COMMITTED_MANIFEST || markerCase();
            verifyCatalog(admitted);
            if (markerCase())
                h.assertTrue(reloaded.marker().phase() == BootstrapJournal.Phase.INTERRUPTED
                                && "INTERRUPTED".equals(reloaded.marker().outcome())
                                && reloaded.marker().reason() == Reason.INTERRUPTED
                                && reloaded.marker().effects() == 0
                                && fixture.durableState().runs().stream().anyMatch(marker ->
                                        marker.equals(reloaded.marker())),
                        "Reload fabricated progress or failed to persist the interrupted marker");
            else {
                h.assertTrue(reloaded.marker().equals(terminal.marker()),
                        "Failed or committed publication result changed on reload");
                h.assertTrue(fixture.repository().status().orphanBodies()
                                == (scenario == Case.MANIFEST_WRITE ? 1 : 0),
                        "Orphan accounting changed durable admission");
            }
        }

        private void verifyCatalog(boolean admitted) {
            var view = fixture.repository().resolve(candidate);
            h.assertTrue(admitted ? view != null && view.usable() : view == null,
                    "Catalog falsely admitted failed work or lost a committed artifact");
            h.assertTrue(fixture.repository().status().bodyCount() == (admitted ? 1 : 0)
                            && fixture.repository().status().revision() == (admitted ? 1 : 0),
                    "Failed publication changed the authoritative catalog revision");
        }

        private void verifyReceipts(BootstrapController.View status, UUID receiptRun, int amount) {
            h.assertTrue(status.receipts().size() == amount * 3
                            && wheat(status, CropDelivery.Stage.HARVEST) == amount
                            && wheat(status, CropDelivery.Stage.PICKUP) == amount
                            && wheat(status, CropDelivery.Stage.DEPOSIT) == amount,
                    "Physical partial or fulfilled receipts are inaccurate");
            var bound = new ValidatedRequest(request, fixture.owner,
                    new ObservationRef(UUID.randomUUID(), h.getLevel().getGameTime(), fixture.dimension));
            for (var receipt : status.receipts())
                h.assertTrue(receipt.runId().equals(receiptRun) && receipt.actor().equals(fixture.actor)
                                && receipt.source().equals(((AreaValue)request.arguments().get("source")).value())
                                && receipt.destination().equals(((ContainerValue)request.arguments()
                                        .get("destination")).value()),
                        "Physical receipt escaped the trusted trial or execution bindings");
            h.assertTrue(CropDelivery.completed(bound, receiptRun, status.receipts())
                            == (amount == ((IntValue)request.arguments().get("amount")).value()),
                    "Pre-existing wheat satisfied completion without attributable work");
        }

        private void verifyStock(int delivered) {
            h.assertTrue(inventoryWheat() == 7 && chest().countItem(Items.WHEAT) == 20 + delivered
                            && droppedWheat() == 0 && matureCount() == 4 - delivered
                            && totalSeeds() >= 9 && totalSeeds() <= 9 + 4 * 64,
                    "Storage failure lost or duplicated physical work");
            h.assertTrue(inventoryWheat() + chest().countItem(Items.WHEAT) + droppedWheat()
                            == 27 + delivered, "Wheat mass changed without legitimate harvest");
        }

        private void freeze() {
            frozenWheat = inventoryWheat(); frozenChest = chest().countItem(Items.WHEAT);
            frozenDrops = droppedWheat(); frozenCrops = matureCount(); frozenSeeds = totalSeeds();
            quietUntil = h.getLevel().getGameTime() + 40;
        }

        private boolean markerCase() {
            return scenario == Case.TERMINAL_MARKER || scenario == Case.TERMINAL_DELAY
                    || scenario == Case.TERMINAL_TIMEOUT;
        }

        private boolean holdTerminalWrite(BootstrapController.View status) {
            if (storageExpired) return observeExpiredWrite(status);
            if (fixture.terminalWriteGate.isDone()) return false;
            if (fixture.delayedTerminalWrites == 0) return true;
            if (pendingTerminal == null) {
                h.assertTrue(status.phase() == BootstrapController.Phase.PERSISTING
                                && status.outcome() != null
                                && status.outcome().status() == ExecutionStatus.SUCCEEDED
                                && status.outcome().committedEffects() == 9
                                && status.marker().phase() == BootstrapJournal.Phase.ACTIVE
                                && status.marker().effects() == 0 && status.storageError() == null,
                        "Held terminal write hid live fulfillment: " + diagnostic());
                verifyReceipts(status, continuationId, 3); verifyStock(4);
                String described = KernelRunStatus.describe(status);
                h.assertTrue(described.contains("phase=PERSISTING")
                                && described.contains("outcome=SUCCEEDED reason=null")
                                && described.contains("effectsAtCheckpoint=0")
                                && described.contains(" effects=9") && described.contains("receipts=9"),
                        "Player status hid fulfillment while persistence was pending: " + described);
                writesBeforeDelay = fixture.journalWritesStarted.get();
                finishedBeforeDelay = fixture.journalWritesFinished.get();
                h.assertTrue(writesBeforeDelay == finishedBeforeDelay,
                        "A prior journal write was still running at the barrier");
                freeze(); delayedUntil = quietUntil;
                rejectPendingWork(); rejectForeignQueries();
                pendingTerminal = status;
                System.out.println((scenario == Case.TERMINAL_TIMEOUT
                        ? "IMP-007 expiring terminal persistence stage=held "
                        : "IMP-007 terminal persistence stage=held ") + diagnostic());
            }
            h.assertTrue(status.equals(pendingTerminal) && !fixture.controller.ready()
                            && fixture.delayedTerminalWrites == 1
                            && fixture.journalWritesStarted.get() == writesBeforeDelay
                            && fixture.journalWritesFinished.get() == finishedBeforeDelay
                            && fixture.journalWriteFailures.get() == 0
                            && fixture.durableState().runs().stream().anyMatch(marker ->
                                    marker.equals(status.marker()))
                            && inventoryWheat() == frozenWheat && chest().countItem(Items.WHEAT) == frozenChest
                            && droppedWheat() == frozenDrops && matureCount() == frozenCrops
                            && totalSeeds() == frozenSeeds && fixture.fakeCalls == 1
                            && fixture.gateway.activeRuns() == 0 && !AiVillages.controls(fixture.villager),
                    "Pending terminal write changed status, records, effects or custody: " + diagnostic());
            if (h.getLevel().getGameTime() < delayedUntil) return true;
            rejectPendingWork();
            if (scenario == Case.TERMINAL_TIMEOUT) {
                fixture.advanceStorageClock(BootstrapController.MAX_STORAGE_WAIT_MILLIS + 1);
                storageExpired = true;
                return true;
            }
            System.out.println("IMP-007 terminal persistence stage=released quietTicks=40 " + diagnostic());
            h.assertTrue(fixture.terminalWriteGate.complete(null), "Terminal gate released twice");
            return true;
        }

        private boolean observeExpiredWrite(BootstrapController.View status) {
            h.assertTrue(status.phase() == BootstrapController.Phase.TERMINAL
                            && status.storageError() == Reason.STORAGE_UNAVAILABLE
                            && status.outcome().equals(pendingTerminal.outcome())
                            && status.receipts().equals(pendingTerminal.receipts())
                            && status.marker().equals(pendingTerminal.marker())
                            && !fixture.controller.ready(),
                    "Expired storage wait lost fulfillment or revived work: " + diagnostic());
            verifyStock(4);
            if (!fixture.terminalWriteGate.isDone()) {
                pendingTerminal = status;
                rejectPendingWork(); rejectForeignQueries();
                h.assertTrue(fixture.terminalWriteGate.complete(null), "Late commit gate released twice");
                return true;
            }
            if (fixture.journalWritesFinished.get() != finishedBeforeDelay + 1) return true;
            h.assertTrue(fixture.journalWritesStarted.get() == writesBeforeDelay + 1
                            && fixture.journalWriteFailures.get() == 0
                            && fixture.durableState().runs().stream().anyMatch(marker ->
                                    marker.id().equals(continuationId)
                                            && marker.phase() == BootstrapJournal.Phase.TERMINAL
                                            && "SUCCEEDED".equals(marker.outcome()) && marker.effects() == 9),
                    "Late physical-result publication was not observed exactly once");
            if (!lateCommitObserved) {
                lateCommitObserved = true; freeze();
                System.out.println("IMP-007 storage timeout acceptance: maxWaitMillis="
                        + BootstrapController.MAX_STORAGE_WAIT_MILLIS
                        + " liveOutcome=SUCCEEDED liveEffects=9 checkpointEffects=0 receipts=9"
                        + " storageError=STORAGE_UNAVAILABLE lateCommitObserved=true"
                        + " nextWorkDenied=true modelCalls=0 " + diagnostic());
                return true;
            }
            stable(continuationId, pendingTerminal);
            h.assertTrue(fixture.gateway.activeRuns() == 0 && !AiVillages.controls(fixture.villager),
                    "Storage expiry retained actor custody");
            return h.getLevel().getGameTime() < quietUntil;
        }

        private void rejectPendingWork() {
            var rejected = fixture.controller.submit(request, fixture.owner);
            h.assertTrue(!rejected.accepted() && rejected.reason() == Reason.BUDGET_EXHAUSTED,
                    "A new request escaped pending terminal persistence");
            pendingBusyRejections++;
        }

        private void rejectForeignQueries() {
            var foreign = new TrustedContext(new PrincipalRef(UUID.randomUUID()), fixture.owner.scope());
            try {
                fixture.controller.status(continuationId, foreign);
                h.fail("Foreign status exposed a pending terminal run");
            } catch (SecurityException denied) { foreignPendingDenials++; }
            try {
                fixture.controller.cancel(continuationId, foreign);
                h.fail("Foreign cancellation controlled a pending terminal run");
            } catch (SecurityException denied) { foreignPendingDenials++; }
        }

        private String diagnostic() {
            UUID selected = continuationId != null ? continuationId : runId != null ? runId : fixture.runId;
            String status;
            if (fixture.controller == null || fixture.owner == null || selected == null)
                status = "run=" + selected + " controllerUnavailable=true";
            else if (phase == 4 && !fixture.controller.ready())
                status = "run=" + selected + " reconciliationPending=true "
                        + reopenedCheckpoint.runs().stream().filter(marker -> marker.id().equals(selected))
                                .map(marker -> "loadedCheckpointPhase=" + marker.phase()
                                        + " loadedCheckpointEffects=" + marker.effects())
                                .findFirst().orElse("checkpointUnavailable=true");
            else status = KernelRunStatus.describe(fixture.controller.status(selected, fixture.owner));
            String result = "phase=" + phase + " fixtureStage=" + fixture.stage + " testTick=" + stepTicks
                    + " gameTick=" + h.getLevel().getGameTime()
                    + " elapsedMs=" + (System.nanoTime() - startedNanos) / 1_000_000
                    + " writeRequests=" + fixture.storageWriteRequests
                    + " journalStarted=" + fixture.journalWritesStarted.get()
                    + " journalFinished=" + fixture.journalWritesFinished.get()
                    + " journalFailures=" + fixture.journalWriteFailures.get()
                    + " failedTerminalWrites=" + fixture.failedTerminalWrites
                    + " delayedTerminalWrites=" + fixture.delayedTerminalWrites
                    + " terminalGateReleased=" + fixture.terminalWriteGate.isDone()
                    + " openingDone=" + fixture.opening.isDone()
                    + " reopeningDone=" + (reopening != null && reopening.isDone()) + " " + status;
            return result.length() <= 1024 ? result : result.substring(0, 1024);
        }

        private void trace() {
            if (!Boolean.getBoolean("cognitivecraft.gametest.trace") || !markerCase() || traceLines == 64) return;
            if (phase == tracedPhase && fixture.stage == tracedStage && stepTicks - tracedTick < 100) return;
            tracedPhase = phase; tracedStage = fixture.stage; tracedTick = stepTicks; traceLines++;
            System.out.println("IMP-007 storage progress case=" + scenario + " " + diagnostic());
        }

        private void stable(UUID id, BootstrapController.View expected) {
            h.assertTrue(fixture.controller.status(id, fixture.owner).equals(expected)
                            && fixture.controller.cancel(id, fixture.owner).equals(expected),
                    "Terminal status/cancel changed after persistence failure or reload");
            h.assertTrue(inventoryWheat() == frozenWheat && chest().countItem(Items.WHEAT) == frozenChest
                            && droppedWheat() == frozenDrops && matureCount() == frozenCrops
                            && totalSeeds() == frozenSeeds && fixture.fakeCalls == 1
                            && fixture.gateway.activeRuns() == 0 && !AiVillages.controls(fixture.villager),
                    "Late ticks replayed work, invoked generation or retained custody");
        }

        private void finish() {
            System.out.println("IMP-007 storage acceptance case=" + scenario + " run=" + runId
                    + " ref=" + candidate.sha256() + " outcome=" + (terminal.outcome() != null
                            ? terminal.outcome().status() : terminal.research().status())
                    + " reason=" + (terminal.outcome() != null
                            ? terminal.outcome().reason() : terminal.research().reason())
                    + " checkpointOutcome=" + terminal.marker().outcome()
                    + " receipts=" + terminal.receipts().size()
                    + " storageError=" + terminal.storageError() + " reloaded=" + reloaded.marker().phase()
                    + " durableEffects=" + reloaded.marker().effects() + " catalog=" + fixture.repository().status()
                    + " fakeCalls=" + fixture.fakeCalls + " repositoryFaults=" + repositoryFaults.get()
                    + " markerFaults=" + fixture.failedTerminalWrites + " continuation=" + continuationId
                    + " continuationReceipts=" + (continued == null ? 0 : continued.receipts().size())
                    + " delayedTerminalWrites=" + fixture.delayedTerminalWrites
                    + " pendingBusyRejections=" + pendingBusyRejections
                    + " foreignPendingDenials=" + foreignPendingDenials
                    + " quietPendingWrite=" + (scenario == Case.TERMINAL_DELAY ? 40 : 0)
                    + " chestWheat=" + chest().countItem(Items.WHEAT) + " seedTotal=" + totalSeeds()
                    + " quietBeforeReload=40 quietAfterReload=40");
            closing = fixture.closeAcceptanceFixture(); phase = 7;
        }

        private void stock() {
            fixture.villager.getInventory().clearContent();
            fixture.villager.getInventory().addItem(new ItemStack(Items.WHEAT, 7));
            fixture.villager.getInventory().addItem(new ItemStack(Items.WHEAT_SEEDS, 9));
            chest().clearContent(); chest().setItem(0, new ItemStack(Items.WHEAT, 20));
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
        private long wheat(BootstrapController.View status, CropDelivery.Stage stage) {
            return status.receipts().stream().filter(receipt -> receipt.stage() == stage)
                    .mapToLong(CropDelivery.CropReceipt::wheat).sum();
        }
        private void maturePatch() {
            for (int x = FIRST.getX(); x <= LAST.getX(); x++) for (int z = FIRST.getZ(); z <= LAST.getZ(); z++) {
                BlockPos crop = new BlockPos(x, FIRST.getY(), z);
                h.setBlock(crop.below(), Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7));
                h.setBlock(crop, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
            }
        }
        private long matureCount() {
            long mature = 0;
            for (int x = FIRST.getX(); x <= LAST.getX(); x++) for (int z = FIRST.getZ(); z <= LAST.getZ(); z++) {
                var state = h.getBlockState(new BlockPos(x, FIRST.getY(), z));
                if (state.is(Blocks.WHEAT) && state.getValue(CropBlock.AGE) == 7) mature++;
            }
            return mature;
        }
    }
}
