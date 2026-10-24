package dev.aivillages.core.kernel;

import java.util.List;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.LanguageReferenceClassifier.*;
import static dev.aivillages.core.kernel.WorldReferenceResolver.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductionRequestLanguageTest {
    static final String ORIGINAL="I need another crafting table. Use the planks from that barrel.";
    @Test void singularNewOutputAndFollowupIngredientSourceAreIndependentlyGrounded() {
        var c=classify(ORIGINAL,List.of("Ada"));
        assertEquals(Intent.MAKE_CRAFTING_TABLES,c.intent());assertEquals(1L,c.production().outputAmount());
        assertEquals(ProductionLanguage.Issue.NONE,c.production().issue());assertNull(c.production().inputAmount());
        assertEquals("the planks",c.production().inputEvidence());
        assertEquals(ResourceDelivery.OAK_PLANKS,c.production().input());
        assertEquals(ReferenceState.OMITTED,c.actor().state());assertEquals(ReferenceState.EXPLICIT_NEAREST,c.source().state());
        assertEquals(ObjectType.BARREL,c.source().objectType());assertEquals("that barrel",c.source().evidence());
        assertEquals(ReferenceState.OMITTED,c.destination().state());assertEquals(ReferenceState.OMITTED,c.table().state());
    }
    @Test void singularAndAdditionalCountsShareRegisteredProductsAndRequestVerbs() {
        for(String product:List.of("oak plank","birch plank","stick","wooden axe","crafting table"))
            for(String frame:List.of("I need another ","We want a ","Ada, we would like one more ","Craft another ","Produce one more ")) {
                var c=classify(frame+product+".",List.of("Ada"));
                assertNotEquals(Intent.UNKNOWN,c.intent(),frame+product);assertEquals(1L,c.production().outputAmount());
                assertEquals(ProductionLanguage.Issue.NONE,c.production().issue());
            }
        var c=classify("We require three more crafting tables from that barrel.",List.of());
        assertEquals(3L,c.production().outputAmount());assertEquals(ObjectType.BARREL,c.source().objectType());
        assertEquals(ProductionLanguage.Issue.NONE,c.production().issue());
        assertEquals(1L,classify("I want an oak plank.",List.of()).production().outputAmount());
    }
    @Test void explicitInputCountsAndCoordinateRolesSurviveFollowupNormalization() {
        var c=classify("Ada, I want two crafting tables. Use eight oak planks from this barrel at 4,64,9 and put them in chest at 6,64,9.",List.of("Ada"));
        assertEquals(2L,c.production().outputAmount());assertEquals(8L,c.production().inputAmount());
        assertEquals(ProductionLanguage.Issue.NONE,c.production().issue());assertEquals("Ada",c.actor().first());
        assertEquals("4,64,9",c.source().first());assertEquals(c.source().first(),c.source().second());
        assertEquals(ObjectType.BARREL,c.source().objectType());assertEquals("6,64,9",c.destination().first());
        assertEquals(ReferenceState.OMITTED,c.table().state());
    }
    @Test void statedIngredientQuantityAndMaterialCannotBeDropped() {
        var c=classify("I need another crafting table. Use three oak planks from that barrel.",List.of());
        assertEquals(3L,c.production().inputAmount());assertEquals(ProductionLanguage.Issue.INPUT_QUANTITY_MISMATCH,c.production().issue());
        c=classify("I need another crafting table. Use four oak planks from that barrel.",List.of());
        assertEquals(ProductionLanguage.Issue.NONE,c.production().issue());assertEquals(4L,c.production().inputAmount());
        c=classify("I need another crafting table. Use the birch planks from that barrel.",List.of());
        assertEquals(ProductionLanguage.Issue.UNSUPPORTED_RECIPE,c.production().issue());
    }
    @Test void ownershipQualifiersPlacementAndExtraInstructionsStayUnexecutable() {
        for(String tail:List.of(". Use only the planks from that barrel.",". Place it at 8,64,9.",
                " from that barrel and place it at 8,64,9.",". Use the planks from that barrel. Trade with Steve.",
                ". Use the planks from that barrel. Use the planks from this chest.",
                " using the crafting table at 8,64,9."," using my table.")) {
            var c=classify("I need another crafting table"+tail,List.of());
            assertNotEquals(ProductionLanguage.Issue.NONE,c.production().issue(),tail);
        }
        var c=classify("I need another crafting table. Use the planks from Steve's barrel.",List.of());
        assertEquals(ReferenceState.EXPLICIT_UNSUPPORTED,c.source().state());
        c=classify("I need another crafting table. Use the planks from that barrel in my house.",List.of());
        assertEquals(ReferenceState.EXPLICIT_UNSUPPORTED,c.source().state());
    }
    @Test void invalidSingularCountsAndUncountedOrUnknownProductsNeverGainDefaults() {
        for(String amount:List.of("0","5","-1","1.5","999999999999999999999"))
            assertEquals(ProductionLanguage.Issue.INVALID_QUANTITY,classify("We need "+amount+" crafting tables.",List.of()).production().issue());
        assertEquals(ProductionLanguage.Issue.INVALID_QUANTITY,classify("I need another crafting tables.",List.of()).production().issue());
        for(String text:List.of("We need crafting tables.","We need another stone pickaxe.","I do not need another crafting table."))
            assertEquals(Intent.UNKNOWN,classify(text,List.of()).intent());
    }
}
