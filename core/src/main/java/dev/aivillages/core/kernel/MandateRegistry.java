package dev.aivillages.core.kernel;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiPredicate;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Mandates.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;

/** Game-thread authority owner. Publication and physical/Job cleanup stay with their existing owners. */
public final class MandateRegistry {
    @FunctionalInterface public interface Storage { CompletionStage<Snapshot> replace(Snapshot expected, Snapshot next); }
    public interface References {
        boolean authority(ActorRef worker, Effect effect, TrustedContext issuer);
        boolean available(TrustedContext coordinator);
        default boolean organization(UUID reference, TrustedContext issuer) { return reference == null; }
    }
    public record Assignment(UUID job, long generation, ArtifactRef artifact, Budgets.Limits allowance) {
        public Assignment { Objects.requireNonNull(job); Objects.requireNonNull(artifact); Objects.requireNonNull(allowance);
            if (generation < 1) throw new IllegalArgumentException("Current assignment"); }
    }
    /** Read current generation, request and run through Jobs; request cancellation, never release a lease here. */
    public interface Executions {
        Assignment assignment(ValidatedRequest request, RunCorrelation run);
        boolean current(Binding binding);
        void requestStop(Binding binding);
    }
    private record Written(Snapshot snapshot, Throwable failure, long completedAt) { }
    private final Thread gameThread = Thread.currentThread();
    private final Storage storage;
    private final References references;
    private final Executions executions;
    private final Clock clock;
    private final Limits limits;
    private final boolean readOnly;
    private final ConcurrentLinkedQueue<Written> writes = new ConcurrentLinkedQueue<>();
    private final Set<UUID> revokedNow = new HashSet<>();
    // Fresh process consent is required after any possible unacknowledged authority write.
    private final Set<UUID> activated = new HashSet<>(), admittedRuns = new HashSet<>();
    private Map<UUID, Grant> grants;
    private Map<UUID, Binding> bindings;
    private Snapshot state, pending;
    private long deadline, lastMaintenance = Long.MIN_VALUE;
    private int maintenanceCursor;
    private boolean fenced;
    private BiPredicate<Set<WorldRetentionManager.Key>, Boolean> rootPublisher = (keys, complete) -> true;

