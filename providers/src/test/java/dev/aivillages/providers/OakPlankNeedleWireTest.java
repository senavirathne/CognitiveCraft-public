package dev.aivillages.providers;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import dev.aivillages.core.kernel.LanguageRequests;

class OakPlankNeedleWireTest {
    @Test void productionToolHasTypedStockSourceAndNoHarvestTool() {
        var p=NeedleWire.prepare(new LanguageRequests.Input("Ada, make 8 oak planks from the available oak logs and store them.",List.of("Ada")));
        assertTrue(p.tools().contains("make_oak_planks"));assertFalse(p.tools().contains("gather_oak_logs"));assertFalse(p.tools().contains("harvest_wheat"));
        String response="{\"type\":\"call\",\"success\":true,\"function_calls\":[{\"name\":\"make_oak_planks\",\"arguments\":{\"citizen\":\"Ada\",\"amount\":8,\"source\":\"stock\"}}],\"confidence\":0.9}";
        var result=NeedleWire.decode(response.getBytes(StandardCharsets.UTF_8),p);
        assertEquals(LanguageRequests.Kind.EXTRACTED,result.kind());assertEquals(LanguageRequests.Action.MAKE_OAK_PLANKS,result.extracted().action());
        assertNull(result.extracted().from());assertNull(result.extracted().through());
    }
    @Test void harvestCannotSubstituteForProduction() {
        var p=NeedleWire.prepare(new LanguageRequests.Input("Ada, make 8 oak planks from the available oak logs and store them.",List.of("Ada")));
        String response="{\"type\":\"call\",\"success\":true,\"function_calls\":[{\"name\":\"harvest_wheat\",\"arguments\":{\"citizen\":\"Ada\",\"amount\":8,\"source\":\"stock\"}}],\"confidence\":0.9}";
        assertEquals(LanguageRequests.Kind.INVALID,NeedleWire.decode(response.getBytes(StandardCharsets.UTF_8),p).kind());
    }
}
