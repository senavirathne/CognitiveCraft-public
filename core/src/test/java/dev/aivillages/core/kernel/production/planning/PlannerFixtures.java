package dev.aivillages.core.kernel.production.planning;

import java.util.*;
import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.production.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.production.planning.PlanningModel.*;

/** Synthetic read-only projections. No Minecraft effect, native authority or saved-method evidence. */
final class PlannerFixtures {
    static UUID id(String label){return UUID.nameUUIDFromBytes(("fixture:"+label).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    static RecipeChoice.ItemKey item(String path){return new RecipeChoice.ItemKey("minecraft:"+path,"{}");}
    static final RecipeChoice.ItemKey LOG=item("oak_log"),PLANK=item("oak_planks"),BIRCH=item("birch_planks"),STICK=item("stick"),TABLE=item("crafting_table"),AXE=item("wooden_axe");
    static final String DIM="minecraft:overworld";
    UUID world=id("world"),root=id("root"),ownerId=id("owner");
    final ActorRef actor=new ActorRef(id("citizen"),id("entity"),DIM);
    final ContainerRef source=new ContainerRef(DIM,1,64,0),originalDestination=new ContainerRef(DIM,3,64,0);
    final FacilityRef tableSite=new FacilityRef(DIM,2,64,2);
    final Cuboid area=new Cuboid(DIM,4,64,0,10,64,0);
    TrustedContext context=new TrustedContext(new PrincipalRef(id("principal")),new ScopeRef(world,id("domain")));
    Owner owner=new Owner(ownerId,actor,0,true,false,false,false);
    Mode mode=Mode.MAKE_NEW;RecipeChoice.ItemKey output=AXE;long demand=1,credit=0,now=1000,revision=1,worldRevision=1,returnsUsed=0,carry=64;
    ContainerRef destination=originalDestination;boolean provision=true,returns=false,complete=true,online=false;long returnLimit=0;
    List<Stock> stock=new ArrayList<>();List<Acquisition> acquisition=new ArrayList<>();List<RecipeProfile> recipes=new ArrayList<>();List<ChoiceOffer> choices=new ArrayList<>();List<SettledProgress> progress=new ArrayList<>();
    Set<Kind> kinds=EnumSet.allOf(Kind.class);Set<ActorRef> executors=new HashSet<>();Set<ContainerRef> sources=new HashSet<>(),destinations=new HashSet<>();
    Budget budget;Facility facility;
    PlannerFixtures(){executors.add(actor);sources.add(source);destinations.add(originalDestination);destinations.add(source);budget=budget(10000,10000,10000,4,4,2,100000);
        recipes.add(profile(OakPlankProduction.ID,List.of(List.of(LOG)),PLANK));recipes.add(profile(StickProduction.ID,List.of(List.of(PLANK,BIRCH),List.of(PLANK,BIRCH)),STICK));
        recipes.add(profile(CraftingTableProduction.ID,Collections.nCopies(4,List.of(PLANK,BIRCH)),TABLE));recipes.add(profile(WoodenAxeProduction.ID,List.of(List.of(PLANK,BIRCH),List.of(PLANK,BIRCH),List.of(PLANK,BIRCH),List.of(STICK),List.of(STICK)),AXE));
        var method=new MethodPin(CraftingTablePlacement.ID,RecipeTransaction.fingerprint("fixture:placement-method"),RecipeTransaction.fingerprint("fixture:placement-native"),true);facility=new Facility(tableSite,Availability.ABSENT,true,true,"",true,1,method,new Cost(2,2,10));}
    static Budget budget(long travel,long effects,long ticks,long retry,long replan,long model,long deadline){return new Budget(Map.of(Counter.TRAVEL,travel,Counter.EFFECTS,effects,Counter.ELAPSED_TICKS,ticks,Counter.RETRIES,retry,Counter.REPLANS,replan,Counter.MODEL_CALLS,model),zeroCounts(),deadline);}
    RecipeProfile profile(CapabilityId legacy,List<List<RecipeChoice.ItemKey>> cells,RecipeChoice.ItemKey result){var capability=new CapabilityId(legacy.name(),2);String semantic=RecipeTransaction.fingerprint("fixture:native:"+legacy.name());return new RecipeProfile(RecipeProduction.of(legacy).recipe(),capability,cells,result,semantic,new MethodPin(capability,RecipeTransaction.fingerprint("fixture:method:"+legacy.name()),semantic,true),new Cost(2,3,10));}
    Stock stock(String key,int slot,RecipeChoice.ItemKey item,long count){return new Stock("fixture:"+key,new Location(source,null,slot),item,count,count,0,null,List.of(),Eligibility.NONE,Availability.PRESENT,true,true,2,1);}
    Stock actorStock(String key,int slot,RecipeChoice.ItemKey item,long count){return new Stock("fixture:"+key,new Location(null,actor,slot),item,count,count,0,null,List.of(),Eligibility.NONE,Availability.PRESENT,true,true,2,1);}
    PlannerFixtures logs(int n){stock.add(stock("logs",0,LOG,n));return this;}
    PlannerFixtures goal(Mode m,RecipeChoice.ItemKey item,long q){mode=m;output=item;demand=q;return this;}
    PlannerFixtures existingTable(){facility=new Facility(tableSite,Availability.PRESENT,true,true,"fixture:current-table",false,1,facility.placement(),facility.placementAllowance());return this;}
    PlannerFixtures logSource(int n){var method=new MethodPin(OakLogDelivery.ID,RecipeTransaction.fingerprint("fixture:log-method"),RecipeTransaction.fingerprint("fixture:log-native"),true);acquisition.add(new Acquisition("fixture:tree",area,LOG,n,Availability.PRESENT,true,true,method,new Cost(2,3,10)));return this;}
    PlannerFixtures credit(long q){credit=q;progress.add(new SettledProgress(root,"fixture:prior-credit","fixture:step-credit",id("attempt-credit"),"fixture:effect-credit",List.of("fixture:receipt-credit"),List.of("fixture:metadata-credit"),SettlementKind.ACCEPTED_EFFECT,revision,0,q));return this;}
    Snapshot snapshot(){var policy=new Policy(actor,executors,sources,destinations,Set.of(area),source,tableSite,provision,returns,returnLimit,carry);var goal=new Goal(mode,output,demand,originalDestination,policy);return new Snapshot(SCHEMA,world,root,revision,context,owner,goal,destination,credit,budget,now,worldRevision,stock,acquisition,recipes,facility,choices,kinds,progress,complete,online,returnsUsed);}
    Decision withChoice(Decision missing){var proposed=PlanningChoiceBuilder.build(snapshot(),Objects.requireNonNull(missing.neededChoice()));var bound=new ValidatedRequest(proposed.invocation(),context,new ObservationRef(id("native-observation"),revision,DIM));choices.add(new ChoiceOffer(bound,proposed.choice(),proposed.sources()));return new PlanningCoordinator().next(snapshot(),missing.analysis().plan(),Memory.empty());}
    void settle(ValidatedPlan plan,int from,int to,long confirmed){revision++;credit=confirmed;for(int i=from;i<to;i++)progress.add(new SettledProgress(root,plan.actions().get(i).token(i),"fixture:step-"+i,id("attempt-"+i),"fixture:effect-"+i,List.of("fixture:receipt-"+i),List.of("fixture:metadata-"+i),SettlementKind.ACCEPTED_EFFECT,revision,0,confirmed));}
}
