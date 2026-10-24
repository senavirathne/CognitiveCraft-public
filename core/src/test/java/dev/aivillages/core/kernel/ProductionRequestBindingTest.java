package dev.aivillages.core.kernel;

import java.util.List;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.WorldReferenceResolver.*;
import static org.junit.jupiter.api.Assertions.*;

/** Injected extraction tests the real host grounding/binding boundary, not an actual model. */
class ProductionRequestBindingTest {
    private static LanguageRequests.Extracted result(long amount) {
        return new LanguageRequests.Extracted(LanguageRequests.Action.MAKE_CRAFTING_TABLES,null,amount,null,null,null,null);
    }
    @Test void originalUsesSufficientBarrelAndProducesAnItemRequestWithoutAnyFacility() {
        var f=new WorldReferenceTypeBindingTest.Fixture();f.put(1,ObjectType.CHEST,64,64);
        f.put(2,ObjectType.BARREL,64,3);f.put(4,ObjectType.BARREL,64,4);f.put(6,ObjectType.BARREL,64,64);
        var v=f.interpret(ProductionRequestLanguageTest.ORIGINAL,result(1));
        assertEquals(LanguageRequests.Phase.SUBMITTED,v.phase(),v.toString());assertEquals(1,f.submissions);
        assertEquals(CraftingTableProduction.ID,f.request.capability());assertEquals(new IntValue(1),f.request.arguments().get("amount"));
        assertEquals(new ContainerValue(f.box(4)),f.request.arguments().get("source"));
        assertEquals(new ContainerValue(f.box(4)),f.request.arguments().get("destination"));
        assertEquals(java.util.Set.of("actor","amount","source","destination"),f.request.arguments().keySet());
    }
    @Test void modelCannotChangeTheGroundedIncrementOrInventConcreteSourceCoordinates() {
        for(var injected:List.of(result(2),new LanguageRequests.Extracted(LanguageRequests.Action.MAKE_CRAFTING_TABLES,null,1L,"1,64,0","1,64,0",null,null))) {
            var f=new WorldReferenceTypeBindingTest.Fixture();f.put(4,ObjectType.BARREL,64,4);
            var v=f.interpret(ProductionRequestLanguageTest.ORIGINAL,injected);
            assertEquals(LanguageRequests.Phase.CLARIFICATION,v.phase());assertEquals(0,f.discoveries);assertEquals(0,f.submissions);
        }
    }
    @Test void insufficientNamedTypeAndIncompleteEvidenceNeverBorrowOtherStock() {
        var f=new WorldReferenceTypeBindingTest.Fixture();f.put(1,ObjectType.CHEST,64,64);f.put(4,ObjectType.BARREL,64,3);
        var v=f.interpret(ProductionRequestLanguageTest.ORIGINAL,result(1));
        assertEquals(LanguageRequests.Phase.REJECTED,v.phase());assertEquals(Outcomes.Reason.RESOURCE_MISSING,v.reason());assertEquals(0,f.submissions);
        var g=new WorldReferenceTypeBindingTest.Fixture();g.put(4,ObjectType.BARREL,64,4);g.unknown.add(g.box(2));
        v=g.interpret(ProductionRequestLanguageTest.ORIGINAL,result(1));
        assertEquals(LanguageRequests.Phase.REJECTED,v.phase());assertEquals(Outcomes.Reason.STALE_OBSERVATION,v.reason());assertEquals(0,g.submissions);
    }
    @Test void rawUnsupportedConstraintsFenceEvenConfidentExtractionBeforeDiscovery() {
        for(String text:List.of("I need another crafting table. Use the planks from Steve's barrel.",
                "I need another crafting table. Use three oak planks from that barrel.",
                "I need another crafting table. Use the birch planks from that barrel.",
                "I need another crafting table from that barrel and place it at 8,64,9.")) {
            var f=new WorldReferenceTypeBindingTest.Fixture();f.put(4,ObjectType.BARREL,64,64);
            var v=f.interpret(text,result(1));assertNotEquals(LanguageRequests.Phase.SUBMITTED,v.phase(),text);
            assertEquals(0,f.discoveries);assertEquals(0,f.submissions);
        }
    }
}
