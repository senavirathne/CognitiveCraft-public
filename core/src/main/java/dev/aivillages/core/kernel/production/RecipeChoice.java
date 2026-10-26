package dev.aivillages.core.kernel.production;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import dev.aivillages.core.kernel.*;
import static dev.aivillages.core.kernel.Contracts.*;

/** Immutable proposal data. Native matching and A's execution authority are still required. */
public record RecipeChoice(CapabilityId capability,String recipe,String semanticFingerprint,String runtimeCompatibility,
                           long reloadEpoch,int width,int height,List<Batch> batches,ItemKey result,int resultCount,
                           long demand,List<ItemAmount> remainders) {
    public static final int SCHEMA=1, COMPATIBILITY_VERSION=2;
    public static final String RUNTIME="minecraft:26.3/native-static-choice-v2";
    public static final int MAX_BYTES=65_536;

    public record ItemKey(String id,String components) {
        public static final ItemKey EMPTY=new ItemKey("minecraft:air","{}");
        public ItemKey {
            identifier(id);
            if(components==null || components.getBytes(StandardCharsets.UTF_8).length>8192
                    || !canonicalComponents(components).equals(components))throw new IllegalArgumentException("Canonical exact components required");
            if(id.equals("minecraft:air") && !components.equals("{}"))throw new IllegalArgumentException("Empty grid cell cannot carry components");
        }
        public String componentDigest(){return RecipeTransaction.fingerprint(components);}
        public boolean empty(){return id.equals("minecraft:air");}
        public Map<String,Object> value(){return Map.of("id",id,"components",components);}
    }
    /** One actual unit for one ordered grid cell. Counts are before this unit is removed. */
    public record Binding(int gridSlot,ContainerRef source,int sourceSlot,int expectedCount,ItemKey item) {
        public Binding {
            Objects.requireNonNull(source);Objects.requireNonNull(item);
            if(gridSlot<0 || gridSlot>8 || sourceSlot<0 || sourceSlot>127 || expectedCount<1 || expectedCount>64 || item.empty())
                throw new IllegalArgumentException("Bounded exact source/grid binding");
        }
        public Map<String,Object> value(){return Map.of("gridSlot",gridSlot,"source",container(source),"sourceSlot",sourceSlot,"expectedCount",expectedCount,"item",item.value());}
    }
    public record Batch(List<ItemKey> grid,List<Binding> bindings) {
        public Batch {
            grid=List.copyOf(grid);bindings=List.copyOf(bindings);
            if(grid.isEmpty() || grid.size()>9 || bindings.isEmpty() || bindings.size()>9
                    || bindings.stream().map(Binding::gridSlot).distinct().count()!=bindings.size())
                throw new IllegalArgumentException("Bounded concrete grid");
            for(int i=0;i<grid.size();i++) {
                final int cell=i;var selected=bindings.stream().filter(b->b.gridSlot()==cell).toList();
                if(grid.get(i).empty() ? !selected.isEmpty() : selected.size()!=1 || !selected.getFirst().item().equals(grid.get(i)))
                    throw new IllegalArgumentException("Every nonempty grid cell needs its exact source unit");
            }
            int gridSize=grid.size();
            if(bindings.stream().anyMatch(b->b.gridSlot()>=gridSize))throw new IllegalArgumentException("Foreign grid slot");
        }
        public Map<String,Object> value(){return Map.of("grid",grid.stream().map(ItemKey::value).toList(),"bindings",bindings.stream().map(Binding::value).toList());}
    }
    public record ItemAmount(ItemKey item,long count) {
        public ItemAmount {Objects.requireNonNull(item);if(item.empty() || count<1 || count>64)throw new IllegalArgumentException("Bounded remainder");}
        public Map<String,Object> value(){return Map.of("item",item.value(),"count",count);}
    }
    public RecipeChoice {
        Objects.requireNonNull(capability);Objects.requireNonNull(result);
        identifier(recipe);
        var definition=RecipeProduction.choiceDefinition(capability);
        var quantities=definition.quantities(demand,BatchQuantities.Role.ROOT);
        batches=List.copyOf(batches);remainders=List.copyOf(remainders);
        if(!RecipeTransaction.hash(semanticFingerprint) || !RUNTIME.equals(runtimeCompatibility) || reloadEpoch<0
                || width<1 || height<1 || width>3 || height>3 || batches.size()!=quantities.batches()
                || !recipe.equals(definition.recipe()) || !result.id().equals(definition.output().id())
                || resultCount!=definition.outputPerBatch() || remainders.size()>9)
            throw new IllegalArgumentException("Unsupported concrete recipe choice contract");
        if(batches.stream().anyMatch(b->b.grid().size()!=width*height))throw new IllegalArgumentException("Ordered grid shape mismatch");
        var dimensions=batches.stream().flatMap(b->b.bindings().stream()).map(b->b.source().dimension()).distinct().toList();
        if(dimensions.size()!=1)throw new IllegalArgumentException("Cross-dimension recipe inputs");
        // Exact source-count transitions are verified natively at each preparation.
        // Merely constructing this value never proves stock or permissions.
        String canonical=canonicalValue(capability,recipe,semanticFingerprint,runtimeCompatibility,reloadEpoch,width,height,batches,result,resultCount,demand,remainders);
        if(canonical.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IllegalArgumentException("Choice byte bound");
    }
    public BatchQuantities quantities(){return RecipeProduction.choiceDefinition(capability).quantities(demand,BatchQuantities.Role.ROOT);}
    public long deliveryForBatch(int index){batch(index);return Math.min(resultCount,Math.subtractExact(demand,Math.multiplyExact((long)index,resultCount)));}
    public Batch batch(int index){return batches.get(index);}
    public String canonical(){return canonicalValue(capability,recipe,semanticFingerprint,runtimeCompatibility,reloadEpoch,width,height,batches,result,resultCount,demand,remainders);}
    public byte[] canonicalBytes(){return canonical().getBytes(StandardCharsets.UTF_8);}
    public String digest(){return RecipeTransaction.fingerprint(canonical());}
    public Map<ItemKey,Long> exactInputs(){var counts=new HashMap<ItemKey,Long>();for(var b:batches)for(var binding:b.bindings())counts.merge(binding.item(),1L,Math::addExact);return Map.copyOf(counts);}
    public static RecipeChoice fromCanonical(String text) {
        try {
            var v=StrictJson.transportObject(text);
            keys(v,"schema","compatibilityVersion","capability","recipe","semanticFingerprint","runtimeCompatibility","reloadEpoch","width","height","batches","result","resultCount","demand","remainders");
            if(number(v,"schema")!=SCHEMA || number(v,"compatibilityVersion")!=COMPATIBILITY_VERSION)throw new IllegalArgumentException("Choice version");
            var cap=object(v.get("capability"));keys(cap,"name","version");
            var batches=new ArrayList<Batch>();
            for(var raw:list(v.get("batches"))) {
                var batch=object(raw);keys(batch,"grid","bindings");var grid=list(batch.get("grid")).stream().map(RecipeChoice::item).toList();
                var bindings=new ArrayList<Binding>();
                for(var b:list(batch.get("bindings"))) {
                    var value=object(b);keys(value,"gridSlot","source","sourceSlot","expectedCount","item");var source=object(value.get("source"));keys(source,"dimension","x","y","z");
                    bindings.add(new Binding(integer(value,"gridSlot"),new ContainerRef(string(source,"dimension"),integer(source,"x"),integer(source,"y"),integer(source,"z")),
                            integer(value,"sourceSlot"),integer(value,"expectedCount"),item(value.get("item"))));
                }
                batches.add(new Batch(grid,bindings));
            }
            var remainders=new ArrayList<ItemAmount>();for(var raw:list(v.get("remainders"))) {var value=object(raw);keys(value,"item","count");remainders.add(new ItemAmount(item(value.get("item")),number(value,"count")));}
            var choice=new RecipeChoice(new CapabilityId(string(cap,"name"),integer(cap,"version")),string(v,"recipe"),string(v,"semanticFingerprint"),
                    string(v,"runtimeCompatibility"),number(v,"reloadEpoch"),integer(v,"width"),integer(v,"height"),batches,item(v.get("result")),
                    integer(v,"resultCount"),number(v,"demand"),remainders);
            if(!choice.canonical().equals(text))throw new IllegalArgumentException("Noncanonical choice bytes");return choice;
        } catch(StrictJson.Invalid invalid){throw new IllegalArgumentException("Invalid canonical choice",invalid);}
    }
    private static ItemKey item(Object raw){var value=object(raw);keys(value,"id","components");return new ItemKey(string(value,"id"),string(value,"components"));}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){if(!(value instanceof Map<?,?> m))throw new IllegalArgumentException("Object required");return (Map<String,Object>)m;}
    private static List<?> list(Object value){if(!(value instanceof List<?> list))throw new IllegalArgumentException("List required");return list;}
    private static String string(Map<String,Object> v,String key){if(!(v.get(key) instanceof String s))throw new IllegalArgumentException("String required");return s;}
    private static long number(Map<String,Object> v,String key){if(!(v.get(key) instanceof Long n))throw new IllegalArgumentException("Integer required");return n;}
    private static int integer(Map<String,Object> v,String key){return Math.toIntExact(number(v,key));}
    private static void keys(Map<String,Object> value,String... expected){if(!value.keySet().equals(Set.of(expected)))throw new IllegalArgumentException("Unknown/missing choice fields");}
    public static Map<String,Object> container(ContainerRef ref){return Map.of("dimension",ref.dimension(),"x",ref.x(),"y",ref.y(),"z",ref.z());}
    private static String canonicalValue(CapabilityId capability,String recipe,String semantic,String runtime,long epoch,int width,int height,
                                         List<Batch> batches,ItemKey result,int count,long demand,List<ItemAmount> remainders) {
        var value=new TreeMap<String,Object>();value.put("schema",SCHEMA);value.put("compatibilityVersion",COMPATIBILITY_VERSION);
        value.put("capability",Map.of("name",capability.name(),"version",capability.version()));value.put("recipe",recipe);
        value.put("semanticFingerprint",semantic);value.put("runtimeCompatibility",runtime);value.put("reloadEpoch",epoch);
        value.put("width",width);value.put("height",height);value.put("batches",batches.stream().map(Batch::value).toList());
        value.put("result",result.value());value.put("resultCount",count);value.put("demand",demand);
        value.put("remainders",remainders.stream().map(ItemAmount::value).toList());return StrictJson.canonical(value);
    }
    /** Full native component codec output is canonicalized separately; outer counts remain integers. */
    public static String canonicalComponents(String json) {
        var value=JsonParser.parseString(json);if(!value.isJsonObject())throw new IllegalArgumentException("Component object required");
        return new GsonBuilder().disableHtmlEscaping().create().toJson(ordered(value));
    }
    private static JsonElement ordered(JsonElement value) {
        if(value.isJsonObject()){var out=new JsonObject();new TreeSet<>(value.getAsJsonObject().keySet()).forEach(k->out.add(k,ordered(value.getAsJsonObject().get(k))));return out;}
        if(value.isJsonArray()){var out=new JsonArray();value.getAsJsonArray().forEach(v->out.add(ordered(v)));return out;}
        if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber())return new JsonPrimitive(value.getAsBigDecimal().stripTrailingZeros());
        return value.deepCopy();
    }
    private static void identifier(String id){if(id==null || !id.matches("[a-z0-9_.-]+:[a-z0-9_/.-]{1,96}"))throw new IllegalArgumentException("Namespaced identity required");}
}
