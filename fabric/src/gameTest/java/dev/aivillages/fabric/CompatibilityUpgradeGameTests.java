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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** GP-15: real legacy owners, production startup, loaded actor/chest and a separate JVM. */
public final class CompatibilityUpgradeGameTests {
    static final String MARKER = "compatibility-restart.json";

    @GameTest(maxTicks = 1800, padding = 32)
    public void loadedLegacyWorldMigratesWithoutChangingRightsOrReplayingEffects(GameTestHelper h) {
        run(h, false);
    }

    static void run(GameTestHelper h, boolean warm) {
        h.assertTrue((warm ? "warm" : "cold").equals(System.getenv("COGNITIVECRAFT_COMPATIBILITY_RESTART")),
                "Compatibility fixture requires its explicit server process phase");
        var driver = new Driver(h, warm);
        h.succeedWhen(() -> {
            driver.step();
            h.assertTrue(driver.phase == 5, "Waiting for compatibility phase=" + driver.phase);
        });
    }

    private static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private static Path bootstrap(Path root) {
        return root.resolve(BootstrapJournal.WORLD_RELATIVE_PATH).resolve("state.json");
    }
    private static final class Driver {
        final GameTestHelper h;
        final boolean warm;
        final Path root;
        final CompletableFuture<Map<String, Object>> proof;
        Map<String, Object> identity;
        CompletableFuture<Void> closing, verified;
        KernelSession session;
        ServerPlayer owner, foreign;
        Villager villager;
        BlockPos chest;
        ActorRef actor;
        UUID runId;
        int phase, generationCalls;

        Driver(GameTestHelper h, boolean warm) {
            this.h = h; this.warm = warm;
            root = h.getLevel().getServer().getWorldPath(LevelResource.ROOT)
                    .resolve("cognitivecraft-compatibility-upgrade");
            if (warm) {
                proof = CompletableFuture.supplyAsync(() -> {
                    try { return StrictJson.object(Files.readString(root.resolve(MARKER))); }
                    catch (Exception failure) { throw new IllegalStateException("Cold compatibility proof missing", failure); }
                });
                return;
            }
            BlockPos offset = new BlockPos(8, 0, 8);
            BlockPos origin = h.absolutePos(offset);
            BootstrapGameTests.forceFixtureChunks(h, origin);
            for (int x = 0; x < 6; x++) for (int z = 0; z < 6; z++)
                h.setBlock(offset.offset(x, 0, z), Blocks.STONE);
            h.setBlock(offset.offset(4, 1, 4), Blocks.CHEST);
            chest = h.absolutePos(offset.offset(4, 1, 4));
            ((Container) h.getLevel().getBlockEntity(chest)).setItem(0, new ItemStack(Items.WHEAT, 3));
            villager = h.spawnWithNoFreeWill(EntityTypes.VILLAGER, offset.getX() + 2, 1, offset.getZ() + 2);
            villager.setNoAi(true); villager.setPersistenceRequired();
            owner = h.makeMockServerPlayerInLevel();
            owner.setPos(villager.getX(), villager.getY(), villager.getZ());
            actor = new ActorRef(UUID.randomUUID(), villager.getUUID(), "minecraft:overworld");
            runId = UUID.randomUUID();
            // These are synthetic historical counters, not claims of behavioral admission or real old effects.
            Map<String, Object> captured = Map.of("citizen", actor.citizenId().toString(),
                    "entity", actor.entityId().toString(), "principal", owner.getUUID().toString(),
                    "run", runId.toString(), "position", List.of((long) origin.getX() + 2,
                            (long) origin.getY() + 1, (long) origin.getZ() + 2),
                    "chest", List.of((long) chest.getX(), (long) chest.getY(), (long) chest.getZ()));
            ActorRef savedActor = actor; UUID savedRun = runId;
            proof = CompletableFuture.supplyAsync(() -> {
                try (var journal = BootstrapJournal.open(root)) {
                    UUID principal = UUID.fromString((String) captured.get("principal"));
                    var control = new TrustedContext(new PrincipalRef(principal),
                            new ScopeRef(journal.state().worldId(), principal));
                    var enrollment = new BootstrapJournal.Enrollment(savedActor, control);
                    journal.replace(journal.state(), enrollment, List.of(new BootstrapJournal.RunMarker(
                            savedRun, savedActor.citizenId(), BootstrapJournal.Phase.ACTIVE,
                            null, null, 3, 0, "a".repeat(64))));
                    var values = new TreeMap<String, Object>(captured);
                    values.put("world", journal.state().worldId().toString());
                    values.put("originalSha256", hash(bootstrap(root)));
                    values.put("coldProcess", ProcessHandle.current().pid());
                    return Map.copyOf(values);
                } catch (Exception failure) { throw new IllegalStateException("Legacy fixture unavailable", failure); }
            });
        }

