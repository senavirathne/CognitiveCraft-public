package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;

/** First-pickup data and classification only; neither data nor a classifier grants effect/Jobs authority. */
public final class NativePickup {
    private NativePickup() { }
    public static final int MAX_RECORD_BYTES=32_768,MAX_TRAIL_CHUNKS=8;
    public enum Live { NO_EFFECT,COMPLETE,PARTIAL,UNRECEIPTED_EFFECT }
    public enum Reconciliation { WAIT_WORLD,UNPROVEN,TORN_SAVED_COHORT,SAVED_COMPLETE,SAVED_NO_EFFECT,PARTIAL_UNRESOLVED }
    public enum Role { ACTOR,DROP }
    public record Chunk(String dimension,int x,int z) implements Comparable<Chunk> {
        public Chunk {new dev.aivillages.core.kernel.Contracts.ContainerRef(dimension,0,0,0);}
        @Override public int compareTo(Chunk b){int c=dimension.compareTo(b.dimension);if(c==0)c=Integer.compare(x,b.x);return c==0?Integer.compare(z,b.z):c;}
    }
    /** Complete capture from the tracked entity's original H lifetime; data alone cannot attest completeness. */
    public record Trail(UUID entity,UUID incarnation,List<Chunk> chunks,Chunk current) {
        public Trail {
            Objects.requireNonNull(entity);Objects.requireNonNull(incarnation);Objects.requireNonNull(current);chunks=List.copyOf(chunks);
            if(chunks.isEmpty() || chunks.size()>MAX_TRAIL_CHUNKS || !chunks.contains(current)
                    || chunks.stream().anyMatch(c->!c.dimension().equals(current.dimension()))
                    || !chunks.equals(chunks.stream().distinct().sorted().toList()))throw new IllegalArgumentException("Canonical complete pickup trail");
        }
    }
    public record Intent(Key key,NativeHarvest.Entry harvest,UUID operation,int requested,Conditions conditions,Trail actorTrail,Trail dropTrail) {
        public Intent {
            Objects.requireNonNull(key);Objects.requireNonNull(harvest);Objects.requireNonNull(operation);Objects.requireNonNull(conditions);
            Objects.requireNonNull(actorTrail);Objects.requireNonNull(dropTrail);
            var h=harvest.intent();var r=harvest.result();var old=h.key();
            if(r==null || r.live()!=NativeHarvest.Live.COMPLETE || r.receipt()==null || harvest.verification()!=null
                    || old.ordinal()!=1 || !old.root().equals(old.step()) || key.ordinal()!=2
                    || !key.world().equals(old.world()) || !key.root().equals(old.root()) || !key.step().equals(old.step())
                    || !key.attempt().equals(old.attempt()) || key.generation()!=old.generation()
                    || operation.equals(h.operation()) || requested<1 || requested>64
                    || r.drops().size()!=1 || r.receipt().quantity()>requested || !r.actor().equals(h.actorBefore())
                    || h.actorBefore().slots().stream().anyMatch(s->s.count()!=0)
                    || h.conditions().usage().getOrDefault(Budgets.Kind.COMMITTED_EFFECTS,0L)!=0
                    || h.conditions().usage().getOrDefault(Budgets.Kind.ATTEMPTED_EFFECTS,0L)!=0
                    || r.usage().getOrDefault(Budgets.Kind.COMMITTED_EFFECTS,0L)!=1
                    || r.usage().getOrDefault(Budgets.Kind.ATTEMPTED_EFFECTS,0L)!=1)
                throw new IllegalArgumentException("Original first H/P binding");
            var drop=r.drops().getFirst();
            if(!actorTrail.entity().toString().equals(r.actor().address()) || !actorTrail.incarnation().equals(r.actor().incarnation())
                    || !dropTrail.entity().equals(drop.entity()) || !dropTrail.incarnation().equals(drop.incarnation())
                    || actorTrail.entity().equals(dropTrail.entity()) || !actorTrail.current().dimension().equals(h.actor().dimension())
                    || !dropTrail.current().dimension().equals(h.actor().dimension())
                    || !dropTrail.chunks().contains(new Chunk(h.source().dimension(),h.source().x()>>4,h.source().z()>>4))
                    || !conditions.citizen().equals(h.actor().citizenId()) || conditions.jobRevision()<h.conditions().jobRevision()
                    || conditions.deadlineEpochMillis()!=h.conditions().deadlineEpochMillis() || conditions.observedTick()<h.conditions().observedTick()
                    || conditions.effectsRemaining()>h.conditions().effectsRemaining()-1
                    || conditions.usage().getOrDefault(Budgets.Kind.COMMITTED_EFFECTS,0L)!=1
                    || conditions.usage().getOrDefault(Budgets.Kind.ATTEMPTED_EFFECTS,0L)!=1)
                throw new IllegalArgumentException("Original pickup trail or cumulative allowance");
            for(var before:r.usage().entrySet())if(conditions.usage().getOrDefault(before.getKey(),0L)<before.getValue())throw new IllegalArgumentException("Pickup usage decreased");
            Map<UUID,Claim> claims=new HashMap<>();h.conditions().claims().forEach(c->claims.put(c.id(),c));
            if(claims.size()!=conditions.claims().size())throw new IllegalArgumentException("Changed original pickup claims");
            for(Claim c:conditions.claims()) {
                Claim prior=claims.get(c.id());
                if(prior==null || !prior.epoch().equals(c.epoch()) || !prior.group().equals(c.group()) || c.generation()<prior.generation())
                    throw new IllegalArgumentException("Changed original pickup claims");
            }
        }
        public String digest(){return sha(StrictJson.canonical(intent(this)));}
    }
    public record Result(String intent,Live live,NativeHarvest.Source source,Participant actor,NativeHarvest.Drop remaining,
                         ResourceDelivery.Receipt receipt,Map<Budgets.Kind,Long> usage,String failure) {
        public Result {
            hash(intent);Objects.requireNonNull(live);Objects.requireNonNull(source);Objects.requireNonNull(actor);usage=Map.copyOf(usage);
            if(failure==null || failure.length()>256 || usage.values().stream().anyMatch(n->n<0)
                    || live==Live.COMPLETE && receipt==null || (live==Live.NO_EFFECT || live==Live.UNRECEIPTED_EFFECT) && receipt!=null
                    || receipt!=null && (receipt.stage()!=ResourceDelivery.Stage.PICKUP || receipt.transformation()!=null || receipt.placement()!=null))
                throw new IllegalArgumentException("Actual native pickup result");
        }
        public String digest(){return sha(StrictJson.canonical(result(this)));}
    }
    public record TrailWitness(Trail trail,Marker marker,boolean restoredFromNativeSave){public TrailWitness{Objects.requireNonNull(trail);}}
    public record Member(Role role,UUID entity,Chunk chunk) {public Member{Objects.requireNonNull(role);Objects.requireNonNull(entity);Objects.requireNonNull(chunk);}}
    /** Exact native-frame membership and current membership; absence still requires its bound native marker. */
    public record MembershipWitness(Member member,UUID incarnation,long revision,Marker marker,boolean restoredFromNativeSave,
                                    boolean savedPresent,boolean currentPresent,String frameDigest) {
        public MembershipWitness {Objects.requireNonNull(member);Objects.requireNonNull(incarnation);hash(frameDigest);if(revision<0)throw new IllegalArgumentException("Native membership revision");}
    }
    public record Observation(Witness actor,NativeHarvest.SourceWitness source,NativeHarvest.DropWitness drop,
                              TrailWitness actorTrail,TrailWitness dropTrail,List<MembershipWitness> memberships) {
        public Observation {memberships=List.copyOf(memberships);if(memberships.size()>MAX_TRAIL_CHUNKS*2)throw new IllegalArgumentException("Pickup membership observation quota");}
    }
    public record Verification(Reconciliation outcome,Observation observation,UUID process,long observedAt) {
        public Verification {
            Objects.requireNonNull(outcome);Objects.requireNonNull(observation);Objects.requireNonNull(process);
            if(observedAt<0 || outcome!=Reconciliation.SAVED_COMPLETE && outcome!=Reconciliation.SAVED_NO_EFFECT)throw new IllegalArgumentException("Positive saved pickup verification required");
        }
    }
    public record Entry(Intent intent,Result result,Verification verification) {
        public Entry(Intent intent,Result result){this(intent,result,null);}
        public Entry {
            Objects.requireNonNull(intent);
            if(result!=null) {
                Live delta=delta(intent,result.source(),result.actor(),result.remaining());
                if(!result.intent().equals(intent.digest()) || !(result.live()==delta || result.live()==Live.UNRECEIPTED_EFFECT && delta==Live.COMPLETE && result.receipt()==null))
                    throw new IllegalArgumentException("Changed original pickup delta");
                var before=intent.conditions().usage();
                for(var v:before.entrySet())if(result.usage().getOrDefault(v.getKey(),0L)<v.getValue())throw new IllegalArgumentException("Pickup usage decreased");
                long committed=result.usage().getOrDefault(Budgets.Kind.COMMITTED_EFFECTS,0L)-before.getOrDefault(Budgets.Kind.COMMITTED_EFFECTS,0L);
                long attempted=result.usage().getOrDefault(Budgets.Kind.ATTEMPTED_EFFECTS,0L)-before.getOrDefault(Budgets.Kind.ATTEMPTED_EFFECTS,0L);
                if(committed<0 || committed>1 || attempted<0 || attempted>1 || result.live()==Live.COMPLETE && (committed!=1 || attempted!=1)
                        || result.live()==Live.NO_EFFECT && committed!=0)throw new IllegalArgumentException("Pickup cumulative effect debit");
                if(result.receipt()!=null) {
                    var actual=result.receipt();var h=intent.harvest().result().receipt();
                    long moved=intent.harvest().result().drops().getFirst().quantity()-(result.remaining()==null?0:result.remaining().quantity());
                    if(!actual.origin().equals(h.origin()) || !actual.runId().equals(h.runId()) || !actual.operationId().equals(intent.operation())
                            || actual.receiptId().equals(h.receiptId()) || !actual.batchId().equals(h.batchId()) || !actual.actor().equals(h.actor())
                            || !actual.source().equals(h.source()) || !actual.destination().equals(h.destination()) || !actual.item().equals(h.item())
                            || !actual.sourceBlock().equals(h.sourceBlock()) || !actual.dropIds().equals(h.dropIds()) || actual.quantity()!=moved)
                        throw new IllegalArgumentException("Actual original pickup receipt binding");
                }
            }
            if(verification!=null && classify(new Entry(intent,result),verification.observation())!=verification.outcome())
                throw new IllegalArgumentException("Saved pickup verification does not prove its outcome");
        }
        public String token(){return intent.key().token();}
    }
    private static Live delta(Intent intent,NativeHarvest.Source source,Participant actor,NativeHarvest.Drop remaining) {
        var before=intent.harvest().result();var drop=before.drops().getFirst();
        if(!before.actor().sameIdentity(actor) || !before.source().sameIdentity(source))throw new IllegalArgumentException("Pickup participant replaced");
        if(remaining!=null && (!remaining.entity().equals(drop.entity()) || !remaining.incarnation().equals(drop.incarnation())
                || !remaining.dimension().equals(drop.dimension()) || !remaining.item().equals(drop.item()) || remaining.revision()<drop.revision()))
            throw new IllegalArgumentException("Pickup drop replaced");
        if(before.source().equals(source) && before.actor().equals(actor) && drop.equals(remaining))return Live.NO_EFFECT;
        long moved=drop.quantity()-(remaining==null?0:remaining.quantity());
        long count=actor.slots().stream().mapToLong(Slot::count).sum();
        return before.source().equals(source) && actor.revision()>before.actor().revision() && actor.slots().size()==before.actor().slots().size()
                && moved>0 && count==moved && actor.slots().stream().allMatch(s->s.count()==0 || s.item().equals(drop.item()))
                && (remaining==null || remaining.revision()>drop.revision())?Live.COMPLETE:Live.PARTIAL;
    }
    public static Result observed(Intent intent,NativeHarvest.Source source,Participant actor,NativeHarvest.Drop remaining,
                                  ResourceDelivery.Receipt receipt,Map<Budgets.Kind,Long> usage,String failure) {
        Live live=delta(intent,source,actor,remaining);if(live==Live.COMPLETE && receipt==null)live=Live.UNRECEIPTED_EFFECT;
        return new Result(intent.digest(),live,source,actor,remaining,receipt,usage,failure);
    }
    public static List<Member> members(Intent intent) {
        var result=new ArrayList<Member>();
        intent.actorTrail().chunks().forEach(c->result.add(new Member(Role.ACTOR,intent.actorTrail().entity(),c)));
        intent.dropTrail().chunks().forEach(c->result.add(new Member(Role.DROP,intent.dropTrail().entity(),c)));
        return List.copyOf(result);
    }
    public static Reconciliation classify(Entry entry,Observation observation) {
        if(observation==null || observation.actor()==null || observation.source()==null)return Reconciliation.WAIT_WORLD;
        var r=entry.result();var actor=observation.actor();var source=observation.source();
        if(!actor.restoredFromNativeSave() || !source.restoredFromNativeSave() || actor.marker()==null || source.marker()==null)return Reconciliation.UNPROVEN;
        if(r==null)return Reconciliation.PARTIAL_UNRESOLVED;
        if(r.live()!=Live.COMPLETE && r.live()!=Live.NO_EFFECT)return Reconciliation.PARTIAL_UNRESOLVED;
        var expected=new Marker(entry.token(),entry.intent().digest(),MarkerPhase.RESULT,r.digest());
        if(!actor.participant().equals(r.actor()) || !source.source().equals(r.source()) || !expected.equals(actor.marker()) || !expected.equals(source.marker()))
            return Reconciliation.TORN_SAVED_COHORT;
        var trail=observation.actorTrail();
        if(trail==null || !trail.restoredFromNativeSave() || !expected.equals(trail.marker()) || !trail.trail().equals(entry.intent().actorTrail()))
            return Reconciliation.UNPROVEN;
        if(r.remaining()!=null) {
            var drop=observation.drop();var dt=observation.dropTrail();
            if(drop==null)return Reconciliation.WAIT_WORLD;
            if(!drop.restoredFromNativeSave() || !drop.drop().equals(r.remaining()) || !expected.equals(drop.marker())
                    || dt==null || !dt.restoredFromNativeSave() || !dt.trail().equals(entry.intent().dropTrail()) || !expected.equals(dt.marker()))return Reconciliation.UNPROVEN;
        }else if(observation.drop()!=null)return Reconciliation.TORN_SAVED_COHORT;
        List<Member> references=members(entry.intent());var witnesses=observation.memberships();
        if(witnesses.size()<references.size())return Reconciliation.WAIT_WORLD;
        if(witnesses.size()!=references.size() || witnesses.stream().map(MembershipWitness::member).distinct().count()!=witnesses.size())return Reconciliation.UNPROVEN;
        var indexed=new HashMap<Member,MembershipWitness>();for(var w:witnesses)indexed.put(w.member(),w);
        var frames=new HashMap<Chunk,String>();
        for(Member ref:references) {
            var w=indexed.get(ref);if(w==null)return Reconciliation.UNPROVEN;
            String frame=frames.putIfAbsent(ref.chunk(),w.frameDigest());
            if(frame!=null && !frame.equals(w.frameDigest()))return Reconciliation.TORN_SAVED_COHORT;
            boolean present=ref.role()==Role.ACTOR?ref.chunk().equals(entry.intent().actorTrail().current()):
                    r.remaining()!=null && ref.chunk().equals(entry.intent().dropTrail().current());
            UUID incarnation=ref.role()==Role.ACTOR?r.actor().incarnation():entry.intent().harvest().result().drops().getFirst().incarnation();
            long revision=ref.role()==Role.ACTOR?r.actor().revision():r.remaining()==null?
                    entry.intent().harvest().result().drops().getFirst().revision():r.remaining().revision();
            if(!w.restoredFromNativeSave() || w.marker()==null || !w.incarnation().equals(incarnation) || w.revision()!=revision)return Reconciliation.UNPROVEN;
            if(!w.marker().equals(expected) || w.savedPresent()!=present || w.currentPresent()!=present)return Reconciliation.TORN_SAVED_COHORT;
        }
        return r.live()==Live.COMPLETE?Reconciliation.SAVED_COMPLETE:Reconciliation.SAVED_NO_EFFECT;
    }
    public static byte[] encode(Entry entry) {
        var row=new LinkedHashMap<String,Object>();row.put("schema",1L);row.put("intent",intent(entry.intent()));
        if(entry.result()!=null)row.put("result",result(entry.result()));if(entry.verification()!=null)row.put("verification",verification(entry.verification()));
        byte[] raw=StrictJson.canonical(row).getBytes(StandardCharsets.UTF_8);
        if(raw.length>MAX_RECORD_BYTES)throw new IllegalArgumentException("Pickup record quota");return raw;
    }
    public static Entry decode(byte[] raw) {
        if(raw.length>MAX_RECORD_BYTES)throw new IllegalArgumentException("Pickup record quota");
        try {
            var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            var row=StrictJson.object(decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString());var fields=new HashSet<>(Set.of("schema","intent"));
            if(row.containsKey("result"))fields.add("result");if(row.containsKey("verification"))fields.add("verification");keys(row,fields);
            if(number(row,"schema")!=1)throw new IllegalArgumentException("Unknown pickup schema");
            var entry=new Entry(intent(object(row,"intent")),row.containsKey("result")?result(object(row,"result")):null,row.containsKey("verification")?verification(object(row,"verification")):null);
            if(!Arrays.equals(raw,encode(entry)))throw new IllegalArgumentException("Noncanonical pickup record");return entry;
        }catch(StrictJson.Invalid|java.nio.charset.CharacterCodingException invalid){throw new IllegalArgumentException("Invalid pickup record",invalid);}
    }
    private static Map<String,Object> chunk(Chunk c){return Map.of("dimension",c.dimension(),"x",c.x(),"z",c.z());}
    private static Chunk chunk(Map<String,Object> m){keys(m,Set.of("dimension","x","z"));return new Chunk(string(m,"dimension"),integer(m,"x"),integer(m,"z"));}
    private static Map<String,Object> trail(Trail t){return Map.of("entity",t.entity().toString(),"incarnation",t.incarnation().toString(),"chunks",t.chunks().stream().map(NativePickup::chunk).toList(),"current",chunk(t.current()));}
    private static Trail trail(Map<String,Object> m){keys(m,Set.of("entity","incarnation","chunks","current"));return new Trail(id(m,"entity"),id(m,"incarnation"),objects(m,"chunks",MAX_TRAIL_CHUNKS).stream().map(NativePickup::chunk).toList(),chunk(object(m,"current")));}
    private static Map<String,Object> source(NativeHarvest.Source s){return Map.of("dimension",s.dimension(),"x",s.x(),"y",s.y(),"z",s.z(),"incarnation",s.incarnation().toString(),"revision",s.revision(),"state",s.state());}
    private static NativeHarvest.Source source(Map<String,Object> m){keys(m,Set.of("dimension","x","y","z","incarnation","revision","state"));return new NativeHarvest.Source(string(m,"dimension"),integer(m,"x"),integer(m,"y"),integer(m,"z"),id(m,"incarnation"),number(m,"revision"),string(m,"state"));}
    private static Map<String,Object> drop(NativeHarvest.Drop d){return Map.of("dimension",d.dimension(),"entity",d.entity().toString(),"incarnation",d.incarnation().toString(),"revision",d.revision(),"item",d.item(),"quantity",d.quantity());}
    private static NativeHarvest.Drop drop(Map<String,Object> m){keys(m,Set.of("dimension","entity","incarnation","revision","item","quantity"));return new NativeHarvest.Drop(string(m,"dimension"),id(m,"entity"),id(m,"incarnation"),number(m,"revision"),string(m,"item"),integer(m,"quantity"));}
    private static Map<String,Object> intent(Intent i) {
        Map<String,Object> ancestor;
        try{ancestor=StrictJson.object(new String(NativeHarvest.encode(i.harvest()),StandardCharsets.UTF_8));}catch(StrictJson.Invalid impossible){throw new IllegalStateException(impossible);}
        return Map.of("key",key(i.key()),"harvest",ancestor,"operation",i.operation().toString(),"requested",i.requested(),"conditions",conditions(i.conditions()),"actorTrail",trail(i.actorTrail()),"dropTrail",trail(i.dropTrail()));
    }
    private static Intent intent(Map<String,Object> m) {
        keys(m,Set.of("key","harvest","operation","requested","conditions","actorTrail","dropTrail"));
        var ancestor=NativeHarvest.decode(StrictJson.canonical(object(m,"harvest")).getBytes(StandardCharsets.UTF_8));
        return new Intent(key(object(m,"key")),ancestor,id(m,"operation"),integer(m,"requested"),conditions(object(m,"conditions")),trail(object(m,"actorTrail")),trail(object(m,"dropTrail")));
    }
    private static Map<String,Object> result(Result r) {
        var m=new LinkedHashMap<String,Object>();m.put("intent",r.intent());m.put("live",r.live().name());m.put("source",source(r.source()));m.put("actor",participant(r.actor()));
        if(r.remaining()!=null)m.put("remaining",drop(r.remaining()));if(r.receipt()!=null)m.put("receipt",ResourceReceiptPrefixCodec.encode(r.receipt()));
        m.put("usage",r.usage().entrySet().stream().collect(java.util.stream.Collectors.toMap(e->e.getKey().name(),Map.Entry::getValue)));m.put("failure",r.failure());return m;
    }
    private static Result result(Map<String,Object> m) {
        var fields=new HashSet<>(Set.of("intent","live","source","actor","usage","failure"));if(m.containsKey("remaining"))fields.add("remaining");if(m.containsKey("receipt"))fields.add("receipt");keys(m,fields);
        var usage=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);var u=object(m,"usage");for(String k:u.keySet())usage.put(Budgets.Kind.valueOf(k),number(u,k));
        return new Result(string(m,"intent"),Live.valueOf(string(m,"live")),source(object(m,"source")),participant(object(m,"actor")),m.containsKey("remaining")?drop(object(m,"remaining")):null,m.containsKey("receipt")?ResourceReceiptPrefixCodec.decode(object(m,"receipt")):null,usage,string(m,"failure"));
    }
    private static Map<String,Object> trailWitness(TrailWitness w){return Map.of("trail",trail(w.trail()),"marker",markerJson(w.marker()),"restored",w.restoredFromNativeSave());}
    private static TrailWitness trailWitness(Map<String,Object> m){keys(m,Set.of("trail","marker","restored"));return new TrailWitness(trail(object(m,"trail")),markerJson(string(m,"marker")),bool(m,"restored"));}
    private static Map<String,Object> observation(Observation o) {
        var m=new LinkedHashMap<String,Object>();
        m.put("actor",Map.of("participant",participant(o.actor().participant()),"marker",markerJson(o.actor().marker()),"restored",o.actor().restoredFromNativeSave()));
        m.put("source",Map.of("source",source(o.source().source()),"marker",markerJson(o.source().marker()),"restored",o.source().restoredFromNativeSave()));
        m.put("actorTrail",trailWitness(o.actorTrail()));
        if(o.drop()!=null)m.put("drop",Map.of("drop",drop(o.drop().drop()),"marker",markerJson(o.drop().marker()),"restored",o.drop().restoredFromNativeSave()));
        if(o.dropTrail()!=null)m.put("dropTrail",trailWitness(o.dropTrail()));
        m.put("memberships",o.memberships().stream().map(w->Map.of("role",w.member().role().name(),"entity",w.member().entity().toString(),"chunk",chunk(w.member().chunk()),
                "incarnation",w.incarnation().toString(),"revision",w.revision(),"marker",markerJson(w.marker()),"restored",w.restoredFromNativeSave(),
                "savedPresent",w.savedPresent(),"currentPresent",w.currentPresent(),"frame",w.frameDigest())).toList());return m;
    }
    private static Observation observation(Map<String,Object> m) {
        var fields=new HashSet<>(Set.of("actor","source","actorTrail","memberships"));if(m.containsKey("drop"))fields.add("drop");if(m.containsKey("dropTrail"))fields.add("dropTrail");keys(m,fields);
        var a=object(m,"actor");keys(a,Set.of("participant","marker","restored"));var s=object(m,"source");keys(s,Set.of("source","marker","restored"));
        NativeHarvest.DropWitness d=null;if(m.containsKey("drop")){var v=object(m,"drop");keys(v,Set.of("drop","marker","restored"));d=new NativeHarvest.DropWitness(drop(object(v,"drop")),markerJson(string(v,"marker")),bool(v,"restored"));}
        var memberships=new ArrayList<MembershipWitness>();
        for(var w:objects(m,"memberships",MAX_TRAIL_CHUNKS*2)) {
            keys(w,Set.of("role","entity","chunk","incarnation","revision","marker","restored","savedPresent","currentPresent","frame"));
            memberships.add(new MembershipWitness(new Member(Role.valueOf(string(w,"role")),id(w,"entity"),chunk(object(w,"chunk"))),id(w,"incarnation"),number(w,"revision"),markerJson(string(w,"marker")),bool(w,"restored"),bool(w,"savedPresent"),bool(w,"currentPresent"),string(w,"frame")));
        }
        return new Observation(new Witness(participant(object(a,"participant")),markerJson(string(a,"marker")),bool(a,"restored")),
                new NativeHarvest.SourceWitness(source(object(s,"source")),markerJson(string(s,"marker")),bool(s,"restored")),d,trailWitness(object(m,"actorTrail")),m.containsKey("dropTrail")?trailWitness(object(m,"dropTrail")):null,memberships);
    }
    private static Map<String,Object> verification(Verification v){return Map.of("outcome",v.outcome().name(),"observation",observation(v.observation()),"process",v.process().toString(),"observedAt",v.observedAt());}
    private static Verification verification(Map<String,Object> m){keys(m,Set.of("outcome","observation","process","observedAt"));return new Verification(Reconciliation.valueOf(string(m,"outcome")),observation(object(m,"observation")),id(m,"process"),number(m,"observedAt"));}
    private static List<Map<String,Object>> objects(Map<String,Object> m,String name,int maximum) {
        if(!(m.get(name) instanceof List<?> list) || list.size()>maximum)throw new IllegalArgumentException("Pickup list quota");
        return list.stream().map(NativeDeposit::object).toList();
    }
    private static boolean bool(Map<String,Object> m,String name){if(!(m.get(name) instanceof Boolean b))throw new IllegalArgumentException("Pickup boolean");return b;}
}
