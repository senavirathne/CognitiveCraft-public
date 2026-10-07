package dev.aivillages.fabric;

import dev.aivillages.core.kernel.BootstrapController;
import dev.aivillages.core.kernel.BootstrapJournal;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.CropDelivery;
import dev.aivillages.core.kernel.Outcomes.*;
import dev.aivillages.core.kernel.StrictJson;
import dev.aivillages.core.kernel.VersionedSkillRepository;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Physical reuse or safe refusal after reopening only disposable copies of authoritative stores. */
public final class BootstrapRecoveryGameTests {
    private static final BlockPos FIRST = new BlockPos(2, 1, 3), LAST = new BlockPos(3, 1, 4);
    private static final BlockPos CHEST = new BlockPos(4, 1, 4);
    private static final int MAX_FILES = 32, MAX_BYTES = 262_144;
    private enum Case { CACHE_DELETE, CORRUPT_BODY, FUTURE_BODY, FUTURE_MANIFEST, FUTURE_JOURNAL }

    @GameTest(maxTicks = 1800, padding = 16)
    public void authoritativeCopyReusesKnowledgeAfterCacheDeletion(GameTestHelper h) {
        run(h, Case.CACHE_DELETE);
    }
    @GameTest(maxTicks = 1800, padding = 16)
    public void corruptBodyIsQuarantinedWithoutPhysicalReplay(GameTestHelper h) {
        run(h, Case.CORRUPT_BODY);
    }
    @GameTest(maxTicks = 1800, padding = 16)
    public void futureBodyPreservesAdmissionWithoutExecution(GameTestHelper h) {
        run(h, Case.FUTURE_BODY);
    }
    @GameTest(maxTicks = 1800, padding = 16)
    public void futureManifestBlocksResearchWithoutDowngrade(GameTestHelper h) {
        run(h, Case.FUTURE_MANIFEST);
    }
    @GameTest(maxTicks = 1800, padding = 16)
    public void futureBootstrapJournalFencesWorkWithoutOverwritingOriginal(GameTestHelper h) {
        run(h, Case.FUTURE_JOURNAL);
    }

    private static void run(GameTestHelper h, Case scenario) {
        Scene scene = new Scene(h, scenario);
        h.succeedWhen(() -> {
            scene.step();
            h.assertTrue(scene.phase == 8 && scene.closing.isDone(),
                    "Waiting for recovery case=" + scenario + " phase=" + scene.phase);
            scene.closing.join();
        });
    }

    private static final class Scene {
        final GameTestHelper h;
        final Case scenario;
        final BootstrapGameTests.Run fixture;
        final AABB arena;
        int phase, rejectedJournalRequests, deletedDisposableFiles;
        UUID acquisitionId, requestId;
        ArtifactRef ref;
        VersionedSkillRepository.ArtifactView admitted;
        List<?> privateOrigins;
        BootstrapController.View acquisition, result, reloaded;
        Path original, copy;
        Map<String, String> originalFiles, preparedFiles;
        CompletableFuture<Void> reopening, closing;
        long quietUntil, frozenWheat, frozenChest, frozenDrops, frozenCrops, frozenSeeds;
        long observedSeeds, observedHarvests, seedYield;

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
            villager.setNoAi(true); // Stationary arena setup; actual gateway effects remain required.
            fixture = new BootstrapGameTests.Run(h, villager, null, null,
                    h.absolutePos(BlockPos.ZERO), null, true);
            fixture.retainFirstTerminal = true; // Preserve live acquisition receipts before reopening.
            BlockPos min = h.absolutePos(BlockPos.ZERO), max = h.absolutePos(new BlockPos(10, 5, 9));
            arena = new AABB(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
            stock();
        }

