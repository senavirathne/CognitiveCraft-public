package dev.aivillages.fabric;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Selects existing real FarmPort bread regressions without changing their fixtures or assertions. */
public final class ParallelBLegacyBreadGameTests {
    @GameTest(padding=16) public void tableAndWheatConservation(GameTestHelper h){new FarmingGameTests().craftingRequiresTableAndConservesWheat(h);}
    @GameTest(padding=16) public void fullStorageConservation(GameTestHelper h){new FarmingGameTests().fullStorageCannotDeleteOrDuplicateBread(h);}
    @GameTest(padding=16) public void nativePermissionRefusal(GameTestHelper h){new FarmingGameTests().mobGriefingRuleBlocksMutation(h);}
}
