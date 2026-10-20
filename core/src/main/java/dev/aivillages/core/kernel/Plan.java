package dev.aivillages.core.kernel;

import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;

/** IMP-019 Plan contract 1. Decomposition data is separate from executable Skill IR. */
public final class Plan {
    public static final int SCHEMA=1;
    public static final AIWorkBroker.Contract OUTPUT=new AIWorkBroker.Contract("goal-plan",1,Generation.SCHEMA);
    private Plan() { }
    public enum Diagnostic { MALFORMED, UNSUPPORTED_CAPABILITY, UNBOUND, FABRICATED_RESOURCE,
        CYCLE, SIZE, GRANULARITY, QUANTITY, AUTHORITY, METHOD, STALE, BUDGET, PUBLICATION, CANCELLED }
    public record Slot(ValidatedRequest bound, ObservationSnapshot observation, ArtifactDescriptor method) {
        public Slot { Objects.requireNonNull(bound);Objects.requireNonNull(observation);Objects.requireNonNull(method); }
    }
    /** Immutable routing and authorized evidence captured before inference. Historical data is advisory. */
    public record Context(UUID parent, Jobs.Guard guard, ValidatedRequest goal,
                          CapabilityResolver.Decision direct, DeterministicPrerequisiteResolver.OutcomeCoverage prerequisites,
                          List<Slot> slots, String history) {
        public Context {
            Objects.requireNonNull(parent);Objects.requireNonNull(guard);Objects.requireNonNull(goal);
            Objects.requireNonNull(direct);Objects.requireNonNull(prerequisites); slots=List.copyOf(slots);
            if(slots.size()>8 || history==null || history.getBytes(StandardCharsets.UTF_8).length>2048)
                throw new IllegalArgumentException("Planning context bounds");
        }
        public boolean needsPlanning() {
            return direct.complete() && direct.routing().status()==Outcomes.ResolutionStatus.NEEDS_PLANNING
                    && prerequisites.complete() && !prerequisites.handled() && prerequisites.capability().equals(goal.request().capability());
        }
        /** Worst-case bounded capture work, including native coverage and eligible-method searches. */
        public int work() {
            int work=Math.multiplyExact(2,Math.addExact(1+direct.candidateWork()+direct.queriedPages()+direct.observation().scannedCells(),prerequisites.inspected()));
            for(var slot:slots)work=Math.addExact(work,PlanValidator.resolutionWorkLimit(slot.bound()));
            return Math.addExact(work,8+(prompt().getBytes(StandardCharsets.UTF_8).length+63)/64);
        }
        public String prompt() {
            return StrictJson.canonical(Map.of("contract","goal-plan:1","goal",requestData(goal.request()),
                    "requiredTotals",java.util.stream.IntStream.range(0,slots.size()).mapToObj(i->Map.of("slot",i,
                            "amount",((IntValue)slots.get(i).bound().request().arguments().get("amount")).value())).toList(),
                    "slots",slots.stream().map(s->Map.of("request",requestData(s.bound().request()),
                            "method",s.method().ref().toString(),"evidence",s.observation().reference().snapshotId().toString(),
                            "counts",s.observation().counts())).toList(),"history",history,
                    "instruction","Return nodes using only these slot bindings. Amounts for each slot must total its requested amount. Dependencies are node IDs; do not invent actors, resources, primitives, budgets or workers."));
        }
    }
    public record Node(int id,int slot,long amount,List<Integer> dependencies) {
        public Node { dependencies=List.copyOf(dependencies); }
    }
    public record Proposal(List<Node> nodes) {
        public Proposal {nodes=List.copyOf(nodes);}
    }
    /** Only the validator can issue canonical work. This value conveys no effect authority. */
    public static final class Validated {
        private final Context context;private final Proposal proposal;private final List<Jobs.Child> children;private final UUID id;
        Validated(Context context,Proposal proposal,List<Jobs.Child> children) {
            this.context=context;this.proposal=proposal;this.children=List.copyOf(children);id=Plan.id(context.parent());
        }
        public UUID id(){return id;} public Context context(){return context;}
        public Proposal proposal(){return proposal;} public List<Jobs.Child> children(){return children;}
    }
    public record Check(Validated value,Diagnostic diagnostic,Outcomes.Reason reason,String path,int work) {
        public boolean accepted(){return value!=null;}
    }
    public static UUID id(UUID parent) {return stable("goal-plan:1:"+parent);}
    public static UUID child(UUID parent,int node) {return stable("goal-plan:1:"+parent+":"+node);}
    public static UUID stable(String value){return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));}
    private static Object requestData(CapabilityRequest request) {
        try {return StrictJson.object(RequestCodec.encode(request));}catch(StrictJson.Invalid invalid){throw new IllegalArgumentException(invalid);}
    }
    public static String encode(Proposal proposal) {
        return StrictJson.canonical(Map.of("schema",1L,"nodes",proposal.nodes().stream().map(n->Map.of("id",n.id(),
                "capability",CropDelivery.ID.name(),"version",1L,"slot",n.slot(),"amount",n.amount(),"dependencies",n.dependencies())).toList()));
    }
    public static Proposal decode(String json,int maximumBytes) throws StrictJson.Invalid {
        if(json==null || json.getBytes(StandardCharsets.UTF_8).length>maximumBytes)throw new StrictJson.Invalid("$","SIZE");
        var root=StrictJson.object(json);Outcomes.exact(root,"schema","nodes");
        if(Outcomes.number(root,"schema","$")!=SCHEMA)throw new StrictJson.Invalid("$.schema","MALFORMED");
        if(!(root.get("nodes") instanceof List<?> rows) || rows.size()>8)throw new StrictJson.Invalid("$.nodes","SIZE");
        var nodes=new ArrayList<Node>();
        for(int i=0;i<rows.size();i++) {
            String path="$.nodes["+i+"]";
            if(!(rows.get(i) instanceof Map<?,?> raw))throw new StrictJson.Invalid(path,"MALFORMED");
            @SuppressWarnings("unchecked") var row=(Map<String,Object>)raw;
            Outcomes.exact(row,"id","capability","version","slot","amount","dependencies");
            if(!CropDelivery.ID.name().equals(Outcomes.string(row,"capability",path)) || Outcomes.number(row,"version",path)!=1)
                throw new StrictJson.Invalid(path+".capability","UNSUPPORTED_CAPABILITY");
            if(!(row.get("dependencies") instanceof List<?> dependencies) || dependencies.size()>8)throw new StrictJson.Invalid(path+".dependencies","SIZE");
            var edges=new ArrayList<Integer>();for(Object dep:dependencies) {
                if(!(dep instanceof Long n) || n<0 || n>7)throw new StrictJson.Invalid(path+".dependencies","UNBOUND");edges.add(n.intValue());
            }
            try {nodes.add(new Node(Math.toIntExact(Outcomes.number(row,"id",path)),Math.toIntExact(Outcomes.number(row,"slot",path)),
                    Outcomes.number(row,"amount",path),edges));}catch(ArithmeticException overflow){throw new StrictJson.Invalid(path,"UNBOUND");}
        }
        return new Proposal(nodes);
    }
    /** Backend grammar is only a transport bound; PlanValidator performs semantic admission. */
    public static Map<String,Object> outputShape() {
        var integer=Map.of("type","integer","minimum",0,"maximum",7);
        var row=Map.of("type","object","additionalProperties",false,"required",List.of("id","capability","version","slot","amount","dependencies"),
                "properties",Map.of("id",integer,"capability",Map.of("type","string","enum",List.of(CropDelivery.ID.name())),
                        "version",Map.of("type","integer","enum",List.of(1)),"slot",Map.of("type","integer","minimum",0,"maximum",1),
                        "amount",Map.of("type","integer","minimum",1,"maximum",64),"dependencies",Map.of("type","array","maxItems",8,"items",integer)));
        return Map.of("type","object","additionalProperties",false,"required",List.of("schema","nodes"),"properties",Map.of(
                "schema",Map.of("type","integer","enum",List.of(1)),"nodes",Map.of("type","array","minItems",2,"maxItems",8,"items",row)));
    }

    /** Canonical transport choices come from the bound request, never a model-supplied quantity. */
    public static Map<String,Object> outputShape(CapabilityRequest goal) {
        if(!PlanningGoals.valid(goal))throw new IllegalArgumentException("Bound planning goal required");
        var slots=PlanningGoals.slots(goal);var choices=new ArrayList<Map<String,Object>>();
        for(int i=0;i<slots.size();i++) {
            long amount=((IntValue)slots.get(i).arguments().get("amount")).value();
            choices.add(Map.of("type","object","additionalProperties",false,
                    "required",List.of("id","capability","version","slot","amount","dependencies"),
                    "properties",Map.of("id",Map.of("type","integer","enum",List.of(i)),
                            "slot",Map.of("type","integer","enum",List.of(i)),
                            "amount",Map.of("type","integer","enum",List.of(amount)),
                            "capability",Map.of("type","string","enum",List.of(CropDelivery.ID.name())),
                            "version",Map.of("type","integer","enum",List.of(1)),
                            "dependencies",Map.of("type","array","maxItems",1,"items",
                                    Map.of("type","integer","enum",List.of(1-i))))));
        }
        return Map.of("type","object","additionalProperties",false,"required",List.of("schema","nodes"),
                "properties",Map.of("schema",Map.of("type","integer","enum",List.of(1)),
                        "nodes",Map.of("type","array","minItems",slots.size(),"maxItems",slots.size(),
                                "uniqueItems",true,"items",Map.of("anyOf",choices))));
    }
}
