package dev.aivillages.providers;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.aivillages.core.kernel.LanguageRequests;
import static org.junit.jupiter.api.Assertions.*;

class OakLogNeedleWireTest {
    @Test void directDeliveryAliasesKeepScalarSemanticsAndTokenizeOnlyConcreteDestinations() {
        for(String verb:List.of("bring","fetch")) {
            var p=NeedleWire.prepare(new LanguageRequests.Input("Bea, "+verb+" nine oak logs into the barrel at 13,64,0.",List.of("Bea")));
            assertTrue(p.tools().contains("gather_oak_logs"));assertFalse(p.tools().contains("harvest_wheat"));
            assertTrue(p.text().contains("nine oak logs"));assertTrue(p.text().contains(verb));
            assertFalse(p.text().contains("13,64,0"));assertEquals("13,64,0",p.destination());
        }
    }
    @Test void originalBarrelUsesTypedNearestPolicyWithoutInventedCoordinates() {
        var p=NeedleWire.prepare(new LanguageRequests.Input("Ada, bring seven oak logs over to that barrel.",List.of("Ada")));
        assertTrue(p.tools().contains("gather_oak_logs"));
        assertEquals(dev.aivillages.core.kernel.WorldReferenceResolver.ReferenceState.EXPLICIT_NEAREST,p.classification().destination().state());
        assertNull(p.destination());assertTrue(p.text().contains("to chest"));
    }
    @Test void typedOakToolIsSelectedAndWheatToolCannotSubstituteForIt() {
        var p=NeedleWire.prepare(new LanguageRequests.Input("Ada, collect 4 oak logs and deliver them to the nearest suitable chest.",List.of("Ada")));
        assertTrue(p.tools().contains("gather_oak_logs"));assertFalse(p.tools().contains("harvest_wheat"));
        String response="{\"type\":\"call\",\"success\":true,\"function_calls\":[{\"name\":\"gather_oak_logs\",\"arguments\":{\"citizen\":\"Ada\",\"amount\":4,\"destination\":\"chest\"}}],\"confidence\":0.9}";
        var result=NeedleWire.decode(response.getBytes(StandardCharsets.UTF_8),p);
        assertEquals(LanguageRequests.Kind.EXTRACTED,result.kind());assertEquals(LanguageRequests.Action.GATHER_OAK_LOGS,result.extracted().action());
        assertEquals(LanguageRequests.Kind.INVALID,NeedleWire.decode(response.replace("gather_oak_logs","harvest_wheat").getBytes(StandardCharsets.UTF_8),p).kind());
    }
}
