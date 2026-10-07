package dev.aivillages.core.kernel;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

import static dev.aivillages.core.kernel.Budgets.Kind;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static dev.aivillages.core.kernel.SkillIr.*;

/** One game-thread interpreter per attempt. Only immutable, recompiled, exact-ref bodies run. */
public final class BoundedSkillExecutor {
    /** The repository may implement this without granting the executor publication rights. */
    public interface ArtifactSource {
        Optional<SkillArtifact> body(ArtifactRef ref);
        Optional<AdmissionRecord> admission(ArtifactRef ref);
        Optional<Compatibility> compatibility(ArtifactRef ref);
    }
    /** A trial permit has no authority until the controller's verifier accepts it. */
    public record TrialPermit(ArtifactRef artifact, TrustedContext context, UUID authorizationId,
                              long expiresAtMillis) {
        public TrialPermit {
            Objects.requireNonNull(artifact); Objects.requireNonNull(context);
            Objects.requireNonNull(authorizationId);
        }
    }
    public interface TrialPolicy {
        boolean authorized(TrialPermit permit, ValidatedRequest request, Budgets.ExecutionLimits limits);
    }
    public interface ControlPolicy {
        boolean mayInspect(TrustedContext caller, TrustedContext owner, UUID runId);
        boolean mayCancel(TrustedContext caller, TrustedContext owner, UUID runId);
    }
    public interface RunRelease { void release(UUID runId); }

    public record Settings(int instructionsPerTick, int maxPinnedArtifacts, int maxTrace,
                           int maxReceipts) {
        public Settings {
            if (instructionsPerTick < 1 || instructionsPerTick > 256
                    || maxPinnedArtifacts < 1 || maxPinnedArtifacts > 16
                    || maxTrace < 1 || maxTrace > 256 || maxReceipts < 1 || maxReceipts > 512)
                throw new IllegalArgumentException("Finite executor settings");
        }
    }
    public enum Phase { RUNNING, WAITING, TERMINAL }
    public record Trace(long tick, String event, ArtifactRef artifact) { }
    /** No interpreter stack or authority token is part of the caller's recovery data. */
    public record Summary(UUID runId, ArtifactRef artifact, UUID researchId,
                          List<ArtifactRef> pinned, long committedEffects,
                          List<CropDelivery.CropReceipt> receipts, Execution outcome,
                          Map<Kind, Long> usage) {
        public Summary {
            pinned = List.copyOf(pinned); receipts = List.copyOf(receipts);
            usage = Map.copyOf(usage);
        }
    }
    public record Progress(Phase phase, Summary summary, List<Trace> trace) {
        public Progress { trace = List.copyOf(trace); }
    }
    public sealed interface Start permits Started, Rejected { }
    public record Started(Run run) implements Start { }
    public record Rejected(Reason reason) implements Start { }

    private final ArtifactSource source;
    private final CapabilityCatalog capabilities;
    private final PrimitiveCatalog primitives;
    private final EligibilityPolicy eligibility;
    private final TrialPolicy trials;
    private final ControlPolicy control;
    private final GatewayPort gateway;
    private final RunRelease release;
    private final Clock clock;
    private final LongSupplier tickClock;
    private final Settings settings;
    private final Thread gameThread;

    public BoundedSkillExecutor(ArtifactSource source, CapabilityCatalog capabilities,
                                PrimitiveCatalog primitives, EligibilityPolicy eligibility,
                                TrialPolicy trials, ControlPolicy control, GatewayPort gateway,
                                RunRelease release, Clock clock, LongSupplier tickClock,
                                Settings settings) {
        this.source = Objects.requireNonNull(source);
        this.capabilities = Objects.requireNonNull(capabilities);
        this.primitives = Objects.requireNonNull(primitives);
        this.eligibility = Objects.requireNonNull(eligibility);
        this.trials = Objects.requireNonNull(trials);
        this.control = Objects.requireNonNull(control);
        this.gateway = Objects.requireNonNull(gateway);
        this.release = Objects.requireNonNull(release);
        this.clock = Objects.requireNonNull(clock);
        this.tickClock = Objects.requireNonNull(tickClock);
        this.settings = Objects.requireNonNull(settings);
        gameThread = Thread.currentThread();
    }

