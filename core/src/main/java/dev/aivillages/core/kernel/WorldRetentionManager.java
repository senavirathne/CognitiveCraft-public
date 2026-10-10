package dev.aivillages.core.kernel;

import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static dev.aivillages.core.kernel.Contracts.*;

/** Policy 1. Background-only, bounded snapshot traversal; record owners perform all mutations. */
public final class WorldRetentionManager {
    public static final int POLICY = 1;
    public static final long MAX_INVENTORY_BYTES = 16_777_216;
    public enum Category { ARTIFACTS, EVIDENCE, RUNS, JOBS, IDENTITIES, LEASES, DIAGNOSTICS, CACHE }
    public record Key(String owner, String id) {
        public Key {
            if (owner == null || !owner.matches("[a-z][a-z0-9_-]{0,63}")
                    || id == null || id.isBlank() || id.length() > 192) throw new IllegalArgumentException("Retention key");
        }
    }
    public static Key artifact(ArtifactRef ref) { return new Key("skills", ref.sha256()); }
    public record Amount(long bytes, long records) {
        public Amount { if (bytes < 0 || records < 0) throw new IllegalArgumentException("Usage"); }
        Amount plus(Amount other) { return new Amount(Math.addExact(bytes, other.bytes), Math.addExact(records, other.records)); }
    }
    public record Quota(long bytes, long records) {
        public Quota { if (bytes < 0 || records < 0) throw new IllegalArgumentException("Quota"); }
        boolean allows(Amount value) { return value.bytes <= bytes && value.records <= records; }
    }
    public record Policy(Map<Category, Quota> categories, Quota total, int owners, int nodes,
                         int links, int sliceRecords, int sliceBytes, int diagnostics) {
        public Policy {
            categories = Map.copyOf(categories); Objects.requireNonNull(total);
            if (categories.size() != Category.values().length || owners < 1 || owners > 16
                    || nodes < 1 || nodes > 8192 || links < 1 || links > 65536
                    || sliceRecords < 1 || sliceRecords > 64 || sliceBytes < 65536 || sliceBytes > 1_048_576
                    || diagnostics < 0 || diagnostics > 32) throw new IllegalArgumentException("Finite retention policy");
        }
        public static Policy defaults() {
            var categories = new EnumMap<Category, Quota>(Category.class);
            for (Category category : Category.values()) categories.put(category, new Quota(16_777_216, 4096));
            return new Policy(categories, new Quota(67_108_864, 16_384), 16, 8192, 65536, 16, 131072, 16);
        }
    }
    /** Bytes include physical backup/stage files. Zero-count envelopes avoid double-counting records. */
    public record Entry(Key key, Category category, Amount usage, List<Key> dependencies,
                        boolean protectedRecord, boolean eligible, Set<TrustedContext> viewers,
                        String identity) {
        public Entry {
            Objects.requireNonNull(key); Objects.requireNonNull(category); Objects.requireNonNull(usage);
            dependencies = List.copyOf(dependencies); viewers = Set.copyOf(viewers);
            if (dependencies.size() > 32 || viewers.size() > 32 || identity == null || identity.length() > 256)
                throw new IllegalArgumentException("Entry bounds");
        }
    }
    public record Page(List<Entry> entries, boolean complete, boolean valid, int inspected, int readBytes) {
        public Page {
            entries = List.copyOf(entries);
            if (inspected < entries.size() || inspected < 0 || readBytes < 0) throw new IllegalArgumentException("Page work");
        }
    }
    public interface Cursor extends AutoCloseable {
        long revision();
        Page next(int records, int bytes) throws IOException;
        @Override default void close() throws IOException { }
    }
    public enum OwnerStatus { COLLECTED, PROTECTED, STALE, CANCELLED, UNAVAILABLE }
    public record OwnerResult(OwnerStatus status, boolean publicationMayHaveSucceeded) {
        public OwnerResult { Objects.requireNonNull(status); }
        public static OwnerResult of(OwnerStatus status) { return new OwnerResult(status, false); }
    }
    /** Cursor creation is O(1); all snapshot I/O and inspection are metered by next(). */
    public interface Owner {
        String id();
        long revision() throws IOException;
        Cursor open() throws IOException;
        default void bindPolicy(WorldRetentionManager manager) { }
        /** Concurrent independent writers reserve their hard ceiling in admission assessments. */
        default Optional<Amount> admissionCeiling() { return Optional.empty(); }
        OwnerResult collect(Entry entry, long expectedRevision, BooleanSupplier cancel);
    }
    public enum State { INVENTORY, ROOTS, REACHABILITY, ELIGIBILITY, READY, INVALID, CANCELLED }
    public enum Diagnostic { OWNER_UNAVAILABLE, SNAPSHOT_CHANGED, WORK_LIMIT, DUPLICATE_KEY,
                             MISSING_DEPENDENCY, CYCLE, INVALID_PAGE }
    public record Usage(Map<Category, Amount> categories, Map<String, Amount> owners, Amount total, boolean complete) {
        public Usage { categories = Map.copyOf(categories); owners = Map.copyOf(owners); }
    }
    public record Work(State state, int inspected, int readBytes, int pending, int candidates,
                       List<Diagnostic> diagnostics, long omittedDiagnostics) {
        public Work { diagnostics = List.copyOf(diagnostics); }
    }
    public enum Capacity { AVAILABLE, STORAGE_LIMIT_REACHED, STORAGE_UNAVAILABLE }
    public record CapacityResult(Capacity status, Category category, Amount projected, Quota limit, boolean totalQuota) { }
    public record CollectionReport(int attempted, int collected, int stale, int protectedRecords,
                                   int cancelled, int unavailable, boolean publicationMayHaveSucceeded) { }
    private final Policy policy;
    private final RetentionRoots roots;
    private final Map<String, Owner> owners = new LinkedHashMap<>();
    private final Predicate<TrustedContext> aggregateReaders;
    private long ownerEpoch;
    public WorldRetentionManager(Policy policy, RetentionRoots roots, Predicate<TrustedContext> aggregateReaders) {
        this.policy = Objects.requireNonNull(policy); this.roots = Objects.requireNonNull(roots);
        this.aggregateReaders = Objects.requireNonNull(aggregateReaders);
    }
    public void register(Owner owner) {
        Objects.requireNonNull(owner);
        if (owners.size() >= policy.owners || owners.containsKey(owner.id())) throw new IllegalArgumentException("Owner registration");
        owners.put(owner.id(), owner);
        ownerEpoch = Math.addExact(ownerEpoch, 1);
        owner.bindPolicy(this);
    }
    public Scan begin() { return new Scan(); }
    RetentionRoots references() { return roots; }
    /** Approval for an owner's finite window replacement, with the same graph and reference fence. */
    public final class DiscardLease implements AutoCloseable {
        private final Scan scan; private final Object token; private boolean closed;
        private DiscardLease(Scan scan, Object token) { this.scan = scan; this.token = token; }
        public boolean valid() { try { return !closed && scan.current(); } catch (IOException failure) { return false; } }
        @Override public void close() { if (!closed) { closed = true; roots.endDelete(token); scan.close(); } }
    }
    public record DiscardApproval(Capacity status, DiscardLease lease) { }
    public DiscardApproval approveDiscard(Set<Key> removed) {
        if (removed.isEmpty() || removed.size() > 192) throw new IllegalArgumentException("Discard batch");
        Scan scan = completedScan();
        if (scan.state != State.READY || removed.stream().anyMatch(key -> !scan.entries.containsKey(key))) {
            scan.close(); return new DiscardApproval(Capacity.STORAGE_UNAVAILABLE, null);
        }
        if (removed.stream().anyMatch(scan.reachable::contains) || scan.entries.values().stream().anyMatch(entry ->
                !removed.contains(entry.key) && entry.dependencies.stream().anyMatch(removed::contains))) {
            scan.close(); return new DiscardApproval(Capacity.STORAGE_LIMIT_REACHED, null);
        }
        Object token = roots.beginDelete(scan.references.generation());
        if (token == null) { scan.close(); return new DiscardApproval(Capacity.STORAGE_UNAVAILABLE, null); }
        var lease = new DiscardLease(scan, token);
        if (!lease.valid()) { lease.close(); return new DiscardApproval(Capacity.STORAGE_UNAVAILABLE, null); }
        return new DiscardApproval(Capacity.AVAILABLE, lease);
    }
    public final class Scan implements AutoCloseable {
        private final RetentionRoots.Snapshot references = roots.snapshot();
        private final List<Owner> sources = List.copyOf(owners.values());
        private final long capturedOwnerEpoch = ownerEpoch;
        private final Map<Key, Entry> entries = new LinkedHashMap<>();
        private final Map<String, Long> revisions = new LinkedHashMap<>();
        private final EnumMap<Category, Amount> categories = new EnumMap<>(Category.class);
        private final Map<String, Amount> ownerUsage = new HashMap<>();
        private final Map<String, Category> ownerCategories = new HashMap<>();
        private final List<Diagnostic> diagnostics = new ArrayList<>();
        private final List<Entry> candidates = new ArrayList<>();
        private final Set<Key> reachable = new HashSet<>();
        private final Map<Key, Integer> colors = new HashMap<>();
        private final Map<Key, Integer> incoming = new HashMap<>();
        private final ArrayDeque<Visit> stack = new ArrayDeque<>();
        private Iterator<Key> starts;
        private Iterator<Key> referenceSeeds;
        private Iterator<Entry> ownerSeeds;
        private Iterator<Entry> eligible;
        private State state = State.INVENTORY;
        private Cursor cursor;
        private int ownerAt, links, applyAt;
        private long inventoryWork, inventoryBytes;
        private long omitted;
        private Amount total = new Amount(0, 0);
        private record Visit(Key key, int nextLink, boolean protect) { }
        private final Set<Key> protectedStarts = new LinkedHashSet<>();

