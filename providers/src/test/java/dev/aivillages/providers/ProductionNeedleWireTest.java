package dev.aivillages.providers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import dev.aivillages.core.kernel.LanguageRequests;
import static org.junit.jupiter.api.Assertions.*;

class ProductionNeedleWireTest {
    @Test void semanticProjectionRetainsProductAmountAndSeparatesIngredientConstraint() {
        var p=NeedleWire.prepare(new LanguageRequests.Input("Could you turn these two oak logs into seven planks for me?",List.of("Ada")));
        assertTrue(p.text().contains("seven oak planks"));assertFalse(p.text().contains("two oak logs"));
        assertTrue(p.text().contains("from stock"));assertTrue(p.tools().contains("make_oak_planks"));
        assertFalse(p.tools().contains("harvest_wheat"));assertFalse(p.tools().contains("\"citizen\""));
        assertEquals(2L,p.classification().production().inputAmount());assertEquals(7L,p.classification().production().outputAmount());
        String output="{\"type\":\"call\",\"success\":true,\"function_calls\":[{\"name\":\"make_oak_planks\",\"arguments\":{\"amount\":7,\"source\":\"stock\"}}],\"confidence\":0.9}";
        var result=NeedleWire.decode(output.getBytes(StandardCharsets.UTF_8),p);
        assertEquals(LanguageRequests.Kind.EXTRACTED,result.kind());assertEquals(7L,result.extracted().amount());
        assertNull(result.extracted().citizen());assertNull(result.extracted().from());assertNull(result.extracted().destination());
    }
    @Test void coordinateRolesSurviveTransformationProjectionAndDisposableAliases() {
        var p=NeedleWire.prepare(new LanguageRequests.Input("Ada, convert two oak logs from chest at 5,201,5 into eight planks and put them in barrel at 9,201,7.",List.of("Ada")));
        assertEquals("5,201,5",p.from());assertEquals(p.from(),p.through());assertEquals("9,201,7",p.destination());
        assertTrue(p.text().contains("eight oak planks"));assertFalse(p.text().contains("5,201,5"));assertFalse(p.text().contains("9,201,7"));
        assertTrue(p.text().contains("from stock"));assertTrue(p.text().contains("in chest"));
    }
    @Test void aNativeCallCannotBypassAnIncompatibleRawIngredientQuantity() {
        var p=NeedleWire.prepare(new LanguageRequests.Input("Ada, turn one oak log into seven planks.",List.of("Ada")));
        String output="{\"type\":\"call\",\"success\":true,\"function_calls\":[{\"name\":\"make_oak_planks\",\"arguments\":{\"citizen\":\"Ada\",\"amount\":7,\"source\":\"stock\"}}],\"confidence\":0.99}";
        assertEquals(LanguageRequests.Kind.CLARIFICATION,NeedleWire.decode(output.getBytes(StandardCharsets.UTF_8),p).kind());
    }
}
