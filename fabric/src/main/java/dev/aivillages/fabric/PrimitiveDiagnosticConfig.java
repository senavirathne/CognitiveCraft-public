package dev.aivillages.fabric;

import java.util.*;
import dev.aivillages.core.kernel.Contracts.ScopeRef;
import dev.aivillages.core.kernel.PrimitiveDiagnostics.*;

/** Trusted startup environment only; never interpreted from player/model content. */
public final class PrimitiveDiagnosticConfig {
    private PrimitiveDiagnosticConfig() { }
    public record Configuration(Policy policy,boolean valid) { }
    public static Configuration read(Map<String,String> environment) {
        try {
            String mode=environment.getOrDefault("COGNITIVECRAFT_PRIMITIVE_DIAGNOSTICS","off");
            Mode selected=switch(mode){case "off"->Mode.OFF;case "metadata"->Mode.METADATA;
                case "excerpts"->Mode.EXCERPTS;default->throw new IllegalArgumentException();};
            Set<ScopeRef> scopes=new HashSet<>();
            String raw=environment.getOrDefault("COGNITIVECRAFT_PRIMITIVE_DIAGNOSTIC_SCOPES","");
            if(raw.length()>8192)throw new IllegalArgumentException();
            if(!raw.isEmpty())for(String entry:raw.split(",",-1)) {
                if(scopes.size()>=64)throw new IllegalArgumentException();
                String[] pair=entry.split("/",-1);
                if(pair.length!=2||!pair[0].matches("[a-fA-F0-9-]{36}")||!pair[1].matches("[a-fA-F0-9-]{36}"))
                    throw new IllegalArgumentException();
                if(!scopes.add(new ScopeRef(UUID.fromString(pair[0]),UUID.fromString(pair[1]))))throw new IllegalArgumentException();
            }
            var allowances=new EnumMap<Limit,Long>(Limit.class);
            for(Limit limit:Limit.values()) {
                String value=environment.get("COGNITIVECRAFT_PRIMITIVE_DIAGNOSTIC_"+limit.name());
                if(value!=null) {
                    if(!value.matches("0|[1-9][0-9]{0,18}"))throw new IllegalArgumentException();
                    allowances.put(limit,Long.parseLong(value));
                }
            }
            return new Configuration(new Policy(selected,allowances,scopes),true);
        } catch(IllegalArgumentException | ArithmeticException invalid) {
            return new Configuration(Policy.off(),false);
        }
    }
}