        void step() {
            if (phase == 8) return;
            if (phase != 2 && phase != 6) fixture.advance();
            if (phase == 0) {
                if (fixture.stage != 9) return;
                acquisitionId = fixture.runId;
                acquisition = fixture.controller.status(acquisitionId, fixture.owner);
                h.assertTrue(acquisition.research() != null
                                && acquisition.research().status() == ResearchStatus.ADMITTED,
                        "Physical acquisition was not admitted: " + acquisition);
                ref = acquisition.research().artifact();
                fixture.controller.inference(false);
                admitted = fixture.repository().resolve(ref);
                privateOrigins = fixture.repository().privateOrigins(ref, fixture.owner);
                h.assertTrue("ADMITTED".equals(acquisition.marker().outcome()) && admitted.usable()
                                && acquisition.receipts().size() == 12
                                && acquisition.marker().effects() == 12
                                && fixture.fakeCalls == 1 && !fixture.controller.inferenceEnabled()
                                && !privateOrigins.isEmpty(),
                        "Expected one physical fake acquisition and durable admission");
                verifyReceipts(acquisition, 4);
                h.assertTrue(inventoryWheat() == 7 && chest().countItem(Items.WHEAT) == 24
                                && matureCount() == 0 && droppedWheat() == 0,
                        "Acquisition did not physically deliver four wheat");
                freeze(); phase = 1;
                return;
            }
            if (phase == 1) {
                stable(acquisitionId, acquisition);
                if (h.getLevel().getGameTime() < quietUntil) return;
                // Explicit setup for a NEW request, outside all acquired-run receipts.
                h.getLevel().getEntitiesOfClass(ItemEntity.class, arena).forEach(ItemEntity::discard);
                maturePatch(); stock();
                BlockPos outside = h.absolutePos(new BlockPos(0, 1, 0));
                var drop = new ItemEntity(h.getLevel(), outside.getX() + 0.5, outside.getY(),
                        outside.getZ() + 0.5, new ItemStack(Items.WHEAT, 13));
                drop.setNoGravity(true); drop.setDeltaMovement(0, 0, 0);
                h.getLevel().addFreshEntity(drop);
                freeze();
                reopening = fixture.reopenAcceptanceStores(this::prepareCopy); phase = 2;
                return;
            }
            if (phase == 2) {
                if (!reopening.isDone()) return;
                reopening.join();
                fixture.resumeAcceptanceController();
                // A blocked catalog must not invite generation even when admission is enabled.
                fixture.controller.inference(scenario != Case.CACHE_DELETE);
                verifyCatalog();
                quietUntil = h.getLevel().getGameTime() + 40; phase = 3;
                return;
            }
            if (phase == 3) {
                stable(null, null);
                if (h.getLevel().getGameTime() < quietUntil) return;
                var submitted = fixture.controller.submit(fixture.request(1, FIRST, LAST, CHEST), fixture.owner);
                if (scenario == Case.FUTURE_JOURNAL) {
                    h.assertTrue(!submitted.accepted() && !fixture.controller.ready(),
                            "Read-only bootstrap recovery activated a new run");
                    rejectedJournalRequests++;
                    freeze(); phase = 5;
                } else {
                    h.assertTrue(submitted.accepted(), "New explicit recovery request was rejected: " + submitted);
                    requestId = submitted.id(); observedSeeds = totalSeeds(); phase = 4;
                }
                return;
            }
            if (phase == 4) {
                var status = fixture.controller.status(requestId, fixture.owner);
                trackSeeds(status);
                if (status.phase() != BootstrapController.Phase.TERMINAL) return;
                result = status;
                verifyResult();
                freeze(); phase = 5;
                return;
            }
            if (phase == 5) {
                stable(requestId, result);
                if (h.getLevel().getGameTime() < quietUntil) return;
                // Reopen the same mutated COPY again; no world reset or automatic request.
                reopening = fixture.reopenAcceptanceStores(); phase = 6;
                return;
            }
            if (phase == 6) {
                if (!reopening.isDone()) return;
                reopening.join(); fixture.resumeAcceptanceController();
                verifyCatalog();
                if (requestId != null) {
                    reloaded = fixture.controller.status(requestId, fixture.owner);
                    h.assertTrue(reloaded.marker().equals(result.marker()) && reloaded.receipts().isEmpty(),
                            "Second reopen lost the summary or invented physical receipts");
                }
                quietUntil = h.getLevel().getGameTime() + 40; phase = 7;
                return;
            }
            stable(requestId, reloaded);
            if (h.getLevel().getGameTime() < quietUntil) return;
            if (scenario == Case.FUTURE_JOURNAL) {
                h.assertTrue(!fixture.controller.submit(fixture.request(1, FIRST, LAST, CHEST),
                                fixture.owner).accepted() && !fixture.controller.ready(),
                        "Repeated unknown-journal reopen restored execution rights");
                rejectedJournalRequests++;
            }
            System.out.println("IMP-007 recovery acceptance case=" + scenario
                    + " actor=" + fixture.actor + " owner=" + fixture.owner
                    + " acquisition=" + acquisitionId + " run=" + requestId + " ref=" + ref.sha256()
                    + " result=" + (result == null ? "REFUSED" : result.marker().outcome())
                    + " reason=" + (result == null ? "READ_ONLY_JOURNAL" : result.marker().reason())
                    + " receipts=" + (result == null ? 0 : result.receipts().size())
                    + " reuseCalls=" + (result == null ? 0 : result.marker().modelCalls())
                    + " fakeCalls=" + fixture.fakeCalls + " deletedDisposableFiles=" + deletedDisposableFiles
                    + " rejectedJournalRequests=" + rejectedJournalRequests
                    + " chestWheat=" + chest().countItem(Items.WHEAT) + " actorWheat=" + inventoryWheat()
                    + " droppedWheat=" + droppedWheat() + " matureCrops=" + matureCount()
                    + " seedYield=" + seedYield + " seedTotal=" + totalSeeds()
                    + " catalog=" + fixture.repository().status()
                    + " journalReadOnly=" + fixture.acceptanceJournalReadOnly()
                    + " quietBeforeCopy=40 quietAfterCopy=40 quietAfterResult=40 quietAfterSecondOpen=40");
            closing = fixture.closeAcceptanceFixture(this::verifyFiles); phase = 8;
        }

