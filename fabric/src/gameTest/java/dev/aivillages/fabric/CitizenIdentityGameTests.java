package dev.aivillages.fabric;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.Outcomes.Reason;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.commands.Commands.literal;

/** GP-09 uses real identity storage, command handlers, controller and physical effects in two JVMs. */
public final class CitizenIdentityGameTests {
    private static final BlockPos OFFSET = new BlockPos(8, 0, 8);
    private static final BlockPos FIRST = new BlockPos(2, 1, 3), LAST = new BlockPos(3, 1, 4);
    private static final BlockPos CHEST_A = new BlockPos(4, 1, 4), CHEST_B = new BlockPos(7, 1, 0);
    private static final String ROOT = "ccidentityfixture";
    private record Proof(ActorRef first, ActorRef second, ActorRef other, TrustedContext owner,
                         TrustedContext foreign, UUID acquired, UUID namedRun, UUID secondRun,
                         UUID otherRun, UUID cancelled, ArtifactRef artifact, BlockPos origin,
                         String legacySha256, long registryRevision, long process) { }

    @GameTest(maxTicks = 1800, padding = 32)
    public void namedCitizensPreserveScopedIdentityAndOfflineTasksAfterServerReload(GameTestHelper h) {
        String phase = System.getenv("COGNITIVECRAFT_IDENTITY_RESTART");
        h.assertTrue("cold".equals(phase) || "warm".equals(phase),
                "Identity fixture needs an explicit cold or warm server process");
        var driver = new Driver(h, "warm".equals(phase));
        h.failIfEver(() -> { if (driver.fatal != null) h.fail(driver.fatal); });
        h.succeedWhen(() -> {
            driver.step();
            h.assertTrue(driver.phase == 30, "Waiting for identity phase=" + driver.phase);
        });
    }

    /** GP-13 reuses the real scoped command/citizen fixture, adding live job cancellation and uncertain restart. */
    static void runJobs(GameTestHelper h, boolean warm) {
        var driver = new Driver(h, warm, true);
        h.failIfEver(() -> { if (driver.fatal != null) h.fail(driver.fatal); });
        h.succeedWhen(() -> { driver.step(); h.assertTrue(driver.phase == 30, "Waiting for job phase=" + driver.phase); });
    }

    private static final class Driver {
        final GameTestHelper h;
        final boolean warm, jobsMode;
        final Path world;
        final CompletableFuture<Proof> metadata;
        final long startedTick;
        final long startedNanos = System.nanoTime();
        BootstrapGameTests.Run fixture;
        KernelSession session;
        ServerPlayer owner, other;
        CommandSourceStack ownerSource, otherSource;
        Villager first, second, foreign;
        ActorRef actor, actor2, actorB;
        TrustedContext ownerContext, foreignContext;
        ArtifactRef artifact;
        UUID acquired, namedRun, secondRun, otherRun, cancelled, active;
        BlockPos origin;
        Proof proof;
        CompletableFuture<Void> closing;
        CompletableFuture<String> archiveDigest;
        long quietUntil;
        long peakHeapBytes, maxKernelTickNanos;
        int phase, lastReported = -1;
        String fatal;
        UUID partialCancelled, uncertainRun;
        long jobQuietUntil;
        Map<String,Object> jobProof;
        CompletableFuture<Map<String,Object>> openingJobProof;
        boolean waitingBindings;

