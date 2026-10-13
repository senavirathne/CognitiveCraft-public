package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Organizations.*;
import static org.junit.jupiter.api.Assertions.*;

class OrganizationRegistryTest {
    static final class Memory implements OrganizationRegistry.Storage {
        Snapshot state; Snapshot heldState; CompletableFuture<Snapshot> held;
        boolean hold, fail; int writes;
        Memory(UUID world) { state = Snapshot.empty(world); }
        public CompletionStage<Snapshot> replace(Snapshot expected, Snapshot next) {
            assertEquals(state, expected); writes++;
            if (fail) return CompletableFuture.failedFuture(new IllegalStateException("storage fixture"));
            if (hold) { heldState = next; held = new CompletableFuture<>(); return held; }
            state = next; return CompletableFuture.completedFuture(next);
        }
        void release() { state = heldState; held.complete(state); hold = false; }
    }
    static final class Scene {
        final JobLifecycleStoreTest.Scene j = new JobLifecycleStoreTest.Scene();
        final TrustedContext owner = j.owner, other = j.foreign;
        final ActorRef foreignActor = JobLifecycleStoreTest.actor();
        final Map<ActorRef, OrganizationRegistry.MemberFact> facts = new HashMap<>();
        final Memory memory = new Memory(j.world);
        final OrganizationRegistry registry;
        boolean headquartersPresent = true;
        Scene() { this(Limits.defaults()); }
        Scene(Limits limits) {
            facts.put(j.actor, new OrganizationRegistry.MemberFact(owner, Participation.ACTIVE));
            facts.put(foreignActor, new OrganizationRegistry.MemberFact(other, Participation.ACTIVE));
            registry = new OrganizationRegistry(memory.state, memory, new OrganizationRegistry.References() {
                public OrganizationRegistry.MemberFact citizen(ActorRef actor) { return facts.get(actor); }
                public boolean headquarters(ContainerRef ref, TrustedContext caller) { return headquartersPresent && caller.equals(owner); }
            }, j.store, j.clock, limits, false);
        }
        Scope create() { return create(Kind.ORGANIZATION, PolicyRef.privateScope()); }
        Scope create(Kind kind, PolicyRef policy) {
            UUID id = UUID.randomUUID();
            complete(registry.create(id, "Farmers", kind, kind == Kind.ORGANIZATION ? 0 : 1000, policy, null, owner));
            return current(id);
        }
        Scope current(UUID id) { return registry.inspect(id, owner).scope(); }
        Scope complete(Change change) {
            assertTrue(change.accepted(), change.toString()); registry.tick(); assertTrue(registry.ready());
            return change.scope() == null ? null : current(change.scope().id());
        }
        Scope join(Scope scope) { return complete(registry.join(scope.id(), current(scope.id()).revision(), j.actor, "worker", owner)); }
        Scope link(Scope scope, Jobs.Job job, Set<TrustedContext> readers) {
            return complete(registry.linkJob(scope.id(), current(scope.id()).revision(), job.id(), readers, owner));
        }
        PolicyRef sharing() { return new PolicyRef(PolicyId.NAMED_SHARING, 1, Set.of(other), Set.of(other)); }
    }

