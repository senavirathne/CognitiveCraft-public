package dev.aivillages.fabric;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.Outcomes.Reason;
import dev.aivillages.providers.LocalGenerationAdapter;
import dev.aivillages.providers.LocalNeedleAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** IMP-007 composition root. The legacy village session does not own kernel records. */
public final class KernelSession implements AutoCloseable {
    // Capture before composing a writer; tick/command reads never acquire its I/O monitor.
    private record Loaded(BootstrapJournal journal, BootstrapJournal.State initialState,
                          VersionedSkillRepository repository,
                          ResearchAdmissionController.DecisionGuard guard,
                          CitizenIdentityStore identities, CitizenRegistry.Snapshot initialIdentities,
                          boolean identityReadOnly, CapabilityRetrievalIndex retrieval,
                          JobJournal jobs, Jobs.Snapshot initialJobs, boolean jobsReadOnly,
                          ResourceLeaseJournal leases, ResourceLeases.Snapshot initialLeases,
                          boolean leasesReadOnly, RetentionEvidenceStore evidence,
                          RetentionRoots roots, WorldRetentionManager retention,
                          WorldRetentionService retentionService, ObservedWorldKnowledgeStore observations,
                          ObservedKnowledgeService knowledgeService, PlanJournal plans, PlanJournal.Snapshot initialPlans,
                          BlueprintStore blueprints, AccomplishmentLedger accomplishments, KnowledgeAccessStore access,
                          OrganizationJournal organizations,
                          Organizations.Snapshot initialOrganizations, boolean organizationsReadOnly,
                          MandateJournal mandates, Mandates.Snapshot initialMandates, boolean mandatesReadOnly,
                          dev.aivillages.core.kernel.runtime.persistence.ArchiveWriter history) { }

    private final MinecraftServer server;
    private final Clock clock = Clock.systemUTC();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(run -> {
        Thread thread = new Thread(run, "cognitivecraft-world-storage");
        thread.setDaemon(true);
        return thread;
    });
    private final CompletableFuture<Loaded> opening;
    private final BlueprintValidator.Registry blueprintRegistry;
    private final java.util.concurrent.atomic.AtomicInteger blueprintQueries=new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean blueprintClosing=new java.util.concurrent.atomic.AtomicBoolean();
    private record PreparedBlueprint(BlueprintStore.Inspection inspection,Blueprint.View view) { }
    private final Map<UUID,PreparedBlueprint> blueprintStages=new HashMap<>();
    private FabricBlueprintMaterials blueprintMaterials;
    private final java.util.concurrent.atomic.AtomicInteger accomplishmentQueries=new java.util.concurrent.atomic.AtomicInteger();
    private AccomplishmentService accomplishmentService;
    private KnowledgeAccessService accessService;
    private final java.util.concurrent.atomic.AtomicInteger accessQueries=new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.ConcurrentHashMap<UUID,KnowledgeAccess.Pair> learningStarts=new java.util.concurrent.ConcurrentHashMap<>();
    private volatile UUID observationSource;
    private final java.util.concurrent.atomic.AtomicInteger observationQueries = new java.util.concurrent.atomic.AtomicInteger();
    private final LocalGenerationAdapter model;
    private final GenerationPort generationSource;
    private final boolean generationConfigured;
    private final PrimitiveDiagnostics.Sink primitiveDiagnosticSink;
    private final PrimitiveDiagnosticService primitiveDiagnostics;
    private AIWorkBroker broker;
    private final LocalNeedleAdapter needle;
    private final LanguageRequests.Port languagePort;
    private LanguageRequests language;
    private ServerPlayer interpretingPlayer;
    private Loaded loaded;
    private BootstrapController controller;
    private SurvivalGateway gateway;
    private FabricGatewayWorld worldAccess;
    private CitizenRegistry citizens;
    private JobLifecycleStore jobs;
    private OrganizationRegistry organizations;
    private MandateRegistry mandates;
    private FabricPrerequisiteRuntime prerequisites;
    private GoalPlanner planner;
    private ResourceLeaseService leases;
    private LeasedGateway leasedGateway;
    private LeasedGateway dispatchGateway;
    private WorkerDispatcher dispatcher;
    private dev.aivillages.core.kernel.runtime.RecoveredJobOwners recoveredOwners;
    private dev.aivillages.core.kernel.runtime.persistence.ArchiveService historyService;
    private LeaseArchive leaseArchive;
    private final boolean leaseHistoryEnabled = "true".equalsIgnoreCase(
            System.getenv().getOrDefault("COGNITIVECRAFT_LEASE_HISTORY", "false"));
    private long historyReferenceBase;
    private boolean historyGenerationUnavailable;
    private final boolean runtimeHistoryEnabled = "true".equalsIgnoreCase(
            System.getenv().getOrDefault("COGNITIVECRAFT_RUNTIME_HISTORY", "false"));
    private final boolean activityViewEnabled = "true".equalsIgnoreCase(
            System.getenv().getOrDefault("COGNITIVECRAFT_ACTIVITY_VIEW", "false"));
    private final Map<String, java.util.Set<WorldRetentionManager.Key>> desiredRoots = new LinkedHashMap<>();
    private List<TrustedContext> dispatchScopes=List.of();
    private long dispatchRevision=-1;
    private int dispatchScopeCursor;
    private CitizenLifecycle lifecycle;
    private FabricCitizenLifecycle lifecycleAdapter;
    private UUID pendingEnrollmentEntity;
    private boolean closed;
    private boolean closeRequested;
    private final CompletableFuture<Void> storeClosure = new CompletableFuture<>();

    public KernelSession(MinecraftServer server) {
        this(server, server.getWorldPath(LevelResource.ROOT));
    }

    /** Isolated-world seam used by physical integration fixtures; runtime owners are unchanged. */
    KernelSession(MinecraftServer server, Path world) {
        this(server, world, null);
    }

    /** Deterministic interpretation fixtures reuse the production binding and controller owners. */
    KernelSession(MinecraftServer server, Path world, LanguageRequests.Port injectedLanguage) {
        this(server, world, injectedLanguage, null);
    }
    /** Controlled generation fixture retains the production broker/research composition. */
    KernelSession(MinecraftServer server, Path world, LanguageRequests.Port injectedLanguage,
                  GenerationPort injectedGeneration) {
        this(server, world, injectedLanguage, injectedGeneration, null);
    }

    /** Trusted observer seam; production configuration is startup-only and defaults off. */
    KernelSession(MinecraftServer server, Path world, LanguageRequests.Port injectedLanguage,
                  GenerationPort injectedGeneration, PrimitiveDiagnostics.Sink primitiveDiagnosticSink) {
        this(server,world,injectedLanguage,injectedGeneration,primitiveDiagnosticSink,null,
                PrimitiveDiagnosticStore.Faults.none());
    }
    /** Isolated real-store/fault fixtures retain the production composition and worker owners. */
    KernelSession(MinecraftServer server,Path suppliedWorld,LanguageRequests.Port injectedLanguage,
                  GenerationPort injectedGeneration,PrimitiveDiagnostics.Sink injectedSink,
                  PrimitiveDiagnostics.Policy diagnosticPolicy,PrimitiveDiagnosticStore.Faults faults) {
        final Path world = suppliedWorld.toAbsolutePath().normalize();
        this.server = server;
        blueprintRegistry=FabricBlueprintRegistry.capture();
        var configuration=diagnosticPolicy==null?PrimitiveDiagnosticConfig.read(System.getenv())
                :new PrimitiveDiagnosticConfig.Configuration(diagnosticPolicy,true);
        if(!configuration.valid())AiVillages.LOG.warn("CognitiveCraft primitive diagnostic configuration disabled; gameplay continues.");
        primitiveDiagnostics=injectedSink==null?PrimitiveDiagnosticService.open(world,configuration.policy(),clock,
                message->AiVillages.LOG.warn(message),faults):null;
        this.primitiveDiagnosticSink=injectedSink==null?primitiveDiagnostics:injectedSink;
        String configured = System.getenv("COGNITIVECRAFT_OLLAMA_MODEL");
        model = injectedGeneration != null || configured == null || configured.isBlank() ? null
                : new LocalGenerationAdapter(new LocalGenerationAdapter.Config(
                        URI.create("http://127.0.0.1:11434/api/chat"), configured, true, 3_072));
        generationConfigured = injectedGeneration != null || model != null;
        generationSource = injectedGeneration != null ? injectedGeneration : model != null ? model : new GenerationPort() {
            @Override public Generation.Handle generate(Generation.Request request, Budgets.InferenceLimits limits,
                                                         Budgets.Ledger usage) { throw new IllegalStateException("No local model configured"); }
            @Override public Generation.Descriptor descriptor() { return new Generation.Descriptor("ollama-chat-v1", "unconfigured-local", null); }
            @Override public Generation.Status status() { return new Generation.Status(Generation.State.UNAVAILABLE, 0, null, Generation.Compute.NOT_STARTED, false, false); }
        };
        String needleDir = System.getenv("COGNITIVECRAFT_NEEDLE_DIR");
        needle = needleDir == null || needleDir.isBlank() ? null : new LocalNeedleAdapter(
                new LocalNeedleAdapter.Config(Path.of(needleDir).resolve("needle"),
                        Path.of(needleDir).resolve("needle3.cact")));
        languagePort = injectedLanguage == null ? needle : injectedLanguage;
        CapabilityCatalog capabilities = PlanningGoals.catalog();
        opening = CompletableFuture.supplyAsync(() -> {
            BootstrapJournal journal = null;
            JobJournal jobs = null;
            ResourceLeaseJournal leases = null;
            CitizenIdentityStore identities = null;
            VersionedSkillRepository repository = null;
            CapabilityRetrievalIndex retrieval = null;
            RetentionEvidenceStore evidence = null;
            ObservedWorldKnowledgeStore observations = null;
            PlanJournal plans = null;
            BlueprintStore blueprints = null;
            AccomplishmentLedger accomplishments = null;
            KnowledgeAccessStore access = null;
            OrganizationJournal organizations = null;
            MandateJournal mandates = null;
            dev.aivillages.core.kernel.runtime.persistence.ArchiveWriter history = null;
            try {
                journal = BootstrapJournal.open(world);
                identities = CitizenIdentityStore.open(world, journal.state().worldId());
                var migration = SaveCompatibility.migrateAtStartup(journal, identities);
                if (!migration.complete())
                    throw new IllegalStateException("Bootstrap compatibility migration: " + migration.status());
                jobs = JobJournal.open(world, journal.state().worldId());
                leases = ResourceLeaseJournal.open(world, journal.state().worldId());
                plans = PlanJournal.open(world,journal.state().worldId());
                var guard = new ResearchAdmissionController.DecisionGuard();
                repository = VersionedSkillRepository.open(world,
                        new VersionedSkillRepository.RuntimeSnapshot("minecraft-26.3",
                                capabilities, GatewayPrimitives.instance()),
                        VersionedSkillRepository.Limits.defaults(), guard,
                        TrustedContext::equals, VersionedSkillRepository.FaultInjector.none());
                var lookups = new CapabilityRetrievalIndex.LookupRegistry(
                        Map.ofEntries(Map.entry("collect birch logs",BirchLogDelivery.ID),Map.entry("deliver birch logs",BirchLogDelivery.ID),Map.entry("make birch planks",BirchPlankProduction.ID),Map.entry("produce birch planks",BirchPlankProduction.ID),Map.entry("harvest wheat",CropDelivery.ID),Map.entry("deliver wheat",CropDelivery.ID),Map.entry("collect oak logs",OakLogDelivery.ID),Map.entry("deliver oak logs",OakLogDelivery.ID),Map.entry("make oak planks",OakPlankProduction.ID),Map.entry("produce oak planks",OakPlankProduction.ID),Map.entry("make sticks",StickProduction.ID),Map.entry("produce sticks",StickProduction.ID),Map.entry("make wooden axes",WoodenAxeProduction.ID),Map.entry("produce wooden axes",WoodenAxeProduction.ID),Map.entry("place crafting table",CraftingTablePlacement.ID),Map.entry("make crafting tables",CraftingTableProduction.ID),Map.entry("produce crafting tables",CraftingTableProduction.ID)),
                        Map.of(CropDelivery.ID,java.util.Set.of("farming","wheat"),
                                BirchLogDelivery.ID,java.util.Set.of("gathering","birch","logs"),BirchPlankProduction.ID,java.util.Set.of("crafting","birch","planks"),
                                OakLogDelivery.ID,java.util.Set.of("gathering","oak","logs"),
                                OakPlankProduction.ID,java.util.Set.of("crafting","oak","planks"),StickProduction.ID,java.util.Set.of("crafting","oak","sticks"),WoodenAxeProduction.ID,java.util.Set.of("crafting","wooden","axes"),CraftingTableProduction.ID,java.util.Set.of("crafting","oak","tables"),CraftingTablePlacement.ID,java.util.Set.of("placement","table")),capabilities);
                retrieval = new CapabilityRetrievalIndex(world.resolve(CapabilityRetrievalIndex.WORLD_RELATIVE_PATH),
                        new CapabilityRetrievalIndex.Identity(journal.state().worldId(), UUID.randomUUID()),
                        CapabilityRetrievalIndex.repositorySource(repository, lookups, () -> 0), lookups,
                        capabilities, CapabilityRetrievalIndex.Limits.defaults(), () -> System.nanoTime() / 1_000_000);
                evidence = RetentionEvidenceStore.open(world, journal.state().worldId(), RetentionEvidenceStore.Limits.defaults(), clock, RetentionEvidenceStore.Faults.none());
                var roots = new RetentionRoots();
                var retention = new WorldRetentionManager(WorldRetentionManager.Policy.defaults(), roots, caller -> false);
                retention.register(repository.retentionOwner()); retention.register(evidence.retentionOwner());
                final var observationIdentities = identities;
                observations = ObservedWorldKnowledgeStore.open(world, journal.state().worldId(),
                        ObservedWorldKnowledgeStore.Limits.defaults(), clock,
                        (actor, effect, context) -> effect == Effect.OBSERVE && observationIdentities.snapshot().citizens().stream()
                                .anyMatch(c -> c.actor().equals(actor) && c.owner().equals(context)),
                        TrustedContext::equals, source -> source.equals(observationSource), point -> { });
                retention.register(observations.retentionOwner());
                retention.register(plans.retentionOwner());
                blueprints=BlueprintStore.open(world,journal.state().worldId());
                retention.register(blueprints.retentionOwner());
                final var accomplishmentJobs=jobs;
                final var accomplishmentCitizens=identities;
                accomplishments=AccomplishmentLedger.open(world,journal.state().worldId(),
                        new JobOutcomeSource(accomplishmentJobs::snapshot,accomplishmentCitizens::snapshot),roots);
                retention.register(accomplishments.retentionOwner());
                accomplishments.capacity(amount->retention.assessReplacement("accomplishments",WorldRetentionManager.Category.EVIDENCE,amount)
                        .status()==WorldRetentionManager.Capacity.AVAILABLE);
                final var learningEvidence=accomplishments;
                access=KnowledgeAccessStore.openUnmigrated(world,journal.state().worldId(),
                        new KnowledgeAccessSource(accomplishmentJobs::snapshot,accomplishmentCitizens::snapshot,learningEvidence::snapshot),
                        KnowledgeAccessStore.repositoryCatalog(repository),clock,roots);
                retention.register(access.retentionOwner());
                access.capacity(amount->retention.assessReplacement("knowledge-access",WorldRetentionManager.Category.EVIDENCE,amount)
                        .status()==WorldRetentionManager.Capacity.AVAILABLE);
                organizations=OrganizationJournal.open(world,journal.state().worldId());
                retention.register(organizations.retentionOwner());
                mandates=MandateJournal.open(world,journal.state().worldId());
                retention.register(mandates.retentionOwner());
                plans.capacity(bytes -> retention.assessReplacement("plans",WorldRetentionManager.Category.EVIDENCE,
                        new WorldRetentionManager.Amount(bytes*3L,24)).status()==WorldRetentionManager.Capacity.AVAILABLE);
                var knowledgeService = new ObservedKnowledgeService(observations);
                final var bootstrapOwner = journal; final var jobOwner = jobs; final var identityOwner = identities; final var leaseOwner = leases;
                retention.register(new RetentionFileOwner("bootstrap", world.resolve(BootstrapJournal.WORLD_RELATIVE_PATH), WorldRetentionManager.Category.RUNS,
                        () -> bootstrapOwner.state().revision(), () -> bootstrapOwner.state().runs().size(), 8));
                retention.register(new RetentionFileOwner("jobs", world.resolve(JobJournal.WORLD_RELATIVE_PATH), WorldRetentionManager.Category.JOBS,
                        () -> jobOwner.snapshot().revision(), () -> jobOwner.snapshot().jobs().size() + jobOwner.snapshot().allocations().size(), 8));
                retention.register(new RetentionFileOwner("citizens", world.resolve(CitizenIdentityStore.WORLD_RELATIVE_PATH), WorldRetentionManager.Category.IDENTITIES,
                        () -> identityOwner.snapshot().revision(), () -> identityOwner.snapshot().citizens().size(), 8));
                retention.register(new RetentionFileOwner("leases", world.resolve(ResourceLeaseJournal.WORLD_RELATIVE_PATH), WorldRetentionManager.Category.LEASES,
                        () -> leaseOwner.snapshot().revision(), () -> leaseOwner.snapshot().leases().size(), 8));
                retention.register(new RetentionFileOwner("diagnostics", world.resolve(PrimitiveDiagnosticStore.RELATIVE_ROOT), WorldRetentionManager.Category.DIAGNOSTICS,
                        () -> 0, () -> 0, 16, 10_485_760));
                retention.register(new RetentionFileOwner("retrieval", world.resolve(CapabilityRetrievalIndex.WORLD_RELATIVE_PATH), WorldRetentionManager.Category.CACHE,
                        () -> 0, () -> 0, 16));
                if (runtimeHistoryEnabled) {
                    history = dev.aivillages.core.kernel.runtime.persistence.ArchiveWriter.open(world,journal.state().worldId(),
                            dev.aivillages.core.kernel.runtime.persistence.PersistencePolicy.defaults());
                    retention.register(history.retentionOwner());
                }
                var accessMigration=access.migrateSharedAccess(()->false);
                if(!access.readOnly()&&accessMigration.code()!=KnowledgeAccess.Code.APPLIED&&accessMigration.code()!=KnowledgeAccess.Code.DUPLICATE)
                    throw new IllegalStateException("Shared access migration unavailable: "+accessMigration);
                var observationMigration = observations.migrate(observations.revision(), () -> false);
                if (observationMigration.status() != ObservedWorldKnowledgeStore.Status.UNCHANGED
                        && observationMigration.status() != ObservedWorldKnowledgeStore.Status.MIGRATED
                        && !observations.readOnly()) throw new IllegalStateException("Observation migration unavailable");
                repository.admissionCapacity((bytes, count) -> retention.assessReplacement("skills", WorldRetentionManager.Category.ARTIFACTS,
                        new WorldRetentionManager.Amount(bytes, count)).status());
                var retentionService = new WorldRetentionService(retention, evidence);
                return new Loaded(journal, journal.state(), repository, guard, identities,
                        identities.snapshot(), identities.readOnly() || journal.readOnly(), retrieval,
                        jobs, jobs.snapshot(), jobs.readOnly(), leases, leases.snapshot(), leases.readOnly(), evidence, roots, retention,
                        retentionService, observations, knowledgeService, plans, plans.snapshot(),blueprints,accomplishments,access,
                        organizations, organizations.snapshot(), organizations.readOnly(),
                        mandates, mandates.snapshot(), mandates.readOnly(), history);
            } catch (Exception failure) {
                if(history!=null)try{history.close();}catch(Exception closeFailure){failure.addSuppressed(closeFailure);}
                if(access!=null)try{access.close();}catch(Exception closeFailure){failure.addSuppressed(closeFailure);}
                if(accomplishments!=null)try{accomplishments.close();}catch(Exception closeFailure){failure.addSuppressed(closeFailure);}
                if(mandates!=null)try{mandates.close();}catch(Exception closeFailure){failure.addSuppressed(closeFailure);}
                if(organizations!=null)try{organizations.close();}catch(Exception closeFailure){failure.addSuppressed(closeFailure);}
                if(blueprints!=null)try{blueprints.close();}catch(Exception closeFailure){failure.addSuppressed(closeFailure);}
                if(plans!=null)try{plans.close();}catch(Exception closeFailure){failure.addSuppressed(closeFailure);}
                if (observations != null) try { observations.close(); } catch (Exception closeFailure) { failure.addSuppressed(closeFailure); }
                if (evidence != null) try { evidence.close(); } catch (Exception closeFailure) { failure.addSuppressed(closeFailure); }
                if (retrieval != null) retrieval.close();
                if (repository != null) try { repository.close(); }
                catch (Exception closeFailure) { failure.addSuppressed(closeFailure); }
                if (jobs != null) try { jobs.close(); }
                catch (Exception closeFailure) { failure.addSuppressed(closeFailure); }
                if (leases != null) try { leases.close(); }
                catch (Exception closeFailure) { failure.addSuppressed(closeFailure); }
                if (identities != null) try { identities.close(); }
                catch (Exception closeFailure) { failure.addSuppressed(closeFailure); }
                if (journal != null) try { journal.close(); }
                catch (Exception closeFailure) { failure.addSuppressed(closeFailure); }
                throw new IllegalStateException("Cannot open kernel world data", failure);
            }
        }, worker);
    }

