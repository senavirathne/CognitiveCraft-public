package dev.aivillages.core.kernel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.ResourceLeases.*;
import static org.junit.jupiter.api.Assertions.*;
class AxeLeaseTest {
    static final class Scene {
        final ResourceLeaseServiceTest.Fixture f=new ResourceLeaseServiceTest.Fixture();
        final Resource planks=Resource.plankStock(f.world,AxeTestSupport.SOURCE);
        final Resource sticks=new Resource(f.world,Kind.STOCK,ResourceLeases.point(AxeTestSupport.SOURCE),ResourceDelivery.STICK.id(),-1);
        final UUID run=f.owners.get(f.alice.job()).run();
        final ValidatedRequest bound;
        Scene() {
            var args=new HashMap<>(AxeTestSupport.bound(4).request().arguments());args.put("actor",new ActorValue(f.actorA));
            bound=new ValidatedRequest(new CapabilityRequest(WoodenAxeProduction.ID,args),f.a,new ObservationRef(UUID.randomUUID(),0,ResourceLeaseServiceTest.DIM));
            f.quantities.put(planks,12L);f.quantities.put(sticks,8L);
        }
        RunCorrelation correlation() {return new RunCorrelation(run,new ArtifactRef(WoodenAxeProduction.ID,"c".repeat(64)),null);}
        ResourceDelivery.Receipt receipt(int index) {
            var d=new AxeTestSupport.Driver(4);d.produce();var r=d.receipts.get(index);
            return new ResourceDelivery.Receipt(r.receiptId(),f.a,run,r.operationId(),r.batchId(),f.actorA,r.source(),r.destination(),r.item(),r.stage(),r.quantity(),r.sourceBlock(),List.of(),r.transformation());
        }
    }
    @Test void stockedAxeBindingRespectsUnchangedFourClaimGroup() {
        var scene=new AxeJobLifecycleTest.Scene();var job=scene.assign(scene.create(4));var execution=job.current().executions().getFirst();
        var actual=LeasedGateway.cropWorkspaces(new JobLeaseOwners(scene.jobs,AxeTestSupport.CLOCK),job.current().bound(),new RunCorrelation(execution.runId(),execution.artifact(),null));
        assertEquals(4,actual.demands().size());assertEquals(List.of(8L,12L),actual.demands().stream().filter(d->d.resource().kind()==Kind.STOCK).map(Demand::quantity).sorted().toList());
        assertEquals(1,actual.demands().stream().filter(d->d.resource().item().equals("minecraft:crafting_table")).count());
        assertFalse(actual.demands().stream().anyMatch(d->d.resource().kind()==Kind.FACILITY && d.resource().item().equals("minecraft:storage") && d.resource().area().equals(ResourceLeases.point(AxeTestSupport.SOURCE))));
        var s=new Scene();
        assertEquals(4,ResourceLeases.Settings.production().group());assertEquals(8,ResourceLeases.Settings.production().perJob());
        assertDoesNotThrow(()->new LeasedGateway.Binding(s.f.alice,List.of(new Demand(s.planks,12),new Demand(s.sticks,8),new Demand(Resource.table(s.f.world,AxeTestSupport.TABLE),1),new Demand(Resource.facility(s.f.world,AxeTestSupport.TARGET),1))));
        assertThrows(IllegalArgumentException.class,()->new LeasedGateway.Binding(s.f.alice,List.of(new Demand(s.planks,12),new Demand(s.sticks,8),new Demand(Resource.table(s.f.world,AxeTestSupport.TABLE),1),new Demand(Resource.facility(s.f.world,AxeTestSupport.TARGET),1),new Demand(Resource.facility(s.f.world,AxeTestSupport.SOURCE),1))));
    }
    @Test void plankWithdrawalConsumesThreeExactlyOnce() {var s=new Scene();var ref=s.f.one(s.f.alice(s.planks,12));var r=s.receipt(0);assertEquals(Code.PENDING,s.f.service.consumedIngredient(ref,s.bound,s.correlation(),r,s.f.a).code());s.f.ack();assertEquals(9,s.f.service.snapshot().leases().getFirst().remaining());assertEquals(Code.VALID,s.f.service.consumedIngredient(ref,s.bound,s.correlation(),r,s.f.a).code());assertEquals(9,s.f.service.snapshot().leases().getFirst().remaining());}
    @Test void stickWithdrawalConsumesTwoExactlyOnce() {var s=new Scene();var ref=s.f.one(s.f.alice(s.sticks,8));var r=s.receipt(1);assertEquals(Code.PENDING,s.f.service.consumedIngredient(ref,s.bound,s.correlation(),r,s.f.a).code());s.f.ack();assertEquals(6,s.f.service.snapshot().leases().getFirst().remaining());assertEquals(Code.VALID,s.f.service.consumedIngredient(ref,s.bound,s.correlation(),r,s.f.a).code());}
    @Test void plankReceiptCannotSpendStickClaim() {var s=new Scene();var ref=s.f.one(s.f.alice(s.sticks,8));var before=s.f.service.snapshot();assertEquals(Code.REJECTED,s.f.service.consumedIngredient(ref,s.bound,s.correlation(),s.receipt(0),s.f.a).code());assertEquals(before,s.f.service.snapshot());}
    @Test void competingJobCannotSpendReservedSecondaryStock() {var s=new Scene();s.f.one(s.f.alice(s.sticks,8));assertEquals(Code.CONFLICT,s.f.bob(s.sticks,2).code());assertEquals(0,s.f.service.availableStock(s.sticks,8,s.f.b).available());}
    @Test void foreignPrincipalCannotDebitEitherIngredient() {var s=new Scene();var ref=s.f.one(s.f.alice(s.planks,12));assertEquals(Code.REJECTED,s.f.service.consumedIngredient(ref,s.bound,s.correlation(),s.receipt(0),s.f.b).code());assertEquals(12,s.f.service.snapshot().leases().getFirst().remaining());}
}
