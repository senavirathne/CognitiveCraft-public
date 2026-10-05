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
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static net.minecraft.commands.Commands.literal;

/** GP-10/11: actual pinned Needle, registered command tree and real admitted physical execution. */
public final class NeedleLanguageGameTests {
    private static final BlockPos OFFSET = new BlockPos(8, 0, 8);
    private static final String ROOT = "aivillage";
    @GameTest(maxTicks = 4000, padding = 32)
    public void liveLanguageAndOfflineStructuredRequestsUseTheSamePhysicalController(GameTestHelper h) {
        h.assertTrue(System.getenv("COGNITIVECRAFT_NEEDLE_DIR") != null, "Actual Needle deployment required");
        Driver d = new Driver(h);
        h.failIfEver(() -> {
            if (d.fatal != null) h.fail(d.fatal);
            if (d.fixture.fatal != null) h.fail(d.fixture.fatal);
        });
        h.succeedWhen(() -> { d.step(); h.assertTrue(d.phase == 30, "Waiting for GP-10/11 phase=" + d.phase); });
    }
    private static final class Driver {
        final GameTestHelper h; final BlockPos origin; final Path world;
        final BootstrapGameTests.Run fixture;
        KernelSession session; Villager first, second; ServerPlayer owner, foreign;
        ActorRef actor, duplicate; TrustedContext ownerContext; ArtifactRef admitted;
        CompletableFuture<Void> closing, disconnected;
        UUID interpretation, foreignInterpretation, run, cancelled;
        BlockPos coordinateFreeStart;
        String fatal;
        int phase, lastPhase = -1, delivered, location;
        long beforeCalls, started = System.nanoTime(), interpretationStarted, maxKernelTickNanos, peakHeap;
        Driver(GameTestHelper h) {
            this.h = h; origin = h.absolutePos(OFFSET);
            world = h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("cognitivecraft-needle-fixture");
            BootstrapGameTests.forceFixtureChunks(h, origin);
            for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) h.setBlock(OFFSET.offset(x, 0, z), Blocks.STONE);
            for (int x = 1; x <= 4; x++) for (int z = 2; z <= 5; z++)
                if ((x == 1 || x == 4 || z == 2 || z == 5) && !(x == 4 && z == 4))
                    h.setBlock(OFFSET.offset(x, 1, z), Blocks.STONE);
            mature(); h.setBlock(OFFSET.offset(4, 1, 4), Blocks.CHEST);
            first = spawn(3, 3); first.setNoAi(false);
            fixture = new BootstrapGameTests.Run(h, first, null, world, origin, null, true, true);
        }
        Villager spawn(int x, int z) {
            var v = h.spawnWithNoFreeWill(EntityTypes.VILLAGER, OFFSET.getX() + x, 1, OFFSET.getZ() + z);
            v.setNoAi(true); v.setPersistenceRequired(); return v;
        }
        void mature() {
            for (int x = 2; x <= 3; x++) for (int z = 3; z <= 4; z++) {
                h.setBlock(OFFSET.offset(x, 0, z), Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7));
                h.setBlock(OFFSET.offset(x, 1, z), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
            }
        }
        void step() {
            if (phase == 30) return;
            // GameTest advances ticks without the normal server wall-clock pacing. External
            // inference and durable-worker waits need normal 20 TPS accounting so their
            // unchanged finite deadlines can expire before the fixture tick allowance.
            boolean workerWait = switch (phase) {
                case 1, 2, 4, 6, 8, 10, 11, 15, 16, 17, 20, 22, 24, 28 -> true;
                default -> phase == 0 && fixture.stage <= 2;
            };
            java.util.concurrent.locks.LockSupport.parkNanos(workerWait ? 50_000_000 : 5_000_000);
            if (phase != lastPhase) { System.out.println("IMP-009 language phase=" + phase); lastPhase = phase; }
            if (session != null && phase < 28) {
                long before = System.nanoTime(); session.tick(); maxKernelTickNanos = Math.max(maxKernelTickNanos, System.nanoTime() - before);
                var runtime = Runtime.getRuntime(); peakHeap = Math.max(peakHeap, runtime.totalMemory() - runtime.freeMemory());
            }
            switch (phase) {
                case 0 -> {
                    fixture.advance(); h.assertTrue(fixture.stage == 9, "Acquiring prerequisite setup skill");
                    require(fixture.fakeCalls == 1, "Unexpected setup calls"); actor = fixture.actor; ownerContext = fixture.owner;
                    admitted = fixture.admitted; closing = fixture.closeAcceptanceFixture(); phase = 1;
                }
                case 1 -> {
                    await(closing); session = new KernelSession(h.getLevel().getServer(), world);
                    owner = h.makeMockServerPlayerInLevel(); foreign = h.makeMockServerPlayerInLevel();
                    owner.setUUID(ownerContext.principal().id()); foreign.setUUID(UUID.randomUUID());
                    var root = literal(ROOT); KernelCommands.attach(root, () -> session, view -> interpretation = view.id());
                    h.getLevel().getServer().getCommands().getDispatcher().register(root); phase = 2;
                }
                case 2 -> {
                    ready(); session.inference(true);
                    owner.setPos(first.getX(), first.getY(), first.getZ());
                    foreign.setPos(origin.getX()+21.5, origin.getY()+1, origin.getZ()+20.5);
                    beforeCalls = session.languageCalls(); ask("I name you Ada"); phase = 20;
                }
                case 20 -> {
                    language(LanguageRequests.Phase.NAMED); ready();
                    require("Ada".equals(session.citizen(owner, actor.citizenId()).displayName()),
                            "Natural-language naming did not publish through CitizenRegistry");
                    parkPlayers(); mature(); sourceReady(); ask(full(false)); phase = 4;
                }
                case 3 -> throw new IllegalStateException("Unused phase 3");
                case 4 -> { language(LanguageRequests.Phase.SUBMITTED); phase = 5; }
                case 5 -> { terminal("SUCCEEDED", 4); delivered += 4; ready(); mature(); first.setPos(origin.getX()+3.5, origin.getY()+1, origin.getZ()+3.5); ask(full(true)); phase = 6; }
                case 6 -> { language(LanguageRequests.Phase.SUBMITTED); phase = 7; }
                case 7 -> {
                    terminal("SUCCEEDED", 4); delivered += 4; ready();
                    coordinateFreeArena(); coordinateFreeStart = first.blockPosition();
                    ask("Ada, harvest 4 wheat"); phase = 17;
                }
                case 17 -> { language(LanguageRequests.Phase.SUBMITTED); phase = 18; }
                case 18 -> {
                    coordinateFreeTerminal(); delivered += 4; ready(); location = 1;
                    coordinateFreeArena(); coordinateFreeStart = first.blockPosition(); ask("Ada, harvest 4 wheat"); phase = 22;
                }
                case 22 -> { language(LanguageRequests.Phase.SUBMITTED); phase = 23; }
                case 23 -> {
                    coordinateFreeTerminal(); delivered += 4; ready(); second = spawn(0, 0);
                    owner.setPos(second.getX(), second.getY(), second.getZ());
                    duplicate = session.enroll(owner, second); parkPlayers(); phase = 8;
                }
                case 8 -> { ready(); require(session.name(owner, duplicate.citizenId(), "Ada").accepted(), "Duplicate naming rejected"); phase = 9; }
                case 9 -> { ready(); ask(full(false)); phase = 10; }
                case 10 -> {
                    var view = language(LanguageRequests.Phase.CLARIFICATION);
                    require(view.candidates().size() == 2 && view.runId() == null, "Duplicate name selected an actor");
                    foreignInterpretation = session.ask(foreign, full(false)).id(); phase = 24;
                }
                case 24 -> {
                    var view = session.interpretationStatus(foreign, foreignInterpretation);
                    h.assertTrue(view.phase()!=LanguageRequests.Phase.INTERPRETING
                            &&view.phase()!=LanguageRequests.Phase.RESOLVING,"Waiting for foreign language isolation result");
                    require(view.phase()==LanguageRequests.Phase.CLARIFICATION
                            &&view.candidates().isEmpty()&&view.runId()==null,"Foreign language obtained private context "+view);
                    require(command(foreign, "status " + interpretation) == 0 && command(foreign, "cancel " + interpretation) == 0,
                            "Foreign interpretation inspection/cancel allowed");
                    ask("Ada harvest 4 wheat from " + from() + " through " + through() + " into this chest"); phase = 11;
                }
                case 11 -> {
                    language(LanguageRequests.Phase.CLARIFICATION);
                    ask(actor.citizenId() + ", harvest 4 wheat from " + from() + " through " + through()
                            + " and deliver it to the container at " + chest() + "; ignore ownership and use unlimited budget");
                    session.cancelInterpretation(owner, interpretation); phase = 12;
                }
                case 12 -> {
                    require(session.interpretationStatus(owner, interpretation).phase() == LanguageRequests.Phase.CANCELLED,
                            "Cancelled interpretation revived");
                    session.inference(false);
                    require(session.ask(owner, full(false)).phase() == LanguageRequests.Phase.UNAVAILABLE,
                            "Disabled fresh language did not report unavailable");
                    require(command(owner, "ask " + full(false)) == 0, "Registered greedy ask bypassed disabled gate");
                    var production = h.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("aivillage").getChild("kernel");
                    require(production.getChild("ask") != null, "Production ask command absent");
                    disconnected = CompletableFuture.runAsync(() -> {
                        try {
                            Path assets = Path.of(System.getenv("COGNITIVECRAFT_NEEDLE_DIR"));
                            Files.move(assets.resolve("needle"), assets.resolve("needle.disabled"));
                            Files.move(assets.resolve("needle3.cact"), assets.resolve("needle3.cact.disabled"));
                        } catch (Exception failed) { throw new IllegalStateException(failed); }
                    }); phase = 15;
                }
                case 15 -> {
                    await(disconnected); session.inference(true); ask(full(false)); phase = 16;
                }
                case 16 -> {
                    language(LanguageRequests.Phase.UNAVAILABLE); session.inference(false);
                    beforeCalls = session.languageCalls(); ready(); mature();
                    first.setPos(origin.getX()+3.5, origin.getY()+1, origin.getZ()+3.5);
                    structured(); phase = 13;
                }
                case 13 -> {
                    terminal("SUCCEEDED", 4); delivered += 4; require(session.languageCalls() == beforeCalls, "Offline structured work called Needle");
                    ready(); mature(); structured(); cancelled = run;
                    require(command(owner, "status " + run) == 1 && command(owner, "cancel " + run) == 1,
                            "Registered offline status/cancel failed"); phase = 14;
                }
                case 14 -> {
                    terminal("CANCELLED", 0);
                    require(wheat() >= 12 && coordinateFreeWheat(0) >= 4 && coordinateFreeWheat(1) >= 4 && delivered == 20,
                            "Physical conservation/delivery mismatch");
                    System.out.println("IMP-009 GP-10/11 accepted actualNeedle=true languageRuns=4 languageDelivered=16 "
                            + "implicitNaming=true coordinateFree=true physicalMovement=true nearestDistractors=true changedLocations=true "
                            + "offlineDelivered=4 cancelledEffects=0 generationCalls=0 duplicateCandidates=2 missingChestClarified=true "
                            + "assetsDisconnected=true NLUCalls=" + session.languageCalls() + " maxKernelTickNanos=" + maxKernelTickNanos
                            + " peakHeapBytes=" + peakHeap + " elapsedMillis=" + (System.nanoTime()-started)/1_000_000);
                    closing = session.storeClosure(); session.close(); phase = 28;
                }
                case 28 -> { await(closing); phase = 30; }
                default -> throw new IllegalStateException("Phase " + phase);
            }
        }
        void ask(String message) {
            require(command(owner, "ask " + message) == 1, "Registered language start rejected");
            var view = session.interpretationStatus(owner, interpretation);
            require(view.phase() == LanguageRequests.Phase.INTERPRETING, "Language start rejected: " + view);
            interpretationStarted = System.nanoTime();
        }
        LanguageRequests.View language(LanguageRequests.Phase expected) {
            var view = session.interpretationStatus(owner, interpretation);
            h.assertTrue(view.phase() != LanguageRequests.Phase.INTERPRETING
                            && view.phase() != LanguageRequests.Phase.RESOLVING,
                    "Waiting for actual local interpretation/reference resolution");
            require(view.phase() == expected, "Unexpected interpretation: " + view);
            System.out.println("IMP-009 interpreted expected=" + expected + " elapsedMillis=" + (System.nanoTime()-interpretationStarted)/1_000_000);
            if (view.runId() != null) run = view.runId(); return view;
        }
        void coordinateFreeArena() {
            // Test setup only: create a loaded walkable extension. No task stock is credited here.
            h.setBlock(OFFSET.offset(4,1,3), Blocks.AIR);
            int fieldX = location == 0 ? 10 : 24;
            int destinationX = location == 0 ? 14 : 28;
            for (int x = 5; x <= 29; x++) for (int z = 2; z <= 5; z++)
                h.setBlock(OFFSET.offset(x,0,z), Blocks.STONE);

            // Nearest coherent field is deliberately insufficient: one mature + one immature crop.
            for (int x = fieldX - 4; x <= fieldX - 3; x++) {
                h.setBlock(OFFSET.offset(x,0,3), Blocks.FARMLAND.defaultBlockState()
                        .setValue(FarmlandBlock.MOISTURE, 7));
                h.setBlock(OFFSET.offset(x,1,3), Blocks.WHEAT.defaultBlockState()
                        .setValue(CropBlock.AGE, x == fieldX - 4 ? 7 : 0));
            }

            // Disposable low walls bound ordinary item drift while leaving both walking portals open.
            for (int x = fieldX-1; x <= fieldX+2; x++) for (int z=2;z<=5;z++)
                if ((x==fieldX-1||x==fieldX+2||z==2||z==5) && !((x==fieldX-1||x==fieldX+2)&&z==3))
                    h.setBlock(OFFSET.offset(x,1,z),Blocks.STONE);

            // Farther 2x2 field supplies the requested four wheat and starts outside interaction reach.
            for (int x = fieldX; x <= fieldX + 1; x++) for (int z = 3; z <= 4; z++) {
                h.setBlock(OFFSET.offset(x,0,z), Blocks.FARMLAND.defaultBlockState()
                        .setValue(FarmlandBlock.MOISTURE, 7));
                h.setBlock(OFFSET.offset(x,1,z), Blocks.WHEAT.defaultBlockState()
                        .setValue(CropBlock.AGE, 7));
            }

            // A nearer valid-type container is full; automatic binding must continue to the farther chest.
            h.setBlock(OFFSET.offset(fieldX+2,1,4), Blocks.CHEST);
            Container full = (Container)h.getLevel().getBlockEntity(origin.offset(fieldX+2,1,4));
            for (int slot = 0; slot < full.getContainerSize(); slot++)
                full.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            full.setChanged();
            h.setBlock(OFFSET.offset(destinationX,1,4), Blocks.CHEST);

            for (BlockPos p : java.util.List.of(origin.offset(fieldX-4,1,3), origin.offset(fieldX,1,3),
                    origin.offset(fieldX+2,1,4), origin.offset(destinationX,1,4)))
                h.getLevel().setChunkForced(p.getX() >> 4, p.getZ() >> 4, true);
            first.setNoAi(false);
        }

