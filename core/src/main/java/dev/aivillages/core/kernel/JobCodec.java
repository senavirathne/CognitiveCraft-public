package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Jobs.*;
import static dev.aivillages.core.kernel.Outcomes.*;

/** Strict schema-1 job records; existing request/outcome codecs retain their meanings. */
final class JobCodec {
    private JobCodec() { }
    static String job(Job job) {
        var row = new LinkedHashMap<String,Object>();
        row.put("kind", "job"); row.put("schema", (long)SCHEMA);
        row.put("id", job.id().toString()); row.put("submission", job.submissionId().toString());
        row.put("revision", job.revision()); row.put("request", bound(job.request()));
        if (job.parent() != null) row.put("parent", job.parent().toString());
        row.put("dependencies", ids(job.dependencies())); row.put("children", ids(job.children()));
        row.put("state", job.state().name());
        if (job.reason() != null) row.put("reason", job.reason().name());
        if (job.cancellationOutcome() != null) row.put("cancellationOutcome", job.cancellationOutcome().name());
        row.put("allowance", limits(job.allowance())); row.put("usage", usageMap(job.usage()));
        row.put("generation", job.generation());
        row.put("attempts", job.attempts().stream().map(JobCodec::attempt).toList());
        row.put("events", job.events().stream().map(e -> {
            var event = new LinkedHashMap<String,Object>();
            event.put("revision", e.revision()); event.put("kind", e.kind().name());
            event.put("generation", e.generation());
            if (e.attempt() != null) event.put("attempt", e.attempt().toString());
            return event;
        }).toList());
        return StrictJson.canonical(row);
    }
    static String allocation(Allocation allocation) {
        return StrictJson.canonical(Map.of("kind", "allocation", "schema", (long)SCHEMA,
                "id", allocation.id().toString(), "producer", allocation.producer().toString(),
                "attempt", allocation.attempt().toString(), "receipt", allocation.receipt().toString(),
                "demand", allocation.demand().toString(), "quantity", allocation.quantity()));
    }
    static Job job(Map<String,Object> row) throws StrictJson.Invalid {
        fields(row, Set.of("kind","schema","id","submission","revision","request","dependencies","children",
                "state","allowance","usage","generation","attempts","events"), Set.of("parent","reason","cancellationOutcome"));
        if (!"job".equals(string(row,"kind","$")) || number(row,"schema","$") != SCHEMA)
            throw new StrictJson.Invalid("$", "JOB_SCHEMA");
        var attempts = new ArrayList<Attempt>();
        for (Object value : list(row,"attempts",8)) attempts.add(attempt(map(value)));
        var events = new ArrayList<Event>();
        for (Object value : list(row,"events",16)) {
            var event = map(value);
            fields(event,Set.of("revision","kind","generation"),Set.of("attempt"));
            events.add(new Event(number(event,"revision","$"), EventKind.valueOf(string(event,"kind","$")),
                    optionalId(event,"attempt"), number(event,"generation","$")));
        }
        return new Job(id(row,"id"),id(row,"submission"),number(row,"revision","$"),
                bound(object(row,"request","$")),optionalId(row,"parent"),uuidList(row,"dependencies",8),
                uuidList(row,"children",8),State.valueOf(string(row,"state","$")),
                row.containsKey("reason") ? Reason.valueOf(string(row,"reason","$")) : null,
                row.containsKey("cancellationOutcome") ? State.valueOf(string(row,"cancellationOutcome","$")) : null,
                limits(object(row,"allowance","$")),usage(object(row,"usage","$")),
                number(row,"generation","$"),attempts,events);
    }
    static Allocation allocation(Map<String,Object> row) throws StrictJson.Invalid {
        exact(row,"kind","schema","id","producer","attempt","receipt","demand","quantity");
        if (!"allocation".equals(string(row,"kind","$")) || number(row,"schema","$") != SCHEMA)
            throw new StrictJson.Invalid("$","ALLOCATION_SCHEMA");
        return new Allocation(id(row,"id"),id(row,"producer"),id(row,"attempt"),id(row,"receipt"),
                id(row,"demand"),number(row,"quantity","$"));
    }
    private static Map<String,Object> attempt(Attempt attempt) {
        var row = new LinkedHashMap<String,Object>();
        row.put("id",attempt.id().toString()); row.put("generation",attempt.generation());
        row.put("worker",actor(attempt.worker())); row.put("request",bound(attempt.bound()));
        row.put("allowance",limits(attempt.allowance())); row.put("usage",usageMap(attempt.usage()));
        row.put("effects",attempt.effects()); row.put("credited",attempt.credited());
        row.put("uncertain",attempt.uncertain()); row.put("cancellationDeliveries",(long)attempt.cancellationDeliveries());
        row.put("executions",attempt.executions().stream().map(r -> Map.of("run",r.runId().toString(),
                "artifact",ref(r.artifact()),"pinned",r.pinned().stream().map(JobCodec::ref).toList())).toList());
        row.put("receipts",attempt.receipts().stream().map(JobCodec::receipt).toList());
        if (attempt.terminal() != null) row.put("terminal",strict(Outcomes.encode(attempt.terminal())));
        return row;
    }
    private static Attempt attempt(Map<String,Object> row) throws StrictJson.Invalid {
        fields(row,Set.of("id","generation","worker","request","allowance","usage","effects","credited",
                "uncertain","cancellationDeliveries","executions","receipts"),Set.of("terminal"));
        var references = new ArrayList<ExecutionReference>();
        for (Object value : list(row,"executions",8)) {
            var ref = map(value); exact(ref,"run","artifact","pinned");
            var pinned = new ArrayList<ArtifactRef>();
            for (Object item : list(ref,"pinned",16)) pinned.add(ref(map(item)));
            references.add(new ExecutionReference(id(ref,"run"),ref(object(ref,"artifact","$")),pinned));
        }
        var receipts = new ArrayList<CropDelivery.CropReceipt>();
        for (Object value : list(row,"receipts",64)) receipts.add(receipt(map(value)));
        return new Attempt(id(row,"id"),number(row,"generation","$"),actor(object(row,"worker","$")),
                bound(object(row,"request","$")),limits(object(row,"allowance","$")),references,
                usage(object(row,"usage","$")),receipts,number(row,"effects","$"),number(row,"credited","$"),
                row.containsKey("terminal") ? (Execution)Outcomes.decode(StrictJson.canonical(row.get("terminal"))) : null,
                bool(row,"uncertain","$"),Math.toIntExact(number(row,"cancellationDeliveries","$")));
    }
    private static Map<String,Object> bound(ValidatedRequest bound) {
        var o = bound.observation(); var c = bound.context();
        return Map.of("request",strict(RequestCodec.encode(bound.request())),
                "principal",c.principal().id().toString(),"world",c.scope().worldId().toString(),
                "domain",c.scope().domainId().toString(),
                "observation",Map.of("id",o.snapshotId().toString(),"revision",o.revision(),"dimension",o.dimension()));
    }
    private static ValidatedRequest bound(Map<String,Object> row) throws StrictJson.Invalid {
        exact(row,"request","principal","world","domain","observation");
        var decoded = RequestCodec.decode(StrictJson.canonical(row.get("request")));
        if (!(decoded instanceof RequestCodec.Valid valid)) throw new StrictJson.Invalid("$","BOUND_REQUEST");
        var observation = object(row,"observation","$"); exact(observation,"id","revision","dimension");
        return new ValidatedRequest(valid.request(),
                new TrustedContext(new PrincipalRef(id(row,"principal")),new ScopeRef(id(row,"world"),id(row,"domain"))),
                new ObservationRef(id(observation,"id"),number(observation,"revision","$"),string(observation,"dimension","$")));
    }
    private static Map<String,Object> limits(Budgets.Limits limits) {
        return Map.of("maxima",usageMap(limits.maxima()),"deadline",limits.deadlineEpochMillis());
    }
    private static Budgets.Limits limits(Map<String,Object> row) throws StrictJson.Invalid {
        exact(row,"maxima","deadline");
        return new Budgets.Limits(usage(object(row,"maxima","$")),number(row,"deadline","$"));
    }
    private static Map<String,Object> usageMap(Map<Budgets.Kind,Long> values) {
        var result = new TreeMap<String,Object>(); values.forEach((k,v) -> result.put(k.name(),v)); return result;
    }
    private static Map<Budgets.Kind,Long> usage(Map<String,Object> row) throws StrictJson.Invalid {
        if (row.size() > Budgets.Kind.values().length) throw new StrictJson.Invalid("$","USAGE_LIMIT");
        var result = new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);
        for (var entry : row.entrySet()) result.put(Budgets.Kind.valueOf(entry.getKey()),number(row,entry.getKey(),"$"));
        return result;
    }
    private static Map<String,Object> ref(ArtifactRef ref) {
        return Map.of("capability",ref.capability().name(),"version",(long)ref.capability().version(),"sha256",ref.sha256());
    }
    private static ArtifactRef ref(Map<String,Object> row) throws StrictJson.Invalid {
        exact(row,"capability","version","sha256");
        return new ArtifactRef(new CapabilityId(string(row,"capability","$"),Math.toIntExact(number(row,"version","$"))),
                string(row,"sha256","$"));
    }
    private static Map<String,Object> actor(ActorRef actor) {
        return Map.of("citizen",actor.citizenId().toString(),"entity",actor.entityId().toString(),"dimension",actor.dimension());
    }
    private static ActorRef actor(Map<String,Object> row) throws StrictJson.Invalid {
        exact(row,"citizen","entity","dimension");
        return new ActorRef(id(row,"citizen"),id(row,"entity"),string(row,"dimension","$"));
    }
    private static Map<String,Object> area(Cuboid a) {
        return Map.of("dimension",a.dimension(),"minX",(long)a.minX(),"minY",(long)a.minY(),"minZ",(long)a.minZ(),
                "maxX",(long)a.maxX(),"maxY",(long)a.maxY(),"maxZ",(long)a.maxZ());
    }
    private static Cuboid area(Map<String,Object> row) throws StrictJson.Invalid {
        exact(row,"dimension","minX","minY","minZ","maxX","maxY","maxZ");
        return new Cuboid(string(row,"dimension","$"),integer(row,"minX"),integer(row,"minY"),integer(row,"minZ"),
                integer(row,"maxX"),integer(row,"maxY"),integer(row,"maxZ"));
    }
    private static Map<String,Object> container(ContainerRef c) {
        return Map.of("dimension",c.dimension(),"x",(long)c.x(),"y",(long)c.y(),"z",(long)c.z());
    }
    private static ContainerRef container(Map<String,Object> row) throws StrictJson.Invalid {
        exact(row,"dimension","x","y","z");
        return new ContainerRef(string(row,"dimension","$"),integer(row,"x"),integer(row,"y"),integer(row,"z"));
    }
    private static Map<String,Object> receipt(CropDelivery.CropReceipt receipt) {
        return Map.of("id",receipt.receiptId().toString(),"run",receipt.runId().toString(),"batch",receipt.batchId().toString(),
                "actor",actor(receipt.actor()),"source",area(receipt.source()),"destination",container(receipt.destination()),
                "stage",receipt.stage().name(),"wheat",receipt.wheat());
    }
    private static CropDelivery.CropReceipt receipt(Map<String,Object> row) throws StrictJson.Invalid {
        exact(row,"id","run","batch","actor","source","destination","stage","wheat");
        return new CropDelivery.CropReceipt(id(row,"id"),id(row,"run"),id(row,"batch"),actor(object(row,"actor","$")),
                area(object(row,"source","$")),container(object(row,"destination","$")),
                CropDelivery.Stage.valueOf(string(row,"stage","$")),number(row,"wheat","$"));
    }
    private static List<String> ids(List<UUID> ids) { return ids.stream().map(UUID::toString).toList(); }
    private static List<UUID> uuidList(Map<String,Object> row,String key,int maximum) throws StrictJson.Invalid {
        var result = new ArrayList<UUID>();
        for (Object value : list(row,key,maximum)) result.add(UUID.fromString((String)value));
        return result;
    }
    private static List<?> list(Map<String,Object> row,String key,int maximum) throws StrictJson.Invalid {
        if (!(row.get(key) instanceof List<?> result) || result.size() > maximum)
            throw new StrictJson.Invalid("$."+key,"LIST_LIMIT");
        return result;
    }
    private static UUID id(Map<String,Object> row,String key) throws StrictJson.Invalid {
        String value = string(row,key,"$");
        if (!value.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            throw new StrictJson.Invalid("$."+key,"UUID");
        return UUID.fromString(value);
    }
    private static UUID optionalId(Map<String,Object> row,String key) throws StrictJson.Invalid {
        return row.containsKey(key) ? id(row,key) : null;
    }
    private static int integer(Map<String,Object> row,String key) throws StrictJson.Invalid {
        return Math.toIntExact(number(row,key,"$"));
    }
    private static void fields(Map<String,Object> row,Set<String> required,Set<String> optional) throws StrictJson.Invalid {
        var allowed = new HashSet<>(required); allowed.addAll(optional);
        if (!row.keySet().containsAll(required) || !allowed.containsAll(row.keySet()))
            throw new StrictJson.Invalid("$","UNKNOWN_OR_MISSING_FIELD");
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Object value) throws StrictJson.Invalid {
        if (!(value instanceof Map<?,?> row)) throw new StrictJson.Invalid("$","OBJECT");
        return (Map<String,Object>)row;
    }
    private static Map<String,Object> strict(String json) {
        try { return StrictJson.object(json); }
        catch (StrictJson.Invalid invalid) { throw new IllegalArgumentException("Bounded record representation",invalid); }
    }
}
