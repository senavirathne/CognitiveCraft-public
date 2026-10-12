package dev.aivillages.core.kernel;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.aivillages.core.kernel.OakLogTestSupport.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.SurvivalGateway.*;
import static org.junit.jupiter.api.Assertions.*;

class OakLogGatewayTest {
    @ParameterizedTest @ValueSource(ints={1,4,7}) void exactAcquisitionKeepsPreloadedAndUnrelatedStock(int quantity) {
        var s=new GatewayScene(quantity,5,9); UUID foreign=UUID.randomUUID();
        s.world.drops.put(foreign,new ItemDropState(ObservationStatus.PRESENT,new Cell(ACTOR.dimension(),4,0,0),17));
        for(int i=0;i<quantity;i++)s.deliverOne();
        assertEquals(5,s.world.actorLogs);assertEquals(9+quantity,s.world.chestLogs);
        assertEquals(quantity,s.world.harvests);assertEquals(quantity,s.world.pickups);assertEquals(quantity,s.world.transfers);
        assertEquals(17,s.world.drops.get(foreign).quantity());
        assertTrue(OakLogDelivery.completed(s.request,s.run,s.receipts));
        assertTrue(s.world.moves>0);assertTrue(s.budget.snapshot().get(Budgets.Kind.ELAPSED_TICKS)>=60L*quantity);
    }
    @Test void timedHarvestCancellationAndRevocationStopBeforeMutation() {
        var s=new GatewayScene(1,0,0);assertNull(s.action(APPROACH_OAK_LOG).reason());
        var h=s.start(HARVEST_NEXT_OAK_LOG);var first=s.gateway.poll(h);assertFalse(first.terminal());
        s.world.tick+=59;assertFalse(s.gateway.poll(h).terminal());assertEquals(0,s.world.harvests);
        s.world.authorized=false;s.world.tick++;assertEquals(Reason.AUTHORITY_DENIED,s.gateway.poll(h).reason());
        assertEquals(0,s.world.harvests);assertEquals(0,s.world.transfers);
        var cancelled=new GatewayScene(1,0,0);cancelled.action(APPROACH_OAK_LOG);var c=cancelled.start(HARVEST_NEXT_OAK_LOG);
        cancelled.gateway.poll(c);assertEquals(Reason.CANCELLED,cancelled.gateway.cancel(c).reason());
        cancelled.world.tick+=100;assertEquals(0,cancelled.gateway.poll(c).committedEffects());
    }
    @Test void scatteredOwnedDropIsApproachedWithoutSelectingForeignStockOrAddingCredit() {
        var s=new GatewayScene(1,0,0);s.action(APPROACH_OAK_LOG);s.action(HARVEST_NEXT_OAK_LOG);
        UUID owned=s.world.drops.keySet().iterator().next(),foreign=UUID.randomUUID();
        s.world.drops.put(owned,new ItemDropState(ObservationStatus.PRESENT,new Cell(ACTOR.dimension(),10,0,0),1));
        s.world.drops.put(foreign,new ItemDropState(ObservationStatus.PRESENT,new Cell(ACTOR.dimension(),0,0,0),17));
        assertNull(s.action(APPROACH_OAK_LOG).reason());assertEquals(9,s.world.actorPosition.x());
        assertEquals(1,s.receipts.size());assertEquals(0,s.world.pickups);assertEquals(0,s.world.actorLogs);
        assertNull(s.action(PICKUP_TRACKED_OAK_LOGS).reason());
        assertEquals(1,s.world.actorLogs);assertEquals(17,s.world.drops.get(foreign).quantity());
        assertFalse(OakLogDelivery.completed(s.request,s.run,s.receipts));
    }
    @Test void fullDestinationOrActorPreventsHarvestAndUnreachableRouteHasNoEffects() {
        for(boolean actor:List.of(true,false)) {
            var s=new GatewayScene(1,0,0);if(actor)s.world.actorCapacity=0;else s.world.capacity=0;
            s.action(APPROACH_OAK_LOG);assertEquals(Reason.TARGET_UNAVAILABLE,s.action(HARVEST_NEXT_OAK_LOG).reason());
            assertEquals(0,s.world.harvests);
        }
        var s=new GatewayScene(1,0,0);s.world.unreachable=true;
        assertEquals(Reason.TARGET_UNAVAILABLE,s.action(APPROACH_OAK_LOG).reason());assertEquals(0,s.world.harvests);
    }
    @Test void inventoryOrDropReplacementRefusesPickupAndDepositCredit() {
        var s=new GatewayScene(1,0,0);s.action(APPROACH_OAK_LOG);s.action(HARVEST_NEXT_OAK_LOG);s.world.actorLogs=1;
        assertEquals(Reason.STALE_OBSERVATION,s.action(PICKUP_TRACKED_OAK_LOGS).reason());assertEquals(0,s.world.pickups);
        var t=new GatewayScene(1,0,0);t.action(APPROACH_OAK_LOG);t.action(HARVEST_NEXT_OAK_LOG);
        UUID drop=t.world.drops.keySet().iterator().next();var old=t.world.drops.get(drop);
        t.world.drops.put(drop,new ItemDropState(old.status(),old.cell(),2));
        assertEquals(Reason.STALE_OBSERVATION,t.action(PICKUP_TRACKED_OAK_LOGS).reason());assertEquals(0,t.world.transfers);
    }
    @Test void noStandingOakCannotBeReplacedByPreloadedInventoryAndWheatPrimitiveIsInvalid() {
        var s=new GatewayScene(1,4,6);s.world.logs.clear();
        assertEquals(Reason.RESOURCE_MISSING,s.action(APPROACH_OAK_LOG).reason());
        assertEquals(Reason.RESOURCE_MISSING,s.action(TRANSFER_OAK_LOGS).reason());
        assertEquals(Reason.REQUEST_INVALID,s.action(HARVEST_NEXT_WHEAT).reason());
        assertEquals(6,s.world.chestLogs);assertFalse(OakLogDelivery.completed(s.request,s.run,s.receipts));
    }
    @Test void exhaustedAllowanceIsNotRefilledByFailedActionOrNewHandle() {
        var s=new GatewayScene(1,0,0);
        s.budget.debit(Budgets.Kind.OBSERVATIONS,4096);
        assertEquals(Reason.BUDGET_EXHAUSTED,s.action(APPROACH_OAK_LOG).reason());
        assertEquals(Reason.BUDGET_EXHAUSTED,s.action(APPROACH_OAK_LOG).reason());
        assertEquals(4096,s.budget.snapshot().get(Budgets.Kind.OBSERVATIONS));assertEquals(0,s.world.harvests);
    }
}
