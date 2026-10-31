package dev.aivillages.core.kernel.interaction;

import dev.aivillages.core.kernel.Contracts.ContainerRef;
import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import dev.aivillages.core.kernel.production.RecipeChoice;
import java.util.*;

import static dev.aivillages.core.kernel.interaction.AssistanceFixtures.*;
import static dev.aivillages.core.kernel.interaction.BlockerProjection.*;
import static dev.aivillages.core.kernel.production.planning.PlanningModel.*;

/** Synthetic snapshot input for Python invariance controls. Not a native entrypoint or production adapter. */
public final class BlockerProbe {
    private BlockerProbe() { }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        Map<String, Object> input = (Map<String, Object>) InteractionCodec.parseJson(args[0]);
        var f = new AssistanceFixtures();
        f.planks = (Long) input.getOrDefault("planks", 1L); f.sticks = (Long) input.getOrDefault("sticks", 2L);
        f.complete = (Boolean) input.getOrDefault("complete", true); f.methodUsable = (Boolean) input.getOrDefault("method_usable", true);
        f.versionTwo = (Boolean) input.getOrDefault("recipe_version_two", false); f.now = (Long) input.getOrDefault("clock_ms", 1000L);
        if (!((Boolean) input.getOrDefault("primitive_supported", true))) f.kinds.remove(Kind.PRODUCE);
        if (input.containsKey("used_effects")) { var used = new EnumMap<Counter, Long>(f.used); used.put(Counter.EFFECTS, (Long) input.get("used_effects")); f.used = used; }
        if (input.containsKey("used_replans")) { var used = new EnumMap<Counter, Long>(f.used); used.put(Counter.REPLANS, (Long) input.get("used_replans")); f.used = used; }
        if ((Boolean) input.getOrDefault("prior_plan", false)) f.memory = f.priorMemory(f.used);
        int ordinal = 0;
        for (Object value : (List<Object>) input.getOrDefault("rows", List.of())) {
            var row = (Map<String, Object>) value;
            boolean authorized = (Boolean) row.get("authorized");
            f.rows.add(new Row(id((Long) row.get("id")), (Boolean) row.get("relevant"), authorized, (Boolean) row.get("fresh"),
                    row.get("code") == null ? null : Code.valueOf((String) row.get("code"))));
            if (!authorized) {
                // Redaction drops the supplied private count; B also forbids retaining private observed units.
                String key = (String) row.getOrDefault("label", "redacted");
                var item = new RecipeChoice.ItemKey((String) row.getOrDefault("item", "minecraft:diamond"), "{}");
                f.extraStock.add(new Stock("private:" + key, new Location(new ContainerRef(DIM, 90, 64, 90), null, 50 + ordinal++), item,
                        0, 0, 0, null, List.of(), Eligibility.NONE, Availability.UNKNOWN, false, false, 0, 5));
            }
        }
        Snapshot snapshot = f.snapshot();
        if ((Boolean) input.getOrDefault("reverse_stock", false)) {
            List<Stock> stock = new ArrayList<>(snapshot.stock()); Collections.reverse(stock);
            Snapshot s = snapshot;
            snapshot = new Snapshot(s.schema(), s.world(), s.root(), s.revision(), s.context(), s.owner(), s.goal(), s.activeDestination(), s.confirmedCredit(),
                    s.budget(), s.nowMillis(), s.relevantWorldRevision(), stock, s.acquisition(), s.recipes(), s.facility(), s.choices(), s.supportedKinds(), s.progress(),
                    s.catalogueComplete(), s.modelAvailable(), s.returnsUsed());
        }
        Evidence evidence = f.evidence();
        if (input.containsKey("returned_root")) {
            var r = f.reference(); var wrong = new ActivityRef(r.world(), r.domain(), r.principal(), UUID.fromString((String) input.get("returned_root")),
                    r.originalOwner(), r.attempt(), r.citizen(), r.generation(), r.revision(), r.originalBudgetDigest(), r.absoluteDeadlineMillis());
            evidence = new Evidence(wrong, id(10), 100, 5, true, true, true, f.rows);
        }
        Evidence returned = evidence;
        var view = new PlanningExplanationAdapter().adapt(snapshot, f.memory, f.descriptors());
        var target = new AssistanceFeasibility.Target(f.world, f.context.principal().id(), f.storage, f.source, true, true, true,
                (Long) input.getOrDefault("target_capacity", 64L), 2);
        var question = new AssistanceFeasibility.Question(id(11), 4, f.now, (Long) input.getOrDefault("current_tick", 100L), 120000);
        var result = new AssistanceFeasibility().assess(f.reference(), ref -> Optional.of(returned), view, target, question);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("tier", "SYNTHETIC_BLOCKER"); output.put("planner_feasible_now", result.plannerFeasibleNow());
        output.put("blocker", result.blocker() == null ? null : result.blocker().wire().body());
        output.put("request", result.request() == null ? null : result.request().body()); output.put("hypotheses", (long) result.hypotheses());
        System.out.print(InteractionCodec.canonical(output));
    }
}
