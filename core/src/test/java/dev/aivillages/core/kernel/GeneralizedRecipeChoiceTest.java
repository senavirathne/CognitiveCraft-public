package dev.aivillages.core.kernel;

import java.util.*;
import org.junit.jupiter.api.Test;
import dev.aivillages.core.kernel.production.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static org.junit.jupiter.api.Assertions.*;

final class GeneralizedRecipeChoiceTest {
    @Test void secondContainerNeedsExplicitSuppliedPolicyAndDistinctStockKeys() {
        var source=RecipeChoiceTest.SOURCE;var second=RecipeChoiceTest.DESTINATION;var a=new RecipeChoice.ItemKey("minecraft:bamboo_planks","{}");var b=new RecipeChoice.ItemKey("minecraft:acacia_planks","{}");var empty=RecipeChoice.ItemKey.EMPTY;
        var choice=new RecipeChoice(new CapabilityId(StickProduction.ID.name(),2),StickProduction.RECIPE,"b".repeat(64),RecipeChoice.RUNTIME,0,2,2,
                List.of(new RecipeChoice.Batch(List.of(a,empty,b,empty),List.of(new RecipeChoice.Binding(0,source,2,1,a),new RecipeChoice.Binding(2,second,26,1,b)))),new RecipeChoice.ItemKey("minecraft:stick","{}"),4,1,List.of());
        var request=RecipeChoiceTest.request(choice,1);assertThrows(IllegalArgumentException.class,()->RecipeChoicePlan.propose(UUID.randomUUID(),0,request,choice));
        var sources=new RecipeSources(List.of(second,source));assertEquals(sources,new RecipeSources(List.of(source,second)));
        var plan=RecipeChoicePlan.propose(UUID.randomUUID(),0,request,choice,sources);assertEquals(2,plan.stock().size());assertNotEquals(plan.stock().get(0).proposedKey(),plan.stock().get(1).proposedKey());
        assertThrows(IllegalArgumentException.class,()->new RecipeSources(List.of(source,source)));assertThrows(IllegalArgumentException.class,()->new RecipeSources(List.of(source,new ContainerRef("minecraft:the_nether",1,2,3))));
    }
    @Test void b03NativeCanonicalBytesKeepTheirExactEncoding() throws Exception {
        try(var input=getClass().getResourceAsStream("/production/b03-birch-q1.json")) {
            var text=new String(Objects.requireNonNull(input).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            var c=RecipeChoice.fromCanonical(text);assertEquals(text,c.canonical());assertNull(c.facility());
            assertEquals("minecraft:birch_log",c.batch(0).grid().getFirst().id());assertFalse(c.canonical().contains("\"facility\""));
        }
    }
    @Test void mixedSourceSlotsDriveExactPrerequisitesWithoutOakSubstitution() {
        var a=new RecipeChoice.ItemKey("minecraft:bamboo_planks","{}");var b=new RecipeChoice.ItemKey("minecraft:acacia_planks","{}");
        var empty=RecipeChoice.ItemKey.EMPTY;var source=RecipeChoiceTest.SOURCE;
        var choice=new RecipeChoice(new CapabilityId(StickProduction.ID.name(),2),StickProduction.RECIPE,"b".repeat(64),RecipeChoice.RUNTIME,0,2,2,
                List.of(new RecipeChoice.Batch(List.of(a,empty,b,empty),List.of(new RecipeChoice.Binding(0,source,2,1,a),new RecipeChoice.Binding(2,source,26,1,b)))),
                new RecipeChoice.ItemKey("minecraft:stick","{}"),4,1,List.of());
        var plan=RecipeChoicePlan.propose(UUID.randomUUID(),0,RecipeChoiceTest.request(choice,1),choice);
        assertEquals(Map.of(a,1L,b,1L),choice.exactInputs());assertEquals(2,plan.stock().size());assertEquals(3,choice.quantities().surplus());
        assertEquals(choice,RecipeChoice.fromCanonical(choice.canonical()));assertTrue(plan.stock().stream().noneMatch(p->p.item().id().contains("oak")));
    }
    @Test void axesRequireBoundThreeByThreeFacilityAndExactRequest() {
        var wood=RecipeChoiceTest.PLANK;var stick=new RecipeChoice.ItemKey("minecraft:stick","{}");var grid=new ArrayList<>(Collections.nCopies(9,RecipeChoice.ItemKey.EMPTY));
        for(int i:new int[]{0,1,3})grid.set(i,wood);for(int i:new int[]{4,7})grid.set(i,stick);
        var bindings=new ArrayList<RecipeChoice.Binding>();for(int i=0;i<9;i++)if(!grid.get(i).empty())bindings.add(new RecipeChoice.Binding(i,RecipeChoiceTest.SOURCE,i,1,grid.get(i)));
        var facility=new RecipeChoice.FacilityBinding(new FacilityRef("minecraft:overworld",2,2,3),"fixture:current-table-not-placement-proof");
        var cap=new CapabilityId(WoodenAxeProduction.ID.name(),2);var result=new RecipeChoice.ItemKey("minecraft:wooden_axe","{}");
        var batches=List.of(new RecipeChoice.Batch(grid,bindings));
        assertThrows(IllegalArgumentException.class,()->new RecipeChoice(cap,WoodenAxeProduction.RECIPE,"a".repeat(64),RecipeChoice.RUNTIME,0,3,3,batches,result,1,1,List.of()));
        var choice=new RecipeChoice(cap,WoodenAxeProduction.RECIPE,"a".repeat(64),RecipeChoice.RUNTIME,0,3,3,batches,result,1,1,List.of(),facility);
        assertEquals(choice,RecipeChoice.fromCanonical(choice.canonical()));var request=RecipeChoiceTest.request(choice,1);
        assertThrows(IllegalArgumentException.class,()->RecipeChoicePlan.propose(UUID.randomUUID(),0,request,choice));
        var args=new HashMap<>(request.request().arguments());args.put("table",new FacilityValue(facility.reference()));
        var bound=new ValidatedRequest(new CapabilityRequest(cap,args),request.context(),request.observation());
        assertEquals(choice.digest(),RecipeChoicePlan.propose(UUID.randomUUID(),0,bound,choice).choiceDigest());
        assertFalse(((RecipeTransaction.ChoiceAuthority)(c,a,t,p)->true).permitsFacility(bound.context(),((ActorValue)args.get("actor")).value(),facility.reference(),RecipeTransaction.ChoicePhase.CRAFT));
    }
}
