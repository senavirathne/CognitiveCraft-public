package dev.aivillages.core.kernel;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Prerequisites.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static org.junit.jupiter.api.Assertions.*;

final class ResourceCompositionTest {
    static final String LOG="minecraft:oak_log",PLANK="minecraft:oak_planks",STICK="minecraft:stick",AXE="minecraft:wooden_axe";
    static final class Fixture {
        final UUID world=UUID.randomUUID(),id=UUID.randomUUID();
        final ActorRef actor=JobLifecycleStoreTest.actor();
        final TrustedContext owner=JobLifecycleStoreTest.owner(world);
        final ContainerRef source=new ContainerRef(actor.dimension(),1,2,3),destination=new ContainerRef(actor.dimension(),4,2,3);
        final Cuboid oak=new Cuboid(actor.dimension(),5,2,3,5,3,3);
        final ObservationRef ref=new ObservationRef(UUID.randomUUID(),100,actor.dimension());
        final Map<String,Long> stock=new HashMap<>();
        final CapabilityRequest request;
        final Need need;
        int methodCalls,inspectionWork=1;final Map<String,Integer> inspections=new HashMap<>();long tick=100;boolean allowed=true;Reason stockReason,methodReason;
        ObservationStatus stockStatus;Catalog catalog=catalog();
        final Clock clock=Clock.fixed(Instant.ofEpochMilli(1000),ZoneOffset.UTC);
        Fixture(int quantity) {
            request=new CapabilityRequest(WoodenAxeProduction.ID,Map.of("actor",new ActorValue(actor),"amount",new IntValue(quantity),
                    "source",new ContainerValue(source),"destination",new ContainerValue(destination),"table",new FacilityValue(new FacilityRef(actor.dimension(),3,2,3))));
            need=new Need(id,new Jobs.Guard(2,0),new ValidatedRequest(request,owner,ref),AXE,quantity,AllocationPolicy.MATERIAL_ONLY,oak);
        }
        static Catalog catalog() {
            var recipes=new ArrayList<Recipe>();var productions=new ArrayList<Production>();productions.add(ResourceComposition.oakAcquisition());
            for(var cap:List.of(OakPlankProduction.ID,StickProduction.ID,WoodenAxeProduction.ID)) {
                var p=RecipeProduction.of(cap).relationship();productions.add(p);recipes.add(new Recipe(p.recipe(),p.output(),p.outputPerBatch(),p.inputs(),Map.of()));
            }
            return new Catalog(1,recipes,productions,Set.of(),true);
        }
        Supply inspect(Need n,String item) {inspections.merge(item,1,Integer::sum);long count=item.equals(AXE)?0:stock.getOrDefault(item,0L);
            return new Supply(item,stockStatus!=null?stockStatus:count==0?ObservationStatus.ABSENT:ObservationStatus.PRESENT,count,
                    "same-physical-source:"+item,new ObservationRef(UUID.randomUUID(),tick,actor.dimension()),tick,inspectionWork,stockReason);}
        MethodSelection method(Production p,long batches,long output,Need n) {
            methodCalls++;var observation=new ObservationRef(UUID.randomUUID(),tick,actor.dimension());
            var seen=new ObservationSnapshot(observation,ObservationStatus.PRESENT,"actual-fixture",tick,1,Map.of());
            var ref=new ArtifactRef(p.capability(),"a".repeat(64));
            var status=methodReason==null?ResolutionStatus.RESOLVED:ResolutionStatus.BLOCKED;
            return new MethodSelection(new CapabilityResolver.Decision(new Resolution(status,methodReason,methodReason==null?ref:null,"known"),
                    methodReason==null?new ValidatedRequest(ResourceComposition.project(n,p.capability(),output),owner,observation):null,
                    Set.of(),seen,1,List.of(),1,1,true,false,null),1);
        }
        Proposal resolve() {return resolve(Limits.defaults());}
        Proposal resolve(Limits limits) {
            return new DeterministicPrerequisiteResolver(limits,()->tick).resolve(List.of(need),catalog,this::inspect,this::method,n->allowed,
                    new Budgets.Ledger(ResourceComposition.allowance(clock.millis()),clock));
        }
        long produced(Proposal p,String item) {return p.nodes().stream().filter(n->n.item().equals(item)).mapToLong(Node::output).sum();}
    }
    @ParameterizedTest
    @CsvSource({"0,3,2,0,0,0","0,5,0,0,0,4","1,2,0,0,4,4","2,0,0,0,8,4","0,0,0,2,8,4","2,0,2,0,4,0","0,0,0,2,8,4"})
    void goldenFixturesNetSharedInputsAndProduceOnlyNecessaryBatches(long logs,long planks,long sticks,long newLogs,long newPlanks,long newSticks) {
        var f=new Fixture(1);f.stock.putAll(Map.of(LOG,logs,PLANK,planks,STICK,sticks,AXE,99L));var p=f.resolve();
        assertTrue(p.accepted(),p.reason()+" "+p.qualification()+" "+p.metrics());
        assertEquals(newLogs,f.produced(p,LOG));assertEquals(newPlanks,f.produced(p,PLANK));assertEquals(newSticks,f.produced(p,STICK));assertEquals(1,f.produced(p,AXE));
        assertEquals(1,p.nodes().stream().filter(n->n.id().equals(f.id)).count());
        assertFalse(p.allocations().stream().anyMatch(a->a.producer().equals(f.id)));
        for(var node:p.nodes())assertEquals(ResourceComposition.project(f.need,node.production().capability(),node.output()),node.request().request());
        assertEquals(p.nodes().size(),p.nodes().stream().map(Node::method).distinct().count());
    }
    @Test void coalescedMAKEInspectsEachNativeContainerItemOncePerExpansion(){var f=new Fixture(4);f.inspectionWork=28;var p=f.resolve();assertTrue(p.accepted(),p.reason()+" "+p.metrics());assertEquals(1,f.inspections.get(PLANK));assertEquals(1,f.inspections.get(LOG));assertEquals(1,f.inspections.get(STICK));}
    @Test void quantityFourHasOneSharedPlankProducerAndFullIntermediateDeposits() {
        var f=new Fixture(4);var p=f.resolve();assertTrue(p.accepted(),p.reason()+" "+p.metrics());
        assertEquals(4,f.produced(p,LOG));assertEquals(16,f.produced(p,PLANK));assertEquals(8,f.produced(p,STICK));assertEquals(4,f.produced(p,AXE));
        assertEquals(16,p.allocations().stream().filter(a->p.nodes().stream().anyMatch(n->n.id().equals(a.producer())&&n.item().equals(PLANK))).mapToLong(AllocationIntent::quantity).sum());
    }
    @Test void oldAxesCannotSatisfyMake() {var f=new Fixture(1);f.stock.put(AXE,64L);assertEquals(1,f.produced(f.resolve(),AXE));}
    @Test void rootInclusiveDepthFourPassesAndThreeRefuses() {var f=new Fixture(1);assertTrue(f.resolve().accepted());var l=Limits.defaults();
        assertEquals(Reason.BUDGET_EXHAUSTED,f.resolve(new Limits(l.demands(),l.nodes(),3,l.edges(),l.alternatives(),l.work(),l.contextBytes(),l.quantity(),l.observationAge())).reason());}
    @Test void unknownStockIsNotZeroAndCallsNoMethods() {var f=new Fixture(1);f.stockStatus=ObservationStatus.UNKNOWN;assertEquals(Reason.STALE_OBSERVATION,f.resolve().reason());assertEquals(0,f.methodCalls);}
    @Test void deniedStockIsAuthorityFailure() {var f=new Fixture(1);f.stockReason=Reason.AUTHORITY_DENIED;assertEquals(ResolutionStatus.UNAUTHORIZED,f.resolve().status());}
    @Test void changedMethodRejectsWholeGraph() {var f=new Fixture(1);f.methodReason=Reason.KNOWLEDGE_REQUIRED;var p=f.resolve();assertFalse(p.accepted());assertTrue(p.nodes().isEmpty());}
    @Test void deniedOriginCannotReadPrivateStock() {var f=new Fixture(1);f.allowed=false;assertEquals(ResolutionStatus.UNAUTHORIZED,f.resolve().status());assertEquals(0,f.methodCalls);}
    @Test void identicalProposalsKeepRootAndChildIdentities() {var f=new Fixture(1);var a=f.resolve();var b=f.resolve();assertEquals(a.id(),b.id());assertEquals(a.nodes().stream().map(Node::id).toList(),b.nodes().stream().map(Node::id).toList());}
    @Test void absentAdditiveSourcePreservesLegacyPrerequisiteBindingIdentity(){var f=new DeterministicPrerequisiteResolverTest.Fixture();var n=f.need("minecraft:wheat",2);var args=new TreeMap<>(n.bound().request().arguments());args.remove("amount");assertEquals(n.origin()+":"+n.bound().request().capability()+":"+args+":"+n.allocation(),Prerequisites.bindingKey(n));}
    @Test void resourceProjectionRejectsUnregisteredProducer() {var f=new Fixture(1);assertThrows(IllegalArgumentException.class,()->ResourceComposition.project(f.need,CropDelivery.ID,1));}
    @Test void acquisitionSourceHasExplicitDimensionAndCellBound() {var f=new Fixture(1);assertThrows(IllegalArgumentException.class,()->new Need(f.id,f.need.guard(),f.need.bound(),AXE,1,AllocationPolicy.MATERIAL_ONLY,new Cuboid(f.actor.dimension(),0,0,0,256,0,0)));}
    @Test void rootBudgetIsFiniteAndNoInferenceAllowanceExists() {var b=ResourceComposition.allowance(1000);assertEquals(7200,b.maximum(Budgets.Kind.ELAPSED_TICKS));assertEquals(0,b.maximum(Budgets.Kind.INPUT_BYTES));assertEquals(601000,b.deadlineEpochMillis());assertThrows(ArithmeticException.class,()->ResourceComposition.allowance(Long.MAX_VALUE));}
}
