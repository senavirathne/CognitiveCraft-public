package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.Contracts.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the original crop gateway and its genuine legacy receipt type. */
final class CropHarvestGatewayTest {
    record Pending(GatewayChecks.FakeWorld world,SurvivalGateway gateway,HarvestGatewayTest.Protocol protocol,ActionHandle handle){}
    static Pending pending() {
        var world=new GatewayChecks.FakeWorld();world.crops.put(world.source,true);
        var clock=Clock.fixed(Instant.ofEpochMilli(1000),ZoneOffset.UTC);var actor=new ActorRef(UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld");
        var context=new TrustedContext(new PrincipalRef(UUID.randomUUID()),new ScopeRef(UUID.randomUUID(),UUID.randomUUID()));
        var bound=new ValidatedRequest(new CapabilityRequest(CropDelivery.ID,Map.of("actor",new ActorValue(actor),"amount",new IntValue(4),
                "source",new AreaValue(new Cuboid(actor.dimension(),0,64,0,1,64,1)),"destination",new ContainerValue(new ContainerRef(actor.dimension(),2,64,0)))),
                context,new ObservationRef(UUID.randomUUID(),0,actor.dimension()));
        var gateway=new SurvivalGateway(world,(a,e,c)->true,new SurvivalGateway.Limits(2,64,48,2,32,1,100,16),clock);
        var protocol=new HarvestGatewayTest.Protocol();gateway.harvestProtocol(protocol);
        var limits=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);for(var kind:Budgets.Kind.values())limits.put(kind,1000L);
        var budget=new Budgets.Ledger(new Budgets.Limits(limits,100_000),clock);
        var handle=gateway.start(bound,new RunCorrelation(UUID.randomUUID(),new ArtifactRef(CropDelivery.ID,"a".repeat(64)),null),
                GatewayPrimitives.Operation.HARVEST_NEXT_WHEAT.requirement(),Map.of("actor",new ActorValue(actor)),budget);
        assertFalse(gateway.poll(handle).terminal());assertNotNull(protocol.action);return new Pending(world,gateway,protocol,handle);
    }
    @Test void actualCropReceiptIsWithheldUntilResultAcknowledgementAndNeverReplacedByAResourceReceipt() {
        var p=pending();p.protocol.prepared=true;p.world.tick++;var unpublished=p.gateway.poll(p.handle);
        assertEquals(1,p.world.harvests);assertEquals(2,p.world.seedsDropped);assertEquals(1,unpublished.committedEffects());
        assertTrue(unpublished.receipts().isEmpty());assertTrue(unpublished.resourceReceipts().isEmpty());assertNull(p.protocol.observed.receipt());
        assertNotNull(p.protocol.observed.cropReceipt());assertEquals(CropDelivery.Stage.HARVEST,p.protocol.observed.cropReceipt().stage());
        p.protocol.acknowledged=true;p.world.tick++;var result=p.gateway.poll(p.handle);
        assertTrue(result.terminal());assertNull(result.reason());assertEquals(List.of(p.protocol.observed.cropReceipt()),result.receipts());assertTrue(result.resourceReceipts().isEmpty());
    }
    @Test void cancellationAfterCropRemovalPreservesUncertainDebitAndWithholdsTheLegacyReceipt() {
        var p=pending();p.protocol.prepared=true;p.world.tick++;p.gateway.poll(p.handle);
        var cancelled=p.gateway.cancel(p.handle);assertEquals(Outcomes.Reason.INTERRUPTED,cancelled.reason());assertEquals(1,cancelled.committedEffects());
        assertTrue(cancelled.receipts().isEmpty());p.protocol.acknowledged=true;p.world.tick++;assertEquals(cancelled,p.gateway.poll(p.handle));assertEquals(1,p.world.harvests);
    }
}
