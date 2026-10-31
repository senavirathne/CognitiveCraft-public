package dev.aivillages.core.kernel.interaction;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResponseTemplatesTest {
    @Test void everyBlockerHasShortAuthorizedExplanationAndNextStep() {
        for (var code : BlockerProjection.Code.values()) {
            var facts = ResponseFixtures.blocker(code); var replies = ResponseTemplates.choices(facts);
            assertEquals(2, replies.size(), code.name());
            for (String reply : replies) {
                assertTrue(reply.getBytes(StandardCharsets.UTF_8).length <= 256, code.name());
                assertTrue(new ResponseValidator().acceptsText(facts, reply), code.name());
                assertTrue(reply.matches(".*(Ask|ask|Choose|Review|Select|Wait|Use|Free|Add).*"), code.name());
            }
        }
    }
    @Test void unsupportedActionsExplicitlyExplainTheSupportLimit() {
        assertTrue(ResponseTemplates.fallback(ResponseFixtures.blocker(BlockerProjection.Code.UNSUPPORTED_EFFECT)).contains("unsupported action"));
        assertTrue(ResponseTemplates.fallback(ResponseFixtures.blocker(BlockerProjection.Code.NO_SUPPORTED_PRODUCTION_PATH)).contains("no supported production path"));
    }
    @Test void allLocalesUseTheSameEnglishFallbackWithoutEchoingTheirInput() {
        var projection = new AssistanceFixtures().assess().blocker();
        for (String locale : new String[]{null, "en", "en-GB", "si", "Hidden Pantry 913"}) {
            var facts = ResponseFacts.forBlocker(projection, locale);
            assertEquals("en", facts.language()); assertEquals(ResponseTemplates.fallback(ResponseFixtures.shortage()), ResponseTemplates.fallback(facts));
        }
    }
    @Test void offlineSmallTalkHasNoWorkClaimsOrHistoricalRelationship() {
        var facts = ResponseFacts.smallTalk("si");
        assertEquals("Hello.", ResponseTemplates.fallback(facts));
        assertEquals(3, ResponseTemplates.choices(facts).size());
        for (String reply : ResponseTemplates.choices(facts)) assertFalse(reply.matches(".*(delivered|harvested|yesterday|again).*"));
    }
    @Test void longAuthorizedMaterialListsUseABoundedExplanationWithoutInventingCounts() {
        var f = new AssistanceFixtures();
        var requirements = java.util.stream.IntStream.range(0, 16).mapToObj(i -> java.util.Map.<String,Object>of(
                "item", new PlanningExplanationAdapter.ItemDescriptor("minecraft:" + "a".repeat(70) + i, "0".repeat(64)).value(), "missing", 1L)).toList();
        var p = BlockerProjection.material(f.evidence(), requirements, f.storage);
        String text = ResponseTemplates.fallback(ResponseFacts.forBlocker(p, "en"));
        assertTrue(text.contains("authorized material list")); assertTrue(text.length() <= 256); assertFalse(text.matches(".*[0-9].*"));
    }
}
