package dev.aivillages.core.kernel.interaction;

import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.production.*;
import dev.aivillages.core.kernel.production.planning.PlanningModel;
import java.util.*;

import static dev.aivillages.core.kernel.production.planning.PlanningModel.*;
import static dev.aivillages.core.kernel.interaction.BlockerProjection.*;

/** Explicit synthetic B/A-shaped snapshots; no native authority, saved method or settlement proof. */
final class AssistanceFixtures {
    static UUID id(long n) { return new UUID(0, n); }
    static final String DIM = "minecraft:overworld";
    static final RecipeChoice.ItemKey PLANK = new RecipeChoice.ItemKey("minecraft:oak_planks", "{}");
    static final RecipeChoice.ItemKey STICK = new RecipeChoice.ItemKey("minecraft:stick", "{}");
    static final RecipeChoice.ItemKey AXE = new RecipeChoice.ItemKey("minecraft:wooden_axe", "{}");
    static final RecipeChoice.ItemKey LOG = new RecipeChoice.ItemKey("minecraft:oak_log", "{}");
    final UUID world = id(1), root = id(2), ownerId = id(3), attempt = id(4), storage = id(5);
    final ActorRef actor = new ActorRef(id(6), id(7), DIM);
    final TrustedContext context = new TrustedContext(new PrincipalRef(id(8)), new ScopeRef(world, id(9)));
    final ContainerRef source = new ContainerRef(DIM, 1, 64, 0), destination = new ContainerRef(DIM, 3, 64, 0);
    final FacilityRef table = new FacilityRef(DIM, 2, 64, 0);
    long generation = 3, revision = 7, now = 1000, deadline = 100000, worldRevision = 5, planks = 1, sticks = 2;
    boolean complete = true, methodUsable = true, tableKnown = true, catalogue = true, versionTwo = false;
    Set<Kind> kinds = EnumSet.allOf(Kind.class);
    Map<Counter, Long> original = Map.of(Counter.TRAVEL, 1000L, Counter.EFFECTS, 1000L, Counter.ELAPSED_TICKS, 10000L,
            Counter.RETRIES, 5L, Counter.REPLANS, 5L, Counter.MODEL_CALLS, 2L);
    Map<Counter, Long> used = Map.of(Counter.TRAVEL, 10L, Counter.EFFECTS, 7L, Counter.ELAPSED_TICKS, 200L,
            Counter.RETRIES, 1L, Counter.REPLANS, 1L, Counter.MODEL_CALLS, 0L);
    List<Stock> extraStock = new ArrayList<>(); List<Row> rows = new ArrayList<>();
    RecipeProfile profile(CapabilityId legacy, List<List<RecipeChoice.ItemKey>> cells, RecipeChoice.ItemKey output) {
        CapabilityId cap = new CapabilityId(legacy.name(), versionTwo ? 2 : 1);
        String semantic = RecipeTransaction.fingerprint("synthetic:C02:recipe:" + cap);
        MethodPin pin = new MethodPin(cap, RecipeTransaction.fingerprint("synthetic:C02:method:" + cap), semantic, methodUsable);
        return new RecipeProfile(RecipeProduction.of(legacy).recipe(), cap, cells, output, semantic, pin, new Cost(2, 3, 10));
    }
    Snapshot snapshot() {
        var policy = new Policy(actor, Set.of(actor), Set.of(source), Set.of(source, destination), Set.of(), source, table, false, false, 0, 64);
        var goal = new Goal(Mode.MAKE_NEW, AXE, 1, destination, policy);
        List<Stock> stock = new ArrayList<>(); stock.add(stock("public:planks", 0, PLANK, planks)); stock.add(stock("public:sticks", 1, STICK, sticks)); stock.addAll(extraStock);
        List<RecipeProfile> recipes = catalogue ? List.of(profile(WoodenAxeProduction.ID,
                List.of(List.of(PLANK), List.of(PLANK), List.of(PLANK), List.of(STICK), List.of(STICK)), AXE),
                profile(StickProduction.ID, List.of(List.of(PLANK), List.of(PLANK)), STICK), profile(OakPlankProduction.ID, List.of(List.of(LOG)), PLANK)) : List.of();
        Facility facility = new Facility(table, tableKnown ? Availability.PRESENT : Availability.UNKNOWN, true, true, "synthetic:table", false, 5, null, Cost.ZERO);
        return new Snapshot(SCHEMA, world, root, revision, context, new Owner(ownerId, actor, generation, true, false, false, false), goal,
                destination, 0, new Budget(original, used, deadline), now, worldRevision, stock, List.of(), recipes, facility, List.of(), kinds, List.of(), complete, false, 0);
    }
    Stock stock(String key, int slot, RecipeChoice.ItemKey item, long units) {
        return new Stock(key, new Location(source, null, slot), item, units, units, 0, null, List.of(), Eligibility.NONE, Availability.PRESENT, true, true, 2, 5);
    }
    Map<RecipeChoice.ItemKey, PlanningExplanationAdapter.ItemDescriptor> descriptors() {
        return Map.of(PLANK, descriptor(PLANK), STICK, descriptor(STICK), LOG, descriptor(LOG));
    }
    PlanningExplanationAdapter.ItemDescriptor descriptor(RecipeChoice.ItemKey item) {
        return new PlanningExplanationAdapter.ItemDescriptor(item.id(), RecipeTransaction.fingerprint("synthetic:C02:default-components:" + item.id()));
    }
    PlanningExplanationAdapter.View view() { return new PlanningExplanationAdapter().adapt(snapshot(), descriptors()); }
    ActivityRef reference() {
        Snapshot s = snapshot();
        return new ActivityRef(world, context.scope().domainId(), context.principal().id(), root, ownerId, attempt, actor.citizenId(),
                generation, revision, s.budget().initialDigest(), deadline);
    }
    Evidence evidence() { return new Evidence(reference(), id(10), 100, worldRevision, true, true, true, rows); }
    AssistanceFeasibility.Target target() { return new AssistanceFeasibility.Target(world, context.principal().id(), storage, source, true, true, true, 64, 2); }
    AssistanceFeasibility.Question question() { return new AssistanceFeasibility.Question(id(11), 4, now, 100, 120000); }
    AssistanceFeasibility.Result assess() { return new AssistanceFeasibility().assess(reference(), ref -> Optional.of(evidence()), view(), target(), question()); }
    Row row(long n, Code code) { return new Row(id(n), true, true, true, code); }
}
