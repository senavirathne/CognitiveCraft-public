package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.runtime.activity.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dev.aivillages.core.kernel.Jobs.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Owner/codec tests with synthetic participants; no native save or continuation qualification. */
final class ActivityAssignmentWindowTest {
    @Test void theOriginalSeparatedStickAuthoritySurvivesTheJobsJournal(@TempDir Path world) throws Exception {
        var actor=new ActorRef(UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld");
        var principal=UUID.randomUUID();
        var owner=new TrustedContext(new PrincipalRef(principal),new ScopeRef(UUID.randomUUID(),principal));
        var source=new ContainerRef(actor.dimension(),0,65,1);
        var sticks=new ContainerRef(actor.dimension(),7,65,1);
        var destination=new ContainerRef(actor.dimension(),6,65,8);
        var table=new FacilityRef(actor.dimension(),5,65,4);
        var area=new Cuboid(actor.dimension(),0,65,0,5,65,0);
        var request=new CapabilityRequest(WoodenAxeProduction.ID,Map.of("actor",new ActorValue(actor),"amount",new IntValue(1),
                "source",new ContainerValue(source),"destination",new ContainerValue(destination),"table",new FacilityValue(table)));
        var clock=Clock.fixed(Instant.ofEpochMilli(100),ZoneOffset.UTC);
        var observation=new ObservationRef(UUID.randomUUID(),1,actor.dimension());
        var allowance=ResourceComposition.allowance(clock.millis());
        var stock=ActivityPayload.OriginalStock.CONTAINERS_AND_SEPARATE_STICKS;
        var root=UUID.randomUUID();
        try(var journal=JobJournal.open(world,owner.scope().worldId())) {
            var store=new JobLifecycleStore(journal.snapshot(),(before,after)->{
                try {return CompletableFuture.completedFuture(journal.replace(before,after));}
                catch(java.io.IOException failure){return CompletableFuture.failedFuture(failure);}
            },JobLifecycleStore.privateJobs((worker,caller)->worker.equals(actor) && caller.equals(owner)),
                    cancellation->fail("Unexpected cancellation"),PlanningGoals.catalog(),new RequestEnvironment() {
                        public boolean enrolled(ActorRef worker,TrustedContext caller){return worker.equals(actor) && caller.equals(owner);}
                        public boolean loaded(Cuboid bounds,ObservationRef witness){return true;}
                        public boolean available(ContainerRef address,ObservationRef witness){return true;}
                    },clock,Settings.defaults(),false);
            assertFalse(store.createProductionActivity(UUID.randomUUID(),request,owner,observation,allowance,4,area,destination,stock).accepted());
            var admitted=store.createProductionActivity(root,request,owner,observation,allowance,4,area,sticks,stock);
            assertTrue(admitted.accepted(),admitted.toString());
            store.tick();
            assertEquals(request,store.query(root,owner).request().request());
        }
        try(var journal=JobJournal.open(world,owner.scope().worldId())) {
            assertFalse(journal.readOnly());
            var persisted=journal.snapshot().jobs().getFirst();
            assertEquals(root,persisted.id());
            assertEquals(request,persisted.request().request());
            assertEquals(allowance,persisted.allowance());
            assertEquals(area,persisted.activity().production().acquisition());
            assertEquals(List.of(source,sticks),ActivityRecipeEvidence.authorizedSources(persisted));
            assertTrue(persisted.activity().production().allowsSeparateSticks());
        }
    }

    @Test void anAbsentWindowKeepsTheOriginalOptionalPayloadRepresentation() throws Exception {
        var scene=new ActivityCheckpointsTest.Scene(false,true,true);
        var payload=scene.job().activity();var encoded=ActivityCodec.encode(payload);
        assertFalse(((Map<?,?>)((List<?>)encoded.get("steps")).getFirst()).containsKey("assignmentExpiresTick"));
        assertEquals(payload,ActivityCodec.decode(StrictJson.object(StrictJson.canonical(encoded))));assertNull(payload.current().assignmentExpiresTick());
    }
    @Test void theOriginalIntegratedWindowSurvivesTheActualJobJournal(@TempDir Path world) throws Exception {
        var scene=new ActivityCheckpointsTest.Scene(false,true,true);var original=scene.job();
        var p=original.activity();var step=p.current().assigned(original.current().id(),1234L);
        var payload=new ActivityPayload(p.mode(),p.policy(),p.plannedSteps(),p.currentStep(),List.of(step),p.checkpoints(),p.blocker(),p.production());
        var bound=new Job(original.id(),original.submissionId(),original.revision(),original.request(),original.parent(),
                original.children(),original.dependencies(),original.state(),original.reason(),original.cancellationOutcome(),
                original.allowance(),original.usage(),original.generation(),original.attempts(),original.events(),original.preparation(),payload);
        payload.validate(bound);assertEquals(payload,ActivityCodec.decode(StrictJson.object(StrictJson.canonical(ActivityCodec.encode(payload)))));
        var id=original.origin().scope().worldId();
        var snapshot=new Snapshot(id,1,List.of(bound),List.of());
        var row=StrictJson.object(new String(JobJournal.encode(snapshot),StandardCharsets.UTF_8).lines().skip(1).findFirst().orElseThrow());
        var stored=(Map<?,?>)((List<?>)row.get("attempts")).getFirst();
        assertEquals(step.id().toString(),stored.get("requestFromActivity"));
        assertFalse(stored.containsKey("request"));
        try(var journal=JobJournal.open(world,id)) {
            journal.replace(journal.snapshot(),snapshot);
        }
        try(var journal=JobJournal.open(world,id)) {
            assertFalse(journal.readOnly());assertEquals(bound,journal.snapshot().jobs().getFirst());
            assertEquals(1234L,journal.snapshot().jobs().getFirst().activity().current().assignmentExpiresTick());
        }
        assertEquals(original.allowance(),bound.allowance());assertEquals(original.current(),bound.current());
        assertEquals(original.generation(),bound.generation());
    }
    @Test void aWindowCannotBeInventedForAnUnassignedOrLegacyStep() {
        var scene=new ActivityCheckpointsTest.Scene(false,true,true);var s=scene.job().activity().current();
        assertThrows(IllegalArgumentException.class,()->s.assigned(s.attempt(),0L));
        assertThrows(IllegalArgumentException.class,()->new ActivityPayload.Step(s.id(),s.proposal(),s.token(),s.bodyDigest(),
                s.signature(),s.creditBefore(),s.quantity(),s.artifact(),s.choice(),s.quantities(),s.allowance(),
                s.preparedRevision(),null,s.invocation(),s.goalCredit(),1234L));
        assertThrows(IllegalArgumentException.class,()->new ActivityPayload.Step(s.id(),s.proposal(),s.token(),s.bodyDigest(),
                s.signature(),s.creditBefore(),s.quantity(),s.artifact(),s.choice(),s.quantities(),s.allowance(),
                s.preparedRevision(),s.attempt(),null,s.quantity(),1234L));
    }

    @Test void registeredPrefixReactivatesOnlyTheSameOriginalAttemptWithinItsWindow() {
        var valid = new ResumeScene(1233);
        assertTrue(valid.dispatcher.continueActivity(valid.job, valid.evidence));
        assertEquals(State.INTERRUPTED, valid.store.snapshot().jobs().getFirst().state());
        valid.store.tick();
        var continued = valid.store.query(valid.job.id(), valid.job.origin());
        assertEquals(State.ACTIVE, continued.state());
        assertEquals(valid.job.generation(), continued.generation());
        assertEquals(valid.job.current().id(), continued.current().id());
        assertEquals(valid.job.current().allowance(), continued.current().allowance());
        assertEquals(valid.job.current().executions(), continued.current().executions());
        assertEquals(valid.evidence.observation().report().resourceReceipts(), continued.current().resourceReceipts());
        assertEquals(valid.evidence.observation().report().usage(), continued.current().usage());
        assertEquals(1234L, continued.activity().current().assignmentExpiresTick());
        assertEquals(2, continued.activity().current().savedPrefixEffects());
        assertEquals(0, continued.fulfilled());
        assertFalse(valid.dispatcher.continueActivity(valid.job, valid.evidence));

        var expired = new ResumeScene(1234);
        assertFalse(expired.dispatcher.continueActivity(expired.job, expired.evidence));
        assertEquals(expired.job, expired.store.query(expired.job.id(), expired.job.origin()));
        var differentProcess = new ResumeScene(1233);
        assertFalse(differentProcess.dispatcher.continueActivity(differentProcess.job, valid.evidence));
    }

    private static final class ResumeScene {
        final Job job;
        final JobLifecycleStore store;
        final WorkerDispatcher dispatcher;
        final StoppedEvidence evidence;

        ResumeScene(long tick) {
            var scene = new ActivityCheckpointsTest.Scene(false, true, true);
            var prior = scene.job();
            var old = prior.current();
            var pickup = scene.entry.intent().pickupPrefix().pickup();
            var run = scene.entry.intent().run();
            var artifact = prior.activity().current().artifact();
            var attempt = new Attempt(run, old.generation(), old.worker(), old.bound(), old.allowance(),
                    List.of(new ExecutionReference(run, artifact, List.of(artifact))), old.usage(), List.of(), 0, 0,
                    old.terminal(), true, 0, List.of());
            var payload = prior.activity();
            var step = payload.current().assigned(run, 1234L);
            job = new Job(prior.id(), prior.submissionId(), prior.revision(), prior.request(), null, List.of(), List.of(),
                    prior.state(), prior.reason(), null, prior.allowance(), prior.usage(), prior.generation(), List.of(attempt),
                    prior.events(), null, new ActivityPayload(payload.mode(), payload.policy(), payload.plannedSteps(),
                    step.id(), List.of(step), List.of(), payload.blocker(), payload.production()));
            var clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC);
            store = new JobLifecycleStore(new Snapshot(job.origin().scope().worldId(), 1, List.of(job), List.of()),
                    (expected, next) -> CompletableFuture.completedFuture(next), JobLifecycleStore.privateJobs((a, c) -> true),
                    cancellation -> fail("No cancellation"), PlanningGoals.catalog(), new RequestEnvironment() {
                        public boolean enrolled(ActorRef a, TrustedContext c) { return true; }
                        public boolean loaded(Cuboid area, ObservationRef o) { return true; }
                        public boolean available(ContainerRef c, ObservationRef o) { return true; }
                    }, clock, Settings.defaults(), false);
            var report = new Report(run, attempt.generation(), 2, pickup.result().usage(), List.of(), null,
                    List.of(pickup.intent().harvest().result().receipt(), pickup.result().receipt()));
            evidence = store.attachRecovery(c -> new StoppedObservation(StoppedKind.CONTINUABLE, report, "Test native prefix"))
                    .capture(StoppedContext.of(job));
            dispatcher = new WorkerDispatcher(store, new WorkerDispatcher.Workers() {
                public boolean ready() { return true; }
                public CitizenRegistry.ControlPage page(TrustedContext c, UUID after, int maximum) { throw new AssertionError(); }
                public boolean controls(ActorRef actor, TrustedContext c) { return true; }
                public long revision() { return 1; }
                public CitizenRegistry.Availability availability(ActorRef actor, TrustedContext c) { return CitizenRegistry.Availability.LOADED; }
            }, (b, a) -> { throw new AssertionError(); }, (r, o) -> { throw new AssertionError(); }, new WorkerDispatcher.ExecutionPort() {
                public List<ArtifactRef> pinned(ArtifactRef a) { return List.of(a); }
                public WorkerDispatcher.Start start(ValidatedRequest b, ArtifactRef a, UUID id, Budgets.ExecutionLimits l, Budgets.Ledger u) {
                    throw new AssertionError("No execution in owner acknowledgement test");
                }
            }, new WorkerDispatcher.Claims() {
                public ResourceLeases.Result prepare(ValidatedRequest r, RunCorrelation correlation) { throw new AssertionError(); }
                public ResourceLeases.Result validate(ValidatedRequest r, RunCorrelation correlation) { throw new AssertionError(); }
                public void release(UUID id) { throw new AssertionError(); }
                public boolean released(UUID id) { return true; }
            }, j -> true, clock, () -> tick, WorkerDispatcher.Settings.production());
        }
    }
}
