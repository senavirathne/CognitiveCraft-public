package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;

import static dev.aivillages.core.kernel.Outcomes.Reason;
import static dev.aivillages.core.kernel.WorldReferenceResolver.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldReferenceResolverTest {
    private static final Criteria FAKE_FACILITY = new Criteria("test:fake_facility");

    @Test void candidateOrderingIsChargedAndCannotExceedTotalWork() {
        Registry registry = new Registry();
        registry.register(Kind.FACILITY, FAKE_FACILITY, search -> new Discovery<String>() {
            public Scan<String> poll(int maxWork) {
                return Scan.complete(List.of(candidate("a", 1,64,0), candidate("b", 2,64,0)),
                        maxWork, Reason.FACILITY_MISSING, List.of());
            }
            public void cancel() { }
        });
        var attempt = registry.<String>start(search(new Point("minecraft:overworld",0,64,0),1,100),0);
        var result = attempt.poll(1);
        assertEquals(Phase.FAILED, result.phase());
        assertEquals(Reason.BUDGET_EXHAUSTED, result.reason());
        assertNull(result.value());
    }

    @Test void completeScanSelectsNearestDeterministicallyRegardlessOfDiscoveryOrder() {
        Point anchor = new Point("minecraft:overworld", 0, 64, 0);
        Candidate<String> far = candidate("far", 5, 64, 0);
        Candidate<String> tieB = candidate("tie-b", 2, 64, 1);
        Candidate<String> tieA = candidate("tie-a", 2, 64, -1);

        assertEquals("tie-a", resolve(anchor, List.of(far, tieB, tieA)));
        assertEquals("tie-a", resolve(anchor, List.of(tieA, far, tieB)));
    }

    @Test void provisionalCandidateIsNotNearestUntilDiscoveryCompletes() {
        Registry registry = new Registry();
        registry.register(Kind.FACILITY, FAKE_FACILITY, search -> new Discovery<String>() {
            int polls;
            @Override public Scan<String> poll(int maxWork) {
                polls++;
                return polls == 1
                        ? Scan.progress(List.of(candidate("provisional", 8, 64, 0)), 1, List.of())
                        : Scan.complete(List.of(candidate("closer", 1, 64, 0)), 1,
                                Reason.FACILITY_MISSING, List.of());
            }
            @Override public void cancel() { }
        });
        Search search = search(new Point("minecraft:overworld", 0, 64, 0), 10, 100);
        Attempt<String> attempt = registry.start(search, 0);
        assertEquals(Phase.RESOLVING, attempt.poll(1).phase());
        Result<String> done = attempt.poll(2);
        assertEquals(Phase.RESOLVED, done.phase());
        assertEquals("closer", done.value());
    }

    @Test void exhaustingWorkWithAProvisionalCandidateDoesNotClaimNearest() {
        Registry registry = new Registry();
        registry.register(Kind.FACILITY, FAKE_FACILITY, search -> new Discovery<String>() {
            @Override public Scan<String> poll(int maxWork) {
                return Scan.progress(List.of(candidate("provisional", 1, 64, 0)), maxWork, List.of());
            }
            @Override public void cancel() { }
        });
        Search search = search(new Point("minecraft:overworld", 0, 64, 0), 1, 100);
        Result<String> result = registry.<String>start(search, 0).poll(1);
        assertEquals(Phase.FAILED, result.phase());
        assertEquals(Reason.BUDGET_EXHAUSTED, result.reason());
        assertNull(result.value());
    }

    @Test void cancellationStopsTheAttemptAndLatePollCannotResolve() {
        boolean[] cancelled = {false};
        Registry registry = new Registry();
        registry.register(Kind.FACILITY, FAKE_FACILITY, search -> new Discovery<String>() {
            @Override public Scan<String> poll(int maxWork) {
                return Scan.progress(List.of(), 1, List.of());
            }
            @Override public void cancel() { cancelled[0] = true; }
        });
        Attempt<String> attempt = registry.start(search(new Point("minecraft:overworld", 0, 64, 0), 10, 100), 0);
        assertEquals(Phase.CANCELLED, attempt.cancel().phase());
        assertTrue(cancelled[0]);
        assertEquals(Phase.CANCELLED, attempt.poll(50).phase());
    }

    @Test void nonWheatProviderDemonstratesSharedPolicyReuse() {
        Registry registry = new Registry();
        registry.register(Kind.FACILITY, FAKE_FACILITY, search -> {
            ArrayDeque<Candidate<String>> values = new ArrayDeque<>(List.of(
                    candidate("smithy", 7, 64, 0), candidate("mill", 3, 64, 0)));
            return new Discovery<String>() {
                @Override public Scan<String> poll(int maxWork) {
                    Candidate<String> next = values.pollFirst();
                    return values.isEmpty()
                            ? Scan.complete(next == null ? List.of() : List.of(next), 1,
                                    Reason.FACILITY_MISSING, List.of("fake-provider"))
                            : Scan.progress(List.of(next), 1, List.of());
                }
                @Override public void cancel() { values.clear(); }
            };
        });
        Attempt<String> attempt = registry.start(
                search(new Point("minecraft:overworld", 0, 64, 0), 10, 100), 0);
        Result<String> result = attempt.poll(1);
        while (result.phase() == Phase.RESOLVING) result = attempt.poll(2);
        assertEquals(Phase.RESOLVED, result.phase());
        assertEquals("mill", result.value());
        assertEquals(List.of("fake-provider"), result.diagnostics());
    }

    @Test void dependentSearchesShareAnAllowanceAndCannotResetIt() {
        Registry registry = new Registry();
        registry.register(Kind.FACILITY,FAKE_FACILITY, search -> new Discovery<String>() {
            public Scan<String> poll(int maxWork) { return Scan.complete(List.of(candidate("one",1,64,0)),2,Reason.FACILITY_MISSING,List.of()); }
            public void cancel() { }
        });
        var search = search(new Point("minecraft:overworld",0,64,0),10,100);
        var allowance = new Allowance(5);
        assertEquals(Phase.RESOLVED,registry.<String>start(search,0,allowance).poll(1).phase());
        assertEquals(3,allowance.used());
        var next = registry.<String>start(search,1,allowance).poll(2);
        assertEquals(Phase.FAILED,next.phase()); assertEquals(Reason.BUDGET_EXHAUSTED,next.reason());
        assertEquals(5,allowance.used()); assertNull(next.value());
    }
    @Test void providerExhaustionAfterAProvisionalCandidateCannotEstablishNearest() {
        Registry registry = new Registry();
        registry.register(Kind.FACILITY,FAKE_FACILITY,search -> new Discovery<String>() {
            int polls;
            public Scan<String> poll(int maxWork) { return ++polls == 1
                    ? Scan.progress(List.of(candidate("provisional",1,64,0)),1,List.of())
                    : Scan.complete(List.of(),1,Reason.BUDGET_EXHAUSTED,List.of()); }
            public void cancel() { }
        });
        var attempt=registry.<String>start(search(new Point("minecraft:overworld",0,64,0),10,100),0);
        assertEquals(Phase.RESOLVING,attempt.poll(1).phase());
        var result=attempt.poll(2); assertEquals(Phase.FAILED,result.phase());
        assertEquals(Reason.BUDGET_EXHAUSTED,result.reason()); assertNull(result.value());
    }
    @Test void diagnosticsAreChargedButRetainedOnlyToTheDeclaredLimit() {
        Registry registry = new Registry();
        registry.register(Kind.FACILITY,FAKE_FACILITY, search -> new Discovery<String>() {
            public Scan<String> poll(int maxWork) { return Scan.complete(List.of(candidate("one",1,64,0)),1,
                    Reason.FACILITY_MISSING,List.of("a","b","c","d")); }
            public void cancel() { }
        });
        var attempt=registry.<String>start(new Search(Kind.FACILITY,Relation.NEAREST,
                new Point("minecraft:overworld",0,64,0),FAKE_FACILITY,new Limits(4,20,1,2,100)),0);
        assertEquals(Phase.RESOLVING,attempt.poll(1).phase());
        var result=attempt.poll(2); assertEquals(Phase.RESOLVED,result.phase());
        assertEquals(6,result.chargedWork()); assertEquals(List.of("a","b"),result.diagnostics());
        assertSame(result,attempt.poll(3)); assertSame(result,attempt.cancel());
    }
    @Test void deadlineDoesNotInspectAndLargeCoordinatesRetainExactDistanceOrder() {
        int[] polls={0}; Registry registry=new Registry();
        registry.register(Kind.FACILITY,FAKE_FACILITY, search -> new Discovery<String>() {
            public Scan<String> poll(int maxWork) { polls[0]++; return Scan.complete(List.of(),0,Reason.FACILITY_MISSING,List.of()); }
            public void cancel() { }
        });
        var attempt=registry.<String>start(search(new Point("minecraft:overworld",0,64,0),10,100),0);
        assertEquals(Reason.BUDGET_EXHAUSTED,attempt.poll(101).reason()); assertEquals(0,polls[0]);
        var anchor=new Point("minecraft:overworld",Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE);
        assertEquals("near",resolve(anchor,List.of(candidate("far",Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE),
                candidate("near",0,0,0))));
    }

    private static String resolve(Point anchor, List<Candidate<String>> candidates) {
        return candidates.stream().min(WorldReferenceResolver.candidateOrder(anchor)).orElseThrow().value();
    }

    private static Search search(Point anchor, long total, long deadline) {
        return new Search(Kind.FACILITY, Relation.NEAREST, anchor, FAKE_FACILITY,
                new Limits((int)Math.min(4, total), total, 16, 8, deadline));
    }

    private static Candidate<String> candidate(String id, int x, int y, int z) {
        return new Candidate<>(id, new Point("minecraft:overworld", x, y, z), id);
    }
}
