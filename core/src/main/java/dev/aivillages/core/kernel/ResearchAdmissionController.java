package dev.aivillages.core.kernel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.concurrent.Executor;

import static dev.aivillages.core.kernel.Budgets.Kind;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static dev.aivillages.core.kernel.VersionedSkillRepository.*;

/** IMP-006 policy/schema 1. One bounded attempt; callbacks never mutate game state. */
public final class ResearchAdmissionController {
    private static final String CROP_BRIEF = "Produce schema-1 Skill IR for crop delivery. "
            + "Use registered primitive calls with exact signatures and typed bindings. "
            + "For each of count={param:amount} iterations, execute in causal order: "
            + "prepare=[move_to_source, harvest_next_wheat]; repeat observe_inventory for count={int:20} "
            + "while waiting for drops; finish=[pickup_tracked_wheat, move_to_destination, transfer_wheat]. "
            + "transfer_wheat uses amount={int:1}. Keep movement, harvest, pickup and transfer "
            + "as separate calls. Do not transfer before pickup or use any effect twice. "
            + "Give each call a distinct into name. End with result={param:amount}. ";
    public enum Phase {
        QUARANTINING, GENERATING, VALIDATING, FIXTURING, PREPARING_TRIAL, TRIALING,
        PUBLISHING, CANCELLING, TERMINAL
    }
    public record Settings(int maxCandidates, int maxDiagnostics, int maxContextBytes) {
        public Settings {
            if (maxCandidates < 1 || maxCandidates > 8 || maxDiagnostics < 1
                    || maxDiagnostics > 32 || maxContextBytes < 128 || maxContextBytes > 8_192)
                throw new IllegalArgumentException("Research bounds");
        }
    }
    public record FixtureEvidence(boolean passed, EvidenceRef evidence, Reason reason,
                                  int cases, long checkedWork) {
        public FixtureEvidence {
            if (passed ? evidence == null || reason != null : reason == null || evidence != null)
                throw new IllegalArgumentException("Fixture result");
            if (cases < 0 || cases > 16 || checkedWork < 0 || checkedWork > 4_096)
                throw new IllegalArgumentException("Fixture work");
        }
    }
    /** Runs deterministic, bounded fixtures off the server thread. */
    @FunctionalInterface public interface FixturePort {
        CompletionStage<FixtureEvidence> evaluate(SkillCompiler.CompiledSkill skill,
                ValidatedRequest bound, Budgets.ResearchLimits limits, Budgets.Ledger usage,
                BooleanSupplier cancelled);
    }
    /** The implementation drives IMP-003's restricted trial on the server thread. */
    public interface TrialPort {
        default boolean prepare(ValidatedRequest request, RunCorrelation correlation,
                                List<ArtifactRef> pinned) { return true; }
        BoundedSkillExecutor.Start start(ValidatedRequest request, ArtifactRef ref,
                RunCorrelation correlation, Budgets.ExecutionLimits limits, Budgets.Ledger usage,
                BoundedSkillExecutor.TrialPermit permit);
        BoundedSkillExecutor.Progress tick(TrustedContext owner);
        BoundedSkillExecutor.Progress cancel(TrustedContext owner);
    }
    public static final class ExecutorTrialPort implements TrialPort {
        private final BoundedSkillExecutor executor;
        private BoundedSkillExecutor.Run run;
        public ExecutorTrialPort(BoundedSkillExecutor executor) { this.executor = Objects.requireNonNull(executor); }
        @Override public BoundedSkillExecutor.Start start(ValidatedRequest request, ArtifactRef ref,
                RunCorrelation correlation, Budgets.ExecutionLimits limits, Budgets.Ledger usage,
                BoundedSkillExecutor.TrialPermit permit) {
            if (run != null) throw new IllegalStateException("One trial at a time");
            BoundedSkillExecutor.Start result = executor.startTrial(request, ref, correlation,
                    limits, usage, permit);
            if (result instanceof BoundedSkillExecutor.Started started) run = started.run();
            return result;
        }
        @Override public BoundedSkillExecutor.Progress tick(TrustedContext owner) {
            BoundedSkillExecutor.Progress progress = run.tick(owner);
            if (progress.phase() == BoundedSkillExecutor.Phase.TERMINAL) run = null;
            return progress;
        }
        @Override public BoundedSkillExecutor.Progress cancel(TrustedContext owner) {
            BoundedSkillExecutor.Progress progress = run.cancel(owner);
            run = null;
            return progress;
        }
    }
    /** One-use, exact-request authorization, wired into IMP-003's TrialPolicy. */
    public static final class TrialGrants implements BoundedSkillExecutor.TrialPolicy {
        private final Clock clock;
        private volatile Grant current;
        private record Grant(BoundedSkillExecutor.TrialPermit permit, ValidatedRequest bound,
                             Budgets.ExecutionLimits limits) { }
        public TrialGrants(Clock clock) { this.clock = Objects.requireNonNull(clock); }
        synchronized BoundedSkillExecutor.TrialPermit issue(ArtifactRef ref, ValidatedRequest bound,
                                               Budgets.ExecutionLimits limits) {
            if (current != null) throw new IllegalStateException("Pending trial grant");
            BoundedSkillExecutor.TrialPermit permit = new BoundedSkillExecutor.TrialPermit(ref,
                    bound.context(), UUID.randomUUID(), limits.total().deadlineEpochMillis());
            current = new Grant(permit, bound, limits);
            return permit;
        }
        synchronized void revoke() { current = null; }
        @Override public synchronized boolean authorized(BoundedSkillExecutor.TrialPermit permit,
                ValidatedRequest request, Budgets.ExecutionLimits limits) {
            Grant grant = current;
            if (grant == null || !grant.permit().equals(permit) || !grant.bound().equals(request)
                    || !grant.limits().equals(limits) || clock.millis() > permit.expiresAtMillis())
                return false;
            current = null;
            return true;
        }
    }
    /** A staged candidate has CANDIDATE admission; admitted dependencies come from IMP-004. */
    public static final class TrialArtifacts implements BoundedSkillExecutor.ArtifactSource {
        private final VersionedSkillRepository repository;
        private SkillArtifact staged;
        public TrialArtifacts(VersionedSkillRepository repository) {
            this.repository = Objects.requireNonNull(repository);
        }
        void stage(SkillArtifact body) {
            if (staged != null) throw new IllegalStateException("Candidate already staged");
            staged = Objects.requireNonNull(body);
        }
        void clear() { staged = null; }
        @Override public Optional<SkillArtifact> body(ArtifactRef ref) {
            if (staged != null && staged.descriptor().ref().equals(ref)) return Optional.of(staged);
            ArtifactView view = repository.resolve(ref);
            return view == null || !view.usable() ? Optional.empty() : Optional.of(view.artifact());
        }
        @Override public Optional<AdmissionRecord> admission(ArtifactRef ref) {
            if (staged != null && staged.descriptor().ref().equals(ref))
                return Optional.of(new AdmissionRecord(ref, AdmissionStatus.CANDIDATE, null, null, 0));
            ArtifactView view = repository.resolve(ref);
            return view == null ? Optional.empty() : Optional.of(view.admission());
        }
        @Override public Optional<Compatibility> compatibility(ArtifactRef ref) {
            if (staged != null && staged.descriptor().ref().equals(ref))
                return Optional.of(new Compatibility(ref, CompatibilityStatus.COMPATIBLE,
                        List.of(), "candidate-trial"));
            ArtifactView view = repository.resolve(ref);
            return view == null ? Optional.empty() : Optional.of(view.compatibility());
        }
    }
    /** The repository guard accepts only an issued, exact controller decision once. */
    public static final class DecisionGuard implements AdmissionAuthority {
        private final Map<UUID, AdmissionDecision> issued = new ConcurrentHashMap<>();
        private final Map<UUID, QuarantineDecision> quarantines = new ConcurrentHashMap<>();
        void issue(AdmissionDecision decision) { issued.put(decision.decisionId(), decision); }
        void revoke(UUID decisionId) { issued.remove(decisionId); }
        void issue(QuarantineDecision decision) {
            quarantines.put(decision.decisionId(), decision);
        }
        void revokeQuarantine(UUID decisionId) { quarantines.remove(decisionId); }
        @Override public boolean authorizes(AdmissionDecision decision) {
            return decision != null && issued.remove(decision.decisionId(), decision);
        }
        @Override public boolean authorizes(QuarantineDecision decision) {
            return decision != null && quarantines.remove(decision.decisionId(), decision);
        }
    }
    public interface PublicationPort {
        long revision();
        Optional<AdmissionRecord> admission(ArtifactRef ref);
        CompletionStage<PublishResult> publish(SkillArtifact artifact, AdmissionDecision decision,
                                                Provenance provenance);
        CompletionStage<PublishResult> quarantine(ArtifactRef ref, QuarantineDecision decision);
    }
    public static PublicationPort repositoryPublication(VersionedSkillRepository repository,
                                                         Executor worker) {
        Objects.requireNonNull(repository); Objects.requireNonNull(worker);
        return new PublicationPort() {
            @Override public long revision() { return repository.status().revision(); }
            @Override public Optional<AdmissionRecord> admission(ArtifactRef ref) {
                ArtifactView view = repository.resolve(ref);
                return view == null ? Optional.empty() : Optional.of(view.admission());
            }
            @Override public CompletionStage<PublishResult> publish(SkillArtifact artifact,
                    AdmissionDecision decision, Provenance provenance) {
                return repository.publishAsync(artifact, decision, provenance, worker);
            }
            @Override public CompletionStage<PublishResult> quarantine(ArtifactRef ref,
                    QuarantineDecision decision) {
                return CompletableFuture.supplyAsync(() -> repository.quarantine(ref, decision), worker);
            }
        };
    }

