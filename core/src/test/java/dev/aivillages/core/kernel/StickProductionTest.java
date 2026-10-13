package dev.aivillages.core.kernel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.Contracts.*;

final class StickProductionTest {
    @Test void oneFourFiveAndSixtyFourHaveExactBatchCeilings() {
        assertEquals(1,StickProduction.batches(1));assertEquals(1,StickProduction.batches(4));
        assertEquals(2,StickProduction.batches(5));assertEquals(16,StickProduction.batches(64));
    }
    @Test void zeroIsInvalid() {assertThrows(IllegalArgumentException.class,()->StickProduction.batches(0));}
    @Test void negativeIsInvalid() {assertThrows(IllegalArgumentException.class,()->StickProduction.batches(-1));}
    @Test void sixtyFiveIsInvalid() {assertThrows(IllegalArgumentException.class,()->StickProduction.batches(65));}
    @Test void overflowCannotWrapIntoAValidQuantity() {assertThrows(IllegalArgumentException.class,()->StickProduction.batches(Long.MAX_VALUE));}
    @Test void shortagesCountIngredientsAndNeverClampOutput() {assertEquals(1,StickProduction.missingInputs(4,1));assertEquals(0,StickProduction.missingInputs(4,2));}
    @Test void signatureHasTwoContainersAndNoHarvestEffect() {
        assertEquals(Type.CONTAINER,StickProduction.SPEC.parameters().get(2).type());
        assertFalse(StickProduction.SPEC.effects().contains(Effect.HARVEST));assertFalse(StickProduction.SPEC.effects().contains(Effect.PICKUP));
    }
    @Test void outcomeDoesNotContainAnAcquisitionMethod() {
        assertTrue(PlanningGoals.catalog().find(StickProduction.ID).isPresent());
        assertNotEquals(OakLogDelivery.ID,StickProduction.ID);
    }
    @Test void twoPlanksAreRequiredPerFullBatch() {assertEquals(2,StickProduction.inputs(1));assertEquals(2,StickProduction.inputs(4));assertEquals(4,StickProduction.inputs(5));assertEquals(32,StickProduction.inputs(64));}
    @Test void registeredRelationshipIsNarrowOakInputData() {var r=RecipeProduction.of(StickProduction.ID).relationship();assertEquals("minecraft:stick",r.output());assertEquals(2,r.inputs().getFirst().quantity());assertEquals(java.util.List.of("minecraft:oak_planks"),r.inputs().getFirst().alternatives());}
}
