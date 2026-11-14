package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.Contracts.ActionHandle;
import dev.aivillages.core.kernel.runtime.evidence.PickupProtocol;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.OakLogTestSupport.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual gateway ordering contracts. The fake port is not native saved evidence. */
final class PickupGatewayTest {
    static final class Protocol implements PickupProtocol {
        final Ticket ticket=new Ticket() { };Request request;Action action;Observed observed;
        boolean prepared,acknowledged,forgotten,fail,unresolved;int calls;
        public Ticket begin(Request request,Action action){assertNull(this.action);this.request=request;this.action=action;return ticket;}
        public Progress poll(Ticket ticket) {
            assertSame(this.ticket,ticket);
            if(fail || forgotten || observed==null && !action.current())return new Progress(Code.FAILED,Outcomes.Reason.INTERRUPTED);
            if(prepared && observed==null){calls++;observed=action.execute();}
            if(unresolved)return new Progress(Code.UNRESOLVED,Outcomes.Reason.INTERRUPTED);
            return new Progress(observed!=null && acknowledged?Code.ACKNOWLEDGED:Code.PENDING,null);
        }
        public void forget(Ticket ticket){assertSame(this.ticket,ticket);forgotten=true;}
    }
    record Pending(GatewayScene scene,ActionHandle handle) { }
    static Pending pending(Protocol protocol) {
        var s=new GatewayScene(2,0,0);s.gateway.pickupProtocol(protocol);assertNull(s.action(APPROACH_OAK_LOG).reason());assertNull(s.action(HARVEST_NEXT_OAK_LOG).reason());
        var h=s.start(PICKUP_TRACKED_OAK_LOGS);
        for(int n=0;n<100 && protocol.action==null;n++){s.world.tick++;assertFalse(s.gateway.poll(h).terminal());}
        assertNotNull(protocol.action);assertEquals(0,s.world.pickups);return new Pending(s,h);
    }
    @Test void nativePickupWaitsForIntentAndReceiptVisibilityWaitsForResultAcknowledgement() {
        var p=new Protocol();var pending=pending(p);var s=pending.scene();var h=pending.handle();
        p.prepared=true;s.world.tick++;var after=s.gateway.poll(h);
        assertEquals(1,s.world.pickups);assertEquals(1,after.committedEffects());assertFalse(after.terminal());assertTrue(after.resourceReceipts().isEmpty());
        for(int n=0;n<4;n++){s.world.tick++;assertFalse(s.gateway.poll(h).terminal());assertTrue(s.gateway.poll(h).resourceReceipts().isEmpty());}
        p.acknowledged=true;s.world.tick++;var answer=s.gateway.poll(h);
        assertTrue(answer.terminal());assertNull(answer.reason());assertEquals(1,answer.resourceReceipts().size());assertTrue(p.forgotten);
        var receipt=answer.resourceReceipts().getFirst();assertEquals(ResourceDelivery.Stage.PICKUP,receipt.stage());
        assertEquals(p.request.operation(),receipt.operationId());assertEquals(p.request.correlation().runId(),receipt.runId());
        assertEquals(p.request.drop(),receipt.dropIds().getFirst());assertEquals(p.request.item(),receipt.item());
        assertEquals(1,p.calls);assertEquals(2L,p.observed.usage().get(Budgets.Kind.COMMITTED_EFFECTS));
        s.world.tick++;assertEquals(answer,s.gateway.poll(h));assertEquals(1,p.calls);
    }
    @Test void cancellationBeforeIntentAcknowledgementCannotInvokeTheLegacyPickup() {
        var p=new Protocol();var pending=pending(p);var s=pending.scene();
        assertEquals(Outcomes.Reason.CANCELLED,s.gateway.cancel(pending.handle()).reason());assertTrue(p.forgotten);
        p.prepared=true;p.acknowledged=true;s.world.tick++;s.gateway.poll(pending.handle());assertEquals(0,p.calls);assertEquals(0,s.world.pickups);
    }
    @Test void cancellationAfterMutationKeepsItsDebitWithoutPublishingAnUnacknowledgedReceipt() {
        var p=new Protocol();var pending=pending(p);var s=pending.scene();p.prepared=true;s.world.tick++;s.gateway.poll(pending.handle());
        var cancelled=s.gateway.cancel(pending.handle());assertEquals(Outcomes.Reason.INTERRUPTED,cancelled.reason());assertEquals(1,cancelled.committedEffects());
        assertTrue(cancelled.resourceReceipts().isEmpty());assertNotNull(p.observed.receipt());
        p.acknowledged=true;s.world.tick++;assertEquals(cancelled,s.gateway.poll(pending.handle()));assertEquals(1,s.world.pickups);assertEquals(0,s.world.transfers);
    }
    @Test void timeoutAfterMutationCannotCloseAnUnacknowledgedPickupAsCertainFailure() {
        var p=new Protocol();var pending=pending(p);var s=pending.scene();p.prepared=true;s.world.tick++;s.gateway.poll(pending.handle());
        s.world.tick+=300;var result=s.gateway.poll(pending.handle());
        assertEquals(Outcomes.Reason.INTERRUPTED,result.reason());assertEquals(1,result.committedEffects());assertTrue(result.resourceReceipts().isEmpty());
        p.acknowledged=true;s.world.tick++;assertEquals(result,s.gateway.poll(pending.handle()));assertEquals(1,p.calls);
    }
    @Test void journalUncertaintyNeverFallsBackToTheOriginalHostOrPublishesAReceipt() {
        var p=new Protocol();var pending=pending(p);var s=pending.scene();p.unresolved=true;s.world.tick++;
        var result=s.gateway.poll(pending.handle());assertEquals(Outcomes.Reason.INTERRUPTED,result.reason());assertTrue(result.resourceReceipts().isEmpty());
        assertEquals(0,p.calls);assertEquals(0,s.world.pickups);assertTrue(p.forgotten);
    }
    @Test void authorityRevocationWhileIntentIsPendingPreventsTheOriginalHostCall() {
        var p=new Protocol();var pending=pending(p);var s=pending.scene();s.world.authorized=false;p.prepared=true;s.world.tick++;
        assertEquals(Outcomes.Reason.AUTHORITY_DENIED,s.gateway.poll(pending.handle()).reason());assertEquals(0,s.world.pickups);assertEquals(0,p.calls);
    }
    @Test void refusedIntentCannotFallBackToAnUnjournaledPickup() {
        var p=new Protocol();var pending=pending(p);p.fail=true;pending.scene().world.tick++;
        assertEquals(Outcomes.Reason.INTERRUPTED,pending.scene().gateway.poll(pending.handle()).reason());assertEquals(0,p.calls);assertEquals(0,pending.scene().world.pickups);
    }
    @Test void originalTwoUnitDemandAndAllowanceAreNotReplacedByTheOnePickupReceipt() {
        var p=new Protocol();var pending=pending(p);var s=pending.scene();
        assertEquals(2,((Contracts.IntValue)p.request.bound().request().arguments().get("amount")).value());
        assertEquals(1L,p.request.usage().getOrDefault(Budgets.Kind.COMMITTED_EFFECTS,0L));
        p.prepared=true;p.acknowledged=true;s.world.tick++;var result=s.gateway.poll(pending.handle());assertEquals(1,result.resourceReceipts().getFirst().quantity());
        assertEquals(2L,s.budget.snapshot().get(Budgets.Kind.COMMITTED_EFFECTS));assertEquals(0,s.world.transfers);
    }
}
