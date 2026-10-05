package dev.aivillages.fabric;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** CI alone kills the paused process; the restarted case must independently prove recovery. */
public final class BootstrapHardKillGameTests {
    @GameTest(maxTicks = 12_000, padding = 32)
    public void killedReconciliationRequiresExplicitOfflineRequest(GameTestHelper h) {
        String phase = System.getenv("COGNITIVECRAFT_BOOTSTRAP_CRASH");
        h.assertTrue("kill".equals(phase) || "warm".equals(phase),
                "Hard-kill acceptance requires an explicit kill or warm phase");
        BootstrapInterruptionGameTests.run(h, "warm", true);
    }
}
