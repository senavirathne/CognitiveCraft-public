package dev.aivillages.core.kernel;

import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Prerequisites.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static org.junit.jupiter.api.Assertions.*;

/** B00 diagnostic: exercise the real resolver and unchanged authoritative guard. */
final class ProductionPlanningBaselineTest {
    @ParameterizedTest @ValueSource(ints={3,4})
    void capturesOriginalBirchRootProjectionAndAdmission(int requested) {
        var f=new ResourceCompositionTest.Fixture(requested,BirchPlankProduction.ID);
        f.stock.put(ResourceDelivery.BIRCH_LOG.id(),1L);
        var disk=new JobLifecycleStoreTest.Memory(f.world);
        var jobs=new JobLifecycleStore(disk.state,disk,
                JobLifecycleStore.privateJobs((a,c)->c.equals(f.owner)),c->{},
                PlanningGoals.catalog(),JobLifecycleStoreTest.environment(),f.clock,Jobs.Settings.defaults(),false);
        assertTrue(jobs.stageComposition(f.id,f.request,f.owner,f.ref,ResourceComposition.allowance(1000)).accepted());
        jobs.tick();assertTrue(jobs.ready());
        var original=jobs.query(f.id,f.owner);
        var need=new Need(f.id,original.guard(),original.request(),f.need.item(),requested,
                AllocationPolicy.MATERIAL_ONLY,f.oak);
        var p=new DeterministicPrerequisiteResolver(Limits.defaults(),()->f.tick).resolve(
                List.of(need),f.catalog,f::inspect,f::method,n->true,
                new Budgets.Ledger(ResourceComposition.allowance(1000),f.clock));
        assertTrue(p.accepted(),String.valueOf(p.reason()));
        var node=p.nodes().stream().filter(n->n.id().equals(f.id)).findFirst().orElseThrow();
        long projected=((IntValue)node.request().request().arguments().get("amount")).value();
        assertEquals(4,projected);
        var observations=new HashMap<UUID,ObservationRef>();
        p.nodes().forEach(n->observations.put(n.id(),n.request().observation()));
        var result=jobs.admitPrerequisites(p,observations,p.metrics().work(),f.owner);
        if(requested==3) {
            assertEquals(Reason.REQUEST_INVALID,result.reason());assertFalse(result.accepted());
            assertEquals(original,jobs.query(f.id,f.owner));
        } else assertTrue(result.accepted(),String.valueOf(result.reason()));
        System.out.println("B00_PROJECTION requested="+requested+" projected="+projected+
                " batches="+node.batches()+" rootRequestEqual="+f.request.equals(node.request().request())+
                " admission="+result.code()+" reason="+result.reason());
    }
}
