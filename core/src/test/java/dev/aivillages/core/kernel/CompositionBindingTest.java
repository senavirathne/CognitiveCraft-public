package dev.aivillages.core.kernel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.WorldReferenceResolver.*;
import static dev.aivillages.core.kernel.WorldReferenceDiscovery.*;
import static org.junit.jupiter.api.Assertions.*;

final class CompositionBindingTest {
    List<Candidate<InputContainerTarget>> scan(Map<Integer,Long> stock,boolean allowShortfall) {
        var actor=AxeTestSupport.ACTOR;var anchor=new Point(actor.dimension(),0,0,0);
        var observation=new Observation() {
            public SurvivalGateway.ActorState actor(ActorRef a){throw new AssertionError("Unused");}
            public SurvivalGateway.CropState crop(ActorRef a,Point p){throw new AssertionError("Unused");}
            public ContainerSample container(ActorRef a,ContainerRef p){throw new AssertionError("Unused");}
            public InputContainerSample inputContainer(ActorRef a,ContainerRef p,ResourceDelivery.ItemIdentity item){
                return p.y()==0&&p.z()==0&&stock.containsKey(p.x()) ? new InputContainerSample(ObservationStatus.PRESENT,stock.get(p.x()),1,"container:"+p.x())
                        : new InputContainerSample(ObservationStatus.ABSENT,0,0,"not-container");
            }
        };
        var discovery=inputContainers(actor,3,anchor,observation,ResourceDelivery.OAK_PLANKS,allowShortfall);
        Scan<InputContainerTarget> result=null;
        for(int i=0;i<100;i++){result=discovery.poll(256);if(result.complete())break;}
        assertNotNull(result);assertTrue(result.complete());return result.candidates();
    }
    @Test void sufficientStockRemainsPreferredOverCloserEmptyContainer(){var result=scan(Map.of(1,0L,3,3L),true);assertEquals(1,result.size());assertEquals(3,result.getFirst().value().container().x());assertEquals(3,result.getFirst().value().evidence().available());}
    @Test void partialStockRetainsHonestCountsWhenNoSufficientCandidateExists(){var result=scan(Map.of(1,1L,3,2L),true);assertEquals(List.of(1L,2L),result.stream().map(c->c.value().evidence().available()).sorted().toList());}
    @Test void originalDiscoveryStillRefusesInsufficientInput(){assertTrue(scan(Map.of(1,0L,3,2L),false).isEmpty());}
}