    @Test void dataDefinedNamesAndRenamePreserveIdentityAndOtherScope() {
        var s = new Scene(); Scope first = s.create(), second = s.create();
        Scope renamed = s.complete(s.registry.update(first.id(), first.revision(), "Explorers", first.policy(), null, s.owner));
        assertEquals(first.id(), renamed.id()); assertEquals(first.owner(), renamed.owner());
        assertEquals(first.getClass(), second.getClass()); assertEquals(second, s.current(second.id()));
        assertEquals(renamed, s.memory.state.scopes().stream().filter(x -> x.id().equals(first.id())).findFirst().orElseThrow());
    }
    @Test void membershipRolesAndDepartureNeverMutateCitizenOrJobAuthority() {
        var s = new Scene(); Scope scope = s.join(s.create()); var job = s.j.create(1); var before = s.j.store.snapshot();
        Scope role = s.complete(s.registry.role(scope.id(), scope.revision(), s.j.actor.citizenId(), "King", s.owner));
        assertEquals("King", role.members().getFirst().role());
        assertEquals(Status.DENIED, s.registry.role(scope.id(), role.revision(), s.j.actor.citizenId(), "Owner", s.other).status());
        s.complete(s.registry.leave(scope.id(), role.revision(), s.j.actor.citizenId(), s.owner));
        assertTrue(s.current(scope.id()).members().isEmpty()); assertEquals(before, s.j.store.snapshot());
        assertFalse(s.j.store.mayControl(job.id(), job.generation(), s.other));
        assertEquals(s.owner, s.facts.get(s.j.actor).owner());
    }
    @Test void invitationsRequireSeparateCurrentCitizenOwnerConsent() {
        var s = new Scene(); Scope scope = s.create(Kind.ORGANIZATION, s.sharing());
        assertEquals(Status.DENIED, s.registry.join(scope.id(), scope.revision(), s.foreignActor, "guest", s.owner).status());
        Change joined = s.registry.join(scope.id(), scope.revision(), s.foreignActor, "guest", s.other);
        assertTrue(joined.accepted()); assertNull(joined.scope(), "A contributor must not receive the private policy/member list");
        s.complete(joined); Scope current = s.current(scope.id());
        assertEquals(s.other, current.members().getFirst().owner());
        assertEquals(Status.DENIED, s.registry.update(scope.id(), current.revision(), "Foreign takeover", current.policy(), null, s.other).status());
        s.complete(s.registry.leave(scope.id(), current.revision(), s.foreignActor.citizenId(), s.other));
        assertTrue(s.current(scope.id()).members().isEmpty());
    }
    @Test void membershipAndDisplayTitleDoNotImplyJobVisibility() {
        var s = new Scene(); PolicyRef invitation = new PolicyRef(PolicyId.NAMED_SHARING, 1, Set.of(s.other), Set.of());
        Scope scope = s.create(Kind.ORGANIZATION, invitation); s.complete(s.registry.join(scope.id(), scope.revision(), s.foreignActor, "Leader", s.other));
        var job = s.j.create(1); s.link(scope, job, Set.of());
        assertEquals(Status.DENIED, s.registry.scopedJobs(scope.id(), s.other, 0, 8).status());
        assertEquals(Status.DENIED, s.registry.inspect(scope.id(), s.other).status());
        assertEquals(Outcomes.Reason.AUTHORITY_DENIED, s.j.store.cancel(job.id(), job.guard(), s.other).reason());
    }
    @Test void eachNamedJobRequiresOriginalOwnerDisclosureConsent() {
        var s = new Scene(); Scope scope = s.create(Kind.ORGANIZATION, s.sharing());
        Jobs.Job shared = s.j.create(1), privateJob = s.j.create(1), unrelated = s.j.create(1);
        s.link(scope, shared, Set.of(s.other)); s.link(scope, privateJob, Set.of());
        var page = s.registry.scopedJobs(scope.id(), s.other, 0, 8);
        assertEquals(List.of(shared), page.jobs()); assertFalse(page.jobs().contains(unrelated));
        assertEquals(1, page.nextOffset()); assertEquals(1, page.inspected()); assertFalse(page.more());
        assertSame(s.j.store.query(shared.id(), s.owner), page.jobs().getFirst());
        assertThrows(SecurityException.class, () -> s.j.store.query(shared.id(), s.other));
        assertFalse(s.j.store.mayControl(shared.id(), shared.generation(), s.other));
        assertEquals(Outcomes.Reason.AUTHORITY_DENIED, s.j.store.cancel(shared.id(), shared.guard(), s.other).reason());
        assertEquals(Outcomes.Reason.AUTHORITY_DENIED, s.j.store.assign(shared.id(), shared.guard(), UUID.randomUUID(), s.j.actor,
                JobLifecycleStoreTest.REF, List.of(JobLifecycleStoreTest.REF), JobLifecycleStoreTest.limits(11_000), s.other).reason());
    }
    @Test void policyExpansionCannotExpandExistingPerJobDisclosure() {
        var s = new Scene(); Scope scope = s.create(); Jobs.Job job = s.j.create(1); scope = s.link(scope, job, Set.of());
        s.complete(s.registry.update(scope.id(), scope.revision(), scope.name(), s.sharing(), null, s.owner));
        assertTrue(s.registry.scopedJobs(scope.id(), s.other, 0, 8).jobs().isEmpty());
    }
    @Test void policyRevocationIsEffectiveBeforeAcknowledgementAndAfterIt() {
        var s = new Scene(); Scope scope = s.create(Kind.ORGANIZATION, s.sharing()); s.link(scope, s.j.create(1), Set.of(s.other));
        scope = s.current(scope.id()); s.memory.hold = true;
        s.registry.update(scope.id(), scope.revision(), scope.name(), PolicyRef.privateScope(), null, s.owner);
        assertEquals(Status.UNAVAILABLE, s.registry.scopedJobs(scope.id(), s.other, 0, 8).status());
        s.memory.release(); s.registry.tick();
        assertEquals(Status.DENIED, s.registry.scopedJobs(scope.id(), s.other, 0, 8).status());
    }
    @Test void jobViewsReadOriginalLifecycleAndUnlinkCannotCancelWork() {
        var s = new Scene(); Scope scope = s.create(); Jobs.Job job = s.j.create(1); s.link(scope, job, Set.of());
        JobLifecycleStoreTest.commit(s.j, s.j.store.transition(job.id(), job.guard(), Jobs.State.FAILED, Outcomes.Reason.ACTION_FAILED, s.owner));
        var actual = s.j.store.query(job.id(), s.owner); var page = s.registry.scopedJobs(scope.id(), s.owner, 0, 8);
        assertSame(actual, page.jobs().getFirst()); assertEquals(Jobs.State.FAILED, page.jobs().getFirst().state());
        Scope current = s.current(scope.id()); s.complete(s.registry.unlinkJob(scope.id(), current.revision(), job.id(), s.owner));
        assertEquals(actual, s.j.store.query(job.id(), s.owner)); assertTrue(s.registry.scopedJobs(scope.id(), s.owner, 0, 8).jobs().isEmpty());
    }
    @Test void expiryImmediatelyDeniesSharedParticipationAndPreservesRunningWorkAndLinks() {
        var s = new Scene(); Scope scope = s.join(s.create(Kind.TEMPORARY_PARTY, s.sharing()));
        Jobs.Job job = s.j.assign(s.j.create(1)); s.link(scope, job, Set.of(s.other)); var original = s.j.store.snapshot();
        s.j.clock.now = scope.expiresAt() - 1;
        assertEquals(Status.APPLIED, s.registry.scopedJobs(scope.id(), s.other, 0, 8).status());
        s.j.clock.now++;
        assertEquals(Status.DENIED, s.registry.scopedJobs(scope.id(), s.other, 0, 8).status());
        assertEquals(Participation.EXPIRED, s.registry.inspect(scope.id(), s.owner).members().getFirst().participation());
        assertEquals(Status.EXPIRED, s.registry.join(scope.id(), s.current(scope.id()).revision(), s.j.actor, "worker", s.owner).status());
        assertEquals(1, s.registry.expire().changed()); s.registry.tick(); Scope expired = s.current(scope.id());
        assertTrue(expired.expired()); assertTrue(expired.members().isEmpty()); assertEquals(1, expired.jobs().size());
        assertEquals(EventKind.EXPIRED, expired.history().getLast().kind());
        assertEquals(original, s.j.store.snapshot()); assertEquals(Jobs.State.ACTIVE, s.j.store.query(job.id(), s.owner).state());
        assertTrue(s.j.notices.isEmpty()); assertEquals(List.of(job), s.registry.scopedJobs(scope.id(), s.owner, 0, 8).jobs());
    }
    @Test void expirySlicesAreBoundedAndPersistentGroupsDoNotExpire() {
        var s = new Scene(); Scope persistent = s.create(); for (int i = 0; i < 8; i++) s.create(Kind.TEMPORARY_PARTY, PolicyRef.privateScope());
        s.j.clock.now += 1000;
        for (int i = 0; i < 3; i++) { Expiry result = s.registry.expire(); assertTrue(result.inspected() <= 4); s.registry.tick(); }
        assertEquals(8, s.registry.snapshot().scopes().stream().filter(Scope::expired).count());
        assertFalse(s.current(persistent.id()).expired());
    }
    @Test void unknownUnloadedAndUnavailableMembersRetainOnlyStableReferences() {
        var s = new Scene(); Scope scope = s.join(s.create());
        for (Participation state : List.of(Participation.UNKNOWN, Participation.UNLOADED, Participation.UNAVAILABLE)) {
            s.facts.put(s.j.actor, new OrganizationRegistry.MemberFact(s.owner, state));
            assertEquals(state, s.registry.inspect(scope.id(), s.owner).members().getFirst().participation());
            assertEquals(Status.MISSING_REFERENCE, s.registry.role(scope.id(), scope.revision(), s.j.actor.citizenId(), "Worker", s.owner).status());
        }
        s.facts.clear(); assertEquals(Participation.MISSING, s.registry.inspect(scope.id(), s.owner).members().getFirst().participation());
        assertEquals(s.j.actor, s.current(scope.id()).members().getFirst().actor());
    }
    @Test void withdrawnInvitationAndChangedControlDoNotTransferMembership() {
        var s = new Scene(); Scope scope = s.create(Kind.ORGANIZATION, s.sharing());
        s.complete(s.registry.join(scope.id(), scope.revision(), s.foreignActor, "guest", s.other)); scope = s.current(scope.id());
        s.complete(s.registry.update(scope.id(), scope.revision(), scope.name(), PolicyRef.privateScope(), null, s.owner));
        assertEquals(Participation.DEPARTED, s.registry.inspect(scope.id(), s.owner).members().getFirst().participation());
        s.complete(s.registry.leave(scope.id(), s.current(scope.id()).revision(), s.foreignActor.citizenId(), s.other));
        scope = s.join(s.current(scope.id())); s.facts.put(s.j.actor, new OrganizationRegistry.MemberFact(s.other, Participation.ACTIVE));
        assertEquals(Participation.DEPARTED, s.registry.inspect(scope.id(), s.owner).members().getFirst().participation());
        assertEquals(s.owner, s.current(scope.id()).members().getFirst().owner());
    }
    @Test void unknownActorAndMissingHeadquartersAreRejectedAndLaterLossIsReported() {
        var s = new Scene(); var hq = new ContainerRef("minecraft:overworld", 0, 64, 0); s.headquartersPresent = false;
        assertEquals(Status.MISSING_REFERENCE, s.registry.create(UUID.randomUUID(), "Guild", Kind.ORGANIZATION, 0, PolicyRef.privateScope(), hq, s.owner).status());
        Scope scope = s.create();
        assertEquals(Status.MISSING_REFERENCE, s.registry.join(scope.id(), scope.revision(), JobLifecycleStoreTest.actor(), "worker", s.owner).status());
        s.headquartersPresent = true; scope = s.complete(s.registry.update(scope.id(), scope.revision(), scope.name(), scope.policy(), hq, s.owner));
        s.headquartersPresent = false;
        assertFalse(s.registry.inspect(scope.id(), s.owner).headquartersPresent()); assertEquals(hq, s.current(scope.id()).headquarters());
    }
    @Test void samePrincipalDifferentDomainAndDifferentWorldAreIsolated() {
        var s = new Scene(); Scope scope = s.create();
        var wrongDomain = new TrustedContext(s.owner.principal(), new ScopeRef(s.j.world, UUID.randomUUID()));
        var wrongWorld = new TrustedContext(s.owner.principal(), new ScopeRef(UUID.randomUUID(), s.owner.scope().domainId()));
        for (TrustedContext caller : List.of(wrongDomain, wrongWorld, s.other)) {
            assertEquals(Status.DENIED, s.registry.inspect(scope.id(), caller).status());
            assertEquals(Status.DENIED, s.registry.update(scope.id(), scope.revision(), "Takeover", scope.policy(), null, caller).status());
            assertTrue(s.registry.scopedJobs(scope.id(), caller, 0, 8).jobs().isEmpty());
        }
    }
    @Test void pendingWriteAndRevisionConflictsExposeOnlyCommittedOwnerState() {
        var s = new Scene(); Scope scope = s.create(); s.memory.hold = true;
        Change result = s.registry.update(scope.id(), scope.revision(), "New name", scope.policy(), null, s.owner);
        assertEquals(Status.PENDING, result.status()); assertEquals(scope, s.current(scope.id()));
        assertEquals(Status.UNAVAILABLE, s.registry.join(scope.id(), scope.revision(), s.j.actor, "worker", s.owner).status());
        s.memory.release(); s.registry.tick();
        assertEquals(Status.CONFLICT, s.registry.update(scope.id(), scope.revision(), "Stale", scope.policy(), null, s.owner).status());
    }
    @Test void writeFailureTimeoutAndLateAcknowledgementCannotReactivateRegistry() {
        var s = new Scene(); Scope scope = s.create(); s.memory.hold = true;
        s.registry.update(scope.id(), scope.revision(), "Too late", scope.policy(), null, s.owner);
        s.j.clock.now += 30_001; s.registry.tick(); assertFalse(s.registry.ready());
        s.memory.release(); s.registry.tick();
        assertEquals(scope, s.registry.snapshot().scopes().getFirst()); assertFalse(s.registry.rootsComplete());
        assertEquals(Status.UNAVAILABLE, s.registry.inspect(scope.id(), s.owner).status());
        var failure = new Scene(); Scope failed = failure.create(); failure.memory.fail = true;
        failure.registry.update(failed.id(), failed.revision(), "Failed", failed.policy(), null, failure.owner);
        failure.registry.tick(); assertFalse(failure.registry.ready()); assertEquals(failed, failure.registry.snapshot().scopes().getFirst());
    }
    @Test void nonmatchingStorageAcknowledgementAndCloseFenceNewPublications() {
        var s = new Scene(); Scope scope = s.create(); s.memory.hold = true;
        s.registry.update(scope.id(), scope.revision(), "Changed", scope.policy(), null, s.owner);
        s.memory.held.complete(s.memory.state); s.registry.tick(); assertFalse(s.registry.ready());
        var closed = new Scene(); closed.registry.close();
        assertEquals(Status.UNAVAILABLE, closed.registry.create(UUID.randomUUID(), "Guild", Kind.ORGANIZATION, 0, PolicyRef.privateScope(), null, closed.owner).status());
        assertEquals(0, closed.memory.writes);
    }
    @Test void aBlockedRootPublicationCommitsNothingAndUncertainWritesFreezeCollection() {
        var s = new Scene(); var roots = new RetentionRoots();
        s.registry.retentionGuard((keys, complete) -> { var u = roots.publishNext("organizations", keys, complete);
            return u == RetentionRoots.Update.APPLIED || u == RetentionRoots.Update.UNCHANGED; });
        Scope scope = s.join(s.create()); s.link(scope, s.j.create(1), Set.of());
        assertTrue(roots.snapshot().roots().contains(new WorldRetentionManager.Key("jobs", "records")));
        assertTrue(roots.snapshot().roots().contains(new WorldRetentionManager.Key("citizens", "records")));
        s.memory.fail = true; scope = s.current(scope.id());
        s.registry.update(scope.id(), scope.revision(), "Uncertain", scope.policy(), null, s.owner); s.registry.tick();
        assertFalse(roots.snapshot().complete());
        var denied = new Scene(); denied.registry.retentionGuard((keys, complete) -> keys.isEmpty());
        assertEquals(Status.UNAVAILABLE, denied.registry.create(UUID.randomUUID(), "Guild", Kind.ORGANIZATION, 0, PolicyRef.privateScope(), null, denied.owner).status());
        assertEquals(0, denied.memory.writes);
    }
    @Test void membershipScopeTemporaryHistoryAndReferenceCountsHaveHardCaps() {
        var s = new Scene(); Scope scope = s.create();
        for (int i = 0; i < 16; i++) {
            ActorRef actor = JobLifecycleStoreTest.actor(); s.facts.put(actor, new OrganizationRegistry.MemberFact(s.owner, Participation.ACTIVE));
            s.complete(s.registry.join(scope.id(), s.current(scope.id()).revision(), actor, "worker", s.owner));
        }
        assertEquals(16, s.current(scope.id()).members().size()); assertEquals(8, s.current(scope.id()).history().size());
        assertEquals(Status.LIMIT_REACHED, s.registry.join(scope.id(), s.current(scope.id()).revision(), s.j.actor, "worker", s.owner).status());
        for (int i = 0; i < 8; i++) s.create(Kind.TEMPORARY_PARTY, PolicyRef.privateScope());
        assertEquals(Status.LIMIT_REACHED, s.registry.create(UUID.randomUUID(), "Party", Kind.TEMPORARY_PARTY, 1, PolicyRef.privateScope(), null, s.owner).status());
        for (int i = 0; i < 7; i++) s.create();
        assertEquals(16, s.registry.snapshot().scopes().size());
        assertEquals(Status.LIMIT_REACHED, s.registry.create(UUID.randomUUID(), "Extra", Kind.ORGANIZATION, 0, PolicyRef.privateScope(), null, s.owner).status());
    }
    @Test void sixteenLinkedJobsAreRetainedAndQueriesInspectOnlyRequestedSlice() {
        var s = new Scene(); Scope scope = s.create();
        for (int i = 0; i < 16; i++) s.link(scope, s.j.create(1), Set.of());
        assertEquals(Status.LIMIT_REACHED, s.registry.linkJob(scope.id(), s.current(scope.id()).revision(), s.j.create(1).id(), Set.of(), s.owner).status());
        var first = s.registry.scopedJobs(scope.id(), s.owner, 0, 8); var second = s.registry.scopedJobs(scope.id(), s.owner, first.nextOffset(), 8);
        assertEquals(8, first.inspected()); assertEquals(8, second.inspected()); assertTrue(first.more()); assertFalse(second.more());
        assertEquals(16, Stream.concat(first.jobs().stream(), second.jobs().stream()).map(Jobs.Job::id).distinct().count());
        assertEquals(Status.INVALID, s.registry.scopedJobs(scope.id(), s.owner, 0, 9).status());
        assertEquals(Status.INVALID, s.registry.scopedJobs(scope.id(), s.owner, 17, 8).status());
        assertEquals(0, s.registry.scopedJobs(scope.id(), s.owner, 0, 0).inspected());
    }
    @Test void normalizedUnicodeLabelsRespectExactCodePointAndByteLimits() {
        var s = new Scene(); var exact = s.registry.create(UUID.randomUUID(), "😀".repeat(64), Kind.ORGANIZATION, 0, PolicyRef.privateScope(), null, s.owner);
        s.complete(exact); Scope scope = s.create();
        assertEquals(Status.INVALID, s.registry.update(scope.id(), scope.revision(), "😀".repeat(65), scope.policy(), null, s.owner).status());
        assertEquals(Status.INVALID, s.registry.update(scope.id(), scope.revision(), "e\u0301", scope.policy(), null, s.owner).status());
        assertEquals(Status.INVALID, s.registry.update(scope.id(), scope.revision(), "\uD800", scope.policy(), null, s.owner).status());
        assertEquals(Status.INVALID, s.registry.update(scope.id(), scope.revision(), " ".repeat(65_536), scope.policy(), null, s.owner).status());
        assertEquals(Status.INVALID, s.registry.join(scope.id(), scope.revision(), s.j.actor, "x".repeat(33), s.owner).status());
        s.complete(s.registry.join(scope.id(), scope.revision(), s.j.actor, "😀".repeat(32), s.owner));
    }
    @Test void lifetimeAndRevisionOverflowNeverResetOrPublishAnAllowance() {
        var s = new Scene();
        for (long ttl : List.of(-1L, 0L, MAX_TTL_MILLIS + 1, Long.MAX_VALUE))
            assertEquals(Status.INVALID, s.registry.create(UUID.randomUUID(), "Party", Kind.TEMPORARY_PARTY, ttl, PolicyRef.privateScope(), null, s.owner).status());
        s.j.clock.now = Long.MAX_VALUE;
        assertEquals(Status.LIMIT_REACHED, s.registry.create(UUID.randomUUID(), "Party", Kind.TEMPORARY_PARTY, 1, PolicyRef.privateScope(), null, s.owner).status());
        assertEquals(0, s.memory.writes);
        var memory = new Memory(s.j.world); memory.state = new Snapshot(s.j.world, Long.MAX_VALUE, List.of());
        var registry = new OrganizationRegistry(memory.state, memory, references(s.owner), s.j.store, new JobLifecycleStoreTest.TestClock(), Limits.defaults(), false);
        assertEquals(Status.LIMIT_REACHED, registry.create(UUID.randomUUID(), "Guild", Kind.ORGANIZATION, 0, PolicyRef.privateScope(), null, s.owner).status());
        assertEquals(0, memory.writes);
    }
    @Test void exactPolicyByteCapAndZeroCapacityDisableCreationWithoutSideEffects() {
        int bytes = OrganizationJournal.policyBytes(PolicyRef.privateScope()); var d = Limits.defaults();
        var exact = new Scene(new Limits(d.scopes(), d.temporary(), d.members(), d.jobs(), d.grants(), bytes, d.queryResults(), d.history(), d.expirySlice(), d.publicationMillis())); exact.create();
        var shortOne = new Scene(new Limits(16, 8, 16, 16, 8, bytes - 1, 8, 8, 4, 30_000));
        assertEquals(Status.LIMIT_REACHED, shortOne.registry.create(UUID.randomUUID(), "Guild", Kind.ORGANIZATION, 0, PolicyRef.privateScope(), null, shortOne.owner).status());
        var zero = new Scene(new Limits(0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertEquals(Status.LIMIT_REACHED, zero.registry.create(UUID.randomUUID(), "Guild", Kind.ORGANIZATION, 0, PolicyRef.privateScope(), null, zero.owner).status());
        assertEquals(0, zero.memory.writes);
    }
    @Test void zeroHistoryAndZeroExpirySliceRemainBoundedExplicitChoices() {
        var s = new Scene(new Limits(16, 8, 16, 16, 8, 4096, 8, 0, 0, 30_000)); Scope scope = s.join(s.create(Kind.TEMPORARY_PARTY, PolicyRef.privateScope()));
        assertTrue(scope.history().isEmpty()); s.j.clock.now += 1000;
        assertEquals(0, s.registry.expire().inspected()); assertEquals(Status.EXPIRED, s.registry.inspect(scope.id(), s.owner).status());
    }
    @Test void modelGeneratedPolicyVocabularyAndForeignWorldReferencesCannotBeAdmitted() {
        assertThrows(IllegalArgumentException.class, () -> PolicyId.valueOf("execute /op player"));
        assertThrows(IllegalArgumentException.class, () -> new PolicyRef(PolicyId.PRIVATE, 2, Set.of(), Set.of()));
        Set<TrustedContext> oversized = new AbstractSet<>() {
            public int size() { return 9; }
            public Iterator<TrustedContext> iterator() { throw new AssertionError("Oversized contexts must reject before traversal/copy"); }
        };
        assertThrows(IllegalArgumentException.class, () -> new PolicyRef(PolicyId.NAMED_SHARING, 1, oversized, Set.of()));
        var s = new Scene(); var otherWorld = JobLifecycleStoreTest.owner(UUID.randomUUID());
        var policy = new PolicyRef(PolicyId.NAMED_SHARING, 1, Set.of(otherWorld), Set.of());
        assertEquals(Status.INVALID, s.registry.create(UUID.randomUUID(), "Guild", Kind.ORGANIZATION, 0, policy, null, s.owner).status()); assertEquals(0, s.memory.writes);
    }
    @Test void wrongJobOrOriginCannotUseNamedReadGrantAndAnIdIsNotAGrant() {
        var s = new Scene(); Jobs.Job job = s.j.create(1), second = s.j.create(1);
        var grant = new JobLifecycleStore.NamedReadGrant(job.id(), s.owner, Set.of(s.other));
        assertSame(job, s.j.store.scopedQuery(job.id(), s.other, grant));
        assertThrows(SecurityException.class, () -> s.j.store.scopedQuery(second.id(), s.other, grant));
        assertThrows(SecurityException.class, () -> s.j.store.scopedQuery(job.id(), s.other, new JobLifecycleStore.NamedReadGrant(job.id(), s.other, Set.of(s.other))));
    }
    @Test void membershipChangesAndQueriesRejectNonOwnerThreads() throws Exception {
        var s = new Scene(); Scope scope = s.create(); var worker = Executors.newSingleThreadExecutor();
        try { var failure = assertThrows(ExecutionException.class, () -> worker.submit(() -> s.registry.inspect(scope.id(), s.owner)).get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause()); }
        finally { worker.shutdownNow(); }
    }
    @Test void foreignJobOwnerCanDiscloseNamedWorkAndRevokeItAfterDeparture() {
        var s = new Scene(); Scope scope = s.create(Kind.ORGANIZATION, s.sharing()); UUID jobId = UUID.randomUUID();
        var creation = s.j.store.create(jobId, jobId, JobLifecycleStoreTest.request(s.foreignActor, 1), s.other,
                JobLifecycleStoreTest.observation(), JobLifecycleStoreTest.limits(11_000), List.of());
        assertTrue(creation.accepted()); s.j.settle(); Jobs.Job job = s.j.store.query(jobId, s.other);
        assertEquals(Status.DENIED, s.registry.linkJob(scope.id(), scope.revision(), jobId, Set.of(s.owner), s.owner).status());
        assertEquals(Status.DENIED, s.registry.linkJob(scope.id(), scope.revision(), jobId, Set.of(s.owner), s.other).status());
        s.complete(s.registry.join(scope.id(), scope.revision(), s.foreignActor, "Guest", s.other)); scope = s.current(scope.id());
        s.complete(s.registry.linkJob(scope.id(), scope.revision(), jobId, Set.of(s.owner), s.other));
        assertSame(job, s.registry.scopedJobs(scope.id(), s.owner, 0, 8).jobs().getFirst());
        assertThrows(SecurityException.class, () -> s.j.store.query(jobId, s.owner));
        scope = s.current(scope.id()); s.complete(s.registry.update(scope.id(), scope.revision(), scope.name(), PolicyRef.privateScope(), null, s.owner));
        assertEquals(Participation.DEPARTED, s.registry.inspect(scope.id(), s.owner).members().getFirst().participation());
        s.complete(s.registry.unlinkJob(scope.id(), s.current(scope.id()).revision(), jobId, s.other));
        assertSame(job, s.j.store.query(jobId, s.other)); assertTrue(s.registry.scopedJobs(scope.id(), s.owner, 0, 8).jobs().isEmpty());
    }
    @Test void newNamedViewersCannotReceiveAnotherOwnersHistoricalDisclosure() {
        var s = new Scene(); Scope scope = s.create(Kind.ORGANIZATION, s.sharing()); Jobs.Job job = s.j.create(1); s.link(scope, job, Set.of(s.other));
        var third = JobLifecycleStoreTest.owner(s.j.world); scope = s.current(scope.id());
        var expanded = new PolicyRef(PolicyId.NAMED_SHARING, 1, Set.of(s.other), Set.of(s.other, third));
        s.complete(s.registry.update(scope.id(), scope.revision(), scope.name(), expanded, null, s.owner));
        assertTrue(s.registry.scopedJobs(scope.id(), third, 0, 8).jobs().isEmpty());
        assertSame(job, s.registry.scopedJobs(scope.id(), s.other, 0, 8).jobs().getFirst());
    }
    static OrganizationRegistry.References references(TrustedContext owner) {
        return new OrganizationRegistry.References() {
            public OrganizationRegistry.MemberFact citizen(ActorRef actor) { return new OrganizationRegistry.MemberFact(owner, Participation.ACTIVE); }
            public boolean headquarters(ContainerRef ref, TrustedContext caller) { return true; }
        };
    }
    static Stream<long[]> finiteLimits() {
        long[] maxima = {16, 8, 16, 16, 8, 4096, 8, 8, 4, 30_000}; var rows = new ArrayList<long[]>();
        for (int field = 0; field < maxima.length; field++) for (long value : new long[]{-1, 0, maxima[field], maxima[field] + 1}) rows.add(new long[]{field, value});
        return rows.stream();
    }
    @ParameterizedTest @MethodSource("finiteLimits") void eachLimitAcceptsZeroAndMaximumAndRejectsUnderflowAndExcess(long[] row) {
        long[] v = {16, 8, 16, 16, 8, 4096, 8, 8, 4, 30_000}; int field = (int) row[0]; long max = v[field]; v[field] = row[1];
        org.junit.jupiter.api.function.Executable construct = () -> new Limits((int)v[0], (int)v[1], (int)v[2], (int)v[3], (int)v[4], (int)v[5], (int)v[6], (int)v[7], (int)v[8], v[9]);
        if (row[1] < 0 || row[1] > max) assertThrows(IllegalArgumentException.class, construct); else assertDoesNotThrow(construct);
    }
}
