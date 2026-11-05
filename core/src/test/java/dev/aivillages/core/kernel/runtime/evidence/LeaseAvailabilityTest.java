package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation.*;
import static org.junit.jupiter.api.Assertions.*;

/** Initial world availability never grants an effect, resets a wait, or suppresses cancellation. */
final class LeaseAvailabilityTest {
    @Test void unknownNativePermissionWaitsForAnActualUsableCohortWithoutGrantingClaims() {
        var scene = new HarvestLeasePublicationTest.Scene(2);
        scene.available = false;
        scene.unavailableReason = Outcomes.Reason.AUTHORITY_DENIED;
        var action = scene.start(APPROACH_OAK_LOG);
        for (int n = 0; n < 4; n++) {
            assertFalse(scene.poll(action).terminal());
            assertTrue(scene.leases.snapshot().leases().isEmpty());
        }
        assertEquals(0, scene.physical.world.harvests);
        scene.available = true;
        assertNull(scene.finish(action).reason());

        var lost = new HarvestLeasePublicationTest.Scene(2);
        lost.available = false;
        lost.unavailableReason = Outcomes.Reason.AUTHORITY_DENIED;
        var pending = lost.start(APPROACH_OAK_LOG);
        lost.poll(pending);
        lost.physical.world.authorized = false;
        assertEquals(Outcomes.Reason.AUTHORITY_DENIED, lost.poll(pending).reason());
        assertTrue(lost.leases.snapshot().leases().isEmpty());
    }

    @Test void unknownDeniedObservationCannotExtendTheOriginalWaitOrCreateLaterClaims() {
        var scene = new HarvestLeasePublicationTest.Scene(2);
        scene.available = false;
        scene.unavailableReason = Outcomes.Reason.AUTHORITY_DENIED;
        var result = scene.finish(scene.start(APPROACH_OAK_LOG));
        assertEquals(Outcomes.Reason.ACTION_TIMEOUT, result.reason());
        scene.available = true;
        scene.tick();
        assertEquals(result, scene.gateway.poll(result.handle()));
        assertTrue(scene.leases.snapshot().leases().isEmpty());
        assertEquals(0, scene.physical.world.harvests);
    }
    @Test void unavailableInitialCohortWaitsAndReobservesWithinTheOriginalAction() {
        var s=new HarvestLeasePublicationTest.Scene(2);s.available=false;var action=s.start(APPROACH_OAK_LOG);
        for(int n=0;n<5;n++){assertFalse(s.poll(action).terminal());assertTrue(s.leases.snapshot().leases().isEmpty());}
        long elapsed=s.physical.budget.snapshot().getOrDefault(Budgets.Kind.ELAPSED_TICKS,0L);assertTrue(elapsed>=5);assertEquals(0,s.physical.world.harvests);
        s.available=true;var result=s.finish(action);assertTrue(result.terminal());assertNull(result.reason());assertFalse(s.leases.snapshot().leases().isEmpty());
        assertTrue(s.physical.budget.snapshot().getOrDefault(Budgets.Kind.ELAPSED_TICKS,0L)>=elapsed);assertEquals(0,s.physical.world.harvests);
    }
    @Test void unavailableCohortCannotWaitPastItsOriginalBound() {
        var s=new HarvestLeasePublicationTest.Scene(2);s.available=false;var result=s.finish(s.start(APPROACH_OAK_LOG));
        assertEquals(Outcomes.Reason.ACTION_TIMEOUT,result.reason());assertEquals(0,result.committedEffects());assertTrue(s.leases.snapshot().leases().isEmpty());
        assertEquals(0,s.physical.world.harvests);s.available=true;s.tick();assertEquals(result,s.gateway.poll(result.handle()));assertTrue(s.leases.snapshot().leases().isEmpty());
    }
    @Test void cancellationAndAuthorityLossStopTheSamePendingActionWithoutClaimsOrEffects() {
        var s=new HarvestLeasePublicationTest.Scene(2);s.available=false;var action=s.start(APPROACH_OAK_LOG);assertFalse(s.poll(action).terminal());
        assertEquals(Outcomes.Reason.CANCELLED,s.gateway.cancel(action).reason());s.available=true;s.tick();assertTrue(s.gateway.poll(action).terminal());assertTrue(s.leases.snapshot().leases().isEmpty());
        var lost=new HarvestLeasePublicationTest.Scene(2);lost.available=false;var pending=lost.start(APPROACH_OAK_LOG);lost.poll(pending);
        lost.physical.world.authorized=false;assertEquals(Outcomes.Reason.AUTHORITY_DENIED,lost.poll(pending).reason());assertTrue(lost.leases.snapshot().leases().isEmpty());
    }
}
