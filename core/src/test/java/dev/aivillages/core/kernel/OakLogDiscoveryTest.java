package dev.aivillages.core.kernel;

import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.SurvivalGateway.*;
import static dev.aivillages.core.kernel.WorldReferenceDiscovery.*;
import static dev.aivillages.core.kernel.WorldReferenceResolver.*;
import static dev.aivillages.core.kernel.OakLogTestSupport.ACTOR;
import static org.junit.jupiter.api.Assertions.*;

class OakLogDiscoveryTest {
    static final class World implements Observation {
        final Set<Point> logs=new HashSet<>(); boolean unknown;
        public ActorState actor(ActorRef actor) { return new ActorState(true,true,actor.dimension(),0,0,0,0); }
        public CropState crop(ActorRef actor,Point p) { return new CropState(ObservationStatus.ABSENT,false,"crop"); }
        public ContainerSample container(ActorRef actor,ContainerRef c) { return new ContainerSample(ObservationStatus.ABSENT,0,0,"wheat"); }
        public ItemBlock itemBlock(ActorRef actor,Point p,ResourceDelivery.ItemIdentity item) {
            return new ItemBlock(unknown && p.x()==16 ? ObservationStatus.UNKNOWN : logs.contains(p) ? ObservationStatus.PRESENT : ObservationStatus.ABSENT,
                    logs.contains(p),p.toString(),60);
        }
        public ItemContainerSample itemContainer(ActorRef actor,ContainerRef c,ResourceDelivery.ItemIdentity item) {
            int capacity=c.x()==4 && c.y()==0 && c.z()==0 ? 2 : c.x()==6 && c.y()==0 && c.z()==0 ? 8 : 0;
            return new ItemContainerSample(item,capacity>0 ? ObservationStatus.PRESENT : ObservationStatus.ABSENT,capacity,capacity>0 ? 27 : 0,"box:"+c.x());
        }
    }
    static <T> Scan<T> complete(Discovery<T> d) {
        for(int i=0;i<500;i++) { var s=d.poll(WORK_PER_POLL);assertTrue(s.chargedWork()<=WORK_PER_POLL);if(s.complete())return s; }
        throw new AssertionError("Unbounded discovery");
    }
    @Test void boundedSourceSelectionCoversAmountAndRevalidatesExactEvidence() {
        var w=new World();for(int x=3;x<7;x++)w.logs.add(new Point(ACTOR.dimension(),x,0,0));
        var scan=complete(sources(OakLogDelivery.ID,ACTOR,4,new Point(ACTOR.dimension(),0,0,0),w));
        assertFalse(scan.candidates().isEmpty());var area=scan.candidates().getFirst().value();
        assertEquals(4,area.eligible().size());assertTrue(volume(area.area())<=256);
        w.logs.remove(w.logs.iterator().next());assertEquals(Outcomes.Reason.STALE_OBSERVATION,new SourceValidation(area,ACTOR,w).poll(256).reason());
    }
    @Test void incompleteOrOversizedRetentionCannotProveNearest() {
        var w=new World();w.logs.add(new Point(ACTOR.dimension(),4,0,0));w.unknown=true;
        assertEquals(Outcomes.Reason.STALE_OBSERVATION,complete(sources(OakLogDelivery.ID,ACTOR,1,new Point(ACTOR.dimension(),0,0,0),w)).emptyReason());
        w.unknown=false;for(int x=-16;x<=16;x++)w.logs.add(new Point(ACTOR.dimension(),x,0,0));
        assertEquals(Outcomes.Reason.BUDGET_EXHAUSTED,complete(sources(OakLogDelivery.ID,ACTOR,1,new Point(ACTOR.dimension(),0,0,0),w)).emptyReason());
    }
    @Test void oakCapacityRejectsNearInsufficientContainerAndKeepsResourceType() {
        var w=new World();var s=complete(itemContainers(OakLogDelivery.ID,ACTOR,4,new Point(ACTOR.dimension(),0,0,0),w));
        assertEquals(1,s.candidates().size());var found=s.candidates().getFirst().value();
        assertEquals(6,found.container().x());assertEquals(ResourceDelivery.OAK_LOG,found.evidence().item());
    }
}