        private void verifyCatalog() {
            var repository = fixture.repository();
            var view = repository.resolve(ref);
            if (scenario == Case.FUTURE_MANIFEST) {
                h.assertTrue(repository.status().readOnly() && repository.status().revision() == 0
                                && repository.status().recoveryState().equals("UNKNOWN_MANIFEST_SCHEMA")
                                && view == null, "Unknown manifest was activated or downgraded");
            } else {
                h.assertTrue(view != null && view.ref().equals(ref)
                                && view.admission().evidence().equals(admitted.admission().evidence())
                                && repository.privateOrigins(ref, fixture.owner).equals(privateOrigins),
                        "Recovery changed artifact identity or admission/provenance evidence");
                var foreign = new TrustedContext(new PrincipalRef(UUID.randomUUID()), fixture.owner.scope());
                h.assertTrue(repository.privateOrigins(ref, foreign).isEmpty(),
                        "Copied knowledge exposed private origin context");
                if (scenario == Case.CORRUPT_BODY) {
                    var events = repository.quarantineEvents(ref, fixture.owner);
                    h.assertTrue(!view.usable()
                                    && view.integrity() == VersionedSkillRepository.Integrity.CORRUPT
                                    && view.admission().status() == AdmissionStatus.QUARANTINED
                                    && repository.status().revision() == 2 && events.size() == 1
                                    && events.getFirst().source().equals("repository:integrity"),
                            "Corruption was executable or quarantine history grew on reopen");
                } else if (scenario == Case.FUTURE_BODY)
                    h.assertTrue(!view.usable()
                                    && view.integrity() == VersionedSkillRepository.Integrity.UNKNOWN_SCHEMA
                                    && view.admission().status() == AdmissionStatus.ADMITTED
                                    && repository.status().revision() == 1
                                    && repository.quarantineEvents(ref, fixture.owner).isEmpty(),
                            "Unknown body was executed or reclassified as corruption");
                else h.assertTrue(view.usable() && view.admission().status() == AdmissionStatus.ADMITTED
                                && repository.status().revision() == 1,
                        "Valid copied knowledge was lost");
            }
            if (scenario == Case.FUTURE_JOURNAL)
                h.assertTrue(fixture.acceptanceJournalReadOnly(), "Unknown journal became writable");
            else {
                h.assertTrue(!fixture.acceptanceJournalReadOnly()
                                && fixture.controller.enrollment().actor().equals(fixture.actor)
                                && fixture.controller.enrollment().owner().equals(fixture.owner),
                        "Authoritative copy lost enrollment or ownership");
                var restored = fixture.controller.status(acquisitionId, fixture.owner);
                h.assertTrue(restored.marker().equals(acquisition.marker()) && restored.receipts().isEmpty(),
                        "Prior acquisition was rewritten or replayed after copying");
            }
        }