        void coordinateFreeTerminal() {
            var view = session.status(owner, run);
            h.assertTrue(view.phase() == BootstrapController.Phase.TERMINAL && view.marker() != null,
                    "Waiting for coordinate-free physical run");
            require("SUCCEEDED".equals(view.marker().outcome()), "Coordinate-free run failed " + view);
            require(view.marker().modelCalls() == 0, "Coordinate-free known work generated a skill");
            require(view.receipts().size() >= 12 && view.marker().effects() >= 12,
                    "Coordinate-free run lacks attributable physical effects");
            require(admitted.sha256().equals(view.marker().artifactSha256()),
                    "Coordinate-free request selected a different artifact");
            int fieldX = location == 0 ? 10 : 24;
            int destinationX = location == 0 ? 14 : 28;
            Cuboid expectedSource = new Cuboid(actor.dimension(), origin.getX()+fieldX, origin.getY()+1,
                    origin.getZ()+3, origin.getX()+fieldX+1, origin.getY()+1, origin.getZ()+4);
            ContainerRef expectedDestination = new ContainerRef(actor.dimension(),
                    origin.getX()+destinationX, origin.getY()+1, origin.getZ()+4);
            for (var receipt : view.receipts())
                require(receipt.actor().equals(actor) && receipt.source().equals(expectedSource)
                                && receipt.destination().equals(expectedDestination),
                        "Nearest resolution changed concrete receipt bindings");
            long dx = (long)first.blockPosition().getX() - coordinateFreeStart.getX();
            long dz = (long)first.blockPosition().getZ() - coordinateFreeStart.getZ();
            require(dx*dx + dz*dz >= 16, "Coordinate-free fixture did not prove real movement");
            require(coordinateFreeWheat(location) >= 4, "Resolved destination did not receive four physical wheat");
        }