    public Start startAdmitted(ValidatedRequest request, ArtifactRef artifact,
                               RunCorrelation correlation, Budgets.ExecutionLimits limits,
                               Budgets.Ledger usage) {
        return start(request, artifact, correlation, limits, usage, null);
    }
    public Start startTrial(ValidatedRequest request, ArtifactRef artifact,
                            RunCorrelation correlation, Budgets.ExecutionLimits limits,
                            Budgets.Ledger usage, TrialPermit permit) {
        if (permit == null || !permit.artifact().equals(artifact)
                || !permit.context().equals(request.context())
                || clock.millis() > permit.expiresAtMillis()
                || !trials.authorized(permit, request, limits))
            return new Rejected(Reason.AUTHORITY_DENIED);
        return start(request, artifact, correlation, limits, usage, permit);
    }
    private Start start(ValidatedRequest request, ArtifactRef artifact, RunCorrelation correlation,
                        Budgets.ExecutionLimits limits, Budgets.Ledger usage, TrialPermit permit) {
        thread();
        Objects.requireNonNull(request); Objects.requireNonNull(artifact);
        Objects.requireNonNull(correlation); Objects.requireNonNull(limits);
        Objects.requireNonNull(usage);
        if (!correlation.artifact().equals(artifact) || !artifact.capability().equals(request.request().capability()))
            return new Rejected(Reason.REQUEST_INVALID);
        if (!control.mayCancel(request.context(), request.context(), correlation.runId()))
            return new Rejected(Reason.AUTHORITY_DENIED);
        if (clock.millis() > limits.total().deadlineEpochMillis()
                || !usage.canDebit(Kind.INSTRUCTIONS, 1)) return new Rejected(Reason.BUDGET_EXHAUSTED);
        ActorRef actor = actor(request);
        if (actor == null) return new Rejected(Reason.REQUEST_INVALID);
        Map<ArtifactRef, SkillCompiler.CompiledSkill> pinned = new LinkedHashMap<>();
        Reason invalid = pin(artifact, permit != null, actor, request.context(), pinned,
                new HashSet<>(), 0, new int[] {0});
        if (invalid != null) return new Rejected(invalid);
        SkillCompiler.CompiledSkill root = pinned.get(artifact);
        if (!arguments(root.artifact().descriptor().parameters(), request.request().arguments()))
            return new Rejected(Reason.REQUEST_INVALID);
        return new Started(new Run(request, correlation, limits, usage, pinned));
    }

    private Reason pin(ArtifactRef ref, boolean trialRoot, ActorRef actor, TrustedContext context,
                       Map<ArtifactRef, SkillCompiler.CompiledSkill> pinned, Set<ArtifactRef> active,
                       int depth, int[] edges) {
        if (depth > SkillCompiler.MAX_CONTROL_DEPTH || ++edges[0] > SkillCompiler.MAX_DEPENDENCY_EDGES
                || pinned.size() >= settings.maxPinnedArtifacts() && !pinned.containsKey(ref))
            return Reason.BUDGET_EXHAUSTED;
        if (active.contains(ref)) return Reason.DEPENDENCY_INCOMPATIBLE;
        if (pinned.containsKey(ref)) return null;
        AdmissionRecord admission = source.admission(ref).orElse(null);
        if (admission == null || !admission.artifact().equals(ref)) return Reason.ARTIFACT_INVALID;
        if (admission.status() == AdmissionStatus.QUARANTINED) return Reason.ARTIFACT_QUARANTINED;
        if (admission.status() != (trialRoot ? AdmissionStatus.CANDIDATE : AdmissionStatus.ADMITTED))
            return Reason.KNOWLEDGE_REQUIRED;
        Compatibility compatibility = source.compatibility(ref).orElse(null);
        if (compatibility == null || !compatibility.artifact().equals(ref)
                || compatibility.status() != CompatibilityStatus.COMPATIBLE)
            return depth == 0 ? Reason.ARTIFACT_INCOMPATIBLE : Reason.DEPENDENCY_INCOMPATIBLE;
        SkillArtifact body = source.body(ref).orElse(null);
        if (body == null || !body.descriptor().ref().equals(ref))
            return depth == 0 ? Reason.ARTIFACT_INVALID : Reason.DEPENDENCY_INCOMPATIBLE;
        if (!trialRoot && !eligibility.mayUse(actor, ref, context)) return Reason.KNOWLEDGE_REQUIRED;
        active.add(ref);
        for (ArtifactRef dependency : body.descriptor().dependencies()) {
            Reason result = pin(dependency, false, actor, context, pinned, active, depth + 1, edges);
            if (result != null) return result;
        }
        active.remove(ref);
        SkillCompiler compiler = new SkillCompiler(capabilities, primitives,
                key -> Optional.ofNullable(pinned.get(key)).map(skill -> skill.artifact().descriptor()));
        SkillCompiler.CompileResult result = compiler.compile(body.canonicalIr());
        if (!(result instanceof SkillCompiler.Success success)
                || !success.skill().artifact().descriptor().equals(body.descriptor())
                || !success.skill().artifact().canonicalIr().equals(body.canonicalIr()))
            return depth == 0 ? Reason.ARTIFACT_INVALID : Reason.DEPENDENCY_INCOMPATIBLE;
        pinned.put(ref, success.skill());
        return null;
    }

