package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;

/** Data-only SUPPLY projection. Jobs and leases, rather than an executable skill, own staging. */
public final class BlueprintMaterials {
    private BlueprintMaterials() { }
    public static final int MAX_AMOUNT=64,MAX_SITE_CELLS=256;
    public static final String PLANKS="minecraft:oak_planks";
    public static final CapabilityId ID=new CapabilityId("cognitivecraft:stage_blueprint_materials",1);
    public static final CapabilitySpec SPEC=new CapabilitySpec(ID,List.of(
            new Parameter("actor",Type.ACTOR,0,0),new Parameter("blueprint",Type.BLUEPRINT,0,0),
            new Parameter("origin",Type.FACILITY,0,0),new Parameter("rotation",Type.INT,0,3),
            new Parameter("site",Type.AREA,0,0),new Parameter("source",Type.CONTAINER,0,0),
            new Parameter("destination",Type.CONTAINER,0,0),new Parameter("acquisition",Type.AREA,0,0),
            new Parameter("amount",Type.INT,0,MAX_AMOUNT)),Type.INT,Set.of(Effect.OBSERVE),Completion.CURRENT_STAGED_MATERIALS);
    public static boolean valid(CapabilityRequest request) {
        try {
            if(!request.capability().equals(ID) || request.arguments().size()!=SPEC.parameters().size())return false;
            var a=request.arguments();for(var p:SPEC.parameters())if(a.get(p.name())==null || a.get(p.name()).type()!=p.type())return false;
            var actor=((ActorValue)a.get("actor")).value();var origin=((FacilityValue)a.get("origin")).value();
            var site=((AreaValue)a.get("site")).value();var source=((ContainerValue)a.get("source")).value();
            var destination=((ContainerValue)a.get("destination")).value();var acquisition=((AreaValue)a.get("acquisition")).value();
            long rotation=((IntValue)a.get("rotation")).value(),amount=amount(request);
            return rotation>=0 && rotation<=3 && amount>=0 && amount<=MAX_AMOUNT && source.equals(destination)
                    && actor.dimension().equals(origin.dimension()) && actor.dimension().equals(site.dimension())
                    && actor.dimension().equals(source.dimension()) && actor.dimension().equals(acquisition.dimension())
                    && ResourceLeases.cells(site)<=MAX_SITE_CELLS && ResourceLeases.cells(acquisition)<=256;
        }catch(RuntimeException invalid){return false;}
    }
    public static Blueprint.Ref ref(CapabilityRequest r){return ((BlueprintValue)r.arguments().get("blueprint")).value();}
    public static long amount(CapabilityRequest r){return ((IntValue)r.arguments().get("amount")).value();}
    public static Blueprint.SiteBinding binding(CapabilityRequest r){var o=((FacilityValue)r.arguments().get("origin")).value();
        return new Blueprint.SiteBinding(ref(r),o.dimension(),o.x(),o.y(),o.z(),Math.toIntExact(((IntValue)r.arguments().get("rotation")).value()));}
    public static CapabilityRequest production(CapabilityRequest r,long shortage) {
        OakPlankProduction.batches(shortage);var a=r.arguments();return new CapabilityRequest(OakPlankProduction.ID,Map.of(
                "actor",a.get("actor"),"amount",new IntValue(shortage),"source",a.get("destination"),"destination",a.get("destination")));
    }
    public static ResourceLeases.Resource resource(Jobs.Job job) {return ResourceLeases.Resource.plankStock(job.origin().scope().worldId(),
            ((ContainerValue)job.request().request().arguments().get("destination")).value());}
    public static long held(Jobs.Job job,ResourceLeaseService leases,boolean validate) {
        long total=0;var resource=resource(job);
        for(var lease:leases.snapshot().leases())if(lease.owner().equals(new ResourceLeases.Owner(job.id(),job.generation()))
                && lease.origin().equals(job.origin()) && lease.resource().equals(resource) && lease.state()==ResourceLeases.State.ACTIVE
                && lease.expires()>leases.currentTick()) {
            if(validate && !leases.validate(lease.ref(),job.origin()).usable())return -1;
            total=Math.addExact(total,lease.remaining());
        }
        return total;
    }
    public record Site(ObservationStatus status,List<Blueprint.State> cells,long tick,int inspected,Reason reason) {
        public Site {cells=List.copyOf(cells);if(tick<0 || inspected<0 || inspected>256 || cells.size()>512)throw new IllegalArgumentException("Bounded site observation");}
    }
    public record Stock(ObservationStatus status,long free,String identity,long tick,int inspected,Reason reason) {
        public Stock {if(free<0 || free>8192 || identity==null || identity.length()>128 || tick<0 || inspected<0 || inspected>256)throw new IllegalArgumentException("Bounded stock observation");}
    }
    public record Report(Blueprint.Ref ref,Map<String,Long> full,Map<String,Long> correct,Map<String,Long> outstanding,
                         Map<String,Long> eligible,Map<String,Long> shortfalls,Set<String> unsupported,Set<String> unknownStock,
                         boolean unknown,int partialStructures,List<String> blockers,Reason reason,long tick,int inspected,String stockIdentity) {
        public Report {full=Map.copyOf(full);correct=Map.copyOf(correct);outstanding=Map.copyOf(outstanding);
            eligible=Map.copyOf(eligible);shortfalls=Map.copyOf(shortfalls);unsupported=Set.copyOf(unsupported);unknownStock=Set.copyOf(unknownStock);blockers=List.copyOf(blockers);}
        public boolean canStage(){return !unknown && blockers.isEmpty();}
        public long required(){return outstanding.getOrDefault(PLANKS,0L);}
        public long available(){return eligible.getOrDefault(PLANKS,0L);}
        public long shortage(){return shortfalls.getOrDefault(PLANKS,0L);}
        public Report blocked(String detail,Reason why){var b=new ArrayList<>(blockers);b.add(detail);return new Report(ref,full,correct,outstanding,eligible,shortfalls,unsupported,unknownStock,unknown,partialStructures,b,why,tick,inspected,stockIdentity);}
    }
    public static Report project(BlueprintStore.Inspection inspection,Blueprint.SiteBinding binding,Site site,Stock stock,
                                 ContainerRef source,ContainerRef destination) {
        var gaps=new TreeSet<String>();var blockers=new ArrayList<String>();Reason reason=null;
        Map<String,Long> correct=Map.of(),outstanding=Map.of(),eligible=Map.of(),shortfalls=Map.of();int partial=0;
        boolean unknown=site.status()==ObservationStatus.UNKNOWN || stock.status()==ObservationStatus.UNKNOWN;
        if(!inspection.compatible() || inspection.body()==null) {
            Reason why=switch(inspection.status()) {
                case DENIED -> Reason.AUTHORITY_DENIED;
                case QUOTA -> Reason.BUDGET_EXHAUSTED;
                case UNAVAILABLE -> Reason.STORAGE_UNAVAILABLE;
                case CANCELLED -> Reason.CANCELLED;
                case INCOMPATIBLE -> Reason.ARTIFACT_INCOMPATIBLE;
                default -> Reason.ARTIFACT_INVALID;
            };
            return new Report(inspection.ref(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Set.of(),Set.of(),true,0,
                    List.of("blueprint:"+inspection.status()),why,Math.min(site.tick(),stock.tick()),site.inspected()+stock.inspected(),"");
        }
        for(var item:inspection.materials().keySet())if(!item.equals(PLANKS)){gaps.add(item);blockers.add("missing-producer-and-staging-support:"+item);reason=Reason.UNSUPPORTED_PRIMITIVE;}
        if(inspection.materials().getOrDefault(PLANKS,0L)>MAX_AMOUNT){blockers.add("bounded-bill:64");reason=Reason.BUDGET_EXHAUSTED;}
        if(site.reason()!=null || site.status()!=ObservationStatus.PRESENT){blockers.add("site:"+(site.reason()==null?"unknown":site.reason()));reason=site.reason()==null?Reason.STALE_OBSERVATION:site.reason();}
        if(stock.reason()!=null || stock.status()==ObservationStatus.UNKNOWN){blockers.add("stock:"+(stock.reason()==null?"unknown":stock.reason()));reason=stock.reason()==null?Reason.STALE_OBSERVATION:stock.reason();}
        if(!source.equals(destination)){blockers.add("missing-transfer-capability");reason=Reason.UNSUPPORTED_PRIMITIVE;}
        if(inspection.compatible() && site.status()==ObservationStatus.PRESENT && site.reason()==null) {
            try {var projection=BlueprintValidator.outstanding(BlueprintValidator.bind(inspection.body(),binding),inspection.materials(),site.cells());
                correct=projection.correct();outstanding=projection.outstanding();partial=projection.partialStructures();
            }catch(IllegalArgumentException|ArithmeticException invalid){blockers.add("site-envelope-or-evidence");reason=Reason.REQUEST_INVALID;unknown=true;}
        }
        if(!unknown && stock.reason()==null && outstanding.containsKey(PLANKS)) {
            long q=outstanding.getOrDefault(PLANKS,0L),present=Math.min(q,stock.free());
            eligible=Map.of(PLANKS,present);shortfalls=Map.of(PLANKS,Math.subtractExact(q,present));
        }
        return new Report(inspection.ref(),inspection.materials(),correct,outstanding,eligible,shortfalls,gaps,gaps,unknown,partial,blockers,reason,
                Math.min(site.tick(),stock.tick()),Math.addExact(site.inspected(),stock.inspected()),stock.identity());
    }
}
