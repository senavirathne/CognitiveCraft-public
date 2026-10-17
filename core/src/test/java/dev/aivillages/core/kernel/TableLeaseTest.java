package dev.aivillages.core.kernel;

import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.ResourceLeases.*;
import static org.junit.jupiter.api.Assertions.*;

class TableLeaseTest {
    static final class Scene {
        final ResourceLeaseServiceTest.Fixture f=new ResourceLeaseServiceTest.Fixture();
        final Resource stock=Resource.plankStock(f.world,TableTestSupport.SOURCE);
        final UUID run=f.owners.get(f.alice.job()).run();
        final ValidatedRequest bound=new ValidatedRequest(new CapabilityRequest(CraftingTableProduction.ID,Map.of("actor",new ActorValue(f.actorA),
                "amount",new IntValue(1),"source",new ContainerValue(TableTestSupport.SOURCE),"destination",new ContainerValue(TableTestSupport.TARGET))),
                f.a,new ObservationRef(UUID.randomUUID(),0,ResourceLeaseServiceTest.DIM));
        Scene(){f.quantities.put(stock,4L);}
        ResourceDelivery.Receipt receipt(){var d=TableProductionTest.withdrawn(1);var r=d.receipts.getFirst();
            return new ResourceDelivery.Receipt(r.receiptId(),f.a,run,r.operationId(),r.batchId(),f.actorA,r.source(),r.destination(),r.item(),r.stage(),r.quantity(),r.sourceBlock(),r.dropIds(),r.transformation());}
        RunCorrelation correlation(){return new RunCorrelation(run,new ArtifactRef(CraftingTableProduction.ID,"a".repeat(64)),null);}
    }
    @Test void fourSuppliedPlanksCannotBeReservedByTwoJobs(){var s=new Scene();s.f.one(s.f.alice(s.stock,4));assertEquals(Code.CONFLICT,s.f.bob(s.stock,1).code());}
    @Test void exactFourInputWithdrawalConsumesTheClaimOnce(){var s=new Scene();var ref=s.f.one(s.f.alice(s.stock,4));var r=s.receipt();
        assertEquals(Code.PENDING,s.f.service.consumedIngredient(ref,s.bound,s.correlation(),r,s.f.a).code());s.f.ack();assertEquals(0,s.f.service.snapshot().leases().getFirst().remaining());
        assertEquals(Code.VALID,s.f.service.consumedIngredient(ref,s.bound,s.correlation(),r,s.f.a).code());assertEquals(1,s.f.service.snapshot().leases().getFirst().consumed().size());}
    @Test void foreignCallerCannotAcknowledgeTheWithdrawal(){var s=new Scene();var ref=s.f.one(s.f.alice(s.stock,4));var before=s.f.service.snapshot();
        assertEquals(Code.REJECTED,s.f.service.consumedIngredient(ref,s.bound,s.correlation(),s.receipt(),s.f.b).code());assertEquals(before,s.f.service.snapshot());}
    @Test void changedRunCannotAcknowledgeTheWithdrawal(){var s=new Scene();var ref=s.f.one(s.f.alice(s.stock,4));
        assertEquals(Code.REJECTED,s.f.service.consumedIngredient(ref,s.bound,new RunCorrelation(UUID.randomUUID(),s.correlation().artifact(),null),s.receipt(),s.f.a).code());}
    @Test void cancellationDoesNotRestoreConsumedPhysicalInputs(){var s=new Scene();var ref=s.f.one(s.f.alice(s.stock,4));s.f.service.consumedIngredient(ref,s.bound,s.correlation(),s.receipt(),s.f.a);s.f.ack();s.f.quantities.put(s.stock,0L);
        assertEquals(Code.PENDING,s.f.service.release(List.of(ref),s.f.a).code());s.f.ack();assertEquals(0,s.f.service.availableStock(s.stock,0,s.f.b).available());assertEquals(1,s.f.service.snapshot().leases().getFirst().consumed().size());}
}
