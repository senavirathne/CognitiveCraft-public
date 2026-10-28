package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;

/** Bounded harvest data. Constructors and classifiers supply no execution or Jobs authority. */
public final class NativeHarvest {
    private NativeHarvest() { }
    public static final int MAX_ENTRIES=64,MAX_RECORD_BYTES=32_768;
    public enum Live { NO_EFFECT,COMPLETE,PARTIAL,UNRECEIPTED_EFFECT }
    public enum Reconciliation { WAIT_WORLD,UNPROVEN,TORN_SAVED_COHORT,SAVED_COMPLETE,SAVED_NO_EFFECT,PARTIAL_UNRESOLVED }
    public record Source(String dimension,int x,int y,int z,UUID incarnation,long revision,String state) {
        public Source {
            Objects.requireNonNull(incarnation);new ContainerRef(dimension,x,y,z);
            if(revision<0 || state==null || state.isBlank() || state.length()>512 || state.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Native source incarnation/state envelope");
        }
        public boolean sameIdentity(Source other){return other!=null && dimension.equals(other.dimension) && x==other.x && y==other.y && z==other.z && incarnation.equals(other.incarnation);}
    }
    public record Drop(String dimension,UUID entity,UUID incarnation,long revision,String item,int quantity) {
        public Drop {
            Objects.requireNonNull(entity);Objects.requireNonNull(incarnation);new ResourceDelivery.ItemIdentity(item,"default");new ContainerRef(dimension,0,0,0);
            if(revision<0 || quantity<1 || quantity>64)throw new IllegalArgumentException("Native tracked drop envelope");
        }
    }
    public record Intent(Key key,TrustedContext origin,UUID run,UUID operation,ActorRef actor,Cuboid area,ContainerRef destination,String item,Source source,Conditions conditions,Participant actorBefore) {
        public Intent {
            Objects.requireNonNull(key);Objects.requireNonNull(origin);Objects.requireNonNull(run);Objects.requireNonNull(operation);
            Objects.requireNonNull(actor);Objects.requireNonNull(area);Objects.requireNonNull(destination);Objects.requireNonNull(source);Objects.requireNonNull(conditions);Objects.requireNonNull(actorBefore);
            if(actorBefore.kind()!=Kind.ACTOR || !actorBefore.address().equals(actor.entityId().toString()) || !actorBefore.dimension().equals(actor.dimension())
                    || key.ordinal()!=Math.addExact(conditions.usage().getOrDefault(Budgets.Kind.COMMITTED_EFFECTS,0L),1L)
                    || !key.world().equals(origin.scope().worldId()) || !actor.citizenId().equals(conditions.citizen())
                    || !actor.dimension().equals(area.dimension()) || !actor.dimension().equals(destination.dimension()) || !actor.dimension().equals(source.dimension)
                    || !Set.of("minecraft:oak_log","minecraft:birch_log").contains(item)
                    || !new ResourceDelivery.BlockPosition(source.dimension,source.x,source.y,source.z).inside(area))
                throw new IllegalArgumentException("Original harvest intent binding");
        }
        public String digest(){return sha(StrictJson.canonical(intent(this)));}
    }
    public record Result(String intent,Live live,Source source,List<Drop> drops,ResourceDelivery.Receipt receipt,Map<Budgets.Kind,Long> usage,String failure,Participant actor) {
        public Result {
            hash(intent);Objects.requireNonNull(live);Objects.requireNonNull(source);Objects.requireNonNull(actor);drops=List.copyOf(drops);usage=Map.copyOf(usage);
            if(drops.size()>64 || drops.stream().map(Drop::entity).distinct().count()!=drops.size() || usage.values().stream().anyMatch(n->n<0)
                    || failure==null || failure.length()>256 || live==Live.COMPLETE && receipt==null
                    || (live==Live.NO_EFFECT || live==Live.UNRECEIPTED_EFFECT) && receipt!=null)
                throw new IllegalArgumentException("Actual native harvest result envelope");
            if(receipt!=null && (receipt.stage()!=ResourceDelivery.Stage.HARVEST || receipt.transformation()!=null || receipt.placement()!=null))
                throw new IllegalArgumentException("Original harvest receipt required");
        }
        public String digest(){return sha(StrictJson.canonical(result(this)));}
    }
    public record SourceWitness(Source source,Marker marker,boolean restoredFromNativeSave){public SourceWitness{Objects.requireNonNull(source);}}
    public record DropWitness(Drop drop,Marker marker,boolean restoredFromNativeSave){public DropWitness{Objects.requireNonNull(drop);}}
    public record Verification(Reconciliation outcome,Witness actor,SourceWitness source,List<DropWitness> drops,UUID process,long observedAt) {
        public Verification {
            Objects.requireNonNull(outcome);Objects.requireNonNull(actor);Objects.requireNonNull(source);Objects.requireNonNull(process);drops=List.copyOf(drops);
            if(observedAt<0 || drops.size()>64 || outcome!=Reconciliation.SAVED_COMPLETE && outcome!=Reconciliation.SAVED_NO_EFFECT)
                throw new IllegalArgumentException("Only complete saved harvest evidence can close an exclusion");
        }
    }
    public record Entry(Intent intent,Result result,Verification verification) {
        public Entry(Intent intent,Result result){this(intent,result,null);}
        public Entry {
            Objects.requireNonNull(intent);
            if(result!=null) {
                if(!result.intent.equals(intent.digest()) || !intent.source.sameIdentity(result.source) || result.source.revision<intent.source.revision
                        || !(result.live==delta(intent,result.source,result.drops,result.actor)
                            || result.live==Live.UNRECEIPTED_EFFECT && delta(intent,result.source,result.drops,result.actor)==Live.COMPLETE && result.receipt==null))
                    throw new IllegalArgumentException("Changed native harvest identity or actual delta");
                for(var before:intent.conditions.usage().entrySet())if(result.usage.getOrDefault(before.getKey(),0L)<before.getValue())throw new IllegalArgumentException("Harvest usage decreased");
                long committed=result.usage.getOrDefault(Budgets.Kind.COMMITTED_EFFECTS,0L)-intent.conditions.usage().getOrDefault(Budgets.Kind.COMMITTED_EFFECTS,0L);
                long attempted=result.usage.getOrDefault(Budgets.Kind.ATTEMPTED_EFFECTS,0L)-intent.conditions.usage().getOrDefault(Budgets.Kind.ATTEMPTED_EFFECTS,0L);
                if(committed<0 || committed>1 || attempted<0 || attempted>1 || result.live==Live.COMPLETE && (committed!=1 || attempted!=1) || result.live==Live.NO_EFFECT && committed!=0)
                    throw new IllegalArgumentException("Original harvest allowance delta");
                if(result.receipt!=null) {
                    var r=result.receipt;
                    if(!r.origin().equals(intent.origin) || !r.runId().equals(intent.run) || !r.operationId().equals(intent.operation)
                            || !r.actor().equals(intent.actor) || !r.source().equals(intent.area) || !r.destination().equals(intent.destination)
                            || !r.item().equals(new ResourceDelivery.ItemIdentity(intent.item,"default"))
                            || !r.sourceBlock().equals(new ResourceDelivery.BlockPosition(intent.source.dimension,intent.source.x,intent.source.y,intent.source.z))
                            || !r.dropIds().equals(result.drops.stream().map(Drop::entity).toList()) || r.quantity()!=result.drops.stream().mapToLong(Drop::quantity).sum())
                        throw new IllegalArgumentException("Actual original gateway harvest receipt binding");
                }
            }
            if(verification!=null && classify(new Entry(intent,result),verification.actor,verification.source,verification.drops)!=verification.outcome)
                throw new IllegalArgumentException("Saved harvest verification does not prove its own outcome");
        }
        public String token(){return intent.key.token();}
    }
    private static Live delta(Intent intent,Source source,List<Drop> drops,Participant actor) {
        if(!intent.actorBefore.sameIdentity(actor) || !intent.source.sameIdentity(source))throw new IllegalArgumentException("Harvest source replaced");
        if(intent.actorBefore.equals(actor) && intent.source.equals(source) && drops.isEmpty())return Live.NO_EFFECT;
        return intent.actorBefore.equals(actor) && source.revision>intent.source.revision && source.state.equals("minecraft:air") && !drops.isEmpty()
                && drops.stream().allMatch(d->d.dimension.equals(intent.source.dimension) && d.item.equals(intent.item))?Live.COMPLETE:Live.PARTIAL;
    }
    public static Result observed(Intent intent,Source source,List<Drop> drops,ResourceDelivery.Receipt receipt,Map<Budgets.Kind,Long> usage,String failure,Participant actor) {
        Live live=delta(intent,source,drops,actor);if(live==Live.COMPLETE && receipt==null)live=Live.UNRECEIPTED_EFFECT;
        return new Result(intent.digest(),live,source,drops,receipt,usage,failure,actor);
    }
    public static Reconciliation classify(Entry entry,Witness actor,SourceWitness source,List<DropWitness> drops) {
        if(actor==null || source==null || drops==null || drops.stream().anyMatch(Objects::isNull))return Reconciliation.WAIT_WORLD;
        if(!actor.restoredFromNativeSave() || actor.marker()==null || !entry.intent.actorBefore.sameIdentity(actor.participant())
                || !source.restoredFromNativeSave || source.marker==null || !entry.intent.source.sameIdentity(source.source)
                || !source.marker.effect().equals(entry.token()) || !source.marker.intent().equals(entry.intent.digest()))return Reconciliation.UNPROVEN;
        // A saved PREPARED source cannot exclude a separately saved spawned drop.
        if(entry.result==null || source.marker.phase()!=MarkerPhase.RESULT)return Reconciliation.PARTIAL_UNRESOLVED;
        if(!actor.marker().equals(source.marker) || !actor.participant().equals(entry.result.actor) || !source.marker.result().equals(entry.result.digest()) || !source.source.equals(entry.result.source))return Reconciliation.TORN_SAVED_COHORT;
        if(drops.size()!=entry.result.drops.size())return Reconciliation.WAIT_WORLD;
        for(int n=0;n<drops.size();n++) {
            var witness=drops.get(n);
            if(!witness.restoredFromNativeSave || witness.marker==null || !witness.drop.equals(entry.result.drops.get(n)))return Reconciliation.UNPROVEN;
            if(!witness.marker.equals(source.marker))return Reconciliation.TORN_SAVED_COHORT;
        }
        return switch(entry.result.live){case COMPLETE->Reconciliation.SAVED_COMPLETE;case NO_EFFECT->Reconciliation.SAVED_NO_EFFECT;case PARTIAL,UNRECEIPTED_EFFECT->Reconciliation.PARTIAL_UNRESOLVED;};
    }
    public static byte[] encode(Entry entry) {
        var row=new LinkedHashMap<String,Object>();row.put("schema",1L);row.put("intent",intent(entry.intent));
        if(entry.result!=null)row.put("result",result(entry.result));if(entry.verification!=null)row.put("verification",verification(entry.verification));
        byte[] raw=StrictJson.canonical(row).getBytes(StandardCharsets.UTF_8);if(raw.length>MAX_RECORD_BYTES)throw new IllegalArgumentException("Harvest record quota");return raw;
    }
    public static Entry decode(byte[] raw) {
        if(raw.length>MAX_RECORD_BYTES)throw new IllegalArgumentException("Harvest record quota");
        try {
            var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            var row=StrictJson.object(decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString());var fields=new HashSet<>(Set.of("schema","intent"));
            if(row.containsKey("result"))fields.add("result");if(row.containsKey("verification"))fields.add("verification");keys(row,fields);
            if(number(row,"schema")!=1)throw new IllegalArgumentException("Unknown harvest record schema");
            var entry=new Entry(intent(object(row,"intent")),row.containsKey("result")?result(object(row,"result")):null,row.containsKey("verification")?verification(object(row,"verification")):null);
            if(!Arrays.equals(raw,encode(entry)))throw new IllegalArgumentException("Noncanonical harvest record");return entry;
        }catch(StrictJson.Invalid|java.nio.charset.CharacterCodingException invalid){throw new IllegalArgumentException("Invalid harvest record",invalid);}
    }
    private static Map<String,Object> source(Source s){return Map.of("dimension",s.dimension,"x",s.x,"y",s.y,"z",s.z,"incarnation",s.incarnation.toString(),"revision",s.revision,"state",s.state);}
    private static Source source(Map<String,Object> r){keys(r,Set.of("dimension","x","y","z","incarnation","revision","state"));return new Source(string(r,"dimension"),integer(r,"x"),integer(r,"y"),integer(r,"z"),id(r,"incarnation"),number(r,"revision"),string(r,"state"));}
    private static Map<String,Object> drop(Drop d){return Map.of("dimension",d.dimension,"entity",d.entity.toString(),"incarnation",d.incarnation.toString(),"revision",d.revision,"item",d.item,"quantity",d.quantity);}
    private static Drop drop(Map<String,Object> r){keys(r,Set.of("dimension","entity","incarnation","revision","item","quantity"));return new Drop(string(r,"dimension"),id(r,"entity"),id(r,"incarnation"),number(r,"revision"),string(r,"item"),integer(r,"quantity"));}
    private static Map<String,Object> intent(Intent i) {
        var row=new LinkedHashMap<String,Object>();row.put("key",NativeDeposit.key(i.key));row.put("principal",i.origin.principal().id().toString());row.put("domain",i.origin.scope().domainId().toString());row.put("run",i.run.toString());row.put("operation",i.operation.toString());
        row.put("actor",Map.of("citizen",i.actor.citizenId().toString(),"entity",i.actor.entityId().toString(),"dimension",i.actor.dimension()));row.put("area",List.of(i.area.minX(),i.area.minY(),i.area.minZ(),i.area.maxX(),i.area.maxY(),i.area.maxZ()));row.put("destination",List.of(i.destination.x(),i.destination.y(),i.destination.z()));
        row.put("actorBefore",NativeDeposit.participant(i.actorBefore));row.put("item",i.item);row.put("source",source(i.source));row.put("conditions",NativeDeposit.conditions(i.conditions));return row;
    }
    private static Intent intent(Map<String,Object> r) {
        keys(r,Set.of("key","principal","domain","run","operation","actor","area","destination","item","source","conditions","actorBefore"));var key=NativeDeposit.key(object(r,"key"));var a=object(r,"actor");keys(a,Set.of("citizen","entity","dimension"));String dimension=string(a,"dimension");int[] area=coordinates(r,"area",6),destination=coordinates(r,"destination",3);
        return new Intent(key,new TrustedContext(new PrincipalRef(id(r,"principal")),new ScopeRef(key.world(),id(r,"domain"))),id(r,"run"),id(r,"operation"),new ActorRef(id(a,"citizen"),id(a,"entity"),dimension),new Cuboid(dimension,area[0],area[1],area[2],area[3],area[4],area[5]),new ContainerRef(dimension,destination[0],destination[1],destination[2]),string(r,"item"),source(object(r,"source")),NativeDeposit.conditions(object(r,"conditions")),NativeDeposit.participant(object(r,"actorBefore")));
    }
    private static int[] coordinates(Map<String,Object> r,String key,int size) {
        if(!(r.get(key) instanceof List<?> values) || values.size()!=size)throw new IllegalArgumentException("Harvest coordinate envelope");int[] result=new int[size];
        for(int n=0;n<size;n++){if(!(values.get(n) instanceof Long value))throw new IllegalArgumentException("Harvest coordinate integer");result[n]=Math.toIntExact(value);}return result;
    }
    private static Map<String,Object> result(Result r) {
        var row=new LinkedHashMap<String,Object>();row.put("intent",r.intent);row.put("actor",NativeDeposit.participant(r.actor));row.put("live",r.live.name());row.put("source",source(r.source));row.put("drops",r.drops.stream().map(NativeHarvest::drop).toList());
        row.put("usage",r.usage.entrySet().stream().collect(java.util.stream.Collectors.toMap(e->e.getKey().name(),Map.Entry::getValue)));row.put("failure",r.failure);if(r.receipt!=null)row.put("receipt",ResourceReceiptPrefixCodec.encode(r.receipt));return row;
    }
    private static Result result(Map<String,Object> r) {
        var fields=new HashSet<>(Set.of("intent","actor","live","source","drops","usage","failure"));if(r.containsKey("receipt"))fields.add("receipt");keys(r,fields);
        if(!(r.get("drops") instanceof List<?> values) || values.size()>64)throw new IllegalArgumentException("Harvest drop quota");var usage=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);var u=object(r,"usage");for(String key:u.keySet())usage.put(Budgets.Kind.valueOf(key),number(u,key));
        return new Result(string(r,"intent"),Live.valueOf(string(r,"live")),source(object(r,"source")),values.stream().map(v->drop(object(v))).toList(),r.containsKey("receipt")?ResourceReceiptPrefixCodec.decode(object(r,"receipt")):null,usage,string(r,"failure"),NativeDeposit.participant(object(r,"actor")));
    }
    private static Map<String,Object> sourceWitness(SourceWitness w){return Map.of("source",source(w.source),"marker",NativeDeposit.markerJson(w.marker),"restored",w.restoredFromNativeSave);}
    private static SourceWitness sourceWitness(Map<String,Object> r){keys(r,Set.of("source","marker","restored"));return new SourceWitness(source(object(r,"source")),NativeDeposit.markerJson(string(r,"marker")),bool(r,"restored"));}
    private static Map<String,Object> dropWitness(DropWitness w){return Map.of("drop",drop(w.drop),"marker",NativeDeposit.markerJson(w.marker),"restored",w.restoredFromNativeSave);}
    private static DropWitness dropWitness(Map<String,Object> r){keys(r,Set.of("drop","marker","restored"));return new DropWitness(drop(object(r,"drop")),NativeDeposit.markerJson(string(r,"marker")),bool(r,"restored"));}
    private static Map<String,Object> verification(Verification v){return Map.of("outcome",v.outcome.name(),"actor",Map.of("participant",NativeDeposit.participant(v.actor.participant()),"marker",NativeDeposit.markerJson(v.actor.marker()),"restored",v.actor.restoredFromNativeSave()),"source",sourceWitness(v.source),"drops",v.drops.stream().map(NativeHarvest::dropWitness).toList(),"process",v.process.toString(),"observedAt",v.observedAt);}
    private static Verification verification(Map<String,Object> r){keys(r,Set.of("outcome","actor","source","drops","process","observedAt"));if(!(r.get("drops") instanceof List<?> values) || values.size()>64)throw new IllegalArgumentException("Saved harvest drop quota");var a=object(r,"actor");keys(a,Set.of("participant","marker","restored"));return new Verification(Reconciliation.valueOf(string(r,"outcome")),new Witness(NativeDeposit.participant(object(a,"participant")),NativeDeposit.markerJson(string(a,"marker")),bool(a,"restored")),sourceWitness(object(r,"source")),values.stream().map(v->dropWitness(object(v))).toList(),id(r,"process"),number(r,"observedAt"));}
    private static boolean bool(Map<String,Object> r,String key){if(!(r.get(key) instanceof Boolean value))throw new IllegalArgumentException("Harvest boolean");return value;}
}
