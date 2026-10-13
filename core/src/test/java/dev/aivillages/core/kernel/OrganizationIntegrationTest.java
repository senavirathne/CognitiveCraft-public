package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Organizations.*;
import static dev.aivillages.core.kernel.ExecutorJUnitTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** GP-23 owner integration: production controller/executor/Jobs, deterministic gateway, real fsynced scope storage. */
class OrganizationIntegrationTest {
    @TempDir Path world;
    static OrganizationRegistry registry(OrganizationJournal journal, JobExecutorIntegrationTest.Scene scene) {
        return new OrganizationRegistry(journal.snapshot(), (expected, next) -> CompletableFuture.supplyAsync(() -> {
            assertNotEquals(Thread.currentThread().getName(), "main");
            try { return journal.replace(expected, next); } catch (Exception failure) { throw new CompletionException(failure); }
        }, scene.worker), OrganizationRegistryTest.references(OWNER), scene.jobs, scene.f.clock, Limits.defaults(), journal.readOnly());
    }
    static void settle(OrganizationRegistry registry, Change change) throws Exception {
        assertTrue(change.accepted(), change.toString()); long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (int work = 0; work < 5000 && !registry.ready() && System.nanoTime() < until; work++) { registry.tick(); Thread.sleep(1); }
        assertTrue(registry.ready(), "Scope publication must acknowledge within both work and wall bounds");
    }
    static OrganizationRegistry registry(OrganizationJournal journal, OrganizationRegistryTest.Scene scene,
                                         ExecutorService worker) {
        return new OrganizationRegistry(journal.snapshot(), (expected, next) -> CompletableFuture.supplyAsync(() -> {
            try { return journal.replace(expected, next); } catch (Exception failure) { throw new CompletionException(failure); }
        }, worker), new OrganizationRegistry.References() {
            public OrganizationRegistry.MemberFact citizen(ActorRef actor) { return scene.facts.get(actor); }
            public boolean headquarters(ContainerRef ref, TrustedContext caller) { return false; }
        }, scene.j.store, scene.j.clock, Limits.defaults(), journal.readOnly());
    }
    @Test void ordinaryKnownJobWorksWithoutGroupsThenSameJobIsViewedAndReloadedWithoutInference() throws Exception {
        var fixture = new JobExecutorIntegrationTest(); fixture.world = world;
        try (var scene = fixture.new Scene()) {
            assertFalse(Files.exists(world.resolve(OrganizationJournal.WORLD_RELATIVE_PATH)));
            UUID submission = scene.submit(); scene.until(() -> scene.terminal(submission));
            Jobs.Job original = scene.job(submission);
            assertEquals(Jobs.State.SUCCEEDED, original.state()); assertEquals(1, original.fulfilled()); assertEquals(3, original.effects()); assertEquals(0, scene.models);
            Jobs.Snapshot completed = scene.jobs.snapshot(); int gatewayCalls = scene.f.gateway.calls.size();
            var journal = scene.worker.submit(() -> OrganizationJournal.open(world, OWNER.scope().worldId())).get(5, TimeUnit.SECONDS);
            UUID id = UUID.randomUUID(); var foreign = JobLifecycleStoreTest.owner(OWNER.scope().worldId());
            try {
                var registry = registry(journal, scene);
                var policy = new PolicyRef(PolicyId.NAMED_SHARING, 1, Set.of(), Set.of(foreign));
                settle(registry, registry.create(id, "Farmers", Organizations.Kind.ORGANIZATION, 0, policy, null, OWNER));
                Scope scope = registry.inspect(id, OWNER).scope();
                settle(registry, registry.join(id, scope.revision(), ACTOR, "worker", OWNER)); scope = registry.inspect(id, OWNER).scope();
                settle(registry, registry.linkJob(id, scope.revision(), original.id(), Set.of(foreign), OWNER));
                assertSame(original, registry.scopedJobs(id, OWNER, 0, 8).jobs().getFirst());
                assertSame(original, registry.scopedJobs(id, foreign, 0, 8).jobs().getFirst());
                assertEquals(Outcomes.Reason.AUTHORITY_DENIED, scene.jobs.cancel(original.id(), original.guard(), foreign).reason());
                scope = registry.inspect(id, OWNER).scope(); settle(registry, registry.leave(id, scope.revision(), ACTOR.citizenId(), OWNER));
                assertEquals(completed, scene.jobs.snapshot()); assertEquals(gatewayCalls, scene.f.gateway.calls.size()); assertEquals(0, scene.models);
                String disk = Files.readString(world.resolve(OrganizationJournal.WORLD_RELATIVE_PATH).resolve("state.jsonl"));
                assertFalse(disk.contains("\"attempts\"")); assertFalse(disk.contains("\"request\"")); assertFalse(disk.contains("\"state\""));
            } finally { scene.worker.submit(() -> { journal.close(); return null; }).get(5, TimeUnit.SECONDS); }
            var reopened = scene.worker.submit(() -> OrganizationJournal.open(world, OWNER.scope().worldId())).get(5, TimeUnit.SECONDS);
            try {
                var registry = registry(reopened, scene); assertEquals(id, registry.inspect(id, OWNER).scope().id());
                assertTrue(registry.inspect(id, OWNER).scope().members().isEmpty());
                assertSame(original, registry.scopedJobs(id, foreign, 0, 8).jobs().getFirst());
                assertEquals(completed, scene.jobs.snapshot()); assertEquals(gatewayCalls, scene.f.gateway.calls.size()); assertEquals(0, scene.models);
            } finally { scene.worker.submit(() -> { reopened.close(); return null; }).get(5, TimeUnit.SECONDS); }
        }
    }
    @Test void partyExpiryAndRemovalLeaveLiveExecutorAssignmentAndCommittedHistoryUntouched() throws Exception {
        var fixture = new JobExecutorIntegrationTest(); fixture.world = world;
        try (var scene = fixture.new Scene()) {
            scene.f.gateway.stall = true; UUID submission = scene.submit(); scene.until(() -> !scene.f.gateway.calls.isEmpty());
            Jobs.Job active = scene.job(submission); assertEquals(Jobs.State.ACTIVE, active.state());
            var journal = scene.worker.submit(() -> OrganizationJournal.open(world, OWNER.scope().worldId())).get(5, TimeUnit.SECONDS);
            try {
                var registry = registry(journal, scene); UUID id = UUID.randomUUID();
                settle(registry, registry.create(id, "Expedition", Organizations.Kind.TEMPORARY_PARTY, 1000, PolicyRef.privateScope(), null, OWNER));
                Scope party = registry.inspect(id, OWNER).scope(); settle(registry, registry.join(id, party.revision(), ACTOR, "captain", OWNER));
                party = registry.inspect(id, OWNER).scope(); settle(registry, registry.linkJob(id, party.revision(), active.id(), Set.of(), OWNER));
                Jobs.Snapshot before = scene.jobs.snapshot(); int actions = scene.f.gateway.calls.size();
                scene.f.clock.millis = party.expiresAt(); assertEquals(1, registry.expire().changed());
                settle(registry, new Change(Status.PENDING, null, null));
                assertTrue(registry.inspect(id, OWNER).scope().expired()); assertTrue(registry.inspect(id, OWNER).scope().members().isEmpty());
                assertSame(active, registry.scopedJobs(id, OWNER, 0, 8).jobs().getFirst());
                assertEquals(before, scene.jobs.snapshot()); assertEquals(actions, scene.f.gateway.calls.size()); assertEquals(0, scene.f.gateway.cancellation); assertEquals(0, scene.models);
            } finally { scene.worker.submit(() -> { journal.close(); return null; }).get(5, TimeUnit.SECONDS); }
        }
    }
    @Test void missingJobReferenceIsReportedWithoutReconstructingOrExecutingWork() {
        var s = new OrganizationRegistryTest.Scene(); Jobs.Job job = s.j.create(1); Scope scope = s.create(); s.link(scope, job, Set.of());
        var emptyJobs = new JobLifecycleStore(Jobs.Snapshot.empty(s.j.world), (old, next) -> CompletableFuture.completedFuture(next),
                JobLifecycleStore.privateJobs((actor, caller) -> caller.equals(s.owner)), cancellation -> { throw new AssertionError("No group cancellation"); },
                id -> Optional.of(CropDelivery.SPEC), JobLifecycleStoreTest.environment(), s.j.clock, Jobs.Settings.defaults(), false);
        var registry = new OrganizationRegistry(s.registry.snapshot(), s.memory, OrganizationRegistryTest.references(s.owner),
                emptyJobs, s.j.clock, Limits.defaults(), false);
        var page = registry.scopedJobs(scope.id(), s.owner, 0, 8);
        assertEquals(1, page.missingReferences()); assertEquals(1, page.inspected()); assertTrue(page.jobs().isEmpty());
        assertEquals(1, registry.inspect(scope.id(), s.owner).scope().jobs().size()); assertTrue(emptyJobs.snapshot().jobs().isEmpty());
    }
    @Test void scopedResourceJobRetainsSchemaTwoReceiptsAndOriginalIdentity() throws Exception {
        var scene = new OakLogJobLifecycleTest.Scene(); Jobs.Job job = scene.assign(scene.create());
        job = scene.finish(job, scene.receipts(job));
        var owner = OakLogTestSupport.OWNER; var memory = new OrganizationRegistryTest.Memory(owner.scope().worldId());
        var registry = new OrganizationRegistry(memory.state, memory, OrganizationRegistryTest.references(owner), scene.jobs,
                OakLogTestSupport.CLOCK, Limits.defaults(), false);
        UUID id = UUID.randomUUID(); Change create = registry.create(id, "Woodworkers", Organizations.Kind.ORGANIZATION,
                0, PolicyRef.privateScope(), null, owner); assertTrue(create.accepted()); registry.tick();
        Scope scope = registry.inspect(id, owner).scope();
        assertTrue(registry.linkJob(id, scope.revision(), job.id(), Set.of(), owner).accepted()); registry.tick();
        Jobs.Job viewed = registry.scopedJobs(id, owner, 0, 8).jobs().getFirst();
        assertSame(job, viewed); assertEquals(OakLogDelivery.ID, viewed.request().request().capability());
        assertEquals(3, viewed.current().resourceReceipts().size()); assertTrue(viewed.current().receipts().isEmpty());
        assertEquals(1, viewed.fulfilled()); assertEquals(Jobs.State.SUCCEEDED, viewed.state());
        byte[] encoded = OrganizationJournal.encode(memory.state);
        assertFalse(new String(encoded, java.nio.charset.StandardCharsets.UTF_8).contains("resourceReceipts"));
        assertEquals(memory.state, OrganizationJournal.decode(encoded, owner.scope().worldId()));
        assertSame(job, scene.jobs.query(job.id(), owner));
    }
    @Test void departedContributorCanDurablyRevokeNamedDisclosureAfterExpiredPartyReload() throws Exception {
        var scene = new OrganizationRegistryTest.Scene(); UUID jobId = UUID.randomUUID(), id = UUID.randomUUID();
        var created = scene.j.store.create(jobId, jobId, JobLifecycleStoreTest.request(scene.foreignActor, 1), scene.other,
                JobLifecycleStoreTest.observation(), JobLifecycleStoreTest.limits(11_000), List.of());
        assertTrue(created.accepted()); scene.j.settle(); Jobs.Job original = scene.j.store.query(jobId, scene.other);
        Jobs.Snapshot before = scene.j.store.snapshot(); var worker = Executors.newSingleThreadExecutor();
        try {
            var journal = worker.submit(() -> OrganizationJournal.open(world, scene.j.world)).get(5, TimeUnit.SECONDS);
            try {
                var registry = registry(journal, scene, worker);
                settle(registry, registry.create(id, "Expedition", Organizations.Kind.TEMPORARY_PARTY, 1000,
                        scene.sharing(), null, scene.owner));
                Scope party = registry.inspect(id, scene.owner).scope();
                settle(registry, registry.join(id, party.revision(), scene.foreignActor, "guest", scene.other));
                party = registry.inspect(id, scene.owner).scope();
                settle(registry, registry.linkJob(id, party.revision(), jobId, Set.of(scene.owner), scene.other));
                party = registry.inspect(id, scene.owner).scope();
                settle(registry, registry.leave(id, party.revision(), scene.foreignActor.citizenId(), scene.other));
                assertSame(original, registry.scopedJobs(id, scene.owner, 0, 8).jobs().getFirst());
                scene.j.clock.now = party.expiresAt(); assertEquals(1, registry.expire().changed());
                settle(registry, new Change(Status.PENDING, null, null)); registry.close();
            } finally { worker.submit(() -> { journal.close(); return null; }).get(5, TimeUnit.SECONDS); }
            var reopened = worker.submit(() -> OrganizationJournal.open(world, scene.j.world)).get(5, TimeUnit.SECONDS);
            try {
                var registry = registry(reopened, scene, worker); Scope party = registry.inspect(id, scene.owner).scope();
                assertTrue(party.expired()); assertTrue(party.members().isEmpty());
                assertSame(original, registry.scopedJobs(id, scene.owner, 0, 8).jobs().getFirst());
                assertEquals(Status.DENIED, registry.scopedJobs(id, scene.other, 0, 8).status());
                settle(registry, registry.unlinkJob(id, party.revision(), jobId, scene.other)); registry.close();
            } finally { worker.submit(() -> { reopened.close(); return null; }).get(5, TimeUnit.SECONDS); }
            var revoked = worker.submit(() -> OrganizationJournal.open(world, scene.j.world)).get(5, TimeUnit.SECONDS);
            try {
                var registry = registry(revoked, scene, worker); Scope party = registry.inspect(id, scene.owner).scope();
                assertTrue(party.jobs().isEmpty()); assertEquals(EventKind.JOB_UNLINKED, party.history().getLast().kind());
                assertTrue(registry.scopedJobs(id, scene.owner, 0, 8).jobs().isEmpty()); registry.close();
            } finally { worker.submit(() -> { revoked.close(); return null; }).get(5, TimeUnit.SECONDS); }
            assertSame(original, scene.j.store.query(jobId, scene.other)); assertEquals(before, scene.j.store.snapshot());
            assertTrue(scene.j.notices.isEmpty()); assertFalse(scene.j.store.mayControl(jobId, original.generation(), scene.owner));
        } finally { worker.shutdownNow(); }
    }
}
