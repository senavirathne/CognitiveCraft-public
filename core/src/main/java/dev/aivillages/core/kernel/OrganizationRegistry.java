package dev.aivillages.core.kernel;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiPredicate;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Organizations.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;

/** Serialized game-thread metadata owner; disk publication is delegated to the existing storage worker. */
public final class OrganizationRegistry {
    @FunctionalInterface public interface Storage { CompletionStage<Snapshot> replace(Snapshot expected, Snapshot next); }
    public record MemberFact(TrustedContext owner, Participation participation) {
        public MemberFact { Objects.requireNonNull(owner); Objects.requireNonNull(participation); }
    }
    /** Trusted, bounded, I/O-free observations. Missing/unloaded citizens are never inferred dead. */
    public interface References {
        MemberFact citizen(ActorRef actor);
        boolean headquarters(ContainerRef ref, TrustedContext caller);
    }
    private record Written(Snapshot value, Throwable error, long completedAt) { }
    private final Thread gameThread = Thread.currentThread();
    private final Storage storage;
    private final References references;
    private final JobLifecycleStore jobs;
    private final Clock clock;
    private final Limits limits;
    private final boolean readOnly;
    private final ConcurrentLinkedQueue<Written> written = new ConcurrentLinkedQueue<>();
    private Snapshot state, pending;
    private long deadline;
    private boolean fenced;
    private int expiryCursor;
    private BiPredicate<Set<WorldRetentionManager.Key>, Boolean> rootPublisher = (roots, complete) -> true;

    public OrganizationRegistry(Snapshot initial, Storage storage, References references,
                                JobLifecycleStore jobs, Clock clock, Limits limits, boolean readOnly) {
        this.state = Objects.requireNonNull(initial); this.storage = Objects.requireNonNull(storage);
        this.references = Objects.requireNonNull(references); this.jobs = Objects.requireNonNull(jobs);
        this.clock = Objects.requireNonNull(clock); this.limits = Objects.requireNonNull(limits); this.readOnly = readOnly;
        if (!initial.worldId().equals(jobs.snapshot().worldId())) throw new IllegalArgumentException("Organization job world");
        validate(initial, limits);
    }
    public boolean ready() { thread(); return !readOnly && !fenced && pending == null; }
    /** Host composition seam, never an unscoped player query. */
    public Snapshot snapshot() { thread(); return state; }
    public void retentionGuard(BiPredicate<Set<WorldRetentionManager.Key>, Boolean> publisher) {
        thread(); rootPublisher = Objects.requireNonNull(publisher);
        if (!rootPublisher.test(protectedRoots(), !readOnly && !fenced)) fenced = true;
    }
    public Set<WorldRetentionManager.Key> protectedRoots() {
        thread(); var result = roots(state); if (pending != null) result.addAll(roots(pending)); return Set.copyOf(result);
    }
    public boolean rootsComplete() { thread(); return !readOnly && !fenced; }
    public void close() { thread(); fenced = true; rootPublisher.test(protectedRoots(), false); }

    public Change create(UUID id, String name, Kind kind, long ttlMillis, PolicyRef policy,
                         ContainerRef headquarters, TrustedContext caller) {
        thread();
        if (!sameWorld(caller)) return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
        if (!ready()) return unavailable();
        if (id == null || kind == null || policy == null || ttlMillis < 0 || ttlMillis > MAX_TTL_MILLIS
                || kind == Kind.ORGANIZATION && ttlMillis != 0
                || kind == Kind.TEMPORARY_PARTY && ttlMillis == 0) return reject(Status.INVALID, Reason.REQUEST_INVALID);
        if (find(id) != null) return reject(Status.CONFLICT, Reason.STALE_OBSERVATION);
        if (state.scopes().size() >= limits.scopes() || kind == Kind.TEMPORARY_PARTY
                && state.scopes().stream().filter(s -> s.kind() == Kind.TEMPORARY_PARTY).count() >= limits.temporary())
            return reject(Status.LIMIT_REACHED, Reason.STORAGE_LIMIT_REACHED);
        if (headquarters != null && !references.headquarters(headquarters, caller))
            return reject(Status.MISSING_REFERENCE, Reason.TARGET_UNAVAILABLE);
        try {
            long now = clock.millis();
            var scope = new Scope(id, 1, caller, name, kind, now,
                    kind == Kind.TEMPORARY_PARTY ? Math.addExact(now, ttlMillis) : 0, false,
                    policy, headquarters, List.of(), List.of(), limits.history() == 0 ? List.of()
                    : List.of(new Event(1, EventKind.CREATED, id)));
            return publish(scope, caller);
        } catch (IllegalArgumentException | NullPointerException invalid) { return reject(Status.INVALID, Reason.REQUEST_INVALID); }
        catch (ArithmeticException overflow) { return reject(Status.LIMIT_REACHED, Reason.BUDGET_EXHAUSTED); }
    }

