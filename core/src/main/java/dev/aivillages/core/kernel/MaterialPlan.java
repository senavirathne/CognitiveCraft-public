package dev.aivillages.core.kernel;

import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;

/** Bounded material-plan preview. No world, Jobs, lease, execution or admission port exists here. */
public final class MaterialPlan {
    public static final CapabilityId ID = new CapabilityId("cognitivecraft:material_plan_preview", 1);
    public static final CapabilitySpec SPEC = new CapabilitySpec(ID,
            List.of(new Parameter("amount", Type.INT, 1, 192)), Type.INT, Set.of(), Completion.ALL_CHILDREN);
    public static final AIWorkBroker.Contract OUTPUT = new AIWorkBroker.Contract("material-plan-preview", 1, Generation.SCHEMA);
    public static final String PLACE = "cognitivecraft:place_crafting_table";
    private static final String TABLE = ResourceDelivery.CRAFTING_TABLE.id();
    private static final String AXE = ResourceDelivery.WOODEN_AXE.id();
    private static final List<RecipeProduction.Definition> RECIPES = List.of(
            RecipeProduction.of(WoodenAxeProduction.ID), RecipeProduction.of(CraftingTableProduction.ID),
            RecipeProduction.of(StickProduction.ID), RecipeProduction.of(OakPlankProduction.ID),
            RecipeProduction.of(BirchPlankProduction.ID));
    private static final Set<String> ITEMS = Set.of(ResourceDelivery.OAK_LOG.id(), ResourceDelivery.BIRCH_LOG.id(),
            ResourceDelivery.OAK_PLANKS.id(), ResourceDelivery.BIRCH_PLANKS.id(), ResourceDelivery.STICK.id(), AXE, TABLE);
    private MaterialPlan() { }

    public record Context(String request, Map<String, Long> goals, Map<String, Long> inventory,
                          boolean tableAvailable, boolean placementAllowed) {
        public Context {
            goals = Map.copyOf(goals); inventory = Map.copyOf(inventory);
            if (request == null || request.getBytes(StandardCharsets.UTF_8).length > 512
                    || goals.isEmpty() || goals.size() > 3 || inventory.size() > 7
                    || goals.entrySet().stream().anyMatch(e -> !e.getKey().matches("minecraft:[a-z_]{1,40}") || e.getValue() < 1 || e.getValue() > 64)
                    || inventory.entrySet().stream().anyMatch(e -> !ITEMS.contains(e.getKey()) || e.getValue() < 0 || e.getValue() > 256))
                throw new IllegalArgumentException("Trusted finite material context required");
        }
        public String prompt() {
            return StrictJson.canonical(Map.of("contract", "material-plan-preview:1", "request", request,
                    "requiredDeliveries", goals, "availableInventory", inventory,
                    "tableAvailable", tableAvailable, "placementAllowed", placementAllowed,
                    "recipes", RECIPES.stream().map(r -> Map.of("recipe", r.recipe(), "inputs", inputs(r),
                            "output", r.output().id(), "outputPerBatch", r.outputPerBatch(),
                            "maximumBatches", r.maximumBatches(), "requiresTable", r.axe())).toList(),
                    "placement", Map.of("recipe", PLACE, "consumes", TABLE, "quantity", 1),
                    "requiredWork", Map.of("status", requirements(this).status(), "batches", requirements(this).batches(), "dependencies", dependencies(this)),
                    "instruction", "Return schema 1 JSON with status, steps and deliveries. The deterministic material precheck supplies requiredWork, not permission to execute. For a plan, include each requiredWork recipe exactly once with its listed batches. Every dependency lists a recipe that must appear BEFORE its consumer. Choose an order respecting all dependencies and stock consumption. Existing stock may already satisfy an intermediate. Axes require a placed table before crafting. Deliveries must match requiredDeliveries exactly; keep unavoidable batch remainders. For an impossible request return the precheck status with empty steps/deliveries. No harvesting, invented resources, recipes, authority or quantity changes."));
        }
    }
    public record Step(String recipe, long batches) { }
    public record Proposal(String status, List<Step> steps, Map<String, Long> deliveries) {
        public Proposal { steps = List.copyOf(steps); deliveries = Map.copyOf(deliveries); }
    }
    public record Check(boolean accepted, String diagnostic, String path, String expectedStatus,
                        Map<String, Long> remainingInventory) {
        public Check { remainingInventory = Map.copyOf(remainingInventory); }
    }
    public record Requirement(String status, Map<String, Long> batches) {
        public Requirement { batches = Map.copyOf(batches); }
    }

