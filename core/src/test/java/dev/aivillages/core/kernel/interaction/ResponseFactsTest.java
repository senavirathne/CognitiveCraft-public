package dev.aivillages.core.kernel.interaction;

import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResponseFactsTest {
    @Test void packetContainsOnlyLanguageModeKeyAndAuthorizedMaterials() {
        var facts = ResponseFixtures.shortage();
        assertEquals(java.util.Set.of("mode", "language", "key", "materials"), facts.packet().keySet());
        assertEquals(java.util.List.of(Map.of("item", "minecraft:oak_planks", "missing", 2L)), facts.packet().get("materials"));
    }
    @Test void redactionRemovesAllActivityStorageAndMaterialIdentifiers() {
        var facts = ResponseFixtures.blocker(BlockerProjection.Code.UNAUTHORIZED);
        String json = InteractionCodec.canonical(facts.packet());
        assertTrue(facts.materials().isEmpty()); assertFalse(json.contains("00000000"));
        for (String field : new String[]{"root", "storage_id", "principal", "owner", "minecraft:", "913"}) assertFalse(json.contains(field));
    }
    @Test void packetAndMaterialViewsAreImmutable() {
        var facts = ResponseFixtures.shortage();
        assertThrows(UnsupportedOperationException.class, () -> facts.materials().clear());
        assertThrows(UnsupportedOperationException.class, () -> facts.packet().put("root", "forged"));
    }
    @Test void digestIsStableAcrossLocaleHintsAndDifferentForDifferentFacts() {
        var p = new AssistanceFixtures().assess().blocker();
        assertEquals(ResponseFacts.forBlocker(p, "en").digest(), ResponseFacts.forBlocker(p, "si").digest());
        assertNotEquals(ResponseFixtures.shortage().digest(), ResponseFacts.smallTalk("en").digest());
    }
    @Test void unboundedOrInvalidMaterialDescriptorsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ResponseFacts.Material("minecraft:oak_planks", 0));
        assertThrows(IllegalArgumentException.class, () -> new ResponseFacts.Material("minecraft:oak_planks", 65));
        assertThrows(IllegalArgumentException.class, () -> new ResponseFacts.Material("Hidden Pantry 913", 2));
    }
}
