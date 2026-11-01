package dev.aivillages.core.kernel.interaction;

import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Same frozen schedule against the unchanged C06 JAR and candidate. Parser timing only, no native credit. */
public final class InteractionGrammarProbe {
    @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception {
        Path jar=Path.of(IntentParser.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if(!Files.isRegularFile(jar))throw new IllegalStateException("actual release JAR required");
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        var schedule=(Map<?,?>)InteractionCodec.parseJson(Files.readString(Path.of(args[0])));var rows=new ArrayList<Map<String,Object>>();
        int index=0;
        for(Object value:(List<?>)schedule.get("cases")) {
            var row=(Map<String,Object>)value;var facts=(Map<?,?>)row.get("facts");
            var key=new ConversationContext.SessionKey(new UUID(0,1),new UUID(0,2),new UUID(0,3));
            var selection=facts.get("selection")==null?null:new CandidateValidator.Selection(new UUID(0,4),(String)facts.get("selection"));
            var t=new CandidateValidator.Transport(key,new UUID(1,++index),index,(String)row.get("text"),selection,new CandidateValidator.Question(new UUID(0,5),1));
            long start=System.nanoTime();var result=new IntentParser().parse(t,new ConversationContext(),1);var selector=new SelectorParser().parse(t.text().replaceFirst("[.!?]$",""),selection);long elapsed=System.nanoTime()-start;
            String kind=result instanceof CandidateValidator.Candidate c?(String)c.intent().body().getOrDefault("action",c.intent().body().get("reply")):"CLARIFY";
            rows.add(Map.of("id",row.get("id"),"seed",row.get("seed"),"interpretation",kind,"selector_parsed",selector instanceof SelectorParser.Parsed,"elapsed_ns",elapsed));
        }
        Files.writeString(Path.of(args[1]),InteractionCodec.canonical(Map.of("tier","SYNTHETIC_PARSER_COMPARISON","jar_sha256",sha,"rows",rows)));
    }
}
