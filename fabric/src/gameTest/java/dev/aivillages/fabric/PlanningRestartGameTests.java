package dev.aivillages.fabric;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class PlanningRestartGameTests {
    @GameTest(maxTicks=2400,padding=32)
    public void admittedJobGraphAndDemonstratedTopologySurviveASecondServerJvm(GameTestHelper h){PlanningGameTests.run(h,true);}
}
