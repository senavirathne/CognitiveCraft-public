package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Budgets.Kind;
import static org.junit.jupiter.api.Assertions.*;

class AIWorkBrokerTest {
    static final String DIM = "minecraft:overworld";
    static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final TrustedContext OWNER = owner(2, WORLD);
    static final ActorRef ACTOR = new ActorRef(new UUID(0, 4), new UUID(0, 5), DIM);
    static final ObservationRef OBS = new ObservationRef(new UUID(0, 6), 1, DIM);
    static TrustedContext owner(int id, UUID world) {
        return new TrustedContext(new PrincipalRef(new UUID(0, id)), new ScopeRef(world, new UUID(0, id)));
    }
    static Generation.Request request(TrustedContext owner, String context) {
        var bound = new ValidatedRequest(new CapabilityRequest(CropDelivery.ID, Map.of(
                "actor", new ActorValue(ACTOR), "source", new AreaValue(new Cuboid(DIM, 0, 1, 0, 0, 1, 0)),
                "destination", new ContainerValue(new ContainerRef(DIM, 2, 1, 0)), "amount", new IntValue(1))), owner, OBS);
        return new Generation.Request(UUID.randomUUID(), bound, CropDelivery.SPEC, List.of(), List.of(), Generation.Role.INITIAL, context);
    }
    static Budgets.InferenceLimits limits(long calls, long output, long deadline) {
        return new Budgets.InferenceLimits(new Budgets.Limits(Map.of(Kind.CALLS, calls, Kind.REPAIRS, Math.min(1, calls),
                Kind.INPUT_BYTES, 1000L, Kind.OUTPUT_BYTES, output), deadline), 500, output);
    }
    static final class Time extends Clock {
        long now = 1000;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
    }
    static final class Backend implements GenerationPort {
        final Time time; final List<Call> calls = new ArrayList<>(); Generation.State availability = Generation.State.READY;
        boolean confirmCancel, stallPreparation; int cancellations;
        Backend(Time time) { this.time = time; }
        @Override public Generation.Descriptor descriptor() { return new Generation.Descriptor("ollama-chat-v1", "controlled-fake", null); }
        @Override public Generation.Status status() {
            Call active = calls.stream().filter(c -> c.compute == Generation.Compute.RUNNING
                    || c.compute == Generation.Compute.STOP_UNCONFIRMED
                    || c.compute == Generation.Compute.NOT_STARTED && !c.future.isDone()).findFirst().orElse(null);
            return new Generation.Status(active == null ? availability : Generation.State.IN_FLIGHT, 0,
                    active == null ? null : active.id(), active == null ? Generation.Compute.NOT_STARTED : active.compute, false, false);
        }
        @Override public Generation.Handle generate(Generation.Request request, Budgets.InferenceLimits limits, Budgets.Ledger usage) {
            var call = new Call(request, limits, usage); calls.add(call); if (!stallPreparation) call.prepare(); return call;
        }
        Call latest() { return calls.getLast(); }
        final class Call implements Generation.Handle {
            final Generation.Request request; final Budgets.InferenceLimits limits; final Budgets.Ledger usage;
            final CompletableFuture<Generation.Result> future = new CompletableFuture<>();
            Generation.Compute compute = Generation.Compute.NOT_STARTED; Budgets.ResponseAllowance response;
            Call(Generation.Request request, Budgets.InferenceLimits limits, Budgets.Ledger usage) { this.request=request;this.limits=limits;this.usage=usage; }
            void prepare() {
                try { response=limits.chargeCall(usage,16,request.role()==Generation.Role.REPAIR);compute=Generation.Compute.RUNNING; }
                catch(Budgets.Exhausted exhausted) { complete(Generation.Outcome.LIMIT, Outcomes.Reason.BUDGET_EXHAUSTED, null); }
            }
            void emit(long bytes) {
                try { response.accept(bytes);complete(Generation.Outcome.CANDIDATE,null,"{}"); }
                catch(Budgets.Exhausted exhausted) { complete(Generation.Outcome.LIMIT,Outcomes.Reason.BUDGET_EXHAUSTED,null); }
            }
            void complete(Generation.Outcome outcome,Outcomes.Reason reason,String candidate) {
                compute=Generation.Compute.COMPLETED;
                var snapshot=usage.snapshot();future.complete(new Generation.Result(id(),request.bound().context(),request.role(),outcome,reason,candidate,
                        descriptor(),new Generation.Usage(snapshot.getOrDefault(Kind.INPUT_BYTES,0L),snapshot.getOrDefault(Kind.OUTPUT_BYTES,0L),-1,-1,Generation.Precision.UNKNOWN),compute));
            }
            @Override public UUID id(){return request.id();}
            @Override public CompletionStage<Generation.Result> result(){return future;}
            @Override public boolean cancel(){cancellations++;compute=confirmCancel?Generation.Compute.STOP_CONFIRMED:Generation.Compute.STOP_UNCONFIRMED;return true;}
            @Override public Generation.Compute compute(){return compute;}
        }
    }
    static final class Fixture {
        final Time time=new Time();final Backend backend=new Backend(time);final AIWorkBroker broker;
        boolean allowed=true;
        Fixture(){this(AIWorkBroker.Sharing.privateScopes());}
        Fixture(AIWorkBroker.Sharing sharing){broker=new AIWorkBroker(backend,time,AIWorkBroker.Settings.defaults(),AIWorkBroker.sessionLimits(time.millis()),r->allowed,sharing);}
        Generation.Handle submit(String context){var limit=limits(2,100,10000);return broker.generate(request(OWNER,context),limit,new Budgets.Ledger(limit.total(),time));}
        AIWorkBroker.View view(Generation.Handle handle){return broker.view(handle.id(),OWNER);}
    }
    @Test void capacityFourRejectsFifthAndNeverDispatchesInSubmit(){
        var f=new Fixture();for(int i=0;i<4;i++)f.submit("private "+i);var rejected=f.submit("fifth");
        assertEquals(AIWorkBroker.State.REJECTED,f.view(rejected).state());assertEquals(4,f.broker.stats().queuedWork());assertTrue(f.backend.calls.isEmpty());
        f.broker.step();assertEquals(1,f.backend.calls.size());assertEquals(1,f.broker.stats().occupied());
    }
    @Test void occupiedComputePlusFourWaitingStillBoundsCapacity(){var f=new Fixture();f.submit("active");f.broker.step();for(int i=0;i<4;i++)f.submit("wait"+i);assertEquals(AIWorkBroker.State.REJECTED,f.view(f.submit("overflow")).state());assertEquals(1,f.backend.calls.size());}
    @Test void equivalentAuthorizedRequestsShareCallAndOwnResultIds(){
        var f=new Fixture();var a=f.submit("same");var b=f.submit("same");assertNotEquals(a.id(),b.id());assertEquals(f.view(a).work(),f.view(b).work());
        f.broker.step();f.backend.latest().emit(9);f.broker.step();assertEquals(1,f.backend.calls.size());
        assertEquals(a.id(),a.result().toCompletableFuture().join().id());assertEquals(b.id(),b.result().toCompletableFuture().join().id());assertEquals(9,f.broker.stats().outputBytes());
    }
    @Test void privateScopesDoNotCoalesce(){var f=new Fixture();f.submit("same");var l=limits(2,100,10000);var other=request(owner(3,WORLD),"same");f.broker.generate(other,l,new Budgets.Ledger(l.total(),f.time));assertEquals(2,f.broker.stats().queuedWork());}
    @Test void privatePromptContextsDoNotCoalesce(){var f=new Fixture();f.submit("one secret");f.submit("another secret");assertEquals(2,f.broker.stats().queuedWork());}
    @Test void contractVersionsDoNotCoalesce(){var f=new Fixture();f.submit("same");var l=limits(2,100,10000);f.broker.submit(request(OWNER,"same"),l,new Budgets.Ledger(l.total(),f.time),new AIWorkBroker.Contract("research-generation",2,1),AIWorkBroker.Priority.NORMAL);assertEquals(2,f.broker.stats().queuedWork());}
    @Test void rolesDoNotCoalesce(){var f=new Fixture();f.submit("same");var a=request(OWNER,"same");var repair=new Generation.Request(a.id(),a.bound(),a.capability(),a.primitives(),a.dependencies(),Generation.Role.REPAIR,a.context());var l=limits(2,100,10000);f.broker.generate(repair,l,new Budgets.Ledger(l.total(),f.time));assertEquals(2,f.broker.stats().queuedWork());}
    @Test void observationsDoNotCoalesce(){var f=new Fixture();f.submit("same");var a=request(OWNER,"same");var b=new ValidatedRequest(a.bound().request(),OWNER,new ObservationRef(UUID.randomUUID(),2,DIM));var l=limits(2,100,10000);f.broker.generate(new Generation.Request(a.id(),b,a.capability(),a.primitives(),a.dependencies(),a.role(),a.context()),l,new Budgets.Ledger(l.total(),f.time));assertEquals(2,f.broker.stats().queuedWork());}
    @Test void explicitSharingRequiresExactContextAndKeepsRecipientsSeparate(){
        var f=new Fixture((a,b)->true);var a=f.submit("explicit shared snapshot");var l=limits(2,100,10000);var owner=owner(3,WORLD);var b=f.broker.generate(request(owner,"explicit shared snapshot"),l,new Budgets.Ledger(l.total(),f.time));
        assertEquals(1,f.broker.stats().queuedWork());f.broker.step();f.backend.latest().emit(9);f.broker.step();assertEquals(owner,b.result().toCompletableFuture().join().owner());assertEquals(OWNER,a.result().toCompletableFuture().join().owner());
        assertThrows(SecurityException.class,()->f.broker.view(a.id(),owner));assertEquals(1,f.broker.queueStatus(owner).size());
    }
    @Test void sharingCannotBridgeDifferentWorlds(){var f=new Fixture((a,b)->true);f.submit("shared");var l=limits(2,100,10000);f.broker.generate(request(owner(3,UUID.randomUUID()),"shared"),l,new Budgets.Ledger(l.total(),f.time));assertEquals(2,f.broker.stats().queuedWork());}
    @Test void sharingCannotMergeDifferentPrivateFields(){var f=new Fixture((a,b)->true);f.submit("secret A");var l=limits(2,100,10000);f.broker.generate(request(owner(3,WORLD),"secret B"),l,new Budgets.Ledger(l.total(),f.time));assertEquals(2,f.broker.stats().queuedWork());}
    @Test void cancelOneKeepsOtherSubscriberAndDoesNotStopBackend(){var f=new Fixture();var a=f.submit("same");var b=f.submit("same");f.broker.step();assertTrue(a.cancel());assertFalse(a.cancel());assertEquals(0,f.backend.cancellations);f.backend.latest().emit(9);f.broker.step();assertEquals(AIWorkBroker.State.CANCELLED,f.view(a).state());assertEquals(AIWorkBroker.State.CANDIDATE,f.view(b).state());assertNull(a.result().toCompletableFuture().join().candidateIr());}
    @Test void lastCancelRequestsOneStopAndUnconfirmedWorkOccupiesSlot(){var f=new Fixture();var a=f.submit("a");f.broker.step();a.cancel();f.submit("replacement");for(int i=0;i<12;i++)f.broker.step();assertEquals(1,f.backend.cancellations);assertEquals(1,f.backend.calls.size());assertEquals(1,f.broker.stats().unconfirmed());assertEquals(Generation.Compute.STOP_UNCONFIRMED,f.view(a).compute());}
    @Test void confirmedStopAllowsNextWork(){var f=new Fixture();f.backend.confirmCancel=true;var a=f.submit("a");f.broker.step();a.cancel();f.submit("b");f.broker.step();assertEquals(2,f.backend.calls.size());assertEquals(Generation.Compute.STOP_CONFIRMED,f.view(a).compute());}
    @Test void lateCompletionCannotReviveCancelledSubscriber(){var f=new Fixture();var a=f.submit("a");f.broker.step();a.cancel();f.backend.latest().emit(10);f.broker.step();assertEquals(AIWorkBroker.State.CANCELLED,f.view(a).state());assertEquals(10,f.view(a).chargedUsage().get(Kind.OUTPUT_BYTES));assertEquals(0,f.broker.stats().occupied());}
    @Test void duplicateCompletionDoesNotDoubleCharge(){var f=new Fixture();var a=f.submit("a");f.broker.step();var c=f.backend.latest();c.emit(10);f.broker.step();c.complete(Generation.Outcome.CANDIDATE,null,"{}");f.broker.step();assertEquals(1,f.broker.stats().calls());assertEquals(10,f.broker.stats().outputBytes());assertEquals(AIWorkBroker.State.CANDIDATE,f.view(a).state());}
    @Test void duplicateSubmissionReturnsSameHandleAndCannotChangeItsAllowance(){var f=new Fixture();var r=request(OWNER,"a");var l=limits(2,100,10000);var parent=new Budgets.Ledger(l.total(),f.time);var a=f.broker.generate(r,l,parent);assertSame(a,f.broker.generate(r,l,parent));assertThrows(IllegalArgumentException.class,()->f.broker.generate(r,l,new Budgets.Ledger(l.total(),f.time)));assertEquals(1,f.broker.stats().subscribers());}
    @Test void sharedChildrenChargeAncestorOnceAndEachBeneficiaryOnce(){var f=new Fixture();var l=limits(1,100,10000);var root=new Budgets.Ledger(l.total(),f.time);var left=root.child(l.total());var right=root.child(l.total());f.broker.generate(request(OWNER,"a"),l,left);f.broker.generate(request(OWNER,"a"),l,right);f.broker.step();f.backend.latest().emit(10);f.broker.step();assertEquals(1,root.snapshot().get(Kind.CALLS));assertEquals(10,root.snapshot().get(Kind.OUTPUT_BYTES));assertEquals(1,left.snapshot().get(Kind.CALLS));assertEquals(1,right.snapshot().get(Kind.CALLS));}
    @Test void cancellationAndResubmissionDoNotResetParentAllowance(){var f=new Fixture();var l=limits(1,100,10000);var root=new Budgets.Ledger(l.total(),f.time);var a=f.broker.generate(request(OWNER,"a"),l,root);f.broker.step();a.cancel();var retry=f.broker.generate(request(OWNER,"retry"),l,root);assertEquals(AIWorkBroker.State.REJECTED,f.view(retry).state());assertEquals(1,root.snapshot().get(Kind.CALLS));}
    @Test void repairUsesOriginalRemainingAllowance(){var f=new Fixture();var l=limits(2,100,10000);var root=new Budgets.Ledger(l.total(),f.time);f.broker.generate(request(OWNER,"initial"),l,root);f.broker.step();f.backend.latest().emit(10);f.broker.step();var r=request(OWNER,"repair");f.broker.generate(new Generation.Request(r.id(),r.bound(),r.capability(),r.primitives(),r.dependencies(),Generation.Role.REPAIR,r.context()),l,root);f.broker.step();f.backend.latest().emit(10);f.broker.step();assertEquals(2,root.snapshot().get(Kind.CALLS));assertEquals(1,root.snapshot().get(Kind.REPAIRS));assertEquals(20,root.snapshot().get(Kind.OUTPUT_BYTES));}
    @Test void lowerSubscriberOutputCapBoundsSharedResponse(){var f=new Fixture();var a=f.submit("same");var l=limits(2,5,10000);var b=f.broker.generate(request(OWNER,"same"),l,new Budgets.Ledger(l.total(),f.time));f.broker.step();assertEquals(5,f.backend.latest().limits.perResponseOutputBytes());f.backend.latest().emit(6);f.broker.step();assertEquals(AIWorkBroker.State.EXPIRED,f.view(a).state());assertEquals(AIWorkBroker.State.EXPIRED,f.view(b).state());}
    @Test void queuedTimeCountsTowardOriginalDeadline(){var f=new Fixture();var l=limits(2,100,1100);var a=f.broker.generate(request(OWNER,"a"),l,new Budgets.Ledger(l.total(),f.time));f.time.now=1100;f.broker.step();assertEquals(AIWorkBroker.State.EXPIRED,f.view(a).state());assertTrue(f.backend.calls.isEmpty());}
    @Test void coldPreparationCannotRefreshDeadline(){var f=new Fixture();f.backend.stallPreparation=true;var l=limits(2,100,1100);var a=f.broker.generate(request(OWNER,"a"),l,new Budgets.Ledger(l.total(),f.time));f.broker.step();f.time.now=1101;f.broker.step();f.backend.latest().prepare();f.broker.step();assertEquals(AIWorkBroker.State.EXPIRED,f.view(a).state());assertEquals(0,f.broker.stats().calls());}
    @Test void tightestDeadlineRemainsSealedAfterCancellation(){var f=new Fixture();var l=limits(2,100,1100);var a=f.broker.generate(request(OWNER,"same"),l,new Budgets.Ledger(l.total(),f.time));f.submit("same");f.broker.step();a.cancel();assertEquals(1100,f.backend.latest().limits.total().deadlineEpochMillis());}
    @Test void outageReturnsExplicitTerminalStatusWithoutAccumulatingRetryWork(){var f=new Fixture();f.backend.availability=Generation.State.UNAVAILABLE;for(int i=0;i<100;i++){var a=f.submit("a"+i);assertEquals(AIWorkBroker.State.UNAVAILABLE,f.view(a).state());}assertEquals(0,f.broker.stats().queuedWork());assertTrue(f.backend.calls.isEmpty());assertTrue(f.broker.stats().retained()<=64);}
    @Test void disabledGateDoesNotResetSpentBudget(){var f=new Fixture();var a=f.submit("a");f.broker.step();f.broker.enabled(false);assertEquals(AIWorkBroker.State.UNAVAILABLE,f.view(a).state());f.broker.enabled(true);f.submit("b");f.broker.step();assertEquals(1,f.backend.calls.size());assertEquals(1,f.broker.stats().calls());}
    @Test void authorityRevokedBeforeDispatchDiscardsWithoutCall(){var f=new Fixture();var a=f.submit("a");f.allowed=false;f.broker.step();assertEquals(AIWorkBroker.State.DISCARDED,f.view(a).state());assertTrue(f.backend.calls.isEmpty());}
    @Test void authorityRevokedBeforeDeliveryNeverReturnsPrivateCandidate(){var f=new Fixture();var a=f.submit("a");f.broker.step();f.backend.latest().emit(10);f.allowed=false;f.broker.step();assertEquals(AIWorkBroker.State.DISCARDED,f.view(a).state());assertNull(a.result().toCompletableFuture().join().candidateIr());}
    @Test void queueStatusAndCancelRequireExactScope(){var f=new Fixture();var a=f.submit("secret");var foreign=owner(3,WORLD);assertTrue(f.broker.queueStatus(foreign).isEmpty());assertThrows(SecurityException.class,()->f.broker.cancel(a.id(),foreign));assertThrows(SecurityException.class,()->f.broker.view(a.id(),foreign));}
    @Test void priorityOrderIsStableAndAgingPreventsStarvation(){
        var f=new Fixture();var l=limits(2,100,10000);var background=f.broker.submit(request(OWNER,"background"),l,new Budgets.Ledger(l.total(),f.time),AIWorkBroker.RESEARCH,AIWorkBroker.Priority.BACKGROUND);
        for(int i=0;i<3;i++)f.broker.submit(request(OWNER,"urgent"+i),l,new Budgets.Ledger(l.total(),f.time),AIWorkBroker.RESEARCH,AIWorkBroker.Priority.URGENT);
        int rounds=0;while(!f.view(background).state().terminal()&&rounds<9){f.broker.step();f.backend.latest().emit(1);f.broker.step();rounds++;if(f.broker.stats().queuedWork()<4)f.broker.submit(request(OWNER,"new urgent"+rounds),l,new Budgets.Ledger(l.total(),f.time),AIWorkBroker.RESEARCH,AIWorkBroker.Priority.URGENT);}
        assertEquals("urgent0",f.backend.calls.getFirst().request.context());assertEquals(AIWorkBroker.State.CANDIDATE,f.view(background).state());assertTrue(rounds<=8);
    }
    @Test void eachSliceAndSubscriberCohortAreBounded(){var f=new Fixture();for(int group=0;group<4;group++)for(int i=0;i<8;i++)f.submit("group"+group);var slice=f.broker.step();assertTrue(slice.workInspected()<=5);assertTrue(slice.subscribersInspected()<=40);assertTrue(slice.starts()<=1);assertEquals(32,f.broker.stats().subscribers());}
    @Test void ninthSubscriberCannotGrowOneCohort(){var f=new Fixture();for(int i=0;i<9;i++)f.submit("same");assertEquals(2,f.broker.stats().queuedWork());assertEquals(7,f.broker.stats().coalesced());}
    @Test void completedComputeWaitsForBoundedResultHandoff(){var f=new Fixture();var a=f.submit("a");f.broker.step();f.backend.latest().compute=Generation.Compute.COMPLETED;f.broker.step();assertEquals(1,f.broker.stats().occupied());f.backend.latest().emit(1);f.broker.step();assertEquals(AIWorkBroker.State.CANDIDATE,f.view(a).state());}
    @Test void inFlightCohortCannotGainFreshSubscriberAllowance(){var f=new Fixture();var a=f.submit("same");f.broker.step();var b=f.submit("same");assertNotEquals(f.view(a).work(),f.view(b).work());assertEquals(1,f.broker.stats().queuedWork());}
    @Test void explicitSubscribeUsesOriginalCohortAndScope(){var f=new Fixture();var a=f.submit("same");var l=limits(2,100,10000);var b=f.broker.subscribe(f.view(a).work(),request(OWNER,"same"),l,new Budgets.Ledger(l.total(),f.time),AIWorkBroker.RESEARCH,AIWorkBroker.Priority.NORMAL);assertEquals(f.view(a).work(),f.view(b).work());}
    @Test void shutdownAbandonsPendingAndNewInstanceNeverReplays(@TempDir Path world)throws Exception{
        Files.writeString(world.resolve("authoritative.json"),"unchanged");var f=new Fixture();var a=f.submit("active");f.broker.step();var b=f.submit("queued");f.broker.close();assertEquals(AIWorkBroker.State.ABANDONED,f.view(a).state());assertEquals(AIWorkBroker.State.ABANDONED,f.view(b).state());
        var restarted=new AIWorkBroker(f.backend,f.time,AIWorkBroker.Settings.defaults(),AIWorkBroker.sessionLimits(f.time.millis()),r->true,AIWorkBroker.Sharing.privateScopes());restarted.step();assertEquals(1,f.backend.calls.size());assertEquals(0,restarted.stats().queuedWork());assertEquals("unchanged",Files.readString(world.resolve("authoritative.json")));assertEquals(1,Files.list(world).count());
    }
    @Test void ownerThreadIsRequired()throws Exception{var f=new Fixture();var failed=new CompletableFuture<Throwable>();var thread=new Thread(()->{try{f.broker.step();failed.complete(null);}catch(Throwable error){failed.complete(error);}});thread.start();thread.join(1000);assertInstanceOf(IllegalStateException.class,failed.join());}
    @Test void sharedDebitFailureIsAtomicAcrossIndependentRoots(){var time=new Time();var l=limits(1,100,10000);var a=new Budgets.Ledger(l.total(),time);var b=new Budgets.Ledger(l.total(),time);var shared=Budgets.Ledger.shared(l.total(),time,List.of(a,b));b.debit(Kind.CALLS,1);assertThrows(Budgets.Exhausted.class,()->shared.debit(Kind.CALLS,1));assertFalse(a.snapshot().containsKey(Kind.CALLS));assertFalse(shared.snapshot().containsKey(Kind.CALLS));}
    @Test void controlledBurstLoadPublishesActualBounds() throws Exception {
        var f=new Fixture();long start=System.nanoTime();
        for(int i=0;i<1000;i++)f.submit("burst private context "+i);
        assertEquals(4,f.broker.stats().queuedWork());assertEquals(996,f.broker.stats().rejected());
        int maxWork=0,maxSubscribers=0,maxStarts=0,maxOccupied=0;long maxSliceNanos=0;
        while(f.broker.stats().queuedWork()>0||f.broker.stats().occupied()>0){
            long before=System.nanoTime();var slice=f.broker.step();maxSliceNanos=Math.max(maxSliceNanos,System.nanoTime()-before);
            maxWork=Math.max(maxWork,slice.workInspected());maxSubscribers=Math.max(maxSubscribers,slice.subscribersInspected());maxStarts=Math.max(maxStarts,slice.starts());maxOccupied=Math.max(maxOccupied,f.broker.stats().occupied());
            if(f.broker.stats().occupied()>0)f.backend.latest().emit(10);
        }
        var stats=f.broker.stats();assertEquals(4,stats.calls());assertEquals(40,stats.outputBytes());assertTrue(stats.retained()<=64);
        Path evidence=Path.of("build/broker-evidence");Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("load.json"),"{\"schema\":1,\"backend\":\"controlled-fake\",\"requests\":1000,\"acceptedWork\":4,\"rejected\":996,\"calls\":4,\"outputBytes\":40,\"maxWork\":"+maxWork+",\"maxSubscribers\":"+maxSubscribers+",\"maxStarts\":"+maxStarts+",\"maxOccupied\":"+maxOccupied+",\"retained\":"+stats.retained()+",\"elapsedNanos\":"+(System.nanoTime()-start)+",\"maxSliceNanos\":"+maxSliceNanos+"}\n");
    }
    @Test void unconfirmedCancellationHoldsOutputAndUnknownCompletionForfeitsIt(){
        var f=new Fixture();var l=limits(2,100,10000);var root=new Budgets.Ledger(l.total(),f.time);
        var a=f.broker.generate(request(OWNER,"a"),l,root);f.broker.step();assertEquals(0,root.remaining(Kind.OUTPUT_BYTES));a.cancel();
        assertEquals(100,f.view(a).reservedOutputBytes());assertEquals(0,root.remaining(Kind.OUTPUT_BYTES));
        f.backend.latest().complete(Generation.Outcome.ABANDONED,Outcomes.Reason.CANCELLED,null);f.broker.step();
        assertEquals(100,root.snapshot().get(Kind.OUTPUT_BYTES));assertEquals(100,f.broker.stats().unmeasuredOutputBytes());assertEquals(0,f.broker.stats().outputBytes());
        assertEquals(AIWorkBroker.State.REJECTED,f.view(f.broker.generate(request(OWNER,"retry"),l,root)).state());
    }
    @Test void confirmedCancellationAfterDeadlineStillAccountsReservedUnknownOutput(){
        var f=new Fixture();f.backend.confirmCancel=true;var l=limits(2,100,1100);var root=new Budgets.Ledger(l.total(),f.time);
        var a=f.broker.generate(request(OWNER,"a"),l,root);f.broker.step();f.time.now=1101;f.broker.step();
        assertEquals(AIWorkBroker.State.EXPIRED,f.view(a).state());assertEquals(100,root.snapshot().get(Kind.OUTPUT_BYTES));assertEquals(0,f.broker.stats().occupied());
    }
    @Test void measuredCompletionReleasesOnlyUnusedOutputReservation(){
        var f=new Fixture();var l=limits(2,100,10000);var root=new Budgets.Ledger(l.total(),f.time);f.broker.generate(request(OWNER,"a"),l,root);f.broker.step();
        assertEquals(0,root.remaining(Kind.OUTPUT_BYTES));f.backend.latest().emit(10);f.broker.step();assertEquals(90,root.remaining(Kind.OUTPUT_BYTES));assertEquals(10,root.snapshot().get(Kind.OUTPUT_BYTES));assertEquals(0,f.broker.stats().reservedOutputBytes());
    }
    @Test void concurrentParentConsumptionCannotPartiallyChargeSharedWork(){
        var f=new Fixture();var l=limits(1,100,10000);var first=new Budgets.Ledger(l.total(),f.time);var second=new Budgets.Ledger(l.total(),f.time);
        var a=f.broker.generate(request(OWNER,"same"),l,first);f.broker.generate(request(OWNER,"same"),l,second);first.debit(Kind.CALLS,1);f.broker.step();
        assertEquals(AIWorkBroker.State.REJECTED,f.view(a).state());assertTrue(f.backend.calls.isEmpty());assertTrue(second.snapshot().isEmpty());assertEquals(100,second.remaining(Kind.OUTPUT_BYTES));
    }
    @Test void sharedReservationFinalizationIsIdempotentAndDeduplicatesCommonAncestor(){
        var time=new Time();var l=limits(2,100,10000);var parent=new Budgets.Ledger(l.total(),time);var shared=Budgets.Ledger.shared(l.total(),time,List.of(parent.child(l.total()),parent.child(l.total())));
        var hold=shared.reserveOutput(100);assertEquals(0,parent.remaining(Kind.OUTPUT_BYTES));shared.debit(Kind.OUTPUT_BYTES,10);assertEquals(90,hold.forfeit());assertEquals(0,hold.forfeit());hold.release();assertEquals(100,parent.snapshot().get(Kind.OUTPUT_BYTES));assertEquals(0,parent.remaining(Kind.OUTPUT_BYTES));
    }
}
