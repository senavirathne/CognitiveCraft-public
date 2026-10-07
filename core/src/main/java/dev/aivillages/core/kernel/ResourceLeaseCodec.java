package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.ResourceLeases.*;
import static dev.aivillages.core.kernel.Outcomes.*;

/** Strict bounded data-only encoding. No executable requests or interpreter state are imported. */
final class ResourceLeaseCodec {
    private ResourceLeaseCodec() { }
    static String encode(Lease l) {
        var a=l.resource().area();var r=l.resource();var c=l.origin();
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("schema",1L);row.put("id",l.id().toString());row.put("group",l.group().toString());
        row.put("epoch",l.epoch().toString());row.put("generation",l.generation());
        row.put("job",l.owner().job().toString());row.put("jobGeneration",l.owner().generation());
        row.put("principal",c.principal().id().toString());row.put("world",c.scope().worldId().toString());
        row.put("domain",c.scope().domainId().toString());
        row.put("resource",Map.of("kind",r.kind().name(),"dimension",a.dimension(),"item",r.item(),
                "slot",(long)r.slot(),"min",List.of((long)a.minX(),(long)a.minY(),(long)a.minZ()),
                "max",List.of((long)a.maxX(),(long)a.maxY(),(long)a.maxZ())));
        row.put("quantity",l.quantity());row.put("remaining",l.remaining());row.put("identity",l.identity());
        row.put("granted",l.granted());row.put("renewed",l.renewed());row.put("expires",l.expires());
        row.put("renewals",(long)l.renewals());row.put("state",l.state().name());
        // StrictJson's data grammar excludes null; the empty string means no failure reason.
        row.put("reason",l.reason()==null?"":l.reason().name());
        var receipts=new TreeMap<String,Long>();l.consumed().forEach((id,qty)->receipts.put(id.toString(),qty));
        row.put("consumed",receipts);return StrictJson.canonical(row);
    }
    static Lease decode(Map<String,Object> row) throws StrictJson.Invalid {
        exact(row,"schema","id","group","epoch","generation","job","jobGeneration","principal","world","domain",
                "resource","quantity","remaining","identity","granted","renewed","expires","renewals","state","reason","consumed");
        if(number(row,"schema","$")!=1)throw new StrictJson.Invalid("$","LEASE_SCHEMA");
        Map<String,Object> r=object(row,"resource","$");exact(r,"kind","dimension","item","slot","min","max");
        int[] min=coordinates(r.get("min")),max=coordinates(r.get("max"));
        UUID world=id(row,"world");
        var context=new TrustedContext(new PrincipalRef(id(row,"principal")),new ScopeRef(world,id(row,"domain")));
        var area=new Cuboid(string(r,"dimension","$"),min[0],min[1],min[2],max[0],max[1],max[2]);
        var resource=new Resource(world,Kind.valueOf(string(r,"kind","$")),area,string(r,"item","$"),
                Math.toIntExact(number(r,"slot","$")));
        var consumed=new LinkedHashMap<UUID,Long>();
        Map<String,Object> values=object(row,"consumed","$");
        if(values.size()>64)throw new StrictJson.Invalid("$","RECEIPT_CAP");
        for(var entry:values.entrySet()) {
            if(!(entry.getValue() instanceof Long qty))throw new StrictJson.Invalid("$","QUANTITY");
            consumed.put(UUID.fromString(entry.getKey()),qty);
        }
        String reason=string(row,"reason","$");
        return new Lease(id(row,"id"),id(row,"group"),id(row,"epoch"),number(row,"generation","$"),
                new Owner(id(row,"job"),number(row,"jobGeneration","$")),context,resource,
                number(row,"quantity","$"),number(row,"remaining","$"),string(row,"identity","$"),
                number(row,"granted","$"),number(row,"renewed","$"),number(row,"expires","$"),
                Math.toIntExact(number(row,"renewals","$")),State.valueOf(string(row,"state","$")),
                reason.isEmpty()?null:Reason.valueOf(reason),consumed);
    }
    private static UUID id(Map<String,Object> row,String key) throws StrictJson.Invalid { return UUID.fromString(string(row,key,"$")); }
    private static int[] coordinates(Object value) throws StrictJson.Invalid {
        if(!(value instanceof List<?> list)||list.size()!=3)throw new StrictJson.Invalid("$","COORDINATES");
        int[] result=new int[3];for(int n=0;n<3;n++) {
            if(!(list.get(n) instanceof Long v))throw new StrictJson.Invalid("$","COORDINATE");
            result[n]=Math.toIntExact(v);
        }return result;
    }
}
