package dev.aivillages.core.kernel.runtime.activity;

import dev.aivillages.core.kernel.StrictJson;
import dev.aivillages.core.kernel.production.RecipeChoice;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.production.planning.PlanningModel.*;

/** Persisted immutable B plan body. Rebinding still requires the current authoritative A frame. */
public record ActivityPlan(String planDigest,List<Action> actions,Map<String,MethodPin> methods,boolean exactChildren) {
    private static final int INLINE_PLAN_CHARS = 4_096;

    public ActivityPlan(String planDigest,List<Action> actions,Map<String,MethodPin> methods) {
        this(planDigest,actions,methods,false);
    }
    public ActivityPlan {
        if(!dev.aivillages.core.kernel.RecipeTransaction.hash(planDigest) || actions.isEmpty()
                || actions.size()>MAX_ACTIONS || methods.isEmpty() || methods.size()>MAX_ACTIONS)
            throw new IllegalArgumentException("Bounded pinned production plan");
        actions=List.copyOf(actions);methods=Map.copyOf(methods);
    }
    public static ActivityPlan of(ValidatedPlan plan){return of(plan,false);}
    public static ActivityPlan of(ValidatedPlan plan,boolean exactChildren){return new ActivityPlan(plan.digest(),plan.actions(),plan.methodPins(),exactChildren);}
    public ValidatedPlan bind(Snapshot current) {
        var plan=new ValidatedPlan(current.world(),current.root(),current.owner().originalOwner(),current.context(),
                current.goal(),current.budget().initialDigest(),current.budget().absoluteDeadlineMillis(),actions,methods);
        if(!plan.digest().equals(planDigest))throw new IllegalArgumentException("Original planned goal/method changed");
        return plan;
    }
    public Set<String> tokens(){
        var tokens=new HashSet<String>();
        for(int index=0;index<actions.size();index++)tokens.add(actions.get(index).token(index));
        return Set.copyOf(tokens);
    }
    public Map<String,Object> encode(){
        var pinRows=new TreeMap<String,Object>();
        methods.forEach((role,pin)->pinRows.put(role,Map.of("capability",capability(pin.capability()),
                "artifact",pin.artifactHash(),"semantic",pin.semanticFingerprint(),"usable",pin.usable(),
                "artifactVersion",pin.artifactVersion(),"contractVersion",pin.contractVersion())));
        var actionRows=new ArrayList<Map<String,Object>>();
        for(var action:actions) {
            var row=new TreeMap<>(action.value());
            var cost=action.allowance();
            row.put("allowance",Map.of("travel",cost.travel(),"effects",cost.effects(),"ticks",cost.ticks()));
            actionRows.add(row);
        }
        var body=new TreeMap<String,Object>(Map.of("planDigest",planDigest,"actions",actionRows,"methods",pinRows));
        if(exactChildren)body.put("exactChildren",true);
        String text=StrictJson.canonical(body);
        String digest=ActivityPayload.digest(text);
        if(text.length()<=INLINE_PLAN_CHARS && actions.stream().noneMatch(action->
                longComponents(action.result()) || action.inputs().keySet().stream().anyMatch(ActivityPlan::longComponents)))
            return Map.of("body",body,"sha256",digest);
        return Map.of("deflatedBody",ActivityEncoding.deflate(text.getBytes(StandardCharsets.UTF_8),StrictJson.MAX_BYTES),
                "sha256",digest);
    }
    public static ActivityPlan decode(Map<String,Object> envelope) {
        Map<String,Object> body;
        if(envelope.containsKey("deflatedBody")) {
            keys(envelope,"deflatedBody","sha256");
            String text=ActivityEncoding.utf8(ActivityEncoding.inflate(envelope.get("deflatedBody"),StrictJson.MAX_BYTES));
            try { body=StrictJson.transportObject(text); }
            catch(StrictJson.Invalid invalid) { throw new IllegalArgumentException("Invalid pinned plan body",invalid); }
            if(!StrictJson.canonical(body).equals(text)) throw new IllegalArgumentException("Noncanonical pinned plan body");
        } else {
            keys(envelope,"body","sha256");
            body=object(envelope.get("body"));
        }
        boolean exactChildren=body.containsKey("exactChildren");
        if(exactChildren) {
            keys(body,"planDigest","actions","methods","exactChildren");
            if(!bool(body,"exactChildren"))throw new IllegalArgumentException("Pinned child transfer policy");
        } else keys(body,"planDigest","actions","methods");
        if(!ActivityPayload.digest(StrictJson.canonical(body)).equals(string(envelope,"sha256")))
            throw new IllegalArgumentException("Pinned plan body digest differs");
        var decoded=new ArrayList<Action>();
        for(var value:list(body,"actions",MAX_ACTIONS)) {
            var action=object(value);
            keys(action,"kind","recipe","capability","inputs","result","yield","demand","root","source","allowance");
            var inputs=new HashMap<RecipeChoice.ItemKey,Long>();
            for(var bound:list(action,"inputs",9)) {
                var row=object(bound);keys(row,"item","units");
                if(inputs.putIfAbsent(item(row.get("item")),number(row,"units"))!=null)throw new IllegalArgumentException("Duplicated input");
            }
            var cost=object(action.get("allowance"));keys(cost,"travel","effects","ticks");
            decoded.add(new Action(Kind.valueOf(string(action,"kind")),string(action,"recipe"),capability(action.get("capability")),
                    inputs,item(action.get("result")),integer(action,"yield"),number(action,"demand"),bool(action,"root"),
                    new Cost(number(cost,"travel"),number(cost,"effects"),number(cost,"ticks")),string(action,"source")));
        }
        var pins=new TreeMap<String,MethodPin>();
        object(body.get("methods")).forEach((role,value)->{
            var pin=object(value);keys(pin,"capability","artifact","semantic","usable","artifactVersion","contractVersion");
            pins.put(role,new MethodPin(capability(pin.get("capability")),string(pin,"artifact"),string(pin,"semantic"),
                    bool(pin,"usable"),integer(pin,"artifactVersion"),integer(pin,"contractVersion")));
        });
        return new ActivityPlan(string(body,"planDigest"),decoded,pins,exactChildren);
    }
    private static Map<String,Object> capability(CapabilityId id){return Map.of("name",id.name(),"version",id.version());}
    private static CapabilityId capability(Object value){var row=object(value);keys(row,"name","version");return new CapabilityId(string(row,"name"),integer(row,"version"));}
    private static boolean longComponents(RecipeChoice.ItemKey key){return key.components().length()>StrictJson.MAX_STRING;}
    private static RecipeChoice.ItemKey item(Object value){var row=object(value);keys(row,"id","components");return new RecipeChoice.ItemKey(string(row,"id"),string(row,"components"));}
    private static void keys(Map<String,Object> row,String... names){if(!row.keySet().equals(Set.of(names)))throw new IllegalArgumentException("Pinned plan fields");}
    private static long number(Map<String,Object> row,String key){if(!(row.get(key) instanceof Long n))throw new IllegalArgumentException("Pinned plan number");return n;}
    private static int integer(Map<String,Object> row,String key){return Math.toIntExact(number(row,key));}
    private static String string(Map<String,Object> row,String key){if(!(row.get(key) instanceof String text))throw new IllegalArgumentException("Pinned plan text");return text;}
    private static boolean bool(Map<String,Object> row,String key){if(!(row.get(key) instanceof Boolean value))throw new IllegalArgumentException("Pinned plan boolean");return value;}
    private static List<?> list(Map<String,Object> row,String key,int maximum){if(!(row.get(key) instanceof List<?> values) || values.size()>maximum)throw new IllegalArgumentException("Pinned plan list");return values;}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){if(!(value instanceof Map<?,?> row))throw new IllegalArgumentException("Pinned plan object");return (Map<String,Object>)row;}
}
