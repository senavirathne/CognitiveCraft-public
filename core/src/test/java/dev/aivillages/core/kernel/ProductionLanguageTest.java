package dev.aivillages.core.kernel;

import java.util.List;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.LanguageReferenceClassifier.*;
import static dev.aivillages.core.kernel.WorldReferenceResolver.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductionLanguageTest {
    @Test void originalSeparatesTwoIngredientsFromSevenRequestedOutputsAndImplicitActor() {
        var c=classify("Could you turn these two oak logs into seven planks for me?",List.of("Ada"));
        assertEquals(Intent.MAKE_OAK_PLANKS,c.intent());assertEquals(ReferenceState.OMITTED,c.actor().state());
        assertEquals(2L,c.production().inputAmount());assertEquals(7L,c.production().outputAmount());
        assertEquals(ResourceDelivery.OAK_LOG,c.production().input());assertEquals(ResourceDelivery.OAK_PLANKS,c.production().output());
        assertEquals(ReferenceState.EXPLICIT_NEAREST,c.source().state());assertEquals(ReferenceState.OMITTED,c.destination().state());
        assertEquals(ProductionLanguage.Issue.NONE,c.production().issue());
    }
    @Test void transformationVerbsShareRegisteredRecipeAndScalarRolesAcrossQuantities() {
        for(String verb:List.of("turn","convert","transform"))
            for(int quantity:List.of(1,3,4,5,7,8,11,32,63,64)) {
                int input=OakPlankProduction.batches(quantity);
                var c=classify("Bea, "+verb+" "+input+" oak logs into "+quantity+" planks.",List.of("Ada","Bea"));
                assertEquals(Intent.MAKE_OAK_PLANKS,c.intent());assertEquals("Bea",c.actor().first());
                assertEquals((long)input,c.production().inputAmount());assertEquals((long)quantity,c.production().outputAmount());
                assertEquals(ProductionLanguage.Issue.NONE,c.production().issue());
            }
    }
    @Test void outputFirstProductionUsesTheSameIngredientAndProductAbstraction() {
        for(String verb:List.of("make","craft","produce")) {
            var c=classify("Ada, "+verb+" five oak planks from two oak logs and store them.",List.of("Ada"));
            assertEquals(Intent.MAKE_OAK_PLANKS,c.intent());assertEquals(2L,c.production().inputAmount());
            assertEquals(5L,c.production().outputAmount());assertEquals(ProductionLanguage.Issue.NONE,c.production().issue());
        }
        var available=classify("Ada, make seven planks from the available oak logs.",List.of("Ada"));
        assertEquals(Intent.MAKE_OAK_PLANKS,available.intent());assertNull(available.production().inputAmount());
        assertEquals(7L,available.production().outputAmount());
    }
    @Test void registeredSingleInputRecipesDetermineMaterialsRatherThanDefaultingEveryTransformationToOak() {
        var birch=classify("Ada, convert two birch logs into seven planks.",List.of("Ada"));
        assertEquals(Intent.MAKE_BIRCH_PLANKS,birch.intent());assertEquals(ResourceDelivery.BIRCH_PLANKS,birch.production().output());
        var sticks=classify("Ada, transform four oak planks into five sticks.",List.of("Ada"));
        assertEquals(Intent.MAKE_STICKS,sticks.intent());assertEquals(ProductionLanguage.Issue.NONE,sticks.production().issue());
        var table=classify("Ada, turn eight oak planks into two crafting tables.",List.of("Ada"));
        assertEquals(Intent.MAKE_CRAFTING_TABLES,table.intent());assertEquals(ProductionLanguage.Issue.NONE,table.production().issue());
    }
    @Test void inputCountsMustMatchExistingWholeBatchConsumption() {
        for(int input:List.of(1,3)) {
            var c=classify("Ada, convert "+input+" oak logs into seven planks.",List.of("Ada"));
            assertEquals(ProductionLanguage.Issue.INPUT_QUANTITY_MISMATCH,c.production().issue());
        }
        for(String quantity:List.of("0","65","7.5","-7","999999999999999999999")) {
            var c=classify("Ada, turn two oak logs into "+quantity+" planks.",List.of("Ada"));
            assertEquals(ProductionLanguage.Issue.INVALID_QUANTITY,c.production().issue(),quantity);
        }
    }
    @Test void rawSourceDestinationCoordinatesAndTypedNearestArePreserved() {
        var c=classify("Ada, turn two oak logs from this barrel into seven planks and put them in chest at 4,64,9.",List.of("Ada"));
        assertEquals(ObjectType.BARREL,c.source().objectType());assertEquals(ReferenceState.EXPLICIT_NEAREST,c.source().state());
        assertEquals(ReferenceState.EXPLICIT_CONCRETE,c.destination().state());assertEquals("4,64,9",c.destination().first());
        c=classify("Ada, convert two oak logs from chest at 1,64,2 into eight planks and store them.",List.of("Ada"));
        assertEquals("1,64,2",c.source().first());assertEquals(c.source().first(),c.source().second());
        c=classify("Ada, make seven oak planks from two oak logs from my grove.",List.of("Ada"));
        assertEquals(ReferenceState.EXPLICIT_UNSUPPORTED,c.source().state());
        c=classify("Ada, turn two oak logs into seven planks in this barrel.",List.of("Ada"));
        assertEquals(ReferenceState.EXPLICIT_NEAREST,c.destination().state());assertEquals(ObjectType.BARREL,c.destination().objectType());
        c=classify("Ada, transform two oak logs into seven planks into my barrel.",List.of("Ada"));
        assertEquals(ReferenceState.EXPLICIT_UNSUPPORTED,c.destination().state());
        c=classify("Ada, turn two oak logs from this chest in my house into seven planks and put them in that barrel.",List.of("Ada"));
        assertEquals(ReferenceState.EXPLICIT_UNSUPPORTED,c.source().state());
        assertEquals(ReferenceState.EXPLICIT_NEAREST,c.destination().state());
    }
    @Test void negationWrongSpeciesUnsupportedRecipesAndCompoundsStayRefused() {
        for(String text:List.of("Ada, do not turn two oak logs into seven planks.",
                "Ada, turn two birch logs into seven oak planks.","Ada, convert two spruce logs into seven planks.",
                "Ada, transform two oak logs into three iron ingots.","Ada, turn two oak logs into seven planks and four sticks.",
                "Ada, turn two oak logs into seven planks and craft a stone pickaxe."))
            assertEquals(Intent.UNKNOWN,classify(text,List.of("Ada")).intent(),text);
        assertEquals(Intent.UNKNOWN,classify("Could you turn these two oak logs into planks?",List.of("Ada")).intent());
    }
}
