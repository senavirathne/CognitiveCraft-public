package dev.aivillages.core.kernel;

import java.util.List;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.WorldReferenceResolver.*;
import static dev.aivillages.core.kernel.WorldReferenceDiscoveryTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SpatialReferenceBindingTest {
    private static Reference<LanguageRequests.AreaCoordinates> source() {
        return Reference.concrete(new LanguageRequests.AreaCoordinates(new LanguageRequests.Coordinates(0,64,0),
                new LanguageRequests.Coordinates(3,64,0)),"explicit source");
    }
    @Test void actorRelativeDestinationDiffersFromBothSourceCentreAndCaller() {
        var f=new WorldReferenceBindingTest.Fixture();var actor=f.add(1,10,0,"Ada",true,true);f.open();
        f.world.box(-9,0,64);f.world.box(1,0,64);f.world.box(9,0,64);
        for(boolean relative:List.of(true,false)) {
            var destination=Reference.<LanguageRequests.Coordinates>nearest("raw nearest",ObjectType.UNSPECIFIED,
                    relative?ReferenceAnchor.ACTOR:ReferenceAnchor.ROLE_DEFAULT);
            var r=f.finish(f.bind(new LanguageRequests.HarvestIntent(Reference.concrete(actor,"Ada"),4,source(),destination),point(-10,64,0)));
            assertEquals(LanguageRequests.ResolutionKind.REQUEST,r.kind());
            assertEquals(new ContainerValue(new ContainerRef(DIM,relative?9:1,64,0)),r.request().arguments().get("destination"));
        }
    }
    @Test void capturedActorAnchorIsStableDuringScanningAndUsesExistingExactCoordinateTies() {
        var f=new WorldReferenceBindingTest.Fixture();var actor=f.add(1,0,0,"Ada",true,true);f.open();
        f.world.box(4,0,64);f.world.box(-4,0,64);
        var b=f.bind(new LanguageRequests.HarvestIntent(Reference.concrete(actor,"Ada"),4,source(),
                Reference.nearest("chest closest to you",ObjectType.UNSPECIFIED,ReferenceAnchor.ACTOR)),point(10,64,0));
        b.poll();f.world.actors.put(actor,new SurvivalGateway.ActorState(true,true,DIM,4,64,0,0));
        var r=f.finish(b);assertEquals(new ContainerValue(new ContainerRef(DIM,-4,64,0)),r.request().arguments().get("destination"));
    }
    @Test void rawOriginalPreservesTypeAndActorAnchorThroughLanguageRequestsAndCanonicalBinding() {
        var f=new WorldReferenceTypeBindingTest.Fixture();f.put(5,ObjectType.CHEST,0,3);
        f.put(6,ObjectType.CHEST,64,0);f.put(-3,ObjectType.CHEST,64,0);f.put(1,ObjectType.BARREL,64,0);
        var v=f.interpret("Make eleven birch planks and put them in the chest closest to you.",
                new LanguageRequests.Extracted(LanguageRequests.Action.MAKE_BIRCH_PLANKS,null,11L,null,null,null,null));
        assertEquals(LanguageRequests.Phase.SUBMITTED,v.phase(),v.toString());assertEquals(1,f.submissions);
        assertEquals(BirchPlankProduction.ID,f.request.capability());assertEquals(new IntValue(11),f.request.arguments().get("amount"));
        assertEquals(new ContainerValue(f.box(5)),f.request.arguments().get("source"));
        assertEquals(new ContainerValue(f.box(-3)),f.request.arguments().get("destination"));
    }
    @Test void nearerFullWrongTypeAndOtherDimensionCandidatesCannotOverrideActorRelativePolicy() {
        var f=new WorldReferenceTypeBindingTest.Fixture();f.put(5,ObjectType.CHEST,0,3);
        f.put(1,ObjectType.BARREL,64,0);f.put(2,ObjectType.CHEST,0,0);f.put(4,ObjectType.CHEST,64,0);
        f.boxes.put(new ContainerRef("minecraft:the_nether",0,64,0),new WorldReferenceTypeBindingTest.Box(ObjectType.CHEST,64,0,WorldReferenceDiscovery.ObservationStatus.PRESENT));
        var v=f.interpret("Ada, make eleven birch planks and put them in the closest chest to you.",
                new LanguageRequests.Extracted(LanguageRequests.Action.MAKE_BIRCH_PLANKS,"Ada",11L,null,null,null,null));
        assertEquals(LanguageRequests.Phase.SUBMITTED,v.phase(),v.toString());
        assertEquals(new ContainerValue(f.box(4)),f.request.arguments().get("destination"));
    }
    @Test void unknownExplicitActorsAndUnsupportedAnchorsCannotFallBackOrSubmit() {
        for(String text:List.of("Unknown, make eleven birch planks and put them in the chest closest to you.",
                "Ada, make eleven birch planks and put them in the chest closest to me.")) {
            var f=new WorldReferenceTypeBindingTest.Fixture();f.put(5,ObjectType.CHEST,64,3);
            var v=f.interpret(text,new LanguageRequests.Extracted(LanguageRequests.Action.MAKE_BIRCH_PLANKS,
                    text.startsWith("Unknown")?"Unknown":"Ada",11L,null,null,null,null));
            assertEquals(LanguageRequests.Phase.CLARIFICATION,v.phase());assertEquals(0,f.discoveries);assertEquals(0,f.submissions);
        }
    }
    @Test void absentUnknownAndChangedDestinationsFenceDispatchInsteadOfChoosingUnprovedNearest() {
        var f=new WorldReferenceBindingTest.Fixture();var actor=f.add(1,0,0,"Ada",true,true);f.open();
        var intent=new LanguageRequests.HarvestIntent(Reference.concrete(actor,"Ada"),4,source(),
                Reference.nearest("closest to you",ObjectType.UNSPECIFIED,ReferenceAnchor.ACTOR));
        assertEquals(Outcomes.Reason.FACILITY_MISSING,f.finish(f.bind(intent,ORIGIN)).reason());
        f.world.box(4,0,64);f.world.unknown.add(point(2,64,0));
        assertEquals(Outcomes.Reason.STALE_OBSERVATION,f.finish(f.bind(intent,ORIGIN)).reason());
        f.world.unknown.clear();var b=f.bind(intent,ORIGIN);
        while(!b.poll().message().contains("References selected")) { }
        f.world.box(4,0,0);var r=f.finish(b);
        assertEquals(Outcomes.Reason.STALE_OBSERVATION,r.reason());assertNull(r.request());
    }
    @Test void revocationAndCallerChangeStillBlockTheExplicitActorAnchor() {
        var f=new WorldReferenceBindingTest.Fixture();var actor=f.add(1,0,0,"Ada",false,true);f.open();f.world.box(4,0,64);
        var intent=new LanguageRequests.HarvestIntent(Reference.concrete(actor,"Ada"),4,source(),
                Reference.nearest("closest to you",ObjectType.UNSPECIFIED,ReferenceAnchor.ACTOR));
        assertEquals(Outcomes.Reason.AUTHORITY_DENIED,f.finish(f.bind(intent,ORIGIN)).reason());
        var g=new WorldReferenceBindingTest.Fixture();actor=g.add(1,0,0,"Ada",true,true);g.open();g.world.box(4,0,64);
        var b=g.bind(new LanguageRequests.HarvestIntent(Reference.concrete(actor,"Ada"),4,source(),intent.destination()),ORIGIN);
        b.poll();g.current=false;assertEquals(Outcomes.Reason.STALE_OBSERVATION,g.finish(b).reason());
    }
}