    public Change update(UUID id, long revision, String name, PolicyRef policy,
                         ContainerRef headquarters, TrustedContext caller) {
        thread(); Scope scope = find(id);
        Change guard = guard(scope, revision, caller, true); if (guard != null) return guard;
        if (headquarters != null && !references.headquarters(headquarters, caller))
            return reject(Status.MISSING_REFERENCE, Reason.TARGET_UNAVAILABLE);
        try {
            if (scope.name().equals(name) && scope.policy().equals(policy) && Objects.equals(scope.headquarters(), headquarters))
                return unchanged(scope, caller);
            return publish(edit(scope, name, policy, headquarters, scope.members(), scope.jobs(), false,
                    EventKind.UPDATED, id), caller);
        } catch (IllegalArgumentException | NullPointerException invalid) { return reject(Status.INVALID, Reason.REQUEST_INVALID); }
        catch (ArithmeticException overflow) { return reject(Status.LIMIT_REACHED, Reason.BUDGET_EXHAUSTED); }
    }

    /** Invitations are not consent: only the currently recorded citizen owner may join that citizen. */
    public Change join(UUID id, long revision, ActorRef actor, String role, TrustedContext caller) {
        thread(); Scope scope = find(id);
        if (scope == null || !sameWorld(caller) || !scope.policy().invites(scope.owner(), caller))
            return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
        Change guard = guard(scope, revision, caller, false); if (guard != null) return guard;
        if (actor == null) return reject(Status.INVALID, Reason.REQUEST_INVALID);
        MemberFact fact = references.citizen(actor);
        if (fact == null) return reject(Status.MISSING_REFERENCE, Reason.ACTOR_UNAVAILABLE);
        if (!fact.owner().equals(caller)) return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
        if (fact.participation() == Participation.MISSING || fact.participation() == Participation.DEPARTED
                || fact.participation() == Participation.UNAVAILABLE || fact.participation() == Participation.EXPIRED)
            return reject(Status.MISSING_REFERENCE, Reason.ACTOR_UNAVAILABLE);
        Member old = member(scope, actor.citizenId());
        try {
            var next = new Member(actor, caller, role);
            if (old != null) return old.equals(next) ? unchanged(scope, caller) : reject(Status.CONFLICT, Reason.STALE_OBSERVATION);
            if (scope.members().size() >= limits.members()) return reject(Status.LIMIT_REACHED, Reason.STORAGE_LIMIT_REACHED);
            var members = new ArrayList<>(scope.members()); members.add(next);
            return publish(edit(scope, scope.name(), scope.policy(), scope.headquarters(), members, scope.jobs(), false,
                    EventKind.JOINED, actor.citizenId()), caller);
        } catch (IllegalArgumentException | NullPointerException invalid) { return reject(Status.INVALID, Reason.REQUEST_INVALID); }
        catch (ArithmeticException overflow) { return reject(Status.LIMIT_REACHED, Reason.BUDGET_EXHAUSTED); }
    }

    public Change leave(UUID id, long revision, UUID citizenId, TrustedContext caller) {
        thread(); Scope scope = find(id); Member member = scope == null ? null : member(scope, citizenId);
        if (member == null || !sameWorld(caller) || !scope.owner().equals(caller) && !member.owner().equals(caller))
            return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
        Change guard = guard(scope, revision, caller, false); if (guard != null) return guard;
        var members = new ArrayList<>(scope.members()); members.remove(member);
        return changed(scope, members, scope.jobs(), EventKind.LEFT, citizenId, caller);
    }
    public Change role(UUID id, long revision, UUID citizenId, String role, TrustedContext caller) {
        thread(); Scope scope = find(id); Member old = scope == null ? null : member(scope, citizenId);
        if (old == null || !sameWorld(caller) || !scope.owner().equals(caller) && !old.owner().equals(caller))
            return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
        Change guard = guard(scope, revision, caller, false); if (guard != null) return guard;
        if (participation(scope, old) != Participation.ACTIVE)
            return reject(Status.MISSING_REFERENCE, Reason.ACTOR_UNAVAILABLE);
        try {
            Member next = new Member(old.actor(), old.owner(), role);
            if (old.equals(next)) return unchanged(scope, caller);
            var members = new ArrayList<>(scope.members()); members.set(members.indexOf(old), next);
            return changed(scope, members, scope.jobs(), EventKind.ROLE, citizenId, caller);
        } catch (IllegalArgumentException | NullPointerException invalid) { return reject(Status.INVALID, Reason.REQUEST_INVALID); }
    }

