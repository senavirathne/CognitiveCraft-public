package dev.aivillages.core.kernel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;

final class OakPlankGatewayTest {
    @Test void stockedEightProducesOnlyNewPlanksAndConsumesTwoLogs() {
        var d=new OakPlankTestSupport.Driver(8);d.produce();assertEquals(2,d.world.crafts);assertEquals(2,d.world.withdrawals);
        assertEquals(8,d.world.chestPlanks);assertEquals(0,d.world.actorPlanks);assertEquals(6,d.effects);
        assertTrue(OakPlankProduction.completed(d.bound,d.run,d.receipts));
    }
    @Test void fiveDepositsExactlyFiveAndRetainsThree() {
        var d=new OakPlankTestSupport.Driver(5);d.produce();assertEquals(5,d.world.chestPlanks);assertEquals(3,d.world.actorPlanks);
        assertEquals(14,d.world.logs);assertEquals(5,TransformationDelivery.quantity(d.bound,d.run,d.receipts));
    }
    @Test void oneHasThreeSurplus() {var d=new OakPlankTestSupport.Driver(1);d.produce();assertEquals(3,d.world.actorPlanks);assertEquals(1,d.world.chestPlanks);}
    @Test void sixtyFourFitsExistingReceiptAndWorkBounds() {
        var d=new OakPlankTestSupport.Driver(64);d.produce();assertEquals(64,d.world.chestPlanks);assertEquals(16,d.world.crafts);
        assertEquals(48,d.receipts.size());assertEquals(48,d.effects);assertTrue(OakPlankProduction.completed(d.bound,d.run,d.receipts));
    }
    @Test void initialShortageHasNoProductionEffects() {
        var world=new OakPlankTestSupport.World();world.logs=1;var d=new OakPlankTestSupport.Driver(8,world);
        assertEquals(Reason.RESOURCE_MISSING,d.action(OBSERVE_PLANK_INPUTS).reason());assertEquals(1,world.logs);assertEquals(0,d.effects);
    }
    @Test void unknownInputsAreNotZeroStock() {
        var world=new OakPlankTestSupport.World();world.known=false;var d=new OakPlankTestSupport.Driver(8,world);
        assertEquals(Reason.STALE_OBSERVATION,d.action(OBSERVE_PLANK_INPUTS).reason());assertEquals(0,world.withdrawals);
    }
    @Test void preExistingPlanksCannotProveNewOutput() {
        var world=new OakPlankTestSupport.World();world.actorPlanks=11;world.chestPlanks=20;var d=new OakPlankTestSupport.Driver(5,world);
        assertEquals(Reason.REQUEST_INVALID,d.action(TRANSFER_OAK_PLANKS).reason());assertEquals(0,d.effects);
        d.produce();assertEquals(25,world.chestPlanks);assertEquals(14,world.actorPlanks);assertEquals(5,TransformationDelivery.quantity(d.bound,d.run,d.receipts));
    }
    @Test void authorityLossAfterWithdrawalPreservesTheRealInput() {
        var d=withdrawn();d.world.authorized=false;assertEquals(Reason.AUTHORITY_DENIED,d.action(CRAFT_OAK_PLANKS).reason());
        assertEquals(1,d.world.actorLogs);assertEquals(0,d.world.crafts);
    }
    @Test void recipeChangeAfterWithdrawalDoesNotConsumeInput() {
        var d=withdrawn();d.world.recipeChanged=true;assertEquals(Reason.ARTIFACT_INCOMPATIBLE,d.action(CRAFT_OAK_PLANKS).reason());
        assertEquals(1,d.world.actorLogs);assertEquals(0,d.world.actorPlanks);
    }
    @Test void unrelatedInventoryMutationFencesCraftCredit() {
        var d=withdrawn();d.world.other++;assertEquals(Reason.STALE_OBSERVATION,d.action(CRAFT_OAK_PLANKS).reason());assertEquals(0,d.world.crafts);
    }
    @Test void fullResultCustodyBlocksWithoutDeletingTheInput() {
        var d=withdrawn();d.world.fullCustody=true;assertEquals(Reason.TARGET_UNAVAILABLE,d.action(CRAFT_OAK_PLANKS).reason());assertEquals(1,d.world.actorLogs);
    }
    @Test void cancellationAfterCraftKeepsAllFourPlanks() {
        var d=withdrawn();assertNull(d.action(CRAFT_OAK_PLANKS).reason());d.gateway.releaseRun(d.run);
        assertEquals(4,d.world.actorPlanks);assertEquals(0,d.world.chestPlanks);assertFalse(OakPlankProduction.completed(d.bound,d.run,d.receipts));
    }
    @Test void changedDestinationCannotEarnDepositCredit() {
        var d=withdrawn();d.action(CRAFT_OAK_PLANKS);d.world.targetChanged=true;
        assertEquals(Reason.STALE_OBSERVATION,d.action(TRANSFER_OAK_PLANKS).reason());assertEquals(4,d.world.actorPlanks);assertEquals(0,d.world.chestPlanks);
    }
    @Test void depletedDestinationReportsPartialRealWork() {
        var d=withdrawn();d.action(CRAFT_OAK_PLANKS);d.world.outputCapacity=0;
        assertEquals(Reason.TARGET_UNAVAILABLE,d.action(TRANSFER_OAK_PLANKS).reason());assertEquals(4,d.world.actorPlanks);
    }
    @Test void lostCapacityAfterOneDepositPreservesPartialCreditAndLaterCustody() {
        var d=withdrawn();d.action(CRAFT_OAK_PLANKS);d.action(MOVE_TO_DESTINATION);d.action(TRANSFER_OAK_PLANKS);
        d.action(MOVE_TO_INPUT);d.action(WITHDRAW_OAK_LOG);d.action(CRAFT_OAK_PLANKS);d.world.outputCapacity=0;
        assertEquals(Reason.TARGET_UNAVAILABLE,d.action(TRANSFER_OAK_PLANKS).reason());
        assertEquals(4,d.world.chestPlanks);assertEquals(4,d.world.actorPlanks);assertEquals(4,TransformationDelivery.quantity(d.bound,d.run,d.receipts));
        assertFalse(OakPlankProduction.completed(d.bound,d.run,d.receipts));d.gateway.releaseRun(d.run);
        assertEquals(4,d.world.actorPlanks);
    }
    @Test void blockedTravelPerformsNoWithdrawal() {
        var world=new OakPlankTestSupport.World();world.unreachable=true;var d=new OakPlankTestSupport.Driver(8,world);d.action(OBSERVE_PLANK_INPUTS);
        assertNotNull(d.action(MOVE_TO_INPUT).reason());assertEquals(0,world.withdrawals);
    }
    static OakPlankTestSupport.Driver withdrawn() {
        var d=new OakPlankTestSupport.Driver(8);assertNull(d.action(OBSERVE_PLANK_INPUTS).reason());
        assertNull(d.action(MOVE_TO_INPUT).reason());assertNull(d.action(WITHDRAW_OAK_LOG).reason());return d;
    }
}
