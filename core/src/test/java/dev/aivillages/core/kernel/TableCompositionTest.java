package dev.aivillages.core.kernel;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Prerequisites.*;
import static org.junit.jupiter.api.Assertions.*;

class TableCompositionTest {
    record Case(ResourceCompositionTest.Fixture fixture,Need need,Proposal proposal) { }
    Case resolve(int amount,long planks,long logs,boolean upstream) {
        var f=new ResourceCompositionTest.Fixture(1);f.stock.put(ResourceCompositionTest.PLANK,planks);f.stock.put(ResourceCompositionTest.LOG,logs);
        var args=new HashMap<>(f.request.arguments());args.remove("table");args.put("amount",new IntValue(amount));
        var bound=new ValidatedRequest(new CapabilityRequest(CraftingTableProduction.ID,args),f.owner,f.ref);
        var need=new Need(f.id,f.need.guard(),bound,ResourceDelivery.CRAFTING_TABLE.id(),amount,AllocationPolicy.MATERIAL_ONLY,f.oak);
        var production=RecipeProduction.of(CraftingTableProduction.ID).relationship();
        var recipes=new ArrayList<>(upstream?f.catalog.recipes():List.<Recipe>of());
        var producers=new ArrayList<>(upstream?f.catalog.productions():List.<Production>of());
        recipes.add(new Recipe(production.recipe(),production.output(),1,production.inputs(),Map.of()));producers.add(production);
        var catalog=new Catalog(1,recipes,producers,Set.of(),true);
        var p=new DeterministicPrerequisiteResolver(Limits.defaults(),()->f.tick).resolve(List.of(need),catalog,
                (n,item)->item.equals(ResourceDelivery.CRAFTING_TABLE.id()) ? new Supply(item,ObservationStatus.ABSENT,0,"make-new-tables",f.ref,f.tick,0,null) : f.inspect(n,item),
                f::method,n->true,new Budgets.Ledger(ResourceComposition.allowance(1000),f.clock));
        return new Case(f,need,p);
    }
    @ParameterizedTest @CsvSource({"1,4,0,0,0","1,2,0,1,4","1,0,1,0,4","4,0,0,4,16"})
    void exactShortfallUsesTheExistingGraph(int amount,long planks,long logs,long producedLogs,long producedPlanks) {
        var c=resolve(amount,planks,logs,true);var p=c.proposal();assertTrue(p.accepted(),p.reason()+" "+p.metrics());
        assertEquals(producedLogs,c.fixture().produced(p,ResourceDelivery.OAK_LOG.id()));assertEquals(producedPlanks,c.fixture().produced(p,ResourceDelivery.OAK_PLANKS.id()));
        assertEquals(amount,c.fixture().produced(p,ResourceDelivery.CRAFTING_TABLE.id()));
        var root=p.nodes().stream().filter(n->n.id().equals(c.fixture().id)).findFirst().orElseThrow();
        assertEquals(c.need().bound().request(),root.request().request());assertFalse(root.request().request().arguments().containsKey("table"));
        assertTrue(p.metrics().nodes()<=16);assertTrue(p.metrics().work()<=512);
    }
    @Test void fullyStockedTableWorksWithNoUpstreamProducer() {var c=resolve(1,4,0,false);assertTrue(c.proposal().accepted());assertEquals(1,c.proposal().nodes().size());assertTrue(c.proposal().allocations().isEmpty());}
    @Test void missingMaterialWithoutProducerIsAnHonestBlocker() {var c=resolve(1,3,0,false);assertFalse(c.proposal().accepted());assertTrue(c.proposal().nodes().isEmpty());}
    @Test void registeredItemAndFacilityAreDifferentFacts() {
        assertEquals(List.of(new Ingredient(List.of("minecraft:oak_planks"),4)),RecipeProduction.of(CraftingTableProduction.ID).relationship().inputs());
        assertTrue(ResourceComposition.RECIPE_KEYS.contains(CraftingTableProduction.RECIPE));
        assertFalse(CraftingTableProduction.SPEC.parameters().stream().anyMatch(p->p.type()==Type.FACILITY));
    }
}