        Driver(GameTestHelper h, boolean warm) { this(h, warm, false); }
        Driver(GameTestHelper h, boolean warm, boolean jobsMode) {
            this.h = h; this.warm = warm; this.jobsMode = jobsMode;
            startedTick = h.getLevel().getGameTime();
            world = h.getLevel().getServer().getWorldPath(LevelResource.ROOT)
                    .resolve("cognitivecraft-citizen-identity-fixture");
            metadata = warm ? CompletableFuture.supplyAsync(() -> readProof(world)) : null;
            if (warm && jobsMode) openingJobProof = CompletableFuture.supplyAsync(() -> {
                try { return StrictJson.object(Files.readString(world.resolve("job-restart.json"))); }
                catch (Exception e) { throw new IllegalStateException(e); }
            });
            if (warm) { phase = 20; return; }
            origin = h.absolutePos(OFFSET);
            BootstrapGameTests.forceFixtureChunks(h, origin);
            for (int x = 0; x < 10; x++) for (int z = 0; z < 9; z++)
                h.setBlock(OFFSET.offset(x, 0, z), Blocks.STONE);
            for (int x = 2; x <= 3; x++) for (int z = 3; z <= 4; z++) mature(x, z);
            for (int x = 6; x <= 7; x++) for (int z = 1; z <= 3; z++) mature(x, z);
            h.setBlock(OFFSET.offset(CHEST_A), Blocks.CHEST);
            h.setBlock(OFFSET.offset(CHEST_B), Blocks.CHEST);
            for (int x = 1; x <= 4; x++) for (int z = 2; z <= 5; z++)
                if ((x == 1 || x == 4 || z == 2 || z == 5) && !(x == 4 && z == 4))
                    h.setBlock(OFFSET.offset(x, 1, z), Blocks.STONE);
            for (int x = 5; x <= 8; x++) for (int z = 0; z <= 4; z++)
                if ((x == 5 || x == 8 || z == 0 || z == 4) && !(x == 7 && z == 0))
                    h.setBlock(OFFSET.offset(x, 1, z), Blocks.STONE);
            first = spawn(3, 3);
            fixture = new BootstrapGameTests.Run(h, first, null, world, origin, null, true);
        }