    /** Reverse topological material balance for this finite, single-producer recipe catalog. */
    public static Requirement requirements(Context c) {
        if (!ITEMS.containsAll(c.goals().keySet())) return new Requirement("unsupported", Map.of());
        var demand = new HashMap<>(c.goals()); var batches = new HashMap<String, Long>();
        if (demand.getOrDefault(AXE, 0L) > c.inventory().getOrDefault(AXE, 0L) && !c.tableAvailable()) {
            if (!c.placementAllowed()) return new Requirement("insufficient_resources", Map.of());
            demand.merge(TABLE, 1L, Math::addExact); batches.put(PLACE, 1L);
        }
        for (var recipe : RECIPES) {
            long missing = Math.max(0, demand.getOrDefault(recipe.output().id(), 0L) - c.inventory().getOrDefault(recipe.output().id(), 0L));
            long count = (missing + recipe.outputPerBatch() - 1) / recipe.outputPerBatch();
            if (count > recipe.maximumBatches()) return new Requirement("unsupported", Map.of());
            if (count > 0) {
                batches.put(recipe.recipe(), count);
                for (var ingredient : inputs(recipe).entrySet()) demand.merge(ingredient.getKey(),
                        Math.multiplyExact(count, ingredient.getValue()), Math::addExact);
            }
        }
        for (String raw : List.of(ResourceDelivery.OAK_LOG.id(), ResourceDelivery.BIRCH_LOG.id()))
            if (demand.getOrDefault(raw, 0L) > c.inventory().getOrDefault(raw, 0L))
                return new Requirement("insufficient_resources", Map.of());
        return new Requirement("plan", batches);
    }
    private static Map<String, Long> inputs(RecipeProduction.Definition recipe) {
        return recipe.axe() ? Map.of(recipe.input().id(), (long) recipe.inputPerBatch(), ResourceDelivery.STICK.id(), 2L)
                : Map.of(recipe.input().id(), (long) recipe.inputPerBatch());
    }