    /** Links must be supplied by the original job owner, with explicit consent for each named reader. */
    public Change linkJob(UUID id, long revision, UUID jobId, Set<TrustedContext> readers, TrustedContext caller) {
        thread(); Scope scope = find(id);
        if (scope == null || !sameWorld(caller) || !scope.policy().invites(scope.owner(), caller))
            return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
        Change guard = guard(scope, revision, caller, false); if (guard != null) return guard;
        Jobs.Job job;
        try { job = jobs.query(jobId, caller); } catch (SecurityException denied) { return reject(Status.DENIED, Reason.AUTHORITY_DENIED); }
        if (!job.origin().equals(caller)) return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
        Member actor = member(scope, job.responsible().citizenId());
        if (!scope.owner().equals(caller) && (actor == null || !actor.owner().equals(caller)
                || participation(scope, actor) != Participation.ACTIVE)) return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
        try {
            var link = new JobLink(jobId, caller, readers);
            if (readers.size() > limits.grants() || readers.stream().anyMatch(c -> !scope.policy().views(scope.owner(), c)))
                return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
            var links = new ArrayList<>(scope.jobs());
            JobLink old = links.stream().filter(j -> j.id().equals(jobId)).findFirst().orElse(null);
            if (old != null && !old.origin().equals(caller)) return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
            if (link.equals(old)) return unchanged(scope, caller);
            if (old == null && links.size() >= limits.jobs()) return reject(Status.LIMIT_REACHED, Reason.STORAGE_LIMIT_REACHED);
            links.remove(old); links.add(link);
            return changed(scope, scope.members(), links, EventKind.JOB_LINKED, jobId, caller);
        } catch (IllegalArgumentException | NullPointerException invalid) { return reject(Status.INVALID, Reason.REQUEST_INVALID); }
    }
    /** Removal affects only this view. It never cancels, completes or deletes the original job. */
    public Change unlinkJob(UUID id, long revision, UUID jobId, TrustedContext caller) {
        thread(); Scope scope = find(id); JobLink link = scope == null ? null : scope.jobs().stream()
                .filter(j -> j.id().equals(jobId)).findFirst().orElse(null);
        if (link == null || !sameWorld(caller) || !scope.owner().equals(caller) && !link.origin().equals(caller))
            return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
        // A former contributor may revoke disclosure even after invitation withdrawal or party expiry.
        if (!ready()) return unavailable();
        if (revision != scope.revision()) return reject(Status.CONFLICT, Reason.STALE_OBSERVATION);
        var links = new ArrayList<>(scope.jobs()); links.remove(link);
        return changed(scope, scope.members(), links, EventKind.JOB_UNLINKED, jobId, caller);
    }

    public Inspection inspect(UUID id, TrustedContext caller) {
        thread(); Scope scope = find(id);
        if (scope == null || !sameWorld(caller) || !scope.owner().equals(caller))
            return new Inspection(Status.DENIED, null, List.of(), false);
        if (readOnly || fenced) return new Inspection(Status.UNAVAILABLE, null, List.of(), false);
        boolean expired = scope.expiredAt(clock.millis());
        return new Inspection(expired ? Status.EXPIRED : Status.APPLIED, scope,
                scope.members().stream().map(m -> new MemberView(m.actor(), m.role(), participation(scope, m))).toList(),
                scope.headquarters() != null && references.headquarters(scope.headquarters(), caller));
    }
    public JobPage scopedJobs(UUID id, TrustedContext caller, int offset, int maximum) {
        thread(); Scope scope = find(id);
        if (scope == null || !sameWorld(caller) || !scope.policy().views(scope.owner(), caller)
                || scope.expiredAt(clock.millis()) && !scope.owner().equals(caller)) return page(Status.DENIED);
        if (readOnly || fenced || pending != null) return page(Status.UNAVAILABLE);
        // The at-most-16-ref filter is private. Public offsets/counts must not reveal undisclosed links.
        List<JobLink> visible = scope.jobs().stream().filter(j -> j.origin().equals(caller) || j.readers().contains(caller)).toList();
        if (offset < 0 || offset > visible.size() || maximum < 0 || maximum > limits.queryResults())
            return page(Status.INVALID);
        if (maximum == 0) return new JobPage(Status.LIMIT_REACHED, List.of(), offset, offset < visible.size(), 0, 0);
        var result = new ArrayList<Jobs.Job>(); int at = offset, inspected = 0, missing = 0;
        while (at < visible.size() && inspected < maximum) {
            JobLink link = visible.get(at++); inspected++;
            try {
                result.add(jobs.scopedQuery(link.id(), caller,
                        new JobLifecycleStore.NamedReadGrant(link.id(), link.origin(), link.readers())));
            } catch (SecurityException unavailable) { missing++; }
        }
        return new JobPage(scope.expiredAt(clock.millis()) ? Status.EXPIRED : Status.APPLIED,
                result, at, at < visible.size(), inspected, missing);
    }

