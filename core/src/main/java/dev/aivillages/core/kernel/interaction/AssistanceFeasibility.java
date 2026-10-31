package dev.aivillages.core.kernel.interaction;

import dev.aivillages.core.kernel.Contracts.ContainerRef;
import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import dev.aivillages.core.kernel.interaction.contract.InteractionWire;
import dev.aivillages.core.kernel.production.RecipeChoice;
import dev.aivillages.core.kernel.production.planning.PlanningModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static dev.aivillages.core.kernel.interaction.BlockerProjection.*;
import static dev.aivillages.core.kernel.production.planning.PlanningModel.*;

/** Grounded explanation only. A alone admits/reobserves/resumes the original activity. */
public final class AssistanceFeasibility {
    public static final long MAX_WAIT_MILLIS = 600_000;
    public static final int MAX_HYPOTHESES = 128;
    public record Target(UUID world, UUID principal, UUID storageId, ContainerRef container,
                         boolean depositAllowed, boolean actorWithdrawAllowed, boolean fresh, long freeCapacityUnits, long travelCost) {
        public Target { Objects.requireNonNull(world); Objects.requireNonNull(principal); Objects.requireNonNull(storageId); Objects.requireNonNull(container); bounded(freeCapacityUnits, 0); bounded(travelCost, 0); }
    }
    public record Question(UUID id, long revision, long nowMillis, long currentTick, long windowMillis) {
        public Question { Objects.requireNonNull(id); bounded(revision, 1); bounded(nowMillis, 0); bounded(currentTick, 0);
            if (windowMillis < 1 || windowMillis > MAX_WAIT_MILLIS) throw new IllegalArgumentException("finite assistance window"); }
    }
    public record Result(boolean plannerFeasibleNow, Projection blocker, InteractionWire.UntrustedMessage request, int hypotheses) {
        public Result {
            if (hypotheses < 0 || hypotheses > MAX_HYPOTHESES || plannerFeasibleNow && (blocker != null || request != null)
                    || request != null && (blocker == null || blocker.code() != Code.INSUFFICIENT_MATERIAL)) throw new IllegalArgumentException("explanation disposition");
        }
    }