    public void tick() {
        if (closed) return;
        if(primitiveDiagnostics!=null)primitiveDiagnostics.beginTick();
        if (controller == null) {
            if (!opening.isDone()) return;
            if (opening.isCompletedExceptionally()) {
                closed = true;
                AiVillages.LOG.error("CognitiveCraft kernel disabled", opening.handle(
                        (ignored, failure) -> failure).join());
                if (model != null) model.close();
                if (needle != null) needle.close();
                if(primitiveDiagnostics!=null)primitiveDiagnostics.close();
                worker.shutdown();
                return;
            }
            loaded = opening.join();
            if(loaded.history()!=null) {
                historyReferenceBase=loaded.history().snapshot().retentionGeneration();
                historyService=new dev.aivillages.core.kernel.runtime.persistence.ArchiveService(loaded.history(),worker);
            }
            compose();
        }
        citizens.tick();
        refreshLearningPresence(null);
        accomplishmentService.maintain(jobs.snapshot(),worker);
        accessService.maintain(jobs.snapshot(),worker);
        organizations.tick();
        organizations.expire();
        mandates.maintain(server.overworld().getGameTime());
        loaded.knowledgeService().beginTick();
        loaded.knowledgeService().maintain(worker);
        loaded.retentionService().beginTick();
        refreshRetentionRoots();
        if(loaded.history()!=null)historyGeneration();
        loaded.retentionService().maintain(worker);
        loaded.retrieval().maintain(worker);
        lifecycleAdapter.step();
        if (pendingEnrollmentEntity != null && citizens.ready()) pendingEnrollmentEntity = null;
        language.tick();
        if (!language.pending()) interpretingPlayer = null;
        gateway.tick();
        leases.tick();
        if(leaseArchive!=null) {
            var snapshot=jobs.snapshot();
            var settled=snapshot.jobs().stream().filter(j->(j.state()==Jobs.State.SUCCEEDED
                    || j.state()==Jobs.State.FAILED || j.state()==Jobs.State.CANCELLED)
                    && j.attempts().stream().noneMatch(Jobs.Attempt::open)).map(Jobs.Job::id)
                    .collect(java.util.stream.Collectors.toSet());
            leaseArchive.step(new LeaseArchive.Closure(snapshot.revision(),leases.snapshot().revision(),historyGeneration(),settled,
                    jobs.ready() && loaded.roots().snapshot().complete() && !historyGenerationUnavailable));
        }
        leasedGateway.tick();
        broker.step();
        controller.tick();
        planner.tick();
        prerequisites.tick();
        dispatchGateway.tick();
        recoveredOwners.step();
        if(dispatchRevision!=jobs.snapshot().revision()) {
            dispatchScopes=jobs.snapshot().jobs().stream().filter(j->!controller.ownsSubmission(j.submissionId()))
                    .map(Jobs.Job::origin).distinct().sorted(java.util.Comparator.comparing(c->c.principal().id().toString()
                            +":"+c.scope().domainId())).toList();
            dispatchRevision=jobs.snapshot().revision();
        }
        if(!dispatchScopes.isEmpty()) {
            dispatchScopeCursor%=dispatchScopes.size();
            dispatcher.step(dispatchScopes.get(dispatchScopeCursor));
            dispatchScopeCursor=(dispatchScopeCursor+1)%dispatchScopes.size();
        }
    }

    private static PrimitiveCatalog diagnosticPrimitives() {
        var runtime = GatewayPrimitives.instance();
        try { return PrimitiveDiagnostics.completeCatalog(runtime.all()); }
        catch (IllegalArgumentException unavailableMetadata) {
            // Diagnostic bounds/metadata can disable observation, never runtime registration.
            return runtime;
        }
    }

