package dev.aivillages.providers;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import dev.aivillages.core.kernel.LanguageRequests;
class AxeNeedleWireTest {
    @Test void groundedAxeToolContainsNoProducerOrFacilityAuthority() {
        var p=NeedleWire.prepare(new LanguageRequests.Input("Ada, make one wooden axe using available materials and store it.",List.of("Ada")));
        assertTrue(p.text().contains("make 1 wooden axe"));assertTrue(p.tools().contains("make_wooden_axes"));assertFalse(p.tools().contains("gather_oak_logs"));assertFalse(p.tools().contains("make_sticks"));
        var result=NeedleWire.decode("{\"type\":\"call\",\"success\":true,\"function_calls\":[{\"name\":\"make_wooden_axes\",\"arguments\":{\"citizen\":\"Ada\",\"amount\":1}}],\"confidence\":0.9}".getBytes(StandardCharsets.UTF_8),p);
        assertEquals(LanguageRequests.Kind.EXTRACTED,result.kind());assertEquals(LanguageRequests.Action.MAKE_WOODEN_AXES,result.extracted().action());assertNull(result.extracted().from());
    }
    @Test void modelCannotInventFacilityCoordinates() {
        var p=NeedleWire.prepare(new LanguageRequests.Input("Ada, make one wooden axe and store it.",List.of("Ada")));
        var result=NeedleWire.decode("{\"type\":\"call\",\"success\":true,\"function_calls\":[{\"name\":\"make_wooden_axes\",\"arguments\":{\"citizen\":\"Ada\",\"amount\":1,\"table\":\"2,0,2\"}}],\"confidence\":0.9}".getBytes(StandardCharsets.UTF_8),p);
        assertEquals(LanguageRequests.Kind.INVALID,result.kind());
    }
}
