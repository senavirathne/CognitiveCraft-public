package dev.aivillages.core.kernel.interaction;

import dev.aivillages.core.kernel.Contracts.ContainerRef;
import dev.aivillages.core.kernel.production.RecipeChoice;
import dev.aivillages.core.kernel.production.planning.PlanningCoordinator;
import dev.aivillages.core.kernel.production.planning.PlanningModel;
import dev.aivillages.core.kernel.production.planning.StaticProductionPlanner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static dev.aivillages.core.kernel.production.planning.PlanningModel.*;

/** Immutable read-only adapter over the actual frozen B planner. No B writer, model or native-choice builder. */
public final class PlanningExplanationAdapter {
    public static final int MAX_ITEMS = 16, MAX_ADDED_UNITS = 64, MAX_RECIPE_CELLS = MAX_ACTIONS * 9;
    /** J supplies verified default-component descriptors; synthetic C fixtures are not native codec proof. */
    public record ItemDescriptor(String registryId, String componentFingerprint) {
        public ItemDescriptor {
            if (registryId == null || !registryId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || registryId.length() > 96
                    || componentFingerprint == null || !componentFingerprint.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("exact installed item descriptor");
        }
        Map<String, Object> value() { return Map.of("registry_id", registryId, "component_policy", "DEFAULT_COMPONENTS_ONLY", "component_fingerprint", componentFingerprint); }
    }
    public record Report(Feasibility feasibility, String reason, DecisionKind decisionKind, String decisionBlocker) { }
    record Evaluated(Analysis analysis, Decision decision) { }

    public static final class View {
        private final Snapshot snapshot;
        private final Map<RecipeChoice.ItemKey, ItemDescriptor> items;
        private final Set<Location> occupied;
        private final Memory memory;
        private View(Snapshot original, Memory memory, Map<RecipeChoice.ItemKey, ItemDescriptor> items) {
            Objects.requireNonNull(original); this.items = Map.copyOf(items);
            this.memory = Objects.requireNonNull(memory);
            this.occupied = original.stock().stream().map(Stock::location).collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (items.size() > MAX_ITEMS || items.entrySet().stream().anyMatch(e -> !e.getKey().id().equals(e.getValue().registryId())))
                throw new IllegalArgumentException("bounded exact item mapping");
            // Private rows are removed before B decision-memory fingerprints are calculated.
            this.snapshot = copy(original, original.stock().stream().filter(Stock::permitted).sorted(Comparator.comparing(Stock::key)).toList());
        }
        public Report analyse() {
            Evaluated e = evaluate(snapshot, memory);
            return new Report(feasibility(e), reason(e), e.decision().kind(), e.decision().blocker());
        }
        Snapshot snapshot() { return snapshot; }
        Map<RecipeChoice.ItemKey, ItemDescriptor> items() { return items; }
        Memory memory() { return memory; }
    }
    public View adapt(Snapshot snapshot, Memory memory, Map<RecipeChoice.ItemKey, ItemDescriptor> items) { return new View(snapshot, memory, items); }

    static Evaluated evaluate(Snapshot snapshot, Memory memory) {
        // Real B memory enforces original scope, usage monotonicity and remaining replan allowance.
        // Do not substitute empty memory or reuse a trial's newly proposed memory as current history.
        Decision decision = new PlanningCoordinator().next(snapshot, null, memory);
        Analysis analysis = decision.analysis();
        if (analysis != null && analysis.feasibility() == Feasibility.FEASIBLE)
            analysis = new StaticProductionPlanner().revalidate(snapshot, analysis.plan());
        return new Evaluated(analysis, decision);
    }
    static Feasibility feasibility(Evaluated e) { return e.analysis() == null ? Feasibility.UNKNOWN : e.analysis().feasibility(); }
    static String reason(Evaluated e) { return !e.decision().blocker().isEmpty() ? e.decision().blocker() : e.analysis() == null ? "" : e.analysis().reason(); }
    static boolean executable(Evaluated e) {
        return e.decision().kind() == DecisionKind.GOAL_ALREADY_CREDITED
                || feasibility(e) == Feasibility.FEASIBLE && e.decision().kind() == DecisionKind.PROPOSE;
    }

