package dev.aivillages.core.kernel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.SurvivalGateway.*;
import static dev.aivillages.core.kernel.WorldReferenceDiscovery.*;
import static dev.aivillages.core.kernel.WorldReferenceResolver.*;
import static org.junit.jupiter.api.Assertions.*;

class OakPlankBindingTest {
    static final class Fixture {
        final WorldReferenceBindingTest.Fixture identity=new WorldReferenceBindingTest.Fixture();
        final ActorRef actor=identity.add(1,10,0,"Ada",true,true);
        final Map<ContainerRef,InputContainerSample> inputs=new HashMap<>();
        final Observation world=new Observation() {
            public ActorState actor(ActorRef a){return identity.world.actor(a);}
            public CropState crop(ActorRef a,Point p){throw new AssertionError("No crop discovery");}
            public ContainerSample container(ActorRef a,ContainerRef c){throw new AssertionError("No wheat discovery");}
            public InputContainerSample inputContainer(ActorRef a,ContainerRef c){return inputs.getOrDefault(c,new InputContainerSample(ObservationStatus.ABSENT,0,0,"empty"));}
            public ItemContainerSample itemContainer(ActorRef a,ContainerRef c,ResourceDelivery.ItemIdentity item){
                assertEquals(ResourceDelivery.OAK_PLANKS,item);var s=identity.world.container(a,c);
                return new ItemContainerSample(item,s.status(),s.wheatCapacity(),s.slotsInspected(),s.identity());
            }
        };
        Fixture(){identity.open();stock(11,2);identity.world.box(14,0,64);}
        ContainerRef box(int x){return new ContainerRef(actor.dimension(),x,64,0);}
        void stock(int x,long n){inputs.put(box(x),new InputContainerSample(ObservationStatus.PRESENT,n,27,"input:"+x+":"+n));}
        WorldReferenceBinding bind(Reference<ActorRef> a,Reference<LanguageRequests.AreaCoordinates> source){
            return new WorldReferenceBinding(world,identity.registry,identity.caller,new Point(actor.dimension(),0,64,0),
                    new LanguageRequests.HarvestIntent(a,5,source,Reference.omitted(),OakPlankProduction.ID),identity.clock,()->identity.current);
        }
        WorldReferenceBinding bind(){return bind(Reference.concrete(actor,"Ada"),Reference.nearest("available oak logs"));}
        LanguageRequests.Resolution finish(WorldReferenceBinding b){return identity.finish(b);}
    }
    @Test void actorAnchoredInputsAndSourceAnchoredOutputsBecomeTypedContainers(){
        var f=new Fixture();f.stock(0,2);var r=f.finish(f.bind());
        assertEquals(LanguageRequests.ResolutionKind.REQUEST,r.kind());assertEquals(OakPlankProduction.ID,r.request().capability());
        assertEquals(f.box(11),((ContainerValue)r.request().arguments().get("source")).value());assertEquals(f.box(14),((ContainerValue)r.request().arguments().get("destination")).value());
    }
    @Test void insufficientNearerInputCannotSubstituteForCompleteStock(){
        var f=new Fixture();f.stock(10,1);var r=f.finish(f.bind());assertEquals(f.box(11),((ContainerValue)r.request().arguments().get("source")).value());
    }
    @Test void selectedInputMutationFencesCanonicalDispatch(){
        var f=new Fixture();var b=f.bind();LanguageRequests.Resolution r;
        do{r=b.poll();}while(!r.message().contains("References selected"));f.stock(11,1);r=f.finish(b);
        assertEquals(Outcomes.Reason.STALE_OBSERVATION,r.reason());assertNull(r.request());
    }
    @Test void explicitUnsupportedInputNeverFallsBackToNearest(){
        var f=new Fixture();var r=f.finish(f.bind(Reference.concrete(f.actor,"Ada"),Reference.unsupported("my northern chest")));
        assertEquals(LanguageRequests.ResolutionKind.CLARIFICATION,r.kind());assertNull(r.request());
    }
    @Test void privateForeignCitizenCannotResolveStockOrOutput(){
        var f=new Fixture();var foreign=new ActorRef(UUID.randomUUID(),UUID.randomUUID(),f.actor.dimension());
        var r=f.finish(f.bind(Reference.concrete(foreign,"foreign private UUID"),Reference.omitted()));
        assertEquals(Outcomes.Reason.AUTHORITY_DENIED,r.reason());assertNull(r.request());
    }
}
