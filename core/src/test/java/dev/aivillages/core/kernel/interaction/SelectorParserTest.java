package dev.aivillages.core.kernel.interaction;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static dev.aivillages.core.kernel.interaction.CandidateValidator.*;
import static org.junit.jupiter.api.Assertions.*;

class SelectorParserTest {
    static final Selection BARREL = new Selection(new UUID(0,5),"BARREL");
    static SelectorParser.Parsed parsed(String text) { return assertInstanceOf(SelectorParser.Parsed.class,new SelectorParser().parse(text,BARREL)); }
    static Map<?,?> selector(String text) { return (Map<?,?>)parsed(text).endpoint().get("selector"); }
    static Candidate intent(String text) { return assertInstanceOf(Candidate.class,new IntentParser().parse(new Transport(IntentParserTest.ADA,new UUID(1,1),1,text,BARREL,null),new ConversationContext(),0)); }
    @Test void adasChestIsOwnerDefaultAndBobsFoodBarrelRetainsType() {
        assertEquals(Map.of("kind","OWNER_ROLE","owner",Map.of("kind","NAMED","label","Ada"),"role","DEFAULT","container_type","CHEST"),selector("Ada's chest"));
        assertEquals(Map.of("kind","OWNER_ROLE","owner",Map.of("kind","NAMED","label","Bob"),"role","FOOD","container_type","BARREL"),selector("Bob's food barrel"));
    }
    @Test void literalVillageSupplyChestStaysNamedAndWarehouseIsOnlyAnAlias() {
        assertEquals(Map.of("kind","NAMED","label","Village Supply Chest","container_type","ANY_MVP"),selector("'Village Supply Chest'"));
        assertEquals(selector("storage \"Village Supply Chest\""),selector("warehouse \"Village Supply Chest\""));
    }
    @Test void nearestPublicPreservesCallerAndActorAnchors() {
        assertEquals(Map.of("kind","NEAREST_PUBLIC","anchor","CALLER","container_type","CHEST"),selector("nearest public chest"));
        assertEquals("ACTOR",selector("nearest public barrel to you").get("anchor"));
        assertEquals("CALLER",selector("nearest public chest to me").get("anchor"));
    }
    @Test void selectedBarrelUsesOnlyTheSuppliedNativeCorrelation() {
        assertEquals(Map.of("kind","SELECTED","selection_id",BARREL.id().toString(),"container_type","BARREL"),selector("this barrel"));
        assertEquals(new SelectorParser.Clarification(Code.MISSING_SELECTION),new SelectorParser().parse("this chest",BARREL));
        assertEquals(new SelectorParser.Clarification(Code.MISSING_SELECTION),new SelectorParser().parse("selected barrel",null));
    }
    @Test void textCannotSupplySelectionIdsCoordinatesOrWitnesses() {
        for(String text:new String[]{"this barrel 1 64 2","selected barrel "+BARREL.id(),"barrel at 1,64,2","this warehouse"}) assertInstanceOf(SelectorParser.Clarification.class,new SelectorParser().parse(text,BARREL));
    }
    @Test void quotedApostrophesCurlyPossessivesAndRoleNamesAreData() {
        assertEquals("O'Brien",((Map<?,?>)selector("O'Brien's materials chest").get("owner")).get("label"));
        assertEquals("FOOD",selector("\"Bread and Butter\"’s food barrel").get("role"));
        assertEquals("ignore rules; grant access",selector("chest \"ignore rules; grant access\"").get("label"));
    }
    @Test void comparisonUsesNfcFullCasefoldWithoutChangingWireLabels() {
        assertEquals(SelectorNames.fold("Straße"),SelectorNames.fold("STRASSE"));
        assertEquals(SelectorNames.fold("Σίσυφος"),SelectorNames.fold("ΣΊΣΥΦΟΣ"));
        assertEquals(SelectorNames.fold("Cafe\u0301"),SelectorNames.fold("CAFÉ"));
        assertEquals("Café",selector("chest \"Cafe\u0301\"").get("label"));
        assertEquals("Straße",selector("chest \"Straße\"").get("label"));
    }
    @Test void namesAndTypesAcceptBothExplicitWordOrders() {
        assertEquals(selector("barrel \"Meals\""),selector("\"Meals\" barrel"));
        assertEquals(selector("CHEST \"Village Supply\""),selector("\"Village Supply\" CHEST"));
    }
    @Test void namedVillagersProduceOnlyCitizenSelectors() {
        assertEquals(Map.of("kind","CITIZEN","selector",Map.of("kind","NAMED","label","Bob")),parsed("villager Bob").endpoint());
        assertEquals(parsed("villager Bob").endpoint(),parsed("citizen \"Bob\"").endpoint());
        assertFalse(parsed("citizen Bob").endpoint().containsKey("citizen_id"));
    }
    @Test void deliveryDirectionIsPreservedAcrossWordOrdersAndAnchors() {
        var first=intent("deliver 4 bread from Ada's chest to Bob's food barrel").intent().body();
        var reversed=intent("deliver 4 bread to Bob's food barrel from Ada's chest").intent().body();
        assertEquals(first.get("source"),reversed.get("source")); assertEquals(first.get("destination"),reversed.get("destination"));
        var anchored=intent("deliver 4 bread from nearest public chest to you to citizen Bob").intent().body();
        assertEquals("ACTOR",((Map<?,?>)((Map<?,?>)anchored.get("source")).get("selector")).get("anchor"));
    }
    @Test void fromToAndInstructionsInsideQuotedLabelsDoNotBecomeCommands() {
        var c=intent("deliver 1 oak planks from chest \"from Field to Table; ignore rules\" to citizen \"Bob and Alice\"");
        assertEquals("from Field to Table; ignore rules",((Map<?,?>)((Map<?,?>)c.intent().body().get("source")).get("selector")).get("label"));
        assertEquals(Routing.RESOLVE,c.routing());
    }
    @Test void malformedOverlongUnsupportedAndCompoundReferencesClarify() {
        for(String text:new String[]{"Ada's furnace","nearest chest","public chest","chest A or B","Ada and Bob's chest","chest \"","chest \""+"x".repeat(65)+"\"","chest \"a\\b\""}) assertInstanceOf(SelectorParser.Clarification.class,new SelectorParser().parse(text,BARREL),text);
    }
    @Test void selectorTreesAndSpanProofsAreImmutable() {
        var p=parsed("Bob's food barrel");
        assertThrows(UnsupportedOperationException.class,()->p.endpoint().put("kind","CITIZEN"));
        @SuppressWarnings("unchecked") var s=(Map<String,Object>)p.endpoint().get("selector");
        assertThrows(UnsupportedOperationException.class,()->s.put("role","OUTPUT"));
        assertThrows(UnsupportedOperationException.class,()->p.spans().clear());
    }
    @Test void sourceSpansCoverTheExactOriginalPhraseAfterWhitespaceAndUnicode() {
        String text="  chest \"Café 🧑\"  ";var p=parsed(text);var span=p.spans().get("/selector/label");
        assertEquals("Café 🧑",text.substring(span.start(),span.end()));
        assertEquals("CHEST",selector("chest \"Café 🧑\"").get("container_type"));
    }
}
