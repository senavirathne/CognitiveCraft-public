package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.runtime.evidence.DepositProtocol;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.OakLogTestSupport.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation.*;
import static org.junit.jupiter.api.Assertions.*;

/** Ordering fixtures exercise the actual gateway; this fake journal proves no native save. */
final class DepositGatewayTest {
    static final class Protocol implements DepositProtocol {
        final Ticket ticket=new Ticket() { };
        Action action; Request request; Observed observed;
        boolean prepared,resultAcknowledged,forgotten,fail;
        int calls;
        public Ticket begin(Request request,Action action){assertNull(this.action);this.request=request;this.action=action;return ticket;}
        public Progress poll(Ticket ticket) {
            assertSame(this.ticket,ticket);
            if(fail || forgotten || !action.current() && observed==null)return new Progress(Code.FAILED,Outcomes.Reason.INTERRUPTED);
            if(prepared && observed==null){calls++;observed=action.execute();}
            return new Progress(observed!=null && resultAcknowledged?Code.ACKNOWLEDGED:Code.PENDING,null);
        }
        public void forget(Ticket ticket){assertSame(this.ticket,ticket);forgotten=true;}
    }
    static GatewayScene ready(Protocol protocol) {
        var s=new GatewayScene(1,0,0);s.gateway.depositProtocol(protocol);
        for(var op:java.util.List.of(APPROACH_OAK_LOG,HARVEST_NEXT_OAK_LOG,PICKUP_TRACKED_OAK_LOGS,MOVE_TO_DESTINATION))assertNull(s.action(op).reason());
        return s;
    }
    @Test void nativeMutationWaitsForIntentAndReceiptWaitsForResultAcknowledgement() {
        var p=new Protocol();var s=ready(p);var h=s.start(TRANSFER_OAK_LOGS);
        assertFalse(s.gateway.poll(h).terminal());assertEquals(0,s.world.transfers);assertEquals(0,p.calls);
        p.prepared=true;s.world.tick++;var pending=s.gateway.poll(h);
        assertFalse(pending.terminal());assertTrue(pending.resourceReceipts().isEmpty());assertEquals(1,s.world.transfers);assertEquals(1,p.calls);
        for(int n=0;n<3;n++){s.world.tick++;assertTrue(s.gateway.poll(h).resourceReceipts().isEmpty());}
        p.resultAcknowledged=true;s.world.tick++;var answer=s.gateway.poll(h);
        assertTrue(answer.terminal());assertNull(answer.reason());assertEquals(1,answer.resourceReceipts().size());
        assertEquals(p.request.operation(),answer.resourceReceipts().getFirst().operationId());
        assertEquals(s.run,p.request.correlation().runId());assertEquals(s.request,p.request.bound());
        assertEquals(1,p.calls);assertEquals(1,s.world.transfers);assertTrue(p.forgotten);
        s.world.tick++;assertEquals(answer,s.gateway.poll(h));assertEquals(1,p.calls);
    }
    @Test void cancellationBeforePreparedAcknowledgementCannotFallBackToTheOldNativeRoute() {
        var p=new Protocol();var s=ready(p);var h=s.start(TRANSFER_OAK_LOGS);s.gateway.poll(h);
        assertEquals(Outcomes.Reason.CANCELLED,s.gateway.cancel(h).reason());assertTrue(p.forgotten);
        p.prepared=true;p.resultAcknowledged=true;s.world.tick++;
        assertEquals(Outcomes.Reason.CANCELLED,s.gateway.poll(h).reason());assertEquals(0,p.calls);assertEquals(0,s.world.transfers);
    }
    @Test void cancellationAfterMutationKeepsAccountingButCannotExposeAnUnacknowledgedReceipt() {
        var p=new Protocol();var s=ready(p);var h=s.start(TRANSFER_OAK_LOGS);s.gateway.poll(h);
        p.prepared=true;s.world.tick++;s.gateway.poll(h);
        var cancelled=s.gateway.cancel(h);assertEquals(Outcomes.Reason.CANCELLED,cancelled.reason());
        assertEquals(1,cancelled.committedEffects());assertTrue(cancelled.resourceReceipts().isEmpty());
        assertNotNull(p.observed.receipt());assertEquals(1,p.calls);assertEquals(1,s.world.transfers);
        p.resultAcknowledged=true;s.world.tick++;assertEquals(cancelled,s.gateway.poll(h));assertEquals(1,p.calls);
    }
    @Test void journalRefusalDoesNotInvokeTheLegacyTransfer() {
        var p=new Protocol();var s=ready(p);p.fail=true;
        var result=s.action(TRANSFER_OAK_LOGS);assertEquals(Outcomes.Reason.INTERRUPTED,result.reason());
        assertEquals(0,s.world.transfers);assertEquals(0,p.calls);assertEquals(1,s.world.actorLogs);assertEquals(0,s.world.chestLogs);
    }
}
