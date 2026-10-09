package dev.aivillages.providers;

import dev.aivillages.core.kernel.Budgets;
import dev.aivillages.core.kernel.Contracts;
import dev.aivillages.core.kernel.CropDelivery;
import dev.aivillages.core.kernel.Generation;
import dev.aivillages.core.kernel.Outcomes;
import dev.aivillages.core.kernel.StrictJson;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static dev.aivillages.core.kernel.Budgets.Kind;

/** Single local Ollama backend. Owns transport status only, never trial or admission state. */
public final class LocalGenerationAdapter implements Contracts.GenerationPort, AutoCloseable {
    public static final int MAX_WIRE_BYTES = 65_536;
    public static final int MAX_REQUEST_BYTES = 16_384;

    public record Config(URI endpoint, String model, boolean enabled, int maxOutputTokens) {
        public Config {
            Objects.requireNonNull(endpoint);
            if (!"http".equals(endpoint.getScheme()) || !List.of("localhost", "127.0.0.1", "[::1]")
                    .contains(endpoint.getHost()) || !"/api/chat".equals(endpoint.getPath())
                    || endpoint.getPort() < 1 || endpoint.getPort() > 65535
                    || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                    || endpoint.getFragment() != null || model == null || model.isBlank()
                    || model.length() > 128 || model.toLowerCase(java.util.Locale.ROOT).endsWith(":cloud")
                    || maxOutputTokens < 1 || maxOutputTokens > 4096)
                throw new IllegalArgumentException("Explicit loopback Ollama endpoint/model required");
        }
    }
    /** Fake seam uses exactly the same byte/chunk/deadline handling as the HTTP backend. */
    public interface Backend {
        Transfer start(byte[] body, long remainingMillis, int maxWireBytes, Sink sink);
    }
    public interface Transfer {
        /** True only with proof that backend computation stopped. HTTP cancellation cannot prove it. */
        boolean cancel();
    }
    public interface Sink {
        void chunk(byte[] bytes);
        void complete(int httpStatus);
        void error();
        void stopped();
        default void modelDigest(String digest) { }
    }

    private final Config config;
    private final Generation.Descriptor descriptor;
    private final Backend backend;
    private final Clock clock;
    private final Executor worker;
    private final Executor handoff;
    private final ScheduledExecutorService timer;
    private final boolean ownsExecutors;
    private final ArrayDeque<Call> waiting = new ArrayDeque<>();
    private Call active;
    private int pendingQueuedHandoffs;
    private boolean unavailable;
    private boolean closed;