        public State state() { return state; }
        public Usage usage() { return new Usage(categories, ownerUsage, total, state == State.READY); }
        public Amount privateUsage(TrustedContext caller) {
            Objects.requireNonNull(caller); var result = new Amount(0, 0);
            for (Entry entry : entries.values()) if (entry.viewers.contains(caller)) result = result.plus(entry.usage);
            return result;
        }
        public Usage aggregateUsage(TrustedContext caller) {
            if (!aggregateReaders.test(caller)) throw new SecurityException("Private world usage");
            return usage();
        }
        /** One finite slice. Graph processing meters both nodes and edges, including cycle exits. */
        public Work step(BooleanSupplier cancel) {
            Objects.requireNonNull(cancel); int work = 0, bytes = 0;
            if (cancel.getAsBoolean()) { state = State.CANCELLED; closeQuietly(); }
            try {
                if (state == State.INVENTORY) {
                    if (!references.complete()) invalid(Diagnostic.OWNER_UNAVAILABLE);
                    else if (ownerAt == sources.size()) {
                        referenceSeeds = references.sources().values().stream().flatMap(source -> source.roots().stream()).iterator();
                        ownerSeeds = entries.values().iterator(); state = State.ROOTS;
                    } else {
                        Owner owner = sources.get(ownerAt);
                        if (cursor == null) { cursor = owner.open(); revisions.put(owner.id(), cursor.revision()); }
                        Page page = cursor.next(policy.sliceRecords, policy.sliceBytes);
                        work = page.inspected; bytes = page.readBytes;
                        inventoryWork = Math.addExact(inventoryWork, work); inventoryBytes = Math.addExact(inventoryBytes, bytes);
                        if (!page.valid || work > policy.sliceRecords || bytes > policy.sliceBytes
                                || !page.complete && work == 0 && bytes == 0) invalid(Diagnostic.INVALID_PAGE);
                        else if (inventoryWork > policy.nodes + policy.owners * 8L || inventoryBytes > MAX_INVENTORY_BYTES) invalid(Diagnostic.WORK_LIMIT);
                        else for (Entry entry : page.entries) {
                            if (!entry.key.owner.equals(owner.id())) { invalid(Diagnostic.INVALID_PAGE); break; }
                            Category priorCategory = ownerCategories.putIfAbsent(owner.id(), entry.category);
                            if (priorCategory != null && priorCategory != entry.category) { invalid(Diagnostic.INVALID_PAGE); break; }
                            if (entries.containsKey(entry.key)) { invalid(Diagnostic.DUPLICATE_KEY); break; }
                            if (entries.size() >= policy.nodes) { invalid(Diagnostic.WORK_LIMIT); break; }
                            entries.put(entry.key, entry);
                            links = Math.addExact(links, entry.dependencies.size());
                            if (entries.size() > policy.nodes || links > policy.links) { invalid(Diagnostic.WORK_LIMIT); break; }
                            total = total.plus(entry.usage);
                            categories.merge(entry.category, entry.usage, Amount::plus);
                            ownerUsage.merge(owner.id(), entry.usage, Amount::plus);
                        }
                        if (page.complete || state == State.INVALID) { if (cursor != null) cursor.close(); cursor = null; ownerAt++; }
                    }
                } else if (state == State.ROOTS) {
                    while (work < policy.sliceRecords) {
                        if (referenceSeeds.hasNext()) { work++; protectedStarts.add(referenceSeeds.next()); }
                        else if (ownerSeeds.hasNext()) { work++; Entry entry = ownerSeeds.next(); if (entry.protectedRecord) protectedStarts.add(entry.key); }
                        else {
                            starts = java.util.stream.Stream.concat(protectedStarts.stream(), entries.keySet().stream()).iterator();
                            state = State.REACHABILITY; break;
                        }
                    }
                } else if (state == State.REACHABILITY) {
                    while (work < policy.sliceRecords && state == State.REACHABILITY) {
                        work++;
                        if (stack.isEmpty()) {
                            if (!starts.hasNext()) { eligible = entries.values().iterator(); state = State.ELIGIBILITY; break; }
                            Key root = starts.next();
                            // An external candidate can be pinned before it has a durable body.
                            if (!entries.containsKey(root) || colors.getOrDefault(root, 0) == 2) continue;
                            boolean protect = protectedStarts.contains(root);
                            colors.put(root, 1); if (protect) reachable.add(root); stack.push(new Visit(root, 0, protect));
                        } else {
                            Visit visit = stack.pop(); Entry entry = entries.get(visit.key);
                            if (visit.nextLink == entry.dependencies.size()) { colors.put(visit.key, 2); continue; }
                            stack.push(new Visit(visit.key, visit.nextLink + 1, visit.protect));
                            Key dependency = entry.dependencies.get(visit.nextLink);
                            incoming.merge(dependency, 1, Math::addExact);
                            if (!entries.containsKey(dependency)) { invalid(Diagnostic.MISSING_DEPENDENCY); break; }
                            int color = colors.getOrDefault(dependency, 0);
                            if (color == 1) { invalid(Diagnostic.CYCLE); break; }
                            if (visit.protect) reachable.add(dependency);
                            if (color == 0) { colors.put(dependency, 1); stack.push(new Visit(dependency, 0, visit.protect)); }
                        }
                    }
                } else if (state == State.ELIGIBILITY) {
                    while (work < policy.sliceRecords && eligible.hasNext()) {
                        work++; Entry entry = eligible.next();
                        if (entry.eligible && !reachable.contains(entry.key) && incoming.getOrDefault(entry.key, 0) == 0) candidates.add(entry);
                    }
                    if (!eligible.hasNext()) state = current() ? State.READY : State.INVALID;
                    if (state == State.INVALID) diagnose(Diagnostic.SNAPSHOT_CHANGED);
                }
            } catch (IOException | RuntimeException failure) { invalid(Diagnostic.OWNER_UNAVAILABLE); }
            return new Work(state, work, bytes, Math.max(0, sources.size() - ownerAt), candidates.size(), diagnostics, omitted);
        }
        private boolean current() throws IOException {
            if (ownerEpoch != capturedOwnerEpoch || roots.snapshot().generation() != references.generation()) return false;
            for (Owner owner : sources) if (owner.revision() != revisions.getOrDefault(owner.id(), -1L)) return false;
            return true;
        }
        /** Replacement accounting is used by a writer with its staging/backup headroom included. */
        public CapacityResult assessReplacement(String owner, Category category, Amount replacement) {
            try {
                if (state != State.READY || !current() || !owners.containsKey(owner))
                    return new CapacityResult(Capacity.STORAGE_UNAVAILABLE, category, replacement, policy.categories.get(category), false);
                Amount old = ownerUsage.getOrDefault(owner, new Amount(0, 0));
                Amount cat = categories.getOrDefault(category, new Amount(0, 0));
                var projected = new Amount(Math.addExact(cat.bytes - old.bytes, replacement.bytes),
                        Math.addExact(cat.records - old.records, replacement.records));
                var all = new Amount(Math.addExact(total.bytes - old.bytes, replacement.bytes),
                        Math.addExact(total.records - old.records, replacement.records));
                for (Owner source : sources) if (!source.id().equals(owner) && source.admissionCeiling().isPresent()) {
                    Amount ceiling = source.admissionCeiling().orElseThrow(); Amount measured = ownerUsage.getOrDefault(source.id(), new Amount(0, 0));
                    var growth = new Amount(Math.max(0, ceiling.bytes - measured.bytes), Math.max(0, ceiling.records - measured.records));
                    all = all.plus(growth);
                    Category reservedCategory = ownerCategories.get(source.id());
                    if (reservedCategory == null) return new CapacityResult(Capacity.STORAGE_UNAVAILABLE, category, replacement, policy.categories.get(category), false);
                    Amount reserved = categories.get(reservedCategory).plus(growth);
                    Quota reservedQuota = policy.categories.get(reservedCategory);
                    if (!reservedQuota.allows(reserved)) return new CapacityResult(Capacity.STORAGE_LIMIT_REACHED, reservedCategory, reserved, reservedQuota, false);
                }
                Quota quota = policy.categories.get(category);
                if (!quota.allows(projected)) return new CapacityResult(Capacity.STORAGE_LIMIT_REACHED, category, projected, quota, false);
                if (!policy.total.allows(all)) return new CapacityResult(Capacity.STORAGE_LIMIT_REACHED, category, all, policy.total, true);
                return new CapacityResult(Capacity.AVAILABLE, category, projected, quota, false);
            } catch (IOException | ArithmeticException bad) {
                return new CapacityResult(Capacity.STORAGE_UNAVAILABLE, category, replacement, policy.categories.get(category), false);
            }
        }
        public CollectionReport collect(BooleanSupplier cancel) {
            int attempts = 0, collected = 0, stale = 0, protectedCount = 0, cancelled = 0, unavailable = 0;
            boolean uncertain = false;
            while (state == State.READY && applyAt < candidates.size() && attempts < policy.sliceRecords) {
                if (cancel.getAsBoolean()) { cancelled++; state = State.CANCELLED; break; }
                attempts++;
                Object lease = roots.beginDelete(references.generation());
                if (lease == null) { stale++; state = State.INVALID; break; }
                try {
                    // All owner generations, not just the target, can affect dependency reachability.
                    if (!current()) { stale++; state = State.INVALID; break; }
                    Entry entry = candidates.get(applyAt++);
                    Owner owner = owners.get(entry.key.owner);
                    OwnerResult result = owner.collect(entry, revisions.get(owner.id()), cancel);
                    uncertain |= result.publicationMayHaveSucceeded;
                    switch (result.status) {
                        case COLLECTED -> collected++;
                        case PROTECTED -> protectedCount++;
                        case STALE -> stale++;
                        case CANCELLED -> cancelled++;
                        case UNAVAILABLE -> unavailable++;
                    }
                    // A mutation requires a new coherent scan, even when more candidates remain.
                    if (result.status != OwnerStatus.PROTECTED) { state = State.INVALID; break; }
                } catch (IOException | RuntimeException failure) { unavailable++; state = State.INVALID; break; }
                finally { roots.endDelete(lease); }
            }
            return new CollectionReport(attempts, collected, stale, protectedCount, cancelled, unavailable, uncertain);
        }
        private void diagnose(Diagnostic value) { if (diagnostics.size() < policy.diagnostics) diagnostics.add(value); else if (omitted < Long.MAX_VALUE) omitted++; }
        private void invalid(Diagnostic value) { diagnose(value); state = State.INVALID; closeQuietly(); }
        private void closeQuietly() { if (cursor != null) try { cursor.close(); } catch (IOException ignored) { } finally { cursor = null; } }
        @Override public void close() { closeQuietly(); }
    }
    /** Finite synchronous background assessment; no model, deletion, or game-thread work. */
    public CapacityResult assessReplacement(String owner, Category category, Amount replacement) {
        try (Scan scan = completedScan()) { return scan.assessReplacement(owner, category, replacement); }
    }
    private Scan completedScan() {
        Scan scan = begin();
        try {
            // Every legal snapshot completes within this node/edge/owner-derived bound.
            int maximum = policy.nodes * 6 + policy.links * 2 + policy.owners * 4 + RetentionRoots.MAX_ROOTS * 2;
            for (int step = 0; step < maximum && scan.state != State.READY && scan.state != State.INVALID; step++) scan.step(() -> false);
            return scan;
        } catch (RuntimeException failure) { scan.close(); throw failure; }
    }
}
