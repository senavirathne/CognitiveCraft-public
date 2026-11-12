package dev.aivillages.core.kernel.runtime.activity;

import dev.aivillages.core.kernel.*;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static dev.aivillages.core.kernel.runtime.activity.ActivityPayload.*;

/** Strict bounded optional Job-schema-4 payload. Archive rows use the identical representation. */
public final class ActivityCodec {
    private ActivityCodec(){}
    public static Map<String,Object> encode(ActivityPayload p) {
        var row=new LinkedHashMap<String,Object>();row.put("schema",1L);row.put("mode",p.mode().name());row.put("policy",p.policy().name());
        row.put("plannedSteps",(long)p.plannedSteps());if(p.currentStep()!=null)row.put("currentStep",p.currentStep().toString());
        row.put("blocker",p.blocker().name());row.put("steps",p.steps().stream().map(s->{
            var v=new LinkedHashMap<String,Object>();v.put("id",s.id().toString());v.put("proposal",s.proposal().toString());v.put("token",s.token());
            v.put("bodyDigest",s.bodyDigest());v.put("signature",s.signature());v.put("creditBefore",s.creditBefore());v.put("quantity",s.quantity());
            v.put("artifact",Map.of("name",s.artifact().capability().name(),"version",(long)s.artifact().capability().version(),"sha256",s.artifact().sha256()));
            if(s.choice()!=null)v.put("choice",Map.of("base64",s.choice().base64(),"sha256",s.choice().sha256()));
            var q=s.quantities();v.put("quantities",Map.of("demand",q.demand(),"yield",q.yield(),"batches",q.batches(),"produced",q.produced(),"allocation",q.allocation(),"surplus",q.surplus(),"remainders",q.remainders()));
            var maxima=new TreeMap<String,Long>();s.allowance().maxima().forEach((k,n)->maxima.put(k.name(),n));v.put("allowance",Map.of("maxima",maxima,"deadline",s.allowance().deadlineEpochMillis()));
            v.put("preparedRevision",s.preparedRevision());if(s.attempt()!=null)v.put("attempt",s.attempt().toString());return v;
        }).toList());
        row.put("checkpoints",p.checkpoints().stream().map(c->Map.of("step",c.step().toString(),"attempt",c.attempt().toString(),"generation",c.generation(),"quantity",c.quantity(),"receipts",c.receipts().stream().map(UUID::toString).toList(),"evidence",c.evidence().name())).toList());
        return row;
    }
    public static ActivityPayload decode(Map<String,Object> row)throws StrictJson.Invalid {
        fields(row,Set.of("schema","mode","policy","plannedSteps","blocker","steps","checkpoints"),Set.of("currentStep"));
        if(number(row,"schema","$")!=1)throw new IllegalArgumentException("Activity schema");
        var steps=new ArrayList<Step>();for(Object raw:list(row,"steps",8)) {
            var s=object(raw);fields(s,Set.of("id","proposal","token","bodyDigest","signature","creditBefore","quantity","artifact","quantities","allowance","preparedRevision"),Set.of("choice","attempt"));
            var a=object(s.get("artifact"));exact(a,"name","version","sha256");var q=object(s.get("quantities"));exact(q,"demand","yield","batches","produced","allocation","surplus","remainders");
            Choice choice=null;if(s.containsKey("choice")){var c=object(s.get("choice"));exact(c,"base64","sha256");choice=new Choice(string(c,"base64","$"),string(c,"sha256","$"));}
            var limits=object(s.get("allowance"));exact(limits,"maxima","deadline");var maxima=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);
            for(var e:object(limits.get("maxima")).entrySet())maxima.put(Budgets.Kind.valueOf(e.getKey()),(Long)e.getValue());
            steps.add(new Step(id(s,"id"),id(s,"proposal"),string(s,"token","$"),string(s,"bodyDigest","$"),string(s,"signature","$"),number(s,"creditBefore","$"),number(s,"quantity","$"),
                    new ArtifactRef(new CapabilityId(string(a,"name","$"),Math.toIntExact(number(a,"version","$"))),string(a,"sha256","$")),choice,
                    new Quantities(number(q,"demand","$"),number(q,"yield","$"),number(q,"batches","$"),number(q,"produced","$"),number(q,"allocation","$"),number(q,"surplus","$"),number(q,"remainders","$")),
                    new Budgets.Limits(maxima,number(limits,"deadline","$")),number(s,"preparedRevision","$"),s.containsKey("attempt")?id(s,"attempt"):null));
        }
        var checkpoints=new ArrayList<Checkpoint>();for(Object raw:list(row,"checkpoints",8)) {
            var c=object(raw);exact(c,"step","attempt","generation","quantity","receipts","evidence");var refs=new ArrayList<UUID>();for(Object id:list(c,"receipts",64))refs.add(UUID.fromString((String)id));
            checkpoints.add(new Checkpoint(id(c,"step"),id(c,"attempt"),number(c,"generation","$"),number(c,"quantity","$"),refs,Evidence.valueOf(string(c,"evidence","$"))));
        }
        return new ActivityPayload(GoalMode.valueOf(string(row,"mode","$")),Policy.valueOf(string(row,"policy","$")),Math.toIntExact(number(row,"plannedSteps","$")),row.containsKey("currentStep")?id(row,"currentStep"):null,steps,checkpoints,Blocker.valueOf(string(row,"blocker","$")));
    }
    private static long number(Map<String,Object> row,String key,String path){if(!(row.get(key) instanceof Long n))throw new IllegalArgumentException("Activity integer");return n;}
    private static String string(Map<String,Object> row,String key,String path){if(!(row.get(key) instanceof String s))throw new IllegalArgumentException("Activity string");return s;}
    private static void exact(Map<String,Object> row,String... names){if(!row.keySet().equals(Set.of(names)))throw new IllegalArgumentException("Activity fields");}
    private static UUID id(Map<String,Object> row,String k)throws StrictJson.Invalid{return UUID.fromString(string(row,k,"$"));}
    private static List<?> list(Map<String,Object> row,String k,int max){if(!(row.get(k) instanceof List<?> v)||v.size()>max)throw new IllegalArgumentException("Activity list bound");return v;}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object raw){if(!(raw instanceof Map<?,?> m))throw new IllegalArgumentException("Activity object");return (Map<String,Object>)m;}
    private static void fields(Map<String,Object> row,Set<String> required,Set<String> optional){var allowed=new HashSet<>(required);allowed.addAll(optional);if(!row.keySet().containsAll(required)||!allowed.containsAll(row.keySet()))throw new IllegalArgumentException("Activity fields");}
}