    /** Trusted tick maintenance; at most four scope inspections and one publication per turn. */
    public Expiry expire() {
        thread(); if (!ready()) return new Expiry(Status.UNAVAILABLE, 0, 0);
        int count = Math.min(limits.expirySlice(), state.scopes().size());
        var rows = new ArrayList<>(state.scopes()); int changed = 0;
        try {
            for (int n = 0; n < count; n++) {
                int at = (expiryCursor + n) % rows.size(); Scope scope = rows.get(at);
                if (scope.kind() == Kind.TEMPORARY_PARTY && !scope.expired() && scope.expiredAt(clock.millis())) {
                    rows.set(at, edit(scope, scope.name(), scope.policy(), scope.headquarters(), List.of(), scope.jobs(),
                            true, EventKind.EXPIRED, scope.id())); changed++;
                }
            }
            expiryCursor = rows.isEmpty() ? 0 : (expiryCursor + count) % rows.size();
            if (changed == 0) return new Expiry(Status.UNCHANGED, count, 0);
            Change publication = publishRows(rows, null, null);
            return new Expiry(publication.status(), count, publication.accepted() ? changed : 0);
        } catch (ArithmeticException overflow) { return new Expiry(Status.LIMIT_REACHED, count, 0); }
    }
    public void tick() {
        thread(); Written ack = written.poll();
        if (ack != null && !fenced && pending != null) {
            if (ack.error() != null || !pending.equals(ack.value()) || ack.completedAt() > deadline) fenced = true;
            else { state = pending; pending = null; }
        }
        if (pending != null && clock.millis() > deadline) fenced = true;
        rootPublisher.test(protectedRoots(), rootsComplete());
    }