    public LocalGenerationAdapter(Config config) {
        this(config, new OllamaHttpBackend(config.endpoint(), config.model()), Clock.systemUTC(),
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(2), r -> daemon(r, "cognitivecraft-generation")),
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(4), r -> daemon(r, "cognitivecraft-result")),
                newTimer(), true);
    }
    public LocalGenerationAdapter(Config config, Backend backend, Clock clock, Executor worker,
                                  Executor handoff, ScheduledExecutorService timer) {
        this(config, backend, clock, worker, handoff, timer, false);
    }
    private LocalGenerationAdapter(Config config, Backend backend, Clock clock, Executor worker,
                                   Executor handoff, ScheduledExecutorService timer, boolean ownsExecutors) {
        this.config = Objects.requireNonNull(config);
        this.backend = Objects.requireNonNull(backend);
        this.clock = Objects.requireNonNull(clock);
        this.worker = Objects.requireNonNull(worker);
        this.handoff = Objects.requireNonNull(handoff);
        this.timer = Objects.requireNonNull(timer);
        this.ownsExecutors = ownsExecutors;
        descriptor = new Generation.Descriptor("ollama-chat-v1", config.model(), null);
    }
    private static Thread daemon(Runnable run, String name) {
        Thread thread = new Thread(run, name);
        thread.setDaemon(true);
        return thread;
    }
    private static ScheduledExecutorService newTimer() {
        var executor = new ScheduledThreadPoolExecutor(1, r -> daemon(r, "cognitivecraft-deadline"));
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    @Override public Generation.Descriptor descriptor() { return descriptor; }
    @Override public synchronized Generation.Status status() {
        Generation.State state = closed ? Generation.State.CLOSED : !config.enabled()
                ? Generation.State.DISABLED : active == null ? unavailable
                ? Generation.State.UNAVAILABLE : Generation.State.READY
                : active.compute == Generation.Compute.STOP_UNCONFIRMED
                ? Generation.State.CANCELLING : active.transfer == null
                ? Generation.State.STARTING : Generation.State.IN_FLIGHT;
        return new Generation.Status(state, waiting.size(), active == null ? null : active.id(),
                active == null ? Generation.Compute.NOT_STARTED : active.compute, false, false);
    }

    @Override public synchronized Generation.Handle generate(Generation.Request request,
            Budgets.InferenceLimits limits, Budgets.Ledger allowance) {
        Objects.requireNonNull(request); Objects.requireNonNull(limits); Objects.requireNonNull(allowance);
        Call call = new Call(request, limits, allowance);
        if (closed || !config.enabled()) {
            finish(call, Generation.Outcome.MODEL_UNAVAILABLE, Outcomes.Reason.MODEL_UNAVAILABLE,
                    null, new Generation.Usage(0, 0, -1, -1, Generation.Precision.UNKNOWN));
        } else if (clock.millis() >= limits.total().deadlineEpochMillis()
                || !allowance.canDebit(Kind.CALLS, 1) || request.role() == Generation.Role.REPAIR
                && !allowance.canDebit(Kind.REPAIRS, 1)) {
            finish(call, Generation.Outcome.LIMIT, Outcomes.Reason.BUDGET_EXHAUSTED,
                    null, emptyUsage());
        } else if (containsId(request.id()) || waiting.size() >= 1 || pendingQueuedHandoffs > 0) {
            finish(call, Generation.Outcome.LIMIT, Outcomes.Reason.BUDGET_EXHAUSTED,
                    null, emptyUsage());
        } else {
            waiting.add(call);
            call.enqueued = true;
            long delay = Math.max(1, limits.total().deadlineEpochMillis() - clock.millis());
            try { call.deadline = timer.schedule(() -> expire(call), delay, TimeUnit.MILLISECONDS); }
            catch (RejectedExecutionException rejected) {
                waiting.remove(call);
                finish(call, Generation.Outcome.MODEL_UNAVAILABLE, Outcomes.Reason.MODEL_UNAVAILABLE,
                        null, emptyUsage());
                return call;
            }
            startNext();
        }
        return call;
    }
    private boolean containsId(UUID id) {
        return active != null && active.id().equals(id)
                || waiting.stream().anyMatch(call -> call.id().equals(id));
    }
    /** Also supports deterministic fake-clock tests and eager expiry on external status polling. */
    public synchronized void sweep() {
        for (Call call : List.copyOf(waiting)) expire(call);
        if (active != null) expire(active);
    }
    private synchronized void expire(Call call) {
        if (call.terminal || clock.millis() < call.limits.total().deadlineEpochMillis()) return;
        terminate(call, Generation.Outcome.LIMIT, Outcomes.Reason.BUDGET_EXHAUSTED);
    }
    private void startNext() {
        if (closed || active != null) return;
        while (!waiting.isEmpty()) {
            Call next = waiting.remove();
            if (next.terminal) continue;
            if (clock.millis() >= next.limits.total().deadlineEpochMillis()) {
                finish(next, Generation.Outcome.LIMIT, Outcomes.Reason.BUDGET_EXHAUSTED,
                        null, emptyUsage());
                continue;
            }
            active = next;
            unavailable = false;
            try { worker.execute(() -> dispatch(next)); }
            catch (RejectedExecutionException rejected) {
                done(next, Generation.Outcome.MODEL_UNAVAILABLE, Outcomes.Reason.MODEL_UNAVAILABLE,
                        null, emptyUsage());
            }
            return;
        }
    }
    private void dispatch(Call call) {
        byte[] body;
        try { body = wireRequest(call.request); }
        catch (IllegalArgumentException exception) {
            synchronized (this) { done(call, Generation.Outcome.LIMIT, Outcomes.Reason.BUDGET_EXHAUSTED,
                    null, emptyUsage()); }
            return;
        }
        synchronized (this) {
            if (active != call || call.terminal) return;
            try {
                if (clock.millis() >= call.limits.total().deadlineEpochMillis()) throw new Budgets.Exhausted();
                call.allowance = call.limits.chargeCall(call.ledger, body.length,
                        call.request.role() == Generation.Role.REPAIR);
                call.inputBytes = body.length;
            } catch (Budgets.Exhausted exhausted) {
                done(call, Generation.Outcome.LIMIT, Outcomes.Reason.BUDGET_EXHAUSTED,
                        null, emptyUsage());
                return;
            }
            call.compute = Generation.Compute.RUNNING;
        }
        try {
            long remaining = call.limits.total().deadlineEpochMillis() - clock.millis();
            if (remaining <= 0) { expire(call); return; }
            Transfer transfer = backend.start(body, remaining, MAX_WIRE_BYTES, new Sink() {
                @Override public void chunk(byte[] bytes) { receive(call, bytes); }
                @Override public void complete(int status) { completed(call, status); }
                @Override public void error() { failed(call); }
                @Override public void stopped() { confirmedStop(call); }
                @Override public void modelDigest(String digest) { observedDigest(call, digest); }
            });
            synchronized (this) {
                if (active == call) {
                    call.transfer = Objects.requireNonNull(transfer);
                    if (call.terminal) requestStop(call);
                }
            }
        } catch (RuntimeException error) { failed(call); }
    }

    private synchronized void receive(Call call, byte[] chunk) {
        if (active != call || call.terminal) return;
        if (chunk == null || chunk.length > MAX_WIRE_BYTES - call.bytes.size()) {
            terminate(call, Generation.Outcome.LIMIT, Outcomes.Reason.BUDGET_EXHAUSTED);
        } else call.bytes.writeBytes(chunk);
    }
    private synchronized void completed(Call call, int status) {
        if (active != call) return;
        if (call.terminal) { call.compute = Generation.Compute.COMPLETED; release(call); return; }
        if (clock.millis() >= call.limits.total().deadlineEpochMillis()) {
            done(call, Generation.Outcome.LIMIT, Outcomes.Reason.BUDGET_EXHAUSTED,
                    null, emptyUsage()); return;
        }
        if (status != 200) {
            unavailable = status == 404 || status == 503 || status == 0;
            done(call, unavailable ? Generation.Outcome.MODEL_UNAVAILABLE
                    : Generation.Outcome.TRANSPORT_FAILED,
                    unavailable ? Outcomes.Reason.MODEL_UNAVAILABLE : Outcomes.Reason.ACTION_FAILED,
                    null, emptyUsage()); return;
        }
        long outputBytes = 0, promptTokens = -1, outputTokens = -1;
        try {
            String wire = StandardCharsets.UTF_8.newDecoder().decode(
                    ByteBuffer.wrap(call.bytes.toByteArray())).toString();
            Map<String, Object> envelope = StrictJson.transportObject(wire);
            if (envelope.get("prompt_eval_count") instanceof Long p && p >= 0) promptTokens = p;
            if (envelope.get("eval_count") instanceof Long o && o >= 0) outputTokens = o;
            @SuppressWarnings("unchecked") Map<String, Object> message = (Map<String, Object>) envelope.get("message");
            if (!"assistant".equals(message.get("role")) || !(message.get("content") instanceof String content))
                throw new IllegalArgumentException();
            byte[] candidate = content.getBytes(StandardCharsets.UTF_8);
            // Candidate counts toward the total allowance even when the syntax is malformed.
            if (candidate.length > call.limits.perResponseOutputBytes()) {
                call.allowance.accept(call.limits.perResponseOutputBytes());
                done(call, Generation.Outcome.LIMIT, Outcomes.Reason.BUDGET_EXHAUSTED,
                        null, new Generation.Usage(call.inputBytes, candidate.length, -1, -1,
                                Generation.Precision.UNKNOWN)); return;
            }
            call.allowance.accept(candidate.length);
            outputBytes = candidate.length;
            if (!Boolean.TRUE.equals(envelope.get("done"))
                    || envelope.containsKey("done_reason") && !"stop".equals(envelope.get("done_reason"))
                    || !config.model().equals(envelope.get("model"))) throw new IllegalArgumentException();
            Map<String, Object> ir = StrictJson.object(content);
            String candidateIr = content;
            if (call.request.capability().id().equals(CropDelivery.ID)) {
                var normalized = new java.util.HashMap<String, Object>(ir);
                normalized.put("body", cropIrBody(ir.get("body"), call.request.primitives()));
                ir = normalized;
                candidateIr = StrictJson.canonical(ir);
            }
            if (!ir.keySet().equals(java.util.Set.of("schema", "capability", "capabilityVersion",
                    "dependencies", "body")) || !Long.valueOf(1).equals(ir.get("schema"))
                    || !call.request.capability().id().name().equals(ir.get("capability"))
                    || !Long.valueOf(call.request.capability().id().version()).equals(ir.get("capabilityVersion"))
                    || !(ir.get("body") instanceof List<?>)
                    || !(ir.get("dependencies") instanceof List<?> refs)
                    || refs.size() > call.request.dependencies().size())
                throw new IllegalArgumentException();
            java.util.Set<String> permitted = new java.util.HashSet<>();
            for (var d : call.request.dependencies()) permitted.add(d.ref().toString());
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Object ref : refs) {
                if (!(ref instanceof Map<?, ?> map) || !map.keySet().equals(
                        java.util.Set.of("capability", "version", "sha256"))
                        || !(map.get("capability") instanceof String name)
                        || !(map.get("version") instanceof Long version)
                        || !(map.get("sha256") instanceof String sha)) throw new IllegalArgumentException();
                String key = new Contracts.ArtifactRef(new Contracts.CapabilityId(name,
                        Math.toIntExact(version)), sha).toString();
                if (!permitted.contains(key) || !seen.add(key)) throw new IllegalArgumentException();
            }
            if (outputTokens > config.maxOutputTokens()) {
                done(call, Generation.Outcome.LIMIT, Outcomes.Reason.BUDGET_EXHAUSTED,
                        null, new Generation.Usage(call.inputBytes, candidate.length,
                                -1, outputTokens, Generation.Precision.UNKNOWN)); return;
            }
            boolean measured = promptTokens >= 0 && outputTokens >= 0;
            Generation.Usage usage = new Generation.Usage(call.inputBytes, candidate.length,
                    measured ? promptTokens : -1, measured ? outputTokens : -1,
                    measured ? Generation.Precision.MEASURED : Generation.Precision.UNKNOWN);
            done(call, Generation.Outcome.CANDIDATE, null, candidateIr, usage);
        } catch (CharacterCodingException | StrictJson.Invalid | IllegalArgumentException
                 | NullPointerException | ClassCastException malformed) {
            done(call, Generation.Outcome.MALFORMED, Outcomes.Reason.ARTIFACT_INVALID,
                    null, new Generation.Usage(call.inputBytes, outputBytes,
                            promptTokens >= 0 && outputTokens >= 0 ? promptTokens : -1,
                            promptTokens >= 0 && outputTokens >= 0 ? outputTokens : -1,
                            promptTokens >= 0 && outputTokens >= 0
                                    ? Generation.Precision.MEASURED : Generation.Precision.UNKNOWN));
        } catch (Budgets.Exhausted exhausted) {
            done(call, Generation.Outcome.LIMIT, Outcomes.Reason.BUDGET_EXHAUSTED,
                    null, emptyUsage());
        }
    }
    private synchronized void failed(Call call) {
        if (active != call || call.terminal) return;
        unavailable = true;
        call.compute = Generation.Compute.STOP_UNCONFIRMED;
        finish(call, Generation.Outcome.MODEL_UNAVAILABLE, Outcomes.Reason.MODEL_UNAVAILABLE,
                null, new Generation.Usage(call.inputBytes, 0, -1, -1, Generation.Precision.UNKNOWN));
    }
    private synchronized void observedDigest(Call call, String digest) {
        if (active != call || call.terminal || digest == null
                || !digest.matches("[a-f0-9]{64}")) return;
        call.modelDescriptor = new Generation.Descriptor("ollama-chat-v1", config.model(), digest);
    }
    private synchronized void confirmedStop(Call call) {
        if (active != call) return;
        call.compute = Generation.Compute.STOP_CONFIRMED;
        if (!call.terminal && !call.terminating) finish(call, Generation.Outcome.CANCELLED,
                Outcomes.Reason.CANCELLED, null, emptyUsage());
        if (call.terminal && call.result.isDone()) release(call);
    }
    private void done(Call call, Generation.Outcome outcome, Outcomes.Reason reason,
                      String candidate, Generation.Usage usage) {
        call.compute = Generation.Compute.COMPLETED;
        finish(call, outcome, reason, candidate, usage);
    }
    private void release(Call call) {
        if (active == call) { active = null; startNext(); }
    }
    private static Generation.Usage emptyUsage() {
        return new Generation.Usage(0, 0, -1, -1, Generation.Precision.UNKNOWN);
    }
    private void finish(Call call, Generation.Outcome outcome, Outcomes.Reason reason,
                        String candidate, Generation.Usage usage) {
        if (call.terminal) return;
        Generation.Result result = new Generation.Result(call.id(), call.request.bound().context(),
                call.request.role(), outcome, reason, candidate, call.modelDescriptor, usage, call.compute);
        // Mark terminal before handoff; completion runs off the caller/game thread in production.
        call.terminal = true;
        if (call.deadline != null) call.deadline.cancel(false);
        if (!call.enqueued) { call.result.complete(result); return; }
        boolean queued = active != call;
        if (queued) pendingQueuedHandoffs++;
        handoff.execute(() -> {
            try {
                synchronized (LocalGenerationAdapter.this) {
                    if (active == call && (call.compute == Generation.Compute.COMPLETED
                            || call.compute == Generation.Compute.STOP_CONFIRMED)) release(call);
                }
                call.result.complete(result);
            }
            finally {
                synchronized (LocalGenerationAdapter.this) {
                    if (queued) pendingQueuedHandoffs--;
                }
            }
        });
    }
    private void requestStop(Call call) {
        boolean stopped = call.transfer != null && call.transfer.cancel();
        if (stopped) { call.compute = Generation.Compute.STOP_CONFIRMED;
            if (call.terminal && call.result.isDone()) release(call);
        }
        else call.compute = Generation.Compute.STOP_UNCONFIRMED;
    }
    private synchronized void terminate(Call call, Generation.Outcome outcome, Outcomes.Reason reason) {
        if (call.terminal) return;
        if (waiting.remove(call)) {
            finish(call, outcome, reason, null, emptyUsage()); return;
        }
        if (active != call) return;
        if (call.compute == Generation.Compute.NOT_STARTED) {
            // dispatch() checks active and terminal under the same monitor. No external call
            // can start after this cancellation, so preparation has confirmed cessation.
            call.compute = Generation.Compute.STOP_CONFIRMED;
            finish(call, outcome, reason, null, emptyUsage()); return;
        }
        call.terminating = true;
        requestStop(call);
        finish(call, call.compute == Generation.Compute.STOP_CONFIRMED
                        && outcome == Generation.Outcome.ABANDONED ? Generation.Outcome.CANCELLED : outcome,
                reason, null,
                new Generation.Usage(call.inputBytes, 0, -1, -1, Generation.Precision.UNKNOWN));
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Call call : List.copyOf(waiting)) terminate(call, Generation.Outcome.ABANDONED,
                Outcomes.Reason.CANCELLED);
        if (active != null) terminate(active, Generation.Outcome.ABANDONED, Outcomes.Reason.CANCELLED);
        if (ownsExecutors) {
            timer.shutdownNow();
            ((ExecutorService) worker).shutdown();
            ((ExecutorService) handoff).shutdown();
        }
    }
    private final class Call implements Generation.Handle {
        final Generation.Request request;
        final Budgets.InferenceLimits limits;
        final Budgets.Ledger ledger;
        final CompletableFuture<Generation.Result> result = new CompletableFuture<>();
        final CompletionStage<Generation.Result> readOnly = result.minimalCompletionStage();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Generation.Compute compute = Generation.Compute.NOT_STARTED;
        Budgets.ResponseAllowance allowance;
        Transfer transfer;
        Generation.Descriptor modelDescriptor = descriptor;
        long inputBytes;
        boolean terminal;
        boolean terminating;
        boolean enqueued;
        ScheduledFuture<?> deadline;
        Call(Generation.Request request, Budgets.InferenceLimits limits, Budgets.Ledger ledger) {
            this.request = request; this.limits = limits; this.ledger = ledger;
        }
        @Override public UUID id() { return request.id(); }
        @Override public CompletionStage<Generation.Result> result() { return readOnly; }
        @Override public boolean cancel() {
            synchronized (LocalGenerationAdapter.this) {
                if (terminal) return false;
                terminate(this, compute == Generation.Compute.NOT_STARTED
                        ? Generation.Outcome.CANCELLED : Generation.Outcome.ABANDONED,
                        Outcomes.Reason.CANCELLED);
                return true;
            }
        }
        @Override public Generation.Compute compute() {
            synchronized (LocalGenerationAdapter.this) { return compute; }
        }
    }

    private byte[] wireRequest(Generation.Request request) {
        var primitives = request.primitives().stream().map(p -> Map.of("id", p.id(),
                "version", (long) p.version(), "fingerprint", p.fingerprint(),
                "arguments", p.parameters().stream().map(a -> Map.of("name", a.name(),
                        "type", a.type().name(), "minimum", a.minimum(), "maximum", a.maximum())).toList(),
                "result", p.resultType().name())).toList();
        var dependencies = request.dependencies().stream().map(d -> Map.of("capability",
                d.ref().capability().name(), "version", (long) d.ref().capability().version(),
                "sha256", d.ref().sha256())).toList();
        var spec = request.capability();
        String user = StrictJson.canonical(Map.of("schema", 1L, "role", request.role().name(),
                "capability", spec.id().name(), "capabilityVersion", (long) spec.id().version(),
                "parameters", spec.parameters().stream().map(p -> Map.of("name", p.name(),
                        "type", p.type().name(), "minimum", p.minimum(), "maximum", p.maximum())).toList(),
                "primitives", primitives, "dependencies", dependencies, "context", request.context()));
        var dependencyShape = Map.of("type", "object", "required",
                List.of("capability", "version", "sha256"), "additionalProperties", false,
                "properties", Map.of("capability", Map.of("type", "string"),
                        "version", Map.of("type", "integer"), "sha256", Map.of("type", "string")));
        List<String> primitiveIds = request.primitives().stream()
                .map(Contracts.PrimitiveSignature::id).toList();
        List<String> fingerprints = request.primitives().stream()
                .map(Contracts.PrimitiveSignature::fingerprint).distinct().toList();
        Map<String, Object> bodyShape = spec.id().equals(CropDelivery.ID)
                ? cropBodyShape(request.primitives()) : Map.of("type", "array",
                        "items", nodeShape(2, primitiveIds, fingerprints, true),
                        "minItems", 1, "maxItems", 8);
        var shape = Map.of("type", "object", "required", List.of("schema", "capability",
                        "capabilityVersion", "dependencies", "body"),
                "properties", Map.of("schema", Map.of("type", "integer", "enum", List.of(1)),
                        "capability", Map.of("type", "string", "enum", List.of(spec.id().name())),
                        "capabilityVersion", Map.of("type", "integer", "enum", List.of(spec.id().version())),
                        "dependencies", Map.of("type", "array", "items", dependencyShape,
                                "maxItems", request.dependencies().size()),
                        "body", bodyShape),
                "additionalProperties", false);
        String wire = StrictJson.canonical(Map.of("model", config.model(), "stream", false,
                "format", shape, "options", Map.of("temperature", 0, "num_predict", config.maxOutputTokens()),
                "messages", List.of(Map.of("role", "system", "content",
                        spec.id().equals(CropDelivery.ID)
                                ? "Return the JSON proposal only. Choose registered primitive IDs for prepare, "
                                + "wait.primitive and each finish entry in causal order. "
                                + "The adapter binds registered signatures to Skill IR; the compiler and trial validate it. "
                                + "No Java or authority claims."
                                : "Return a short parameterized Skill IR JSON object only. Each body node has an op. "
                                + "Use registered calls and repeat nodes instead of unrolling actions or ticks. "
                                + "End the root body with a result node. No Java or authority claims."),
                        Map.of("role", "user", "content", user))));
        byte[] body = wire.getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_REQUEST_BYTES) throw new IllegalArgumentException("Request size");
        return body;
    }

    /** The model selects each primitive and wait count; the adapter lowers its bounded plan to Skill IR. */
    private static Map<String, Object> cropBodyShape(List<Contracts.PrimitiveSignature> primitives) {
        var amount = Map.of("type", "object", "required", List.of("param"),
                "properties", Map.of("param", Map.of("type", "string",
                        "enum", List.of("amount"))), "additionalProperties", false);
        var primitiveId = Map.of("type", "string", "enum", primitives.stream()
                .map(Contracts.PrimitiveSignature::id).toList());
        var wait = Map.of("type", "object", "required", List.of("primitive", "count"),
                "properties", Map.of("primitive", primitiveId,
                        "count", Map.of("type", "integer", "minimum", 20, "maximum", 30)),
                "additionalProperties", false);
        boolean movement = primitives.stream().anyMatch(p -> p.id().equals("cognitivecraft:move_to_source"));
        Map<String,Object> prepare = movement ? Map.of("type","array","items",primitiveId,"minItems",2,"maxItems",2) : primitiveId;
        var loop = Map.of("type", "object", "required", List.of("count", "body"),
                "properties", Map.of("count", amount, "body", Map.of("type", "object", "required",
                                List.of("prepare", "wait", "finish"),
                                "properties", Map.of("prepare", prepare,
                                        "wait", wait, "finish", Map.of("type", "array",
                                                "items", primitiveId,
                                                "minItems", movement ? 3 : 2, "maxItems", movement ? 3 : 2)),
                                "additionalProperties", false)), "additionalProperties", false);
        return Map.of("type", "object", "required", List.of("loop", "result"),
                "properties", Map.of("loop", loop, "result", amount),
                "additionalProperties", false);
    }

    private static List<Object> cropIrBody(Object proposal,
            List<Contracts.PrimitiveSignature> primitives) {
        boolean movement = primitives.stream().anyMatch(p -> p.id().equals("cognitivecraft:move_to_source"));
        if (!(proposal instanceof Map<?, ?> parts)
                || !parts.keySet().equals(java.util.Set.of("loop", "result"))
                || !(parts.get("loop") instanceof Map<?, ?> loop)
                || !loop.keySet().equals(java.util.Set.of("count", "body"))
                || !Map.of("param", "amount").equals(loop.get("count"))
                || !Map.of("param", "amount").equals(parts.get("result"))
                || !(loop.get("body") instanceof Map<?, ?> steps)
                || !steps.keySet().equals(java.util.Set.of("prepare", "wait", "finish"))
                || !(steps.get("wait") instanceof Map<?, ?> wait)
                || !wait.keySet().equals(java.util.Set.of("primitive", "count"))
                || !(wait.get("primitive") instanceof String waitId)
                || !(wait.get("count") instanceof Long count) || count < 20 || count > 30
                || !(steps.get("finish") instanceof List<?> finish) || finish.size() != (movement ? 3 : 2))
            throw new IllegalArgumentException("Crop proposal shape");
        List<?> prepare;
        if (movement) {
            if (!(steps.get("prepare") instanceof List<?> values) || values.size() != 2)
                throw new IllegalArgumentException("Movement prepare shape");
            prepare = values;
        } else {
            if (!(steps.get("prepare") instanceof String id)) throw new IllegalArgumentException("Prepare shape");
            prepare = List.of(id);
        }
        List<Object> calls = new java.util.ArrayList<>();
        for (Object value : prepare) {
            if (!(value instanceof String id)) throw new IllegalArgumentException("Primitive ID");
            calls.add(cropCall(id,primitives));
        }
        calls.add(Map.of("op","repeat","count",Map.of("int",count),"body",List.of(cropCall(waitId,primitives))));
        for (Object value : finish) {
            if (!(value instanceof String id)) throw new IllegalArgumentException("Primitive ID");
            calls.add(cropCall(id,primitives));
        }
        return List.of(Map.of("op","repeat","count",Map.of("param","amount"),"body",List.copyOf(calls)),
                Map.of("op","result","value",Map.of("param","amount")));
    }

    /** Signature metadata and bindings come only from the registered request allowlist. */
    private static Map<String, Object> cropCall(String id,
            List<Contracts.PrimitiveSignature> primitives) {
        var primitive = primitives.stream().filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unregistered primitive"));
        var args = new java.util.LinkedHashMap<String, Object>();
        for (var parameter : primitive.parameters()) {
            Object binding = switch (parameter.name()) {
                case "actor" -> parameter.type() == Contracts.Type.ACTOR
                        ? Map.of("param", "actor") : null;
                case "source" -> parameter.type() == Contracts.Type.AREA
                        ? Map.of("param", "source") : null;
                case "destination" -> parameter.type() == Contracts.Type.CONTAINER
                        ? Map.of("param", "destination") : null;
                case "amount" -> parameter.type() == Contracts.Type.INT
                        && parameter.minimum() <= 1 && parameter.maximum() >= 1
                        ? Map.of("int", 1L) : null;
                default -> null;
            };
            if (binding == null) throw new IllegalArgumentException("Unsupported primitive binding");
            args.put(parameter.name(), binding);
        }
        return Map.of("op", "call", "kind", "primitive", "id", primitive.id(),
                "version", (long) primitive.version(), "fingerprint", primitive.fingerprint(),
                "args", args, "into", id.substring(id.lastIndexOf(':') + 1));
    }

    /** A bounded proposal grammar, not an admission validator or a prewritten procedure. */
    private static Map<String, Object> nodeShape(int repeatsLeft, List<String> primitiveIds,
                                                  List<String> fingerprints, boolean root) {
        return nodeShape(repeatsLeft, primitiveIds, fingerprints, root, 8);
    }
    private static Map<String, Object> nodeShape(int repeatsLeft, List<String> primitiveIds,
                                                  List<String> fingerprints, boolean root,
                                                  int nestedMaxItems) {
        var expression = Map.of("type", "object", "minProperties", 1, "maxProperties", 1,
                "properties", Map.of("param", Map.of("type", "string"),
                        "local", Map.of("type", "string"),
                        "int", Map.of("type", "integer"),
                        "bool", Map.of("type", "boolean")), "additionalProperties", false);
        var call = Map.of("type", "object", "required", List.of("op", "kind", "id",
                        "version", "fingerprint", "args", "into"),
                "properties", Map.of("op", Map.of("type", "string", "enum", List.of("call")),
                        "kind", Map.of("type", "string", "enum", List.of("primitive")),
                        "id", Map.of("type", "string", "enum", primitiveIds),
                        "version", Map.of("type", "integer", "enum", List.of(1)),
                        "fingerprint", Map.of("type", "string", "enum", fingerprints),
                        "args", Map.of("type", "object", "maxProperties", 8,
                                "additionalProperties", expression),
                        "into", Map.of("type", "string", "maxLength", 64)),
                "additionalProperties", false);
        var alternatives = new java.util.ArrayList<Map<String, Object>>();
        alternatives.add(call);
        if (repeatsLeft > 0) alternatives.add(Map.of("type", "object",
                "required", List.of("op", "count", "body"),
                "properties", Map.of("op", Map.of("type", "string", "enum", List.of("repeat")),
                        "count", expression, "body", Map.of("type", "array",
                                "items", nodeShape(repeatsLeft - 1, primitiveIds, fingerprints,
                                        false, nestedMaxItems),
                                "minItems", 1, "maxItems", nestedMaxItems)), "additionalProperties", false));
        if (root) alternatives.add(Map.of("type", "object", "required", List.of("op", "value"),
                "properties", Map.of("op", Map.of("type", "string", "enum", List.of("result")),
                        "value", expression), "additionalProperties", false));
        return Map.of("oneOf", alternatives);
    }
}
