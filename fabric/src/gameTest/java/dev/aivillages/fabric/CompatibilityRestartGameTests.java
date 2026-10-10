package dev.aivillages.fabric;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** The dedicated workflow runs this in the second JVM against the cold fixture's saved world. */
public final class CompatibilityRestartGameTests {
    @GameTest(maxTicks = 1800, padding = 32)
    public void separateServerRestartPreservesMigratedRightsHistoryAndExactOriginal(GameTestHelper h) {
        CompatibilityUpgradeGameTests.run(h, true);
    }
}
