package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.Contracts.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.ResourceLeases.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the original crop gateway and its genuine legacy receipt type. */
final class CropHarvestGatewayTest {
    record Pending(GatewayChecks.FakeWorld world,SurvivalGateway gateway,HarvestGatewayTest.Protocol protocol,ActionHandle handle){}
    private static final class Scene {
        final GatewayChecks.FakeWorld world=new GatewayChecks.FakeWorld();
        final Clock clock=Clock.fixed(Instant.ofEpochMilli(1000),ZoneOffset.UTC);
        final ActorRef actor=new ActorRef(UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld");
        final TrustedContext origin=new TrustedContext(new PrincipalRef(UUID.randomUUID()),new ScopeRef(UUID.randomUUID(),UUID.randomUUID()));
        final Cuboid source=new Cuboid(actor.dimension(),0,64,0,1,64,1);
        final ContainerRef destination=new ContainerRef(actor.dimension(),2,64,0);
        final ValidatedRequest request=new ValidatedRequest(new CapabilityRequest(CropDelivery.ID,
                Map.of("actor",new ActorValue(actor),"amount",new IntValue(1),"source",new AreaValue(source),
                        "destination",new ContainerValue(destination))),origin,new ObservationRef(UUID.randomUUID(),0,actor.dimension()));
        final RunCorrelation run=new RunCorrelation(UUID.randomUUID(),new ArtifactRef(CropDelivery.ID,"a".repeat(64)),null);
        final SurvivalGateway nativeGateway=new SurvivalGateway(world,(a,e,c)->true,new SurvivalGateway.Limits(2,64,48,2,32,1,100,16),clock);
        final HarvestGatewayTest.Protocol protocol=new HarvestGatewayTest.Protocol();

        Scene() {
            world.crops.put(world.source,true);
            nativeGateway.harvestProtocol(protocol);
        }

        ActionHandle start(GatewayPort gateway) {
            var limits=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);
            for(var kind:Budgets.Kind.values())limits.put(kind,1000L);
            var budget=new Budgets.Ledger(new Budgets.Limits(limits,100_000),clock);
            return gateway.start(request,run,GatewayPrimitives.Operation.HARVEST_NEXT_WHEAT.requirement(),
                    Map.of("actor",new ActorValue(actor),"source",new AreaValue(source)),budget);
        }
    }
    static Pending pending() {
        var scene=new Scene();
        var gateway=scene.nativeGateway;
        var protocol=scene.protocol;
        var handle=scene.start(gateway);
        var waiting=gateway.poll(handle);assertFalse(waiting.terminal(),()->"Crop evidence preparation: "+waiting);assertNotNull(protocol.action);return new Pending(scene.world,gateway,protocol,handle);
    }
    @Test void actualCropReceiptIsWithheldUntilResultAcknowledgementAndNeverReplacedByAResourceReceipt() {
        var p=pending();p.protocol.prepared=true;p.world.tick++;var unpublished=p.gateway.poll(p.handle);
        assertEquals(1,p.world.harvests);assertEquals(2,p.world.seedsDropped);assertEquals(1,unpublished.committedEffects());
        assertTrue(unpublished.cropReceipts().isEmpty());assertTrue(unpublished.resourceReceipts().isEmpty());assertNull(p.protocol.observed.receipt());
        assertNotNull(p.protocol.observed.cropReceipt());assertEquals(CropDelivery.Stage.HARVEST,p.protocol.observed.cropReceipt().stage());
        p.protocol.acknowledged=true;p.world.tick++;var result=p.gateway.poll(p.handle);
        assertTrue(result.terminal());assertNull(result.reason());assertEquals(List.of(p.protocol.observed.cropReceipt()),result.cropReceipts());assertTrue(result.resourceReceipts().isEmpty());
    }
    @Test void cancellationAfterCropRemovalPreservesUncertainDebitAndWithholdsTheLegacyReceipt() {
        var p=pending();p.protocol.prepared=true;p.world.tick++;p.gateway.poll(p.handle);
        var cancelled=p.gateway.cancel(p.handle);assertEquals(Outcomes.Reason.INTERRUPTED,cancelled.reason());assertEquals(1,cancelled.committedEffects());
        assertTrue(cancelled.cropReceipts().isEmpty());p.protocol.acknowledged=true;p.world.tick++;assertEquals(cancelled,p.gateway.poll(p.handle));assertEquals(1,p.world.harvests);
    }
    @Test void originalCropClaimSurvivesTheRemovalAndConsumesOnlyTheAcknowledgedHarvest() {
        var scene=new Scene();
        var worldId=scene.origin.scope().worldId();
        var owner=new Owner(UUID.randomUUID(),1);
        var crop=Resource.crops(worldId,scene.source);
        var facility=Resource.facility(worldId,scene.destination);
        var leases=new ResourceLeaseService(Snapshot.empty(worldId),(before,after)->CompletableFuture.completedFuture(after),
                (id,caller)->id.equals(owner) && caller.equals(scene.origin)
                        ?new OwnerFacts(scene.origin,scene.actor,scene.run.runId(),true,null):null,
                (resource,facts)->{
                    long quantity=resource.equals(crop)?scene.world.crops.size():1;
                    return new Observation(resource,quantity==0?ObservationStatus.ABSENT:ObservationStatus.PRESENT,
                            quantity,"observed-native-target",scene.world.tick,1,null);
                },Settings.production(),()->scene.world.tick,scene.clock,false);
        var gateway=new LeasedGateway(scene.nativeGateway,leases,(bound,run)->new LeasedGateway.Binding(owner,
                List.of(new Demand(crop,1),new Demand(facility,1))),()->scene.world.tick,1200,100,4,64);
        var handle=scene.start(gateway);
        for(int n=0;n<8 && scene.protocol.action==null;n++) {
            scene.world.tick++;
            leases.tick();
            assertFalse(gateway.poll(handle).terminal());
        }
        assertNotNull(scene.protocol.action);
        scene.protocol.prepared=true;
        scene.world.tick++;
        assertFalse(gateway.poll(handle).terminal());
        assertTrue(scene.world.crops.isEmpty());
        assertTrue(gateway.validate(scene.request,scene.run).usable());
        var reserved=leases.snapshot().leases().stream().filter(lease->lease.resource().equals(crop)).findFirst().orElseThrow();
        assertEquals(1,reserved.remaining());
        assertTrue(reserved.consumed().isEmpty());

        scene.protocol.acknowledged=true;
        scene.world.tick++;
        assertFalse(gateway.poll(handle).terminal());
        leases.tick();
        scene.world.tick++;
        var result=gateway.poll(handle);
        assertTrue(result.terminal());
        assertNull(result.reason());
        assertEquals(List.of(scene.protocol.observed.cropReceipt()),result.cropReceipts());
        var consumed=leases.snapshot().leases().stream().filter(lease->lease.resource().equals(crop)).findFirst().orElseThrow();
        assertEquals(0,consumed.remaining());
        assertEquals(1,consumed.consumed().size());
        assertEquals(1,scene.world.harvests);
        assertTrue(gateway.validate(scene.request,scene.run).usable());
    }
}
