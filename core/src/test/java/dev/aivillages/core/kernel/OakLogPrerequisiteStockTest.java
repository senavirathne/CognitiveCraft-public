package dev.aivillages.core.kernel;

import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static dev.aivillages.core.kernel.Prerequisites.*;
import static org.junit.jupiter.api.Assertions.*;

/** Historical delivery evidence never becomes a second stock pool for future consumers. */
class OakLogPrerequisiteStockTest {
    private static final String ITEM="minecraft:oak_log";
    private static final ContainerRef BOX=new ContainerRef("minecraft:overworld",1,2,3);
    private static ValidatedRequest bound(ResourceLeaseServiceTest.Fixture f) {
        var source=new Cuboid(BOX.dimension(),4,0,0,11,0,0);
        return new ValidatedRequest(new CapabilityRequest(OakLogDelivery.ID,Map.of(
                "actor",new ActorValue(f.actorA),"amount",new IntValue(1),"source",new AreaValue(source),
                "destination",new ContainerValue(BOX))),f.a,new ObservationRef(UUID.randomUUID(),0,BOX.dimension()));
    }
    private static Need need(ResourceLeaseServiceTest.Fixture f) {
        return new Need(f.alice.job(),new Jobs.Guard(0,0),bound(f),ITEM,1,AllocationPolicy.MATERIAL_ONLY);
    }
    @Test void receiptsRemainEvidenceWhenActualDepositedStockIsGone() {
        var f=new ResourceLeaseServiceTest.Fixture();var resource=ResourceLeases.Resource.oakStock(f.world,BOX);
        var supplies=new LeasePrerequisiteSupplies(f.service,()->f.tick);var request=bound(f);var run=UUID.randomUUID();
        var historical=OakLogTestSupport.chain(request,run,1);
        assertTrue(OakLogDelivery.completed(request,run,historical));
        f.quantities.put(resource,7L);assertEquals(7,supplies.inspect(need(f),ITEM).available());
        assertFalse(OakLogDelivery.completed(request,UUID.randomUUID(),List.of()),"Old physical stock is not newly collected output");
        f.quantities.put(resource,0L);var absent=supplies.inspect(need(f),ITEM);
        assertEquals(ObservationStatus.ABSENT,absent.status());assertEquals(0,absent.available());assertNull(absent.reason());
        assertTrue(OakLogDelivery.completed(request,run,historical),"Historical evidence retains its original meaning");
        assertTrue(f.service.snapshot().leases().isEmpty(),"Inspection itself must not reserve a future consumer's stock");
    }
    @Test void availableOakUnitsShareTheExistingPhysicalLeasePoolAcrossPrincipals() {
        var f=new ResourceLeaseServiceTest.Fixture();var resource=ResourceLeases.Resource.oakStock(f.world,BOX);
        f.quantities.put(resource,5L);assertTrue(f.bob(resource,3).usable());
        var supplies=new LeasePrerequisiteSupplies(f.service,()->f.tick);assertEquals(2,supplies.inspect(need(f),ITEM).available());
        assertEquals(3,f.service.snapshot().leases().getFirst().remaining());
        f.denied.add(f.alice.job());var denied=supplies.inspect(need(f),ITEM);
        assertEquals(0,denied.available());assertEquals(Reason.AUTHORITY_DENIED,denied.reason());
    }
}
