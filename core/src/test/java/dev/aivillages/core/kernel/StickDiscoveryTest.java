package dev.aivillages.core.kernel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.SurvivalGateway.*;
import static dev.aivillages.core.kernel.WorldReferenceDiscovery.*;
import static dev.aivillages.core.kernel.WorldReferenceResolver.*;
import static dev.aivillages.core.kernel.StickTestSupport.ACTOR;
import static org.junit.jupiter.api.Assertions.*;
class StickDiscoveryTest {
    static final class World implements Observation {
        final Map<Integer,Long> stock=new HashMap<>();boolean unknown;int output=8;
        public ActorState actor(ActorRef a){return new ActorState(true,true,a.dimension(),0,0,0,0);}
        public CropState crop(ActorRef a,Point p){return new CropState(ObservationStatus.ABSENT,false,"crop");}
        public ContainerSample container(ActorRef a,ContainerRef c){return new ContainerSample(ObservationStatus.ABSENT,0,0,"wheat");}
        public InputContainerSample inputContainer(ActorRef a,ContainerRef c,ResourceDelivery.ItemIdentity item){
            assertEquals(ResourceDelivery.OAK_PLANKS,item);
            boolean present=c.y()==0 && c.z()==0 && stock.containsKey(c.x());
            return new InputContainerSample(unknown && c.x()==16 ? ObservationStatus.UNKNOWN : present ? ObservationStatus.PRESENT : ObservationStatus.ABSENT,
                    present ? stock.get(c.x()) : 0,present ? 27 : 0,"stock:"+c.x());
        }
        public ItemContainerSample itemContainer(ActorRef a,ContainerRef c,ResourceDelivery.ItemIdentity item){
            boolean present=c.y()==0 && c.z()==0 && c.x()==4;
            return new ItemContainerSample(item,present ? ObservationStatus.PRESENT : ObservationStatus.ABSENT,present ? output : 0,present ? 27 : 0,"output");
        }
    }
    static <T> Scan<T> complete(Discovery<T> d){return OakLogDiscoveryTest.complete(d);}
    static Point anchor(){return new Point(ACTOR.dimension(),0,0,0);}
    @Test void suppliedInputSelectionUsesCeilingBatchCount(){
        var w=new World();w.stock.put(2,1L);w.stock.put(5,4L);
        var result=complete(inputContainers(ACTOR,StickProduction.inputs(5),anchor(),w,ResourceDelivery.OAK_PLANKS));
        assertEquals(1,result.candidates().size());assertEquals(5,result.candidates().getFirst().value().container().x());
    }
    @Test void incompleteEnvelopeCannotInventNearestInput(){
        var w=new World();w.stock.put(2,4L);w.unknown=true;
        assertEquals(Outcomes.Reason.STALE_OBSERVATION,complete(inputContainers(ACTOR,4,anchor(),w,ResourceDelivery.OAK_PLANKS)).emptyReason());
    }
    @Test void absentInputIsExactResourceMissingRatherThanGather(){
        assertEquals(Outcomes.Reason.RESOURCE_MISSING,complete(inputContainers(ACTOR,4,anchor(),new World(),ResourceDelivery.OAK_PLANKS)).emptyReason());
    }
    @Test void outputDiscoveryUsesPlanksCapacityAndType(){
        var w=new World();var r=complete(itemContainers(StickProduction.ID,ACTOR,8,anchor(),w));
        assertEquals(1,r.candidates().size());assertEquals(ResourceDelivery.STICK,r.candidates().getFirst().value().evidence().item());
        w.output=7;assertTrue(complete(itemContainers(StickProduction.ID,ACTOR,8,anchor(),w)).candidates().isEmpty());
    }
    @Test void oversizedInputEnvelopeIsRejected(){
        assertThrows(IllegalArgumentException.class,()->inputContainers(ACTOR,33,anchor(),new World(),ResourceDelivery.OAK_PLANKS));
        assertThrows(IllegalArgumentException.class,()->new InputContainerSample(ObservationStatus.PRESENT,8193,27,"box"));
    }
}