    public MandateRegistry(Snapshot initial, Storage storage, References references, Executions executions,
                           Clock clock, Limits limits, boolean readOnly) {
        state = Objects.requireNonNull(initial); this.storage = Objects.requireNonNull(storage);
        this.references = Objects.requireNonNull(references); this.executions = Objects.requireNonNull(executions);
        this.clock = Objects.requireNonNull(clock); this.limits = Objects.requireNonNull(limits); this.readOnly = readOnly;
        validate(initial, limits); index();
    }
    public Snapshot snapshot() { thread(); return state; }
    public boolean reservedRun(UUID run) {
        thread(); return bindings.containsKey(run) || pending != null && pending.bindings().stream().anyMatch(b -> b.run().equals(run));
    }
    public boolean ready() { thread(); return !readOnly && !fenced && pending == null; }
    public boolean rootsComplete() { thread(); return !readOnly && !fenced; }
    public Set<WorldRetentionManager.Key> protectedRoots() {
        thread(); var keys = roots(state); if (pending != null) keys.addAll(roots(pending)); return Set.copyOf(keys);
    }
    public void retentionGuard(BiPredicate<Set<WorldRetentionManager.Key>, Boolean> publisher) {
        thread(); rootPublisher = Objects.requireNonNull(publisher);
        if (!rootPublisher.test(protectedRoots(), rootsComplete())) fenced = true;
    }
    public Change grant(UUID id, UUID parent, UUID organization, Rights rights, long expiresAt,
                        Budgets.Limits budget, TrustedContext grantee, TrustedContext issuer) {
        thread();
        if (!sameWorld(issuer) || !sameWorld(grantee)) return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
        if (!ready()) return unavailable();
        if (id == null || rights == null || budget == null || grantee.equals(issuer)
                || expiresAt <= clock.millis() || budget.deadlineEpochMillis() <= clock.millis()
                || budget.deadlineEpochMillis() > expiresAt || grants.containsKey(id))
            return reject(Status.INVALID, Reason.REQUEST_INVALID);
        if (state.grants().size() == limits.mandates()) return reject(Status.LIMIT_REACHED, Reason.STORAGE_LIMIT_REACHED);
        if (!references.available(issuer) || !references.available(grantee)
                || organization != null && !references.organization(organization, issuer))
            return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
        if (parent == null) {
            for (var actor : rights.workers()) for (var effect : rights.actions())
                if (!references.authority(actor, effect, issuer)) return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
        } else {
            List<Grant> chain = chain(parent);
            if (chain.isEmpty() || chain.size() == limits.depth() || !chain.getFirst().grantee().equals(issuer))
                return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
            for (Grant ancestor : chain) {
                if (ancestor.id().equals(id) || ancestor.issuer().equals(grantee) || ancestor.grantee().equals(grantee)
                        || !rights.subsetOf(ancestor.rights()) || expiresAt > ancestor.expiresAt()
                        || !within(budget, ancestor.budget())) return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
            }
            Grant root = chain.getLast();
            for (var actor : rights.workers()) for (var effect : rights.actions())
                if (!references.authority(actor, effect, root.issuer())) return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
        }
        try {
            long now = clock.millis();
            var row = new Grant(id, 1, issuer, grantee, parent, organization, rights, now, expiresAt, budget,
                    Map.of(), false, List.of(new Event(1, EventKind.GRANTED, id, issuer)));
            var rows = new ArrayList<>(state.grants()); rows.add(row);
            return publish(rows, state.bindings(), id, 1);
        } catch (IllegalArgumentException | NullPointerException invalid) { return reject(Status.INVALID, Reason.REQUEST_INVALID); }
        catch (ArithmeticException overflow) { return reject(Status.LIMIT_REACHED, Reason.BUDGET_EXHAUSTED); }
    }
    /** Acceptance immediately fences descendants; APPLIED is the separate durable acknowledgement. */
    public Change revoke(UUID id, long revision, TrustedContext caller) {
        thread(); Grant row = grants.get(id);
        if (row == null || !sameWorld(caller) || !revoker(row, caller)) return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
        if (row.revoked()) return new Change(Status.DUPLICATE, null, id, row.revision());
        if (!ready()) return unavailable();
        if (revision != row.revision()) return reject(Status.INVALID, Reason.STALE_OBSERVATION);
        return revoke(row, EventKind.REVOKED, caller);
    }
    private Change revoke(Grant row, EventKind kind, TrustedContext actor) {
        try {
            Grant next = edit(row, row.reserved(), true, kind, row.id(), actor);
            var rows = new ArrayList<>(state.grants()); rows.set(rows.indexOf(row), next);
            revokedNow.add(row.id());
            return publish(rows, state.bindings(), row.id(), next.revision());
        } catch (ArithmeticException overflow) { fenced = true; return unavailable(); }
    }
    /** Recovered grants require exact issuer consent; deadlines and cumulative reservations remain unchanged. */
    public Change revalidate(UUID id, long revision, TrustedContext issuer) {
        thread(); Grant g = grants.get(id);
        if (g == null || !g.issuer().equals(issuer) || !sameWorld(issuer) || g.revoked() || revokedNow.contains(id)
                || clock.millis() < g.issuedAt() || g.expiresAt() <= clock.millis() || g.budget().deadlineEpochMillis() <= clock.millis())
            return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
        if (!ready()) return unavailable();
        if (g.revision() != revision) return reject(Status.INVALID, Reason.STALE_OBSERVATION);
        if (!references.available(g.issuer()) || !references.available(g.grantee())) return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
        List<Grant> parents = g.parent() == null ? List.of() : chain(g.parent());
        if (g.parent() != null && (parents.isEmpty() || parents.size() == limits.depth()
                || parents.stream().anyMatch(p -> p.id().equals(id)) || !parents.getFirst().grantee().equals(issuer)
                || g.expiresAt() > parents.getFirst().expiresAt()
                || !g.rights().subsetOf(parents.getFirst().rights()) || !within(g.budget(), parents.getFirst().budget())))
            return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
        TrustedContext original = parents.isEmpty() ? issuer : parents.getLast().issuer();
        for (ActorRef actor : g.rights().workers()) for (Effect effect : g.rights().actions())
            if (!references.authority(actor, effect, original)) return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
        if (activated.contains(id)) return new Change(Status.DUPLICATE, null, id, revision);
        try {
            Grant next = edit(g, g.reserved(), false, EventKind.REVALIDATED, id, issuer);
            var rows = new ArrayList<>(state.grants()); rows.set(rows.indexOf(g), next);
            return publish(rows, state.bindings(), id, next.revision());
        } catch (ArithmeticException overflow) { fenced = true; return unavailable(); }
    }
    /** Coarse eligibility is never enough to authorize an effect. Exact durable binding is checked below. */
    public boolean controls(ActorRef actor, Effect effect, TrustedContext caller) {
        thread(); if (!sameWorld(caller) || readOnly || fenced) return false;
        for (Grant row : state.grants()) if (row.grantee().equals(caller) && row.rights().workers().contains(actor)
                && row.rights().actions().contains(effect)) {
            var chain = chain(row.id());
            if (!chain.isEmpty() && references.authority(actor, effect, chain.getLast().issuer())) return true;
        }
        return false;
    }
    /** Deterministic named candidate set; bounded independently of citizen selection or display metadata. */
    public List<ActorRef> workers(TrustedContext caller) {
        thread(); if (!sameWorld(caller) || readOnly || fenced) return List.of();
        var result = new TreeMap<String, ActorRef>();
        for (Grant row : state.grants()) if (row.grantee().equals(caller) && row.rights().actions().contains(Effect.OBSERVE)) {
            var chain = chain(row.id()); if (chain.isEmpty()) continue;
            for (ActorRef actor : row.rights().workers()) if (references.authority(actor, Effect.OBSERVE, chain.getLast().issuer()))
                result.put(actor.citizenId().toString(), actor);
        }
        return List.copyOf(result.values());
    }
    /** Existing lease/execution preparation calls this before admitting any native action. */
    public Change prepare(ValidatedRequest request, RunCorrelation run) {
        thread();
        if (request == null || run == null || !sameWorld(request.context()) || readOnly || fenced)
            return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
        if (!run.artifact().capability().equals(request.request().capability())) return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
        Binding old = bindings.get(run.runId());
        if (old != null) return old.request().equals(request) && old.artifact().equals(run.artifact())
                && current(old) ? new Change(Status.DUPLICATE, null, old.run(), state.revision())
                : reject(Status.DENIED, Reason.AUTHORITY_DENIED);
        if (!ready()) return new Change(Status.PENDING, null, run.runId(), state.revision());
        if (state.bindings().size() == limits.bindings()) return reject(Status.LIMIT_REACHED, Reason.STORAGE_LIMIT_REACHED);
        Assignment owner = executions.assignment(request, run);
        if (owner == null || !owner.artifact().equals(run.artifact())) return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
        List<Grant> selected = List.of();
        for (Grant row : state.grants().stream().sorted(Comparator.comparing(r -> r.id().toString())).toList()) {
            if (!row.grantee().equals(request.context()) || !row.rights().matches(request)
                    || ResourceDelivery.spec(request.request().capability()).filter(s -> row.rights().actions().containsAll(s.effects())).isEmpty()) continue;
            var candidate = chain(row.id());
            if (candidate.isEmpty() || candidate.stream().anyMatch(g -> !g.rights().matches(request))) continue;
            ActorRef actor = ((ActorValue)request.request().arguments().get("actor")).value();
            if (row.rights().actions().stream().anyMatch(e -> !references.authority(actor, e, candidate.getLast().issuer()))) continue;
            boolean fits = true;
            for (Grant g : candidate) {
                if (!within(owner.allowance(), g.budget()) || owner.allowance().deadlineEpochMillis() > g.expiresAt()) { fits = false; break; }
                for (var kind : Budgets.Kind.values()) if (owner.allowance().maximum(kind)
                        > g.budget().maximum(kind) - g.reserved().getOrDefault(kind, 0L)) fits = false;
            }
            if (fits) { selected = candidate; break; }
        }
        if (selected.isEmpty()) return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
        try {
            // Delegated execution is known, model-free work; byte/repair/trial allowance may not be smuggled in.
            for (var kind : List.of(Budgets.Kind.INPUT_BYTES, Budgets.Kind.OUTPUT_BYTES, Budgets.Kind.REPAIRS,
                    Budgets.Kind.CANDIDATES, Budgets.Kind.TRIALS))
                if (owner.allowance().maximum(kind) != 0) return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
            Binding binding = new Binding(run.runId(), owner.job(), owner.generation(), selected.getFirst().id(),
                    run.artifact(), request, owner.allowance());
            var rows = new ArrayList<>(state.grants());
            for (Grant g : selected) {
                var charged = new EnumMap<Budgets.Kind, Long>(Budgets.Kind.class); charged.putAll(g.reserved());
                for (var kind : Budgets.Kind.values()) charged.put(kind,
                        Math.addExact(charged.getOrDefault(kind, 0L), owner.allowance().maximum(kind)));
                rows.set(rows.indexOf(g), edit(g, charged, false, EventKind.RESERVED, binding.run(), request.context()));
            }
            var uses = new ArrayList<>(state.bindings()); uses.add(binding);
            return publish(rows, uses, binding.run(), state.revision() + 1);
        } catch (ArithmeticException overflow) { return reject(Status.LIMIT_REACHED, Reason.BUDGET_EXHAUSTED); }
    }
    public boolean allows(ValidatedRequest request, UUID run, Effect effect, EffectTarget target) {
        thread(); Binding binding = bindings.get(run);
        if (binding == null || !binding.request().equals(request) || !current(binding)) return false;
        List<Grant> chain = chain(binding.mandate());
        ActorRef worker = ((ActorValue)request.request().arguments().get("actor")).value();
        for (Grant grant : chain) if (!grant.rights().actions().contains(effect) || !grant.rights().matches(request)
                || !contains(grant.rights().area(), target.dimension(), target.x(), target.y(), target.z())) return false;
        return !chain.isEmpty() && references.authority(worker, effect, chain.getLast().issuer());
    }
    public Evidence evidence(UUID id, TrustedContext caller) {
        thread(); Grant row = grants.get(id);
        if (row == null || !sameWorld(caller) || !row.issuer().equals(caller) && !row.grantee().equals(caller))
            return new Evidence(null, 0, Status.DENIED, List.of(), 0);
        var chain = chain(id);
        Status status = chain.isEmpty() ? row.expiresAt() <= clock.millis() ? Status.EXPIRED : Status.DENIED : Status.APPLIED;
        return new Evidence(id, row.revision(), status, chain.stream().map(Grant::id).toList(), chain.size());
    }
    public List<Evidence> query(TrustedContext caller, int offset, int maximum) {
        thread(); if (!sameWorld(caller) || offset < 0 || maximum < 1 || maximum > limits.query())
            throw new IllegalArgumentException("Private mandate page");
        return state.grants().stream().filter(g -> g.issuer().equals(caller) || g.grantee().equals(caller))
                .skip(offset).limit(maximum).map(g -> evidence(g.id(), caller)).toList();
    }
    /** One finite mixed expiry/cleanup slice per tick. No direct lease release or assignment mutation. */
    public int maintain(long tick) {
        thread(); tick(); if (tick <= lastMaintenance) return 0; lastMaintenance = tick;
        int count = state.grants().size() + state.bindings().size(), inspected = 0;
        if (count == 0) return 0;
        while (inspected < Math.min(count, limits.cleanup())) {
            int at = maintenanceCursor++ % count; inspected++;
            if (at < state.grants().size()) {
                Grant g = state.grants().get(at);
                if (g.expiresAt() <= clock.millis() && !g.revoked() && ready()) revoke(g, EventKind.EXPIRED, g.issuer());
            } else {
                Binding b = state.bindings().get(at - state.grants().size());
                if (!current(b)) executions.requestStop(b);
            }
        }
        maintenanceCursor %= count; return inspected;
    }
    public void tick() {
        thread(); Written written = writes.poll();
        if (written != null && pending != null) {
            if (written.failure() != null || !pending.equals(written.snapshot()) || written.completedAt() >= deadline
                    || fenced || !rootPublisher.test(roots(pending), true)) { fenced = true; }
            else {
                Snapshot before = state; state = pending; index();
                for (Grant g : state.grants()) {
                    Grant old = before.grants().stream().filter(r -> r.id().equals(g.id())).findFirst().orElse(null);
                    if (old == null || !old.equals(g) && g.events().getLast().kind() == EventKind.REVALIDATED) activated.add(g.id());
                }
                for (Binding b : state.bindings()) if (before.bindings().stream().noneMatch(old -> old.run().equals(b.run()))) admittedRuns.add(b.run());
            }
            pending = null;
        }
        if (pending != null && clock.millis() >= deadline) fenced = true;
        if (fenced) rootPublisher.test(protectedRoots(), false);
    }
    public void close() { thread(); fenced = true; rootPublisher.test(protectedRoots(), false); }
    private boolean current(Binding binding) {
        return !readOnly && !fenced && admittedRuns.contains(binding.run()) && clock.millis() < binding.allowance().deadlineEpochMillis()
                && !chain(binding.mandate()).isEmpty() && executions.current(binding);
    }
    private List<Grant> chain(UUID id) {
        if (readOnly || fenced) return List.of();
        var result = new ArrayList<Grant>(); var seen = new HashSet<UUID>();
        while (id != null) {
            Grant g = grants.get(id);
            if (g == null || result.size() == limits.depth() || !seen.add(id) || !activated.contains(id) || g.revoked() || revokedNow.contains(id)
                    || clock.millis() < g.issuedAt()
                    || clock.millis() >= g.expiresAt() || clock.millis() >= g.budget().deadlineEpochMillis()
                    || !references.available(g.issuer()) || !references.available(g.grantee())) return List.of();
            if (!result.isEmpty()) {
                Grant child = result.getLast();
                if (!child.issuer().equals(g.grantee()) || !child.rights().subsetOf(g.rights())
                        || child.expiresAt() > g.expiresAt() || !within(child.budget(), g.budget())) return List.of();
            }
            result.add(g); id = g.parent();
        }
        return List.copyOf(result);
    }
    private boolean revoker(Grant g, TrustedContext caller) {
        var seen = new HashSet<UUID>();
        for (int depth = 0; g != null && depth < limits.depth() && seen.add(g.id()); depth++) {
            if (g.issuer().equals(caller) || g.grantee().equals(caller)) return true;
            g = grants.get(g.parent());
        }
        return false;
    }
    private Grant edit(Grant g, Map<Budgets.Kind, Long> reserved, boolean revoked, EventKind kind, UUID subject, TrustedContext actor) {
        long revision = Math.addExact(g.revision(), 1); var events = new ArrayList<>(g.events());
        if (events.size() == limits.history()) events.removeFirst(); events.add(new Event(revision, kind, subject, actor));
        return new Grant(g.id(), revision, g.issuer(), g.grantee(), g.parent(), g.organization(), g.rights(),
                g.issuedAt(), g.expiresAt(), g.budget(), reserved, g.revoked() || revoked, events);
    }
    private Change publish(List<Grant> rows, List<Binding> uses, UUID id, long revision) {
        try {
            Snapshot next = new Snapshot(state.worldId(), Math.addExact(state.revision(), 1), rows, uses);
            validate(next, limits); deadline = Math.addExact(clock.millis(), limits.publicationMillis());
            if (!rootPublisher.test(roots(next), true)) { fenced = true; return unavailable(); }
            pending = next;
            storage.replace(state, next).whenComplete((value, failure) -> writes.add(new Written(value, failure, clock.millis())));
            return new Change(Status.PENDING, null, id, revision);
        } catch (RuntimeException failure) { fenced = true; return unavailable(); }
    }
    private boolean sameWorld(TrustedContext caller) { return caller != null && caller.scope().worldId().equals(state.worldId()); }
    private void index() {
        var gs = new HashMap<UUID, Grant>(); state.grants().forEach(g -> gs.put(g.id(), g)); grants = Map.copyOf(gs);
        var bs = new HashMap<UUID, Binding>(); state.bindings().forEach(b -> bs.put(b.run(), b)); bindings = Map.copyOf(bs);
    }
    static boolean within(Budgets.Limits child, Budgets.Limits parent) {
        return child.deadlineEpochMillis() <= parent.deadlineEpochMillis()
                && Arrays.stream(Budgets.Kind.values()).allMatch(k -> child.maximum(k) <= parent.maximum(k));
    }
    static void validate(Snapshot state, Limits limits) {
        if (state.grants().size() > limits.mandates() || state.bindings().size() > limits.bindings())
            throw new IllegalArgumentException("Mandate capacity");
        for (Grant g : state.grants()) if (!g.issuer().scope().worldId().equals(state.worldId())
                || !g.grantee().scope().worldId().equals(state.worldId()) || g.events().size() > limits.history())
            throw new IllegalArgumentException("Mandate scope/history");
        for (Binding b : state.bindings()) if (!b.request().context().scope().worldId().equals(state.worldId()))
            throw new IllegalArgumentException("Mandate binding world");
    }
    static Set<WorldRetentionManager.Key> roots(Snapshot state) {
        var keys = new HashSet<WorldRetentionManager.Key>();
        for (Grant g : state.grants()) { keys.add(new WorldRetentionManager.Key("mandates", g.id().toString()));
            keys.add(new WorldRetentionManager.Key("citizens", "records")); }
        for (Binding b : state.bindings()) { keys.add(new WorldRetentionManager.Key("jobs", "records"));
            keys.add(new WorldRetentionManager.Key("mandates", "run-" + b.run()));
            keys.add(WorldRetentionManager.artifact(b.artifact())); }
        return keys;
    }
    private Change reject(Status status, Reason reason) { return new Change(status, reason, null, 0); }
    private Change unavailable() { return reject(Status.UNAVAILABLE, readOnly || fenced ? Reason.STORAGE_UNAVAILABLE : Reason.BUDGET_EXHAUSTED); }
    private void thread() { if (Thread.currentThread() != gameThread) throw new IllegalStateException("Mandate game thread required"); }
}
