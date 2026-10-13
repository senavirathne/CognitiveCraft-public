package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Mandates.*;
import static dev.aivillages.core.kernel.Outcomes.*;

/** Strict mandate schema 1; original request, context, artifact and budget encodings are reused. */
final class MandateCodec {
    private MandateCodec() { }
    static String grant(Grant g) {
        var row = new LinkedHashMap<String,Object>();
        row.put("kind", "grant"); row.put("id", g.id().toString()); row.put("revision", g.revision());
        row.put("issuer", context(g.issuer())); row.put("grantee", context(g.grantee()));
        if (g.parent() != null) row.put("parent", g.parent().toString());
        if (g.organization() != null) row.put("organization", g.organization().toString());
        row.put("rights", Map.of("workers", g.rights().workers().stream().sorted(Comparator.comparing(a -> a.citizenId().toString())).map(JobCodec::actor).toList(),
                "actions", g.rights().actions().stream().map(Enum::name).sorted().toList(),
                "resources", g.rights().resources().stream().sorted().toList(), "area", JobCodec.area(g.rights().area()),
                "containers", g.rights().containers().stream().sorted(Comparator.comparing(Object::toString)).map(JobCodec::container).toList()));
        row.put("issuedAt", g.issuedAt()); row.put("expiresAt", g.expiresAt()); row.put("budget", JobCodec.limits(g.budget()));
        row.put("reserved", JobCodec.usageMap(g.reserved())); row.put("revoked", g.revoked());
        row.put("events", g.events().stream().map(e -> Map.of("revision", e.revision(), "kind", e.kind().name(),
                "subject", e.subject().toString(), "actor", context(e.actor()))).toList());
        return StrictJson.canonical(row);
    }
    static Grant grant(Map<String,Object> row) throws StrictJson.Invalid {
        fields(row, Set.of("kind", "id", "revision", "issuer", "grantee", "rights", "issuedAt", "expiresAt", "budget", "reserved", "revoked", "events"), Set.of("parent", "organization"));
        if (!string(row,"kind","$").equals("grant")) throw new StrictJson.Invalid("$", "MANDATE_KIND");
        var rights = object(row,"rights","$"); exact(rights,"workers","actions","resources","area","containers");
        var workers = new HashSet<ActorRef>(); for (Object value : list(rights,"workers",8))
            unique(workers.add(JobCodec.actor(map(value))));
        var actions = EnumSet.noneOf(Effect.class); for (Object value : list(rights,"actions",7)) unique(actions.add(Effect.valueOf((String)value)));
        var resources = new HashSet<String>(); for (Object value : list(rights,"resources",4)) unique(resources.add((String)value));
        var containers = new HashSet<ContainerRef>(); for (Object value : list(rights,"containers",4)) unique(containers.add(JobCodec.container(map(value))));
        var events = new ArrayList<Event>(); for (Object value : list(row,"events",8)) {
            var e = map(value); exact(e,"revision","kind","subject","actor");
            events.add(new Event(number(e,"revision","$"), EventKind.valueOf(string(e,"kind","$")), id(e,"subject"), context(object(e,"actor","$"))));
        }
        return new Grant(id(row,"id"), number(row,"revision","$"), context(object(row,"issuer","$")), context(object(row,"grantee","$")),
                row.containsKey("parent") ? id(row,"parent") : null, row.containsKey("organization") ? id(row,"organization") : null,
                new Rights(workers, actions, resources, JobCodec.area(object(rights,"area","$")), containers),
                number(row,"issuedAt","$"), number(row,"expiresAt","$"), JobCodec.limits(object(row,"budget","$")),
                JobCodec.usage(object(row,"reserved","$")), bool(row,"revoked","$"), events);
    }
    static String binding(Binding b) {
        return StrictJson.canonical(Map.of("kind", "binding", "run", b.run().toString(), "job", b.job().toString(),
                "generation", b.generation(), "mandate", b.mandate().toString(), "artifact", JobCodec.ref(b.artifact()),
                "request", JobCodec.bound(b.request()), "allowance", JobCodec.limits(b.allowance())));
    }
    static Binding binding(Map<String,Object> row) throws StrictJson.Invalid {
        exact(row,"kind","run","job","generation","mandate","artifact","request","allowance");
        if (!string(row,"kind","$").equals("binding")) throw new StrictJson.Invalid("$", "MANDATE_KIND");
        return new Binding(id(row,"run"), id(row,"job"), number(row,"generation","$"), id(row,"mandate"),
                JobCodec.ref(object(row,"artifact","$")), JobCodec.bound(object(row,"request","$")), JobCodec.limits(object(row,"allowance","$")));
    }
    private static Map<String,Object> context(TrustedContext c) { return Map.of("principal", c.principal().id().toString(),
            "world", c.scope().worldId().toString(), "domain", c.scope().domainId().toString()); }
    private static TrustedContext context(Map<String,Object> row) throws StrictJson.Invalid {
        exact(row,"principal","world","domain"); return new TrustedContext(new PrincipalRef(id(row,"principal")), new ScopeRef(id(row,"world"),id(row,"domain")));
    }
    private static UUID id(Map<String,Object> row,String key) throws StrictJson.Invalid {
        String value = string(row,key,"$"); UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new StrictJson.Invalid("$", "UUID"); return id;
    }
    private static void unique(boolean added) throws StrictJson.Invalid { if (!added) throw new StrictJson.Invalid("$", "DUPLICATE_RIGHT"); }
    private static List<?> list(Map<String,Object> row,String key,int maximum) throws StrictJson.Invalid {
        if (!(row.get(key) instanceof List<?> values) || values.size() > maximum) throw new StrictJson.Invalid("$", "MANDATE_LIST_LIMIT"); return values;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) throws StrictJson.Invalid {
        if (!(value instanceof Map<?,?> row)) throw new StrictJson.Invalid("$", "OBJECT"); return (Map<String,Object>)row;
    }
    private static void fields(Map<String,Object> row,Set<String> required,Set<String> optional) throws StrictJson.Invalid {
        var allowed = new HashSet<>(required); allowed.addAll(optional);
        if (!row.keySet().containsAll(required) || !allowed.containsAll(row.keySet())) throw new StrictJson.Invalid("$", "UNKNOWN_OR_MISSING_FIELD");
    }
}