    private static ActorRef actor(ValidatedRequest request) {
        Value value = request.request().arguments().get("actor");
        return value instanceof ActorValue actor ? actor.value() : null;
    }
    private static boolean arguments(List<Parameter> parameters, Map<String, Value> bindings) {
        if (parameters.size() != bindings.size()) return false;
        for (Parameter parameter : parameters) {
            Value value = bindings.get(parameter.name());
            if (value == null || value.type() != parameter.type()) return false;
            if (value instanceof IntValue number && (number.value() < parameter.minimum()
                    || number.value() > parameter.maximum())) return false;
        }
        return true;
    }
    private void thread() {
        if (Thread.currentThread() != gameThread) throw new IllegalStateException("Game thread required");
    }

    private static final class Cursor {
        final List<Node> nodes;
        final Map<String, Value> base;
        Map<String, Value> locals;
        long remaining;
        int position;
        Cursor(List<Node> nodes, Map<String, Value> locals, long remaining) {
            this.nodes = nodes; this.base = Map.copyOf(locals);
            this.locals = new HashMap<>(locals); this.remaining = remaining;
        }
    }
    private static final class Frame {
        final ArtifactRef ref;
        final Map<String, Value> parameters;
        final Deque<Cursor> cursors = new ArrayDeque<>();
        final String returnInto;
        Frame(ArtifactRef ref, Program program, Map<String, Value> parameters, String returnInto) {
            this.ref = ref; this.parameters = Map.copyOf(parameters); this.returnInto = returnInto;
            cursors.push(new Cursor(program.body(), Map.of(), 1));
        }
    }
    private record Pending(ActionHandle handle, Type type, String into, long startedMillis) { }

    public final class Run {
        private final ValidatedRequest request;
        private final RunCorrelation correlation;
        private final Budgets.ExecutionLimits limits;
        private final Budgets.Ledger usage;
        private final Map<ArtifactRef, SkillCompiler.CompiledSkill> pinned;
        private final Deque<Frame> stack = new ArrayDeque<>();
        private final List<CropDelivery.CropReceipt> receipts = new ArrayList<>();
        private final List<Trace> trace = new ArrayList<>();
        private final Map<UUID, CropDelivery.CropReceipt> receiptsById = new HashMap<>();
        private long pendingEffects;
        private Pending pending;
        private long lastTick;
        private long effects;
        private Execution terminal;

