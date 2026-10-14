package dev.aivillages.core.kernel;

import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;

/** IMP-018 proposal contract 1. Scratch values confer no stock, admission or effect authority. */
public final class Prerequisites {
    public static final int CONTRACT_VERSION = 1;
    public static final String COMPATIBLE_ARCHITECTURE = "0.5";
    private Prerequisites() { }

    public enum AllocationPolicy { ATTRIBUTABLE_WHEAT, MATERIAL_ONLY }
    public enum Qualification { NONE, CYCLE, LIMIT, UNKNOWN, CHANGED, EXISTING_WORK, UNSUPPORTED_ACCOUNTING }

    public record Limits(int demands, int nodes, int depth, int edges, int alternatives,
                         int work, int contextBytes, long quantity, long observationAge) {
        public Limits {
            if (demands < 1 || demands > 8 || nodes < 1 || nodes > 16 || depth < 1 || depth > 4
                    || edges < 1 || edges > 64 || alternatives < 1 || alternatives > 8
                    || work < 1 || work > 512 || contextBytes < 256 || contextBytes > 16_384
                    || quantity < 1 || quantity > 1_000_000 || observationAge < 0 || observationAge > 10)
                throw new IllegalArgumentException("Finite prerequisite limits");
        }
        public static Limits defaults() { return new Limits(8, 16, 4, 64, 4, 512, 16_384, 4096, 10); }
    }
    /** Job responsibility is context, not proof that pre-existing stock completed that job's outcome. */
    public record Need(UUID demand, Jobs.Guard guard, ValidatedRequest bound, String item,
                       long quantity, AllocationPolicy allocation, Cuboid acquisitionSource) {
        public Need(UUID demand, Jobs.Guard guard, ValidatedRequest bound, String item, long quantity, AllocationPolicy allocation) {
            this(demand,guard,bound,item,quantity,allocation,null);
        }
        public Need {
            Objects.requireNonNull(demand); Objects.requireNonNull(guard); Objects.requireNonNull(bound);
            itemId(item); Objects.requireNonNull(allocation);
            if (quantity < 1) throw new IllegalArgumentException("Positive exact material quantity");
            if(acquisitionSource!=null && (!acquisitionSource.dimension().equals(bound.observation().dimension())
                    || ResourceLeases.cells(acquisitionSource)>256))throw new IllegalArgumentException("Bounded acquisition source");
            if (bound.request().arguments().size() > 16 || bound.request().arguments().keySet().stream()
                    .anyMatch(key -> key.length() > 64 || !key.matches("[a-z][a-zA-Z0-9_]{0,63}")))
                throw new IllegalArgumentException("Bounded request binding");
        }
        public TrustedContext origin() { return bound.context(); }
    }
    public record Ingredient(List<String> alternatives, long quantity) {
        public Ingredient {
            alternatives = alternatives.stream().distinct().sorted().toList();
            if (alternatives.isEmpty() || alternatives.size() > 16 || quantity < 1)
                throw new IllegalArgumentException("Bounded integral ingredient");
            alternatives.forEach(Prerequisites::itemId);
        }
    }
    /** Values captured from the installed registry. No embedded code or recipe execution. */
    public record Recipe(String id, String output, long outputPerBatch, List<Ingredient> inputs,
                         Map<String, Long> remainders) {
        public Recipe {
            itemId(id); itemId(output); inputs = List.copyOf(inputs); remainders = Map.copyOf(remainders);
            if (outputPerBatch < 1 || inputs.size() > 8 || remainders.size() > 8)
                throw new IllegalArgumentException("Bounded recipe");
            remainders.forEach((item, quantity) -> {
                itemId(item); if (quantity == null || quantity < 1) throw new IllegalArgumentException("Remainder");
            });
        }
    }
    /** Host-registered meaning of an existing production capability, independently of recipe presence. */
    public record Production(String id, String output, CapabilityId capability, String recipe,
                             long outputPerBatch, List<Ingredient> inputs) {
        public Production {
            itemId(id); itemId(output); Objects.requireNonNull(capability); Objects.requireNonNull(recipe);
            if (!recipe.isEmpty()) itemId(recipe);
            inputs = List.copyOf(inputs);
            if (outputPerBatch < 1 || inputs.size() > 8) throw new IllegalArgumentException("Production mapping");
        }
        public static Production cropDelivery() {
            return new Production("cognitivecraft:deliver_wheat", "minecraft:wheat", CropDelivery.ID, "", 1,
                    List.of(new Ingredient(List.of("minecraft:mature_wheat"), 1)));
        }
    }
    public record Catalog(long epoch, List<Recipe> recipes, List<Production> productions,
                          Set<String> unsupportedOutputs, boolean complete) {
        public Catalog {
            recipes = List.copyOf(recipes); productions = List.copyOf(productions);
            unsupportedOutputs = Set.copyOf(unsupportedOutputs); unsupportedOutputs.forEach(Prerequisites::itemId);
            if (epoch < 0 || recipes.size() > 128 || productions.size() > 32 || unsupportedOutputs.size() > 128
                    || recipes.stream().map(Recipe::id).distinct().count() != recipes.size()
                    || productions.stream().map(Production::id).distinct().count() != productions.size())
                throw new IllegalArgumentException("Bounded installed catalog");
            for (Production production : productions) if (!production.recipe().isEmpty()) {
                Recipe recipe = recipes.stream().filter(r -> r.id().equals(production.recipe())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Mapping without installed recipe"));
                if (!recipe.output().equals(production.output()) || recipe.outputPerBatch() != production.outputPerBatch())
                    throw new IllegalArgumentException("Recipe/mapping disagreement");
            }
        }
    }
    /** Scope-approved free stock, after the lease owner's current conflicts; UNKNOWN is not zero. */
    public record Supply(String item, ObservationStatus status, long available, String identity,
                         ObservationRef observation, long tick, int inspected, Reason reason) {
        public Supply {
            itemId(item); Objects.requireNonNull(status); Objects.requireNonNull(observation);
            if (available < 0 || identity == null || identity.isBlank() || identity.length() > 128
                    || tick < 0 || inspected < 0 || inspected > 256
                    || status == ObservationStatus.ABSENT && available != 0)
                throw new IllegalArgumentException("Bounded supply proof");
        }
    }
    @FunctionalInterface public interface Supplies { Supply inspect(Need owner, String item); }
    public record MethodSelection(CapabilityResolver.Decision decision,int inspected) {
        public MethodSelection {
            Objects.requireNonNull(decision);
            if(inspected<0 || inspected>256)throw new IllegalArgumentException("Bounded method observation work");
        }
    }
    @FunctionalInterface public interface Methods {
        MethodSelection resolve(Production production, long batches, long output, Need owner);
    }
    @FunctionalInterface public interface Access { boolean current(Need need); }

    public record StockUse(Need owner, Supply proof, long quantity) {
        public StockUse {
            Objects.requireNonNull(owner); Objects.requireNonNull(proof);
            if (quantity < 0 || quantity > proof.available()) throw new IllegalArgumentException("Stock use");
        }
    }
    public record AllocationIntent(UUID demand, UUID producer, long quantity, AllocationPolicy policy) {
        public AllocationIntent {
            Objects.requireNonNull(demand); Objects.requireNonNull(producer); Objects.requireNonNull(policy);
            if (quantity < 1) throw new IllegalArgumentException("Output units");
        }
    }
    public record Node(UUID id, UUID owner, String item, long output, long batches, long surplus,
                       Production production, ValidatedRequest request, ArtifactRef method,
                       List<UUID> dependencies, Map<String, Long> remainders) {
        public Node {
            Objects.requireNonNull(id); Objects.requireNonNull(owner); itemId(item);
            Objects.requireNonNull(production); Objects.requireNonNull(request); Objects.requireNonNull(method);
            dependencies = List.copyOf(dependencies); remainders = Map.copyOf(remainders);
            if (output < 1 || batches < 1 || surplus < 0 || surplus > output || dependencies.size() > 8
                    || new HashSet<>(dependencies).size() != dependencies.size() || dependencies.contains(id)
                    || remainders.size() > 8 || !request.request().capability().equals(production.capability())
                    || !method.capability().equals(production.capability()))
                throw new IllegalArgumentException("Bounded production proposal");
        }
    }
    public record Metrics(int nodes, int edges, int alternatives, int work, int contextBytes) {
        public Metrics {
            if (nodes < 0 || edges < 0 || alternatives < 0 || work < 0 || contextBytes < 0)
                throw new IllegalArgumentException("Measured work");
        }
    }
    /** Issued immutable scratch result. Admission must revalidate every owner, stock and method. */
    public static final class Proposal {
        private final UUID id;
        private final Catalog catalog;
        private final List<Need> needs;
        private final List<Node> nodes;
        private final List<StockUse> stock;
        private final List<AllocationIntent> allocations;
        private final ResolutionStatus status;
        private final Reason reason;
        private final Qualification qualification;
        private final Metrics metrics;
        private final long capturedTick;
        private Proposal(Catalog catalog, List<Need> needs, List<Node> nodes, List<StockUse> stock,
                         List<AllocationIntent> allocations, ResolutionStatus status, Reason reason,
                         Qualification qualification, Metrics metrics, long tick) {
            this.catalog = catalog; this.needs = List.copyOf(needs); this.nodes = List.copyOf(nodes);
            this.stock = List.copyOf(stock); this.allocations = List.copyOf(allocations);
            this.status = status; this.reason = reason; this.qualification = qualification;
            this.metrics = metrics; this.capturedTick = tick;
            id = stableId("proposal:" + needs.stream().map(n -> n.demand().toString()).sorted().toList());
        }
        public int schema() { return CONTRACT_VERSION; }
        public UUID id() { return id; }
        public Catalog catalog() { return catalog; }
        public List<Need> needs() { return needs; }
        public List<Node> nodes() { return nodes; }
        public List<StockUse> stock() { return stock; }
        public List<AllocationIntent> allocations() { return allocations; }
        public ResolutionStatus status() { return status; }
        public Reason reason() { return reason; }
        public Qualification qualification() { return qualification; }
        public Metrics metrics() { return metrics; }
        public long capturedTick() { return capturedTick; }
        public boolean accepted() { return status == ResolutionStatus.RESOLVED; }
    }
    static Proposal proposal(Catalog catalog, List<Need> needs, List<Node> nodes, List<StockUse> stock,
                             List<AllocationIntent> allocations, ResolutionStatus status, Reason reason,
                             Qualification qualification, Metrics metrics, long tick) {
        return new Proposal(catalog, needs, nodes, stock, allocations, status, reason, qualification, metrics, tick);
    }
    public record Admission(ResolutionStatus status, Reason reason, Jobs.Change publication,
                            List<UUID> producers, Qualification qualification) {
        public Admission { producers = List.copyOf(producers); }
    }
    /** Runtime expansion and its authoritative publication/billing decision, in one bounded operation. */
    public record Result(Proposal proposal, Admission admission) { }

    public static long exactQuantity(Object value) {
        if (!(value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte))
            throw new IllegalArgumentException("Integral quantity required");
        long result = ((Number)value).longValue();
        if (result < 1) throw new IllegalArgumentException("Positive quantity");
        return result;
    }
    static void itemId(String id) {
        if (id == null || id.length() > 128 || !id.matches("[a-z0-9_.-]+:[a-z0-9_/.-]{1,96}"))
            throw new IllegalArgumentException("Namespaced item/recipe/method");
    }
    static UUID stableId(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }
    static String bindingKey(Need need) {
        var args = new TreeMap<>(need.bound().request().arguments()); args.remove("amount");
        return need.origin() + ":" + need.bound().request().capability() + ":" + args + ":" + need.allocation() + ":" + need.acquisitionSource();
    }
}
