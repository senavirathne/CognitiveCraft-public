package dev.aivillages.core.kernel.interaction;

import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import dev.aivillages.core.kernel.interaction.contract.InteractionWire;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static dev.aivillages.core.kernel.interaction.CandidateValidator.*;

/** Raw observations only. This driver also runs unchanged against the frozen C04 production JAR. */
public final class SelectorVariationProbe {
    @SuppressWarnings("unchecked") public static void main(String[] args) throws Exception {
        var campaign=(Map<String,Object>)InteractionCodec.parseJson(Files.readString(Path.of(args[0])));
        Path loaded=Path.of(IntentParser.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Object> rows=new ArrayList<>();long before=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        int index=0;
        for(Object value:(List<?>)campaign.get("cases")) {
            var test=(Map<String,Object>)value;var selection=(Map<String,Object>)test.get("selection");
            var t=new Transport(new ConversationContext.SessionKey(new UUID(0,1),new UUID(0,2),new UUID(0,3)),new UUID(1,++index),index,(String)test.get("text"),
                    new Selection(UUID.fromString((String)selection.get("id")),(String)selection.get("container_type")),null);
            long start=System.nanoTime();var result=new IntentParser().parse(t,new ConversationContext(),0);long elapsed=System.nanoTime()-start;
            Map<String,Object> row=new LinkedHashMap<>();row.put("id",test.get("id"));row.put("seed",test.get("seed"));row.put("text",t.text());row.put("elapsed_ns",elapsed);
            if(result instanceof Candidate c) { row.put("disposition","CANDIDATE");row.put("intent",InteractionCodec.parseJson(new InteractionCodec().encode(c.intent())));row.put("routing",c.routing().name()); }
            else {row.put("disposition","CLARIFY");row.put("reason",((Clarify)result).code().name());}
            rows.add(row);
        }
        long after=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        System.out.print(InteractionCodec.canonical(Map.of("tier","SYNTHETIC_SELECTOR_VARIATIONS","production_jar_sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(loaded))),
                "cases",rows,"heap_before",before,"heap_after",after,"native_executed",0L,"client_executed",0L)));
    }
}
