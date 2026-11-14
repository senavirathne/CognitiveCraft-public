package dev.aivillages.core.kernel.production.planning;

import java.util.*;
import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.production.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.production.planning.PlanningModel.*;

/** Constructs proposed exact grids from fresh scoped observations. A must validate them natively before any effect. */
public final class PlanningChoiceBuilder {
    private PlanningChoiceBuilder(){}
    public record ProposedChoice(CapabilityRequest invocation,RecipeChoice choice,RecipeSources sources) {
        public ProposedChoice {Objects.requireNonNull(invocation);Objects.requireNonNull(choice);Objects.requireNonNull(sources);if(!invocation.capability().equals(choice.capability()))throw new IllegalArgumentException("Proposed invocation mismatch");}
    }
    public static ProposedChoice build(Snapshot s,ChoiceRequest request){String boundary=StaticProductionPlanner.boundaryProblem(s);if(!boundary.isEmpty())throw new IllegalArgumentException(boundary);if(request.capability().version()!=2)throw new IllegalArgumentException("Legacy methods retain their original native preflight path");
        var profile=s.recipes().stream().filter(p->p.capability().equals(request.capability())).findFirst().orElseThrow();if(!profile.semanticFingerprint().equals(request.semanticFingerprint()) || !s.goal().policy().allowedSources().containsAll(request.permittedSources()) || !(request.destination().equals(s.activeDestination()) || request.destination().equals(s.goal().policy().staging())))throw new IllegalArgumentException("Proposed native scope/semantic mismatch");
        var options=request.permittedSources().stream().distinct().sorted(Comparator.comparing(Object::toString)).toList();var sets=new ArrayList<List<ContainerRef>>();for(var first:options){sets.add(List.of(first));for(var second:options)if(first.toString().compareTo(second.toString())<0)sets.add(List.of(first,second));}
        var candidates=new ArrayList<ProposedChoice>();for(var sources:sets){var proposal=attempt(s,request,profile,sources);if(proposal!=null)candidates.add(proposal);}
        return candidates.stream().min(Comparator.comparingLong((ProposedChoice p)->travel(s,p)).thenComparingInt(p->p.sources().containers().size()).thenComparing(p->p.sources().containers().toString()).thenComparing(p->p.choice().batches().toString())).orElseThrow(()->new IllegalArgumentException("No exact currently available one/two-container grid for the full-goal input choice"));
    }
    private static long travel(Snapshot s,ProposedChoice p){long n=0;for(var x:s.stock())if(p.choice().batches().stream().flatMap(b->b.bindings().stream()).anyMatch(b->x.location().container()!=null && b.source().equals(x.location().container()) && b.sourceSlot()==x.location().slot()))n=Math.addExact(n,x.travelCost());return n;}
    private static ProposedChoice attempt(Snapshot s,ChoiceRequest request,RecipeProfile p,List<ContainerRef> sources){
        var stock=s.stock().stream().filter(x->StaticProductionPlanner.usable(s,x) && x.location().container()!=null && sources.contains(x.location().container())).sorted(Comparator.comparingLong(Stock::travelCost).thenComparing(Stock::key)).toList();var available=new HashMap<String,Long>();var observed=new HashMap<String,Long>();for(var x:stock){available.put(x.key(),x.units());observed.put(x.key(),x.observedUnits());}
        int width=p.axe()?3:2;int[] slots=p.axe()?new int[]{0,1,3,4,7}:p.cells().size()==4?new int[]{0,1,2,3}:p.cells().size()==2?new int[]{0,2}:new int[]{0};var batches=new ArrayList<RecipeChoice.Batch>();var actualSources=new LinkedHashSet<ContainerRef>();
        var ordered=new ArrayList<RecipeChoice.ItemKey>();if(!assign(p.cells(),0,new HashMap<>(request.inputsPerBatch()),ordered))return null;
        for(int batch=0;batch<request.batches();batch++){var grid=new ArrayList<>(Collections.nCopies(width*width,RecipeChoice.ItemKey.EMPTY));var bindings=new ArrayList<RecipeChoice.Binding>();
            for(int i=0;i<ordered.size();i++){Stock selected=null;for(var x:stock)if(available.get(x.key())>0 && x.item().equals(ordered.get(i))){selected=x;break;}if(selected==null)return null;
                grid.set(slots[i],selected.item());bindings.add(new RecipeChoice.Binding(slots[i],selected.location().container(),selected.location().slot(),Math.toIntExact(observed.get(selected.key())),selected.item()));actualSources.add(selected.location().container());available.put(selected.key(),available.get(selected.key())-1);observed.put(selected.key(),observed.get(selected.key())-1);}
            batches.add(new RecipeChoice.Batch(grid,bindings));}
        RecipeChoice.FacilityBinding facility=null;if(p.axe()){if(s.facility()==null || s.facility().availability()!=Availability.PRESENT || !s.facility().permitted() || !s.facility().reachable())return null;facility=new RecipeChoice.FacilityBinding(s.facility().reference(),s.facility().identity());}
        var choice=new RecipeChoice(p.capability(),p.recipe(),p.semanticFingerprint(),RecipeChoice.RUNTIME,p.reloadEpoch(),width,width,batches,p.result(),p.yield(),request.nativeAmount(),List.of(),facility);var policy=new RecipeSources(new ArrayList<>(actualSources));
        var args=new HashMap<String,Value>();args.put("actor",new ActorValue(s.owner().executor()));args.put("amount",new IntValue(request.nativeAmount()));args.put("source",new ContainerValue(policy.containers().getFirst()));args.put("destination",new ContainerValue(request.destination()));if(facility!=null)args.put("table",new FacilityValue(facility.reference()));
        return new ProposedChoice(new CapabilityRequest(p.capability(),args),choice,policy);
    }
    private static boolean assign(List<List<RecipeChoice.ItemKey>> cells,int index,Map<RecipeChoice.ItemKey,Long> left,List<RecipeChoice.ItemKey> out){if(index==cells.size())return left.values().stream().allMatch(n->n==0);for(var key:cells.get(index).stream().distinct().sorted(Comparator.comparing(PlanningModel::itemOrder)).toList())if(left.getOrDefault(key,0L)>0){left.put(key,left.get(key)-1);out.add(key);if(assign(cells,index+1,left,out))return true;out.removeLast();left.put(key,left.get(key)+1);}return false;}
}