    public Result assess(ActivityRef reference, ActivityEvidencePort port, PlanningExplanationAdapter.View view, Target target, Question question) {
        Objects.requireNonNull(reference); Objects.requireNonNull(port); Objects.requireNonNull(view); Objects.requireNonNull(question);
        Optional<Evidence> read;
        try { read = Objects.requireNonNull(port.read(reference)); }
        catch (RuntimeException unavailable) { read = Optional.empty(); }
        Evidence e = read.orElseGet(() -> new Evidence(reference, question.id(), question.currentTick(), 0, false, true, true, List.of()));
        if (!e.activity().equals(reference)) return blocked(e, Code.UNAUTHORIZED, 0);
        Snapshot s = view.snapshot();
        if (!reference.sameScope(s) || target != null && (!target.world().equals(reference.world()) || !target.principal().equals(reference.principal())
                || !target.depositAllowed() || !target.actorWithdrawAllowed())) return blocked(e, Code.UNAUTHORIZED, 0);
        Optional<Projection> runtime = new BlockerProjection().project(e, question.currentTick());
        if (runtime.isPresent() && rank(runtime.get().code()) <= 1) return new Result(false, runtime.get(), null, 0);
        if (!reference.matches(s) || e.worldRevision() != s.relevantWorldRevision() || s.nowMillis() != question.nowMillis()
                || target != null && !target.fresh()) return blocked(e, Code.STALE_REFERENCE, 0);
        PlanningExplanationAdapter.Evaluated current = PlanningExplanationAdapter.evaluate(s);
        // Unknown evidence outranks unsupported/capacity facts, and never supplies a made-up number.
        if (current.analysis().feasibility() == Feasibility.UNKNOWN) return blocked(e, Code.UNKNOWN_WORLD_STATE, 0);
        if (runtime.isPresent()) return new Result(false, runtime.get(), null, 0);
        if (!s.supportedKinds().contains(Kind.PRODUCE) && s.goal().mode() != Mode.SUPPLY) return blocked(e, Code.UNSUPPORTED_EFFECT, 0);
        if (question.nowMillis() >= reference.absoluteDeadlineMillis() || s.owner().cancellationRequested()
                || s.budget().used().entrySet().stream().anyMatch(x -> x.getValue() > s.budget().original().get(x.getKey()))
                || current.analysis().reason().contains("BUDGET") || current.analysis().reason().contains("DEADLINE")
                || current.analysis().reason().contains("RETRIES")) return blocked(e, Code.BUDGET_OR_DEADLINE_EXCEEDED, 0);
        if (PlanningExplanationAdapter.executable(current)) return new Result(true, null, null, 0);
        if (current.analysis().feasibility() == Feasibility.FEASIBLE) return blocked(e, Code.DEPENDENCY_NOT_QUALIFIED, 0);
        if (target == null) return blocked(e, Code.UNKNOWN_WORLD_STATE, 0);
        if (!s.goal().policy().staging().equals(target.container()) || !s.goal().policy().allowedSources().contains(target.container()))
            return blocked(e, Code.UNAUTHORIZED, 0);
        if (target.freeCapacityUnits() == 0) return blocked(e, Code.DESTINATION_FULL, 0);
        List<Map<RecipeChoice.ItemKey, Long>> candidates = PlanningExplanationAdapter.candidateAdditions(view, current.analysis());
        if (candidates.isEmpty()) return blocked(e, s.recipes().stream().anyMatch(r -> r.result().equals(s.goal().item()))
                ? Code.DEPENDENCY_NOT_QUALIFIED : Code.NO_SUPPORTED_PRODUCTION_PATH, 0);
        int hypotheses = 0; boolean budgetLimited = false;
        for (Map<RecipeChoice.ItemKey, Long> candidate : candidates) {
            if (candidate.values().stream().mapToLong(Long::longValue).sum() > target.freeCapacityUnits()) continue;
            if (++hypotheses > MAX_HYPOTHESES) return blocked(e, Code.UNKNOWN_WORLD_STATE, MAX_HYPOTHESES);
            PlanningExplanationAdapter.Evaluated trial;
            try { trial = PlanningExplanationAdapter.evaluate(PlanningExplanationAdapter.hypothetical(view, target.container(), candidate, target.travelCost())); }
            catch (IllegalArgumentException | ArithmeticException invalid) { return blocked(e, Code.UNKNOWN_WORLD_STATE, hypotheses); }
            if (trial.analysis().feasibility() == Feasibility.UNKNOWN) return blocked(e, Code.UNKNOWN_WORLD_STATE, hypotheses);
            if (trial.analysis().feasibility() != Feasibility.FEASIBLE) {
                budgetLimited |= trial.analysis().reason().contains("BUDGET") || trial.analysis().reason().contains("DEADLINE") || trial.analysis().reason().contains("RETRIES");
                continue;
            }
            if (!PlanningExplanationAdapter.executable(trial)) return blocked(e, Code.DEPENDENCY_NOT_QUALIFIED, hypotheses);
            Map<RecipeChoice.ItemKey, Long> minimal = new HashMap<>(candidate);
            for (RecipeChoice.ItemKey item : candidate.keySet().stream().sorted(Comparator.comparing(PlanningModel::itemOrder)).toList()) {
                while (minimal.containsKey(item)) {
                    Map<RecipeChoice.ItemKey, Long> smaller = new HashMap<>(minimal);
                    if (smaller.get(item) == 1) smaller.remove(item); else smaller.put(item, smaller.get(item) - 1);
                    if (smaller.isEmpty()) break; // Current complete snapshot was already proven infeasible.
                    if (hypotheses == MAX_HYPOTHESES) return blocked(e, Code.UNKNOWN_WORLD_STATE, hypotheses);
                    hypotheses++;
                    PlanningExplanationAdapter.Evaluated reduced = PlanningExplanationAdapter.evaluate(PlanningExplanationAdapter.hypothetical(view, target.container(), smaller, target.travelCost()));
                    if (reduced.analysis().feasibility() == Feasibility.UNKNOWN) return blocked(e, Code.UNKNOWN_WORLD_STATE, hypotheses);
                    if (reduced.analysis().feasibility() != Feasibility.FEASIBLE) break;
                    if (!PlanningExplanationAdapter.executable(reduced)) return blocked(e, Code.DEPENDENCY_NOT_QUALIFIED, hypotheses);
                    minimal = smaller;
                }
            }
            List<Map<String, Object>> requirements = new ArrayList<>();
            minimal.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(PlanningModel::itemOrder)))
                    .forEach(x -> requirements.add(Map.of("item", view.items().get(x.getKey()).value(), "missing", x.getValue())));
            Projection blocker = material(e, requirements, target.storageId());
            long expires = question.nowMillis() + Math.min(question.windowMillis(), reference.absoluteDeadlineMillis() - question.nowMillis());
            Map<String, Object> body = Map.of("question_id", question.id().toString(), "dialogue_revision", question.revision(), "activity", reference.value(),
                    "blocker", blocker.wire().body(), "expires_ms", expires,
                    "allowed_replies", List.of("CANCEL", "CHECK_AGAIN", "CHOOSE_TARGET", "CORRECT_GOAL", "HELP"));
            var request = new InteractionCodec().decode(InteractionCodec.canonical(Map.of("schema", InteractionWire.VERSION, "kind", "AssistanceRequest", "body", body)));
            return new Result(false, blocker, request, hypotheses);
        }
        return blocked(e, candidates.stream().allMatch(m -> m.values().stream().mapToLong(Long::longValue).sum() > target.freeCapacityUnits())
                ? Code.DESTINATION_FULL : budgetLimited ? Code.BUDGET_OR_DEADLINE_EXCEEDED : Code.UNKNOWN_WORLD_STATE, hypotheses);
    }
    private static Result blocked(Evidence e, Code code, int hypotheses) { return new Result(false, failure(e, code, "B_PLANNER"), null, hypotheses); }
}
