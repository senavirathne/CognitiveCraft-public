package dev.aivillages.fabric.runtime.evidence;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.SurvivalGateway.Cell;
import dev.aivillages.core.kernel.runtime.evidence.HarvestProtocol.Request;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Scoped exclusion-query contracts only; no native execution or saved evidence is supplied. */
final class HarvestPreflightScopeTest {
    static Request request() {
        var actor=new ActorRef(UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld");var area=new Cuboid(actor.dimension(),73,208,71,80,209,76);var box=new ContainerRef(actor.dimension(),81,208,72);
        var context=new TrustedContext(new PrincipalRef(UUID.randomUUID()),new ScopeRef(UUID.randomUUID(),UUID.randomUUID()));
        var bound=new ValidatedRequest(new CapabilityRequest(OakLogDelivery.ID,Map.of("actor",new ActorValue(actor),"source",new AreaValue(area),"destination",new ContainerValue(box),"amount",new IntValue(2))),context,new ObservationRef(UUID.randomUUID(),0,actor.dimension()));
        return new Request(bound,new RunCorrelation(UUID.randomUUID(),new ArtifactRef(OakLogDelivery.ID,"a".repeat(64)),null),UUID.randomUUID(),UUID.randomUUID(),actor,area,box,new Cell(actor.dimension(),73,208,73),ResourceDelivery.OAK_LOG,10000,8,Map.of());
    }
    static Cell destination(Request request){var d=request.destination();return new Cell(d.dimension(),d.x(),d.y(),d.z());}
    @Test void ownDestinationCanBeCheckedDuringPreflightButNoTransferCanUseTheScopeAfterward() {
        var scope=new HarvestPreflightScope();var request=request();var target=destination(request);
        assertFalse(scope.destination(request,request.bound(),Effect.TRANSFER,target));
        assertTrue(scope.test(request,()->scope.destination(request,request.bound(),Effect.TRANSFER,target)));
        assertFalse(scope.destination(request,request.bound(),Effect.TRANSFER,target));
    }
    @Test void pickupAnotherDestinationAnotherRequestAndAnotherTicketNeverBorrowTheScope() {
        var scope=new HarvestPreflightScope();var request=request();var other=request();var target=destination(request);
        scope.test(request,()->{
            assertFalse(scope.destination(request,request.bound(),Effect.PICKUP,target));
            assertFalse(scope.destination(request,request.bound(),Effect.TRANSFER,new Cell(target.dimension(),target.x()+1,target.y(),target.z())));
            assertFalse(scope.destination(request,other.bound(),Effect.TRANSFER,target));
            assertFalse(scope.destination(other,request.bound(),Effect.TRANSFER,target));return true;
        });
    }
    @Test void exceptionsAndRejectedReentrancyAlwaysCloseTheOriginalPermissionProbe() {
        var scope=new HarvestPreflightScope();var request=request();
        assertThrows(IllegalStateException.class,()->scope.test(request,()->{scope.test(request,()->true);return true;}));
        assertFalse(scope.destination(request,request.bound(),Effect.TRANSFER,destination(request)));
        assertThrows(IllegalArgumentException.class,()->scope.test(request,()->{throw new IllegalArgumentException("fixture");}));
        assertFalse(scope.destination(request,request.bound(),Effect.TRANSFER,destination(request)));
    }
}
