package dev.aivillages.core.kernel;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static dev.aivillages.core.kernel.Prerequisites.*;

/** Finite material graph expansion. There is no model, executor, world, job-write or lease-grant port. */
public final class DeterministicPrerequisiteResolver {
    private final Limits limits;
    private final LongSupplier ticks;
    public DeterministicPrerequisiteResolver(Limits limits, LongSupplier ticks) {
        this.limits = Objects.requireNonNull(limits); this.ticks = Objects.requireNonNull(ticks);
    }
    /** Exact bounded outcome vocabulary check, before a broad goal may enter planning.
     * A handled outcome must return to deterministic prerequisites; this never runs a model. */
    public record OutcomeCoverage(CapabilityId capability, boolean handled, boolean complete, int inspected) { }
    public static OutcomeCoverage outcomeCoverage(CapabilityId capability, Catalog catalog) {
        Objects.requireNonNull(capability);Objects.requireNonNull(catalog);
        return new OutcomeCoverage(capability,catalog.productions().stream().anyMatch(p->p.capability().equals(capability)),
                catalog.complete(),catalog.productions().size()+catalog.recipes().size());
    }
    public Proposal resolve(List<Need> requested, Catalog catalog, Supplies supplies, Methods methods,
                            Access access, Budgets.Ledger parent) {
        Objects.requireNonNull(catalog); Objects.requireNonNull(supplies); Objects.requireNonNull(methods);
        Objects.requireNonNull(access); Objects.requireNonNull(parent);
        long tick = ticks.getAsLong();
        var expansion = new Expansion(catalog, supplies, methods, access, parent, tick);
        try {
            if (tick < 0 || requested == null || requested.isEmpty() || requested.size() > limits.demands())
                throw stop(Reason.REQUEST_INVALID, Qualification.LIMIT);
            expansion.needs = requested.stream().sorted(Comparator.comparing(n -> n.demand().toString())).toList();
            if (expansion.needs.stream().map(Need::demand).distinct().count() != requested.size())
                throw stop(Reason.REQUEST_INVALID, Qualification.NONE);
            expansion.step(catalog.recipes().size() + catalog.productions().size() + requested.size());
            var groups = new TreeMap<String, List<Need>>();
            for (Need need : expansion.needs) {
                if (need.quantity() > limits.quantity()) throw stop(Reason.REQUEST_INVALID, Qualification.LIMIT);
                if (!access.current(need)) throw unauthorized();
                groups.computeIfAbsent(bindingKey(need) + ":" + need.item(), ignored -> new ArrayList<>()).add(need);
            }
            for (List<Need> group : groups.values()) {
                long wanted = 0;
                for (Need need : group) wanted = Math.addExact(wanted, need.quantity());
                if (wanted > limits.quantity()) throw stop(Reason.BUDGET_EXHAUSTED, Qualification.LIMIT);
                Need owner = group.getFirst();
                Solved solved = expansion.solve(owner.item(), wanted, owner, 1, new LinkedHashSet<>());
                long stock = solved.stock;
                for (Need need : group) {
                    long used = Math.min(stock, need.quantity()); stock -= used;
                    long produced = need.quantity() - used;
                    if (produced > 0 && !Objects.equals(solved.node,need.demand())) expansion.allocate(need.demand(), solved.node, produced, need.allocation());
                }
            }
            List<Node> nodes = expansion.plans.values().stream().map(Plan::node)
                    .sorted(Comparator.comparing(n -> n.id().toString())).toList();
            List<AllocationIntent> allocations = expansion.allocations.values().stream()
                    .sorted(Comparator.comparing(a -> a.demand() + ":" + a.producer())).toList();
            for (Node node : nodes) {
                long assigned = 0;
                for (AllocationIntent allocation : allocations) if (allocation.producer().equals(node.id()))
                    assigned = Math.addExact(assigned, allocation.quantity());
                if (assigned > node.output()) throw stop(Reason.REQUEST_INVALID, Qualification.NONE);
            }
            int bytes = contextBytes(expansion.needs, nodes, expansion.stock, allocations);
            if (bytes > limits.contextBytes()) throw stop(Reason.BUDGET_EXHAUSTED, Qualification.LIMIT);
            expansion.step((bytes + 63) / 64);
            return proposal(catalog, expansion.needs, nodes, expansion.stock, allocations,
                    ResolutionStatus.RESOLVED, null, Qualification.NONE, expansion.metrics(bytes), tick);
        } catch (Stop stopped) {
            return expansion.rejected(stopped);
        } catch (Budgets.Exhausted exhausted) {
            return expansion.rejected(stop(Reason.BUDGET_EXHAUSTED, Qualification.LIMIT));
        } catch (ArithmeticException | IllegalArgumentException invalid) {
            return expansion.rejected(stop(Reason.REQUEST_INVALID, Qualification.NONE));
        } catch (SecurityException denied) {
            return expansion.rejected(unauthorized());
        } catch (RuntimeException unavailable) {
            return expansion.rejected(stop(Reason.STORAGE_UNAVAILABLE, Qualification.UNKNOWN));
        }
    }
    private final class Expansion {
        final Catalog catalog; final Supplies supplies; final Methods methods; final Access access;
        final Budgets.Ledger parent; final long tick;
        List<Need> needs = List.of();
        Map<String, Plan> plans = new LinkedHashMap<>();
        Map<String, Pool> pools = new HashMap<>();
        final Map<String,Supply> inspectedStock=new HashMap<>();
        List<StockUse> stock = new ArrayList<>();
        Map<String, AllocationIntent> allocations = new HashMap<>();
        int work, edges, alternatives, visited;
        Expansion(Catalog catalog, Supplies supplies, Methods methods, Access access, Budgets.Ledger parent, long tick) {
            this.catalog=catalog; this.supplies=supplies; this.methods=methods; this.access=access; this.parent=parent; this.tick=tick;
        }
        void step(int amount) {
            if (amount < 0 || amount > limits.work() - work) throw stop(Reason.BUDGET_EXHAUSTED, Qualification.LIMIT);
            parent.debit(Budgets.Kind.INSTRUCTIONS, amount); work += amount;
        }
        void edge() { if (edges == limits.edges()) throw stop(Reason.BUDGET_EXHAUSTED, Qualification.LIMIT); edges++; step(1); }
        Solved solve(String item, long wanted, Need owner, int depth, Set<String> path) {
            step(1);
            if (wanted < 1 || wanted > limits.quantity()) throw stop(Reason.BUDGET_EXHAUSTED, Qualification.LIMIT);
            if (depth > limits.depth()) throw stop(Reason.BUDGET_EXHAUSTED, Qualification.LIMIT);
            if (!access.current(owner)) throw unauthorized();
            String key = bindingKey(owner) + ":" + item;
            if (!path.add(key)) throw stop(Reason.REQUEST_INVALID, Qualification.CYCLE);
            try {
                Supply proof=ResourceComposition.make(owner)?inspectedStock.get(key):null;
                if(proof==null){proof=Objects.requireNonNull(supplies.inspect(owner,item));step(Math.addExact(proof.inspected(),1));if(ResourceComposition.make(owner))inspectedStock.put(key,proof);}
                else step(1);
                if (!proof.item().equals(item) || !proof.observation().dimension().equals(owner.bound().observation().dimension()))
                    throw stop(Reason.REQUEST_INVALID, Qualification.NONE);
                if (proof.reason() == Reason.AUTHORITY_DENIED) throw unauthorized();
                if (proof.reason() == Reason.UNSUPPORTED_PRIMITIVE)
                    throw new Stop(ResolutionStatus.UNSUPPORTED_RUNTIME, proof.reason(), Qualification.UNSUPPORTED_ACCOUNTING);
                if (proof.reason() != null) throw stop(proof.reason(), Qualification.UNKNOWN);
                if (proof.status() == ObservationStatus.UNKNOWN || proof.tick() > tick || tick-proof.tick() > limits.observationAge())
                    throw stop(Reason.STALE_OBSERVATION, Qualification.UNKNOWN);
                String poolKey = owner.origin().scope().worldId() + ":" + proof.observation().dimension()
                        + ":" + item + ":" + proof.identity();
                Pool pool = pools.get(poolKey);
                if (pool == null) { pool = new Pool(proof.available(), 0); pools.put(poolKey, pool); }
                else if (pool.capacity != proof.available()) throw stop(Reason.STALE_OBSERVATION, Qualification.CHANGED);
                long taken = Math.min(wanted, pool.capacity-pool.used);
                if (taken > 0) {
                    pool.used = Math.addExact(pool.used, taken);
                }
                stock.add(new StockUse(owner, proof, taken));
                long deficit = wanted-taken;
                if (deficit == 0) return new Solved(null, taken);
                List<Production> options = catalog.productions().stream().filter(p -> p.output().equals(item))
                        .sorted(Comparator.comparing(Production::id)).toList();
                if (options.size() > limits.alternatives()) throw stop(Reason.BUDGET_EXHAUSTED, Qualification.LIMIT);
                if (options.isEmpty()) {
                    if (!catalog.complete()) throw stop(Reason.STALE_OBSERVATION, Qualification.UNKNOWN);
                    if (catalog.unsupportedOutputs().contains(item))
                        throw new Stop(ResolutionStatus.UNSUPPORTED_RUNTIME, Reason.UNSUPPORTED_PRIMITIVE, Qualification.NONE);
                    throw stop(Reason.RESOURCE_MISSING, Qualification.NONE);
                }
                Stop best = null;
                for (Production option : options) {
                    alternatives++; step(1);
                    Checkpoint before = checkpoint();
                    try {
                        Plan planned = plans.get(key);
                        if (planned != null && !planned.production.equals(option))
                            throw stop(Reason.RESOURCE_MISSING, Qualification.EXISTING_WORK);
                        if (planned == null) {
                            if (plans.size() == limits.nodes() || visited == limits.nodes())
                                throw stop(Reason.BUDGET_EXHAUSTED, Qualification.LIMIT);
                            visited++;
                            planned = new Plan(ResourceComposition.make(owner) && item.equals(owner.item()) ? owner.demand()
                                    : stableId("production:1:" + owner.demand() + ":" + key + ":" + option.id()), owner, item, option);
                            plans.put(key, planned);
                        }
                        long total = Math.addExact(planned.wanted, deficit);
                        if (total > limits.quantity()) throw stop(Reason.BUDGET_EXHAUSTED, Qualification.LIMIT);
                        long batches = total / option.outputPerBatch() + (total % option.outputPerBatch() == 0 ? 0 : 1);
                        long produced = Math.multiplyExact(batches, option.outputPerBatch());
                        if (produced > limits.quantity()) throw stop(Reason.BUDGET_EXHAUSTED, Qualification.LIMIT);
                        long addedBatches = batches-planned.batches;
                        List<Ingredient> ingredients = option.inputs();
                        Map<String, Long> remainder = Map.of();
                        if (!option.recipe().isEmpty()) {
                            Recipe recipe = catalog.recipes().stream().filter(r -> r.id().equals(option.recipe())).findFirst().orElseThrow();
                            ingredients=ResourceComposition.make(owner) ? option.inputs() : recipe.inputs(); remainder=recipe.remainders();
                        }
                        for (Ingredient ingredient : ingredients) {
                            if (addedBatches == 0) break;
                            long input = Math.multiplyExact(addedBatches, ingredient.quantity());
                            if (ingredient.alternatives().size() > limits.alternatives())
                                throw stop(Reason.BUDGET_EXHAUSTED, Qualification.LIMIT);
                            Stop missing = null; boolean supplied = false;
                            for (String alternative : ingredient.alternatives()) {
                                alternatives++; edge();
                                Checkpoint beforeInput=checkpoint();
                                try {
                                    Solved dependency=solve(alternative,input,owner,depth+1,path);
                                    // Checkpoint rollback replaces builders; reacquire the authoritative scratch builder.
                                    planned=plans.get(key);
                                    if (dependency.node!=null) {
                                        planned.dependencies.add(dependency.node);
                                        allocate(planned.id,dependency.node,input-dependency.stock,AllocationPolicy.MATERIAL_ONLY);
                                    }
                                    supplied=true;break;
                                } catch (Stop unavailable) {
                                    restore(beforeInput); if (fatal(unavailable)) throw unavailable;
                                    missing=preferred(missing,unavailable);
                                }
                            }
                            if (!supplied) throw Objects.requireNonNull(missing);
                        }
                        planned=plans.get(key);
                        var selected=Objects.requireNonNull(methods.resolve(option,batches,produced,owner));
                        CapabilityResolver.Decision decision=selected.decision();
                        step(Math.addExact(Math.addExact(decision.candidateWork(),decision.queriedPages()),1+selected.inspected()));
                        if (decision.routing().status()!=ResolutionStatus.RESOLVED) {
                            ResolutionStatus status=decision.routing().status()==ResolutionStatus.UNSUPPORTED_RUNTIME
                                    ? ResolutionStatus.UNSUPPORTED_RUNTIME : decision.routing().status()==ResolutionStatus.UNAUTHORIZED
                                    ? ResolutionStatus.UNAUTHORIZED : ResolutionStatus.BLOCKED;
                            throw new Stop(status,decision.routing().reason()==null?Reason.RESOURCE_MISSING:decision.routing().reason(),Qualification.NONE);
                        }
                        if (!decision.complete() || decision.selectedBindings()==null || decision.routing().artifact()==null
                                || !decision.selectedBindings().context().equals(owner.origin())
                                || !decision.selectedBindings().request().capability().equals(option.capability())
                                || !(ResourceComposition.make(owner)
                                    ? ResourceComposition.project(owner,option.capability(),produced).equals(decision.selectedBindings().request())
                                    : compatibleBindings(owner.bound(), decision.selectedBindings(), produced))
                                || !decision.selectedBindings().observation().equals(decision.observation().reference())
                                || decision.observation().status()!=ObservationStatus.PRESENT
                                || decision.observation().observedTick()>tick
                                || tick-decision.observation().observedTick()>limits.observationAge())
                            throw stop(Reason.STALE_OBSERVATION, Qualification.UNKNOWN);
                        planned.wanted=total;planned.batches=batches;planned.output=produced;
                        planned.request=decision.selectedBindings();planned.method=decision.routing().artifact();
                        var returned=new TreeMap<String,Long>();
                        for(var entry:remainder.entrySet()) {
                            long quantity=Math.multiplyExact(batches,entry.getValue());
                            if(quantity>limits.quantity())throw stop(Reason.BUDGET_EXHAUSTED,Qualification.LIMIT);
                            returned.put(entry.getKey(),quantity);
                        }
                        planned.remainders=Map.copyOf(returned);
                        return new Solved(planned.id,taken);
                    } catch (Stop failed) {
                        restore(before); if(fatal(failed))throw failed;
                        best=preferred(best,failed);
                    }
                }
                throw Objects.requireNonNull(best);
            } finally { path.remove(key); }
        }
        void allocate(UUID demand,UUID producer,long quantity,AllocationPolicy policy) {
            if(producer==null || quantity<1)throw stop(Reason.REQUEST_INVALID,Qualification.NONE);
            String key=demand+":"+producer+":"+policy;
            AllocationIntent old=allocations.get(key);
            allocations.put(key,new AllocationIntent(demand,producer,old==null?quantity:Math.addExact(old.quantity(),quantity),policy));
        }
        Checkpoint checkpoint() {
            step(plans.size()+pools.size()+allocations.size());
            var planCopy=new LinkedHashMap<String,Plan>();plans.forEach((k,v)->planCopy.put(k,new Plan(v)));
            var poolCopy=new HashMap<String,Pool>();pools.forEach((k,v)->poolCopy.put(k,new Pool(v.capacity,v.used)));
            return new Checkpoint(planCopy,poolCopy,new ArrayList<>(stock),new HashMap<>(allocations));
        }
        void restore(Checkpoint checkpoint) {
            plans=checkpoint.plans;pools=checkpoint.pools;stock=checkpoint.stock;allocations=checkpoint.allocations;
        }
        Metrics metrics(int bytes) { return new Metrics(visited,edges,alternatives,work,bytes); }
        Proposal rejected(Stop stopped) {
            return proposal(catalog,needs,List.of(),List.of(),List.of(),stopped.status,stopped.reason,
                    stopped.qualification,metrics(0),tick);
        }
    }
    private static final class Plan {
        final UUID id; final Need owner;final String item;final Production production;
        long wanted,batches,output;ValidatedRequest request;ArtifactRef method;
        Set<UUID> dependencies=new LinkedHashSet<>();Map<String,Long> remainders=Map.of();
        Plan(UUID id,Need owner,String item,Production production) { this.id=id;this.owner=owner;this.item=item;this.production=production; }
        Plan(Plan other) {
            this(other.id,other.owner,other.item,other.production);wanted=other.wanted;batches=other.batches;output=other.output;
            request=other.request;method=other.method;dependencies=new LinkedHashSet<>(other.dependencies);remainders=other.remainders;
        }
        Node node() { return new Node(id,owner.demand(),item,output,batches,output-wanted,production,request,method,
                dependencies.stream().sorted(Comparator.comparing(UUID::toString)).toList(),remainders); }
    }
    private static final class Pool {
        final long capacity;long used;
        Pool(long capacity,long used){this.capacity=capacity;this.used=used;}
    }
    private record Solved(UUID node,long stock) { }
    private record Checkpoint(Map<String,Plan> plans,Map<String,Pool> pools,List<StockUse> stock,
                              Map<String,AllocationIntent> allocations) { }
    private static final class Stop extends RuntimeException {
        final ResolutionStatus status;final Reason reason;final Qualification qualification;
        Stop(ResolutionStatus status,Reason reason,Qualification qualification) {
            this.status=status;this.reason=reason;this.qualification=qualification;
        }
    }
    private static Stop stop(Reason reason,Qualification qualification) { return new Stop(ResolutionStatus.BLOCKED,reason,qualification); }
    private static Stop unauthorized() { return new Stop(ResolutionStatus.UNAUTHORIZED,Reason.AUTHORITY_DENIED,Qualification.NONE); }
    private static boolean fatal(Stop value) {
        return value.status==ResolutionStatus.UNAUTHORIZED || value.reason==Reason.BUDGET_EXHAUSTED
                || value.reason==Reason.STALE_OBSERVATION || value.reason==Reason.STORAGE_UNAVAILABLE
                || value.reason==Reason.TARGET_UNAVAILABLE;
    }
    static boolean compatibleBindings(ValidatedRequest root,ValidatedRequest method,long quantity) {
        var before=root.request().arguments();var after=method.request().arguments();
        for(String name:List.of("actor","source","destination"))
            if(before.containsKey(name) && !Objects.equals(before.get(name),after.get(name)))return false;
        return after.get("amount") instanceof IntValue amount && amount.value()==quantity;
    }
    private static Stop preferred(Stop first,Stop second) {
        if(first==null)return second;
        if(second.reason==Reason.BUDGET_EXHAUSTED || second.qualification==Qualification.CYCLE
                || second.status==ResolutionStatus.UNAUTHORIZED)return second;
        if(first.status==ResolutionStatus.UNSUPPORTED_RUNTIME && second.status==ResolutionStatus.BLOCKED)return second;
        if(second.reason==Reason.STALE_OBSERVATION)return second;
        return first;
    }
    private static int contextBytes(List<Need> needs,List<Node> nodes,List<StockUse> stock,List<AllocationIntent> allocations) {
        long result=128;
        for(Need need:needs)result=Math.addExact(result,128L+RequestCodec.encode(need.bound().request()).getBytes(StandardCharsets.UTF_8).length);
        for(Node node:nodes)result=Math.addExact(result,node.toString().getBytes(StandardCharsets.UTF_8).length);
        for(StockUse use:stock)result=Math.addExact(result,(ResourceComposition.make(use.owner()) ? use.owner().demand()+":"+use.proof()+":"+use.quantity() : use.toString()).getBytes(StandardCharsets.UTF_8).length);
        for(AllocationIntent allocation:allocations)result=Math.addExact(result,allocation.toString().getBytes(StandardCharsets.UTF_8).length);
        return Math.toIntExact(result);
    }
}
