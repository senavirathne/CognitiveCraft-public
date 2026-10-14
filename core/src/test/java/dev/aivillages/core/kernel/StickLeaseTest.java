package dev.aivillages.core.kernel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.ResourceLeases.*;
import static org.junit.jupiter.api.Assertions.*;

class StickLeaseTest {
    static final class Scene {
        final ResourceLeaseServiceTest.Fixture f=new ResourceLeaseServiceTest.Fixture();
        final Resource stock=Resource.plankStock(f.world,StickTestSupport.SOURCE);
        final UUID run=f.owners.get(f.alice.job()).run();
        final ValidatedRequest bound=new ValidatedRequest(new CapabilityRequest(StickProduction.ID,Map.of("actor",new ActorValue(f.actorA),
                "amount",new IntValue(8),"source",new ContainerValue(StickTestSupport.SOURCE),"destination",new ContainerValue(StickTestSupport.TARGET))),
                f.a,new ObservationRef(UUID.randomUUID(),0,ResourceLeaseServiceTest.DIM));
        ResourceDelivery.Receipt receipt() {
            var d=new StickTestSupport.Driver(8);d.produce();var r=d.receipts.getFirst();
            return new ResourceDelivery.Receipt(r.receiptId(),f.a,run,r.operationId(),r.batchId(),f.actorA,r.source(),r.destination(),r.item(),r.stage(),r.quantity(),r.sourceBlock(),r.dropIds(),r.transformation());
        }
        RunCorrelation correlation() {return new RunCorrelation(run,new ArtifactRef(StickProduction.ID,"c".repeat(64)),null);}
    }
    @Test void knownReservedStockIsExcludedBeforeAnyGenerationOrEffects() {
        var s=new Scene();s.f.quantities.put(s.stock,4L);s.f.one(s.f.bob(s.stock,2));
        var v=s.f.service.availableStock(s.stock,4,s.f.a);assertTrue(v.usable());assertEquals(2,v.available());
        assertEquals(2,StickProduction.missingInputs(8,v.available()));assertEquals(1,s.f.service.snapshot().leases().size());
    }
    @Test void competingPrivateRequestsCannotSpendOneInputTwice() {
        var s=new Scene();s.f.quantities.put(s.stock,4L);s.f.one(s.f.alice(s.stock,4));
        var rejected=s.f.bob(s.stock,2);assertEquals(Code.CONFLICT,rejected.code());assertEquals(0,rejected.available());assertTrue(rejected.leases().isEmpty());
    }
    @Test void realWithdrawalDebitsOnceAndReplayDoesNotDebitAgain() {
        var s=new Scene();s.f.quantities.put(s.stock,4L);var ref=s.f.one(s.f.alice(s.stock,4));var r=s.receipt();
        assertEquals(Code.PENDING,s.f.service.consumedIngredient(ref,s.bound,s.correlation(),r,s.f.a).code());s.f.ack();
        assertEquals(2,s.f.service.snapshot().leases().getFirst().remaining());
        assertEquals(Code.VALID,s.f.service.consumedIngredient(ref,s.bound,s.correlation(),r,s.f.a).code());
        assertEquals(2,s.f.service.snapshot().leases().getFirst().remaining());
    }
    @Test void foreignPrincipalCannotDebitOwnedInputClaim() {
        var s=new Scene();var ref=s.f.one(s.f.alice(s.stock,4));var before=s.f.service.snapshot();
        assertEquals(Code.REJECTED,s.f.service.consumedIngredient(ref,s.bound,s.correlation(),s.receipt(),s.f.b).code());assertEquals(before,s.f.service.snapshot());
    }
    @Test void anotherRunCannotUseCurrentInputClaim() {
        var s=new Scene();var ref=s.f.one(s.f.alice(s.stock,4));var before=s.f.service.snapshot();
        var other=new RunCorrelation(UUID.randomUUID(),s.correlation().artifact(),null);
        assertEquals(Code.REJECTED,s.f.service.consumedIngredient(ref,s.bound,other,s.receipt(),s.f.a).code());assertEquals(before,s.f.service.snapshot());
    }
    @Test void cancellationReleasesFutureInputsWithoutRestoringSpentMaterial() {
        var s=new Scene();s.f.quantities.put(s.stock,4L);var ref=s.f.one(s.f.alice(s.stock,4));
        s.f.service.consumedIngredient(ref,s.bound,s.correlation(),s.receipt(),s.f.a);s.f.ack();s.f.quantities.put(s.stock,1L);
        assertEquals(Code.PENDING,s.f.service.release(List.of(ref),s.f.a).code());s.f.ack();
        assertEquals(1,s.f.service.availableStock(s.stock,1,s.f.b).available());assertEquals(1,s.f.service.snapshot().leases().getFirst().consumed().size());
    }
}
