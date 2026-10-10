package dev.aivillages.fabric;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
/** Invoked only by the second dedicated process against the same saved Minecraft world. */
public final class RetentionRestartGameTests {
    @GameTest(maxTicks=2400,padding=32)
    public void savedWorldRetentionAndCacheDiscardPreserveRightsKnowledgeAndAccounting(GameTestHelper h){RetentionGameTests.run(h,true);}
}
