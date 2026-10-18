package dev.aivillages.core.kernel;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static org.junit.jupiter.api.Assertions.*;

final class BlueprintMaterialsTest {
    static final String DIM="minecraft:overworld",PLANK="minecraft:oak_planks";
    static final ActorRef ACTOR=JobLifecycleStoreTest.actor();
    static final ContainerRef BOX=new ContainerRef(DIM,4,2,4);
    static Blueprint.Body planks(int n) {
        var cells=new ArrayList<Blueprint.Cell>();for(int i=0;i<n;i++)cells.add(new Blueprint.Cell(i%8,0,i/8,0));
        return new Blueprint.Body(new Blueprint.Dimensions(8,1,Math.max(1,(n+7)/8)),new Blueprint.Constraints(true,true,0),
                List.of(new Blueprint.State(PLANK,Map.of())),cells);
    }
    static BlueprintStore.Inspection inspection(Blueprint.Body body) {
        var result=BlueprintValidator.validate(body.canonical(),BlueprintFixture.registry(),BlueprintValidator.Limits.defaults(),()->false);
        assertTrue(result.valid(),result.reason());return new BlueprintStore.Inspection(BlueprintStore.Status.RESOLVED,result.body().ref(),result.body(),result.materials(),List.of(),List.of(),null);
    }
    static CapabilityRequest request(Blueprint.Ref ref,long amount) {
        return new CapabilityRequest(BlueprintMaterials.ID,Map.of("actor",new ActorValue(ACTOR),"blueprint",new BlueprintValue(ref),
                "origin",new FacilityValue(new FacilityRef(DIM,0,0,0)),"rotation",new IntValue(0),
                "site",new AreaValue(new Cuboid(DIM,0,0,0,7,0,7)),"source",new ContainerValue(BOX),"destination",new ContainerValue(BOX),
                "acquisition",new AreaValue(new Cuboid(DIM,10,1,10,17,1,10)),"amount",new IntValue(amount)));
    }
    static BlueprintMaterials.Report report(BlueprintStore.Inspection i,List<Blueprint.State> actual,long stock) {
        return BlueprintMaterials.project(i,new Blueprint.SiteBinding(i.ref(),DIM,0,0,0,0),
                new BlueprintMaterials.Site(ObservationStatus.PRESENT,actual,100,actual.size(),null),
                new BlueprintMaterials.Stock(stock==0?ObservationStatus.ABSENT:ObservationStatus.PRESENT,stock,"actual-box",100,28,null),BOX,BOX);
    }
    static List<Blueprint.State> air(BlueprintStore.Inspection i){return Collections.nCopies(i.body().cells().size(),new Blueprint.State("minecraft:air",Map.of()));}
    @ParameterizedTest @CsvSource({"1,0,1","5,2,3","64,64,0","64,8192,0"})
    void presentStockReducesSupplyWithoutChangingAuthoritativeBill(int bill,long stock,long deficit) {
        var i=inspection(planks(bill));var r=report(i,air(i),stock);assertTrue(r.canStage());assertEquals(bill,r.full().get(PLANK));
        assertEquals(bill,r.required());assertEquals(deficit,r.shortage());assertEquals(bill-deficit,r.available());
    }
    @Test void matchingCurrentCellsReduceDemandOnce(){var i=inspection(planks(5));var cells=new ArrayList<>(air(i));cells.set(0,i.body().palette().getFirst());cells.set(1,i.body().palette().getFirst());var r=report(i,cells,1);assertEquals(2,r.correct().get(PLANK));assertEquals(3,r.required());assertEquals(2,r.shortage());}
    @Test void allCorrectNeedsZeroMaterials(){var i=inspection(planks(1));var r=report(i,List.of(i.body().palette().getFirst()),0);assertTrue(r.canStage());assertEquals(0,r.required());}
    @Test void removedMatchingBlockRestoresOutstandingDemand(){var i=inspection(planks(1));assertEquals(0,report(i,List.of(i.body().palette().getFirst()),0).required());assertEquals(1,report(i,air(i),0).required());}
    @Test void movedStockAndOldReceiptsDoNotRecreateAvailability(){var i=inspection(planks(5));assertEquals(0,report(i,air(i),5).shortage());assertEquals(5,report(i,air(i),0).shortage());}
    @Test void aboveStagingBillCapIsExplicitAndNotSilentlySplit(){var i=inspection(planks(65));var r=report(i,air(i),65);assertFalse(r.canStage());assertTrue(r.blockers().contains("bounded-bill:64"));}
    @ParameterizedTest @ValueSource(ints={0,1,2,3})
    void rotationPreservesOwnerBillAndCompleteDoorCountsOnce(int rotation) {
        var i=inspection(BlueprintFixture.compact());var view=BlueprintValidator.bind(i.body(),new Blueprint.SiteBinding(i.ref(),DIM,3,4,5,rotation));
        var actual=view.geometry().cells().stream().map(c->view.geometry().palette().get(c.palette())).toList();
        var p=BlueprintValidator.outstanding(view,i.materials(),actual);assertEquals(i.materials(),p.correct());assertEquals(0,p.outstanding().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(1,p.correct().get("minecraft:oak_door"));assertEquals(i.ref(),view.source());
    }
    @Test void transientOpenPoweredDoorStateDoesNotDemandAnotherItem(){var i=inspection(BlueprintFixture.compact());var cells=i.body().cells().stream().map(c->{var s=i.body().palette().get(c.palette());if(!s.block().equals("minecraft:oak_door"))return s;var p=new HashMap<>(s.properties());p.put("open","true");p.put("powered","true");return new Blueprint.State(s.block(),p);}).toList();assertEquals(1,report(i,cells,0).correct().get("minecraft:oak_door"));}
    @Test void partialDoorIsQualifiedAndReceivesNoHalfItemCredit(){var i=inspection(BlueprintFixture.compact());var cells=new ArrayList<>(air(i));for(int n=0;n<i.body().cells().size();n++){var s=i.body().palette().get(i.body().cells().get(n).palette());if("lower".equals(s.properties().get("half")))cells.set(n,s);}var r=report(i,cells,0);assertEquals(1,r.partialStructures());assertEquals(1,r.outstanding().get("minecraft:oak_door"));assertFalse(r.correct().containsKey("minecraft:oak_door"));}
    @Test void mixedCompactFixtureExplicitlyBlocksEveryMissingProducer(){var i=inspection(BlueprintFixture.compact());var r=report(i,air(i),3);assertFalse(r.canStage());assertEquals(Set.of("minecraft:oak_slab","minecraft:oak_door","minecraft:torch"),r.unsupported());assertEquals(3,r.blockers().size());}
    @Test void doubleSlabUsesTwoItemsAndPartialSingleSlabGetsNoCredit(){var body=new Blueprint.Body(new Blueprint.Dimensions(1,1,1),new Blueprint.Constraints(true,true,0),List.of(new Blueprint.State("minecraft:oak_slab",Map.of("type","double"))),List.of(new Blueprint.Cell(0,0,0,0)));var i=inspection(body);assertEquals(2,i.materials().get("minecraft:oak_slab"));assertEquals(2,report(i,List.of(new Blueprint.State("minecraft:oak_slab",Map.of("type","bottom","waterlogged","false"))),0).outstanding().get("minecraft:oak_slab"));}
    @ParameterizedTest @EnumSource(value=Reason.class,names={"AUTHORITY_DENIED","TARGET_UNAVAILABLE","STALE_OBSERVATION"})
    void deniedUnloadedAndUnknownSiteCannotBecomeAnOutstandingZero(Reason why){var i=inspection(planks(1));var r=BlueprintMaterials.project(i,BlueprintMaterials.binding(request(i.ref(),1)),new BlueprintMaterials.Site(ObservationStatus.UNKNOWN,List.of(),100,1,why),new BlueprintMaterials.Stock(ObservationStatus.PRESENT,64,"box",100,28,null),BOX,BOX);assertTrue(r.unknown());assertTrue(r.outstanding().isEmpty());assertTrue(r.shortfalls().isEmpty());assertFalse(r.canStage());}
    @Test void unknownStockIsDistinctFromKnownEmpty(){var i=inspection(planks(1));var r=BlueprintMaterials.project(i,BlueprintMaterials.binding(request(i.ref(),1)),new BlueprintMaterials.Site(ObservationStatus.PRESENT,air(i),100,1,null),new BlueprintMaterials.Stock(ObservationStatus.UNKNOWN,0,"unloaded",100,1,Reason.TARGET_UNAVAILABLE),BOX,BOX);assertTrue(r.unknown());assertTrue(r.shortfalls().isEmpty());assertEquals(1,report(i,air(i),0).shortage());}
    @Test void otherSourceReportsMissingTransferBeforeWork(){var i=inspection(planks(1));var r=BlueprintMaterials.project(i,BlueprintMaterials.binding(request(i.ref(),1)),new BlueprintMaterials.Site(ObservationStatus.PRESENT,air(i),100,1,null),new BlueprintMaterials.Stock(ObservationStatus.PRESENT,1,"box",100,28,null),new ContainerRef(DIM,9,2,3),BOX);assertFalse(r.canStage());assertTrue(r.blockers().contains("missing-transfer-capability"));}
    @ParameterizedTest @ValueSource(longs={-1,65,Long.MAX_VALUE})
    void invalidQuantityCannotBecomeOwnerDemand(long q){assertFalse(BlueprintMaterials.valid(request(inspection(planks(1)).ref(),q)));}
    @Test void siteCellCapAccepts256AndRejects272(){var r=request(inspection(planks(1)).ref(),1);var a=new HashMap<>(r.arguments());a.put("site",new AreaValue(new Cuboid(DIM,0,0,0,15,0,15)));assertTrue(BlueprintMaterials.valid(new CapabilityRequest(r.capability(),a)));a.put("site",new AreaValue(new Cuboid(DIM,0,0,0,15,0,16)));assertFalse(BlueprintMaterials.valid(new CapabilityRequest(r.capability(),a)));}
    @Test void invalidRotationDimensionAndCoordinateOverflowReject(){var r=request(inspection(planks(1)).ref(),1);var a=new HashMap<>(r.arguments());a.put("rotation",new IntValue(4));assertFalse(BlueprintMaterials.valid(new CapabilityRequest(r.capability(),a)));var eight=inspection(planks(8));assertThrows(ArithmeticException.class,()->BlueprintValidator.bind(eight.body(),new Blueprint.SiteBinding(eight.ref(),DIM,Integer.MAX_VALUE,0,0,0)));}
    @Test void typedRefRoundTripRetainsExactIdentityAndRejectsFutureSchema()throws Exception{var r=request(inspection(planks(5)).ref(),5);assertEquals(r,((RequestCodec.Valid)RequestCodec.decode(RequestCodec.encode(r))).request());assertInstanceOf(RequestCodec.Invalid.class,RequestCodec.decode(RequestCodec.encode(r).replace("\"schema\":1,\"sha256\"","\"schema\":2,\"sha256\"")));}
    @Test void fullHouseSpecificationAndActualImportAreSeparate(){assertEquals(Map.of(PLANK,224L,"minecraft:oak_slab",99L,"minecraft:oak_door",2L,"minecraft:torch",4L),BlueprintFixture.bill());var result=BlueprintValidator.validate(BlueprintFixture.timber().canonical(),BlueprintFixture.registry(),BlueprintValidator.Limits.defaults(),()->false);assertTrue(result.valid(),result.reason());assertEquals(BlueprintFixture.bill(),result.materials());assertEquals(495,result.body().dimensions().volume());}
    @ParameterizedTest @CsvSource({"1,0,1,1","5,1,1,1","5,2,0,1","64,16,0,1"})
    void exactShortageGraphKeepsRootRequestAndOnlyMissingInputs(int quantity,long logs,long acquired,int ignored){var f=new ResourceCompositionTest.Fixture(quantity,OakPlankProduction.ID);f.stock.put("minecraft:oak_log",logs);var p=f.resolve();assertTrue(p.accepted(),p.reason()+" "+p.metrics());assertEquals(quantity,f.produced(p,PLANK));assertEquals(acquired,f.produced(p,"minecraft:oak_log"));assertEquals(f.request,p.nodes().stream().filter(n->n.id().equals(f.id)).findFirst().orElseThrow().request().request());}
}
