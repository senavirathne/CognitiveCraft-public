package dev.aivillages.fabric;

import com.google.gson.*;
import com.google.gson.stream.JsonWriter;
import dev.aivillages.core.kernel.Jobs;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Test-only encoding cache, called only after the original native authorization query. */
public final class RuntimeAObservationJson {
    private static final Gson JSON=new Gson();
    private static final boolean ENABLED="true".equalsIgnoreCase(System.getenv("COGNITIVECRAFT_A04_PROBE"));
    private static final int MAX_ROWS=128,MAX_BYTES=16*1024*1024;
    private record Entry(Object original,JsonObject tree,int bytes,Map<JsonElement,String> literals) {}
    private static final LinkedHashMap<UUID,Entry> CACHE=new LinkedHashMap<>(128,.75f,true);
    private static int bytes;
    private static volatile Map<JsonElement,String> rawLiterals=Collections.emptyMap();
    public static JsonElement job(Jobs.Job job) {
        return ENABLED ? cached(job.id(),job) : JSON.toJsonTree(job);
    }
    private static synchronized JsonObject cached(UUID id,Object value) {
        Entry entry=CACHE.get(id);
        // Jobs publishes immutable records. Any replacement object is encoded again,
        // even if a caller accidentally keeps an old revision or changes its origin.
        if(entry==null||entry.original()!=value) {
            JsonObject tree=JSON.toJsonTree(value).getAsJsonObject();int size=JSON.toJson(tree).getBytes(StandardCharsets.UTF_8).length;
            var literals=new IdentityHashMap<JsonElement,String>();
            for(var field:tree.entrySet())if(field.getValue().isJsonObject()||field.getValue().isJsonArray()){
                String raw=JSON.toJson(field.getValue());if(raw.length()>=256){literals.put(field.getValue(),raw);size+=raw.getBytes(StandardCharsets.UTF_8).length;}
            }
            if(entry!=null){CACHE.remove(id);bytes-=entry.bytes();}
            entry=new Entry(value,tree,size,Collections.unmodifiableMap(literals));
            if(size<=MAX_BYTES){CACHE.put(id,entry);bytes+=size;}
            while(CACHE.size()>MAX_ROWS||bytes>MAX_BYTES){var iterator=CACHE.entrySet().iterator();var oldest=iterator.next();bytes-=oldest.getValue().bytes();iterator.remove();}
            var next=new IdentityHashMap<JsonElement,String>();for(Entry cached:CACHE.values())next.putAll(cached.literals());
            rawLiterals=Collections.unmodifiableMap(next);
        }
        // The observer adds fulfilled/dispatch fields only to this fresh outer object.
        // Cached nested values are never mutated; an older captured frame stays intact.
        var result=new JsonObject();for(var field:entry.tree().entrySet())result.add(field.getKey(),field.getValue());return result;
    }
    /** Encode every field, reusing only exact strings of immutable captured children. */
    public static String frame(JsonObject frame) {
        var output=new StringWriter();
        try {var writer=JSON.newJsonWriter(output);writer.setHtmlSafe(true);writer.setSerializeNulls(false);
            write(frame,writer,rawLiterals);writer.flush();return output.toString();
        }catch(IOException impossible){throw new IllegalStateException("In-memory observation encoding",impossible);}
    }
    private static void write(JsonElement value,JsonWriter writer,Map<JsonElement,String> literals)throws IOException {
        String raw=literals.get(value);if(raw!=null){writer.jsonValue(raw);return;}
        if(value!=null&&value.isJsonObject()){
            writer.beginObject();for(var field:value.getAsJsonObject().entrySet()){writer.name(field.getKey());write(field.getValue(),writer,literals);}writer.endObject();
        }else if(value!=null&&value.isJsonArray()){
            writer.beginArray();for(var item:value.getAsJsonArray())write(item,writer,literals);writer.endArray();
        }else com.google.gson.internal.Streams.write(value,writer);
    }
    public static void main(String[] args) {
        UUID id=UUID.randomUUID();Object first=Map.of("id",id,"revision",1,"request",Map.of("principal","one","escaped","<>& λ \\\" \\\\ ".repeat(40)),"rows",List.of("λ","escaped \\\""));
        JsonObject before=cached(id,first);before.addProperty("dispatch","first-capture");
        JsonObject second=cached(id,first);
        if(second.has("dispatch")||!second.equals(JSON.toJsonTree(first)))throw new IllegalStateException("Mutable outer fields leaked into metadata cache");
        var full=new JsonObject();full.add("before",before);full.add("alias",before);
        full.add("other",JsonParser.parseString("{\"array\":[null,true,0.000001,1e40],\"null\":null}"));
        if(rawLiterals.isEmpty()||!frame(full).equals(JSON.toJson(full)))throw new IllegalStateException("Memoized full frame differs from ordinary Gson bytes");
        Object replacement=Map.of("id",id,"revision",1,"request",Map.of("principal","two"),"rows",List.of("changed"));
        if(!cached(id,replacement).equals(JSON.toJsonTree(replacement))||!before.getAsJsonObject("request").get("principal").getAsString().equals("one"))
            throw new IllegalStateException("Replacement reused stale metadata or changed an older capture");
        if(!frame(full).equals(JSON.toJson(full)))throw new IllegalStateException("An older full frame changed after metadata replacement");
        for(int n=0;n<MAX_ROWS+2;n++){UUID next=UUID.randomUUID();cached(next,Map.of("id",next,"index",n));}
        if(CACHE.size()>MAX_ROWS||bytes>MAX_BYTES)throw new IllegalStateException("Metadata encoding cache quota");
        if(!frame(full).equals(JSON.toJson(full)))throw new IllegalStateException("An older full frame changed after cache eviction");
        System.out.println("A04_OBSERVATION_JSON_SELF_TEST_PASS exact-full-frame-bytes immutable-captures replacement-origin bounded-cache");
    }
}