    public record Status(Phase phase, Research outcome, ArtifactRef candidate,
                         long committedEffects, List<CropDelivery.CropReceipt> receipts,
                         Map<Kind, Long> usage, List<SkillCompiler.Diagnostic> diagnostics) {
        public Status {
            Objects.requireNonNull(phase);
            receipts = List.copyOf(receipts);
            usage = Map.copyOf(usage);
            diagnostics = List.copyOf(diagnostics);
        }
    }
    private record Event(int generation, Object payload, Throwable error) { }
    private final CapabilityCatalog capabilities;
    private final PrimitiveCatalog primitives;
    private final ArtifactCatalog artifacts;
    private final GenerationPort generator;
    private final FixturePort fixtures;
    private final TrialPort trials;
    private final TrialArtifacts staging;
    private final TrialGrants grants;
    private final PublicationPort publication;
    private final DecisionGuard decisions;
    private final CapabilityResolver.Engine resolver;
    private final CapabilityResolver.ControlPolicy control;
    private final AuthorityPolicy authority;
    private final Clock clock;
    private final Executor staticWorker;
    private final Settings settings;
    private final Thread ownerThread;
    private Attempt active;

    public ResearchAdmissionController(CapabilityCatalog capabilities, PrimitiveCatalog primitives,
            ArtifactCatalog artifacts, GenerationPort generator, FixturePort fixtures,
            TrialPort trials, TrialArtifacts staging, TrialGrants grants,
            PublicationPort publication, DecisionGuard decisions,
            CapabilityResolver.Engine resolver,
            CapabilityResolver.ControlPolicy control, AuthorityPolicy authority,
            Clock clock, Executor staticWorker, Settings settings) {
        this.capabilities = Objects.requireNonNull(capabilities);
        this.primitives = Objects.requireNonNull(primitives);
        this.artifacts = Objects.requireNonNull(artifacts);
        this.generator = Objects.requireNonNull(generator);
        this.fixtures = Objects.requireNonNull(fixtures);
        this.trials = Objects.requireNonNull(trials);
        this.staging = Objects.requireNonNull(staging);
        this.grants = Objects.requireNonNull(grants);
        this.publication = Objects.requireNonNull(publication);
        this.decisions = Objects.requireNonNull(decisions);
        this.resolver = Objects.requireNonNull(resolver);
        this.control = Objects.requireNonNull(control);
        this.authority = Objects.requireNonNull(authority);
        this.clock = Objects.requireNonNull(clock);
        this.staticWorker = Objects.requireNonNull(staticWorker);
        this.settings = Objects.requireNonNull(settings);
        ownerThread = Thread.currentThread();
    }

