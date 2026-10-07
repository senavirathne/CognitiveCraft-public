package dev.aivillages.providers;

import com.sun.net.httpserver.HttpServer;
import dev.aivillages.core.kernel.Budgets;
import dev.aivillages.core.kernel.Contracts;
import dev.aivillages.core.kernel.CropDelivery;
import dev.aivillages.core.kernel.GatewayPrimitives;
import dev.aivillages.core.kernel.Generation;
import dev.aivillages.core.kernel.StrictJson;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static dev.aivillages.core.kernel.Budgets.Kind;

/** Standalone deterministic acceptance checks; JUnit entry points run the same groups. */
public final class GenerationChecks {
    private GenerationChecks() { }
    private static final String CANDIDATE = "{\"schema\":1,\"capability\":\"cognitivecraft:deliver_wheat\",\"capabilityVersion\":1,\"dependencies\":[],\"body\":{\"loop\":{\"count\":{\"param\":\"amount\"},\"body\":{\"prepare\":\"cognitivecraft:harvest_next_wheat\",\"wait\":{\"primitive\":\"cognitivecraft:observe_inventory\",\"count\":30},\"finish\":[\"cognitivecraft:pickup_tracked_wheat\",\"cognitivecraft:transfer_wheat\"]}},\"result\":{\"param\":\"amount\"}}}";