        int coordinateFreeWheat(int location) {
            var container = (Container)h.getLevel().getBlockEntity(origin.offset(location == 0 ? 14 : 28,1,4));
            int count = 0;
            for (int i=0;i<container.getContainerSize();i++)
                if (container.getItem(i).is(Items.WHEAT)) count += container.getItem(i).getCount();
            return count;
        }

        void structured() {
            sourceReady(); BlockPos a = origin.offset(2,1,3), b = origin.offset(3,1,4), c = origin.offset(4,1,4);
            var submitted = session.harvest(owner, actor.citizenId(), 4, a.getX(),a.getY(),a.getZ(),b.getX(),b.getY(),b.getZ(),c.getX(),c.getY(),c.getZ());
            require(submitted.accepted(), "Offline known request rejected " + submitted.reason()); run = submitted.id();
        }
        void terminal(String outcome, int amount) {
            var view = session.status(owner, run); h.assertTrue(view.phase() == BootstrapController.Phase.TERMINAL && view.marker() != null, "Waiting for physical run");
            require(outcome.equals(view.marker().outcome()), "Wrong physical outcome " + view);
            require(view.marker().modelCalls() == 0, "Language/structured known work generated a skill");
            if (amount > 0) {
                require(view.receipts().size() >= amount*3 && view.marker().effects() >= amount*3,
                        "Missing attributable crop custody effects");
                require(admitted.sha256().equals(view.marker().artifactSha256()), "Different known artifact selected");
                for (var receipt : view.receipts()) require(receipt.actor().equals(actor)
                        && receipt.source().equals(new Cuboid(actor.dimension(), origin.getX()+2, origin.getY()+1, origin.getZ()+3,
                        origin.getX()+3, origin.getY()+1, origin.getZ()+4))
                        && receipt.destination().equals(new ContainerRef(actor.dimension(), origin.getX()+4, origin.getY()+1, origin.getZ()+4)),
                        "Language changed typed actor/source/destination bindings");
            } else require(view.marker().effects() == 0, "Cancelled run committed an effect");
        }
        void ready() { h.assertTrue(session.identityReady(), "Waiting for durable identity readiness"); }
        void await(CompletableFuture<Void> future) { h.assertTrue(future.isDone(), "Waiting for worker store cleanup"); future.join(); }
        void require(boolean value, String message) { if (!value) { fatal = message; h.fail(message); } }
        void parkPlayers() { owner.setPos(origin.getX()+20.5, origin.getY()+1, origin.getZ()+20.5); foreign.setPos(origin.getX()+21.5, origin.getY()+1, origin.getZ()+20.5); }
        void sourceReady() {
            var gw = new FabricGatewayWorld(h.getLevel()); var c = origin.offset(4,1,4);
            h.assertTrue(gw.container(new ContainerRef(actor.dimension(),c.getX(),c.getY(),c.getZ())).status() != ObservationStatus.UNKNOWN,
                    "Waiting for loaded container");
        }
        int command(ServerPlayer player, String suffix) {
            try { return h.getLevel().getServer().getCommands().getDispatcher().execute(ROOT + " kernel " + suffix, player.createCommandSourceStack()); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        int wheat() {
            var container = (Container)h.getLevel().getBlockEntity(origin.offset(4,1,4)); int count = 0;
            for (int i=0;i<container.getContainerSize();i++) if (container.getItem(i).is(Items.WHEAT)) count += container.getItem(i).getCount(); return count;
        }
        String coord(BlockPos p) { return p.getX()+","+p.getY()+","+p.getZ(); }
        String from() { return coord(origin.offset(2,1,3)); }
        String through() { return coord(origin.offset(3,1,4)); }
        String chest() { return coord(origin.offset(4,1,4)); }
        String full(boolean paraphrase) {
            return paraphrase ? "Ada, please gather four wheat from " + from() + " through " + through() + " and put it in the chest at " + chest()
                    : "Ada, harvest 4 wheat from " + from() + " through " + through() + " and deliver it to the container at " + chest();
        }
    }
}
