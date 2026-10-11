package dev.aivillages.fabric;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class PrerequisiteRestartGameTests {
    @GameTest(maxTicks=2400,padding=32)
    public void durableProducerAndAllocationIdentitiesSurviveARealServerRestart(GameTestHelper helper) {
        PrerequisiteGameTests.run(helper,true);
    }
}