    public Attempt startMissing(CapabilityResolver.Decision route, ValidatedRequest bound,
                                Budgets.ResearchLimits limits) {
        thread(); Objects.requireNonNull(route); Objects.requireNonNull(bound);
        Objects.requireNonNull(limits);
        idle();
        Attempt attempt = new Attempt(bound, limits, null, null, route.catalogRevision());
        active = attempt;
        if (!valid(bound)) {
            attempt.finish(ResearchStatus.BLOCKED, Reason.REQUEST_INVALID); return attempt;
        }
        if (route.routing().status() != ResolutionStatus.MISSING_IMPLEMENTATION
                || !route.complete() || route.catalogRevision() != publication.revision()
                || !route.observation().reference().equals(bound.observation())
                || !route.equals(resolver.resolve(bound, route.observation()))) {
            attempt.finish(ResearchStatus.BLOCKED, route.routing().reason() == null
                    ? Reason.STALE_OBSERVATION : route.routing().reason());
            return attempt;
        }
        if (!allowed(bound)) {
            attempt.finish(ResearchStatus.BLOCKED, Reason.AUTHORITY_DENIED); return attempt;
        }
        attempt.generate(Generation.Role.INITIAL, "Current source observation="
                + route.observation().status()
                + ", mature_wheat=" + route.observation().counts().getOrDefault("mature_wheat", 0L));
        return attempt;
    }
    public Attempt startRepair(ValidatedRequest bound, ArtifactRef parent, EvidenceRef defect,
                               Reason cause, Budgets.ResearchLimits limits) {
        thread(); Objects.requireNonNull(bound); Objects.requireNonNull(limits); idle();
        if (!valid(bound) || parent == null || !parent.capability().equals(bound.request().capability())
                || defect == null || cause != Reason.ARTIFACT_INVALID
                || publication.admission(parent).filter(record -> record.status()
                        != AdmissionStatus.CANDIDATE).isEmpty())
            throw new IllegalArgumentException("Identified defect and exact artifact required");
        Attempt attempt = new Attempt(bound, limits, parent, defect, publication.revision());
        active = attempt;
        if (!allowed(bound)) attempt.finish(ResearchStatus.BLOCKED, Reason.AUTHORITY_DENIED);
        else attempt.quarantine();
        return attempt;
    }
    /** Candidate input is untrusted IR; no model is needed, and validation precedes its trial. */
    public Attempt evaluateCandidate(ValidatedRequest bound, String candidateIr,
                                     Budgets.ResearchLimits limits) {
        thread(); Objects.requireNonNull(bound); Objects.requireNonNull(limits); idle();
        Attempt attempt = new Attempt(bound, limits, null, null, publication.revision());
        active = attempt;
        if (!valid(bound)) attempt.finish(ResearchStatus.BLOCKED, Reason.REQUEST_INVALID);
        else if (!allowed(bound)) attempt.finish(ResearchStatus.BLOCKED, Reason.AUTHORITY_DENIED);
        else if (candidateIr == null || candidateIr.getBytes(StandardCharsets.UTF_8).length
                > StrictJson.MAX_BYTES) attempt.finish(ResearchStatus.NOT_ADMITTED, Reason.ARTIFACT_INVALID);
        else attempt.compile(candidateIr);
        return attempt;
    }
    private boolean valid(ValidatedRequest bound) {
        if (!bound.request().capability().equals(CropDelivery.ID)
                || !bound.observation().dimension().equals(
                        bound.request().arguments().get("actor") instanceof ActorValue actor
                                ? actor.value().dimension() : "")) return false;
        Map<String, Value> args = bound.request().arguments();
        if (args.size() != CropDelivery.SPEC.parameters().size()) return false;
        for (Parameter parameter : CropDelivery.SPEC.parameters()) {
            Value value = args.get(parameter.name());
            if (value == null || value.type() != parameter.type()
                    || value instanceof IntValue integer && (integer.value() < parameter.minimum()
                    || integer.value() > parameter.maximum())) return false;
        }
        return ((AreaValue)args.get("source")).value().dimension().equals(bound.observation().dimension())
                && ((ContainerValue)args.get("destination")).value().dimension()
                        .equals(bound.observation().dimension());
    }
    private boolean allowed(ValidatedRequest bound) {
        if (!bound.request().capability().equals(CropDelivery.ID)
                || !capabilities.find(CropDelivery.ID).filter(CropDelivery.SPEC::equals).isPresent()
                || !(bound.request().arguments().get("actor") instanceof ActorValue value)
                || !control.controls(value.value(), bound.context())) return false;
        for (Effect required : List.of(Effect.OBSERVE, Effect.HARVEST, Effect.PICKUP, Effect.TRANSFER))
            if (!authority.currentlyAllows(value.value(), required, bound.context())) return false;
        return true;
    }
    private void idle() {
        if (active != null && active.phase != Phase.TERMINAL)
            throw new IllegalStateException("Research attempt already active");
    }
    private void thread() {
        if (Thread.currentThread() != ownerThread) throw new IllegalStateException("Server thread required");
    }

