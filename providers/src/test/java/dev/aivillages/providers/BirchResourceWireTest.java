package dev.aivillages.providers;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;
import dev.aivillages.core.kernel.LanguageRequests;

class BirchResourceWireTest {
    @ParameterizedTest @CsvSource({"gather 4 birch logs,gather_birch_logs,GATHER_BIRCH_LOGS","make 8 birch planks,make_birch_planks,MAKE_BIRCH_PLANKS"})
    void exactSpeciesToolRoundTrip(String text,String tool,String action){
        var p=NeedleWire.prepare(new LanguageRequests.Input("Ada, "+text,List.of("Ada")));
        assertTrue(p.tools().contains(tool));assertFalse(p.tools().contains("gather_oak_logs"));assertFalse(p.tools().contains("make_oak_planks"));
        String response="{\"type\":\"call\",\"success\":true,\"function_calls\":[{\"name\":\""+tool+"\",\"arguments\":{\"citizen\":\"Ada\",\"amount\":"+(tool.startsWith("gather")?4:8)+"}}],\"confidence\":0.9}";
        var result=NeedleWire.decode(response.getBytes(StandardCharsets.UTF_8),p);assertEquals(LanguageRequests.Kind.EXTRACTED,result.kind());assertEquals(action,result.extracted().action().name());
    }
    @Test void oakResponseCannotSubstituteForBirch(){
        var p=NeedleWire.prepare(new LanguageRequests.Input("Ada, make 8 birch planks",List.of("Ada")));
        String response="{\"type\":\"call\",\"success\":true,\"function_calls\":[{\"name\":\"make_oak_planks\",\"arguments\":{\"citizen\":\"Ada\",\"amount\":8}}],\"confidence\":0.9}";
        assertEquals(LanguageRequests.Kind.INVALID,NeedleWire.decode(response.getBytes(StandardCharsets.UTF_8),p).kind());
    }
    @Test void explicitBirchStockAndCoordinatesRetainReferenceProvenance(){
        var p=NeedleWire.prepare(new LanguageRequests.Input("Ada, make 8 birch planks from the available birch logs and store them.",List.of("Ada")));
        assertTrue(p.tools().contains("make_birch_planks"));assertTrue(p.text().contains("from stock"));
    }
    @ParameterizedTest @CsvSource({"gather_birch_logs,area,GATHER_BIRCH_LOGS","make_birch_planks,stock,MAKE_BIRCH_PLANKS"})
    void explicitBirchReferencesRoundTripAndRejectWrongAliases(String tool,String alias,String action){
        boolean gather=tool.startsWith("gather");int amount=gather?4:8;
        String text=gather?"Ada, gather 4 birch logs from 6,201,14 through 13,201,14 and deliver them to the chest at 5,201,5."
                :"Ada, make 8 birch planks from the chest at 5,201,5 and store them in the chest at 9,201,7.";
        var p=NeedleWire.prepare(new LanguageRequests.Input(text,List.of("Ada")));
        assertTrue(p.text().contains("from "+alias));
        String response="{\"type\":\"call\",\"success\":true,\"function_calls\":[{\"name\":\""+tool+"\",\"arguments\":{\"citizen\":\"Ada\",\"amount\":"+amount+",\"source\":\""+alias+"\",\"destination\":\"chest\"}}],\"confidence\":0.9}";
        var result=NeedleWire.decode(response.getBytes(StandardCharsets.UTF_8),p);
        assertEquals(LanguageRequests.Kind.EXTRACTED,result.kind());assertEquals(action,result.extracted().action().name());
        assertEquals((long)amount,result.extracted().amount());assertEquals(gather?"6,201,14":"5,201,5",result.extracted().from());
        assertEquals(gather?"13,201,14":null,result.extracted().through());assertEquals(gather?"5,201,5":"9,201,7",result.extracted().destination());
        for(String wrong:List.of("farm",gather?"stock":"area"))
            assertEquals(LanguageRequests.Kind.INVALID,NeedleWire.decode(response.replace("\"source\":\""+alias+"\"","\"source\":\""+wrong+"\"").getBytes(StandardCharsets.UTF_8),p).kind());
    }
}
