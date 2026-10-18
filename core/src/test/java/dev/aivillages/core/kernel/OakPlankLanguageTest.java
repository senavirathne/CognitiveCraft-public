package dev.aivillages.core.kernel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.WorldReferenceResolver.ReferenceState;

final class OakPlankLanguageTest {
    @Test void requiredAvailableStockPhraseHasItsOwnProductionIntent() {
        var c=LanguageReferenceClassifier.classify("Ada, make 8 oak planks from the available oak logs and store them.",List.of("Ada"));
        assertEquals(LanguageReferenceClassifier.Intent.MAKE_OAK_PLANKS,c.intent());assertEquals("Ada",c.actor().first());
        assertEquals(ReferenceState.EXPLICIT_NEAREST,c.source().state());assertEquals(ReferenceState.OMITTED,c.destination().state());
    }
    @Test void explicitContainerCoordinatesRemainGrounded() {
        var c=LanguageReferenceClassifier.classify("Ada, craft 5 oak planks from chest at 1,2,3 and store them in chest at 4,5,6.",List.of("Ada"));
        assertEquals(ReferenceState.EXPLICIT_CONCRETE,c.source().state());assertEquals("1,2,3",c.source().first());
        assertEquals("1,2,3",c.source().second());assertEquals("4,5,6",c.destination().first());
    }
    @Test void unsupportedExplicitChestReferenceCannotFallBackToNearest() {
        var c=LanguageReferenceClassifier.classify("Ada, make 5 oak planks from my northern chest and store them.",List.of("Ada"));
        assertEquals(ReferenceState.EXPLICIT_UNSUPPORTED,c.source().state());
    }
    @Test void birchOrAxeIntentDoesNotWidenTheEnvelope() {
        assertEquals(LanguageReferenceClassifier.Intent.MAKE_BIRCH_PLANKS,LanguageReferenceClassifier.classify("Ada, make 8 birch planks and store them.",List.of("Ada")).intent());
        assertEquals(LanguageReferenceClassifier.Intent.UNKNOWN,LanguageReferenceClassifier.classify("Ada, make an axe from oak planks.",List.of("Ada")).intent());
    }
}
