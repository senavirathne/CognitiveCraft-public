package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LiveAttemptAuthorityTest {
    private Jobs.Job original() {
        String dimension = "minecraft:overworld";
        var actor = new ActorRef(UUID.randomUUID(), UUID.randomUUID(), dimension);
        var context = new TrustedContext(new PrincipalRef(UUID.randomUUID()), new ScopeRef(UUID.randomUUID(), UUID.randomUUID()));
        var request = new ValidatedRequest(new CapabilityRequest(OakLogDelivery.ID, Map.of(
                "actor", new ActorValue(actor), "source", new AreaValue(new Cuboid(dimension, 71, 208, 71, 80, 208, 76)),
                "destination", new ContainerValue(new ContainerRef(dimension, 81, 208, 73)), "amount", new IntValue(1))),
                context, new ObservationRef(UUID.randomUUID(), 1, dimension));
        var limits = new Budgets.Limits(Map.of(Budgets.Kind.CALLS, 64L, Budgets.Kind.COMMITTED_EFFECTS, 64L), 100000);
        var artifact = new ArtifactRef(OakLogDelivery.ID, "a".repeat(64));
        UUID run = UUID.randomUUID();
        var attempt = new Jobs.Attempt(run, 1, actor, request, limits,
                List.of(new Jobs.ExecutionReference(run, artifact, List.of(artifact))),
                Map.of(Budgets.Kind.CALLS, 1L), List.of(), 0, 0, null, false, 0);
        return new Jobs.Job(UUID.randomUUID(), UUID.randomUUID(), 106, request, null, List.of(), List.of(),
                Jobs.State.ACTIVE, null, null, limits, attempt.usage(), 1, List.of(attempt), List.of());
    }

    private Jobs.Attempt update(Jobs.Attempt old, long calls, long effects, List<Jobs.ExecutionReference> executions) {
        return new Jobs.Attempt(old.id(), old.generation(), old.worker(), old.bound(), old.allowance(), executions,
                Map.of(Budgets.Kind.CALLS, calls), old.receipts(), effects, old.credited(),
                old.terminal(), old.uncertain(), old.cancellationDeliveries(), old.resourceReceipts());
    }

    private Jobs.Job update(Jobs.Job old, long revision, Jobs.Attempt attempt, Jobs.State state, Budgets.Limits limits) {
        return new Jobs.Job(old.id(), old.submissionId(), revision, old.request(), old.parent(),
                old.dependencies(), old.children(), state, old.reason(), old.cancellationOutcome(),
                limits, attempt.usage(), old.generation(), List.of(attempt), old.events(), old.preparation());
    }

    @Test void acknowledgedUsageCanAdvanceWhileTheOriginalHarvestIntentIsPending() {
        var original = original();
        var attempt = original.current();
        var observed = update(original, 108, update(attempt, 2, 0, attempt.executions()), Jobs.State.ACTIVE, original.allowance());
        assertTrue(LiveAttemptAuthority.unchanged(original, observed));
        assertEquals(106, original.revision());
        assertEquals(1L, original.current().usage().get(Budgets.Kind.CALLS).longValue());
    }

    @Test void aCancellationDifferentExecutionOrPhysicalEffectInvalidatesTheBinding() {
        var original = original();
        var attempt = original.current();
        assertFalse(LiveAttemptAuthority.unchanged(original, update(original, 107, attempt, Jobs.State.CANCELLING, original.allowance())));
        var artifact = attempt.executions().getFirst().artifact();
        var otherExecution = new Jobs.ExecutionReference(UUID.randomUUID(), artifact, List.of(artifact));
        assertFalse(LiveAttemptAuthority.unchanged(original, update(original, 107, update(attempt, 2, 0, List.of(otherExecution)),
                Jobs.State.ACTIVE, original.allowance())));
        assertFalse(LiveAttemptAuthority.unchanged(original, update(original, 107, update(attempt, 2, 1, attempt.executions()),
                Jobs.State.ACTIVE, original.allowance())));
    }

    @Test void usageAndRevisionCannotRegressAndTheOriginalDeadlineIsRequired() {
        var original = original();
        var attempt = original.current();
        assertFalse(LiveAttemptAuthority.unchanged(original, update(original, 107, update(attempt, 0, 0, attempt.executions()),
                Jobs.State.ACTIVE, original.allowance())));
        var extended = new Budgets.Limits(original.allowance().maxima(), 200000);
        assertFalse(LiveAttemptAuthority.unchanged(original, update(original, 107, attempt, Jobs.State.ACTIVE, extended)));
        assertFalse(LiveAttemptAuthority.unchanged(original, update(original, 105, attempt, Jobs.State.ACTIVE, original.allowance())));
    }
}