    private void compose() {
        citizens = new CitizenRegistry(loaded.initialIdentities(), (expected, next) ->
                CompletableFuture.supplyAsync(() -> {
                    try { return loaded.identities().replace(expected, next); }
                    catch (Exception failed) { throw new IllegalStateException(failed); }
                }, worker), CitizenRegistry.privateAddresses(), clock, loaded.identityReadOnly());
        CapabilityCatalog capabilities = PlanningGoals.catalog();
        var primitives = diagnosticPrimitives();
        var repository = loaded.repository();
        var staging = new ResearchAdmissionController.TrialArtifacts(repository);
        var grants = new ResearchAdmissionController.TrialGrants(clock);
        var access=loaded.access();
        AuthorityPolicy direct = (actor, effect, context) -> controls(actor, context) && citizens.available(actor)
                && villager(actor) != null;
        mandates = new MandateRegistry(loaded.initialMandates(), (expected, next) ->
                CompletableFuture.supplyAsync(() -> {
                    try { return loaded.mandates().replace(expected, next); }
                    catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
                }, worker), new MandateRegistry.References() {
                    public boolean authority(ActorRef actor,Effect effect,TrustedContext issuer) { return direct.currentlyAllows(actor,effect,issuer); }
                    public boolean available(TrustedContext coordinator) {
                        return server.getPlayerList().getPlayer(coordinator.principal().id()) != null;
                    }
                    public boolean organization(UUID reference,TrustedContext issuer) {
                        if(reference==null)return true;
                        return organizations != null && organizations.snapshot().scopes().stream()
                                .anyMatch(s -> s.id().equals(reference) && s.owner().equals(issuer) && !s.expired()
                                        && (s.expiresAt()==0 || clock.millis()<s.expiresAt()));
                    }
                }, new MandateJobExecutions(() -> jobs), clock, Mandates.Limits.defaults(), loaded.mandatesReadOnly());
        mandates.retentionGuard((keys, complete) -> {
            var update=loaded.roots().publishNext("mandates",keys,complete);
            return update==RetentionRoots.Update.APPLIED || update==RetentionRoots.Update.UNCHANGED;
        });
        CapabilityResolver.ControlPolicy control = (actor,context) -> controls(actor,context)
                || mandates.controls(actor,Effect.OBSERVE,context);
        AuthorityPolicy authority = new MandateAuthority(direct,mandates);
        BoundedSkillExecutor.ControlPolicy runControl = new BoundedSkillExecutor.ControlPolicy() {
            @Override public boolean mayInspect(TrustedContext caller, TrustedContext owner,
                                                UUID runId) { return owner.equals(caller); }
            @Override public boolean mayCancel(TrustedContext caller, TrustedContext owner,
                                               UUID runId) { return owner.equals(caller); }
        };
        // The gateway owns actor control; one world-wide service owns resource coordination.
        worldAccess = new FabricGatewayWorld(server.overworld());
        gateway = new SurvivalGateway(worldAccess, authority,
                new SurvivalGateway.Limits(8, 512, 64, 16, 256, 1, 200, 32), clock);
        observationSource = gateway.observationSource();
        gateway.observationSink(loaded.knowledgeService()::offer);
        leases = new ResourceLeaseService(loaded.initialLeases(), (expected, next) ->
                CompletableFuture.supplyAsync(() -> {
                    try { return loaded.leases().replace(expected, next); }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                }, worker), new ResourceLeaseService.Owners() {
                    public ResourceLeases.OwnerFacts inspect(ResourceLeases.Owner owner,TrustedContext caller) {
                        return new JobLeaseOwners(jobs,clock).inspect(owner,caller);
                    }
                    public boolean stopPending(ResourceLeases.Owner owner,TrustedContext caller) {
                        return new JobLeaseOwners(jobs,clock).stopPending(owner,caller);
                    }
                },
                new FabricResourceLeaseWorld(server), ResourceLeases.Settings.production(),
                () -> server.overworld().getGameTime(), clock, loaded.leasesReadOnly());
        if(leaseHistoryEnabled) {
            if(loaded.history()==null)throw new IllegalStateException("Lease history requires runtime history");
            leaseArchive=new LeaseArchive(leases,loaded.history(),historyService,
                    dev.aivillages.core.kernel.runtime.persistence.PersistencePolicy.defaults());
        }
        leasedGateway = new LeasedGateway(gateway, leases,
                (request, run) -> LogAcquisition.is(request.request().capability())
                        ? LeasedGateway.cropWorkspaces(new JobLeaseOwners(jobs,clock),request,run)
                        : LeasedGateway.workspaces(new JobLeaseOwners(jobs, clock), request, run),
                () -> server.overworld().getGameTime(), 1200, 200, 64, 512);
        leasedGateway.admission(LeasedGateway.mandates(mandates,direct));
        var executor = new BoundedSkillExecutor(staging, capabilities, primitives,
                access, grants, runControl, leasedGateway,
                leasedGateway::releaseRun, clock, () -> server.overworld().getGameTime(),
                new BoundedSkillExecutor.Settings(8, 16, 32, 64));
        var resolver = new CapabilityResolver.Engine(capabilities, primitives,
                loaded.retrieval().candidates(), loaded.retrieval().authoritativeFallback(),
                PlanningGoals.support(), control,
                access, authority,
                CapabilityResolver.cropPrerequisites(),
                () -> server.overworld().getGameTime(), CapabilityResolver.Limits.defaults());
        broker = new AIWorkBroker(generationSource, clock, AIWorkBroker.Settings.defaults(),
                AIWorkBroker.sessionLimits(clock.millis()), request -> {
                    var actor = ((ActorValue)request.bound().request().arguments().get("actor")).value();
                    return control.controls(actor, request.bound().context())
                            && (request.role().planning()
                                ? CropDelivery.SPEC.effects()
                                : ResourceDelivery.spec(request.bound().request().capability()).orElseThrow().effects()).stream()
                            .allMatch(effect -> authority.currentlyAllows(actor, effect, request.bound().context()));
                }, AIWorkBroker.Sharing.privateScopes());
        var research = new ResearchAdmissionController(capabilities, primitives, repository,
                broker, new CropFixtureRunner(staging, capabilities, primitives,
                        access, worker, clock),
                new ResearchAdmissionController.TrialPort() {
                    final ResearchAdmissionController.ExecutorTrialPort delegate =
                            new ResearchAdmissionController.ExecutorTrialPort(executor);
                    @Override public boolean prepare(ValidatedRequest request, RunCorrelation correlation,
                                                     List<ArtifactRef> pinned) {
                        return controller.prepareTrial(request, correlation, pinned);
                    }
                    @Override public BoundedSkillExecutor.Start start(ValidatedRequest request, ArtifactRef ref,
                            RunCorrelation correlation, Budgets.ExecutionLimits limits, Budgets.Ledger usage,
                            BoundedSkillExecutor.TrialPermit permit) {
                        return delegate.start(request, ref, correlation, limits, usage, permit);
                    }
                    @Override public BoundedSkillExecutor.Progress tick(TrustedContext owner) { return delegate.tick(owner); }
                    @Override public BoundedSkillExecutor.Progress cancel(TrustedContext owner) { return delegate.cancel(owner); }
                }, staging, grants,
                ResearchAdmissionController.repositoryPublication(repository, worker),
                loaded.guard(), resolver, control, authority, clock, worker,
                new ResearchAdmissionController.Settings(2, 8, 2_048), primitiveDiagnosticSink);
        research.retention(loaded.retentionService()::offer,
                refs -> publishRoots("research", refs.stream().map(WorldRetentionManager::artifact).collect(java.util.stream.Collectors.toSet())));
        RequestEnvironment environment = new RequestEnvironment() {
            @Override public boolean enrolled(ActorRef actor, TrustedContext context) {
                return control.controls(actor, context);
            }
            @Override public boolean loaded(Cuboid source, ObservationRef observation) {
                if (sourceCells(source) > 256 || !source.dimension().equals(observation.dimension()))
                    return false;
                for (long x = source.minX(); x <= source.maxX(); x++)
                    for (long y = source.minY(); y <= source.maxY(); y++)
                        for (long z = source.minZ(); z <= source.maxZ(); z++)
                            if (gatewayWorld().crop(new SurvivalGateway.Cell(source.dimension(),
                                    (int)x, (int)y, (int)z)).status()
                                    == ObservationStatus.UNKNOWN) return false;
                return true;
            }
            @Override public boolean available(ContainerRef destination, ObservationRef observation) {
                return gatewayWorld().container(destination).status() != ObservationStatus.UNKNOWN;
            }
        };
        jobs = new JobLifecycleStore(loaded.initialJobs(), (expected, next) ->
                CompletableFuture.supplyAsync(() -> {
                    try { return loaded.jobs().replace(expected, next); }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                }, worker), JobLifecycleStore.privateJobs(control::controls),
                cancellation -> controller.observeJobCancellation(cancellation), capabilities, environment,
                clock, Jobs.Settings.defaults(), loaded.jobsReadOnly());
        organizations = new OrganizationRegistry(loaded.initialOrganizations(), (expected, next) ->
                CompletableFuture.supplyAsync(() -> {
                    try { return loaded.organizations().replace(expected, next); }
                    catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
                }, worker), new OrganizationRegistry.References() {
                    public OrganizationRegistry.MemberFact citizen(ActorRef actor) {
                        var enrollment = citizens.find(actor.citizenId());
                        if (enrollment == null || !enrollment.actor().equals(actor)) return null;
                        if (!citizens.controls(actor, enrollment.owner()))
                            return new OrganizationRegistry.MemberFact(enrollment.owner(), Organizations.Participation.UNAVAILABLE);
                        var current = citizens.query(actor.citizenId(), enrollment.owner());
                        var participation = switch (current.availability()) {
                            case LOADED -> Organizations.Participation.ACTIVE;
                            case UNKNOWN -> Organizations.Participation.UNKNOWN;
                            case UNLOADED -> Organizations.Participation.UNLOADED;
                            case UNAVAILABLE, REPLACEMENT_UNRESOLVED -> Organizations.Participation.UNAVAILABLE;
                        };
                        return new OrganizationRegistry.MemberFact(enrollment.owner(), participation);
                    }
                    public boolean headquarters(ContainerRef ref, TrustedContext caller) {
                        return caller.scope().worldId().equals(loaded.initialOrganizations().worldId())
                                && gatewayWorld().container(ref).status() == ObservationStatus.PRESENT;
                    }
                }, jobs, clock, Organizations.Limits.defaults(), loaded.organizationsReadOnly());
        organizations.retentionGuard((keys, complete) -> {
            var update = loaded.roots().publishNext("organizations", keys, complete);
            return update == RetentionRoots.Update.APPLIED || update == RetentionRoots.Update.UNCHANGED;
        });
        accomplishmentService=new AccomplishmentService(loaded.accomplishments());
        accessService=new KnowledgeAccessService(access);
        refreshLearningPresence(null);
        prerequisites = new FabricPrerequisiteRuntime(server,jobs,leases,resolver,clock,this::observePrerequisite);
        prerequisites.facilities(this::observeFacilityPreparation);
        blueprintMaterials=new FabricBlueprintMaterials(server,leases);
        prerequisites.blueprints(job->{var prepared=Objects.requireNonNull(blueprintStages.get(job.id()),"Inactive blueprint binding");
            return blueprintMaterials.capture(prepared.inspection(),prepared.view(),job.request().request(),job.origin(),Math.max(0,BlueprintMaterials.held(job,leases,false)));});
        planner=new GoalPlanner(jobs,broker,new FabricGoalPlanning(server,this::observe,resolver,this::controls,
                loaded.retrieval().authoritativeFallback(),loaded.observations(),worker),PlanningBudget.defaults(),clock,loaded.initialPlans(),
                (expected,next)->CompletableFuture.supplyAsync(()->{try{return loaded.plans().replace(expected,next);}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}},worker),
                (template,caller)->template.owner().equals(caller));
        jobs.retentionGuard(next -> {
            var roots = new java.util.HashSet<WorldRetentionManager.Key>();
            for(var job:next.jobs())if(BlueprintMaterials.valid(job.request().request()))roots.add(Blueprint.retentionKey(BlueprintMaterials.ref(job.request().request())));
            for(var job:jobs.snapshot().jobs())if(BlueprintMaterials.valid(job.request().request()))roots.add(Blueprint.retentionKey(BlueprintMaterials.ref(job.request().request())));
            for(var job:next.jobs())if(job.preparation()!=null && job.preparation().method()!=null)roots.add(WorldRetentionManager.artifact(job.preparation().method()));
            for (var job : next.jobs()) for (var attempt : job.attempts()) for (var executionRef : attempt.executions())
                for (var ref : executionRef.pinned()) roots.add(WorldRetentionManager.artifact(ref));
            roots.addAll(jobs.protectedRoots().artifacts().stream().map(WorldRetentionManager::artifact).toList());
            return publishRoots("jobs", roots);
        });
        publishRoots("jobs", jobs.protectedRoots().artifacts().stream().map(WorldRetentionManager::artifact).collect(java.util.stream.Collectors.toSet()));
        var blueprintRoots=new java.util.HashSet<WorldRetentionManager.Key>(desiredRoots.getOrDefault("jobs",Set.of()));
        for(var job:jobs.snapshot().jobs())if(BlueprintMaterials.valid(job.request().request()))blueprintRoots.add(Blueprint.retentionKey(BlueprintMaterials.ref(job.request().request())));
        publishRoots("jobs",blueprintRoots);
        controller = new BootstrapController(loaded.initialState(),
                (expected, enrollment, runs) -> CompletableFuture.supplyAsync(() -> {
                    try {
                        if (enrollment != null) throw new IllegalStateException("Registry owns enrollment");
                        return loaded.journal().replaceRuns(expected, runs, loaded.identities().snapshot());
                    }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                }, worker), this::observe, resolver::resolve,
                (decision, bound, limits) -> {
                    var attempt = research.startMissing(decision, bound, limits);
                    return new BootstrapController.Research.Handle() {
                        @Override public ResearchAdmissionController.Status tick(TrustedContext owner) {
                            return attempt.tick(owner);
                        }
                        @Override public ResearchAdmissionController.Status status(TrustedContext owner) {
                            return attempt.status(owner);
                        }
                        @Override public ResearchAdmissionController.Status cancel(TrustedContext owner) {
                            return attempt.cancel(owner);
                        }
                    };
                }, new BootstrapController.Execution() {
                    @Override public List<ArtifactRef> pinned(ArtifactRef ref) {
                        return JobArtifactPins.closure(repository.find(ref).orElseThrow(), repository);
                    }
                    @Override public BootstrapController.Execution.Start start(ValidatedRequest bound, ArtifactRef ref,
                            UUID id, Budgets.ExecutionLimits limits, Budgets.Ledger usage) {
                    var started = executor.startAdmitted(bound, ref,
                            new RunCorrelation(id, ref, null), limits, usage);
                    if (started instanceof BoundedSkillExecutor.Rejected rejected)
                        return new BootstrapController.Execution.Start(null, rejected.reason());
                    var run = ((BoundedSkillExecutor.Started)started).run();
                    return new BootstrapController.Execution.Start(
                            new BootstrapController.Execution.Handle() {
                                @Override public BoundedSkillExecutor.Progress tick(TrustedContext owner) {
                                    return run.tick(owner);
                                }
                                @Override public BoundedSkillExecutor.Progress progress(TrustedContext owner) {
                                    return run.progress(owner);
                                }
                                @Override public BoundedSkillExecutor.Progress cancel(TrustedContext owner) {
                                    return run.cancel(owner);
                                }
                                @Override public BoundedSkillExecutor.Progress interrupt(TrustedContext owner) {
                                    return run.interrupt(owner);
                                }
                            }, null);
                }}, environment, capabilities,new BootstrapController.ResearchBudget() {
                    public Budgets.ResearchLimits issue(long now) { return researchLimits(now); }
                    public Budgets.ResearchLimits issue(CapabilityId capability,long now) {
                        var base=researchLimits(now);
                        return (RecipeProduction.is(capability) || CraftingTablePlacement.is(capability)) ? OakPlankProduction.researchLimits(now,base)
                                : LogAcquisition.is(capability) ? OakLogDelivery.researchLimits(now,base) : base;
                    }
                },new BootstrapController.ExecutionBudget() {
                    public Budgets.ExecutionLimits issue(long now) { return executionLimits(now); }
                    public Budgets.ExecutionLimits issue(CapabilityId capability,long now) {
                        return (RecipeProduction.is(capability) || CraftingTablePlacement.is(capability)) ? OakPlankProduction.executionLimits(now)
                                : LogAcquisition.is(capability) ? OakLogDelivery.executionLimits(now) : executionLimits(now);
                    }
                },clock,citizens,jobs);
        controller.completionPublisher((jobId,method,owner)->{
            var job=jobs.query(jobId,owner);
            if(job.state()!=Jobs.State.SUCCEEDED||!ResourceDelivery.resource(job.request().request().capability()))
                return CompletableFuture.completedFuture(true);
            var actor=job.current().worker();
            return CompletableFuture.supplyAsync(()->{
                var publication=access.recordAcquisition(jobId,()->closed);
                return (publication.code()==KnowledgeAccess.Code.APPLIED||publication.code()==KnowledgeAccess.Code.DUPLICATE)
                        &&access.mayUse(actor,method,owner);
            },worker);
        });
        controller.evidenceSink(loaded.retentionService()::offer);
        publishRoots("bootstrap", controller.retentionRoots());
        publishRoots("research", java.util.Set.of());

        dispatchGateway=new LeasedGateway(gateway,leases,
                (request,run)->LeasedGateway.cropWorkspaces(new JobLeaseOwners(jobs,clock),request,run),
                ()->server.overworld().getGameTime(),1200,200,8,512);
        dispatchGateway.admission(LeasedGateway.mandates(mandates,direct));
        var dispatchExecutor=new BoundedSkillExecutor(staging,capabilities,primitives,
                access,grants,runControl,dispatchGateway,
                dispatchGateway::releaseRun,clock,()->server.overworld().getGameTime(),
                new BoundedSkillExecutor.Settings(8,16,32,64));
        dispatcher=new WorkerDispatcher(jobs,new MandateWorkers(citizens,mandates),this::observe,resolver::resolve,
                WorkerDispatcher.executorPort(dispatchExecutor,repository),WorkerDispatcher.leasedClaims(dispatchGateway),
                job->ResourceDelivery.isDelivery(job.request().request().capability()) && !controller.ownsSubmission(job.submissionId()),clock,()->server.overworld().getGameTime(),
                WorkerDispatcher.Settings.production());
        dispatcher.jobResolver((job,request,observation)->resolver.withEligibility(access.policyForJob(job.id())).resolve(request,observation));

