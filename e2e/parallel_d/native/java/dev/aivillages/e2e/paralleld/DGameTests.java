package dev.aivillages.e2e.paralleld;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Bootstrap only. D00 has no physical component case and does not claim this executed. */
public final class DGameTests {
    @GameTest(maxTicks = 20)
    public void explicitlyTaggedFixtureAuthority(GameTestHelper helper) {
        helper.assertTrue(DTestAuthority.acquireTaggedWorld().world() != null,"Missing D-world tag");
        helper.succeed();
    }
}
