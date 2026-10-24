package dev.aivillages.providers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import dev.aivillages.core.kernel.LanguageRequests;
import static dev.aivillages.core.kernel.WorldReferenceResolver.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicitly injected wire fixtures; genuine measurements use D05LanguageLiveProbe separately. */
class ProductionRequestNeedleWireTest {
    private static byte[] call(String arguments) {
        return ("{\"type\":\"call\",\"success\":true,\"function_calls\":[{\"name\":\"make_crafting_tables\",\"arguments\":"+arguments+"}],\"confidence\":0.99}").getBytes(StandardCharsets.UTF_8);
    }
    @Test void originalProjectsGroundedOneNewItemWithOnlyTheDisposableStockAlias() {
        var p=NeedleWire.prepare(new LanguageRequests.Input("I need another crafting table. Use the planks from that barrel.",List.of("Ada")));
        assertEquals("make 1 crafting tables from stock.",p.text());assertEquals(ObjectType.BARREL,p.classification().source().objectType());
        assertFalse(p.tools().contains("\"citizen\""));assertFalse(p.tools().contains("\"destination\""));assertFalse(p.tools().contains("\"table\""));
        var r=NeedleWire.decode(call("{\"amount\":1,\"source\":\"stock\"}"),p);
        assertEquals(LanguageRequests.Kind.EXTRACTED,r.kind());assertEquals(1L,r.extracted().amount());
        assertNull(r.extracted().from());assertNull(r.extracted().through());assertNull(r.extracted().destination());
    }
    @Test void typedCoordinateSpansAreRestoredOnlyAfterTheWireAliasesAgree() {
        var p=NeedleWire.prepare(new LanguageRequests.Input("I want another crafting table from barrel at 5,64,5 and put it in chest at 9,64,7.",List.of("Ada")));
        var r=NeedleWire.decode(call("{\"amount\":1,\"source\":\"stock\",\"destination\":\"chest\"}"),p);
        assertEquals(LanguageRequests.Kind.EXTRACTED,r.kind());assertEquals("5,64,5",r.extracted().from());assertEquals("9,64,7",r.extracted().destination());
        assertNotEquals(LanguageRequests.Kind.EXTRACTED,NeedleWire.decode(call("{\"amount\":1,\"source\":\"barrel\",\"destination\":\"chest\"}"),p).kind());
    }
    @Test void rawMaterialPlacementAndUnknownSourceCannotBeErasedByHighConfidenceOutput() {
        for(String text:List.of("I need another crafting table. Use three oak planks from that barrel.",
                "I need another crafting table. Use the birch planks from that barrel.",
                "I need another crafting table. Use the planks from Steve's barrel.",
                "I need another crafting table. Place it at 8,64,9.",
                "I need another crafting table using my table.")) {
            var p=NeedleWire.prepare(new LanguageRequests.Input(text,List.of("Ada")));
            assertNotEquals(LanguageRequests.Kind.EXTRACTED,NeedleWire.decode(call("{\"amount\":1,\"source\":\"stock\"}"),p).kind(),text);
        }
    }
}