        recoveredOwners = new dev.aivillages.core.kernel.runtime.RecoveredJobOwners(jobs,
                new dev.aivillages.core.kernel.runtime.RecoveredJobOwners.LiveOwners() {
                    public boolean bootstrap(Jobs.Job job) { return controller.liveSubmission(job.submissionId()); }
                    public boolean dispatcher(Jobs.Job job) { return dispatcher.ownsAttempt(job); }
                    public dev.aivillages.core.kernel.runtime.RecoveredJobOwners.Presence presence(Jobs.Job job) {
                        var actor = job.current() == null ? job.responsible() : job.current().worker();
                        var enrollment = citizens.find(actor.citizenId());
                        if (enrollment == null || !enrollment.actor().equals(actor))
                            return dev.aivillages.core.kernel.runtime.RecoveredJobOwners.Presence.UNAVAILABLE;
                        var current = citizens.query(actor.citizenId(), job.origin());
                        if (current.availability() == CitizenRegistry.Availability.UNLOADED
                                || current.availability() == CitizenRegistry.Availability.UNKNOWN)
                            return dev.aivillages.core.kernel.runtime.RecoveredJobOwners.Presence.WAIT_WORLD;
                        if (current.availability() == CitizenRegistry.Availability.UNAVAILABLE
                                || current.availability() == CitizenRegistry.Availability.REPLACEMENT_UNRESOLVED)
                            return dev.aivillages.core.kernel.runtime.RecoveredJobOwners.Presence.UNAVAILABLE;
                        if (villager(actor) == null)
                            return dev.aivillages.core.kernel.runtime.RecoveredJobOwners.Presence.WAIT_WORLD;
                        for (var value : job.request().request().arguments().values()) {
                            if (value instanceof ContainerValue c && worldAccess.container(c.value()).status() == ObservationStatus.UNKNOWN)
                                return dev.aivillages.core.kernel.runtime.RecoveredJobOwners.Presence.WAIT_WORLD;
                            if (value instanceof AreaValue a && (!server.overworld().hasChunkAt(new BlockPos(a.value().minX(),a.value().minY(),a.value().minZ()))
                                    || !server.overworld().hasChunkAt(new BlockPos(a.value().maxX(),a.value().maxY(),a.value().maxZ()))))
                                return dev.aivillages.core.kernel.runtime.RecoveredJobOwners.Presence.WAIT_WORLD;
                        }
                        return dev.aivillages.core.kernel.runtime.RecoveredJobOwners.Presence.AVAILABLE;
                    }
                }, original -> Jobs.StoppedObservation.ambiguous("legacy: no save-confirmed original effect proof"), 4);
        worldAccess.effectFence((request,effect,target)->loaded.history()!=null && (loaded.history().readOnly() || historyGenerationUnavailable
                || leaseArchive!=null && leaseArchive.readOnly())
                && effect!=Effect.OBSERVE && effect!=Effect.MOVE || recoveredOwners.fenced(request,effect,target));

        lifecycle = new CitizenLifecycle(citizens, new CitizenWorkReconciler(actor -> {
            // Notify both owners; short-circuiting would leave the second owner's work running.
            boolean bootstrapStopped = controller.interruptActor(actor);
            boolean dispatchStopped = dispatcher.interruptActor(actor);
            return bootstrapStopped && dispatchStopped;
        }, jobs, leases), CitizenLifecycle.Settings.production(), server.overworld().getGameTime());
        lifecycleAdapter = new FabricCitizenLifecycle(server, citizens, lifecycle);

