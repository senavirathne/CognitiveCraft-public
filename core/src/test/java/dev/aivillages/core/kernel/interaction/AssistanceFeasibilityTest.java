package dev.aivillages.core.kernel.interaction;

import dev.aivillages.core.kernel.production.planning.PlanningModel;
import dev.aivillages.core.kernel.production.planning.StaticProductionPlanner;
import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import org.junit.jupiter.api.Test;
import java.util.*;

import static dev.aivillages.core.kernel.interaction.AssistanceFixtures.*;
import static dev.aivillages.core.kernel.interaction.BlockerProjection.*;
import static dev.aivillages.core.kernel.production.planning.PlanningModel.*;
import static org.junit.jupiter.api.Assertions.*;

class AssistanceFeasibilityTest {
    @Test void supportedAxeMissingTwoPlanksAsksForExactlyTwoInAuthorizedStorageWithOriginalRoot() {
        var f = new AssistanceFixtures(); var result = f.assess();
        assertEquals(Code.INSUFFICIENT_MATERIAL, result.blocker().code()); assertNotNull(result.request());
        assertEquals(List.of(Map.of("item", f.descriptor(PLANK).value(), "missing", 2L)), result.blocker().wire().body().get("requirements"));
        assertEquals(f.storage.toString(), result.blocker().wire().body().get("storage_id"));
        assertEquals(f.reference().value(), result.request().body().get("activity"));
        assertEquals(f.reference().value(), ((Map<?, ?>) result.request().body().get("blocker")).get("activity"));
        assertEquals(f.deadline, result.request().body().get("expires_ms")); assertFalse(result.plannerFeasibleNow());
        assertEquals("FEASIBLE_AFTER_ASSISTANCE", result.blocker().wire().body().get("continuation"));
    }

    @Test void bResidualIsExistingStockAndCannotBeMistakenForTheShortage() {
        var f = new AssistanceFixtures(); var analysis = new StaticProductionPlanner().analyse(f.snapshot());
        assertEquals(Feasibility.INFEASIBLE, analysis.feasibility()); assertEquals(1L, analysis.residual().get(PLANK));
        assertEquals(2L, analysis.residual().get(STICK));
        assertEquals(List.of(Map.of("item", f.descriptor(PLANK).value(), "missing", 2L)), f.assess().blocker().wire().body().get("requirements"));
    }

    @Test void multipleMissingMaterialsAreActuallyRecheckedAsOneBoundedAddition() {
        var f = new AssistanceFixtures(); f.sticks = 0; var result = f.assess();
        assertNotNull(result.request()); assertEquals(List.of(Map.of("item", f.descriptor(PLANK).value(), "missing", 2L),
                Map.of("item", f.descriptor(STICK).value(), "missing", 2L)), result.blocker().wire().body().get("requirements"));
        assertTrue(result.hypotheses() <= AssistanceFeasibility.MAX_HYPOTHESES);
    }

    @Test void hypotheticalStockNeverChangesRootPolicyMethodsChoicesBudgetsOrCredit() {
        var f = new AssistanceFixtures(); var view = f.view(); var before = view.snapshot();
        var after = PlanningExplanationAdapter.hypothetical(view, f.source, Map.of(PLANK, 2L), 2);
        assertEquals(before.world(), after.world()); assertEquals(before.root(), after.root()); assertEquals(before.owner(), after.owner());
        assertEquals(before.goal(), after.goal()); assertEquals(before.budget(), after.budget()); assertEquals(before.choices(), after.choices());
        assertEquals(before.recipes(), after.recipes()); assertEquals(before.progress(), after.progress()); assertEquals(before.context(), after.context());
        assertEquals(before.confirmedCredit(), after.confirmedCredit()); assertEquals(before.returnsUsed(), after.returnsUsed());
        assertEquals(1, before.stock().getFirst().units());
        assertEquals(0, after.stock().stream().mapToLong(Stock::rootEligibleUnits).sum());
        assertEquals(Feasibility.FEASIBLE, new StaticProductionPlanner().analyse(after).feasibility());
        assertThrows(UnsupportedOperationException.class, () -> after.stock().clear());
    }

    @Test void exhaustedOriginalBudgetNeverResetsToMakeAssistanceLookAchievable() {
        var f = new AssistanceFixtures(); var used = new EnumMap<Counter, Long>(f.used); used.put(Counter.EFFECTS, 999L); f.used = used;
        var result = f.assess(); assertNull(result.request()); assertEquals(Code.BUDGET_OR_DEADLINE_EXCEEDED, result.blocker().code());
        assertEquals(999L, f.snapshot().budget().used().get(Counter.EFFECTS));
    }

    @Test void deadlineAndQuestionWindowAreBoundedByOriginalActivity() {
        var f = new AssistanceFixtures();
        var q = new AssistanceFeasibility.Question(id(11), 4, f.now, 100, 50);
        var result = new AssistanceFeasibility().assess(f.reference(), ref -> Optional.of(f.evidence()), f.view(), f.target(), q);
        assertEquals(f.now + 50, result.request().body().get("expires_ms"));
        f.now = f.deadline; assertEquals(Code.BUDGET_OR_DEADLINE_EXCEEDED, f.assess().blocker().code()); assertNull(f.assess().request());
    }

