package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.WorldReferenceResolver.*;
import static dev.aivillages.core.kernel.WorldReferenceDiscoveryTest.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldReferenceBindingTest {
    static final class Fixture {
        final CitizenRegistryTest.TestClock clock=new CitizenRegistryTest.TestClock();
        final TrustedContext caller=new TrustedContext(new PrincipalRef(new UUID(0,11)),new ScopeRef(new UUID(0,12),new UUID(0,13)));
        final Fake world=new Fake();
        final List<CitizenRegistry.Citizen> rows=new ArrayList<>();
        CitizenRegistry registry;
        CompletableFuture<CitizenRegistry.Snapshot> held;
        CitizenRegistry.Snapshot heldSnapshot;
        boolean hold, current=true;
        ActorRef add(int id,int x,int z,String name,boolean owned,boolean loaded) {
            var actor=new ActorRef(new UUID(0,id),new UUID(1,id),DIM);
            var owner=owned?caller:new TrustedContext(new PrincipalRef(new UUID(0,99)),caller.scope());
            rows.add(new CitizenRegistry.Citizen(actor,owner,name,CitizenRegistry.Availability.LOADED));
            world.actors.put(actor,new SurvivalGateway.ActorState(loaded,true,DIM,x,64,z,0)); return actor;
        }
        void open() {
            registry=new CitizenRegistry(new CitizenRegistry.Snapshot(caller.scope().worldId(),0,rows,null),
                    (expected,next)-> {
                        if (!hold) return CompletableFuture.completedFuture(next);
                        heldSnapshot=next; return held=new CompletableFuture<>();
                    },
                    CitizenRegistry.privateAddresses(),clock,false);
            for(var row:rows) registry.observeAvailability(row.actor().citizenId(),CitizenRegistry.Availability.LOADED);
        }
        WorldReferenceBinding bind(LanguageRequests.Intent intent,Point callerAt) {
            return new WorldReferenceBinding(world,registry,caller,callerAt,intent,clock,()->current);
        }
        LanguageRequests.Resolution finish(WorldReferenceBinding binding) {
            LanguageRequests.Resolution result; int polls=0;
            do { result=binding.poll(); registry.tick(); assertTrue(++polls<500); }
            while(result.kind()==LanguageRequests.ResolutionKind.PENDING);
            assertTrue(binding.usedWork()<=WorldReferenceDiscovery.TOTAL_WORK); return result;
        }
    }
    @Test void initialActorObservationAndFirstScanShareTheSameTickAllowance() {
        for (boolean explicit : new boolean[] {true,false}) {
            var f=new Fixture(); var actor=f.add(1,0,0,"Ada",true,true); f.open();
            Reference<ActorRef> target=explicit ? Reference.concrete(actor,"Ada") : Reference.omitted();
            var binding=f.bind(new LanguageRequests.HarvestIntent(target,4,Reference.omitted(),Reference.omitted()),ORIGIN);
            assertEquals(LanguageRequests.ResolutionKind.PENDING,binding.poll().kind());
            assertTrue(binding.usedWork()<=WorldReferenceDiscovery.WORK_PER_POLL,
                    "Construction and immediate poll exceeded the server-tick allowance");
            if (explicit) assertEquals(WorldReferenceDiscovery.WORK_PER_POLL,binding.usedWork());
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"your name is Ada","i give you the name Ada","I name you Ada","name the villager Ada","give name to the villager as Ada"})
    void allNamingFormsUseNearestUnnamedControlledEnrolledCitizen(String text) {
        var f=new Fixture(); var expected=f.add(1,2,0,null,true,true);
        var named=f.add(2,0,0,"Named",true,true); var foreign=f.add(3,0,0,null,false,true);
        var unloaded=f.add(4,0,0,null,true,false); f.add(5,25,0,null,true,true); f.open();
        var interpreted=LanguageReferenceClassifier.classify(text,List.of());
        assertEquals(LanguageReferenceClassifier.Intent.NAME_CITIZEN,interpreted.intent());
        var result=f.finish(f.bind(new LanguageRequests.NamingIntent(Reference.omitted(),interpreted.proposedName()),ORIGIN));
        assertEquals(LanguageRequests.ResolutionKind.NAMED,result.kind());
        assertEquals("Ada",f.registry.query(expected.citizenId(),f.caller).displayName());
        assertEquals("Named",f.registry.query(named.citizenId(),f.caller).displayName());
        assertNull(f.registry.snapshot().citizens().stream().filter(c->c.actor().equals(foreign)).findFirst().orElseThrow().displayName());
        assertNull(f.registry.query(unloaded.citizenId(),f.caller).displayName());
        assertEquals(CitizenRegistry.AddressStatus.FOUND,f.registry.address("Ada",f.caller).status());
    }
    @Test void explicitCitizenOverridesNearestAndPreservesAuthorizedRenameOfNamedCitizens() {
        var f=new Fixture(); var explicit=f.add(1,12,0,"Old",true,true); var nearer=f.add(2,0,0,null,true,true); f.open();
        var result=f.finish(f.bind(new LanguageRequests.NamingIntent(Reference.concrete(explicit,"id"),"Ada"),ORIGIN));
        assertEquals(LanguageRequests.ResolutionKind.NAMED,result.kind());
        assertEquals("Ada",f.registry.query(explicit.citizenId(),f.caller).displayName());
        assertNull(f.registry.query(nearer.citizenId(),f.caller).displayName());
    }
    @Test void nearestNameTieIsStableAndEnumerationBeyondTheFirstPageIsComplete() {
        var f=new Fixture(); for(int i=1;i<=20;i++) f.add(i,i==20?1:20,0,null,true,true); f.open();
        assertTrue(f.registry.citizens(f.caller).more());
        f.finish(f.bind(new LanguageRequests.NamingIntent(Reference.omitted(),"Ada"),ORIGIN));
        assertEquals(new UUID(0,20),f.registry.address("Ada",f.caller).candidates().getFirst().actor().citizenId());
        var g=new Fixture(); var a=g.add(2,1,0,null,true,true); g.add(1,-1,0,null,true,true); g.open();
        g.finish(g.bind(new LanguageRequests.NamingIntent(Reference.omitted(),"Ada"),ORIGIN));
        assertNull(g.registry.query(a.citizenId(),g.caller).displayName());
        assertEquals(new UUID(0,1),g.registry.address("Ada",g.caller).candidates().getFirst().actor().citizenId());
    }
    @Test void actorAndSourceAnchorsDetermineBindingsInsteadOfPlayerPosition() {
        var f=new Fixture(); var actor=f.add(1,10,0,"Ada",true,true); f.open();
        f.world.patch(0,0,4); f.world.patch(11,0,4); f.world.box(0,2,64); f.world.box(15,0,64);
        var binding=f.bind(new LanguageRequests.HarvestIntent(Reference.concrete(actor,"Ada"),4,Reference.omitted(),Reference.omitted()),ORIGIN);
        var resolved=f.finish(binding); assertEquals(LanguageRequests.ResolutionKind.REQUEST,resolved.kind());
        assertEquals(new Cuboid(DIM,11,64,0,14,64,0),((AreaValue)resolved.request().arguments().get("source")).value());
        assertEquals(new ContainerRef(DIM,15,64,0),((ContainerValue)resolved.request().arguments().get("destination")).value());
    }
    @Test void movingActorAndCallerCannotShiftCapturedAnchorsDuringScanning() {
        var f=new Fixture(); var actor=f.add(1,10,0,"Ada",true,true); f.open();
        f.world.patch(0,0,4); f.world.patch(11,0,4); f.world.box(15,0,64);
        var binding=f.bind(new LanguageRequests.HarvestIntent(Reference.concrete(actor,"Ada"),4,Reference.omitted(),Reference.omitted()),ORIGIN);
        binding.poll(); f.world.actors.put(actor,new SurvivalGateway.ActorState(true,true,DIM,0,64,0,0));
        var result=f.finish(binding);
        assertEquals(11,((AreaValue)result.request().arguments().get("source")).value().minX());
    }
    @Test void explicitFullDestinationIsPreservedAndExplicitAreaUsesTheSameDestinationAnchor() {
        var f=new Fixture(); var actor=f.add(1,0,0,"Ada",true,true); f.open(); f.world.patch(2,0,4); f.world.box(7,0,0); f.world.box(9,0,64);
        var explicitBox=Reference.concrete(new LanguageRequests.Coordinates(7,64,0),"coordinates");
        var result=f.finish(f.bind(new LanguageRequests.HarvestIntent(Reference.concrete(actor,"Ada"),4,Reference.omitted(),explicitBox),ORIGIN));
        assertEquals(7,((ContainerValue)result.request().arguments().get("destination")).value().x());
        var source=Reference.concrete(new LanguageRequests.AreaCoordinates(new LanguageRequests.Coordinates(2,64,0),new LanguageRequests.Coordinates(5,64,0)),"coordinates");
        result=f.finish(f.bind(new LanguageRequests.HarvestIntent(Reference.concrete(actor,"Ada"),4,source,Reference.omitted()),ORIGIN));
        assertEquals(9,((ContainerValue)result.request().arguments().get("destination")).value().x());
    }
    @Test void changedSelectedContainerFailsBeforeCanonicalDispatch() {
        var f=new Fixture(); var actor=f.add(1,0,0,"Ada",true,true); f.open(); f.world.patch(2,0,4); f.world.box(7,0,64);
        var binding=f.bind(new LanguageRequests.HarvestIntent(Reference.concrete(actor,"Ada"),4,Reference.omitted(),Reference.omitted()),ORIGIN);
        LanguageRequests.Resolution view;
        do { view=binding.poll(); } while(!view.message().contains("References selected"));
        f.world.box(7,0,3); view=f.finish(binding);
        assertEquals(Outcomes.Reason.STALE_OBSERVATION,view.reason()); assertNull(view.request());
    }
    @Test void cancellationAndDeadlinePreventFurtherObservationAndDispatch() {
        var f=new Fixture(); var actor=f.add(1,0,0,"Ada",true,true); f.open(); f.world.patch(2,0,4); f.world.box(7,0,64);
        var binding=f.bind(new LanguageRequests.HarvestIntent(Reference.concrete(actor,"Ada"),4,Reference.omitted(),Reference.omitted()),ORIGIN);
        binding.poll(); int observed=f.world.cells; assertTrue(binding.cancel());
        assertEquals(Outcomes.Reason.CANCELLED,binding.poll().reason()); assertEquals(observed,f.world.cells);
        var next=f.bind(new LanguageRequests.HarvestIntent(Reference.concrete(actor,"Ada"),4,Reference.omitted(),Reference.omitted()),ORIGIN);
        f.clock.now+=WorldReferenceDiscovery.RESOLUTION_MILLIS+1;
        assertEquals(Outcomes.Reason.BUDGET_EXHAUSTED,next.poll().reason()); assertEquals(observed,f.world.cells);
    }
    @Test void foreignExplicitActorAndAbsentImplicitActorNeverStartFieldDiscovery() {
        var f=new Fixture(); var foreign=f.add(1,0,0,null,false,true); f.open();
        var denied=f.finish(f.bind(new LanguageRequests.NamingIntent(Reference.concrete(foreign,"id"),"Ada"),ORIGIN));
        assertEquals(Outcomes.Reason.AUTHORITY_DENIED,denied.reason()); assertEquals(0,f.world.cells);
        var absent=f.finish(f.bind(new LanguageRequests.NamingIntent(Reference.omitted(),"Ada"),ORIGIN));
        assertEquals(Outcomes.Reason.ACTOR_UNAVAILABLE,absent.reason()); assertEquals(0,f.world.cells);
    }
    @Test void namingWaitsForAnExistingRegistryPublicationThenUsesTheSameIdentityOwner() {
        var f=new Fixture(); var actor=f.add(1,0,0,null,true,true);
        var other=f.add(2,3,0,"Before",true,true); f.open(); f.hold=true;
        assertTrue(f.registry.rename(other.citizenId(),"After",f.caller).pending());
        var binding=f.bind(new LanguageRequests.NamingIntent(Reference.concrete(actor,"id"),"Ada"),ORIGIN);
        assertEquals(LanguageRequests.ResolutionKind.PENDING,binding.poll().kind());
        assertNull(f.registry.query(actor.citizenId(),f.caller).displayName());
        f.held.complete(f.heldSnapshot); f.registry.tick(); f.hold=false;
        assertEquals(LanguageRequests.ResolutionKind.NAMED,f.finish(binding).kind());
        assertEquals("Ada",f.registry.query(actor.citizenId(),f.caller).displayName());
        assertEquals("After",f.registry.query(other.citizenId(),f.caller).displayName());
    }
    @Test void namingCanBeCancelledWhileWaitingForSomeOtherRegistryPublication() {
        var f=new Fixture(); var actor=f.add(1,0,0,null,true,true);
        var other=f.add(2,3,0,"Before",true,true); f.open(); f.hold=true;
        f.registry.rename(other.citizenId(),"After",f.caller);
        var binding=f.bind(new LanguageRequests.NamingIntent(Reference.concrete(actor,"id"),"Ada"),ORIGIN);
        assertEquals(LanguageRequests.ResolutionKind.PENDING,binding.poll().kind());
        assertTrue(binding.cancel()); f.held.complete(f.heldSnapshot); f.registry.tick();
        assertEquals(Outcomes.Reason.CANCELLED,binding.poll().reason());
        assertNull(f.registry.query(actor.citizenId(),f.caller).displayName());
    }
    @Test void registryPublicationRemainsPendingAndCannotBeReportedAsDurableOrUndoneByCancellation() {
        var f=new Fixture(); var actor=f.add(1,0,0,null,true,true); f.open(); f.hold=true;
        var binding=f.bind(new LanguageRequests.NamingIntent(Reference.omitted(),"Ada"),ORIGIN);
        assertEquals(LanguageRequests.ResolutionKind.PENDING,binding.poll().kind());
        assertEquals(LanguageRequests.ResolutionKind.PENDING,binding.poll().kind());
        assertFalse(binding.cancellable());
        assertFalse(binding.cancel()); assertNull(f.registry.query(actor.citizenId(),f.caller).displayName());
        var next=new CitizenRegistry.Snapshot(f.caller.scope().worldId(),1,List.of(new CitizenRegistry.Citizen(actor,f.caller,"Ada",CitizenRegistry.Availability.LOADED)),null);
        f.held.complete(next); f.registry.tick();
        assertEquals(LanguageRequests.ResolutionKind.NAMED,binding.poll().kind());
        assertFalse(binding.cancellable());
        assertEquals("Ada",f.registry.query(actor.citizenId(),f.caller).displayName());
    }
}