        void mature(int x, int z) {
            h.setBlock(OFFSET.offset(x, 0, z), Blocks.FARMLAND.defaultBlockState()
                    .setValue(FarmlandBlock.MOISTURE, 7));
            h.setBlock(OFFSET.offset(x, 1, z), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        }
        Villager spawn(int x, int z) {
            var villager = h.spawnWithNoFreeWill(EntityTypes.VILLAGER,
                    OFFSET.getX() + x, 1, OFFSET.getZ() + z);
            villager.setNoAi(true); villager.setPersistenceRequired();
            return villager;
        }

        void step() {
            if (phase == 30) return;
            // Normal 20 TPS for asynchronous world/chunk setup; work keeps the existing tick budgets.
            java.util.concurrent.locks.LockSupport.parkNanos(
                    phase == 0 && fixture.stage <= 2 || phase == 20 || waitingBindings
                            ? 50_000_000 : 1_000_000);
            if (phase != lastReported) {
                System.out.println("IMP-008 identity phase=" + phase + " warm=" + warm
                        + " elapsedTicks=" + (h.getLevel().getGameTime() - startedTick));
                lastReported = phase;
            }
            if (session != null && phase < 28) {
                long before = System.nanoTime(); session.tick();
                maxKernelTickNanos = Math.max(maxKernelTickNanos, System.nanoTime() - before);
                var runtime = Runtime.getRuntime();
                peakHeapBytes = Math.max(peakHeapBytes, runtime.totalMemory() - runtime.freeMemory());
            }
            switch (phase) {
                case 0 -> {
                    fixture.advance();
                    h.assertTrue(fixture.stage == 9, "Physically acquiring setup method with one fake candidate");
                    require(fixture.fakeCalls == 1 && fixture.admitted != null, "Missing admitted setup method");
                    actor = fixture.actor; ownerContext = fixture.owner;
                    artifact = fixture.admitted; acquired = fixture.runId;
                    closing = fixture.closeAcceptanceFixture(); phase = 1;
                }
                case 1 -> {
                    await(closing, "Closing legacy owners before identity handoff");
                    session = new KernelSession(h.getLevel().getServer(), world);
                    players(ownerContext.principal().id(), UUID.randomUUID());
                    register(); phase = 2;
                }
                case 2 -> {
                    identityReady();
                    require(session.citizen(owner, actor.citizenId()).actor().equals(actor)
                            && session.citizen(owner, actor.citizenId()).owner().equals(ownerContext),
                            "Migration changed the actor binding or control scope");
                    terminal(owner, acquired, "ADMITTED", 4, true);
                    require(command(ownerSource, "name " + actor.citizenId() + " Ada") == 1,
                            "Actual name command rejected Ada");
                    archiveDigest = CompletableFuture.supplyAsync(() -> hash(legacy(world)));
                    phase = 3;
                }
                case 3 -> {
                    identityReady();
                    require("Ada".equals(session.citizen(owner, actor.citizenId()).displayName()), "Ada not durable");
                    require(session.address(owner, "ada").status() == CitizenRegistry.AddressStatus.FOUND,
                            "Unique normalized address not found");
                    first.setPos(origin.getX() + 7.5, origin.getY() + 1, origin.getZ() + 1.5);
                    active = submit(owner, actor, 6, 1); namedRun = active; phase = 4;
                }
                case 4 -> {
                    terminal(owner, active, "SUCCEEDED", 1, false);
                    park(first, 3, 3);
                    identityReady();
                    require(command(ownerSource, "name " + actor.citizenId() + " Mira") == 1,
                            "Actual rename command rejected Mira");
                    phase = 5;
                }
                case 5 -> {
                    identityReady();
                    second = spawn(7, 2); position(owner, second);
                    actor2 = session.enroll(owner, second); phase = 6;
                    park(owner);
                }
                case 6 -> {
                    identityReady();
                    require(command(ownerSource, "name " + actor2.citizenId() + " Mira") == 1,
                            "Second citizen name rejected"); phase = 7;
                }
                case 7 -> {
                    identityReady(); duplicateAndPrivacy(false);
                    active = submit(owner, actor2, 6, 2); secondRun = active; phase = 8;
                }
                case 8 -> {
                    terminal(owner, active, "SUCCEEDED", 1, false); identityReady();
                    park(second, 2, 4);
                    foreign = spawn(7, 2); position(other, foreign);
                    actorB = session.enroll(other, foreign); phase = 9;
                    park(other);
                }
                case 9 -> {
                    identityReady(); foreignContext = session.citizen(other, actorB.citizenId()).owner();
                    require(command(otherSource, "name " + actorB.citizenId() + " Mira") == 1,
                            "Other principal cannot name its own citizen"); phase = 10;
                }
                case 10 -> {
                    identityReady(); duplicateAndPrivacy(true);
                    active = submit(other, actorB, 6, 3); otherRun = active; phase = 11;
                }
                case 11 -> {
                    terminal(other, active, "SUCCEEDED", 1, false); identityReady();
                    require(stock(CHEST_B) == 3, "Three serial citizens did not deliver real wheat");
                    active = submit(owner, actor, 7, 3); cancelled = active;
                    require(command(ownerSource, "cancel " + cancelled) == 1, "Cancellation command rejected");
                    phase = 12;
                }
                case 12 -> {
                    terminal(owner, cancelled, "CANCELLED", 0, false); identityReady();
                    require(command(ownerSource, "name " + actor.citizenId() + " Mira II") == 1,
                            "Rename after cancellation rejected"); phase = 13;
                }
                case 13 -> {
                    identityReady();
                    require(command(ownerSource, "name " + actor.citizenId() + " Mira") == 1,
                            "Final duplicate name rejected"); phase = 14;
                }
                case 14 -> {
                    identityReady();
                    session.entityUnloaded(first); session.tick();
                    require(session.citizen(owner, actor.citizenId()).availability()
                            == CitizenRegistry.Availability.UNLOADED, "Unload fact deleted or reclassified identity");
                    require(session.citizen(owner, actor.citizenId()).actor().equals(actor), "Unload changed binding");
                    session.entityLoaded(first); quietUntil = h.getLevel().getGameTime() + 40; phase = 15;
                }
                case 15 -> {
                    h.assertTrue(h.getLevel().getGameTime() >= quietUntil, "Checking rename cannot revive cancelled work");
                    identityReady(); duplicateAndPrivacy(true);
                    terminal(owner, cancelled, "CANCELLED", 0, false);
                    require(stock(CHEST_A) == 4 && stock(CHEST_B) == 3
                                    && h.getLevel().getBlockState(origin.offset(7, 1, 3)).is(Blocks.WHEAT),
                            "Cancellation/rename changed physical outcomes");
                    require(session.citizen(owner, actor.citizenId()).availability()
                            == CitizenRegistry.Availability.LOADED, "Reload fact lost identity");
                    await(archiveDigest, "Hashing immutable legacy source");
                    proof = new Proof(actor, actor2, actorB, ownerContext, foreignContext, acquired,
                            namedRun, secondRun, otherRun, cancelled, artifact, origin, archiveDigest.join(),
                            0, ProcessHandle.current().pid());
                    if (jobsMode) {
                        plantJobSource(); park(first,7,1); park(second,2,4); park(foreign,2,3);
                        active = submitJob(); partialCancelled = active; phase = 16;
                    } else { session.close(); closing = session.storeClosure(); phase = 28; }
                }
                case 16 -> {
                    h.assertTrue(stock(CHEST_B) >= 5, "Waiting for real delivery 2/5 before cancellation");
                    require(stock(CHEST_B) == 5, "Partial cancellation fixture exceeded two deposits");
                    require(session.job(active,ownerContext).orElseThrow().state() == Jobs.State.ACTIVE,
                            "Attempt stopped before durable cancellation");
                    require(command(ownerSource,"cancel " + active) == 1,"Physical partial cancel rejected");
                    phase = 17;
                }
                case 17 -> {
                    var status = session.status(owner,active);
                    h.assertTrue(status.phase() == BootstrapController.Phase.TERMINAL,"Waiting for observed cessation");
                    var job = session.job(active,ownerContext).orElseThrow();
                    require(job.state() == Jobs.State.CANCELLED && job.fulfilled() == 2 && job.effects() >= 6
                            && !job.current().open(),"Partial cancellation lost attributable output or stopped-owner evidence");
                    require(stock(CHEST_B) == 5,"Cancelled execution kept depositing");
                    require(session.job(active,foreignContext).isEmpty(),"Guessed job leaked across scopes");
                    jobQuietUntil = h.getLevel().getGameTime() + 40; phase = 18;
                }
                case 18 -> {
                    h.assertTrue(h.getLevel().getGameTime() >= jobQuietUntil,"Checking cancelled work stays stopped");
                    require(stock(CHEST_B) == 5,"Cancelled work resumed");
                    plantJobSource(); active = submitJob(); uncertainRun = active; phase = 19;
                }
                case 19 -> {
                    h.assertTrue(stock(CHEST_B) >= 7,"Waiting for physical effects newer than job metadata");
                    require(stock(CHEST_B) == 7,"Mismatch fixture exceeded two deposits");
                    var job = session.job(active,ownerContext).orElseThrow();
                    require(job.state() == Jobs.State.ACTIVE && job.fulfilled() == 0 && job.current().receipts().isEmpty(),
                            "Expected committed effects with no terminal metadata acknowledgement");
                    require(session.jobRoots().artifacts().contains(artifact),"Missing live artifact root");
                    jobProof = Map.of("schema",1L,"cancelled",partialCancelled.toString(),"uncertain",uncertainRun.toString(),
                            "job",job.id().toString(),"artifact",artifact.sha256(),"delivered",7L,"process",ProcessHandle.current().pid());
                    session.close(); closing = session.storeClosure(); phase = 28;
                }
                case 20 -> {
                    await(metadata, "Reading cold process identity metadata"); proof = metadata.join();
                    require(proof.process() != ProcessHandle.current().pid(), "Warm proof reused a Minecraft JVM");
                    origin = proof.origin(); BootstrapGameTests.forceFixtureChunks(h, origin);
                    first = loaded(proof.first()); second = loaded(proof.second()); foreign = loaded(proof.other());
                    actor = proof.first(); actor2 = proof.second(); actorB = proof.other();
                    ownerContext = proof.owner(); foreignContext = proof.foreign(); artifact = proof.artifact();
                    acquired = proof.acquired(); namedRun = proof.namedRun(); secondRun = proof.secondRun();
                    otherRun = proof.otherRun(); cancelled = proof.cancelled();
                    players(ownerContext.principal().id(), foreignContext.principal().id()); register();
                    session = new KernelSession(h.getLevel().getServer(), world); phase = 21;
                }
                case 21 -> {
                    identityReady();
                    if (jobsMode) h.assertTrue(session.jobsReady(),"Waiting for durable job interruption publication");
                    duplicateAndPrivacy(true);
                    require(session.citizen(owner, actor.citizenId()).owner().equals(ownerContext)
                            && session.citizen(other, actorB.citizenId()).owner().equals(foreignContext),
                            "Saved owner scope changed");
                    terminal(owner, acquired, "ADMITTED", 0, true);
                    terminal(owner, namedRun, "SUCCEEDED", 0, false);
                    terminal(owner, secondRun, "SUCCEEDED", 0, false);
                    terminal(other, otherRun, "SUCCEEDED", 0, false);
                    terminal(owner, cancelled, "CANCELLED", 0, false);
                    require(stock(CHEST_A) == 4 && stock(CHEST_B) == (jobsMode ? 7 : 3),
                            "Cold physical stock did not survive Minecraft save/reload");
                    require(h.getLevel().getBlockState(origin.offset(FIRST)).isAir()
                            && h.getLevel().getBlockState(origin.offset(LAST)).isAir(), "Cold source reset on reload");
                    require(session.catalog(owner).contains("inference=false"), "Reload enabled inference");
                    if (jobsMode) {
                        await(openingJobProof,"Reading durable job mismatch proof"); jobProof=openingJobProof.join();
                        require(((Number)jobProof.get("process")).longValue()!=ProcessHandle.current().pid(),"Jobs restart reused a JVM");
                        uncertainRun=UUID.fromString((String)jobProof.get("uncertain"));
                        partialCancelled=UUID.fromString((String)jobProof.get("cancelled"));
                        var interrupted=session.job(uncertainRun,ownerContext).orElseThrow();
                        require(interrupted.state()==Jobs.State.INTERRUPTED && interrupted.current().uncertain()
                                && interrupted.fulfilled()==0 && interrupted.current().receipts().isEmpty(),
                                "Reload inferred output from final inventory or released an uncertain reservation");
                        var cancelledJob=session.job(partialCancelled,ownerContext).orElseThrow();
                        require(cancelledJob.state()==Jobs.State.CANCELLED && cancelledJob.fulfilled()==2,"Reload lost cancellation accounting");
                        require(session.job(uncertainRun,foreignContext).isEmpty(),"Reload shared a private job");
                        require(session.jobRoots().artifacts().contains(artifact),"Reload lost exact artifact root");
                        require(session.jobRoots().runs().contains(uncertainRun),"Reload lost execution root");
                        jobQuietUntil=h.getLevel().getGameTime()+80; phase=24; return;
                    }
                    park(foreign, 2, 3);
                    park(first, 7, 1);
                    active = submit(owner, actor, 7, 1); phase = 22;
                }
                case 22 -> {
                    terminal(owner, active, "SUCCEEDED", 1, false); identityReady();
                    park(first, 3, 3);
                    park(foreign, 7, 2);
                    active = submit(other, actorB, 7, 2); phase = 23;
                }
                case 23 -> {
                    terminal(other, active, "SUCCEEDED", 1, false);
                    require(stock(CHEST_B) == 5, "Warm serial tasks did not deliver changed real crops");
                    terminal(owner, cancelled, "CANCELLED", 0, false);
                    session.close(); closing = session.storeClosure(); phase = 28;
                }
                case 24 -> {
                    h.assertTrue(h.getLevel().getGameTime()>=jobQuietUntil,"Observing model-disabled restart without replay");
                    require(stock(CHEST_B)==7,"Reload replayed uncertain or cancelled physical effects");
                    require(session.job(uncertainRun,ownerContext).orElseThrow().state()==Jobs.State.INTERRUPTED,
                            "Uncertain work silently became ready");
                    session.close();closing=session.storeClosure();phase=28;
                }
                case 28 -> {
                    await(closing, "Closing production identity owners");
                    closing = CompletableFuture.runAsync(() -> {
                        verifyFiles(world, proof, warm);
                        if (jobsMode) try {
                            if (!warm) Files.writeString(world.resolve("job-restart.json"),StrictJson.canonical(jobProof));
                            try (var journal=JobJournal.open(world,ownerContext.scope().worldId())) {
                                JobLifecycleStore.validateSnapshot(journal.snapshot(),Jobs.Settings.defaults());
                                if(journal.readOnly())throw new IllegalStateException("Job snapshot could not recover");
                                System.out.println("IMP-011 physical accepted warm=" + warm
                                        + " scopes=2 serialJobs=3 cancelledCredit=2 uncertainCredit=0 delivered=7"
                                        + " noReplay=true exactRoots=true generationCalls=0 jobSchema=1 architecture=0.2"
                                        + " jobRecords=" + journal.snapshot().jobs().size()
                                        + " jobBytes=" + Files.size(world.resolve(JobJournal.WORLD_RELATIVE_PATH).resolve("state.jsonl")));
                            }
                        } catch(Exception e) { throw new IllegalStateException(e); }
                    }); phase = 29;
                }
                case 29 -> {
                    await(closing, "Verifying durable schema, migration and history evidence");
                    System.out.println("IMP-008 identity accepted warm=" + warm + " citizen=" + actor.citizenId()
                            + " entity=" + actor.entityId() + " owner=" + ownerContext.principal().id()
                            + " scope=" + ownerContext.scope() + " artifact=" + artifact.sha256()
                            + " candidates=2 foreignCandidates=1 serialCitizens=3 cancelledEffects=0"
                            + " newModelCalls=0 delivered=" + stock(CHEST_B)
                            + " process=" + ProcessHandle.current().pid()
                            + " elapsedMs=" + (System.nanoTime() - startedNanos) / 1_000_000
                            + " peakHeapBytes=" + peakHeapBytes
                            + " maxKernelTickNanos=" + maxKernelTickNanos); phase = 30;
                }
                default -> throw new IllegalStateException("Unknown identity phase " + phase);
            }
        }

        void plantJobSource() {
            for(int x=6;x<=7;x++)for(int z=1;z<=3;z++) {
                h.getLevel().setBlockAndUpdate(origin.offset(x,0,z),Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));
                h.getLevel().setBlockAndUpdate(origin.offset(x,1,z),Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));
            }
        }
        UUID submitJob() {
            var from=origin.offset(6,1,1);var last=origin.offset(7,1,3);var box=origin.offset(CHEST_B);
            var result=session.harvest(owner,actor.citizenId(),5,from.getX(),from.getY(),from.getZ(),
                    last.getX(),last.getY(),last.getZ(),box.getX(),box.getY(),box.getZ());
            require(result.accepted(),"Bound job request rejected: " + result.reason());return result.id();
        }
        void players(UUID ownerId, UUID otherId) {
            owner = h.makeMockServerPlayerInLevel(); other = h.makeMockServerPlayerInLevel();
            owner.setUUID(ownerId); other.setUUID(otherId);
            ownerSource = owner.createCommandSourceStack(); otherSource = other.createCommandSourceStack();
            park(owner); park(other);
        }
        void register() {
            var root = literal(ROOT); KernelCommands.attach(root, () -> session);
            h.getLevel().getServer().getCommands().getDispatcher().register(root);
            var production = h.getLevel().getServer().getCommands().getDispatcher()
                    .getRoot().getChild("aivillage").getChild("kernel");
            require(production.getChild("name") != null && production.getChild("citizens") != null,
                    "Production name/citizens commands are absent");
        }
        void position(ServerPlayer player, Villager villager) { player.setPos(villager.getX(), villager.getY(), villager.getZ()); }
        // Fixture placement only: idle entities cannot consume the next run's drops.
        void park(Villager villager, int x, int z) {
            villager.setPos(origin.getX() + x + 0.5, origin.getY() + 1, origin.getZ() + z + 0.5);
        }
        void park(ServerPlayer player) { player.setPos(origin.getX() + 20.5, origin.getY() + 1, origin.getZ() + 20.5); }
        Villager loaded(ActorRef actor) {
            var entity = h.getLevel().getEntity(actor.entityId());
            h.assertTrue(entity instanceof Villager && entity.isAlive(), "Loading saved citizen " + actor.entityId());
            return (Villager)entity;
        }
        int command(CommandSourceStack source, String suffix) {
            try { return h.getLevel().getServer().getCommands().getDispatcher().execute(ROOT + " kernel " + suffix, source); }
            catch (Exception failure) { throw new IllegalStateException("Identity command " + suffix, failure); }
        }
        void identityReady() { h.assertTrue(session.identityReady(), "Waiting for acknowledged identity publication"); }
        void require(boolean condition, String message) {
            if (!condition) { fatal = message; h.fail(message); }
        }
        void await(CompletableFuture<?> future, String message) {
            h.assertTrue(future.isDone(), message);
            if (future.isCompletedExceptionally()) throw new IllegalStateException(message, future.handle((v,e) -> e).join());
        }
        int stock(BlockPos relative) { return ((Container)h.getLevel().getBlockEntity(origin.offset(relative))).countItem(Items.WHEAT); }
        UUID submit(ServerPlayer caller, ActorRef selected, int x, int z) {
            BlockPos from = origin.offset(x, 1, z), box = origin.offset(CHEST_B);
            var access = new FabricGatewayWorld(h.getLevel());
            waitingBindings = !access.actor(selected).loaded()
                    || access.crop(new SurvivalGateway.Cell(selected.dimension(), from.getX(),
                            from.getY(), from.getZ())).status() == ObservationStatus.UNKNOWN
                    || access.container(new ContainerRef(selected.dimension(), box.getX(),
                            box.getY(), box.getZ())).status() == ObservationStatus.UNKNOWN;
            h.assertTrue(!waitingBindings, "Waiting for actual actor/source/container observations");
            var submitted = session.harvest(caller, selected.citizenId(), 1,
                    from.getX(), from.getY(), from.getZ(), from.getX(), from.getY(), from.getZ(),
                    box.getX(), box.getY(), box.getZ());
            require(submitted.accepted(), "Exact-ID known request rejected: " + submitted.reason());
            return submitted.id();
        }
        void terminal(ServerPlayer caller, UUID id, String outcome, int delivered, boolean acquisition) {
            var status = session.status(caller, id);
            h.assertTrue(status.phase() == BootstrapController.Phase.TERMINAL && status.marker() != null,
                    "Waiting for terminal identity run " + id);
            require(outcome.equals(status.marker().outcome()), "Wrong run outcome: " + status);
            if(!acquisition) {
                var context=caller.getUUID().equals(ownerContext.principal().id())?ownerContext:foreignContext;
                var job=session.job(id,context).orElseThrow();
                require(job.origin().equals(context) && job.submissionId().equals(id),"Orchestration did not preserve job origin");
                require(job.state()==("CANCELLED".equals(outcome)?Jobs.State.CANCELLED:Jobs.State.SUCCEEDED),"Job/result state diverged");
                if(delivered>0)require(job.fulfilled()==delivered,"Job did not credit actual physical receipts");
            }
            require(acquisition || status.marker().modelCalls() == 0, "Naming/reuse invoked a generation model");
            if (!"CANCELLED".equals(outcome)) require(artifact.sha256().equals(status.marker().artifactSha256()),
                    "Naming changed the acquired artifact reference");
            if (delivered > 0) require(status.marker().effects() >= delivered * 3
                            && (acquisition || status.receipts().size() >= delivered * 3),
                    "Known run lacks attributable physical custody receipts");
            if ("CANCELLED".equals(outcome)) require(status.marker().effects() == 0
                    && status.marker().reason() == Reason.CANCELLED, "Cancelled work resumed");
        }
        void duplicateAndPrivacy(boolean hasForeign) {
            var candidates = session.address(owner, "Mira");
            require(candidates.status() == CitizenRegistry.AddressStatus.AMBIGUOUS
                    && candidates.candidates().size() == 2 && !candidates.more(), "Duplicate names picked an arbitrary actor");
            require(candidates.candidates().stream().map(c -> c.actor().citizenId()).collect(java.util.stream.Collectors.toSet())
                    .equals(java.util.Set.of(actor.citizenId(), actor2.citizenId())), "Wrong scoped candidates");
            require(session.address(owner, actor.citizenId()).candidates().getFirst().actor().equals(actor)
                    && session.address(owner, actor2.citizenId()).candidates().getFirst().actor().equals(actor2),
                    "Exact identity did not disambiguate");
            require(command(otherSource, "name " + actor.citizenId() + " Stolen") == 0,
                    "Foreign principal renamed guessed identity");
            require(session.address(other, actor.citizenId()).status() == CitizenRegistry.AddressStatus.NOT_FOUND,
                    "Guessed ID widened public addressing");
            try { session.citizen(other, actor.citizenId()); h.fail("Foreign private query allowed"); }
            catch (SecurityException denied) { }
            require(command(otherSource, "status " + namedRun) == 0
                    && command(otherSource, "cancel " + namedRun) == 0, "Foreign principal controlled prior run");
            require(command(ownerSource, "citizens") == 1 && command(otherSource, "citizens") == 1,
                    "Scoped diagnostic command failed");
            require(session.citizens(owner).citizens().size() == 2
                    && session.citizens(other).citizens().size() == (hasForeign ? 1 : 0), "Private roster leaked across principals");
            if (hasForeign) require(session.address(other, "Mira").candidates().size() == 1
                    && session.address(other, "Mira").candidates().getFirst().actor().equals(actorB), "Other owner's address escaped scope");
            require(session.citizen(owner, actor.citizenId()).actor().equals(actor)
                    && session.citizen(owner, actor.citizenId()).owner().equals(ownerContext), "Rename changed stable binding");
        }
    }

    private static Path legacy(Path world) { return world.resolve(BootstrapJournal.WORLD_RELATIVE_PATH).resolve("state.legacy-v1.json"); }
    private static String hash(Path path) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static void verifyFiles(Path world, Proof proof, boolean warm) {
        try {
            String legacySha = hash(legacy(world));
            if (!legacySha.equals(proof.legacySha256())) throw new IllegalStateException("Migration rewrote legacy source");
            var identity = StrictJson.object(Files.readString(world.resolve(CitizenIdentityStore.WORLD_RELATIVE_PATH).resolve("state.json")));
            var journal = StrictJson.object(Files.readString(world.resolve(BootstrapJournal.WORLD_RELATIVE_PATH).resolve("state.json")));
            if (!Long.valueOf(1).equals(identity.get("schema")) || !Long.valueOf(2).equals(journal.get("schema"))
                    || journal.containsKey("enrollment")) throw new IllegalStateException("Identity has two writable owners");
            long revision = ((Number)identity.get("revision")).longValue();
            if (!(identity.get("citizens") instanceof java.util.List<?> rows) || rows.size() != 3)
                throw new IllegalStateException("Migration duplicated or lost a citizen");
            if (warm && revision < proof.registryRevision()) throw new IllegalStateException("Registry revision rolled back");
            if (!warm) writeProof(world, new Proof(proof.first(), proof.second(), proof.other(), proof.owner(),
                    proof.foreign(), proof.acquired(), proof.namedRun(), proof.secondRun(), proof.otherRun(),
                    proof.cancelled(), proof.artifact(), proof.origin(), legacySha, revision, proof.process()));
            System.out.println("IMP-008 durable identitySchema=1 journalSchema=2 legacySha256=" + legacySha
                    + " registryRevision=" + revision + " migrationOnce=true warm=" + warm);
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static void writeProof(Path world, Proof p) throws Exception {
        var fields = new LinkedHashMap<String,Object>();
        fields.put("first", p.first().citizenId().toString()); fields.put("entity", p.first().entityId().toString());
        fields.put("second", p.second().citizenId().toString()); fields.put("secondEntity", p.second().entityId().toString());
        fields.put("other", p.other().citizenId().toString()); fields.put("otherEntity", p.other().entityId().toString());
        fields.put("dimension", p.first().dimension()); fields.put("owner", p.owner().principal().id().toString());
        fields.put("world", p.owner().scope().worldId().toString()); fields.put("domain", p.owner().scope().domainId().toString());
        fields.put("foreign", p.foreign().principal().id().toString()); fields.put("foreignDomain", p.foreign().scope().domainId().toString());
        fields.put("acquired", p.acquired().toString()); fields.put("namedRun", p.namedRun().toString());
        fields.put("secondRun", p.secondRun().toString()); fields.put("otherRun", p.otherRun().toString());
        fields.put("cancelled", p.cancelled().toString()); fields.put("artifact", p.artifact().sha256());
        fields.put("x", p.origin().getX()); fields.put("y", p.origin().getY()); fields.put("z", p.origin().getZ());
        fields.put("legacySha256", p.legacySha256()); fields.put("registryRevision", p.registryRevision()); fields.put("process", p.process());
        Files.writeString(world.resolve("identity-restart.json"), StrictJson.canonical(fields));
    }
    private static Proof readProof(Path world) {
        try {
            var p = StrictJson.object(Files.readString(world.resolve("identity-restart.json")));
            String dimension = (String)p.get("dimension"); UUID worldId = id(p,"world");
            return new Proof(new ActorRef(id(p,"first"),id(p,"entity"),dimension),
                    new ActorRef(id(p,"second"),id(p,"secondEntity"),dimension),
                    new ActorRef(id(p,"other"),id(p,"otherEntity"),dimension),
                    new TrustedContext(new PrincipalRef(id(p,"owner")),new ScopeRef(worldId,id(p,"domain"))),
                    new TrustedContext(new PrincipalRef(id(p,"foreign")),new ScopeRef(worldId,id(p,"foreignDomain"))),
                    id(p,"acquired"),id(p,"namedRun"),id(p,"secondRun"),id(p,"otherRun"),id(p,"cancelled"),
                    new ArtifactRef(CropDelivery.ID,(String)p.get("artifact")),
                    new BlockPos(number(p,"x"),number(p,"y"),number(p,"z")),(String)p.get("legacySha256"),
                    ((Number)p.get("registryRevision")).longValue(),((Number)p.get("process")).longValue());
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static UUID id(Map<String,Object> p, String name) { return UUID.fromString((String)p.get(name)); }
    private static int number(Map<String,Object> p, String name) { return Math.toIntExact(((Number)p.get(name)).longValue()); }
}
