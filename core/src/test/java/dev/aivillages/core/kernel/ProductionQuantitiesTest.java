package dev.aivillages.core.kernel;

import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Prerequisites.*;
import static dev.aivillages.core.kernel.production.BatchQuantities.*;
import dev.aivillages.core.kernel.production.BatchQuantities;
import static org.junit.jupiter.api.Assertions.*;

final class ProductionQuantitiesTest {
    static Stream<Object[]> boundaries() {
        var rows=new ArrayList<Object[]>();
        for(var cap:List.of(OakPlankProduction.ID,BirchPlankProduction.ID,StickProduction.ID,
                CraftingTableProduction.ID,WoodenAxeProduction.ID)) {
            var d=RecipeProduction.of(cap);long y=d.outputPerBatch();
            var quantities=new TreeSet<Long>(List.of(1L,y,y+1,(long)d.maximumAmount()));
            if(y>1)quantities.add(y-1);
            for(long q:quantities)if(q<=d.maximumAmount())for(var role:Role.values())rows.add(new Object[]{cap,q,role});
        }
        return rows.stream();
    }
    @ParameterizedTest @MethodSource("boundaries")
    void everyRegisteredProducerSeparatesDemandBatchOutputAndDelivery(CapabilityId cap,long q,Role role) {
        var d=RecipeProduction.of(cap);var value=d.quantities(q,role);
        assertEquals(q,value.demand());assertEquals(d.batches(q),value.batches());
        assertEquals(Math.multiplyExact(value.batches(),d.outputPerBatch()),value.physicalOutput());
        assertEquals(role==Role.ROOT?q:value.physicalOutput(),value.requiredDelivery());
        assertEquals(q,value.maximumAllocation());assertEquals(value.physicalOutput()-q,value.surplus());
        assertEquals(value.physicalOutput(),Math.addExact(value.maximumAllocation(),value.surplus()));
    }
    @Test void ceilingsDoNotOverflowByAddingYieldBeforeDivision() {
        var exact=BatchQuantities.project(Long.MAX_VALUE,1,Long.MAX_VALUE,Long.MAX_VALUE,Role.ROOT);
        assertEquals(Long.MAX_VALUE,exact.physicalOutput());
        assertThrows(ArithmeticException.class,()->BatchQuantities.project(Long.MAX_VALUE,4,Long.MAX_VALUE,Long.MAX_VALUE,Role.ROOT));
    }
    @Test void invalidNegativeUnsupportedAndForgedQuantitiesRefuse() {
        for(long q:new long[]{Long.MIN_VALUE,-1,0,65,Long.MAX_VALUE})
            assertThrows(IllegalArgumentException.class,()->RecipeProduction.of(BirchPlankProduction.ID).quantities(q,Role.ROOT));
        assertThrows(IllegalArgumentException.class,()->BatchQuantities.project(1,0,64,16,Role.ROOT));
        assertThrows(IllegalArgumentException.class,()->BatchQuantities.project(5,4,64,1,Role.ROOT));
        assertThrows(IllegalArgumentException.class,()->new BatchQuantities(Role.ROOT,3,4,1,4,4,3,1));
        assertThrows(IllegalArgumentException.class,()->new BatchQuantities(Role.MATERIAL_CHILD,3,4,1,4,4,4,1));
    }
    @Test void existingFinalStockCannotSatisfyANewMakeRoot() {
        var f=new ResourceCompositionTest.Fixture(3,BirchPlankProduction.ID);
        f.stock.put(ResourceDelivery.BIRCH_LOG.id(),1L);
        Supplies supplies=(need,item)->{
            var p=f.inspect(need,item);
            return item.equals(f.need.item()) ? new Supply(item,ObservationStatus.PRESENT,50,p.identity(),p.observation(),p.tick(),p.inspected(),null):p;
        };
        var proposal=new DeterministicPrerequisiteResolver(Limits.defaults(),()->f.tick).resolve(
                List.of(f.need),f.catalog,supplies,f::method,n->true,
                new Budgets.Ledger(ResourceComposition.allowance(1000),f.clock));
        assertTrue(proposal.accepted(),String.valueOf(proposal.reason()));
        var root=proposal.nodes().stream().filter(n->n.id().equals(f.id)).findFirst().orElseThrow();
        assertEquals(f.request,root.request().request());assertEquals(3,root.output());
        assertEquals(0,proposal.stock().stream().filter(s->s.proof().item().equals(f.need.item())).mapToLong(StockUse::quantity).sum());
    }
    @Test void childWholeBatchDeliveryIsNotExtraParentCredit() {
        var f=new ResourceCompositionTest.Fixture(1);f.stock.put(ResourceCompositionTest.PLANK,5L);
        var proposal=f.resolve();assertTrue(proposal.accepted());
        var child=proposal.nodes().stream().filter(n->n.item().equals(ResourceCompositionTest.STICK)).findFirst().orElseThrow();
        assertEquals(4,child.output());assertEquals(2,child.surplus());
        assertEquals(2,proposal.allocations().stream().filter(a->a.producer().equals(child.id())).mapToLong(AllocationIntent::quantity).sum());
        assertFalse(proposal.allocations().stream().anyMatch(a->a.producer().equals(f.id)));
    }
}
