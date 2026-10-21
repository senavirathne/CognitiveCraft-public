package dev.aivillages.core.kernel;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.SurvivalGateway.*;
import static dev.aivillages.core.kernel.WorldReferenceDiscovery.*;
import static dev.aivillages.core.kernel.WorldReferenceResolver.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real deterministic orchestration with a read-only fake world and explicitly injected extraction. */
class WorldReferenceTypeBindingTest {
    record Box(ObjectType type,int capacity,long stock,ObservationStatus status) { }
    static final class Fixture extends LanguageRequestsTest.Fixture {
        final Map<ContainerRef,Box> boxes=new HashMap<>();
        final Set<ContainerRef> unknown=new HashSet<>();
        final CitizenRegistry registry;
        boolean current=true;
        final Observation world=new Observation() {
            public ActorState actor(ActorRef a) {return new ActorState(true,true,a.dimension(),0,64,0,0);}
            public CropState crop(ActorRef a,Point p) {return new CropState(ObservationStatus.ABSENT,false,"empty");}
            public ContainerSample container(ActorRef a,ContainerRef c) {throw new AssertionError("Use typed resource observation");}
            public ItemContainerSample itemContainer(ActorRef a,ContainerRef c,ResourceDelivery.ItemIdentity item,ObjectType type) {
                var b=boxes.get(c);var status=status(c,type);
                return new ItemContainerSample(item,status,status==ObservationStatus.PRESENT ? b.capacity() : 0,
                        status==ObservationStatus.PRESENT ? 27 : 0,"box:"+c+":"+(b==null ? "absent" : b.type()));
            }
            public InputContainerSample inputContainer(ActorRef a,ContainerRef c,ResourceDelivery.ItemIdentity item,ObjectType type) {
                var b=boxes.get(c);var status=status(c,type);
                return new InputContainerSample(status,status==ObservationStatus.PRESENT ? b.stock() : 0,
                        status==ObservationStatus.PRESENT ? 27 : 0,"input:"+c+":"+(b==null ? "absent" : b.type()));
            }
            private ObservationStatus status(ContainerRef c,ObjectType type) {
                if(unknown.contains(c))return ObservationStatus.UNKNOWN;
                var b=boxes.get(c);
                return b==null || type.specificContainer() && type!=b.type() ? ObservationStatus.ABSENT : b.status();
            }
        };
        Fixture() {
            registry=new CitizenRegistry(new CitizenRegistry.Snapshot(owner.scope().worldId(),0,
                    List.of(new CitizenRegistry.Citizen(actor,owner,"Ada",CitizenRegistry.Availability.LOADED)),null),
                    (expected,next)->CompletableFuture.completedFuture(next),CitizenRegistry.privateAddresses(),clock,false);
            registry.observeAvailability(actor.citizenId(),CitizenRegistry.Availability.LOADED);
        }
        ContainerRef box(int x) {return new ContainerRef(actor.dimension(),x,64,0);}
        void put(int x,ObjectType type,int capacity,long stock) {boxes.put(box(x),new Box(type,capacity,stock,ObservationStatus.PRESENT));}
        Reference<LanguageRequests.AreaCoordinates> area() {
            var p=new LanguageRequests.Coordinates(0,64,0);
            return Reference.concrete(new LanguageRequests.AreaCoordinates(p,p),"0,64,0 through 0,64,0");
        }
        WorldReferenceBinding bind(ObjectType type) {
            return new WorldReferenceBinding(world,registry,owner,new Point(actor.dimension(),0,64,0),
                    new LanguageRequests.HarvestIntent(Reference.concrete(actor,"Ada"),4,area(),Reference.nearest("that object",type),OakLogDelivery.ID),clock,()->current);
        }
        LanguageRequests.Resolution finish(WorldReferenceBinding b) {
            for(int i=0;i<500;i++) {
                long before=b.usedWork();var r=b.poll();
                assertTrue(b.usedWork()-before<=WORK_PER_POLL);assertTrue(b.usedWork()<=TOTAL_WORK);
                if(r.kind()!=LanguageRequests.ResolutionKind.PENDING)return r;
            }
            throw new AssertionError("Unbounded resolution");
        }
        @Override public LanguageRequests.ResolutionHandle resolve(LanguageRequests.Intent intent,TrustedContext caller) {
            discoveries++;
            return new WorldReferenceBinding(world,registry,caller,new Point(actor.dimension(),0,64,0),intent,clock,()->current);
        }
        LanguageRequests.View interpret(String text,LanguageRequests.Extracted extracted) {
            var ticket=ask(text);fake.response.complete(new LanguageRequests.Result(LanguageRequests.Kind.EXTRACTED,extracted,.95));
            for(int i=0;i<500;i++) {
                language.tick();var v=language.status(ticket,owner);
                if(v.phase()!=LanguageRequests.Phase.INTERPRETING && v.phase()!=LanguageRequests.Phase.RESOLVING)return v;
            }
            throw new AssertionError("Unbounded interpretation");
        }
    }
    @Test void nearestAvailableMatchingTypeExcludesNearerChestAndFullBarrelWithStableTies() {
        var f=new Fixture();f.put(1,ObjectType.CHEST,64,0);f.put(2,ObjectType.BARREL,0,0);
        f.put(4,ObjectType.BARREL,64,0);f.put(-4,ObjectType.BARREL,64,0);f.put(6,ObjectType.BARREL,64,0);
        var r=f.finish(f.bind(ObjectType.BARREL));
        assertEquals(LanguageRequests.ResolutionKind.REQUEST,r.kind());
        assertEquals(new ContainerValue(f.box(-4)),r.request().arguments().get("destination"));
        r=f.finish(f.bind(ObjectType.CHEST));assertEquals(new ContainerValue(f.box(1)),r.request().arguments().get("destination"));
        r=f.finish(f.bind(ObjectType.CONTAINER));assertEquals(new ContainerValue(f.box(1)),r.request().arguments().get("destination"));
    }
    @Test void noMatchingContainerAndIncompleteEvidenceCannotFallBackToWrongType() {
        var f=new Fixture();f.put(1,ObjectType.CHEST,64,0);
        assertEquals(Outcomes.Reason.FACILITY_MISSING,f.finish(f.bind(ObjectType.BARREL)).reason());
        f.put(4,ObjectType.BARREL,64,0);f.unknown.add(f.box(2));
        assertEquals(Outcomes.Reason.STALE_OBSERVATION,f.finish(f.bind(ObjectType.BARREL)).reason());
    }
    @Test void replacementWithWrongTypeOrLostCapacityFencesCanonicalDispatch() {
        for(boolean wrongType:List.of(true,false)) {
            var f=new Fixture();f.put(4,ObjectType.BARREL,64,0);var b=f.bind(ObjectType.BARREL);
            while(!b.poll().message().contains("References selected")) { }
            f.put(4,wrongType ? ObjectType.CHEST : ObjectType.BARREL,wrongType ? 64 : 0,0);
            var r=f.finish(b);assertEquals(Outcomes.Reason.STALE_OBSERVATION,r.reason());assertNull(r.request());
        }
    }
    @Test void genericRawDestinationSurvivesExtractionAndBindsThroughTheSharedOwner() {
        for(String target:List.of("that barrel","this barrel","the barrel","barrel")) {
            var f=new Fixture();f.put(1,ObjectType.CHEST,64,0);f.put(4,ObjectType.BARREL,64,0);
            var v=f.interpret("Ada, bring four oak logs from 0,64,0 through 0,64,0 to "+target+".",
                    new LanguageRequests.Extracted(LanguageRequests.Action.GATHER_OAK_LOGS,"Ada",4L,"0,64,0","0,64,0",null,null));
            assertEquals(LanguageRequests.Phase.SUBMITTED,v.phase(),v.toString());assertEquals(1,f.submissions);
            assertEquals(OakLogDelivery.ID,f.request.capability());assertEquals(new ContainerValue(f.box(4)),f.request.arguments().get("destination"));
        }
    }
    @Test void sharedRecipeInputAndOutputSelectionBothPreserveTheirNamedTypes() {
        var f=new Fixture();f.put(1,ObjectType.CHEST,64,16);f.put(3,ObjectType.BARREL,64,16);
        var v=f.interpret("Ada, make four sticks from this barrel and put them into that chest.",
                new LanguageRequests.Extracted(LanguageRequests.Action.MAKE_STICKS,"Ada",4L,null,null,null,null));
        assertEquals(LanguageRequests.Phase.SUBMITTED,v.phase(),v.toString());
        assertEquals(new ContainerValue(f.box(3)),f.request.arguments().get("source"));
        assertEquals(new ContainerValue(f.box(1)),f.request.arguments().get("destination"));
    }
    @Test void explicitCoordinatesAreNeverRetargetedWhenTheStatedTypeDoesNotMatch() {
        var f=new Fixture();f.put(1,ObjectType.CHEST,64,0);f.put(4,ObjectType.BARREL,64,0);
        var v=f.interpret("Ada, bring four oak logs from 0,64,0 through 0,64,0 to that barrel at 1,64,0.",
                new LanguageRequests.Extracted(LanguageRequests.Action.GATHER_OAK_LOGS,"Ada",4L,"0,64,0","0,64,0","1,64,0",null));
        assertEquals(LanguageRequests.Phase.REJECTED,v.phase());assertEquals(0,f.submissions);
    }
    @Test void typeUnknownToTheObservationAdapterCannotBeInferredFromAnIdentityLabel() {
        Observation untyped=new Observation() {
            public ActorState actor(ActorRef a) {throw new AssertionError();}
            public CropState crop(ActorRef a,Point p) {throw new AssertionError();}
            public ContainerSample container(ActorRef a,ContainerRef c) {
                return new ContainerSample(ObservationStatus.PRESENT,64,27,"barrel-looking-identity");
            }
        };
        var f=new Fixture();
        var sample=untyped.itemContainer(f.actor,f.box(1),ResourceDelivery.WHEAT,ObjectType.BARREL);
        assertEquals(ObservationStatus.UNKNOWN,sample.status());assertEquals(27,sample.slotsInspected());
    }
}