        private void verifyResult() {
            Reason reason = switch (scenario) {
                case CACHE_DELETE -> null;
                case CORRUPT_BODY -> Reason.ARTIFACT_QUARANTINED;
                case FUTURE_BODY -> Reason.ARTIFACT_INCOMPATIBLE;
                case FUTURE_MANIFEST -> Reason.STORAGE_UNAVAILABLE;
                default -> throw new AssertionError("Journal must reject before run creation");
            };
            String outcome = scenario == Case.CACHE_DELETE ? "SUCCEEDED"
                    : scenario == Case.FUTURE_BODY ? "INCOMPATIBLE" : "BLOCKED";
            h.assertTrue(outcome.equals(result.marker().outcome()) && result.marker().reason() == reason
                            && result.marker().modelCalls() == 0 && result.research() == null
                            && fixture.fakeCalls == 1,
                    "Recovery invoked research or misreported its typed result: " + result);
            int delivered = scenario == Case.CACHE_DELETE ? 1 : 0;
            verifyReceipts(result, delivered);
            h.assertTrue(result.marker().effects() == delivered * 3
                            && inventoryWheat() == 7 && chest().countItem(Items.WHEAT) == 20 + delivered
                            && droppedWheat() == 13 && matureCount() == 4 - delivered
                            && totalSeeds() == 9 + seedYield,
                    "Recovery duplicated/lost stock or credited unrelated wheat");
            if (delivered == 1)
                h.assertTrue(ref.sha256().equals(result.marker().artifactSha256())
                                && !fixture.controller.inferenceEnabled(),
                        "Cache-free reuse changed identity or enabled inference");
            else h.assertTrue(result.routing().reason() == reason && result.receipts().isEmpty(),
                    "Invalid knowledge produced physical receipts");
        }

        private void verifyReceipts(BootstrapController.View view, int amount) {
            h.assertTrue(view.receipts().size() == amount * 3
                            && wheat(view, CropDelivery.Stage.HARVEST) == amount
                            && wheat(view, CropDelivery.Stage.PICKUP) == amount
                            && wheat(view, CropDelivery.Stage.DEPOSIT) == amount,
                    "Receipt totals do not prove actual delivery");
            var request = fixture.request(amount == 4 ? 4 : 1, FIRST, LAST, CHEST);
            var bound = new ValidatedRequest(request, fixture.owner,
                    new ObservationRef(UUID.randomUUID(), h.getLevel().getGameTime(), fixture.dimension));
            UUID receiptRun = amount == 4 ? view.receipts().getFirst().runId() : requestId;
            for (var receipt : view.receipts())
                h.assertTrue(receipt.runId().equals(receiptRun) && receipt.actor().equals(fixture.actor)
                                && receipt.source().equals(((AreaValue)request.arguments().get("source")).value())
                                && receipt.destination().equals(((ContainerValue)request.arguments()
                                        .get("destination")).value()),
                        "Receipt escaped its exact request bindings");
            h.assertTrue(CropDelivery.completed(bound, receiptRun, view.receipts()) == (amount > 0),
                    "Unrelated stock or reconstructed receipts satisfied completion");
        }

        private void trackSeeds(BootstrapController.View view) {
            long harvests = wheat(view, CropDelivery.Stage.HARVEST), seeds = totalSeeds();
            if (harvests > observedHarvests) {
                long delta = seeds - observedSeeds;
                h.assertTrue(delta >= 0 && delta <= 64 * (harvests - observedHarvests),
                        "Physical seed yield exceeds its envelope");
                seedYield += delta;
            } else h.assertTrue(seeds == observedSeeds, "Seed total changed without a new harvest");
            observedSeeds = seeds; observedHarvests = harvests;
        }

