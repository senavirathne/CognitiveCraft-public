package dev.aivillages.core.kernel;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.ResourceLeases.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;

/** Adversarial scheduling against the unchanged authoritative lease writer. */
class ImmediateClaimsTest {
    ResourceLeaseService.ImmediateTicket ticket(ResourceLeaseServiceTest.Fixture f,Owner o,long n) {
        return f.service.immediateTicket(o,List.of(new Demand(f.stock,n)),o.equals(f.alice)?f.a:f.b,()->null);
    }
    Result grant(ResourceLeaseServiceTest.Fixture f,ResourceLeaseService.ImmediateTicket t,Contracts.TrustedContext c) {
        Result r=f.service.acquireImmediate(t,c);assertEquals(Code.PENDING,r.code(),r.toString());
        assertNotNull(r.group());f.ack();r=f.service.acquireImmediate(t,c);assertTrue(r.usable(),r.toString());return r;
    }
    void release(ResourceLeaseServiceTest.Fixture f,ResourceLeaseService.ImmediateTicket t,Contracts.TrustedContext c) {
        assertEquals(Code.PENDING,f.service.completeImmediate(t,c).code());f.ack();
        assertEquals(Code.RELEASED,f.service.completeImmediate(t,c).code());
    }
    @Test void infeasibleOlderFourDoesNotFreezeSmallerWorkAndNeverHoldsAClaim() {
        var f=new ResourceLeaseServiceTest.Fixture();f.quantities.put(f.stock,2L);
        var older=ticket(f,f.alice,4);assertEquals(Code.PENDING,f.service.acquireImmediate(older,f.a).code());
        assertTrue(f.service.snapshot().leases().isEmpty());var small=ticket(f,f.bob,1);
        grant(f,small,f.b);assertEquals(-1,f.service.immediateView(f.a).getFirst().eligibleTick());
    }
    @Test void olderBecomesFeasibleAndWinsEvenWhenNewSmallPollsFirst() {
        var f=new ResourceLeaseServiceTest.Fixture();f.quantities.put(f.stock,2L);
        var older=ticket(f,f.alice,4);f.service.acquireImmediate(older,f.a);
        f.tick=2;f.quantities.put(f.stock,4L);var younger=ticket(f,f.bob,1);
        assertEquals(Code.PENDING,f.service.acquireImmediate(younger,f.b).code());
        assertTrue(f.service.snapshot().leases().isEmpty());grant(f,older,f.a);
        var v=f.service.immediateView(f.a).getFirst();assertEquals(2,v.eligibleTick());assertEquals(2,v.grantedTick());
        assertEquals(0,v.grantedTick()-v.eligibleTick());
    }
    @Test void sixNewSmallArrivalsCannotBypassOlderFeasibleDemand() {
        var f=new ResourceLeaseServiceTest.Fixture();f.quantities.put(f.stock,0L);
        var older=ticket(f,f.alice,4);f.service.acquireImmediate(older,f.a);f.tick=2;f.quantities.put(f.stock,5L);
        for(int i=0;i<6;i++)assertNull(f.service.acquireImmediate(ticket(f,f.bob,1),f.b).group());
        grant(f,older,f.a);assertEquals(1,f.service.snapshot().leases().size());
    }
    @Test void navigationAndInferenceFeasibilityNeverReserveChestOrTable() {
        var f=new ResourceLeaseServiceTest.Fixture();boolean[] reached={false};
        var t=f.service.immediateTicket(f.alice,List.of(new Demand(f.facility(2),1)),f.a,()->reached[0]?null:Reason.TARGET_UNAVAILABLE);
        for(int i=0;i<20;i++){f.tick=i;assertNull(f.service.acquireImmediate(t,f.a).group());}
        assertTrue(f.service.snapshot().leases().isEmpty());reached[0]=true;f.tick=20;grant(f,t,f.a);
    }
    @Test void normalizedGroupOrderIsIndependentOfCallerOrderAndAllOrNothing() {
        var f=new ResourceLeaseServiceTest.Fixture();f.quantities.put(f.stock,3L);
        var t=f.service.immediateTicket(f.alice,List.of(new Demand(f.facility(9),1),new Demand(f.stock,1),new Demand(f.stock,2),new Demand(f.facility(1),1)),f.a,()->null);
        Result r=grant(f,t,f.a);assertEquals(3,r.leases().size());
        var rows=f.service.snapshot().leases();assertEquals(Kind.STOCK,rows.getFirst().resource().kind());assertEquals(3,rows.getFirst().quantity());
        assertTrue(rows.get(1).resource().area().minX()<rows.get(2).resource().area().minX());
    }
    @Test void capacityOrAuthorityLossBetweenAdmissionAndGrantPublishesNothing() {
        var f=new ResourceLeaseServiceTest.Fixture();int[] probes={0};
        var t=f.service.immediateTicket(f.alice,List.of(new Demand(f.stock,3)),f.a,()->++probes[0]==1?null:Reason.AUTHORITY_DENIED);
        Result r=f.service.acquireImmediate(t,f.a);assertEquals(Code.REJECTED,r.code());assertEquals(Reason.AUTHORITY_DENIED,r.reason());
        assertTrue(f.service.snapshot().leases().isEmpty());
    }
    @Test void currentExecutionPinAndPrivateCallerCannotBeReplaced() {
        var f=new ResourceLeaseServiceTest.Fixture();var t=ticket(f,f.alice,3);
        assertEquals(Reason.AUTHORITY_DENIED,f.service.acquireImmediate(t,f.b).reason());
        var old=f.owners.get(f.alice.job());f.owners.put(f.alice.job(),new OwnerFacts(old.origin(),old.worker(),UUID.randomUUID(),true,null));
        assertEquals(Reason.AUTHORITY_DENIED,f.service.acquireImmediate(t,f.a).reason());assertTrue(f.service.immediateView(f.b).isEmpty());
    }
    @Test void staleCompletionCannotReleaseNewTicketAndShortLeaseIsNotRenewed() {
        var f=new ResourceLeaseServiceTest.Fixture();var t=ticket(f,f.alice,3);Result old=grant(f,t,f.a);release(f,t,f.a);
        var fresh=ticket(f,f.alice,2);Result next=grant(f,fresh,f.a);
        assertEquals(Code.STALE,f.service.completeImmediate(t,f.a).code());assertTrue(f.service.validate(next.leases().getFirst(),f.a).usable());
        f.tick=8;assertEquals(Code.EXPIRED,f.service.validate(next.leases().getFirst(),f.a).code());
        assertNotEquals(old.group(),next.group());
    }
    @Test void queueCapIdleExpiryAndOriginalTicketCannotResetAge() {
        var f=new ResourceLeaseServiceTest.Fixture();f.service.fairLimits(new ResourceLeaseService.FairLimits(1,20,5,2,8));
        f.quantities.put(f.stock,0L);var t=ticket(f,f.alice,3);f.service.acquireImmediate(t,f.a);
        assertEquals(Reason.BUDGET_EXHAUSTED,f.service.acquireImmediate(ticket(f,f.bob,1),f.b).reason());
        f.tick=6;f.ack();assertEquals(Code.STALE,f.service.acquireImmediate(t,f.a).code());
        var next=ticket(f,f.bob,1);assertEquals(Code.PENDING,f.service.acquireImmediate(next,f.b).code());
    }
    @Test void budgetFailureInImminentProbeIsTerminalAndSettingsAreFinite() {
        var f=new ResourceLeaseServiceTest.Fixture();var t=f.service.immediateTicket(f.alice,List.of(new Demand(f.stock,1)),f.a,()->{throw new Budgets.Exhausted();});
        assertEquals(Code.REJECTED,f.service.acquireImmediate(t,f.a).code());assertTrue(f.service.snapshot().leases().isEmpty());
        assertThrows(IllegalStateException.class,()->f.service.fairLimits(ResourceLeaseService.FairLimits.defaults()));
        assertThrows(IllegalArgumentException.class,()->new ResourceLeaseService.FairLimits(65,20,5,2,8));
    }
    @Test void newProcessRejectsOldTicketWithoutClearingInterruptedHistory() {
        var f=new ResourceLeaseServiceTest.Fixture();var t=ticket(f,f.alice,3);grant(f,t,f.a);
        f.service=f.make(f.disk,false);f.ack();assertEquals(Code.STALE,f.service.acquireImmediate(t,f.a).code());
        assertEquals(State.INTERRUPTED,f.service.snapshot().leases().getFirst().state());
    }
}
