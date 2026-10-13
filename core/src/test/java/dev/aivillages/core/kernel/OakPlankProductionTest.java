package dev.aivillages.core.kernel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.Contracts.*;

final class OakPlankProductionTest {
    @Test void oneFourFiveAndSixtyFourHaveExactBatchCeilings() {
        assertEquals(1,OakPlankProduction.batches(1));assertEquals(1,OakPlankProduction.batches(4));
        assertEquals(2,OakPlankProduction.batches(5));assertEquals(16,OakPlankProduction.batches(64));
    }
    @Test void zeroIsInvalid() {assertThrows(IllegalArgumentException.class,()->OakPlankProduction.batches(0));}
    @Test void negativeIsInvalid() {assertThrows(IllegalArgumentException.class,()->OakPlankProduction.batches(-1));}
    @Test void sixtyFiveIsInvalid() {assertThrows(IllegalArgumentException.class,()->OakPlankProduction.batches(65));}
    @Test void overflowCannotWrapIntoAValidQuantity() {assertThrows(IllegalArgumentException.class,()->OakPlankProduction.batches(Long.MAX_VALUE));}
    @Test void shortagesCountIngredientsAndNeverClampOutput() {assertEquals(1,OakPlankProduction.missingInputs(8,1));assertEquals(0,OakPlankProduction.missingInputs(8,2));}
    @Test void signatureHasTwoContainersAndNoHarvestEffect() {
        assertEquals(Type.CONTAINER,OakPlankProduction.SPEC.parameters().get(2).type());
        assertFalse(OakPlankProduction.SPEC.effects().contains(Effect.HARVEST));assertFalse(OakPlankProduction.SPEC.effects().contains(Effect.PICKUP));
    }
    @Test void outcomeDoesNotContainAnAcquisitionMethod() {
        assertTrue(PlanningGoals.catalog().find(OakPlankProduction.ID).isPresent());
        assertNotEquals(OakLogDelivery.ID,OakPlankProduction.ID);
    }
}