        private void freeze() {
            frozenWheat = inventoryWheat(); frozenChest = chest().countItem(Items.WHEAT);
            frozenDrops = droppedWheat(); frozenCrops = matureCount(); frozenSeeds = totalSeeds();
            quietUntil = h.getLevel().getGameTime() + 40;
        }
        private void stable(UUID id, BootstrapController.View expected) {
            if (id != null)
                h.assertTrue(fixture.controller.status(id, fixture.owner).equals(expected)
                                && fixture.controller.cancel(id, fixture.owner).equals(expected),
                        "Late work changed a terminal view");
            h.assertTrue(inventoryWheat() == frozenWheat && chest().countItem(Items.WHEAT) == frozenChest
                            && droppedWheat() == frozenDrops && matureCount() == frozenCrops
                            && totalSeeds() == frozenSeeds && fixture.fakeCalls == 1
                            && fixture.gateway.activeRuns() == 0 && !AiVillages.controls(fixture.villager),
                    "Store recovery replayed effects, changed physical stock or retained custody");
        }

        private Path prepareCopy(Path source) {
            try {
                original = source; originalFiles = snapshot(source);
                copy = Files.createTempDirectory("cc-bootstrap-recovery-" + scenario + "-");
                for (String relative : originalFiles.keySet()) {
                    Path target = copy.resolve(relative); Files.createDirectories(target.getParent());
                    Files.copy(source.resolve(relative), target);
                }
                Path skillRoot = copy.resolve(VersionedSkillRepository.WORLD_RELATIVE_PATH);
                Path body = skillRoot.resolve("bodies/" + ref.sha256() + ".json");
                Path manifest = skillRoot.resolve("manifest.json");
                switch (scenario) {
                    case CACHE_DELETE -> {
                        // Labeled disposable sentinels, not a production index implementation.
                        for (Path disposable : List.of(copy.resolve("cache/disposable.index"),
                                skillRoot.resolve("staging/disposable-candidate.json"))) {
                            Files.createDirectories(disposable.getParent());
                            Files.writeString(disposable, "disposable fixture data");
                            Files.delete(disposable); deletedDisposableFiles++;
                        }
                    }
                    case CORRUPT_BODY -> Files.writeString(body, "corrupt fixture body");
                    case FUTURE_BODY -> {
                        Map<String, Object> futureBody = StrictJson.object(Files.readString(body));
                        futureBody.put("schema", 2L);
                        String encoded = StrictJson.canonical(futureBody);
                        Files.writeString(body, encoded);
                        Map<String, Object> root = StrictJson.object(Files.readString(manifest));
                        List<?> entries = (List<?>) root.get("entries");
                        require(entries.size() == 1, "Expected one admitted body in copied manifest");
                        Map<String, Object> record = new LinkedHashMap<>();
                        ((Map<?, ?>) entries.getFirst()).forEach((key, value) -> record.put((String) key, value));
                        record.put("bodySha256", digest(encoded.getBytes(StandardCharsets.UTF_8)));
                        record.put("bodyBytes", (long) encoded.getBytes(StandardCharsets.UTF_8).length);
                        root.put("entries", List.of(record));
                        root.put("checksum", digest(StrictJson.canonical(Map.of("schema", root.get("schema"),
                                "revision", root.get("revision"), "entries", root.get("entries")))
                                .getBytes(StandardCharsets.UTF_8)));
                        Files.writeString(manifest, StrictJson.canonical(root));
                    }
                    case FUTURE_MANIFEST -> {
                        // A valid old backup must not downgrade a newer unknown manifest.
                        Files.copy(manifest, skillRoot.resolve("manifest.previous.json"),
                                StandardCopyOption.REPLACE_EXISTING);
                        Map<String, Object> future = StrictJson.object(Files.readString(manifest));
                        future.put("schema", 2L); future.put("futureRights", "preserve");
                        Files.writeString(manifest, StrictJson.canonical(future));
                    }
                    case FUTURE_JOURNAL -> {
                        Path journal = copy.resolve(BootstrapJournal.WORLD_RELATIVE_PATH).resolve("state.json");
                        Map<String, Object> future = StrictJson.object(Files.readString(journal));
                        future.put("schema", (long)BootstrapJournal.IDENTITY_REFERENCE_SCHEMA + 1);
                        future.put("futureControl", "preserve");
                        Files.writeString(journal, StrictJson.canonical(future));
                    }
                }
                preparedFiles = snapshot(copy);
                return copy;
            } catch (Exception failed) { throw new IllegalStateException("Preparing bounded recovery copy", failed); }
        }