    @Test void feasibleCurrentPlanNeedsNoMaterialQuestionOrCompletionClaim() {
        var f = new AssistanceFixtures(); f.planks = 3; var result = f.assess();
        assertTrue(result.plannerFeasibleNow()); assertNull(result.request()); assertNull(result.blocker());
        assertEquals(0, f.snapshot().confirmedCredit()); assertTrue(f.snapshot().progress().isEmpty());
    }

    @Test void nativeChoiceAndAdmittedMethodAreNotInventedToCompleteAHypothesis() {
        var f = new AssistanceFixtures(); f.versionTwo = true;
        var result = f.assess(); assertNull(result.request()); assertEquals(Code.DEPENDENCY_NOT_QUALIFIED, result.blocker().code());
        assertTrue(f.snapshot().choices().isEmpty());
        f.versionTwo = false; f.methodUsable = false; result = f.assess();
        assertNull(result.request()); assertEquals(Code.UNKNOWN_WORLD_STATE, result.blocker().code());
    }

    @Test void unavailableEvidenceAndUnknownItemDescriptorsNeverInventNumbers() {
        var f = new AssistanceFixtures(); var service = new AssistanceFeasibility();
        var unavailable = service.assess(f.reference(), ref -> Optional.empty(), f.view(), f.target(), f.question());
        assertEquals(Code.UNKNOWN_WORLD_STATE, unavailable.blocker().code()); assertNull(unavailable.request());
        var view = new PlanningExplanationAdapter().adapt(f.snapshot(), Map.of());
        var noItems = service.assess(f.reference(), ref -> Optional.of(f.evidence()), view, f.target(), f.question());
        assertNull(noItems.request()); assertEquals(List.of(), noItems.blocker().wire().body().get("requirements"));
    }

    @Test void differentRootScopeRevisionOwnerOrBudgetCannotBeAdopted() {
        var f = new AssistanceFixtures(); var r = f.reference();
        List<ActivityRef> wrong = List.of(new ActivityRef(r.world(), r.domain(), id(888), r.root(), r.originalOwner(), r.attempt(), r.citizen(), r.generation(), r.revision(), r.originalBudgetDigest(), r.absoluteDeadlineMillis()),
                new ActivityRef(r.world(), r.domain(), r.principal(), id(999), r.originalOwner(), r.attempt(), r.citizen(), r.generation(), r.revision(), r.originalBudgetDigest(), r.absoluteDeadlineMillis()),
                new ActivityRef(r.world(), r.domain(), r.principal(), r.root(), r.originalOwner(), r.attempt(), r.citizen(), r.generation(), r.revision()+1, r.originalBudgetDigest(), r.absoluteDeadlineMillis()),
                new ActivityRef(r.world(), r.domain(), r.principal(), r.root(), id(777), r.attempt(), r.citizen(), r.generation(), r.revision(), r.originalBudgetDigest(), r.absoluteDeadlineMillis()),
                new ActivityRef(r.world(), r.domain(), r.principal(), r.root(), r.originalOwner(), r.attempt(), r.citizen(), r.generation(), r.revision(), "0".repeat(64), r.absoluteDeadlineMillis()));
        for (ActivityRef other : wrong) {
            var returned = new Evidence(other, id(10), 100, 5, true, true, true, List.of());
            var result = new AssistanceFeasibility().assess(r, ref -> Optional.of(returned), f.view(), f.target(), f.question());
            assertNull(result.request()); assertEquals(Code.UNAUTHORIZED, result.blocker().code());
            assertNull(result.blocker().wire().body().get("activity"));
        }
    }

    @Test void depositOnlyStorageDoesNotRequireDisclosureOfItsExistingContents() {
        var f = new AssistanceFixtures();
        // Target grants deposit to caller and withdrawal to actor; it contains no caller INSPECT grant.
        assertNotNull(f.assess().request());
        var denied = new AssistanceFeasibility.Target(f.world, f.context.principal().id(), f.storage, f.source, false, true, true, 64, 2);
        var result = new AssistanceFeasibility().assess(f.reference(), ref -> Optional.of(f.evidence()), f.view(), denied, f.question());
        assertEquals(Code.UNAUTHORIZED, result.blocker().code()); assertNull(result.request());
    }

    @Test void arbitraryStorageAndOverflowingProjectionCannotWidenOriginalPolicy() {
        var f = new AssistanceFixtures();
        var target = new AssistanceFeasibility.Target(f.world, f.context.principal().id(), f.storage, f.destination, true, true, true, 64, 2);
        var result = new AssistanceFeasibility().assess(f.reference(), ref -> Optional.of(f.evidence()), f.view(), target, f.question());
        assertEquals(Code.UNAUTHORIZED, result.blocker().code()); assertNull(result.request());
        assertThrows(IllegalArgumentException.class, () -> new ActivityRef(f.world, id(9), id(8), f.root, f.ownerId, f.attempt,
                f.actor.citizenId(), MAX_INTEGER + 1, 7, f.reference().originalBudgetDigest(), f.deadline));
    }
}
