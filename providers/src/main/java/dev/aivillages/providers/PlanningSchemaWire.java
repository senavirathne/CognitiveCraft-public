package dev.aivillages.providers;

import dev.aivillages.core.kernel.StrictJson;
import java.util.*;

/** Wire-only schema ordering. Canonical identities, schema values and candidate bytes stay unchanged. */
final class PlanningSchemaWire {
    private PlanningSchemaWire() { }

    static String request(Map<String,?> request) {
        var out=new StringBuilder("{");boolean first=true;
        for(String key:new TreeSet<>(request.keySet())) {
            if(!first)out.append(',');first=false;
            out.append(StrictJson.canonical(key)).append(':');
            if(key.equals("format"))schema(request.get(key),out);
            else out.append(StrictJson.canonical(request.get(key)));
        }
        return out.append('}').toString();
    }

    private static void schema(Object value,StringBuilder out) {
        if(value instanceof Map<?,?> map) {
            var keys=new TreeSet<String>();for(Object key:map.keySet())keys.add((String)key);
            out.append('{');boolean first=true;
            for(String key:keys) {
                if(!first)out.append(',');first=false;
                out.append(StrictJson.canonical(key)).append(':');
                Object child=map.get(key);
                if(key.equals("properties") && child instanceof Map<?,?> properties) {
                    // Ollama's ordered-JSON converter emits object members in this exact order.
                    var ordered=new LinkedHashSet<String>();
                    if(map.get("required") instanceof List<?> required)
                        for(Object name:required)if(name instanceof String field && properties.containsKey(field))ordered.add(field);
                    var rest=new TreeSet<String>();for(Object name:properties.keySet())rest.add((String)name);
                    ordered.addAll(rest);out.append('{');boolean firstProperty=true;
                    for(String name:ordered) {
                        if(!firstProperty)out.append(',');firstProperty=false;
                        out.append(StrictJson.canonical(name)).append(':');schema(properties.get(name),out);
                    }
                    out.append('}');
                } else if(Set.of("items","prefixItems","anyOf","oneOf","allOf").contains(key))schema(child,out);
                else out.append(StrictJson.canonical(child));
            }
            out.append('}');
        } else if(value instanceof List<?> list) {
            out.append('[');
            for(int i=0;i<list.size();i++) {if(i>0)out.append(',');schema(list.get(i),out);}
            out.append(']');
        } else out.append(StrictJson.canonical(value));
    }
}
