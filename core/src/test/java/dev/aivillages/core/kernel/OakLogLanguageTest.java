package dev.aivillages.core.kernel;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.aivillages.core.kernel.WorldReferenceResolver.ReferenceState;
import static org.junit.jupiter.api.Assertions.*;

class OakLogLanguageTest {
    @Test void originalDeliveryUsesTheAuthorizedNearestTypedReferencePolicy() {
        var c=LanguageReferenceClassifier.classify("Ada, bring seven oak logs over to that barrel.",List.of("Ada"));
        assertEquals(LanguageReferenceClassifier.Intent.GATHER_OAK_LOGS,c.intent());
        assertEquals("Ada",c.actor().first());
        assertEquals(ReferenceState.OMITTED,c.source().state());
        assertEquals(ReferenceState.EXPLICIT_NEAREST,c.destination().state());
        assertEquals(WorldReferenceResolver.ObjectType.BARREL,c.destination().objectType());
        assertEquals("that barrel",c.destination().evidence());
    }
    @ParameterizedTest
    @ValueSource(strings={
            "Bea, please bring nine oak logs to the nearest suitable barrel.",
            "Could Bea fetch three oak logs from the nearest oak logs and put them into the nearest chest?",
            "Ask Bea to bring two oak logs into the barrel at 13,64,0.",
            "Fetch five oak logs, Bea, from 4,64,0 through 9,64,2; deposit them into the chest at 13,64,0."})
    void deliveryVerbsShareActorAndWorldReferenceProvenance(String text) {
        var c=LanguageReferenceClassifier.classify(text,List.of("Ada","Bea"));
        assertEquals(LanguageReferenceClassifier.Intent.GATHER_OAK_LOGS,c.intent());
        assertEquals("Bea",c.actor().first());
        assertTrue(c.destination().state()==ReferenceState.EXPLICIT_NEAREST || c.destination().state()==ReferenceState.EXPLICIT_CONCRETE);
    }
    @ParameterizedTest
    @ValueSource(strings={"Ada, do not bring 4 oak logs", "Ada, fetch 4 spruce logs",
            "Ada, bring 4 stripped oak logs", "Ada, bring 4 oak and birch logs",
            "Ada, bring 4 oak logs and craft planks", "Ada, fetch 4 oak logs then build a house",
            "Ada, bring 4 oak logs and fetch 2 oak logs"})
    void deliveryAliasesPreserveNegationSpeciesAndCompoundRejection(String text) {
        assertEquals(LanguageReferenceClassifier.Intent.UNKNOWN,LanguageReferenceClassifier.classify(text,List.of("Ada")).intent());
    }
    @Test void namesQuantitiesCoordinatesAndNearestSuitableKeepRawReferenceProvenance() {
        var c=LanguageReferenceClassifier.classify("Ada, collect 4 oak logs and deliver them to the nearest suitable chest.",List.of("Ada"));
        assertEquals(LanguageReferenceClassifier.Intent.GATHER_OAK_LOGS,c.intent());
        assertEquals("Ada",c.actor().first());assertEquals(ReferenceState.OMITTED,c.source().state());
        assertEquals(ReferenceState.EXPLICIT_NEAREST,c.destination().state());
        var explicit=LanguageReferenceClassifier.classify("Bea, gather seven oak logs from 4,64,0 through 9,64,2 and deposit them in the chest at 13,64,0",List.of("Bea"));
        assertEquals(ReferenceState.EXPLICIT_CONCRETE,explicit.source().state());
        assertEquals(ReferenceState.EXPLICIT_CONCRETE,explicit.destination().state());
        assertEquals("4,64,0",explicit.source().first());
    }
    @Test void unsupportedSpeciesActionsAndExplicitLabelsNeverBecomeOmittedDefaults() {
        for(String text:List.of("Ada, collect 4 oak and birch logs","Ada, collect 4 stripped oak logs","Ada, collect 4 oak logs and craft planks"))
            assertEquals(LanguageReferenceClassifier.Intent.UNKNOWN,LanguageReferenceClassifier.classify(text,List.of("Ada")).intent());
        var label=LanguageReferenceClassifier.classify("Ada, collect 4 oak logs from the grove by my house and deliver them to my chest",List.of("Ada"));
        assertEquals(ReferenceState.EXPLICIT_UNSUPPORTED,label.source().state());
        assertEquals(ReferenceState.EXPLICIT_UNSUPPORTED,label.destination().state());
    }
}
