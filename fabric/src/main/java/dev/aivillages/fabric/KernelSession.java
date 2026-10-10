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
                          boolean leasesReadOnly) { }

    private final MinecraftServer server;
    private final Clock clock = Clock.systemUTC();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(run -> {
        Thread thread = new Thread(run, "cognitivecraft-world-storage");
        thread.setDaemon(true);
        return thread;
    });
    private final CompletableFuture<Loaded> opening;
    private final LocalGenerationAdapter model;
    private final GenerationPort generationSource;
    private final boolean generationConfigured;
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
    private ResourceLeaseService leases;
    private LeasedGateway leasedGateway;
    private LeasedGateway dispatchGateway;
    private WorkerDispatcher dispatcher;
    private List<TrustedContext> dispatchScopes=List.of();
    private long dispatchRevision=-1;
    private int dispatchScopeCursor;
    private final Map<UUID, CitizenRegistry.Availability> availabilityFacts = new LinkedHashMap<>();
    private UUID pendingEnrollmentEntity;
    private int availabilityCursor;
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
        this.server = server;
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
        CapabilityCatalog capabilities = id -> id.equals(CropDelivery.ID)
                ? Optional.of(CropDelivery.SPEC) : Optional.empty();
        opening = CompletableFuture.supplyAsync(() -> {
            BootstrapJournal journal = null;
            JobJournal jobs = null;
            ResourceLeaseJournal leases = null;
            CitizenIdentityStore identities = null;
            VersionedSkillRepository repository = null;
            CapabilityRetrievalIndex retrieval = null;
            try {
                journal = BootstrapJournal.open(world);
                identities = CitizenIdentityStore.open(world, journal.state().worldId());
                if (!journal.state().externalIdentities()) {
                    var imported = identities.migrateBootstrap(journal.state());
                    journal.migrateIdentity(imported);
                } else {
                    if (identities.snapshot().migration() == null)
                        throw new IllegalStateException("Referenced citizen registry is missing");
                    journal.migrateIdentity(identities.snapshot());
                }
                jobs = JobJournal.open(world, journal.state().worldId());
                leases = ResourceLeaseJournal.open(world, journal.state().worldId());
                var guard = new ResearchAdmissionController.DecisionGuard();
                repository = VersionedSkillRepository.open(world,
                        new VersionedSkillRepository.RuntimeSnapshot("minecraft-26.3",
                                capabilities, GatewayPrimitives.instance()),
                        VersionedSkillRepository.Limits.defaults(), guard,
                        TrustedContext::equals, VersionedSkillRepository.FaultInjector.none());
                var lookups = new CapabilityRetrievalIndex.LookupRegistry(
                        Map.of("harvest wheat", CropDelivery.ID, "deliver wheat", CropDelivery.ID),
                        Map.of(CropDelivery.ID, java.util.Set.of("farming", "wheat")), capabilities);
                retrieval = new CapabilityRetrievalIndex(world.resolve(CapabilityRetrievalIndex.WORLD_RELATIVE_PATH),
                        new CapabilityRetrievalIndex.Identity(journal.state().worldId(), UUID.randomUUID()),
                        CapabilityRetrievalIndex.repositorySource(repository, lookups, () -> 0), lookups,
                        capabilities, CapabilityRetrievalIndex.Limits.defaults(), () -> System.nanoTime() / 1_000_000);
                return new Loaded(journal, journal.state(), repository, guard, identities,
                        identities.snapshot(), identities.readOnly() || journal.readOnly(), retrieval,
                        jobs, jobs.snapshot(), jobs.readOnly(), leases, leases.snapshot(), leases.readOnly());
            } catch (Exception failure) {
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
        if (controller == null) {
            if (!opening.isDone()) return;
            if (opening.isCompletedExceptionally()) {
                closed = true;
                AiVillages.LOG.error("CognitiveCraft kernel disabled", opening.handle(
                        (ignored, failure) -> failure).join());
                if (model != null) model.close();
                if (needle != null) needle.close();
                worker.shutdown();
                return;
            }
            loaded = opening.join();
            compose();
        }
        citizens.tick();
        loaded.retrieval().maintain(worker);
        refreshAvailability();
        if (pendingEnrollmentEntity != null && citizens.ready()) pendingEnrollmentEntity = null;
        language.tick();
        if (!language.pending()) interpretingPlayer = null;
        gateway.tick();
        leases.tick();
        leasedGateway.tick();
        broker.step();
        controller.tick();
        dispatchGateway.tick();
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

    private void compose() {
        citizens = new CitizenRegistry(loaded.initialIdentities(), (expected, next) ->
                CompletableFuture.supplyAsync(() -> {
                    try { return loaded.identities().replace(expected, next); }
                    catch (Exception failed) { throw new IllegalStateException(failed); }
                }, worker), CitizenRegistry.privateAddresses(), clock, loaded.identityReadOnly());
        CapabilityCatalog capabilities = id -> id.equals(CropDelivery.ID)
                ? Optional.of(CropDelivery.SPEC) : Optional.empty();
        var primitives = GatewayPrimitives.instance();
        var repository = loaded.repository();
        var staging = new ResearchAdmissionController.TrialArtifacts(repository);
        var grants = new ResearchAdmissionController.TrialGrants(clock);
        CapabilityResolver.ControlPolicy control = this::controls;
        AuthorityPolicy authority = (actor, effect, context) -> controls(actor, context)
                && villager(actor) != null;
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
        leases = new ResourceLeaseService(loaded.initialLeases(), (expected, next) ->
                CompletableFuture.supplyAsync(() -> {
                    try { return loaded.leases().replace(expected, next); }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                }, worker), (owner, caller) -> new JobLeaseOwners(jobs, clock).inspect(owner, caller),
                new FabricResourceLeaseWorld(server), ResourceLeases.Settings.production(),
                () -> server.overworld().getGameTime(), clock, loaded.leasesReadOnly());
        leasedGateway = new LeasedGateway(gateway, leases,
                (request, run) -> LeasedGateway.workspaces(new JobLeaseOwners(jobs, clock), request, run),
                () -> server.overworld().getGameTime(), 1200, 200, 64, 512);
        var executor = new BoundedSkillExecutor(staging, capabilities, primitives,
                CapabilityResolver.enrolledWorldSkills(control), grants, runControl, leasedGateway,
                leasedGateway::releaseRun, clock, () -> server.overworld().getGameTime(),
                new BoundedSkillExecutor.Settings(8, 16, 32, 64));
        var resolver = new CapabilityResolver.Engine(capabilities, primitives,
                loaded.retrieval().candidates(), loaded.retrieval().authoritativeFallback(),
                CapabilityResolver.cropDeliverySupport(), control,
                CapabilityResolver.enrolledWorldSkills(control), authority,
                CapabilityResolver.cropPrerequisites(),
                () -> server.overworld().getGameTime(), CapabilityResolver.Limits.defaults());
        broker = new AIWorkBroker(generationSource, clock, AIWorkBroker.Settings.defaults(),
                AIWorkBroker.sessionLimits(clock.millis()), request -> {
                    var actor = ((ActorValue)request.bound().request().arguments().get("actor")).value();
                    return control.controls(actor, request.bound().context())
                            && List.of(Effect.OBSERVE, Effect.HARVEST, Effect.PICKUP, Effect.TRANSFER).stream()
                            .allMatch(effect -> authority.currentlyAllows(actor, effect, request.bound().context()));
                }, AIWorkBroker.Sharing.privateScopes());
        var research = new ResearchAdmissionController(capabilities, primitives, repository,
                broker, new CropFixtureRunner(staging, capabilities, primitives,
                        CapabilityResolver.enrolledWorldSkills(control), worker, clock),
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
                new ResearchAdmissionController.Settings(2, 8, 2_048));
        RequestEnvironment environment = new RequestEnvironment() {
            @Override public boolean enrolled(ActorRef actor, TrustedContext context) {
                return controls(actor, context);
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
                }, worker), JobLifecycleStore.privateJobs(this::controls),
                cancellation -> controller.observeJobCancellation(cancellation), capabilities, environment,
                clock, Jobs.Settings.defaults(), loaded.jobsReadOnly());
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
                }}, environment, capabilities, this::researchLimits, this::executionLimits, clock, citizens, jobs);

        dispatchGateway=new LeasedGateway(gateway,leases,
                (request,run)->LeasedGateway.cropWorkspaces(new JobLeaseOwners(jobs,clock),request,run),
                ()->server.overworld().getGameTime(),1200,200,8,512);
        var dispatchExecutor=new BoundedSkillExecutor(staging,capabilities,primitives,
                CapabilityResolver.enrolledWorldSkills(control),grants,runControl,dispatchGateway,
                dispatchGateway::releaseRun,clock,()->server.overworld().getGameTime(),
                new BoundedSkillExecutor.Settings(8,16,32,64));
        dispatcher=new WorkerDispatcher(jobs,citizens,this::observe,resolver::resolve,
                WorkerDispatcher.executorPort(dispatchExecutor,repository),WorkerDispatcher.leasedClaims(dispatchGateway),
                job->!controller.ownsSubmission(job.submissionId()),clock,()->server.overworld().getGameTime(),
                WorkerDispatcher.Settings.production());

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
                return new FabricWorldReferenceResolver(worldAccess, citizens, caller, player, intent, clock);
            }
            @Override public BootstrapController.Submission submit(CapabilityRequest request, TrustedContext caller) {
                return submitRequest(request, caller, interpretingPlayer);
            }
            @Override public void cancel(UUID run, TrustedContext caller) { controller.cancel(run, caller); }
            @Override public List<UUID> cancellableRunIds(TrustedContext caller) {
                return controller.cancellableRunIds(caller);
            }
        }, clock);
    }

    private FabricGatewayWorld gatewayWorld() { return worldAccess; }
    private long sourceCells(Cuboid source) {
        return ((long)source.maxX() - source.minX() + 1)
                * ((long)source.maxY() - source.minY() + 1)
                * ((long)source.maxZ() - source.minZ() + 1);
    }
    private ObservationSnapshot observe(CapabilityRequest request, ActorRef actor) {
        Cuboid area = ((AreaValue)request.arguments().get("source")).value();
        int scanned = 0; long mature = 0, unknown = 0;
        if (sourceCells(area) > 256 || !area.dimension().equals(actor.dimension()))
            throw new IllegalArgumentException("Source exceeds kernel observation limit");
        var world = gatewayWorld();
        for (long x = area.minX(); x <= area.maxX(); x++)
            for (long y = area.minY(); y <= area.maxY(); y++)
                for (long z = area.minZ(); z <= area.maxZ(); z++) {
                    var crop = world.crop(new SurvivalGateway.Cell(area.dimension(),
                            (int)x, (int)y, (int)z));
                    scanned++;
                    if (crop.status() == ObservationStatus.UNKNOWN) unknown++;
                    else if (crop.matureWheat()) mature++;
                }
        long tick = server.overworld().getGameTime();
        String identity = "source:" + UUID.nameUUIDFromBytes(
                area.toString().getBytes(StandardCharsets.UTF_8));
        return new ObservationSnapshot(new ObservationRef(UUID.randomUUID(), tick,
                actor.dimension()), unknown > 0 ? ObservationStatus.UNKNOWN
                        : mature > 0 ? ObservationStatus.PRESENT : ObservationStatus.ABSENT,
                identity, tick, scanned,
                Map.of("mature_wheat", mature, "unknown_cells", unknown));
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
            request = new CapabilityRequest(CropDelivery.ID, Map.of(
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
                                                        ServerPlayer player) {
        var actor = ((ActorValue)request.arguments().get("actor")).value();
        if (player == null || !player.getUUID().equals(owner.principal().id()) || !controls(actor, owner))
            return new BootstrapController.Submission(UUID.randomUUID(), false, Reason.AUTHORITY_DENIED);
        var entity = villager(actor);
        if (entity == null || entity.level() != player.level())
            return new BootstrapController.Submission(UUID.randomUUID(), false, Reason.ACTOR_UNAVAILABLE);
        // Network commands run before the end-of-tick dispatcher. A completed owner write
        // must be acknowledged here, or its next background write can starve player work.
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
    public List<UUID> queuedJobIds(ServerPlayer player,boolean cancellable){
        ready();var origin=caller(player);
        return jobs.snapshot().jobs().stream().filter(j->j.origin().equals(origin)&&!controller.ownsSubmission(j.submissionId()))
                .filter(j->!cancellable||!j.state().terminal()).map(Jobs.Job::id).toList();
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
        return "catalog=" + page.candidates().stream().map(candidate ->
                candidate.ref().sha256() + "/" + candidate.admission() + "/"
                        + candidate.compatibility().status()).toList()
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
    boolean identityReady() { return citizens != null && citizens.ready(); }
    CitizenRegistry.Citizen citizen(ServerPlayer player, UUID citizenId) {
        ready(); return citizens.query(citizenId, caller(player));
    }

    public void entityLoaded(Entity entity) {
        if (entity instanceof Villager && entity.isAlive()) recordAvailability(entity, CitizenRegistry.Availability.LOADED);
    }
    public void entityUnloaded(Entity entity) {
        recordAvailability(entity, CitizenRegistry.Availability.UNLOADED);
    }
    public void entityUnavailable(Entity entity) {
        recordAvailability(entity, CitizenRegistry.Availability.UNAVAILABLE);
    }
    public void entityReplacementUnresolved(Entity entity) {
        recordAvailability(entity, CitizenRegistry.Availability.REPLACEMENT_UNRESOLVED);
    }
    private void recordAvailability(Entity entity, CitizenRegistry.Availability fact) {
        if (citizens == null || closed) return;
        citizens.snapshot().citizens().stream().filter(c -> c.actor().entityId().equals(entity.getUUID()))
                .findFirst().ifPresent(c -> {
                    UUID id = c.actor().citizenId();
                    var previous = availabilityFacts.get(id);
                    if (fact != CitizenRegistry.Availability.UNLOADED
                            || previous != CitizenRegistry.Availability.UNAVAILABLE
                            && previous != CitizenRegistry.Availability.REPLACEMENT_UNRESOLVED)
                        availabilityFacts.put(id, fact == CitizenRegistry.Availability.LOADED
                                && !c.actor().dimension().equals(entity.level().dimension().identifier().toString())
                                ? CitizenRegistry.Availability.UNKNOWN : fact);
                });
    }
    private void refreshAvailability() {
        if (!availabilityFacts.isEmpty()) {
            var entry = availabilityFacts.entrySet().iterator().next();
            var changed = citizens.observeAvailability(entry.getKey(), entry.getValue());
            if (changed.accepted()) availabilityFacts.remove(entry.getKey());
            return;
        }
        var rows = citizens.snapshot().citizens();
        if (rows.isEmpty()) return;
        availabilityCursor %= rows.size();
        var citizen = rows.get(availabilityCursor);
        availabilityCursor = (availabilityCursor + 1) % rows.size();
        var loadedActor = villager(citizen.actor());
        refreshLoadedAvailability(citizens, citizen, loadedActor != null);
    }

    static void refreshLoadedAvailability(CitizenRegistry citizens, CitizenRegistry.Citizen citizen,
                                          boolean loaded) {
        if (loaded) citizens.observeAvailability(citizen.actor().citizenId(), CitizenRegistry.Availability.LOADED);
        // An acknowledged runtime unload can precede its asynchronous metadata publication.
        // The current observation must win over the older durable LOADED row.
        else if (citizens.query(citizen.actor().citizenId(), citizen.owner()).availability()
                == CitizenRegistry.Availability.LOADED)
            citizens.observeAvailability(citizen.actor().citizenId(), CitizenRegistry.Availability.UNKNOWN);
    }

    /** Fixtures can await worker cleanup without blocking the game thread. */
    CompletableFuture<Void> storeClosure() { return storeClosure; }

    @Override public void close() {
        if (closeRequested) return;
        closeRequested = true;
        if(dispatcher!=null)dispatcher.close();
        if(broker!=null)broker.close();
        closed = true;
        if (model != null) model.close();
        if (language != null) language.close();
        if (needle != null) needle.close();
        interpretingPlayer = null;
        opening.whenComplete((opened, failed) -> {
            if (opened != null) {
                opened.retrieval().close();
                worker.execute(() -> {
                    Exception failedClose = null;
                    for (AutoCloseable store : List.of(opened.repository(), opened.identities(), opened.journal(), opened.jobs(), opened.leases())) {
                        try { store.close(); }
                        catch (Exception closeFailure) {
                            AiVillages.LOG.error("Kernel close failed", closeFailure);
                            if (failedClose == null) failedClose = closeFailure;
                            else failedClose.addSuppressed(closeFailure);
                        }
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
