package dev.aivillages.fabric;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
public final class BirchLogEffectGameTests {
    @GameTest(maxTicks=1600,padding=16)
    public void exactBirchLootTimingComponentsAndForeignSpecies(GameTestHelper h) {new OakLogEffectGameTests().effects(h,true);}
}
