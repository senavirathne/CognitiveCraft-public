package dev.aivillages.core.kernel.production.planning;

import java.util.*;
import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.production.*;
import static dev.aivillages.core.kernel.production.planning.PlanningModel.*;

/** Bounded whole-goal resource search. All state below is hypothetical and discarded after the call. */
public final class StaticProductionPlanner {
    private final int maximumExpansions;
    public StaticProductionPlanner(){this(MAX_EXPANSIONS);}
    public StaticProductionPlanner(int maximumExpansions){if(maximumExpansions<1 || maximumExpansions>MAX_EXPANSIONS)throw new IllegalArgumentException("Finite planning work bound required");this.maximumExpansions=maximumExpansions;}
    private static final String STICK="minecraft:stick",TABLE="minecraft:crafting_table",AXE="minecraft:wooden_axe";
    private record State(Map<RecipeChoice.ItemKey,Long> pool,Map<String,Long> acquired,long eligible,boolean table,Cost cost,List<Action> actions){}
    private static String stateKey(State s){return StrictJson.canonical(Map.of("pool",s.pool.entrySet().stream().filter(e->e.getValue()>0).sorted(Comparator.comparing(e->itemOrder(e.getKey()))).map(e->Map.of("item",e.getKey().value(),"n",e.getValue())).toList(),"acquired",new TreeMap<>(s.acquired),"eligible",s.eligible,"table",s.table));}
    private static long total(Map<RecipeChoice.ItemKey,Long> pool,String id){long n=0;for(var e:pool.entrySet())if(e.getKey().id().equals(id))n=Math.addExact(n,e.getValue());return n;}
    public static boolean usable(Snapshot s,Stock x){return x.availability()==Availability.PRESENT && x.permitted() && x.reachable() && (x.location().container()!=null?s.goal().policy().allowedSources().contains(x.location().container()) && x.location().container().dimension().equals(s.owner().executor().dimension()):x.location().actor().equals(s.owner().executor()));}
    public static boolean rootUsable(Snapshot s,Stock x){return usable(s,x) || x.availability()==Availability.PRESENT && x.permitted() && x.reachable() && x.location().container()!=null && x.location().container().equals(s.activeDestination());}
    static Map<RecipeChoice.ItemKey,Long> pool(Snapshot s){var p=new HashMap<RecipeChoice.ItemKey,Long>();for(var x:s.stock())if(usable(s,x) || rootUsable(s,x) && x.item().equals(s.goal().item()))p.merge(x.item(),x.units(),Math::addExact);return p;}
    static long eligibleIn(Snapshot s,Stock x){if(!rootUsable(s,x) || !x.item().equals(s.goal().item()))return 0;if(s.goal().mode()==Mode.SUPPLY)return x.units();if(!s.root().equals(x.eligibleRoot()))return 0;return s.goal().mode()==Mode.MAKE_NEW && x.eligibility()==Eligibility.ROOT_PRODUCED || s.goal().mode()==Mode.ACQUIRE_NEW && x.eligibility()==Eligibility.ROOT_ACQUIRED?x.rootEligibleUnits():0;}
    static long eligible(Snapshot s){long n=0;for(var x:s.stock())n=Math.addExact(n,eligibleIn(s,x));return Math.min(n,s.remainingDemand());}
    static String boundaryProblem(Snapshot s){
        if(!s.world().equals(s.context().scope().worldId()))return "WORLD_SCOPE_MISMATCH";
        if(!s.goal().policy().allowedExecutors().contains(s.owner().executor()))return "EXECUTOR_CHANGE_NOT_AUTHORIZED";
        if(!s.goal().policy().allowedDestinations().contains(s.activeDestination()))return "DESTINATION_CHANGE_NOT_AUTHORIZED";
        if(!s.activeDestination().dimension().equals(s.owner().executor().dimension()) || !s.goal().policy().staging().dimension().equals(s.owner().executor().dimension()))return "DIMENSION_MISMATCH";
        if(s.owner().cancellationRequested())return "CANCELLATION_REQUESTED";
        if(s.owner().unsettledOrUncertain())return "ORIGINAL_OWNER_NOT_SETTLED";
        if(!s.owner().canPropose())return "AUTHORITY_OR_ACTOR_UNAVAILABLE";
        if(s.nowMillis()>=s.budget().absoluteDeadlineMillis())return "ORIGINAL_DEADLINE_EXPIRED";
        if(s.owner().retryRequired() && s.budget().remaining(Counter.RETRIES)==0)return "CUMULATIVE_RETRIES_EXHAUSTED";
        return "";
    }
    private static boolean facilityReady(Snapshot s){var f=s.facility();return f!=null && f.availability()==Availability.PRESENT && f.permitted() && f.reachable();}
    private static boolean canPlace(Snapshot s){var f=s.facility();return f!=null && f.availability()==Availability.ABSENT && f.permitted() && f.reachable() && f.approvedSiteFree() && s.goal().policy().allowProvision() && f.reference().equals(s.goal().policy().approvedTableSite()) && f.placement()!=null && f.placement().usable() && s.supportedKinds().contains(Kind.PLACE_TABLE);}
    private static boolean unknown(Snapshot s){return !s.catalogueComplete() || s.stock().stream().anyMatch(x->x.permitted() && x.availability()==Availability.UNKNOWN)
            || s.acquisition().stream().anyMatch(x->x.permitted() && (x.availability()==Availability.UNKNOWN || x.method()==null || !x.method().usable()))
            || s.facility()!=null && s.facility().availability()==Availability.UNKNOWN || s.recipes().stream().anyMatch(x->x.method()==null || !x.method().usable());}
    private static Set<String> relevant(Snapshot s){var needed=new HashSet<RecipeChoice.ItemKey>();needed.add(s.goal().item());var out=new HashSet<String>();boolean changed=true;
        while(changed){changed=false;for(var p:s.recipes())if(needed.contains(p.result()) && out.add(p.recipe())){for(var cell:p.cells())needed.addAll(cell);if(p.axe() && !facilityReady(s) && canPlace(s))for(var table:s.recipes())if(table.result().id().equals(TABLE))needed.add(table.result());changed=true;}}
        return Set.copyOf(out);}
    private static long ceil(long n,long y){return n/y+(n%y==0?0:1);}
    private static long plankBound(Snapshot s){long n=s.remainingDemand();String id=s.goal().item().id();if(id.endsWith("_planks"))return n;if(id.equals(STICK))return Math.multiplyExact(ceil(n,4),2);if(id.equals(TABLE))return Math.multiplyExact(n,4);
        if(id.equals(AXE))return Math.addExact(Math.addExact(Math.multiplyExact(n,3),Math.multiplyExact(ceil(Math.multiplyExact(n,2),4),2)),!facilityReady(s) && canPlace(s)?4:0);return 0;}
    private static long useful(Snapshot s,State state,RecipeProfile p,Set<String> relevant){if(!relevant.contains(p.recipe()))return 0;long rootNeed=Math.max(0,s.remainingDemand()-state.eligible);
        if(p.result().equals(s.goal().item()) && s.goal().mode()!=Mode.ACQUIRE_NEW)return Math.min(rootNeed,p.yield());
        if(p.result().id().endsWith("_planks"))return Math.min(p.yield(),Math.max(0,plankBound(s)-state.pool.getOrDefault(p.result(),0L)));
        if(p.result().id().equals(STICK) && s.goal().item().id().equals(AXE))return Math.min(p.yield(),Math.max(0,Math.multiplyExact(s.remainingDemand(),2)-total(state.pool,STICK)));
        if(p.result().id().equals(TABLE) && s.goal().item().id().equals(AXE) && !state.table && canPlace(s))return Math.max(0,1-total(state.pool,TABLE));return 0;}
    private static List<Map<RecipeChoice.ItemKey,Long>> allocations(RecipeProfile recipe,Map<RecipeChoice.ItemKey,Long> pool){var out=new LinkedHashMap<String,Map<RecipeChoice.ItemKey,Long>>();allocate(recipe.cells(),0,pool,new HashMap<>(),out);return List.copyOf(out.values());}
    private static void allocate(List<List<RecipeChoice.ItemKey>> cells,int cell,Map<RecipeChoice.ItemKey,Long> pool,Map<RecipeChoice.ItemKey,Long> chosen,Map<String,Map<RecipeChoice.ItemKey,Long>> out){
        if(out.size()>=64)return;if(cell==cells.size()){var rows=chosen.entrySet().stream().sorted(Comparator.comparing(e->itemOrder(e.getKey()))).map(e->Map.of("item",e.getKey().value(),"n",e.getValue())).toList();out.putIfAbsent(StrictJson.canonical(rows),Map.copyOf(chosen));return;}
        for(var key:cells.get(cell).stream().distinct().sorted(Comparator.comparing(PlanningModel::itemOrder)).toList())if(pool.getOrDefault(key,0L)>chosen.getOrDefault(key,0L)){chosen.merge(key,1L,Math::addExact);allocate(cells,cell+1,pool,chosen,out);long left=chosen.get(key)-1;if(left==0)chosen.remove(key);else chosen.put(key,left);}
    }
    private static State apply(State state,Action a){var pool=new HashMap<>(state.pool);for(var input:a.inputs().entrySet()){long left=pool.getOrDefault(input.getKey(),0L)-input.getValue();if(left<0)return null;if(left==0)pool.remove(input.getKey());else pool.put(input.getKey(),left);}
        if(a.yield()>0)pool.merge(a.result(),(long)a.yield(),Math::addExact);var acquired=new HashMap<>(state.acquired);if(a.kind()==Kind.ACQUIRE)acquired.merge(a.sourceKey(),1L,Math::addExact);
        if(a.kind()==Kind.RETURN_CUSTODY){acquired.merge("return:"+a.sourceKey(),a.requestedDemand(),Math::addExact);acquired.merge("return-count",1L,Math::addExact);}var actions=new ArrayList<>(state.actions);actions.add(a);
        return new State(Map.copyOf(pool),Map.copyOf(acquired),a.rootOutput()?Math.addExact(state.eligible,a.yield()):state.eligible,state.table || a.kind()==Kind.PLACE_TABLE,state.cost.plus(a.allowance()),List.copyOf(actions));}
    private static List<Action> candidates(Snapshot s,State state,Set<String> relevant,boolean[] truncated){var out=new ArrayList<Action>();
        var ready=new HashMap<>(state.pool);for(var x:s.stock())if(usable(s,x) && x.location().actor()!=null){long held=Math.max(0,x.units()-state.acquired.getOrDefault("return:"+x.key(),0L));ready.merge(x.item(),-held,Math::addExact);}
        if(s.supportedKinds().contains(Kind.PRODUCE))for(var p:s.recipes().stream().sorted(Comparator.comparing(RecipeProfile::recipe)).toList()){
            long demand=useful(s,state,p,relevant);if(demand==0 || p.method()==null || !p.method().usable() || p.axe() && !state.table || Math.max(p.cells().size(),p.yield())>s.goal().policy().carryingUnits())continue;
            var choices=allocations(p,ready);if(choices.size()>=64)truncated[0]=true;for(var inputs:choices)out.add(new Action(Kind.PRODUCE,p.recipe(),p.capability(),inputs,p.result(),p.yield(),demand,p.result().equals(s.goal().item()),p.allowance(),""));}
        if(s.supportedKinds().contains(Kind.ACQUIRE))for(var a:s.acquisition().stream().sorted(Comparator.comparing(Acquisition::key)).toList()){
            boolean root=a.result().equals(s.goal().item()) && s.goal().mode()!=Mode.MAKE_NEW;boolean material=s.recipes().stream().anyMatch(p->relevant.contains(p.recipe()) && p.cells().stream().anyMatch(c->c.contains(a.result()))) && state.pool.getOrDefault(a.result(),0L)<ceil(plankBound(s),4);
            if((root && state.eligible<s.remainingDemand() || material) && a.permitted() && a.reachable() && a.availability()==Availability.PRESENT && a.method()!=null && a.method().usable() && s.goal().policy().allowedAcquisition().contains(a.source()) && a.source().dimension().equals(s.owner().executor().dimension()) && state.acquired.getOrDefault(a.key(),0L)<a.availableUnits())out.add(new Action(Kind.ACQUIRE,"",a.method().capability(),Map.of(),a.result(),1,1,root,a.allowance(),a.key()));}
        if(!state.table && canPlace(s) && s.goal().item().id().equals(AXE))for(var e:ready.entrySet().stream().sorted(Comparator.comparing(e->itemOrder(e.getKey()))).toList())if(e.getKey().id().equals(TABLE) && e.getValue()>0){out.add(new Action(Kind.PLACE_TABLE,"",CraftingTablePlacement.ID,Map.of(e.getKey(),1L),e.getKey(),0,1,false,s.facility().placementAllowance(),""));break;}
        if(s.goal().policy().allowCustodyReturn() && s.supportedKinds().contains(Kind.RETURN_CUSTODY) && s.returnsUsed()+state.acquired.getOrDefault("return-count",0L)<s.goal().policy().maximumReturns())for(var x:s.stock().stream().sorted(Comparator.comparing(Stock::key)).toList())if(usable(s,x) && x.location().actor()!=null){long held=x.units()-state.acquired.getOrDefault("return:"+x.key(),0L);var method=PlanningCoordinator.outputMethod(s,x.item());boolean needed=s.recipes().stream().anyMatch(p->relevant.contains(p.recipe()) && p.cells().stream().anyMatch(c->c.contains(x.item())));
            if(held>0 && needed && method!=null)out.add(new Action(Kind.RETURN_CUSTODY,"",method.capability(),Map.of(),x.item(),0,Math.min(held,s.goal().policy().carryingUnits()),false,new Cost(x.travelCost(),1,1),x.key()));}
        return out;
    }
    private static String methodKey(Action a){return a.kind()==Kind.PRODUCE?a.recipe():a.kind()==Kind.ACQUIRE?"acquire:"+a.sourceKey():a.kind()==Kind.RETURN_CUSTODY?"return:"+itemOrder(a.result()):"place";}
    private static ValidatedPlan pin(Snapshot s,List<Action> actions){var methods=new TreeMap<String,MethodPin>();for(var a:actions){if(a.kind()==Kind.PRODUCE)methods.put(a.recipe(),s.recipe(a.recipe()).orElseThrow().method());else if(a.kind()==Kind.ACQUIRE)methods.put("acquire:"+a.sourceKey(),s.acquisition().stream().filter(x->x.key().equals(a.sourceKey())).findFirst().orElseThrow().method());else if(a.kind()==Kind.PLACE_TABLE)methods.put("place",s.facility().placement());else if(a.kind()==Kind.RETURN_CUSTODY)methods.put(methodKey(a),PlanningCoordinator.outputMethod(s,a.result()));}
        return new ValidatedPlan(s.world(),s.root(),s.owner().originalOwner(),s.context(),s.goal(),s.budget().initialDigest(),s.budget().absoluteDeadlineMillis(),actions,methods);}
    private static Map<RecipeChoice.ItemKey,Long> residual(Snapshot s,State state){var result=new HashMap<>(state.pool);long left=result.getOrDefault(s.goal().item(),0L)-s.remainingDemand();if(left<0)throw new IllegalArgumentException("Virtual goal allocation exceeds available units");if(left==0)result.remove(s.goal().item());else result.put(s.goal().item(),left);return Map.copyOf(result);}
    private static boolean covers(Cost pinned,Cost fresh){return pinned.travel()>=fresh.travel() && pinned.effects()>=fresh.effects() && pinned.ticks()>=fresh.ticks();}
    private static boolean cellsMatch(List<List<RecipeChoice.ItemKey>> cells,int index,Map<RecipeChoice.ItemKey,Long> remaining){if(index==cells.size())return remaining.values().stream().allMatch(n->n==0);for(var key:cells.get(index))if(remaining.getOrDefault(key,0L)>0){remaining.put(key,remaining.get(key)-1);boolean matched=cellsMatch(cells,index+1,remaining);remaining.put(key,remaining.get(key)+1);if(matched)return true;}return false;}
    private static MethodPin currentMethod(Snapshot s,Action a){if(a.kind()==Kind.PRODUCE)return s.recipe(a.recipe()).map(RecipeProfile::method).orElse(null);if(a.kind()==Kind.ACQUIRE)return s.acquisition().stream().filter(x->x.key().equals(a.sourceKey())).findFirst().map(Acquisition::method).orElse(null);if(a.kind()==Kind.RETURN_CUSTODY)return PlanningCoordinator.outputMethod(s,a.result());return s.facility()==null?null:s.facility().placement();}
    private static boolean legal(Snapshot s,State state,Action a){
        if(a.kind()==Kind.PRODUCE){var p=s.recipe(a.recipe()).orElse(null);if(p==null || !a.capability().equals(p.capability()) || a.yield()!=p.yield() || !a.result().equals(p.result()) || a.requestedDemand()>p.yield() || !a.sourceKey().isEmpty() || a.rootOutput()!=(a.result().equals(s.goal().item()) && s.goal().mode()!=Mode.ACQUIRE_NEW) || !covers(a.allowance(),p.allowance()) || !cellsMatch(p.cells(),0,new HashMap<>(a.inputs())) || Math.max(p.cells().size(),p.yield())>s.goal().policy().carryingUnits())return false;
            for(var input:a.inputs().entrySet()){long ready=state.pool.getOrDefault(input.getKey(),0L);for(var x:s.stock())if(usable(s,x) && x.location().actor()!=null && x.item().equals(input.getKey()))ready-=Math.max(0,x.units()-state.acquired.getOrDefault("return:"+x.key(),0L));if(ready<input.getValue())return false;}return !p.axe() || state.table;}
        if(a.kind()==Kind.ACQUIRE){var x=s.acquisition().stream().filter(v->v.key().equals(a.sourceKey())).findFirst().orElse(null);return x!=null && x.method()!=null && a.capability().equals(x.method().capability()) && a.result().equals(x.result()) && a.yield()==1 && a.requestedDemand()==1 && a.inputs().isEmpty() && a.recipe().isEmpty() && a.rootOutput()==(a.result().equals(s.goal().item()) && s.goal().mode()!=Mode.MAKE_NEW) && x.permitted() && x.reachable() && x.availability()==Availability.PRESENT && s.goal().policy().allowedAcquisition().contains(x.source()) && state.acquired.getOrDefault(x.key(),0L)<x.availableUnits() && covers(a.allowance(),x.allowance());}
        if(a.kind()==Kind.PLACE_TABLE)return canPlace(s) && a.capability().equals(CraftingTablePlacement.ID) && a.result().id().equals(TABLE) && a.inputs().equals(Map.of(a.result(),1L)) && a.yield()==0 && a.requestedDemand()==1 && !a.rootOutput() && covers(a.allowance(),s.facility().placementAllowance());
        if(a.kind()==Kind.RETURN_CUSTODY){var x=s.stock().stream().filter(v->v.key().equals(a.sourceKey())).findFirst().orElse(null);var method=x==null?null:PlanningCoordinator.outputMethod(s,x.item());return x!=null && usable(s,x) && x.location().actor()!=null && method!=null && a.capability().equals(method.capability()) && a.result().equals(x.item()) && a.inputs().isEmpty() && a.yield()==0 && !a.rootOutput() && s.goal().policy().allowCustodyReturn() && s.returnsUsed()+state.acquired.getOrDefault("return-count",0L)<s.goal().policy().maximumReturns() && a.requestedDemand()<=Math.min(s.goal().policy().carryingUnits(),x.units()-state.acquired.getOrDefault("return:"+x.key(),0L)) && covers(a.allowance(),new Cost(x.travelCost(),1,1));}
        return false;
    }
    public Analysis analyse(Snapshot s){String blocked=boundaryProblem(s);if(!blocked.isEmpty())return new Analysis(blocked.equals("ORIGINAL_OWNER_NOT_SETTLED") || blocked.equals("AUTHORITY_OR_ACTOR_UNAVAILABLE")?Feasibility.UNKNOWN:Feasibility.INFEASIBLE,blocked,null,Cost.ZERO,Map.of(),0);
        var initial=new State(Map.copyOf(pool(s)),Map.of(),eligible(s),facilityReady(s),Cost.ZERO,List.of());
        var order=Comparator.<State>comparingInt(x->x.actions.size()).thenComparing(Comparator.comparingLong((State x)->x.eligible).reversed()).thenComparingLong(x->x.cost.effects()).thenComparingLong(x->x.cost.travel()).thenComparingLong(x->x.cost.ticks()).thenComparing(StaticProductionPlanner::stateKey);
        var queue=new PriorityQueue<>(order);queue.add(initial);var visited=new HashMap<String,Cost>();var relevant=relevant(s);int expansions=0;boolean limited=false,budgetPruned=false;boolean[] truncated={false};
        while(!queue.isEmpty() && expansions<maximumExpansions){var state=queue.remove();String key=stateKey(state);var old=visited.get(key);if(old!=null && old.travel()<=state.cost.travel() && old.effects()<=state.cost.effects() && old.ticks()<=state.cost.ticks())continue;visited.put(key,state.cost);expansions++;
            if(state.eligible>=s.remainingDemand())return new Analysis(Feasibility.FEASIBLE,"FEASIBLE_CURRENT_SNAPSHOT",pin(s,state.actions),state.cost,residual(s,state),expansions);
            if(state.actions.size()>=MAX_ACTIONS){limited=true;continue;}
            for(var action:candidates(s,state,relevant,truncated)){State next;try{next=apply(state,action);}catch(ArithmeticException overflow){limited=true;continue;}
                if(next!=null && s.budget().fits(next.cost)){if(queue.size()<4096)queue.add(next);else limited=true;}else if(next!=null)budgetPruned=true;}
        }
        if(!queue.isEmpty() || limited || truncated[0])return new Analysis(Feasibility.UNKNOWN,"BOUNDED_SEARCH_EXHAUSTED",null,Cost.ZERO,initial.pool,expansions);
        if(unknown(s))return new Analysis(Feasibility.UNKNOWN,"CURRENT_EVIDENCE_OR_METHOD_INCOMPLETE",null,Cost.ZERO,initial.pool,expansions);
        return new Analysis(Feasibility.INFEASIBLE,budgetPruned?"CUMULATIVE_WHOLE_GOAL_BUDGET_INSUFFICIENT":"NO_SUPPORTED_WHOLE_GOAL_PATH",null,Cost.ZERO,initial.pool,expansions);
    }
    /** Simulates the remaining pinned sequence against a fresh projection. It applies no physical effects. */
    public Analysis revalidate(Snapshot s,ValidatedPlan plan){String blocked=boundaryProblem(s);if(!blocked.isEmpty())return new Analysis(Feasibility.UNKNOWN,blocked,null,Cost.ZERO,Map.of(),0);
        if(!plan.world().equals(s.world()) || !plan.root().equals(s.root()) || !plan.owner().equals(s.owner().originalOwner()) || !plan.context().equals(s.context()) || !plan.goal().equals(s.goal()))return new Analysis(Feasibility.INFEASIBLE,"PINNED_GOAL_OR_OWNER_CHANGED",null,Cost.ZERO,Map.of(),0);
        if(!plan.budgetInitialDigest().equals(s.budget().initialDigest()) || plan.originalDeadlineMillis()!=s.budget().absoluteDeadlineMillis())return new Analysis(Feasibility.INFEASIBLE,"ORIGINAL_BUDGET_OR_DEADLINE_CHANGED",null,Cost.ZERO,Map.of(),0);
        for(int i=0;i<plan.actions().size();i++){var a=plan.actions().get(i);if(s.settledActionTokens().contains(a.token(i)))continue;var pin=plan.methodPins().get(methodKey(a));var current=currentMethod(s,a);if(pin==null || !pin.equals(current) || !pin.usable())return new Analysis(Feasibility.UNKNOWN,"PINNED_METHOD_OR_RECIPE_UNAVAILABLE",null,Cost.ZERO,Map.of(),0);}
        long rootNeed=eligible(s);for(int i=0;i<plan.actions().size();i++)if(!s.settledActionTokens().contains(plan.actions().get(i).token(i)) && plan.actions().get(i).rootOutput())rootNeed=Math.addExact(rootNeed,plan.actions().get(i).requestedDemand());
        if(rootNeed!=s.remainingDemand())return new Analysis(Feasibility.UNKNOWN,"AUTHORITATIVE_PROGRESS_REQUIRES_REPLAN",null,Cost.ZERO,Map.of(),0);
        var state=new State(Map.copyOf(pool(s)),Map.of(),eligible(s),facilityReady(s),Cost.ZERO,List.of());int index=0;
        for(var action:plan.actions()){if(s.settledActionTokens().contains(action.token(index++)))continue;
            if(!s.supportedKinds().contains(action.kind()))return new Analysis(Feasibility.UNKNOWN,"CURRENT_PRIMITIVE_UNSUPPORTED",null,Cost.ZERO,state.pool,index);
            if(!legal(s,state,action))return new Analysis(Feasibility.UNKNOWN,"CURRENT_ACTION_BINDING_OR_COST_REQUIRES_REPLAN",null,Cost.ZERO,state.pool,index);
            if(action.kind()==Kind.ACQUIRE){var a=s.acquisition().stream().filter(x->x.key().equals(action.sourceKey())).findFirst().orElse(null);if(a==null || !a.permitted() || !a.reachable() || a.availability()!=Availability.PRESENT || !s.goal().policy().allowedAcquisition().contains(a.source()) || state.acquired.getOrDefault(a.key(),0L)>=a.availableUnits())return new Analysis(Feasibility.UNKNOWN,"CURRENT_ACQUISITION_SOURCE_UNAVAILABLE",null,Cost.ZERO,state.pool,index);}
            if(action.kind()==Kind.PRODUCE && s.recipe(action.recipe()).map(RecipeProfile::axe).orElse(false) && !state.table || action.kind()==Kind.PLACE_TABLE && !canPlace(s))return new Analysis(Feasibility.UNKNOWN,"CURRENT_FACILITY_REQUIRES_REPLAN",null,Cost.ZERO,state.pool,index);
            state=apply(state,action);if(state==null)return new Analysis(Feasibility.UNKNOWN,"REMOVED_CHECKPOINT_MATERIAL_REQUIRES_REPLAN",null,Cost.ZERO,Map.of(),index);
            if(!s.budget().fits(state.cost))return new Analysis(Feasibility.INFEASIBLE,"CUMULATIVE_REMAINING_PLAN_BUDGET_INSUFFICIENT",null,Cost.ZERO,state.pool,index);}
        return state.eligible>=s.remainingDemand()?new Analysis(Feasibility.FEASIBLE,"PINNED_SEQUENCE_REVALIDATED_WITHOUT_INFERENCE",plan,state.cost,residual(s,state),index):new Analysis(Feasibility.UNKNOWN,"AUTHORITATIVE_PROGRESS_REQUIRES_REPLAN",null,Cost.ZERO,state.pool,index);
    }
}