    /** Residual is stock, not missing units. Only complete, known exhaustive failures may seed candidates. */
    static List<Map<RecipeChoice.ItemKey, Long>> candidateAdditions(View view, Analysis current) {
        Snapshot s = view.snapshot;
        if (current.feasibility() != Feasibility.INFEASIBLE || !current.reason().equals("NO_SUPPORTED_WHOLE_GOAL_PATH")
                || !s.catalogueComplete()) return List.of();
        List<Map<RecipeChoice.ItemKey, Long>> result = new ArrayList<>();
        if (s.goal().mode() == Mode.SUPPLY && view.items.containsKey(s.goal().item())) {
            long missing = s.remainingDemand() - current.residual().getOrDefault(s.goal().item(), 0L);
            if (missing > 0 && missing <= MAX_ADDED_UNITS) result.add(Map.of(s.goal().item(), missing));
        }
        for (RecipeProfile recipe : s.recipes().stream().filter(r -> r.result().equals(s.goal().item())).sorted(Comparator.comparing(RecipeProfile::recipe)).toList()) {
            long batches = (s.remainingDemand() + recipe.yield() - 1) / recipe.yield();
            if (batches < 1 || batches > MAX_ACTIONS || batches * recipe.cells().size() > MAX_RECIPE_CELLS) continue;
            List<List<RecipeChoice.ItemKey>> cells = new ArrayList<>();
            for (int i = 0; i < batches; i++) cells.addAll(recipe.cells());
            RecipeChoice.ItemKey[] matched = new RecipeChoice.ItemKey[cells.size()];
            for (int i = 0; i < cells.size(); i++) matchKnown(i, cells, current.residual(), matched, new HashSet<>());
            Map<RecipeChoice.ItemKey, Long> missing = new HashMap<>(); boolean supported = true;
            for (int i = 0; i < cells.size(); i++) if (matched[i] == null) {
                RecipeChoice.ItemKey key = cells.get(i).stream().filter(view.items::containsKey).min(Comparator.comparing(PlanningModel::itemOrder)).orElse(null);
                if (key == null) { supported = false; break; }
                missing.merge(key, 1L, Math::addExact);
            }
            if (supported && !missing.isEmpty() && missing.size() <= MAX_ITEMS && missing.values().stream().mapToLong(Long::longValue).sum() <= MAX_ADDED_UNITS)
                result.add(Map.copyOf(missing));
        }
        return result.stream().distinct().sorted(Comparator.<Map<RecipeChoice.ItemKey, Long>>comparingLong(m -> m.values().stream().mapToLong(Long::longValue).sum())
                .thenComparing(PlanningExplanationAdapter::additionOrder)).toList();
    }
    private static String additionOrder(Map<RecipeChoice.ItemKey, Long> additions) {
        return additions.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(PlanningModel::itemOrder)))
                .map(e -> PlanningModel.itemOrder(e.getKey()) + "=" + e.getValue()).toList().toString();
    }
    /** Bounded bipartite matching avoids assigning a flexible cell the only item a later cell accepts. */
    private static boolean matchKnown(int index, List<List<RecipeChoice.ItemKey>> cells, Map<RecipeChoice.ItemKey, Long> available,
                                      RecipeChoice.ItemKey[] matched, Set<Integer> seen) {
        if (!seen.add(index)) return false;
        for (RecipeChoice.ItemKey key : cells.get(index).stream().distinct().sorted(Comparator.comparing(PlanningModel::itemOrder)).toList()) {
            long used = java.util.Arrays.stream(matched).filter(key::equals).count();
            if (used < available.getOrDefault(key, 0L)) { matched[index] = key; return true; }
            for (int j = 0; j < matched.length; j++) if (key.equals(matched[j]) && matchKnown(j, cells, available, matched, seen)) {
                matched[index] = key; return true;
            }
        }
        return false;
    }

    /** Discarded virtual stock only; no changes to recipes, choices, ownership, policy, credit or budgets. */
    static Snapshot hypothetical(View view, ContainerRef storage, Map<RecipeChoice.ItemKey, Long> additions, long travelCost) {
        Snapshot s = view.snapshot; List<Stock> stock = new ArrayList<>(s.stock());
        BlockerProjection.bounded(travelCost, 0);
        if (!s.goal().policy().allowedSources().contains(storage) || !s.goal().policy().staging().equals(storage)
                || additions.isEmpty() || additions.size() > MAX_ITEMS || additions.values().stream().anyMatch(n -> n < 1 || n > MAX_ADDED_UNITS)
                || additions.values().stream().mapToLong(Long::longValue).sum() > MAX_ADDED_UNITS)
            throw new IllegalArgumentException("bounded original staging addition");
        int ordinal = 0;
        for (var addition : additions.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(PlanningModel::itemOrder))).toList()) {
            if (!view.items.containsKey(addition.getKey())) throw new IllegalArgumentException("unknown item descriptor");
            long remaining = addition.getValue();
            for (int i = 0; i < stock.size() && remaining > 0; i++) {
                Stock x = stock.get(i);
                if (!storage.equals(x.location().container()) || !x.item().equals(addition.getKey()) || !StaticProductionPlanner.usable(s, x)) continue;
                long added = Math.min(remaining, 64 - x.observedUnits());
                stock.set(i, new Stock(x.key(), x.location(), x.item(), x.units() + added, x.observedUnits() + added,
                        x.rootEligibleUnits(), x.eligibleRoot(), x.eligibilityReferences(), x.eligibility(), x.availability(), x.permitted(), x.reachable(), x.travelCost(), x.relevantRevision()));
                remaining -= added;
            }
            if (remaining > 0) {
                int slot = -1;
                for (int i = 0; i < 128 && slot < 0; i++) {
                    int candidate = i;
                    if (!view.occupied.contains(new Location(storage, null, candidate))
                            && stock.stream().noneMatch(x -> storage.equals(x.location().container()) && x.location().slot() == candidate)) slot = i;
                }
                if (slot < 0 || stock.size() >= MAX_STOCK) throw new IllegalArgumentException("no bounded virtual slot");
                String key;
                do { key = "c-hypothetical-" + ordinal++; } while (hasKey(stock, key));
                stock.add(new Stock(key, new Location(storage, null, slot), addition.getKey(), remaining, remaining, 0, null, List.of(),
                        Eligibility.NONE, Availability.PRESENT, true, true, travelCost, s.relevantWorldRevision()));
            }
        }
        return copy(s, stock);
    }
    private static boolean hasKey(List<Stock> stock, String key) { return stock.stream().anyMatch(x -> x.key().equals(key)); }
    private static Snapshot copy(Snapshot s, List<Stock> stock) {
        return new Snapshot(s.schema(), s.world(), s.root(), s.revision(), s.context(), s.owner(), s.goal(), s.activeDestination(), s.confirmedCredit(),
                s.budget(), s.nowMillis(), s.relevantWorldRevision(), stock, s.acquisition(), s.recipes(), s.facility(), s.choices(), s.supportedKinds(),
                s.progress(), s.catalogueComplete(), s.modelAvailable(), s.returnsUsed());
    }
}