        @SuppressWarnings("unchecked") private BlockPos position(String field) {
            var p = (List<Long>) identity.get(field);
            return new BlockPos(Math.toIntExact(p.get(0)), Math.toIntExact(p.get(1)), Math.toIntExact(p.get(2)));
        }
        void step() {
            if (phase == 5) return;
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            if (session != null && phase < 3) session.tick();
            switch (phase) {
                case 0 -> {
                    h.assertTrue(proof.isDone(), "Reading/writing only on a fixture worker");
                    identity = proof.join();
                    if (warm) {
                        h.assertTrue((Long) identity.get("coldProcess") != ProcessHandle.current().pid(),
                                "Warm compatibility fixture must use a separate actual JVM");
                        BlockPos saved = position("position");
                        BootstrapGameTests.forceFixtureChunks(h, saved);
                        h.getLevel().getChunk(saved.getX() >> 4, saved.getZ() >> 4);
                        var entity = h.getLevel().getEntity(UUID.fromString((String) identity.get("entity")));
                        h.assertTrue(entity instanceof Villager, "Saved actor not loaded yet");
                        villager = (Villager) entity;
                        owner = h.makeMockServerPlayerInLevel();
                        owner.setUUID(UUID.fromString((String) identity.get("principal")));
                        owner.setPos(villager.getX(), villager.getY(), villager.getZ());
                        actor = new ActorRef(UUID.fromString((String) identity.get("citizen")), villager.getUUID(), "minecraft:overworld");
                        runId = UUID.fromString((String) identity.get("run")); chest = position("chest");
                    }
                    foreign = h.makeMockServerPlayerInLevel(); foreign.setUUID(UUID.randomUUID());
                    GenerationPort offline = new GenerationPort() {
                        @Override public Generation.Handle generate(Generation.Request request, Budgets.InferenceLimits limits, Budgets.Ledger ledger) {
                            generationCalls++; throw new AssertionError("Compatibility invoked a model");
                        }
                        @Override public Generation.Descriptor descriptor() {
                            return new Generation.Descriptor("compatibility-offline-v1", "unavailable-test-backend", null);
                        }
                        @Override public Generation.Status status() {
                            return new Generation.Status(Generation.State.UNAVAILABLE, 0, null, Generation.Compute.NOT_STARTED, false, false);
                        }
                    };
                    session = new KernelSession(h.getLevel().getServer(), root, null, offline, PrimitiveDiagnostics.noop());
                    phase = 1;
                }
                case 1 -> {
                    h.assertTrue(session.identityReady(), "Waiting for production startup migration");
                    session.inference(false);
                    var address = session.address(owner, actor.citizenId());
                    h.assertTrue(address.status() == CitizenRegistry.AddressStatus.FOUND
                            && address.candidates().getFirst().actor().equals(actor), "Migration changed citizen/entity binding");
                    var citizen = session.citizen(owner, actor.citizenId());
                    h.assertTrue(citizen.owner().principal().id().equals(owner.getUUID())
                            && citizen.owner().scope().equals(new ScopeRef(UUID.fromString((String) identity.get("world")), owner.getUUID())),
                            "Migration changed immutable control scope");
                    h.assertTrue(session.address(foreign, actor.citizenId()).status() == CitizenRegistry.AddressStatus.NOT_FOUND,
                            "Foreign principal observed private identity");
                    boolean denied = false;
                    try { session.status(foreign, runId); }
                    catch (SecurityException expected) { denied = true; }
                    h.assertTrue(denied, "Foreign principal observed private run history");
                    phase = 2;
                }
                case 2 -> {
                    var view = session.status(owner, runId);
                    h.assertTrue(view != null && view.marker().phase() == BootstrapJournal.Phase.INTERRUPTED,
                            "Waiting for owner to fence uncertain legacy run");
                    h.assertTrue(view.marker().effects() == 3 && view.marker().modelCalls() == 0
                            && view.marker().artifactSha256().equals("a".repeat(64)), "Legacy history changed");
                    h.assertTrue(((Container) h.getLevel().getBlockEntity(chest)).countItem(Items.WHEAT) == 3
                            && generationCalls == 0, "Migration replayed a world effect or invoked inference");
                    session.close(); closing = session.storeClosure(); phase = 3;
                }
                case 3 -> {
                    h.assertTrue(closing.isDone() && !closing.isCompletedExceptionally(), "Waiting for owner cleanup");
                    verified = CompletableFuture.runAsync(() -> {
                        try (var journal = BootstrapJournal.open(root);
                             var citizens = CitizenIdentityStore.open(root, journal.state().worldId())) {
                            var before = PrimitiveDiagnosticRestartGameTests.hashes(root);
                            var report = SaveCompatibility.migrateAtStartup(journal, citizens);
                            if (report.status() != SaveCompatibility.Status.NO_OP
                                    || !before.equals(PrimitiveDiagnosticRestartGameTests.hashes(root))
                                    || citizens.snapshot().citizens().size() != 1
                                    || !journal.state().externalIdentities()
                                    || journal.state().runs().getFirst().phase() != BootstrapJournal.Phase.INTERRUPTED
                                    || !hash(bootstrap(root).resolveSibling("state.legacy-v1.json")).equals(identity.get("originalSha256")))
                                throw new AssertionError("Actual migrated stores changed on repeated handoff");
                            if (!warm) Files.writeString(root.resolve(MARKER), StrictJson.canonical(identity));
                        } catch (Exception failure) { throw new IllegalStateException("Compatibility owner proof unavailable", failure); }
                    });
                    phase = 4;
                }
                case 4 -> {
                    h.assertTrue(verified.isDone(), "Verifying recoverable owner bytes off-thread"); verified.join();
                    System.out.println("IMP-015 loaded-world compatibility accepted phase=" + (warm ? "warm" : "cold")
                            + " source=1 target=2 identities=1 rightsUnchanged=true exactOriginal=true"
                            + " uncertainRun=INTERRUPTED historicalEffects=3 physicalWheat=3 replayedEffects=0 modelCalls=0 repeated=NO_OP");
                    phase = 5;
                }
                default -> throw new IllegalStateException("Compatibility phase");
            }
        }
    }
}
