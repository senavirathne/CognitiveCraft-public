package dev.aivillages.fabric;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Dedicated GP-13 portion: real commands, durable jobs, physical cancellation and two Minecraft JVMs. */
public final class JobLifecycleGameTests {
    @GameTest(maxTicks=2400,padding=32)
    public void durableJobsRetainPartialEffectsAndNeverReplayAfterServerRestart(GameTestHelper h) {
        String phase=System.getenv("COGNITIVECRAFT_JOB_RESTART");
        h.assertTrue("cold".equals(phase)||"warm".equals(phase),"Jobs fixture requires cold/warm process");
        CitizenIdentityGameTests.runJobs(h,"warm".equals(phase));
    }
}