    /** Conservative recipe dependency edges, not an ordered plan or executable work. */
    private static List<Map<String,String>> dependencies(Context context) {
        var required=requirements(context).batches();var edges=new ArrayList<Map<String,String>>();
        for(var consumer:RECIPES)if(required.containsKey(consumer.recipe())) {
            for(var producer:RECIPES)if(required.containsKey(producer.recipe()) && inputs(consumer).containsKey(producer.output().id()))
                edges.add(Map.of("before",producer.recipe(),"after",consumer.recipe()));
            if(consumer.axe() && required.containsKey(PLACE))edges.add(Map.of("before",PLACE,"after",consumer.recipe()));
        }
        if(required.containsKey(PLACE))for(var producer:RECIPES)if(producer.table() && required.containsKey(producer.recipe()))
            edges.add(Map.of("before",producer.recipe(),"after",PLACE));
        return List.copyOf(edges);
    }
    public static Proposal decode(String json) throws StrictJson.Invalid {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > 8192) throw new StrictJson.Invalid("$", "SIZE");
        var root = StrictJson.object(json); Outcomes.exact(root, "schema", "status", "steps", "deliveries");
        if (Outcomes.number(root, "schema", "$") != 1) throw new StrictJson.Invalid("$.schema", "MALFORMED");
        String status = Outcomes.string(root, "status", "$");
        if (!Set.of("plan", "insufficient_resources", "unsupported").contains(status)) throw new StrictJson.Invalid("$.status", "MALFORMED");
        if (!(root.get("steps") instanceof List<?> rows) || rows.size() > 8
                || !(root.get("deliveries") instanceof List<?> outputs) || outputs.size() > 3)
            throw new StrictJson.Invalid("$", "SIZE");
        var steps = new ArrayList<Step>(); var deliveries = new HashMap<String, Long>();
        for (Object raw : rows) {
            if (!(raw instanceof Map<?, ?>)) throw new StrictJson.Invalid("$.steps", "MALFORMED");
            @SuppressWarnings("unchecked") var row = (Map<String, Object>) raw;
            Outcomes.exact(row, "recipe", "batches");
            String recipe = Outcomes.string(row, "recipe", "$.steps"); long n = Outcomes.number(row, "batches", "$.steps");
            if (n < 1 || n > 16 || !recipe.equals(PLACE) && RECIPES.stream().noneMatch(r -> r.recipe().equals(recipe)))
                throw new StrictJson.Invalid("$.steps", "UNSUPPORTED_CAPABILITY");
            steps.add(new Step(recipe, n));
        }
        for (Object raw : outputs) {
            if (!(raw instanceof Map<?, ?>)) throw new StrictJson.Invalid("$.deliveries", "MALFORMED");
            @SuppressWarnings("unchecked") var row = (Map<String, Object>) raw;
            Outcomes.exact(row, "item", "amount"); String item = Outcomes.string(row, "item", "$.deliveries");
            long n = Outcomes.number(row, "amount", "$.deliveries");
            if (n < 1 || n > 64 || deliveries.put(item, n) != null) throw new StrictJson.Invalid("$.deliveries", "QUANTITY");
        }
        return new Proposal(status, steps, deliveries);
    }
    public static Check validate(String candidate, Context c) {
        var required = requirements(c);
        try {
            var proposal = decode(candidate);
            if (!proposal.status().equals(required.status())) return denied("RESOURCE_STATUS", "$.status", required);
            if (!proposal.status().equals("plan")) return proposal.steps().isEmpty() && proposal.deliveries().isEmpty()
                    ? new Check(true, "NONE", "", required.status(), c.inventory()) : denied("REFUSAL_EFFECTS", "$.steps", required);
            if (!proposal.deliveries().equals(c.goals())) return denied("QUANTITY", "$.deliveries", required);
            var stock = new HashMap<>(c.inventory()); var used = new HashMap<String, Long>(); boolean table = c.tableAvailable();
            for (int i = 0; i < proposal.steps().size(); i++) {
                var step = proposal.steps().get(i); String path = "$.steps[" + i + "]";
                used.merge(step.recipe(), step.batches(), Math::addExact);
                if (step.recipe().equals(PLACE)) {
                    if (!c.placementAllowed() || table || step.batches() != 1 || stock.getOrDefault(TABLE, 0L) < 1)
                        return denied("FACILITY", path, required);
                    stock.merge(TABLE, -1L, Math::addExact); table = true; continue;
                }
                var recipe = RecipeProduction.recipe(step.recipe());
                if (step.batches() > recipe.maximumBatches()) return denied("QUANTITY", path, required);
                if (recipe.axe() && !table) return denied("FACILITY", path, required);
                for (var ingredient : inputs(recipe).entrySet()) {
                    long amount = Math.multiplyExact(ingredient.getValue(), step.batches());
                    if (stock.getOrDefault(ingredient.getKey(), 0L) < amount) return denied("FABRICATED_RESOURCE", path + ".inputs." + ingredient.getKey(), required);
                    stock.merge(ingredient.getKey(), -amount, Math::addExact);
                }
                stock.merge(recipe.output().id(), Math.multiplyExact(step.batches(), recipe.outputPerBatch()), Math::addExact);
            }
            if (!used.equals(required.batches())) return denied("QUANTITY", "$.steps.batches", required);
            for (var target : c.goals().entrySet()) {
                if (stock.getOrDefault(target.getKey(), 0L) < target.getValue()) return denied("QUANTITY", "$.deliveries", required);
                stock.merge(target.getKey(), -target.getValue(), Math::addExact);
            }
            return new Check(true, "NONE", "", required.status(), stock);
        } catch (StrictJson.Invalid invalid) { return denied(invalid.code(), invalid.path(), required); }
        catch (RuntimeException invalid) { return denied("MALFORMED", "$", required); }
    }
    private static Check denied(String code, String path, Requirement r) { return new Check(false, code, path, r.status(), Map.of()); }
    public static Map<String, Object> outputShape() {
        var recipes = new ArrayList<>(RECIPES.stream().map(RecipeProduction.Definition::recipe).toList()); recipes.add(PLACE);
        var step = Map.of("type", "object", "additionalProperties", false, "required", List.of("recipe", "batches"),
                "properties", Map.of("recipe", Map.of("type", "string", "enum", recipes), "batches", Map.of("type", "integer", "minimum", 1, "maximum", 16)));
        var delivery = Map.of("type", "object", "additionalProperties", false, "required", List.of("item", "amount"),
                "properties", Map.of("item", Map.of("type", "string", "pattern", "^minecraft:[a-z_]{1,40}$"), "amount", Map.of("type", "integer", "minimum", 1, "maximum", 64)));
        return Map.of("type", "object", "additionalProperties", false, "required", List.of("schema", "status", "steps", "deliveries"),
                "properties", Map.of("schema", Map.of("type", "integer", "enum", List.of(1)),
                        "status", Map.of("type", "string", "enum", List.of("plan", "insufficient_resources", "unsupported")),
                        "steps", Map.of("type", "array", "maxItems", 8, "items", step),
                        "deliveries", Map.of("type", "array", "maxItems", 3, "items", delivery)));
    }

    /** Read only canonical material facts; repair cannot replace them with its previous proposal. */
    public static Context contextFromPrompt(String text) throws StrictJson.Invalid {
        if(text==null || text.getBytes(StandardCharsets.UTF_8).length>8192)throw new IllegalArgumentException("Bounded material facts required");
        var root=StrictJson.object(text);
        if(root.containsKey("trustedContext"))root=StrictJson.object(Outcomes.string(root,"trustedContext","$"));
        if(!"material-plan-preview:1".equals(Outcomes.string(root,"contract","$")))throw new IllegalArgumentException("Material context contract");
        if(!(root.get("requiredDeliveries") instanceof Map<?,?> goals) || !(root.get("availableInventory") instanceof Map<?,?> inventory)
                || !(root.get("tableAvailable") instanceof Boolean table) || !(root.get("placementAllowed") instanceof Boolean place))
            throw new IllegalArgumentException("Canonical material facts required");
        var g=new HashMap<String,Long>();var stock=new HashMap<String,Long>();
        for(var e:goals.entrySet()) {
            if(!(e.getKey() instanceof String k) || !(e.getValue() instanceof Long n))throw new IllegalArgumentException("Typed goals");
            g.put(k,n);
        }
        for(var e:inventory.entrySet()) {
            if(!(e.getKey() instanceof String k) || !(e.getValue() instanceof Long n))throw new IllegalArgumentException("Typed stock");
            stock.put(k,n);
        }
        return new Context(Outcomes.string(root,"request","$"),g,stock,table,place);
    }

    /** Refine the grammar with deterministic quantities/status; the model still orders the steps. */
    public static Map<String,Object> outputShape(Context context) {
        var required=requirements(context);boolean feasible=required.status().equals("plan");
        var steps=required.batches().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->Map.of(
                "type","object","additionalProperties",false,"required",List.of("recipe","batches"),
                "properties",Map.of("recipe",Map.of("type","string","enum",List.of(e.getKey())),
                        "batches",Map.of("type","integer","enum",List.of(e.getValue()))))).toList();
        var deliveries=context.goals().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->Map.of(
                "type","object","additionalProperties",false,"required",List.of("item","amount"),
                "properties",Map.of("item",Map.of("type","string","enum",List.of(e.getKey())),
                        "amount",Map.of("type","integer","enum",List.of(e.getValue()))))).toList();
        Map<String,Object> stepArray=steps.isEmpty()?Map.of("type","array","maxItems",0):Map.of(
                "type","array","minItems",steps.size(),"maxItems",steps.size(),"uniqueItems",true,"items",Map.of("anyOf",steps));
        Map<String,Object> deliveryArray=!feasible?Map.of("type","array","maxItems",0):Map.of(
                "type","array","minItems",deliveries.size(),"maxItems",deliveries.size(),"uniqueItems",true,"items",Map.of("anyOf",deliveries));
        return Map.of("type","object","additionalProperties",false,"required",List.of("schema","status","steps","deliveries"),
                "properties",Map.of("schema",Map.of("type","integer","enum",List.of(1)),
                        "status",Map.of("type","string","enum",List.of(required.status())),"steps",stepArray,"deliveries",deliveryArray));
    }
}