    private Participation participation(Scope scope, Member member) {
        if (scope.expiredAt(clock.millis())) return Participation.EXPIRED;
        if (!scope.policy().invites(scope.owner(), member.owner())) return Participation.DEPARTED;
        MemberFact fact = references.citizen(member.actor());
        return fact == null ? Participation.MISSING : !member.owner().equals(fact.owner())
                ? Participation.DEPARTED : fact.participation();
    }
    private Change guard(Scope scope, long revision, TrustedContext caller, boolean ownerOnly) {
        if (scope == null || !sameWorld(caller) || ownerOnly && !scope.owner().equals(caller))
            return reject(Status.DENIED, Reason.AUTHORITY_DENIED);
        if (!ready()) return unavailable();
        if (scope.expiredAt(clock.millis())) return reject(Status.EXPIRED, Reason.STALE_OBSERVATION);
        return revision == scope.revision() ? null : reject(Status.CONFLICT, Reason.STALE_OBSERVATION);
    }
    private Change changed(Scope scope, List<Member> members, List<JobLink> links, EventKind event,
                           UUID reference, TrustedContext caller) {
        try { return publish(edit(scope, scope.name(), scope.policy(), scope.headquarters(), members, links,
                scope.expired(), event, reference), caller); }
        catch (ArithmeticException overflow) { return reject(Status.LIMIT_REACHED, Reason.BUDGET_EXHAUSTED); }
    }
    private Scope edit(Scope scope, String name, PolicyRef policy, ContainerRef headquarters, List<Member> members,
                       List<JobLink> links, boolean expired, EventKind event, UUID reference) {
        long revision = Math.addExact(scope.revision(), 1); var history = new ArrayList<>(scope.history());
        if (limits.history() > 0) { while (history.size() >= limits.history()) history.removeFirst(); history.add(new Event(revision, event, reference)); }
        else history.clear();
        return new Scope(scope.id(), revision, scope.owner(), name, scope.kind(), scope.createdAt(), scope.expiresAt(),
                expired, policy, headquarters, members, links, history);
    }
    private Change publish(Scope scope, TrustedContext caller) {
        var rows = new ArrayList<>(state.scopes()); rows.removeIf(s -> s.id().equals(scope.id())); rows.add(scope);
        return publishRows(rows, scope, caller);
    }
    private Change publishRows(List<Scope> rows, Scope scope, TrustedContext caller) {
        if (limits.publicationMillis() == 0) return reject(Status.LIMIT_REACHED, Reason.BUDGET_EXHAUSTED);
        Snapshot next;
        try { next = new Snapshot(state.worldId(), Math.addExact(state.revision(), 1), rows);
            validate(next, limits); deadline = Math.addExact(clock.millis(), limits.publicationMillis()); }
        catch (ArithmeticException overflow) { return reject(Status.LIMIT_REACHED, Reason.BUDGET_EXHAUSTED); }
        catch (IllegalArgumentException quota) { return reject(Status.LIMIT_REACHED, Reason.STORAGE_LIMIT_REACHED); }
        var protectedKeys = roots(state); protectedKeys.addAll(roots(next));
        if (!rootPublisher.test(Set.copyOf(protectedKeys), true)) return reject(Status.UNAVAILABLE, Reason.STORAGE_UNAVAILABLE);
        pending = next;
        try { storage.replace(state, next).whenComplete((value, error) -> written.add(new Written(value, error, clock.millis()))); }
        catch (RuntimeException failed) { fenced = true; rootPublisher.test(protectedRoots(), false); return unavailable(); }
        return new Change(Status.PENDING, scope != null && scope.owner().equals(caller) ? scope : null, null);
    }
    static void validate(Snapshot snapshot, Limits limits) {
        if (snapshot.scopes().size() > limits.scopes()
                || snapshot.scopes().stream().filter(s -> s.kind() == Kind.TEMPORARY_PARTY).count() > limits.temporary())
            throw new IllegalArgumentException("Scope quota");
        for (Scope s : snapshot.scopes())
            if (s.members().size() > limits.members() || s.jobs().size() > limits.jobs() || s.history().size() > limits.history()
                    || s.policy().memberOwners().size() > limits.grants() || s.policy().viewers().size() > limits.grants()
                    || OrganizationJournal.policyBytes(s.policy()) > limits.policyBytes()
                    || s.jobs().stream().anyMatch(j -> j.readers().size() > limits.grants()))
                throw new IllegalArgumentException("Scope reference quota");
    }
    static Set<WorldRetentionManager.Key> roots(Snapshot snapshot) {
        var roots = new HashSet<WorldRetentionManager.Key>();
        if (!snapshot.scopes().isEmpty()) roots.add(new WorldRetentionManager.Key("organizations", "records"));
        for (Scope s : snapshot.scopes()) {
            if (!s.members().isEmpty()) roots.add(new WorldRetentionManager.Key("citizens", "records"));
            if (!s.jobs().isEmpty()) roots.add(new WorldRetentionManager.Key("jobs", "records")); }
        return roots;
    }
    public static WorldRetentionManager.Key key(UUID id) { return new WorldRetentionManager.Key("organizations", id.toString()); }
    private Scope find(UUID id) { return state.scopes().stream().filter(s -> s.id().equals(id)).findFirst().orElse(null); }
    private static Member member(Scope scope, UUID id) { return scope.members().stream().filter(m -> m.actor().citizenId().equals(id)).findFirst().orElse(null); }
    private boolean sameWorld(TrustedContext caller) { return caller != null && caller.scope().worldId().equals(state.worldId()); }
    private Change unchanged(Scope scope, TrustedContext caller) { return new Change(Status.UNCHANGED, scope.owner().equals(caller) ? scope : null, null); }
    private Change unavailable() { return reject(Status.UNAVAILABLE, Reason.STORAGE_UNAVAILABLE); }
    private static Change reject(Status status, Reason reason) { return new Change(status, null, reason); }
    private static JobPage page(Status status) { return new JobPage(status, List.of(), 0, false, 0, 0); }
    private void thread() { if (Thread.currentThread() != gameThread) throw new IllegalStateException("Organization game thread required"); }
}
