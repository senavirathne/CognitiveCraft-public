package dev.aivillages.providers;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import dev.aivillages.core.kernel.LanguageRequests;
import static org.junit.jupiter.api.Assertions.*;

class ProductionGoalNeedleWireTest {
    @Test void originalProjectsOnlyGroundedGoalAndStockReferenceToTheActualBackend() {
        var p=NeedleWire.prepare(new LanguageRequests.Input("We need three wooden axes. Use what we've already got.",List.of("Ada")));
        assertEquals("make 3 wooden axes from stock.",p.text());assertTrue(p.tools().contains("make_wooden_axes"));
        assertFalse(p.tools().contains("\"citizen\""));assertFalse(p.tools().contains("\"table\""));assertFalse(p.tools().contains("inventory"));
        var r=NeedleWire.decode("{\"type\":\"call\",\"success\":true,\"function_calls\":[{\"name\":\"make_wooden_axes\",\"arguments\":{\"amount\":3,\"source\":\"stock\"}}],\"confidence\":0.9}".getBytes(StandardCharsets.UTF_8),p);
        assertEquals(LanguageRequests.Kind.EXTRACTED,r.kind());assertEquals(3L,r.extracted().amount());
        assertNull(r.extracted().citizen());assertNull(r.extracted().from());assertNull(r.extracted().destination());
    }
    @Test void sourceDestinationAndFacilityRemainIndependentlyGrounded() {
        var p=NeedleWire.prepare(new LanguageRequests.Input("We require three wooden axes from chest at 5,201,5 using crafting table at 7,201,9 and put them in barrel at 9,201,7.",List.of("Ada")));
        assertEquals("5,201,5",p.from());assertEquals(p.from(),p.through());assertEquals("9,201,7",p.destination());
        assertEquals("7,201,9",p.classification().table().first());assertFalse(p.tools().contains("\"table\""));
    }
    @Test void unknownTableAndInventoryConstraintsCannotBeBypassedByModelConfidence() {
        for(String tail:List.of("using my private table.","using my stock.",". Use available materials. Buy more.")) {
            var p=NeedleWire.prepare(new LanguageRequests.Input("We need three wooden axes "+tail,List.of("Ada")));
            var r=NeedleWire.decode("{\"type\":\"call\",\"success\":true,\"function_calls\":[{\"name\":\"make_wooden_axes\",\"arguments\":{\"amount\":3}}],\"confidence\":0.99}".getBytes(StandardCharsets.UTF_8),p);
            assertNotEquals(LanguageRequests.Kind.EXTRACTED,r.kind());
        }
    }
}
