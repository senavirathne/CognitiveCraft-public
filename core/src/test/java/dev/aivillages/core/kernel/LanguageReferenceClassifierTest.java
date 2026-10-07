package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.aivillages.core.kernel.LanguageReferenceClassifier.*;
import static dev.aivillages.core.kernel.WorldReferenceResolver.ReferenceState;
import static org.junit.jupiter.api.Assertions.*;

class LanguageReferenceClassifierTest {
    @Test void unknownExplicitCitizenWithoutCommaNeverBecomesOmission() {
        for (String text : List.of("Mira harvest 4 wheat", "Could Mira collect 4 wheat",
                "Please have Mira gather 4 wheat", "Ask Mira to harvest 4 wheat")) {
            var c = classify(text, List.of("Ada"));
            assertEquals(ReferenceState.EXPLICIT_CONCRETE, c.actor().state(), text);
            assertEquals("Mira", c.actor().first(), text);
        }
    }

    @Test void explicitWorldMentionsWithoutRoleWordsCannotBecomeOmission() {
        for (String text : List.of("Ada harvest 4 wheat at my farm","Ada harvest 4 wheat near that field",
                "Ada harvest 4 wheat behind my house","Ada harvest 4 wheat using this chest",
                "Ada harvest 4 wheat beside that container","Ada harvest 4 wheat by my barrel")) {
            var c=classify(text,List.of("Ada"));
            assertTrue(c.source().state()==ReferenceState.EXPLICIT_UNSUPPORTED
                    ||c.destination().state()==ReferenceState.EXPLICIT_UNSUPPORTED,text);
        }
    }
    @Test void actorNamesThatAreWorldNounsDoNotInventAWorldReference() {
        var c=classify("Farm, harvest 4 wheat",List.of("Farm"));
        assertEquals(ReferenceState.EXPLICIT_CONCRETE,c.actor().state());
        assertEquals("Farm",c.actor().first());
        assertEquals(ReferenceState.OMITTED,c.source().state());
        assertEquals(ReferenceState.OMITTED,c.destination().state());
    }
    @Test void nearestCitizenIsAnExplicitSelectionPolicy() {
        var c = classify("nearest villager, harvest 4 wheat", List.of());
        assertEquals(ReferenceState.EXPLICIT_NEAREST, c.actor().state());
    }

    @Test void aSupportedSpanCannotHideAnotherExplicitConstraint() {
        for (String text : List.of(
                "Ada harvest 4 wheat from my farm from 0,64,0 through 2,64,2",
                "Ada harvest 4 wheat from nearest field behind my house",
                "Ada harvest 4 wheat and put it into nearest chest behind my house",
                "Ada harvest 4 wheat from 0,64,0 through 2,64,2 from that field")) {
            var c = classify(text, List.of("Ada"));
            assertTrue(c.source().state() == ReferenceState.EXPLICIT_UNSUPPORTED
                    || c.destination().state() == ReferenceState.EXPLICIT_UNSUPPORTED, text);
        }
    }
    @Test void omissionAndExplicitNearestRemainDistinct() {
        var omitted = classify("Ada, harvest 4 wheat", List.of("Ada"));
        assertEquals(ReferenceState.OMITTED, omitted.source().state());
        assertEquals(ReferenceState.OMITTED, omitted.destination().state());

        var nearest = classify("Ada, harvest 4 wheat from the nearest field and put it into the nearest chest",
                List.of("Ada"));
        assertEquals(ReferenceState.EXPLICIT_NEAREST, nearest.source().state());
        assertEquals(ReferenceState.EXPLICIT_NEAREST, nearest.destination().state());
    }

    @Test void explicitCoordinatesArePreservedAsRawGroundedBindings() {
        var c = classify("Ada harvest 4 wheat from 1, 64, 2 through 3,64,4 and deliver to the chest at 8,64,9",
                List.of("Ada"));
        assertEquals(ReferenceState.EXPLICIT_CONCRETE, c.source().state());
        assertEquals("1,64,2", c.source().first());
        assertEquals("3,64,4", c.source().second());
        assertEquals(ReferenceState.EXPLICIT_CONCRETE, c.destination().state());
        assertEquals("8,64,9", c.destination().first());
    }

    @Test void unsupportedExplicitPhrasesNeverBecomeOmission() {
        for (String text : List.of(
                "Ada harvest 4 wheat from my farm",
                "Ada harvest 4 wheat from that field",
                "Ada harvest 4 wheat and put it into this chest",
                "Ada harvest 4 wheat and put it in the chest behind my house")) {
            var c = classify(text, List.of("Ada"));
            assertTrue(c.source().state() == ReferenceState.EXPLICIT_UNSUPPORTED
                    || c.destination().state() == ReferenceState.EXPLICIT_UNSUPPORTED, text);
        }
    }

    @Test void unknownExplicitLeadingCitizenRetainsConcreteProvenanceForScopedLookup() {
        var c = classify("Mira, harvest 4 wheat", List.of("Ada"));
        assertEquals(ReferenceState.EXPLICIT_CONCRETE, c.actor().state());
        assertEquals("Mira", c.actor().first());
    }

    @Test void standaloneUnsupportedDestinationCannotBecomeOmitted() {
        for (String text : List.of(
                "Ada harvest 4 wheat into this chest",
                "Ada harvest 4 wheat into that container",
                "Ada harvest 4 wheat in the chest behind my house")) {
            var c = classify(text, List.of("Ada"));
            assertEquals(ReferenceState.EXPLICIT_UNSUPPORTED, c.destination().state(), text);
        }
    }

    @Test void explicitNamingKeepsExistingCitizenSeparateFromNewName() {
        String id = "01234567-89ab-cdef-0123-456789abcdef";
        var byName = classify("rename Ada to Mira", List.of("Ada"));
        assertEquals(Intent.NAME_CITIZEN, byName.intent());
        assertEquals(ReferenceState.EXPLICIT_CONCRETE, byName.actor().state());
        assertEquals("Ada", byName.actor().first());
        assertEquals("Mira", byName.proposedName());

        var byId = classify("name " + id + " as Ada", List.of());
        assertEquals(ReferenceState.EXPLICIT_CONCRETE, byId.actor().state());
        assertEquals(id, byId.actor().first());
        assertEquals("Ada", byId.proposedName());
    }

    @Test void supportedNamingFormsSeparateNewNameFromImplicitTarget() {
        for (String text : List.of("your name is Ada", "i give you the name Ada", "I name you Ada",
                "name the villager Ada", "give name to the villager as Ada")) {
            var c = classify(text, List.of());
            assertEquals(Intent.NAME_CITIZEN, c.intent(), text);
            assertEquals("Ada", c.proposedName(), text);
            assertEquals(ReferenceState.OMITTED, c.actor().state(), text);
        }
    }
}
