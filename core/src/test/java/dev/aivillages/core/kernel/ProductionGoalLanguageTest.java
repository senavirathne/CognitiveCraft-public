package dev.aivillages.core.kernel;

import java.util.List;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.LanguageReferenceClassifier.*;
import static dev.aivillages.core.kernel.WorldReferenceResolver.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductionGoalLanguageTest {
    static final String ORIGINAL="We need three wooden axes. Use what we've already got.";
    @Test void originalRetainsOutputGoalAndExplicitStockReferenceWithoutInventoryFacts() {
        var c=classify(ORIGINAL,List.of("Ada"));assertEquals(Intent.MAKE_WOODEN_AXES,c.intent());
        assertEquals(3L,c.production().outputAmount());assertNull(c.production().inputAmount());
        assertEquals(ReferenceState.OMITTED,c.actor().state());assertEquals(ReferenceState.EXPLICIT_NEAREST,c.source().state());
        assertEquals("what we've already got",c.source().evidence());assertEquals(ReferenceState.OMITTED,c.destination().state());
        assertEquals(ReferenceState.OMITTED,c.table().state());assertEquals(ProductionLanguage.Issue.NONE,c.production().issue());
    }
    @Test void demandVerbsAndQuantitiesShareOneRegisteredProductionGoal() {
        for(String verb:List.of("need","want","require","would like"))for(int q:List.of(1,2,3,4)) {
            var c=classify("Ada, we "+verb+" "+q+" wooden axes using existing stock.",List.of("Ada"));
            assertEquals(Intent.MAKE_WOODEN_AXES,c.intent());assertEquals("Ada",c.actor().first());
            assertEquals((long)q,c.production().outputAmount());assertEquals(ProductionLanguage.Issue.NONE,c.production().issue());
        }
    }
    @Test void stockVocabularyIsSeparateFromTheRequestedProduct() {
        for(String cue:List.of("Use what we already have.","Use the materials we already have.",
                "Use what I've already got.","Use available materials.","Use existing supplies.")) {
            var c=classify("We need two wooden axes. "+cue,List.of("Ada"));
            assertEquals(Intent.MAKE_WOODEN_AXES,c.intent());assertEquals(ReferenceState.EXPLICIT_NEAREST,c.source().state());
            assertEquals(2L,c.production().outputAmount());assertNull(c.production().inputAmount());
        }
        assertEquals(ReferenceState.OMITTED,classify("We need two wooden axes.",List.of("Ada")).source().state());
    }
    @Test void actorContainerFacilityAndCoordinateRolesSurviveGoalNormalization() {
        var c=classify("Unknown, we need three wooden axes from chest at 5,201,5 using crafting table at 7,201,9 and put them in barrel at 9,201,7.",List.of("Ada"));
        assertEquals("Unknown",c.actor().first());assertEquals("5,201,5",c.source().first());assertEquals(c.source().first(),c.source().second());
        assertEquals("7,201,9",c.table().first());assertEquals("9,201,7",c.destination().first());
        assertEquals(ObjectType.CHEST,c.source().objectType());assertEquals(ObjectType.BARREL,c.destination().objectType());
        c=classify("Ada, we would like two wooden axes from this chest and put them in that barrel.",List.of("Ada"));
        assertEquals(ObjectType.CHEST,c.source().objectType());assertEquals(ObjectType.BARREL,c.destination().objectType());
        c=classify("We need two wooden axes from this chest in my house and put them in that barrel.",List.of("Ada"));
        assertEquals(ReferenceState.EXPLICIT_UNSUPPORTED,c.source().state());
    }
    @Test void registeredOutputSelectionIsCommonAcrossExplicitAndSingularNewOutputGoals() {
        assertEquals(Intent.MAKE_OAK_PLANKS,classify("We need seven oak planks. Use available materials.",List.of()).intent());
        assertEquals(Intent.MAKE_STICKS,classify("I want six sticks using existing stock.",List.of()).intent());
        assertEquals(Intent.MAKE_CRAFTING_TABLES,classify("We require two crafting tables.",List.of()).intent());
        assertEquals(Intent.MAKE_CRAFTING_TABLES,classify("I need another crafting table. Use the planks from that barrel.",List.of()).intent());
    }
    @Test void invalidAmountsUnknownInventoryConstraintsAndExtraInstructionsStayUnexecutable() {
        for(String amount:List.of("0","5","-1","1.5","999999999999999999999"))
            assertEquals(ProductionLanguage.Issue.INVALID_QUANTITY,classify("We need "+amount+" wooden axes.",List.of()).production().issue());
        for(String tail:List.of(" using my stock.",". Use what Ada already has."," and trade with someone.",
                ". Use available materials. Buy more."," using only existing stock."))
            assertEquals(ProductionLanguage.Issue.UNSUPPORTED_RECIPE,classify("We need three wooden axes"+tail,List.of()).production().issue(),tail);
    }
    @Test void unsupportedMaterialsNegationMissingQuantitiesAndCompoundActionsRemainUnknown() {
        for(String text:List.of("We need stone axes.","We need three stone axes.","We want two stone pickaxes.",
                "We do not need three wooden axes.","We need wooden axes.","We need three wooden axes and build a house."))
            assertEquals(Intent.UNKNOWN,classify(text,List.of()).intent(),text);
    }
}
