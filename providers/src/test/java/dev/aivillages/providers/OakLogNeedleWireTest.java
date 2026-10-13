package dev.aivillages.providers;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.aivillages.core.kernel.LanguageRequests;
import static org.junit.jupiter.api.Assertions.*;

class OakLogNeedleWireTest {
    @Test void typedOakToolIsSelectedAndWheatToolCannotSubstituteForIt() {
        var p=NeedleWire.prepare(new LanguageRequests.Input("Ada, collect 4 oak logs and deliver them to the nearest suitable chest.",List.of("Ada")));
        assertTrue(p.tools().contains("gather_oak_logs"));assertFalse(p.tools().contains("harvest_wheat"));
        String response="{\"type\":\"call\",\"success\":true,\"function_calls\":[{\"name\":\"gather_oak_logs\",\"arguments\":{\"citizen\":\"Ada\",\"amount\":4,\"destination\":\"chest\"}}],\"confidence\":0.9}";
        var result=NeedleWire.decode(response.getBytes(StandardCharsets.UTF_8),p);
        assertEquals(LanguageRequests.Kind.EXTRACTED,result.kind());assertEquals(LanguageRequests.Action.GATHER_OAK_LOGS,result.extracted().action());
        assertEquals(LanguageRequests.Kind.INVALID,NeedleWire.decode(response.replace("gather_oak_logs","harvest_wheat").getBytes(StandardCharsets.UTF_8),p).kind());
    }
}