    public final class Attempt {
        private final UUID id = UUID.randomUUID();
        private final ValidatedRequest bound;
        private final Budgets.ResearchLimits limits;
        private final Budgets.Ledger total, inference;
        private final ArtifactRef repairParent;
        private final EvidenceRef defect;
        private long catalogRevision;
        private final java.util.concurrent.ConcurrentLinkedQueue<Event> events =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final List<SkillCompiler.Diagnostic> diagnostics = new ArrayList<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private Phase phase = Phase.GENERATING;
        private Research terminal;
        private Generation.Handle generationHandle;
        private Generation.Descriptor model;
        private String candidateDigest;
        private SkillCompiler.CompiledSkill compiled;
        private FixtureEvidence fixture;
        private BoundedSkillExecutor.Summary trialSummary;
        private RunCorrelation pendingTrial;
        private UUID publicationDecision;
        private UUID quarantineDecision;
        private boolean quarantinePending;
        private boolean cancellationPending;
        private int epoch, candidates;
        private UUID expectedGeneration;
        private Generation.Role expectedRole;

        private Attempt(ValidatedRequest bound, Budgets.ResearchLimits limits,
                        ArtifactRef repairParent, EvidenceRef defect, long catalogRevision) {
            this.bound = bound; this.limits = limits;
            this.repairParent = repairParent; this.defect = defect;
            this.catalogRevision = catalogRevision;
            total = new Budgets.Ledger(limits.total(), clock);
            inference = total.child(limits.inference().total());
        }
        public UUID id() { return id; }
        public Status status(TrustedContext caller) {
            thread(); inspect(caller);
            return new Status(phase, terminal,
                    compiled == null ? null : compiled.artifact().descriptor().ref(),
                    trialSummary == null ? 0 : trialSummary.committedEffects(),
                    trialSummary == null ? List.of() : trialSummary.receipts(),
                    total.snapshot(), diagnostics);
        }
        private void inspect(TrustedContext caller) {
            if (!bound.context().equals(caller)) throw new SecurityException("Private research attempt");
        }
        private void generate(Generation.Role role, String context) {
            String prompt = CROP_BRIEF + context;
            if (prompt.getBytes(StandardCharsets.UTF_8).length > settings.maxContextBytes()) {
                finish(ResearchStatus.BLOCKED, Reason.BUDGET_EXHAUSTED); return;
            }
            if (role == Generation.Role.REPAIR && !inference.canDebit(Kind.REPAIRS, 1)
                    || !inference.canDebit(Kind.CALLS, 1)) {
                finish(ResearchStatus.BLOCKED, Reason.BUDGET_EXHAUSTED); return;
            }
            phase = Phase.GENERATING;
            int token = ++epoch;
            // Only crop-relevant observation, custody and typed movement signatures.
            // Coordinate-targeted movement and unrelated catalog entries remain undisclosed.
            Generation.Request request = new Generation.Request(UUID.randomUUID(), bound,
                    CropDelivery.SPEC, List.of(
                            GatewayPrimitives.Operation.MOVE_TO_SOURCE.signature(),
                            GatewayPrimitives.Operation.MOVE_TO_DESTINATION.signature(),
                            GatewayPrimitives.Operation.HARVEST_NEXT_WHEAT.signature(),
                            GatewayPrimitives.Operation.OBSERVE_INVENTORY.signature(),
                            GatewayPrimitives.Operation.PICKUP_TRACKED_WHEAT.signature(),
                            GatewayPrimitives.Operation.TRANSFER_WHEAT.signature()),
                    List.of(), role, prompt);
            expectedGeneration = request.id(); expectedRole = role;
            try {
                generationHandle = generator.generate(request, limits.inference(), inference);
                generationHandle.result().whenComplete((result, failure) ->
                        events.add(new Event(token, result, failure)));
            } catch (RuntimeException failure) { finish(ResearchStatus.BLOCKED, Reason.MODEL_UNAVAILABLE); }
        }
        private void quarantine() {
            QuarantineDecision decision = new QuarantineDecision(UUID.randomUUID(),
                    bound.context(), defect, Reason.ARTIFACT_INVALID);
            decisions.issue(decision);
            quarantineDecision = decision.decisionId();
            quarantinePending = true;
            phase = Phase.QUARANTINING;
            int token = ++epoch;
            try { publication.quarantine(repairParent, decision)
                    .whenComplete((value, failure) -> events.add(new Event(token, value, failure))); }
            catch (RuntimeException failure) { quarantineFailed(); }
        }
        private void quarantined(PublishResult result) {
            decisions.revokeQuarantine(quarantineDecision);
            quarantinePending = false;
            if (result != null && result.status() == PublishStatus.QUARANTINED
                    && publication.admission(repairParent).filter(record -> record.status()
                            == AdmissionStatus.QUARANTINED
                            && record.reason() == Reason.ARTIFACT_INVALID).isPresent()) {
                catalogRevision = publication.revision();
                if (cancellationPending) finish(ResearchStatus.CANCELLED, Reason.CANCELLED);
                else generate(Generation.Role.REPAIR, "Repair exact quarantined artifact="
                        + repairParent.sha256() + ", defect evidence=" + defect.id()
                        + ", cause=ARTIFACT_INVALID. Use the registered primitive contracts.");
            } else if (cancellationPending && result != null
                    && result.status() == PublishStatus.UNAUTHORIZED)
                finish(ResearchStatus.CANCELLED, Reason.CANCELLED);
            else finish(ResearchStatus.BLOCKED, result != null
                    && result.status() == PublishStatus.STORAGE_LIMIT_REACHED
                    ? Reason.STORAGE_LIMIT_REACHED : Reason.STORAGE_UNAVAILABLE);
        }
        private void quarantineFailed() {
            decisions.revokeQuarantine(quarantineDecision);
            quarantinePending = false;
            finish(ResearchStatus.BLOCKED, Reason.STORAGE_UNAVAILABLE);
        }
        /** At most one bounded state transition on the game thread per call. */
        public Status tick(TrustedContext caller) {
            thread(); inspect(caller);
            if (phase == Phase.TERMINAL) return status(caller);
            if (clock.millis() > limits.total().deadlineEpochMillis()
                    && phase != Phase.QUARANTINING && phase != Phase.PUBLISHING
                    && phase != Phase.CANCELLING) {
                stop(ResearchStatus.BLOCKED, Reason.BUDGET_EXHAUSTED);
                return status(caller);
            }
            if (phase == Phase.PREPARING_TRIAL) { startPreparedTrial(); return status(caller); }
            if (phase == Phase.TRIALING) {
                BoundedSkillExecutor.Progress progress = trials.tick(bound.context());
                if (progress.phase() == BoundedSkillExecutor.Phase.TERMINAL) {
                    trialSummary = progress.summary();
                    staging.clear();
                    if (trialSummary.outcome().status() == ExecutionStatus.SUCCEEDED
                            && CropDelivery.completed(bound, trialSummary.runId(), trialSummary.receipts()))
                        publish();
                    else if (trialSummary.outcome().status() == ExecutionStatus.SUCCEEDED)
                        finish(ResearchStatus.NOT_ADMITTED, Reason.ARTIFACT_INVALID);
                    else finish(trialSummary.outcome().status() == ExecutionStatus.BLOCKED
                                    ? ResearchStatus.BLOCKED : ResearchStatus.NOT_ADMITTED,
                            trialSummary.outcome().reason() == null ? Reason.ARTIFACT_INVALID
                                    : trialSummary.outcome().reason());
                }
                return status(caller);
            }
            Event event;
            do { event = events.poll(); }
            while (event != null && event.generation() != epoch);
            if (event == null) return status(caller);
            if (event.error() != null) {
                if (quarantinePending) quarantineFailed();
                else if (phase == Phase.PUBLISHING || phase == Phase.CANCELLING)
                    publicationFailed(Reason.STORAGE_UNAVAILABLE);
                else finish(ResearchStatus.BLOCKED, phase == Phase.GENERATING
                        ? Reason.MODEL_UNAVAILABLE : Reason.ACTION_FAILED);
                return status(caller);
            }
            if (phase == Phase.GENERATING && event.payload() instanceof Generation.Result result)
                generated(result);
            else if ((phase == Phase.QUARANTINING || phase == Phase.CANCELLING
                    && quarantinePending) && event.payload() instanceof PublishResult result)
                quarantined(result);
            else if (phase == Phase.VALIDATING && event.payload() instanceof SkillCompiler.CompileResult result)
                compiled(result);
            else if (phase == Phase.FIXTURING && event.payload() instanceof FixtureEvidence result)
                fixtured(result);
            else if ((phase == Phase.PUBLISHING || phase == Phase.CANCELLING)
                    && event.payload() instanceof PublishResult result) published(result);
            return status(caller);
        }
        private void generated(Generation.Result result) {
            if (result == null || !result.id().equals(expectedGeneration)
                    || !result.owner().equals(bound.context()) || result.role() != expectedRole) {
                finish(ResearchStatus.BLOCKED, Reason.REQUEST_INVALID); return;
            }
            if (result.outcome() != Generation.Outcome.CANDIDATE) {
                if (result.outcome() == Generation.Outcome.MALFORMED && repairAllowed()) {
                    generate(Generation.Role.REPAIR, "Repair response to generation request="
                            + expectedGeneration + "; malformed strict Skill IR JSON."
                            + " Keep the same bound capability and registered primitive signatures.");
                } else finish(result.reason() == Reason.MODEL_UNAVAILABLE
                                || result.reason() == Reason.BUDGET_EXHAUSTED
                        ? ResearchStatus.BLOCKED : ResearchStatus.NOT_ADMITTED, result.reason());
                return;
            }
            try { total.debit(Kind.CANDIDATES, 1); }
            catch (Budgets.Exhausted exhausted) {
                finish(ResearchStatus.BLOCKED, Reason.BUDGET_EXHAUSTED); return;
            }
            candidates++;
            model = result.descriptor();
            candidateDigest = sha256(result.candidateIr());
            if (candidates > settings.maxCandidates()) {
                finish(ResearchStatus.BLOCKED, Reason.BUDGET_EXHAUSTED); return;
            }
            compile(result.candidateIr());
        }
        private void compile(String source) {
            phase = Phase.VALIDATING;
            int token = ++epoch;
            CompletableFuture.supplyAsync(() -> new SkillCompiler(capabilities, primitives, artifacts)
                    .compile(source), staticWorker).whenComplete((compiled, failure) ->
                    events.add(new Event(token, compiled, failure)));
        }
        private void compiled(SkillCompiler.CompileResult result) {
            if (result instanceof SkillCompiler.Failure failure) {
                for (SkillCompiler.Diagnostic diagnostic : failure.diagnostics()) {
                    if (diagnostics.size() == settings.maxDiagnostics()) break;
                    diagnostics.add(diagnostic);
                }
                if (repairAllowed() && !diagnostics.isEmpty()) generate(Generation.Role.REPAIR,
                        "Repair candidate SHA-256=" + candidateDigest
                                + "; compiler rejected: " + diagnostics.getFirst().code()
                                + " at " + diagnostics.getFirst().path()
                                + ". Return corrected strict Skill IR JSON.");
                else finish(ResearchStatus.NOT_ADMITTED, Reason.ARTIFACT_INVALID);
                return;
            }
            compiled = ((SkillCompiler.Success) result).skill();
            phase = Phase.FIXTURING;
            int token = ++epoch;
            try { fixtures.evaluate(compiled, bound, limits, total, cancelled::get)
                    .whenComplete((value, failure) ->
                    events.add(new Event(token, value, failure))); }
            catch (RuntimeException failure) { finish(ResearchStatus.BLOCKED, Reason.ACTION_FAILED); }
        }
        private void fixtured(FixtureEvidence result) {
            if (result == null) { finish(ResearchStatus.BLOCKED, Reason.ACTION_FAILED); return; }
            if (!result.passed()) {
                if (result.reason() == Reason.ARTIFACT_INVALID && repairAllowed())
                    generate(Generation.Role.REPAIR, "Repair candidate artifact="
                            + compiled.artifact().descriptor().ref().sha256()
                            + "; fixture rejected: " + result.reason()
                            + ". Return corrected strict Skill IR JSON.");
                else finish(result.reason() == Reason.ARTIFACT_INVALID
                                ? ResearchStatus.NOT_ADMITTED : ResearchStatus.BLOCKED, result.reason());
                return;
            }
            fixture = result;
            if (publication.revision() != catalogRevision) {
                finish(ResearchStatus.BLOCKED, Reason.STALE_OBSERVATION); return;
            }
            pendingTrial = new RunCorrelation(UUID.randomUUID(), compiled.artifact().descriptor().ref(), id);
            phase = Phase.PREPARING_TRIAL;
            startPreparedTrial();
        }
        private void startPreparedTrial() {
            List<ArtifactRef> pinned = JobArtifactPins.closure(compiled.artifact().descriptor(), artifacts);
            try { if (!trials.prepare(bound, pendingTrial, List.copyOf(pinned))) return; }
            catch (RuntimeException failed) { finish(ResearchStatus.BLOCKED, Reason.STORAGE_UNAVAILABLE); return; }
            try { total.debit(Kind.TRIALS, 1); }
            catch (Budgets.Exhausted exhausted) {
                finish(ResearchStatus.BLOCKED, Reason.BUDGET_EXHAUSTED); return;
            }
            Budgets.Ledger trialUsage;
            try { trialUsage = total.child(limits.trial().total()); }
            catch (Budgets.Exhausted exhausted) {
                finish(ResearchStatus.BLOCKED, Reason.BUDGET_EXHAUSTED); return;
            }
            SkillArtifact artifact = compiled.artifact();
            try {
                staging.stage(artifact);
                BoundedSkillExecutor.TrialPermit permit = grants.issue(artifact.descriptor().ref(), bound,
                        limits.trial());
                BoundedSkillExecutor.Start started = trials.start(bound, artifact.descriptor().ref(),
                        pendingTrial,
                        limits.trial(), trialUsage, permit);
                grants.revoke();
                if (started instanceof BoundedSkillExecutor.Rejected rejected) {
                    staging.clear(); finish(ResearchStatus.BLOCKED, rejected.reason());
                } else phase = Phase.TRIALING;
            } catch (RuntimeException failure) {
                grants.revoke(); staging.clear(); finish(ResearchStatus.BLOCKED, Reason.ACTION_FAILED);
            }
        }
        private void publish() {
            EvidenceRef staticCheck = new EvidenceRef(compiled.artifact().descriptor().ref().sha256(),
                    "compiler:1", "bounded-static");
            EvidenceRef trialCheck = Objects.requireNonNull(trialSummary.outcome().completion());
            EvidenceBundle bundle = new EvidenceBundle(staticCheck, fixture.evidence(), trialCheck,
                    receiptsDigest(trialSummary.receipts()));
            AdmissionDecision admission = new AdmissionDecision(UUID.randomUUID(), bound.context(), bundle);
            Provenance provenance = new Provenance(1, model == null ? null
                    : model.protocol() + "/" + model.model() + "/" + model.digest(),
                    "compiler:1", "minecraft-26.3", bound.context().scope().worldId(),
                    compiled.artifact().descriptor().dependencies(),
                    List.of(staticCheck, fixture.evidence(), trialCheck));
            decisions.issue(admission);
            publicationDecision = admission.decisionId();
            phase = Phase.PUBLISHING;
            int token = ++epoch;
            try { publication.publish(compiled.artifact(), admission, provenance)
                    .whenComplete((result, failure) -> events.add(new Event(token, result, failure))); }
            catch (RuntimeException failure) { publicationFailed(Reason.STORAGE_UNAVAILABLE); }
        }
        private void published(PublishResult result) {
            decisions.revoke(publicationDecision);
            if (result == null) { publicationFailed(Reason.STORAGE_UNAVAILABLE); return; }
            boolean committed = publication.admission(compiled.artifact().descriptor().ref())
                    .filter(record -> record.status() == AdmissionStatus.ADMITTED
                            && record.evidence().equals(trialSummary.outcome().completion()))
                    .isPresent();
            if (result.status() == PublishStatus.ADMITTED && committed) {
                terminal = new Research(ResearchStatus.ADMITTED, null,
                        compiled.artifact().descriptor().ref(), fixture.evidence(), true,
                        inference.snapshot().getOrDefault(Kind.CALLS, 0L));
                phase = Phase.TERMINAL;
            } else if (result.commitMayHaveSucceeded() && committed) {
                terminal = new Research(ResearchStatus.ADMITTED, null,
                        compiled.artifact().descriptor().ref(), fixture.evidence(), true,
                        inference.snapshot().getOrDefault(Kind.CALLS, 0L));
                phase = Phase.TERMINAL;
            } else if (cancellationPending && result.status() == PublishStatus.UNAUTHORIZED)
                finish(ResearchStatus.CANCELLED, Reason.CANCELLED);
            else if (result.status() == PublishStatus.ADMITTED)
                finish(ResearchStatus.BLOCKED, Reason.STORAGE_UNAVAILABLE);
            else finish(ResearchStatus.BLOCKED, result.status() == PublishStatus.STORAGE_LIMIT_REACHED
                    ? Reason.STORAGE_LIMIT_REACHED : result.status() == PublishStatus.STORAGE_UNAVAILABLE
                    ? Reason.STORAGE_UNAVAILABLE : Reason.ARTIFACT_INVALID);
        }
        private void publicationFailed(Reason reason) {
            decisions.revoke(publicationDecision);
            finish(ResearchStatus.BLOCKED, reason);
        }
        private boolean repairAllowed() {
            return expectedGeneration != null && candidates < settings.maxCandidates()
                    && inference.canDebit(Kind.REPAIRS, 1)
                    && inference.canDebit(Kind.CALLS, 1);
        }
        public Status cancel(TrustedContext caller) {
            thread(); inspect(caller);
            if (phase == Phase.TERMINAL) return status(caller);
            if (phase == Phase.QUARANTINING || phase == Phase.PUBLISHING
                    || phase == Phase.CANCELLING) {
                cancellationPending = true;
                if (quarantinePending) decisions.revokeQuarantine(quarantineDecision);
                else decisions.revoke(publicationDecision);
                phase = Phase.CANCELLING;
                return status(caller);
            }
            stop(ResearchStatus.CANCELLED, Reason.CANCELLED);
            return status(caller);
        }
        private void stop(ResearchStatus status, Reason reason) {
            cancelled.set(true);
            if (phase == Phase.TRIALING) {
                try { trialSummary = trials.cancel(bound.context()).summary(); }
                finally { staging.clear(); grants.revoke(); }
            }
            if (phase == Phase.GENERATING && generationHandle != null) generationHandle.cancel();
            finish(status, reason);
        }
        private void finish(ResearchStatus status, Reason reason) {
            if (phase == Phase.TERMINAL) return;
            grants.revoke(); staging.clear();
            terminal = new Research(status, reason, compiled == null ? repairParent
                    : compiled.artifact().descriptor().ref(), null, false,
                    inference.snapshot().getOrDefault(Kind.CALLS, 0L));
            phase = Phase.TERMINAL;
            events.clear();
        }
    }
    private static String receiptsDigest(List<CropDelivery.CropReceipt> receipts) {
        List<String> rows = receipts.stream().map(CropDelivery.CropReceipt::toString).sorted().toList();
        return sha256(String.join("\n", rows));
    }
    private static String sha256(String content) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(sha.digest(
                    content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
