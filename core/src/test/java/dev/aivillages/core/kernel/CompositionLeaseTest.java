package dev.aivillages.core.kernel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.ResourceLeases.*;
import static org.junit.jupiter.api.Assertions.*;

final class CompositionLeaseTest {
    @Test void dispatcherValidationInterleavesBothAtomicWithdrawalAcknowledgementsWithoutInventingStock() {
        var s=new AxeLeaseTest.Scene();var world=new AxeTestSupport.World();
        var raw=new SurvivalGateway(world,(a,e,c)->true,new SurvivalGateway.Limits(8,512,64,16,256,1,200,32),s.f.clock);
        var gateway=new LeasedGateway(raw,s.f.service,(b,r)->new LeasedGateway.Binding(s.f.alice,List.of(new Demand(s.planks,12),new Demand(s.sticks,8))),()->s.f.tick,100,200,8,32);
        var usage=new Budgets.Ledger(WoodenAxeProduction.executionLimits(1000).total(),s.f.clock);
        assertEquals(Code.PENDING,gateway.prepare(s.bound,s.correlation()).code());s.f.ack();
        for(var operation:List.of(GatewayPrimitives.Operation.OBSERVE_AXE_INPUTS,GatewayPrimitives.Operation.MOVE_TO_INPUT,GatewayPrimitives.Operation.WITHDRAW_AXE_INPUTS)) {
            var args=new LinkedHashMap<String,Value>();operation.signature().parameters().forEach(p->args.put(p.name(),s.bound.request().arguments().get(p.name())));
            var handle=gateway.start(s.bound,s.correlation(),operation.requirement(),args,usage);ActionReceipt result=null;boolean withdrawalSeen=false;
            for(int i=0;i<25;i++) {
                s.f.tick++;world.tick++;s.f.ack();
                // This is the ordinary dispatcher's pre-poll call. Native source counts have
                // already changed while one or both lease debits still await acknowledgement.
                s.f.quantities.put(s.planks,(long)world.logs);s.f.quantities.put(s.sticks,(long)world.sticks);
                assertTrue(gateway.validate(s.bound,s.correlation()).usable(),"Dispatcher falsely required removed inputs");
                result=gateway.poll(handle);
                if(world.withdrawals>0)withdrawalSeen=true;
                if(result.terminal())break;
            }
            assertNotNull(result);assertTrue(result.terminal());assertNull(result.reason());
            if(operation==GatewayPrimitives.Operation.WITHDRAW_AXE_INPUTS) {
                assertTrue(withdrawalSeen);assertEquals(2,result.resourceReceipts().size());assertEquals(1,world.withdrawals);
            }
        }
        s.f.ack();assertEquals(List.of(6L,9L),s.f.service.snapshot().leases().stream().map(Lease::remaining).sorted().toList());
        assertEquals(9,world.logs);assertEquals(6,world.sticks);
        assertTrue(gateway.validate(s.bound,s.correlation()).usable());
        s.f.quantities.put(s.planks,0L);
        assertFalse(gateway.validate(s.bound,s.correlation()).usable(),"Acknowledged action hid later stock removal");
    }
}
