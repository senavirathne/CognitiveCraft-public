package dev.aivillages.core.kernel;

import java.util.List;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.WorldReferenceResolver.*;
import static dev.aivillages.core.kernel.LanguageReferenceClassifier.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldReferenceLanguageTest {
    @Test void commonObjectPolicyAppliesAcrossExistingCapabilityDestinations() {
        for(String action:List.of("harvest 4 wheat", "bring 4 oak logs", "gather 4 birch logs",
                "make 4 oak planks", "make 4 birch planks", "make 4 sticks", "make 4 wooden axes", "make 4 crafting tables"))
            for(String article:List.of("that ","this ","the ","a ",""))
                for(var type:List.of(ObjectType.CHEST,ObjectType.BARREL,ObjectType.CONTAINER)) {
                    String noun=type.name().toLowerCase(java.util.Locale.ROOT);
                    String text="Ada, "+action+" and put them in "+article+noun+".";
                    var c=classify(text,List.of("Ada"));
                    assertNotEquals(Intent.UNKNOWN,c.intent(),text);
                    assertEquals(ReferenceState.EXPLICIT_NEAREST,c.destination().state(),text);
                    assertEquals(type,c.destination().objectType(),text);
                    assertNull(c.destination().first());
                }
    }
    @Test void samePolicyAppliesToInputContainersSourceAreasAndFacilities() {
        for(String article:List.of("that ","this ","the ","")) {
            var source=classify("Ada, make 4 sticks from "+article+"barrel and store them.",List.of("Ada")).source();
            assertEquals(ReferenceState.EXPLICIT_NEAREST,source.state());assertEquals(ObjectType.BARREL,source.objectType());
            source=classify("Ada, harvest 4 wheat from "+article+"field and deliver it.",List.of("Ada")).source();
            assertEquals(ReferenceState.EXPLICIT_NEAREST,source.state());assertEquals(ObjectType.FIELD,source.objectType());
            var table=classify("Ada, make 2 wooden axes using "+article+"crafting table.",List.of("Ada")).table();
            assertEquals(ReferenceState.EXPLICIT_NEAREST,table.state());assertEquals(ObjectType.CRAFTING_TABLE,table.objectType());
        }
        var combined=classify("Ada, make two wooden axes from that barrel using this crafting table and put them in a chest.",List.of("Ada"));
        assertEquals(ObjectType.BARREL,combined.source().objectType());
        assertEquals(ObjectType.CRAFTING_TABLE,combined.table().objectType());
        assertEquals(ObjectType.CHEST,combined.destination().objectType());
    }
    @Test void coordinatesRetainTheirExactTypeAndDoNotBecomeAutomatic() {
        for(String article:List.of("that ","this ","the ","")) {
            var slot=WorldReferenceLanguage.container(article+"barrel at -4, 64, 7");
            assertEquals(ReferenceState.EXPLICIT_CONCRETE,slot.state());assertEquals("-4,64,7",slot.first());
            assertEquals(ObjectType.BARREL,slot.objectType());
        }
        assertEquals(ReferenceState.OMITTED,classify("Ada, bring 4 oak logs.",List.of("Ada")).destination().state());
        assertEquals(ReferenceState.EXPLICIT_UNSUPPORTED,classify("Ada, gather 4 oak logs from 1,2,3.",List.of("Ada")).source().state());
    }
    @Test void namedConstraintsAndUnsupportedOrMultipleObjectsCannotDisappear() {
        for(String phrase:List.of("my barrel","that barrel behind my house","this chest and that barrel",
                "barrel closest to me","that furnace","that","this","nearest chest at 1,2,3"))
            assertEquals(ReferenceState.EXPLICIT_UNSUPPORTED,WorldReferenceLanguage.container(phrase).state(),phrase);
        assertEquals(ReferenceState.EXPLICIT_UNSUPPORTED,WorldReferenceLanguage.parse("that barrel",ObjectType.CRAFTING_TABLE).state());
    }
    @Test void disposableAliasReplacesTheRoleSpanRatherThanTheRequestedMaterial() {
        String text="Ada, gather four oak logs from oak logs and deliver them to this barrel.";
        var c=classify(text,List.of("Ada"));
        String prompt=WorldReferenceLanguage.alias(text,c.source(),"stock","from");
        prompt=WorldReferenceLanguage.alias(prompt,c.destination(),"chest","to|into|in|at");
        assertEquals("Ada, gather four oak logs from stock and deliver them to chest.",prompt);
    }
}
