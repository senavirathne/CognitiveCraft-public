package dev.aivillages.fabric;

import com.google.gson.*;
import dev.aivillages.core.kernel.Jobs;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Test-only encoding cache, called only after the original native authorization query. */
public final class RuntimeAObservationJson {
    private static final Gson JSON=new Gson();
    private static final boolean ENABLED="true".equalsIgnoreCase(System.getenv("COGNITIVECRAFT_A04_PROBE"));
    private static final int MAX_ROWS=128,MAX_BYTES=16*1024*1024;
    private record Entry(Object original,JsonObject tree,int bytes) {}
    private static final LinkedHashMap<UUID,Entry> CACHE=new LinkedHashMap<>(128,.75f,true);
    private static int bytes;
    public static JsonElement job(Jobs.Job job) {
        return ENABLED ? cached(job.id(),job) : JSON.toJsonTree(job);
    }
    private static synchronized JsonObject cached(UUID id,Object value) {
        Entry entry=CACHE.get(id);
        // Jobs publishes immutable records. Any replacement object is encoded again,
        // even if a caller accidentally keeps an old revision or changes its origin.
        if(entry==null||entry.original()!=value) {
            JsonObject tree=JSON.toJsonTree(value).getAsJsonObject();int size=JSON.toJson(tree).getBytes(StandardCharsets.UTF_8).length;
            if(entry!=null){CACHE.remove(id);bytes-=entry.bytes();}
            entry=new Entry(value,tree,size);
            if(size<=MAX_BYTES){CACHE.put(id,entry);bytes+=size;}
            while(CACHE.size()>MAX_ROWS||bytes>MAX_BYTES){var iterator=CACHE.entrySet().iterator();var oldest=iterator.next();bytes-=oldest.getValue().bytes();iterator.remove();}
        }
        // The observer adds fulfilled/dispatch fields only to this fresh outer object.
        // Cached nested values are never mutated; an older captured frame stays intact.
        var result=new JsonObject();for(var field:entry.tree().entrySet())result.add(field.getKey(),field.getValue());return result;
    }
    public static void main(String[] args) {
        UUID id=UUID.randomUUID();Object first=Map.of("id",id,"revision",1,"request",Map.of("principal","one"),"rows",List.of("λ","escaped \\\""));
        JsonObject before=cached(id,first);before.addProperty("dispatch","first-capture");
        JsonObject second=cached(id,first);
        if(second.has("dispatch")||!second.equals(JSON.toJsonTree(first)))throw new IllegalStateException("Mutable outer fields leaked into metadata cache");
        Object replacement=Map.of("id",id,"revision",1,"request",Map.of("principal","two"),"rows",List.of("changed"));
        if(!cached(id,replacement).equals(JSON.toJsonTree(replacement))||!before.getAsJsonObject("request").get("principal").getAsString().equals("one"))
            throw new IllegalStateException("Replacement reused stale metadata or changed an older capture");
        for(int n=0;n<MAX_ROWS+2;n++){UUID next=UUID.randomUUID();cached(next,Map.of("id",next,"index",n));}
        if(CACHE.size()>MAX_ROWS||bytes>MAX_BYTES)throw new IllegalStateException("Metadata encoding cache quota");
        System.out.println("A04_OBSERVATION_JSON_SELF_TEST_PASS exact-bytes immutable-captures replacement-origin bounded-cache");
    }
}
