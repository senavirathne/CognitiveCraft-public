package dev.aivillages.e2e.paralleld;

import dev.aivillages.core.kernel.storage.StorageBindingRules;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BarrelBlock;

public final class DStorageLifecycleCostGameTests {
    @GameTest(maxTicks=4000)
    public void boundedLifecycleCostPreservesBinding(GameTestHelper h) {
        DNativeScene.open(h,"D02-PERF",false,s->{
            var p=h.absolutePos(new BlockPos(2,2,2));s.level.setBlockAndUpdate(p,Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING,Direction.UP));
            s.register(p,StorageBindingRules.BARREL,"performance",id->s.bind(id,p,true,b->{
                DLifecycleBenchmark.measure(s.level,p);h.assertTrue(s.adapter.validates(b),"open/close changes invalidated identity");s.frame("PERFORMANCE_VALID",id,p);s.finish();
            }));
        });
    }
}
