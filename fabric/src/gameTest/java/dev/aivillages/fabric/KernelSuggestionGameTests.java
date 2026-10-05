package dev.aivillages.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.Suggestion;
import dev.aivillages.core.kernel.BootstrapController;
import dev.aivillages.core.kernel.Contracts.ActorRef;
import dev.aivillages.core.kernel.LanguageRequests;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.storage.LevelResource;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import static net.minecraft.commands.Commands.literal;

/** The production command tree and owner stores, with a bounded pending interpreter. */
public final class KernelSuggestionGameTests {
    @GameTest(maxTicks = 800, padding = 16)
    public void playerScopedUuidSuggestions(GameTestHelper h) {
        var fixture = new Fixture(h);
        h.failIfEver(() -> { if (fixture.fatal != null) h.fail(fixture.fatal); });
        h.succeedWhen(() -> {
            fixture.step();
            h.assertTrue(fixture.phase == 9, "Waiting for UUID completion fixture phase=" + fixture.phase);
        });
    }

    private static final class Fixture implements LanguageRequests.Port {
        final GameTestHelper h;
        final KernelSession session;
        final CommandDispatcher<CommandSourceStack> commands = new CommandDispatcher<>();
        final ServerPlayer owner, other;
        final CommandSourceStack ownerSource, otherSource;
        final Villager first, second, foreign;
        final BlockPos crop, chest;
        ActorRef ownActor, secondActor, foreignActor;
        UUID ownTerminal, foreignTerminal, foreignTicket, cancelledTicket, pendingTicket, activeRun;
        LanguageRequests.View lastTicket;
        int phase, modelCalls, modelCancels;
        String fatal;

