package dev.aivillages.core.kernel;

import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.WorldReferenceResolver.ReferenceState;
import static org.junit.jupiter.api.Assertions.*;

class OakLogLanguageTest {
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
        for(String text:List.of("Ada, collect 4 birch logs","Ada, collect 4 stripped oak logs","Ada, collect 4 oak logs and craft planks"))
            assertEquals(LanguageReferenceClassifier.Intent.UNKNOWN,LanguageReferenceClassifier.classify(text,List.of("Ada")).intent());
        var label=LanguageReferenceClassifier.classify("Ada, collect 4 oak logs from the grove by my house and deliver them to my chest",List.of("Ada"));
        assertEquals(ReferenceState.EXPLICIT_UNSUPPORTED,label.source().state());
        assertEquals(ReferenceState.EXPLICIT_UNSUPPORTED,label.destination().state());
    }
}
