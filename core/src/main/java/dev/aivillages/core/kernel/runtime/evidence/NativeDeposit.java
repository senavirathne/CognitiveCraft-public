package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.Budgets;
import dev.aivillages.core.kernel.ResourceDelivery;
import dev.aivillages.core.kernel.StrictJson;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Bounded deposit data and pure classification. Data constructors grant no native authority. */
public final class NativeDeposit {
    public static final int SCHEMA=1, MAX_ENTRIES=64, MAX_RECORD_BYTES=32_768;
    private NativeDeposit() { }
    public enum Kind { ACTOR, CHEST }
    public enum MarkerPhase { PREPARED, MUTATING, RESULT }
    public enum Live { NO_EFFECT, COMPLETE, PARTIAL }
    public enum Reconciliation { WAIT_WORLD, UNPROVEN, PREPARED_NO_EFFECT, SAVED_COMPLETE,
        SAVED_NO_EFFECT, TORN_PARTICIPANTS, JOURNAL_AHEAD_OF_PARTICIPANTS, PARTIAL_UNRESOLVED }

    public record Key(UUID world,UUID root,UUID step,UUID attempt,long generation,int ordinal) {
        public Key {
            Objects.requireNonNull(world);Objects.requireNonNull(root);Objects.requireNonNull(step);Objects.requireNonNull(attempt);
            if(generation<1 || ordinal<1 || ordinal>64)throw new IllegalArgumentException("Deposit effect identity");
        }
        public String token(){return sha(StrictJson.canonical(key(this)));}
    }
    /** Only unchanged default-component stacks are in this first native envelope. */
    public record Slot(String item,int count) {
        public Slot {
            if(item==null || item.length()>128 || !item.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+") || count<0 || count>64
                    || (count==0)!=item.equals("minecraft:air"))throw new IllegalArgumentException("Deposit slot/components envelope");
        }
    }
    public record Participant(Kind kind,String dimension,String address,UUID incarnation,long revision,List<Slot> slots) {
        public Participant {
            Objects.requireNonNull(kind);Objects.requireNonNull(incarnation);slots=List.copyOf(slots);
            if(dimension==null || dimension.length()>128 || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")
                    || address==null || address.length()>96 || revision<0 || slots.isEmpty() || slots.size()>(kind==Kind.ACTOR?8:27))
                throw new IllegalArgumentException("Deposit participant envelope");
            if(kind==Kind.ACTOR)uuid(address);
            else {
                String[] cell=address.split(",",-1);if(cell.length!=3)throw new IllegalArgumentException("Single chest address");
                int x=Integer.parseInt(cell[0]),y=Integer.parseInt(cell[1]),z=Integer.parseInt(cell[2]);
                if(!address.equals(x+","+y+","+z))throw new IllegalArgumentException("Canonical chest address");
                new ContainerRef(dimension,x,y,z);
            }
        }
        public boolean sameIdentity(Participant other) {
            return other!=null && kind==other.kind && dimension.equals(other.dimension) && address.equals(other.address)
                    && incarnation.equals(other.incarnation);
        }
    }
    public record Claim(UUID id,UUID epoch,UUID group,long generation) {
        public Claim {Objects.requireNonNull(id);Objects.requireNonNull(epoch);Objects.requireNonNull(group);if(generation<1)throw new IllegalArgumentException("Deposit claim generation");}
    }
    public record Conditions(UUID citizen,long jobRevision,long deadlineEpochMillis,long actionDeadlineEpochMillis,long effectsRemaining,long observedTick,
                             int maximumReachMilli,int observedReachMilli,List<Claim> claims,Map<Budgets.Kind,Long> usage) {
        public Conditions {
            Objects.requireNonNull(citizen);claims=List.copyOf(claims);usage=Map.copyOf(usage);
            if(jobRevision<0 || deadlineEpochMillis<0 || actionDeadlineEpochMillis<0 || actionDeadlineEpochMillis>deadlineEpochMillis || effectsRemaining<1 || effectsRemaining>4096 || observedTick<0
                    || maximumReachMilli!=2500 || observedReachMilli<0 || observedReachMilli>maximumReachMilli
                    || claims.isEmpty() || claims.size()>4 || claims.stream().map(Claim::id).distinct().count()!=claims.size()
                    || claims.stream().map(Claim::group).distinct().count()!=1 || usage.values().stream().anyMatch(n->n<0)
                    || usage.getOrDefault(Budgets.Kind.COMMITTED_EFFECTS,0L)>Long.MAX_VALUE-effectsRemaining)throw new IllegalArgumentException("Deposit authority/reach/claim envelope");
        }
    }
    public record Intent(Key key,TrustedContext origin,UUID run,UUID operation,Participant actor,Participant chest,
                         String item,int actorSlot,int chestSlot,int capacity,Conditions conditions) {
        public Intent {
            Objects.requireNonNull(key);Objects.requireNonNull(origin);Objects.requireNonNull(run);Objects.requireNonNull(operation);
            Objects.requireNonNull(actor);Objects.requireNonNull(chest);Objects.requireNonNull(conditions);
            if(!key.world.equals(origin.scope().worldId()) || actor.kind!=Kind.ACTOR || chest.kind!=Kind.CHEST
                    || !actor.dimension.equals(chest.dimension) || !Set.of("minecraft:oak_log","minecraft:birch_log").contains(item)
                    || actorSlot<0 || actorSlot>=actor.slots.size() || chestSlot<0 || chestSlot>=chest.slots.size()
                    || capacity<1 || capacity>64)throw new IllegalArgumentException("Deposit intent binding");
            Slot from=actor.slots.get(actorSlot),to=chest.slots.get(chestSlot);
            if(!from.item.equals(item) || from.count<1 || to.count>=capacity || to.count!=0 && !to.item.equals(item))
                throw new IllegalArgumentException("Deposit precondition");
        }
        public String digest(){return sha(StrictJson.canonical(intent(this)));}
    }
    public record Result(String intent,Live live,Participant actor,Participant chest,String failure,ResourceDelivery.Receipt receipt,Map<Budgets.Kind,Long> usage) {
        public Result(String intent,Live live,Participant actor,Participant chest,String failure){this(intent,live,actor,chest,failure,null,Map.of());}
        public Result {
            hash(intent);Objects.requireNonNull(live);Objects.requireNonNull(actor);Objects.requireNonNull(chest);usage=Map.copyOf(usage);
            if(actor.kind!=Kind.ACTOR || chest.kind!=Kind.CHEST || failure==null || failure.length()>256 || usage.values().stream().anyMatch(n->n<0)
                    || live==Live.COMPLETE && receipt==null || live==Live.NO_EFFECT && receipt!=null)
                throw new IllegalArgumentException("Deposit observed result");
            if(receipt!=null)DepositReceiptCodec.encode(receipt);
        }
        public String digest(){return sha(StrictJson.canonical(result(this)));}
    }
    public record Verification(Reconciliation outcome,Participant actor,Participant chest,Marker actorMarker,Marker chestMarker,UUID process,long observedAt) {
        public Verification {
            Objects.requireNonNull(outcome);Objects.requireNonNull(actor);Objects.requireNonNull(chest);Objects.requireNonNull(actorMarker);Objects.requireNonNull(chestMarker);Objects.requireNonNull(process);
            if(observedAt<0 || !Set.of(Reconciliation.PREPARED_NO_EFFECT,Reconciliation.SAVED_COMPLETE,Reconciliation.SAVED_NO_EFFECT).contains(outcome))
                throw new IllegalArgumentException("Only complete saved evidence can close a deposit exclusion");
        }
    }
    public record Entry(Intent intent,Result result,Verification verification) {
        public Entry(Intent intent,Result result){this(intent,result,null);}
        public Entry {
            Objects.requireNonNull(intent);
            if(result!=null && (!result.intent.equals(intent.digest()) || !intent.actor.sameIdentity(result.actor)
                    || !intent.chest.sameIdentity(result.chest) || result.actor.revision<intent.actor.revision
                    || result.chest.revision<intent.chest.revision || result.live!=delta(intent,result.actor,result.chest)))
                throw new IllegalArgumentException("Deposit result identity/delta");
            if(result!=null) {
                for(var before:intent.conditions.usage.entrySet())if(result.usage.getOrDefault(before.getKey(),0L)<before.getValue())throw new IllegalArgumentException("Deposit usage decreased");
                long committed=result.usage.getOrDefault(Budgets.Kind.COMMITTED_EFFECTS,0L)-intent.conditions.usage.getOrDefault(Budgets.Kind.COMMITTED_EFFECTS,0L);
                long attempted=result.usage.getOrDefault(Budgets.Kind.ATTEMPTED_EFFECTS,0L)-intent.conditions.usage.getOrDefault(Budgets.Kind.ATTEMPTED_EFFECTS,0L);
                if(committed<0 || committed>1 || result.live==Live.COMPLETE && committed!=1 || result.live==Live.NO_EFFECT && committed!=0)
                    throw new IllegalArgumentException("Deposit committed allowance");
                if(attempted<0 || attempted>1 || result.live==Live.COMPLETE && attempted!=1)throw new IllegalArgumentException("Deposit attempted allowance");
                if(result.receipt!=null) {
                    var r=result.receipt;
                    if(!r.origin().equals(intent.origin) || !r.runId().equals(intent.run) || !r.operationId().equals(intent.operation)
                            || !r.actor().citizenId().equals(intent.conditions.citizen) || !r.actor().entityId().toString().equals(intent.actor.address)
                            || !r.actor().dimension().equals(intent.actor.dimension) || !r.destination().dimension().equals(intent.chest.dimension)
                            || !(r.destination().x()+","+r.destination().y()+","+r.destination().z()).equals(intent.chest.address) || !r.item().id().equals(intent.item))
                        throw new IllegalArgumentException("Actual gateway receipt changed its original intent binding");
                }
            }
            if(verification!=null && classify(new Entry(intent,result,null),new Witness(verification.actor,verification.actorMarker,true),
                    new Witness(verification.chest,verification.chestMarker,true))!=verification.outcome)throw new IllegalArgumentException("Deposit verification does not prove its own outcome");
        }
        public String token(){return intent.key.token();}
    }
    /** Written with the participant's own native serialization; not a save acknowledgement. */
    public record Marker(String effect,String intent,MarkerPhase phase,String result) {
        public Marker {
            hash(effect);hash(intent);Objects.requireNonNull(phase);Objects.requireNonNull(result);
            if(phase==MarkerPhase.RESULT)hash(result);else if(!result.isEmpty())throw new IllegalArgumentException("Premature deposit result marker");
        }
    }
    public record Witness(Participant participant,Marker marker,boolean restoredFromNativeSave) {
        public Witness {Objects.requireNonNull(participant);}
    }
    public static Result observed(Intent intent,Participant actor,Participant chest,String failure,ResourceDelivery.Receipt receipt,Map<Budgets.Kind,Long> usage) {
        return new Result(intent.digest(),delta(intent,actor,chest),actor,chest,failure,receipt,usage);
    }
    private static Live delta(Intent intent,Participant actor,Participant chest) {
        if(!intent.actor.sameIdentity(actor) || !intent.chest.sameIdentity(chest))throw new IllegalArgumentException("Participant replaced during deposit");
        if(intent.actor.equals(actor) && intent.chest.equals(chest))return Live.NO_EFFECT;
        var from=new ArrayList<>(intent.actor.slots);var to=new ArrayList<>(intent.chest.slots);
        int source=from.get(intent.actorSlot).count-1,target=to.get(intent.chestSlot).count+1;
        from.set(intent.actorSlot,new Slot(source==0?"minecraft:air":intent.item,source));to.set(intent.chestSlot,new Slot(intent.item,target));
        return actor.revision>intent.actor.revision && chest.revision>intent.chest.revision && actor.slots.equals(from) && chest.slots.equals(to)
                ?Live.COMPLETE:Live.PARTIAL;
    }
    public static Reconciliation classify(Entry entry,Witness actor,Witness chest) {
        if(actor==null || chest==null)return Reconciliation.WAIT_WORLD;
        if(!actor.restoredFromNativeSave || !chest.restoredFromNativeSave || actor.marker==null || chest.marker==null
                || !entry.intent.actor.sameIdentity(actor.participant) || !entry.intent.chest.sameIdentity(chest.participant))return Reconciliation.UNPROVEN;
        for(Marker marker:List.of(actor.marker,chest.marker))
            if(!marker.effect.equals(entry.token()) || !marker.intent.equals(entry.intent.digest()))return Reconciliation.UNPROVEN;
        boolean before=actor.marker.phase==MarkerPhase.PREPARED && chest.marker.phase==MarkerPhase.PREPARED
                && actor.participant.equals(entry.intent.actor) && chest.participant.equals(entry.intent.chest);
        if(before)return entry.result==null?Reconciliation.PREPARED_NO_EFFECT:Reconciliation.JOURNAL_AHEAD_OF_PARTICIPANTS;
        if(actor.marker.phase!=chest.marker.phase || actor.marker.phase==MarkerPhase.MUTATING)return Reconciliation.TORN_PARTICIPANTS;
        if(entry.result==null || actor.marker.phase!=MarkerPhase.RESULT
                || !actor.marker.result.equals(entry.result.digest()) || !chest.marker.result.equals(entry.result.digest())
                || !actor.participant.equals(entry.result.actor) || !chest.participant.equals(entry.result.chest))return Reconciliation.UNPROVEN;
        return switch(entry.result.live) {case COMPLETE->Reconciliation.SAVED_COMPLETE;case NO_EFFECT->Reconciliation.SAVED_NO_EFFECT;case PARTIAL->Reconciliation.PARTIAL_UNRESOLVED;};
    }
    public static byte[] encode(Entry entry) {
        var value=new LinkedHashMap<String,Object>();value.put("schema",1L);value.put("intent",intent(entry.intent));
        if(entry.result!=null)value.put("result",result(entry.result));
        if(entry.verification!=null)value.put("verification",verification(entry.verification));
        byte[] raw=StrictJson.canonical(value).getBytes(StandardCharsets.UTF_8);
        if(raw.length>MAX_RECORD_BYTES)throw new IllegalArgumentException("Deposit record quota");return raw;
    }
    public static Entry decode(byte[] bytes) {
        if(bytes.length>MAX_RECORD_BYTES)throw new IllegalArgumentException("Deposit record quota");
        try {
            var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            Map<String,Object> row=StrictJson.object(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString());
            var fields=new HashSet<>(Set.of("schema","intent"));if(row.containsKey("result"))fields.add("result");if(row.containsKey("verification"))fields.add("verification");keys(row,fields);
            if(number(row,"schema")!=1)throw new IllegalArgumentException("Unknown deposit record schema");
            Entry entry=new Entry(intent(object(row,"intent")),row.containsKey("result")?result(object(row,"result")):null,
                    row.containsKey("verification")?verification(object(row,"verification")):null);
            if(!Arrays.equals(encode(entry),bytes))throw new IllegalArgumentException("Noncanonical deposit record");return entry;
        }catch(StrictJson.Invalid|java.nio.charset.CharacterCodingException invalid){throw new IllegalArgumentException("Invalid deposit record",invalid);}
    }
    public static String markerJson(Marker marker) {
        return StrictJson.canonical(Map.of("schema",1L,"effect",marker.effect,"intent",marker.intent,"phase",marker.phase.name(),"result",marker.result));
    }
    public static Marker markerJson(String json) {
        try {
            var row=StrictJson.object(json);keys(row,Set.of("schema","effect","intent","phase","result"));
            if(number(row,"schema")!=1)throw new IllegalArgumentException("Unknown native marker schema");
            Marker marker=new Marker(string(row,"effect"),string(row,"intent"),MarkerPhase.valueOf(string(row,"phase")),string(row,"result"));
            if(!markerJson(marker).equals(json))throw new IllegalArgumentException("Noncanonical native marker");return marker;
        }catch(StrictJson.Invalid invalid){throw new IllegalArgumentException("Invalid native marker",invalid);}
    }
    private static Map<String,Object> key(Key key) {return Map.of("world",key.world.toString(),"root",key.root.toString(),"step",key.step.toString(),"attempt",key.attempt.toString(),"generation",key.generation,"ordinal",key.ordinal);}
    private static Key key(Map<String,Object> row) {keys(row,Set.of("world","root","step","attempt","generation","ordinal"));return new Key(id(row,"world"),id(row,"root"),id(row,"step"),id(row,"attempt"),number(row,"generation"),integer(row,"ordinal"));}
    private static Map<String,Object> participant(Participant p) {return Map.of("kind",p.kind.name(),"dimension",p.dimension,"address",p.address,"incarnation",p.incarnation.toString(),"revision",p.revision,"slots",p.slots.stream().map(s->Map.of("item",s.item,"count",s.count)).toList());}
    private static Participant participant(Map<String,Object> row) {
        keys(row,Set.of("kind","dimension","address","incarnation","revision","slots"));Object raw=row.get("slots");
        if(!(raw instanceof List<?> values) || values.size()>27)throw new IllegalArgumentException("Deposit slots");
        var slots=new ArrayList<Slot>();for(Object value:values) {var slot=object(value);keys(slot,Set.of("item","count"));slots.add(new Slot(string(slot,"item"),integer(slot,"count")));}
        return new Participant(Kind.valueOf(string(row,"kind")),string(row,"dimension"),string(row,"address"),id(row,"incarnation"),number(row,"revision"),slots);
    }
    private static Map<String,Object> intent(Intent i) {
        var row=new LinkedHashMap<String,Object>();row.put("key",key(i.key));row.put("principal",i.origin.principal().id().toString());row.put("domain",i.origin.scope().domainId().toString());
        row.put("run",i.run.toString());row.put("operation",i.operation.toString());row.put("actor",participant(i.actor));row.put("chest",participant(i.chest));
        row.put("item",i.item);row.put("actorSlot",i.actorSlot);row.put("chestSlot",i.chestSlot);row.put("capacity",i.capacity);row.put("conditions",conditions(i.conditions));return row;
    }
    private static Intent intent(Map<String,Object> row) {
        keys(row,Set.of("key","principal","domain","run","operation","actor","chest","item","actorSlot","chestSlot","capacity","conditions"));Key key=key(object(row,"key"));
        return new Intent(key,new TrustedContext(new PrincipalRef(id(row,"principal")),new ScopeRef(key.world,id(row,"domain"))),id(row,"run"),id(row,"operation"),
                participant(object(row,"actor")),participant(object(row,"chest")),string(row,"item"),integer(row,"actorSlot"),integer(row,"chestSlot"),integer(row,"capacity"),conditions(object(row,"conditions")));
    }
    private static Map<String,Object> conditions(Conditions c) {return Map.of("citizen",c.citizen.toString(),"jobRevision",c.jobRevision,"deadline",c.deadlineEpochMillis,"actionDeadline",c.actionDeadlineEpochMillis,"effectsRemaining",c.effectsRemaining,"tick",c.observedTick,
            "maximumReachMilli",c.maximumReachMilli,"observedReachMilli",c.observedReachMilli,"claims",c.claims.stream().map(r->Map.of("id",r.id.toString(),"epoch",r.epoch.toString(),"group",r.group.toString(),"generation",r.generation)).toList(),
            "usage",c.usage.entrySet().stream().collect(java.util.stream.Collectors.toMap(e->e.getKey().name(),Map.Entry::getValue)));}
    private static Conditions conditions(Map<String,Object> row) {
        keys(row,Set.of("citizen","jobRevision","deadline","actionDeadline","effectsRemaining","tick","maximumReachMilli","observedReachMilli","claims","usage"));
        if(!(row.get("claims") instanceof List<?> values) || values.size()>4)throw new IllegalArgumentException("Deposit claim bound");
        var claims=new ArrayList<Claim>();for(Object value:values){var claim=object(value);keys(claim,Set.of("id","epoch","group","generation"));claims.add(new Claim(id(claim,"id"),id(claim,"epoch"),id(claim,"group"),number(claim,"generation")));}
        var usage=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);var original=object(row,"usage");for(String kind:original.keySet())usage.put(Budgets.Kind.valueOf(kind),number(original,kind));
        return new Conditions(id(row,"citizen"),number(row,"jobRevision"),number(row,"deadline"),number(row,"actionDeadline"),number(row,"effectsRemaining"),number(row,"tick"),integer(row,"maximumReachMilli"),integer(row,"observedReachMilli"),claims,usage);
    }
    private static Map<String,Object> verification(Verification v) {return Map.of("outcome",v.outcome.name(),"actor",participant(v.actor),"chest",participant(v.chest),"actorMarker",markerJson(v.actorMarker),"chestMarker",markerJson(v.chestMarker),"process",v.process.toString(),"observedAt",v.observedAt);}
    private static Verification verification(Map<String,Object> row) {keys(row,Set.of("outcome","actor","chest","actorMarker","chestMarker","process","observedAt"));return new Verification(Reconciliation.valueOf(string(row,"outcome")),participant(object(row,"actor")),participant(object(row,"chest")),markerJson(string(row,"actorMarker")),markerJson(string(row,"chestMarker")),id(row,"process"),number(row,"observedAt"));}
    private static Map<String,Object> result(Result r) {
        var row=new LinkedHashMap<String,Object>();row.put("intent",r.intent);row.put("live",r.live.name());row.put("actor",participant(r.actor));row.put("chest",participant(r.chest));row.put("failure",r.failure);
        row.put("usage",r.usage.entrySet().stream().collect(java.util.stream.Collectors.toMap(e->e.getKey().name(),Map.Entry::getValue)));if(r.receipt!=null)row.put("receipt",DepositReceiptCodec.encode(r.receipt));return row;
    }
    private static Result result(Map<String,Object> row) {
        var fields=new HashSet<>(Set.of("intent","live","actor","chest","failure","usage"));if(row.containsKey("receipt"))fields.add("receipt");keys(row,fields);
        var usage=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);var values=object(row,"usage");for(String kind:values.keySet())usage.put(Budgets.Kind.valueOf(kind),number(values,kind));
        return new Result(string(row,"intent"),Live.valueOf(string(row,"live")),participant(object(row,"actor")),participant(object(row,"chest")),string(row,"failure"),row.containsKey("receipt")?DepositReceiptCodec.decode(object(row,"receipt")):null,usage);
    }
    static Map<String,Object> object(Object value) {
        if(!(value instanceof Map<?,?> row) || !row.keySet().stream().allMatch(k->k instanceof String))throw new IllegalArgumentException("Deposit object");
        @SuppressWarnings("unchecked")var result=(Map<String,Object>)row;return result;
    }
    static Map<String,Object> object(Map<String,Object> row,String key){return object(row.get(key));}
    static void keys(Map<String,Object> row,Set<String> expected){if(!row.keySet().equals(expected))throw new IllegalArgumentException("Deposit fields");}
    static long number(Map<String,Object> row,String key){if(!(row.get(key) instanceof Long value))throw new IllegalArgumentException("Deposit integer");return value;}
    static int integer(Map<String,Object> row,String key){return Math.toIntExact(number(row,key));}
    static String string(Map<String,Object> row,String key){if(!(row.get(key) instanceof String value))throw new IllegalArgumentException("Deposit string");return value;}
    static UUID id(Map<String,Object> row,String key){return uuid(string(row,key));}
    private static UUID uuid(String value){UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw new IllegalArgumentException("Canonical deposit UUID");return id;}
    static void hash(String value){if(value==null || !value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Deposit SHA-256");}
    public static String sha(String value){return sha(value.getBytes(StandardCharsets.UTF_8));}
    public static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
