package dev.aivillages.core.kernel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.ResourceLeases.*;
import static dev.aivillages.core.kernel.SurvivalGateway.*;
import static dev.aivillages.core.kernel.WorldReferenceResolver.*;
import static dev.aivillages.core.kernel.WorldReferenceDiscovery.*;
import static org.junit.jupiter.api.Assertions.*;

class BirchLeaseDiscoveryTest {
    @Test void sameBlockPositionsConflictEvenWhenSpeciesDescriptorsDiffer(){
        var f=new ResourceLeaseServiceTest.Fixture();var area=new Cuboid(ResourceLeaseServiceTest.DIM,1,2,3,2,2,3);
        f.one(f.alice(Resource.oakBlocks(f.world,area),1));
        var b=LogAcquisition.blocks(f.world,BirchLogDelivery.ID,area);assertEquals(Code.CONFLICT,f.bob(b,1).code());
    }
    @Test void actualContainerPoolsRemainSpeciesSpecific(){
        var f=new ResourceLeaseServiceTest.Fixture();var box=OakPlankTestSupport.SOURCE;
        var oak=RecipeProduction.of(OakPlankProduction.ID).stock(f.world,box);var birch=RecipeProduction.of(BirchPlankProduction.ID).stock(f.world,box);
        f.one(f.alice(oak,2));f.one(f.bob(birch,2));assertEquals(2,f.service.snapshot().leases().size());
        assertEquals(Code.CONFLICT,f.alice(birch,4).code());
    }
    @Test void witnessedBirchBlockConsumptionIsExactlyBoundAndDeduplicated(){
        var f=new ResourceLeaseServiceTest.Fixture();var area=OakLogTestSupport.SOURCE;var resource=LogAcquisition.blocks(f.world,BirchLogDelivery.ID,area);
        var ref=f.one(f.alice(resource,2));UUID operation=UUID.randomUUID(),run=f.owners.get(f.alice.job()).run();
        var bound=new ValidatedRequest(new CapabilityRequest(BirchLogDelivery.ID,Map.of("actor",new ActorValue(f.actorA),"amount",new IntValue(2),"source",new AreaValue(area),"destination",new ContainerValue(OakLogTestSupport.BOX))),f.a,new ObservationRef(UUID.randomUUID(),0,area.dimension()));
        var correlation=new RunCorrelation(run,new ArtifactRef(BirchLogDelivery.ID,"a".repeat(64)),null);
        assertEquals(Code.PENDING,f.service.consumedOakBlock(ref,bound,correlation,operation,f.a).code());f.ack();
        assertEquals(Code.VALID,f.service.consumedOakBlock(ref,bound,correlation,operation,f.a).code());
        assertEquals(1,f.service.snapshot().leases().getFirst().remaining());
        var oak=new ValidatedRequest(new CapabilityRequest(OakLogDelivery.ID,bound.request().arguments()),f.a,bound.observation());
        assertEquals(Code.REJECTED,f.service.consumedOakBlock(ref,oak,correlation,UUID.randomUUID(),f.a).code());
        assertEquals(1,f.service.snapshot().leases().getFirst().remaining());
    }
    @Test void revokedLeaseOwnerCannotDebitBirchBlocks(){
        var f=new ResourceLeaseServiceTest.Fixture();var area=OakLogTestSupport.SOURCE;var ref=f.one(f.alice(LogAcquisition.blocks(f.world,BirchLogDelivery.ID,area),1));
        f.control(f.alice,false,Outcomes.Reason.AUTHORITY_DENIED);assertFalse(f.service.validate(ref,f.a).usable());
    }
    @Test void realBirchIngredientReceiptDebitsOnlyItsPoolOnce(){
        var f=new ResourceLeaseServiceTest.Fixture();var resource=RecipeProduction.of(BirchPlankProduction.ID).stock(f.world,OakPlankTestSupport.SOURCE);
        var ref=f.one(f.alice(resource,2));var d=new OakPlankTestSupport.Driver(8,new OakPlankTestSupport.World(BirchPlankProduction.ID));d.produce();var original=d.receipts.getFirst();
        var run=f.owners.get(f.alice.job()).run();var bound=new ValidatedRequest(new CapabilityRequest(BirchPlankProduction.ID,Map.of("actor",new ActorValue(f.actorA),"amount",new IntValue(8),"source",new ContainerValue(OakPlankTestSupport.SOURCE),"destination",new ContainerValue(OakPlankTestSupport.TARGET))),f.a,new ObservationRef(UUID.randomUUID(),0,f.actorA.dimension()));
        var receipt=new ResourceDelivery.Receipt(original.receiptId(),f.a,run,original.operationId(),original.batchId(),f.actorA,original.source(),original.destination(),original.item(),original.stage(),original.quantity(),original.sourceBlock(),original.dropIds(),original.transformation());
        var correlation=new RunCorrelation(run,new ArtifactRef(BirchPlankProduction.ID,"a".repeat(64)),null);
        assertEquals(Code.PENDING,f.service.consumedIngredient(ref,bound,correlation,receipt,f.a).code());f.ack();
        assertEquals(Code.VALID,f.service.consumedIngredient(ref,bound,correlation,receipt,f.a).code());assertEquals(1,f.service.snapshot().leases().getFirst().remaining());
    }
    @Test void discoveryAndFinalValidationUseTheSameExactBirchIdentity(){
        var actor=OakLogTestSupport.ACTOR;boolean[] changed={false};
        WorldReferenceDiscovery.Observation world=new WorldReferenceDiscovery.Observation(){
            public ActorState actor(ActorRef a){return new ActorState(true,true,a.dimension(),0,0,0,0);}
            public CropState crop(ActorRef a,Point p){throw new AssertionError("Birch requested crop scan");}
            public ContainerSample container(ActorRef a,ContainerRef c){throw new AssertionError("Birch requested wheat containers");}
            public ItemBlock itemBlock(ActorRef a,Point p,ResourceDelivery.ItemIdentity item){assertEquals(ResourceDelivery.BIRCH_LOG,item);boolean eligible=!changed[0]&&p.y()==0&&p.z()==0&&(p.x()==1||p.x()==2);return new ItemBlock(eligible?ObservationStatus.PRESENT:ObservationStatus.ABSENT,eligible,"cell:"+p,eligible?60:0);}
        };
        var discovery=WorldReferenceDiscovery.sources(BirchLogDelivery.ID,actor,2,new Point(actor.dimension(),0,0,0),world);Scan<SourceArea> result=null;
        for(int i=0;i<256;i++){result=discovery.poll(256);if(result.complete())break;}
        assertNotNull(result);assertTrue(result.complete());assertFalse(result.candidates().isEmpty());var source=result.candidates().getFirst().value();assertEquals(ResourceDelivery.BIRCH_LOG,source.item());
        assertNull(new SourceValidation(source,actor,world).poll(256).reason());changed[0]=true;
        assertEquals(Outcomes.Reason.STALE_OBSERVATION,new SourceValidation(source,actor,world).poll(256).reason());
    }
}