        private Run(ValidatedRequest request, RunCorrelation correlation,
                    Budgets.ExecutionLimits limits, Budgets.Ledger usage,
                    Map<ArtifactRef, SkillCompiler.CompiledSkill> pinned) {
            this.request = request; this.correlation = correlation;
            this.limits = limits; this.usage = usage;
            this.pinned = Collections.unmodifiableMap(new LinkedHashMap<>(pinned));
            lastTick = tickClock.getAsLong();
            stack.push(new Frame(correlation.artifact(), pinned.get(correlation.artifact()).program(),
                    request.request().arguments(), null));
            record("start", correlation.artifact());
        }
        public Progress progress(TrustedContext caller) {
            thread(); inspect(caller);
            return snapshot();
        }
        public Progress tick(TrustedContext caller) {
            thread(); control(caller);
            if (terminal != null) return snapshot();
            long now = tickClock.getAsLong();
            if (now < lastTick) { finish(ExecutionStatus.INTERRUPTED, Reason.INTERRUPTED); return snapshot(); }
            if (clock.millis() > limits.total().deadlineEpochMillis()) {
                finish(ExecutionStatus.BLOCKED, Reason.BUDGET_EXHAUSTED); return snapshot();
            }
            if (pending != null && clock.millis() - pending.startedMillis() > limits.actionDeadlineMillis()) {
                finish(ExecutionStatus.BLOCKED, Reason.ACTION_TIMEOUT); return snapshot();
            }
            long elapsed = now - lastTick;
            if (pending != null && !usage.canDebit(Kind.ELAPSED_TICKS, elapsed)) {
                finish(ExecutionStatus.BLOCKED, Reason.BUDGET_EXHAUSTED); return snapshot();
            }
            if (pending == null) {
                try { usage.debit(Kind.ELAPSED_TICKS, elapsed); }
                catch (Budgets.Exhausted exhausted) {
                    finish(ExecutionStatus.BLOCKED, Reason.BUDGET_EXHAUSTED); return snapshot();
                }
            }
            lastTick = now;
            if (pending != null) { poll(elapsed); return snapshot(); }
            int performed = 0;
            try {
                while (terminal == null && pending == null && performed < settings.instructionsPerTick()) {
                    Frame frame = stack.peek();
                    Cursor cursor = frame.cursors.peek();
                    if (cursor.position == cursor.nodes.size()) {
                        // Empty/nested repeats also consume a finite slice; compiler work alone
                        // does not charge their runtime cursor transitions.
                        usage.debit(Kind.INSTRUCTIONS, 1);
                        performed++;
                        if (--cursor.remaining > 0) {
                            cursor.position = 0; cursor.locals = new HashMap<>(cursor.base);
                        } else frame.cursors.pop();
                        if (frame.cursors.isEmpty()) throw new InvalidRun();
                        continue;
                    }
                    usage.debit(Kind.INSTRUCTIONS, 1);
                    performed++;
                    Node node = cursor.nodes.get(cursor.position++);
                    execute(frame, cursor, node);
                }
            } catch (Budgets.Exhausted exhausted) {
                finish(ExecutionStatus.BLOCKED, Reason.BUDGET_EXHAUSTED);
            } catch (InvalidRun | ArithmeticException invalid) {
                finish(ExecutionStatus.FAILED, Reason.ARTIFACT_INVALID);
            } catch (RuntimeException gatewayFailure) {
                finish(ExecutionStatus.INTERRUPTED, Reason.INTERRUPTED);
            }
            return snapshot();
        }
        private void execute(Frame frame, Cursor cursor, Node node) {
            if (node instanceof Bind bind) {
                cursor.locals.put(bind.name(), evaluate(bind.value(), frame, cursor));
            } else if (node instanceof Branch branch) {
                boolean yes = ((BoolValue) evaluate(branch.test(), frame, cursor)).value();
                frame.cursors.push(new Cursor(yes ? branch.whenTrue() : branch.whenFalse(), cursor.locals, 1));
            } else if (node instanceof Repeat repeat) {
                long count = ((IntValue) evaluate(repeat.count(), frame, cursor)).value();
                if (count < 0 || count > repeat.maximumCount()) throw new InvalidRun();
                if (count != 0) frame.cursors.push(new Cursor(repeat.body(), cursor.locals, count));
            } else if (node instanceof Call call) {
                usage.debit(Kind.CALLS, 1);
                Map<String, Value> args = new LinkedHashMap<>();
                for (var entry : call.arguments().entrySet())
                    args.put(entry.getKey(), evaluate(entry.getValue(), frame, cursor));
                if (call.artifactRef() != null) {
                    SkillCompiler.CompiledSkill child = pinned.get(call.artifactRef());
                    if (child == null || !arguments(child.artifact().descriptor().parameters(), args))
                        throw new InvalidRun();
                    stack.push(new Frame(call.artifactRef(), child.program(), args, call.into()));
                    record("call", call.artifactRef());
                } else {
                    PrimitiveRequirement requirement = new PrimitiveRequirement(call.primitiveId(),
                            call.primitiveVersion(), call.fingerprint());
                    PrimitiveSignature signature = primitives.find(requirement.id(), requirement.version()).orElse(null);
                    if (signature == null || !signature.fingerprint().equals(requirement.fingerprint())
                            || !arguments(signature.parameters(), args)) throw new InvalidRun();
                    ActionHandle handle = gateway.start(request, correlation, requirement, args, usage);
                    if (handle == null || !handle.runId().equals(correlation.runId())) throw new InvalidRun();
                    pending = new Pending(handle, call.resultType(), call.into(), clock.millis());
                    pendingEffects = 0;
                    record("action", frame.ref);
                }
            } else if (node instanceof Result result) {
                Value value = evaluate(result.value(), frame, cursor);
                stack.pop();
                record("return", frame.ref);
                if (stack.isEmpty()) {
                    if (CropDelivery.completed(request, correlation.runId(), receipts))
                        finish(ExecutionStatus.SUCCEEDED, null);
                    else finish(ExecutionStatus.FAILED, Reason.ACTION_FAILED);
                } else if (frame.returnInto != null && !frame.returnInto.isEmpty()) {
                    stack.peek().cursors.peek().locals.put(frame.returnInto, value);
                }
            } else throw new InvalidRun();
        }
        private Value evaluate(Expr expr, Frame frame, Cursor cursor) {
            Value value;
            if (expr instanceof ParameterExpr parameter) value = frame.parameters.get(parameter.name());
            else if (expr instanceof LocalExpr local) value = cursor.locals.get(local.name());
            else if (expr instanceof IntegerExpr integer) value = new IntValue(integer.value());
            else if (expr instanceof BooleanExpr bool) value = new BoolValue(bool.value());
            else if (expr instanceof BinaryExpr binary) {
                Value a = evaluate(binary.left(), frame, cursor);
                Value b = evaluate(binary.right(), frame, cursor);
                value = switch (binary.operator()) {
                    case ADD -> new IntValue(Math.addExact(((IntValue) a).value(), ((IntValue) b).value()));
                    case SUB -> new IntValue(Math.subtractExact(((IntValue) a).value(), ((IntValue) b).value()));
                    case EQ -> new BoolValue(a.equals(b));
                    case LT -> new BoolValue(((IntValue) a).value() < ((IntValue) b).value());
                    case GTE -> new BoolValue(((IntValue) a).value() >= ((IntValue) b).value());
                };
            } else throw new InvalidRun();
            if (value == null || value.type() != expr.type()) throw new InvalidRun();
            return value;
        }
        private void poll(long elapsed) {
            Pending waiting = pending;
            try {
                long before = usage.snapshot().getOrDefault(Kind.ELAPSED_TICKS, 0L);
                ActionReceipt answer = gateway.poll(waiting.handle());
                long gatewayCharge = usage.snapshot().getOrDefault(Kind.ELAPSED_TICKS, 0L) - before;
                if (gatewayCharge < elapsed) usage.debit(Kind.ELAPSED_TICKS, elapsed - gatewayCharge);
                if (!answer.handle().equals(waiting.handle())) throw new InvalidRun();
                account(answer);
                if (!answer.terminal()) return;
                pending = null;
                if (answer.reason() != null) {
                    Reason reason = answer.reason();
                    if (reason == Reason.ACTOR_UNAVAILABLE || reason == Reason.INTERRUPTED)
                        finish(ExecutionStatus.INTERRUPTED, Reason.INTERRUPTED);
                    else finish(blocker(reason) ? ExecutionStatus.BLOCKED : ExecutionStatus.FAILED, reason);
                    return;
                }
                if (answer.observation() != null
                        && answer.observation().status() == ObservationStatus.UNKNOWN) {
                    finish(ExecutionStatus.BLOCKED, Reason.STALE_OBSERVATION);
                    return;
                }
                if (answer.result() == null || answer.result().type() != waiting.type())
                    throw new InvalidRun();
                if (!waiting.into().isEmpty()) stack.peek().cursors.peek().locals.put(waiting.into(), answer.result());
                record("receipt", stack.peek().ref);
            } catch (Budgets.Exhausted exhausted) {
                finish(ExecutionStatus.BLOCKED, Reason.BUDGET_EXHAUSTED);
            } catch (InvalidRun malformed) {
                finish(ExecutionStatus.FAILED, Reason.ACTION_FAILED);
            } catch (RuntimeException failure) {
                finish(ExecutionStatus.INTERRUPTED, Reason.INTERRUPTED);
            }
        }
        private boolean blocker(Reason reason) {
            return switch (reason) {
                case RESOURCE_MISSING, FACILITY_MISSING, KNOWLEDGE_REQUIRED, AUTHORITY_DENIED,
                     STALE_OBSERVATION, TARGET_UNAVAILABLE, TARGET_INVALID, BUDGET_EXHAUSTED,
                     ACTION_TIMEOUT, UNSUPPORTED_PRIMITIVE -> true;
                default -> false;
            };
        }
        private void account(ActionReceipt answer) {
            if (pending == null || !pending.handle().equals(answer.handle())
                    || answer.committedEffects() < pendingEffects) throw new InvalidRun();
            effects = Math.addExact(effects, answer.committedEffects() - pendingEffects);
            pendingEffects = answer.committedEffects();
            for (CropDelivery.CropReceipt receipt : answer.cropReceipts()) {
                if (!receipt.runId().equals(correlation.runId())) throw new InvalidRun();
                CropDelivery.CropReceipt prior = receiptsById.get(receipt.receiptId());
                if (prior != null && !prior.equals(receipt)) throw new InvalidRun();
                if (prior == null) {
                    if (receipts.size() >= settings.maxReceipts()) throw new Budgets.Exhausted();
                    receiptsById.put(receipt.receiptId(), receipt);
                    receipts.add(receipt);
                }
            }
        }
        public Progress cancel(TrustedContext caller) {
            thread(); control(caller);
            finish(ExecutionStatus.CANCELLED, Reason.CANCELLED);
            return snapshot();
        }
        public Progress interrupt(TrustedContext caller) {
            thread(); control(caller);
            finish(ExecutionStatus.INTERRUPTED, Reason.INTERRUPTED);
            return snapshot();
        }
        private void finish(ExecutionStatus status, Reason reason) {
            if (terminal != null) return;
            if (pending != null) {
                try {
                    ActionReceipt stopped = gateway.cancel(pending.handle());
                    account(stopped);
                    stopConfirmed = stopped.terminal();
                }
                catch (RuntimeException ignored) { stopConfirmed = false; }
                pending = null;
            }
            terminal = new Execution(status, reason, effects,
                    status == ExecutionStatus.SUCCEEDED ?
                            new EvidenceRef(correlation.runId().toString(), "crop-delivery:1",
                                    request.context().scope().domainId().toString()) : null);
            try { if (stopConfirmed) release.release(correlation.runId()); }
            finally { record(status.name(), correlation.artifact()); }
        }
        private boolean stopConfirmed = true;
        /** An uncertain cancellation may not be used as evidence permitting reassignment. */
        public boolean stopped(TrustedContext caller) {
            thread(); inspect(caller); return terminal != null && stopConfirmed;
        }
        private void inspect(TrustedContext caller) {
            if (!control.mayInspect(caller, request.context(), correlation.runId()))
                throw new SecurityException("Run inspection denied");
        }
        private void control(TrustedContext caller) {
            if (!control.mayCancel(caller, request.context(), correlation.runId()))
                throw new SecurityException("Run control denied");
        }
        private void record(String event, ArtifactRef artifact) {
            if (trace.size() == settings.maxTrace()) trace.remove(0);
            trace.add(new Trace(tickClock.getAsLong(), event, artifact));
        }
        private Progress snapshot() {
            Phase phase = terminal != null ? Phase.TERMINAL : pending != null ? Phase.WAITING : Phase.RUNNING;
            Summary summary = new Summary(correlation.runId(), correlation.artifact(), correlation.researchId(),
                    List.copyOf(pinned.keySet()), effects, receipts, terminal, usage.snapshot());
            return new Progress(phase, summary, trace);
        }
    }
    private static final class InvalidRun extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
