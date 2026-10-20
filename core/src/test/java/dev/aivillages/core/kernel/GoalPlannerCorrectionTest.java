package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.Outcomes.*;

final class GoalPlannerCorrectionTest {
    private static String wrong(GoalPlannerTest.Fixture f) { return f.valid().replace("\"amount\":3", "\"amount\":4"); }
    @Test void quantityErrorGetsOneChargedCorrectionBeforeAnyChildrenArePublished() {
        var f = new GoalPlannerTest.Fixture(); var root = f.create(); var v = f.queued(root);
        f.model.calls.getFirst().respond(wrong(f)); v = f.advance(v, 5);
        assertEquals(2, f.model.calls.size()); assertEquals(GoalPlanner.State.QUEUED, v.state());
        assertTrue(f.jobs.query(root.id(), f.owner).children().isEmpty());
        var correction = f.model.calls.get(1); assertEquals(Generation.Role.PLANNING_REPAIR, correction.request.role());
        assertTrue(correction.request.context().contains("QUANTITY"));
        assertTrue(correction.request.context().contains("requiredTotals"));
        assertNotEquals(f.model.calls.getFirst().id(), correction.id());
        correction.respond(f.valid()); v = f.advance(v, 6);
        assertEquals(GoalPlanner.State.ADMITTED, v.state()); assertEquals(2, f.jobs.query(root.id(), f.owner).children().size());
        assertEquals(2L, v.actual().get(Budgets.Kind.CALLS)); assertEquals(1L, v.actual().get(Budgets.Kind.REPAIRS));
        assertEquals(65536L, v.reserved().get(Budgets.Kind.INPUT_BYTES));
        assertEquals(root.allowance().deadlineEpochMillis(), f.jobs.query(root.id(), f.owner).allowance().deadlineEpochMillis());
    }
    @Test void anotherInvalidQuantityStopsAfterTwoCallsWithoutPublishing() {
        var f = new GoalPlannerTest.Fixture(); var root = f.create(); var v = f.queued(root);
        f.model.calls.getFirst().respond(wrong(f)); v = f.advance(v, 5); f.model.calls.get(1).respond(wrong(f)); v = f.advance(v, 8);
        assertEquals(GoalPlanner.State.REJECTED, v.state()); assertEquals(Plan.Diagnostic.QUANTITY, v.diagnostic());
        assertEquals(2, f.model.calls.size()); assertTrue(f.jobs.query(root.id(), f.owner).children().isEmpty());
    }
    @Test void aCorrectionNeverOverridesChangedStock() {
        var f = new GoalPlannerTest.Fixture(); var root = f.create(); var v = f.queued(root);
        f.model.calls.getFirst().respond(wrong(f)); v = f.advance(v, 5); f.changed = true;
        f.model.calls.get(1).respond(f.valid()); v = f.advance(v, 6);
        assertEquals(GoalPlanner.State.REJECTED, v.state()); assertEquals(Plan.Diagnostic.STALE, v.diagnostic());
        assertTrue(f.jobs.query(root.id(), f.owner).children().isEmpty());
    }
    @Test void cancellationDiscardsLateCorrectionWithoutAThirdCall() {
        var f = new GoalPlannerTest.Fixture(); var root = f.create(); var v = f.queued(root);
        f.model.calls.getFirst().respond(wrong(f)); v = f.advance(v, 5);
        assertTrue(f.planner.cancelProposal(v.id(), f.owner)); f.model.calls.get(1).respond(f.valid()); v = f.advance(v, 6);
        assertEquals(GoalPlanner.State.CANCELLED, v.state()); assertEquals(2, f.model.calls.size());
        assertTrue(f.jobs.query(root.id(), f.owner).children().isEmpty());
    }
    @Test void deadlineIncludesTheCorrectionAndCannotBeRenewed() {
        var f = new GoalPlannerTest.Fixture(); var root = f.create(); var v = f.queued(root);
        f.model.calls.getFirst().respond(wrong(f)); v = f.advance(v, 5); f.clock.now = root.allowance().deadlineEpochMillis();
        f.model.calls.get(1).respond(f.valid()); v = f.advance(v, 6);
        assertEquals(GoalPlanner.State.REJECTED, v.state()); assertEquals(Reason.BUDGET_EXHAUSTED, v.reason());
        assertTrue(f.jobs.query(root.id(), f.owner).children().isEmpty());
    }
}
