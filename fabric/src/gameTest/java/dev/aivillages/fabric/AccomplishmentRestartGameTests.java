package dev.aivillages.fabric;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
public final class AccomplishmentRestartGameTests {
    @GameTest(maxTicks=1800,padding=32)
    public void derivedCreditAndRenamedCitizensSurviveASecondServerJvm(GameTestHelper helper){AccomplishmentGameTests.run(helper,true);}
}
