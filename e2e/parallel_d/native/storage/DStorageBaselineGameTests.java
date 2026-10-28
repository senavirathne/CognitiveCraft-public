package dev.aivillages.e2e.paralleld;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BarrelBlock;

/** Supporting performance baseline on exact qualified D01, not a new D02 pass. */
public final class DStorageBaselineGameTests {
    @GameTest(maxTicks=100)
    public void unchangedBaselineLifecycleCost(GameTestHelper h) {
        DTestAuthority.acquireTaggedWorld();var p=h.absolutePos(new BlockPos(2,2,2));
        h.getLevel().setBlockAndUpdate(p,Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING,Direction.UP));
        DLifecycleBenchmark.measure(h.getLevel(),p);h.succeed();
    }
}
