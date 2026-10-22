package dev.aivillages.core.kernel;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.ResourceLeases.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;
import static dev.aivillages.core.kernel.OakLogTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Logical mechanics plus the real lease service/decorator; native physics is tested separately. */
class LeasedDropContinuityTest {
    static final class Scene {
        final GatewayScene physical;
        final ResourceLeaseService leases;
        final LeasedGateway gateway;
        final Owner owner=new Owner(UUID.randomUUID(),0);
        final RunCorrelation correlation;
        final boolean birch;
        Snapshot disk;
        final List<ResourceDelivery.Receipt> receipts=new ArrayList<>();
        Scene(boolean birch) {
            this.birch=birch;
            physical=new GatewayScene(1,2,11,birch?BirchLogDelivery.ID:OakLogDelivery.ID);
            disk=Snapshot.empty(OWNER.scope().worldId());
            correlation=new RunCorrelation(physical.run,new ArtifactRef(physical.request.request().capability(),"a".repeat(64)),null);
            leases=new ResourceLeaseService(disk,(expected,next)->{
                assertEquals(disk,expected);disk=next;return CompletableFuture.completedFuture(next);
            },(o,c)->o.equals(owner)&&c.equals(OWNER)
                    ?new OwnerFacts(OWNER,ACTOR,physical.run,physical.world.authorized,
                        physical.world.authorized?null:Outcomes.Reason.AUTHORITY_DENIED):null,
                    (resource,facts)->{
                        long quantity=resource.kind()==Kind.STOCK?physical.world.logs.size():1;
                        return new Observation(resource,quantity==0?ObservationStatus.ABSENT:ObservationStatus.PRESENT,
                                quantity,"unchanged-physical-target",physical.world.tick,1,null);
                    },Settings.fixture(),()->physical.world.tick,CLOCK,false);
            leases.tick();
            var source=LogAcquisition.blocks(OWNER.scope().worldId(),physical.request.request().capability(),SOURCE);
            var demands=List.of(new Demand(source,1),new Demand(Resource.space(OWNER.scope().worldId(),SOURCE),1),
                    new Demand(Resource.facility(OWNER.scope().worldId(),BOX),1));
            gateway=new LeasedGateway(physical.gateway,leases,(request,run)->new LeasedGateway.Binding(owner,demands),
                    ()->physical.world.tick,100,20,4,64);
        }
        void tick() {physical.world.tick++;physical.gateway.tick();leases.tick();gateway.tick();}
        ActionReceipt action(Operation operation) {
            if(birch)operation=BirchResourceFamilyTest.birch(operation);
            var arguments=new HashMap<String,Value>();
            for(var parameter:operation.signature().parameters())
                arguments.put(parameter.name(),parameter.name().equals("amount")?new IntValue(1):physical.request.request().arguments().get(parameter.name()));
            tick();
            var handle=gateway.start(physical.request,correlation,operation.requirement(),arguments,physical.budget);
            for(int i=0;i<210;i++) {
                tick();var result=gateway.poll(handle);
                if(result.terminal()) {
                    receipts.addAll(result.resourceReceipts());
                    if(result.reason()!=null)System.out.println("A02_LEASE_BASELINE operation="+operation+" tick="+physical.world.tick
                            +" reason="+result.reason()+" harvests="+physical.world.harvests+" pickups="+physical.world.pickups
                            +" deposits="+physical.world.transfers+" leases="+leases.snapshot().leases());
                    return result;
                }
            }
            throw new AssertionError("Original bounded action did not finish");
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void activePickupRetainsValidClaimAcrossTheOriginalLeaseTerm(boolean birch) {
        var s=new Scene(birch);
        assertNull(s.action(Operation.APPROACH_OAK_LOG).reason());
        assertNull(s.action(Operation.HARVEST_NEXT_OAK_LOG).reason());
        UUID drop=s.physical.world.drops.keySet().iterator().next();
        for(int i=0;i<20;i++)assertNull(s.action(Operation.OBSERVE_OAK_INVENTORY).reason(),"Waiting for real owned loot, observation "+i);
        assertTrue(s.physical.world.tick>100,"The lease boundary was not crossed");
        assertEquals(1,s.physical.world.drops.get(drop).quantity());
        assertNull(s.action(Operation.APPROACH_OAK_LOG).reason());
        assertNull(s.action(Operation.PICKUP_TRACKED_OAK_LOGS).reason());
        assertNull(s.action(Operation.MOVE_TO_DESTINATION).reason());
        assertNull(s.action(Operation.TRANSFER_OAK_LOGS).reason());
        assertEquals(1,s.physical.world.harvests);assertEquals(1,s.physical.world.pickups);assertEquals(1,s.physical.world.transfers);
        assertEquals(2,s.physical.world.actorLogs);assertEquals(12,s.physical.world.chestLogs);
        assertFalse(s.physical.world.drops.containsKey(drop));
        assertTrue(ResourceDelivery.completed(s.physical.request,s.physical.run,List.of(),s.receipts));
        assertTrue(s.leases.snapshot().leases().stream().allMatch(l->l.renewals()>0 && l.renewals()<=Settings.fixture().renewals()));
        s.gateway.releaseRun(s.physical.run);
    }
}
