package dev.aivillages.providers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import dev.aivillages.core.kernel.LanguageRequests;
import static dev.aivillages.core.kernel.WorldReferenceResolver.*;
import static org.junit.jupiter.api.Assertions.*;

/** Injected decoder fixtures, explicitly separate from genuine model measurement. */
class SpatialReferenceNeedleWireTest {
    private static byte[] answer() {
        return "{\"type\":\"call\",\"success\":true,\"confidence\":0.9,\"function_calls\":[{\"name\":\"make_birch_planks\",\"arguments\":{\"amount\":11,\"destination\":\"chest\"}}]}".getBytes(StandardCharsets.UTF_8);
    }
    @Test void disposableAliasCannotReplaceTheHostAnchorWithModelCoordinates() {
        var p=NeedleWire.prepare(new LanguageRequests.Input("Make eleven birch planks and put them in the chest closest to you.",List.of()));
        assertEquals("Make eleven birch planks and put them in chest.",p.text());
        assertEquals(ReferenceAnchor.ACTOR,p.classification().destination().anchor());
        assertEquals(ObjectType.CHEST,p.classification().destination().objectType());
        assertFalse(p.tools().contains("anchor"));assertNull(p.destination());
        var result=NeedleWire.decode(answer(),p);assertEquals(LanguageRequests.Kind.EXTRACTED,result.kind());
        assertEquals(11L,result.extracted().amount());assertNull(result.extracted().destination());
    }
    @Test void aModelCannotEraseAnUnsupportedExplicitAnchor() {
        var p=NeedleWire.prepare(new LanguageRequests.Input("Make eleven birch planks and put them in the chest closest to me.",List.of()));
        assertEquals(LanguageRequests.Kind.CLARIFICATION,NeedleWire.decode(answer(),p).kind());
    }
}
