package dev.aivillages.fabric;

import dev.aivillages.core.kernel.BootstrapController;
import dev.aivillages.core.kernel.BootstrapJournal;
import dev.aivillages.core.kernel.Outcomes.Reason;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the registered command parser with two distinct server player identities. */
public final class KernelCommandGameTests {
    @GameTest(maxTicks = 200, padding = 16)
    public void playerSourceCommandsRejectInvalidAndForeignWork(GameTestHelper h) {
        // The cold process owns this world's enrollment. The warm process tests
        // the same journal through BootstrapGameTests without inventing a new owner.
        if ("warm".equals(System.getenv("COGNITIVECRAFT_BOOTSTRAP_RESTART"))) {
            h.succeed();
            return;
        }
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++)
            h.setBlock(x, 0, z, Blocks.STONE);
        BlockPos chest = h.absolutePos(new BlockPos(2, 1, 0));
        BlockPos source = h.absolutePos(new BlockPos(2, 1, 2));
        h.setBlock(2, 1, 0, Blocks.CHEST);
        var villager = h.spawnWithNoFreeWill(EntityTypes.VILLAGER, 3, 1, 2);
        ServerPlayer owner = h.makeMockServerPlayerInLevel();
        ServerPlayer other = h.makeMockServerPlayerInLevel();
        owner.setPos(source.getX() + 0.5, source.getY(), source.getZ() + 0.5);
        other.setPos(source.getX() + 0.5, source.getY(), source.getZ() + 0.5);
        h.assertTrue(!owner.getUUID().equals(other.getUUID()),
                "Mock players did not have distinct principals");
        CommandSourceStack ownerSource = owner.createCommandSourceStack()
                .withPermission(PermissionSet.ALL_PERMISSIONS);
        CommandSourceStack otherSource = other.createCommandSourceStack();
        CommandSourceStack grantedSource = otherSource.withPermission(PermissionSet.ALL_PERMISSIONS);
        h.assertTrue(!otherSource.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),
                "Expected an unprivileged foreign command source before its explicit role grant");
        int[] phase = {0};
        UUID[] run = {null};
        UUID[] boundaryRun = {null};
        BootstrapController.View[] cancelled = {null};
        JournalMonitorBarrier[] monitor = {null};
        int[] busyRejections = {0};
        long[] commandTick = {0};
        h.succeedWhen(() -> {
            KernelSession session = AiVillages.kernel();
            h.assertTrue(session != null, "Waiting for production kernel");
            if (phase[0] == 0) {
                // The composition root opens authoritative stores off-thread.
                boolean fresh;
                try { fresh = session.enrollment() == null; }
                catch (IllegalStateException opening) {
                    h.assertTrue(false, "Waiting for production kernel stores");
                    return;
                }
                h.assertTrue(fresh, "Expected fresh kernel enrollment");
                h.assertTrue(command(h, ownerSource, "aivillage kernel enroll") == 1,
                        "Operator command did not enroll nearby worker");
                phase[0] = 1;
            }
            if (phase[0] == 1) {
                h.assertTrue(session.enrollment() != null,
                        "Waiting for durable command enrollment");
                var actor = session.enrollment().actor();
                h.assertTrue(actor.entityId().equals(villager.getUUID())
                        && session.enrollment().owner().principal().id().equals(owner.getUUID()),
                        "Command enrollment lost actor or principal identity");
                if (monitor[0] == null) {
                    monitor[0] = new JournalMonitorBarrier(session);
                }
                h.assertTrue(monitor[0].held.isDone(), "Waiting for the off-thread journal monitor barrier");
                if (!monitor[0].checked) {
                    h.assertTrue(session.scope(owner.getUUID()).equals(
                                    session.enrollment().owner().scope().worldId() + "/" + owner.getUUID())
                                    && !monitor[0].holder.isDone(),
                            "Scope lookup waited for the stalled journal writer instead of its loaded snapshot");
                    monitor[0].checked = true;
                    monitor[0].release.countDown();
                }
                h.assertTrue(monitor[0].holder.isDone(), "Waiting for journal monitor fixture cleanup");
                monitor[0].holder.join();
                h.assertTrue(monitor[0].timeouts.get() == 0
                                && session.catalog(owner).contains("recentModelCalls=0")
                                && session.catalog(owner).contains("inference=false"),
                        "Startup loaded a model or journal monitor was not released promptly");
                h.assertTrue(command(h, ownerSource, "aivillage kernel enroll") == 0
                                && session.enrollment().actor().equals(actor),
                        "Replay enrollment replaced or duplicated the controlled actor");
                deniedSyntax(h, otherSource, "aivillage kernel enroll");
                deniedSyntax(h, otherSource, "aivillage kernel inference false");
                h.assertTrue(command(h, grantedSource, "aivillage kernel inference false") == 1,
                        "Explicit gamemaster role did not permit the declared operator action");
                deniedSyntax(h, otherSource, "aivillage kernel inference false");
                h.assertTrue(command(h, ownerSource, "aivillage kernel inference false") == 1,
                        "Offline inference control failed");
                h.assertTrue(command(h, ownerSource, "aivillage kernel catalog") == 1,
                        "Owner could not inspect catalog");
                String binding = actor.citizenId() + " ";
                String area = " " + source.getX() + " " + source.getY() + " " + source.getZ()
                        + " " + source.getX() + " " + source.getY() + " " + source.getZ()
                        + " " + chest.getX() + " " + chest.getY() + " " + chest.getZ();
                for (int amount : new int[] {-1, 0, 65, Integer.MIN_VALUE, Integer.MAX_VALUE})
                    h.assertTrue(command(h, ownerSource, "aivillage kernel harvest " + binding
                            + amount + area) == 0, "Accepted invalid amount " + amount);
                deniedSyntax(h, ownerSource, "aivillage kernel harvest " + binding
                        + "2147483648" + area);
                h.assertTrue(command(h, ownerSource, "aivillage kernel harvest " + binding
                        + "1 " + source.getX() + " " + source.getY() + " " + source.getZ()
                        + " " + (source.getX() + 256) + " " + source.getY() + " " + source.getZ()
                        + " " + chest.getX() + " " + chest.getY() + " " + chest.getZ()) == 0,
                        "Accepted a 257-cell source above the 256-cell kernel limit");
                h.assertTrue(command(h, ownerSource, "aivillage kernel harvest " + binding
                        + "1 -2147483648 -2147483648 -2147483648 2147483647 2147483647 2147483647 "
                        + chest.getX() + " " + chest.getY() + " " + chest.getZ()) == 0,
                        "Overflowing cuboid escaped request validation");
                h.assertTrue(command(h, ownerSource, "aivillage kernel harvest " + UUID.randomUUID()
                        + " 1" + area) == 0, "Accepted unknown actor");
                h.assertTrue(command(h, ownerSource, "aivillage kernel harvest " + binding
                        + "1 " + (source.getX() + 3) + " " + source.getY() + " " + source.getZ()
                        + " " + source.getX() + " " + source.getY() + " " + source.getZ()
                        + " " + chest.getX() + " " + chest.getY() + " " + chest.getZ()) == 0,
                        "Accepted inverted source");
                h.assertTrue(command(h, otherSource, "aivillage kernel harvest " + binding
                        + "1" + area) == 0, "Foreign principal submitted work");
                h.assertTrue(command(h, otherSource, "aivillage kernel catalog") == 0,
                        "Foreign principal inspected catalog");
                var submitted = session.harvest(owner, actor.citizenId(), 1,
                        source.getX(), source.getY(), source.getZ(),
                        source.getX(), source.getY(), source.getZ(),
                        chest.getX(), chest.getY(), chest.getZ());
                h.assertTrue(submitted.accepted(), "Valid offline request rejected: "
                        + submitted.reason());
                run[0] = submitted.id();
                h.assertTrue(command(h, ownerSource, "aivillage kernel harvest " + binding
                        + "1" + area) == 0, "Concurrent replay escaped the active-run limit");
                busyRejections[0]++;
                h.assertTrue(command(h, otherSource, "aivillage kernel status " + run[0]) == 0,
                        "Foreign principal inspected run");
                h.assertTrue(command(h, otherSource, "aivillage kernel cancel " + run[0]) == 0,
                        "Foreign principal cancelled run");
                h.assertTrue(command(h, grantedSource, "aivillage kernel harvest " + binding
                        + "1" + area) == 0
                                && command(h, grantedSource, "aivillage kernel catalog") == 0
                                && command(h, grantedSource, "aivillage kernel status " + run[0]) == 0
                                && command(h, grantedSource, "aivillage kernel cancel " + run[0]) == 0,
                        "Operator inference role widened private run ownership");
                h.assertTrue(command(h, ownerSource, "aivillage kernel status " + run[0]) == 1,
                        "Owner could not inspect run");
                h.assertTrue(command(h, ownerSource, "aivillage kernel cancel " + run[0]) == 1,
                        "Owner could not cancel run");
                phase[0] = 2;
            }
            if (phase[0] == 2) {
                var status = session.status(owner, run[0]);
                h.assertTrue(status.phase() == BootstrapController.Phase.TERMINAL
                                && status.marker() != null, "Waiting for cancelled marker");
                h.assertTrue("CANCELLED".equals(status.marker().outcome())
                                && status.marker().reason() == Reason.CANCELLED
                                && status.marker().effects() == 0
                                && status.marker().modelCalls() == 0,
                        "Command cancellation changed world or invoked model: " + status);
                cancelled[0] = status;
                BlockPos min = h.absolutePos(BlockPos.ZERO), max = h.absolutePos(new BlockPos(7, 3, 7));
                var accepted = session.harvest(owner, session.enrollment().actor().citizenId(), 64,
                        min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ(),
                        chest.getX(), chest.getY(), chest.getZ());
                h.assertTrue(accepted.accepted(), "Exact amount/source limits were rejected: " + accepted.reason());
                boundaryRun[0] = accepted.id();
                h.assertTrue(command(h, ownerSource, "aivillage kernel harvest "
                        + session.enrollment().actor().citizenId() + " 64 "
                        + min.getX() + " " + min.getY() + " " + min.getZ() + " "
                        + max.getX() + " " + max.getY() + " " + max.getZ() + " "
                        + chest.getX() + " " + chest.getY() + " " + chest.getZ()) == 0,
                        "Replay of an exact-bound request escaped busy control");
                busyRejections[0]++;
                h.assertTrue(command(h, ownerSource, "aivillage kernel cancel " + boundaryRun[0]) == 1,
                        "Could not cancel the accepted exact-bound request offline");
                phase[0] = 3;
            }
            if (phase[0] == 3) {
                var status = session.status(owner, boundaryRun[0]);
                h.assertTrue(status.phase() == BootstrapController.Phase.TERMINAL,
                        "Waiting for exact-bound request cancellation to persist");
                h.assertTrue("CANCELLED".equals(status.marker().outcome())
                                && status.marker().effects() == 0 && status.marker().modelCalls() == 0
                                && status.receipts().isEmpty(),
                        "Exact-bound cancellation performed work or inference");
                h.assertTrue(command(h, ownerSource, "aivillage kernel harvest "
                        + session.enrollment().actor().citizenId() + " 1 "
                        + source.getX() + " " + source.getY() + " " + source.getZ() + " "
                        + source.getX() + " " + source.getY() + " " + source.getZ() + " "
                        + chest.getX() + " " + chest.getY() + " " + chest.getZ()) == 1,
                        "Valid player-source harvest command rejected");
                commandTick[0] = h.getLevel().getGameTime();
                phase[0] = 4;
            }
            if (phase[0] == 4) {
                h.assertTrue(h.getLevel().getGameTime() - commandTick[0] >= 20,
                        "Waiting for offline command to terminate");
                h.assertTrue(((Container)h.getLevel().getBlockEntity(chest))
                                .countItem(Items.WHEAT) == 0,
                        "Command request created wheat without mature crops");
                h.assertTrue(session.catalog(owner).contains("recentModelCalls=0"),
                        "Offline command invoked a model");
                h.assertTrue(session.status(owner, run[0]).equals(cancelled[0])
                                && busyRejections[0] == 2 && monitor[0].checked
                                && monitor[0].timeouts.get() == 0,
                        "Late requests changed prior cancellation or required command checks were omitted");
                System.out.println("IMP-007 command fixture: enrolled actor=" + villager.getUUID()
                        + " owner=" + owner.getUUID() + " deniedPrincipal=" + other.getUUID()
                        + " invalid/foreign commands rejected; offline cancel kept zero effects");
                System.out.println("IMP-007 command acceptance: maxAmount=64 maxSourceCells=256"
                        + " acceptedBounds=true sourceOverLimit=257 sourceOverflowRejected=true"
                        + " syntaxOverflowRejected=true invalidAmounts=5 replayBusyRejections=2"
                        + " explicitRoleAllowed=1 permissionDenials=3 foreignPrivateDenials=8"
                        + " scopeUnderStalledJournal=true journalMonitorTimeouts=0"
                        + " initialModelCalls=0 cancelEffects=0 stableLateTicks=20");
            }
            h.assertTrue(phase[0] == 4 && h.getLevel().getGameTime() - commandTick[0] >= 20,
                    "Waiting for all command acceptance phases, current phase=" + phase[0]);
        });
    }

    private static int command(GameTestHelper h, CommandSourceStack source, String input) {
        try { return h.getLevel().getServer().getCommands().getDispatcher().execute(input, source); }
        catch (Exception failure) { throw new AssertionError("Command failed: " + input, failure); }
    }

    private static void deniedSyntax(GameTestHelper h, CommandSourceStack source, String input) {
        try {
            h.getLevel().getServer().getCommands().getDispatcher().execute(input, source);
            h.fail("Expected command parser/permission rejection: " + input);
        } catch (CommandSyntaxException expected) { }
    }

    /** Holds the real journal monitor off-thread; the watchdog bounds a regressed getter's wait. */
    private static final class JournalMonitorBarrier {
        final CompletableFuture<Void> held = new CompletableFuture<>();
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger timeouts = new AtomicInteger();
        final CompletableFuture<Void> holder;
        boolean checked;

        JournalMonitorBarrier(KernelSession session) {
            BootstrapJournal journal;
            try {
                var field = KernelSession.class.getDeclaredField("loaded"); field.setAccessible(true);
                var loaded = field.get(session);
                var accessor = loaded.getClass().getDeclaredMethod("journal"); accessor.setAccessible(true);
                journal = (BootstrapJournal) accessor.invoke(loaded);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("Could not bind the production journal monitor fixture", failure);
            }
            holder = CompletableFuture.runAsync(() -> {
                synchronized (journal) {
                    held.complete(null);
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            timeouts.incrementAndGet();
                            throw new AssertionError("Journal monitor barrier expired before scope returned");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
                    }
                }
            });
        }
    }
}
