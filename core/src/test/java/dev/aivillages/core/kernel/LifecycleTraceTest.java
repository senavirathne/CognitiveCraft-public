package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.runtime.lifecycle.LifecycleTrace;
import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.CitizenLifecycle.*;
import static dev.aivillages.core.kernel.runtime.lifecycle.LifecycleTrace.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

final class LifecycleTraceTest {
    private CitizenLifecycleTest.Scene scene(int rows) {
        return new CitizenLifecycleTest.Scene(Settings.production(), new LifecycleTrace(rows));
    }
    private LifecycleTrace.View view(CitizenLifecycleTest.Scene s) {
        return s.lifecycle.diagnostics(s.actor.citizenId(), s.owner);
    }
    @Test void lossFactsPrecedeCessationAndEachDurableAcknowledgement() {
        var s=scene(128);s.load();s.fact(Event.UNLOAD,Source.CHUNK_UNLOAD);s.settle();
        var rows=view(s).rows();
        int fact=-1,stop=-1;var masks=new ArrayList<Integer>();
        for(int n=0;n<rows.size();n++) {
            var row=rows.get(n);
            if(row.marker().event()!=Event.UNLOAD)continue;
            if(row.phase()==FACT&&row.decision()==Decision.ACCEPTED)fact=n;
            if(row.phase()==STOP_REQUESTED)stop=n;
            if(row.phase()==PUBLISHED)masks.add(row.marker().acknowledged());
        }
        assertTrue(fact>=0&&stop>fact);assertEquals(List.of(0,1,3,7),masks);
        assertFalse(s.registry.available(s.actor));
        for(int n=1;n<rows.size();n++)assertTrue(rows.get(n).sequence()>rows.get(n-1).sequence());
    }
    @Test void requestedPublicationIsNotReportedAsDurableWhileTheWriterIsHeld() {
        var s=scene(128);s.load();s.memory.hold=true;s.fact(Event.UNLOAD,Source.CHUNK_UNLOAD);s.step();
        assertTrue(view(s).rows().stream().anyMatch(r->r.phase()==PUBLICATION_REQUESTED&&r.marker().event()==Event.UNLOAD));
        assertFalse(view(s).rows().stream().anyMatch(r->r.phase()==PUBLISHED&&r.marker().event()==Event.UNLOAD));
        s.memory.hold=false;s.memory.release();s.settle();
        assertTrue(view(s).rows().stream().anyMatch(r->r.phase()==PUBLISHED&&r.marker().event()==Event.UNLOAD));
    }
    @Test void missingAndDelayedNativeUnloadRemainDistinctFacts() {
        var s=scene(128);s.load();s.fact(Event.MISSING,Source.UNKNOWN_REMOVAL);s.settle();
        s.fact(Event.UNLOAD,Source.CHUNK_UNLOAD);s.settle();
        var facts=view(s).rows().stream().filter(r->r.phase()==FACT&&r.decision()==Decision.ACCEPTED).toList();
        assertEquals(List.of(Event.LOAD,Event.MISSING,Event.UNLOAD),facts.stream().map(r->r.fact().event()).toList());
        assertFalse(s.marker().terminal());
    }
    @Test void obsoleteGenerationEvidenceDoesNotNotifyOwnersOrBecomeCurrent() {
        var s=scene(128);s.load();long generation=s.marker().generation();
        s.fact(Event.UNLOAD,Source.CHUNK_UNLOAD);s.settle();s.fact(Event.LOAD,Source.ENTITY_LOAD);s.settle();
        int stops=s.owners.stops;
        s.lifecycle.offer(new Fact(s.owner.scope().worldId(),s.actor,generation,Event.DEATH,Source.KILLED,12));
        var row=view(s).rows().getLast();assertEquals(Decision.STALE,row.decision());
        assertEquals(generation,row.fact().generation());assertEquals(generation+1,row.marker().generation());
        assertEquals(stops,s.owners.stops);assertTrue(s.registry.available(s.actor));
    }
    @Test void everyDiagnosticReadRechecksCallerScope() {
        var s=scene(128);s.load();
        assertThrows(SecurityException.class,()->s.lifecycle.diagnostics(s.actor.citizenId(),CitizenRegistryTest.caller(s.owner.scope().worldId())));
        assertThrows(RuntimeException.class,()->s.lifecycle.diagnostics(UUID.randomUUID(),s.owner));
    }
    @Test void eventStormHasAnExplicitBoundAndKeepsAnImmutableScopedSnapshot() {
        var s=scene(8);s.load();var before=view(s);long initial=before.nextSequence();
        for(int n=0;n<100;n++)s.fact(Event.LOAD,Source.ENTITY_LOAD);
        var after=view(s);assertEquals(8,after.rows().size());
        assertEquals(initial+100,after.nextSequence());assertEquals(after.firstSequence(),after.discarded());
        assertEquals(after.nextSequence()-8,after.firstSequence());assertEquals(initial,before.nextSequence());
        assertThrows(UnsupportedOperationException.class,()->after.rows().clear());
    }
    @Test void routineLookupDuplicatesDoNotEraseTheLastNativeFact() {
        var s=scene(8);s.load();var before=view(s);
        for(int n=0;n<1000;n++)s.fact(Event.LOAD,Source.LOADED_LOOKUP);
        assertEquals(before,view(s));
    }
    @Test void restartStartsANewDiagnosticEpochWithoutReplayingPriorEvents() {
        var s=scene(128);s.load();s.fact(Event.UNLOAD,Source.CHUNK_UNLOAD);s.settle();var old=view(s);
        var next=new CitizenLifecycle(s.registry,s.owners,Settings.production(),50,new LifecycleTrace(128));
        var fresh=next.diagnostics(s.actor.citizenId(),s.owner);
        assertNotEquals(old.process(),fresh.process());assertEquals(0,fresh.nextSequence());assertTrue(fresh.rows().isEmpty());
        assertEquals(Event.VERIFY,next.marker(s.actor.citizenId()).event());
    }
    @Test void disabledTraceHasNoRowsAndPreservesTheExistingLifecycle() {
        var s=scene(0);s.load();s.fact(Event.UNLOAD,Source.CHUNK_UNLOAD);s.settle();
        assertFalse(view(s).enabled());assertEquals(0,view(s).nextSequence());assertTrue(view(s).rows().isEmpty());
        assertEquals(7,s.marker().acknowledged());assertEquals(Event.UNLOAD,s.marker().event());
    }
    @Test void diagnosticClockSeparatesEventTickFromPublicationTick() {
        var s=scene(128);s.load();s.fact(Event.UNLOAD,Source.CHUNK_UNLOAD);
        for(int n=0;n<100;n++){s.registry.tick();s.lifecycle.step(100+n);}
        assertTrue(view(s).rows().stream().anyMatch(r->r.phase()==PUBLISHED&&r.marker().event()==Event.UNLOAD
                &&r.marker().tick()==11&&r.observedTick()>=100));
    }
    @Test void diagnosticCapacityIsFiniteAndCannotExpandWithPopulation() {
        assertThrows(IllegalArgumentException.class,()->new LifecycleTrace(-1));
        assertThrows(IllegalArgumentException.class,()->new LifecycleTrace(129));
        var trace=new LifecycleTrace(2);var marker=Marker.initial();var actors=new ArrayList<Contracts.ActorRef>();
        for(int n=0;n<65;n++) {
            var actor=new Contracts.ActorRef(UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld");actors.add(actor);
            trace.record(actor,1,PUBLISHED,marker,null,null,true);
        }
        assertEquals(1,trace.view(actors.getFirst()).rows().size());assertTrue(trace.view(actors.getLast()).rows().isEmpty());
    }
    @Test void foreignThreadCannotReadOrAppendDiagnosticEvents() throws Exception {
        var s=scene(128);s.load();var errors=new ArrayList<Throwable>();
        var thread=new Thread(()->{try{view(s);}catch(Throwable failure){errors.add(failure);}});
        thread.start();thread.join();assertEquals(1,errors.size());assertInstanceOf(IllegalStateException.class,errors.getFirst());
    }
}
