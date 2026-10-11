package dev.aivillages.core.kernel;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;
import static dev.aivillages.core.kernel.SurvivalGateway.*;
import static dev.aivillages.core.kernel.ObservedWorldKnowledgeStore.*;
import dev.aivillages.core.kernel.ObservedWorldKnowledgeStore.Limits;

/** Actual SurvivalGateway snapshots, fake mechanics/time, real owner files and retention coordination. */
class ObservedWorldKnowledgeStoreTest {
    @TempDir Path root;
    static final class Time extends Clock {
        long now = 1_000_000;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
        public long millis() { return now; }
    }
    static final class Fixture {
        final UUID world = UUID.randomUUID();
        final Time clock = new Time();
        final GatewayChecks.FakeWorld physics = new GatewayChecks.FakeWorld();
        final ActorRef actor = new ActorRef(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld");
        final TrustedContext owner = context(world), foreign = context(world);
        final AtomicBoolean permission = new AtomicBoolean(true), sharing = new AtomicBoolean(false);
        final Set<UUID> sources = ConcurrentHashMap.newKeySet();
        final Cuboid area = new Cuboid(actor.dimension(), 0, 64, 0, 1, 64, 1);
        final ContainerRef chest = new ContainerRef(actor.dimension(), 2, 64, 0);
        final SurvivalGateway gateway = gateway();
        final RetentionRoots roots = new RetentionRoots();
        final WorldRetentionManager manager = new WorldRetentionManager(WorldRetentionManager.Policy.defaults(), roots, caller -> false);
        SurvivalGateway gateway() {
            var gateway = new SurvivalGateway(physics, (a,e,c) -> permission.get(),
                    new SurvivalGateway.Limits(4, 64, 16, 2, 256, 1, 200, 16), clock);
            sources.add(gateway.observationSource()); return gateway;
        }
        static TrustedContext context(UUID world) { return new TrustedContext(new PrincipalRef(UUID.randomUUID()), new ScopeRef(world, UUID.randomUUID())); }
        ObservedEvidence capture() { return capture(gateway, owner, area, Operation.OBSERVE_SOURCE); }
        ObservedEvidence capture(SurvivalGateway gateway, TrustedContext caller, Cuboid region, Operation op) {
            physics.tick++; clock.now += 50;
            var request = new ValidatedRequest(new CapabilityRequest(CropDelivery.ID, Map.of("actor", new ActorValue(actor),
                    "amount", new IntValue(1), "source", new AreaValue(region), "destination", new ContainerValue(chest))), caller,
                    new ObservationRef(UUID.randomUUID(), physics.tick, actor.dimension()));
            Map<String, Value> arguments = switch (op) {
                case OBSERVE_SOURCE -> Map.of("actor", new ActorValue(actor), "source", new AreaValue(region));
                case OBSERVE_CONTAINER -> Map.of("actor", new ActorValue(actor), "destination", new ContainerValue(chest));
                case OBSERVE_INVENTORY -> Map.of("actor", new ActorValue(actor));
                default -> throw new IllegalArgumentException();
            };
            var usage = new Budgets.Ledger(new Budgets.Limits(Map.of(Budgets.Kind.OBSERVATIONS, 4096L,
                    Budgets.Kind.ELAPSED_TICKS, 200L, Budgets.Kind.INSTRUCTIONS, 4096L), clock.now + 10000), clock);
            UUID run = UUID.randomUUID();
            var handle = gateway.start(request, new RunCorrelation(run, new ArtifactRef(CropDelivery.ID, "a".repeat(64)), null), op.requirement(), arguments, usage);
            var receipt = gateway.poll(handle);
            while (!receipt.terminal()) { physics.tick++; clock.now += 50; receipt = gateway.poll(handle); }
            assertNull(receipt.reason()); assertEquals(0, receipt.committedEffects());
            var evidence = gateway.observedEvidence(handle, caller); gateway.releaseRun(run); return evidence;
        }
        ObservedWorldKnowledgeStore open(Path root) throws IOException { return open(root, Limits.defaults(), p -> { }); }
        ObservedWorldKnowledgeStore open(Path root, Limits limits, Faults faults) throws IOException {
            var store = ObservedWorldKnowledgeStore.open(root, world, limits, clock, (a,e,c) -> permission.get(),
                    (viewer, owner) -> viewer.equals(owner) || sharing.get(), sources::contains, faults);
            manager.register(store.retentionOwner()); return store;
        }
        EvidenceSnapshot query(ObservedWorldKnowledgeStore store, ObservedEvidence evidence) {
            return store.queryFreshEvidence(evidence.owner(), evidence.target(), QueryLimits.defaults());
        }
    }
    private static Path file(Path root, String name) { return root.resolve(WORLD_RELATIVE_PATH).resolve(name); }
    private static void rewrite(Path path, java.util.function.Consumer<Map<String,Object>> mutate) throws Exception {
        var raw = new LinkedHashMap<>(StrictJson.object(Files.readString(path))); mutate.accept(raw); raw.remove("checksum");
        raw.put("checksum", RepositoryCodec.digest(StrictJson.canonical(raw))); Files.writeString(path, StrictJson.canonical(raw));
    }
    private static RecordView only(EvidenceSnapshot snapshot) { assertTrue(snapshot.complete()); assertEquals(1, snapshot.evidence().size()); return snapshot.evidence().getFirst().record(); }

