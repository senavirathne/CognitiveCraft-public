package dev.aivillages.core.kernel;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.ResourceLeases.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation.*;
import static dev.aivillages.core.kernel.OakLogTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real lease service/decorator and gateway; logical mechanics are not native save proof. */
final class HarvestLeasePublicationTest {
    static final class Scene {
        final GatewayScene physical;
        final HarvestGatewayTest.Protocol protocol=new HarvestGatewayTest.Protocol();
        final Owner owner=new Owner(UUID.randomUUID(),1);
        final RunCorrelation correlation;
        final ResourceLeaseService leases;
        final LeasedGateway gateway;
        Snapshot disk;
        boolean facility=true;
        ActionHandle handle;
        Scene(int quantity) {
            physical=new GatewayScene(quantity,0,0);
            physical.gateway.harvestProtocol(protocol);
            disk=Snapshot.empty(OWNER.scope().worldId());
            correlation=new RunCorrelation(physical.run,new ArtifactRef(OakLogDelivery.ID,"a".repeat(64)),null);
            leases=new ResourceLeaseService(disk,(expected,next)->{
                assertEquals(disk,expected);disk=next;return CompletableFuture.completedFuture(next);
            },(o,c)->o.equals(owner)&&c.equals(OWNER)
                    ?new OwnerFacts(OWNER,ACTOR,physical.run,physical.world.authorized,
                        physical.world.authorized?null:Outcomes.Reason.AUTHORITY_DENIED):null,
                    (resource,facts)->{
                        long quantityNow=resource.logBlocks()?physical.world.logs.size():
                                resource.kind()==Kind.FACILITY&&!facility?0:1;
                        return new Observation(resource,quantityNow==0?ObservationStatus.ABSENT:ObservationStatus.PRESENT,
                                quantityNow,"unchanged-physical-target",physical.world.tick,1,null);
                    },Settings.production(),()->physical.world.tick,CLOCK,false);
            leases.tick();
            var source=LeasedGateway.sourceResource(OWNER.scope().worldId(),physical.request);
            var demands=List.of(new Demand(source,quantity),
                    new Demand(Resource.space(OWNER.scope().worldId(),SOURCE),1),
                    new Demand(Resource.facility(OWNER.scope().worldId(),BOX),1));
            gateway=new LeasedGateway(physical.gateway,leases,(request,run)->new LeasedGateway.Binding(owner,demands),
                    ()->physical.world.tick,1200,200,4,64);
        }
        void tick(){physical.world.tick++;physical.gateway.tick();leases.tick();gateway.tick();}
        ActionHandle start(GatewayPrimitives.Operation operation) {
            var arguments=new HashMap<String,Value>();
            for(var parameter:operation.signature().parameters())
                arguments.put(parameter.name(),physical.request.request().arguments().get(parameter.name()));
            tick();return gateway.start(physical.request,correlation,operation.requirement(),arguments,physical.budget);
        }
        ActionReceipt poll(ActionHandle action){tick();return gateway.poll(action);}
        ActionReceipt finish(ActionHandle action) {
            for(int n=0;n<205;n++){var answer=poll(action);if(answer.terminal())return answer;}
            throw new AssertionError("Original action did not finish");
        }
        void prepare() {
            assertNull(finish(start(APPROACH_OAK_LOG)).reason());
            handle=start(HARVEST_NEXT_OAK_LOG);
            for(int n=0;n<100&&protocol.action==null;n++)assertFalse(poll(handle).terminal());
            assertNotNull(protocol.action);assertEquals(0,physical.world.harvests);
        }
        ActionReceipt mutate() {
            prepare();protocol.prepared=true;var answer=poll(handle);
            assertFalse(answer.terminal());assertEquals(1,answer.committedEffects());
            assertTrue(answer.resourceReceipts().isEmpty());assertEquals(1,physical.world.harvests);return answer;
        }
    }
    @Test void ownRemovedBlockCannotRevokeTheClaimBeforeItsResultAcknowledgement() {
        var s=new Scene(2);s.mutate();
        assertEquals(1,s.physical.world.logs.size());
        for(int n=0;n<5;n++) {
            assertTrue(s.gateway.validate(s.physical.request,s.correlation).usable());
            var waiting=s.poll(s.handle);assertFalse(waiting.terminal());assertTrue(waiting.resourceReceipts().isEmpty());
        }
        var source=s.leases.snapshot().leases().stream().filter(l->l.resource().logBlocks()).findFirst().orElseThrow();
        assertEquals(2,source.remaining());assertTrue(source.consumed().isEmpty());
        s.protocol.acknowledged=true;var result=s.finish(s.handle);
        assertNull(result.reason());assertEquals(1,result.resourceReceipts().size());assertEquals(1,result.committedEffects());
        assertEquals(2,((IntValue)s.protocol.request.bound().request().arguments().get("amount")).value());
        var consumed=s.leases.snapshot().leases().stream().filter(l->l.resource().logBlocks()).findFirst().orElseThrow();
        assertEquals(1,consumed.remaining());assertEquals(1,consumed.consumed().size());
        assertEquals(1,s.protocol.calls);assertEquals(0,s.physical.world.pickups);assertEquals(0,s.physical.world.transfers);
        assertEquals(result,s.poll(s.handle));assertEquals(1,s.physical.world.harvests);
    }
    @Test void lastRemovedBlockMayFinishAccountingWithoutAnAbsentSourceBeingReharvested() {
        var s=new Scene(1);s.mutate();assertTrue(s.physical.world.logs.isEmpty());
        assertTrue(s.gateway.validate(s.physical.request,s.correlation).usable());
        s.protocol.acknowledged=true;assertNull(s.finish(s.handle).reason());
        assertEquals(0,s.leases.snapshot().leases().stream().filter(l->l.resource().logBlocks()).findFirst().orElseThrow().remaining());
        assertEquals(1,s.physical.world.harvests);
    }
    @Test void stockLostBeforeMutationStillRevokesTheOriginalClaim() {
        var s=new Scene(2);s.prepare();s.physical.world.logs.clear();s.protocol.prepared=true;
        var answer=s.poll(s.handle);assertTrue(answer.terminal());assertEquals(Outcomes.Reason.RESOURCE_MISSING,answer.reason());
        assertEquals(0,answer.committedEffects());assertEquals(0,s.physical.world.harvests);assertTrue(s.protocol.forgotten);
    }
    @Test void cancellationWhileResultIsUnacknowledgedRemainsInterruptedThroughTheDecorator() {
        var s=new Scene(2);s.mutate();var answer=s.gateway.cancel(s.handle);
        assertEquals(Outcomes.Reason.INTERRUPTED,answer.reason());assertEquals(1,answer.committedEffects());assertTrue(answer.resourceReceipts().isEmpty());
        s.protocol.acknowledged=true;assertEquals(answer,s.poll(s.handle));assertEquals(1,s.physical.world.harvests);
    }
    @Test void releaseBeforeDecoratedCancellationCannotEraseTheRawUnpublishedDebit() {
        var s=new Scene(2);s.mutate();s.physical.gateway.releaseRun(s.physical.run);
        var answer=s.gateway.cancel(s.handle);assertEquals(Outcomes.Reason.INTERRUPTED,answer.reason());
        assertEquals(1,answer.committedEffects());assertTrue(answer.resourceReceipts().isEmpty());
    }
    @Test void cancellationBeforeMutationRemainsCertainAndHasNoEffect() {
        var s=new Scene(2);s.prepare();var answer=s.gateway.cancel(s.handle);
        assertEquals(Outcomes.Reason.CANCELLED,answer.reason());assertEquals(0,answer.committedEffects());
        s.protocol.prepared=true;s.protocol.acknowledged=true;assertEquals(answer,s.poll(s.handle));
        assertEquals(0,s.physical.world.harvests);assertEquals(0,s.protocol.calls);
    }
    @Test void revokedOwnerCannotTurnAnAlreadyStartedHarvestIntoCertainFailure() {
        var s=new Scene(2);s.mutate();s.physical.world.authorized=false;var answer=s.finish(s.handle);
        assertTrue(answer.terminal());assertEquals(Outcomes.Reason.INTERRUPTED,answer.reason());
        assertEquals(1,answer.committedEffects());assertTrue(answer.resourceReceipts().isEmpty());assertTrue(s.protocol.forgotten);
    }
    @Test void originalWaitingDeadlineStillStopsWithoutASecondMutation() {
        var s=new Scene(2);s.mutate();s.physical.world.tick+=201;var answer=s.poll(s.handle);
        assertEquals(Outcomes.Reason.INTERRUPTED,answer.reason());assertEquals(1,answer.committedEffects());
        assertTrue(answer.resourceReceipts().isEmpty());assertEquals(1,s.physical.world.harvests);
    }
    @Test void publicationWaitDoesNotSkipTheDestinationClaim() {
        var s=new Scene(2);s.mutate();s.facility=false;var answer=s.poll(s.handle);
        assertTrue(answer.terminal());assertEquals(Outcomes.Reason.INTERRUPTED,answer.reason());
        assertTrue(s.leases.snapshot().leases().stream().anyMatch(l->l.state()==State.REVOKED));
        assertEquals(1,s.physical.world.harvests);assertEquals(0,s.physical.world.transfers);
    }
    @Test void anotherActionCannotUseThePendingHarvestToSkipItsOwnStockValidation() {
        var s=new Scene(2);s.mutate();var other=s.start(HARVEST_NEXT_OAK_LOG);var answer=s.poll(other);
        assertTrue(answer.terminal());assertEquals(Outcomes.Reason.RESOURCE_MISSING,answer.reason());
        assertEquals(1,s.protocol.calls);assertEquals(1,s.physical.world.harvests);assertEquals(0,s.physical.world.pickups);
    }
}
