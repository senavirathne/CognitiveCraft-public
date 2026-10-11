package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.time.*;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static dev.aivillages.core.kernel.Prerequisites.*;
import static org.junit.jupiter.api.Assertions.*;

/** Every IMP-018 matrix row: disposable expansion plus actual Jobs/Leases admission and credit. */
final class DeterministicPrerequisiteResolverTest {
    static final String WHEAT="minecraft:wheat",MATURE="minecraft:mature_wheat";
    static final ArtifactRef REF=JobLifecycleStoreTest.REF;
    static final class Fixture {
        final UUID world=UUID.randomUUID();
        final TrustedContext owner=JobLifecycleStoreTest.owner(world);
        final ActorRef actor=JobLifecycleStoreTest.actor();
        final JobLifecycleStoreTest.TestClock clock=new JobLifecycleStoreTest.TestClock();
        final Map<String,Long> counts=new HashMap<>(Map.of(MATURE,64L));
        Catalog catalog=new Catalog(1,List.of(),List.of(Production.cropDelivery()),Set.of(),true);
        Limits bounds=Limits.defaults();long tick=100;boolean allowed=true;
        int methodCalls,inspections;Reason reason;ObservationStatus status;
        final Supplies supplies=(need,item)->{
            inspections++;long quantity=counts.getOrDefault(item,0L);
            String target=item.equals(WHEAT)?need.bound().request().arguments().get("destination").toString():"same-source";
            return new Supply(item,status!=null?status:quantity==0?ObservationStatus.ABSENT:ObservationStatus.PRESENT,
                    quantity,item+":"+target,new ObservationRef(UUID.randomUUID(),0,actor.dimension()),tick,1,reason);
        };
        final Methods methods=(production,batches,output,need)->{
            methodCalls++;var args=new HashMap<>(need.bound().request().arguments());args.put("amount",new IntValue(Math.toIntExact(output)));
            var ref=new ObservationRef(UUID.randomUUID(),0,actor.dimension());
            var bound=new ValidatedRequest(new CapabilityRequest(production.capability(),args),need.origin(),ref);
            var obs=new ObservationSnapshot(ref,ObservationStatus.PRESENT,"fixture-source",tick,1,Map.of("mature_wheat",64L));
            return new MethodSelection(new CapabilityResolver.Decision(new Resolution(ResolutionStatus.RESOLVED,null,REF,"known"),bound,
                    Set.of(Effect.OBSERVE,Effect.HARVEST,Effect.PICKUP,Effect.TRANSFER),obs,1,List.of(),0,0,true,false,null),0);
        };
        Need need(String item,long quantity) {
            return new Need(UUID.randomUUID(),new Jobs.Guard(1,0),new ValidatedRequest(
                    JobLifecycleStoreTest.request(actor,(int)Math.min(64,quantity)),owner,JobLifecycleStoreTest.observation()),
                    item,quantity,AllocationPolicy.ATTRIBUTABLE_WHEAT);
        }
        Budgets.Ledger ledger(long instructions) {
            return new Budgets.Ledger(new Budgets.Limits(Map.of(Budgets.Kind.INSTRUCTIONS,instructions),11_000),clock);
        }
        Proposal resolve(Need... needs) { return resolve(ledger(4096),needs); }
        Proposal resolve(Budgets.Ledger ledger,Need... needs) {
            return new DeterministicPrerequisiteResolver(bounds,()->tick).resolve(List.of(needs),catalog,supplies,methods,n->allowed,ledger);
        }
    }
    static Production production(String output,List<Ingredient> inputs) {
        return new Production("fixture:"+output.substring(output.indexOf(':')+1),output,CropDelivery.ID,"",1,inputs);
    }
    static Ingredient ingredient(String item) {return new Ingredient(List.of(item),1);}
    static Need change(Need need,TrustedContext owner,Map<String,Value> arguments,AllocationPolicy policy) {
        return new Need(UUID.randomUUID(),need.guard(),new ValidatedRequest(new CapabilityRequest(CropDelivery.ID,arguments),owner,
                need.bound().observation()),need.item(),need.quantity(),policy);
    }
    static Limits bounds(int nodes,int depth,int edges,int alternatives,int work,int bytes,long quantity) {
        return new Limits(8,nodes,depth,edges,alternatives,work,bytes,quantity,10);
    }
    @ParameterizedTest @ValueSource(ints={1,8,64})
    void existingApprovedSupplyCreatesNoProducerOrCompletionCredit(int quantity) {
        var f=new Fixture();f.counts.put(WHEAT,(long)quantity);var p=f.resolve(f.need(WHEAT,quantity));
        assertTrue(p.accepted(),p.reason()+" "+p.metrics());assertTrue(p.nodes().isEmpty());assertTrue(p.allocations().isEmpty());
        assertEquals(quantity,p.stock().stream().mapToLong(StockUse::quantity).sum());assertEquals(0,f.methodCalls);
    }
    @Test void partiallyStockedRequestProducesOnlyTrueDeficit() {
        var f=new Fixture();f.counts.put(WHEAT,2L);var p=f.resolve(f.need(WHEAT,5));
        assertTrue(p.accepted(),p.reason()+" "+p.metrics());assertEquals(1,p.nodes().size());assertEquals(3,p.nodes().getFirst().output());
        assertEquals(3,p.allocations().getFirst().quantity());assertEquals(2,p.stock().getFirst().quantity());
    }
    @Test void installedRecipeBatchSurplusAndRemaindersAreExactAndNeverInventStock() {
        var f=new Fixture();f.counts.put(WHEAT,2L);f.counts.put("fixture:flour",6L);
        var recipe=new Recipe("fixture:bread",WHEAT,3,List.of(new Ingredient(List.of("fixture:flour"),2)),Map.of("fixture:bowl",1L));
        var production=new Production("fixture:bake",WHEAT,CropDelivery.ID,recipe.id(),3,List.of());
        f.catalog=new Catalog(1,List.of(recipe),List.of(production),Set.of(),true);var p=f.resolve(f.need(WHEAT,9));
        assertTrue(p.accepted(),p.reason()+" "+p.metrics());var node=p.nodes().getFirst();
        assertEquals(3,node.batches());assertEquals(9,node.output());assertEquals(2,node.surplus());assertEquals(Map.of("fixture:bowl",3L),node.remainders());
        assertEquals(7,p.allocations().getFirst().quantity());assertEquals(6,p.stock().stream().filter(s->s.proof().item().equals("fixture:flour")).mapToLong(StockUse::quantity).sum());
    }
    @ParameterizedTest @ValueSource(strings={"output","input","remainder"})
    void checkedArithmeticRejectsOverflowWithoutPartialProposals(String field) {
        var f=new Fixture();long output=field.equals("output")?Long.MAX_VALUE:1;
        var r=new Recipe("fixture:overflow",WHEAT,output,List.of(new Ingredient(List.of(MATURE),field.equals("input")?Long.MAX_VALUE:1)),
                Map.of("fixture:bowl",field.equals("remainder")?Long.MAX_VALUE:1L));
        f.catalog=new Catalog(1,List.of(r),List.of(new Production("fixture:produce",WHEAT,CropDelivery.ID,r.id(),output,List.of())),Set.of(),true);
        var p=f.resolve(f.need(WHEAT,2));assertFalse(p.accepted());assertTrue(p.nodes().isEmpty());assertTrue(p.allocations().isEmpty());
        assertTrue(p.reason()==Reason.REQUEST_INVALID || p.reason()==Reason.BUDGET_EXHAUSTED);
    }
    @ParameterizedTest @ValueSource(doubles={0,-1,1.5,Double.NaN,Double.POSITIVE_INFINITY})
    void fractionalAndNonIntegralQuantitiesHaveNoImplicitRounding(double quantity) {
        assertThrows(IllegalArgumentException.class,()->exactQuantity(quantity));
    }
    @Test void recipeDataAloneDoesNotCreatePhysicalSupportOrCallMethods() {
        var f=new Fixture();var recipe=new Recipe("minecraft:bread","minecraft:bread",1,List.of(new Ingredient(List.of(WHEAT),3)),Map.of());
        f.catalog=new Catalog(1,List.of(recipe),List.of(),Set.of("minecraft:bread"),true);
        var p=f.resolve(f.need("minecraft:bread",1));assertEquals(ResolutionStatus.UNSUPPORTED_RUNTIME,p.status());assertEquals(0,f.methodCalls);
    }
    @Test void missingOrdinarySupplyReturnsShortageWithoutPlanning() {
        var f=new Fixture();f.counts.put(MATURE,0L);var p=f.resolve(f.need(WHEAT,1));
        assertEquals(Reason.RESOURCE_MISSING,p.reason());assertEquals(0,f.methodCalls);assertTrue(p.nodes().isEmpty());
    }
    @Test void knownMethodGapDoesNotInvokeResearchOrReturnGenerativePlanning() {
        var f=new Fixture();var need=f.need(WHEAT,1);
        Methods gap=(production,batches,output,owner)->new MethodSelection(new CapabilityResolver.Decision(new Resolution(ResolutionStatus.MISSING_IMPLEMENTATION,null,null,"gap"),
                null,Set.of(),new ObservationSnapshot(owner.bound().observation(),ObservationStatus.PRESENT,"source",100,1,Map.of()),1,List.of(),1,1,true,false,null),0);
        var p=new DeterministicPrerequisiteResolver(f.bounds,()->100).resolve(List.of(need),f.catalog,f.supplies,gap,n->true,f.ledger(4096));
        assertEquals(ResolutionStatus.BLOCKED,p.status());assertEquals(Reason.RESOURCE_MISSING,p.reason());assertTrue(p.nodes().isEmpty());
    }
    @Test void twoNodeCycleTerminatesWithQualifiedBlockage() {
        var f=new Fixture();f.catalog=new Catalog(1,List.of(),List.of(production("fixture:a",List.of(ingredient("fixture:b"))),
                production("fixture:b",List.of(ingredient("fixture:a")))),Set.of(),true);
        var p=f.resolve(f.need("fixture:a",1));assertEquals(Qualification.CYCLE,p.qualification());assertTrue(p.nodes().isEmpty());assertEquals(2,p.metrics().nodes());
    }
    @ParameterizedTest @ValueSource(strings={"nodes","depth","edges","alternatives","work","bytes","quantity"})
    void exactBoundPassesAndOneBeyondStopsWithoutResettingWork(String bound) {
        var f=new Fixture();Need need=f.need(WHEAT,2);
        if(bound.equals("alternatives"))f.catalog=new Catalog(1,List.of(),List.of(Production.cropDelivery(),
                new Production("zz:alternative",WHEAT,CropDelivery.ID,"",1,List.of())),Set.of(),true);
        var first=f.resolve(need);assertTrue(first.accepted(),first.reason()+" "+first.metrics());
        int nodes=16,depth=4,edges=64,alternatives=4,work=512,bytes=16_384;long quantity=4096;
        switch(bound) {
            case "nodes" -> nodes=1;
            case "depth" -> depth=2;
            case "edges" -> edges=1;
            case "alternatives" -> alternatives=2;
            case "work" -> work=first.metrics().work();
            case "bytes" -> bytes=first.metrics().contextBytes();
            case "quantity" -> quantity=2;
        }
        f.bounds=bounds(nodes,depth,edges,alternatives,work,bytes,quantity);assertTrue(f.resolve(need).accepted(),bound);
        switch(bound) {
            case "nodes" -> {f.catalog=new Catalog(1,List.of(),List.of(Production.cropDelivery(),production(MATURE,List.of())),Set.of(),true);f.counts.put(MATURE,0L);}
            case "depth" -> depth=1;
            case "edges" -> {f.catalog=new Catalog(1,List.of(),List.of(new Production("fixture:edges",WHEAT,CropDelivery.ID,"",1,
                    List.of(ingredient(MATURE),ingredient("fixture:hoe")))),Set.of(),true);f.counts.put("fixture:hoe",1L);}
            case "alternatives" -> alternatives=1;
            case "work" -> work--;
            case "bytes" -> bytes--;
            case "quantity" -> need=f.need(WHEAT,3);
        }
        f.bounds=bounds(nodes,depth,edges,alternatives,work,bytes,quantity);var rejected=f.resolve(need);
        assertFalse(rejected.accepted(),bound);assertTrue(rejected.metrics().work()<=work);assertTrue(rejected.nodes().isEmpty());
    }
    @Test void alternativesRollbackStockAndBuildersButNeverRefundPerformedWork() {
        var f=new Fixture();f.counts.put("fixture:z",2L);
        f.catalog=new Catalog(1,List.of(),List.of(new Production("fixture:choice",WHEAT,CropDelivery.ID,"",1,
                List.of(new Ingredient(List.of("fixture:a","fixture:z"),1)))),Set.of(),true);
        var ledger=f.ledger(4096);var p=f.resolve(ledger,f.need(WHEAT,2));
        assertTrue(p.accepted(),p.reason()+" "+p.metrics());assertEquals(1,p.nodes().size());assertEquals(3,p.metrics().alternatives());
        assertEquals(p.metrics().work(),ledger.snapshot().getOrDefault(Budgets.Kind.INSTRUCTIONS,0L));assertFalse(p.stock().stream().anyMatch(s->s.proof().item().equals("fixture:a")));
    }
    @Test void compatibleDemandsShareOneProductionWithBoundedUnitIntents() {
        var f=new Fixture();f.counts.put(WHEAT,2L);var p=f.resolve(f.need(WHEAT,5),f.need(WHEAT,3));
        assertTrue(p.accepted(),p.reason()+" "+p.metrics());assertEquals(1,p.nodes().size());assertEquals(6,p.nodes().getFirst().output());
        assertEquals(6,p.allocations().stream().mapToLong(AllocationIntent::quantity).sum());assertEquals(2,p.allocations().size());
    }
    @ParameterizedTest @ValueSource(strings={"destination","source","actor","scope","principal","allocation"})
    void incompatibleBindingsNeverShareAProducer(String changed) {
        var f=new Fixture();var first=f.need(WHEAT,1);var arguments=new HashMap<>(first.bound().request().arguments());var owner=f.owner;
        var policy=AllocationPolicy.ATTRIBUTABLE_WHEAT;
        switch(changed) {
            case "destination" -> arguments.put(changed,new ContainerValue(new ContainerRef(f.actor.dimension(),4,0,4)));
            case "source" -> arguments.put(changed,new AreaValue(new Cuboid(f.actor.dimension(),10,0,0,12,0,2)));
            case "actor" -> arguments.put(changed,new ActorValue(JobLifecycleStoreTest.actor()));
            case "scope" -> owner=new TrustedContext(f.owner.principal(),new ScopeRef(f.world,UUID.randomUUID()));
            case "principal" -> owner=new TrustedContext(new PrincipalRef(UUID.randomUUID()),f.owner.scope());
            case "allocation" -> policy=AllocationPolicy.MATERIAL_ONLY;
        }
        var p=f.resolve(first,change(first,owner,arguments,policy));assertTrue(p.accepted(),p.reason()+" "+p.metrics());assertEquals(2,p.nodes().size());
    }
    @Test void incompatibleRequestsCannotSpendTheSamePhysicalStockTwice() {
        var f=new Fixture();f.counts.put(WHEAT,1L);var one=f.need(WHEAT,1);
        var other=change(one,f.owner,one.bound().request().arguments(),AllocationPolicy.MATERIAL_ONLY);
        var p=f.resolve(one,other);assertTrue(p.accepted(),p.reason()+" "+p.metrics());
        assertEquals(1,p.stock().stream().filter(s->s.proof().item().equals(WHEAT)).mapToLong(StockUse::quantity).sum());assertEquals(1,p.nodes().getFirst().output());
    }
    @ParameterizedTest @ValueSource(strings={"unknown","old","future","unloaded","denied","incomplete"})
    void staleOrUnknownSupplyIsNotReinterpretedAsZero(String mode) {
        var f=new Fixture();Need need=f.need(WHEAT,1);long now=100;
        switch(mode) {
            case "unknown" -> f.status=ObservationStatus.UNKNOWN;
            case "old" -> now=111;
            case "future" -> now=99;
            case "unloaded" -> f.reason=Reason.TARGET_UNAVAILABLE;
            case "denied" -> f.reason=Reason.AUTHORITY_DENIED;
            case "incomplete" -> {f.catalog=new Catalog(1,List.of(),List.of(),Set.of(),false);}
        }
        final long current=now;var p=new DeterministicPrerequisiteResolver(f.bounds,()->current).resolve(List.of(need),f.catalog,f.supplies,f.methods,n->true,f.ledger(4096));
        assertFalse(p.accepted());assertEquals(0,f.methodCalls);assertTrue(p.nodes().isEmpty());
        if(mode.equals("denied"))assertEquals(ResolutionStatus.UNAUTHORIZED,p.status());
    }
    @Test void repeatedExpansionSharesParentUsageAndCannotRenewDeadline() {
        var f=new Fixture();var need=f.need(WHEAT,1);var first=f.resolve(need);var ledger=f.ledger(first.metrics().work());
        assertTrue(f.resolve(ledger,need).accepted());assertEquals(Reason.BUDGET_EXHAUSTED,f.resolve(ledger,need).reason());
        f.clock.now=11_001;assertEquals(Reason.BUDGET_EXHAUSTED,f.resolve(f.ledger(4096),need).reason());
        assertEquals(0,ledger.snapshot().getOrDefault(Budgets.Kind.CALLS,0L));
    }
    @Test void authorityLossDuringRecursiveExpansionCannotFallBackToAnotherInput() {
        var f=new Fixture();var need=f.need(WHEAT,1);int[] checks={0};
        var p=new DeterministicPrerequisiteResolver(f.bounds,()->100).resolve(List.of(need),f.catalog,f.supplies,f.methods,n->++checks[0]<3,f.ledger(4096));
        assertEquals(ResolutionStatus.UNAUTHORIZED,p.status());assertEquals(0,f.methodCalls);
    }
    @Test void methodCannotChangeActorDestinationQuantityOrObservationBinding() {
        var f=new Fixture();var need=f.need(WHEAT,2);
        Methods changed=(production,batches,output,owner)->f.methods.resolve(production,batches,output+1,owner);
        var p=new DeterministicPrerequisiteResolver(f.bounds,()->100).resolve(List.of(need),f.catalog,f.supplies,changed,n->true,f.ledger(4096));
        assertEquals(Reason.STALE_OBSERVATION,p.reason());assertTrue(p.nodes().isEmpty());
    }
    @Test void diamondDependenciesReuseOneProducerAndAccountEachInternalUnitOnce() {
        var f=new Fixture();f.catalog=new Catalog(1,List.of(),List.of(production("fixture:a",List.of(ingredient("fixture:b"),ingredient("fixture:c"))),
                production("fixture:b",List.of(ingredient("fixture:d"))),production("fixture:c",List.of(ingredient("fixture:d"))),production("fixture:d",List.of())),Set.of(),true);
        var p=f.resolve(f.need("fixture:a",1));assertTrue(p.accepted(),p.reason()+" "+p.metrics());assertEquals(4,p.nodes().size());
        var d=p.nodes().stream().filter(n->n.item().equals("fixture:d")).findFirst().orElseThrow();assertEquals(2,d.output());
        assertEquals(2,p.allocations().stream().filter(a->a.producer().equals(d.id())).mapToLong(AllocationIntent::quantity).sum());
        assertEquals(2,p.nodes().stream().filter(n->n.dependencies().contains(d.id())).count());
    }
}