    @Test void freshFactsRetainActualGatewayProofAndBoundedImmutableContext() throws Exception {
        var f = new Fixture(); f.physics.crops.put(f.physics.source, true);
        try (var store = f.open(root)) {
            var issued = f.capture(); var result = store.recordObservation(f.owner, issued, () -> false);
            assertEquals(Status.RECORDED, result.status()); var snapshot = f.query(store, issued); var row = only(snapshot);
            assertEquals(Freshness.CURRENTLY_VERIFIED, snapshot.freshness()); assertEquals(issued.snapshot(), row.latest().observation());
            assertEquals(issued.at(), row.latest().at()); assertEquals(issued.source(), row.latest().source());
            assertEquals(issued.target(), row.target()); assertTrue(snapshot.contextBytes() <= 8192);
            assertThrows(UnsupportedOperationException.class, () -> snapshot.evidence().clear());
            assertThrows(UnsupportedOperationException.class, () -> row.latest().observation().counts().put("invented", 4L));
        }
    }
    @Test void unloadedIsUnknownAndEmptyScanOnlyProvesBoundedAbsence() throws Exception {
        var f = new Fixture();
        try (var store = f.open(root)) {
            var empty = f.capture(); store.recordObservation(f.owner, empty, () -> false);
            assertEquals(Freshness.OBSERVED_ABSENT, f.query(store, empty).freshness()); assertEquals(4, only(f.query(store, empty)).latest().observation().scannedCells());
            var unloaded = f.capture(f.gateway, f.owner, new Cuboid(f.actor.dimension(),99,64,0,99,64,0), Operation.OBSERVE_SOURCE);
            store.recordObservation(f.owner, unloaded, () -> false); assertEquals(Freshness.UNKNOWN, f.query(store, unloaded).freshness());
            var unobserved = new ObservationTarget(ObservationKind.SOURCE_CROPS, new Cuboid(f.actor.dimension(),8,64,8,8,64,8), null);
            var unknown = store.queryFreshEvidence(f.owner, unobserved, QueryLimits.defaults());
            assertTrue(unknown.complete()); assertTrue(unknown.evidence().isEmpty()); assertEquals(Freshness.UNKNOWN, unknown.freshness());
        }
    }
    @Test void changedTargetRetainsConflictingProvenanceAndSupersedesOldFact() throws Exception {
        var f = new Fixture(); f.physics.crops.put(f.physics.source,true);
        try (var store = f.open(root)) {
            var old = f.capture(); store.recordObservation(f.owner, old, () -> false); f.physics.crops.clear();
            var changed = f.capture(); store.recordObservation(f.owner, changed, () -> false);
            var rows = f.query(store, changed).evidence(); assertEquals(2,rows.size());
            assertEquals(Freshness.OBSERVED_ABSENT, rows.getFirst().freshness());
            assertEquals(Freshness.STALE,rows.getLast().freshness()); assertEquals(Qualification.SUPERSEDED, rows.getLast().qualification());
            assertEquals(old.snapshot(), rows.getLast().record().latest().observation());
        }
    }
    @Test void explicitInvalidationExpiryAndClockRegressionNeverRefreshObservation() throws Exception {
        var f = new Fixture(); f.physics.crops.put(f.physics.source,true);
        try (var store = f.open(root)) {
            var proof = f.capture(); var result = store.recordObservation(f.owner, proof, () -> false); var row = only(f.query(store,proof));
            f.clock.now = row.expiresAt(); assertEquals(Freshness.STALE,f.query(store,proof).freshness());
            f.clock.now = proof.at()-1; assertEquals(Qualification.CLOCK_REGRESSED,f.query(store,proof).evidence().getFirst().qualification());
            f.clock.now = proof.at()+1;
            assertEquals(Status.INVALIDATED,store.invalidateObservation(f.owner,result.record(),Invalidation.TARGET_CHANGED,store.revision(),()->false).status());
            assertEquals(Freshness.INVALID_TARGET,f.query(store,proof).freshness()); assertEquals(proof.at(),only(f.query(store,proof)).latest().at());
        }
    }
    @Test void thousandFreshEquivalentCapturesCoalesceWithStableIdentityAndFirstProof() throws Exception {
        var f = new Fixture(); f.physics.crops.put(f.physics.source,true);
        try (var store = f.open(root)) {
            var first = f.capture(); var initial = store.recordObservation(f.owner,first,()->false); ObservedEvidence last = first;
            for(int i=1;i<1000;i++) {last=f.capture();var result=store.recordObservation(f.owner,last,()->false);
                assertEquals(Status.RECORDED,result.status(),"Capture "+i+", readOnly="+store.readOnly());assertEquals(initial.record(),result.record());}
            var row=only(f.query(store,last)); assertEquals(1,store.privateRecordCount(f.owner)); assertEquals(1000,row.count());
            assertEquals(first.snapshot(),row.first().observation());assertEquals(last.snapshot(),row.latest().observation());
            assertTrue(Files.size(file(root,"state.json"))<4096);
            Path out=Path.of("build/observation-evidence");Files.createDirectories(out);
            Files.writeString(out.resolve("dedup.json"),StrictJson.canonical(Map.of("events",1000L,"records",1L,"history",1L,
                    "bytes",Files.size(file(root,"state.json")),"modelCalls",0L,"effects",0L)));
        }
    }
    @Test void replayDoesNotRefreshOrIncrementAndOlderIssuedEvidenceCannotWin() throws Exception {
        var f=new Fixture();try(var store=f.open(root)) {
            var old=f.capture();var latest=f.capture();var result=store.recordObservation(f.owner,latest,()->false);
            assertEquals(Status.OUT_OF_ORDER,store.recordObservation(f.owner,old,()->false).status());
            f.clock.now+=30000;assertEquals(Status.UNCHANGED,store.recordObservation(f.owner,latest,()->false).status());
            var row=only(f.query(store,latest));assertEquals(1,row.count());assertEquals(latest.at(),row.latest().at());
            assertEquals(Freshness.STALE,f.query(store,latest).freshness());assertEquals(result.record(),row.id());
        }
    }
    @Test void differentTrustedSourcesDoNotEraseContradictionsOrManufactureCertainty() throws Exception {
        var f=new Fixture();try(var store=f.open(root)) {
            f.physics.crops.put(f.physics.source,true);var old=f.capture();store.recordObservation(f.owner,old,()->false);
            var second=f.gateway();f.physics.crops.clear();var different=f.capture(second,f.owner,f.area,Operation.OBSERVE_SOURCE);
            store.recordObservation(f.owner,different,()->false);
            assertEquals(2,f.query(store,different).evidence().size());
            for(var row:f.query(store,different).evidence()) {assertEquals(Freshness.STALE,row.freshness());assertEquals(Qualification.CONFLICTING,row.qualification());}
            f.sources.remove(old.source());assertEquals(Freshness.OBSERVED_ABSENT,f.query(store,different).freshness());
        }
    }
    @Test void equivalentDifferentSourcesKeepTheirIndividualProvenance() throws Exception {
        var f=new Fixture();try(var store=f.open(root)) {
            var a=f.capture();store.recordObservation(f.owner,a,()->false);var gateway=f.gateway();
            var b=f.capture(gateway,f.owner,f.area,Operation.OBSERVE_SOURCE);store.recordObservation(f.owner,b,()->false);
            assertEquals(2,f.query(store,b).evidence().size());assertNotEquals(a.source(),b.source());
            assertTrue(f.query(store,b).evidence().stream().allMatch(e->e.freshness()==Freshness.OBSERVED_ABSENT));
        }
    }
    @Test void arbitrarySnapshotsModelClaimsAndRecordValuesCannotCreatePhysicalFacts() throws Exception {
        var f=new Fixture();try(var store=f.open(root)) {
            var evidence=f.capture();for(Object claim:List.of(evidence.snapshot(),"The chest contains wheat",Map.of("wheat",64L)))
                assertEquals(Status.UNVERIFIED,store.recordObservation(f.owner,claim,()->false).status());
            assertEquals(0,store.privateRecordCount(f.owner));store.recordObservation(f.owner,evidence,()->false);
            assertEquals(Status.UNVERIFIED,store.recordObservation(f.owner,only(f.query(store,evidence)),()->false).status());
        }
    }
    @Test void evidenceCannotBeRelabeledToAnotherPrincipalWorldOrDomain() throws Exception {
        var f=new Fixture();try(var store=f.open(root)) {
            var evidence=f.capture();for(var caller:List.of(f.foreign,Fixture.context(UUID.randomUUID()),new TrustedContext(f.owner.principal(),new ScopeRef(f.world,UUID.randomUUID())))) {
                var denied=store.recordObservation(caller,evidence,()->false);assertEquals(Status.DENIED,denied.status());assertNull(denied.record());
            }
            assertEquals(0,store.privateRecordCount(f.owner));
        }
    }
    @Test void identicalCoordinatesRemainPrivateIncludingCountsAndDenials() throws Exception {
        var f=new Fixture();try(var store=f.open(root)) {
            var own=f.capture();store.recordObservation(f.owner,own,()->false);
            var before=store.queryFreshEvidence(f.foreign,own.target(),QueryLimits.defaults());assertTrue(before.evidence().isEmpty());assertEquals(0,before.scanned());
            f.physics.crops.put(f.physics.source,true);var foreign=f.capture(f.gateway,f.foreign,f.area,Operation.OBSERVE_SOURCE);
            store.recordObservation(f.foreign,foreign,()->false);
            assertEquals(1,f.query(store,own).evidence().size());assertEquals(Freshness.OBSERVED_ABSENT,f.query(store,own).freshness());
            assertEquals(1,f.query(store,foreign).evidence().size());assertEquals(1,store.privateRecordCount(f.foreign));
            var denied=store.invalidateObservation(f.foreign,only(f.query(store,own)).id(),Invalidation.TARGET_CHANGED,store.revision(),()->false);
            var missing=store.invalidateObservation(f.foreign,UUID.randomUUID(),Invalidation.TARGET_CHANGED,store.revision(),()->false);
            assertEquals(missing,denied);
        }
    }
    @Test void sharingRequiresCurrentPolicyAndExposesOnlySelectedRecordWithoutControl() throws Exception {
        var f=new Fixture();try(var store=f.open(root)) {
            var own=f.capture();var id=store.recordObservation(f.owner,own,()->false).record();
            var stock=f.capture(f.gateway,f.owner,f.area,Operation.OBSERVE_CONTAINER);store.recordObservation(f.owner,stock,()->false);
            assertEquals(Status.DENIED,store.shareObservation(f.owner,id,f.foreign,store.revision(),()->false).status());
            f.sharing.set(true);assertEquals(Status.SHARED,store.shareObservation(f.owner,id,f.foreign,store.revision(),()->false).status());
            assertEquals(1,store.queryFreshEvidence(f.foreign,own.target(),QueryLimits.defaults()).evidence().size());
            assertTrue(store.queryFreshEvidence(f.foreign,stock.target(),QueryLimits.defaults()).evidence().isEmpty());
            assertEquals(Status.DENIED,store.recordObservation(f.foreign,own,()->false).status());
            var shared=store.createEvidenceSnapshot(f.foreign,own.target(),QueryLimits.defaults());assertTrue(store.snapshotCurrent(shared,f.foreign));
            f.sharing.set(false);assertTrue(store.queryFreshEvidence(f.foreign,own.target(),QueryLimits.defaults()).evidence().isEmpty());
            assertFalse(store.snapshotCurrent(shared,f.foreign));
        }
    }
    @Test void recipientCannotSeeOtherSharingRecipients() throws Exception {
        var f=new Fixture();try(var store=f.open(root)) {
            var proof=f.capture();var id=store.recordObservation(f.owner,proof,()->false).record();f.sharing.set(true);
            store.shareObservation(f.owner,id,f.foreign,store.revision(),()->false);
            store.shareObservation(f.owner,id,Fixture.context(f.world),store.revision(),()->false);
            assertEquals(Set.of(f.foreign),only(store.queryFreshEvidence(f.foreign,proof.target(),QueryLimits.defaults())).sharedWith());
        }
    }
    @Test void expirySourceLossDimensionAndTargetIdentityRemainQualified() throws Exception {
        var f=new Fixture();try(var store=f.open(root)) {
            var proof=f.capture();store.recordObservation(f.owner,proof,()->false);f.sources.clear();
            assertEquals(Qualification.SOURCE_LOST,f.query(store,proof).evidence().getFirst().qualification());
            var other=new ObservationTarget(proof.target().kind(),new Cuboid("minecraft:the_nether",0,64,0,1,64,1),null);
            assertEquals(Freshness.UNKNOWN,store.queryFreshEvidence(f.owner,other,QueryLimits.defaults()).freshness());
            assertEquals(0,store.queryFreshEvidence(f.owner,other,QueryLimits.defaults()).scanned());
        }
    }
    @Test void indexesAreDisposableBoundedAndRejectStalePublication() throws Exception {
        var f=new Fixture();try(var store=f.open(root)) {
            var proof=f.capture();store.recordObservation(f.owner,proof,()->false);var prior=only(f.query(store,proof));long revision=store.revision();
            store.deleteDerivedIndexes();assertEquals(QueryStatus.INDEX_UNAVAILABLE,f.query(store,proof).status());
            var build=store.beginIndexRebuild();assertEquals(IndexStatus.INCOMPLETE,store.rebuildIndex(build,0).status());
            assertEquals(IndexStatus.READY,store.rebuildIndex(build,1).status());assertEquals(prior,only(f.query(store,proof)));assertEquals(revision,store.revision());
            var stale=store.beginIndexRebuild();store.invalidateObservation(f.owner,prior.id(),Invalidation.TARGET_CHANGED,store.revision(),()->false);
            assertEquals(IndexStatus.STALE,store.rebuildIndex(stale,16).status());assertEquals(Freshness.INVALID_TARGET,f.query(store,proof).freshness());
        }
    }
    @Test void immutableSnapshotsDetectRelevantChangesButNotUnrelatedPrivateActivity() throws Exception {
        var f=new Fixture();try(var store=f.open(root)) {
            var proof=f.capture();store.recordObservation(f.owner,proof,()->false);var snapshot=f.query(store,proof);
            var privateProof=f.capture(f.gateway,f.foreign,f.area,Operation.OBSERVE_SOURCE);store.recordObservation(f.foreign,privateProof,()->false);
            assertTrue(store.snapshotCurrent(snapshot,f.owner));
            var publicProof=only(snapshot).latest();assertEquals(proof.snapshot(),publicProof.observation());
            assertTrue(Arrays.stream(publicProof.getClass().getRecordComponents()).noneMatch(c->c.getName().equals("sequence")),
                    "Public provenance must not reveal the gateway's cross-scope issuance counter");
            assertFalse(publicProof.toString().contains("sequence="));
            store.invalidateObservation(f.owner,only(snapshot).id(),Invalidation.TARGET_CHANGED,store.revision(),()->false);
            assertFalse(store.snapshotCurrent(snapshot,f.owner));assertEquals(Freshness.OBSERVED_ABSENT,snapshot.freshness());
        }
    }
    @Test void queryAndContextLimitsNeverClaimExhaustiveResults() throws Exception {
        var f=new Fixture();try(var store=f.open(root)) {
            var proof=f.capture();store.recordObservation(f.owner,proof,()->false);f.physics.crops.put(f.physics.source,true);
            store.recordObservation(f.owner,f.capture(),()->false);
            for(var bounds:List.of(new QueryLimits(0,8,8192),new QueryLimits(128,0,8192),new QueryLimits(128,8,0),new QueryLimits(1,1,8192))) {
                var result=store.queryFreshEvidence(f.owner,proof.target(),bounds);assertFalse(result.complete());assertEquals(QueryStatus.LIMIT_REACHED,result.status());
                assertTrue(result.scanned()<=bounds.scanned());assertTrue(result.evidence().size()<=bounds.results());assertTrue(result.contextBytes()<=bounds.contextBytes());
            }
        }
    }
    @Test void storageAndHistoryBoundsRejectOverflowAndPreserveProtectedRecords() throws Exception {
        var f=new Fixture();try(var store=f.open(root,new Limits(2,2,MAX_BYTES,30000,604800000),p->{})) {
            var a=f.capture();store.recordObservation(f.owner,a,()->false);
            var pin=f.roots.publishNext("future_consumer",Set.of(only(f.query(store,a)).retentionKey()),true);assertEquals(RetentionRoots.Update.APPLIED,pin);
            f.physics.crops.put(f.physics.source,true);store.recordObservation(f.owner,f.capture(),()->false);
            f.physics.crops.clear();assertEquals(Status.LIMIT_REACHED,store.recordObservation(f.owner,f.capture(),()->false).status());
            assertEquals(2,store.privateRecordCount(f.owner));f.roots.publishNext("future_consumer",Set.of(),true);
            assertEquals(Status.RECORDED,store.recordObservation(f.owner,f.capture(),()->false).status());assertEquals(2,store.privateRecordCount(f.owner));
            var extra=f.capture(f.gateway,f.owner,f.area,Operation.OBSERVE_CONTAINER);
            assertEquals(Status.LIMIT_REACHED,store.recordObservation(f.owner,extra,()->false).status());
        }
    }
    @Test void ownerFileByteQuotaRejectsWithoutReplacingPriorData() throws Exception {
        var f=new Fixture();try(var store=f.open(root,new Limits(4,2,1024,30000,604800000),p->{})) {
            assertEquals(Status.LIMIT_REACHED,store.recordObservation(f.owner,f.capture(),()->false).status());
            assertEquals(0,store.privateRecordCount(f.owner));assertFalse(Files.exists(file(root,"state.json")));
        }
    }
    @Test void retentionCollectsExpiredUnpinnedRecordsAndProtectsActiveSnapshotReferences() throws Exception {
        var f=new Fixture();try(var store=f.open(root)) {
            var proof=f.capture();store.recordObservation(f.owner,proof,()->false);var row=only(f.query(store,proof));
            f.roots.publishNext("future_consumer",Set.of(row.retentionKey()),true);f.clock.now=row.retainUntil();
            try(var scan=f.manager.begin()) {while(scan.state()!=WorldRetentionManager.State.READY&&scan.state()!=WorldRetentionManager.State.INVALID)scan.step(()->false);assertEquals(WorldRetentionManager.State.READY,scan.state());assertEquals(0,scan.collect(()->false).collected());}
            assertEquals(1,store.privateRecordCount(f.owner));f.roots.publishNext("future_consumer",Set.of(),true);
            try(var scan=f.manager.begin()) {while(scan.state()!=WorldRetentionManager.State.READY&&scan.state()!=WorldRetentionManager.State.INVALID)scan.step(()->false);assertEquals(1,scan.collect(()->false).collected());}
            assertEquals(0,store.privateRecordCount(f.owner));assertEquals(Freshness.UNKNOWN,f.query(store,proof).freshness());
        }
    }
    @Test void changedOwnerRevisionInvalidatesRetentionProposal() throws Exception {
        var f=new Fixture();try(var store=f.open(root)) {
            var proof=f.capture();store.recordObservation(f.owner,proof,()->false);f.clock.now=only(f.query(store,proof)).retainUntil();
            try(var scan=f.manager.begin()) {while(scan.state()!=WorldRetentionManager.State.READY)scan.step(()->false);
                store.recordObservation(f.owner,f.capture(),()->false);assertEquals(0,scan.collect(()->false).collected());assertEquals(1,store.privateRecordCount(f.owner));}
        }
    }
    @Test void noModelsNoEffectsAndNoWorldAccessAreNeededAfterCapture() throws Exception {
        var f=new Fixture();try(var store=f.open(root)) {
            var proof=f.capture();int scans=f.physics.scans;f.physics.gameThread=false;
            assertEquals(Status.RECORDED,store.recordObservation(f.owner,proof,()->false).status());
            for(int i=0;i<100;i++)assertTrue(f.query(store,proof).complete());
            store.deleteDerivedIndexes();store.rebuildIndex(store.beginIndexRebuild(),16);
            assertEquals(scans,f.physics.scans);assertEquals(0,f.physics.moves+f.physics.harvests+f.physics.pickups+f.physics.transfers);
            assertEquals(Status.INVALIDATED,store.invalidateObservation(f.owner,only(f.query(store,proof)).id(),Invalidation.SOURCE_LOST,store.revision(),()->false).status());
        }
    }
    @Test void deniedCaptureAndGatewayThreadChecksCannotIssueEvidence() {
        var f=new Fixture();f.permission.set(false);assertThrows(AssertionError.class,f::capture);
        f.physics.gameThread=false;assertThrows(IllegalStateException.class,f::capture);
        assertTrue(Arrays.stream(ObservedEvidence.class.getDeclaredConstructors()).allMatch(c->java.lang.reflect.Modifier.isPrivate(c.getModifiers())));
    }
    @Test void delayedAuthorityRevocationIsRecheckedImmediatelyBeforeOwnerPublication() throws Exception {
        var f=new Fixture();try(var store=f.open(root,Limits.defaults(),p->{if(p==Point.BEFORE_REPLACE)f.permission.set(false);})) {
            var proof=f.capture();assertEquals(Status.DENIED,store.recordObservation(f.owner,proof,()->false).status());
            assertEquals(0,store.privateRecordCount(f.owner));assertFalse(Files.exists(file(root,"state.json")));
        }
    }
    @Test void serviceQueueAndWorkerSlicesAreBoundedAndShutdownDropsUnpublishedEvidence() throws Exception {
        var f=new Fixture();try(var store=f.open(root);var service=new ObservedKnowledgeService(store)) {
            var proof=f.capture();var tasks=new ArrayList<Runnable>();
            for(int tick=0;tick<4;tick++){service.beginTick();for(int i=0;i<8;i++)assertTrue(service.offer(proof));assertFalse(service.offer(proof));}
            service.beginTick();assertFalse(service.offer(proof));assertEquals(32,service.status().queued());
            service.maintain(tasks::add);service.maintain(tasks::add);assertEquals(1,tasks.size());tasks.removeFirst().run();
            assertEquals(28,service.status().queued());assertEquals(4,service.status().recorded());
            service.closeWorker();assertEquals(0,service.status().queued());assertEquals(33,service.status().dropped());
        }
    }
    @Test void staleQueuedEvidenceCannotPublishAfterAuthorityOrSourceLoss() throws Exception {
        var f=new Fixture();try(var store=f.open(root);var service=new ObservedKnowledgeService(store)) {
            var proof=f.capture();service.offer(proof);f.permission.set(false);service.cycle();assertEquals(1,service.status().rejected());
            f.permission.set(true);service.beginTick();service.offer(proof);f.sources.clear();service.cycle();assertEquals(2,service.status().rejected());assertEquals(0,store.privateRecordCount(f.owner));
        }
    }
    @Test void currentSchemaReopenPreservesIdentityProvenanceAgeAndPrivateSharing() throws Exception {
        var f=new Fixture();ObservedEvidence proof;RecordView expected;
        try(var store=f.open(root)) {proof=f.capture();store.recordObservation(f.owner,proof,()->false);f.sharing.set(true);store.shareObservation(f.owner,only(f.query(store,proof)).id(),f.foreign,store.revision(),()->false);expected=only(f.query(store,proof));}
        f.clock.now=expected.expiresAt();
        try(var store=ObservedWorldKnowledgeStore.open(root,f.world,Limits.defaults(),f.clock,(a,e,c)->true,(a,b)->f.sharing.get(),f.sources::contains,p->{})) {
            assertFalse(store.readOnly());assertEquals(expected,only(f.query(store,proof)));assertEquals(Freshness.STALE,f.query(store,proof).freshness());
            assertEquals(1,store.queryFreshEvidence(f.foreign,proof.target(),QueryLimits.defaults()).evidence().size());
        }
    }
    @Test void explicitSupportedMigrationPreservesExactOriginalAgeScopeAndIdentity() throws Exception {
        var f=new Fixture();ObservedEvidence proof;RecordView before;
        try(var store=f.open(root)) {proof=f.capture();store.recordObservation(f.owner,proof,()->false);before=only(f.query(store,proof));}
        rewrite(file(root,"state.json"),raw->{raw.put("schema",1L);for(Object item:(List<?>)raw.get("records"))((Map<?,?>)item).remove("invalidatedAt");});
        byte[] original=Files.readAllBytes(file(root,"state.json"));f.clock.now=before.expiresAt();
        var roots=new RetentionRoots();var manager=new WorldRetentionManager(WorldRetentionManager.Policy.defaults(),roots,c->false);
        try(var store=ObservedWorldKnowledgeStore.open(root,f.world,Limits.defaults(),f.clock,(a,e,c)->true,TrustedContext::equals,f.sources::contains,p->{})) {
            manager.register(store.retentionOwner());assertEquals(1,store.formatVersion());assertEquals(Freshness.INCOMPATIBLE,f.query(store,proof).freshness());
            assertEquals(Status.MIGRATED,store.migrate(store.revision(),()->false).status());assertEquals(SCHEMA,store.formatVersion());
            assertEquals(before,only(f.query(store,proof)));assertEquals(Freshness.STALE,f.query(store,proof).freshness());
            assertArrayEquals(original,Files.readAllBytes(file(root,"state.legacy-v1.json")));
        }
    }
    @ParameterizedTest @EnumSource(Point.class)
    void interruptedPublicationPreservesValidOldOrNewStateWithoutFabricatedAge(Point point) throws Exception {
        var f=new Fixture();var inject=new AtomicBoolean();ObservedEvidence before,after;
        try(var store=f.open(root,Limits.defaults(),p->{if(inject.get()&&p==point)throw new IOException("Injected boundary");})) {
            before=f.capture();store.recordObservation(f.owner,before,()->false);inject.set(true);f.physics.crops.put(f.physics.source,true);after=f.capture();
            var result=store.recordObservation(f.owner,after,()->false);assertEquals(Status.STORAGE_UNAVAILABLE,result.status());
            assertEquals(point==Point.AFTER_REPLACE,result.publicationMayHaveSucceeded());
        }
        try(var store=ObservedWorldKnowledgeStore.open(root,f.world,Limits.defaults(),f.clock,(a,e,c)->true,TrustedContext::equals,f.sources::contains,p->{})) {
            var query=f.query(store,before);assertTrue(query.complete());
            assertEquals(point==Point.AFTER_REPLACE?after.at():before.at(),query.evidence().getFirst().record().latest().at());
            assertFalse(store.readOnly());
        }
    }
    @Test void futureCorruptPartialAndMissingFieldFilesRemainIntactAndInactive() throws Exception {
        for(String mutation:List.of("future","partial","missing","checksum","world","freshness","retention")) {
            var f=new Fixture();Path path=root.resolve(mutation);ObservedEvidence proof;
            try(var store=f.open(path)) {proof=f.capture();store.recordObservation(f.owner,proof,()->false);store.recordObservation(f.owner,f.capture(),()->false);}
            if(mutation.equals("partial"))Files.writeString(file(path,"state.json"),"{\"schema\":2,");
            else if(mutation.equals("checksum"))Files.writeString(file(path,"state.json"),Files.readString(file(path,"state.json")).replace("\"count\":2","\"count\":3"));
            else rewrite(file(path,"state.json"),raw->{switch(mutation){case "future"->raw.put("schema",99L);case "missing"->raw.remove("records");case "world"->raw.put("world",UUID.randomUUID().toString());
                case "freshness","retention"->{var row=(Map<String,Object>)((List<?>)raw.get("records")).getFirst();row.put("retain",Long.MAX_VALUE);if(mutation.equals("freshness"))row.put("expires",Long.MAX_VALUE);}
            }});
            byte[] corrupt=Files.readAllBytes(file(path,"state.json"));
            try(var store=ObservedWorldKnowledgeStore.open(path,f.world,Limits.defaults(),f.clock,(a,e,c)->true,TrustedContext::equals,f.sources::contains,p->{})) {
                assertTrue(store.readOnly());assertEquals(Freshness.INCOMPATIBLE,f.query(store,proof).freshness());
                assertFalse(f.query(store,proof).complete());assertEquals(proof.at(),f.query(store,proof).evidence().getFirst().record().latest().at());
                assertEquals(Status.STORAGE_UNAVAILABLE,store.recordObservation(f.owner,f.capture(),()->false).status());
            }
            assertArrayEquals(corrupt,Files.readAllBytes(file(path,"state.json")));
        }
    }
    @Test void sparseOversizedAndBothCorruptGenerationsNeverAllocateUnboundedInput() throws Exception {
        var f=new Fixture();try(var store=f.open(root)){store.recordObservation(f.owner,f.capture(),()->false);}
        try(var channel=java.nio.channels.FileChannel.open(file(root,"state.json"),StandardOpenOption.WRITE)){channel.position((long)Integer.MAX_VALUE+32);channel.write(java.nio.ByteBuffer.wrap(new byte[]{1}));}
        try(var store=ObservedWorldKnowledgeStore.open(root,f.world,Limits.defaults(),f.clock,(a,e,c)->true,TrustedContext::equals,f.sources::contains,p->{})) {
            assertTrue(store.readOnly());assertEquals(0,store.privateRecordCount(f.owner));
        }
        Files.writeString(file(root,"state.prev.json"),"invalid");
        try(var store=ObservedWorldKnowledgeStore.open(root,f.world,Limits.defaults(),f.clock,(a,e,c)->true,TrustedContext::equals,f.sources::contains,p->{})) {assertTrue(store.readOnly());assertEquals(0,store.privateRecordCount(f.owner));}
    }
    @Test void cancellationLeavesValidOriginalAndUncertainEffectsAreNeverReplayed() throws Exception {
        var f=new Fixture();var inject=new AtomicBoolean();var cancelled=new AtomicBoolean();
        try(var store=f.open(root,Limits.defaults(),point->{if(inject.get()&&point==Point.AFTER_BACKUP)cancelled.set(true);})) {
            var proof=f.capture();store.recordObservation(f.owner,proof,()->false);byte[] original=Files.readAllBytes(file(root,"state.json"));
            assertEquals(Status.CANCELLED,store.recordObservation(f.owner,f.capture(),()->true).status());assertArrayEquals(original,Files.readAllBytes(file(root,"state.json")));
            f.physics.crops.put(f.physics.source,true);assertEquals(Status.RECORDED,store.recordObservation(f.owner,f.capture(),()->false).status());
            byte[] retained=Files.readAllBytes(file(root,"state.json"));long revision=store.revision();inject.set(true);
            assertEquals(Status.CANCELLED,store.recordObservation(f.owner,f.capture(),cancelled::get).status());
            assertArrayEquals(retained,Files.readAllBytes(file(root,"state.json")));assertEquals(revision,store.revision());assertFalse(store.readOnly());
            try(var scan=f.manager.begin()) {
                while(scan.state()!=WorldRetentionManager.State.READY&&scan.state()!=WorldRetentionManager.State.INVALID)scan.step(()->false);
                assertTrue(scan.usage().complete());assertEquals(4,scan.usage().owners().get("observations").records());
            }
            assertEquals(0,f.physics.harvests+f.physics.pickups+f.physics.transfers);
        }
    }
    @Test void writerExclusivityAndPolicyLimitConstructorsFailClosed() throws Exception {
        var f=new Fixture();try(var store=f.open(root)) {
            assertThrows(IOException.class,()->ObservedWorldKnowledgeStore.open(root,f.world,Limits.defaults(),f.clock,(a,e,c)->true,TrustedContext::equals,f.sources::contains,p->{}));
        }
        assertThrows(IllegalArgumentException.class,()->new Limits(129,4,MAX_BYTES,30000,604800000));
        assertThrows(IllegalArgumentException.class,()->new Limits(64,9,MAX_BYTES,30000,604800000));
        assertThrows(IllegalArgumentException.class,()->new QueryLimits(129,8,8192));
        assertThrows(IllegalArgumentException.class,()->new QueryLimits(128,17,8192));
        assertThrows(IllegalArgumentException.class,()->new QueryLimits(128,8,16385));
    }
    @Test void externalFileChangeCannotBeOverwrittenByAStaleOwnerPublication() throws Exception {
        var f=new Fixture();try(var store=f.open(root)) {
            var proof=f.capture();store.recordObservation(f.owner,proof,()->false);
            Files.writeString(file(root,"state.json"),"corrupt external replacement");
            var result=store.recordObservation(f.owner,f.capture(),()->false);
            assertEquals(Status.STORAGE_UNAVAILABLE,result.status());assertTrue(store.readOnly());
            assertEquals("corrupt external replacement",Files.readString(file(root,"state.json")));
            assertEquals(Freshness.INCOMPATIBLE,f.query(store,proof).freshness());
        }
        for(var boundary:List.of(Point.AFTER_STAGE,Point.BEFORE_REPLACE)) {
            var scoped=new Fixture();var inject=new AtomicBoolean();Path path=root.resolve(boundary.name());
            ObservedEvidence retained;byte[] original;
            try(var store=scoped.open(path,Limits.defaults(),point->{if(inject.get()&&point==boundary)Files.writeString(file(path,"state.json"),"corrupt during publication");})) {
                store.recordObservation(scoped.owner,scoped.capture(),()->false);retained=scoped.capture();store.recordObservation(scoped.owner,retained,()->false);
                original=Files.readAllBytes(file(path,"state.json"));inject.set(true);
                var result=store.recordObservation(scoped.owner,scoped.capture(),()->false);
                assertEquals(Status.STORAGE_UNAVAILABLE,result.status());assertFalse(result.publicationMayHaveSucceeded());
                assertEquals("corrupt during publication",Files.readString(file(path,"state.json")));
                assertArrayEquals(original,Files.readAllBytes(file(path,"state.prev.json")));
            }
            try(var recovered=ObservedWorldKnowledgeStore.open(path,scoped.world,Limits.defaults(),scoped.clock,(a,e,c)->true,TrustedContext::equals,scoped.sources::contains,p->{})) {
                var query=scoped.query(recovered,retained);assertFalse(query.complete());assertEquals(Freshness.INCOMPATIBLE,query.freshness());
                assertEquals(retained.snapshot(),query.evidence().getFirst().record().latest().observation());
                assertEquals(retained.at(),query.evidence().getFirst().record().latest().at());
            }
        }
    }
    @Test void unsupportedRecordSemanticsFailClosedEvenWithValidEnvelopeChecksum() throws Exception {
        var f=new Fixture();ObservedEvidence proof;
        try(var store=f.open(root)){proof=f.capture();store.recordObservation(f.owner,proof,()->false);store.recordObservation(f.owner,f.capture(),()->false);}
        rewrite(file(root,"state.json"),raw->{var row=(Map<?,?>)((List<?>)raw.get("records")).getFirst();row.remove("latest");});
        byte[] invalid=Files.readAllBytes(file(root,"state.json"));
        try(var store=ObservedWorldKnowledgeStore.open(root,f.world,Limits.defaults(),f.clock,(a,e,c)->true,TrustedContext::equals,f.sources::contains,p->{})) {
            assertTrue(store.readOnly());assertEquals(Freshness.INCOMPATIBLE,f.query(store,proof).freshness());
        }
        assertArrayEquals(invalid,Files.readAllBytes(file(root,"state.json")));
    }
    @Test void duplicateCountersSaturateAndRevisionOverflowReturnsExplicitLimit() throws Exception {
        var f=new Fixture();ObservedEvidence proof;
        try(var store=f.open(root)){proof=f.capture();store.recordObservation(f.owner,proof,()->false);}
        rewrite(file(root,"state.json"),raw->{var row=(Map<String,Object>)((List<?>)raw.get("records")).getFirst();row.put("count",Long.MAX_VALUE);});
        var manager=new WorldRetentionManager(WorldRetentionManager.Policy.defaults(),new RetentionRoots(),c->false);
        try(var store=ObservedWorldKnowledgeStore.open(root,f.world,Limits.defaults(),f.clock,(a,e,c)->true,TrustedContext::equals,f.sources::contains,p->{})) {
            manager.register(store.retentionOwner());assertEquals(Status.RECORDED,store.recordObservation(f.owner,f.capture(),()->false).status());assertEquals(Long.MAX_VALUE,only(f.query(store,proof)).count());
        }
        rewrite(file(root,"state.json"),raw->raw.put("revision",Long.MAX_VALUE));
        try(var store=ObservedWorldKnowledgeStore.open(root,f.world,Limits.defaults(),f.clock,(a,e,c)->true,TrustedContext::equals,f.sources::contains,p->{})) {
            var retention=new WorldRetentionManager(WorldRetentionManager.Policy.defaults(),new RetentionRoots(),c->false);
            var owner=store.retentionOwner();retention.register(owner);
            assertEquals(Status.LIMIT_REACHED,store.invalidateObservation(f.owner,only(f.query(store,proof)).id(),Invalidation.TARGET_CHANGED,store.revision(),()->false).status());
            assertEquals(Status.LIMIT_REACHED,store.recordObservation(f.owner,f.capture(),()->false).status());
            f.clock.now=only(f.query(store,proof)).retainUntil()+1;
            try(var cursor=owner.open()) {
                var entry=cursor.next(16,8192).entries().stream().filter(e->e.key().equals(only(f.query(store,proof)).retentionKey())).findFirst().orElseThrow();
                assertEquals(WorldRetentionManager.OwnerStatus.UNAVAILABLE,owner.collect(entry,Long.MAX_VALUE,()->false).status());
            }
            assertFalse(store.readOnly());assertEquals(Long.MAX_VALUE,store.revision());assertEquals(1,store.privateRecordCount(f.owner));
        }
        var late=new Fixture();late.clock.now=Long.MAX_VALUE-20_000;
        try(var store=late.open(root.resolve("clock-overflow"))) {
            assertEquals(Status.LIMIT_REACHED,store.recordObservation(late.owner,late.capture(),()->false).status());
            assertEquals(0,store.privateRecordCount(late.owner));assertFalse(store.readOnly());
        }
    }
    @Test void migrationQuotaRefusalDoesNotCreateAnArchiveOrRefreshAge() throws Exception {
        var f=new Fixture();ObservedEvidence proof;try(var store=f.open(root)){proof=f.capture();store.recordObservation(f.owner,proof,()->false);}
        rewrite(file(root,"state.json"),raw->{raw.put("schema",1L);for(Object item:(List<?>)raw.get("records"))((Map<?,?>)item).remove("invalidatedAt");});
        var categories=new EnumMap<WorldRetentionManager.Category,WorldRetentionManager.Quota>(WorldRetentionManager.Category.class);
        for(var category:WorldRetentionManager.Category.values())categories.put(category,new WorldRetentionManager.Quota(0,0));
        var manager=new WorldRetentionManager(new WorldRetentionManager.Policy(categories,new WorldRetentionManager.Quota(0,0),16,8192,65536,16,131072,16),new RetentionRoots(),c->false);
        try(var store=ObservedWorldKnowledgeStore.open(root,f.world,Limits.defaults(),f.clock,(a,e,c)->true,TrustedContext::equals,f.sources::contains,p->{})) {
            manager.register(store.retentionOwner());assertEquals(Status.LIMIT_REACHED,store.migrate(store.revision(),()->false).status());
            assertFalse(Files.exists(file(root,"state.legacy-v1.json")));assertEquals(proof.at(),only(f.query(store,proof)).latest().at());
        }
        var currentPolicy=new WorldRetentionManager(WorldRetentionManager.Policy.defaults(),new RetentionRoots(),c->false);
        try(var store=ObservedWorldKnowledgeStore.open(root,f.world,Limits.defaults(),f.clock,(a,e,c)->true,TrustedContext::equals,f.sources::contains,p->{})) {
            currentPolicy.register(store.retentionOwner());Files.writeString(file(root,"state.json"),"external legacy corruption");
            assertEquals(Status.STORAGE_UNAVAILABLE,store.migrate(store.revision(),()->false).status());assertTrue(store.readOnly());
            assertFalse(Files.exists(file(root,"state.legacy-v1.json")));assertEquals("external legacy corruption",Files.readString(file(root,"state.json")));
        }
    }
    @Test void unavailableCorruptStoreCannotPresentAnExhaustiveUnknownOrAbsence() throws Exception {
        var f=new Fixture();ObservedEvidence proof;try(var store=f.open(root)){proof=f.capture();store.recordObservation(f.owner,proof,()->false);}
        Files.writeString(file(root,"state.json"),"corrupt");
        try(var store=ObservedWorldKnowledgeStore.open(root,f.world,Limits.defaults(),f.clock,(a,e,c)->true,TrustedContext::equals,f.sources::contains,p->{})) {
            var query=f.query(store,proof);assertFalse(query.complete());assertEquals(QueryStatus.STORAGE_UNAVAILABLE,query.status());
            assertEquals(Freshness.INCOMPATIBLE,query.freshness());assertTrue(query.evidence().isEmpty());
        }
    }
    @Test void actorStockEvidenceIsBoundToObserverEntityAndNewPhysicalStockSupersedesIt() throws Exception {
        var f=new Fixture();try(var store=f.open(root)) {
            f.physics.actorWheat=7;var before=f.capture(f.gateway,f.owner,f.area,Operation.OBSERVE_INVENTORY);
            assertEquals(Status.RECORDED,store.recordObservation(f.owner,before,()->false).status());
            var row=only(f.query(store,before));assertEquals(f.actor.entityId(),row.target().entity());assertEquals(7,row.latest().observation().counts().get("wheat"));
            f.physics.actorWheat=0;var after=f.capture(f.gateway,f.owner,f.area,Operation.OBSERVE_INVENTORY);store.recordObservation(f.owner,after,()->false);
            var query=f.query(store,after);assertEquals(Freshness.CURRENTLY_VERIFIED,query.freshness());
            assertEquals(0,query.evidence().getFirst().record().latest().observation().counts().get("wheat"));
            assertEquals(Freshness.STALE,query.evidence().getLast().freshness());assertEquals(0,f.physics.transfers);
        }
    }
}
