package dev.aivillages.core.kernel.production.planning;

import java.util.*;
import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.production.*;
import static dev.aivillages.core.kernel.Contracts.*;

/** Immutable B projections and proposals. No value grants authority or certifies a physical effect/save. */
public final class PlanningModel {
    private PlanningModel(){}
    public static final int SCHEMA=1,MAX_STOCK=128,MAX_RECIPES=16,MAX_ACTIONS=32,MAX_EXPANSIONS=4096;
    public enum Mode { MAKE_NEW, ACQUIRE_NEW, SUPPLY }
    public enum Availability { PRESENT, ABSENT, UNKNOWN }
    public enum Eligibility { NONE, ROOT_PRODUCED, ROOT_ACQUIRED }
    public enum Feasibility { FEASIBLE, INFEASIBLE, UNKNOWN }
    public enum Kind { PRODUCE, ACQUIRE, PLACE_TABLE, DELIVER_CURRENT, ALLOCATE_CURRENT, RETURN_CUSTODY }
    public enum Counter { TRAVEL, EFFECTS, ELAPSED_TICKS, RETRIES, REPLANS, MODEL_CALLS }
    public record Cost(long travel,long effects,long ticks) {
        public static final Cost ZERO=new Cost(0,0,0);
        public Cost {nonnegative(travel,effects,ticks);}
        public Cost plus(Cost b){return new Cost(Math.addExact(travel,b.travel),Math.addExact(effects,b.effects),Math.addExact(ticks,b.ticks));}
        public Cost times(long n){nonnegative(n);return new Cost(Math.multiplyExact(travel,n),Math.multiplyExact(effects,n),Math.multiplyExact(ticks,n));}
    }
    public record Budget(Map<Counter,Long> original,Map<Counter,Long> used,long absoluteDeadlineMillis) {
        public Budget {original=counts(original);used=counts(used);nonnegative(absoluteDeadlineMillis);}
        public long remaining(Counter c){return Math.max(0,original.get(c)-used.get(c));}
        public String initialDigest(){return digest(named(original));}
        public boolean fits(Cost c){return c.travel<=remaining(Counter.TRAVEL) && c.effects<=remaining(Counter.EFFECTS) && c.ticks<=remaining(Counter.ELAPSED_TICKS);}
    }
    public record Policy(ActorRef originalActor,Set<ActorRef> allowedExecutors,Set<ContainerRef> allowedSources,
                         Set<ContainerRef> allowedDestinations,Set<Cuboid> allowedAcquisition,ContainerRef staging,
                         FacilityRef approvedTableSite,boolean allowProvision,boolean allowCustodyReturn,long maximumReturns,long carryingUnits) {
        public Policy {
            Objects.requireNonNull(originalActor);Objects.requireNonNull(staging);allowedExecutors=Set.copyOf(allowedExecutors);allowedSources=Set.copyOf(allowedSources);allowedDestinations=Set.copyOf(allowedDestinations);allowedAcquisition=Set.copyOf(allowedAcquisition);
            if(!allowedExecutors.contains(originalActor) || allowedExecutors.size()>4 || allowedSources.isEmpty() || allowedSources.size()>4
                    || allowedDestinations.isEmpty() || allowedDestinations.size()>4 || allowedAcquisition.size()>4 || !allowedSources.contains(staging)
                    || carryingUnits<1 || carryingUnits>512 || maximumReturns<0 || maximumReturns>4 || !allowCustodyReturn && maximumReturns!=0)
                throw new IllegalArgumentException("Finite explicit actor/source/destination/return policy required");
        }
        public Map<String,Object> value(){var v=new TreeMap<String,Object>();v.put("actor",originalActor.toString());v.put("executors",allowedExecutors.stream().map(Object::toString).sorted().toList());v.put("sources",allowedSources.stream().map(Object::toString).sorted().toList());v.put("destinations",allowedDestinations.stream().map(Object::toString).sorted().toList());v.put("acquisition",allowedAcquisition.stream().map(Object::toString).sorted().toList());v.put("staging",staging.toString());v.put("site",String.valueOf(approvedTableSite));v.put("provision",allowProvision);v.put("returns",allowCustodyReturn);v.put("maximumReturns",maximumReturns);v.put("carry",carryingUnits);return v;}
    }
    public record Goal(Mode mode,RecipeChoice.ItemKey item,long demand,ContainerRef destination,Policy policy) {
        public Goal {Objects.requireNonNull(mode);Objects.requireNonNull(item);Objects.requireNonNull(destination);Objects.requireNonNull(policy);if(item.empty() || demand<1 || demand>64 || !policy.allowedDestinations.contains(destination))throw new IllegalArgumentException("Bounded exact authorized goal required");}
        public String digest(){return PlanningModel.digest(Map.of("mode",mode.name(),"item",item.value(),"demand",demand,"destination",RecipeChoice.container(destination),"policy",policy.value()));}
    }
    public record Owner(UUID originalOwner,ActorRef executor,long executionGeneration,boolean canPropose,
                        boolean unsettledOrUncertain,boolean cancellationRequested,boolean retryRequired) {
        public Owner {Objects.requireNonNull(originalOwner);Objects.requireNonNull(executor);nonnegative(executionGeneration);}
    }
    public record Location(ContainerRef container,ActorRef actor,int slot) {
        public Location {if((container==null)==(actor==null) || slot<0 || slot>127)throw new IllegalArgumentException("Exact container or actor slot required");}
    }
    /** units is available/unallocated; observedUnits is the full native stack count used by exact bindings. */
    public record Stock(String key,Location location,RecipeChoice.ItemKey item,long units,long observedUnits,long rootEligibleUnits,
                        Eligibility eligibility,Availability availability,boolean permitted,boolean reachable,long travelCost,long relevantRevision) {
        public Stock {text(key,128);Objects.requireNonNull(location);Objects.requireNonNull(item);Objects.requireNonNull(eligibility);Objects.requireNonNull(availability);nonnegative(units,observedUnits,rootEligibleUnits,travelCost,relevantRevision);
            if(item.empty() || observedUnits<units || observedUnits>64 || rootEligibleUnits>units || eligibility==Eligibility.NONE && rootEligibleUnits!=0
                    || availability!=Availability.PRESENT && observedUnits!=0 || !permitted && observedUnits!=0)throw new IllegalArgumentException("Private/unknown stock cannot expose or grant quantities");}
    }
    public record MethodPin(CapabilityId capability,String artifactHash,String semanticFingerprint,boolean usable,int artifactVersion,int contractVersion) {
        public MethodPin(CapabilityId capability,String artifactHash,String semanticFingerprint,boolean usable){this(capability,artifactHash,semanticFingerprint,usable,1,capability.version());}
        public MethodPin {Objects.requireNonNull(capability);hash(artifactHash);hash(semanticFingerprint);if(artifactVersion!=1 || contractVersion!=capability.version())throw new IllegalArgumentException("Unsupported artifact/contract version");}
    }
    /** Ordered nonempty cells from an actual validated native recipe profile, never from model JSON. */
    public record RecipeProfile(String recipe,CapabilityId capability,List<List<RecipeChoice.ItemKey>> cells,
                                RecipeChoice.ItemKey result,String semanticFingerprint,MethodPin method,Cost allowance) {
        public RecipeProfile {
            var d=definition(capability);Objects.requireNonNull(result);Objects.requireNonNull(allowance);hash(semanticFingerprint);cells=cells.stream().map(List::copyOf).toList();
            if(!d.recipe().equals(recipe) || !d.output().id().equals(result.id()) || cells.size()!=d.totalInputs() || allowance.effects<3 || allowance.ticks<1
                    || cells.stream().anyMatch(c->c.isEmpty() || c.size()>16 || c.stream().anyMatch(RecipeChoice.ItemKey::empty))
                    || method!=null && (!method.capability.equals(capability) || !method.semanticFingerprint.equals(semanticFingerprint)))throw new IllegalArgumentException("Bounded static profile, result and immutable method pin required");
            for(var cell:cells)for(var key:cell){boolean stick=d.axe() && key.id().equals("minecraft:stick");boolean primary;
                if(capability.version()==1)primary=key.id().equals(d.input().id());
                else if(d.output().id().endsWith("_planks")){String log=d.input().id();primary=Set.of(log,log.replace("_log","_wood"),log.replace(":",":stripped_"),log.replace(":",":stripped_").replace("_log","_wood")).contains(key.id());}
                else primary=key.id().matches("minecraft:(oak|spruce|birch|jungle|acacia|dark_oak|mangrove|cherry|bamboo|crimson|warped|pale_oak|poplar)_planks");
                if(!primary && !stick)throw new IllegalArgumentException("Profile cannot widen the pinned material envelope");}
            if(d.axe() && (cells.stream().filter(c->c.stream().allMatch(k->k.id().equals("minecraft:stick"))).count()!=2
                    || cells.stream().anyMatch(c->c.stream().anyMatch(k->k.id().equals("minecraft:stick")) && c.stream().anyMatch(k->!k.id().equals("minecraft:stick")))))throw new IllegalArgumentException("Three plank cells and two stick cells required");
        }
        public int yield(){return definition(capability).outputPerBatch();}
        public boolean axe(){return definition(capability).axe();}
    }
    public record Acquisition(String key,Cuboid source,RecipeChoice.ItemKey result,long availableUnits,Availability availability,
                              boolean permitted,boolean reachable,MethodPin method,Cost allowance) {
        public Acquisition {text(key,128);Objects.requireNonNull(source);Objects.requireNonNull(result);Objects.requireNonNull(availability);Objects.requireNonNull(allowance);nonnegative(availableUnits);
            if(availableUnits>64 || availability!=Availability.PRESENT && availableUnits!=0 || !permitted && availableUnits!=0 || allowance.effects<1 || allowance.ticks<1
                    || !Set.of("minecraft:oak_log","minecraft:birch_log").contains(result.id()) || method!=null && !method.capability.equals(result.id().equals("minecraft:oak_log")?OakLogDelivery.ID:BirchLogDelivery.ID))throw new IllegalArgumentException("Bounded observed authorized acquisition source required");}
    }
    public record Facility(FacilityRef reference,Availability availability,boolean permitted,boolean reachable,String identity,
                           boolean approvedSiteFree,long relevantRevision,MethodPin placement,Cost placementAllowance) {
        public Facility {Objects.requireNonNull(reference);Objects.requireNonNull(availability);text(identity,128);Objects.requireNonNull(placementAllowance);nonnegative(relevantRevision);
            if(availability==Availability.PRESENT && identity.isBlank() || placement!=null && (!placement.capability.equals(CraftingTablePlacement.ID) || placementAllowance.effects<2 || placementAllowance.ticks<1))throw new IllegalArgumentException("Current facility/placement projection required");}
    }
    public record ChoiceOffer(ValidatedRequest request,RecipeChoice choice,RecipeSources sources) {
        public ChoiceOffer {Objects.requireNonNull(request);Objects.requireNonNull(choice);Objects.requireNonNull(sources);RecipeChoicePlan.propose(new UUID(0,0),0,request,choice,sources);}
    }
    public record Snapshot(int schema,UUID world,UUID root,long revision,TrustedContext context,Owner owner,Goal goal,
                           ContainerRef activeDestination,long confirmedCredit,Budget budget,long nowMillis,long relevantWorldRevision,
                           List<Stock> stock,List<Acquisition> acquisition,List<RecipeProfile> recipes,Facility facility,List<ChoiceOffer> choices,
                           Set<Kind> supportedKinds,Set<String> settledActionTokens,boolean catalogueComplete,boolean modelAvailable,long returnsUsed) {
        public Snapshot {Objects.requireNonNull(world);Objects.requireNonNull(root);Objects.requireNonNull(context);Objects.requireNonNull(owner);Objects.requireNonNull(goal);Objects.requireNonNull(activeDestination);Objects.requireNonNull(budget);
            stock=List.copyOf(stock);acquisition=List.copyOf(acquisition);recipes=List.copyOf(recipes);choices=List.copyOf(choices);supportedKinds=Set.copyOf(supportedKinds);settledActionTokens=Set.copyOf(settledActionTokens);nonnegative(revision,confirmedCredit,nowMillis,relevantWorldRevision,returnsUsed);
            if(schema!=SCHEMA || confirmedCredit>goal.demand || stock.size()>MAX_STOCK || acquisition.size()>8 || recipes.size()>MAX_RECIPES || choices.size()>32 || settledActionTokens.size()>MAX_ACTIONS
                    || stock.stream().map(Stock::key).distinct().count()!=stock.size() || recipes.stream().map(RecipeProfile::recipe).distinct().count()!=recipes.size())throw new IllegalArgumentException("Bounded versioned planning snapshot required");}
        public long remainingDemand(){return goal.demand-confirmedCredit;}
        public Optional<RecipeProfile> recipe(String id){return recipes.stream().filter(r->r.recipe.equals(id)).findFirst();}
    }
    public record Action(Kind kind,String recipe,CapabilityId capability,Map<RecipeChoice.ItemKey,Long> inputs,
                         RecipeChoice.ItemKey result,int yield,long requestedDemand,boolean rootOutput,Cost allowance,String sourceKey) {
        public Action {Objects.requireNonNull(kind);Objects.requireNonNull(capability);inputs=Map.copyOf(inputs);Objects.requireNonNull(result);Objects.requireNonNull(allowance);text(recipe,128);text(sourceKey,128);
            if(yield<0 || yield>4 || requestedDemand<1 || requestedDemand>64 || inputs.size()>9 || inputs.values().stream().anyMatch(n->n<1 || n>64))throw new IllegalArgumentException("Bounded proposed action required");}
        public Map<String,Object> value(){return Map.of("kind",kind.name(),"recipe",recipe,"capability",Map.of("name",capability.name(),"version",capability.version()),"inputs",inputs.entrySet().stream().sorted(Comparator.comparing(e->itemOrder(e.getKey()))).map(e->Map.of("item",e.getKey().value(),"units",e.getValue())).toList(),"result",result.value(),"yield",yield,"demand",requestedDemand,"root",rootOutput,"source",sourceKey);}
        public String token(int index){return "proposal-action:"+digest(Map.of("index",index,"action",value()));}
    }
    public record ValidatedPlan(UUID world,UUID root,UUID owner,TrustedContext context,Goal goal,String budgetInitialDigest,long originalDeadlineMillis,List<Action> actions,Map<String,MethodPin> methodPins) {
        public ValidatedPlan {Objects.requireNonNull(world);Objects.requireNonNull(root);Objects.requireNonNull(owner);Objects.requireNonNull(context);Objects.requireNonNull(goal);hash(budgetInitialDigest);actions=List.copyOf(actions);methodPins=Map.copyOf(methodPins);nonnegative(originalDeadlineMillis);if(actions.size()>MAX_ACTIONS)throw new IllegalArgumentException("Bounded immutable sequence required");}
        public String digest(){return PlanningModel.digest(Map.of("world",world.toString(),"root",root.toString(),"owner",owner.toString(),"context",context.toString(),"goal",goal.digest(),"budget",budgetInitialDigest,"deadline",originalDeadlineMillis,"actions",actions.stream().map(Action::value).toList(),"methods",new TreeMap<>(methodPins).entrySet().stream().map(e->Map.of("key",e.getKey(),"artifact",e.getValue().artifactHash,"semantic",e.getValue().semanticFingerprint,"capability",e.getValue().capability.toString(),"artifactVersion",e.getValue().artifactVersion,"contractVersion",e.getValue().contractVersion)).toList()));}
    }
    public record Analysis(Feasibility feasibility,String reason,ValidatedPlan plan,Cost allowance,Map<RecipeChoice.ItemKey,Long> residual,int expansions) {
        public Analysis {Objects.requireNonNull(feasibility);text(reason,128);Objects.requireNonNull(allowance);residual=Map.copyOf(residual);if(expansions<0 || feasibility==Feasibility.FEASIBLE && plan==null)throw new IllegalArgumentException("Explicit coarse feasibility required");}
    }
    public record Quantities(long requestedDemand,long batches,long newPhysicalOutput,long eligibleExistingUnits,long nativeDelivery,
                             long goalAllocationUpperBound,long parentAllocationUpperBound,long unallocatedPhysical,long actorSurplus) {
        public Quantities {nonnegative(requestedDemand,batches,newPhysicalOutput,eligibleExistingUnits,nativeDelivery,goalAllocationUpperBound,parentAllocationUpperBound,unallocatedPhysical,actorSurplus);long eligible=Math.addExact(newPhysicalOutput,eligibleExistingUnits);
            if(requestedDemand<1 || Math.addExact(Math.addExact(goalAllocationUpperBound,parentAllocationUpperBound),unallocatedPhysical)>eligible || actorSurplus>unallocatedPhysical || nativeDelivery>eligible)throw new IllegalArgumentException("Proposal cannot over-allocate attributable units");}
    }
    public enum Predicate { CONFIRMED_DELIVERY_DELTA, CURRENT_MATERIALS, CURRENT_FACILITY, CURRENT_AUTHORIZED_ALLOCATION, CURRENT_RETURNED_CUSTODY }
    public record Checkpoint(Predicate predicate,RecipeChoice.ItemKey item,long units,ContainerRef container,FacilityRef facility) {
        public Checkpoint {Objects.requireNonNull(predicate);Objects.requireNonNull(item);if(units<1 || units>64)throw new IllegalArgumentException("Bounded useful checkpoint required");}
    }
    public record StockBinding(String key,Location location,RecipeChoice.ItemKey item,long units,long observedUnits) {
        public StockBinding {text(key,128);Objects.requireNonNull(location);Objects.requireNonNull(item);if(units<1 || observedUnits<units || observedUnits>64)throw new IllegalArgumentException("Exact proposed stock binding required");}
    }
    public record SegmentProposal(int schema,String proposalId,String stepToken,UUID world,UUID root,long revision,UUID owner,TrustedContext context,ActorRef executor,
                                  long executionGeneration,Kind kind,CapabilityId capability,MethodPin method,List<String> coveredActionTokens,
                                  List<StockBinding> bindings,String acquisitionSourceKey,Cuboid acquisitionArea,ContainerRef destination,FacilityRef facility,
                                  ChoiceOffer nativeChoice,Quantities quantities,Map<Counter,Long> requestedAllowance,long originalDeadlineMillis,Checkpoint checkpoint) {
        public SegmentProposal {if(schema!=SCHEMA)throw new IllegalArgumentException("Proposal version");nonnegative(revision,executionGeneration,originalDeadlineMillis);hash(proposalId);text(stepToken,96);text(acquisitionSourceKey,128);Objects.requireNonNull(world);Objects.requireNonNull(root);Objects.requireNonNull(owner);Objects.requireNonNull(context);Objects.requireNonNull(executor);Objects.requireNonNull(kind);Objects.requireNonNull(quantities);Objects.requireNonNull(checkpoint);
            coveredActionTokens=List.copyOf(coveredActionTokens);bindings=List.copyOf(bindings);requestedAllowance=counts(requestedAllowance);if(coveredActionTokens.size()>MAX_ACTIONS || bindings.size()>MAX_STOCK || kind==Kind.ACQUIRE && acquisitionArea==null)throw new IllegalArgumentException("Bounded exact segment bindings required");}
    }
    /** A may persist this pure payload. B owns no writer, timer, wake loop or retry ledger. */
    public record Memory(String signature,String lastProposalId,int unchangedChecks,long nextCheckMillis,String originalBudgetDigest,
                         long originalDeadlineMillis,Map<Counter,Long> observedUsage,long returnsUsed) {
        public static Memory empty(){return new Memory("","",0,0,"",0,zeroCounts(),0);}
        public Memory {text(signature,128);text(lastProposalId,64);text(originalBudgetDigest,64);observedUsage=counts(observedUsage);nonnegative(nextCheckMillis,originalDeadlineMillis,returnsUsed);if(unchangedChecks<0 || unchangedChecks>4)throw new IllegalArgumentException("Bounded no-progress memory required");}
    }
    public record ChoiceRequest(CapabilityId capability,long nativeAmount,ContainerRef destination,String semanticFingerprint,List<ContainerRef> permittedSources) {
        public ChoiceRequest {Objects.requireNonNull(capability);Objects.requireNonNull(destination);hash(semanticFingerprint);permittedSources=List.copyOf(permittedSources);if(nativeAmount<1 || nativeAmount>64 || permittedSources.size()>4)throw new IllegalArgumentException("Bounded read-only native-choice request required");}
    }
    public enum DecisionKind { PROPOSE, BLOCKED, WAIT, GOAL_ALREADY_CREDITED }
    public record Decision(DecisionKind kind,String blocker,Analysis analysis,SegmentProposal proposal,ChoiceRequest neededChoice,Memory memory,long nextCheckMillis) {
        public Decision {Objects.requireNonNull(kind);text(blocker,128);Objects.requireNonNull(memory);nonnegative(nextCheckMillis);if(kind==DecisionKind.PROPOSE && proposal==null)throw new IllegalArgumentException("Explicit proposal required");}
    }
    static RecipeProduction.Definition definition(CapabilityId id){return id.version()==2?RecipeProduction.choiceDefinition(id):RecipeProduction.of(id);}
    static void text(String value,int max){if(value==null || value.length()>max)throw new IllegalArgumentException("Bounded text required");}
    static void hash(String value){if(!RecipeTransaction.hash(value))throw new IllegalArgumentException("SHA-256 identity required");}
    static void nonnegative(long... n){for(long value:n)if(value<0)throw new IllegalArgumentException("Nonnegative checked quantities required");}
    public static String digest(Object value){return RecipeTransaction.fingerprint(StrictJson.canonical(value));}
    public static String itemOrder(RecipeChoice.ItemKey key){return key.id()+":"+key.componentDigest();}
    public static Map<Counter,Long> zeroCounts(){var m=new EnumMap<Counter,Long>(Counter.class);for(var c:Counter.values())m.put(c,0L);return Map.copyOf(m);}
    static Map<Counter,Long> counts(Map<Counter,Long> value){var m=new EnumMap<Counter,Long>(Counter.class);m.putAll(value);if(m.size()!=Counter.values().length || m.values().stream().anyMatch(v->v==null || v<0))throw new IllegalArgumentException("Every cumulative counter required");return Map.copyOf(m);}
    static Map<String,Long> named(Map<Counter,Long> value){var m=new TreeMap<String,Long>();value.forEach((k,v)->m.put(k.name(),v));return m;}
}