        language = new LanguageRequests(languagePort, new LanguageRequests.Binding() {
            @Override public List<String> references(TrustedContext caller) {
                return citizens.citizens(caller).citizens().stream().flatMap(address ->
                        address.displayName() == null ? java.util.stream.Stream.of(address.actor().citizenId().toString())
                                : java.util.stream.Stream.of(address.displayName(), address.actor().citizenId().toString()))
                        .distinct().limit(32).toList();
            }
            @Override public CitizenRegistry.Addresses address(String reference, TrustedContext caller) {
                try { return citizens.address(UUID.fromString(reference), caller); }
                catch (IllegalArgumentException name) { return citizens.address(reference, caller); }
            }
            @Override public LanguageRequests.ResolutionHandle resolve(LanguageRequests.Intent intent,
                                                                       TrustedContext caller) {
                ServerPlayer player = interpretingPlayer;
                if (player == null || !player.getUUID().equals(caller.principal().id()))
                    throw new SecurityException("No trusted player is attached to this interpretation");
                return new FabricWorldReferenceResolver(worldAccess,citizens,caller,player,intent,clock,
                        (source,quantity)->leases.availableStock(intent instanceof LanguageRequests.HarvestIntent h && RecipeProduction.is(h.capability())
                                ? RecipeProduction.of(h.capability()).stock(caller.scope().worldId(),source) : ResourceLeases.Resource.oakStock(caller.scope().worldId(),source),quantity,caller),
                        (source,quantity)->leases.availableStock(new ResourceLeases.Resource(caller.scope().worldId(),ResourceLeases.Kind.STOCK,ResourceLeases.point(source),ResourceDelivery.STICK.id(),-1),quantity,caller));
            }
            @Override public BootstrapController.Submission submit(CapabilityRequest request, TrustedContext caller) {
                return submitRequest(request, caller, interpretingPlayer,true);
            }
            @Override public void cancel(UUID run, TrustedContext caller) { controller.cancel(run, caller); }
            @Override public List<UUID> cancellableRunIds(TrustedContext caller) {
                return controller.cancellableRunIds(caller);
            }
        }, clock);
    }

    private FabricGatewayWorld gatewayWorld() { return worldAccess; }
    private long sourceCells(Cuboid source) {
        return Math.multiplyExact(Math.multiplyExact((long)source.maxX()-source.minX()+1,
                (long)source.maxY()-source.minY()+1),(long)source.maxZ()-source.minZ()+1);
    }
    private ObservationSnapshot observe(CapabilityRequest request, ActorRef actor) {
        if(CraftingTablePlacement.is(request.capability())) {
            long tick=server.overworld().getGameTime();var context=citizens.find(actor.citizenId()).owner();var area=ResourceDelivery.sourceArea(request);
            var bound=new ValidatedRequest(request,context,new ObservationRef(UUID.randomUUID(),tick,actor.dimension()));
            var current=gatewayWorld().placementPreflight(bound);var source=((ContainerValue)request.arguments().get("source")).value();
            var stock=leases.availableStock(LeasedGateway.sourceResource(context.scope().worldId(),bound),current.availableItems(),context);
            boolean known=current.status()==ObservationStatus.PRESENT && current.reason()==null && stock.usable();
            return new ObservationSnapshot(bound.observation(),known?ObservationStatus.PRESENT:ObservationStatus.UNKNOWN,
                    "source:"+UUID.nameUUIDFromBytes(area.toString().getBytes(StandardCharsets.UTF_8)),tick,1,
                    // Structural planning feasibility is distinct from a moving entity.
                    // Observation permits explicit MOVE to leave an occupied site;
                    // every mutation still requires actual clearance.
                    Map.of("table_items",stock.usable()?stock.available():0L,"site_usable",known?1L:0L,
                            "site_clear",known && !current.pending()?1L:0L,"unknown_cells",known?0L:1L));
        }
        if(RecipeProduction.is(request.capability())) {
            var area=ResourceDelivery.sourceArea(request);
            long tick=server.overworld().getGameTime();
            var context=citizens.find(actor.citizenId()).owner();
            var bound=new ValidatedRequest(request,context,new ObservationRef(UUID.randomUUID(),tick,actor.dimension()));
            var current=gatewayWorld().recipePreflight(bound);
            long available=current.availableInputs();
            var stock=leases.availableStock(RecipeProduction.of(request.capability()).stock(context.scope().worldId(),
                    ((ContainerValue)request.arguments().get("source")).value()),available,context);
            boolean known=current.status()==ObservationStatus.PRESENT && current.reason()==null && stock.usable();
            if(stock.usable())available=stock.available();
            long needed=RecipeProduction.of(request.capability()).inputs(((IntValue)request.arguments().get("amount")).value());
            if(RecipeProduction.of(request.capability()).axe()) {
                var d=RecipeProduction.of(request.capability());var source=((ContainerValue)request.arguments().get("source")).value();
                var secondary=leases.availableStock(d.secondaryStock(context.scope().worldId(),source),current.availableSecondary(),context);
                known=known && secondary.usable();long sticks=secondary.usable() ? secondary.available() : current.availableSecondary();
                long required=d.secondaryInputs(((IntValue)request.arguments().get("amount")).value());
                return new ObservationSnapshot(new ObservationRef(UUID.randomUUID(),tick,actor.dimension()),known ? ObservationStatus.PRESENT : ObservationStatus.UNKNOWN,
                        "source:"+UUID.nameUUIDFromBytes(area.toString().getBytes(StandardCharsets.UTF_8)),tick,1,
                        Map.of("input_planks",available,"required_planks",needed,"missing_planks",Math.max(0,needed-available),
                                "input_sticks",sticks,"required_sticks",required,"missing_sticks",Math.max(0,required-sticks),
                                "output_capacity",current.outputCapacity(),"recipe_supported",current.recipe()==null ? 0L : 1L,"unknown_cells",known ? 0L : 1L));
            }
            return new ObservationSnapshot(new ObservationRef(UUID.randomUUID(),tick,actor.dimension()),known ? ObservationStatus.PRESENT : ObservationStatus.UNKNOWN,
                    "source:"+UUID.nameUUIDFromBytes(area.toString().getBytes(StandardCharsets.UTF_8)),tick,1,
                    Map.of(RecipeProduction.of(request.capability()).inputCount(),available,!LogAcquisition.log(RecipeProduction.of(request.capability()).input()) ? "required_planks" : "required_logs",needed,RecipeProduction.of(request.capability()).missingCount(),Math.max(0,needed-available),
                            "output_capacity",current.outputCapacity(),"recipe_supported",current.recipe()==null ? 0L : 1L,"unknown_cells",known ? 0L : 1L));
        }
        Cuboid area = ((AreaValue)request.arguments().get("source")).value();
        int scanned = 0; long mature = 0, unknown = 0;
        if (sourceCells(area) > 256 || !area.dimension().equals(actor.dimension()))
            throw new IllegalArgumentException("Source exceeds kernel observation limit");
        var world = gatewayWorld();
        for (long x = area.minX(); x <= area.maxX(); x++)
            for (long y = area.minY(); y <= area.maxY(); y++)
                for (long z = area.minZ(); z <= area.maxZ(); z++) {
                    var cell=new SurvivalGateway.Cell(area.dimension(),(int)x,(int)y,(int)z);
                    if(LogAcquisition.is(request.capability())
                            && !world.discoverable(actor,new BlockPos((int)x,(int)y,(int)z),Effect.HARVEST)) { scanned++;unknown++;continue; }
                    var block=world.resourceBlock(cell,LogAcquisition.is(request.capability())
                            ? LogAcquisition.item(request.capability()) : ResourceDelivery.WHEAT);
                    scanned++;
                    if (block.status() == ObservationStatus.UNKNOWN) unknown++;
                    else if (block.eligible()) mature++;
                }
        long tick = server.overworld().getGameTime();
        String identity = "source:" + UUID.nameUUIDFromBytes(
                area.toString().getBytes(StandardCharsets.UTF_8));
        return new ObservationSnapshot(new ObservationRef(UUID.randomUUID(), tick,
                actor.dimension()), unknown > 0 ? ObservationStatus.UNKNOWN
                        : mature > 0 ? ObservationStatus.PRESENT : ObservationStatus.ABSENT,
                identity, tick, scanned,
                Map.of(LogAcquisition.is(request.capability()) ? LogAcquisition.count(request.capability()) : "mature_wheat", mature, "unknown_cells", unknown));
    }

    private Budgets.ResearchLimits researchLimits(long now) {
        long deadline = Math.addExact(now, 600_000);
        var total = new EnumMap<Budgets.Kind, Long>(Budgets.Kind.class);
        total.put(Budgets.Kind.CALLS, 512L); total.put(Budgets.Kind.REPAIRS, 1L);
        total.put(Budgets.Kind.INPUT_BYTES, 40_000L);
        total.put(Budgets.Kind.OUTPUT_BYTES, 20_000L);
        total.put(Budgets.Kind.CANDIDATES, 2L); total.put(Budgets.Kind.TRIALS, 1L);
        total.put(Budgets.Kind.INSTRUCTIONS, 3_000L);
        total.put(Budgets.Kind.OBSERVATIONS, 700L);
        total.put(Budgets.Kind.TRAVEL_BLOCKS, 100L);
        total.put(Budgets.Kind.ATTEMPTED_EFFECTS, 300L);
        total.put(Budgets.Kind.COMMITTED_EFFECTS, 300L);
        total.put(Budgets.Kind.ELAPSED_TICKS, 1_000L);
        var inference = new Budgets.InferenceLimits(new Budgets.Limits(Map.of(
                Budgets.Kind.CALLS, 2L, Budgets.Kind.REPAIRS, 1L,
                Budgets.Kind.INPUT_BYTES, 40_000L, Budgets.Kind.OUTPUT_BYTES, 20_000L),
                deadline), 16_384, 10_000);
        var trial = new Budgets.ExecutionLimits(new Budgets.Limits(Map.of(
                Budgets.Kind.CALLS, 256L, Budgets.Kind.INSTRUCTIONS, 768L,
                Budgets.Kind.OBSERVATIONS, 256L, Budgets.Kind.TRAVEL_BLOCKS, 32L,
                Budgets.Kind.ATTEMPTED_EFFECTS, 64L, Budgets.Kind.COMMITTED_EFFECTS, 64L,
                Budgets.Kind.ELAPSED_TICKS, 600L), deadline), 15_000);
        return new Budgets.ResearchLimits(inference, trial, new Budgets.Limits(total, deadline));
    }
    private Budgets.ExecutionLimits executionLimits(long now) {
        long deadline = Math.addExact(now, 600_000);
        return new Budgets.ExecutionLimits(new Budgets.Limits(Map.of(
                Budgets.Kind.CALLS, 256L, Budgets.Kind.INSTRUCTIONS, 768L,
                Budgets.Kind.OBSERVATIONS, 256L, Budgets.Kind.TRAVEL_BLOCKS, 32L,
                Budgets.Kind.ATTEMPTED_EFFECTS, 64L, Budgets.Kind.COMMITTED_EFFECTS, 64L,
                Budgets.Kind.ELAPSED_TICKS, 600L), deadline), 15_000);
    }

    private void ready() {
        if (closed || controller == null)
            throw new IllegalStateException("Kernel world data is loading or unavailable");
    }
    /** Explicit bounded dependency API for existing Jobs; expansion is disposable and has no model port. */
    public Prerequisites.Result resolvePrerequisites(List<Prerequisites.Need> needs,
                                                      TrustedContext caller,List<String> recipeKeys) {
        ready();return prerequisites.resolve(needs,caller,recipeKeys);
    }
    /** Structured driver boundary; the request contains no authority or model-supplied allowance. */
    public Jobs.Change createPlanningObjective(UUID id,CapabilityRequest goal,TrustedContext caller) {
        ready();if(!PlanningGoals.valid(goal))throw new IllegalArgumentException("Recognized planning goal required");
        var actor=((ActorValue)goal.arguments().get("actor")).value();
        if(!controls(actor,caller))throw new SecurityException("Planning objective control required");
        var observation=observe(goal,actor);
        var maxima=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);maxima.putAll(researchLimits(clock.millis()).total().maxima());
        maxima.put(Budgets.Kind.INPUT_BYTES,65536L);maxima.put(Budgets.Kind.OUTPUT_BYTES,16384L);maxima.put(Budgets.Kind.CANDIDATES,2L);
        // Two known outcomes receive one existing dispatch window each, fixed before any proposal or retry.
        maxima.put(Budgets.Kind.ELAPSED_TICKS,2L*WorkerDispatcher.Settings.production().assignmentTicks());
        return jobs.create(id,id,goal,caller,observation.reference(),new Budgets.Limits(maxima,clock.millis()+600000),List.of());
    }
    public GoalPlanner.View proposePlan(UUID parent,TrustedContext caller){ready();return planner.propose(parent,caller);}
    public GoalPlanner.View planStatus(UUID proposal,TrustedContext caller){ready();return planner.view(proposal,caller);}
    public boolean cancelPlanProposal(UUID proposal,TrustedContext caller){ready();return planner.cancelProposal(proposal,caller);}
    public Jobs.Change cancelPlanningObjective(UUID parent,TrustedContext caller){ready();return planner.cancelObjective(parent,caller);}
    public boolean retainPlanningTemplate(UUID parent,TrustedContext caller){ready();return planner.demonstrate(parent,caller);}
    public List<PlanJournal.Template> planningTemplates(TrustedContext caller){ready();return planner.templates(caller);}
    public GoalPlanner.View reusePlanningTemplate(UUID parent,UUID template,TrustedContext caller){ready();return planner.reuse(parent,template,caller);}
    private boolean controls(ActorRef actor, TrustedContext context) {
        return citizens != null && citizens.controls(actor, context);
    }
    private Villager villager(ActorRef actor) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION,
                Identifier.parse(actor.dimension())));
        if (level == null) return null;
        var entity = level.getEntity(actor.entityId());
        return entity instanceof Villager villager && villager.isAlive() && !villager.isRemoved()
                ? villager : null;
    }
    public boolean enrolled(Villager villager) {
        return villager.getUUID().equals(pendingEnrollmentEntity) || citizens != null
                && citizens.snapshot().citizens().stream().anyMatch(c ->
                        c.actor().entityId().equals(villager.getUUID()));
    }
    public ActorRef enroll(ServerPlayer player, Villager villager) {
        ready();
        if (!villager.isAlive() || villager.isBaby() || player.level() != villager.level()
                || villager.level() != server.overworld()
                || player.distanceToSqr(villager) > 36 || GatewayControl.controls(villager)
                || AiVillages.session() != null && AiVillages.session().agents().stream().anyMatch(
                        agent -> agent.entityId.equals(villager.getUUID().toString())))
            throw new IllegalArgumentException("Choose an unclaimed adult villager within 6 blocks");
        var change = citizens.enroll(villager.getUUID(),
                player.level().dimension().identifier().toString(), caller(player));
        if (!change.accepted()) throw new IllegalStateException("Enrollment: " + change.reason());
        ActorRef actor = change.citizen().actor();
        if (change.pending()) pendingEnrollmentEntity = actor.entityId();
        villager.setPersistenceRequired();
        return actor;
    }
    public BootstrapJournal.Enrollment enrollment() { ready(); return controller.enrollment(); }
    /** Scoped responsibility view corresponding to a submitted run; no identifier grants access. */
    public Optional<Jobs.Job> job(UUID submission, TrustedContext caller) {
        ready(); return jobs.bySubmission(submission, caller);
    }
    public Jobs.Roots jobRoots() { ready(); return jobs.protectedRoots(); }
    /** Trusted server-side immutable storage capture. No player/model command or physical authority. */
    public record RuntimeStorageView(Jobs.Snapshot jobs,ResourceLeases.Snapshot leases,long retentionGeneration,boolean ready) { }
    private long historyGeneration() {
        try{return dev.aivillages.core.kernel.runtime.persistence.ArchiveFormat.retentionGeneration(historyReferenceBase,loaded.roots().snapshot().generation());}
        catch(ArithmeticException overflow){historyGenerationUnavailable=true;return Long.MAX_VALUE;}
    }
    public RuntimeStorageView runtimeStorageView() {
        ready();long generation=historyGeneration();return new RuntimeStorageView(jobs.snapshot(),leases.snapshot(),generation,jobs.ready() && leases.ready() && !historyGenerationUnavailable);
    }
    /** Startup-only storage activation; domain eviction and physical evidence protocols are separate gates. */
    public dev.aivillages.core.kernel.runtime.persistence.ArchiveService runtimeHistory() {
        ready();if(historyService==null)throw new IllegalStateException("Runtime history storage is disabled");return historyService;
    }
    public record RuntimeHistoryStatus(boolean enabled,boolean readOnly,
            dev.aivillages.core.kernel.runtime.persistence.ArchiveFormat.Manifest manifest) { }
    public RuntimeHistoryStatus runtimeHistoryStatus() {
        ready();return new RuntimeHistoryStatus(loaded.history()!=null,loaded.history()!=null && (loaded.history().readOnly() || historyGenerationUnavailable),
                loaded.history()==null?null:loaded.history().snapshot());
    }
    /** Aggregate operator diagnostics only; no principal or historical resource identifiers. */
    public LeaseArchive.Status runtimeLeaseHistoryStatus() { ready();return leaseArchive==null?null:leaseArchive.status(); }
    /** Private, bounded historical evidence. This view grants no dispatch preference or authority. */
    /** Host-facing IMP-022 API. No player command/client feature is introduced by this data owner. */
    public KnowledgeAccess.Decision knowledgeEligibility(ActorRef actor,ArtifactRef method,TrustedContext caller){
        ready();return loaded.access().evaluateUse(actor,method,caller);
    }
    public CompletableFuture<KnowledgeAccess.Result> requirePersonalLearning(ActorRef actor,TrustedContext caller){
        ready();if(!controls(actor,caller))throw new SecurityException("Apprentice control");
        if(accessQueries.incrementAndGet()>8){accessQueries.decrementAndGet();return CompletableFuture.failedFuture(new IllegalStateException("Learning operation bound"));}
        try{return CompletableFuture.supplyAsync(()->loaded.access().requirePersonalLearning(actor,caller,()->closed),worker)
                .whenComplete((ignored,error)->accessQueries.decrementAndGet());}
        catch(RuntimeException refused){accessQueries.decrementAndGet();return CompletableFuture.failedFuture(refused);}
    }
    public CompletableFuture<KnowledgeAccess.Result> startApprenticeship(UUID jobId,ActorRef teacher,
                                                                        ArtifactRef method,TrustedContext caller){
        ready();var job=jobs.query(jobId,caller);
        if(loaded.access().snapshot().practices().stream().filter(p->p.stage()==KnowledgeAccess.Stage.PRACTICING).count()+learningStarts.size()>=8)
            return CompletableFuture.completedFuture(new KnowledgeAccess.Result(KnowledgeAccess.Code.LIMIT,Reason.STORAGE_LIMIT_REACHED,jobId,0,0,false));
        var learner=((ActorValue)job.request().request().arguments().get("actor")).value();
        var pair=new KnowledgeAccess.Pair(teacher,learner);
        if(learningStarts.putIfAbsent(jobId,pair)!=null)return CompletableFuture.completedFuture(new KnowledgeAccess.Result(KnowledgeAccess.Code.PENDING,null,jobId,0,0,false));
        refreshLearningPresence(pair);
        if(accessQueries.incrementAndGet()>8){accessQueries.decrementAndGet();learningStarts.remove(jobId,pair);
            return CompletableFuture.failedFuture(new IllegalStateException("Learning operation bound"));}
        try{return CompletableFuture.supplyAsync(()->loaded.access().startApprenticeship(jobId,teacher,method,caller,()->closed),worker)
                .whenComplete((ignored,error)->{learningStarts.remove(jobId,pair);accessQueries.decrementAndGet();});}
        catch(RuntimeException refused){learningStarts.remove(jobId,pair);accessQueries.decrementAndGet();return CompletableFuture.failedFuture(refused);}
    }
    private void refreshLearningPresence(KnowledgeAccess.Pair starting){
        var pairs=new java.util.HashSet<KnowledgeAccess.Pair>();
        for(var practice:loaded.access().snapshot().practices())if(practice.stage()==KnowledgeAccess.Stage.PRACTICING)
            pairs.add(new KnowledgeAccess.Pair(practice.teacher().actor(),practice.learner().actor()));
        pairs.addAll(learningStarts.values());
        if(starting!=null)pairs.add(starting);
        var present=new java.util.HashSet<KnowledgeAccess.Pair>();
        for(var pair:pairs.stream().limit(8).toList()){
            var teacher=villager(pair.teacher());var learner=villager(pair.learner());
            var teacherOwner=citizens.snapshot().citizens().stream().filter(c->c.actor().equals(pair.teacher())).findFirst().orElse(null);
            var learnerOwner=citizens.snapshot().citizens().stream().filter(c->c.actor().equals(pair.learner())).findFirst().orElse(null);
            if(teacher!=null&&learner!=null&&teacher.isAlive()&&learner.isAlive()&&teacherOwner!=null&&learnerOwner!=null
                    &&controls(pair.teacher(),teacherOwner.owner())&&controls(pair.learner(),learnerOwner.owner())
                    &&pair.teacher().dimension().equals(pair.learner().dimension())&&teacher.distanceToSqr(learner)<=256)
                present.add(pair);
        }
        loaded.access().source().observe(citizens.snapshot(),jobs.snapshot(),present);
    }

    public CompletableFuture<Accomplishments.Query> accomplishments(ServerPlayer player,UUID citizenId,int maximum){
        if(!server.isSameThread())throw new IllegalStateException("Evidence intake requires server thread");
        ready();var owner=caller(player);var citizen=citizens.query(citizenId,owner);
        return accomplishmentWork(()->loaded.accomplishments().query(citizen.actor(),owner,maximum));
    }
    CompletableFuture<Accomplishments.Query> accomplishments(ActorRef actor,TrustedContext caller,int maximum){
        if(!server.isSameThread())throw new IllegalStateException("Evidence intake requires server thread");
        ready();return accomplishmentWork(()->loaded.accomplishments().query(actor,caller,maximum));
    }
    private <T> CompletableFuture<T> accomplishmentWork(java.util.function.Supplier<T> operation){
        if(accomplishmentQueries.incrementAndGet()>8){accomplishmentQueries.decrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("Evidence query capacity"));}
        try{return CompletableFuture.supplyAsync(operation,worker).whenComplete((ignored,error)->accomplishmentQueries.decrementAndGet());}
        catch(RuntimeException refused){accomplishmentQueries.decrementAndGet();return CompletableFuture.failedFuture(refused);}
    }
    /** Host integration only; no organization client command or model policy endpoint is registered. */
    public OrganizationRegistry organizations() {
        if (!server.isSameThread()) throw new IllegalStateException("Organization intake requires server thread");
        ready(); return organizations;
    }
    /** Host-only scoped mandate intake; text/IR and organization titles cannot create these grants. */
    public MandateRegistry mandates() {
        if (!server.isSameThread()) throw new IllegalStateException("Mandate intake requires server thread");
        ready(); return mandates;
    }
    private boolean publishRoots(String source, java.util.Set<WorldRetentionManager.Key> keys) {
        if (keys.size() > RetentionRoots.MAX_ROOTS) return false;
        desiredRoots.put(source, java.util.Set.copyOf(keys));
        boolean complete = !source.equals("jobs") || jobs == null || jobs.protectedRoots().complete();
        var old = loaded.roots().snapshot().sources().get(source);
        if (old != null && old.complete() == complete && old.roots().equals(keys)) return true;
        long revision = old == null ? 0 : old.revision() == Long.MAX_VALUE ? Long.MAX_VALUE : old.revision() + 1;
        var update = loaded.roots().publish(source, new RetentionRoots.Source(revision, keys, complete));
        return update == RetentionRoots.Update.APPLIED || update == RetentionRoots.Update.UNCHANGED;
    }
    private void refreshRetentionRoots() {
        if (controller != null) desiredRoots.put("bootstrap", controller.retentionRoots());
        if(planner!=null)desiredRoots.put("plans",planner.retentionRoots());
        for (var entry : List.copyOf(desiredRoots.entrySet())) publishRoots(entry.getKey(), entry.getValue());
    }
    WorldRetentionService.Status retentionStatus() { ready(); return loaded.retentionService().status(); }
    /** Historical evidence only. The bounded worker query never substitutes for gateway validation. */
    public CompletableFuture<ObservedWorldKnowledgeStore.EvidenceSnapshot> observedKnowledge(ServerPlayer player,
            SurvivalGateway.ObservationTarget target, ObservedWorldKnowledgeStore.QueryLimits limits) {
        if (!server.isSameThread()) throw new IllegalStateException("Observation query requires server-thread intake");
        ready(); var viewer = caller(player);
        if (observationQueries.incrementAndGet() > 8) {
            observationQueries.decrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("Observation query capacity"));
        }
        try {
            return CompletableFuture.supplyAsync(() -> loaded.observations().createEvidenceSnapshot(viewer, target, limits), worker)
                    .whenComplete((ignored, failure) -> observationQueries.decrementAndGet());
        } catch (RuntimeException unavailable) {
            observationQueries.decrementAndGet(); return CompletableFuture.failedFuture(unavailable);
        }
    }
    public List<RetentionEvidenceStore.Aggregate> retentionFailures(ServerPlayer player) { ready(); return loaded.evidence().failures(caller(player)); }
    /** Bounded data-only intake. Acceptance grants no construction or site permission. */
    public CompletableFuture<BlueprintStore.Publication> storeBlueprint(ServerPlayer player,String data,String label) {
        if(!server.isSameThread())throw new IllegalStateException("Blueprint intake requires server thread");
        ready();var origin=caller(player);
        var quota=new BlueprintStore.Publication(BlueprintStore.Status.QUOTA,null,0,0,false);
        if(data==null)return CompletableFuture.completedFuture(
                new BlueprintStore.Publication(BlueprintStore.Status.INVALID_DATA,null,0,0,false));
        if(data.length()>65536)return CompletableFuture.completedFuture(quota);
        final BlueprintStore.Provenance provenance;
        try{provenance=new BlueprintStore.Provenance(origin,label);}
        catch(IllegalArgumentException invalid){return CompletableFuture.completedFuture(
                new BlueprintStore.Publication(BlueprintStore.Status.INVALID_DATA,null,0,0,false));}
        return blueprintWork(()->loaded.blueprints().store(data,blueprintRegistry,provenance,blueprintClosing::get),quota);
    }
    public CompletableFuture<BlueprintStore.Inspection> inspectBlueprint(ServerPlayer player,Blueprint.Ref ref) {
        if(!server.isSameThread())throw new IllegalStateException("Blueprint intake requires server thread");
        ready();var viewer=caller(player);
        if(ref==null)return CompletableFuture.completedFuture(
                new BlueprintStore.Inspection(BlueprintStore.Status.INVALID_DATA,null,null,Map.of(),List.of(),List.of(),null));
        var quota=new BlueprintStore.Inspection(BlueprintStore.Status.QUOTA,ref,null,Map.of(),List.of(),List.of(),null);
        return blueprintWork(()->loaded.blueprints().inspect(ref,viewer,blueprintRegistry,blueprintClosing::get),quota);
    }
    private <T> CompletableFuture<T> blueprintWork(java.util.function.Supplier<T> operation,T quota) {
        if(blueprintQueries.incrementAndGet()>8){blueprintQueries.decrementAndGet();return CompletableFuture.completedFuture(quota);}
        try{return CompletableFuture.supplyAsync(operation,worker).whenComplete((ignored,failure)->blueprintQueries.decrementAndGet());}
        catch(RuntimeException unavailable){blueprintQueries.decrementAndGet();return CompletableFuture.failedFuture(unavailable);}
    }
    public record MaterialResult(UUID job,boolean accepted,BlueprintMaterials.Report report,Reason reason) { }
    public record MaterialStatus(Jobs.Job job,BlueprintMaterials.Report report,long allocated,boolean currentlyAllocated) { }
    private CompletableFuture<PreparedBlueprint> prepareBlueprint(ServerPlayer player,CapabilityRequest request) {
        if(!server.isSameThread())throw new IllegalStateException("Blueprint intake game thread");
        ready();var viewer=caller(player);var ref=BlueprintMaterials.ref(request);
        var quota=new PreparedBlueprint(new BlueprintStore.Inspection(BlueprintStore.Status.QUOTA,ref,null,Map.of(),List.of(),List.of(),null),null);
        return blueprintWork(()->{
            var inspection=loaded.blueprints().inspect(ref,viewer,blueprintRegistry,blueprintClosing::get);
            Blueprint.View view=null;
            if(inspection.compatible())try{view=BlueprintValidator.bind(inspection.body(),BlueprintMaterials.binding(request));}
            catch(IllegalArgumentException|ArithmeticException invalid){/* Immutable invalid binding; observation reports REQUEST_INVALID. */}
            return new PreparedBlueprint(inspection,view);
        },quota);
    }
    /** Explicit artifact/site binding. Preflight has no publication or inference path. */
    public CompletableFuture<MaterialResult> blueprintMaterials(ServerPlayer player,UUID actorId,Blueprint.Ref ref,int rotation,
            FacilityRef origin,Cuboid site,ContainerRef source,ContainerRef destination,Cuboid acquisition,boolean stage) {
        if(!server.isSameThread())throw new IllegalStateException("Blueprint intake game thread");
        ready();var owner=caller(player);var citizen=citizens.query(actorId,owner);var actor=citizen.actor();
        var allowance=ResourceComposition.allowance(clock.millis());
        var args=new HashMap<String,Value>();args.put("actor",new ActorValue(actor));args.put("blueprint",new BlueprintValue(ref));
        args.put("origin",new FacilityValue(origin));args.put("rotation",new IntValue(rotation));args.put("site",new AreaValue(site));
        args.put("source",new ContainerValue(source));args.put("destination",new ContainerValue(destination));args.put("acquisition",new AreaValue(acquisition));args.put("amount",new IntValue(0));
        var request=new CapabilityRequest(BlueprintMaterials.ID,args);var result=new CompletableFuture<MaterialResult>();
        prepareBlueprint(player,request).whenComplete((prepared,failure)->server.execute(()->{
            try {
                ready();if(failure!=null)throw new IllegalStateException("Blueprint inspection unavailable",failure);
                if(!controls(actor,owner) || villager(actor)==null || villager(actor).level()!=player.level())throw new SecurityException("Current blueprint authority");
                var report=blueprintMaterials.capture(prepared.inspection(),prepared.view(),request,owner,0);
                if(!BlueprintMaterials.valid(request))report=report.blocked(source.equals(destination)?"bounded-request-or-dimension":"missing-transfer-capability",
                        source.equals(destination)?Reason.REQUEST_INVALID:Reason.UNSUPPORTED_PRIMITIVE);
                if(report.canStage() && report.shortage()>0)report=blueprintSupport(request,report,owner,allowance);
                UUID id=stage?UUID.randomUUID():null;
                if(!stage || !report.canStage()){result.complete(new MaterialResult(id,!stage&&report.canStage(),report,report.reason()));return;}
                blueprintStages.keySet().removeIf(old->jobs.snapshot().jobs().stream().anyMatch(j->j.id().equals(old)&&(j.state().terminal()||j.state()==Jobs.State.INTERRUPTED)));
                if(blueprintStages.size()>=32){result.complete(new MaterialResult(id,false,report,Reason.BUDGET_EXHAUSTED));return;}
                var concrete=new HashMap<>(request.arguments());concrete.put("amount",new IntValue(report.required()));
                var checked=new CapabilityRequest(BlueprintMaterials.ID,concrete);jobs.tick();
                var change=jobs.stageBlueprint(id,checked,owner,new ObservationRef(UUID.randomUUID(),server.overworld().getGameTime(),actor.dimension()),allowance,1024,1024);
                if(change.accepted())blueprintStages.put(id,prepared);
                result.complete(new MaterialResult(id,change.accepted(),report,change.reason()));
            }catch(Throwable error){result.completeExceptionally(error);}
        }));return result;
    }
    private BlueprintMaterials.Report blueprintSupport(CapabilityRequest request,BlueprintMaterials.Report report,TrustedContext owner,Budgets.Limits allowance) {
        var production=BlueprintMaterials.production(request,report.shortage());var actor=((ActorValue)request.arguments().get("actor")).value();
        var observed=observePrerequisite(production);var bound=new ValidatedRequest(production,owner,observed.reference());
        var preflight=gatewayWorld().recipePreflight(bound);
        if(preflight.reason()!=null)return report.blocked("recipe-or-container:"+preflight.reason(),preflight.reason());
        if(preflight.outputCapacity()<report.shortage())return report.blocked("staging-capacity",Reason.TARGET_UNAVAILABLE);
        var free=leases.availableStock(ResourceLeases.Resource.oakStock(owner.scope().worldId(),((ContainerValue)request.arguments().get("destination")).value()),preflight.availableInputs(),owner);
        if(!free.usable())return report.blocked("input-stock:"+free.reason(),free.reason()==null?Reason.STALE_OBSERVATION:free.reason());
        long missing=OakPlankProduction.missingInputs(report.shortage(),free.available());
        if(missing>8)return report.blocked("bounded-acquisition:8-logs;missing:"+missing,Reason.BUDGET_EXHAUSTED);
        var acquisition=((AreaValue)request.arguments().get("acquisition")).value();
        var preview=prerequisites.preview(bound,acquisition,blueprintMaterials::supply,n->controls(actor,owner),allowance);
        if(!preview.accepted())return report.blocked("prerequisite:"+preview.status()+":"+preview.reason(),preview.reason()==null?Reason.KNOWLEDGE_REQUIRED:preview.reason());
        if(preview.nodes().size()>2)return report.blocked("bounded-graph:2-producers",Reason.BUDGET_EXHAUSTED);
        if(preview.nodes().stream().anyMatch(n->n.production().capability().equals(OakLogDelivery.ID)))
            for(long x=acquisition.minX();x<=acquisition.maxX();x++)for(long y=acquisition.minY();y<=acquisition.maxY();y++)for(long z=acquisition.minZ();z<=acquisition.maxZ();z++) {
                var denied=gatewayWorld().permit(bound,Effect.HARVEST,new SurvivalGateway.Cell(acquisition.dimension(),(int)x,(int)y,(int)z));
                if(denied!=null)return report.blocked("acquisition-authority:"+denied,denied);
            }
        return report;
    }
    public CompletableFuture<MaterialStatus> blueprintMaterialStatus(ServerPlayer player,UUID id) {
        ready();var owner=caller(player);var original=jobs.query(id,owner);
        if(!BlueprintMaterials.valid(original.request().request()))throw new IllegalArgumentException("Blueprint staging Job required");
        var result=new CompletableFuture<MaterialStatus>();
        prepareBlueprint(player,original.request().request()).whenComplete((prepared,failure)->server.execute(()->{
            try {if(failure!=null)throw new IllegalStateException("Blueprint inspection unavailable",failure);var current=jobs.query(id,owner);
                long held=BlueprintMaterials.held(current,leases,true);
                var report=blueprintMaterials.capture(prepared.inspection(),prepared.view(),current.request().request(),owner,Math.max(0,held));
                result.complete(new MaterialStatus(current,report,Math.max(0,held),current.state()==Jobs.State.SUCCEEDED&&report.canStage()
                        &&report.required()==BlueprintMaterials.amount(current.request().request())&&held==report.required()));
            }catch(Throwable error){result.completeExceptionally(error);}
        }));return result;
    }
    List<RetentionEvidenceStore.Aggregate> retentionFailures(TrustedContext caller) { ready(); return loaded.evidence().failures(caller); }
    public String scope(UUID principal) {
        ready();
        return loaded.initialState().worldId() + "/" + principal;
    }
    private TrustedContext caller(ServerPlayer player) {
        if (citizens == null) throw new IllegalStateException("Kernel not ready");
        return citizens.snapshot().citizens().stream().map(CitizenRegistry.Citizen::owner)
                .filter(owner -> owner.principal().id().equals(player.getUUID())).findFirst()
                .orElseGet(() -> new TrustedContext(new PrincipalRef(player.getUUID()),
                        new ScopeRef(loaded.initialState().worldId(), player.getUUID())));
    }
    public BootstrapController.Submission harvest(ServerPlayer player, UUID actorId, int amount,
            int x1, int y1, int z1, int x2, int y2, int z2, int cx, int cy, int cz) {
        return delivery(CropDelivery.ID,player,actorId,amount,x1,y1,z1,x2,y2,z2,cx,cy,cz);
    }
    public BootstrapController.Submission gatherBirch(ServerPlayer player,UUID actorId,int amount,
            int x1,int y1,int z1,int x2,int y2,int z2,int cx,int cy,int cz) {
        return delivery(BirchLogDelivery.ID,player,actorId,amount,x1,y1,z1,x2,y2,z2,cx,cy,cz);
    }
    public BootstrapController.Submission makeBirchPlanks(ServerPlayer player,UUID actorId,int amount,
            int sx,int sy,int sz,int dx,int dy,int dz) {return makeRecipe(BirchPlankProduction.ID,player,actorId,amount,sx,sy,sz,dx,dy,dz);}
    public BootstrapController.Submission composeBirchPlanks(ServerPlayer player,UUID actorId,int amount,ContainerRef source,ContainerRef destination,Cuboid birchSource) {
        ready();var owner=caller(player);var citizen=citizens.query(actorId,owner);
        if(amount<1 || amount>64)return new BootstrapController.Submission(UUID.randomUUID(),false,Reason.REQUEST_INVALID);
        return composeRequest(new CapabilityRequest(BirchPlankProduction.ID,Map.of("actor",new ActorValue(citizen.actor()),"amount",new IntValue(amount),
                "source",new ContainerValue(source),"destination",new ContainerValue(destination))),owner,player,birchSource);
    }
    public BootstrapController.Submission gatherOak(ServerPlayer player, UUID actorId, int amount,
            int x1, int y1, int z1, int x2, int y2, int z2, int cx, int cy, int cz) {
        return delivery(OakLogDelivery.ID,player,actorId,amount,x1,y1,z1,x2,y2,z2,cx,cy,cz);
    }
    public BootstrapController.Submission makePlanks(ServerPlayer player,UUID actorId,int amount,
            int sx,int sy,int sz,int dx,int dy,int dz) {return makeRecipe(OakPlankProduction.ID,player,actorId,amount,sx,sy,sz,dx,dy,dz);}
    public BootstrapController.Submission makeAxes(ServerPlayer player,UUID actorId,int amount,int sx,int sy,int sz,int dx,int dy,int dz,int tx,int ty,int tz) {
        ready();var owner=caller(player);var citizen=citizens.query(actorId,owner);
        if(amount<1 || amount>4)return new BootstrapController.Submission(UUID.randomUUID(),false,Reason.REQUEST_INVALID);
        String dimension=citizen.actor().dimension();
        return submitRequest(new CapabilityRequest(WoodenAxeProduction.ID,Map.of("actor",new ActorValue(citizen.actor()),"amount",new IntValue(amount),
                "source",new ContainerValue(new ContainerRef(dimension,sx,sy,sz)),"destination",new ContainerValue(new ContainerRef(dimension,dx,dy,dz)),
                "table",new FacilityValue(new FacilityRef(dimension,tx,ty,tz)))),owner,player);
    }
    public BootstrapController.Submission composeAxes(ServerPlayer player,UUID actorId,int amount,ContainerRef source,ContainerRef destination,FacilityRef table,Cuboid oakSource) {
        ready();var owner=caller(player);var citizen=citizens.query(actorId,owner);
        if(amount<1||amount>4)return new BootstrapController.Submission(UUID.randomUUID(),false,Reason.REQUEST_INVALID);
        var request=new CapabilityRequest(WoodenAxeProduction.ID,Map.of("actor",new ActorValue(citizen.actor()),"amount",new IntValue(amount),"source",new ContainerValue(source),"destination",new ContainerValue(destination),"table",new FacilityValue(table)));
        return composeRequest(request,owner,player,oakSource);
    }
    public BootstrapController.Submission provisionAxe(ServerPlayer player,UUID actorId,ContainerRef source,ContainerRef destination,FacilityRef target,Cuboid oakSource){
        ready();var owner=caller(player);var citizen=citizens.query(actorId,owner);UUID id=UUID.randomUUID();
        var request=new CapabilityRequest(WoodenAxeProduction.ID,Map.of("actor",new ActorValue(citizen.actor()),"amount",new IntValue(1),"source",new ContainerValue(source),"destination",new ContainerValue(destination),"table",new FacilityValue(target)));
        if(!source.dimension().equals(citizen.actor().dimension()) || oakSource==null || !oakSource.dimension().equals(source.dimension()) || sourceCells(oakSource)>256)
            return new BootstrapController.Submission(id,false,Reason.REQUEST_INVALID);
        var checked=observeFacilityPreparation(request);
        if(checked.reason()!=null)return new BootstrapController.Submission(id,false,checked.reason());
        if(checked.existing())return composeRequest(request,owner,player,oakSource);
        var bound=new ValidatedRequest(request,owner,checked.axe().reference());
        for(long x=oakSource.minX();x<=oakSource.maxX();x++)for(long y=oakSource.minY();y<=oakSource.maxY();y++)for(long z=oakSource.minZ();z<=oakSource.maxZ();z++){
            var denied=gatewayWorld().permit(bound,Effect.HARVEST,new SurvivalGateway.Cell(oakSource.dimension(),(int)x,(int)y,(int)z));
            if(denied!=null)return new BootstrapController.Submission(id,false,denied);
        }
        jobs.tick();var change=jobs.stageComposition(id,request,owner,bound.observation(),ResourceComposition.allowance(clock.millis()));
        if(change.accepted())prerequisites.stageFacility(id,oakSource);
        return new BootstrapController.Submission(id,change.accepted(),change.reason());
    }
    private FabricPrerequisiteRuntime.FacilityObservation observeFacilityPreparation(CapabilityRequest request){
        var actor=((ActorValue)request.arguments().get("actor")).value();var citizen=citizens.find(actor.citizenId());
        if(citizen==null || !controls(actor,citizen.owner()))throw new SecurityException("Current facility authority");
        var context=citizen.owner();long tick=server.overworld().getGameTime();var ref=new ObservationRef(UUID.randomUUID(),tick,actor.dimension());
        var bound=new ValidatedRequest(request,context,ref);var existing=gatewayWorld().recipePreflight(bound);
        if(existing.reason()==null && existing.status()==ObservationStatus.PRESENT)return new FabricPrerequisiteRuntime.FacilityObservation(true,observe(request,actor),null,null);
        if(existing.reason()!=Reason.FACILITY_MISSING)return new FabricPrerequisiteRuntime.FacilityObservation(false,null,null,existing.reason()==null?Reason.STALE_OBSERVATION:existing.reason());
        var placement=FacilityPrerequisites.placement(request);var site=gatewayWorld().placementPreflight(new ValidatedRequest(placement,context,ref));
        if(site.reason()!=null)return new FabricPrerequisiteRuntime.FacilityObservation(false,null,null,site.reason());
        var current=gatewayWorld().futureFacilityRecipe(bound);
        if(current.reason()!=null)return new FabricPrerequisiteRuntime.FacilityObservation(false,null,null,current.reason());
        var area=ResourceDelivery.sourceArea(request);var source=((ContainerValue)request.arguments().get("source")).value();var definition=RecipeProduction.of(WoodenAxeProduction.ID);
        var planks=leases.availableStock(definition.stock(context.scope().worldId(),source),current.availableInputs(),context);
        var sticks=leases.availableStock(definition.secondaryStock(context.scope().worldId(),source),current.availableSecondary(),context);
        if(!planks.usable() || !sticks.usable())return new FabricPrerequisiteRuntime.FacilityObservation(false,null,null,Reason.STALE_OBSERVATION);
        var counts=Map.ofEntries(Map.entry("input_planks",planks.available()),Map.entry("required_planks",3L),Map.entry("missing_planks",Math.max(0,3-planks.available())),
                Map.entry("input_sticks",sticks.available()),Map.entry("required_sticks",2L),Map.entry("missing_sticks",Math.max(0,2-sticks.available())),Map.entry("output_capacity",current.outputCapacity()),
                Map.entry("recipe_supported",1L),Map.entry("unknown_cells",0L),Map.entry("missing_facility",1L),Map.entry("site_usable",1L));
        var preview=new ObservationSnapshot(ref,ObservationStatus.PRESENT,"source:"+UUID.nameUUIDFromBytes(area.toString().getBytes(StandardCharsets.UTF_8)),tick,1,counts);
        return new FabricPrerequisiteRuntime.FacilityObservation(false,preview,observe(placement,actor),null);
    }
    private BootstrapController.Submission composeRequest(CapabilityRequest request,TrustedContext owner,ServerPlayer player,Cuboid oakSource) {
        UUID id=UUID.randomUUID();var actor=((ActorValue)request.arguments().get("actor")).value();
        if(player==null||!player.getUUID().equals(owner.principal().id())||!controls(actor,owner))return new BootstrapController.Submission(id,false,Reason.AUTHORITY_DENIED);
        if(villager(actor)==null||villager(actor).level()!=player.level())return new BootstrapController.Submission(id,false,Reason.ACTOR_UNAVAILABLE);
        try {
            if(oakSource!=null && (!oakSource.dimension().equals(actor.dimension())||sourceCells(oakSource)>256))return new BootstrapController.Submission(id,false,Reason.REQUEST_INVALID);
            var bound=new ValidatedRequest(request,owner,new ObservationRef(UUID.randomUUID(),server.overworld().getGameTime(),actor.dimension()));
            if(oakSource!=null)for(long x=oakSource.minX();x<=oakSource.maxX();x++)for(long y=oakSource.minY();y<=oakSource.maxY();y++)for(long z=oakSource.minZ();z<=oakSource.maxZ();z++) {
                var reason=gatewayWorld().permit(bound,Effect.HARVEST,new SurvivalGateway.Cell(oakSource.dimension(),(int)x,(int)y,(int)z));
                if(reason!=null)return new BootstrapController.Submission(id,false,reason);
            }
            var preflight=gatewayWorld().recipePreflight(bound);
            if(preflight.reason()!=null)return new BootstrapController.Submission(id,false,preflight.reason());
            if(preflight.outputCapacity()<((IntValue)request.arguments().get("amount")).value())return new BootstrapController.Submission(id,false,Reason.TARGET_UNAVAILABLE);
            jobs.tick();
            var change=jobs.stageComposition(id,request,owner,bound.observation(),ResourceComposition.allowance(clock.millis()));
            if(change.accepted())prerequisites.stage(id,oakSource);
            return new BootstrapController.Submission(id,change.accepted(),change.reason());
        }catch(IllegalArgumentException|ArithmeticException invalid){return new BootstrapController.Submission(id,false,Reason.REQUEST_INVALID);}
    }
    private Cuboid compositionOakEnvelope(ActorRef actor) {
        var v=villager(actor);if(v==null)return null;var at=v.blockPosition();
        // One explicit bounded implicit envelope: 9*9*3=243 loaded cells. No substitution of explicit sources.
        return new Cuboid(actor.dimension(),at.getX()-4,at.getY()-1,at.getZ()-4,at.getX()+4,at.getY()+1,at.getZ()+4);
    }
    private ObservationSnapshot observePrerequisite(CapabilityRequest request) {
        var actor=((ActorValue)request.arguments().get("actor")).value();var citizen=citizens.find(actor.citizenId());
        if(citizen==null||!controls(actor,citizen.owner()))throw new SecurityException("Current resource authority");
        var bound=new ValidatedRequest(request,citizen.owner(),new ObservationRef(UUID.randomUUID(),server.overworld().getGameTime(),actor.dimension()));
        if(LogAcquisition.is(request.capability())) {
            var area=((AreaValue)request.arguments().get("source")).value();
            for(long x=area.minX();x<=area.maxX();x++)for(long y=area.minY();y<=area.maxY();y++)for(long z=area.minZ();z<=area.maxZ();z++)
                if(gatewayWorld().permit(bound,Effect.HARVEST,new SurvivalGateway.Cell(area.dimension(),(int)x,(int)y,(int)z))==Reason.AUTHORITY_DENIED)throw new SecurityException("Explicit source denied");
        }
        return observe(request,actor);
    }
    public BootstrapController.Submission makeTables(ServerPlayer player,UUID actorId,int amount,
            int sx,int sy,int sz,int dx,int dy,int dz) {return makeRecipe(CraftingTableProduction.ID,player,actorId,amount,sx,sy,sz,dx,dy,dz);}
    public BootstrapController.Submission placeTable(ServerPlayer player,UUID actorId,ContainerRef source,FacilityRef target) {
        ready();var owner=caller(player);var citizen=citizens.query(actorId,owner);
        return submitRequest(new CapabilityRequest(CraftingTablePlacement.ID,Map.of("actor",new ActorValue(citizen.actor()),"amount",new IntValue(1),"source",new ContainerValue(source),"target",new FacilityValue(target))),owner,player);
    }
    public BootstrapController.Submission composeTables(ServerPlayer player,UUID actorId,int amount,ContainerRef source,ContainerRef destination,Cuboid oakSource) {
        ready();var owner=caller(player);var citizen=citizens.query(actorId,owner);
        if(amount<1 || amount>4)return new BootstrapController.Submission(UUID.randomUUID(),false,Reason.REQUEST_INVALID);
        return composeRequest(new CapabilityRequest(CraftingTableProduction.ID,Map.of("actor",new ActorValue(citizen.actor()),"amount",new IntValue(amount),
                "source",new ContainerValue(source),"destination",new ContainerValue(destination))),owner,player,oakSource);
    }
    public BootstrapController.Submission makeSticks(ServerPlayer player,UUID actorId,int amount,
            int sx,int sy,int sz,int dx,int dy,int dz) {return makeRecipe(StickProduction.ID,player,actorId,amount,sx,sy,sz,dx,dy,dz);}
    private BootstrapController.Submission makeRecipe(CapabilityId capability,ServerPlayer player,UUID actorId,int amount,
            int sx,int sy,int sz,int dx,int dy,int dz) {
        ready();var owner=caller(player);var citizen=citizens.find(actorId);
        if(citizen==null)return new BootstrapController.Submission(UUID.randomUUID(),false,Reason.REQUEST_INVALID);
        if(!citizen.owner().equals(owner))throw new SecurityException("This kernel run belongs to another principal");
        if(amount<1 || amount>RecipeProduction.of(capability).maximumAmount())return new BootstrapController.Submission(UUID.randomUUID(),false,Reason.REQUEST_INVALID);
        return submitRequest(new CapabilityRequest(capability,Map.of("actor",new ActorValue(citizen.actor()),"amount",new IntValue(amount),
                "source",new ContainerValue(new ContainerRef(citizen.actor().dimension(),sx,sy,sz)),
                "destination",new ContainerValue(new ContainerRef(citizen.actor().dimension(),dx,dy,dz)))),owner,player);
    }
    private BootstrapController.Submission delivery(CapabilityId capability,ServerPlayer player, UUID actorId, int amount,
            int x1, int y1, int z1, int x2, int y2, int z2, int cx, int cy, int cz) {
        ready();
        TrustedContext owner = caller(player);
        var enrolled = citizens.find(actorId);
        if (enrolled == null)
            return new BootstrapController.Submission(UUID.randomUUID(), false,
                    Reason.REQUEST_INVALID);
        if (!enrolled.owner().equals(owner))
            throw new SecurityException("This kernel run belongs to another principal");
        ActorRef actor = enrolled.actor();
        var villager = villager(actor);
        if (villager == null || villager.level() != player.level())
            return new BootstrapController.Submission(UUID.randomUUID(), false,
                    Reason.ACTOR_UNAVAILABLE);
        CapabilityRequest request;
        try {
            var source = new Cuboid(actor.dimension(), x1, y1, z1, x2, y2, z2);
            if (sourceCells(source) > 256)
                return new BootstrapController.Submission(UUID.randomUUID(), false,
                        Reason.REQUEST_INVALID);
            request = new CapabilityRequest(capability, Map.of(
                    "actor", new ActorValue(actor), "amount", new IntValue(amount),
                    "source", new AreaValue(source),
                    "destination", new ContainerValue(new ContainerRef(actor.dimension(),
                            cx, cy, cz))));
        } catch (IllegalArgumentException | ArithmeticException malformed) {
            return new BootstrapController.Submission(UUID.randomUUID(), false,
                    Reason.REQUEST_INVALID);
        }
        return submitRequest(request, owner, player);
    }
    private BootstrapController.Submission submitRequest(CapabilityRequest request, TrustedContext owner,
                                                        ServerPlayer player) {return submitRequest(request,owner,player,false);}
    private BootstrapController.Submission submitRequest(CapabilityRequest request,TrustedContext owner,ServerPlayer player,boolean composition) {
        var actor = ((ActorValue)request.arguments().get("actor")).value();
        if (player == null || !player.getUUID().equals(owner.principal().id()) || !controls(actor, owner))
            return new BootstrapController.Submission(UUID.randomUUID(), false, Reason.AUTHORITY_DENIED);
        var entity = villager(actor);
        if (entity == null || entity.level() != player.level())
            return new BootstrapController.Submission(UUID.randomUUID(), false, Reason.ACTOR_UNAVAILABLE);
        if(CraftingTablePlacement.is(request.capability())) {
            if(!CraftingTablePlacement.valid(request))return new BootstrapController.Submission(UUID.randomUUID(),false,Reason.REQUEST_INVALID);
            var bound=new ValidatedRequest(request,owner,new ObservationRef(UUID.randomUUID(),server.overworld().getGameTime(),actor.dimension()));
            var current=gatewayWorld().placementPreflight(bound);
            if(current.reason()!=null)return new BootstrapController.Submission(UUID.randomUUID(),false,current.reason());
            var free=leases.availableStock(LeasedGateway.sourceResource(owner.scope().worldId(),bound),current.availableItems(),owner);
            if(!free.usable() || free.available()<1)return new BootstrapController.Submission(UUID.randomUUID(),false,free.reason()==null?Reason.RESOURCE_MISSING:free.reason());
        }
        if(RecipeProduction.is(request.capability())) {
            if(!(request.arguments().get("amount") instanceof IntValue amount) || amount.value()<1 || amount.value()>RecipeProduction.of(request.capability()).maximumAmount())
                return new BootstrapController.Submission(UUID.randomUUID(),false,Reason.REQUEST_INVALID);
            var bound=new ValidatedRequest(request,owner,new ObservationRef(UUID.randomUUID(),server.overworld().getGameTime(),actor.dimension()));
            var current=gatewayWorld().recipePreflight(bound);
            if(current.reason()!=null)return new BootstrapController.Submission(UUID.randomUUID(),false,current.reason());
            leases.tick();
            var free=leases.availableStock(RecipeProduction.of(request.capability()).stock(owner.scope().worldId(),
                    ((ContainerValue)request.arguments().get("source")).value()),current.availableInputs(),owner);
            if(!free.usable())return new BootstrapController.Submission(UUID.randomUUID(),false,free.reason()==null ? Reason.STALE_OBSERVATION : free.reason());
            long needed=RecipeProduction.of(request.capability()).inputs(amount.value());
            if(RecipeProduction.of(request.capability()).axe()) {
                var d=RecipeProduction.of(request.capability());var source=((ContainerValue)request.arguments().get("source")).value();
                var secondary=leases.availableStock(d.secondaryStock(owner.scope().worldId(),source),current.availableSecondary(),owner);
                if(!secondary.usable())return new BootstrapController.Submission(UUID.randomUUID(),false,secondary.reason()==null ? Reason.STALE_OBSERVATION : secondary.reason());
                long sticks=d.secondaryInputs(amount.value());
                if(free.available()<needed || secondary.available()<sticks) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Oak planks required="+needed+" available="+free.available()+" missing="+Math.max(0,needed-free.available())
                            +"; sticks required="+sticks+" available="+secondary.available()+" missing="+Math.max(0,sticks-secondary.available())));
                    if(composition)return composeRequest(request,owner,player,compositionOakEnvelope(actor));
                    return new BootstrapController.Submission(UUID.randomUUID(),false,Reason.RESOURCE_MISSING);
                }
            }
            if(free.available()<needed) {
                if(composition && (request.capability().equals(CraftingTableProduction.ID) || request.capability().equals(BirchPlankProduction.ID)))return composeRequest(request,owner,player,compositionOakEnvelope(actor));
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal((request.capability().equals(BirchPlankProduction.ID) ? "Birch logs required=" : !LogAcquisition.log(RecipeProduction.of(request.capability()).input()) ? "Oak planks required=" : "Oak logs required=")+needed+" available="+free.available()+" missing="+(needed-free.available())));
                return new BootstrapController.Submission(UUID.randomUUID(),false,Reason.RESOURCE_MISSING);
            }
            if(current.outputCapacity()<amount.value())return new BootstrapController.Submission(UUID.randomUUID(),false,Reason.TARGET_UNAVAILABLE);
        }
        if(LogAcquisition.is(request.capability())) {
            var area=((AreaValue)request.arguments().get("source")).value();
            if(sourceCells(area)>256) return new BootstrapController.Submission(UUID.randomUUID(),false,Reason.REQUEST_INVALID);
            var bound=new ValidatedRequest(request,owner,new ObservationRef(UUID.randomUUID(),server.overworld().getGameTime(),actor.dimension()));
            var access=gatewayWorld();
            var sourceDenied=access.permit(bound,Effect.HARVEST,new SurvivalGateway.Cell(area.dimension(),area.minX(),area.minY(),area.minZ()));
            if(sourceDenied!=null) return new BootstrapController.Submission(UUID.randomUUID(),false,sourceDenied);
            var box=((ContainerValue)request.arguments().get("destination")).value();
            var denied=access.permit(bound,Effect.TRANSFER,new SurvivalGateway.Cell(box.dimension(),box.x(),box.y(),box.z()));
            if(denied!=null) return new BootstrapController.Submission(UUID.randomUUID(),false,denied);
        }
        // Commands precede the end-of-tick dispatcher. Acknowledge completed owner
        // publication before admission so the next background write cannot starve it.
        jobs.tick();
        return controller.submit(request, owner);
    }
    public long languageCalls() { return needle == null ? 0 : needle.calls(); }
    long generationCalls() { ready();return controller.modelCalls(); }
    AIWorkBroker inferenceBroker() { ready(); return broker; }
    public List<AIWorkBroker.View> inferenceStatus(ServerPlayer player) { ready(); return broker.queueStatus(caller(player)); }
    /** Model-independent durable queue; the actor is a responsibility anchor, not forced worker selection. */
    public BootstrapController.Submission queueHarvest(ServerPlayer player, UUID citizenId, int amount,
                                                       Cuboid source, ContainerRef destination) {
        ready();TrustedContext owner=caller(player);UUID id=UUID.randomUUID();
        var citizen=citizens.query(citizenId,owner);
        var actor=citizen.actor();
        if(villager(actor)==null || villager(actor).level()!=player.level())
            return new BootstrapController.Submission(id,false,Reason.ACTOR_UNAVAILABLE);
        var request=new CapabilityRequest(CropDelivery.ID,Map.of("actor",new ActorValue(actor),"amount",new IntValue(amount),
                "source",new AreaValue(source),"destination",new ContainerValue(destination)));
        var observation=observe(request,actor);
        jobs.tick();
        var change=jobs.create(id,id,request,owner,observation.reference(),executionLimits(clock.millis()).total(),List.of());
        return new BootstrapController.Submission(id,change.accepted(),change.reason());
    }
    public Jobs.Job queuedJob(ServerPlayer player,UUID id){ready();return jobs.query(id,caller(player));}
    /** Read-only and private. Enabled explicitly for initial qualification, off by default. */
    public dev.aivillages.core.kernel.runtime.RecoveredJobOwners.View activity(ServerPlayer player, UUID root) {
        ready();
        if (!activityViewEnabled) throw new IllegalStateException("Activity view disabled");
        return recoveredOwners.view(root, caller(player));
    }
    public dev.aivillages.core.kernel.runtime.RecoveredJobOwners.Page activities(ServerPlayer player, int offset, int maximum) {
        ready();
        if (!activityViewEnabled) throw new IllegalStateException("Activity view disabled");
        return recoveredOwners.page(caller(player), offset, maximum);
    }
    dev.aivillages.core.kernel.runtime.RecoveredJobOwners recoveryOwner() { ready(); return recoveredOwners; }
    public List<UUID> queuedJobIds(ServerPlayer player,boolean cancellable){
        ready();var origin=caller(player);
        return jobs.snapshot().jobs().stream().filter(j->j.origin().equals(origin)&&!controller.ownsSubmission(j.submissionId()))
                .filter(j->!cancellable||!j.state().terminal()||j.state()==Jobs.State.SUCCEEDED&&BlueprintMaterials.valid(j.request().request())).map(Jobs.Job::id).toList();
    }
    public Jobs.Change cancelQueuedJob(ServerPlayer player,UUID id){ready();var owner=caller(player);jobs.tick();var job=jobs.query(id,owner);return jobs.cancel(id,job.guard(),owner);}
    public List<WorkerDispatcher.Decision> dispatchDiagnostics(ServerPlayer player,UUID id){ready();return dispatcher.diagnostics(id,caller(player));}
    void clearDispatchCache(){ready();dispatcher.clearPolicyCache();dispatchRevision=-1;dispatchScopes=List.of();}
    public LanguageRequests.View ask(ServerPlayer player, String message) {
        ready();
        var view = language.ask(message, caller(player));
        if (view.phase() == LanguageRequests.Phase.INTERPRETING) interpretingPlayer = player;
        return view;
    }
    public LanguageRequests.View interpretationStatus(ServerPlayer player, UUID id) {
        ready(); return language.status(id, caller(player));
    }
    public LanguageRequests.View cancelInterpretation(ServerPlayer player, UUID id) {
        ready(); return language.cancel(id, caller(player));
    }
    public List<UUID> runOrTicketIds(ServerPlayer player, boolean cancellableOnly) {
        ready(); var owner = caller(player);
        var ids = new ArrayList<UUID>();
        ids.addAll(cancellableOnly ? language.cancellableTicketIds(owner) : language.ticketIds(owner));
        ids.addAll(cancellableOnly ? controller.cancellableRunIds(owner) : controller.runIds(owner));
        return List.copyOf(ids);
    }
    public BootstrapController.View status(ServerPlayer player, UUID id) {
        if (controller == null) throw new IllegalStateException("Kernel not ready");
        return controller.status(id, caller(player));
    }
    public BootstrapController.View cancel(ServerPlayer player, UUID id) {
        if (controller == null) throw new IllegalStateException("Kernel not ready");
        return controller.cancel(id, caller(player));
    }
    public void inference(boolean enabled) {
        ready();
        if (enabled && !generationConfigured && languagePort == null)
            throw new IllegalStateException("Set COGNITIVECRAFT_OLLAMA_MODEL or COGNITIVECRAFT_NEEDLE_DIR for local inference");
        controller.inference(enabled);
        broker.enabled(enabled);
        language.enabled(enabled);
    }
    public String catalog(ServerPlayer player) {
        ready(); var owner = caller(player);
        if (citizens.snapshot().citizens().stream().noneMatch(c -> c.owner().equals(owner)))
            throw new SecurityException("This kernel catalog requires an enrolled citizen in your scope");
        var page = loaded.repository().page(CropDelivery.ID, "", 8);
        var birch=loaded.repository().page(BirchLogDelivery.ID,"",8);var birchPlanks=loaded.repository().page(BirchPlankProduction.ID,"",8);
        var oak=loaded.repository().page(OakLogDelivery.ID,"",8);
        var planks=loaded.repository().page(OakPlankProduction.ID,"",8);
        var sticks=loaded.repository().page(StickProduction.ID,"",8);
        var axes=loaded.repository().page(WoodenAxeProduction.ID,"",8);
        var tables=loaded.repository().page(CraftingTableProduction.ID,"",8);
        var placement=loaded.repository().page(CraftingTablePlacement.ID,"",8);
        return "catalog=" + page.candidates().stream().map(candidate ->
                candidate.ref().sha256() + "/" + candidate.admission() + "/"
                        + candidate.compatibility().status()).toList()
                + " birchCatalog="+birch.candidates().stream().map(c->c.ref().sha256()+"/"+c.admission()+"/"+c.compatibility().status()).toList()
                + " birchPlankCatalog="+birchPlanks.candidates().stream().map(c->c.ref().sha256()+"/"+c.admission()+"/"+c.compatibility().status()).toList()
                + " oakCatalog="+oak.candidates().stream().map(c -> c.ref().sha256()+"/"+c.admission()+"/"+c.compatibility().status()).toList()
                + " plankCatalog="+planks.candidates().stream().map(c -> c.ref().sha256()+"/"+c.admission()+"/"+c.compatibility().status()).toList()
                + " stickCatalog="+sticks.candidates().stream().map(c -> c.ref().sha256()+"/"+c.admission()+"/"+c.compatibility().status()).toList()
                + " axeCatalog="+axes.candidates().stream().map(c->c.ref().sha256()+"/"+c.admission()+"/"+c.compatibility().status()).toList()
                + " tableItemCatalog="+tables.candidates().stream().map(c->c.ref().sha256()+"/"+c.admission()+"/"+c.compatibility().status()).toList()
                + " placementCatalog="+placement.candidates().stream().map(c->c.ref().sha256()+"/"+c.admission()+"/"+c.compatibility().status()).toList()
                + " revision=" + page.revision() + " recentModelCalls=" + controller.modelCalls(owner)
                + " inference=" + controller.inferenceEnabled();
    }

    public CitizenRegistry.Change name(ServerPlayer player, UUID citizenId, String name) {
        ready(); return citizens.rename(citizenId, name, caller(player));
    }
    public CitizenRegistry.Page citizens(ServerPlayer player) {
        ready(); return citizens.citizens(caller(player));
    }
    public List<UUID> citizenIds(ServerPlayer player) {
        ready(); var owner = caller(player);
        return citizens(player).citizens().stream().filter(address -> controls(address.actor(), owner))
                .map(address -> address.actor().citizenId()).toList();
    }
    public CitizenRegistry.Addresses address(ServerPlayer player, String name) {
        ready(); return citizens.address(name, caller(player));
    }
    public CitizenRegistry.Addresses address(ServerPlayer player, UUID citizenId) {
        ready(); return citizens.address(citizenId, caller(player));
    }
    boolean jobsReady() { return jobs != null && jobs.ready(); }
    boolean leasesReady() { return leases != null && leases.ready(); }
    ResourceLeaseService leaseOwner() { return leases; }
    LeaseArchive leaseHistoryOwner() { return leaseArchive; }
    boolean identityReady() { return citizens != null && citizens.ready() && lifecycle != null
            && lifecycle.counters().queued() == 0 && citizens.snapshot().citizens().stream().noneMatch(c -> c.lifecycle().pending()); }
    CitizenRegistry.Citizen citizen(ServerPlayer player, UUID citizenId) {
        ready(); return citizens.query(citizenId, caller(player));
    }

    public void entityLoaded(Entity entity) {
        if (lifecycleAdapter != null && !closed) lifecycleAdapter.loaded(entity);
    }
    public void entityUnloaded(Entity entity) {
        if (lifecycleAdapter != null && !closed) lifecycleAdapter.unloaded(entity);
    }
    public void entityUnavailable(Entity entity) {
        if (lifecycleAdapter != null && !closed) lifecycleAdapter.died(entity);
    }
    public void entityReplacementUnresolved(Entity entity) {
        if (lifecycleAdapter != null && !closed) lifecycleAdapter.replacement(entity);
    }
    CitizenLifecycle lifecycleOwner() { return lifecycle; }
    FabricCitizenLifecycle lifecycleAdapter() { return lifecycleAdapter; }
    int lifecycleLookups() { return lifecycleAdapter == null ? 0 : lifecycleAdapter.lastLookups(); }

    static void refreshLoadedAvailability(CitizenRegistry citizens, CitizenRegistry.Citizen citizen,
                                          boolean loaded) {
        if (loaded) citizens.observeAvailability(citizen.actor().citizenId(), CitizenRegistry.Availability.LOADED);
        // The current unload observation can precede asynchronous durable publication.
        else if (citizens.query(citizen.actor().citizenId(), citizen.owner()).availability()
                == CitizenRegistry.Availability.LOADED)
            citizens.observeAvailability(citizen.actor().citizenId(), CitizenRegistry.Availability.UNKNOWN);
    }

    /** Fixtures can await worker cleanup without blocking the game thread. */
    CompletableFuture<Void> storeClosure() { return storeClosure; }
    PrimitiveDiagnosticService.Status diagnosticStatus() {
        return primitiveDiagnostics==null?null:primitiveDiagnostics.status();
    }
    CompletableFuture<Void> diagnosticClosure() {
        return primitiveDiagnostics==null?CompletableFuture.completedFuture(null):primitiveDiagnostics.closeAsync();
    }

    @Override public void close() {
        if (closeRequested) return;
        closeRequested = true;
        blueprintClosing.set(true);
        if(accomplishmentService!=null)accomplishmentService.close();
        if(accessService!=null)accessService.close();
        if(primitiveDiagnostics!=null)primitiveDiagnostics.close();
        if(dispatcher!=null)dispatcher.close();
        if(recoveredOwners!=null)recoveredOwners.close();
        if(historyService!=null)historyService.stopAdmissions();
        if(planner!=null)planner.close();
        if(organizations!=null)organizations.close();
        if(mandates!=null)mandates.close();
        if(broker!=null)broker.close();
        closed = true;
        if (model != null) model.close();
        if (language != null) language.close();
        if (needle != null) needle.close();
        interpretingPlayer = null;
        opening.whenComplete((opened, failed) -> {
            if (opened != null) {
                observationSource = null;
                opened.knowledgeService().close();
                opened.retentionService().close();
                opened.retrieval().close();
                worker.execute(() -> {
                    opened.retentionService().closeWorker();
                    opened.knowledgeService().closeWorker();
                    Exception failedClose = null;
                    for (AutoCloseable store : List.of(opened.access(),opened.accomplishments(),opened.mandates(),opened.organizations(),opened.blueprints(),opened.plans(),opened.observations(), opened.evidence(), opened.repository(), opened.identities(), opened.journal(), opened.jobs(), opened.leases())) {
                        try { store.close(); }
                        catch (Exception closeFailure) {
                            AiVillages.LOG.error("Kernel close failed", closeFailure);
                            if (failedClose == null) failedClose = closeFailure;
                            else failedClose.addSuppressed(closeFailure);
                        }
                    }
                    if(opened.history()!=null)try{opened.history().close();}catch(Exception closeFailure){
                        AiVillages.LOG.error("Runtime history close failed",closeFailure);
                        if(failedClose==null)failedClose=closeFailure;else failedClose.addSuppressed(closeFailure);
                    }
                    if (failedClose == null) storeClosure.complete(null);
                    else storeClosure.completeExceptionally(failedClose);
                });
            }
            else storeClosure.complete(null);
            worker.shutdown();
        });
    }
}
