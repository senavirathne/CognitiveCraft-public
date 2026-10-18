package dev.aivillages.fabric;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
public final class BirchPlankEffectGameTests {
    @GameTest(maxTicks=10000,padding=16)
    public void exactBirchInstalledRecipeCustodyAndReload(GameTestHelper h) {new OakPlankEffectGameTests().effects(h,true);}
}
