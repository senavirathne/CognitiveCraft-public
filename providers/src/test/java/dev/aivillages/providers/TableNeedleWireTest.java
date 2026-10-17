package dev.aivillages.providers;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import dev.aivillages.core.kernel.LanguageRequests;
class TableNeedleWireTest {
    @Test void groundedAxeToolContainsNoProducerOrFacilityAuthority() {
        var p=NeedleWire.prepare(new LanguageRequests.Input("Ada, make one crafting table using available materials and store it.",List.of("Ada")));
        assertTrue(p.text().contains("make 1 crafting table"));assertTrue(p.tools().contains("make_crafting_tables"));assertFalse(p.tools().contains("gather_oak_logs"));assertFalse(p.tools().contains("make_sticks"));
        var result=NeedleWire.decode("{\"type\":\"call\",\"success\":true,\"function_calls\":[{\"name\":\"make_crafting_tables\",\"arguments\":{\"citizen\":\"Ada\",\"amount\":1}}],\"confidence\":0.9}".getBytes(StandardCharsets.UTF_8),p);
        assertEquals(LanguageRequests.Kind.EXTRACTED,result.kind());assertEquals(LanguageRequests.Action.MAKE_CRAFTING_TABLES,result.extracted().action());assertNull(result.extracted().from());
    }
    @Test void modelCannotInventFacilityCoordinates() {
        var p=NeedleWire.prepare(new LanguageRequests.Input("Ada, make one crafting table and store it.",List.of("Ada")));
        var result=NeedleWire.decode("{\"type\":\"call\",\"success\":true,\"function_calls\":[{\"name\":\"make_crafting_tables\",\"arguments\":{\"citizen\":\"Ada\",\"amount\":1,\"table\":\"2,0,2\"}}],\"confidence\":0.9}".getBytes(StandardCharsets.UTF_8),p);
        assertEquals(LanguageRequests.Kind.INVALID,result.kind());
    }

    @Test void singularArticleNormalizationRetainsOriginalCitizenAndStrictToolExtraction() {
        for(String phrase:List.of("Ada, make me a crafting table and store it.","Ada, craft a crafting table and store it.","Ada, produce us a crafting table and store it.")) {
            var p=NeedleWire.prepare(new LanguageRequests.Input(phrase,List.of("Ada")));
            assertTrue(p.text().contains("1 crafting table"),p.text());assertFalse(p.text().contains(" me "));assertFalse(p.text().contains(" us "));
            var result=NeedleWire.decode("{\"type\":\"call\",\"success\":true,\"function_calls\":[{\"name\":\"make_crafting_tables\",\"arguments\":{\"citizen\":\"Ada\",\"amount\":1}}],\"confidence\":0.9}".getBytes(StandardCharsets.UTF_8),p);
            assertEquals(LanguageRequests.Kind.EXTRACTED,result.kind());assertEquals(1L,result.extracted().amount());assertEquals("Ada",result.extracted().citizen());
        }
    }
    @Test void pluralArticleIsNotInventedAsOneAxe() {
        var text="Ada, make a crafting tables and store them.";var p=NeedleWire.prepare(new LanguageRequests.Input(text,List.of("Ada")));
        assertEquals(text,p.text());assertFalse(p.text().contains("1 wooden"));
    }
    @Test void ingredientArticleIsNotNormalizedAsRequestedOutput() {
        var text="Ada, make 2 crafting tables using a crafting table and store them.";var p=NeedleWire.prepare(new LanguageRequests.Input(text,List.of("Ada")));
        assertEquals(text,p.text());assertTrue(p.text().contains("using a crafting table"));
    }
}
