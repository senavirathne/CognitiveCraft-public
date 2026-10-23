package dev.aivillages.core.kernel;

import java.util.List;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.WorldReferenceResolver.*;
import static dev.aivillages.core.kernel.LanguageReferenceClassifier.*;
import static org.junit.jupiter.api.Assertions.*;

class SpatialReferenceLanguageTest {
    @Test void commonNearestSynonymsRetainExplicitActorAnchorAndObjectType() {
        for(String adjective:List.of("nearest","closest"))
            for(String noun:List.of("chest","barrel","container"))
                for(String phrase:List.of("the "+noun+" "+adjective+" to you","that "+adjective+" "+noun+" to you")) {
                    var slot=WorldReferenceLanguage.container(phrase);
                    assertEquals(ReferenceState.EXPLICIT_NEAREST,slot.state(),phrase);
                    assertEquals(ReferenceAnchor.ACTOR,slot.anchor(),phrase);
                    assertEquals(ObjectType.valueOf(noun.toUpperCase(java.util.Locale.ROOT)),slot.objectType());
                    assertEquals(phrase,slot.evidence());assertNull(slot.first());assertNull(slot.second());
                }
    }
    @Test void samePolicyAppliesAcrossExistingActionsInputAreasAndFacilities() {
        for(String action:List.of("harvest 4 wheat","bring 4 oak logs","gather 4 birch logs",
                "make 4 oak planks","make 4 birch planks","make 4 sticks","make 4 wooden axes","make 4 crafting tables")) {
            var c=classify("Ada, "+action+" and put them in the chest closest to you.",List.of("Ada"));
            assertNotEquals(Intent.UNKNOWN,c.intent());assertEquals(ReferenceAnchor.ACTOR,c.destination().anchor());
        }
        var c=classify("Ada, make 2 wooden axes from the barrel nearest to you using the crafting table closest to you and put them in the chest closest to you.",List.of("Ada"));
        assertEquals(ReferenceAnchor.ACTOR,c.source().anchor());assertEquals(ObjectType.BARREL,c.source().objectType());
        assertEquals(ReferenceAnchor.ACTOR,c.table().anchor());assertEquals(ObjectType.CRAFTING_TABLE,c.table().objectType());
        assertEquals(ReferenceAnchor.ACTOR,c.destination().anchor());
        c=classify("Ada, harvest 4 wheat from the field closest to you and deliver it to the nearest chest.",List.of("Ada"));
        assertEquals(ReferenceAnchor.ACTOR,c.source().anchor());assertEquals(ReferenceAnchor.ROLE_DEFAULT,c.destination().anchor());
    }
    @Test void unqualifiedNearestOmittedAndCoordinatesPreserveTheExistingRoleDefaults() {
        for(String phrase:List.of("nearest chest","the closest chest","that barrel","this container"))
            assertEquals(ReferenceAnchor.ROLE_DEFAULT,WorldReferenceLanguage.container(phrase).anchor());
        assertEquals(ReferenceState.OMITTED,classify("Make 4 birch planks.",List.of()).destination().state());
        var slot=WorldReferenceLanguage.container("chest at -4, 64, 7");
        assertEquals(ReferenceState.EXPLICIT_CONCRETE,slot.state());assertEquals("-4,64,7",slot.first());
        assertEquals(ReferenceAnchor.ROLE_DEFAULT,slot.anchor());
    }
    @Test void aRelativeSourceOrFacilityCannotInventAnOutputDestinationFromItsToYouSpan() {
        for(String text:List.of("Make 4 birch planks from the chest closest to you.",
                "Make 4 birch planks from the nearest chest to you.",
                "Ada, harvest 4 wheat from the field closest to you.",
                "Ada, make 2 wooden axes using the closest crafting table to you."))
            assertEquals(ReferenceState.OMITTED,classify(text,List.of("Ada")).destination().state(),text);
        assertEquals(ReferenceState.EXPLICIT_UNSUPPORTED,classify("Make 4 birch planks to you.",List.of()).destination().state());
    }
    @Test void unknownAnchorsExtraConstraintsAndCoordinateMixesCannotBecomeNearestDefaults() {
        for(String phrase:List.of("chest closest to me","chest nearest to Ada","chest closest to your house",
                "my closest chest","chest closest to you at 1,2,3","nearest chest behind you",
                "closest chest and barrel","closest chest to them","chest nearest to you and nearest to me"))
            assertEquals(ReferenceState.EXPLICIT_UNSUPPORTED,WorldReferenceLanguage.container(phrase).state(),phrase);
        assertEquals(ReferenceState.EXPLICIT_UNSUPPORTED,WorldReferenceLanguage.container("furnace closest to you").state());
        assertThrows(IllegalArgumentException.class,()->new Slot(ReferenceState.OMITTED,null,null,null,ObjectType.CHEST,ReferenceAnchor.ACTOR));
        assertThrows(IllegalArgumentException.class,()->new Reference<>(ReferenceState.EXPLICIT_CONCRETE,"value","raw",ObjectType.CHEST,ReferenceAnchor.ACTOR));
    }
}