    static final class Time extends Clock {
        long now = 1_000_000;
        @Override public long millis() { return now; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
    static final class Fake implements LocalGenerationAdapter.Backend {
        final List<LocalGenerationAdapter.Sink> sinks = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        int cancels;
        boolean confirmed;
        @Override public LocalGenerationAdapter.Transfer start(byte[] body, long remaining, int max,
                LocalGenerationAdapter.Sink sink) {
            check(remaining > 0 && max == LocalGenerationAdapter.MAX_WIRE_BYTES, "transport bounds");
            sinks.add(sink); bodies.add(new String(body, StandardCharsets.UTF_8));
            return () -> { cancels++; return confirmed; };
        }
        void respond(int index, String candidate, Long promptTokens, Long outputTokens) {
            String reply = StrictJson.canonical(Map.of("model", "installed:test", "done", true,
                    "done_reason", "stop", "message", Map.of("role", "assistant", "content", candidate)));
            if (promptTokens != null && outputTokens != null)
                reply = reply.substring(0, reply.length() - 1) + ",\"prompt_eval_count\":" + promptTokens
                        + ",\"eval_count\":" + outputTokens + "}";
            byte[] wire = reply.getBytes(StandardCharsets.UTF_8);
            int mid = wire.length / 2;
            sinks.get(index).chunk(java.util.Arrays.copyOfRange(wire, 0, mid));
            sinks.get(index).chunk(java.util.Arrays.copyOfRange(wire, mid, wire.length));
            sinks.get(index).complete(200);
        }
    }
    static final class Fixture implements AutoCloseable {
        final Time clock = new Time();
        final Fake backend = new Fake();
        final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final LocalGenerationAdapter adapter;
        Fixture() { this(Runnable::run); }
        Fixture(Executor worker) {
            timer.setRemoveOnCancelPolicy(true);
            adapter = new LocalGenerationAdapter(config(true), backend, clock, worker,
                    Runnable::run, timer);
        }
        Generation.Handle generate(Generation.Role role, String context, Budgets.Ledger ledger,
                                   Budgets.InferenceLimits limits) {
            return adapter.generate(request(role, context), limits, ledger);
        }
        @Override public void close() { adapter.close(); timer.shutdownNow(); }
    }
    private static LocalGenerationAdapter.Config config(boolean enabled) {
        return new LocalGenerationAdapter.Config(URI.create("http://127.0.0.1:11434/api/chat"),
                "installed:test", enabled, 256);
    }
    static Generation.Request request(Generation.Role role, String context) {
        var actor = new Contracts.ActorRef(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld");
        var owner = new Contracts.TrustedContext(new Contracts.PrincipalRef(UUID.randomUUID()),
                new Contracts.ScopeRef(UUID.randomUUID(), UUID.randomUUID()));
        var bound = new Contracts.ValidatedRequest(new Contracts.CapabilityRequest(CropDelivery.ID,
                Map.of("actor", new Contracts.ActorValue(actor), "amount", new Contracts.IntValue(2),
                        "source", new Contracts.AreaValue(new Contracts.Cuboid("minecraft:overworld",
                                0, 64, 0, 2, 64, 2)), "destination", new Contracts.ContainerValue(
                                        new Contracts.ContainerRef("minecraft:overworld", 5, 64, 0)))),
                owner, new Contracts.ObservationRef(UUID.randomUUID(), 0, "minecraft:overworld"));
        return new Generation.Request(UUID.randomUUID(), bound, CropDelivery.SPEC,
                List.of(GatewayPrimitives.Operation.HARVEST_NEXT_WHEAT.signature(),
                        GatewayPrimitives.Operation.OBSERVE_INVENTORY.signature(),
                        GatewayPrimitives.Operation.PICKUP_TRACKED_WHEAT.signature(),
                        GatewayPrimitives.Operation.TRANSFER_WHEAT.signature()), List.of(), role, context);
    }
    private static Budgets.InferenceLimits limits(Time time, long output) {
        return new Budgets.InferenceLimits(new Budgets.Limits(Map.of(Kind.CALLS, 3L,
                Kind.REPAIRS, 1L, Kind.INPUT_BYTES, 48_000L, Kind.OUTPUT_BYTES, 30_000L),
                time.millis() + 10_000), 16_384, output);
    }
    private static Budgets.Ledger ledger(Time time, Budgets.InferenceLimits limits) {
        return new Budgets.Ledger(limits.total(), time);
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    public static void structuredRequest() throws Exception {
        try (var f = new Fixture()) {
            var limits = limits(f.clock, 500); var ledger = ledger(f.clock, limits);
            var request = request(Generation.Role.INITIAL, "bounded source observations");
            var handle = f.adapter.generate(request, limits, ledger);
            var wire = StrictJson.transportObject(f.backend.bodies.get(0));
            equal("installed:test", wire.get("model")); equal(false, wire.get("stream"));
            check(wire.containsKey("format") && !f.backend.bodies.get(0).contains(
                    request.bound().context().principal().id().toString()), "private routing is not prompt content");
            @SuppressWarnings("unchecked") var shape = (Map<String, Object>) wire.get("format");
            @SuppressWarnings("unchecked") var fields = (Map<String, Object>) shape.get("properties");
            @SuppressWarnings("unchecked") var capability = (Map<String, Object>) fields.get("capability");
            equal(List.of(CropDelivery.ID.name()), capability.get("enum"));
            @SuppressWarnings("unchecked") var body = (Map<String, Object>) fields.get("body");
            equal("object", body.get("type"));
            equal(List.of("loop", "result"), body.get("required"));
            @SuppressWarnings("unchecked") var positions = (Map<String, Map<String, Object>>) body.get("properties");
            @SuppressWarnings("unchecked") var first = (Map<String, Object>) positions.get("loop").get("properties");
            @SuppressWarnings("unchecked") var last = (Map<String, Object>) positions.get("result").get("properties");
            equal(List.of("amount"), ((Map<?, ?>) last.get("param")).get("enum"));
            @SuppressWarnings("unchecked") var loop = (Map<String, Object>) first.get("body");
            @SuppressWarnings("unchecked") var stepFields = (Map<String, Object>) loop.get("properties");
            @SuppressWarnings("unchecked") var choices = (Map<String, Object>) stepFields.get("prepare");
            @SuppressWarnings("unchecked") var repeatFields = (Map<String, Object>) stepFields.get("wait");
            @SuppressWarnings("unchecked") var waitFields = (Map<String, Object>) repeatFields.get("properties");
            equal(20L, ((Map<?, ?>) waitFields.get("count")).get("minimum"));
            equal(30L, ((Map<?, ?>) waitFields.get("count")).get("maximum"));
            equal(request.primitives().stream().map(Contracts.PrimitiveSignature::id).toList(),
                    choices.get("enum"));
            equal(choices.get("enum"), ((Map<?, ?>) waitFields.get("primitive")).get("enum"));
            check(f.backend.bodies.get(0).contains("harvest_next_wheat")
                    && f.backend.bodies.get(0).contains("INITIAL"), "allowlist and role");
            f.backend.respond(0, CANDIDATE, 16L, 22L);
            var result = handle.result().toCompletableFuture().join();
            equal(Generation.Outcome.CANDIDATE, result.outcome());
            equal(request.id(), result.id()); equal(request.bound().context(), result.owner());
            var normalized = StrictJson.object(result.candidateIr());
            @SuppressWarnings("unchecked") var nodesOut = (List<Map<String, Object>>) normalized.get("body");
            equal(List.of("repeat", "result"), nodesOut.stream().map(node -> node.get("op")).toList());
            @SuppressWarnings("unchecked") var work = (List<Map<String, Object>>) nodesOut.get(0).get("body");
            equal(List.of("call", "repeat", "call", "call"),
                    work.stream().map(node -> node.get("op")).toList());
            equal(List.of("cognitivecraft:harvest_next_wheat", "cognitivecraft:pickup_tracked_wheat",
                            "cognitivecraft:transfer_wheat"),
                    List.of(work.get(0).get("id"), work.get(2).get("id"), work.get(3).get("id")));
            @SuppressWarnings("unchecked") var nested = (List<Map<String, Object>>) work.get(1).get("body");
            equal("cognitivecraft:observe_inventory", nested.get(0).get("id"));
            for (var call : List.of(work.get(0), nested.get(0), work.get(2), work.get(3))) {
                var signature = request.primitives().stream().filter(p -> p.id().equals(call.get("id")))
                        .findFirst().orElseThrow();
                equal(signature.fingerprint(), call.get("fingerprint"));
                equal((long) signature.version(), call.get("version"));
            }
            equal(Map.of("actor", Map.of("param", "actor"), "source", Map.of("param", "source")),
                    work.get(0).get("args"));
            equal(Map.of("actor", Map.of("param", "actor"),
                    "destination", Map.of("param", "destination"), "amount", Map.of("int", 1L)),
                    work.get(3).get("args"));
            equal((long) CANDIDATE.getBytes(StandardCharsets.UTF_8).length,
                    result.usage().outputBytes());
            equal(Generation.Precision.MEASURED, result.usage().tokenPrecision());
            equal(1L, ledger.snapshot().get(Kind.CALLS));
        }
    }
    public static void generationRepair() {
        try (var f = new Fixture()) {
            var limits = limits(f.clock, 500); var ledger = ledger(f.clock, limits);
            var initial = f.generate(Generation.Role.INITIAL, "world-A", ledger, limits);
            f.backend.respond(0, CANDIDATE, 1L, 1L);
            equal(Generation.Outcome.CANDIDATE, initial.result().toCompletableFuture().join().outcome());
            var repair = f.generate(Generation.Role.REPAIR, "world-B", ledger, limits);
            f.backend.respond(1, CANDIDATE, null, null);
            equal(Generation.Role.REPAIR, repair.result().toCompletableFuture().join().role());
            equal(1L, ledger.snapshot().get(Kind.REPAIRS));
            equal(2L, ledger.snapshot().get(Kind.CALLS));
            equal(Generation.Outcome.LIMIT, f.generate(Generation.Role.REPAIR,
                    "world-C", ledger, limits).result().toCompletableFuture().join().outcome());
            equal(2, f.backend.sinks.size());
            check(!f.backend.bodies.get(1).contains("world-A"), "context must not merge");
        }
    }
    public static void transportBounds() {
        try (var f = new Fixture()) {
            var limits = limits(f.clock, 2_048); var handle = f.generate(Generation.Role.INITIAL,
                    "", ledger(f.clock, limits), limits);
            String longEnvelopeContent = CANDIDATE + " ".repeat(1_100);
            f.backend.respond(0, longEnvelopeContent, 42L, 200L);
            var result = handle.result().toCompletableFuture().join();
            equal(Generation.Outcome.CANDIDATE, result.outcome());
            check(StrictJson.object(result.candidateIr()).get("body") instanceof List<?> nodes
                    && nodes.size() == 2, "long transport string normalized to bounded IR");
            equal((long) longEnvelopeContent.length(), result.usage().outputBytes());
        } catch (StrictJson.Invalid malformed) { throw new AssertionError(malformed); }
        try (var f = new Fixture()) {
            var limits = limits(f.clock, 500); var handle = f.generate(Generation.Role.INITIAL,
                    "", ledger(f.clock, limits), limits);
            f.backend.sinks.get(0).chunk(new byte[LocalGenerationAdapter.MAX_WIRE_BYTES]);
            f.backend.sinks.get(0).chunk(new byte[1]);
            equal(Generation.Outcome.LIMIT, handle.result().toCompletableFuture().join().outcome());
            equal(Generation.Compute.STOP_UNCONFIRMED, handle.compute());
            equal(1, f.backend.cancels);
        }
        for (String response : List.of("{", "{\"model\":\"installed:test\",\"done\":true,\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"schema\\\":1,\\\"schema\\\":1}\"}}")) {
            try (var f = new Fixture()) {
                var limits = limits(f.clock, 500); var handle = f.generate(Generation.Role.INITIAL,
                        "", ledger(f.clock, limits), limits);
                f.backend.sinks.get(0).chunk(response.getBytes(StandardCharsets.UTF_8));
                f.backend.sinks.get(0).complete(200);
                equal(Generation.Outcome.MALFORMED, handle.result().toCompletableFuture().join().outcome());
            }
        }
        try (var f = new Fixture()) {
            var limits = limits(f.clock, 500); var handle = f.generate(Generation.Role.INITIAL,
                    "", ledger(f.clock, limits), limits);
            f.backend.respond(0, CANDIDATE.replace("\"result\":{\"param\"", "\"extra\":{\"param\""), null, null);
            equal(Generation.Outcome.MALFORMED, handle.result().toCompletableFuture().join().outcome());
        }
        try (var f = new Fixture()) {
            var limits = limits(f.clock, 500); var handle = f.generate(Generation.Role.INITIAL,
                    "", ledger(f.clock, limits), limits);
            f.backend.respond(0, CANDIDATE.replace("cognitivecraft:harvest_next_wheat",
                    "cognitivecraft:unregistered_primitive"), null, null);
            equal(Generation.Outcome.MALFORMED, handle.result().toCompletableFuture().join().outcome());
        }
        try (var f = new Fixture()) {
            var limits = limits(f.clock, 500); var usage = ledger(f.clock, limits);
            var handle = f.generate(Generation.Role.INITIAL, "", usage, limits);
            String truncated = StrictJson.canonical(Map.of("model", "installed:test", "done", true,
                    "done_reason", "length", "prompt_eval_count", 13L, "eval_count", 256L,
                    "message", Map.of("role", "assistant", "content", CANDIDATE)));
            f.backend.sinks.get(0).chunk(truncated.getBytes(StandardCharsets.UTF_8));
            f.backend.sinks.get(0).complete(200);
            var result = handle.result().toCompletableFuture().join();
            equal(Generation.Outcome.MALFORMED, result.outcome());
            equal((long) CANDIDATE.length(), result.usage().outputBytes());
            equal(256L, result.usage().outputTokens());
            equal((long) CANDIDATE.length(), usage.snapshot().get(Kind.OUTPUT_BYTES));
        }
        for (long cap : List.of((long) CANDIDATE.getBytes(StandardCharsets.UTF_8).length,
                (long) CANDIDATE.getBytes(StandardCharsets.UTF_8).length - 1)) {
            try (var f = new Fixture()) {
                var limits = limits(f.clock, cap); var handle = f.generate(Generation.Role.INITIAL,
                        "", ledger(f.clock, limits), limits);
                f.backend.respond(0, CANDIDATE, null, null);
                equal(cap == CANDIDATE.length() ? Generation.Outcome.CANDIDATE
                        : Generation.Outcome.LIMIT, handle.result().toCompletableFuture().join().outcome());
            }
        }
    }
    public static void deadline() {
        var queued = new ArrayDeque<Runnable>();
        try (var f = new Fixture(queued::add)) {
            var limits = limits(f.clock, 500); var ledger = ledger(f.clock, limits);
            var first = f.generate(Generation.Role.INITIAL, "cold", ledger, limits);
            var second = f.generate(Generation.Role.INITIAL, "queued", ledger, limits);
            f.clock.now += 10_001; f.adapter.sweep();
            equal(Generation.Outcome.LIMIT, first.result().toCompletableFuture().join().outcome());
            equal(Generation.Outcome.LIMIT, second.result().toCompletableFuture().join().outcome());
            queued.remove().run(); equal(0, f.backend.sinks.size());
            equal(0L, ledger.snapshot().getOrDefault(Kind.CALLS, 0L));
        }
        try (var f = new Fixture()) {
            var limits = limits(f.clock, 500); var handle = f.generate(Generation.Role.INITIAL,
                    "active", ledger(f.clock, limits), limits);
            f.clock.now += 10_001; f.adapter.sweep();
            equal(Generation.Outcome.LIMIT, handle.result().toCompletableFuture().join().outcome());
            equal(Generation.Compute.STOP_UNCONFIRMED, handle.compute());
        }
    }
    public static void cancellation() {
        var queued = new ArrayDeque<Runnable>();
        try (var f = new Fixture(queued::add)) {
            var limits = limits(f.clock, 500);
            var handle = f.generate(Generation.Role.INITIAL, "", ledger(f.clock, limits), limits);
            check(handle.cancel(), "first cancel"); check(!handle.cancel(), "idempotent cancel");
            equal(Generation.Outcome.CANCELLED, handle.result().toCompletableFuture().join().outcome());
            queued.remove().run(); equal(0, f.backend.sinks.size());
        }
        try (var f = new Fixture()) {
            var limits = limits(f.clock, 500); var ledger = ledger(f.clock, limits);
            var first = f.generate(Generation.Role.INITIAL, "one", ledger, limits);
            var second = f.generate(Generation.Role.INITIAL, "two", ledger, limits);
            check(first.cancel(), "abandon");
            equal(Generation.Outcome.ABANDONED, first.result().toCompletableFuture().join().outcome());
            equal(Generation.Compute.STOP_UNCONFIRMED, first.compute());
            equal(1, f.backend.sinks.size()); equal(1, f.adapter.status().waiting());
            f.backend.respond(0, CANDIDATE, 2L, 2L);
            equal(Generation.Outcome.ABANDONED, first.result().toCompletableFuture().join().outcome());
            equal(2, f.backend.sinks.size());
            f.backend.respond(1, CANDIDATE, 2L, 2L);
            equal(Generation.Outcome.CANDIDATE, second.result().toCompletableFuture().join().outcome());
        }
        try (var f = new Fixture()) {
            f.backend.confirmed = true; var limits = limits(f.clock, 500);
            var handle = f.generate(Generation.Role.INITIAL, "", ledger(f.clock, limits), limits);
            check(handle.cancel(), "confirmed cancel");
            equal(Generation.Outcome.CANCELLED, handle.result().toCompletableFuture().join().outcome());
            equal(Generation.Compute.STOP_CONFIRMED, handle.compute());
        }
    }
    public static void availabilityAndLifecycle() {
        var timer = new ScheduledThreadPoolExecutor(1);
        try (var disabled = new LocalGenerationAdapter(config(false), new Fake(),
                new Time(), Runnable::run, Runnable::run, timer)) {
            equal(Generation.State.DISABLED, disabled.status().state());
            var time = new Time(); var limits = limits(time, 500);
            equal(Generation.Outcome.MODEL_UNAVAILABLE, disabled.generate(request(
                    Generation.Role.INITIAL, ""), limits, ledger(time, limits)).result().toCompletableFuture().join().outcome());
        } finally { timer.shutdownNow(); }
        try (var f = new Fixture()) {
            equal(Generation.State.READY, f.adapter.status().state());
            check(!f.adapter.status().loadSupported() && !f.adapter.status().unloadSupported(),
                    "externally managed model controls unsupported");
            equal(0, f.backend.sinks.size());
            var limits = limits(f.clock, 500); var handle = f.generate(Generation.Role.INITIAL,
                    "", ledger(f.clock, limits), limits);
            equal(Generation.State.IN_FLIGHT, f.adapter.status().state());
            f.backend.sinks.get(0).complete(404);
            equal(Generation.Outcome.MODEL_UNAVAILABLE, handle.result().toCompletableFuture().join().outcome());
            equal(Generation.State.UNAVAILABLE, f.adapter.status().state());
        }
        for (String uri : List.of("https://localhost:11434/api/chat", "http://example.com/api/chat",
                "http://localhost:11434/api/pull", "http://user:pass@localhost:11434/api/chat")) {
            try { new LocalGenerationAdapter.Config(URI.create(uri), "installed:test", true, 256);
                throw new AssertionError("accepted remote or download endpoint"); }
            catch (IllegalArgumentException expected) { }
        }
    }
    public static void duplicateCallbacksAndShutdown() {
        try (var f = new Fixture()) {
            var limits = limits(f.clock, 500); var ledger = ledger(f.clock, limits);
            var first = f.generate(Generation.Role.INITIAL, "", ledger, limits);
            f.backend.respond(0, CANDIDATE, null, null);
            f.backend.respond(0, CANDIDATE, null, null);
            equal(Generation.Outcome.CANDIDATE, first.result().toCompletableFuture().join().outcome());
            equal(1L, ledger.snapshot().get(Kind.CALLS));
            equal((long) CANDIDATE.length(), ledger.snapshot().get(Kind.OUTPUT_BYTES));
            var second = f.generate(Generation.Role.INITIAL, "", ledger, limits);
            f.adapter.close();
            equal(Generation.Outcome.ABANDONED, second.result().toCompletableFuture().join().outcome());
            f.backend.respond(1, CANDIDATE, null, null);
            equal(Generation.Outcome.ABANDONED, second.result().toCompletableFuture().join().outcome());
            equal(Generation.State.CLOSED, f.adapter.status().state());
        }
    }
    public static void isolationAndUsage() {
        try (var f = new Fixture()) {
            var limits = limits(f.clock, 500); var ledger = ledger(f.clock, limits);
            var a = request(Generation.Role.INITIAL, "private-a");
            var b = request(Generation.Role.REPAIR, "private-b");
            var first = f.adapter.generate(a, limits, ledger);
            var second = f.adapter.generate(b, limits, ledger);
            f.backend.respond(0, CANDIDATE, null, null);
            equal(Generation.Precision.UNKNOWN, first.result().toCompletableFuture().join().usage().tokenPrecision());
            check(f.backend.bodies.get(1).contains("private-b")
                    && !f.backend.bodies.get(1).contains("private-a"), "private prompt isolation");
            f.backend.respond(1, CANDIDATE, 11L, 7L);
            equal(b.bound().context(), second.result().toCompletableFuture().join().owner());
            equal(Generation.Precision.MEASURED, second.result().toCompletableFuture().join().usage().tokenPrecision());
            equal(7L, second.result().toCompletableFuture().join().usage().outputTokens());
            equal(1L, ledger.snapshot().get(Kind.REPAIRS));
        }
        try (var f = new Fixture()) {
            var limits = limits(f.clock, 500); var ledger = ledger(f.clock, limits);
            var handle = f.generate(Generation.Role.INITIAL, "", ledger, limits);
            f.backend.sinks.get(0).complete(503);
            equal(Generation.Outcome.MODEL_UNAVAILABLE, handle.result().toCompletableFuture().join().outcome());
            equal(1L, ledger.snapshot().get(Kind.CALLS));
        }
    }
    public static void threadingAndOverload() {
        var queue = new ArrayDeque<Runnable>();
        try (var f = new Fixture(queue::add)) {
            var limits = limits(f.clock, 500); var ledger = ledger(f.clock, limits);
            var a = f.generate(Generation.Role.INITIAL, "", ledger, limits);
            var b = f.generate(Generation.Role.INITIAL, "", ledger, limits);
            var c = f.generate(Generation.Role.INITIAL, "", ledger, limits);
            equal(Generation.Outcome.LIMIT, c.result().toCompletableFuture().join().outcome());
            equal(1, queue.size()); equal(0, f.backend.sinks.size());
            equal(Generation.State.STARTING, f.adapter.status().state());
            queue.remove().run(); f.backend.respond(0, CANDIDATE, null, null);
            queue.remove().run(); f.backend.respond(1, CANDIDATE, null, null);
            equal(Generation.Outcome.CANDIDATE, a.result().toCompletableFuture().join().outcome());
            equal(Generation.Outcome.CANDIDATE, b.result().toCompletableFuture().join().outcome());
        }
    }
    public static void invalidAndZeroLimits() {
        try (var f = new Fixture()) {
            var zero = new Budgets.InferenceLimits(new Budgets.Limits(Map.of(Kind.CALLS, 0L,
                    Kind.REPAIRS, 0L, Kind.INPUT_BYTES, 1L, Kind.OUTPUT_BYTES, 1L),
                    f.clock.now + 10_000), 1, 1);
            equal(Generation.Outcome.LIMIT, f.generate(Generation.Role.INITIAL,
                    "", ledger(f.clock, zero), zero).result().toCompletableFuture().join().outcome());
            equal(0, f.backend.sinks.size());
            try { request(Generation.Role.INITIAL, "x".repeat(8_193));
                throw new AssertionError("context byte limit"); }
            catch (IllegalArgumentException expected) { }
            try { new LocalGenerationAdapter.Config(URI.create("http://127.0.0.1:11434/api/chat"),
                    "model:cloud", true, 256);
                throw new AssertionError("cloud tag accepted"); }
            catch (IllegalArgumentException expected) { }
        }
    }
    public static void localHttpTransport() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var bodies = new ArrayList<String>();
        server.createContext("/api/tags", exchange -> {
            byte[] listing = StrictJson.canonical(Map.of("models", List.of(Map.of(
                    "name", "installed:test", "digest", "a".repeat(64)))))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, listing.length);
            exchange.getResponseBody().write(listing); exchange.close();
        });
        server.createContext("/api/chat", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] answer = StrictJson.canonical(Map.of("model", "installed:test", "done", true,
                    "message", Map.of("role", "assistant", "content", CANDIDATE),
                    "prompt_eval_count", 4L, "eval_count", 5L)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer); exchange.close();
        });
        server.start();
        try (var adapter = new LocalGenerationAdapter(new LocalGenerationAdapter.Config(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/chat"),
                "installed:test", true, 256))) {
            var time = new Time(); time.now = System.currentTimeMillis();
            var limits = limits(time, 500);
            var result = adapter.generate(request(Generation.Role.INITIAL, "smoke"), limits,
                    ledger(time, limits)).result().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            equal(Generation.Outcome.CANDIDATE, result.outcome());
            equal(5L, result.usage().outputTokens());
            equal("a".repeat(64), result.descriptor().digest());
            check(bodies.get(0).contains("smoke"), "HTTP request sent");
        } finally { server.stop(0); }
    }
    public static void missingInstalledTag() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var chatCalls = new int[1];
        server.createContext("/api/tags", exchange -> {
            byte[] listing = "{\"models\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, listing.length);
            exchange.getResponseBody().write(listing); exchange.close();
        });
        server.createContext("/api/chat", exchange -> { chatCalls[0]++;
            exchange.sendResponseHeaders(500, -1); exchange.close();
        });
        server.start();
        try (var adapter = new LocalGenerationAdapter(new LocalGenerationAdapter.Config(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/chat"),
                "installed:test", true, 256))) {
            var time = new Time(); time.now = System.currentTimeMillis();
            var limits = limits(time, 500);
            var result = adapter.generate(request(Generation.Role.INITIAL, ""), limits,
                    ledger(time, limits)).result().toCompletableFuture().get(5,
                            java.util.concurrent.TimeUnit.SECONDS);
            equal(Generation.Outcome.MODEL_UNAVAILABLE, result.outcome());
            equal(Generation.State.UNAVAILABLE, adapter.status().state());
            equal(0, chatCalls[0]);
        } finally { server.stop(0); }
    }
    public static void main(String[] args) throws Exception {
        structuredRequest(); generationRepair(); transportBounds(); deadline(); cancellation();
        availabilityAndLifecycle(); duplicateCallbacksAndShutdown(); isolationAndUsage();
        threadingAndOverload(); invalidAndZeroLimits(); localHttpTransport(); missingInstalledTag();
        System.out.println("IMP-005 fake and local HTTP checks passed");
    }
}
