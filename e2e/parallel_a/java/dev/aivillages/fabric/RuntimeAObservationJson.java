package dev.aivillages.fabric;

import com.google.gson.*;
import com.google.gson.stream.JsonWriter;
import dev.aivillages.core.kernel.Jobs;
import dev.aivillages.core.kernel.BootstrapController;
import dev.aivillages.core.kernel.ResourceLeases;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Test-only encoding cache, called only after the original native authorization query. */
public final class RuntimeAObservationJson {
    private static final Gson JSON=new Gson();
    private static final boolean ENABLED="true".equalsIgnoreCase(System.getenv("COGNITIVECRAFT_A04_PROBE"));
    private static final int MAX_ROWS=128,MAX_BYTES=16*1024*1024;
    private record Key(String kind,UUID id) {}
    private record Entry(Object original,JsonElement tree,int bytes,Map<JsonElement,String> literals) {}
    private static final LinkedHashMap<Key,Entry> CACHE=new LinkedHashMap<>(128,.75f,true);
    private static int bytes;
    private static volatile Map<JsonElement,String> rawLiterals=Collections.emptyMap();
    public static JsonElement job(Jobs.Job job) {
        return ENABLED ? cached(job.id(),job) : JSON.toJsonTree(job);
    }
    public static JsonElement leases(UUID principal,List<ResourceLeases.Lease> leases) {
        return ENABLED ? entry(new Key("LEASES",principal),List.copyOf(leases),true).tree() : JSON.toJsonTree(leases);
    }
    public static JsonObject run(UUID principal,UUID id,BootstrapController.View view) {
        return ENABLED ? outer(entry(new Key("RUN/"+principal,id),view,true).tree()) : JSON.toJsonTree(view).getAsJsonObject();
    }
    private static JsonObject cached(UUID id,Object value) {return outer(entry(new Key("JOB",id),value,false).tree());}
    private static JsonObject outer(JsonElement tree) {
        var result=new JsonObject();for(var field:tree.getAsJsonObject().entrySet())result.add(field.getKey(),field.getValue());return result;
    }
    private static synchronized Entry entry(Key key,Object value,boolean immutableValueEquality) {
        Entry entry=CACHE.get(key);
        // Jobs publishes immutable records. Any replacement object is encoded again,
        // even if a caller accidentally keeps an old revision or changes its origin.
        if(entry==null||entry.original()!=value&&(!immutableValueEquality||!entry.original().equals(value))) {
            JsonElement tree=JSON.toJsonTree(value);int size=JSON.toJson(tree).getBytes(StandardCharsets.UTF_8).length;
            var literals=new IdentityHashMap<JsonElement,String>();
            if(tree.isJsonArray()){String raw=JSON.toJson(tree);if(raw.length()>=256){literals.put(tree,raw);size+=raw.getBytes(StandardCharsets.UTF_8).length;}}
            if(tree.isJsonObject())for(var field:tree.getAsJsonObject().entrySet())if(field.getValue().isJsonObject()||field.getValue().isJsonArray()){
                String raw=JSON.toJson(field.getValue());if(raw.length()>=256){literals.put(field.getValue(),raw);size+=raw.getBytes(StandardCharsets.UTF_8).length;}
            }
            if(entry!=null){CACHE.remove(key);bytes-=entry.bytes();}
            entry=new Entry(value,tree,size,Collections.unmodifiableMap(literals));
            if(size<=MAX_BYTES){CACHE.put(key,entry);bytes+=size;}
            while(CACHE.size()>MAX_ROWS||bytes>MAX_BYTES){var iterator=CACHE.entrySet().iterator();var oldest=iterator.next();bytes-=oldest.getValue().bytes();iterator.remove();}
            var next=new IdentityHashMap<JsonElement,String>();for(Entry cached:CACHE.values())next.putAll(cached.literals());
            rawLiterals=Collections.unmodifiableMap(next);
        }
        return entry;
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
        Key allowed=new Key("LEASES",id),denied=new Key("LEASES",UUID.randomUUID());
        List<?> visible=List.of(first);JsonElement originalLeases=entry(allowed,visible,true).tree();
        if(originalLeases!=entry(allowed,new ArrayList<>(visible),true).tree())throw new IllegalStateException("Equal immutable view was encoded again");
        if(!entry(denied,List.of(),true).tree().equals(JSON.toJsonTree(List.of())))throw new IllegalStateException("Denied filtered view reused another principal");
        if(!entry(allowed,List.of(replacement),true).tree().equals(JSON.toJsonTree(List.of(replacement)))||!originalLeases.equals(JSON.toJsonTree(visible)))
            throw new IllegalStateException("Changed immutable view or older capture reused stale bytes");
        full.add("oldLeases",originalLeases);
        if(!frame(full).equals(JSON.toJson(full)))throw new IllegalStateException("Full array capture differs from ordinary Gson");
        for(int n=0;n<MAX_ROWS+2;n++){UUID next=UUID.randomUUID();cached(next,Map.of("id",next,"index",n));}
        if(CACHE.size()>MAX_ROWS||bytes>MAX_BYTES)throw new IllegalStateException("Metadata encoding cache quota");
        if(!frame(full).equals(JSON.toJson(full)))throw new IllegalStateException("An older full frame changed after cache eviction");
        System.out.println("A04_OBSERVATION_JSON_SELF_TEST_PASS exact-full-frame-bytes immutable-captures replacement-origin principal-filtered-views bounded-cache");
    }
}