        private void verifyFiles() {
            try {
                require(snapshot(original).equals(originalFiles), "Recovery modified the source stores");
                Map<String, String> after = snapshot(copy);
                String skills = VersionedSkillRepository.WORLD_RELATIVE_PATH + "/";
                String journal = BootstrapJournal.WORLD_RELATIVE_PATH + "/";
                for (String path : preparedFiles.keySet()) {
                    boolean unchanged = !path.startsWith(journal)
                            && (scenario != Case.CORRUPT_BODY || !path.endsWith("/manifest.json"))
                            || scenario == Case.FUTURE_JOURNAL;
                    if (unchanged) require(preparedFiles.get(path).equals(after.get(path)),
                            "Recovery overwrote protected file " + path);
                }
                require(after.containsKey(skills + "bodies/" + ref.sha256() + ".json"),
                        "Recovery discarded the immutable original");
                require(!Files.exists(copy.resolve("cache/disposable.index"))
                                && !Files.exists(copy.resolve(skills + "staging/disposable-candidate.json")),
                        "Recovery depended on or rebuilt a disposable sentinel");
                System.out.println("IMP-007 recovery disk case=" + scenario
                        + " sourceUnchanged=true protectedOriginals=true originalFiles=" + originalFiles.size()
                        + " copiedFiles=" + after.size() + " boundedFiles=" + MAX_FILES
                        + " boundedBytes=" + MAX_BYTES);
            } catch (Exception failed) { throw new IllegalStateException("Verifying recovery originals", failed); }
        }

        private static Map<String, String> snapshot(Path world) throws Exception {
            Map<String, String> result = new LinkedHashMap<>();
            int bytes = 0;
            try (var paths = Files.walk(world.resolve("data"))) {
                List<Path> bounded = paths.limit(MAX_FILES * 4L + 1).toList();
                require(bounded.size() <= MAX_FILES * 4, "Fixture traversal exceeded its bound");
                for (Path path : bounded) {
                    require(!Files.isSymbolicLink(path), "Fixture path is symbolic");
                    if (!Files.isRegularFile(path) || path.getFileName().toString().endsWith(".lock")
                            || path.toString().contains("/staging/")) continue;
                    require(Files.size(path) <= 65_536, "Fixture file exceeds the read bound");
                    byte[] content = Files.readAllBytes(path);
                    bytes = Math.addExact(bytes, content.length);
                    require(content.length <= 65_536 && bytes <= MAX_BYTES && result.size() < MAX_FILES,
                            "Fixture snapshot exceeded its finite envelope");
                    result.put(world.relativize(path).toString(), digest(content));
                }
            }
            return Map.copyOf(result);
        }
        private static String digest(byte[] bytes) throws Exception {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        private static void require(boolean condition, String message) throws IOException {
            if (!condition) throw new IOException(message);
        }

        private void stock() {
            fixture.villager.getInventory().clearContent();
            fixture.villager.getInventory().addItem(new ItemStack(Items.WHEAT, 7));
            fixture.villager.getInventory().addItem(new ItemStack(Items.WHEAT_SEEDS, 9));
            chest().clearContent(); chest().setItem(0, new ItemStack(Items.WHEAT, 20));
        }
        private Container chest() { return (Container) h.getLevel().getBlockEntity(h.absolutePos(CHEST)); }
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
        private static long wheat(BootstrapController.View status, CropDelivery.Stage stage) {
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
            long count = 0;
            for (int x = FIRST.getX(); x <= LAST.getX(); x++) for (int z = FIRST.getZ(); z <= LAST.getZ(); z++) {
                var state = h.getBlockState(new BlockPos(x, FIRST.getY(), z));
                if (state.is(Blocks.WHEAT) && state.getValue(CropBlock.AGE) == 7) count++;
            }
            return count;
        }
    }
}