        Fixture(GameTestHelper h) {
            this.h = h;
            for (int x = 0; x < 7; x++) for (int z = 0; z < 6; z++) h.setBlock(x, 0, z, Blocks.STONE);
            h.setBlock(1, 0, 3, Blocks.FARMLAND);
            h.setBlock(1, 1, 3, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
            h.setBlock(5, 1, 3, Blocks.CHEST);
            crop = h.absolutePos(new BlockPos(1, 1, 3));
            chest = h.absolutePos(new BlockPos(5, 1, 3));
            first = spawn(1); second = spawn(2); foreign = spawn(3);
            owner = h.makeMockServerPlayerInLevel(); other = h.makeMockServerPlayerInLevel();
            owner.setUUID(UUID.randomUUID()); other.setUUID(UUID.randomUUID());
            ownerSource = owner.createCommandSourceStack().withPermission(PermissionSet.ALL_PERMISSIONS);
            otherSource = other.createCommandSourceStack().withPermission(PermissionSet.ALL_PERMISSIONS);
            var path = h.getLevel().getServer().getWorldPath(LevelResource.ROOT)
                    .resolve("cognitivecraft-uuid-fixture-" + UUID.randomUUID());
            session = new KernelSession(h.getLevel().getServer(), path, this);
            var root = literal("aivillage");
            KernelCommands.attach(root, () -> session, view -> lastTicket = view);
            commands.register(root);
        }

        Villager spawn(int x) {
            var villager = h.spawnWithNoFreeWill(EntityTypes.VILLAGER, x, 1, 1);
            villager.setPersistenceRequired();
            return villager;
        }

        @Override public LanguageRequests.Handle interpret(LanguageRequests.Input input) {
            modelCalls++;
            return new LanguageRequests.Handle() {
                final CompletableFuture<LanguageRequests.Result> result = new CompletableFuture<>();
                public java.util.concurrent.CompletionStage<LanguageRequests.Result> result() { return result; }
                public void cancel() { modelCancels++; }
            };
        }

        void step() {
            if (phase == 9) return;
            java.util.concurrent.locks.LockSupport.parkNanos(5_000_000);
            if (phase < 8) session.tick();
            switch (phase) {
                case 0 -> { ready(); beside(owner, first); ownActor = session.enroll(owner, first); phase = 1; }
                case 1 -> { ready(); beside(owner, second); secondActor = session.enroll(owner, second); phase = 2; }
                case 2 -> { ready(); beside(other, foreign); foreignActor = session.enroll(other, foreign); phase = 3; }
                case 3 -> {
                    ready();
                    require(command(ownerSource, "aivillage kernel name " + ownActor.citizenId() + " Ada") == 1,
                            "Name execution changed after attaching suggestions");
                    phase = 4;
                }
                case 4 -> {
                    ready();
                    for (String verb : List.of("harvest", "name")) {
                        expect(ownerSource, verb, Set.of(ownActor.citizenId(), secondActor.citizenId()));
                        expect(otherSource, verb, Set.of(foreignActor.citizenId()));
                        prefix(ownerSource, verb, ownActor.citizenId().toString().substring(0, 8).toUpperCase(Locale.ROOT),
                                Set.of(ownActor.citizenId(), secondActor.citizenId()));
                    }
                    var submitted = harvest(other, foreignActor);
                    require(submitted.accepted(), "Foreign scoped fixture request rejected");
                    foreignTerminal = submitted.id(); phase = 5;
                }
                case 5 -> {
                    terminal(other, foreignTerminal);
                    var submitted = harvest(owner, ownActor);
                    require(submitted.accepted(), "Owner scoped fixture request rejected");
                    ownTerminal = submitted.id(); phase = 6;
                }
                case 6 -> {
                    terminal(owner, ownTerminal);
                    foreignTicket = session.ask(other, text()).id();
                    require(session.interpretationStatus(other, foreignTicket).phase() == LanguageRequests.Phase.UNAVAILABLE,
                            "Expected retained unavailable foreign ticket");
                    session.inference(true);
                    cancelledTicket = ask();
                    require(command(ownerSource, "aivillage kernel cancel " + cancelledTicket) == 1,
                            "Interpretation cancellation execution changed");
                    pendingTicket = ask();
                    require(command(ownerSource, "aivillage kernel harvest " + ownActor.citizenId() + " 1" + area()) == 1,
                            "Harvest execution changed after attaching suggestions");
                    List<UUID> runs = session.runOrTicketIds(owner, true).stream()
                            .filter(id -> !id.equals(pendingTicket)).toList();
                    require(runs.size() == 1, "Expected one current owner run"); activeRun = runs.getFirst();
                    require(session.status(owner, activeRun).marker().citizenId().equals(ownActor.citizenId()),
                            "Harvest command lost its actor binding");
                    int calls = modelCalls, cancels = modelCancels;
                    Set<UUID> retained = Set.of(ownTerminal, activeRun, cancelledTicket, pendingTicket);
                    expect(ownerSource, "status", retained);
                    expect(otherSource, "status", Set.of(foreignTerminal, foreignTicket));
                    expect(ownerSource, "cancel", Set.of(activeRun, pendingTicket));
                    expect(otherSource, "cancel", Set.of());
                    prefix(ownerSource, "status", pendingTicket.toString().substring(0, 8).toUpperCase(Locale.ROOT), retained);
                    prefix(ownerSource, "cancel", activeRun.toString().substring(0, 8), Set.of(activeRun, pendingTicket));
                    for (String verb : List.of("harvest", "name", "status", "cancel")) {
                        prefix(ownerSource, verb, "not-a-uuid", Set.of());
                        require(suggestions(h.getLevel().getServer().createCommandSourceStack(), verb, "").isEmpty(),
                                "Non-player source received private UUIDs");
                    }
                    require(modelCalls == calls && modelCancels == cancels
                                    && session.interpretationStatus(owner, pendingTicket).phase() == LanguageRequests.Phase.INTERPRETING
                                    && session.status(owner, activeRun).phase() == BootstrapController.Phase.PERSISTING,
                            "Completion advanced, interpreted or cancelled work");
                    require(command(ownerSource, "aivillage kernel status " + ownTerminal) == 1,
                            "Retained status execution changed");
                    require(command(ownerSource, "aivillage kernel cancel " + pendingTicket) == 1,
                            "Pending ticket cancellation execution changed");
                    expect(ownerSource, "cancel", Set.of(activeRun));
                    require(command(ownerSource, "aivillage kernel cancel " + activeRun) == 1,
                            "Run cancellation execution changed");
                    expect(ownerSource, "cancel", Set.of());
                    expect(ownerSource, "status", retained);
                    phase = 7;
                }
                case 7 -> {
                    terminal(owner, activeRun);
                    require("CANCELLED".equals(session.status(owner, activeRun).marker().outcome()),
                            "Run cancellation outcome changed");
                    expect(ownerSource, "cancel", Set.of());
                    require(modelCalls == 2 && modelCancels == 2, "Unexpected interpreter work from completion");
                    session.close(); phase = 8;
                }
                case 8 -> {
                    h.assertTrue(session.storeClosure().isDone(), "Waiting for isolated completion stores to close");
                    session.storeClosure().join();
                    for (String verb : List.of("harvest", "name", "status", "cancel")) expect(ownerSource, verb, Set.of());
                    AiVillages.LOG.info("UUID completion acceptance: players=2 ownCitizens=2 foreignCitizens=1 privacy=true prefix=true executionUnchanged=true");
                    phase = 9;
                }
                default -> throw new IllegalStateException("Completion fixture phase=" + phase);
            }
        }

        BootstrapController.Submission harvest(ServerPlayer player, ActorRef actor) {
            return session.harvest(player, actor.citizenId(), 1, crop.getX(), crop.getY(), crop.getZ(),
                    crop.getX(), crop.getY(), crop.getZ(), chest.getX(), chest.getY(), chest.getZ());
        }
        String area() {
            return " " + crop.getX() + " " + crop.getY() + " " + crop.getZ() + " " + crop.getX()
                    + " " + crop.getY() + " " + crop.getZ() + " " + chest.getX() + " " + chest.getY() + " " + chest.getZ();
        }
        String text() { return "Ada, harvest 1 wheat from " + crop.getX() + "," + crop.getY() + "," + crop.getZ()
                + " through " + crop.getX() + "," + crop.getY() + "," + crop.getZ() + " and deliver to the container at "
                + chest.getX() + "," + chest.getY() + "," + chest.getZ(); }
        UUID ask() {
            require(command(ownerSource, "aivillage kernel ask " + text()) == 1, "Registered interpretation request rejected");
            require(lastTicket.phase() == LanguageRequests.Phase.INTERPRETING, "Expected a pending interpretation");
            return lastTicket.id();
        }
        void ready() { h.assertTrue(session.identityReady(), "Waiting for identity persistence"); }
        void terminal(ServerPlayer player, UUID run) {
            h.assertTrue(session.status(player, run).phase() == BootstrapController.Phase.TERMINAL,
                    "Waiting for retained terminal run");
        }
        void beside(ServerPlayer player, Villager villager) { player.setPos(villager.getX(), villager.getY(), villager.getZ()); }
        int command(CommandSourceStack source, String input) {
            try { return commands.execute(input, source); }
            catch (Exception failure) { fatal = "Command execution failed: " + input; throw new AssertionError(fatal, failure); }
        }
        Set<String> suggestions(CommandSourceStack source, String verb, String prefix) {
            String input = "aivillage kernel " + verb + " " + prefix;
            var result = commands.getCompletionSuggestions(commands.parse(input, source));
            require(result.isDone(), "UUID completion waited for asynchronous work");
            return result.join().getList().stream().map(Suggestion::getText).collect(Collectors.toSet());
        }
        void expect(CommandSourceStack source, String verb, Set<UUID> expected) {
            require(suggestions(source, verb, "").equals(expected.stream().map(UUID::toString).collect(Collectors.toSet())),
                    "Incorrect scoped " + verb + " completion");
        }
        void prefix(CommandSourceStack source, String verb, String prefix, Set<UUID> candidates) {
            Set<String> expected = candidates.stream().map(UUID::toString)
                    .filter(id -> id.startsWith(prefix.toLowerCase(Locale.ROOT))).collect(Collectors.toSet());
            require(suggestions(source, verb, prefix).equals(expected), "Incorrect UUID prefix filtering for " + verb);
        }
        void require(boolean condition, String message) { if (!condition) { fatal = message; h.fail(message); } }
    }
}
