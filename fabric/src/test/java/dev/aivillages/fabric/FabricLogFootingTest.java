package dev.aivillages.fabric;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FabricLogFootingTest {
    private static final BlockPos LOG = new BlockPos(73, 208, 73);
    private static final BlockPos FACE = new BlockPos(73, 208, 72);
    private static final BlockPos CORNER = new BlockPos(72, 208, 72);

    @Test void theApproachStillRequiresAValidFace() {
        assertTrue(FabricGatewayWorld.logFooting(FACE, LOG, 73.638, 72.638, true));
        assertFalse(FabricGatewayWorld.logFooting(CORNER, LOG, 72.717, 72.700, true));
    }

    @Test void aCornerWithinTheSameDistanceCanSupportTheBreak() {
        assertTrue(FabricGatewayWorld.logFooting(CORNER, LOG, 72.717, 72.700, false));
        assertFalse(FabricGatewayWorld.logFooting(CORNER, LOG, 72.400, 72.700, false));
    }

    @Test void aWorkerCannotBreakFromTheLogColumn() {
        assertFalse(FabricGatewayWorld.logFooting(LOG.above(), LOG, 73.500, 73.500, false));
    }
}
