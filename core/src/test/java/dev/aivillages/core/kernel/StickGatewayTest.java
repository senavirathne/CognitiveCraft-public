package dev.aivillages.core.kernel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;

final class StickGatewayTest {
    @Test void stockedEightProducesOnlyNewSticksAndConsumesFourPlanks() {
        var d=new StickTestSupport.Driver(8);d.produce();assertEquals(2,d.world.crafts);assertEquals(2,d.world.withdrawals);
        assertEquals(8,d.world.chestSticks);assertEquals(0,d.world.actorSticks);assertEquals(6,d.effects);
        assertTrue(StickProduction.completed(d.bound,d.run,d.receipts));
    }
    @Test void fiveDepositsExactlyFiveAndRetainsThree() {
        var d=new StickTestSupport.Driver(5);d.produce();assertEquals(5,d.world.chestSticks);assertEquals(3,d.world.actorSticks);
        assertEquals(28,d.world.logs);assertEquals(5,TransformationDelivery.quantity(d.bound,d.run,d.receipts));
    }
    @Test void oneHasThreeSurplus() {var d=new StickTestSupport.Driver(1);d.produce();assertEquals(3,d.world.actorSticks);assertEquals(1,d.world.chestSticks);}
    @Test void sixtyFourFitsExistingReceiptAndWorkBounds() {
        var d=new StickTestSupport.Driver(64);d.produce();assertEquals(64,d.world.chestSticks);assertEquals(16,d.world.crafts);
        assertEquals(48,d.receipts.size());assertEquals(48,d.effects);assertTrue(StickProduction.completed(d.bound,d.run,d.receipts));
    }
    @Test void initialShortageHasNoProductionEffects() {
        var world=new StickTestSupport.World();world.logs=1;var d=new StickTestSupport.Driver(8,world);
        assertEquals(Reason.RESOURCE_MISSING,d.action(OBSERVE_STICK_INPUTS).reason());assertEquals(1,world.logs);assertEquals(0,d.effects);
    }
    @Test void unknownInputsAreNotZeroStock() {
        var world=new StickTestSupport.World();world.known=false;var d=new StickTestSupport.Driver(8,world);
        assertEquals(Reason.STALE_OBSERVATION,d.action(OBSERVE_STICK_INPUTS).reason());assertEquals(0,world.withdrawals);
    }
    @Test void preExistingSticksCannotProveNewOutput() {
        var world=new StickTestSupport.World();world.actorSticks=11;world.chestSticks=20;var d=new StickTestSupport.Driver(5,world);
        assertEquals(Reason.REQUEST_INVALID,d.action(TRANSFER_STICKS).reason());assertEquals(0,d.effects);
        d.produce();assertEquals(25,world.chestSticks);assertEquals(14,world.actorSticks);assertEquals(5,TransformationDelivery.quantity(d.bound,d.run,d.receipts));
    }
    @Test void authorityLossAfterWithdrawalPreservesTheRealInput() {
        var d=withdrawn();d.world.authorized=false;assertEquals(Reason.AUTHORITY_DENIED,d.action(CRAFT_STICKS).reason());
        assertEquals(2,d.world.actorPlanks);assertEquals(0,d.world.crafts);
    }
    @Test void recipeChangeAfterWithdrawalDoesNotConsumeInput() {
        var d=withdrawn();d.world.recipeChanged=true;assertEquals(Reason.ARTIFACT_INCOMPATIBLE,d.action(CRAFT_STICKS).reason());
        assertEquals(2,d.world.actorPlanks);assertEquals(0,d.world.actorSticks);
    }
    @Test void unrelatedInventoryMutationFencesCraftCredit() {
        var d=withdrawn();d.world.other++;assertEquals(Reason.STALE_OBSERVATION,d.action(CRAFT_STICKS).reason());assertEquals(0,d.world.crafts);
    }
    @Test void fullResultCustodyBlocksWithoutDeletingTheInput() {
        var d=withdrawn();d.world.fullCustody=true;assertEquals(Reason.TARGET_UNAVAILABLE,d.action(CRAFT_STICKS).reason());assertEquals(2,d.world.actorPlanks);
    }
    @Test void cancellationAfterCraftKeepsAllFourSticks() {
        var d=withdrawn();assertNull(d.action(CRAFT_STICKS).reason());d.gateway.releaseRun(d.run);
        assertEquals(4,d.world.actorSticks);assertEquals(0,d.world.chestSticks);assertFalse(StickProduction.completed(d.bound,d.run,d.receipts));
    }
    @Test void changedDestinationCannotEarnDepositCredit() {
        var d=withdrawn();d.action(CRAFT_STICKS);d.world.targetChanged=true;
        assertEquals(Reason.STALE_OBSERVATION,d.action(TRANSFER_STICKS).reason());assertEquals(4,d.world.actorSticks);assertEquals(0,d.world.chestSticks);
    }
    @Test void depletedDestinationReportsPartialRealWork() {
        var d=withdrawn();d.action(CRAFT_STICKS);d.world.outputCapacity=0;
        assertEquals(Reason.TARGET_UNAVAILABLE,d.action(TRANSFER_STICKS).reason());assertEquals(4,d.world.actorSticks);
    }
    @Test void lostCapacityAfterOneDepositPreservesPartialCreditAndLaterCustody() {
        var d=withdrawn();d.action(CRAFT_STICKS);d.action(MOVE_TO_DESTINATION);d.action(TRANSFER_STICKS);
        d.action(MOVE_TO_INPUT);d.action(WITHDRAW_OAK_PLANKS);d.action(CRAFT_STICKS);d.world.outputCapacity=0;
        assertEquals(Reason.TARGET_UNAVAILABLE,d.action(TRANSFER_STICKS).reason());
        assertEquals(4,d.world.chestSticks);assertEquals(4,d.world.actorSticks);assertEquals(4,TransformationDelivery.quantity(d.bound,d.run,d.receipts));
        assertFalse(StickProduction.completed(d.bound,d.run,d.receipts));d.gateway.releaseRun(d.run);
        assertEquals(4,d.world.actorSticks);
    }
    @Test void blockedTravelPerformsNoWithdrawal() {
        var world=new StickTestSupport.World();world.unreachable=true;var d=new StickTestSupport.Driver(8,world);d.action(OBSERVE_STICK_INPUTS);
        assertNotNull(d.action(MOVE_TO_INPUT).reason());assertEquals(0,world.withdrawals);
    }
    static StickTestSupport.Driver withdrawn() {
        var d=new StickTestSupport.Driver(8);assertNull(d.action(OBSERVE_STICK_INPUTS).reason());
        assertNull(d.action(MOVE_TO_INPUT).reason());assertNull(d.action(WITHDRAW_OAK_PLANKS).reason());return d;
    }
}
