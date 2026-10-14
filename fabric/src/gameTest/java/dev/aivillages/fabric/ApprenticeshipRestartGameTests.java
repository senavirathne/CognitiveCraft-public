package dev.aivillages.fabric;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
public final class ApprenticeshipRestartGameTests {
    @GameTest(maxTicks=2000,padding=32)
    public void learnedSharedMethodRunsInAnotherSavedWorldJvmWithBackendStopped(GameTestHelper h){ApprenticeshipGameTests.run(h,true);}
}
