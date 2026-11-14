package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.SurvivalGateway.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.OakLogTestSupport.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation.*;
import static org.junit.jupiter.api.Assertions.*;

final class PickupDelayGatewayTest {
    static final class Scene {
        final World world=new World(1,0,0);final ValidatedRequest bound=bound(1);final UUID run=UUID.randomUUID();
        final Budgets.Ledger budget=new Budgets.Ledger(OakLogDelivery.executionLimits(CLOCK.millis()).total(),CLOCK);
        final SurvivalGateway gateway;boolean delayed=true;
        Scene() {
            var port=(WorldAccess)Proxy.newProxyInstance(WorldAccess.class.getClassLoader(),new Class<?>[]{WorldAccess.class},(proxy,method,args)->{
                if(method.getName().equals("resourcePickupDelayed"))return delayed;
                try{return method.invoke(world,args);}catch(InvocationTargetException error){throw error.getCause();}
            });
            gateway=new SurvivalGateway(port,(a,e,c)->world.authorized && c.equals(OWNER),new Limits(1,64,64,16,256,1,200,32),CLOCK);
        }
        ActionHandle start(GatewayPrimitives.Operation op) {
            var args=new HashMap<String,Value>();for(var parameter:op.signature().parameters())args.put(parameter.name(),bound.request().arguments().get(parameter.name()));
            return gateway.start(bound,new RunCorrelation(run,new ArtifactRef(OakLogDelivery.ID,"a".repeat(64)),null),op.requirement(),args,budget);
        }
        ActionReceipt action(GatewayPrimitives.Operation op){var h=start(op);for(int n=0;n<200;n++){world.tick++;var r=gateway.poll(h);if(r.terminal())return r;}throw new AssertionError("Finite action failed");}
        void harvest(){assertNull(action(APPROACH_OAK_LOG).reason());assertNull(action(HARVEST_NEXT_OAK_LOG).reason());}
    }
    @Test void nativePickupDelayWaitsWithoutSpendingAnEffectAttemptOrReplacingGoods() {
        var s=new Scene();s.harvest();var h=s.start(PICKUP_TRACKED_OAK_LOGS);long before=s.budget.snapshot().get(Budgets.Kind.ATTEMPTED_EFFECTS);
        for(int n=0;n<10;n++){s.world.tick++;assertFalse(s.gateway.poll(h).terminal());assertEquals(0,s.world.pickups);}
        assertEquals(before,s.budget.snapshot().get(Budgets.Kind.ATTEMPTED_EFFECTS));assertEquals(1,s.world.harvests);
        s.delayed=false;s.world.tick++;var result=s.gateway.poll(h);assertTrue(result.terminal());assertNull(result.reason());assertEquals(1,s.world.pickups);
    }
    @Test void cancellationAndRevocationStillStopTheDelayedPickupBeforeAnyPhysicalCall() {
        var s=new Scene();s.harvest();var h=s.start(PICKUP_TRACKED_OAK_LOGS);s.world.tick++;assertFalse(s.gateway.poll(h).terminal());
        assertEquals(Outcomes.Reason.CANCELLED,s.gateway.cancel(h).reason());s.delayed=false;s.world.tick++;s.gateway.poll(h);assertEquals(0,s.world.pickups);
        var revoked=new Scene();revoked.harvest();var active=revoked.start(PICKUP_TRACKED_OAK_LOGS);revoked.world.tick++;revoked.gateway.poll(active);
        revoked.world.authorized=false;revoked.world.tick++;assertEquals(Outcomes.Reason.AUTHORITY_DENIED,revoked.gateway.poll(active).reason());assertEquals(0,revoked.world.pickups);
    }
}
