package dev.aivillages.fabric;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;

/** Checks whether a second GameTest JVM actually reopens the saved Minecraft world. */
public final class ServerRestartProbeGameTests {
    @GameTest(maxTicks = 1_000, padding = 16)
    public void savedBlockSurvivesSeparateServerProcess(GameTestHelper h) {
        String phase = System.getenv("COGNITIVECRAFT_RESTART_PROBE");
        if (phase == null) { h.succeed(); return; }
        var marker = h.getLevel().getServer().getWorldPath(LevelResource.ROOT)
                .resolve("cognitivecraft-restart-probe.txt");
        if (phase.equals("cold")) {
            BlockPos location = new BlockPos(8, 1, 8);
            h.setBlock(location, Blocks.GOLD_BLOCK);
            BlockPos absolute = h.absolutePos(location);
            var saved = CompletableFuture.runAsync(() -> {
                try { Files.writeString(marker, absolute.getX() + " " + absolute.getY()
                        + " " + absolute.getZ()); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            });
            h.succeedWhen(() -> {
                h.assertTrue(saved.isDone(), "Writing restart probe marker");
                saved.join();
                System.out.println("IMP-007 restart probe cold=" + absolute);
            });
        } else if (phase.equals("warm")) {
            var loaded = CompletableFuture.supplyAsync(() -> {
                try {
                    String[] parts = Files.readString(marker).split(" ");
                    return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]));
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            });
            h.succeedWhen(() -> {
                h.assertTrue(loaded.isDone(), "Reading restart probe marker");
                BlockPos absolute = loaded.join();
                h.getLevel().getChunk(absolute.getX() >> 4, absolute.getZ() >> 4);
                h.assertTrue(h.getLevel().getBlockState(absolute).is(Blocks.GOLD_BLOCK),
                        "Saved Minecraft block missing after full server restart at " + absolute);
                System.out.println("IMP-007 restart probe warm=" + absolute);
            });
        } else h.fail("Unknown restart probe phase");
    }
}
